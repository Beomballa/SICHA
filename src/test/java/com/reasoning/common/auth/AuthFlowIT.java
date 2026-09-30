package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reasoning.admin.auth.service.LoginSessionService;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.RecoveryService;
import com.reasoning.common.auth.service.TotpService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
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

import java.time.Instant;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowIT extends DatabaseContextTest {
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

    @Autowired EnrollmentService enrollment;
    @Autowired LoginSessionService login;
    @Autowired RecoveryService recovery;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test
    @DisplayName(
            "AUTH-V01/AUTH-V09/SESSION-V01: bootstrap, MFA enrollment, session activation and"
                + " revocation")
    void enrollmentAndSessionLifecycle() throws Exception {
        String password = "Example-Distinct-Password-9876";
        var initial =
                enrollment.createBootstrap(
                        "admin01", "verify_012345", "operator_01", UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                enrollment.createBootstrap(
                                        "admin02",
                                        "verify_012345",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<com.reasoning.common.auth.service.AuthModels.EnrollExchange>
                attempt =
                        () -> {
                            ready.countDown();
                            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Exchange workers did not start");
                            try {
                                return enrollment.exchange(
                                        initial.code(), null, "test-source", UUID.randomUUID());
                            } catch (AuthException consumed) {
                                return null;
                            }
                        };
        com.reasoning.common.auth.service.AuthModels.EnrollExchange exchanged;
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results =
                    java.util.Arrays.asList(
                            first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
            exchanged =
                    results.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        }
        String grant = exchanged.cookie().value();
        assertThat(exchanged.cookie().name()).isEqualTo("__Host-admin-enroll");
        assertThat(enrollment.stage(grant).stage()).isEqualTo("PASSWORD_REQUIRED");
        assertThatThrownBy(
                        () ->
                                enrollment.setPassword(
                                        grant, "Password-Already-Leaked-1234", UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        enrollment.setPassword(grant, password, UUID.randomUUID());
        var mfa = enrollment.prepareMfa(grant, UUID.randomUUID());
        assertThat(
                        java.net.URLDecoder.decode(
                                mfa.provisioningUri(), java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("otpauth://totp/시차:")
                .contains("&issuer=시차&");
        var at =
                jdbc.queryForObject(
                        "select clock_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
        long previous = at.getEpochSecond() / 30 - 1;
        enrollment.verifyMfa(
                grant,
                totp.code(mfa.secret(), Instant.ofEpochSecond(previous * 30)),
                "test-source",
                UUID.randomUUID());
        jdbc.execute(
                "create function reject_completion_audit() returns trigger language plpgsql as $$"
                    + " begin if NEW.action='ENROLL_COMPLETED' then raise exception 'audit"
                    + " unavailable'; end if; return NEW; end $$");
        jdbc.execute(
                "create trigger reject_completion before insert on admin_auth_audit for each row"
                    + " execute function reject_completion_audit()");
        assertThatThrownBy(() -> enrollment.complete(grant, UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(enrollment.stage(grant).stage()).isEqualTo("READY_TO_COMPLETE");
        assertThat(jdbc.queryForObject("select count(*) from admin_recovery_code", Integer.class))
                .isZero();
        jdbc.execute("drop trigger reject_completion on admin_auth_audit");
        jdbc.execute("drop function reject_completion_audit()");
        var completed = enrollment.complete(grant, UUID.randomUUID());
        assertThat(completed.recoveryCodes()).hasSize(10);
        assertThatThrownBy(() -> enrollment.complete(grant, UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        var step = login.login("admin01", password, null, null, "test-source", UUID.randomUUID());
        assertThat(step.stage()).isEqualTo("MFA_REQUIRED");
        String currentCode = totp.code(mfa.secret(), Instant.now());
        var mfaReady = new java.util.concurrent.CountDownLatch(2);
        var mfaStart = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<com.reasoning.common.auth.service.AuthModels.Authenticated>
                mfaAttempt =
                        () -> {
                            mfaReady.countDown();
                            if (!mfaStart.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("MFA workers did not start");
                            try {
                                return login.authenticate(
                                        step.cookie().value(),
                                        currentCode,
                                        "test-source",
                                        UUID.randomUUID());
                            } catch (AuthException consumed) {
                                return null;
                            }
                        };
        com.reasoning.common.auth.service.AuthModels.Authenticated authenticated;
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(mfaAttempt);
            var second = workers.submit(mfaAttempt);
            assertThat(mfaReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mfaStart.countDown();
            var results =
                    java.util.Arrays.asList(
                            first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
            authenticated =
                    results.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        }
        assertThat(authenticated.result()).isEqualTo("AUTHENTICATED");
        String sid = authenticated.cookie().value();
        var principal = loginPrincipal(sid);
        assertThatThrownBy(() -> login.status(step.cookie().value()))
                .isInstanceOf(AuthException.class);
        var responseLostRetry =
                login.login("admin01", password, null, null, "test-source", UUID.randomUUID());
        assertThat(responseLostRetry.stage()).isEqualTo("MFA_REQUIRED");
        login.cancel(responseLostRetry.cookie().value(), UUID.randomUUID());
        assertThat(login.isActive(sid, principal)).isTrue();
        var originalTimes =
                jdbc.queryForMap(
                        "select started_at,last_action_at,expires_at,reauth_at from admin_session"
                            + " where session_key=?",
                        principal.sessionKey());
        jdbc.update(
                "with stamp as (select clock_timestamp() as instant) update admin_session set"
                    + " started_at=stamp.instant-interval '31"
                    + " minutes',last_action_at=stamp.instant-interval '31"
                    + " minutes',expires_at=stamp.instant+interval '7 hours 29"
                    + " minutes',reauth_at=stamp.instant from stamp where session_key=?",
                principal.sessionKey());
        var idleBefore =
                jdbc.queryForObject(
                        "select last_action_at from admin_session where session_key=?",
                        java.sql.Timestamp.class,
                        principal.sessionKey());
        var fakeRequest =
                new org.springframework.mock.web.MockHttpServletRequest(
                        "POST", "/admin/api/auth/recovery-codes");
        var fakeResponse = new org.springframework.mock.web.MockHttpServletResponse();
        try {
            new com.reasoning.admin.auth.audit.AccessHistoryFilter(jdbc)
                    .doFilter(
                            fakeRequest,
                            fakeResponse,
                            (request, response) -> {
                                org.springframework.security.core.context.SecurityContextHolder
                                        .getContext()
                                        .setAuthentication(
                                                org.springframework.security.authentication
                                                        .UsernamePasswordAuthenticationToken
                                                        .authenticated(
                                                                principal,
                                                                null,
                                                                java.util.List.of()));
                                ((jakarta.servlet.http.HttpServletResponse) response)
                                        .setStatus(201);
                            });
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(
                        jdbc.queryForObject(
                                "select last_action_at from admin_session where session_key=?",
                                java.sql.Timestamp.class,
                                principal.sessionKey()))
                .isEqualTo(idleBefore);
        assertThat(login.isActive(sid, principal)).isFalse();
        jdbc.update(
                "update admin_session set started_at=?,last_action_at=?,expires_at=?,reauth_at=?"
                    + " where session_key=?",
                originalTimes.get("started_at"),
                originalTimes.get("last_action_at"),
                originalTimes.get("expires_at"),
                originalTimes.get("reauth_at"),
                principal.sessionKey());
        assertThat(login.me(sid, principal).permissions()).contains("MANAGE");
        var issued = new org.springframework.mock.web.MockHttpServletResponse();
        sessionAdapter.issueCookie(
                sid, new org.springframework.mock.web.MockHttpServletRequest(), issued);
        Cookie session = issued.getCookie("__Host-admin-session");
        mvc.perform(get("/admin/auth/manage").secure(true).cookie(session))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from access_history where kind='SERVER' and"
                                    + " route='/admin/auth/manage' and actor_kind='ADMIN'",
                                Integer.class))
                .isEqualTo(1);
        var csrfResult =
                mvc.perform(get("/admin/api/auth/csrf").secure(true))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("__Host-admin-csrf");
        String csrfToken =
                json.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        String event = "{\"eventKey\":\"" + UUID.randomUUID() + "\",\"screenCode\":\"ADMIN_HOME\"}";
        var nav =
                post("/admin/api/history/navigation")
                        .secure(true)
                        .with(
                                request -> {
                                    request.setScheme("https");
                                    request.setServerPort(443);
                                    return request;
                                })
                        .header("Origin", "https://localhost")
                        .header("X-CSRF-TOKEN", csrfToken)
                        .cookie(session, csrfCookie)
                        .contentType("application/json")
                        .content(event);
        mvc.perform(nav).andExpect(status().isNoContent());
        mvc.perform(
                        post("/admin/api/history/navigation")
                                .secure(true)
                                .with(
                                        request -> {
                                            request.setScheme("https");
                                            request.setServerPort(443);
                                            return request;
                                        })
                                .header("Origin", "https://localhost")
                                .header("X-CSRF-TOKEN", csrfToken)
                                .cookie(session, csrfCookie)
                                .contentType("application/json")
                                .content(event))
                .andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from access_history where kind='NAV' and"
                                    + " screen_code='ADMIN_HOME' and http_status is null",
                                Integer.class))
                .isEqualTo(1);
        long consumedStep =
                jdbc.queryForObject("select last_step from admin_credential", Long.class);
        Instant nextStepTime;
        do {
            nextStepTime =
                    jdbc.queryForObject(
                            "select clock_timestamp()",
                            (rs, row) -> rs.getTimestamp(1).toInstant());
            if (nextStepTime.getEpochSecond() / 30 <= consumedStep) Thread.sleep(250);
        } while (nextStepTime.getEpochSecond() / 30 <= consumedStep);
        var rotated =
                login.reauth(
                        sid,
                        principal,
                        password,
                        totp.code(mfa.secret(), nextStepTime),
                        "test-source",
                        UUID.randomUUID());
        assertThat(rotated.absoluteExpiresAt()).isEqualTo(authenticated.absoluteExpiresAt());
        assertThat(login.isActive(sid, principal)).isFalse();
        String activeSid = rotated.cookie().value();
        var activePrincipal = loginPrincipal(activeSid);
        assertThat(login.isActive(activeSid, activePrincipal)).isTrue();
        var actor =
                new com.reasoning.common.auth.service.AuthModels.SessionPrincipal(
                        activePrincipal.accountId(),
                        activePrincipal.accountKey(),
                        activePrincipal.sessionKey(),
                        authenticated.absoluteExpiresAt(),
                        Instant.now().plusSeconds(1800),
                        Instant.now().plusSeconds(300),
                        true,
                        false,
                        false,
                        false);
        var resetCode =
                recovery.issue(
                        actor,
                        activeSid,
                        activePrincipal.accountKey(),
                        "PASSWORD_RESET",
                        "verified_01",
                        UUID.randomUUID());
        jdbc.execute(
                "create function reject_logout_audit() returns trigger language plpgsql as $$ begin"
                    + " if NEW.action='SESSION_REVOKED' then raise exception 'audit unavailable';"
                    + " end if; return NEW; end $$");
        jdbc.execute(
                "create trigger reject_logout before insert on admin_auth_audit for each row"
                    + " execute function reject_logout_audit()");
        login.logout(activeSid, activePrincipal, UUID.randomUUID());
        assertThat(login.isActive(activeSid, activePrincipal)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from admin_auth_audit where"
                                    + " action='SESSION_REVOKED'",
                                Integer.class))
                .isZero();
        jdbc.execute("drop trigger reject_logout on admin_auth_audit");
        jdbc.execute("drop function reject_logout_audit()");
        assertThatThrownBy(
                        () ->
                                recovery.exchange(
                                        resetCode.code(),
                                        "MFA_RECOVERY",
                                        "test-source",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> recovery.resetPassword(null, "short", UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("AUTH_FLOW_UNAVAILABLE");
        var resetResponse =
                mvc.perform(
                                post("/admin/api/auth/recovery/exchange")
                                        .secure(true)
                                        .with(
                                                request -> {
                                                    request.setScheme("https");
                                                    request.setServerPort(443);
                                                    return request;
                                                })
                                        .header("Origin", "https://localhost")
                                        .header("X-CSRF-TOKEN", csrfToken)
                                        .cookie(csrfCookie)
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        java.util.Map.of(
                                                                "code",
                                                                resetCode.code(),
                                                                "purpose",
                                                                "PASSWORD_RESET"))))
                        .andExpect(status().isOk())
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .jsonPath("$.stage")
                                        .value("PASSWORD_REQUIRED"))
                        .andReturn()
                        .getResponse();
        String recoveryHeader =
                resetResponse.getHeaders("Set-Cookie").stream()
                        .filter(value -> value.startsWith("__Host-admin-recovery="))
                        .findFirst()
                        .orElseThrow();
        Cookie recoveryCookie =
                new Cookie(
                        "__Host-admin-recovery",
                        recoveryHeader.split(";", 2)[0].substring(
                                "__Host-admin-recovery=".length()));
        mvc.perform(get("/admin/api/auth/recovery").secure(true).cookie(recoveryCookie))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.purpose")
                                .value("PASSWORD_RESET"));
        assertThatThrownBy(
                        () ->
                                recovery.resetPassword(
                                        recoveryCookie.getValue(),
                                        "Password-Already-Leaked-1234",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        String newPassword = "Distinct-Replacement-Password-8765";
        mvc.perform(
                        post("/admin/api/auth/recovery/password")
                                .secure(true)
                                .with(
                                        request -> {
                                            request.setScheme("https");
                                            request.setServerPort(443);
                                            return request;
                                        })
                                .header("Origin", "https://localhost")
                                .header("X-CSRF-TOKEN", csrfToken)
                                .cookie(csrfCookie, recoveryCookie)
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                java.util.Map.of("password", newPassword))))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.result")
                                .value("RESET"));
        assertThatThrownBy(() -> recovery.stage(recoveryCookie.getValue()))
                .isInstanceOf(AuthException.class);
        var recovering =
                recovery.startMfa(
                        "admin01",
                        newPassword,
                        completed.recoveryCodes().getFirst(),
                        "test-source",
                        UUID.randomUUID());
        assertThat(recovering.stage()).isEqualTo("MFA_SETUP_REQUIRED");
        String recoveryGrant = recovering.cookie().value();
        assertThat(recovery.stage(recoveryGrant).purpose()).isEqualTo("MFA_RECOVERY");
        assertThatThrownBy(
                        () ->
                                login.login(
                                        "admin01",
                                        newPassword,
                                        null,
                                        null,
                                        "test-source",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        var replacement = recovery.setupMfa(recoveryGrant, UUID.randomUUID());
        assertThat(
                        java.net.URLDecoder.decode(
                                replacement.provisioningUri(),
                                java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("otpauth://totp/시차:")
                .endsWith("&issuer=시차");
        long replacementStep = Instant.now().getEpochSecond() / 30 - 1;
        recovery.verifyMfa(
                recoveryGrant,
                totp.code(replacement.secret(), Instant.ofEpochSecond(replacementStep * 30)),
                "test-source",
                UUID.randomUUID());
        var replaced = recovery.completeMfa(recoveryGrant, UUID.randomUUID());
        assertThat(replaced.recoveryCodes())
                .hasSize(10)
                .doesNotContain(completed.recoveryCodes().getFirst());
        assertThatThrownBy(() -> recovery.stage(recoveryGrant)).isInstanceOf(AuthException.class);
        var renewed =
                login.login("admin01", newPassword, null, null, "test-source", UUID.randomUUID());
        var newSession =
                login.authenticate(
                        renewed.cookie().value(),
                        totp.code(replacement.secret(), Instant.now()),
                        "test-source",
                        UUID.randomUUID());
        String renewedSid = newSession.cookie().value();
        var renewedPrincipal = loginPrincipal(renewedSid);
        assertThat(login.isActive(renewedSid, renewedPrincipal)).isTrue();
        var secondLogin =
                login.login("admin01", newPassword, null, null, "second-source", UUID.randomUUID());
        long usedStep = jdbc.queryForObject("select last_step from admin_credential", Long.class);
        Instant freshTime;
        do {
            freshTime =
                    jdbc.queryForObject(
                            "select clock_timestamp()",
                            (rs, row) -> rs.getTimestamp(1).toInstant());
            if (freshTime.getEpochSecond() / 30 <= usedStep) Thread.sleep(250);
        } while (freshTime.getEpochSecond() / 30 <= usedStep);
        var secondDevice =
                login.authenticate(
                        secondLogin.cookie().value(),
                        totp.code(replacement.secret(), freshTime),
                        "second-source",
                        UUID.randomUUID());
        String secondSid = secondDevice.cookie().value();
        var secondPrincipal = loginPrincipal(secondSid);
        assertThat(login.isActive(secondSid, secondPrincipal)).isTrue();
        assertThat(login.isActive(renewedSid, renewedPrincipal)).isTrue();
        login.logoutAll(renewedSid, renewedPrincipal, UUID.randomUUID());
        assertThat(login.isActive(renewedSid, renewedPrincipal)).isFalse();
        assertThat(login.isActive(secondSid, secondPrincipal)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from admin_auth_audit where"
                                    + " action='ACCOUNT_SESSIONS_REVOKED'",
                                Integer.class))
                .isEqualTo(1);
        var abort =
                recovery.startMfa(
                        "admin01",
                        newPassword,
                        replaced.recoveryCodes().getFirst(),
                        "test-source",
                        UUID.randomUUID());
        jdbc.execute(
                "create function reject_cancel_audit() returns trigger language plpgsql as $$ begin"
                    + " if NEW.action='RECOVERY_ABORTED' then raise exception 'audit unavailable';"
                    + " end if; return NEW; end $$");
        jdbc.execute(
                "create trigger reject_cancel before insert on admin_auth_audit for each row"
                    + " execute function reject_cancel_audit()");
        assertThatThrownBy(() -> recovery.cancel(abort.cookie().value(), UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("AUTH_RESULT_UNCONFIRMED");
        assertThatThrownBy(() -> recovery.stage(abort.cookie().value()))
                .isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("select mfa_state from admin_credential", String.class))
                .isEqualTo("RECOVERY");
        jdbc.execute("drop trigger reject_cancel on admin_auth_audit");
        jdbc.execute("drop function reject_cancel_audit()");
        for (int attemptNumber = 0; attemptNumber < 5; attemptNumber++) {
            assertThatThrownBy(
                            () ->
                                    login.login(
                                            "admin01",
                                            "Incorrect-Password-12345",
                                            null,
                                            null,
                                            "limited-source",
                                            UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from admin_auth_limit where action='PASSWORD' and"
                                    + " bucket_kind='ACCOUNT' and fail_count=5 and blocked_until is"
                                    + " not null",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select max(fail_count) from admin_auth_limit where"
                                    + " action='PASSWORD' and bucket_kind='SOURCE'",
                                Integer.class))
                .isGreaterThanOrEqualTo(4);
        assertThatThrownBy(
                        () ->
                                login.login(
                                        "admin01",
                                        newPassword,
                                        null,
                                        null,
                                        "limited-source",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("RATE_LIMITED");
    }

    private com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal loginPrincipal(
            String id) {
        return sessionAdapter.findStoredPrincipal(id).orElseThrow();
    }

    @Autowired com.reasoning.admin.auth.session.AdminSessionAdapter sessionAdapter;
}
