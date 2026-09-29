package com.reasoning.common.story.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryService.OwnerScope;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 소유권 인계의 24시간 요청, 현재 자격, 회수 후 재전송과 원자 감사를 관리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryOwnershipService {
    private final StoryService stories;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public StoryOwnershipService(StoryService stories, JdbcTemplate db, ObjectMapper json) {
        this.stories = stories;
        this.db = db;
        this.json = json;
    }

    /**
     * 현재 소유자·지정 수신자·MANAGE에게만 현재 사건과 유효성 투영을 노출한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 최근 재인증한 관리자
     * @param code 정확한 사건 코드
     * @param id 현재 읽기 요청 ID
     * @return 제목·본문 없는 현재 소유권과 저장된 요청 상태
     */
    public OwnershipView getOwnership(String sid, AdminActor actor, String code, UUID id) {
        required(id);
        return stories.withOwnership(sid, actor, code, null, scope -> {
            Transfer pending = pending(scope.storyId());
            boolean manager = manage(actor.accountId());
            if (actor.accountId() != scope.ownerId() && !manager
                    && (pending == null || pending.toId() != actor.accountId())) throw missing();
            PendingView view = pending == null ? null : new PendingView(pending.key(),
                    key(pending.fromId()), key(pending.toId()), pending.keepEditor(), effective(pending, scope),
                    pending.createdAt(), pending.expiresAt(), Long.toString(pending.storyRev()));
            return new OwnershipView(code, Long.toString(scope.rev()), scope.active(), scope.ownerKey(), view, id);
        });
    }

    /**
     * 활성 사건의 소유자가 현재 활성 EDIT 관리자에게 24시간 수락 요청을 발행한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 요청 시점의 소유자
     * @param code 정확한 사건 코드
     * @param expectedStoryRev 무변경에도 필요한 현재 사건 수정번호
     * @param recipient 대상 관리자 공개 UUID
     * @param keepEditor 수락 뒤 이전 소유자의 EDIT 유지 선택
     * @param reason HANDOVER만 허용
     * @param reference 비개인 확인 참조 8~64자
     * @param requestKey 재전송에 그대로 사용할 UUID v4
     * @param id 현재 요청 ID
     * @return 저장된 최초 확정 결과 또는 동일 의도의 안전 재생
     */
    public OwnerAction requestTransfer(String sid, AdminActor actor, String code, String expectedStoryRev,
            UUID recipient, boolean keepEditor, String reason, String reference, UUID requestKey, UUID id) {
        validate(requestKey, id, expectedStoryRev);
        reference(reference);
        if (recipient == null || !"HANDOVER".equals(reason)) throw AuthException.badRequest("INVALID_REQUEST");
        Map<String, Object> input = input(expectedStoryRev, "toAccountKey", recipient.toString(),
                "keepEditor", keepEditor, "reasonCode", reason, "verificationRef", reference);
        return stories.withOwnership(sid, actor, code, recipient, scope -> {
            OwnerAction replay = replay(scope, actor, "OWNER_REQ", input, requestKey, id);
            if (replay != null) return replay;
            if (scope.ownerId() != actor.accountId()) denyAction(scope, actor);
            checkRev(scope, expectedStoryRev);
            if (!scope.active()) throw AuthException.conflict("STATE_CONFLICT");
            if (scope.recipientId() == null || scope.recipientId() == scope.ownerId())
                throw AuthException.conflict("ACCOUNT_NOT_READY");
            if (!ready(scope.recipientId()).ready() || !hasEdit(scope.storyId(), scope.recipientId()))
                throw AuthException.conflict("ACCOUNT_NOT_READY");
            Transfer old = pending(scope.storyId());
            if (old != null) {
                if ("PENDING".equals(effective(old, scope))) throw AuthException.conflict("TRANSFER_PENDING");
                maintain(old, scope);
            }
            checkOverflow(scope.rev());
            Instant now = now();
            UUID key = UUID.randomUUID();
            long after = scope.rev() + 1;
            db.update("INSERT INTO story_transfer(transfer_key,story_id,from_id,to_id,actor_id,mode,state,"
                            + "keep_editor,story_rev,from_auth_rev,to_auth_rev,reason_code,verification_ref,"
                            + "created_at,expires_at) VALUES (?,?,?,?,?,'NORMAL','PENDING',?,?,?,?,?,?,?,?)",
                    key, scope.storyId(), scope.ownerId(), scope.recipientId(), actor.accountId(), keepEditor,
                    after, ready(scope.ownerId()).authRev(), ready(scope.recipientId()).authRev(), reason,
                    reference, now, now.plus(24, ChronoUnit.HOURS));
            db.update("UPDATE story SET edit_rev=edit_rev+1,updated_at=clock_timestamp() WHERE id=?", scope.storyId());
            OwnerOriginal original = new OwnerOriginal(key, code, scope.ownerKey(), recipient, keepEditor,
                    "PENDING", reason, reference, now, now.plus(24, ChronoUnit.HOURS), null, Long.toString(after));
            long receipt = receipt(scope, actor, requestKey, "OWNER_REQ", input, original);
            audit(scope, actor, "OWNER_REQUESTED", scope.rev(), after, key, receipt, id,
                    scope.ownerKey(), recipient, keepEditor, reason, reference, "PENDING");
            return new OwnerAction("OWNER_REQ", false, original, id);
        });
    }

    /**
     * 지정 수신자가 살아 있는 요청을 수락해 소유자와 EDIT 관계를 함께 교체한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 지정 수신자
     * @param code 사건 코드
     * @param transferKey 서버 발급 인계 UUID
     * @param expectedStoryRev 현재 사건 수정번호
     * @param requestKey 새 수락 의도 UUID v4
     * @param id 요청 ID
     * @return 당시 확정 결과 또는 같은 의도의 재생
     */
    public OwnerAction acceptTransfer(String sid, AdminActor actor, String code, UUID transferKey,
            String expectedStoryRev, UUID requestKey, UUID id) {
        validate(requestKey, id, expectedStoryRev);
        required(transferKey);
        Map<String, Object> input = input(expectedStoryRev, "transferKey", transferKey.toString());
        UUID target = recipient(code, transferKey);
        return stories.withOwnership(sid, actor, code, target, scope -> {
            OwnerAction replay = replay(scope, actor, "OWNER_ACCEPT", input, requestKey, id);
            if (replay != null) return replay;
            Transfer transfer = transfer(scope.storyId(), transferKey);
            if (transfer == null) throw missing();
            if (actor.accountId() != transfer.toId()) denyAction(scope, actor);
            validatePending(transfer, scope);
            checkRev(scope, expectedStoryRev);
            checkOverflow(scope.rev());
            Instant now = now();
            db.update("UPDATE story_access SET active_yn=false,updated_at=? WHERE story_id=? AND admin_id=? "
                    + "AND permission='EDIT' AND active_yn", now, scope.storyId(), transfer.toId());
            if (transfer.keepEditor()) db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) "
                            + "VALUES (?,?,'EDIT',?) ON CONFLICT (story_id,admin_id,permission) DO UPDATE "
                            + "SET active_yn=true,granted_by=EXCLUDED.granted_by,updated_at=clock_timestamp()",
                    scope.storyId(), transfer.fromId(), actor.accountId());
            else db.update("UPDATE story_access SET active_yn=false,updated_at=? WHERE story_id=? AND admin_id=? "
                    + "AND permission='EDIT' AND active_yn", now, scope.storyId(), transfer.fromId());
            db.update("UPDATE story SET owner_id=?,edit_rev=edit_rev+1,updated_at=? WHERE id=?",
                    transfer.toId(), now, scope.storyId());
            db.update("UPDATE story_transfer SET state='ACCEPTED',closed_at=?,closed_by=? WHERE id=?",
                    now, actor.accountId(), transfer.id());
            OwnerOriginal original = original(transfer, code, "ACCEPTED", now, scope.rev() + 1);
            long receipt = receipt(scope, actor, requestKey, "OWNER_ACCEPT", input, original);
            audit(scope, actor, "OWNER_ACCEPTED", scope.rev(), scope.rev() + 1, transferKey, receipt, id,
                    scope.ownerKey(), key(transfer.toId()), transfer.keepEditor(), transfer.reason(), transfer.reference(), "ACCEPTED");
            return new OwnerAction("OWNER_ACCEPT", false, original, id);
        });
    }

    /**
     * 현재 소유자는 취소, 지정 수신자는 거절로 살아 있는 요청을 종료한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 수신자
     * @param code 사건 코드
     * @param transferKey 서버 인계 UUID
     * @param expectedStoryRev 현재 사건 수정번호
     * @param decision CANCEL 또는 DECLINE
     * @param requestKey 새 종료 의도 UUID v4
     * @param id 요청 ID
     * @return 현재 응답 또는 원래 확정 결과 재생
     */
    public OwnerAction closeTransfer(String sid, AdminActor actor, String code, UUID transferKey,
            String expectedStoryRev, String decision, UUID requestKey, UUID id) {
        validate(requestKey, id, expectedStoryRev);
        required(transferKey);
        if (!"CANCEL".equals(decision) && !"DECLINE".equals(decision)) throw AuthException.badRequest("INVALID_REQUEST");
        Map<String, Object> input = input(expectedStoryRev, "transferKey", transferKey.toString(), "decision", decision);
        UUID target = recipient(code, transferKey);
        return stories.withOwnership(sid, actor, code, target, scope -> {
            OwnerAction replay = replay(scope, actor, "OWNER_CLOSE", input, requestKey, id);
            if (replay != null) return replay;
            Transfer transfer = transfer(scope.storyId(), transferKey);
            if (transfer == null) throw missing();
            if ("CANCEL".equals(decision) && actor.accountId() != scope.ownerId()
                    || "DECLINE".equals(decision) && actor.accountId() != transfer.toId()) denyAction(scope, actor);
            validatePending(transfer, scope);
            checkRev(scope, expectedStoryRev);
            checkOverflow(scope.rev());
            Instant now = now();
            String state = "CANCEL".equals(decision) ? "CANCELLED" : "DECLINED";
            db.update("UPDATE story_transfer SET state=?,closed_at=?,closed_by=? WHERE id=?",
                    state, now, actor.accountId(), transfer.id());
            db.update("UPDATE story SET edit_rev=edit_rev+1,updated_at=? WHERE id=?", now, scope.storyId());
            OwnerOriginal original = original(transfer, code, state, now, scope.rev() + 1);
            long receipt = receipt(scope, actor, requestKey, "OWNER_CLOSE", input, original);
            audit(scope, actor, "CANCEL".equals(decision) ? "OWNER_CANCELLED" : "OWNER_DECLINED",
                    scope.rev(), scope.rev() + 1, transferKey, receipt, id, scope.ownerKey(), key(transfer.toId()),
                    transfer.keepEditor(), transfer.reason(), transfer.reference(), state);
            return new OwnerAction("OWNER_CLOSE", false, original, id);
        });
    }

    /**
     * MANAGE가 실제 비활성/복구 제한 소유자에게서 준비된 다른 관리자에게 소유권을 복구한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 MANAGE 관리자
     * @param code 사건 코드
     * @param expectedStoryRev 현재 사건 수정번호
     * @param recipient 새 소유자 계정 UUID
     * @param keepEditor 이전 소유자의 EDIT 유지 선택; 현재 비정상이면 false여야 한다
     * @param reason OWNER_DISABLED 또는 OWNER_RECOVERY
     * @param reference 비개인 확인 참조
     * @param requestKey 새 예외 인계 UUID v4
     * @param id 요청 ID
     * @return 당시 확정 결과 또는 동일 성공 영수증
     */
    public OwnerAction overrideTransfer(String sid, AdminActor actor, String code, String expectedStoryRev,
            UUID recipient, boolean keepEditor, String reason, String reference, UUID requestKey, UUID id) {
        validate(requestKey, id, expectedStoryRev);
        reference(reference);
        if (recipient == null || !"OWNER_DISABLED".equals(reason) && !"OWNER_RECOVERY".equals(reason))
            throw AuthException.badRequest("INVALID_REQUEST");
        Map<String, Object> input = input(expectedStoryRev, "toAccountKey", recipient.toString(),
                "keepEditor", keepEditor, "reasonCode", reason, "verificationRef", reference);
        return stories.withOwnership(sid, actor, code, recipient, scope -> {
            OwnerAction replay = replay(scope, actor, "OWNER_FORCE", input, requestKey, id);
            if (replay != null) return replay;
            if (!manage(actor.accountId())) denyAction(scope, actor);
            checkRev(scope, expectedStoryRev);
            if (scope.recipientId() == null || scope.recipientId() == scope.ownerId()
                    || !ready(scope.recipientId()).ready()) throw AuthException.conflict("ACCOUNT_NOT_READY");
            AccountState former = ready(scope.ownerId());
            if ("OWNER_DISABLED".equals(reason) != !former.active()
                    || "OWNER_RECOVERY".equals(reason) && (!former.active() || former.ready()))
                throw AuthException.conflict("STATE_CONFLICT");
            if (keepEditor && !former.ready()) throw AuthException.conflict("ACCOUNT_NOT_READY");
            Transfer old = pending(scope.storyId());
            if (old != null) maintainForced(old, scope);
            checkOverflow(scope.rev());
            Instant now = now();
            UUID key = UUID.randomUUID();
            long after = scope.rev() + 1;
            db.update("UPDATE story_access SET active_yn=false,updated_at=? WHERE story_id=? AND admin_id=? "
                    + "AND permission='EDIT' AND active_yn", now, scope.storyId(), scope.recipientId());
            if (keepEditor) db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) "
                            + "VALUES (?,?,'EDIT',?) ON CONFLICT (story_id,admin_id,permission) DO UPDATE "
                            + "SET active_yn=true,granted_by=EXCLUDED.granted_by,updated_at=clock_timestamp()",
                    scope.storyId(), scope.ownerId(), actor.accountId());
            else db.update("UPDATE story_access SET active_yn=false,updated_at=? WHERE story_id=? AND admin_id=? "
                    + "AND permission='EDIT' AND active_yn", now, scope.storyId(), scope.ownerId());
            db.update("UPDATE story SET owner_id=?,edit_rev=edit_rev+1,updated_at=? WHERE id=?",
                    scope.recipientId(), now, scope.storyId());
            db.update("INSERT INTO story_transfer(transfer_key,story_id,from_id,to_id,actor_id,mode,state,"
                            + "keep_editor,story_rev,from_auth_rev,to_auth_rev,reason_code,verification_ref,created_at,"
                            + "closed_at,closed_by) VALUES (?,?,?,?,?,'OVERRIDE','OVERRIDDEN',?,?,?,?,?,?,?,?,?)",
                    key, scope.storyId(), scope.ownerId(), scope.recipientId(), actor.accountId(), keepEditor,
                    after, former.authRev(), ready(scope.recipientId()).authRev(), reason, reference,
                    now, now, actor.accountId());
            OwnerOriginal original = new OwnerOriginal(key, code, scope.ownerKey(), recipient, keepEditor,
                    "OVERRIDDEN", reason, reference, now, null, now, Long.toString(after));
            long receipt = receipt(scope, actor, requestKey, "OWNER_FORCE", input, original);
            audit(scope, actor, "OWNER_OVERRIDDEN", scope.rev(), after, key, receipt, id,
                    scope.ownerKey(), recipient, keepEditor, reason, reference, "OVERRIDDEN");
            return new OwnerAction("OWNER_FORCE", false, original, id);
        });
    }

    private void validatePending(Transfer transfer, OwnerScope scope) {
        if (!"PENDING".equals(transfer.state())) throw AuthException.conflict("TRANSFER_CLOSED");
        String state = effective(transfer, scope);
        if ("EXPIRED".equals(state)) throw AuthException.conflict("TRANSFER_EXPIRED");
        if (!"PENDING".equals(state)) throw AuthException.conflict("TRANSFER_INVALIDATED");
    }

    private String effective(Transfer transfer, OwnerScope scope) {
        if (!"PENDING".equals(transfer.state())) return transfer.state();
        if (!now().isBefore(transfer.expiresAt())) return "EXPIRED";
        if (scope.ownerId() != transfer.fromId() || scope.rev() != transfer.storyRev()) return "INVALIDATED";
        AccountState from = ready(transfer.fromId());
        AccountState to = ready(transfer.toId());
        if (!from.ready() || !to.ready() || from.authRev() != transfer.fromAuthRev()
                || to.authRev() != transfer.toAuthRev() || !hasEdit(scope.storyId(), transfer.toId()))
            return "INVALIDATED";
        return "PENDING";
    }

    private void maintain(Transfer old, OwnerScope scope) {
        String state = effective(old, scope);
        if ("PENDING".equals(state)) throw AuthException.conflict("TRANSFER_PENDING");
        closeSystem(old, scope, state);
    }

    private void maintainForced(Transfer old, OwnerScope scope) {
        closeSystem(old, scope, "INVALIDATED");
    }

    private void closeSystem(Transfer old, OwnerScope scope, String state) {
        Instant at = now();
        db.update("UPDATE story_transfer SET state=?,closed_at=?,closed_by=NULL WHERE id=?",
                state, at, old.id());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("actorKind", "SYSTEM");
        detail.put("revisionScope", "STORY");
        detail.put("transferKey", old.key());
        detail.put("before", Map.of("state", "PENDING"));
        detail.put("after", Map.of("state", state));
        stories.auditOwnership(scope, null, "EXPIRED".equals(state) ? "OWNER_EXPIRED" : "OWNER_INVALIDATED",
                scope.rev(), scope.rev(), detail);
    }

    private OwnerAction replay(OwnerScope scope, AdminActor actor, String action, Map<String, Object> input,
            UUID requestKey, UUID id) {
        var rows = db.query("SELECT actor_id,action,request_data::text,result_data::text FROM story_action "
                        + "WHERE story_id=? AND request_key=? FOR UPDATE",
                (rs, index) -> new Receipt(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                scope.storyId(), requestKey);
        if (rows.isEmpty()) return null;
        Receipt row = rows.getFirst();
        try {
            if (row.actorId() != actor.accountId() || !row.action().equals(action)
                    || !json.readTree(row.requestData()).equals(json.valueToTree(input)))
                throw AuthException.conflict("REQUEST_KEY_CONFLICT");
            return new OwnerAction(action, true, json.readValue(row.resultData(), OwnerOriginal.class), id);
        } catch (JsonProcessingException failure) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    private long receipt(OwnerScope scope, AdminActor actor, UUID requestKey, String action,
            Map<String, Object> input, OwnerOriginal original) {
        return db.queryForObject("INSERT INTO story_action(story_id,request_key,action,actor_id,request_data,result_data) "
                        + "VALUES (?,?,?,?,?::jsonb,?::jsonb) RETURNING id", Long.class,
                scope.storyId(), requestKey, action, actor.accountId(), serialize(input), serialize(original));
    }

    private void audit(OwnerScope scope, AdminActor actor, String action, long before, long after,
            UUID transferKey, long actionId, UUID requestId, UUID from, UUID to, boolean keepEditor,
            String reason, String ref, String state) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("revisionScope", "STORY");
        detail.put("transferKey", transferKey);
        detail.put("actionId", actionId);
        detail.put("fromAccountKey", from);
        detail.put("toAccountKey", to);
        detail.put("keepEditor", keepEditor);
        detail.put("reasonCode", reason);
        detail.put("verificationRef", ref);
        detail.put("after", Map.of("state", state));
        stories.auditOwnership(scope, actor, action, before, after, detail);
    }

    private Transfer pending(long storyId) {
        return db.query("SELECT id,transfer_key,from_id,to_id,keep_editor,state,story_rev,from_auth_rev,to_auth_rev,"
                        + "reason_code,verification_ref,created_at,expires_at,closed_at FROM story_transfer "
                        + "WHERE story_id=? AND state='PENDING' FOR UPDATE",
                rs -> rs.next() ? mapTransfer(rs) : null, storyId);
    }

    private Transfer transfer(long storyId, UUID key) {
        return db.query("SELECT id,transfer_key,from_id,to_id,keep_editor,state,story_rev,from_auth_rev,to_auth_rev,"
                        + "reason_code,verification_ref,created_at,expires_at,closed_at FROM story_transfer "
                        + "WHERE story_id=? AND transfer_key=? FOR UPDATE",
                rs -> rs.next() ? mapTransfer(rs) : null, storyId, key);
    }

    private static Transfer mapTransfer(ResultSet rs) throws SQLException {
        return new Transfer(rs.getLong(1), (UUID) rs.getObject(2), rs.getLong(3), rs.getLong(4),
                rs.getBoolean(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant(),
                rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(),
                rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toInstant());
    }

    private UUID recipient(String code, UUID transferKey) {
        return db.query("SELECT a.account_key FROM story_transfer t JOIN story s ON s.id=t.story_id "
                        + "JOIN admin_account a ON a.id=t.to_id WHERE s.code=? AND t.transfer_key=?",
                rs -> rs.next() ? (UUID) rs.getObject(1) : null, code, transferKey);
    }

    private AccountState ready(long id) {
        return db.query("SELECT a.active_yn,c.enrolled_at IS NOT NULL,c.mfa_state,c.auth_rev FROM admin_account a "
                        + "LEFT JOIN admin_credential c ON c.account_id=a.id WHERE a.id=?",
                rs -> rs.next() ? new AccountState(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3),
                        rs.getLong(4)) : null, id);
    }

    private boolean hasEdit(long story, long admin) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM story_access WHERE "
                + "story_id=? AND admin_id=? AND permission='EDIT' AND active_yn)", Boolean.class, story, admin));
    }

    private boolean manage(long id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT can_manage FROM admin_account WHERE id=?", Boolean.class, id));
    }

    private UUID key(long id) {
        return db.queryForObject("SELECT account_key FROM admin_account WHERE id=?", UUID.class, id);
    }

    private Instant now() {
        return db.queryForObject("SELECT clock_timestamp()", (rs, index) -> rs.getTimestamp(1).toInstant());
    }

    private String serialize(Object value) {
        try {
            String data = json.writeValueAsString(value);
            if (data.getBytes(StandardCharsets.UTF_8).length > 32768) throw AuthException.badRequest("INVALID_REQUEST");
            return data;
        } catch (JsonProcessingException failure) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    private void checkRev(OwnerScope scope, String raw) {
        if (!Long.toString(scope.rev()).equals(raw)) throw AuthException.conflict("EDIT_CONFLICT");
    }

    private static void checkOverflow(long rev) {
        if (rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
    }

    private static void required(UUID id) {
        if (id == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static void validate(UUID requestKey, UUID id, String expected) {
        required(id);
        if (requestKey == null || requestKey.version() != 4 || expected == null
                || !expected.matches("0|[1-9][0-9]*")) throw AuthException.badRequest("INVALID_REQUEST");
        try { Long.parseLong(expected); } catch (NumberFormatException failure) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    private static void reference(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static Map<String, Object> input(String expected, Object... parts) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("formatNo", 1);
        value.put("expectedStoryRev", expected);
        for (int index = 0; index < parts.length; index += 2) value.put((String) parts[index], parts[index + 1]);
        return value;
    }

    private OwnerOriginal original(Transfer transfer, String code, String state, Instant closed, long rev) {
        return new OwnerOriginal(transfer.key(), code, key(transfer.fromId()), key(transfer.toId()),
                transfer.keepEditor(), state, transfer.reason(), transfer.reference(), transfer.createdAt(),
                transfer.expiresAt(), closed, Long.toString(rev));
    }

    private void denyAction(OwnerScope scope, AdminActor actor) {
        if (actor.accountId() == scope.ownerId()
                || scope.recipientId() != null && actor.accountId() == scope.recipientId()
                || scope.pendingRecipientId() != null && actor.accountId() == scope.pendingRecipientId()
                || manage(actor.accountId()))
            throw AuthException.forbidden("FORBIDDEN");
        throw missing();
    }

    private static AuthException missing() {
        return new AuthException(404, "NOT_FOUND", "NOT_FOUND");
    }

    private record AccountState(boolean active, boolean enrolled, String mfa, long authRev) {
        boolean ready() { return active && enrolled && "READY".equals(mfa); }
    }
    private record Transfer(long id, UUID key, long fromId, long toId, boolean keepEditor, String state,
            long storyRev, long fromAuthRev, long toAuthRev, String reason, String reference,
            Instant createdAt, Instant expiresAt, Instant closedAt) {}
    private record Receipt(long actorId, String action, String requestData, String resultData) {}
    public record PendingView(UUID transferKey, UUID fromAccountKey, UUID toAccountKey, boolean keepEditor,
            String effectiveState, Instant createdAt, Instant expiresAt, String storyRev) {}
    public record OwnershipView(String storyCode, String storyRev, boolean activeYn, UUID ownerAccountKey,
            PendingView pending, UUID requestId) {}
    public record OwnerOriginal(UUID transferKey, String storyCode, UUID fromAccountKey, UUID toAccountKey,
            boolean keepEditor, String state, String reasonCode, String verificationRef,
            Instant createdAt, Instant expiresAt, Instant closedAt, String storyRev) {}
    public record OwnerAction(String action, boolean replayed, OwnerOriginal original, UUID requestId) {}
}
