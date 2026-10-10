package com.reasoning.common.member.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.BreachedPasswordChecker;
import com.reasoning.common.auth.service.CryptoService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** LOCAL 가입·인증의 업무 원자성과 현재 효력 검사를 소유한다. 원문 비밀의 재생 저장소는 없다. */
public final class MemberAuthService {
    /** 현재 초과한 모든 제한 창이 끝날 때까지의 서버 산출 대기 초를 제공한다. */
    public static final class RateLimited extends AuthException {
        private final long retryAfterSeconds;

        private RateLimited(long retryAfterSeconds) {
            super(429, "RATE_LIMITED", "RATE_LIMITED");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public record MemberPrincipal(
            long memberId, UUID memberKey, long sessionId, UUID sessionKey, long authRev) {}

    public record TokenPair(
            String tokenType,
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt,
            UUID sessionKey,
            Instant sessionAbsoluteExpiresAt,
            UUID requestId) {}

    public record Issued(TokenPair tokens, MemberPrincipal principal) {}

    public record FlowStart(UUID flowKey, String flowBinder, Instant expiresAt, UUID requestId) {}

    public record Verified(UUID flowKey, String state, Instant expiresAt, UUID requestId) {}

    public record IdentityView(UUID identityKey, String provider) {}

    public record MemberView(
            UUID memberKey,
            String nickname,
            List<IdentityView> identities,
            UUID sessionKey,
            UUID requestId) {}

    public record Logout(String state, UUID requestId) {}

    public record Revoked(Logout response, MemberPrincipal principal) {}

    private record Flow(
            long id,
            UUID key,
            String binderHash,
            String state,
            String lookupHash,
            String codeHash,
            byte[] proof,
            int attempts,
            Instant expires,
            Instant verifiedAt) {}

    private record LocalIdentity(
            long id,
            UUID key,
            long memberId,
            UUID memberKey,
            long authRev,
            String memberState,
            String lookupHash,
            String passwordHash,
            boolean active,
            Instant proofAt) {}

    private record Token(
            long id,
            long sessionId,
            String kind,
            long generation,
            String hash,
            String state,
            Instant expires) {}

    private record Family(
            long id,
            UUID key,
            long memberId,
            long identityId,
            long authRev,
            Instant idle,
            Instant absolute,
            Instant revoked) {}

    private record Account(long id, UUID key, String state, long revision) {}

    private record Bound(Account account, Family family, Token token, LocalIdentity identity) {}

    private record StartWork(
            FlowStart response,
            boolean allowed,
            String code,
            MemberMailTransport.Boundary boundary) {}

    private record Bucket(String scope, String hash, long seconds, int maximum) {}

    private record Result<T>(T value, AuthException failure) {
        static <T> Result<T> ok(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> denied(AuthException failure) {
            return new Result<>(null, failure);
        }
    }

    private final JdbcTemplate db;
    private final CryptoService crypto;
    private final BreachedPasswordChecker breached;
    private final MemberPolicyGate gate;
    private final MemberMailTransport mail;
    private final TransactionTemplate transaction;
    private final TransactionTemplate independent;
    private final Argon2PasswordEncoder passwords =
            new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(MemberAuthService.class);
    private static final String ACCESS = "MEMBER_ACCESS_V1",
            REFRESH = "MEMBER_REFRESH_V1",
            BINDER = "MEMBER_SIGNUP_BINDER_V1";

    public MemberAuthService(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            CryptoService crypto,
            BreachedPasswordChecker breached,
            MemberPolicyGate gate,
            MemberMailTransport mail) {
        this.db = Objects.requireNonNull(db);
        if (!(manager instanceof DataSourceTransactionManager owner)
                || db.getDataSource() == null
                || owner.getDataSource() != db.getDataSource())
            throw new IllegalArgumentException("INVALID_MEMBER_TRANSACTION_MANAGER");
        this.crypto = Objects.requireNonNull(crypto);
        this.breached = Objects.requireNonNull(breached);
        this.gate = Objects.requireNonNull(gate);
        this.mail = Objects.requireNonNull(mail);
        transaction = template(manager);
        independent = template(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        dummyHash = passwords.encode(crypto.randomToken(32));
    }

    private static TransactionTemplate template(PlatformTransactionManager manager) {
        TransactionTemplate value = new TransactionTemplate(manager);
        value.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        value.setTimeout(15);
        return value;
    }

    public FlowStart startSignup(String email, String source, UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    outside();
                    var evidence = gate.prepare();
                    limit("SIGNUP_START", email, source, requestId);
                    String normalized = email(email);
                    StartWork work =
                            committed(
                                    () -> {
                                        var permit = gate.lock(evidence, null);
                                        Instant now = gate.clock();
                                        UUID key = crypto.randomUuid();
                                        String binder = crypto.randomToken(32);
                                        String lookup = hex(crypto.memberEmailHash(normalized));
                                        boolean allowed = identitiesByLookup(lookup).isEmpty();
                                        String code =
                                                String.format(
                                                        Locale.ROOT,
                                                        "%08d",
                                                        random.nextInt(100000000));
                                        long id = reserve("member_flow");
                                        var proof = new ObjectMapper().createObjectNode();
                                        proof.put("formatNo", 1);
                                        proof.put("email", normalized);
                                        proof.put("realm", "LOCAL");
                                        proof.put("lookupVer", 1);
                                        proof.put("signupAllowed", allowed);
                                        byte[] cipher =
                                                encrypt(
                                                        MemberPolicyEvidenceRegistry.canonical(
                                                                proof),
                                                        aad("member_flow", id, "proof_cipher"));
                                        Instant expires = now.plusSeconds(600);
                                        db.update(
                                                "INSERT INTO"
                                                    + " member_flow(id,flow_key,binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,attempt_count,created_at,expires_at)"
                                                    + " OVERRIDING SYSTEM VALUE VALUES"
                                                    + " (?,?,?,'SIGNUP','PENDING','LOCAL',?,?,?,0,?,?)",
                                                id,
                                                key,
                                                hash(BINDER, binder),
                                                lookup,
                                                codeHash(key, code),
                                                cipher,
                                                time(now),
                                                time(expires));
                                        audit("SIGNUP_START", "STARTED", null, requestId);
                                        gate.check(permit);
                                        if (!gate.clock().isBefore(expires))
                                            throw flowUnavailable();
                                        return Result.ok(
                                                new StartWork(
                                                        new FlowStart(
                                                                key, binder, expires, requestId),
                                                        allowed,
                                                        code,
                                                        evidence.mail()));
                                    });
                    if (work.allowed()) mail.sendSignup(normalized, work.code(), work.boundary());
                    return work.response();
                });
    }

    public Verified verifySignup(
            UUID flowKey, String binder, String code, String source, UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    outside();
                    var evidence = gate.prepare();
                    limit("EMAIL_VERIFY", null, source, requestId);
                    flowInput(flowKey, binder);
                    if (code == null || !code.matches("[0-9]{8}")) {
                        return verifyInvalidCode(flowKey, binder, evidence, requestId);
                    }
                    return verifyCode(flowKey, binder, code, evidence, requestId);
                });
    }

