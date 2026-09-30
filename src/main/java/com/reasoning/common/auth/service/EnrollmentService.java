package com.reasoning.common.auth.service;

import com.reasoning.common.auth.service.AuthModels.EnrollComplete;
import com.reasoning.common.auth.service.AuthModels.EnrollExchange;
import com.reasoning.common.auth.service.AuthModels.EnrollStage;
import com.reasoning.common.auth.service.AuthModels.FlowCookie;
import com.reasoning.common.auth.service.AuthModels.MfaSetup;
import com.reasoning.common.auth.service.AuthModels.SessionPrincipal;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Business transactions for the offline bootstrap, invitations and restricted enrollment grant. */
@Service
public class EnrollmentService {
    private static final String COOKIE = "__Host-admin-enroll";
    private static final String CODE_PURPOSE = "enrollment-code";
    private static final String GRANT_PURPOSE = "enrollment-grant";
    private static final String RECOVERY_PURPOSE = "admin-recovery-code";
    private static final Set<String> COMMON_PASSWORDS =
            Set.of(
                    "passwordpassword",
                    "password123456",
                    "123456789012345",
                    "1234567890123456",
                    "qwertyuiopasdfgh",
                    "adminadminadmin",
                    "letmeinletmein123",
                    "correcthorsebatterystaple");
    private static final String LOOKUP =
            "select"
                + " e.id,e.registration_key,e.account_id,e.kind,e.generation,e.code_hash,e.code_expires_at,e.code_consumed_at,e.grant_hash,e.grant_expires_at,e.verified_by,e.verification_ref,e.revoked_at,e.completed_at,a.account_key,a.active_yn,c.enroll_gen,c.password_hash,c.mfa_cipher,c.mfa_verified_at,c.last_step,c.enrolled_at"
                + " from admin_enrollment e join admin_account a on a.id=e.account_id join"
                + " admin_credential c on c.account_id=e.account_id ";
    private static final RowMapper<Enrollment> ENROLLMENT =
            (rs, row) ->
                    new Enrollment(
                            rs.getLong("id"),
                            (UUID) rs.getObject("registration_key"),
                            rs.getLong("account_id"),
                            rs.getString("kind"),
                            rs.getInt("generation"),
                            rs.getBytes("code_hash"),
                            time(rs, "code_expires_at"),
                            time(rs, "code_consumed_at"),
                            rs.getBytes("grant_hash"),
                            time(rs, "grant_expires_at"),
                            (Long) rs.getObject("verified_by"),
                            rs.getString("verification_ref"),
                            time(rs, "revoked_at"),
                            time(rs, "completed_at"),
                            (UUID) rs.getObject("account_key"),
                            rs.getBoolean("active_yn"),
                            rs.getInt("enroll_gen"),
                            rs.getString("password_hash"),
                            rs.getString("mfa_cipher"),
                            time(rs, "mfa_verified_at"),
                            (Long) rs.getObject("last_step"),
                            time(rs, "enrolled_at"));
    private final JdbcTemplate db;
    private final CryptoService crypto;
    private final TotpService totp;
    private final BreachedPasswordChecker breachedPasswords;
    private final Argon2PasswordEncoder passwords =
            new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    private final SecureRandom random = new SecureRandom();
    private final TransactionTemplate transaction;

    public EnrollmentService(
            JdbcTemplate db,
            CryptoService crypto,
            TotpService totp,
            BreachedPasswordChecker breachedPasswords,
            PlatformTransactionManager manager) {
        this.db = db;
        this.crypto = crypto;
        this.totp = totp;
        this.breachedPasswords = breachedPasswords;
        this.transaction = new TransactionTemplate(manager);
    }

