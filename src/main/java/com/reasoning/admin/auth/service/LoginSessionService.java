package com.reasoning.admin.auth.service;

import com.reasoning.admin.auth.security.AdminSecurityConfig.ActiveSessionVerifier;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.auth.service.TotpService;
import com.reasoning.common.auth.service.AuthModels.Authenticated;
import com.reasoning.common.auth.service.AuthModels.FlowCookie;
import com.reasoning.common.auth.service.AuthModels.LoginStart;
import com.reasoning.common.auth.service.AuthModels.Me;
import com.reasoning.common.auth.service.AuthModels.ReauthResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** SES-01 through SES-08. Framework persistence is deliberately outside both business transactions. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class LoginSessionService implements ActiveSessionVerifier {
    private static final Logger log = LoggerFactory.getLogger(LoginSessionService.class);
    private static final String MFA_COOKIE = "__Host-admin-mfa";
    private static final String LOGIN_ROUTE = "/admin/api/auth/login";
    private static final String MFA_ROUTE = "/admin/api/auth/login/mfa";
    private static final String REAUTH_ROUTE = "/admin/api/auth/reauth";
    private static final String LOGOUT_ROUTE = "/admin/api/auth/logout";
    private static final String ALL_ROUTE = "/admin/api/auth/logout-all";
    private final JdbcTemplate db;
    private final CryptoService crypto;
    private final TotpService totp;
    private final AdminSessionAdapter sessions;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    private final String dummyPasswordHash = passwords.encode("missing-account-password-check");

    public LoginSessionService(JdbcTemplate db, CryptoService crypto, TotpService totp,
            AdminSessionAdapter sessions, PlatformTransactionManager manager) {
        this.db = db;
        this.crypto = crypto;
        this.totp = totp;
        this.sessions = sessions;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** SES-01: verify a password and issue only a short-lived restricted MFA grant. */
    public LoginStart login(String loginId, String password, String existingMfaToken,
            String existingSessionId, String source, UUID requestId) {
        if (loginId == null || !loginId.matches("^[a-z0-9][a-z0-9._-]{3,39}$") || password == null
                || password.codePointCount(0, password.length()) > 128
                || source == null || source.isBlank()) throw AuthException.badRequest("INVALID_REQUEST");
        if (existingSessionId != null && sessions.findStoredPrincipal(existingSessionId)
                .filter(p -> isActive(existingSessionId, p)).isPresent()) throw AuthException.conflict("STATE_CONFLICT");
        if (existingMfaToken != null && statusIfPresent(existingMfaToken) != null)
            throw AuthException.conflict("STATE_CONFLICT");
        byte[] hash = crypto.loginHash(loginId);
        Account found = db.query("SELECT a.id,a.account_key,a.active_yn,a.can_manage,a.can_create,a.can_review,a.can_publish,"
                        + "c.auth_rev,c.password_hash,c.mfa_cipher,c.last_step,c.enrolled_at,c.mfa_state "
                        + "FROM admin_credential c JOIN admin_account a ON a.id=c.account_id "
                        + "WHERE c.login_hash=? AND c.search_key_ver=1", rs -> rs.next() ? account(rs) : null, hash);
        long accountId = found == null ? 0 : found.id();
        limit("PASSWORD", accountId, source, false);
        boolean correct = passwords.matches(password, found == null || found.passwordHash() == null
                ? dummyPasswordHash : found.passwordHash());
        if (!correct || !ready(found)) {
            limit("PASSWORD", accountId, source, true);
            denied("PASSWORD_VERIFIED", LOGIN_ROUTE, "POST", requestId, "AUTH_FAILED");
            throw AuthException.unauthorized("AUTH_FAILED");
        }
        String token = crypto.randomToken(32);
        UUID key = crypto.randomUuid();
        try {
            Instant expiry = tx.execute(s -> {
                Account a = lockAccount(accountId);
                byte[] currentHash = db.queryForObject("SELECT login_hash FROM admin_credential WHERE account_id=?",
                        (rs, row) -> rs.getBytes(1), accountId);
                if (!ready(a) || !crypto.constantEquals(hash, currentHash)
                        || !passwords.matches(password, a.passwordHash())) throw AuthException.unauthorized("AUTH_FAILED");
                Instant now = now();
                Instant until = now.plusSeconds(300);
                db.update("INSERT INTO admin_auth_grant (grant_key,account_id,purpose,delivery,token_hash,auth_rev,expires_at,created_at,updated_at) "
                        + "VALUES (?,?,'LOGIN_MFA','COOKIE',?,?,?, ?,?)", key, accountId,
                        crypto.tokenHash("LOGIN_MFA", token), a.rev(), until, now, now);
                audit("ANONYMOUS", null, accountId, "PASSWORD_VERIFIED", requestId, LOGIN_ROUTE,
                        "POST", 200, null, key);
                return until;
            });
            return new LoginStart("MFA_REQUIRED", expiry, new FlowCookie(MFA_COOKIE, token, expiry));
        } catch (AuthException e) {
            denied("PASSWORD_VERIFIED", LOGIN_ROUTE, "POST", requestId, e.code());
            throw e;
        }
    }

    /** SES-03: short status lookup; never extends the grant lifetime or app activity. */
    public LoginStart status(String token) {
        Instant expiry = statusIfPresent(token);
        if (expiry == null) throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        return new LoginStart("MFA_REQUIRED", expiry, null);
    }

    private Instant statusIfPresent(String token) {
        if (token == null || token.isBlank()) return null;
        return db.query("SELECT g.expires_at FROM admin_auth_grant g JOIN admin_account a ON a.id=g.account_id "
                + "JOIN admin_credential c ON c.account_id=a.id WHERE g.purpose='LOGIN_MFA' AND g.delivery='COOKIE' "
                + "AND g.token_hash=? AND g.consumed_at IS NULL AND g.revoked_at IS NULL AND g.expires_at>clock_timestamp() "
                + "AND g.auth_rev=c.auth_rev AND a.active_yn AND c.enrolled_at IS NOT NULL AND c.mfa_state='READY'",
                rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null, crypto.tokenHash("LOGIN_MFA", token));
    }

    /** SES-04: idempotently revoke only the presented pending grant. */
    public void cancel(String token, UUID requestId) {
        if (token == null || token.isBlank()) return;
        byte[] hash = crypto.tokenHash("LOGIN_MFA", token);
        try {
            revokeGrant(hash, requestId, true);
        } catch (RuntimeException failure) {
            try { revokeGrant(hash, requestId, false); }
            catch (RuntimeException unconfirmed) { throw AuthException.unavailable("REVOCATION_UNCONFIRMED"); }
        }
    }

    private void revokeGrant(byte[] hash, UUID requestId, boolean withAudit) {
        tx.executeWithoutResult(s -> {
            Long id = db.query("SELECT account_id FROM admin_auth_grant WHERE token_hash=? AND purpose='LOGIN_MFA'",
                    rs -> rs.next() ? rs.getLong(1) : null, hash);
            if (id == null) return;
            lockAccount(id);
            int changed = db.update("UPDATE admin_auth_grant SET token_hash=NULL,revoked_at=clock_timestamp(),updated_at=clock_timestamp() "
                    + "WHERE account_id=? AND token_hash=? AND consumed_at IS NULL AND revoked_at IS NULL", id, hash);
            if (changed != 0 && withAudit) audit("ANONYMOUS", null, id, "LOGIN_MFA_CANCELLED", requestId,
                    LOGIN_ROUTE, "DELETE", 204, null, null);
        });
    }

    /** SES-02: PENDING business commit, independent JDBC save, then ACTIVE business commit. */
    public Authenticated authenticate(String token, String code, String source, UUID requestId) {
        if (token == null || token.isBlank()) throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        if (source == null || source.isBlank()) throw AuthException.badRequest("INVALID_REQUEST");
        byte[] hash = crypto.tokenHash("LOGIN_MFA", token);
        Long id = db.query("SELECT account_id FROM admin_auth_grant WHERE token_hash=? AND purpose='LOGIN_MFA'",
                rs -> rs.next() ? rs.getLong(1) : null, hash);
        if (id == null) throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        limit("TOTP", id, source, false);
        AdminSessionAdapter.PreparedSession prepared = sessions.prepare();
        UUID key = crypto.randomUuid();
        Pending pending;
        try {
            pending = tx.execute(s -> {
                Account a = lockAccount(id);
                Grant grant = db.query("SELECT grant_key,auth_rev,expires_at FROM admin_auth_grant "
                                + "WHERE token_hash=? AND purpose='LOGIN_MFA' AND delivery='COOKIE' AND consumed_at IS NULL "
                                + "AND revoked_at IS NULL FOR UPDATE",
                        rs -> rs.next() ? new Grant((UUID) rs.getObject(1), rs.getLong(2), rs.getTimestamp(3).toInstant()) : null, hash);
                Instant now = now();
                if (!ready(a) || grant == null || grant.rev() != a.rev() || !now.isBefore(grant.expires()))
                    throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
                long step = totp.verify(secret(a), code, now, a.lastStep());
                db.update("UPDATE admin_credential SET last_step=?,updated_at=? WHERE account_id=?", step, now, id);
                db.update("UPDATE admin_auth_grant SET token_hash=NULL,consumed_at=?,updated_at=? WHERE grant_key=?",
                        now, now, grant.key());
                insertPending(key, a, prepared.id(), now, now, now, null);
                audit("ENROLLEE", id, id, "AUTH_VERIFIED", requestId, MFA_ROUTE, "POST", 200, key, grant.key());
                return new Pending(a.id(), a.key(), a.rev(), now.plusSeconds(28800));
            });
        } catch (AuthException e) {
            if ("INVALID_TOTP".equals(e.code())) limit("TOTP", id, source, true);
            denied("AUTH_VERIFIED", MFA_ROUTE, "POST", requestId, e.code());
            throw e;
        }
        AdminPrincipal principal = new AdminPrincipal(pending.id(), pending.accountKey(), key, pending.rev());
        activatePrepared(prepared, principal, null, requestId, MFA_ROUTE);
        return new Authenticated("AUTHENTICATED", pending.expiry(),
                new FlowCookie("__Host-admin-session", prepared.id(), pending.expiry()));
    }

    /** SES-05: read live DB authorization without changing lastActionAt. */
    public Me me(String sid, AdminPrincipal principal) {
        Snapshot snapshot = snapshot(sid, principal);
        if (snapshot == null) throw AuthException.unauthorized("AUTH_REQUIRED");
        List<String> permissions = new ArrayList<>();
        if (snapshot.account().canManage()) permissions.add("MANAGE");
        if (snapshot.account().canCreate()) permissions.add("CREATE");
        if (snapshot.account().canReview()) permissions.add("REVIEW");
        if (snapshot.account().canPublish()) permissions.add("PUBLISH");
        return new Me(principal.accountKey(), permissions, snapshot.expiry(), snapshot.lastAction().plusSeconds(1800),
                snapshot.reauth().plusSeconds(300));
    }

    /** Filter verifier: both independent framework storage and app authorization must match. */
    @Override
    public boolean isActive(String sid, AdminPrincipal principal) {
        return snapshot(sid, principal) != null;
    }

    /** SES-06: reauthenticate and rotate the framework ID, preserving the original absolute clock. */
    public ReauthResult reauth(String sid, AdminPrincipal principal, String password, String code,
            String source, UUID requestId) {
        if (source == null || source.isBlank()) throw AuthException.badRequest("INVALID_REQUEST");
        Snapshot old = snapshot(sid, principal);
        if (old == null) throw AuthException.unauthorized("AUTH_REQUIRED");
        limit("PASSWORD", principal.accountId(), source, false);
        limit("TOTP", principal.accountId(), source, false);
        boolean matched = passwords.matches(password == null ? "" : password, old.account().passwordHash());
        if (!matched) {
            limit("PASSWORD", principal.accountId(), source, true);
            denied("SESSION_REAUTHENTICATED", REAUTH_ROUTE, "POST", requestId, "AUTH_FAILED");
            throw AuthException.unauthorized("AUTH_FAILED");
        }
        AdminSessionAdapter.PreparedSession prepared = sessions.prepare();
        UUID key = crypto.randomUuid();
        Pending pending;
        try {
            pending = tx.execute(s -> {
                Account account = lockAccount(principal.accountId());
                Snapshot current = lockedSnapshot(sid, principal, account, true);
                if (current == null) throw AuthException.unauthorized("AUTH_REQUIRED");
                if (!passwords.matches(password, account.passwordHash())) throw AuthException.unauthorized("AUTH_FAILED");
                Instant now = now();
                long step = totp.verify(secret(account), code, now, account.lastStep());
                db.update("UPDATE admin_credential SET last_step=?,updated_at=? WHERE account_id=?", step, now, account.id());
                insertPending(key, account, prepared.id(), current.started(), now, current.lastAction(), principal.sessionKey());
                return new Pending(account.id(), account.key(), account.rev(), current.expiry());
            });
        } catch (AuthException e) {
            if ("INVALID_TOTP".equals(e.code())) limit("TOTP", principal.accountId(), source, true);
            denied("SESSION_REAUTHENTICATED", REAUTH_ROUTE, "POST", requestId, e.code());
            throw e;
        }
        activatePrepared(prepared, new AdminPrincipal(pending.id(), pending.accountKey(), key, pending.rev()),
                new AdminSessionAdapter.CurrentSession(sid, principal), requestId, REAUTH_ROUTE);
        try { sessions.delete(sid); } catch (RuntimeException ignored) { /* revoked app row denies old ID */ }
        return new ReauthResult(reauthExpiry(key), pending.expiry(),
                new FlowCookie("__Host-admin-session", prepared.id(), pending.expiry()));
    }

    /** SES-07: revoke current app authority even when mandatory success audit fails. */
    public void logout(String sid, AdminPrincipal principal, UUID requestId) {
        if (sid == null || principal == null) return;
        try {
            tx.executeWithoutResult(s -> {
                Account a = lockAccount(principal.accountId());
                Snapshot current = lockedSnapshot(sid, principal, a, true);
                if (current == null) return;
                db.update("UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp(),updated_at=clock_timestamp() "
                        + "WHERE session_key=? AND state='ACTIVE'", principal.sessionKey());
                audit("ADMIN", a.id(), a.id(), "SESSION_REVOKED", requestId, LOGOUT_ROUTE, "POST", 204,
                        principal.sessionKey(), null);
            });
        } catch (RuntimeException auditOrBusinessFailure) {
            try {
                int[] revoked = new int[1];
                tx.executeWithoutResult(s -> {
                    lockAccount(principal.accountId());
                    revoked[0] = db.update("UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp(),updated_at=clock_timestamp() "
                            + "WHERE session_key=? AND account_id=? AND state='ACTIVE' AND sid_hash=?",
                            principal.sessionKey(), principal.accountId(), crypto.sessionHash(sid));
                });
                log.error("Mandatory logout audit failed; emergency revocation committed; requestId={}, rows={}",
                        requestId, revoked[0]);
            } catch (RuntimeException failure) {
                log.error("Mandatory logout audit failed; emergency revocation unconfirmed; requestId={}", requestId);
                throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
            }
        }
        try { sessions.delete(sid); } catch (RuntimeException ignored) { /* app revocation is authoritative */ }
    }

    /** SES-08: whole-account revocation is serialized with login and session activation. */
    public void logoutAll(String sid, AdminPrincipal principal, UUID requestId) {
        tx.executeWithoutResult(s -> {
            Account a = lockAccount(principal.accountId());
            Snapshot current = lockedSnapshot(sid, principal, a, true);
            if (current == null) throw AuthException.unauthorized("AUTH_REQUIRED");
            if (!now().isBefore(current.reauth().plusSeconds(300))) throw AuthException.forbidden("REAUTH_REQUIRED");
            if (a.rev() == Long.MAX_VALUE) throw AuthException.conflict("STATE_CONFLICT");
            db.update("UPDATE admin_credential SET auth_rev=auth_rev+1,updated_at=clock_timestamp() WHERE account_id=?", a.id());
            audit("ADMIN", a.id(), a.id(), "ACCOUNT_SESSIONS_REVOKED", requestId, ALL_ROUTE, "POST", 204,
                    principal.sessionKey(), null);
        });
        try { sessions.delete(sid); } catch (RuntimeException ignored) { /* revision rejects every old ID */ }
    }

    private void activatePrepared(AdminSessionAdapter.PreparedSession prepared, AdminPrincipal principal,
            AdminSessionAdapter.CurrentSession replaced, UUID requestId, String route) {
        try {
            sessions.save(prepared, principal);
            tx.executeWithoutResult(s -> {
                Account a = lockAccount(principal.accountId());
                UUID previous = replaced == null ? null : replaced.principal().sessionKey();
                // Lock both session rows in key order, including the replaced session.
                List<UUID> keys = new ArrayList<>(List.of(principal.sessionKey()));
                if (previous != null) keys.add(previous);
                keys.sort(UUID::compareTo);
                for (UUID key : keys) db.query("SELECT session_key FROM admin_session WHERE session_key=? FOR UPDATE",
                        rs -> { while (rs.next()) { } return null; }, key);
                Instant now = now();
                PendingRow row = db.query("SELECT auth_rev,created_at,replaces_key FROM admin_session WHERE session_key=? "
                                + "AND account_id=? AND sid_hash=? AND state='PENDING'",
                        rs -> rs.next() ? new PendingRow(rs.getLong(1), rs.getTimestamp(2).toInstant(),
                                (UUID) rs.getObject(3)) : null, principal.sessionKey(), a.id(), crypto.sessionHash(prepared.id()));
                if (!ready(a) || row == null || row.rev() != a.rev() || row.rev() != principal.authRev()
                        || !now.isBefore(row.created().plusSeconds(60)) || !java.util.Objects.equals(previous, row.replaces())
                        || !principal.equals(sessions.findStoredPrincipal(prepared.id()).orElse(null))
                        || (replaced != null && lockedSnapshot(replaced.id(), replaced.principal(), a, false) == null))
                    throw AuthException.unavailable("AUTH_UNAVAILABLE");
                db.update("UPDATE admin_session SET state='ACTIVE',activated_at=?,updated_at=? WHERE session_key=?", now, now, principal.sessionKey());
                if (previous != null) db.update("UPDATE admin_session SET state='REVOKED',revoked_at=?,updated_at=? "
                        + "WHERE session_key=? AND state='ACTIVE'", now, now, previous);
                audit("ADMIN", a.id(), a.id(), previous == null ? "SESSION_ACTIVATED" : "SESSION_REAUTHENTICATED",
                        requestId, route, "POST", 200, principal.sessionKey(), null);
            });
        } catch (RuntimeException failure) {
            try {
                tx.executeWithoutResult(s -> {
                    lockAccount(principal.accountId());
                    db.update("UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp(),updated_at=clock_timestamp() "
                            + "WHERE session_key=? AND state='PENDING'", principal.sessionKey());
                });
            } catch (RuntimeException ignored) { /* PENDING never authorizes and expires */ }
            try { sessions.delete(prepared.id()); } catch (RuntimeException ignored) { /* PENDING never authorizes */ }
            try { denied(replaced == null ? "SESSION_ACTIVATED" : "SESSION_REAUTHENTICATED",
                    route, "POST", requestId, "AUTH_UNAVAILABLE"); }
            catch (RuntimeException ignored) { /* an unavailable audit store cannot activate a session */ }
            throw AuthException.unavailable("AUTH_UNAVAILABLE");
        }
    }

    private Snapshot snapshot(String sid, AdminPrincipal p) {
        if (sid == null || p == null || !p.equals(sessions.findStoredPrincipal(sid).orElse(null))) return null;
        return db.query("SELECT a.id,a.account_key,a.active_yn,a.can_manage,a.can_create,a.can_review,a.can_publish,"
                        + "c.auth_rev,c.password_hash,c.mfa_cipher,c.last_step,c.enrolled_at,c.mfa_state,"
                        + "s.started_at,s.last_action_at,s.expires_at,s.reauth_at FROM admin_account a "
                        + "JOIN admin_credential c ON c.account_id=a.id JOIN admin_session s ON s.account_id=a.id "
                        + "WHERE s.session_key=? AND s.sid_hash=? AND s.state='ACTIVE' AND s.auth_rev=c.auth_rev "
                        + "AND c.auth_rev=? AND a.id=? AND a.account_key=? AND a.active_yn "
                        + "AND c.enrolled_at IS NOT NULL AND c.mfa_state='READY' "
                        + "AND s.expires_at>clock_timestamp() AND s.last_action_at+interval '30 minutes'>clock_timestamp()",
                rs -> rs.next() ? new Snapshot(account(rs), rs.getTimestamp(14).toInstant(),
                        rs.getTimestamp(15).toInstant(), rs.getTimestamp(16).toInstant(),
                        rs.getTimestamp(17).toInstant()) : null,
                p.sessionKey(), crypto.sessionHash(sid), p.authRev(), p.accountId(), p.accountKey());
    }

    private Snapshot lockedSnapshot(String sid, AdminPrincipal p, Account a, boolean lock) {
        if (sid == null || p == null || a == null || !ready(a) || a.id() != p.accountId()
                || !a.key().equals(p.accountKey()) || a.rev() != p.authRev()
                || !p.equals(sessions.findStoredPrincipal(sid).orElse(null))) return null;
        String sql = "SELECT started_at,last_action_at,expires_at,reauth_at FROM admin_session WHERE session_key=? "
                + "AND account_id=? AND sid_hash=? AND auth_rev=? AND state='ACTIVE' "
                + "AND expires_at>clock_timestamp() AND last_action_at+interval '30 minutes'>clock_timestamp()"
                + (lock ? " FOR UPDATE" : "");
        return db.query(sql, rs -> rs.next() ? new Snapshot(a, rs.getTimestamp(1).toInstant(),
                rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()) : null,
                p.sessionKey(), a.id(), crypto.sessionHash(sid), a.rev());
    }

    private Account lockAccount(long id) {
        Account a = db.query("SELECT id,account_key,active_yn,can_manage,can_create,can_review,can_publish "
                + "FROM admin_account WHERE id=? FOR UPDATE", rs -> rs.next()
                ? new Account(rs.getLong(1), (UUID) rs.getObject(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), 0, null, null, null, null, null) : null, id);
        if (a == null) throw AuthException.unauthorized("AUTH_REQUIRED");
        return db.query("SELECT auth_rev,password_hash,mfa_cipher,last_step,enrolled_at,mfa_state "
                        + "FROM admin_credential WHERE account_id=? FOR UPDATE", rs -> rs.next()
                        ? new Account(a.id(), a.key(), a.active(), a.canManage(), a.canCreate(), a.canReview(),
                                a.canPublish(), rs.getLong(1), rs.getString(2), rs.getString(3),
                                (Long) rs.getObject(4), instant(rs, 5), rs.getString(6)) : null, id);
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(rs.getLong(1), (UUID) rs.getObject(2), rs.getBoolean(3), rs.getBoolean(4),
                rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), rs.getLong(8), rs.getString(9),
                rs.getString(10), (Long) rs.getObject(11), instant(rs, 12), rs.getString(13));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean ready(Account a) {
        return a != null && a.active() && a.enrolled() != null && "READY".equals(a.mfaState())
                && a.passwordHash() != null && a.mfaCipher() != null;
    }

    private String secret(Account a) {
        return crypto.decrypt(a.mfaCipher(), "admin-account/" + a.key() + "/mfa/v1");
    }

    private void insertPending(UUID key, Account a, String sid, Instant started, Instant reauth,
            Instant lastAction, UUID replaces) {
        db.update("INSERT INTO admin_session (session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,"
                        + "expires_at,reauth_at,replaces_key,created_at,updated_at) VALUES (?,?,?,?,'PENDING',?,?,"
                        + "cast(? as timestamptz) + interval '8 hours',?,?,?,?)", key, a.id(), crypto.sessionHash(sid), a.rev(),
                started, lastAction, started, reauth, replaces, reauth, reauth);
    }

    private Instant now() {
        return db.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    private Instant reauthExpiry(UUID key) {
        return db.queryForObject("SELECT reauth_at + interval '5 minutes' FROM admin_session WHERE session_key=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), key);
    }

    /** Rate limits use independent transactions so a failed business transaction cannot erase a failure count. */
    private void limit(String action, long accountId, String source, boolean failure) {
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(new Bucket("SOURCE", crypto.limitHash(action, source)));
        if (accountId > 0) buckets.add(new Bucket("ACCOUNT", crypto.limitHash(action, Long.toString(accountId))));
        buckets.sort((a, b) -> a.kind().compareTo(b.kind()));
        tx.executeWithoutResult(s -> {
            for (Bucket b : buckets) {
                db.update("INSERT INTO admin_auth_limit (action,bucket_kind,bucket_hash,window_at) "
                        + "VALUES (?,?,?,clock_timestamp()) ON CONFLICT DO NOTHING", action, b.kind(), b.hash());
                Limit state = db.query("SELECT window_at,fail_count,blocked_until FROM admin_auth_limit "
                        + "WHERE action=? AND bucket_kind=? AND bucket_hash=? FOR UPDATE",
                        rs -> rs.next() ? new Limit(instant(rs, 1), rs.getInt(2), instant(rs, 3)) : null,
                        action, b.kind(), b.hash());
                Instant time = now();
                if (state.blocked() != null && !time.isBefore(state.blocked())) state = new Limit(time, 0, null);
                if (time.isBefore(state.window().plusSeconds(900)) && state.blocked() != null)
                    throw new AuthException(429, "RATE_LIMITED", "RATE_LIMITED");
                int count = time.isBefore(state.window().plusSeconds(900)) ? state.count() : 0;
                if (failure) count = Math.min(5, count + 1);
                db.update("UPDATE admin_auth_limit SET window_at=?,fail_count=?,blocked_until=?,updated_at=? "
                        + "WHERE action=? AND bucket_kind=? AND bucket_hash=?",
                        time.isBefore(state.window().plusSeconds(900)) ? state.window() : time,
                        count, count == 5 ? time.plusSeconds(900) : null, time, action, b.kind(), b.hash());
            }
        });
    }

    private void audit(String kind, Long actor, Long target, String action, UUID requestId,
            String route, String method, int status, UUID sessionKey, UUID grantKey) {
        db.update("INSERT INTO admin_auth_audit (actor_kind,actor_id,target_id,action,outcome,request_id,route,method,"
                        + "http_status,session_key,grant_key) VALUES (?,?,?,?,'COMMITTED',?,?,?,?,?,?)",
                kind, actor, target, action, requestId, route, method, status, sessionKey, grantKey);
    }

    private void denied(String action, String route, String method, UUID requestId, String reason) {
        tx.executeWithoutResult(s -> db.update("INSERT INTO admin_auth_audit (actor_kind,action,outcome,reason_code,"
                        + "request_id,route,method,http_status) VALUES ('ANONYMOUS',?,?,?,?,?, ?,?)",
                action, "AUTH_UNAVAILABLE".equals(reason) ? "FAILED" : "DENIED",
                reason, requestId, route, method, switch (reason) {
                    case "INVALID_TOTP" -> 422;
                    case "AUTH_UNAVAILABLE", "REVOCATION_UNCONFIRMED" -> 503;
                    case "STATE_CONFLICT" -> 409;
                    default -> 401;
                }));
    }

    private record Account(long id, UUID key, boolean active, boolean canManage, boolean canCreate,
            boolean canReview, boolean canPublish, long rev, String passwordHash, String mfaCipher,
            Long lastStep, Instant enrolled, String mfaState) {}
    private record Grant(UUID key, long rev, Instant expires) {}
    private record Pending(long id, UUID accountKey, long rev, Instant expiry) {}
    private record PendingRow(long rev, Instant created, UUID replaces) {}
    private record Snapshot(Account account, Instant started, Instant lastAction, Instant expiry, Instant reauth) {}
    private record Bucket(String kind, byte[] hash) {}
    private record Limit(Instant window, int count, Instant blocked) {}
}
