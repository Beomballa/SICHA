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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
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
        for (String path :
                java.util.List.of(
                        "/admin/ui.css/extra",
                        "/admin/ui.js/extra",
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
}
