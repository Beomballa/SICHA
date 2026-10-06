package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.member.auth.MemberAuthConfiguration;
import com.reasoning.common.member.auth.MemberMailTransport;
import com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry;
import com.reasoning.common.member.auth.MemberPolicyGate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;

/** 실제 DB·루프백 SMTP·HTTP dispatcher의 합성 회귀이며 실제 인원·운영 효력의 증거는 아니다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LocalMemberAuthIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GreenMail SMTP = smtp();
    private static final String PASSWORD = "Synthetic-member-password-987!";
    private static final String BASE = "/api/member/auth";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AuthProperties keys;
    @Autowired MemberAuthConfiguration.Properties configuration;
    @Autowired MemberMailTransport mail;
    @Autowired com.reasoning.common.member.auth.MemberAuthService service;
    private String code;
    private String noticeHash;
    private long policy;

    private static GreenMail smtp() {
        var result = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        result.start();
        return result;
    }

    @AfterAll
    static void closeMail() {
        SMTP.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> key((byte) 91));
        properties.add("app.auth.search-key-file", () -> key((byte) 92));
        properties.add("app.auth.limit-key-file", () -> key((byte) 93));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
        properties.add("app.member-auth.env-code", () -> "SYNTHETIC_HTTP");
        properties.add("app.member-auth.mail-from", () -> "synthetic@example.invalid");
    }

    private static String key(byte seed) {
        try {
            return Path.of(TestKeys.create(seed)).toRealPath().toString();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 보호된 외부 파일은 합성 절차 기록만 포함하며 운영 등록에는 쓰지 않는다. */
    @BeforeEach
    void ready() throws Exception {
        SMTP.purgeEmailFromAllMailboxes();
        configuration.setCollectionEnabled(true);
        db.update("UPDATE privacy_policy SET state='RETIRED' WHERE state='ACTIVE'");
        long owner =
                db.queryForObject(
                        "INSERT INTO admin_account(account_key,can_manage) VALUES (?,true)"
                                + " RETURNING id",
                        Long.class,
                        UUID.randomUUID());
        db.update(
                "INSERT INTO"
                    + " admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                    + " VALUES (?,?,?,?,?,now(),0,now(),'READY')",
                owner,
                "synthetic",
                java.nio.ByteBuffer.allocate(32).putLong(owner).array(),
                "synthetic",
                "synthetic");
        code = "SYNTHETIC_" + UUID.randomUUID().toString().replace("-", "");
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600),
                until = from.plusSeconds(86400);
        var notice =
                JSON.valueToTree(
                        Map.of(
                                "version",
                                "1",
                                "body",
                                "합성 인증 테스트 고지",
                                "contact",
                                "synthetic@example.invalid"));
        noticeHash = MemberPolicyGate.noticeHash(code, notice);
        Map<String, String> keyFiles =
                Map.of(
                        "encryption",
                        keys.getCryptoKeyFile(),
                        "search",
                        keys.getSearchKeyFile(),
                        "limit",
                        keys.getLimitKeyFile());
        var boundary = mail.boundary();
        Map<String, Object> mailData =
                Map.of(
                        "host",
                        boundary.host(),
                        "port",
                        boundary.port(),
                        "protocol",
                        boundary.protocol(),
                        "from",
                        boundary.from(),
                        "timeoutMillis",
                        boundary.timeoutMillis());
        Map<String, Object> evidence = new TreeMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String kind : MemberPolicyEvidenceRegistry.KINDS) {
            String ref = "synthetic:" + kind;
            var artifact = new TreeMap<String, Object>();
            artifact.putAll(
                    Map.of(
                            "formatNo",
                            1,
                            "kind",
                            kind,
                            "ref",
                            ref,
                            "envCode",
                            "SYNTHETIC_HTTP",
                            "scope",
                            "MEMBER_AUTH",
                            "ownerId",
                            owner,
                            "noticeHash",
                            noticeHash,
                            "verifiedAt",
                            from.toString(),
                            "validUntil",
                            until.toString(),
                            "claims",
                            claims(kind, keyFiles, mailData)));
            byte[] bytes = JSON.writeValueAsBytes(artifact);
            Path path = protectedFile(bytes);
            String hash = MemberPolicyEvidenceRegistry.sha256(bytes);
            records.add(Map.of("kind", kind, "ref", ref, "path", path.toString(), "sha256", hash));
            evidence.put(
                    kind,
                    Map.of(
                            "ref",
                            ref,
                            "sha256",
                            hash,
                            "envCode",
                            "SYNTHETIC_HTTP",
                            "scope",
                            "MEMBER_AUTH",
                            "verifiedAt",
                            from.toString(),
                            "validUntil",
                            until.toString()));
        }
        var manifest = new TreeMap<String, Object>();
        manifest.putAll(
                Map.of(
                        "formatNo",
                        1,
                        "envCode",
                        "SYNTHETIC_HTTP",
                        "scope",
                        "MEMBER_AUTH",
                        "ownerId",
                        owner,
                        "noticeHash",
                        noticeHash,
                        "validFrom",
                        from.toString(),
                        "validUntil",
                        until.toString(),
                        "keyFiles",
                        keyFiles,
                        "mail",
                        mailData,
                        "records",
                        records));
        configuration.setEvidenceRegistryFile(
                protectedFile(JSON.writeValueAsBytes(manifest)).toString());
        Map<String, Object> retention = new TreeMap<>();
        retention.putAll(
                Map.of(
                        "flowTtlSeconds",
                        600,
                        "flowCleanupGraceSeconds",
                        3600,
                        "accessTtlSeconds",
                        300,
                        "accessCleanupGraceSeconds",
                        3600,
                        "refreshIdleSeconds",
                        2592000,
                        "sessionAbsoluteSeconds",
                        7776000,
                        "familyCleanupGraceSeconds",
                        86400,
                        "limitMaxSeconds",
                        86400,
                        "accessHistoryMaxSeconds",
                        2592000,
                        "securityAuditMaxSeconds",
                        7776000));
        retention.put("backupMaxSeconds", 3024000);
        var document =
                Map.of(
                        "formatNo",
                        1,
                        "notice",
                        notice,
                        "validFrom",
                        from.toString(),
                        "validUntil",
                        until.toString(),
                        "authProviders",
                        List.of("LOCAL"),
                        "retention",
                        retention,
                        "evidence",
                        evidence);
        policy =
                db.queryForObject(
                        "INSERT INTO"
                            + " privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                            + " VALUES (?,'SYNTHETIC_HTTP','MEMBER_AUTH','DRAFT',?,?::jsonb,?)"
                            + " RETURNING id",
                        Long.class,
                        code,
                        noticeHash,
                        JSON.writeValueAsString(document),
                        owner);
        db.update("UPDATE privacy_policy SET state='ACTIVE' WHERE id=?", policy);
    }

    private static Map<String, Object> claims(
            String kind, Map<String, String> keys, Map<String, Object> mail) {
        return switch (kind) {
            case "responsibility" ->
                    Map.of(
                            "purpose",
                            "SYNTHETIC_ONLY",
                            "items",
                            List.of("synthetic email"),
                            "contact",
                            "synthetic@example.invalid");
            case "access" ->
                    Map.of(
                            "privilegedReaders",
                            List.of("synthetic"),
                            "securityOperators",
                            List.of("synthetic"),
                            "erasureOperators",
                            List.of("synthetic"),
                            "boundaries",
                            "isolated test");
            case "keys" ->
                    Map.of(
                            "encryption",
                            keys.get("encryption"),
                            "search",
                            keys.get("search"),
                            "limit",
                            keys.get("limit"),
                            "custodians",
                            List.of("synthetic"),
                            "rotationProcedure",
                            "synthetic fixture",
                            "restoreProcedure",
                            "synthetic fixture",
                            "lookupVer",
                            1);
            case "processors" ->
                    Map.of(
                            "boundary",
                            mail,
                            "socialProviders",
                            List.of(),
                            "gradingProcessors",
                            List.of(),
                            "retentionSeconds",
                            600,
                            "storageRestrictions",
                            "synthetic only",
                            "deletionProcedure",
                            "purge fixture",
                            "confirmationProcedure",
                            "test assertions");
            case "copies" ->
                    Map.of(
                            "inventory",
                            List.of(
                                            "DATABASE_BACKUP",
                                            "WAL_PITR",
                                            "SNAPSHOT",
                                            "EXPORT",
                                            "MAIL",
                                            "PROCESSOR")
                                    .stream()
                                    .map(
                                            value ->
                                                    Map.of(
                                                            "mechanism",
                                                            value,
                                                            "lifetimeSeconds",
                                                            600,
                                                            "disposition",
                                                            "VERIFIED_ABSENT",
                                                            "evidence",
                                                            "synthetic boundary, not operational"
                                                                    + " evidence"))
                                    .toList(),
                            "deletionLedger",
                            "synthetic",
                            "restoreGuard",
                            "synthetic");
            case "verification" ->
                    Map.of(
                            "localAuthErasure",
                            "synthetic fixture",
                            "mailDeletion",
                            "GreenMail cleanup",
                            "isolatedRestore",
                            "synthetic fixture",
                            "deletionRecordsApplied",
                            "synthetic fixture",
                            "excludedProducers",
                            List.of("SOCIAL", "GRADING", "PLAYTEST"),
                            "limitations",
                            "does not verify production erasure or restoration");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static Path protectedFile(byte[] bytes) throws Exception {
        Path path = Files.createTempFile("local-member-synthetic-", ".json").toRealPath();
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Files.write(path, bytes);
        path.toFile().deleteOnExit();
        return path;
    }

    private JsonNode send(String path, Object body, int expected) throws Exception {
        var result =
                mvc.perform(
                                post(path)
                                        .secure(true)
                                        .contentType("application/json")
                                        .content(JSON.writeValueAsBytes(body)))
                        .andExpect(status().is(expected))
                        .andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        if (expected == 429)
            assertThat(Long.parseLong(result.getResponse().getHeader("Retry-After")))
                    .isBetween(1L, 86400L);
        JsonNode node = JSON.readTree(result.getResponse().getContentAsByteArray());
        assertThat(node.path("requestId").asText())
                .isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        return node;
    }

    private JsonNode account(String email) throws Exception {
        var flow = send(BASE + "/email/signup", Map.of("email", email), 202);
        assertThat(SMTP.waitForIncomingEmail(5000, 1)).isTrue();
        var messages = SMTP.getReceivedMessages();
        String content = messages[messages.length - 1].getContent().toString();
        var match = java.util.regex.Pattern.compile("[0-9]{8}").matcher(content);
        assertThat(match.find()).isTrue();
        var verify =
                Map.of(
                        "flowKey",
                        flow.path("flowKey").asText(),
                        "flowBinder",
                        flow.path("flowBinder").asText(),
                        "code",
                        match.group());
        send(BASE + "/email/verify", verify, 200);
        return send(
                BASE + "/email/signup/complete",
                Map.of(
                        "flowKey",
                        flow.path("flowKey").asText(),
                        "flowBinder",
                        flow.path("flowBinder").asText(),
                        "password",
                        PASSWORD,
                        "nickname",
                        " 합성회원 ",
                        "policyCode",
                        code,
                        "noticeHash",
                        noticeHash,
                        "requestKey",
                        UUID.randomUUID().toString()),
                201);
    }

    /** 실제 SMTP 코드와 binder로 가입하고 로그인·갱신·재사용에 따른 family 회수를 확인한다. */
    @Test
    void signupLoginRefreshReuseAndLogout() throws Exception {
        String email = "Member-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        String access = issued.path("accessToken").asText();
        mvc.perform(get(BASE + "/me").secure(true).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        var rotated =
                send(
                        BASE + "/refresh",
                        Map.of("refreshToken", issued.path("refreshToken").asText()),
                        200);
        send(BASE + "/refresh", Map.of("refreshToken", issued.path("refreshToken").asText()), 401);
        mvc.perform(
                        get(BASE + "/me")
                                .secure(true)
                                .header(
                                        "Authorization",
                                        "Bearer " + rotated.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        var login = send(BASE + "/login/local", Map.of("email", email, "password", PASSWORD), 200);
        var result =
                mvc.perform(
                                post(BASE + "/logout")
                                        .secure(true)
                                        .header(
                                                "Authorization",
                                                "Bearer " + login.path("accessToken").asText())
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsBytes(
                                                        Map.of(
                                                                "requestKey",
                                                                UUID.randomUUID().toString()))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(
                        JSON.readTree(result.getResponse().getContentAsByteArray())
                                .path("state")
                                .asText())
                .isEqualTo("LOGGED_OUT");
        mvc.perform(
                        get(BASE + "/me")
                                .secure(true)
                                .header(
                                        "Authorization",
                                        "Bearer " + login.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM access_history WHERE actor_kind='MEMBER'",
                                Long.class))
                .isGreaterThan(0);
    }

    /** 공개 경계·타입·중복·크기를 실제 controller에서 거절하고 관리자 CSRF를 보존한다. */
    @Test
    void strictHttpAndIsolation() throws Exception {
        for (String source :
                List.of(
                        "{\"email\":\"a@example.invalid\",\"email\":\"b@example.invalid\"}",
                        "{\"email\":7}",
                        "{\"email\":\"a@example.invalid\",\"extra\":true}",
                        "{\"email\":\"a@example.invalid\"} {}")) {
            mvc.perform(
                            post(BASE + "/email/signup")
                                    .secure(true)
                                    .contentType("application/json")
                                    .content(source))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        post(BASE + "/email/signup")
                                .secure(true)
                                .contentType("application/json")
                                .content(" ".repeat(16385)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post(BASE + "/email/signup").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(BASE + "/email/signup")
                                .secure(true)
                                .header("Origin", "https://untrusted.example")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(BASE + "/me")
                                .secure(true)
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "__Host-admin-session", "fake")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/me?accessToken=secret").secure(true))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/password/reset").secure(true)).andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/api/auth/login")
                                .secure(true)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        var notice =
                mvc.perform(get("/api/member/privacy/policies/member-auth").secure(true))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(JSON.readTree(notice.getResponse().getContentAsByteArray()).has("evidence"))
                .isFalse();
    }

    /** 정책 중단 중에도 로그아웃 회수는 성공하며 인증·수집을 재개하지 않는다. */
    @Test
    void suspendedPolicyAllowsOnlyRevocation() throws Exception {
        var issued = account("suspended-" + UUID.randomUUID() + "@example.invalid");
        db.update("UPDATE privacy_policy SET state='SUSPENDED' WHERE id=?", policy);
        mvc.perform(
                        get(BASE + "/me")
                                .secure(true)
                                .header(
                                        "Authorization",
                                        "Bearer " + issued.path("accessToken").asText()))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(
                        post(BASE + "/logout")
                                .secure(true)
                                .header(
                                        "Authorization",
                                        "Bearer " + issued.path("accessToken").asText())
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                Map.of(
                                                        "requestKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
    }

    /** 이미 가입한 주소도 같은 봉투를 반환하며 잘못된 binder는 타인의 코드 시도를 소비하지 않는다. */
    @Test
    void enumerationBinderAndRateLimits() throws Exception {
        String email = "duplicate-" + UUID.randomUUID() + "@example.invalid";
        account(email);
        int delivered = SMTP.getReceivedMessages().length;
        var dummy = send(BASE + "/email/signup", Map.of("email", email), 202);
        assertThat(SMTP.getReceivedMessages()).hasSize(delivered);
        assertThat(dummy.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("flowKey", "flowBinder", "expiresAt", "requestId");
        String wrongBinder =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        send(
                BASE + "/email/verify",
                Map.of(
                        "flowKey",
                        dummy.path("flowKey").asText(),
                        "flowBinder",
                        wrongBinder,
                        "code",
                        "00000000"),
                401);
        assertThat(
                        db.queryForObject(
                                "SELECT attempt_count FROM member_flow WHERE flow_key=?",
                                Integer.class,
                                UUID.fromString(dummy.path("flowKey").asText())))
                .isZero();
        send(BASE + "/email/signup", Map.of("email", email), 202);
        send(BASE + "/email/signup", Map.of("email", email), 429);
        for (int i = 0; i < 5; i++)
            send(
                    BASE + "/login/local",
                    Map.of("email", email, "password", "Wrong-synthetic-password-123!"),
                    401);
        send(BASE + "/login/local", Map.of("email", email, "password", PASSWORD), 429);
    }

    /** 같은 refresh의 두 비행 중 하나만 발급되며 재사용은 발급된 후속 family까지 회수한다. */
    @Test
    void concurrentRefreshRevokesWinner() throws Exception {
        var issued = account("race-" + UUID.randomUUID() + "@example.invalid");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var winners =
                new java.util.concurrent.CopyOnWriteArrayList<
                        com.reasoning.common.member.auth.MemberAuthService.Issued>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> attempt =
                    () -> {
                        ready.countDown();
                        start.await();
                        try {
                            winners.add(
                                    service.refresh(
                                            issued.path("refreshToken").asText(),
                                            UUID.randomUUID()));
                            return "ROTATED";
                        } catch (com.reasoning.common.auth.service.AuthException failure) {
                            return failure.code();
                        }
                    };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(
                            List.of(
                                    first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                                    second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ROTATED", "REFRESH_UNAVAILABLE");
        }
        assertThat(winners).hasSize(1);
        mvc.perform(
                        get(BASE + "/me")
                                .secure(true)
                                .header(
                                        "Authorization",
                                        "Bearer " + winners.getFirst().tokens().accessToken()))
                .andExpect(status().isUnauthorized());
    }

    /** 소비 refresh의 보안 회수는 정책 준비나 감사 성공에 의존하지 않는다. */
    @Test
    void usedRefreshRevokesDespitePolicyAndAuditFailure() throws Exception {
        var issued = account("revoke-" + UUID.randomUUID() + "@example.invalid");
        var rotated =
                send(
                        BASE + "/refresh",
                        Map.of("refreshToken", issued.path("refreshToken").asText()),
                        200);
        configuration.setCollectionEnabled(false);
        auditFault();
        try {
            send(
                    BASE + "/refresh",
                    Map.of("refreshToken", issued.path("refreshToken").asText()),
                    401);
            assertThat(
                            db.queryForObject(
                                    "SELECT revoked_at IS NOT NULL FROM member_session WHERE"
                                            + " session_key=?",
                                    Boolean.class,
                                    UUID.fromString(rotated.path("sessionKey").asText())))
                    .isTrue();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM member_token t JOIN member_session s ON"
                                            + " s.id=t.session_id WHERE s.session_key=? AND"
                                            + " t.state='ISSUED'",
                                    Long.class,
                                    UUID.fromString(rotated.path("sessionKey").asText())))
                    .isZero();
        } finally {
            removeAuditFault();
        }
    }

    /** 발급 필수 감사 실패는 회전을 롤백하고 원래 refresh를 정상 재사용 가능하게 유지한다. */
    @Test
    void issuanceAuditFailureRollsBackRotation() throws Exception {
        var issued = account("audit-" + UUID.randomUUID() + "@example.invalid");
        auditFault();
        try {
            send(
                    BASE + "/refresh",
                    Map.of("refreshToken", issued.path("refreshToken").asText()),
                    503);
        } finally {
            removeAuditFault();
        }
        send(BASE + "/refresh", Map.of("refreshToken", issued.path("refreshToken").asText()), 200);
    }

    /** 폐기 DB의 새 INSERT 실패 장치만 설치하고 기존 불변 방어는 유지한다. */
    private void auditFault() {
        db.execute(
                "CREATE FUNCTION synthetic_member_audit_failure() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END; $$");
        db.execute(
                "CREATE TRIGGER synthetic_member_audit_failure BEFORE INSERT ON member_auth_audit"
                        + " FOR EACH ROW EXECUTE FUNCTION synthetic_member_audit_failure()");
    }

    private void removeAuditFault() {
        db.execute("DROP TRIGGER synthetic_member_audit_failure ON member_auth_audit");
        db.execute("DROP FUNCTION synthetic_member_audit_failure()");
    }

    /** 24시간 이메일 제한의 Retry-After를15분으로 잘못 축소하지 않는다. */
    @Test
    void dailyLimitReportsItsOwnDeadline() throws Exception {
        String email = "daily-" + UUID.randomUUID() + "@example.invalid";
        send(BASE + "/email/signup", Map.of("email", email), 202);
        var crypto = new com.reasoning.common.auth.service.CryptoService(keys);
        String hash =
                java.util.HexFormat.of().formatHex(crypto.limitHash("SIGNUP_EMAIL_24H", email));
        db.update(
                "UPDATE member_auth_limit SET hit_count=10 WHERE scope='SIGNUP_EMAIL_24H' AND"
                        + " bucket_hash=?",
                hash);
        var result =
                mvc.perform(
                                post(BASE + "/email/signup")
                                        .secure(true)
                                        .contentType("application/json")
                                        .content(JSON.writeValueAsBytes(Map.of("email", email))))
                        .andExpect(status().isTooManyRequests())
                        .andReturn();
        assertThat(Long.parseLong(result.getResponse().getHeader("Retry-After")))
                .isBetween(86390L, 86400L);
    }

    /** 다섯 틀린 코드는 flow를 잠그고 암호화 증거·검증 해시를 제거한다. */
    @Test
    void fiveInvalidCodesEraseFlowProof() throws Exception {
        var flow =
                send(
                        BASE + "/email/signup",
                        Map.of("email", "locked-" + UUID.randomUUID() + "@example.invalid"),
                        202);
        for (int i = 0; i < 5; i++)
            send(
                    BASE + "/email/verify",
                    Map.of(
                            "flowKey",
                            flow.path("flowKey").asText(),
                            "flowBinder",
                            flow.path("flowBinder").asText(),
                            "code",
                            "invalid"),
                    401);
        var row =
                db.queryForMap(
                        "SELECT state,attempt_count,lookup_hash,code_hash,proof_cipher FROM"
                                + " member_flow WHERE flow_key=?",
                        UUID.fromString(flow.path("flowKey").asText()));
        assertThat(row.get("state")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(5);
        for (String field : List.of("lookup_hash", "code_hash", "proof_cipher"))
            assertThat(row.get(field)).isNull();
    }
}
