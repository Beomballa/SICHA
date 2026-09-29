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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Non-web ADM-OFFLINE-01 emergency revocation; external network blocks are managed separately. */
@Service
@ConditionalOnNotWebApplication
public final class AdminOfflineBlockService {
    private static final Logger log = LoggerFactory.getLogger(AdminOfflineBlockService.class);
    private final JdbcTemplate db;
    private final ObjectProvider<ApprovalVerifier> approvals;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public AdminOfflineBlockService(JdbcTemplate db, ObjectProvider<ApprovalVerifier> approvals,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db;
        this.approvals = approvals;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Blocks an administrator after exact revision-bound independent approval, including the last manager.
     * @param accountKey exact target UUID, never an authorization credential
     * @param expectedRev positive canonical decimal edit revision
     * @param verificationRef nonpersonal identity-check reference of 8–64 safe characters
     * @param approvalRef independently signed approval reference of 8–64 safe characters
     * @param actorRef responsible offline operator reference of 8–64 safe characters
     * @param requestId correlation UUID generated for this execution
     * @return confirmed revocation status; UNCONFIRMED means the block committed without mandatory audit
     * @throws AuthException when approval is absent, revision is stale, or DB revocation cannot be confirmed
     */
    public BlockResult block(UUID accountKey, String expectedRev, String verificationRef,
            String approvalRef, String actorRef, UUID requestId) {
        if (accountKey == null || requestId == null || !reference(verificationRef)
                || !reference(approvalRef) || !reference(actorRef)) throw AuthException.badRequest("INVALID_REQUEST");
        long revision = revision(expectedRev);
        try {
            return apply(accountKey, revision, verificationRef, approvalRef, actorRef, requestId, true);
        } catch (AuditFailure failure) {
            try {
                BlockResult result = apply(accountKey, revision, verificationRef, approvalRef, actorRef, requestId, false);
                if (result.changed()) log.error("Emergency block committed without mandatory audit; requestId={}", requestId);
                return result;
            } catch (AuthException failureInBlock) {
                if (failureInBlock.status() == 503) {
                    log.error("Emergency block unconfirmed after audit failure; requestId={}", requestId);
                    throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
                }
                throw failureInBlock;
            } catch (RuntimeException failureInBlock) {
                log.error("Emergency block unconfirmed after audit failure; requestId={}", requestId);
                throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
            }
        } catch (DataAccessException failure) {
            if (lockFailure(failure)) throw AuthException.unavailable("ADMIN_CHANGE_BUSY");
            throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
        }
    }

    private BlockResult apply(UUID key, long expected, String verification, String approval,
            String actor, UUID requestId, boolean audited) {
        return tx.execute(status -> {
            db.execute("SET LOCAL lock_timeout = '5s'");
            db.execute("SELECT pg_advisory_xact_lock(821,1)");
            Long id = db.query("SELECT id FROM admin_account WHERE account_key=?",
                    rs -> rs.next() ? rs.getLong(1) : null, key);
            if (id == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
            db.queryForObject("SELECT id FROM admin_account WHERE id=? FOR UPDATE", Long.class, id);
            Account before = account(id);
            if (!before.key().equals(key)) throw AuthException.conflict("STATE_CONFLICT");
            if (!before.enrolled()) db.query("SELECT id FROM admin_enrollment WHERE account_id=? FOR UPDATE",
                    rs -> { while (rs.next()) { } return null; }, id);
            db.queryForObject("SELECT account_id FROM admin_credential WHERE account_id=? FOR UPDATE", Long.class, id);
            List<UUID> sessions = db.query("SELECT session_key FROM admin_session WHERE account_id=? ORDER BY session_key",
                    (rs, row) -> (UUID) rs.getObject(1), id);
            for (UUID session : sessions)
                db.queryForObject("SELECT session_key FROM admin_session WHERE session_key=? FOR UPDATE", UUID.class, session);
            List<UUID> grants = db.query("SELECT grant_key FROM admin_auth_grant WHERE account_id=? ORDER BY grant_key",
                    (rs, row) -> (UUID) rs.getObject(1), id);
            for (UUID grant : grants)
                db.queryForObject("SELECT grant_key FROM admin_auth_grant WHERE grant_key=? FOR UPDATE", UUID.class, grant);
            before = account(id);
            if (before.editRev() != expected) throw AuthException.conflict("STATE_CONFLICT");
            ApprovalVerifier verifier = approvals.getIfAvailable();
            if (verifier == null || !verifier.offlineApproved(key, "ADMIN_BLOCK_REV_" + expected,
                    verification, approval, actor)) throw AuthException.forbidden("OFFLINE_APPROVAL_REQUIRED");
            if (!before.active()) return new BlockResult(key, Long.toString(before.editRev()), false, "NOT_REQUIRED", requestId);
            if (before.editRev() == Long.MAX_VALUE || before.authRev() == Long.MAX_VALUE)
                throw AuthException.conflict("STATE_CONFLICT");
            Instant now = db.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
            db.update("UPDATE admin_account SET active_yn=false,edit_rev=edit_rev+1,updated_at=? WHERE id=?", now, id);
            if (!before.enrolled()) {
                db.update("UPDATE admin_enrollment SET code_hash=NULL,grant_hash=NULL,revoked_at=COALESCE(revoked_at,?),"
                        + "updated_at=? WHERE account_id=? AND completed_at IS NULL", now, now, id);
                db.update("UPDATE admin_credential SET password_hash=NULL,mfa_cipher=NULL,mfa_verified_at=NULL,"
                        + "last_step=NULL,auth_rev=auth_rev+1,updated_at=? WHERE account_id=?", now, id);
            } else {
                db.update("UPDATE admin_credential SET auth_rev=auth_rev+1,updated_at=? WHERE account_id=?", now, id);
            }
            db.update("UPDATE admin_session SET state='REVOKED',revoked_at=?,updated_at=? WHERE account_id=? "
                    + "AND state IN ('ACTIVE','PENDING')", now, now, id);
            db.update("UPDATE admin_auth_grant SET token_hash=NULL,mfa_cipher=NULL,mfa_verified_at=NULL,"
                    + "last_step=NULL,revoked_at=?,updated_at=? WHERE account_id=? AND consumed_at IS NULL "
                    + "AND revoked_at IS NULL", now, now, id);
            if (audited) {
                try { audit(id, before, account(id), verification, actor, requestId); }
                catch (DataAccessException failure) { throw new AuditFailure(failure); }
            }
            return new BlockResult(key, Long.toString(expected + 1), true,
                    audited ? "RECORDED" : "UNCONFIRMED", requestId);
        });
    }

    private Account account(long id) {
        return db.query("SELECT a.account_key,a.active_yn,a.can_create,a.can_review,a.can_publish,a.can_manage,"
                + "a.edit_rev,c.auth_rev,c.enrolled_at FROM admin_account a JOIN admin_credential c "
                + "ON c.account_id=a.id WHERE a.id=?", rs -> {
                    if (!rs.next()) throw AuthException.conflict("STATE_CONFLICT");
                    return new Account((UUID) rs.getObject(1), rs.getBoolean(2), rs.getBoolean(3),
                            rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getLong(7),
                            rs.getLong(8), rs.getTimestamp(9) != null);
                }, id);
    }

    private void audit(long id, Account before, Account after, String verification, String actor, UUID requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", state(before));
        data.put("after", state(after));
        data.put("reasonCode", "INCIDENT");
        data.put("verificationRef", verification);
        String payload;
        try { payload = json.writeValueAsString(data); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid emergency block audit", failure); }
        if (payload.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalStateException("Emergency block audit exceeds size limit");
        db.update("INSERT INTO admin_auth_audit(actor_kind,actor_ref,target_id,action,outcome,request_id,route,method,change_data) "
                + "VALUES ('OFFLINE',?,?,"
                + "'ACCOUNT_EMERGENCY_BLOCKED','COMMITTED',?,'ADM-OFFLINE-01','OFFLINE',?::jsonb)",
                actor, id, requestId, payload);
    }

    private static Map<String, Object> state(Account a) {
        List<String> permissions = new ArrayList<>(4);
        if (a.create()) permissions.add("CREATE");
        if (a.manage()) permissions.add("MANAGE");
        if (a.publish()) permissions.add("PUBLISH");
        if (a.review()) permissions.add("REVIEW");
        return Map.of("activeYn", a.active(), "permissions", permissions, "editRev", Long.toString(a.editRev()));
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

    public record BlockResult(UUID accountKey, String editRev, boolean changed, String auditStatus, UUID requestId) {}
    private record Account(UUID key, boolean active, boolean create, boolean review, boolean publish,
            boolean manage, long editRev, long authRev, boolean enrolled) {}
    private static final class AuditFailure extends RuntimeException {
        AuditFailure(DataAccessException cause) { super(cause); }
    }
}
