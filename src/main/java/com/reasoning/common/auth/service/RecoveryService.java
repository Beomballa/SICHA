package com.reasoning.common.auth.service;

import com.reasoning.common.auth.service.AuthModels.FlowCookie;
import com.reasoning.common.auth.service.AuthModels.MfaSetup;
import com.reasoning.common.auth.service.AuthModels.RecoveryExchange;
import com.reasoning.common.auth.service.AuthModels.RecoveryIssue;
import com.reasoning.common.auth.service.AuthModels.RecoveryStage;
import com.reasoning.common.auth.service.AuthModels.SessionPrincipal;
import com.reasoning.common.auth.service.AuthModels.SimpleResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Transactional recovery operations. HTTP/CLI adapters must provide CSRF and controlled secret
 * delivery.
 */
@Service
public class RecoveryService {
    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);
    private static final String COOKIE = "__Host-admin-recovery";
    private static final String PASSWORD = "PASSWORD_RESET";
    private static final String MFA = "MFA_RECOVERY";
    private static final String CODE_HASH = "recovery-issue-code";
    private static final String COOKIE_HASH = "recovery-cookie";
    private static final String RECOVERY_CODE_HASH = "admin-recovery-code:";
    private static final String GRANT_SQL =
            "select"
                + " grant_key,account_id,purpose,delivery,token_hash,auth_rev,expires_at,allow_inactive,mfa_cipher,mfa_verified_at,last_step,consumed_at,revoked_at"
                + " from admin_auth_grant where token_hash=?";
    private final JdbcTemplate db;
    private final CryptoService crypto;
    private final TotpService totp;
    private final BreachedPasswordChecker breachedPasswords;
    private final TransactionTemplate tx;
    private final TransactionTemplate limitTx;
    private final ObjectProvider<ApprovalVerifier> approvals;
    private final Argon2PasswordEncoder passwords =
            new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    private final SecureRandom random = new SecureRandom();

    /**
     * A separately implemented trusted approval source, not a request flag or a self-asserted
     * reference.
     */
    public interface ApprovalVerifier {
        boolean offlineApproved(
                UUID accountKey,
                String purpose,
                String verificationRef,
                String approvalRef,
                String actorRef);
    }

    public RecoveryService(
            JdbcTemplate db,
            CryptoService crypto,
            TotpService totp,
            BreachedPasswordChecker breachedPasswords,
            PlatformTransactionManager manager,
            ObjectProvider<ApprovalVerifier> approvals) {
        this.db = db;
        this.crypto = crypto;
        this.totp = totp;
        this.breachedPasswords = breachedPasswords;
        this.approvals = approvals;
        this.tx = new TransactionTemplate(manager);
        this.limitTx = new TransactionTemplate(manager);
        this.limitTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Issues a ten-minute purpose-bound code only after live session, MANAGE, reauth and external
     * identity verification.
     */
    public RecoveryIssue issue(
            SessionPrincipal actor,
            String sessionCookie,
            UUID targetKey,
            String purpose,
            String verificationRef,
            UUID requestId) {
        purpose(purpose);
        reference(verificationRef);
        if (actor == null || targetKey == null || sessionCookie == null)
            throw AuthException.unauthorized("AUTH_REQUIRED");
        return tx.execute(
                s -> {
                    Long targetId =
                            db.query(
                                    "select id from admin_account where account_key=?",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    targetKey);
                    if (targetId == null) throw AuthException.conflict("STATE_CONFLICT");
                    lockAccounts(actor.accountId(), targetId);
                    operator(actor, sessionCookie);
                    Account target = account(targetId);
                    eligible(target, false);
                    // The authorized operator attests to the separately performed identity check.
                    // verificationRef is an audit reference, not machine-proof of identity.
                    return issueLocked(
                            target,
                            purpose,
                            actor.accountId(),
                            verificationRef,
                            false,
                            "ADMIN",
                            null,
                            requestId,
                            null);
                });
    }

    /**
     * Offline-only issuance; no caller-supplied approval flag substitutes for a trusted approval
     * verifier.
     */
    public RecoveryIssue issueOffline(
            UUID targetKey,
            String purpose,
            String verificationRef,
            String approvalRef,
            String actorRef,
            UUID requestId) {
        purpose(purpose);
        reference(verificationRef);
        reference(approvalRef);
        reference(actorRef);
        if (targetKey == null) throw AuthException.badRequest("INVALID_ACCOUNT");
        return tx.execute(
                s -> {
                    Long id =
                            db.query(
                                    "select id from admin_account where account_key=?",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    targetKey);
                    if (id == null) throw AuthException.conflict("STATE_CONFLICT");
                    lockAccounts(id);
                    Account target = account(id);
                    eligible(target, true);
                    ApprovalVerifier verifier = approvals.getIfAvailable();
                    if (verifier == null
                            || !verifier.offlineApproved(
                                    targetKey, purpose, verificationRef, approvalRef, actorRef))
                        throw AuthException.forbidden("OFFLINE_APPROVAL_REQUIRED");
                    Boolean used =
                            db.queryForObject(
                                    "select exists(select 1 from admin_auth_audit where target_id=?"
                                        + " and actor_kind='OFFLINE' and action='RECOVERY_ISSUED'"
                                        + " and change_data->>'approvalRef'=?)",
                                    Boolean.class,
                                    id,
                                    approvalRef);
                    if (Boolean.TRUE.equals(used))
                        throw AuthException.forbidden("OFFLINE_APPROVAL_USED");
                    return issueLocked(
                            target,
                            purpose,
                            null,
                            verificationRef,
                            !target.active,
                            "OFFLINE",
                            actorRef,
                            requestId,
                            approvalRef);
                });
    }

    /**
     * Exchanges a single CODE for a single COOKIE without extending the code's original lifetime.
     */
    public RecoveryExchange exchange(
            String code, String purpose, String trustedSource, UUID requestId) {
        purpose(purpose);
        Grant candidate =
                code != null && code.matches("[A-Za-z0-9_-]{43}")
                        ? grant(crypto.tokenHash(CODE_HASH + ":" + purpose, code))
                        : null;
        if (!rate(
                        List.of(
                                new RateCheck(
                                        "EXCHANGE",
                                        candidate == null ? null : candidate.accountId,
                                        () ->
                                                candidate != null
                                                        && candidate.purpose.equals(purpose)
                                                        && candidate.delivery.equals("CODE")
                                                        && candidate.revokedAt == null
                                                        && candidate.consumedAt == null
                                                        && clock().isBefore(candidate.expires))),
                        trustedSource)
                .getFirst()) throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        return tx.execute(
                s -> {
                    if (MFA.equals(purpose)) globalLock();
                    Grant found = grant(crypto.tokenHash(CODE_HASH + ":" + purpose, code));
                    if (found == null
                            || !found.purpose.equals(purpose)
                            || !found.delivery.equals("CODE"))
                        throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
                    lockAccounts(found.accountId);
                    Account account = account(found.accountId);
                    Grant g = lockGrant(found.key);
                    check(g, account, purpose, "CODE", found.hash);
                    String token = crypto.randomToken(32);
                    long revision = account.revision;
                    if (MFA.equals(purpose)) {
                        if (!account.state.equals("READY") && !account.state.equals("RECOVERY"))
                            throw AuthException.conflict("STATE_CONFLICT");
                        revision = nextRevision(account);
                        db.update(
                                "update admin_credential set"
                                    + " auth_rev=?,mfa_state='RECOVERY',updated_at=clock_timestamp()"
                                    + " where account_id=?",
                                revision,
                                account.id);
                        revokeSessions(account.id);
                        revokeGrants(account.id, g.key);
                    }
                    db.update(
                            "update admin_auth_grant set"
                                + " delivery='COOKIE',token_hash=?,auth_rev=?,updated_at=clock_timestamp()"
                                + " where grant_key=?",
                            crypto.tokenHash(COOKIE_HASH + ":" + purpose, token),
                            revision,
                            g.key);
                    audit(
                            "ANONYMOUS",
                            null,
                            null,
                            account.id,
                            g.key,
                            "RECOVERY_EXCHANGED",
                            requestId,
                            "POST /admin/api/auth/recovery/exchange",
                            "POST",
                            200);
                    return new RecoveryExchange(
                            purpose,
                            "PASSWORD_RESET".equals(purpose)
                                    ? "PASSWORD_REQUIRED"
                                    : "MFA_SETUP_REQUIRED",
                            g.expires,
                            new FlowCookie(COOKIE, token, g.expires));
                });
    }

    /**
     * Reads only the purpose and stage of the still-current recovery cookie; never returns an MFA
     * secret.
     */
    public RecoveryStage stage(String token) {
        return tx.execute(
                s -> {
                    Grant found = cookie(token);
                    lockAccounts(found.accountId);
                    Account account = account(found.accountId);
                    Grant g = lockGrant(found.key);
                    check(g, account, g.purpose, "COOKIE", found.hash);
                    return new RecoveryStage(g.purpose, stage(g), g.expires);
                });
    }

    /**
     * Resets the password, leaving the credential MFA fields intact and revoking all old sessions
     * and grants.
     */
    public SimpleResult resetPassword(String token, String password, UUID requestId) {
        Grant candidate = cookie(token);
        check(candidate, account(candidate.accountId), PASSWORD, "COOKIE", candidate.hash);
        validatePassword(password);
        String hash = passwords.encode(password);
        return tx.execute(
                s -> {
                    Grant found = cookie(token);
                    lockAccounts(found.accountId);
                    Account account = account(found.accountId);
                    Grant g = lockGrant(found.key);
                    check(g, account, PASSWORD, "COOKIE", found.hash);
                    long revision = nextRevision(account);
                    db.update(
                            "update admin_credential set"
                                + " password_hash=?,auth_rev=?,updated_at=clock_timestamp() where"
                                + " account_id=?",
                            hash,
                            revision,
                            account.id);
                    revokeSessions(account.id);
                    revokeGrants(account.id, null);
                    audit(
                            "ANONYMOUS",
                            null,
                            null,
                            account.id,
                            g.key,
                            "PASSWORD_RESET",
                            requestId,
                            "POST /admin/api/auth/recovery/password",
                            "POST",
                            200);
                    return new SimpleResult("RESET", "LOGIN");
                });
    }

    /**
     * Starts self recovery only with the current password and an unused 128-bit account-bound
     * recovery code.
     */
    public RecoveryExchange startMfa(
            String loginId,
            String password,
            String recoveryCode,
            String trustedSource,
            UUID requestId) {
        Long id =
                loginId != null && loginId.matches("[a-z0-9][a-z0-9._-]{3,39}")
                        ? db.query(
                                "select account_id from admin_credential where login_hash=?",
                                rs -> rs.next() ? rs.getLong(1) : null,
                                crypto.loginHash(loginId))
                        : null;
        boolean validFormat =
                recoveryCode != null
                        && recoveryCode.matches("[A-Z2-7]{26}")
                        && "AEIMQUY4".indexOf(recoveryCode.charAt(25)) >= 0;
        byte[] codeHash =
                validFormat && id != null
                        ? crypto.tokenHash(RECOVERY_CODE_HASH + id, recoveryCode)
                        : null;
        String oldHash =
                id == null
                        ? null
                        : db.query(
                                "select password_hash from admin_credential where account_id=?",
                                rs -> rs.next() ? rs.getString(1) : null,
                                id);
        List<Boolean> checks =
                rate(
                        List.of(
                                new RateCheck(
                                        "PASSWORD",
                                        id,
                                        () ->
                                                password != null
                                                        && oldHash != null
                                                        && passwords.matches(password, oldHash)),
                                new RateCheck(
                                        "RECOVERY_CODE",
                                        id,
                                        () ->
                                                codeHash != null
                                                        && db.query(
                                                                "select code_hash from"
                                                                    + " admin_recovery_code where"
                                                                    + " account_id=? and"
                                                                    + " code_hash=? and consumed_at"
                                                                    + " is null and revoked_at is"
                                                                    + " null",
                                                                rs ->
                                                                        rs.next()
                                                                                && crypto
                                                                                        .constantEquals(
                                                                                                codeHash,
                                                                                                rs
                                                                                                        .getBytes(
                                                                                                                1)),
                                                                id,
                                                                codeHash))),
                        trustedSource);
        if (!checks.get(0) || !checks.get(1))
            throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        final long accountId = id;
        return tx.execute(
                s -> {
                    globalLock();
                    lockAccounts(accountId);
                    Account account = account(accountId);
                    eligible(account, false);
                    if (!account.state.equals("READY")
                            || !passwords.matches(password, account.passwordHash))
                        throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
                    Long codeId =
                            db.query(
                                    "select id from admin_recovery_code where account_id=? and"
                                        + " code_hash=? and consumed_at is null and revoked_at is"
                                        + " null for update",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    accountId,
                                    codeHash);
                    if (codeId == null
                            || !crypto.constantEquals(
                                    codeHash,
                                    db.queryForObject(
                                            "select code_hash from admin_recovery_code where id=?",
                                            byte[].class,
                                            codeId)))
                        throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
                    Instant now = clock();
                    db.update(
                            "update admin_recovery_code set consumed_at=? where id=?",
                            Timestamp.from(now),
                            codeId);
                    revokeGrants(accountId, null);
                    long revision = nextRevision(account);
                    db.update(
                            "update admin_credential set"
                                + " auth_rev=?,mfa_state='RECOVERY',updated_at=clock_timestamp()"
                                + " where account_id=?",
                            revision,
                            accountId);
                    revokeSessions(accountId);
                    String token = crypto.randomToken(32);
                    UUID key = crypto.randomUuid();
                    Instant expires = now.plus(Duration.ofMinutes(10));
                    db.update(
                            "insert into"
                                + " admin_auth_grant(grant_key,account_id,purpose,delivery,token_hash,auth_rev,expires_at)"
                                + " values (?,?,'MFA_RECOVERY','COOKIE',?,?,?)",
                            key,
                            accountId,
                            crypto.tokenHash(COOKIE_HASH + ":" + MFA, token),
                            revision,
                            Timestamp.from(expires));
                    audit(
                            "ANONYMOUS",
                            null,
                            null,
                            accountId,
                            key,
                            "MFA_RECOVERY_STARTED",
                            requestId,
                            "POST /admin/api/auth/recovery/mfa/start",
                            "POST",
                            200);
                    return new RecoveryExchange(
                            MFA,
                            "MFA_SETUP_REQUIRED",
                            expires,
                            new FlowCookie(COOKIE, token, expires));
                });
    }

    /**
     * 교체 대기 중인 MFA 비밀과 시차 등록 URI만 생성·재전달하며 검증된 비밀은 반환하지 않는다.
     *
     * @param token 유효한 MFA 복구 자격 토큰
     * @param requestId 감사 요청 식별자
     * @return 시차 발급자와 교체 비밀이 포함된 인증 앱 설정
     * @throws AuthException 복구 상태나 자격이 유효하지 않은 경우
     */
    public MfaSetup setupMfa(String token, UUID requestId) {
        return tx.execute(
                s -> {
                    Grant found = cookie(token);
                    lockAccounts(found.accountId);
                    Account account = account(found.accountId);
                    Grant g = lockGrant(found.key);
                    check(g, account, MFA, "COOKIE", found.hash);
                    if (g.verifiedAt != null) throw AuthException.conflict("STATE_CONFLICT");
                    String secret;
                    if (g.mfaCipher == null) {
                        byte[] bytes = new byte[20];
                        random.nextBytes(bytes);
                        secret = totp.encodeBase32(bytes);
                        db.update(
                                "update admin_auth_grant set"
                                    + " mfa_cipher=?,updated_at=clock_timestamp() where"
                                    + " grant_key=?",
                                crypto.encrypt(secret, aad(account.key, g.key)),
                                g.key);
                    } else secret = crypto.decrypt(g.mfaCipher, aad(account.key, g.key));
                    audit(
                            "ANONYMOUS",
                            null,
                            null,
                            account.id,
                            g.key,
                            "RECOVERY_MFA_PREPARED",
                            requestId,
                            "POST /admin/api/auth/recovery/mfa/setup",
                            "POST",
                            200);
                    String issuer = URLEncoder.encode("시차", StandardCharsets.UTF_8);
                    return new MfaSetup(
                            "MFA_VERIFY_REQUIRED",
                            secret,
                            "otpauth://totp/"
                                    + issuer
                                    + ":"
                                    + URLEncoder.encode(
                                            account.key.toString(), StandardCharsets.UTF_8)
                                    + "?secret="
                                    + secret
                                    + "&issuer="
                                    + issuer,
                            g.expires);
                });
    }

    /**
     * Consumes the next valid TOTP time step of the pending replacement, without modifying the
     * original MFA.
     */
    public RecoveryStage verifyMfa(
            String token, String value, String trustedSource, UUID requestId) {
        Grant candidate =
                token != null && token.matches("[A-Za-z0-9_-]{43}")
                        ? grant(crypto.tokenHash(COOKIE_HASH + ":" + MFA, token))
                        : null;
        boolean valid =
                rate(
                                List.of(
                                        new RateCheck(
                                                "TOTP",
                                                candidate == null ? null : candidate.accountId,
                                                () -> {
                                                    if (candidate == null
                                                            || candidate.mfaCipher == null
                                                            || candidate.verifiedAt != null
                                                            || candidate.consumedAt != null
                                                            || candidate.revokedAt != null
                                                            || !clock().isBefore(candidate.expires))
                                                        return false;
                                                    UUID accountKey =
                                                            db.query(
                                                                    "select account_key from"
                                                                        + " admin_account where"
                                                                        + " id=?",
                                                                    rs ->
                                                                            rs.next()
                                                                                    ? (UUID)
                                                                                            rs
                                                                                                    .getObject(
                                                                                                            1)
                                                                                    : null,
                                                                    candidate.accountId);
                                                    return accountKey != null
                                                            && validTotp(
                                                                    crypto.decrypt(
                                                                            candidate.mfaCipher,
                                                                            aad(
                                                                                    accountKey,
                                                                                    candidate.key)),
                                                                    value);
                                                })),
                                trustedSource)
                        .getFirst();
        if (!valid) throw AuthException.unprocessable("INVALID_TOTP");
        return tx.execute(
                s -> {
                    Grant found = cookie(token);
                    lockAccounts(found.accountId);
                    Account account = account(found.accountId);
                    Grant g = lockGrant(found.key);
                    check(g, account, MFA, "COOKIE", found.hash);
                    if (g.mfaCipher == null || g.verifiedAt != null)
                        throw AuthException.conflict("STATE_CONFLICT");
                    Instant now = clock();
                    long step =
                            totp.verify(
                                    crypto.decrypt(g.mfaCipher, aad(account.key, g.key)),
                                    value,
                                    now,
                                    null);
                    db.update(
                            "update admin_auth_grant set"
                                + " mfa_verified_at=?,last_step=?,updated_at=clock_timestamp()"
                                + " where grant_key=?",
                            Timestamp.from(now),
                            step,
                            g.key);
                    audit(
                            "ANONYMOUS",
                            null,
                            null,
                            account.id,
                            g.key,
                            "RECOVERY_MFA_VERIFIED",
                            requestId,
                            "POST /admin/api/auth/recovery/mfa/verify",
                            "POST",
                            200);
                    return new RecoveryStage(MFA, "READY_TO_COMPLETE", g.expires);
                });
    }

    /**
     * Installs the verified MFA while retaining enrolled_at and replacing all prior recovery codes
     * atomically.
     */
    public Completed completeMfa(String token, UUID requestId) {
        return tx.execute(
                s -> {
                    globalLock();
                    Grant found = cookie(token);
                    lockAccounts(found.accountId);
                    Account account = account(found.accountId);
                    Grant g = lockGrant(found.key);
                    check(g, account, MFA, "COOKIE", found.hash);
                    if (g.mfaCipher == null || g.verifiedAt == null || g.lastStep == null)
                        throw AuthException.conflict("STATE_CONFLICT");
                    String secret = crypto.decrypt(g.mfaCipher, aad(account.key, g.key));
                    long revision = nextRevision(account);
                    db.update(
                            "update admin_credential set"
                                + " mfa_cipher=?,mfa_verified_at=?,last_step=?,mfa_state='READY',auth_rev=?,updated_at=clock_timestamp()"
                                + " where account_id=?",
                            crypto.encrypt(secret, "admin-account/" + account.key + "/mfa/v1"),
                            Timestamp.from(g.verifiedAt),
                            g.lastStep,
                            revision,
                            account.id);
                    List<String> codes = replaceCodes(account.id);
                    revokeSessions(account.id);
                    revokeGrants(account.id, null);
                    audit(
                            "ANONYMOUS",
                            null,
                            null,
                            account.id,
                            g.key,
                            "MFA_RECOVERY_COMPLETED",
                            requestId,
                            "POST /admin/api/auth/recovery/mfa/complete",
                            "POST",
                            200);
                    return new Completed("RECOVERED", "LOGIN", codes);
                });
    }

    /**
     * Revokes the presented grant and its pending secret; never restores a previous MFA from
     * RECOVERY.
     */
    public void cancel(String token, UUID requestId) {
        try {
            tx.executeWithoutResult(
                    s -> {
                        Grant found = cookie(token);
                        lockAccounts(found.accountId);
                        Account account = account(found.accountId);
                        Grant g = lockGrant(found.key);
                        check(g, account, g.purpose, "COOKIE", found.hash);
                        revokeGrant(g.key);
                        audit(
                                "ANONYMOUS",
                                null,
                                null,
                                account.id,
                                g.key,
                                "RECOVERY_ABORTED",
                                requestId,
                                "DELETE /admin/api/auth/recovery",
                                "DELETE",
                                204);
                    });
        } catch (DataAccessException failure) {
            try {
                tx.executeWithoutResult(
                        s -> {
                            Grant found = cookie(token);
                            lockAccounts(found.accountId);
                            Account account = account(found.accountId);
                            Grant g = lockGrant(found.key);
                            check(g, account, g.purpose, "COOKIE", found.hash);
                            revokeGrant(g.key);
                        });
                log.error(
                        "Mandatory recovery cancellation audit failed; emergency revocation"
                            + " committed; requestId={}",
                        requestId);
            } catch (RuntimeException unconfirmed) {
                log.error(
                        "Mandatory recovery cancellation audit failed; emergency revocation"
                            + " unconfirmed; requestId={}",
                        requestId);
            }
            throw AuthException.unavailable("AUTH_RESULT_UNCONFIRMED");
        }
    }

    /**
     * Replaces the entire unused bundle for an active READY administrator with a current
     * reauthenticated session.
     */
    public List<String> replaceRecoveryCodes(
            SessionPrincipal actor, String sessionCookie, UUID requestId) {
        if (actor == null || sessionCookie == null)
            throw AuthException.unauthorized("AUTH_REQUIRED");
        return tx.execute(
                s -> {
                    lockAccounts(actor.accountId());
                    operatorSession(actor, sessionCookie, false);
                    Account account = account(actor.accountId());
                    eligible(account, false);
                    if (!account.state.equals("READY"))
                        throw AuthException.conflict("STATE_CONFLICT");
                    List<String> codes = replaceCodes(account.id);
                    audit(
                            "ADMIN",
                            account.id,
                            null,
                            account.id,
                            null,
                            "RECOVERY_CODES_REPLACED",
                            requestId,
                            "POST /admin/api/auth/mfa/recovery-codes",
                            "POST",
                            200);
                    return codes;
                });
    }

    public record Completed(String result, String nextAction, List<String> recoveryCodes) {}

    private record Account(
            long id,
            UUID key,
            boolean active,
            boolean manage,
            long revision,
            String state,
            Instant enrolled,
            String passwordHash) {}

    private record Grant(
            UUID key,
            long accountId,
            String purpose,
            String delivery,
            byte[] hash,
            long revision,
            Instant expires,
            boolean allowInactive,
            String mfaCipher,
            Instant verifiedAt,
            Long lastStep,
            Instant consumedAt,
            Instant revokedAt) {}

    private record RateCheck(String action, Long accountId, BooleanSupplier verify) {}

    private record LimitBucket(String action, String kind, byte[] hash, int checkIndex) {}

    private record LimitState(int failures, Instant window, Instant blockedUntil) {}

    private List<Boolean> rate(List<RateCheck> checks, String source) {
        if (source == null || source.isBlank()) throw AuthException.unavailable("AUTH_UNAVAILABLE");
        List<LimitBucket> buckets = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) {
            RateCheck check = checks.get(i);
            buckets.add(
                    new LimitBucket(
                            check.action, "SOURCE", crypto.limitHash(check.action, source), i));
            if (check.accountId != null)
                buckets.add(
                        new LimitBucket(
                                check.action,
                                "ACCOUNT",
                                crypto.limitHash(check.action, Long.toString(check.accountId)),
                                i));
        }
        buckets.sort(
                Comparator.comparing(LimitBucket::action)
                        .thenComparing(LimitBucket::kind)
                        .thenComparing(b -> java.util.HexFormat.of().formatHex(b.hash)));
        return limitTx.execute(
                s -> {
                    Instant now = clock();
                    List<LimitState> states = new ArrayList<>(buckets.size());
                    for (LimitBucket b : buckets) {
                        db.update(
                                "insert into"
                                    + " admin_auth_limit(action,bucket_kind,bucket_hash,window_at)"
                                    + " values (?,?,?,?) on conflict do nothing",
                                b.action,
                                b.kind,
                                b.hash,
                                Timestamp.from(now));
                        LimitState state =
                                db.query(
                                        "select fail_count,window_at,blocked_until from"
                                            + " admin_auth_limit where action=? and bucket_kind=?"
                                            + " and bucket_hash=? for update",
                                        rs -> {
                                            rs.next();
                                            return new LimitState(
                                                    rs.getInt(1),
                                                    rs.getTimestamp(2).toInstant(),
                                                    rs.getTimestamp(3) == null
                                                            ? null
                                                            : rs.getTimestamp(3).toInstant());
                                        },
                                        b.action,
                                        b.kind,
                                        b.hash);
                        if (state.blockedUntil != null && now.isBefore(state.blockedUntil))
                            throw AuthException.forbidden("RATE_LIMITED");
                        states.add(state);
                    }
                    List<Boolean> results = new ArrayList<>(checks.size());
                    for (RateCheck check : checks) results.add(check.verify.getAsBoolean());
                    for (int i = 0; i < buckets.size(); i++) {
                        LimitBucket b = buckets.get(i);
                        LimitState state = states.get(i);
                        boolean valid = results.get(b.checkIndex);
                        int failures =
                                now.isBefore(state.window.plus(Duration.ofMinutes(15)))
                                        ? state.failures
                                        : 0;
                        int next = valid ? 0 : Math.min(5, failures + 1);
                        db.update(
                                "update admin_auth_limit set"
                                    + " fail_count=?,blocked_until=?,window_at=?,updated_at=? where"
                                    + " action=? and bucket_kind=? and bucket_hash=?",
                                next,
                                next == 5 ? Timestamp.from(now.plus(Duration.ofMinutes(15))) : null,
                                Timestamp.from(valid || failures == 0 ? now : state.window),
                                Timestamp.from(now),
                                b.action,
                                b.kind,
                                b.hash);
                    }
                    return results;
                });
    }

    private boolean validTotp(String secret, String value) {
        try {
            totp.verify(secret, value, clock(), null);
            return true;
        } catch (AuthException ex) {
            if (!"INVALID_TOTP".equals(ex.code())) throw ex;
            return false;
        }
    }

    private RecoveryIssue issueLocked(
            Account account,
            String purpose,
            Long issuer,
            String ref,
            boolean allowInactive,
            String kind,
            String actorRef,
            UUID requestId,
            String approvalRef) {
        revokeGrants(account.id, null, purpose);
        Instant now = clock();
        Instant expires = now.plus(Duration.ofMinutes(10));
        String code = crypto.randomToken(32);
        UUID key = crypto.randomUuid();
        db.update(
                "insert into"
                    + " admin_auth_grant(grant_key,account_id,purpose,delivery,token_hash,auth_rev,expires_at,issued_by,verification_ref,allow_inactive)"
                    + " values (?,? ,?,'CODE',?,?,?,?,?,?)",
                key,
                account.id,
                purpose,
                crypto.tokenHash(CODE_HASH + ":" + purpose, code),
                account.revision,
                Timestamp.from(expires),
                issuer,
                ref,
                allowInactive);
        audit(
                kind,
                issuer,
                actorRef,
                account.id,
                key,
                "RECOVERY_ISSUED",
                requestId,
                kind.equals("OFFLINE") ? "REC-OFFLINE-01" : "POST /admin/api/auth/recovery-codes",
                kind.equals("OFFLINE") ? "OFFLINE" : "POST",
                kind.equals("OFFLINE") ? null : 201,
                approvalRef);
        return new RecoveryIssue(code, expires);
    }

    private List<String> replaceCodes(long id) {
        Instant now = clock();
        db.update(
                "update admin_recovery_code set revoked_at=? where account_id=? and consumed_at is"
                    + " null and revoked_at is null",
                Timestamp.from(now),
                id);
        UUID setKey = crypto.randomUuid();
        List<String> codes = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            String code = totp.encodeBase32(bytes);
            db.update(
                    "insert into admin_recovery_code(account_id,set_key,code_hash) values (?,?,?)",
                    id,
                    setKey,
                    crypto.tokenHash(RECOVERY_CODE_HASH + id, code));
            codes.add(code);
        }
        return List.copyOf(codes);
    }

    private void operator(SessionPrincipal actor, String cookie) {
        operatorSession(actor, cookie, true);
    }

    private void operatorSession(SessionPrincipal actor, String cookie, boolean manage) {
        Account a = account(actor.accountId());
        eligible(a, false);
        if (!a.state.equals("READY") || !actor.accountKey().equals(a.key) || manage && !a.manage)
            throw AuthException.forbidden("FORBIDDEN");
        byte[] sid = crypto.sessionHash(cookie);
        Boolean valid =
                db.query(
                        "select"
                            + " state,auth_rev,reauth_at,started_at,last_action_at,expires_at,revoked_at,sid_hash"
                            + " from admin_session where session_key=? and account_id=? for update",
                        rs -> {
                            if (!rs.next()) return false;
                            Instant now = clock();
                            return rs.getString("state").equals("ACTIVE")
                                    && rs.getLong("auth_rev") == a.revision
                                    && rs.getTimestamp("revoked_at") == null
                                    && crypto.constantEquals(sid, rs.getBytes("sid_hash"))
                                    && now.isBefore(
                                            rs.getTimestamp("reauth_at")
                                                    .toInstant()
                                                    .plus(Duration.ofMinutes(5)))
                                    && now.isBefore(rs.getTimestamp("expires_at").toInstant())
                                    && now.isBefore(
                                            rs.getTimestamp("last_action_at")
                                                    .toInstant()
                                                    .plus(Duration.ofMinutes(30)));
                        },
                        actor.sessionKey(),
                        actor.accountId());
        if (!Boolean.TRUE.equals(valid)) throw AuthException.forbidden("REAUTH_REQUIRED");
    }

    private void eligible(Account a, boolean offline) {
        if (a.enrolled == null || a.passwordHash == null || !offline && !a.active)
            throw AuthException.conflict("STATE_CONFLICT");
    }

    private Grant cookie(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        Grant found = grant(crypto.tokenHash(COOKIE_HASH + ":" + PASSWORD, token));
        if (found == null) found = grant(crypto.tokenHash(COOKIE_HASH + ":" + MFA, token));
        if (found == null || !found.delivery.equals("COOKIE"))
            throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        return found;
    }

    private Grant grant(byte[] hash) {
        return db.query(GRANT_SQL, rs -> rs.next() ? from(rs) : null, hash);
    }

    private Grant lockGrant(UUID key) {
        return db.query(
                "select"
                    + " grant_key,account_id,purpose,delivery,token_hash,auth_rev,expires_at,allow_inactive,mfa_cipher,mfa_verified_at,last_step,consumed_at,revoked_at"
                    + " from admin_auth_grant where grant_key=? for update",
                rs -> rs.next() ? from(rs) : null,
                key);
    }

    private static Grant from(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Grant(
                (UUID) rs.getObject("grant_key"),
                rs.getLong("account_id"),
                rs.getString("purpose"),
                rs.getString("delivery"),
                rs.getBytes("token_hash"),
                rs.getLong("auth_rev"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("allow_inactive"),
                rs.getString("mfa_cipher"),
                rs.getTimestamp("mfa_verified_at") == null
                        ? null
                        : rs.getTimestamp("mfa_verified_at").toInstant(),
                (Long) rs.getObject("last_step"),
                rs.getTimestamp("consumed_at") == null
                        ? null
                        : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("revoked_at") == null
                        ? null
                        : rs.getTimestamp("revoked_at").toInstant());
    }

    private void check(Grant g, Account a, String purpose, String delivery, byte[] expectedHash) {
        if (g == null
                || g.accountId != a.id
                || !g.purpose.equals(purpose)
                || !g.delivery.equals(delivery)
                || !crypto.constantEquals(g.hash, expectedHash)
                || g.consumedAt != null
                || g.revokedAt != null
                || g.revision != a.revision
                || !clock().isBefore(g.expires)
                || !a.active && !g.allowInactive)
            throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        eligible(a, g.allowInactive);
        if (MFA.equals(purpose) && delivery.equals("COOKIE") && !a.state.equals("RECOVERY"))
            throw AuthException.conflict("STATE_CONFLICT");
    }

    private String stage(Grant g) {
        if (PASSWORD.equals(g.purpose)) return "PASSWORD_REQUIRED";
        if (g.verifiedAt != null) return "READY_TO_COMPLETE";
        return g.mfaCipher == null ? "MFA_SETUP_REQUIRED" : "MFA_VERIFY_REQUIRED";
    }

    private Account account(long id) {
        Account a =
                db.query(
                        "select"
                            + " a.id,a.account_key,a.active_yn,a.can_manage,c.auth_rev,c.mfa_state,c.enrolled_at,c.password_hash"
                            + " from admin_account a join admin_credential c on c.account_id=a.id"
                            + " where a.id=?",
                        rs ->
                                rs.next()
                                        ? new Account(
                                                rs.getLong(1),
                                                (UUID) rs.getObject(2),
                                                rs.getBoolean(3),
                                                rs.getBoolean(4),
                                                rs.getLong(5),
                                                rs.getString(6),
                                                rs.getTimestamp(7) == null
                                                        ? null
                                                        : rs.getTimestamp(7).toInstant(),
                                                rs.getString(8))
                                        : null,
                        id);
        if (a == null) throw AuthException.unauthorized("AUTH_FLOW_UNAVAILABLE");
        db.queryForObject(
                "select account_id from admin_credential where account_id=? for update",
                Long.class,
                id);
        return a;
    }

    private void lockAccounts(long... ids) {
        for (long id : java.util.Arrays.stream(ids).distinct().sorted().toArray()) {
            if (db.query(
                            "select id from admin_account where id=? for update",
                            rs -> {
                                return rs.next();
                            },
                            id)
                    != Boolean.TRUE) throw AuthException.conflict("STATE_CONFLICT");
        }
    }

    private void globalLock() {
        db.execute("set local lock_timeout = '5s'");
        db.queryForObject("select pg_advisory_xact_lock(821,1)", Object.class);
    }

    private Instant clock() {
        return db.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private long nextRevision(Account a) {
        if (a.revision == Long.MAX_VALUE) throw AuthException.conflict("STATE_CONFLICT");
        return a.revision + 1;
    }

    private void revokeSessions(long id) {
        db.query(
                "select session_key from admin_session where account_id=? order by session_key for"
                    + " update",
                rs -> {
                    while (rs.next()) {}
                    return null;
                },
                id);
        db.update(
                "update admin_session set"
                    + " state='REVOKED',revoked_at=clock_timestamp(),updated_at=clock_timestamp()"
                    + " where account_id=? and state<>'REVOKED'",
                id);
    }

    private void revokeGrants(long id, UUID except) {
        revokeGrants(id, except, null);
    }

    private void revokeGrant(UUID key) {
        db.update(
                "update admin_auth_grant set"
                    + " revoked_at=clock_timestamp(),token_hash=null,mfa_cipher=null,mfa_verified_at=null,last_step=null,updated_at=clock_timestamp()"
                    + " where grant_key=?",
                key);
    }

    private void revokeGrants(long id, UUID except, String purpose) {
        db.update(
                "update admin_auth_grant set"
                    + " revoked_at=clock_timestamp(),token_hash=null,mfa_cipher=null,mfa_verified_at=null,last_step=null,updated_at=clock_timestamp()"
                    + " where account_id=? and consumed_at is null and revoked_at is null and"
                    + " (?::uuid is null or grant_key<>?::uuid) and (?::varchar is null or"
                    + " purpose=?::varchar)",
                id,
                except,
                except,
                purpose,
                purpose);
    }

    private static String aad(UUID key, UUID grant) {
        return "admin-account/" + key + "/recovery-grant/" + grant + "/mfaSecret/v1";
    }

    private void audit(
            String kind,
            Long actor,
            String actorRef,
            long target,
            UUID grant,
            String action,
            UUID requestId,
            String route,
            String method,
            Integer status) {
        audit(kind, actor, actorRef, target, grant, action, requestId, route, method, status, null);
    }

    private void audit(
            String kind,
            Long actor,
            String actorRef,
            long target,
            UUID grant,
            String action,
            UUID requestId,
            String route,
            String method,
            Integer status,
            String approvalRef) {
        if (requestId == null) throw AuthException.badRequest("INVALID_REQUEST_ID");
        db.update(
                "insert into"
                    + " admin_auth_audit(actor_kind,actor_id,actor_ref,target_id,grant_key,action,outcome,request_id,route,method,http_status,change_data)"
                    + " values (?,?,?,?,?,'"
                        + action
                        + "','COMMITTED',?,?,?,?,case when ?::varchar is null then null else"
                        + " jsonb_build_object('approvalRef',?::varchar) end)",
                kind,
                actor,
                actorRef,
                target,
                grant,
                requestId,
                route,
                method,
                status,
                approvalRef,
                approvalRef);
    }

    private static void purpose(String purpose) {
        if (!PASSWORD.equals(purpose) && !MFA.equals(purpose))
            throw AuthException.badRequest("INVALID_PURPOSE");
    }

    private static void reference(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_VERIFICATION_REF");
    }

    private void validatePassword(String password) {
        if (password == null
                || password.codePointCount(0, password.length()) < 15
                || password.codePointCount(0, password.length()) > 128
                || List.of(
                                "passwordpassword",
                                "password123456",
                                "123456789012345",
                                "1234567890123456",
                                "qwertyuiopasdfgh",
                                "adminadminadmin",
                                "letmeinletmein123",
                                "correcthorsebatterystaple")
                        .contains(password.toLowerCase(java.util.Locale.ROOT))
                || breachedPasswords.isBreached(password))
            throw AuthException.unprocessable("INVALID_PASSWORD");
    }
}
