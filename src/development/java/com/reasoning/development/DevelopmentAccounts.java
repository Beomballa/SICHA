package com.reasoning.development;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.reasoning.admin.auth.service.AdminAccountService;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.TotpService;
import com.reasoning.common.member.auth.MemberAuthConfiguration;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.member.auth.MemberMailTransport;
import com.reasoning.common.member.auth.MemberPolicyGate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 실제 등록·암호화·회원 정책을 사용하는 격리 개발 계정. 운영 산출물에는 포함하지 않는다. */
@Component
@ConditionalOnProperty(name = "app.development.enabled", havingValue = "true")
public final class DevelopmentAccounts implements ApplicationRunner {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String LOGIN = "synthetic-development";
    private static final String REF = "SYNTHETIC_DEVELOPMENT";
    private final DevelopmentState state;
    private final JdbcTemplate db;
    private final EnrollmentService enrollment;
    private final TotpService totp;
    private final CryptoService crypto;
    private final AdminSessionAdapter sessions;
    private final AdminAccountService administrators;
    private final MemberAuthService members;
    private final MemberPolicyGate gate;
    private final GreenMail smtp;
    private final TransactionTemplate transaction;
    private final DevelopmentPolicy policy;
    private volatile boolean ready;
    private JsonNode credentials;
    private long administratorId;
    private UUID administratorKey;

    /** 별도 개발 상태·실제 등록/인증·암호화·정책·소유자 전용 메일과 거래 서비스를 주입한다. */
    public DevelopmentAccounts(
            DevelopmentState state,
            JdbcTemplate db,
            EnrollmentService enrollment,
            TotpService totp,
            CryptoService crypto,
            AdminSessionAdapter sessions,
            AdminAccountService administrators,
            MemberAuthService members,
            MemberPolicyGate gate,
            @Qualifier("developmentSmtp") GreenMail smtp,
            PlatformTransactionManager manager,
            MemberAuthConfiguration.Properties configuration,
            AuthProperties keys,
            MemberMailTransport mail) {
        this.state = state;
        this.db = db;
        this.enrollment = enrollment;
        this.totp = totp;
        this.crypto = crypto;
        this.sessions = sessions;
        this.administrators = administrators;
        this.members = members;
        this.gate = gate;
        this.smtp = smtp;
        this.transaction = new TransactionTemplate(manager);
        this.policy = new DevelopmentPolicy(state, db, configuration, keys, mail);
    }

