package com.reasoning.common.story.service;

import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryService.AccessScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 사건 소유자/운영자의 접근 관계를 현재 계정·사건 잠금 아래 관리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryAccessService {
    private static final Logger log = LoggerFactory.getLogger(StoryAccessService.class);
    private static final Set<String> PERMISSIONS = Set.of("EDIT", "REVIEW", "PUBLISH");
    private static final Set<String> REVOKE_REASONS = Set.of("ASSIGNMENT_CHANGE", "ACCESS_REVIEW", "OFFBOARDING", "INCIDENT");
    private final StoryService stories;
    private final JdbcTemplate db;

    public StoryAccessService(StoryService stories, JdbcTemplate db) {
        this.stories = stories;
        this.db = db;
    }

    /**
     * 본문·제목 없이 활성 관계 총수와 계정 키/권한의 ASCII 커서 목록을 반환한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 소유자 또는 MANAGE 행위자이며 최근 재인증이 필요하다
     * @param storyCode 정확한 사건 코드
     * @param size 1~100 또는 기본 20의 페이지 크기
     * @param afterKey 마지막 accountKey~permission 또는 null
     * @return 비활성 관계까지 포함한 목록과 전체 활성 관계 수
     */
    public AccessPage getAccessList(String sid, AdminPrincipal actor, String storyCode, Integer size, String afterKey) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        String accountAfter = "";
        String permissionAfter = "";
        if (afterKey != null) {
            String[] parts = afterKey.split("~", -1);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || !PERMISSIONS.contains(parts[1])) throw AuthException.badRequest("INVALID_REQUEST");
            accountAfter = parts[0];
            permissionAfter = parts[1];
        }
        String afterAccount = accountAfter;
        String afterPermission = permissionAfter;
        return stories.withStoryAccess(sid, actor, storyCode, null, null, null, scope -> {
            int activeCount = db.queryForObject("SELECT count(*) FROM story_access WHERE story_id=? AND active_yn",
                    Integer.class, scope.storyId());
            List<AccessItem> rows = db.query("SELECT a.account_key,sa.permission,sa.active_yn FROM story_access sa "
                            + "JOIN admin_account a ON a.id=sa.admin_id WHERE sa.story_id=? AND "
                            + "(a.account_key::text COLLATE \"C\">? COLLATE \"C\" OR "
                            + "(a.account_key::text COLLATE \"C\"=? COLLATE \"C\" "
                            + "AND sa.permission COLLATE \"C\">? COLLATE \"C\")) "
                            + "ORDER BY a.account_key::text COLLATE \"C\",sa.permission COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new AccessItem((UUID) rs.getObject(1), rs.getString(2), rs.getBoolean(3)),
                    scope.storyId(), afterAccount, afterAccount, afterPermission, limit + 1);
            boolean next = rows.size() > limit;
            List<AccessItem> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
            AccessItem last = next ? items.get(items.size() - 1) : null;
            return new AccessPage(storyCode, Long.toString(scope.rev()), scope.active(), scope.ownerKey(),
                    activeCount, items, next, last == null ? null : last.accountKey() + "~" + last.permission());
        });
    }

    /**
     * 활성 사건의 준비된 대상 계정에 사건 권한 한 개를 부여한다.
     * @param sid 현재 일반 세션 ID
     * @param actor EDIT는 소유자, REVIEW/PUBLISH는 MANAGE 행위자
     * @param storyCode 대상 사건 코드
     * @param expectedStoryRev 무변경에도 요구하는 현재 사건 수정번호
     * @param accountKey 대상 계정의 공개 UUID
     * @param permission EDIT, REVIEW 또는 PUBLISH
     * @param reasonCode ASSIGNMENT_CHANGE
     * @param verificationRef 비개인 확인 참조 8~64자
     * @param requestId 필수 감사 요청 ID
     * @return 실제 변경에만 증가하는 사건 수정번호와 감사 상태
     */
    public AccessResult grantAccess(String sid, AdminPrincipal actor, String storyCode, String expectedStoryRev,
            UUID accountKey, String permission, String reasonCode, String verificationRef, UUID requestId) {
        validate(accountKey, permission, reasonCode, verificationRef, requestId, true);
        try {
            return change(sid, actor, storyCode, expectedStoryRev, accountKey, permission, reasonCode,
                    verificationRef, requestId, true, true);
        } catch (StoryService.AccessAuditFailure failure) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    /**
     * 대상 자격이 이미 사라져도 관계 차단을 수행하고 감사 실패 시 별도 보안 축소 TX로 재검사한다.
     * @param sid 현재 일반 세션 ID
     * @param actor EDIT는 소유자, REVIEW/PUBLISH는 MANAGE 행위자
     * @param storyCode 대상 사건 코드
     * @param expectedStoryRev 무변경에도 요구하는 현재 사건 수정번호
     * @param accountKey 대상 계정의 공개 UUID
     * @param permission EDIT, REVIEW 또는 PUBLISH
     * @param reasonCode 허용된 변경·검토·퇴직·사고 사유 코드
     * @param verificationRef 비개인 확인 참조 8~64자
     * @param requestId 필수 감사 요청 ID
     * @return 정상 감사, 불필요한 변경 또는 비상 감사 미확정의 영수증
     */
    public AccessResult revokeAccess(String sid, AdminPrincipal actor, String storyCode, String expectedStoryRev,
            UUID accountKey, String permission, String reasonCode, String verificationRef, UUID requestId) {
        validate(accountKey, permission, reasonCode, verificationRef, requestId, false);
        try {
            return change(sid, actor, storyCode, expectedStoryRev, accountKey, permission, reasonCode,
                    verificationRef, requestId, false, true);
        } catch (StoryService.AccessAuditFailure failure) {
            try {
                AccessResult result = change(sid, actor, storyCode, expectedStoryRev, accountKey, permission,
                        reasonCode, verificationRef, requestId, false, false);
                if (result.changed()) log.error("Emergency story access revocation without audit; requestId={}", requestId);
                return result;
            } catch (RuntimeException unavailable) {
                log.error("Emergency story access revocation unconfirmed; requestId={}", requestId);
                throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
            }
        }
    }

    private AccessResult change(String sid, AdminPrincipal actor, String code, String expected, UUID key,
            String permission, String reason, String ref, UUID requestId, boolean grant, boolean audited) {
        return stories.withStoryAccess(sid, actor, code, key, expected, "EDIT".equals(permission), scope -> {
            if ("EDIT".equals(permission) && scope.ownerId() == scope.targetId())
                throw AuthException.conflict("OWNER_RELATION_FIXED");
            if (grant && !scope.active()) throw AuthException.conflict("STATE_CONFLICT");
            if (grant) {
                Target target = db.query("SELECT a.active_yn,c.enrolled_at IS NOT NULL,c.mfa_state,a.can_review,a.can_publish "
                                + "FROM admin_account a LEFT JOIN admin_credential c ON c.account_id=a.id WHERE a.id=?",
                        rs -> rs.next() ? new Target(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3),
                                rs.getBoolean(4), rs.getBoolean(5)) : null, scope.targetId());
                if (target == null || !target.active() || !target.enrolled() || !"READY".equals(target.mfa())
                        || "REVIEW".equals(permission) && !target.review()
                        || "PUBLISH".equals(permission) && !target.publish())
                    throw AuthException.conflict("ACCOUNT_NOT_READY");
            }
            Boolean before = db.query("SELECT active_yn FROM story_access WHERE story_id=? AND admin_id=? "
                            + "AND permission=? FOR UPDATE", rs -> rs.next() ? rs.getBoolean(1) : null,
                    scope.storyId(), scope.targetId(), permission);
            boolean enabled = Boolean.TRUE.equals(before);
            if (enabled == grant) return result(code, scope.rev(), key, permission, grant, false, "NOT_REQUIRED", requestId);
            if (before == null) {
                db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,?,?)",
                        scope.storyId(), scope.targetId(), permission, actor.accountId());
            } else {
                db.update("UPDATE story_access SET active_yn=?,granted_by=?,updated_at=clock_timestamp() "
                                + "WHERE story_id=? AND admin_id=? AND permission=?",
                        grant, actor.accountId(), scope.storyId(), scope.targetId(), permission);
            }
            long rev = stories.recordAccessChange(scope, actor, key, permission, enabled, grant,
                    reason, ref, requestId, audited);
            return result(code, rev, key, permission, grant, true, audited ? "RECORDED" : "UNCONFIRMED", requestId);
        });
    }

    private static void validate(UUID key, String permission, String reason, String ref, UUID id, boolean grant) {
        if (key == null || !PERMISSIONS.contains(permission) || id == null
                || ref == null || !ref.matches("[A-Za-z0-9_-]{8,64}")
                || !(grant ? "ASSIGNMENT_CHANGE".equals(reason) : REVOKE_REASONS.contains(reason)))
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static AccessResult result(String code, long rev, UUID key, String permission, boolean active,
            boolean changed, String audit, UUID id) {
        return new AccessResult(code, Long.toString(rev), key, permission, active, changed, audit, id);
    }

    private record Target(boolean active, boolean enrolled, String mfa, boolean review, boolean publish) {}
    public record AccessItem(UUID accountKey, String permission, boolean activeYn) {}
    public record AccessPage(String storyCode, String storyRev, boolean activeYn, UUID ownerAccountKey,
            int activeRelationCount, List<AccessItem> items, boolean hasNext, String nextAfterKey) {}
    public record AccessResult(String storyCode, String storyRev, UUID accountKey, String permission,
            boolean activeYn, boolean changed, String auditStatus, UUID requestId) {}
}