    private Verified verifyInvalidCode(
            UUID key,
            String binder,
            MemberPolicyEvidenceRegistry.Snapshot evidence,
            UUID requestId) {
        return verifyCode(key, binder, null, evidence, requestId);
    }

    private Verified verifyCode(
            UUID key,
            String binder,
            String code,
            MemberPolicyEvidenceRegistry.Snapshot evidence,
            UUID requestId) {
        return committed(
                () -> {
                    var permit = gate.lock(evidence, null);
                    Flow flow = boundFlow(key, binder);
                    Instant now = gate.clock();
                    if (flow == null)
                        return denial("EMAIL_VERIFY", "DENIED", null, requestId, flowUnavailable());
                    if (!now.isBefore(flow.expires())) {
                        failFlow(flow);
                        return denial(
                                "EMAIL_VERIFY", "FLOW_EXPIRED", null, requestId, flowUnavailable());
                    }
                    if (!flow.state().equals("PENDING"))
                        return denial(
                                "EMAIL_VERIFY", "FLOW_LOCKED", null, requestId, flowUnavailable());
                    if (code == null || !equal(codeHash(key, code), flow.codeHash())) {
                        int attempts = flow.attempts() + 1;
                        if (attempts >= 5)
                            db.update(
                                    "UPDATE member_flow SET"
                                        + " attempt_count=5,state='FAILED',lookup_hash=NULL,code_hash=NULL,proof_cipher=NULL"
                                        + " WHERE id=?",
                                    flow.id());
                        else
                            db.update(
                                    "UPDATE member_flow SET attempt_count=? WHERE id=?",
                                    attempts,
                                    flow.id());
                        return denial(
                                "EMAIL_VERIFY",
                                attempts >= 5 ? "FLOW_LOCKED" : "DENIED",
                                null,
                                requestId,
                                flowUnavailable());
                    }
                    decodeProof(flow);
                    db.update(
                            "UPDATE member_flow SET state='VERIFIED',code_hash=NULL,verified_at=?"
                                    + " WHERE id=?",
                            time(now),
                            flow.id());
                    audit("EMAIL_VERIFY", "VERIFIED", null, requestId);
                    gate.check(permit);
                    if (!gate.clock().isBefore(flow.expires())) throw flowUnavailable();
                    return Result.ok(new Verified(key, "VERIFIED", flow.expires(), requestId));
                });
    }

