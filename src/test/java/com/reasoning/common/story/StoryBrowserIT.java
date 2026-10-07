package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthModels;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.TotpService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 합성 등록 계정으로 실제 HTTPS 로그인·MFA와 폐기 DB 연결 화면을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StoryBrowserIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    static final String storePassword = UUID.randomUUID().toString();
    static final Path keyStore = createKeyStore();

    @LocalServerPort int port;
    @Autowired EnrollmentService enrollment;
    @Autowired TotpService totp;
    @Autowired CryptoService crypto;
    @Autowired com.reasoning.admin.auth.service.AdminAccountService accounts;
    @Autowired com.reasoning.admin.auth.session.AdminSessionAdapter frameworkSessions;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    /** DB 주소는 반드시 Testcontainers가 만든 임의 포트만 사용한다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 91));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 92));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 93));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> keyStore.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> storePassword);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
    }

    /** 폐기 TLS 인증서를 비공개 임시 디렉터리에 만들며 제품 인증서는 사용하지 않는다. */
    static Path createKeyStore() {
        try {
            Path dir =
                    Files.createTempDirectory(
                            "h1-browser-tls-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            Path store = dir.resolve("test.p12");
            ProcessBuilder command =
                    new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                            "-genkeypair",
                            "-alias",
                            "test",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "1",
                            "-dname",
                            "CN=localhost",
                            "-ext",
                            "SAN=dns:localhost,ip:127.0.0.1",
                            "-storetype",
                            "PKCS12",
                            "-keystore",
                            store.toString(),
                            "-storepass:env",
                            "H1_STORE_PASSWORD");
            command.environment().put("H1_STORE_PASSWORD", storePassword);
            Process process = command.start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalStateException("폐기 HTTPS 인증서 생성 실패");
            store.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return store;
        } catch (Exception error) {
            throw new IllegalStateException("격리 HTTPS 준비 실패", error);
        }
    }

    /** 등록은 서버 서비스로 준비하고 로그인·MFA·모든 사건 요청은 실제 브라우저로 실행한다. */
    @Test
    void realHttpsStoryWorkflow() throws Exception {
        String password = UUID.randomUUID() + "-Browser-Only";
        var bootstrap =
                enrollment.createBootstrap(
                        "browser01", "verified_browser", "operator_browser", UUID.randomUUID());
        var grant =
                enrollment.exchange(bootstrap.code(), null, "browser-fixture", UUID.randomUUID());
        enrollment.setPassword(grant.cookie().value(), password, UUID.randomUUID());
        var setup = enrollment.prepareMfa(grant.cookie().value(), UUID.randomUUID());
        Instant previous = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 30 - 1) * 30);
        enrollment.verifyMfa(
                grant.cookie().value(),
                totp.code(setup.secret(), previous),
                "browser-fixture",
                UUID.randomUUID());
        enrollment.complete(grant.cookie().value(), UUID.randomUUID());
        db.update("UPDATE admin_account SET can_create=true,can_review=true");
        String receiverPassword = UUID.randomUUID() + "-Receiver-Only";
        Long inviterId =
                db.queryForObject(
                        "SELECT account_id FROM admin_enrollment WHERE registration_key=?",
                        Long.class,
                        bootstrap.registrationKey());
        UUID inviterKey =
                db.queryForObject(
                        "SELECT account_key FROM admin_account WHERE id=?", UUID.class, inviterId);
        var prepared = frameworkSessions.prepare();
        String fixtureSid = prepared.id();
        Long fixtureAuthRev =
                db.queryForObject(
                        "SELECT auth_rev FROM admin_credential WHERE account_id=?",
                        Long.class,
                        inviterId);
        UUID fixtureSession = UUID.randomUUID();
        Instant now = Instant.now();
        db.update(
                "INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " VALUES (?,?,?,?,'ACTIVE',?,?,?, ?,?)",
                fixtureSession,
                inviterId,
                crypto.sessionHash(fixtureSid),
                fixtureAuthRev,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(8 * 3600)),
                Timestamp.from(now),
                Timestamp.from(now));
        var fixturePrincipal =
                new com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal(
                        inviterId, inviterKey, fixtureSession, fixtureAuthRev);
        frameworkSessions.save(prepared, fixturePrincipal);
        var inviter =
                new AuthModels.SessionPrincipal(
                        inviterId,
                        inviterKey,
                        fixtureSession,
                        now.plusSeconds(8 * 3600),
                        now.plusSeconds(1800),
                        now.plusSeconds(300),
                        true,
                        true,
                        true,
                        false);
        UUID receiverRegistration = UUID.randomUUID();
        var receiverInvitation =
                enrollment.issueInvitation(
                        inviter,
                        fixtureSid,
                        receiverRegistration,
                        "browser02",
                        "verified_receiver",
                        UUID.randomUUID());
        UUID inactiveRegistration = UUID.randomUUID();
        enrollment.issueInvitation(
                inviter,
                fixtureSid,
                inactiveRegistration,
                "browser03",
                "verified_ui_inactive",
                UUID.randomUUID());
        UUID inactiveKey =
                db.queryForObject(
                        "SELECT a.account_key FROM admin_account a JOIN admin_enrollment e"
                                + " ON e.account_id=a.id WHERE e.registration_key=?",
                        UUID.class,
                        inactiveRegistration);
        accounts.deactivate(
                fixtureSid,
                fixturePrincipal,
                inactiveKey,
                "1",
                "OFFBOARDING",
                "verified_ui_inactive",
                UUID.randomUUID());
        db.update(
                "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp() WHERE"
                        + " session_key=?",
                fixtureSession);
        var receiverGrant =
                enrollment.exchange(
                        receiverInvitation.code(), null, "browser-fixture", UUID.randomUUID());
        enrollment.setPassword(receiverGrant.cookie().value(), receiverPassword, UUID.randomUUID());
        var receiverSetup =
                enrollment.prepareMfa(receiverGrant.cookie().value(), UUID.randomUUID());
        enrollment.verifyMfa(
                receiverGrant.cookie().value(),
                totp.code(receiverSetup.secret(), previous),
                "browser-fixture",
                UUID.randomUUID());
        enrollment.complete(receiverGrant.cookie().value(), UUID.randomUUID());
        UUID receiverKey =
                db.queryForObject(
                        "SELECT a.account_key FROM admin_account a JOIN admin_enrollment e ON"
                                + " e.account_id=a.id WHERE e.registration_key=?",
                        UUID.class,
                        receiverRegistration);

        Process browser =
                new ProcessBuilder("node", "src/test/browser/story-workflow.mjs")
                        .inheritIO()
                        .redirectInput(ProcessBuilder.Redirect.PIPE)
                        .start();
        try (var input = browser.getOutputStream()) {
            json.writeValue(
                    input,
                    Map.of(
                            "url",
                            "https://localhost:" + port,
                            "loginId",
                            "browser01",
                            "password",
                            password,
                            "secret",
                            setup.secret(),
                            "receiverLoginId",
                            "browser02",
                            "receiverPassword",
                            receiverPassword,
                            "receiverSecret",
                            receiverSetup.secret(),
                            "receiverKey",
                            receiverKey,
                            "inactiveKey",
                            inactiveKey));
        }
        boolean completed = browser.waitFor(180, TimeUnit.SECONDS);
        if (!completed) browser.destroyForcibly();
        assertThat(completed).as("브라우저 시험 제한 시간").isTrue();
        assertThat(browser.exitValue()).as("실제 HTTPS 브라우저 회귀").isZero();
        // PT-A12 화면의 합성 가로채기는 실제 해소·terminal 실행 자료를 DB에 만들지 않는다.
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_action WHERE action='ISSUE_RESOLVE'",
                                Integer.class))
                .as("합성 PT-A12 UI 전송은 실제 해소 API 검증이 아님")
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM execution_issue WHERE issue_key=?",
                                Integer.class,
                                UUID.fromString("a1212121-1212-4212-8212-121212121212")))
                .as("화면용 합성 지적을 실제 PG 행으로 삽입하지 않음")
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_batch WHERE batch_key=?",
                                Integer.class,
                                UUID.fromString("b1212121-1212-4212-8212-121212121212")))
                .as("화면용 후속 BATCH는 실제 terminal 실행 근거가 아님")
                .isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM story", Integer.class)).isEqualTo(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_version WHERE title = ?",
                                Integer.class,
                                "저장 초안 확인 합성 회귀"))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_sample s JOIN story_version v"
                                        + " ON v.id = s.version_id WHERE v.title = ?"
                                        + " AND s.active_yn AND s.checked_by IS NOT NULL",
                                Integer.class,
                                "저장 초안 확인 합성 회귀"))
                .isEqualTo(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE action IN "
                                        + "('STORY_DEACTIVATED','STORY_REACTIVATED')",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE action IN "
                                        + "('ACCESS_GRANTED','ACCESS_REVOKED')",
                                Integer.class))
                .isEqualTo(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE action='ACCESS_GRANTED' AND"
                                    + " detail->>'verificationRef'='synthetic_lifecycle_review' AND"
                                    + " detail->'after'->>'permission'='REVIEW' AND"
                                    + " detail->'after'->>'activeYn'='true'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE action IN "
                                        + "('OWNER_REQUESTED','OWNER_ACCEPTED')",
                                Integer.class))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM story_action", Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE action='SECTION_UPDATED'",
                                Integer.class))
                .isGreaterThanOrEqualTo(5);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE detail::text LIKE"
                                        + " '%local-answer%'",
                                Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE action='ITEM_CREATED' AND"
                                        + " detail->>'itemKey'='UI_PERSON'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE detail::text LIKE '%인물 UI"
                                        + " 전용 비밀%'",
                                Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE detail::text LIKE '%합성 인물"
                                        + " 비밀%'",
                                Integer.class))
                .isZero();
    }
}
