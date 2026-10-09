package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.story.model.StoryReviewPreview;

import org.postgresql.util.PSQLException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 수락된 두 회원의 준비·시작·접속·자료 노출·단계별 힌트만 처리한다. 보고서와 결과는 다루지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PlaytestInvestigationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PlaytestAccess access;
    private final JdbcTemplate db;
    private final CryptoService crypto;

    public record Heartbeat(
            String state, Instant serverTime, Instant playDeadline, UUID requestId) {}

    private record Result<T>(T value, AuthException denial) {
        static <T> Result<T> allow(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> deny(AuthException error) {
            return new Result<>(null, error);
        }

        T unwrap() {
            if (denial != null) throw denial;
            return value;
        }
    }

    public PlaytestInvestigationService(
            PlaytestAccess access, JdbcTemplate db, CryptoService crypto) {
        this.access = access;
        this.db = db;
        this.crypto = crypto;
    }

    /**
     * 수락한 회원의 준비 설정·철회를 수정번호와 전역 의도 키에 결속하여 확정한다.
     *
     * @param token 현재 LOCAL 접근 토큰
     * @param testKey 테스트 UUID v4
     * @param revision 현재 수정번호(음수 불가)
     * @param ready true이면 준비, false이면 자신의 준비 철회
     * @param requestKey 전역 UUID v4 재전송 키
     * @param requestId 서버 감사 UUID v4
     * @return 원래 여섯 안전 필드와 현재 상태
     */
    public PlaytestInvitationService.ActionResult ready(
            String token,
            UUID testKey,
            long revision,
            boolean ready,
            UUID requestKey,
            UUID requestId) {
        keys(requestKey, requestId);
        if (revision < 0 || revision == Long.MAX_VALUE) throw invalid();
        String hash = digest(testKey + ":" + revision + ":" + ready);
        return command(
                token,
                testKey,
                "TEST_READY",
                hash,
                requestKey,
                requestId,
                revision,
                (c, now) -> {
                    if (c.self().ready() == ready) throw AuthException.conflict("STATE_CONFLICT");
                    if (db.update(
                                    "UPDATE test_member SET ready_yn=?,updated_at=clock_timestamp()"
                                            + " WHERE test_id=? AND member_id=? AND ready_yn=?",
                                    ready,
                                    c.id(),
                                    c.self().id(),
                                    !ready)
                            != 1) throw unavailable();
                    return ready ? "READY" : "WITHDRAWN";
                });
    }

    /**
     * 쌍방 준비와 최근 접속이 확인된 대기를 한 번만 시작하고 역할을 보안 난수로 배정한다.
     *
     * @param token 현재 LOCAL 접근 토큰
     * @param testKey 테스트 UUID v4
     * @param revision 현재 수정번호
     * @param requestKey 전역 UUID v4 의도 키
     * @param requestId 서버 감사 UUID v4
     * @return 최초 시작 결과 또는 동일 의도의 불변 재전송
     */
    public PlaytestInvitationService.ActionResult start(
            String token, UUID testKey, long revision, UUID requestKey, UUID requestId) {
        keys(requestKey, requestId);
        if (revision < 0 || revision == Long.MAX_VALUE) throw invalid();
        return command(
                token,
                testKey,
                "TEST_START",
                digest(testKey + ":" + revision),
                requestKey,
                requestId,
                revision,
                (c, now) -> {
                    if (!c.self().ready()
                            || !c.partner().ready()
                            || !recent(c.self().seen(), now)
                            || !recent(c.partner().seen(), now))
                        throw AuthException.conflict("PARTNER_NOT_READY");
                    boolean flip = RANDOM.nextBoolean();
                    String self = flip == (c.self().slot() == 1) ? c.roleA() : c.roleB();
                    String partner = self.equals(c.roleA()) ? c.roleB() : c.roleA();
                    if (self.equals(partner)
                            || !java.util.Set.of(c.roleA(), c.roleB())
                                    .equals(java.util.Set.of(self, partner))) throw unavailable();
                    if (db.update(
                                    "UPDATE test_member SET"
                                            + " role_code=?,updated_at=clock_timestamp() WHERE"
                                            + " test_id=? AND member_id=? AND role_code IS NULL",
                                    self,
                                    c.id(),
                                    c.self().id())
                            != 1) throw unavailable();
                    if (db.update(
                                    "UPDATE test_member SET"
                                            + " role_code=?,updated_at=clock_timestamp() WHERE"
                                            + " test_id=? AND member_id=? AND role_code IS NULL",
                                    partner,
                                    c.id(),
                                    c.partner().id())
                            != 1) throw unavailable();
                    int limit =
                            c.payload().path("sections").path("basic").path("limitSec").intValue();
                    if (limit <= 0) throw AuthException.conflict("INVITATION_INVALIDATED");
                    if (db.update(
                                    "UPDATE play_test SET"
                                        + " state='RUNNING',started_at=?,deadline_at=?::timestamptz,rev=rev+1,updated_at=clock_timestamp()"
                                        + " WHERE id=? AND state='WAITING' AND rev=?",
                                    Timestamp.from(now),
                                    Timestamp.from(now.plusSeconds(limit)),
                                    c.id(),
                                    c.revision())
                            != 1) throw unavailable();
                    return "STARTED";
                });
    }

    @FunctionalInterface
    private interface Change {
        String apply(PlaytestAccess.Context context, Instant now);
    }

    private PlaytestInvitationService.ActionResult command(
            String token,
            UUID key,
            String action,
            String hash,
            UUID requestKey,
            UUID requestId,
            long revision,
            Change change) {
        try {
            return access.execute(
                            token,
                            key,
                            c -> {
                                Instant now = c.clock(db);
                                var existing = receipt(requestKey);
                                if (existing != null) {
                                    check(existing, c, action, hash);
                                    normalize(c, requestId);
                                    return Result.allow(
                                            new PlaytestInvitationService.ActionResult(
                                                    action,
                                                    true,
                                                    false,
                                                    existing.original(),
                                                    current(c),
                                                    requestId));
                                }
                                AuthException expired = normalize(c, requestId);
                                if (expired != null)
                                    return Result.<PlaytestInvitationService.ActionResult>deny(
                                            expired);
                                if (!"WAITING".equals(c.state())
                                        || !now.isBefore(c.inviteUntil())
                                        || c.readyUntil() != null && !now.isBefore(c.readyUntil()))
                                    throw AuthException.conflict("STATE_CONFLICT");
                                if (revision != c.revision())
                                    throw AuthException.conflict("EDIT_CONFLICT");
                                String outcome = change.apply(c, now);
                                if ("TEST_READY".equals(action)
                                        && db.update(
                                                        "UPDATE play_test SET"
                                                            + " rev=rev+1,updated_at=clock_timestamp()"
                                                            + " WHERE id=? AND rev=? AND"
                                                            + " state='WAITING'",
                                                        c.id(),
                                                        revision)
                                                != 1) throw unavailable();
                                Map<String, Object> original = current(c);
                                saveReceipt(c, action, hash, requestKey, original);
                                UUID auditKey = audit(c, action, requestId, outcome);
                                verifyCommand(
                                        c,
                                        action,
                                        revision,
                                        now,
                                        outcome,
                                        requestKey,
                                        hash,
                                        original,
                                        auditKey,
                                        requestId);
                                return Result.allow(
                                        new PlaytestInvitationService.ActionResult(
                                                action, false, true, original, original,
                                                requestId));
                            })
                    .unwrap();
        } catch (DataAccessException failure) {
            if (!collision(failure)) throw unavailable();
            return access.execute(
                    token,
                    key,
                    c -> {
                        var prior = receipt(requestKey);
                        if (prior == null) throw unavailable();
                        check(prior, c, action, hash);
                        normalize(c, requestId);
                        return new PlaytestInvitationService.ActionResult(
                                action, true, false, prior.original(), current(c), requestId);
                    });
        }
    }

    /** 준비 또는 시작 직후 부모·참가 행과 전역 영수증·감사가 같은 거래에서 확정됐는지 대조한다. */
    private void verifyCommand(
            PlaytestAccess.Context c,
            String action,
            long revision,
            Instant started,
            String outcome,
            UUID requestKey,
            String hash,
            Map<String, Object> original,
            UUID auditKey,
            UUID requestId) {
        boolean ready = "TEST_READY".equals(action);
        Boolean valid =
                db.queryForObject(
                        """
                        SELECT t.rev=? AND t.state=? AND t.outcome IS NULL
                          AND self.invite_state='ACCEPTED' AND partner.invite_state='ACCEPTED'
                          AND self.ready_yn=? AND
                          (CASE WHEN ? THEN self.role_code IS NULL AND partner.role_code IS NULL
                            AND t.started_at IS NULL AND t.deadline_at IS NULL
                           ELSE self.ready_yn AND partner.ready_yn
                            AND self.role_code IN (?,?) AND partner.role_code IN (?,?)
                            AND self.role_code<>partner.role_code AND t.started_at=? AND t.deadline_at=? END)
                        FROM play_test t JOIN test_member self ON self.test_id=t.id AND self.member_id=?
                        JOIN test_member partner ON partner.test_id=t.id AND partner.member_id=? WHERE t.id=?
                        """,
                        Boolean.class,
                        revision + 1,
                        ready ? "WAITING" : "RUNNING",
                        "READY".equals(outcome) || !ready,
                        ready,
                        c.roleA(),
                        c.roleB(),
                        c.roleA(),
                        c.roleB(),
                        ready ? null : Timestamp.from(started),
                        ready
                                ? null
                                : Timestamp.from(
                                        started.plusSeconds(
                                                c.payload()
                                                        .path("sections")
                                                        .path("basic")
                                                        .path("limitSec")
                                                        .longValue())),
                        c.self().id(),
                        c.partner().id(),
                        c.id());
        if (!Boolean.TRUE.equals(valid)) throw unavailable();
        verifyPersistence(c, action, requestKey, hash, original, auditKey, requestId, outcome);
    }

    /** 전역 키의 저장 결과와 유일한 감사 이벤트를 읽어 쓴 그대로 검증한다. */
    private void verifyPersistence(
            PlaytestAccess.Context c,
            String action,
            UUID requestKey,
            String hash,
            Map<String, Object> original,
            UUID auditKey,
            UUID requestId,
            String businessResult) {
        Receipt stored = receipt(requestKey);
        if (stored == null || !original.equals(stored.original())) throw unavailable();
        check(stored, c, action, hash);
        Boolean audited =
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM test_audit WHERE event_key=? AND actor_kind='MEMBER'
                          AND actor_ref=? AND action=? AND scope_kind='PLAYTEST' AND scope_key=?
                          AND request_id=? AND phase='RESULT' AND business_result=?)
                        """,
                        Boolean.class,
                        auditKey,
                        c.self().key().toString(),
                        action,
                        c.scope(),
                        requestId,
                        businessResult);
        if (!Boolean.TRUE.equals(audited)) throw unavailable();
    }

    /**
     * 서버 시각으로 마지막 접속만 갱신하며 도달한 플레이 기한은 거절 전에 별도 정상 거래로 종료한다.
     *
     * @param token 현재 LOCAL 접근 토큰
     * @param key 테스트 UUID v4
     * @param requestId 서버 감사 UUID v4
     * @return 기록된 현재 상태와 서버 시각
     */
    public Heartbeat heartbeat(String token, UUID key, UUID requestId) {
        uuid(requestId);
        return access.execute(
                        token,
                        key,
                        c -> {
                            Instant now = c.clock(db);
                            AuthException expired = normalize(c, requestId);
                            if (expired != null) return Result.<Heartbeat>deny(expired);
                            if ("RUNNING".equals(c.state())) {
                                Result<Heartbeat> result = running(c, requestId);
                                if (result.denial() != null) return result;
                            } else if (!"WAITING".equals(c.state())
                                    || !now.isBefore(c.inviteUntil())
                                    || c.readyUntil() != null && !now.isBefore(c.readyUntil())) {
                                throw AuthException.conflict("STATE_CONFLICT");
                            }
                            db.update(
                                    "UPDATE test_member SET"
                                            + " last_seen_at=?,updated_at=clock_timestamp() WHERE"
                                            + " test_id=? AND member_id=?",
                                    Timestamp.from(now),
                                    c.id(),
                                    c.self().id());
                            audit(c, "TEST_HEARTBEAT", requestId, "SEEN");
                            return Result.allow(
                                    new Heartbeat(c.state(), now, c.deadline(), requestId));
                        })
                .unwrap();
    }

    /**
     * 서버 고정 사본에서 역할 공개 자료를 투영하고 영구 노출·필수 감사를 먼저 확정한다.
     *
     * @param token 현재 LOCAL 접근 토큰
     * @param key 테스트 UUID v4
     * @param requestId 서버 감사 UUID v4
     * @return 해당 역할의 공개 자료만
     */
    public JsonNode materials(String token, UUID key, UUID requestId) {
        uuid(requestId);
        return access.execute(
                        token,
                        key,
                        c -> {
                            Result<JsonNode> denied = running(c, requestId);
                            if (denied.denial() != null) return denied;
                            if (c.self().role() == null)
                                throw AuthException.conflict("STATE_CONFLICT");
                            ObjectNode body;
                            try {
                                body =
                                        (ObjectNode)
                                                StoryReviewPreview.role(
                                                        c.payload().get("sections"),
                                                        c.payload().get("resources"),
                                                        c.self().role());
                            } catch (IllegalArgumentException failure) {
                                throw AuthException.conflict("INVITATION_INVALIDATED");
                            }
                            var opened = body.putArray("openedHints");
                            var levels =
                                    db.queryForList(
                                            "SELECT level FROM test_hint WHERE test_id=? AND"
                                                    + " member_id=? ORDER BY level",
                                            Integer.class,
                                            c.id(),
                                            c.self().id());
                            for (int level : levels) {
                                JsonNode hint = hint(c.payload(), level);
                                if (hint == null
                                        || !hint.path("body").isTextual()
                                        || hint.path("body").textValue().isBlank())
                                    throw AuthException.conflict("HINT_UNAVAILABLE");
                                opened.addObject()
                                        .put("level", level)
                                        .put("body", hint.get("body").textValue());
                            }
                            var notices = body.putArray("requiredNotices");
                            for (JsonNode rubric : c.payload().path("resources").path("rubrics")) {
                                if (!rubric.path("requiredYn").asBoolean(false)) continue;
                                JsonNode notice = rubric.path("ruleData").path("requiredNotice");
                                if (!notice.isTextual() || notice.textValue().isBlank())
                                    throw AuthException.conflict("INVITATION_INVALIDATED");
                                notices.addObject()
                                        .put("rubricCode", rubric.path("code").textValue())
                                        .put("text", notice.textValue());
                            }
                            body.put("requestId", requestId.toString());
                            db.update(
                                    "INSERT INTO test_exposure(story_id,member_id) VALUES (?,?) ON"
                                            + " CONFLICT DO NOTHING",
                                    c.storyId(),
                                    c.self().id());
                            audit(c, "TEST_MATERIALS", requestId, "EXPOSED");
                            return Result.allow(body);
                        })
                .unwrap();
    }

    /**
     * 개인별 첫 단계 열람만 복합 힌트 장부에 쓰고 반복 열람은 한도를 소비하지 않는다.
     *
     * @param token 현재 LOCAL 접근 토큰
     * @param key 테스트 UUID v4
     * @param level 1~3 단계
     * @param requestKey 전역 UUID v4 의도 키
     * @param requestId 서버 감사 UUID v4
     * @param revision 현재 수정번호
     * @return 본문을 포함하지 않는 여섯 안전 필드의 신규 또는 재전송 영수증
     */
    public PlaytestInvitationService.ActionResult openHint(
            String token, UUID key, int level, long revision, UUID requestKey, UUID requestId) {
        keys(requestKey, requestId);
        if (level < 1 || level > 3 || revision < 0 || revision == Long.MAX_VALUE) throw invalid();
        String hash = digest(key + ":" + level + ":" + revision);
        try {
            return hintAttempt(token, key, level, revision, requestKey, requestId, hash);
        } catch (DataAccessException failure) {
            if (!collision(failure)) throw unavailable();
            return hintAttempt(token, key, level, revision, requestKey, requestId, hash);
        }
    }

    private PlaytestInvitationService.ActionResult hintAttempt(
            String token,
            UUID key,
            int level,
            long revision,
            UUID requestKey,
            UUID requestId,
            String hash) {
        return access.execute(
                        token,
                        key,
                        c -> {
                            Instant now = c.clock(db);
                            Receipt prior = receipt(requestKey);
                            if (prior != null) {
                                check(prior, c, "HINT_OPEN", hash);
                                if ("RUNNING".equals(c.state())) running(c, requestId);
                                return Result.allow(
                                        new PlaytestInvitationService.ActionResult(
                                                "HINT_OPEN",
                                                true,
                                                false,
                                                prior.original(),
                                                current(c),
                                                requestId));
                            }
                            Result<PlaytestInvitationService.ActionResult> denied =
                                    running(c, requestId);
                            if (denied.denial() != null) return denied;
                            JsonNode selected = hint(c.payload(), level);
                            if (selected == null
                                    || !selected.path("body").isTextual()
                                    || selected.path("body").textValue().isBlank())
                                throw AuthException.conflict("HINT_UNAVAILABLE");
                            if (revision != c.revision())
                                throw AuthException.conflict("EDIT_CONFLICT");
                            int limit =
                                    c.payload().path("policy").path("hintsPerPerson").intValue();
                            Integer count =
                                    db.queryForObject(
                                            "SELECT count(DISTINCT level) FROM test_hint WHERE"
                                                    + " test_id=? AND member_id=?",
                                            Integer.class,
                                            c.id(),
                                            c.self().id());
                            Boolean opened =
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM test_hint WHERE test_id=?"
                                                    + " AND member_id=? AND level=?)",
                                            Boolean.class,
                                            c.id(),
                                            c.self().id(),
                                            level);
                            if (!Boolean.TRUE.equals(opened) && (count == null || count >= limit))
                                throw AuthException.conflict("HINT_LIMIT_REACHED");
                            boolean changed = !Boolean.TRUE.equals(opened);
                            if (changed) {
                                if (db.update(
                                                "INSERT INTO"
                                                    + " test_hint(test_id,member_id,level,opened_at)"
                                                    + " VALUES (?,?,?,?)",
                                                c.id(),
                                                c.self().id(),
                                                level,
                                                Timestamp.from(now))
                                        != 1) throw unavailable();
                                db.update(
                                        "INSERT INTO test_exposure(story_id,member_id) VALUES (?,?)"
                                                + " ON CONFLICT DO NOTHING",
                                        c.storyId(),
                                        c.self().id());
                                if (db.update(
                                                "UPDATE play_test SET"
                                                        + " rev=rev+1,updated_at=clock_timestamp()"
                                                        + " WHERE id=? AND rev=?",
                                                c.id(),
                                                revision)
                                        != 1) throw AuthException.conflict("EDIT_CONFLICT");
                            }
                            Map<String, Object> original = current(c);
                            saveReceipt(c, "HINT_OPEN", hash, requestKey, original);
                            UUID auditKey =
                                    audit(
                                            c,
                                            "HINT_OPEN",
                                            requestId,
                                            changed ? "OPENED" : "UNCHANGED");
                            Boolean valid =
                                    db.queryForObject(
                                            """
                                            SELECT t.rev=? AND t.state='RUNNING'
                                              AND (?=false OR h.opened_at=?)
                                              AND (SELECT count(*) FROM test_hint WHERE test_id=? AND member_id=? AND level=?)=1
                                              AND (SELECT count(*) FROM test_hint WHERE test_id=? AND member_id=?)=?
                                              AND (?=false OR EXISTS(SELECT 1 FROM test_exposure WHERE story_id=? AND member_id=?))
                                            FROM play_test t JOIN test_hint h ON h.test_id=t.id AND h.member_id=? AND h.level=?
                                            WHERE t.id=?
                                            """,
                                            Boolean.class,
                                            revision + (changed ? 1 : 0),
                                            changed,
                                            Timestamp.from(now),
                                            c.id(),
                                            c.self().id(),
                                            level,
                                            c.id(),
                                            c.self().id(),
                                            count + (changed ? 1 : 0),
                                            changed,
                                            c.storyId(),
                                            c.self().id(),
                                            c.self().id(),
                                            level,
                                            c.id());
                            if (!Boolean.TRUE.equals(valid)) throw unavailable();
                            verifyPersistence(
                                    c,
                                    "HINT_OPEN",
                                    requestKey,
                                    hash,
                                    original,
                                    auditKey,
                                    requestId,
                                    changed ? "OPENED" : "UNCHANGED");
                            return Result.allow(
                                    new PlaytestInvitationService.ActionResult(
                                            "HINT_OPEN",
                                            false,
                                            changed,
                                            original,
                                            current(c),
                                            requestId));
                        })
                .unwrap();
    }

    /** 고정된 단계에는 정확히 하나의 힌트만 허용한다. */
    private static JsonNode hint(JsonNode payload, int level) {
        JsonNode selected = null;
        for (JsonNode row : payload.path("resources").path("hints")) {
            if (row.path("level").asInt() != level) continue;
            if (selected != null) throw AuthException.conflict("HINT_UNAVAILABLE");
            selected = row;
        }
        return selected;
    }

    private <T> Result<T> running(PlaytestAccess.Context c, UUID requestId) {
        AuthException expired = normalize(c, requestId);
        if (expired != null) return Result.deny(expired);
        if (!"RUNNING".equals(c.state()) || c.deadline() == null)
            throw AuthException.conflict("STATE_CONFLICT");
        return Result.allow(null);
    }

    private AuthException normalize(PlaytestAccess.Context c, UUID requestId) {
        return PlaytestAccess.normalizeTime(
                db, crypto, c.id(), "MEMBER", c.self().key().toString(), requestId);
    }

    private record Receipt(
            Long memberId,
            String action,
            String scope,
            String hash,
            Map<String, Object> original) {}

    private Receipt receipt(UUID key) {
        var rows =
                db.queryForList(
                        "SELECT member_id,action,scope_key,request_hash,result_data->>'testKey' AS"
                                + " test_key,result_data->>'rev' AS rev,result_data->>'state' AS"
                                + " state,result_data->>'outcome' AS"
                                + " outcome,(result_data->>'startedAt')::timestamptz AS"
                                + " started_at,(result_data->>'playDeadline')::timestamptz AS"
                                + " play_deadline FROM test_action WHERE request_key=?",
                        key);
        if (rows.isEmpty()) return null;
        var r = rows.getFirst();
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("testKey", r.get("test_key"));
        original.put("rev", r.get("rev"));
        original.put("state", r.get("state"));
        original.put("outcome", r.get("outcome"));
        original.put(
                "startedAt",
                r.get("started_at") == null ? null : ((Timestamp) r.get("started_at")).toInstant());
        original.put(
                "playDeadline",
                r.get("play_deadline") == null
                        ? null
                        : ((Timestamp) r.get("play_deadline")).toInstant());
        return new Receipt(
                (Long) r.get("member_id"),
                (String) r.get("action"),
                (String) r.get("scope_key"),
                (String) r.get("request_hash"),
                java.util.Collections.unmodifiableMap(original));
    }

    private void check(Receipt r, PlaytestAccess.Context c, String action, String hash) {
        if (r.memberId() == null
                || r.memberId() != c.self().id()
                || !action.equals(r.action())
                || !c.scope().equals(r.scope())
                || !hash.equals(r.hash())) throw AuthException.conflict("REQUEST_KEY_CONFLICT");
    }

    private Map<String, Object> current(PlaytestAccess.Context c) {
        return db.queryForObject(
                "SELECT test_key::text,rev::text,state,outcome,started_at,deadline_at FROM"
                        + " play_test WHERE id=?",
                (rs, n) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("testKey", rs.getString(1));
                    result.put("rev", rs.getString(2));
                    result.put("state", rs.getString(3));
                    result.put("outcome", rs.getString(4));
                    result.put(
                            "startedAt",
                            rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant());
                    result.put(
                            "playDeadline",
                            rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant());
                    return java.util.Collections.unmodifiableMap(result);
                },
                c.id());
    }

    private void saveReceipt(
            PlaytestAccess.Context c,
            String action,
            String hash,
            UUID key,
            Map<String, Object> result) {
        if (db.update(
                        """
                        INSERT INTO test_action(request_key,member_id,action,scope_key,request_hash,result_data)
                        VALUES (?,?,?,?,?,jsonb_build_object('testKey',?::text,'rev',?::text,'state',?::text,
                        'outcome',?::text,'startedAt',?::timestamptz,'playDeadline',?::timestamptz))
                        """,
                        key,
                        c.self().id(),
                        action,
                        c.scope(),
                        hash,
                        result.get("testKey"),
                        result.get("rev"),
                        result.get("state"),
                        result.get("outcome"),
                        result.get("startedAt") == null
                                ? null
                                : Timestamp.from((Instant) result.get("startedAt")),
                        result.get("playDeadline") == null
                                ? null
                                : Timestamp.from((Instant) result.get("playDeadline")))
                != 1) throw unavailable();
    }

    private UUID audit(PlaytestAccess.Context c, String action, UUID requestId, String outcome) {
        UUID eventKey = UUID.randomUUID();
        if (db.update(
                        "INSERT INTO"
                            + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                            + " VALUES (?,'MEMBER',?,?,'PLAYTEST',?,?,'RESULT',?,'{}'::jsonb)",
                        eventKey,
                        c.self().key().toString(),
                        action,
                        c.scope(),
                        requestId,
                        outcome)
                != 1) throw unavailable();
        return eventKey;
    }

    private static boolean recent(Instant seen, Instant now) {
        return seen != null && !seen.isAfter(now) && seen.isAfter(now.minusSeconds(30));
    }

    private static void keys(UUID key, UUID requestId) {
        uuid(key);
        uuid(requestId);
    }

    private static void uuid(UUID value) {
        if (value == null || value.version() != 4 || value.variant() != 2) throw invalid();
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("PLAYTEST_UNAVAILABLE");
    }

    private static String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw unavailable();
        }
    }

    private static boolean collision(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sql && sql.getServerErrorMessage() != null)
                return "23505".equals(sql.getSQLState())
                        && "uk_test_action_request"
                                .equals(sql.getServerErrorMessage().getConstraint());
            if (cause instanceof SQLException sql && !"23505".equals(sql.getSQLState()))
                return false;
        }
        return false;
    }
}
