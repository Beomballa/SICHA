package com.reasoning.common.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Online administrator account changes; the caller handles CSRF, response cookies and HTTP limits. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AdminAccountService {
    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);
    private static final String BASE = "SELECT a.id,a.account_key,a.active_yn,a.can_create,a.can_review,a.can_publish,a.can_manage,"
            + "a.edit_rev,a.created_at,a.updated_at,c.enrolled_at,c.mfa_state,c.auth_rev "
            + "FROM admin_account a JOIN admin_credential c ON c.account_id=a.id ";
    private final JdbcTemplate db;
    private final AdminSessionAdapter sessions;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final AdminImpactService impact;
    private final TransactionTemplate tx;
    private final TransactionTemplate previewTx;

    public AdminAccountService(JdbcTemplate db, AdminSessionAdapter sessions, CryptoService crypto,
            ObjectMapper json, AdminImpactService impact, PlatformTransactionManager manager) {
        this.db = db;
        this.sessions = sessions;
        this.crypto = crypto;
        this.json = json;
        this.impact = impact;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.previewTx = new TransactionTemplate(manager);
        this.previewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.previewTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * ADM-01: authorizes a live, recently reauthenticated manager and lists minimal account state.
     * @param sid stored framework session ID, never a client-supplied account ID
     * @param actor principal bound to that session
     * @param size page size 1–100; null means 20
     * @param afterId positive decimal id cursor, or null for the first page
     * @param accountKey optional exact account key
     * @param activeYn optional active-state filter
     * @param enrolled optional enrollment-completed filter
     * @param permission optional single CREATE, REVIEW, PUBLISH or MANAGE filter
     * @return id-descending page without credentials or personal information
     * @throws AuthException when authorization, filter syntax or the session is invalid
     */
    public AccountPage getAccountList(String sid, AdminPrincipal actor, Integer size, String afterId,
            UUID accountKey, Boolean activeYn, Boolean enrolled, String permission) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        Long cursor = afterId == null ? null : decimal(afterId);
        if (permission != null) column(permission);
        return tx.execute(status -> {
        authorize(sid, actor);
        StringBuilder sql = new StringBuilder(BASE).append("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (cursor != null) { sql.append(" AND a.id < ?"); args.add(cursor); }
        if (accountKey != null) { sql.append(" AND a.account_key=?"); args.add(accountKey); }
        if (activeYn != null) { sql.append(" AND a.active_yn=?"); args.add(activeYn); }
        if (enrolled != null) { sql.append(enrolled ? " AND c.enrolled_at IS NOT NULL" : " AND c.enrolled_at IS NULL"); }
        if (permission != null) sql.append(" AND a.").append(column(permission)).append("=true");
        sql.append(" ORDER BY a.id DESC LIMIT ?");
        args.add(limit + 1);
        List<Account> rows = db.query(sql.toString(), (rs, n) -> account(rs), args.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        List<AccountView> items = rows.stream().map(AdminAccountService::view).toList();
        return new AccountPage(items, more, more ? Long.toString(rows.getLast().id()) : null);
        });
    }

    /**
     * ADM-02: reads the minimum current account state after live management authorization.
     * @param sid stored framework session ID
     * @param actor principal bound to that session
     * @param accountKey exact account UUID, not an authorization credential
     * @return current account state; never a replay of a previous mutation
     * @throws AuthException when unauthorized or the account does not exist
     */
    public AccountView getAccountDetail(String sid, AdminPrincipal actor, UUID accountKey) {
        return tx.execute(status -> {
            authorize(sid, actor);
            return view(target(accountKey));
        });
    }

    /**
     * ADM-07: reads all retained relations in one consistent snapshot, disclosing at most 20 safe codes.
     * @param sid current stored framework session ID
     * @param actor live manager principal requiring recent reauthentication
     * @param accountKey exact inactive account UUID
     * @return full-relation hash, counts, and bounded sample; never incident content
     * @throws AuthException if authorization fails, target is missing or already active
     */
    public ReactivationPreview getReactivationPreview(String sid, AdminPrincipal actor, UUID accountKey) {
        return previewTx.execute(status -> {
            authorize(sid, actor);
            Account target = target(accountKey);
            if (target.active()) throw AuthException.conflict("ACCOUNT_ALREADY_ACTIVE");
            var snapshot = impact.getImpact(accountKey);
            return new ReactivationPreview(view(target), snapshot.ownedCount(), snapshot.accessCount(),
                    snapshot.relationSample(), snapshot.truncated(), snapshot.impactHash(), credentialAction(target, true));
        });
    }

    /**
     * ADM-03: grants distinct global permissions in an audited, globally serialized transaction.
     * @param sid stored framework session ID
     * @param actor authenticated principal with current MANAGE and recent reauthentication
     * @param accountKey exact target UUID
     * @param expectedRev positive decimal edit revision as a string, without leading zeroes
     * @param permissions one to four distinct permission codes
     * @param reasonCode ASSIGNMENT_CHANGE or ACCESS_REVIEW
     * @param verificationRef nonpersonal out-of-band reference of 8–64 safe characters
     * @param requestId server-generated request correlation UUID
     * @return committed change result or unchanged result at the current revision
     * @throws AuthException on stale state, absent authority, target not ready or audit failure
     */
    public ChangeResult grantPermissions(String sid, AdminPrincipal actor, UUID accountKey,
            String expectedRev, List<String> permissions, String reasonCode, String verificationRef, UUID requestId) {
        return change(sid, actor, accountKey, expectedRev, permissions, reasonCode, verificationRef,
                requestId, Operation.GRANT, null);
    }

    /**
     * ADM-04: revokes distinct permissions, rechecking a separate shrinking transaction after audit failure.
     * @param sid stored framework session ID
     * @param actor authenticated principal with current MANAGE and recent reauthentication
     * @param accountKey exact target UUID
     * @param expectedRev positive decimal edit revision as a string, without leading zeroes
     * @param permissions one to four distinct permission codes
     * @param reasonCode ASSIGNMENT_CHANGE, ACCESS_REVIEW, OFFBOARDING or INCIDENT
     * @param verificationRef nonpersonal out-of-band reference of 8–64 safe characters
     * @param requestId server-generated request correlation UUID
     * @return committed result with RECORDED, NOT_REQUIRED or UNCONFIRMED audit status
     * @throws AuthException on stale state, last-manager protection or unconfirmed revocation
     */
    public ChangeResult revokePermissions(String sid, AdminPrincipal actor, UUID accountKey,
            String expectedRev, List<String> permissions, String reasonCode, String verificationRef, UUID requestId) {
        return change(sid, actor, accountKey, expectedRev, permissions, reasonCode, verificationRef,
                requestId, Operation.REVOKE, null);
    }

    /**
     * ADM-05: deactivates an account and discards unfinished enrollment credentials.
     * @param sid stored framework session ID
     * @param actor authenticated principal with current MANAGE and recent reauthentication
     * @param accountKey exact target UUID
     * @param expectedRev positive decimal edit revision as a string, without leading zeroes
     * @param reasonCode OFFBOARDING or INCIDENT
     * @param verificationRef nonpersonal out-of-band reference of 8–64 safe characters
     * @param requestId server-generated request correlation UUID
     * @return committed result; an already inactive account is unchanged
     * @throws AuthException on stale state, last-manager protection or unconfirmed revocation
     */
    public ChangeResult deactivate(String sid, AdminPrincipal actor, UUID accountKey, String expectedRev,
            String reasonCode, String verificationRef, UUID requestId) {
        return change(sid, actor, accountKey, expectedRev, List.of(), reasonCode, verificationRef,
                requestId, Operation.DEACTIVATE, null);
    }

    /**
     * ADM-06: reactivates a retained account only after a full relationship impact match and required audit.
     * @param sid current stored framework session ID
     * @param actor live manager principal requiring recent reauthentication
     * @param accountKey exact inactive account UUID
     * @param expectedRev latest positive decimal account revision
     * @param impactHash SHA-256 from a separately confirmed current preview
     * @param reasonCode RETURN_TO_WORK
     * @param verificationRef nonpersonal verification reference of 8–64 safe characters
     * @param requestId server-generated UUID for the action audit
     * @return change result, never restoring prior sessions or grants
     * @throws AuthException on stale revision, changed impact, missing authority or audit failure
     */
    public ChangeResult reactivate(String sid, AdminPrincipal actor, UUID accountKey, String expectedRev,
            String impactHash, String reasonCode, String verificationRef, UUID requestId) {
        if (impactHash == null || !impactHash.matches("[0-9a-f]{64}"))
            throw AuthException.badRequest("INVALID_IMPACT_HASH");
        return change(sid, actor, accountKey, expectedRev, List.of(), reasonCode, verificationRef,
                requestId, Operation.REACTIVATE, impactHash);
    }

    private ChangeResult change(String sid, AdminPrincipal actor, UUID key, String revision,
            List<String> permissions, String reason, String ref, UUID requestId, Operation operation, String impactHash) {
        long expected = decimal(revision);
        if (key == null || requestId == null || ref == null || !ref.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        if (!operation.reasons.contains(reason)) throw AuthException.badRequest("INVALID_REASON_CODE");
        if (operation != Operation.DEACTIVATE && operation != Operation.REACTIVATE && (permissions == null || permissions.isEmpty()
                || permissions.size() > 4 || permissions.stream().anyMatch(p -> p == null || !validPermission(p))
                || permissions.stream().distinct().count() != permissions.size()))
            throw AuthException.badRequest("INVALID_PERMISSIONS");
        authorize(sid, actor); // Never disclose whether the target exists before checking the actor.
        Account candidate = target(key);
        try {
            return apply(sid, actor, candidate, expected, permissions, reason, ref, requestId, operation, impactHash, true);
        } catch (AuditFailure failure) {
            if (operation == Operation.GRANT || operation == Operation.REACTIVATE)
                throw AuthException.unavailable("AUTH_UNAVAILABLE");
            try {
                ChangeResult result = apply(sid, actor, candidate, expected, permissions, reason, ref,
                        requestId, operation, null, false);
                if (result.changed()) log.error("Emergency admin change without audit; requestId={}, action={}, targetKey={}",
                        requestId, operation, key);
                return result;
            } catch (DataAccessException failureInBlock) {
                log.error("Emergency admin change unconfirmed; requestId={}, action={}, targetKey={}", requestId, operation, key);
                throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
            }
        } catch (DataAccessException failure) {
            if (lockFailure(failure)) throw AuthException.unavailable("ADMIN_CHANGE_BUSY");
            throw AuthException.unavailable("AUTH_UNAVAILABLE");
        }
    }

    private ChangeResult apply(String sid, AdminPrincipal actor, Account candidate, long expected,
            List<String> permissions, String reason, String ref, UUID requestId, Operation op,
            String impactHash, boolean audited) {
        try {
            return tx.execute(status -> {
                db.execute("SET LOCAL lock_timeout = '5s'");
                db.execute("SELECT pg_advisory_xact_lock(821,1)");
                List<Long> ids = new ArrayList<>(List.of(actor.accountId(), candidate.id()));
                ids = ids.stream().distinct().sorted().toList();
                for (long id : ids) db.queryForObject("SELECT id FROM admin_account WHERE id=? FOR UPDATE", Long.class, id);
                Account target = target(candidate.key());
                if (target.id() != candidate.id()) throw AuthException.conflict("STATE_CONFLICT");
                // For unfinished enrollment, acquire enrollment before either credential row.
                if (target.enrolledAt() == null) db.query("SELECT id FROM admin_enrollment WHERE account_id=? FOR UPDATE",
                        rs -> { while (rs.next()) { } return null; }, target.id());
                for (long id : ids) db.queryForObject("SELECT account_id FROM admin_credential WHERE account_id=? FOR UPDATE",
                        Long.class, id);
                List<UUID> sessionKeys = db.query("SELECT session_key FROM admin_session WHERE "
                        + "(account_id=? AND state IN ('ACTIVE','PENDING')) OR session_key=? ORDER BY session_key",
                        (rs, n) -> (UUID) rs.getObject(1), target.id(), actor.sessionKey());
                for (UUID sessionKey : sessionKeys) db.queryForObject(
                        "SELECT session_key FROM admin_session WHERE session_key=? FOR UPDATE", UUID.class, sessionKey);
                List<UUID> grantKeys = db.query("SELECT grant_key FROM admin_auth_grant WHERE account_id=? "
                        + "AND consumed_at IS NULL AND revoked_at IS NULL ORDER BY grant_key",
                        (rs, n) -> (UUID) rs.getObject(1), target.id());
                for (UUID grantKey : grantKeys) db.queryForObject(
                        "SELECT grant_key FROM admin_auth_grant WHERE grant_key=? FOR UPDATE", UUID.class, grantKey);
                authorize(sid, actor);
                target = target(candidate.key());
                if (target.editRev() != expected) throw AuthException.conflict("STATE_CONFLICT");
                if (op == Operation.GRANT && (!target.active() || target.enrolledAt() == null
                        || !"READY".equals(target.mfaState()))) throw AuthException.conflict("ACCOUNT_NOT_READY");
                List<String> before = target.permissions();
                boolean changed = switch (op) {
                    case DEACTIVATE -> target.active();
                    case REACTIVATE -> !target.active();
                    case GRANT, REVOKE -> permissions.stream().anyMatch(p -> before.contains(p) != (op == Operation.GRANT));
                };
                if (!changed) return result(target, false, "NOT_REQUIRED", requestId, actor);
                if (target.editRev() == Long.MAX_VALUE || target.authRev() == Long.MAX_VALUE)
                    throw AuthException.conflict("STATE_CONFLICT");
                if (op == Operation.REACTIVATE && !impact.getImpact(target.key()).impactHash().equals(impactHash))
                    throw AuthException.conflict("IMPACT_CHANGED");
                boolean removesManager = target.active() && target.enrolledAt() != null
                        && "READY".equals(target.mfaState()) && before.contains("MANAGE")
                        && (op == Operation.DEACTIVATE || op == Operation.REVOKE && permissions.contains("MANAGE"));
                if (removesManager) {
                    Integer successors = db.queryForObject("SELECT count(*) FROM admin_account a JOIN admin_credential c "
                            + "ON c.account_id=a.id WHERE a.id<>? AND a.active_yn AND a.can_manage "
                            + "AND c.enrolled_at IS NOT NULL AND c.mfa_state='READY'", Integer.class, target.id());
                    if (successors == null || successors == 0) throw AuthException.conflict("LAST_MANAGER_REQUIRED");
                }
                Instant now = db.queryForObject("SELECT clock_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
                if (op == Operation.DEACTIVATE || op == Operation.REACTIVATE) {
                    db.update("UPDATE admin_account SET active_yn=?,edit_rev=edit_rev+1,updated_at=? WHERE id=?",
                            op == Operation.REACTIVATE, now, target.id());
                    if (target.enrolledAt() == null) {
                        db.update("UPDATE admin_enrollment SET code_hash=NULL,grant_hash=NULL,revoked_at=COALESCE(revoked_at,?),"
                                + "updated_at=? WHERE account_id=? AND completed_at IS NULL", now, now, target.id());
                        db.update("UPDATE admin_credential SET password_hash=NULL,mfa_cipher=NULL,mfa_verified_at=NULL,"
                                + "last_step=NULL,auth_rev=auth_rev+1,updated_at=? WHERE account_id=?", now, target.id());
                    } else bumpCredential(target.id(), now);
                } else {
                    for (String permission : permissions) db.update("UPDATE admin_account SET " + column(permission)
                            + "=? WHERE id=?", op == Operation.GRANT, target.id());
                    db.update("UPDATE admin_account SET edit_rev=edit_rev+1,updated_at=? WHERE id=?", now, target.id());
                    bumpCredential(target.id(), now);
                }
                db.update("UPDATE admin_session SET state='REVOKED',revoked_at=?,updated_at=? "
                        + "WHERE account_id=? AND state IN ('ACTIVE','PENDING')", now, now, target.id());
                db.update("UPDATE admin_auth_grant SET token_hash=NULL,mfa_cipher=NULL,mfa_verified_at=NULL,"
                        + "last_step=NULL,revoked_at=?,updated_at=? WHERE account_id=? AND consumed_at IS NULL "
                        + "AND revoked_at IS NULL", now, now, target.id());
                Account after = target(candidate.key());
                if (audited) {
                    try { audit(actor, target, after, reason, ref, requestId, op, impactHash); }
                    catch (DataAccessException failed) { throw new AuditFailure(failed); }
                }
                return result(after, true, audited ? "RECORDED" : "UNCONFIRMED", requestId, actor);
            });
        } catch (DataAccessException failure) {
            if (lockFailure(failure)) throw AuthException.unavailable("ADMIN_CHANGE_BUSY");
            if (!audited) throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
            throw AuthException.unavailable("AUTH_UNAVAILABLE");
        }
    }

    private void bumpCredential(long id, Instant now) {
        db.update("UPDATE admin_credential SET auth_rev=auth_rev+1,updated_at=? WHERE account_id=?", now, id);
    }

    private void audit(AdminPrincipal actor, Account before, Account after, String reason, String ref,
            UUID requestId, Operation op, String impactHash) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", auditState(before));
        data.put("after", auditState(after));
        data.put("reasonCode", reason);
        data.put("verificationRef", ref);
        if (op == Operation.REACTIVATE) data.put("impactHash", impactHash);
        String payload;
        try { payload = json.writeValueAsString(data); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Admin audit serialization failed", failure); }
        if (payload.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalStateException("Admin audit exceeds size limit");
        db.update("INSERT INTO admin_auth_audit(actor_kind,actor_id,target_id,action,outcome,request_id,route,method,"
                + "http_status,session_key,change_data) VALUES ('ADMIN',?,?,?,'COMMITTED',?,?, 'POST',200,?,?::jsonb)",
                actor.accountId(), before.id(), op.action, requestId, op.route, actor.sessionKey(), payload);
    }

    private static Map<String, Object> auditState(Account a) {
        return Map.of("activeYn", a.active(), "permissions", a.permissions(), "editRev", Long.toString(a.editRev()));
    }

    private void authorize(String sid, AdminPrincipal actor) {
        if (sid == null || actor == null || !actor.equals(sessions.findStoredPrincipal(sid).orElse(null)))
            throw AuthException.unauthorized("AUTH_REQUIRED");
        byte[] hash = crypto.sessionHash(sid);
        List<AuthState> states = db.query("SELECT a.active_yn,a.can_manage,c.enrolled_at,c.mfa_state,c.auth_rev,"
                + "s.reauth_at,s.expires_at,s.last_action_at FROM admin_account a JOIN admin_credential c "
                + "ON c.account_id=a.id JOIN admin_session s ON s.account_id=a.id WHERE a.id=? AND a.account_key=? "
                + "AND s.session_key=? AND s.sid_hash=? AND s.state='ACTIVE' AND s.auth_rev=c.auth_rev "
                + "AND c.auth_rev=? FOR UPDATE OF a", (rs, n) -> new AuthState(rs.getBoolean(1), rs.getBoolean(2),
                rs.getTimestamp(3), rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()),
                actor.accountId(), actor.accountKey(), actor.sessionKey(), hash, actor.authRev());
        if (states.isEmpty()) throw AuthException.unauthorized("AUTH_REQUIRED");
        AuthState state = states.getFirst();
        Instant now = db.queryForObject("SELECT clock_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
        if (!state.active() || state.enrolled() == null || !"READY".equals(state.mfa())
                || !now.isBefore(state.expires()) || !now.isBefore(state.lastAction().plusSeconds(1800)))
            throw AuthException.unauthorized("AUTH_REQUIRED");
        if (!state.manage()) throw AuthException.forbidden("FORBIDDEN");
        if (!now.isBefore(state.reauth().plusSeconds(300))) throw AuthException.forbidden("REAUTH_REQUIRED");
    }

    private Account target(UUID key) {
        if (key == null) throw AuthException.badRequest("INVALID_ACCOUNT_KEY");
        List<Account> rows = db.query(BASE + "WHERE a.account_key=?", (rs, n) -> account(rs), key);
        if (rows.isEmpty()) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return rows.getFirst();
    }

    private static Account account(java.sql.ResultSet rs) throws SQLException {
        Timestamp enrolled = rs.getTimestamp(11);
        return new Account(rs.getLong(1), (UUID) rs.getObject(2), rs.getBoolean(3), rs.getBoolean(4),
                rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), rs.getLong(8), rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10).toInstant(), enrolled == null ? null : enrolled.toInstant(), rs.getString(12), rs.getLong(13));
    }

    private static AccountView view(Account a) {
        return new AccountView(a.key(), a.active(), a.enrolledAt() != null, a.mfaState(), a.permissions(),
                Long.toString(a.editRev()), a.createdAt(), a.updatedAt());
    }

    private static ChangeResult result(Account a, boolean changed, String auditStatus, UUID requestId, AdminPrincipal actor) {
        return new ChangeResult(a.key(), Long.toString(a.editRev()), changed,
                changed && a.id() == actor.accountId() ? "LOGIN" : "REFRESH",
                credentialAction(a, changed), auditStatus, requestId);
    }

    private static String credentialAction(Account a, boolean changed) {
        return !changed ? "NONE" : a.enrolledAt() == null ? "REISSUE_ENROLLMENT"
                : "RECOVERY".equals(a.mfaState()) ? "REISSUE_MFA_RECOVERY" : "LOGIN";
    }

    private static long decimal(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw AuthException.badRequest("INVALID_REVISION");
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw AuthException.badRequest("INVALID_REVISION"); }
    }

    private static boolean validPermission(String value) {
        return "CREATE".equals(value) || "REVIEW".equals(value) || "PUBLISH".equals(value) || "MANAGE".equals(value);
    }

    private static String column(String permission) {
        return switch (permission) {
            case "CREATE" -> "can_create";
            case "REVIEW" -> "can_review";
            case "PUBLISH" -> "can_publish";
            case "MANAGE" -> "can_manage";
            default -> throw AuthException.badRequest("INVALID_PERMISSIONS");
        };
    }

    private static boolean lockFailure(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ("55P03".equals(sql.getSQLState())
                    || "40P01".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    private enum Operation {
        GRANT("ACCOUNT_PERMISSION_GRANTED", "/admin/api/accounts/{accountKey}/permissions/grant",
                List.of("ASSIGNMENT_CHANGE", "ACCESS_REVIEW")),
        REVOKE("ACCOUNT_PERMISSION_REVOKED", "/admin/api/accounts/{accountKey}/permissions/revoke",
                List.of("ASSIGNMENT_CHANGE", "ACCESS_REVIEW", "OFFBOARDING", "INCIDENT")),
        DEACTIVATE("ACCOUNT_DEACTIVATED", "/admin/api/accounts/{accountKey}/deactivate",
                List.of("OFFBOARDING", "INCIDENT")),
        REACTIVATE("ACCOUNT_REACTIVATED", "/admin/api/accounts/{accountKey}/reactivate",
                List.of("RETURN_TO_WORK"));
        private final String action;
        private final String route;
        private final List<String> reasons;
        Operation(String action, String route, List<String> reasons) {
            this.action = action;
            this.route = route;
            this.reasons = reasons;
        }
    }

    private static final class AuditFailure extends RuntimeException {
        AuditFailure(DataAccessException failure) { super(failure); }
    }

    private record AuthState(boolean active, boolean manage, Timestamp enrolled, String mfa, long rev,
            Instant reauth, Instant expires, Instant lastAction) {}
    private record Account(long id, UUID key, boolean active, boolean create, boolean review, boolean publish,
            boolean manage, long editRev, Instant createdAt, Instant updatedAt, Instant enrolledAt, String mfaState,
            long authRev) {
        List<String> permissions() {
            List<String> values = new ArrayList<>(4);
            if (create) values.add("CREATE");
            if (manage) values.add("MANAGE");
            if (publish) values.add("PUBLISH");
            if (review) values.add("REVIEW");
            return List.copyOf(values);
        }
    }

    /** Minimal account projection; no login identifiers or personal information. */
    public record AccountView(UUID accountKey, boolean activeYn, boolean enrolled, String mfaState,
            List<String> permissions, String editRev, Instant createdAt, Instant updatedAt) {}
    /** Fixed-order id-descending page with an opaque-to-clients decimal keyset cursor. */
    public record AccountPage(List<AccountView> items, boolean hasNext, String nextAfterId) {}
    /** Committed change outcome; callers must clear the current browser's cookies for a self-change. */
    public record ChangeResult(UUID accountKey, String editRev, boolean changed, String nextAction,
            String credentialAction, String auditStatus, UUID requestId) {}
    /** Read-only bounded sample and hash of all retained permissions and story relationships. */
    public record ReactivationPreview(AccountView account, long ownedCount, long accessCount,
            List<AdminImpactService.Relation> relationSample, boolean truncated, String impactHash,
            String nextCredentialAction) {}
}
