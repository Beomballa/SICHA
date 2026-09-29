package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.TotpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

/** 합성 등록 계정으로 실제 HTTPS 로그인·MFA와 폐기 DB 연결 화면을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StoryBrowserIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse(
            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
            .asCompatibleSubstituteFor("postgres"));

    static final String storePassword = UUID.randomUUID().toString();
    static final Path keyStore = createKeyStore();

    @LocalServerPort int port;
    @Autowired EnrollmentService enrollment;
    @Autowired TotpService totp;
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
            Path dir = Files.createTempDirectory("h1-browser-tls-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path store = dir.resolve("test.p12");
            ProcessBuilder command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass:env", "H1_STORE_PASSWORD");
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
        var bootstrap = enrollment.createBootstrap("browser01", "verified_browser", "operator_browser", UUID.randomUUID());
        var grant = enrollment.exchange(bootstrap.code(), null, "browser-fixture", UUID.randomUUID());
        enrollment.setPassword(grant.cookie().value(), password, UUID.randomUUID());
        var setup = enrollment.prepareMfa(grant.cookie().value(), UUID.randomUUID());
        Instant previous = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 30 - 1) * 30);
        enrollment.verifyMfa(grant.cookie().value(), totp.code(setup.secret(), previous), "browser-fixture", UUID.randomUUID());
        enrollment.complete(grant.cookie().value(), UUID.randomUUID());
        db.update("UPDATE admin_account SET can_create=true,can_review=true");

        Process browser = new ProcessBuilder("node", "src/test/browser/story-workflow.mjs").inheritIO()
                .redirectInput(ProcessBuilder.Redirect.PIPE).start();
        try (var input = browser.getOutputStream()) {
            json.writeValue(input, Map.of("url", "https://localhost:" + port, "loginId", "browser01",
                    "password", password, "secret", setup.secret()));
        }
        boolean completed = browser.waitFor(180, TimeUnit.SECONDS);
        if (!completed) browser.destroyForcibly();
        assertThat(completed).as("브라우저 시험 제한 시간").isTrue();
        assertThat(browser.exitValue()).as("실제 HTTPS 브라우저 회귀").isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM story", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE action IN "
                + "('STORY_DEACTIVATED','STORY_REACTIVATED')", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE action IN "
                + "('ACCESS_GRANTED','ACCESS_REVOKED')", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE action='SECTION_UPDATED'", Integer.class))
                .isGreaterThanOrEqualTo(5);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE detail::text LIKE '%local-answer%'", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE action='ITEM_CREATED' AND detail->>'itemKey'='UI_PERSON'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE detail::text LIKE '%인물 UI 전용 비밀%'", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE detail::text LIKE '%합성 인물 비밀%'", Integer.class)).isZero();
    }
}
