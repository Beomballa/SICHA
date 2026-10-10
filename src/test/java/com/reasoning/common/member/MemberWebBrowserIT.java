package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 실제 컴파일된 Flutter·폐기 PostgreSQL·합성 SMTP/정책 근거·loopback TLS만 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({LocalMemberAuthIT.Installation.class, MemberWebBrowserIT.WorkerInstallation.class})
public class MemberWebBrowserIT extends LocalMemberAuthIT {
    private static final URI ORIGIN = origin();
    private static final Path ASSETS = assets();
    private static final String STORE_PASSWORD = UUID.randomUUID().toString();
    private static final Path STORE = keyStore();

    @TestConfiguration(proxyBeanMethods = false)
    static class WorkerInstallation {
        @Bean
        WorkerController fixtureWorker() {
            return new WorkerController();
        }
    }

    /** 제품 자산과 component scan에는 없는 시험 전용 제어 워커다. */
    @TestComponent
    @RestController
    static class WorkerController {
        @GetMapping(value = "/player/test-worker.js", produces = "application/javascript")
        String worker() {
            return "self.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));"
                    + "self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));";
        }
    }

    private static URI origin() {
        String value = System.getenv("PLAYER_WEB_TEST_ORIGIN");
        if (value == null) throw new IllegalStateException("PLAYER_WEB_TEST_ORIGIN is required");
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme())
                || !"localhost".equals(uri.getHost())
                || uri.getPort() < 1024
                || uri.getPort() > 65535
                || uri.getPort() == 18443
                || !value.equals("https://localhost:" + uri.getPort())) {
            throw new IllegalStateException(
                    "A canonical nonproduction localhost HTTPS origin is required");
        }
        return uri;
    }

    private static Path assets() {
        String value = System.getenv("PLAYER_WEB_TEST_ASSETS");
        if (value == null) throw new IllegalStateException("PLAYER_WEB_TEST_ASSETS is required");
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute())
                throw new IllegalStateException("Absolute Flutter assets path required");
            path = path.toRealPath();
            if (!Files.isRegularFile(path.resolve("index.html"))
                    || !Files.isRegularFile(path.resolve("main.dart.js"))
                    || !Files.isRegularFile(path.resolve("flutter_bootstrap.js"))) {
                throw new IllegalStateException("Actual compiled Flutter assets are required");
            }
            return path;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Flutter assets unavailable", failure);
        }
    }

    private static Path keyStore() {
        try {
            Path directory =
                    Files.createTempDirectory(
                            "player-browser-tls-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            Path store = directory.resolve("test.p12");
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
                            "PLAYER_TEST_STORE_PASSWORD");
            command.environment().put("PLAYER_TEST_STORE_PASSWORD", STORE_PASSWORD);
            Process process = command.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Test certificate deadline exceeded");
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException("Test certificate creation failed");
            Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-------"));
            store.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            return store;
        } catch (Exception failure) {
            throw new IllegalStateException("Disposable TLS setup failed", failure);
        }
    }

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry properties) {
        properties.add("server.port", ORIGIN::getPort);
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> STORE.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> STORE_PASSWORD);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("app.member-web.enabled", () -> true);
        properties.add("app.member-web.origin", ORIGIN::toString);
        properties.add("app.member-web.assets-directory", ASSETS::toString);
    }

    /** 전용 Gradle 메서드 필터는 상속된 LocalMemberAuthIT 시험을 제외해야 한다. */
    @Test
    void webAuthenticationLifecycle() throws Exception {
        String email = "web-browser-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        long member =
                db.queryForObject(
                        "SELECT member_id FROM member_session WHERE session_key=?",
                        Long.class,
                        UUID.fromString(issued.path("sessionKey").asText()));
        Path receipt = Files.createTempFile("player-browser-result-", ".json");
        Path errors = Files.createTempFile("player-browser-error-", ".txt");
        try {
            Process browser =
                    new ProcessBuilder("node", "src/test/browser/player-web-auth.mjs")
                            .redirectOutput(receipt.toFile())
                            .redirectError(errors.toFile())
                            .start();
            try (var input = browser.getOutputStream()) {
                JSON.writeValue(
                        input,
                        Map.of(
                                "origin",
                                ORIGIN.toString(),
                                "email",
                                email,
                                "password",
                                "Synthetic-member-password-987!",
                                "nickname",
                                "합성회원"));
            }
            if (!browser.waitFor(150, TimeUnit.SECONDS)) {
                browser.destroyForcibly();
                throw new AssertionError("Compiled Flutter browser deadline exceeded");
            }
            // Node의 고정 단계·횟수·비밀 없는 진단만 전달하며 자격 원문은 출력하지 않는다.
            assertThat(browser.exitValue())
                    .withFailMessage("Browser regression failed: %s", Files.readString(errors))
                    .isZero();
            var result = JSON.readTree(receipt.toFile());
            assertThat(result.path("ok").asBoolean()).isTrue();
            assertThat(result.path("counters").path("sentinel").asInt()).isZero();
            assertThat(result.path("counters").path("unknownReloadRefresh").asInt()).isZero();
            assertThat(result.path("counters").path("reloadRefresh").asInt()).isEqualTo(1);
            assertThat(result.path("counters").path("twoTabRefresh").asInt()).isEqualTo(2);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                            + " revoke_code='REFRESH_REUSED'",
                                    Integer.class,
                                    member))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                            + " revoked_at IS NULL",
                                    Integer.class,
                                    member))
                    .isEqualTo(1); // 가입 fixture의 native family만 남는다.
            assertThat(
                            db.queryForObject(
                                    "SELECT coalesce(max(t.generation),0) FROM member_token t JOIN"
                                            + " member_session s ON s.id=t.session_id WHERE"
                                            + " s.member_id=?",
                                    Integer.class,
                                    member))
                    .isGreaterThanOrEqualTo(3);
            // 영수증 원문 대신 검증한 정수 횟수만 XML에 남겨 재현 근거를 보존한다.
            var safeResult = JSON.createObjectNode().put("ok", true);
            var safeCounters = safeResult.putObject("counters");
            for (String name :
                    new String[] {
                        "login",
                        "refresh",
                        "logout",
                        "me",
                        "sentinel",
                        "reloadRefresh",
                        "twoTabRefresh",
                        "unknownReloadRefresh",
                        "boundaryProbes",
                        "redirectRefused",
                        "droppedResponse",
                        "serviceWorkerBlocked"
                    }) {
                var value = result.path("counters").path(name);
                assertThat(value.isIntegralNumber()).isTrue();
                safeCounters.set(name, value);
            }
            var observations = safeResult.putObject("responseObservation");
            for (String name : new String[] {"deliveredFetch200", "checkedFetch200"}) {
                var value = result.path("responseObservation").path(name);
                assertThat(value.isIntegralNumber()).isTrue();
                observations.set(name, value);
            }
            System.out.println("PLAYER_WEB_AUTH_EVIDENCE " + safeResult);
        } finally {
            Files.deleteIfExists(receipt);
            Files.deleteIfExists(errors);
        }
    }

    /** 별도 두 LOCAL 계정의 실제 초대를 컴파일된 Flutter 조사 화면에서 진행한다. */
    @Test
    void webInvestigationLifecycle() throws Exception {
        playtestPolicy();
        String emailA = "web-investigation-a-" + UUID.randomUUID() + "@example.invalid";
        String emailB = "web-investigation-b-" + UUID.randomUUID() + "@example.invalid";
        var nativeA = account(emailA);
        var nativeB = account(emailB);
        UUID memberA =
                invitations
                        .getMemberIdentity(nativeA.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID memberB =
                invitations
                        .getMemberIdentity(nativeB.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID key = invitation(List.of(memberA, memberB));
        List<Long> memberIds =
                List.of(nativeA, nativeB).stream()
                        .map(
                                issued ->
                                        db.queryForObject(
                                                "SELECT member_id FROM member_session WHERE"
                                                    + " session_key=?",
                                                Long.class,
                                                UUID.fromString(
                                                        issued.path("sessionKey").asText())))
                        .toList();
        Path receipt = Files.createTempFile("player-investigation-result-", ".json");
        Path errors = Files.createTempFile("player-investigation-error-", ".txt");
        try {
            Process browser =
                    new ProcessBuilder("node", "src/test/browser/player-web-investigation.mjs")
                            .redirectOutput(receipt.toFile())
                            .redirectError(errors.toFile())
                            .start();
            try (var input = browser.getOutputStream()) {
                JSON.writeValue(
                        input,
                        Map.of(
                                "origin",
                                ORIGIN.toString(),
                                "testKey",
                                key.toString(),
                                "accounts",
                                List.of(
                                        Map.of("email", emailA, "memberKey", memberA.toString()),
                                        Map.of("email", emailB, "memberKey", memberB.toString())),
                                "password",
                                "Synthetic-member-password-987!"));
            }
            if (!browser.waitFor(150, TimeUnit.SECONDS)) {
                browser.destroyForcibly();
                throw new AssertionError("Compiled Flutter investigation deadline exceeded");
            }
            assertThat(browser.exitValue())
                    .withFailMessage("Browser investigation failed: %s", Files.readString(errors))
                    .isZero();
            var result = JSON.readTree(receipt.toFile());
            assertThat(result.path("ok").asBoolean()).isTrue();
            var safe = JSON.createObjectNode().put("ok", true);
            var counts = safe.putObject("counters");
            for (String name :
                    List.of(
                            "login",
                            "logout",
                            "accept",
                            "ready",
                            "start",
                            "hint",
                            "heartbeat",
                            "materials",
                            "validatedMaterials",
                            "droppedAccept",
                            "originalReplay",
                            "boundaryProbes",
                            "backgroundHidden",
                            "sameRoleRecovered",
                            "accountPurged",
                            "runtimeErrors",
                            "excludedCalls")) {
                var value = result.path("counters").path(name);
                assertThat(value.isIntegralNumber() && value.canConvertToLong()).isTrue();
                assertThat(value.longValue()).isBetween(0L, 9007199254740991L);
                counts.set(name, value);
            }
            assertThat(counts.path("accept").asInt()).isEqualTo(3);
            assertThat(counts.path("ready").asInt()).isEqualTo(2);
            assertThat(counts.path("start").asInt()).isEqualTo(1);
            assertThat(counts.path("hint").asInt()).isEqualTo(1);
            assertThat(counts.path("materials").asInt())
                    .isPositive()
                    .isEqualTo(counts.path("validatedMaterials").asInt());
            for (String name :
                    List.of(
                            "droppedAccept",
                            "originalReplay",
                            "backgroundHidden",
                            "sameRoleRecovered",
                            "accountPurged")) {
                assertThat(counts.path(name).asInt()).isEqualTo(1);
            }
            assertThat(counts.path("runtimeErrors").asInt()).isZero();
            assertThat(counts.path("excludedCalls").asInt()).isZero();
            for (long member : memberIds) {
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                            + " revoked_at IS NULL",
                                        Integer.class,
                                        member))
                        .isEqualTo(1);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                            + " revoke_code='REFRESH_REUSED'",
                                        Integer.class,
                                        member))
                        .isZero();
            }
            assertThat(
                            db.queryForList(
                                    "SELECT session_key FROM member_session WHERE member_id IN"
                                        + " (?,?) AND revoked_at IS NULL",
                                    UUID.class,
                                    memberIds.get(0),
                                    memberIds.get(1)))
                    .containsExactlyInAnyOrder(
                            UUID.fromString(nativeA.path("sessionKey").asText()),
                            UUID.fromString(nativeB.path("sessionKey").asText()));
            var participants =
                    db.queryForList(
                            "SELECT slot,member_id,invite_state,role_code FROM test_member m JOIN"
                                + " play_test t ON t.id=m.test_id WHERE t.test_key=? ORDER BY slot",
                            key);
            assertThat(participants).hasSize(2);
            assertThat(participants.stream().map(row -> row.get("slot")).toList())
                    .containsExactly(1, 2);
            assertThat(participants.stream().map(row -> row.get("member_id")).toList())
                    .containsExactlyElementsOf(memberIds);
            assertThat(participants.stream().map(row -> row.get("invite_state")).toList())
                    .containsOnly("ACCEPTED");
            assertThat(participants.stream().map(row -> row.get("role_code")).toList())
                    .containsExactlyInAnyOrder("R1", "R2");
            for (String table : List.of("test_action", "test_audit")) {
                for (var expected :
                        Map.of(
                                        "INVITATION_ACCEPT",
                                        2,
                                        "TEST_READY",
                                        2,
                                        "TEST_START",
                                        1,
                                        "HINT_OPEN",
                                        1)
                                .entrySet()) {
                    assertThat(
                                    db.queryForObject(
                                            "SELECT count(*) FROM "
                                                    + table
                                                    + " WHERE scope_key=? AND action=?",
                                            Integer.class,
                                            "test:" + key,
                                            expected.getKey()))
                            .isEqualTo(expected.getValue());
                }
            }
            System.out.println("PLAYER_WEB_INVESTIGATION_EVIDENCE " + safe);
        } finally {
            Files.deleteIfExists(receipt);
            Files.deleteIfExists(errors);
        }
    }
}