    /** DB 소유 경계를 먼저 검사하고 재시작해도 기존 자격·자료를 변경하지 않는다. */
    @Override
    public synchronized void run(ApplicationArguments arguments) throws Exception {
        state.requireDatabase(db);
        Path secret = state.home().resolve("synthetic-accounts.json");
        if (!Files.exists(secret, LinkOption.NOFOLLOW_LINKS)) {
            if (db.queryForObject("SELECT count(*) FROM admin_account", Long.class) != 0
                    || db.queryForObject("SELECT count(*) FROM member_account", Long.class) != 0)
                throw new IllegalStateException("개발 계정 지도 없는 기존 계정은 재사용하지 않음");
            save(
                    "synthetic-accounts.json",
                    Map.of(
                            "formatNo",
                            1,
                            "administratorPassword",
                            password(),
                            "a",
                            Map.of("email", "synthetic-a@example.invalid", "password", password()),
                            "b",
                            Map.of(
                                    "email",
                                    "synthetic-b@example.invalid",
                                    "password",
                                    password())));
        }
        credentials = load("synthetic-accounts.json");
        com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry.exact(
                credentials, "formatNo", "administratorPassword", "a", "b");
        com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry.integer(
                credentials, "formatNo", 1);
        seedAdministrator();
        transaction.executeWithoutResult(
                status -> {
                    try {
                        policy.install(administratorId);
                        policy.installFriendly(administratorId);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
        // 실제 요청 시와 같은 정책 파일·소유자·DB 효력 검사를 수행한다.
        transaction.executeWithoutResult(status -> gate.check(gate.lock(gate.prepare(), null)));
        seedMember("a");
        seedMember("b");
        ready = true;
    }

    /** 모든 실제 계정과 합성 정책의 초기화가 끝났는지 반환한다. */
    public boolean isReady() {
        return ready;
    }

    /**
     * 같은 출처 POST에서만 호출할 관리자 진입점이다.
     *
     * @param request 경계 검사 대상 요청
     * @param response 세션 쿠키 응답
     */
    public void loginAdministrator(HttpServletRequest request, HttpServletResponse response) {
        requireReady();
        state.requireRequest(request, true);
        var issued = issueSession();
        sessions.issueCookie(issued.id(), request, response);
    }

    /**
     * 알려진 합성 계정의 개발 발급 세션만 갱신한다. 만료·회수 세대·다른 계정은 변경하지 않는다.
     *
     * @param request 현재 요청
     */
    public void refreshAdministrator(HttpServletRequest request) {
        requireReady();
        state.requireConnector(request);
        var current = sessions.current(request);
        if (current.isEmpty()) return;
        var presented = current.get();
        AdminPrincipal actor = presented.principal();
        if (actor.accountId() != administratorId || !actor.accountKey().equals(administratorKey))
            return;
        Path marker = state.home().resolve("synthetic-session-" + actor.sessionKey() + ".json");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            JsonNode registered = load(marker.getFileName().toString());
            if (!registered.path("accountKey").asText().equals(administratorKey.toString())
                    || !registered
                            .path("sidHash")
                            .asText()
                            .equals(HexFormat.of().formatHex(crypto.sessionHash(presented.id()))))
                throw new IllegalStateException("개발 세션 지도 불일치");
            transaction.executeWithoutResult(
                    status -> {
                        int changed =
                                db.update(
                                        "UPDATE admin_session s SET"
                                            + " reauth_at=clock_timestamp(),updated_at=clock_timestamp()"
                                            + " FROM admin_account a,admin_credential c WHERE"
                                            + " s.account_id=a.id AND c.account_id=a.id AND a.id=?"
                                            + " AND a.account_key=? AND a.active_yn AND"
                                            + " c.enrolled_at IS NOT NULL AND c.mfa_state='READY'"
                                            + " AND s.session_key=? AND s.sid_hash=? AND"
                                            + " s.auth_rev=? AND c.auth_rev=s.auth_rev AND"
                                            + " s.state='ACTIVE' AND s.expires_at>clock_timestamp()"
                                            + " AND s.last_action_at+interval '30"
                                            + " minutes'>clock_timestamp()",
                                        administratorId,
                                        administratorKey,
                                        actor.sessionKey(),
                                        crypto.sessionHash(presented.id()),
                                        actor.authRev());
                        if (changed == 1) audit(actor, "SESSION_REAUTHENTICATED");
                    });
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("개발 세션 지도 검증 실패");
        }
    }

    /**
     * 회원 원문 자격은 메모리 응답에서만 사용한다.
     *
     * @param slot 허용된 a 또는 b
     * @return 이메일·비밀번호 두 필드만 반환
     */
    public Map<String, String> memberCredentials(String slot) {
        requireReady();
        if (!"a".equals(slot) && !"b".equals(slot)) throw AuthException.badRequest("INVALID_SLOT");
        JsonNode member = credentials.get(slot);
        validateMember(slot);
        return Map.of(
                "email",
                member.path("email").asText(),
                "password",
                member.path("password").asText());
    }

    /** 외부 호출마다 실제 DB 경계와 초기화 완료를 다시 확인한다. */
    private void requireReady() {
        state.requireDatabase(db);
        if (!ready) throw AuthException.unavailable("DEVELOPMENT_NOT_READY");
    }

    /** 실제 등록 단계를 사용하며 기존 관리자·비밀번호·권한을 임의 복원하지 않는다. */
    private void seedAdministrator() throws Exception {
        Path bootstrapFile = state.home().resolve("synthetic-bootstrap.json");
        if (!Files.exists(bootstrapFile, LinkOption.NOFOLLOW_LINKS)) {
            if (db.queryForObject("SELECT count(*) FROM admin_account", Long.class) != 0)
                throw new IllegalStateException("예상하지 않은 기존 관리자 등록");
            var bootstrap = enrollment.createBootstrap(LOGIN, REF, REF, UUID.randomUUID());
            save(
                    "synthetic-bootstrap.json",
                    Map.of(
                            "registrationKey",
                            bootstrap.registrationKey().toString(),
                            "code",
                            bootstrap.code()));
        }
        JsonNode bootstrap = load("synthetic-bootstrap.json");
        UUID registration = UUID.fromString(bootstrap.path("registrationKey").asText());
        var row =
                db.queryForMap(
                        "SELECT"
                            + " a.id,a.account_key,a.active_yn,a.can_manage,a.can_create,a.can_review,a.edit_rev,c.login_hash,c.login_cipher,c.password_hash,c.mfa_cipher,c.mfa_verified_at,c.enrolled_at,c.mfa_state,e.verification_ref"
                            + " FROM admin_account a JOIN admin_credential c ON c.account_id=a.id"
                            + " JOIN admin_enrollment e ON e.account_id=a.id WHERE"
                            + " e.registration_key=? AND e.kind='BOOTSTRAP'",
                        registration);
        administratorId = ((Number) row.get("id")).longValue();
        administratorKey = (UUID) row.get("account_key");
        if (!Boolean.TRUE.equals(row.get("active_yn"))
                || !Boolean.TRUE.equals(row.get("can_manage"))
                || !REF.equals(row.get("verification_ref"))
                || !crypto.constantEquals((byte[]) row.get("login_hash"), crypto.loginHash(LOGIN))
                || !LOGIN.equals(
                        crypto.decrypt((String) row.get("login_cipher"), adminAad("loginId"))))
            throw new IllegalStateException("합성 관리자 식별 불일치");
        String password = credentials.path("administratorPassword").asText();
        if (row.get("password_hash") != null
                && !encoder().matches(password, (String) row.get("password_hash")))
            throw new IllegalStateException("기존 관리자 비밀번호 불일치");
        if (row.get("enrolled_at") == null) {
            Path grantFile = state.home().resolve("synthetic-enrollment-grant.json");
            if (!Files.exists(grantFile, LinkOption.NOFOLLOW_LINKS)) {
                var grant =
                        enrollment.exchange(
                                bootstrap.path("code").asText(),
                                null,
                                "127.0.0.1",
                                UUID.randomUUID());
                save("synthetic-enrollment-grant.json", Map.of("cookie", grant.cookie().value()));
            }
            String grant = load("synthetic-enrollment-grant.json").path("cookie").asText();
            if (row.get("password_hash") == null)
                enrollment.setPassword(grant, password, UUID.randomUUID());
            if (row.get("mfa_verified_at") == null) {
                var setup = enrollment.prepareMfa(grant, UUID.randomUUID());
                save("synthetic-administrator-mfa.json", Map.of("secret", setup.secret()));
                Instant previous =
                        Instant.ofEpochSecond((Instant.now().getEpochSecond() / 30 - 1) * 30);
                enrollment.verifyMfa(
                        grant, totp.code(setup.secret(), previous), "127.0.0.1", UUID.randomUUID());
            }
            var complete = enrollment.complete(grant, UUID.randomUUID());
            save(
                    "synthetic-administrator-recovery.json",
                    Map.of("recoveryCodes", complete.recoveryCodes()));
        }
        JsonNode mfa = load("synthetic-administrator-mfa.json");
        String actualSecret =
                db.queryForObject(
                        "SELECT mfa_cipher FROM admin_credential WHERE account_id=? AND enrolled_at"
                                + " IS NOT NULL AND mfa_state='READY'",
                        String.class,
                        administratorId);
        if (!mfa.path("secret").asText().equals(crypto.decrypt(actualSecret, adminAad("mfa"))))
            throw new IllegalStateException("기존 관리자 MFA 불일치");
        save(
                "synthetic-administrator-id.json",
                Map.of("accountId", administratorId, "accountKey", administratorKey.toString()));
        Path permissions = state.home().resolve("synthetic-administrator-permissions.json");
        boolean hasPermissions =
                Boolean.TRUE.equals(row.get("can_create"))
                        && Boolean.TRUE.equals(row.get("can_review"));
        if (Files.exists(permissions, LinkOption.NOFOLLOW_LINKS) && !hasPermissions)
            throw new IllegalStateException("기존 개발 관리자 권한 변경은 자동 복원하지 않음");
        if (!hasPermissions) {
            var issued = issueSession();
            administrators.grantPermissions(
                    issued.id(),
                    issued.principal(),
                    administratorKey,
                    row.get("edit_rev").toString(),
                    List.of("CREATE", "REVIEW"),
                    "ASSIGNMENT_CHANGE",
                    REF,
                    UUID.randomUUID());
        }
        save(
                "synthetic-administrator-permissions.json",
                Map.of(
                        "accountKey",
                        administratorKey.toString(),
                        "permissions",
                        List.of("MANAGE", "CREATE", "REVIEW")));
    }

    /**
     * 합성 메일 확인과 기존 LOCAL 가입 서비스로 한 회원을 준비하거나 현재 계정을 검증한다.
     *
     * @param slot 고정된 a 또는 b
     * @throws Exception 등록·메일·기존 자격의 결속을 확인할 수 없는 경우
     */
    private void seedMember(String slot) throws Exception {
        JsonNode member = credentials.get(slot);
        com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry.exact(
                member, "email", "password");
        String email = member.path("email").asText();
        if (!("synthetic-" + slot + "@example.invalid").equals(email))
            throw new IllegalStateException("합성 이메일 불일치");
        String lookup = HexFormat.of().formatHex(crypto.memberEmailHash(email));
        if (db.queryForObject(
                        "SELECT count(*) FROM member_identity WHERE provider='LOCAL' AND"
                                + " realm='LOCAL' AND lookup_hash=?",
                        Long.class,
                        lookup)
                == 0) {
            var flow = members.startSignup(email, "127.0.0.1", UUID.randomUUID());
            String code = null;
            // SMTP 전송은 동기식이며 다른 수신자의 메일이나 기존 메일은 지우지 않는다.
            for (var message : smtp.getReceivedMessages()) {
                boolean matches =
                        java.util.Arrays.stream(message.getAllRecipients())
                                .anyMatch(address -> email.equals(address.toString()));
                if (matches) {
                    var matcher =
                            Pattern.compile("확인 코드: ([0-9]{8})")
                                    .matcher(message.getContent().toString());
                    if (matcher.find()) code = matcher.group(1);
                }
            }
            if (code == null) throw new IllegalStateException("합성 루프백 이메일 미수신");
            members.verifyEmail(
                    flow.flowKey(), flow.flowBinder(), code, "127.0.0.1", UUID.randomUUID());
            var issued =
                    members.completeSignup(
                            flow.flowKey(),
                            flow.flowBinder(),
                            member.path("password").asText(),
                            "합성 개발 회원 " + slot,
                            policy.code(),
                            policy.noticeHash(),
                            UUID.randomUUID(),
                            UUID.randomUUID());
            save(
                    "synthetic-member-" + slot + "-id.json",
                    Map.of(
                            "memberId",
                            issued.principal().memberId(),
                            "memberKey",
                            issued.principal().memberKey().toString()));
            members.logout(issued.tokens().accessToken(), UUID.randomUUID(), UUID.randomUUID());
        }
        validateMember(slot);
    }

    /**
     * 비활성·탈퇴·암호 변경을 새 기본값으로 덮지 않고 기존 계정의 ID·정책·암호를 확인한다.
     *
     * @param slot 고정된 a 또는 b
     */
    private void validateMember(String slot) {
        try {
            JsonNode member = credentials.get(slot);
            JsonNode identity = load("synthetic-member-" + slot + "-id.json");
            var row =
                    db.queryForMap(
                            "SELECT a.id,a.member_key,a.state,i.password_hash,i.proof_at FROM"
                                + " member_account a JOIN member_identity i ON i.member_id=a.id"
                                + " JOIN member_profile p ON p.member_id=a.id JOIN privacy_policy"
                                + " policy ON policy.id=p.policy_id WHERE a.id=? AND"
                                + " i.provider='LOCAL' AND i.realm='LOCAL' AND i.active_yn AND"
                                + " i.lookup_ver=1 AND i.lookup_hash=? AND policy.code=?",
                            identity.path("memberId").asLong(),
                            HexFormat.of()
                                    .formatHex(
                                            crypto.memberEmailHash(member.path("email").asText())),
                            policy.code());
            if (!identity.path("memberKey").asText().equals(row.get("member_key").toString())
                    || !"ACTIVE".equals(row.get("state"))
                    || row.get("proof_at") == null
                    || !encoder()
                            .matches(
                                    member.path("password").asText(),
                                    (String) row.get("password_hash")))
                throw new IllegalStateException("기존 합성 회원 불일치");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("합성 회원 지도 검증 실패");
        }
    }

    /** 실제 PENDING·독립 프레임워크 저장·ACTIVE 순서로 현재 유효 합성 관리자의 세션을 발급한다. */
    private IssuedSession issueSession() {
        state.requireDatabase(db);
        var prepared = sessions.prepare();
        UUID key = UUID.randomUUID();
        AdminPrincipal principal =
                transaction.execute(
                        status -> {
                            var rows =
                                    db.queryForList(
                                            "SELECT c.auth_rev FROM admin_account a JOIN"
                                                + " admin_credential c ON c.account_id=a.id WHERE"
                                                + " a.id=? AND a.account_key=? AND a.active_yn AND"
                                                + " c.enrolled_at IS NOT NULL AND"
                                                + " c.mfa_state='READY' FOR UPDATE OF a,c",
                                            administratorId,
                                            administratorKey);
                            if (rows.size() != 1)
                                throw AuthException.unavailable("DEVELOPMENT_ADMIN_UNAVAILABLE");
                            long revision = ((Number) rows.getFirst().get("auth_rev")).longValue();
                            db.update(
                                    "INSERT INTO"
                                        + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at)"
                                        + " VALUES"
                                        + " (?,?,?,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval"
                                        + " '8 hours',CURRENT_TIMESTAMP)",
                                    key,
                                    administratorId,
                                    crypto.sessionHash(prepared.id()),
                                    revision);
                            return new AdminPrincipal(
                                    administratorId, administratorKey, key, revision);
                        });
        try {
            save(
                    "synthetic-session-" + key + ".json",
                    Map.of(
                            "accountKey",
                            administratorKey.toString(),
                            "sidHash",
                            HexFormat.of().formatHex(crypto.sessionHash(prepared.id()))));
            sessions.save(prepared, principal);
            transaction.executeWithoutResult(
                    status -> {
                        int changed =
                                db.update(
                                        "UPDATE admin_session s SET"
                                            + " state='ACTIVE',activated_at=clock_timestamp(),updated_at=clock_timestamp()"
                                            + " FROM admin_account a,admin_credential c WHERE"
                                            + " s.account_id=a.id AND c.account_id=a.id AND a.id=?"
                                            + " AND a.account_key=? AND a.active_yn AND"
                                            + " c.enrolled_at IS NOT NULL AND c.mfa_state='READY'"
                                            + " AND s.session_key=? AND s.sid_hash=? AND"
                                            + " s.auth_rev=? AND c.auth_rev=s.auth_rev AND"
                                            + " s.state='PENDING' AND"
                                            + " s.expires_at>clock_timestamp()",
                                        administratorId,
                                        administratorKey,
                                        key,
                                        crypto.sessionHash(prepared.id()),
                                        principal.authRev());
                        if (changed != 1 || !sessions.matchesStored(prepared.id(), principal))
                            throw AuthException.unavailable("DEVELOPMENT_SESSION_UNAVAILABLE");
                        audit(principal, "SESSION_ACTIVATED");
                    });
            return new IssuedSession(prepared.id(), principal);
        } catch (Exception failure) {
            // PENDING은 권한을 주지 않는다. 기존 세션·다른 계정에는 손대지 않는다.
            transaction.executeWithoutResult(
                    status ->
                            db.update(
                                    "UPDATE admin_session SET"
                                        + " state='REVOKED',revoked_at=clock_timestamp(),updated_at=clock_timestamp()"
                                        + " WHERE session_key=? AND account_id=? AND state IN"
                                        + " ('PENDING','ACTIVE')",
                                    key,
                                    administratorId));
            throw AuthException.unavailable("DEVELOPMENT_SESSION_UNAVAILABLE");
        }
    }

    /**
     * 개발 세션의 발급/최근 인증 갱신과 같은 거래에 최소 보안 이력을 남긴다.
     *
     * @param actor 실제 저장된 합성 관리자 세션의 행위자
     * @param action 고정된 SESSION_ACTIVATED 또는 SESSION_REAUTHENTICATED
     */
    private void audit(AdminPrincipal actor, String action) {
        db.update(
                "INSERT INTO"
                    + " admin_auth_audit(actor_kind,actor_id,target_id,action,outcome,request_id,route,method,http_status,session_key)"
                    + " VALUES ('ADMIN',?,?,?,'COMMITTED',?,'SYNTHETIC_DEVELOPMENT','POST',200,?)",
                actor.accountId(),
                actor.accountId(),
                action,
                UUID.randomUUID(),
                actor.sessionKey());
    }

    private String adminAad(String field) {
        return "admin-account/" + administratorKey + "/" + field + "/v1";
    }

    private String password() {
        return crypto.randomToken(32) + "-Dev9!";
    }

    private static Argon2PasswordEncoder encoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }

    private JsonNode load(String name) throws java.io.IOException {
        return com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry.parse(
                DevelopmentPolicy.readPrivate(state.home().resolve(name)));
    }

    private void save(String name, Object value) throws java.io.IOException {
        DevelopmentPolicy.sameFile(state.home().resolve(name), JSON.writeValueAsBytes(value));
    }

    private record IssuedSession(String id, AdminPrincipal principal) {}
}
