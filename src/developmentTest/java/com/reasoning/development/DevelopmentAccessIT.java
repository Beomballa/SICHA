package com.reasoning.development;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.reasoning.ReasoningApplication;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.CryptoService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** 폐기 PostgreSQL·합성 SMTP·실제 HTTPS·브라우저로 개발 진입의 제품 인증 연계를 검증한다. */
@Testcontainers
@SpringBootTest(
        classes = ReasoningApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(DevelopmentAccessIT.Installation.class)
class DevelopmentAccessIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path HOME = privateHome();
    private static final int PORT = port();
    private static final int SMTP_PORT = port();
    private static final URI ORIGIN = URI.create("https://localhost:" + PORT);
    private static final String STORE_PASSWORD = UUID.randomUUID().toString();
    private static final Path STORE = keyStore();
    private static final String CRYPTO = key("crypto.key");
    private static final String SEARCH = key("search.key");
    private static final String LIMIT = key("limit.key");
    private static final String CORPUS = corpus();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(
                                            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("sicha_development")
                    .withUsername("sicha_development")
                    .withPassword(UUID.randomUUID().toString());

    @Autowired DevelopmentAccounts accounts;
    @Autowired JdbcTemplate db;
    @Autowired AdminSessionAdapter sessions;
    @Autowired CryptoService crypto;

    @Autowired
    @Qualifier("developmentSmtp")
    GreenMail smtp;

    private List<String> rejectionSecrets;

    @TestConfiguration(proxyBeanMethods = false)
    static class Installation {
        @Bean
        DevelopmentState developmentState() {
            return new DevelopmentState(HOME, ORIGIN);
        }

        @Bean(name = "developmentSmtp", initMethod = "start", destroyMethod = "stop")
        GreenMail developmentSmtp() {
            return new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", "smtp"));
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://127.0.0.1:"
                                + POSTGRES.getMappedPort(5432)
                                + "/sicha_development");
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("server.port", () -> PORT);
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> STORE.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> STORE_PASSWORD);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("server.ssl.key-alias", () -> "test");
        properties.add("app.auth.crypto-key-file", () -> CRYPTO);
        properties.add("app.auth.search-key-file", () -> SEARCH);
        properties.add("app.auth.limit-key-file", () -> LIMIT);
        properties.add("app.auth.breached-hashes-file", () -> CORPUS);
        properties.add("app.development.enabled", () -> true);
        properties.add("app.member-auth.env-code", () -> "SYNTHETIC_DEVELOPMENT");
        properties.add("app.member-auth.collection-enabled", () -> true);
        properties.add("app.member-auth.cleanup-enabled", () -> false);
        properties.add("app.member-auth.mail-from", () -> "development@example.invalid");
        properties.add("app.member-web.enabled", () -> true);
        properties.add("app.member-web.origin", ORIGIN::toString);
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> SMTP_PORT);
        properties.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> 5000);
        properties.add("spring.mail.properties.mail.smtp.timeout", () -> 5000);
        properties.add("spring.mail.properties.mail.smtp.writetimeout", () -> 5000);
    }

    /** 자동 시드 후 실제 화면 진입·자료 보존·회수 경계를 한 컨텍스트에서 순서대로 검사한다. */
    @Test
    @Timeout(180)
    void actualAutomaticEntryPreservesDataAndNeverRevivesInvalidSessions() throws Exception {
        assertThat(accounts.isReady()).isTrue();
        var http = client();
        for (String method : List.of("GET", "HEAD")) {
            var response = send(http, method, "/__development", null, request -> {});
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
        var asset = send(http, "GET", "/__development/client.mjs", null, request -> {});
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.body().contains("createWebSession")).isTrue();
        rejectMalformedCredentials(http);

        // 테스트 소유 브라우저가 실제 회원 A/B·복원·기록 조회·관리자 CSRF 생성을 수행한다.
        JsonNode receipt = browser();
        assertThat(receipt.path("ok").asBoolean()).isTrue();
        assertThat(receipt.path("runtimeErrors").asInt(-1)).isZero();
        List<String> keys = new ArrayList<>();
        receipt.path("memberKeys").forEach(value -> keys.add(value.asText()));
        assertThat(keys).hasSize(2).doesNotHaveDuplicates();
        assertThat(
                        db.queryForList(
                                "SELECT member_key::text FROM member_account ORDER BY id",
                                String.class))
                .containsExactlyInAnyOrderElementsOf(keys);
        String story = receipt.path("storyCode").asText();
        assertThat(story).matches("[A-Z0-9_]{1,40}");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story s JOIN story_version v ON"
                                        + " v.story_id=s.id WHERE s.code=? AND v.status='DRAFT' AND"
                                        + " v.title LIKE '합성 개발 회귀 %'",
                                Integer.class, story))
                .isEqualTo(1);

        Administrator administrator = login(http);
        assertThat(sessions.findStoredPrincipal(administrator.id()).isPresent()).isTrue();
        assertThat(sessionState(administrator).get("state")).isEqualTo("ACTIVE");
        assertThat(
                        send(
                                        http,
                                        "GET",
                                        "/admin/api/auth/me",
                                        null,
                                        request -> request.header("Cookie", administrator.cookie()))
                                .statusCode())
                .isEqualTo(200);
        // 방금 만든 시험 소유 초안에만 합성 payload를 두고 재시드가 삭제·수정하지 않는지 검사한다.
        assertThat(
                        db.update(
                                "INSERT INTO"
                                    + " review_snapshot(version_id,edit_rev,format_no,payload,request_key,created_by)"
                                    + " SELECT v.id,v.edit_rev,1,?::jsonb,?,v.created_by FROM"
                                    + " story_version v JOIN story s ON s.id=v.story_id WHERE"
                                    + " s.code=? AND v.version_no=1",
                                JSON.writeValueAsString(
                                        Map.of(
                                                "syntheticDevelopmentRegression",
                                                UUID.randomUUID().toString())),
                                UUID.randomUUID(),
                                story))
                .isEqualTo(1);
        var before = persistentState();
        var files = fileHashes();
        var sessionRows =
                db.queryForList(
                        "SELECT to_jsonb(s)::text FROM admin_session s ORDER BY session_key",
                        String.class);
        int delivered = smtp.getReceivedMessages().length;
        accounts.run(new DefaultApplicationArguments());
        assertThat(accounts.isReady()).isTrue();
        // 비교 실패에서도 자격·암호화 payload를 출력하지 않고 안전한 boolean만 보고한다.
        assertThat(before.equals(persistentState()))
                .as("Account, policy and draft rows preserved")
                .isTrue();
        assertThat(files.equals(fileHashes()))
                .as("Generated credentials, policy evidence and keys preserved")
                .isTrue();
        assertThat(
                        sessionRows.equals(
                                db.queryForList(
                                        "SELECT to_jsonb(s)::text FROM admin_session s ORDER BY"
                                                + " session_key",
                                        String.class)))
                .as("Runner must not reset existing sessions")
                .isTrue();
        assertThat(smtp.getReceivedMessages().length)
                .as("Runner must not repeat signup mail")
                .isEqualTo(delivered);
        verifyRefreshBoundaries(http);
    }

    /** 닫힌 JSON 문법과 헤더·메서드·정확 경로 거절 응답에서 비밀 유출도 함께 검사한다. */
    private void rejectMalformedCredentials(HttpClient http) throws Exception {
        rejectionSecrets =
                List.of(
                        accounts.memberCredentials("a").get("password"),
                        accounts.memberCredentials("b").get("password"));
        List<String> bodies =
                List.of(
                        "{}",
                        "{\"account\":\"c\"}",
                        "{\"account\":1}",
                        "{\"account\":\"a\",\"extra\":true}",
                        "{\"account\":\"a\",\"account\":\"b\"}",
                        "{\"account\":\"a\"} {}",
                        "{\"account\":\"a\"",
                        " ".repeat(16385));
        for (String body : bodies)
            rejected(
                    send(
                            http,
                            "POST",
                            "/__development/member-credentials",
                            body,
                            request -> credentialHeaders(request, "application/json")));
        for (String type :
                List.of(
                        "text/plain",
                        "application/json;charset=ISO-8859-1",
                        "application/json;extra=1"))
            rejected(
                    send(
                            http,
                            "POST",
                            "/__development/member-credentials",
                            "{\"account\":\"a\"}",
                            request -> credentialHeaders(request, type)));
        for (byte[] bytes :
                List.of(
                        "{\"account\":\"a\"}".getBytes(StandardCharsets.UTF_16LE),
                        new byte[] {(byte) 0xff, (byte) 0xfe})) {
            var malformed =
                    HttpRequest.newBuilder(ORIGIN.resolve("/__development/member-credentials"))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            credentialHeaders(malformed, "application/json");
            rejected(http.send(malformed.build(), HttpResponse.BodyHandlers.ofString()));
        }
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request -> {}));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request ->
                                request.header("Origin", ORIGIN.toString())
                                        .header("Content-Type", "application/json")));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request ->
                                request.header("X-Sicha-Development", "1")
                                        .header("Content-Type", "application/json")));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request ->
                                request.header("Origin", ORIGIN.toString())
                                        .header("X-Sicha-Development", "1")));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request ->
                                request.header("Origin", ORIGIN.toString())
                                        .header("X-Sicha-Development", "0")
                                        .header("Content-Type", "application/json")));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request ->
                                request.header("Origin", "https://foreign.invalid")
                                        .header("X-Sicha-Development", "1")
                                        .header("Content-Type", "application/json")));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request -> {
                            credentialHeaders(request, "application/json");
                            request.header("X-Sicha-Development", "1");
                        }));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request -> {
                            credentialHeaders(request, "application/json");
                            request.header("Origin", ORIGIN.toString());
                        }));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials",
                        "{\"account\":\"a\"}",
                        request -> {
                            credentialHeaders(request, "application/json");
                            request.header("Sec-Fetch-Site", "cross-site");
                        }));
        for (String method : List.of("GET", "HEAD", "PUT", "OPTIONS"))
            rejected(send(http, method, "/__development/member-credentials", null, request -> {}));
        for (String path :
                List.of(
                        "/__development?x=1",
                        "/__development/client.mjs?x=1",
                        "/__development/",
                        "/__development/unknown"))
            rejected(send(http, "GET", path, null, request -> {}));
        rejected(
                send(
                        http,
                        "POST",
                        "/__development/member-credentials?account=a",
                        "{\"account\":\"a\"}",
                        request -> credentialHeaders(request, "application/json")));
    }

    private static void credentialHeaders(HttpRequest.Builder request, String type) {
        request.header("Origin", ORIGIN.toString())
                .header("X-Sicha-Development", "1")
                .header("Content-Type", type);
    }

    private void rejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).isBetween(400, 499);
        assertThat(response.body().contains("password"))
                .as("Rejected response contains no credential fields")
                .isFalse();
        for (String secret : rejectionSecrets)
            assertThat(response.body().contains(secret))
                    .as("Rejected response contains no generated secret")
                    .isFalse();
        assertThat(response.headers().allValues("Set-Cookie").isEmpty())
                .as("Rejected response must issue no cookies")
                .isTrue();
    }

    /** 실제 발급 세션 중 유효한 개발 표식·현재 주체만 최근 인증을 갱신할 수 있다. */
    private void verifyRefreshBoundaries(HttpClient http) throws Exception {
        Administrator valid = login(http);
        db.update(
                "UPDATE admin_session SET started_at=CURRENT_TIMESTAMP-interval '1"
                        + " hour',expires_at=CURRENT_TIMESTAMP+interval '7"
                        + " hours',reauth_at=CURRENT_TIMESTAMP-interval '20 minutes' WHERE"
                        + " session_key=?",
                valid.key());
        Object old = sessionState(valid).get("reauth_at");
        assertThat(
                        send(
                                        http,
                                        "GET",
                                        "/admin/api/auth/me",
                                        null,
                                        request -> request.header("Cookie", valid.cookie()))
                                .statusCode())
                .isEqualTo(200);
        assertThat(old.equals(sessionState(valid).get("reauth_at")))
                .as("Only valid marked current actor receives recent authentication")
                .isFalse();
        for (String mutation :
                List.of(
                        "state='REVOKED',revoked_at=clock_timestamp()",
                        "auth_rev=auth_rev+1",
                        "started_at=CURRENT_TIMESTAMP-interval '9"
                                + " hours',expires_at=CURRENT_TIMESTAMP-interval '1 hour'",
                        "started_at=CURRENT_TIMESTAMP-interval '1"
                            + " hour',expires_at=CURRENT_TIMESTAMP+interval '7"
                            + " hours',last_action_at=CURRENT_TIMESTAMP-interval '31 minutes'")) {
            Administrator invalid = login(http);
            db.update(
                    "UPDATE admin_session SET " + mutation + " WHERE session_key=?", invalid.key());
            Object reauth = sessionState(invalid).get("reauth_at");
            var response =
                    send(
                            http,
                            "GET",
                            "/admin/api/auth/me",
                            null,
                            request -> request.header("Cookie", invalid.cookie()));
            assertThat(response.statusCode()).isBetween(400, 499);
            assertThat(reauth.equals(sessionState(invalid).get("reauth_at")))
                    .as("Invalid session must not be revived")
                    .isTrue();
        }
        Administrator unmarked = login(http);
        // 현재 시험이 발급한 세션 표식만 제거해 일반 세션을 개발 세션으로 오인하지 않는지 확인한다.
        Files.delete(HOME.resolve("synthetic-session-" + unmarked.key() + ".json"));
        db.update(
                "UPDATE admin_session SET started_at=CURRENT_TIMESTAMP-interval '1"
                        + " hour',expires_at=CURRENT_TIMESTAMP+interval '7"
                        + " hours',reauth_at=CURRENT_TIMESTAMP-interval '20 minutes' WHERE"
                        + " session_key=?",
                unmarked.key());
        Object reauth = sessionState(unmarked).get("reauth_at");
        send(
                http,
                "GET",
                "/admin/api/auth/me",
                null,
                request -> request.header("Cookie", unmarked.cookie()));
        assertThat(reauth.equals(sessionState(unmarked).get("reauth_at")))
                .as("Unmarked session is never refreshed")
                .isTrue();

        // 외부 계정·Spring Session 모두 이 폐기 DB의 시험 소유 행이다.
        UUID accountKey = UUID.randomUUID();
        long accountId =
                db.queryForObject(
                        "INSERT INTO admin_account(account_key,active_yn) VALUES (?,true) RETURNING"
                                + " id",
                        Long.class,
                        accountKey);
        var prepared = sessions.prepare();
        UUID foreignSession = UUID.randomUUID();
        sessions.save(prepared, new AdminPrincipal(accountId, accountKey, foreignSession, 1));
        db.update(
                "INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " VALUES (?,?,?,1,'ACTIVE',CURRENT_TIMESTAMP-interval '1"
                    + " hour',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '7"
                    + " hours',CURRENT_TIMESTAMP-interval '20 minutes',CURRENT_TIMESTAMP)",
                foreignSession,
                accountId,
                crypto.sessionHash(prepared.id()));
        Administrator foreign =
                new Administrator(
                        prepared.id(),
                        "__Host-admin-session="
                                + Base64.getEncoder()
                                        .encodeToString(
                                                prepared.id().getBytes(StandardCharsets.UTF_8)),
                        foreignSession);
        Object foreignReauth = sessionState(foreign).get("reauth_at");
        assertThat(
                        send(
                                        http,
                                        "GET",
                                        "/admin/api/auth/me",
                                        null,
                                        request -> request.header("Cookie", foreign.cookie()))
                                .statusCode())
                .isBetween(400, 499);
        assertThat(foreignReauth.equals(sessionState(foreign).get("reauth_at")))
                .as("Foreign administrator session is untouched")
                .isTrue();
    }

    private Administrator login(HttpClient http) throws Exception {
        var response =
                send(
                        http,
                        "POST",
                        "/__development/login/admin",
                        "",
                        request -> request.header("Origin", ORIGIN.toString()));
        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location")).contains("/admin");
        String cookie =
                response.headers().allValues("Set-Cookie").stream()
                        .filter(value -> value.startsWith("__Host-admin-session="))
                        .findFirst()
                        .orElseThrow()
                        .split(";", 2)[0];
        String id =
                new String(
                        Base64.getDecoder().decode(cookie.substring(cookie.indexOf('=') + 1)),
                        StandardCharsets.UTF_8);
        var principal = sessions.findStoredPrincipal(id).orElseThrow();
        return new Administrator(id, cookie, principal.sessionKey());
    }

    private Map<String, Object> sessionState(Administrator administrator) {
        return db.queryForMap(
                "SELECT state,reauth_at FROM admin_session WHERE session_key=?",
                administrator.key());
    }

    /** 실패 메시지로 출력하지 않을 전체 행 스냅샷이며 시험 DB 외에는 접근하지 않는다. */
    private List<String> persistentState() {
        var rows = new ArrayList<String>();
        for (String table :
                List.of(
                        "admin_account",
                        "admin_credential",
                        "member_account",
                        "member_identity",
                        "member_profile",
                        "member_session",
                        "member_token",
                        "privacy_policy",
                        "story",
                        "story_version",
                        "review_snapshot"))
            rows.addAll(
                    db.queryForList(
                            "SELECT to_jsonb(t)::text FROM "
                                    + table
                                    + " t ORDER BY to_jsonb(t)::text",
                            String.class));
        return rows;
    }

    /** 소유한 임시 디렉터리의 키·정책·payload·세션 표식 바이트를 해시로 비교한다. */
    private Map<String, String> fileHashes() throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        try (var paths = Files.list(HOME)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList())
                hashes.put(
                        file.getFileName().toString(),
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(Files.readAllBytes(file))));
        }
        return hashes;
    }

    /** 브라우저 실행을 필수화하고 영수증·오류는 0600 파일에만 남긴다. */
    private static JsonNode browser() throws Exception {
        Path output = privateFile("browser-output.json", new byte[0]);
        Path errors = privateFile("browser-errors.json", new byte[0]);
        var command = new ProcessBuilder("node", "src/test/browser/development-access.mjs");
        command.redirectOutput(output.toFile()).redirectError(errors.toFile());
        Process process = command.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(JSON.writeValueAsBytes(Map.of("origin", ORIGIN.toString())));
            }
            boolean exited = process.waitFor(160, TimeUnit.SECONDS);
            assertThat(exited).as("Required real HTTPS browser helper deadline").isTrue();
            assertThat(process.exitValue())
                    .as("Required real HTTPS browser helper; private receipt: %s", errors)
                    .isZero();
            assertThat(Files.size(errors)).as("Browser helper emits no diagnostic errors").isZero();
            return JSON.readTree(Files.readAllBytes(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static HttpResponse<String> send(
            HttpClient http,
            String method,
            String path,
            String body,
            Consumer<HttpRequest.Builder> headers)
            throws Exception {
        var request = HttpRequest.newBuilder(ORIGIN.resolve(path)).timeout(Duration.ofSeconds(10));
        headers.accept(request);
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 자체 서명 신뢰 예외는 이 시험의 loopback HTTPS 클라이언트에만 한정한다. */
    private static HttpClient client() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
                null,
                new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    }
                },
                new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(context)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static Path privateHome() {
        try {
            return Files.createTempDirectory(
                            "sicha-development-it-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")))
                    .toRealPath();
        } catch (Exception failure) {
            throw new IllegalStateException("개발 시험 전용 디렉터리 생성 실패");
        }
    }

    private static int port() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (Exception failure) {
            throw new IllegalStateException("개발 시험 루프백 포트 확보 실패");
        }
    }

    private static Path privateFile(String name, byte[] bytes) throws Exception {
        Path file = HOME.resolve(name);
        Files.createFile(
                file,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, bytes);
        return file;
    }

    /** 공개 고정 키 대신 각 폐기 컨텍스트마다 독립 난수 키를 생성한다. */
    private static String key(String name) {
        try {
            byte[] value = new byte[32];
            new SecureRandom().nextBytes(value);
            return privateFile(name, Base64.getUrlEncoder().withoutPadding().encode(value))
                    .toString();
        } catch (Exception failure) {
            throw new IllegalStateException("개발 시험 비밀 키 생성 실패");
        }
    }

    private static String corpus() {
        try {
            return privateFile(
                            "breached.txt",
                            (HexFormat.of()
                                                    .withUpperCase()
                                                    .formatHex(
                                                            MessageDigest.getInstance("SHA-1")
                                                                    .digest(
                                                                            "Synthetic-Previously-Leaked-9!"
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .UTF_8)))
                                            + "\n")
                                    .getBytes(StandardCharsets.UTF_8))
                    .toString();
        } catch (Exception failure) {
            throw new IllegalStateException("합성 유출 비밀번호 목록 생성 실패");
        }
    }

    /** 비밀번호는 keytool 인수가 아닌 환경으로 전달하고 진단도 소유자 전용 파일에 둔다. */
    private static Path keyStore() {
        try {
            Path store = HOME.resolve("test.p12");
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
                            "DEVELOPMENT_IT_STORE_PASSWORD");
            command.environment().put("DEVELOPMENT_IT_STORE_PASSWORD", STORE_PASSWORD);
            command.redirectOutput(privateFile("keytool-out.log", new byte[0]).toFile());
            command.redirectError(privateFile("keytool-err.log", new byte[0]).toFile());
            Process process = command.start();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0)
                    throw new IllegalStateException("합성 TLS 생성 실패");
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
            Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-------"));
            return store;
        } catch (Exception failure) {
            throw new IllegalStateException("개발 시험 TLS 키 저장소 생성 실패");
        }
    }

    private record Administrator(String id, String cookie, UUID key) {}
}
