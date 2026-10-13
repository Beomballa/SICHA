package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 실제 제품 HTML·JavaScript·폐기 PostgreSQL·SMTP·HTTPS를 Flutter 없이 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class NativeAccountWebIT extends LocalMemberAuthIT {
    private static final int PORT = testPort();
    private static final String ORIGIN = "https://localhost:" + PORT;
    private static final String STORE_PASSWORD = UUID.randomUUID().toString();
    private static final Path STORE = keyStore();

    /** 운영 포트를 지정하지 않고 루프백 임시 포트를 확보한다. */
    private static int testPort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (Exception failure) {
            throw new IllegalStateException("Disposable account port unavailable", failure);
        }
    }

    /** 폐기형 시험 키는 소유자 전용 임시 디렉터리에 만들고 자격 원문을 출력하지 않는다. */
    private static Path keyStore() {
        try {
            Path directory =
                    Files.createTempDirectory(
                            "sicha-account-tls-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            Path store = directory.resolve("test.p12");
            var command =
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
                            "ACCOUNT_TEST_STORE_PASSWORD");
            command.environment().put("ACCOUNT_TEST_STORE_PASSWORD", STORE_PASSWORD);
            var process = command.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Disposable certificate deadline exceeded");
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException("Disposable certificate unavailable");
            Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-------"));
            directory.toFile().deleteOnExit();
            store.toFile().deleteOnExit();
            return store;
        } catch (Exception failure) {
            throw new IllegalStateException("Disposable account TLS unavailable", failure);
        }
    }

    /** 같은 실제 connector 원점을 회원 웹 정책과 TLS에 함께 설치한다. */
    @DynamicPropertySource
    static void webProperties(DynamicPropertyRegistry properties) {
        properties.add("server.port", () -> PORT);
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> STORE.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> STORE_PASSWORD);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("app.member-web.enabled", () -> true);
        properties.add("app.member-web.origin", () -> ORIGIN);
    }

    /** 실제 폼 입력·메일 확인·복구·최근 확인·전체 회수를 DB 결과와 함께 검사한다. */
    @Test
    void nativeAccountLifecycle() throws Exception {
        String email = "native-web-" + UUID.randomUUID() + "@example.invalid";
        var smtp = (GreenMail) ReflectionTestUtils.getField(LocalMemberAuthIT.class, "SMTP");
        assertThat(smtp).isNotNull();
        int expected = smtp.getReceivedMessages().length + 1;
        Path receipt = Files.createTempFile("sicha-account-result-", ".json");
        Path errors = Files.createTempFile("sicha-account-error-", ".txt");
        Process browser = null;
        try {
            browser =
                    new ProcessBuilder("node", "src/test/browser/native-account.mjs")
                            .redirectOutput(receipt.toFile())
                            .redirectError(errors.toFile())
                            .start();
            try (var input = browser.getOutputStream()) {
                input.write(
                        (JSON.writeValueAsString(
                                                Map.of(
                                                        "origin",
                                                        ORIGIN,
                                                        "email",
                                                        email,
                                                        "password",
                                                        "Synthetic-member-password-987!",
                                                        "nickname",
                                                        "합성웹회원"))
                                        + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                input.flush();
                for (int delivery = 0; delivery < 2; delivery++) {
                    assertThat(smtp.waitForIncomingEmail(45000, expected + delivery)).isTrue();
                    var message = smtp.getReceivedMessages()[expected + delivery - 1];
                    assertThat(message.getAllRecipients()[0].toString()).isEqualTo(email);
                    var code =
                            java.util.regex.Pattern.compile("[0-9]{8}")
                                    .matcher(message.getContent().toString());
                    assertThat(code.find()).isTrue();
                    input.write(
                            (JSON.writeValueAsString(Map.of("code", code.group())) + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
                    input.flush();
                }
            }
            if (!browser.waitFor(150, TimeUnit.SECONDS)) {
                browser.destroyForcibly();
                throw new AssertionError("Native account browser deadline exceeded");
            }
            assertThat(browser.exitValue())
                    .withFailMessage("Native account regression: %s", Files.readString(errors))
                    .isZero();
            var result = JSON.readTree(receipt.toFile());
            assertThat(result.path("ok").asBoolean()).isTrue();
            assertThat(result.path("unexpectedRequests").asInt()).isZero();
            assertThat(result.path("runtimeErrors").asInt()).isZero();
            String lookup = java.util.HexFormat.of().formatHex(crypto.memberEmailHash(email));
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM member_identity WHERE lookup_hash=? AND"
                                            + " active_yn",
                                    Integer.class,
                                    lookup))
                    .isEqualTo(1);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM member_session s JOIN member_identity i"
                                            + " ON i.id=s.identity_id WHERE i.lookup_hash=? AND"
                                            + " s.revoked_at IS NULL",
                                    Integer.class,
                                    lookup))
                    .isZero();
        } finally {
            if (browser != null && browser.isAlive()) browser.destroyForcibly();
            Files.deleteIfExists(receipt);
            Files.deleteIfExists(errors);
        }
    }

    /** 실제 회원과 제품 문서에서 수집 미준비를 가짜 빈 목록이나 공개 사건으로 바꾸지 않는다. */
    @Test
    void soloCatalogDoesNotBypassRealCollectionGate() throws Exception {
        configuration.setFriendlyCollectionEnabled(false);
        String email = "solo-web-gate-" + UUID.randomUUID() + "@example.invalid";
        account(email);
        int roomsBefore =
                db.queryForObject(
                        "SELECT count(*) FROM game_room WHERE purpose='PUBLIC'", Integer.class);
        Path receipt = Files.createTempFile("sicha-solo-gate-", ".json");
        Path errors = Files.createTempFile("sicha-solo-gate-error-", ".txt");
        Process browser = null;
        try {
            browser =
                    new ProcessBuilder("node", "src/test/browser/native-solo-gate.mjs")
                            .redirectOutput(receipt.toFile())
                            .redirectError(errors.toFile())
                            .start();
            try (var input = browser.getOutputStream()) {
                JSON.writeValue(
                        input,
                        Map.of(
                                "origin",
                                ORIGIN,
                                "email",
                                email,
                                "password",
                                "Synthetic-member-password-987!"));
            }
            if (!browser.waitFor(60, TimeUnit.SECONDS)) {
                browser.destroyForcibly();
                throw new AssertionError("Native solo gate browser deadline exceeded");
            }
            assertThat(browser.exitValue())
                    .withFailMessage("Solo browser gate: %s", Files.readString(errors))
                    .isZero();
            assertThat(JSON.readTree(receipt.toFile()).path("ok").asBoolean()).isTrue();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM game_room WHERE purpose='PUBLIC'",
                                    Integer.class))
                    .isEqualTo(roomsBefore);
        } finally {
            if (browser != null && browser.isAlive()) browser.destroyForcibly();
            Files.deleteIfExists(receipt);
            Files.deleteIfExists(errors);
        }
    }
}