    /**
     * Creates the sole immutable bootstrap marker offline; caller must authenticate the offline
     * operator and deliver the code privately.
     */
    @Transactional
    public AuthModels.BootstrapResult createBootstrap(
            String loginId, String verificationRef, String operatorRef, UUID requestId) {
        validateIdentity(loginId, verificationRef);
        if (operatorRef == null || !operatorRef.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_OPERATOR_REF");
        globalLock();
        if (db.queryForObject(
                        "select count(*) from admin_enrollment where kind='BOOTSTRAP'",
                        Integer.class)
                != 0) throw AuthException.conflict("STATE_CONFLICT");
        UUID key = crypto.randomUuid();
        UUID registration = crypto.randomUuid();
        String code = crypto.randomToken(32);
        Instant now = clock();
        Long id =
                db.queryForObject(
                        "insert into admin_account(account_key,can_manage) values (?,true)"
                            + " returning id",
                        Long.class,
                        key);
        db.update(
                "insert into admin_credential(account_id,login_cipher,login_hash) values (?,?,?)",
                id,
                crypto.encrypt(loginId, aad(key, "loginId")),
                crypto.loginHash(loginId));
        Long enrollment =
                db.queryForObject(
                        "insert into"
                            + " admin_enrollment(registration_key,account_id,kind,code_hash,code_expires_at,verification_ref,verified_at)"
                            + " values (?,?,'BOOTSTRAP',?,?,?,?) returning id",
                        Long.class,
                        registration,
                        id,
                        crypto.tokenHash(CODE_PURPOSE, code),
                        Timestamp.from(now.plus(Duration.ofMinutes(30))),
                        verificationRef,
                        Timestamp.from(now));
        audit(
                "OFFLINE",
                null,
                operatorRef,
                id,
                enrollment,
                1,
                "BOOTSTRAP_CREATED",
                requestId,
                "BOOTSTRAP-01",
                "OFFLINE",
                null);
        return new AuthModels.BootstrapResult(registration, code, now.plus(Duration.ofMinutes(30)));
    }

    /**
     * Reissues only the unfinished existing bootstrap account offline; never creates a second
     * marker.
     */
    @Transactional
    public AuthModels.BootstrapResult reissueBootstrap(
            String verificationRef, String operatorRef, UUID requestId) {
        validateRef(verificationRef);
        if (operatorRef == null || !operatorRef.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_OPERATOR_REF");
        globalLock();
        Enrollment candidate = one(LOOKUP + "where e.kind='BOOTSTRAP'");
        if (candidate == null) throw AuthException.conflict("STATE_CONFLICT");
        Enrollment e = locked(candidate, null);
        if (e.enrolledAt != null || e.completedAt != null || !e.active)
            throw AuthException.conflict("STATE_CONFLICT");
        String code = reissue(e, verificationRef, null);
        Instant expires =
                db.queryForObject(
                        "select code_expires_at from admin_enrollment where id=?",
                        (rs, row) -> rs.getTimestamp(1).toInstant(),
                        e.id);
        audit(
                "OFFLINE",
                null,
                operatorRef,
                e.accountId,
                e.id,
                e.generation + 1,
                "BOOTSTRAP_REISSUED",
                requestId,
                "BOOTSTRAP-01",
                "OFFLINE",
                null);
        return new AuthModels.BootstrapResult(e.key, code, expires);
    }

    /**
     * Issues a new invitation after verifying the current stored session and management permission
     * under account locks.
     */
    @Transactional
    public InvitationIssued issueInvitation(
            SessionPrincipal actor,
            String sessionCookie,
            UUID registrationKey,
            String loginId,
            String verificationRef,
            UUID requestId) {
        validateIdentity(loginId, verificationRef);
        if (registrationKey == null || registrationKey.version() != 4)
            throw AuthException.badRequest("INVALID_REGISTRATION_KEY");
        operator(actor, sessionCookie);
        UUID accountKey = crypto.randomUuid();
        String code = crypto.randomToken(32);
        Instant now = clock();
        Long target;
        Long enrollment;
        try {
            target =
                    db.queryForObject(
                            "insert into admin_account(account_key) values (?) returning id",
                            Long.class,
                            accountKey);
            db.update(
                    "insert into admin_credential(account_id,login_cipher,login_hash) values"
                        + " (?,?,?)",
                    target,
                    crypto.encrypt(loginId, aad(accountKey, "loginId")),
                    crypto.loginHash(loginId));
            enrollment =
                    db.queryForObject(
                            "insert into"
                                + " admin_enrollment(registration_key,account_id,kind,code_hash,code_expires_at,verified_by,verification_ref,verified_at)"
                                + " values (?,?,'INVITE',?,?,?,?,?) returning id",
                            Long.class,
                            registrationKey,
                            target,
                            crypto.tokenHash(CODE_PURPOSE, code),
                            Timestamp.from(now.plus(Duration.ofMinutes(30))),
                            actor.accountId(),
                            verificationRef,
                            Timestamp.from(now));
        } catch (DataIntegrityViolationException ex) {
            throw AuthException.conflict("STATE_CONFLICT");
        }
        audit(
                "ADMIN",
                actor.accountId(),
                null,
                target,
                enrollment,
                1,
                "INVITE_CREATED",
                requestId,
                "POST /admin/api/auth/invitations",
                "POST",
                201);
        return new InvitationIssued(registrationKey, 1, code, now.plus(Duration.ofMinutes(30)));
    }

    /**
     * Inspects an invitation without returning login ID, password state or secrets; inspection is
     * audited.
     */
    @Transactional
    public InvitationStatus inspectInvitation(
            SessionPrincipal actor, String sessionCookie, UUID registrationKey, UUID requestId) {
        Enrollment candidate = invitation(registrationKey);
        Enrollment e = locked(candidate, actor == null ? null : actor.accountId());
        operatorLocked(actor, sessionCookie);
        Instant now = clock();
        String status =
                !e.active
                        ? "BLOCKED"
                        : e.completedAt != null
                                ? "COMPLETED"
                                : e.revokedAt != null
                                        ? "REVOKED"
                                        : e.codeConsumedAt == null
                                                ? !now.isBefore(e.codeExpiresAt)
                                                        ? "EXPIRED"
                                                        : "AWAITING_EXCHANGE"
                                                : !now.isBefore(e.grantExpiresAt)
                                                        ? "EXPIRED"
                                                        : "ENROLLING";
        Instant codeExpiry =
                status.equals("AWAITING_EXCHANGE")
                                || status.equals("EXPIRED") && e.codeConsumedAt == null
                        ? e.codeExpiresAt
                        : null;
        Instant grantExpiry =
                status.equals("ENROLLING") || status.equals("EXPIRED") && e.codeConsumedAt != null
                        ? e.grantExpiresAt
                        : null;
        audit(
                "ADMIN",
                actor.accountId(),
                null,
                e.accountId,
                e.id,
                e.generation,
                "INVITE_INSPECTED",
                requestId,
                "GET /admin/api/auth/invitations/{registrationKey}",
                "GET",
                200);
        return new InvitationStatus(e.key, e.generation, status, codeExpiry, grantExpiry);
    }

    /** Rechecks target verification and replaces every pending secret at a new generation. */
    @Transactional
    public InvitationIssued reissueInvitation(
            SessionPrincipal actor,
            String sessionCookie,
            UUID registrationKey,
            int expectedGeneration,
            String verificationRef,
            UUID requestId) {
        validateRef(verificationRef);
        Enrollment candidate = invitation(registrationKey);
        Enrollment e = locked(candidate, actor == null ? null : actor.accountId());
        operatorLocked(actor, sessionCookie);
        if (e.generation != expectedGeneration
                || e.enrolledAt != null
                || e.completedAt != null
                || !e.active) throw AuthException.conflict("STATE_CONFLICT");
        String code = reissue(e, verificationRef, actor.accountId());
        Instant expiry =
                db.queryForObject(
                        "select code_expires_at from admin_enrollment where id=?",
                        (rs, row) -> rs.getTimestamp(1).toInstant(),
                        e.id);
        audit(
                "ADMIN",
                actor.accountId(),
                null,
                e.accountId,
                e.id,
                e.generation + 1,
                "INVITE_REISSUED",
                requestId,
                "POST /admin/api/auth/invitations/{registrationKey}/reissue",
                "POST",
                200);
        return new InvitationIssued(e.key, e.generation + 1, code, expiry);
    }

    /**
     * Revokes an unfinished invitation; repeating revocation of the same generation is idempotent.
     */
    public void revokeInvitation(
            SessionPrincipal actor,
            String sessionCookie,
            UUID registrationKey,
            int expectedGeneration,
            UUID requestId) {
        try {
            revokeInvitationTransaction(
                    actor, sessionCookie, registrationKey, expectedGeneration, requestId, true);
        } catch (AuditWriteFailed auditFailure) {
            // The audited transaction rolled back. Retry the block in an independent transaction,
            // including authorization and generation checks; never report an uncommitted block.
            revokeInvitationTransaction(
                    actor, sessionCookie, registrationKey, expectedGeneration, requestId, false);
        }
    }

    private void revokeInvitationTransaction(
            SessionPrincipal actor,
            String sessionCookie,
            UUID registrationKey,
            int expectedGeneration,
            UUID requestId,
            boolean withAudit) {
        transaction.executeWithoutResult(
                status -> {
                    Enrollment candidate = invitation(registrationKey);
                    Enrollment e = locked(candidate, actor == null ? null : actor.accountId());
                    operatorLocked(actor, sessionCookie);
                    if (e.generation != expectedGeneration
                            || e.completedAt != null
                            || e.enrolledAt != null) throw AuthException.conflict("STATE_CONFLICT");
                    if (e.revokedAt != null) return;
                    Instant now = clock();
                    db.update(
                            "update admin_enrollment set"
                                + " code_hash=null,grant_hash=null,revoked_at=?,updated_at=? where"
                                + " id=?",
                            now,
                            now,
                            e.id);
                    clearPending(e.accountId, e.generation);
                    if (withAudit) {
                        try {
                            audit(
                                    "ADMIN",
                                    actor.accountId(),
                                    null,
                                    e.accountId,
                                    e.id,
                                    e.generation,
                                    "INVITE_REVOKED",
                                    requestId,
                                    "DELETE /admin/api/auth/invitations/{registrationKey}",
                                    "DELETE",
                                    204);
                        } catch (DataAccessException failure) {
                            throw new AuditWriteFailed(failure);
                        }
                    }
                });
    }

    /**
     * Consumes a single 43-character enrollment code and issues a 15-minute restricted cookie,
     * never a general session.
     */
    public EnrollExchange exchange(
            String code, String existingCookie, String trustedSource, UUID requestId) {
        Enrollment candidate =
                code != null && code.matches("[A-Za-z0-9_-]{43}")
                        ? one(LOOKUP + "where e.code_hash=?", crypto.tokenHash(CODE_PURPOSE, code))
                        : null;
        limit(
                "EXCHANGE",
                candidate == null ? null : candidate.accountId,
                trustedSource,
                () -> candidate != null);
        if (candidate == null) throw AuthException.unprocessable("INVALID_CODE");
        return transaction.execute(
                status -> {
                    Enrollment e = locked(candidate, null);
                    Instant now = clock();
                    if (existingCookie != null && !existingCookie.isBlank()) {
                        Enrollment other = candidateGrant(existingCookie);
                        if (other != null && validGrant(other, existingCookie, now))
                            throw AuthException.conflict("STATE_CONFLICT");
                    }
                    if (!e.active
                            || e.revokedAt != null
                            || e.completedAt != null
                            || e.codeConsumedAt != null
                            || !now.isBefore(e.codeExpiresAt)
                            || !crypto.constantEquals(
                                    e.codeHash, crypto.tokenHash(CODE_PURPOSE, code)))
                        throw AuthException.unprocessable("INVALID_CODE");
                    String grant = crypto.randomToken(32);
                    Instant expires = now.plus(Duration.ofMinutes(15));
                    db.update(
                            "update admin_enrollment set"
                                + " code_hash=null,code_consumed_at=?,grant_hash=?,grant_expires_at=?,updated_at=?"
                                + " where id=?",
                            now,
                            crypto.tokenHash(GRANT_PURPOSE, grant),
                            Timestamp.from(expires),
                            now,
                            e.id);
                    audit(
                            "ENROLLEE",
                            e.accountId,
                            null,
                            e.accountId,
                            e.id,
                            e.generation,
                            "ENROLL_EXCHANGED",
                            requestId,
                            "POST /admin/api/auth/enrollment/exchange",
                            "POST",
                            200);
                    return new EnrollExchange(
                            "PASSWORD_REQUIRED", expires, new FlowCookie(COOKIE, grant, expires));
                });
    }

    /** Returns only the server-derived enrollment stage for a still-valid restricted cookie. */
    @Transactional
    public EnrollStage stage(String cookie) {
        Enrollment e = lockedGrant(cookie);
        return new EnrollStage(stageOf(e), e.grantExpiresAt);
    }

    /** Sets a valid password once for this generation; repeated submission never overwrites it. */
    @Transactional
    public EnrollStage setPassword(String cookie, String password, UUID requestId) {
        validatePassword(password);
        String hash = passwords.encode(password);
        Enrollment e = lockedGrant(cookie);
        if (e.passwordHash != null) throw AuthException.conflict("STATE_CONFLICT");
        db.update(
                "update admin_credential set password_hash=?,updated_at=? where account_id=? and"
                    + " enroll_gen=?",
                hash,
                clock(),
                e.accountId,
                e.generation);
        auditEnroll(
                e,
                "ENROLL_PASSWORD_SET",
                "PUT /admin/api/auth/enrollment/password",
                "PUT",
                requestId);
        return new EnrollStage("MFA_SETUP_REQUIRED", e.grantExpiresAt);
    }

    /**
     * 등록용 20바이트 TOTP 비밀을 최초 생성하거나 검증 전까지 같은 시차 등록 URI를 반환한다.
     *
     * @param cookie 유효한 등록 자격 쿠키
     * @param requestId 감사 요청 식별자
     * @return 인증 앱에 표시할 시차 발급자와 비밀이 포함된 설정
     * @throws AuthException 등록 상태나 자격이 유효하지 않은 경우
     */
    @Transactional
    public MfaSetup prepareMfa(String cookie, UUID requestId) {
        Enrollment e = lockedGrant(cookie);
        if (e.passwordHash == null) throw AuthException.conflict("STATE_CONFLICT");
        if (e.mfaVerifiedAt != null) throw AuthException.conflict("STATE_CONFLICT");
        String secret;
        if (e.mfaCipher == null) {
            byte[] bytes = new byte[20];
            random.nextBytes(bytes);
            secret = totp.encodeBase32(bytes);
            db.update(
                    "update admin_credential set mfa_cipher=?,updated_at=? where account_id=? and"
                        + " enroll_gen=?",
                    crypto.encrypt(secret, aad(e.accountKey, "mfa")),
                    clock(),
                    e.accountId,
                    e.generation);
        } else {
            secret = crypto.decrypt(e.mfaCipher, aad(e.accountKey, "mfa"));
        }
        auditEnroll(
                e,
                "ENROLL_MFA_PREPARED",
                "POST /admin/api/auth/enrollment/mfa/setup",
                "POST",
                requestId);
        String issuer = URLEncoder.encode("시차", StandardCharsets.UTF_8);
        String uri =
                "otpauth://totp/"
                        + issuer
                        + ":"
                        + URLEncoder.encode(e.accountKey.toString(), StandardCharsets.UTF_8)
                        + "?secret="
                        + secret
                        + "&issuer="
                        + issuer
                        + "&algorithm=SHA1&digits=6&period=30";
        return new MfaSetup("MFA_VERIFY_REQUIRED", secret, uri, e.grantExpiresAt);
    }

    /**
     * Verifies TOTP in a separate rate-limit transaction before storing the accepted step under
     * business locks.
     */
    public EnrollStage verifyMfa(String cookie, String code, String trustedSource, UUID requestId) {
        Enrollment candidate = candidateGrant(cookie);
        String secret =
                candidate != null && candidate.mfaCipher != null && candidate.mfaVerifiedAt == null
                        ? crypto.decrypt(candidate.mfaCipher, aad(candidate.accountKey, "mfa"))
                        : null;
        limit(
                "TOTP",
                candidate == null ? null : candidate.accountId,
                trustedSource,
                () -> secret != null && validTotp(secret, code));
        if (secret == null) throw AuthException.unauthorized("INVALID_ENROLLMENT");
        return transaction.execute(
                status -> {
                    Enrollment e = lockedGrant(cookie);
                    if (e.passwordHash == null
                            || e.mfaCipher == null
                            || e.mfaVerifiedAt != null
                            || !e.mfaCipher.equals(candidate.mfaCipher)
                            || e.generation != candidate.generation)
                        throw AuthException.conflict("STATE_CONFLICT");
                    Instant now = clock();
                    long step = totp.verify(secret, code, now, e.lastStep);
                    db.update(
                            "update admin_credential set mfa_verified_at=?,last_step=?,updated_at=?"
                                + " where account_id=? and enroll_gen=?",
                            now,
                            step,
                            now,
                            e.accountId,
                            e.generation);
                    auditEnroll(
                            e,
                            "ENROLL_MFA_VERIFIED",
                            "POST /admin/api/auth/enrollment/mfa/verify",
                            "POST",
                            requestId);
                    return new EnrollStage("READY_TO_COMPLETE", e.grantExpiresAt);
                });
    }

    /**
     * Completes enrollment atomically with ten one-time recovery hashes; no ordinary session is
     * issued.
     */
    @Transactional
    public EnrollComplete complete(String cookie, UUID requestId) {
        globalLock();
        Enrollment e = lockedGrant(cookie);
        if (e.passwordHash == null
                || e.mfaCipher == null
                || e.mfaVerifiedAt == null
                || e.lastStep == null
                || e.verifiedBy == null && !e.kind.equals("BOOTSTRAP")
                || e.verificationRef == null) throw AuthException.conflict("STATE_CONFLICT");
        Instant now = clock();
        UUID set = crypto.randomUuid();
        List<String> codes = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            // 128 bits are represented by 26 unpadded base32 characters (two zero padding bits).
            String recovery = totp.encodeBase32(bytes);
            codes.add(recovery);
            db.update(
                    "insert into admin_recovery_code(account_id,set_key,code_hash,created_at)"
                        + " values (?,?,?,?)",
                    e.accountId,
                    set,
                    crypto.tokenHash(RECOVERY_PURPOSE + ":" + e.accountId, recovery),
                    now);
        }
        db.update(
                "update admin_credential set enrolled_at=?,mfa_state='READY',updated_at=? where"
                    + " account_id=? and enroll_gen=?",
                now,
                now,
                e.accountId,
                e.generation);
        db.update(
                "update admin_enrollment set completed_at=?,grant_hash=null,updated_at=? where"
                    + " id=?",
                now,
                now,
                e.id);
        auditEnroll(
                e,
                "ENROLL_COMPLETED",
                "POST /admin/api/auth/enrollment/complete",
                "POST",
                requestId);
        return new EnrollComplete("ENROLLED", "LOGIN", List.copyOf(codes));
    }

    /**
     * Invalidates this restricted grant and discards all pending credentials; caller clears the
     * cookie after commit.
     */
    public void cancel(String cookie, UUID requestId) {
        try {
            cancelTransaction(cookie, requestId, true);
        } catch (AuditWriteFailed auditFailure) {
            cancelTransaction(cookie, requestId, false);
        }
    }

    private void cancelTransaction(String cookie, UUID requestId, boolean withAudit) {
        transaction.executeWithoutResult(
                status -> {
                    Enrollment e = lockedGrant(cookie);
                    Instant now = clock();
                    db.update(
                            "update admin_enrollment set grant_hash=null,revoked_at=?,updated_at=?"
                                + " where id=?",
                            now,
                            now,
                            e.id);
                    clearPending(e.accountId, e.generation);
                    if (withAudit) {
                        try {
                            audit(
                                    "ENROLLEE",
                                    e.accountId,
                                    null,
                                    e.accountId,
                                    e.id,
                                    e.generation,
                                    "ENROLL_ABORTED",
                                    requestId,
                                    "DELETE /admin/api/auth/enrollment",
                                    "DELETE",
                                    204);
                        } catch (DataAccessException failure) {
                            throw new AuditWriteFailed(failure);
                        }
                    }
                });
    }

    private static final class AuditWriteFailed extends RuntimeException {
        private AuditWriteFailed(DataAccessException cause) {
            super(cause);
        }
    }

    public record InvitationIssued(
            UUID registrationKey, int generation, String code, Instant codeExpiresAt) {}

    public record InvitationStatus(
            UUID registrationKey,
            int generation,
            String status,
            Instant codeExpiresAt,
            Instant grantExpiresAt) {}

    private record Enrollment(
            long id,
            UUID key,
            long accountId,
            String kind,
            int generation,
            byte[] codeHash,
            Instant codeExpiresAt,
            Instant codeConsumedAt,
            byte[] grantHash,
            Instant grantExpiresAt,
            Long verifiedBy,
            String verificationRef,
            Instant revokedAt,
            Instant completedAt,
            UUID accountKey,
            boolean active,
            int credentialGeneration,
            String passwordHash,
            String mfaCipher,
            Instant mfaVerifiedAt,
            Long lastStep,
            Instant enrolledAt) {}

    private static Instant time(ResultSet rs, String name) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Enrollment one(String sql, Object... args) {
        List<Enrollment> rows = db.query(sql, ENROLLMENT, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Enrollment invitation(UUID key) {
        Enrollment e = key == null ? null : one(LOOKUP + "where e.registration_key=?", key);
        if (e == null || !e.kind.equals("INVITE")) throw AuthException.conflict("STATE_CONFLICT");
        return e;
    }

    private Enrollment locked(Enrollment candidate, Long actorId) {
        List<Long> ids = new ArrayList<>();
        ids.add(candidate.accountId);
        if (actorId != null && !actorId.equals(candidate.accountId)) ids.add(actorId);
        ids.sort(Comparator.naturalOrder());
        for (Long id : ids)
            db.queryForObject("select id from admin_account where id=? for update", Long.class, id);
        db.queryForObject(
                "select id from admin_enrollment where id=? for update", Long.class, candidate.id);
        db.queryForObject(
                "select account_id from admin_credential where account_id=? for update",
                Long.class,
                candidate.accountId);
        return one(LOOKUP + "where e.id=?", candidate.id);
    }

    private Enrollment candidateGrant(String cookie) {
        if (cookie == null || !cookie.matches("[A-Za-z0-9_-]{43}")) return null;
        return one(LOOKUP + "where e.grant_hash=?", crypto.tokenHash(GRANT_PURPOSE, cookie));
    }

    private Enrollment lockedGrant(String cookie) {
        Enrollment candidate = candidateGrant(cookie);
        if (candidate == null) throw AuthException.unauthorized("INVALID_ENROLLMENT");
        Enrollment e = locked(candidate, null);
        if (!validGrant(e, cookie, clock())) throw AuthException.unauthorized("INVALID_ENROLLMENT");
        return e;
    }

    private boolean validGrant(Enrollment e, String cookie, Instant now) {
        return e.active
                && e.generation == e.credentialGeneration
                && e.completedAt == null
                && e.revokedAt == null
                && e.codeConsumedAt != null
                && e.grantExpiresAt != null
                && now.isBefore(e.grantExpiresAt)
                && crypto.constantEquals(e.grantHash, crypto.tokenHash(GRANT_PURPOSE, cookie))
                && e.enrolledAt == null;
    }

    private String stageOf(Enrollment e) {
        if (e.passwordHash == null) return "PASSWORD_REQUIRED";
        if (e.mfaCipher == null) return "MFA_SETUP_REQUIRED";
        return e.mfaVerifiedAt == null ? "MFA_VERIFY_REQUIRED" : "READY_TO_COMPLETE";
    }

    private String reissue(Enrollment e, String ref, Long verifier) {
        if (e.generation == Integer.MAX_VALUE) throw AuthException.conflict("STATE_CONFLICT");
        String code = crypto.randomToken(32);
        Instant now = clock();
        db.update(
                "update admin_enrollment set"
                    + " generation=generation+1,code_hash=?,code_expires_at=?,code_consumed_at=null,grant_hash=null,grant_expires_at=null,verified_by=?,verification_ref=?,verified_at=?,revoked_at=null,updated_at=?"
                    + " where id=?",
                crypto.tokenHash(CODE_PURPOSE, code),
                now.plus(Duration.ofMinutes(30)),
                verifier,
                ref,
                now,
                now,
                e.id);
        db.update(
                "update admin_credential set"
                    + " enroll_gen=enroll_gen+1,password_hash=null,mfa_cipher=null,mfa_verified_at=null,last_step=null,updated_at=?"
                    + " where account_id=? and enroll_gen=?",
                now,
                e.accountId,
                e.generation);
        return code;
    }

    private void clearPending(long accountId, int generation) {
        db.update(
                "update admin_credential set"
                    + " password_hash=null,mfa_cipher=null,mfa_verified_at=null,last_step=null,updated_at=?"
                    + " where account_id=? and enroll_gen=? and enrolled_at is null",
                clock(),
                accountId,
                generation);
    }

    private void operator(SessionPrincipal actor, String cookie) {
        if (actor == null) throw AuthException.unauthorized("AUTH_REQUIRED");
        db.queryForObject(
                "select id from admin_account where id=? for update",
                Long.class,
                actor.accountId());
        operatorLocked(actor, cookie);
    }

    private void operatorLocked(SessionPrincipal actor, String cookie) {
        if (actor == null || cookie == null || cookie.isBlank())
            throw AuthException.unauthorized("AUTH_REQUIRED");
        Instant now = clock();
        Integer count =
                db.queryForObject(
                        "select count(*) from admin_account a join admin_credential c on"
                            + " c.account_id=a.id join admin_session s on s.account_id=a.id where"
                            + " a.id=? and a.account_key=? and a.active_yn and a.can_manage and"
                            + " c.enrolled_at is not null and c.mfa_state='READY' and"
                            + " s.session_key=? and s.sid_hash=? and s.state='ACTIVE' and"
                            + " s.auth_rev=c.auth_rev and s.expires_at>? and"
                            + " s.last_action_at+interval '30 minutes'>? and s.reauth_at+interval"
                            + " '5 minutes'>?",
                        Integer.class,
                        actor.accountId(),
                        actor.accountKey(),
                        actor.sessionKey(),
                        crypto.sessionHash(cookie),
                        now,
                        now,
                        now);
        if (count == null || count == 0) throw AuthException.forbidden("MANAGE_REAUTH_REQUIRED");
    }

    private void globalLock() {
        db.execute("select pg_advisory_xact_lock(821,1)");
    }

    private Instant clock() {
        return db.queryForObject(
                "select clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    private void auditEnroll(
            Enrollment e, String action, String route, String method, UUID requestId) {
        audit(
                "ENROLLEE",
                e.accountId,
                null,
                e.accountId,
                e.id,
                e.generation,
                action,
                requestId,
                route,
                method,
                200);
    }

    private void audit(
            String kind,
            Long actor,
            String operator,
            Long target,
            Long enrollment,
            Integer generation,
            String action,
            UUID requestId,
            String route,
            String method,
            Integer status) {
        if (requestId == null) throw AuthException.badRequest("INVALID_REQUEST_ID");
        db.update(
                "insert into"
                    + " admin_auth_audit(actor_kind,actor_id,actor_ref,target_id,enrollment_id,generation,action,outcome,request_id,route,method,http_status)"
                    + " values (?,?,?,?,?,?,?,'COMMITTED',?,?,?,?)",
                kind,
                actor,
                operator,
                target,
                enrollment,
                generation,
                action,
                requestId,
                route,
                method,
                status);
    }

    private boolean validTotp(String secret, String code) {
        try {
            totp.verify(secret, code, clock(), null);
            return true;
        } catch (AuthException invalid) {
            if (!"INVALID_TOTP".equals(invalid.code())) throw invalid;
            return false;
        }
    }

    private void limit(String action, Long accountId, String source, BooleanSupplier check) {
        if (source == null || source.isBlank()) throw AuthException.unavailable("AUTH_UNAVAILABLE");
        List<Bucket> buckets = new ArrayList<>();
        if (accountId != null)
            buckets.add(new Bucket("ACCOUNT", crypto.limitHash(action, Long.toString(accountId))));
        buckets.add(new Bucket("SOURCE", crypto.limitHash(action, source)));
        transaction.executeWithoutResult(
                status -> {
                    Instant now = clock();
                    List<Limit> limits = new ArrayList<>(buckets.size());
                    for (Bucket bucket : buckets) {
                        db.update(
                                "insert into"
                                    + " admin_auth_limit(action,bucket_kind,bucket_hash,window_at)"
                                    + " values (?,?,?,?) on conflict do nothing",
                                action,
                                bucket.kind,
                                bucket.hash,
                                now);
                        List<Limit> rows =
                                db.query(
                                        "select fail_count,window_at,blocked_until from"
                                            + " admin_auth_limit where action=? and bucket_kind=?"
                                            + " and bucket_hash=? for update",
                                        (rs, row) ->
                                                new Limit(
                                                        rs.getInt(1),
                                                        rs.getTimestamp(2).toInstant(),
                                                        time(rs, "blocked_until")),
                                        action,
                                        bucket.kind,
                                        bucket.hash);
                        Limit prior = rows.getFirst();
                        if (prior.blockedUntil != null && now.isBefore(prior.blockedUntil))
                            throw AuthException.forbidden("RATE_LIMITED");
                        limits.add(prior);
                    }
                    boolean valid = check.getAsBoolean();
                    for (int i = 0; i < buckets.size(); i++) {
                        Bucket bucket = buckets.get(i);
                        Limit prior = limits.get(i);
                        int failures =
                                now.isBefore(prior.windowAt.plus(Duration.ofMinutes(15)))
                                        ? prior.failCount
                                        : 0;
                        if (valid) {
                            db.update(
                                    "update admin_auth_limit set"
                                        + " fail_count=0,blocked_until=null,window_at=?,updated_at=?"
                                        + " where action=? and bucket_kind=? and bucket_hash=?",
                                    now,
                                    now,
                                    action,
                                    bucket.kind,
                                    bucket.hash);
                        } else {
                            int next = Math.min(5, failures + 1);
                            db.update(
                                    "update admin_auth_limit set"
                                        + " fail_count=?,blocked_until=?,window_at=?,updated_at=?"
                                        + " where action=? and bucket_kind=? and bucket_hash=?",
                                    next,
                                    next == 5 ? now.plus(Duration.ofMinutes(15)) : null,
                                    failures == 0 ? now : prior.windowAt,
                                    now,
                                    action,
                                    bucket.kind,
                                    bucket.hash);
                        }
                    }
                });
    }

    private record Bucket(String kind, byte[] hash) {}

    private record Limit(int failCount, Instant windowAt, Instant blockedUntil) {}

    private static void validateRef(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_VERIFICATION_REF");
    }

    private static void validateIdentity(String login, String ref) {
        validateRef(ref);
        if (login == null || !login.matches("[a-z0-9][a-z0-9._-]{3,39}"))
            throw AuthException.badRequest("INVALID_LOGIN_ID");
    }

    private void validatePassword(String password) {
        if (password == null
                || password.codePointCount(0, password.length()) < 15
                || password.codePointCount(0, password.length()) > 128
                || password.isBlank()
                || COMMON_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT))
                || password.codePoints().distinct().count() == 1
                || breachedPasswords.isBreached(password))
            throw AuthException.unprocessable("INVALID_PASSWORD");
    }

    private static String aad(UUID key, String field) {
        return "admin-account/" + key + "/" + field + "/v1";
    }
}