    public Issued completeSignup(
            UUID flowKey,
            String binder,
            String password,
            String nickname,
            String policyCode,
            String noticeHash,
            UUID requestKey,
            UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    request(requestKey);
                    outside();
                    flowInput(flowKey, binder);
                    var evidence = gate.prepare();
                    String normalizedNickname = nickname(nickname);
                    password(password);
                    if (breached.isBreached(password))
                        throw AuthException.unprocessable("INVALID_INPUT");
                    String passwordHash = passwords.encode(password);
                    try {
                        return committed(
                                () -> {
                                    var permit = gate.lock(evidence, null);
                                    gate.requireNotice(permit, policyCode, noticeHash);
                                    Flow flow = boundFlow(flowKey, binder);
                                    Instant now = gate.clock();
                                    if (flow == null)
                                        return denial(
                                                "SIGNUP_COMPLETE",
                                                "DENIED",
                                                null,
                                                requestId,
                                                flowUnavailable());
                                    if (!now.isBefore(flow.expires())) {
                                        failFlow(flow);
                                        return denial(
                                                "SIGNUP_COMPLETE",
                                                "FLOW_EXPIRED",
                                                null,
                                                requestId,
                                                flowUnavailable());
                                    }
                                    if (!flow.state().equals("VERIFIED"))
                                        return denial(
                                                "SIGNUP_COMPLETE",
                                                "FLOW_LOCKED",
                                                null,
                                                requestId,
                                                flowUnavailable());
                                    JsonNode proof = decodeProof(flow);
                                    if (!proof.get("signupAllowed").booleanValue()
                                            || !identitiesByLookup(flow.lookupHash()).isEmpty()) {
                                        failFlow(flow);
                                        return denial(
                                                "SIGNUP_COMPLETE",
                                                "IDENTITY_CONFLICT",
                                                null,
                                                requestId,
                                                AuthException.conflict("IDENTITY_UNAVAILABLE"));
                                    }
                                    UUID memberKey = crypto.randomUuid();
                                    Long memberId =
                                            db.queryForObject(
                                                    "INSERT INTO"
                                                        + " member_account(member_key,state,created_at,updated_at)"
                                                        + " VALUES (?,'ACTIVE',?,?) RETURNING id",
                                                    Long.class,
                                                    memberKey,
                                                    time(now),
                                                    time(now));
                                    if (memberId == null) throw unavailable();
                                    db.update(
                                            "INSERT INTO"
                                                + " member_profile(member_id,nickname_cipher,policy_id,accepted_at,created_at,updated_at)"
                                                + " VALUES (?,?,?,?,?,?)",
                                            memberId,
                                            encrypt(
                                                    normalizedNickname,
                                                    aad(
                                                            "member_profile",
                                                            memberId,
                                                            "nickname_cipher")),
                                            permit.current().id(),
                                            time(now),
                                            time(now),
                                            time(now));
                                    long identityId = reserve("member_identity");
                                    UUID identityKey = crypto.randomUuid();
                                    db.update(
                                            "INSERT INTO"
                                                + " member_identity(id,identity_key,member_id,provider,realm,lookup_hash,lookup_ver,subject_cipher,password_hash,active_yn,proof_at,bound_at,created_at,updated_at)"
                                                + " OVERRIDING SYSTEM VALUE VALUES"
                                                + " (?,?,?,'LOCAL','LOCAL',?,1,?,?,true,?,?,?,?)",
                                            identityId,
                                            identityKey,
                                            memberId,
                                            flow.lookupHash(),
                                            encrypt(
                                                    proof.get("email").textValue(),
                                                    aad(
                                                            "member_identity",
                                                            identityId,
                                                            "subject_cipher")),
                                            passwordHash,
                                            time(flow.verifiedAt()),
                                            time(now),
                                            time(now),
                                            time(now));
                                    Account account = new Account(memberId, memberKey, "ACTIVE", 0);
                                    Issued issued = newFamily(account, identityId, requestId);
                                    db.update(
                                            "UPDATE member_flow SET"
                                                + " state='CONSUMED',consumed_at=?,lookup_hash=NULL,code_hash=NULL,proof_cipher=NULL"
                                                + " WHERE id=?",
                                            time(gate.clock()),
                                            flow.id());
                                    audit(
                                            "SIGNUP_COMPLETE",
                                            "CREATED",
                                            issued.principal(),
                                            requestId);
                                    gate.check(permit);
                                    finalIssued(issued);
                                    if (!gate.clock().isBefore(flow.expires()))
                                        throw flowUnavailable();
                                    return Result.ok(issued);
                                });
                    } catch (org.springframework.dao.DuplicateKeyException collision) {
                        independentAudit("SIGNUP_COMPLETE", "IDENTITY_CONFLICT", null, requestId);
                        throw AuthException.conflict("IDENTITY_UNAVAILABLE");
                    }
                });
    }

    public Issued login(String email, String password, String source, UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    outside();
                    var evidence = gate.prepare();
                    limit("LOGIN_LOCAL", email, source, requestId);
                    String normalized;
                    try {
                        normalized = email(email);
                    } catch (AuthException invalid) {
                        passwords.matches(validDummyInput(password), dummyHash);
                        independentAudit("LOGIN_LOCAL", "DENIED", null, requestId);
                        throw memberRequired();
                    }
                    String lookup = hex(crypto.memberEmailHash(normalized));
                    List<LocalIdentity> candidates = identitiesByLookup(lookup);
                    LocalIdentity before = candidates.size() == 1 ? candidates.getFirst() : null;
                    boolean eligible =
                            before != null
                                    && before.active()
                                    && before.memberState().equals("ACTIVE")
                                    && before.proofAt() != null;
                    boolean validInput = validPassword(password);
                    boolean matched =
                            passwords.matches(
                                    validInput ? password : validDummyInput(password),
                                    eligible && argon(before.passwordHash())
                                            ? before.passwordHash()
                                            : dummyHash);
                    if (!eligible || !validInput || !matched) {
                        independentAudit("LOGIN_LOCAL", "DENIED", null, requestId);
                        throw memberRequired();
                    }
                    return committed(
                            () -> {
                                var permit = gate.lock(evidence, before.memberId());
                                Account account = account(before.memberId(), true);
                                LocalIdentity current = identity(before.id(), true);
                                if (account == null
                                        || current == null
                                        || !account.state().equals("ACTIVE")
                                        || !current.active()
                                        || current.memberId() != account.id()
                                        || !current.lookupHash().equals(lookup)
                                        || !current.passwordHash().equals(before.passwordHash())
                                        || account.revision() != before.authRev()
                                        || current.proofAt() == null)
                                    return denial(
                                            "LOGIN_LOCAL",
                                            "DENIED",
                                            null,
                                            requestId,
                                            memberRequired());
                                gate.checkPinned(permit, account.id());
                                Issued issued = newFamily(account, current.id(), requestId);
                                audit(
                                        "LOGIN_LOCAL",
                                        "AUTHENTICATED",
                                        issued.principal(),
                                        requestId);
                                gate.check(permit);
                                finalIssued(issued);
                                return Result.ok(issued);
                            });
                });
    }

    public Issued refresh(String refreshToken, UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    outside();
                    secret(refreshToken, "REFRESH_UNAVAILABLE");
                    Token provisional = token(hash(REFRESH, refreshToken), false);
                    if (provisional == null || !provisional.kind().equals("REFRESH"))
                        throw refreshUnavailable();
                    Family family = family(provisional.sessionId(), false);
                    if (family == null) throw refreshUnavailable();
                    if (provisional.state().equals("USED")) {
                        revokeIndependently(
                                family.memberId(),
                                family.id(),
                                "REFRESH_REUSED",
                                "REFRESH",
                                requestId);
                        throw refreshUnavailable();
                    }
                    var evidence = gate.prepare();
                    return committed(
                            () -> {
                                var permit = gate.lock(evidence, family.memberId());
                                Bound bound =
                                        lockBound(
                                                refreshToken,
                                                "REFRESH",
                                                family.memberId(),
                                                family.id());
                                if (bound != null && bound.token().state().equals("USED")) {
                                    revoke(bound.family().id(), "REFRESH_REUSED");
                                    return Result.denied(refreshUnavailable());
                                }
                                if (!valid(bound, "REFRESH", false))
                                    return denial(
                                            "REFRESH",
                                            "DENIED",
                                            null,
                                            requestId,
                                            refreshUnavailable());
                                gate.checkPinned(permit, bound.account().id());
                                Instant now = gate.clock();
                                db.update(
                                        "UPDATE member_token SET state='USED',used_at=? WHERE id=?"
                                                + " AND state='ISSUED'",
                                        time(now),
                                        bound.token().id());
                                Instant idle =
                                        minimum(
                                                now.plusSeconds(2592000),
                                                bound.family().absolute());
                                db.update(
                                        "UPDATE member_session SET last_refresh_at=?,idle_until=?"
                                                + " WHERE id=?",
                                        time(now),
                                        time(idle),
                                        bound.family().id());
                                Issued issued =
                                        pair(
                                                bound.account(),
                                                bound.family(),
                                                bound.token().generation() + 1,
                                                now,
                                                requestId);
                                audit("REFRESH", "ROTATED", issued.principal(), requestId);
                                gate.check(permit);
                                finalIssued(issued);
                                return Result.ok(issued);
                            },
                            () -> {
                                Token latest = token(hash(REFRESH, refreshToken), false);
                                if (latest != null && latest.state().equals("USED"))
                                    revokeIndependently(
                                            family.memberId(),
                                            family.id(),
                                            "REFRESH_REUSED",
                                            "REFRESH",
                                            requestId);
                            });
                });
    }

    public MemberPrincipal authenticate(String accessToken, boolean revocationOnly) {
        return safe(
                () -> {
                    outside();
                    secret(accessToken, "MEMBER_AUTH_REQUIRED");
                    Token provisional = token(hash(ACCESS, accessToken), false);
                    if (provisional == null || !provisional.kind().equals("ACCESS"))
                        throw memberRequired();
                    Family family = family(provisional.sessionId(), false);
                    if (family == null) throw memberRequired();
                    var evidence = revocationOnly ? null : gate.prepare();
                    return committed(
                            () -> {
                                var permit =
                                        revocationOnly
                                                ? null
                                                : gate.lock(evidence, family.memberId());
                                Bound bound =
                                        lockBound(
                                                accessToken,
                                                "ACCESS",
                                                family.memberId(),
                                                family.id());
                                if (!valid(bound, "ACCESS", revocationOnly))
                                    return Result.denied(memberRequired());
                                if (permit != null) {
                                    gate.checkPinned(permit, bound.account().id());
                                    gate.check(permit);
                                }
                                return Result.ok(principal(bound.account(), bound.family()));
                            });
                });
    }

    public MemberView getMe(String accessToken, UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    outside();
                    secret(accessToken, "MEMBER_AUTH_REQUIRED");
                    Token provisional = token(hash(ACCESS, accessToken), false);
                    if (provisional == null || !provisional.kind().equals("ACCESS"))
                        throw memberRequired();
                    Family family = family(provisional.sessionId(), false);
                    if (family == null) throw memberRequired();
                    var evidence = gate.prepare();
                    return committed(
                            () -> {
                                var permit = gate.lock(evidence, family.memberId());
                                Bound bound =
                                        lockBound(
                                                accessToken,
                                                "ACCESS",
                                                family.memberId(),
                                                family.id());
                                if (!valid(bound, "ACCESS", false))
                                    return denial(
                                            "ME_READ", "DENIED", null, requestId, memberRequired());
                                gate.checkPinned(permit, bound.account().id());
                                MemberPrincipal principal =
                                        principal(bound.account(), bound.family());
                                audit("ME_READ", "AUTHENTICATED", principal, requestId);
                                byte[] cipher =
                                        db.queryForObject(
                                                "SELECT nickname_cipher FROM member_profile WHERE"
                                                        + " member_id=?",
                                                byte[].class,
                                                principal.memberId());
                                String nickname =
                                        decrypt(
                                                cipher,
                                                aad(
                                                        "member_profile",
                                                        principal.memberId(),
                                                        "nickname_cipher"));
                                if (!nickname(nickname).equals(nickname)) throw unavailable();
                                List<IdentityView> identities =
                                        db.query(
                                                "SELECT identity_key,provider FROM member_identity"
                                                        + " WHERE member_id=? AND active_yn AND"
                                                        + " provider='LOCAL' AND realm='LOCAL' AND"
                                                        + " proof_at IS NOT NULL ORDER BY id",
                                                (rs, row) ->
                                                        new IdentityView(
                                                                rs.getObject(1, UUID.class),
                                                                rs.getString(2)),
                                                principal.memberId());
                                if (identities.size() != 1) throw memberRequired();
                                gate.check(permit);
                                if (!valid(bound, "ACCESS", false)) throw memberRequired();
                                return Result.ok(
                                        new MemberView(
                                                principal.memberKey(),
                                                nickname,
                                                List.copyOf(identities),
                                                principal.sessionKey(),
                                                requestId));
                            });
                });
    }

    public Logout logout(String accessToken, UUID requestKey, UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    request(requestKey);
                    outside();
                    secret(accessToken, "MEMBER_AUTH_REQUIRED");
                    Token provisional = token(hash(ACCESS, accessToken), false);
                    if (provisional == null || !provisional.kind().equals("ACCESS"))
                        throw memberRequired();
                    Family family = family(provisional.sessionId(), false);
                    if (family == null) throw memberRequired();
                    MemberPrincipal principal =
                            committed(
                                    () -> {
                                        Bound bound =
                                                lockBound(
                                                        accessToken,
                                                        "ACCESS",
                                                        family.memberId(),
                                                        family.id());
                                        if (!valid(bound, "ACCESS", true))
                                            return Result.denied(memberRequired());
                                        MemberPrincipal verified =
                                                principal(bound.account(), bound.family());
                                        revoke(bound.family().id(), "LOGOUT");
                                        return Result.ok(verified);
                                    });
                    try {
                        independentAudit("LOGOUT", "LOGGED_OUT", principal, requestId);
                    } catch (RuntimeException failure) {
                        emergency();
                    }
                    return new Logout("LOGGED_OUT", requestId);
                });
    }

    /**
     * 원래 만료 전 ISSUED 또는 USED refresh 증명으로만 family를 회수하며 정책 수집은 재개하지 않는다.
     *
     * @param refreshToken canonical 32바이트 refresh 원문이며 저장·감사하지 않는다
     * @param requestKey null이 아닌 UUID v4 요청 키
     * @param requestId null이 아닌 서버 감사 요청 키
     * @return 회수를 완료한 상태와 검증된 감사 주체
     */
    public Revoked revokeByRefresh(String refreshToken, UUID requestKey, UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    request(requestKey);
                    if (requestKey.version() != 4 || requestKey.variant() != 2)
                        throw AuthException.badRequest("INVALID_REQUEST");
                    outside();
                    secret(refreshToken, "REFRESH_UNAVAILABLE");
                    Token provisional = token(hash(REFRESH, refreshToken), false);
                    if (provisional == null || !provisional.kind().equals("REFRESH"))
                        throw refreshUnavailable();
                    Family family = family(provisional.sessionId(), false);
                    if (family == null) throw refreshUnavailable();
                    MemberPrincipal verified =
                            committed(
                                    () -> {
                                        Bound bound =
                                                lockBound(
                                                        refreshToken,
                                                        "REFRESH",
                                                        family.memberId(),
                                                        family.id());
                                        if (!validRefreshRevocation(bound))
                                            return Result.denied(refreshUnavailable());
                                        MemberPrincipal actor =
                                                principal(bound.account(), bound.family());
                                        revoke(bound.family().id(), "LOGOUT");
                                        return Result.ok(actor);
                                    });
                    try {
                        independentAudit("LOGOUT", "LOGGED_OUT", verified, requestId);
                    } catch (RuntimeException failure) {
                        emergency();
                    }
                    return new Revoked(new Logout("LOGGED_OUT", requestId), verified);
                });
    }

    /** 소비된 증명도 회수에만 허용하며 발급·업무 인증의 valid 조건을 변경하지 않는다. */
    private boolean validRefreshRevocation(Bound bound) {
        if (bound == null
                || !bound.token().kind().equals("REFRESH")
                || !(bound.token().state().equals("ISSUED") || bound.token().state().equals("USED"))
                || !bound.account().state().equals("ACTIVE")
                || bound.family().revoked() != null
                || bound.family().authRev() != bound.account().revision()
                || bound.identity() == null
                || bound.identity().memberId() != bound.account().id()
                || !bound.identity().active()
                || bound.identity().proofAt() == null) return false;
        Instant now = gate.clock();
        return now.isBefore(bound.token().expires())
                && now.isBefore(bound.family().idle())
                && now.isBefore(bound.family().absolute());
    }

    public MemberPolicyGate.Notice getNotice(UUID requestId) {
        return safe(
                () -> {
                    request(requestId);
                    outside();
                    var evidence = gate.prepare();
                    return committed(
                            () -> Result.ok(gate.notice(gate.lock(evidence, null), requestId)));
                });
    }

    private Issued newFamily(Account account, long identityId, UUID requestId) {
        Instant now = gate.clock(),
                absolute = now.plusSeconds(7776000),
                idle = now.plusSeconds(2592000);
        UUID key = crypto.randomUuid();
        Long id =
                db.queryForObject(
                        "INSERT INTO"
                            + " member_session(session_key,member_id,identity_id,auth_rev,created_at,last_refresh_at,idle_until,absolute_until)"
                            + " VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                        Long.class,
                        key,
                        account.id(),
                        identityId,
                        account.revision(),
                        time(now),
                        time(now),
                        time(idle),
                        time(absolute));
        if (id == null) throw unavailable();
        return pair(
                account,
                new Family(
                        id,
                        key,
                        account.id(),
                        identityId,
                        account.revision(),
                        idle,
                        absolute,
                        null),
                0,
                now,
                requestId);
    }

    private Issued pair(
            Account account, Family family, long generation, Instant now, UUID requestId) {
        String access = crypto.randomToken(32), refresh = crypto.randomToken(32);
        Instant accessExpiry = minimum(now.plusSeconds(300), family.absolute());
        Instant refreshExpiry = minimum(now.plusSeconds(2592000), family.absolute());
        if (!now.isBefore(accessExpiry) || !now.isBefore(refreshExpiry)) throw refreshUnavailable();
        db.update(
                "INSERT INTO"
                    + " member_token(session_id,kind,generation,token_hash,state,issued_at,expires_at)"
                    + " VALUES (?,'ACCESS',?,?,'ISSUED',?,?)",
                family.id(),
                generation,
                hash(ACCESS, access),
                time(now),
                time(accessExpiry));
        db.update(
                "INSERT INTO"
                    + " member_token(session_id,kind,generation,token_hash,state,issued_at,expires_at)"
                    + " VALUES (?,'REFRESH',?,?,'ISSUED',?,?)",
                family.id(),
                generation,
                hash(REFRESH, refresh),
                time(now),
                time(refreshExpiry));
        return new Issued(
                new TokenPair(
                        "Bearer",
                        access,
                        accessExpiry,
                        refresh,
                        refreshExpiry,
                        family.key(),
                        family.absolute(),
                        requestId),
                principal(account, family));
    }

    private void finalIssued(Issued issued) {
        Bound bound =
                lockBound(
                        issued.tokens().accessToken(),
                        "ACCESS",
                        issued.principal().memberId(),
                        issued.principal().sessionId());
        if (!valid(bound, "ACCESS", false)
                || !gate.clock().isBefore(issued.tokens().refreshExpiresAt())) throw unavailable();
    }

    private Bound lockBound(String raw, String kind, long memberId, long sessionId) {
        Account account = account(memberId, true);
        Family family = family(sessionId, true);
        Token token = token(hash(kind.equals("ACCESS") ? ACCESS : REFRESH, raw), true);
        if (account == null
                || family == null
                || token == null
                || family.memberId() != account.id()
                || token.sessionId() != family.id()
                || !token.kind().equals(kind)) return null;
        LocalIdentity identity = identity(family.identityId(), true);
        return new Bound(account, family, token, identity);
    }

    private boolean valid(Bound bound, String kind, boolean revocationOnly) {
        if (bound == null
                || !bound.token().kind().equals(kind)
                || !bound.token().state().equals("ISSUED")
                || !bound.account().state().equals("ACTIVE")
                || bound.family().revoked() != null
                || bound.family().authRev() != bound.account().revision()
                || bound.identity() == null
                || bound.identity().memberId() != bound.account().id()
                || !bound.identity().active()
                || bound.identity().proofAt() == null) return false;
        Instant now = gate.clock();
        return now.isBefore(bound.token().expires())
                && now.isBefore(bound.family().idle())
                && now.isBefore(bound.family().absolute());
    }

    private Flow boundFlow(UUID key, String binder) {
        List<Flow> values =
                db.query(
                        "SELECT"
                            + " id,flow_key,binder_hash,state,lookup_hash,code_hash,proof_cipher,attempt_count,expires_at,verified_at"
                            + " FROM member_flow WHERE flow_key=? AND binder_hash=? AND"
                            + " purpose='SIGNUP' AND provider='LOCAL' FOR UPDATE",
                        (rs, row) ->
                                new Flow(
                                        rs.getLong(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getBytes(7),
                                        rs.getInt(8),
                                        instant(rs, 9),
                                        instant(rs, 10)),
                        key,
                        hash(BINDER, binder));
        return values.size() == 1 ? values.getFirst() : null;
    }

    private JsonNode decodeProof(Flow flow) {
        JsonNode proof =
                MemberPolicyEvidenceRegistry.parse(
                        decrypt(flow.proof(), aad("member_flow", flow.id(), "proof_cipher"))
                                .getBytes(StandardCharsets.UTF_8));
        MemberPolicyEvidenceRegistry.exact(
                proof, "formatNo", "email", "realm", "lookupVer", "signupAllowed");
        MemberPolicyEvidenceRegistry.integer(proof, "formatNo", 1);
        MemberPolicyEvidenceRegistry.integer(proof, "lookupVer", 1);
        String email = MemberPolicyEvidenceRegistry.text(proof, "email");
        if (!MemberPolicyEvidenceRegistry.text(proof, "realm").equals("LOCAL")
                || !proof.get("signupAllowed").isBoolean()
                || !email(email).equals(email)
                || !equal(hex(crypto.memberEmailHash(email)), flow.lookupHash()))
            throw unavailable();
        return proof;
    }

    private void failFlow(Flow flow) {
        if (flow.state().equals("PENDING") || flow.state().equals("VERIFIED"))
            db.update(
                    "UPDATE member_flow SET"
                        + " state='FAILED',lookup_hash=NULL,code_hash=NULL,proof_cipher=NULL WHERE"
                        + " id=?",
                    flow.id());
    }

    private List<LocalIdentity> identitiesByLookup(String lookup) {
        return identities("i.lookup_hash=? AND i.active_yn", false, lookup);
    }

    private LocalIdentity identity(long id, boolean lock) {
        List<LocalIdentity> values = identities("i.id=?", lock, id);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private List<LocalIdentity> identities(String condition, boolean lock, Object... parameters) {
        return db.query(
                "SELECT"
                    + " i.id,i.identity_key,i.member_id,a.member_key,a.auth_rev,a.state,i.lookup_hash,i.password_hash,i.active_yn,i.proof_at"
                    + " FROM member_identity i JOIN member_account a ON a.id=i.member_id WHERE"
                    + " i.provider='LOCAL' AND i.realm='LOCAL' AND i.lookup_ver=1 AND "
                        + condition
                        + (lock ? " FOR UPDATE OF i" : ""),
                (rs, row) ->
                        new LocalIdentity(
                                rs.getLong(1),
                                rs.getObject(2, UUID.class),
                                rs.getLong(3),
                                rs.getObject(4, UUID.class),
                                rs.getLong(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getString(8),
                                rs.getBoolean(9),
                                instant(rs, 10)),
                parameters);
    }

    private Account account(long id, boolean lock) {
        List<Account> values =
                db.query(
                        "SELECT id,member_key,state,auth_rev FROM member_account WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, row) ->
                                new Account(
                                        rs.getLong(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getLong(4)),
                        id);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private Family family(long id, boolean lock) {
        List<Family> values =
                db.query(
                        "SELECT"
                            + " id,session_key,member_id,identity_id,auth_rev,idle_until,absolute_until,revoked_at"
                            + " FROM member_session WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, row) ->
                                new Family(
                                        rs.getLong(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getLong(5),
                                        instant(rs, 6),
                                        instant(rs, 7),
                                        instant(rs, 8)),
                        id);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private Token token(String hash, boolean lock) {
        List<Token> values =
                db.query(
                        "SELECT id,session_id,kind,generation,token_hash,state,expires_at FROM"
                                + " member_token WHERE token_hash=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, row) ->
                                new Token(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        instant(rs, 7)),
                        hash);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private void limit(String action, String email, String source, UUID requestId) {
        if (source == null || source.length() > 45 || !source.matches("[0-9A-Fa-f:.]+"))
            throw AuthException.badRequest("INVALID_REQUEST");
        List<Bucket> buckets = new ArrayList<>();
        if (action.equals("SIGNUP_START")) {
            buckets.add(bucket("SIGNUP_EMAIL_15M", bucketEmail(email), 900, 3));
            buckets.add(bucket("SIGNUP_EMAIL_24H", bucketEmail(email), 86400, 10));
            buckets.add(bucket("SIGNUP_SOURCE_15M", source, 900, 30));
        } else if (action.equals("LOGIN_LOCAL")) {
            buckets.add(bucket("LOGIN_EMAIL_15M", bucketEmail(email), 900, 5));
            buckets.add(bucket("LOGIN_SOURCE_15M", source, 900, 30));
        } else buckets.add(bucket("VERIFY_SOURCE_15M", source, 900, 30));
        buckets.sort(Comparator.comparing(Bucket::scope).thenComparing(Bucket::hash));
        Long blockedSeconds =
                independent.execute(
                        status -> {
                            locks();
                            Instant now = gate.clock();
                            long retryAfter = 0;
                            for (Bucket bucket : buckets) {
                                db.update(
                                        "INSERT INTO"
                                            + " member_auth_limit(scope,bucket_hash,window_at,hit_count,purge_at)"
                                            + " VALUES (?,?,?,0,?) ON CONFLICT (scope,bucket_hash)"
                                            + " DO NOTHING",
                                        bucket.scope(),
                                        bucket.hash(),
                                        time(now),
                                        time(now.plusSeconds(bucket.seconds())));
                                var row =
                                        db.queryForMap(
                                                "SELECT window_at,hit_count,purge_at FROM"
                                                        + " member_auth_limit WHERE scope=? AND"
                                                        + " bucket_hash=? FOR UPDATE",
                                                bucket.scope(),
                                                bucket.hash());
                                Instant window = ((Timestamp) row.get("window_at")).toInstant(),
                                        purge = ((Timestamp) row.get("purge_at")).toInstant();
                                int hits = ((Number) row.get("hit_count")).intValue();
                                if (!now.isBefore(purge)) {
                                    window = now;
                                    purge = now.plusSeconds(bucket.seconds());
                                    hits = 0;
                                }
                                int next = hits == Integer.MAX_VALUE ? hits : hits + 1;
                                boolean reject = next > bucket.maximum();
                                if (reject) {
                                    long remaining =
                                            java.time.Duration.between(now, purge).toMillis();
                                    retryAfter =
                                            Math.max(
                                                    retryAfter,
                                                    Math.max(1, (remaining + 999) / 1000));
                                }
                                db.update(
                                        "UPDATE member_auth_limit SET"
                                            + " window_at=?,hit_count=?,blocked_until=?,purge_at=?"
                                            + " WHERE scope=? AND bucket_hash=?",
                                        time(window),
                                        next,
                                        reject ? time(purge) : null,
                                        time(purge),
                                        bucket.scope(),
                                        bucket.hash());
                            }
                            return retryAfter;
                        });
        if (blockedSeconds != null && blockedSeconds > 0) {
            independentAudit(action, "RATE_LIMITED", null, requestId);
            throw new RateLimited(blockedSeconds);
        }
    }

    private Bucket bucket(String scope, String value, long seconds, int maximum) {
        return new Bucket(scope, hex(crypto.limitHash(scope, value)), seconds, maximum);
    }

    private static String bucketEmail(String email) {
        try {
            return email(email);
        } catch (AuthException failure) {
            return "INVALID_EMAIL";
        }
    }

    private void revoke(long sessionId, String reason) {
        db.update(
                "UPDATE member_session SET revoked_at=clock_timestamp(),revoke_code=? WHERE id=?"
                        + " AND revoked_at IS NULL",
                reason,
                sessionId);
        db.update(
                "UPDATE member_token SET state='REVOKED' WHERE session_id=? AND state='ISSUED'",
                sessionId);
    }

    private void revokeIndependently(
            long memberId, long sessionId, String reason, String action, UUID requestId) {
        MemberPrincipal verified =
                independent.execute(
                        status -> {
                            locks();
                            Account account = account(memberId, true);
                            Family family = family(sessionId, true);
                            if (account == null
                                    || family == null
                                    || family.memberId() != account.id()) throw unavailable();
                            revoke(sessionId, reason);
                            return principal(account, family);
                        });
        try {
            independentAudit(action, "REFRESH_REUSED", verified, requestId);
        } catch (RuntimeException failure) {
            emergency();
        }
    }

    private static void emergency() {
        LOG.warn("MEMBER_SECURITY_AUDIT_UNAVAILABLE");
    }

    private void audit(String action, String result, MemberPrincipal principal, UUID requestId) {
        Instant now = gate.clock();
        db.update(
                "INSERT INTO"
                    + " member_auth_audit(event_key,member_id,request_id,action,result_code,http_status,created_at,purge_at,auth_rev)"
                    + " VALUES (?,?,?,?,?,NULL,?,?,?)",
                crypto.randomUuid(),
                principal == null ? null : principal.memberId(),
                requestId,
                action,
                result,
                time(now),
                time(now.plusSeconds(7776000)),
                principal == null ? null : principal.authRev());
    }

    private void independentAudit(
            String action, String result, MemberPrincipal principal, UUID requestId) {
        independent.executeWithoutResult(
                status -> {
                    locks();
                    audit(action, result, principal, requestId);
                });
    }

    private <T> Result<T> denial(
            String action,
            String result,
            MemberPrincipal principal,
            UUID requestId,
            AuthException failure) {
        audit(action, result, principal, requestId);
        return Result.denied(failure);
    }

    private <T> T committed(Supplier<Result<T>> operation) {
        return committed(operation, null);
    }

    private <T> T committed(Supplier<Result<T>> operation, Runnable onFailure) {
        Result<T> result;
        try {
            result =
                    transaction.execute(
                            status -> {
                                locks();
                                return operation.get();
                            });
        } catch (RuntimeException failure) {
            if (onFailure != null) onFailure.run();
            throw failure;
        }
        if (result == null) throw unavailable();
        if (result.failure() != null) {
            if (onFailure != null) onFailure.run();
            throw result.failure();
        }
        return result.value();
    }

    private void locks() {
        db.execute("SET LOCAL lock_timeout='5s'");
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }

    private static <T> T safe(Supplier<T> action) {
        try {
            return action.get();
        } catch (RateLimited failure) {
            throw failure;
        } catch (AuthException failure) {
            throw new AuthException(failure.status(), failure.code(), failure.code());
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private long reserve(String table) {
        if (!table.equals("member_flow") && !table.equals("member_identity")) throw unavailable();
        Long value =
                db.queryForObject(
                        "SELECT nextval(pg_get_serial_sequence(?, 'id'))", Long.class, table);
        if (value == null) throw unavailable();
        return value;
    }

    private byte[] encrypt(String value, String aad) {
        return crypto.encrypt(value, aad).getBytes(StandardCharsets.UTF_8);
    }

    private String decrypt(byte[] bytes, String aad) {
        if (bytes == null) throw unavailable();
        try {
            String envelope =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            return crypto.decrypt(envelope, aad);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static String aad(String table, long id, String field) {
        return table + "/" + id + "/" + field + "/v1";
    }

    private String hash(String purpose, String raw) {
        return hex(crypto.tokenHash(purpose, raw));
    }

    private String codeHash(UUID flow, String code) {
        return hex(crypto.limitHash("MEMBER_SIGNUP_CODE_V1", flow + ":" + code));
    }

    private boolean equal(String left, String right) {
        return left != null
                && right != null
                && crypto.constantEquals(
                        left.getBytes(StandardCharsets.US_ASCII),
                        right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static Timestamp time(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant minimum(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static MemberPrincipal principal(Account account, Family family) {
        return new MemberPrincipal(
                account.id(), account.key(), family.id(), family.key(), account.revision());
    }

    private static void request(UUID value) {
        if (value == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static void flowInput(UUID key, String binder) {
        if (key == null) throw flowUnavailable();
        secret(binder, "FLOW_UNAVAILABLE");
    }

    private static void secret(String raw, String code) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}"))
            throw AuthException.unauthorized(code);
        try {
            if (java.util.Base64.getUrlDecoder().decode(raw).length != 32
                    || !java.util.Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(java.util.Base64.getUrlDecoder().decode(raw))
                            .equals(raw)) throw AuthException.unauthorized(code);
        } catch (IllegalArgumentException failure) {
            throw AuthException.unauthorized(code);
        }
    }

    private static boolean argon(String hash) {
        return hash != null
                && hash.matches(
                        "\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    }

    private static boolean validPassword(String password) {
        if (password == null) return false;
        int count = password.codePointCount(0, password.length());
        if (count < 15 || count > 128) return false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= password.length() || !Character.isLowSurrogate(password.charAt(i)))
                    return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static void password(String value) {
        if (!validPassword(value)) throw AuthException.unprocessable("INVALID_INPUT");
    }

    private static String validDummyInput(String password) {
        return validPassword(password) ? password : "invalid-member-password-input";
    }

    private static String nickname(String value) {
        if (value == null) throw AuthException.unprocessable("INVALID_INPUT");
        String result = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        int count = result.codePointCount(0, result.length());
        if (count < 2
                || count > 20
                || result.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
            throw AuthException.unprocessable("INVALID_INPUT");
        return result;
    }

    private static String email(String value) {
        if (value == null
                || value.length() > 254
                || !value.equals(value.strip())
                || value.chars().anyMatch(c -> c < 33 || c > 126))
            throw AuthException.unprocessable("INVALID_INPUT");
        int at = value.indexOf('@');
        if (at < 1 || at > 64 || at != value.lastIndexOf('@'))
            throw AuthException.unprocessable("INVALID_INPUT");
        String local = value.substring(0, at),
                domain = value.substring(at + 1).toLowerCase(Locale.ROOT);
        if (!local.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+")
                || local.startsWith(".")
                || local.endsWith(".")
                || local.contains("..")
                || domain.length() > 253
                || domain.isEmpty()) throw AuthException.unprocessable("INVALID_INPUT");
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) throw AuthException.unprocessable("INVALID_INPUT");
        for (String label : labels)
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                throw AuthException.unprocessable("INVALID_INPUT");
        return local + "@" + domain;
    }

    private static AuthException memberRequired() {
        return AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
    }

    private static AuthException flowUnavailable() {
        return AuthException.unauthorized("FLOW_UNAVAILABLE");
    }

    private static AuthException refreshUnavailable() {
        return AuthException.unauthorized("REFRESH_UNAVAILABLE");
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("AUTH_UNAVAILABLE");
    }
}
