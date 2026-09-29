package com.reasoning.common.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.RecoveryService.ApprovalVerifier;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** ADM-OFFLINE-02 is available only in the private non-web application mode. */
@Service
@ConditionalOnNotWebApplication
public final class AdminOfflineReactivateService {
    private final JdbcTemplate db;
    private final AdminImpactService impact;
    private final ObjectProvider<ApprovalVerifier> approvals;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate previewTx;

    public AdminOfflineReactivateService(JdbcTemplate db, AdminImpactService impact,
            ObjectProvider<ApprovalVerifier> approvals, ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db;
        this.impact = impact;
        this.approvals = approvals;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.previewTx = new TransactionTemplate(manager);
        this.previewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.previewTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Reads the full relationship impact using a separate signed, revision-bound preview approval.
     * @param accountKey exact blocked manager UUID
     * @param expectedRev current positive decimal account edit revision
     * @param verificationRef nonpersonal out-of-band identity confirmation reference
     * @param approvalRef independently signed preview approval reference
     * @param actorRef responsible private-console operator reference
     * @return safe bounded sample and hash of every retained relationship
     * @throws AuthException if the signed approval, target state or operator-absence policy fails
     */
    public Preview preview(UUID accountKey, String expectedRev, String verificationRef,
            String approvalRef, String actorRef) {
        checkInputs(accountKey, verificationRef, approvalRef, actorRef);
        long revision = revision(expectedRev);
        try {
            return previewTx.execute(status -> {
                Account target = account(accountKey);
                if (target.editRev() != revision) throw AuthException.conflict("STATE_CONFLICT");
                approved(accountKey, "ADMIN_REACTIVATE_PREVIEW_REV_" + revision,
                        verificationRef, approvalRef, actorRef);
                eligible(target);
                var snapshot = impact.getImpact(accountKey);
                return new Preview(accountKey, Long.toString(target.editRev()), snapshot.ownedCount(),
                        snapshot.accessCount(), snapshot.relationSample(), snapshot.truncated(),
                        snapshot.impactHash(), target.enrolled() ? "LOGIN" : "REISSUE_ENROLLMENT");
            });
        } catch (DataAccessException failure) {
            throw AuthException.unavailable("AUTH_UNAVAILABLE");
        }
    }

    /**
     * Reactivates an inactive retained manager with a separate hash-bound approval and mandatory audit.
     * @param accountKey exact blocked manager UUID
     * @param expectedRev current positive decimal account edit revision
     * @param impactHash current, confirmed lower-hex SHA-256 from the separate offline preview
     * @param verificationRef nonpersonal out-of-band identity confirmation reference
     * @param approvalRef independently signed activation approval reference
     * @param actorRef responsible private-console operator reference
     * @param requestId new correlation UUID for this execution
     * @return audited revision change and required credential action; never restores an old session
     * @throws AuthException on stale state, changed impact, missing approval or audit failure
     */
    public Result reactivate(UUID accountKey, String expectedRev, String impactHash, String verificationRef,
            String approvalRef, String actorRef, UUID requestId) {
        checkInputs(accountKey, verificationRef, approvalRef, actorRef);
        if (requestId == null || impactHash == null || !impactHash.matches("[0-9a-f]{64}"))
            throw AuthException.badRequest("INVALID_IMPACT_HASH");
        long revision = revision(expectedRev);
        try {
            return tx.execute(status -> {
                db.execute("SET LOCAL lock_timeout = '5s'");
                db.execute("SELECT pg_advisory_xact_lock(821,1)");
                Account target = account(accountKey);
                db.queryForObject("SELECT id FROM admin_account WHERE id=? FOR UPDATE", Long.class, target.id());
                target = account(accountKey);
                if (!target.enrolled()) db.query("SELECT id FROM admin_enrollment WHERE account_id=? FOR UPDATE",
                        rs -> { while (rs.next()) { } return null; }, target.id());
                db.queryForObject("SELECT account_id FROM admin_credential WHERE account_id=? FOR UPDATE",
                        Long.class, target.id());
                List<UUID> sessionKeys = db.query("SELECT session_key FROM admin_session WHERE account_id=? "
                        + "ORDER BY session_key", (rs, row) -> (UUID) rs.getObject(1), target.id());
                for (UUID key : sessionKeys)
                    db.queryForObject("SELECT session_key FROM admin_session WHERE session_key=? FOR UPDATE", UUID.class, key);
                List<UUID> grantKeys = db.query("SELECT grant_key FROM admin_auth_grant WHERE account_id=? "
                        + "ORDER BY grant_key", (rs, row) -> (UUID) rs.getObject(1), target.id());
                for (UUID key : grantKeys)
                    db.queryForObject("SELECT grant_key FROM admin_auth_grant WHERE grant_key=? FOR UPDATE", UUID.class, key);
                target = account(accountKey);
                if (target.editRev() != revision) throw AuthException.conflict("STATE_CONFLICT");
                approved(accountKey, "ADMIN_REACTIVATE_REV_" + revision + "_HASH_" + impactHash,
                        verificationRef, approvalRef, actorRef);
                eligible(target);
                if (target.editRev() == Long.MAX_VALUE || target.authRev() == Long.MAX_VALUE)
                    throw AuthException.conflict("STATE_CONFLICT");
                if (!impact.getImpact(accountKey).impactHash().equals(impactHash))
                    throw AuthException.conflict("IMPACT_CHANGED");
                Instant now = db.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
                db.update("UPDATE admin_account SET active_yn=true,edit_rev=edit_rev+1,updated_at=? WHERE id=?",
                        now, target.id());
                if (target.enrolled()) {
                    db.update("UPDATE admin_credential SET auth_rev=auth_rev+1,updated_at=? WHERE account_id=?",
                            now, target.id());
                } else {
                    db.update("UPDATE admin_enrollment SET code_hash=NULL,grant_hash=NULL,"
                            + "revoked_at=COALESCE(revoked_at,?),updated_at=? WHERE account_id=? AND completed_at IS NULL",
                            now, now, target.id());
                    db.update("UPDATE admin_credential SET password_hash=NULL,mfa_cipher=NULL,mfa_verified_at=NULL,"
                            + "last_step=NULL,auth_rev=auth_rev+1,updated_at=? WHERE account_id=?", now, target.id());
                }
                db.update("UPDATE admin_session SET state='REVOKED',revoked_at=?,updated_at=? "
                        + "WHERE account_id=? AND state IN ('ACTIVE','PENDING')", now, now, target.id());
                db.update("UPDATE admin_auth_grant SET token_hash=NULL,mfa_cipher=NULL,mfa_verified_at=NULL,"
                        + "last_step=NULL,revoked_at=?,updated_at=? WHERE account_id=? AND consumed_at IS NULL "
                        + "AND revoked_at IS NULL", now, now, target.id());
                audit(target, account(accountKey), impactHash, verificationRef, actorRef, requestId);
                return new Result(accountKey, Long.toString(revision + 1), true, "RECORDED", requestId,
                        target.enrolled() ? "LOGIN" : "REISSUE_ENROLLMENT");
            });
        } catch (DataAccessException failure) {
            if (lockFailure(failure)) throw AuthException.unavailable("ADMIN_CHANGE_BUSY");
            throw AuthException.unavailable("AUTH_UNAVAILABLE");
        }
    }

    private void eligible(Account target) {
        if (target.active() || !target.manage()) throw AuthException.conflict("ACCOUNT_NOT_READY");
        if (target.enrolled()) {
            if (!"READY".equals(target.mfaState())) throw AuthException.conflict("ACCOUNT_NOT_READY");
        } else {
            if (!"PENDING".equals(target.mfaState()) || !db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM admin_enrollment WHERE account_id=? "
                            + "AND kind='BOOTSTRAP' AND completed_at IS NULL)", Boolean.class, target.id()))
                throw AuthException.conflict("ACCOUNT_NOT_READY");
        }
        Integer readyManagers = db.queryForObject("SELECT count(*) FROM admin_account a JOIN admin_credential c "
                + "ON c.account_id=a.id WHERE a.active_yn AND a.can_manage AND c.enrolled_at IS NOT NULL "
                + "AND c.mfa_state='READY'", Integer.class);
        if (readyManagers == null || readyManagers != 0)
            throw AuthException.conflict("NORMAL_MANAGER_AVAILABLE");
    }

