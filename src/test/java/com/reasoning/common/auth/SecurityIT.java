package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.ssl.enabled=false",
            "app.member-web.enabled=false",
            "app.member-web.origin=",
            "app.development.enabled=true",
            "app.member-auth.collection-enabled=false"
        })
@AutoConfigureMockMvc
@Testcontainers
class SecurityIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 1));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 2));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 3));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.reasoning.admin.auth.session.AdminSessionAdapter sessions;
    @LocalServerPort int port;

    /** 기본 비활성 회원 설정·빈 origin에서도 실제 HTTP로 공개 문서와 고정 자산만 익명 열람한다. */
    @Test
    void defaultHttpFrontendDoesNotNeedAdministratorOrMemberActivation() throws Exception {
        try (var client =
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (String path :
                    List.of(
                            "/",
                            "/account",
                            "/play",
                            "/coop-invite",
                            "/records",
                            "/player-public.css",
                            "/player-common/web-api.mjs",
                            "/player-common/web-session.mjs",
                            "/player-common/account.mjs",
                            "/player-common/game-model.mjs",
                            "/player-common/game-client.mjs",
                            "/player-common/play-page.mjs",
                            "/player-common/invite-bootstrap.js",
                            "/player-common/records-page.mjs")) {
                for (String method : List.of("GET", "HEAD")) {
                    var request =
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                    .timeout(Duration.ofSeconds(10))
                                    .method(method, HttpRequest.BodyPublishers.noBody())
                                    .build();
                    var response =
                            client.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    assertThat(response.statusCode()).as(method + " " + path).isEqualTo(200);
                    assertThat(response.headers().allValues("Location")).isEmpty();
                    assertThat(response.headers().allValues("WWW-Authenticate")).isEmpty();
                    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                    assertThat(response.headers().firstValue("Referrer-Policy"))
                            .contains("no-referrer");
                    if (method.equals("GET") && path.equals("/play")) {
                        assertThat(response.body())
                                .contains("단독·협동 사건 조사", "/player-common/play-page.mjs");
                    }
                }
            }
            var notice =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://localhost:" + port + "/privacy"))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(notice.statusCode()).isEqualTo(503);
            assertThat(notice.body()).contains("현재 고지를 확인할 수 없습니다");
            assertThat(notice.headers().allValues("Location")).isEmpty();
            assertThat(notice.headers().allValues("Set-Cookie")).isEmpty();
        }
    }

    /** 공개 틀이 열려도 관리자·회원 원문 API와 HTTPS·회원 웹 활성화 조건은 그대로 남는다. */
    @Test
    void publicShellDoesNotAuthorizeAdministratorOrMemberData() throws Exception {
        mvc.perform(get("/admin").secure(true))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/admin/login"));
        mvc.perform(get("/admin/api/auth/me").secure(true)).andExpect(status().isUnauthorized());
        for (String path :
                List.of("/api/member/auth/me", "/api/games/current", "/api/member/records")) {
            mvc.perform(get(path).secure(true)).andExpect(status().isUnauthorized());
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
        mvc.perform(
                        get("/api/member/browser-auth/me")
                                .secure(true)
                                .header("X-Sicha-Player-Web", "1"))
                .andExpect(status().isForbidden());
        for (String path :
                List.of("/play/extra", "/player-public.css/extra", "/player-common/private.mjs")) {
            mvc.perform(get(path).secure(true)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/play")).andExpect(status().isForbidden());
        mvc.perform(get("/play?token=synthetic")).andExpect(status().isForbidden());
    }

    /** 개발 플래그를 켜도 일반 실행에는 자동 진입 클래스·경로·자격 발급이 존재하지 않는다. */
    @Test
    void developmentFlagCannotInstallDevelopmentRuntime() throws Exception {
        assertThat(
                        org.springframework.util.ClassUtils.isPresent(
                                "com.reasoning.development.DevelopmentLauncher",
                                getClass().getClassLoader()))
                .isFalse();
        for (String path :
                List.of(
                        "/__development",
                        "/__development/client.mjs",
                        "/__development/member-credentials")) {
            mvc.perform(get(path).secure(true)).andExpect(status().isUnauthorized());
        }
        for (String path :
                List.of("/__development/login/admin", "/__development/member-credentials")) {
            mvc.perform(
                            post(path)
                                    .secure(true)
                                    .header("Origin", "https://localhost")
                                    .header("X-Sicha-Development", "1")
                                    .contentType("application/json")
                                    .content("{\"account\":\"a\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("SESSION-V02: framework session can save, read and delete minimal principal")
    void frameworkSessionPersistence() {
        var prepared = sessions.prepare();
        var principal =
                new com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal(
                        1, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 1);
        sessions.save(prepared, principal);
        assertThat(sessions.findStoredPrincipal(prepared.id())).contains(principal);
        sessions.delete(prepared.id());
        assertThat(sessions.findStoredPrincipal(prepared.id())).isEmpty();
    }

    @Test
    @DisplayName(
            "AUTH-UI-01: public HTML and assets work while protected HTML redirects without"
                    + " credentials")
    void publicPagesAndProtectedRedirect() throws Exception {
        mvc.perform(get("/admin/login").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/admin/auth.css").secure(true)).andExpect(status().isOk());
        mvc.perform(get("/admin/auth.js").secure(true)).andExpect(status().isOk());
        mvc.perform(get("/admin").secure(true))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/admin/login"));
        mvc.perform(get("/admin/api/auth/me").secure(true)).andExpect(status().isUnauthorized());
    }

    /** 공통 UI 자산의 공개가 인접 관리자 자산·화면·API의 인증을 해제하지 않는지 확인한다. */
    @Test
    @DisplayName(
            "UI-SYSTEM-01: only shared UI assets are public; neighboring administrator resources"
                    + " stay protected")
    void sharedAssetsKeepAdministratorBoundary() throws Exception {
        mvc.perform(get("/admin/ui.css").secure(true))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .contentTypeCompatibleWith("text/css"));
        mvc.perform(get("/admin/ui.js").secure(true))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Content-Type",
                                        org.hamcrest.Matchers.containsString("javascript")));
        mvc.perform(get("/admin/shell.js").secure(true)).andExpect(status().isOk());
        for (String path :
                java.util.List.of(
                        "/admin/ui.css/extra",
                        "/admin/ui.js/extra",
                        "/admin/shell.js/extra",
                        "/admin/dashboard.css",
                        "/admin/player-home.css",
                        "/admin/preview/player-home/extra",
                        "/admin/stories.css",
                        "/admin/stories.js",
                        "/admin/api/stories")) {
            mvc.perform(get(path).secure(true))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        }
        mvc.perform(get("/admin/stories").secure(true))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/admin/login"));
        mvc.perform(get("/admin/preview/player-home").secure(true))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/admin/login"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/admin").param("tab", "members").secure(true))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/admin/login"));
    }

    @Test
    @DisplayName("SEC-03: CSRF token is available, but protected API stays closed")
    void csrfAndDefaultDeny() throws Exception {
        mvc.perform(get("/admin/api/auth/csrf").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(get("/admin/api/auth/me").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from access_history where kind='SERVER' and"
                                        + " route='/admin/api/auth/csrf' and http_status=200",
                                Integer.class))
                .isGreaterThanOrEqualTo(1);
        assertThat(
                        jdbc.query(
                                "select route || ':' || coalesce(http_status::text, 'null') from"
                                        + " access_history where kind='SERVER'",
                                (rs, row) -> rs.getString(1)))
                .contains("/admin/api/auth/me:401");
    }

    @Test
    @DisplayName("SEC-03: cross-origin and absent-CSRF mutations are denied")
    void rejectsCrossOriginAndMissingCsrf() throws Exception {
        mvc.perform(
                        post("/admin/api/auth/login")
                                .secure(true)
                                .header("Origin", "https://foreign.example"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/api/auth/login")
                                .secure(true)
                                .header("Origin", "https://localhost"))
                .andExpect(status().isForbidden());
    }

    /** 보안 필터가 차단한 응답의 서버 요청 UUID를 실제 SERVER 접근 이력과 결속한다. */
    @Test
    void deniedSecurityResponseSharesActualAccessHistoryRequestId() throws Exception {
        for (var request :
                java.util.List.of(
                        get("/admin/api/auth/me").secure(true),
                        post("/admin/api/auth/login")
                                .secure(true)
                                .header("Origin", "https://foreign.example"),
                        post("/admin/api/auth/login")
                                .secure(true)
                                .header("Origin", "https://localhost"),
                        post("/admin/api/auth/login")
                                .secure(false)
                                .header("Origin", "http://localhost"))) {
            var result = mvc.perform(request).andReturn();
            int status = result.getResponse().getStatus();
            assertThat(status).isIn(401, 403);
            var body =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(result.getResponse().getContentAsByteArray());
            java.util.UUID id = java.util.UUID.fromString(body.get("requestId").textValue());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM access_history WHERE request_id=? AND"
                                            + " kind='SERVER' AND http_status=? AND route=?",
                                    Integer.class,
                                    id,
                                    status,
                                    result.getRequest().getRequestURI()))
                    .isEqualTo(1);
        }
    }

    /** 승인된 검수 경로만 고정 틀로 저장하고 실제 사건·버전·사본·query를 이력에 복제하지 않는다. */
    @Test
    void reviewRoutesAreNormalizedWithoutSourceIdentifiersOrQuery() throws Exception {
        String base = "/admin/api/stories/SYNTHETIC_SECRET_PATH/versions/4242/";
        String template = "/admin/api/stories/{storyCode}/versions/{versionNo}/";
        for (var entry :
                java.util.Map.of(
                                "review-precheck", "review-precheck",
                                "review-requests", "review-requests",
                                "return-to-draft", "return-to-draft",
                                "preview", "preview",
                                "grade-samples/check", "grade-samples/check",
                                "review-snapshots", "review-snapshots",
                                "review-snapshots/987654", "review-snapshots/{snapshotId}",
                                "review-snapshots/987654/records",
                                        "review-snapshots/{snapshotId}/records")
                        .entrySet()) {
            var result =
                    mvc.perform(
                                    get(base + entry.getKey())
                                            .secure(true)
                                            .param("ignored", "SYNTHETIC_QUERY_SECRET"))
                            .andExpect(status().isUnauthorized())
                            .andReturn();
            Object id =
                    result.getRequest()
                            .getAttribute(
                                    com.reasoning.common.auth.audit.RequestAuditKernel
                                            .REQUEST_ID_ATTRIBUTE);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT route FROM access_history WHERE request_id=? AND"
                                            + " kind='SERVER'",
                                    String.class,
                                    id))
                    .isEqualTo(template + entry.getValue());
        }
    }
}