    private Account account(UUID key) {
        List<Account> accounts = db.query("SELECT a.id,a.account_key,a.active_yn,a.can_create,a.can_review,"
                + "a.can_publish,a.can_manage,a.edit_rev,c.auth_rev,c.enrolled_at,c.mfa_state "
                + "FROM admin_account a JOIN admin_credential c ON c.account_id=a.id WHERE a.account_key=?",
                (rs, row) -> new Account(rs.getLong(1), (UUID) rs.getObject(2), rs.getBoolean(3),
                        rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7),
                        rs.getLong(8), rs.getLong(9), rs.getTimestamp(10) != null, rs.getString(11)), key);
        if (accounts.isEmpty()) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return accounts.getFirst();
    }

    private void approved(UUID accountKey, String purpose, String verification, String approval, String actor) {
        ApprovalVerifier verifier = approvals.getIfAvailable();
        if (verifier == null || !verifier.offlineApproved(accountKey, purpose, verification, approval, actor))
            throw AuthException.forbidden("OFFLINE_APPROVAL_REQUIRED");
    }

    private void audit(Account before, Account after, String impactHash, String verification, String actor,
            UUID requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", state(before));
        data.put("after", state(after));
        data.put("reasonCode", "RETURN_TO_WORK");
        data.put("verificationRef", verification);
        data.put("impactHash", impactHash);
        String payload;
        try { payload = json.writeValueAsString(data); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Offline activation audit failed", failure); }
        if (payload.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalStateException("Offline activation audit exceeds size limit");
        db.update("INSERT INTO admin_auth_audit(actor_kind,actor_ref,target_id,action,outcome,request_id,route,"
                + "method,change_data) VALUES ('OFFLINE',?,?,'ACCOUNT_OFFLINE_REACTIVATED','COMMITTED',?,"
                + "'ADM-OFFLINE-02','OFFLINE',?::jsonb)", actor, before.id(), requestId, payload);
    }

    private static Map<String, Object> state(Account a) {
        List<String> permissions = new ArrayList<>(4);
        if (a.create()) permissions.add("CREATE");
        if (a.manage()) permissions.add("MANAGE");
        if (a.publish()) permissions.add("PUBLISH");
        if (a.review()) permissions.add("REVIEW");
        return Map.of("activeYn", a.active(), "permissions", permissions, "editRev", Long.toString(a.editRev()));
    }

    private static void checkInputs(UUID key, String verification, String approval, String actor) {
        if (key == null || !reference(verification) || !reference(approval) || !reference(actor))
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static boolean reference(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,64}");
    }

    private static long revision(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw AuthException.badRequest("INVALID_REVISION");
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw AuthException.badRequest("INVALID_REVISION"); }
    }

    private static boolean lockFailure(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ("55P03".equals(sql.getSQLState())
                    || "40P01".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    /** Read-only offline impact receipt; disclosure requires an independently signed preview approval. */
    public record Preview(UUID accountKey, String editRev, long ownedCount, long accessCount,
            List<AdminImpactService.Relation> relationSample, boolean truncated, String impactHash,
            String nextCredentialAction) {}

    /** Confirmed offline activation result; external access-block release remains a separate approval. */
    public record Result(UUID accountKey, String editRev, boolean changed, String auditStatus, UUID requestId,
            String nextCredentialAction) {}

    private record Account(long id, UUID key, boolean active, boolean create, boolean review, boolean publish,
            boolean manage, long editRev, long authRev, boolean enrolled, String mfaState) {}
}
