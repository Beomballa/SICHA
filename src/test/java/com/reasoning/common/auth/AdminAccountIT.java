package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.common.auth.service.AuthModels.SessionPrincipal;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.LoginSessionService;
import com.reasoning.common.auth.service.TotpService;
import jakarta.servlet.http.Cookie;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminAccountIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 51));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 52));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 53));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    @Autowired EnrollmentService enrollment;
    @Autowired LoginSessionService login;
    @Autowired TotpService totp;
    @Autowired AdminSessionAdapter sessions;
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    /** Exercises live HTTP authorization, revisions, mandatory audit and shrinking fallback on a disposable DB. */
    @Test
    void managementLifecycleAndAuditFailure() throws Exception {
        String password = "Administrator-Independent-Password-7042";
        var bootstrap = enrollment.createBootstrap("manager01", "verified_12345", "operator_01", UUID.randomUUID());
        var first = enrollment.exchange(bootstrap.code(), null, "manager-test", UUID.randomUUID());
        enrollment.setPassword(first.cookie().value(), password, UUID.randomUUID());
        var setup = enrollment.prepareMfa(first.cookie().value(), UUID.randomUUID());
        Instant previous = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 30 - 1) * 30);
        enrollment.verifyMfa(first.cookie().value(), totp.code(setup.secret(), previous), "manager-test", UUID.randomUUID());
        enrollment.complete(first.cookie().value(), UUID.randomUUID());
        var loginStart = login.login("manager01", password, null, null, "manager-test", UUID.randomUUID());
        var active = login.authenticate(loginStart.cookie().value(), totp.code(setup.secret(), Instant.now()),
                "manager-test", UUID.randomUUID());
        String sid = active.cookie().value();
        var actor = sessions.findStoredPrincipal(sid).orElseThrow();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        sessions.issueCookie(sid, new org.springframework.mock.web.MockHttpServletRequest(), response);
        Cookie sessionCookie = response.getCookie("__Host-admin-session");
        var csrf = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrf.getResponse().getCookie("__Host-admin-csrf");
        String csrfToken = json.readTree(csrf.getResponse().getContentAsString()).path("token").asText();

        mvc.perform(get("/admin/accounts").secure(true)).andExpect(status().isSeeOther());
        mvc.perform(get("/admin/api/accounts").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/accounts").secure(true).cookie(sessionCookie)).andExpect(status().isOk());
        JsonNode initial = body(mvc.perform(get("/admin/api/accounts").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn());
        assertThat(initial.path("items").size()).isEqualTo(1);
        assertThat(initial.path("items").get(0).path("permissions").toString()).contains("MANAGE");
        assertThat(initial.path("items").get(0).has("loginId")).isFalse();

        SessionPrincipal invitationActor = new SessionPrincipal(actor.accountId(), actor.accountKey(), actor.sessionKey(),
                active.absoluteExpiresAt(), Instant.now().plusSeconds(1800), Instant.now().plusSeconds(300),
                true, false, false, false);
        var invite = enrollment.issueInvitation(invitationActor, sid, UUID.randomUUID(), "staff01",
                "verified_23456", UUID.randomUUID());
        var limited = enrollment.exchange(invite.code(), null, "staff-test", UUID.randomUUID());
        enrollment.setPassword(limited.cookie().value(), "Staff-Independent-Password-8173", UUID.randomUUID());
        var staffSetup = enrollment.prepareMfa(limited.cookie().value(), UUID.randomUUID());
        enrollment.verifyMfa(limited.cookie().value(), totp.code(staffSetup.secret(), previous),
                "staff-test", UUID.randomUUID());
        enrollment.complete(limited.cookie().value(), UUID.randomUUID());
        UUID target = db.queryForObject("SELECT account_key FROM admin_account WHERE id<>?",
                UUID.class, actor.accountId());
        String path = "/admin/api/accounts/" + target;
        var staffLogin = login.login("staff01", "Staff-Independent-Password-8173", null, null,
                "staff-test", UUID.randomUUID());
        var staffSession = login.authenticate(staffLogin.cookie().value(), totp.code(staffSetup.secret(), Instant.now()),
                "staff-test", UUID.randomUUID());
        var staffResponse = new org.springframework.mock.web.MockHttpServletResponse();
        sessions.issueCookie(staffSession.cookie().value(), new org.springframework.mock.web.MockHttpServletRequest(),
                staffResponse);
        mvc.perform(get("/admin/api/accounts").secure(true)
                .cookie(staffResponse.getCookie("__Host-admin-session"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/accounts").secure(true)
                .cookie(staffResponse.getCookie("__Host-admin-session"))).andExpect(status().isForbidden());
        JsonNode detail = body(mvc.perform(get(path).secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.path("editRev").asText()).isEqualTo("1");
        assertThat(detail.path("enrolled").asBoolean()).isTrue();
        assertThat(detail.path("permissions").size()).isZero();
        mvc.perform(get("/admin/api/accounts/" + UUID.randomUUID()).secure(true).cookie(sessionCookie))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/api/accounts").secure(true).cookie(sessionCookie)
                .param("accountKey", target.toString()).param("permission", "MANAGE"))
                .andExpect(status().isOk()).andDo(result ->
                        assertThat(json.readTree(result.getResponse().getContentAsString()).path("items").size()).isZero());

        String managerPath = "/admin/api/accounts/" + actor.accountKey();
        mvc.perform(change(managerPath + "/permissions/revoke", "{\"expectedRev\":\"1\",\"permissions\":[\"MANAGE\"],\"reasonCode\":\"ACCESS_REVIEW\",\"verificationRef\":\"verified_12345\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isConflict())
                .andDo(result -> assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText())
                        .isEqualTo("LAST_MANAGER_REQUIRED"));
        mvc.perform(change(path + "/permissions/grant", "{\"expectedRev\":1,\"permissions\":[\"CREATE\"],\"reasonCode\":\"ASSIGNMENT_CHANGE\",\"verificationRef\":\"verified_23456\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isBadRequest());
        JsonNode granted = body(mvc.perform(change(path + "/permissions/grant", "{\"expectedRev\":\"1\",\"permissions\":[\"CREATE\"],\"reasonCode\":\"ASSIGNMENT_CHANGE\",\"verificationRef\":\"verified_23456\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isOk()).andReturn());
        assertThat(granted.path("changed").asBoolean()).isTrue();
        assertThat(granted.path("editRev").asText()).isEqualTo("2");
        assertThat(granted.path("nextAction").asText()).isEqualTo("REFRESH");
        mvc.perform(change(path + "/permissions/grant", "{\"expectedRev\":\"1\",\"permissions\":[\"CREATE\"],\"reasonCode\":\"ASSIGNMENT_CHANGE\",\"verificationRef\":\"verified_23456\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isConflict());
        JsonNode noChange = body(mvc.perform(change(path + "/permissions/grant", "{\"expectedRev\":\"2\",\"permissions\":[\"CREATE\"],\"reasonCode\":\"ASSIGNMENT_CHANGE\",\"verificationRef\":\"verified_23456\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isOk()).andReturn());
        assertThat(noChange.path("changed").asBoolean()).isFalse();
        assertThat(noChange.path("editRev").asText()).isEqualTo("2");
        assertThat(noChange.path("auditStatus").asText()).isEqualTo("NOT_REQUIRED");

        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(821,1)");
                mvc.perform(change(path + "/permissions/grant", "{\"expectedRev\":\"2\",\"permissions\":[\"REVIEW\"],\"reasonCode\":\"ACCESS_REVIEW\",\"verificationRef\":\"verified_23456\"}",
                        sessionCookie, csrfCookie, csrfToken)).andExpect(status().isServiceUnavailable())
                        .andDo(result -> assertThat(json.readTree(result.getResponse().getContentAsString())
                                .path("code").asText()).isEqualTo("ADMIN_CHANGE_BUSY"));
            } finally {
                blocker.rollback();
            }
        }
        assertThat(db.queryForObject("SELECT edit_rev FROM admin_account WHERE account_key=?", Long.class, target)).isEqualTo(2);

        db.execute("CREATE FUNCTION fail_grant_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='ACCOUNT_PERMISSION_GRANTED' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_grant BEFORE INSERT ON admin_auth_audit FOR EACH ROW EXECUTE FUNCTION fail_grant_audit()");
        mvc.perform(change(path + "/permissions/grant", "{\"expectedRev\":\"2\",\"permissions\":[\"REVIEW\"],\"reasonCode\":\"ACCESS_REVIEW\",\"verificationRef\":\"verified_23456\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT edit_rev FROM admin_account WHERE account_key=?", Long.class, target)).isEqualTo(2);
        db.execute("DROP TRIGGER fail_grant ON admin_auth_audit");
        db.execute("DROP FUNCTION fail_grant_audit()");

        db.execute("CREATE FUNCTION fail_revoke_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='ACCOUNT_PERMISSION_REVOKED' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_revoke BEFORE INSERT ON admin_auth_audit FOR EACH ROW EXECUTE FUNCTION fail_revoke_audit()");
        JsonNode revoked = body(mvc.perform(change(path + "/permissions/revoke", "{\"expectedRev\":\"2\",\"permissions\":[\"CREATE\"],\"reasonCode\":\"ACCESS_REVIEW\",\"verificationRef\":\"verified_23456\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isOk()).andReturn());
        assertThat(revoked.path("auditStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(revoked.path("editRev").asText()).isEqualTo("3");
        db.execute("DROP TRIGGER fail_revoke ON admin_auth_audit");
        db.execute("DROP FUNCTION fail_revoke_audit()");

        JsonNode successor = body(mvc.perform(change(path + "/permissions/grant", "{\"expectedRev\":\"3\",\"permissions\":[\"MANAGE\"],\"reasonCode\":\"ASSIGNMENT_CHANGE\",\"verificationRef\":\"verified_23456\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isOk()).andReturn());
        assertThat(successor.path("editRev").asText()).isEqualTo("4");
        var pending = enrollment.issueInvitation(invitationActor, sid, UUID.randomUUID(), "pending01",
                "verified_34567", UUID.randomUUID());
        UUID pendingKey = db.queryForObject("SELECT a.account_key FROM admin_account a JOIN admin_enrollment e "
                + "ON e.account_id=a.id WHERE e.registration_key=?", UUID.class, pending.registrationKey());
        JsonNode disabled = body(mvc.perform(change("/admin/api/accounts/" + pendingKey + "/deactivate",
                "{\"expectedRev\":\"1\",\"reasonCode\":\"OFFBOARDING\",\"verificationRef\":\"verified_34567\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isOk()).andReturn());
        assertThat(disabled.path("credentialAction").asText()).isEqualTo("REISSUE_ENROLLMENT");
        assertThat(db.queryForObject("SELECT code_hash IS NULL AND grant_hash IS NULL AND revoked_at IS NOT NULL "
                + "FROM admin_enrollment WHERE registration_key=?", Boolean.class, pending.registrationKey())).isTrue();
        Long pendingId = db.queryForObject("SELECT id FROM admin_account WHERE account_key=?", Long.class, pendingKey);
        Long lastStory = null;
        for (int i = 0; i < 21; i++) {
            lastStory = db.queryForObject("INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id", Long.class,
                    String.format("H0_CASE_%02d", i), pendingId);
        }
        db.update("INSERT INTO story_access(story_id,admin_id,permission,active_yn,granted_by) "
                + "VALUES (?,?,'EDIT',false,?)", lastStory, pendingId, actor.accountId());
        db.update("INSERT INTO story_access(story_id,admin_id,permission,active_yn,granted_by) "
                + "VALUES (?,?,'REVIEW',true,?)", lastStory, pendingId, actor.accountId());
        String pendingPath = "/admin/api/accounts/" + pendingKey;
        JsonNode preview = body(mvc.perform(get(pendingPath + "/reactivation-preview")
                .secure(true).cookie(sessionCookie)).andExpect(status().isOk()).andReturn());
        assertThat(preview.path("account").path("editRev").asText()).isEqualTo("2");
        assertThat(preview.path("ownedCount").asLong()).isEqualTo(21);
        assertThat(preview.path("accessCount").asLong()).isEqualTo(1);
        assertThat(preview.path("relationSample").size()).isEqualTo(20);
        assertThat(preview.path("truncated").asBoolean()).isTrue();
        assertThat(preview.path("relationSample").toString()).doesNotContain("H0_CASE_20");
        assertThat(preview.toString()).doesNotContain("title", "answer", "loginCipher");
        String confirmedHash = preview.path("impactHash").asText();
        assertThat(confirmedHash).matches("[0-9a-f]{64}");
        List<List<Object>> relationRecords = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String code = String.format("H0_CASE_%02d", i);
            if (i == 20) relationRecords.add(List.of(code, "EDIT", false));
            relationRecords.add(List.of(code, "OWNER", true));
            if (i == 20) relationRecords.add(List.of(code, "REVIEW", true));
        }
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                json.writeValueAsBytes(List.of(1, pendingKey.toString(), "2", "2", false, "PENDING",
                        false, List.of(), relationRecords))));
        assertThat(confirmedHash).isEqualTo(expectedHash);
        String activate = "{\"expectedRev\":\"2\",\"impactHash\":\"" + confirmedHash
                + "\",\"reasonCode\":\"RETURN_TO_WORK\",\"verificationRef\":\"verified_34567\"}";
        db.update("UPDATE story_access SET active_yn=true WHERE story_id=? AND admin_id=? AND permission='EDIT'",
                lastStory, pendingId);
        JsonNode changedImpact = body(mvc.perform(change(pendingPath + "/reactivate", activate,
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isConflict()).andReturn());
        assertThat(changedImpact.path("code").asText()).isEqualTo("IMPACT_CHANGED");
        assertThat(db.queryForObject("SELECT edit_rev FROM admin_account WHERE id=?", Long.class, pendingId)).isEqualTo(2);
        db.update("UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=? AND permission='EDIT'",
                lastStory, pendingId);
        JsonNode refreshed = body(mvc.perform(get(pendingPath + "/reactivation-preview")
                .secure(true).cookie(sessionCookie)).andExpect(status().isOk()).andReturn());
        assertThat(refreshed.path("impactHash").asText()).isEqualTo(confirmedHash);
        JsonNode wrongRevision = body(mvc.perform(change(pendingPath + "/reactivate",
                activate.replace("\"expectedRev\":\"2\"", "\"expectedRev\":\"1\""),
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isConflict()).andReturn());
        assertThat(wrongRevision.path("code").asText()).isEqualTo("STATE_CONFLICT");
        db.execute("CREATE FUNCTION fail_activate_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='ACCOUNT_REACTIVATED' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_activate BEFORE INSERT ON admin_auth_audit FOR EACH ROW EXECUTE FUNCTION fail_activate_audit()");
        mvc.perform(change(pendingPath + "/reactivate", activate, sessionCookie, csrfCookie, csrfToken))
                .andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT active_yn FROM admin_account WHERE id=?", Boolean.class, pendingId)).isFalse();
        assertThat(db.queryForObject("SELECT edit_rev FROM admin_account WHERE id=?", Long.class, pendingId)).isEqualTo(2);
        db.execute("DROP TRIGGER fail_activate ON admin_auth_audit");
        db.execute("DROP FUNCTION fail_activate_audit()");
        JsonNode reactivated = body(mvc.perform(change(pendingPath + "/reactivate", activate,
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isOk()).andReturn());
        assertThat(reactivated.path("changed").asBoolean()).isTrue();
        assertThat(reactivated.path("credentialAction").asText()).isEqualTo("REISSUE_ENROLLMENT");
        assertThat(reactivated.path("editRev").asText()).isEqualTo("3");
        assertThat(db.queryForObject("SELECT auth_rev FROM admin_credential WHERE account_id=?", Long.class,
                pendingId)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT code_hash IS NULL AND grant_hash IS NULL FROM admin_enrollment "
                + "WHERE account_id=?", Boolean.class, pendingId)).isTrue();
        assertThat(db.queryForObject("SELECT change_data->>'impactHash' FROM admin_auth_audit "
                + "WHERE target_id=? AND action='ACCOUNT_REACTIVATED'", String.class, pendingId)).isEqualTo(confirmedHash);
        mvc.perform(get(pendingPath + "/reactivation-preview").secure(true).cookie(sessionCookie))
                .andExpect(status().isConflict());
        var selfResponse = mvc.perform(change(managerPath + "/permissions/revoke", "{\"expectedRev\":\"1\",\"permissions\":[\"MANAGE\"],\"reasonCode\":\"ACCESS_REVIEW\",\"verificationRef\":\"verified_12345\"}",
                sessionCookie, csrfCookie, csrfToken)).andExpect(status().isOk()).andReturn();
        JsonNode self = body(selfResponse);
        assertThat(self.path("nextAction").asText()).isEqualTo("LOGIN");
        assertThat(selfResponse.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header).contains("__Host-admin-session=").contains("Max-Age=0"));
        assertThat(selfResponse.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header).contains("__Host-admin-recovery=").contains("Max-Age=0"));
        mvc.perform(get("/admin/api/accounts").secure(true).cookie(sessionCookie)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT can_manage FROM admin_account WHERE account_key=?", Boolean.class, actor.accountKey()))
                .isFalse();
    }

    private static MockHttpServletRequestBuilder change(String path, String payload, Cookie session, Cookie csrf, String token) {
        return post(path).secure(true).with(request -> { request.setScheme("https"); request.setServerPort(443); return request; })
                .header("Origin", "https://localhost").header("X-CSRF-TOKEN", token)
                .cookie(session, csrf).contentType("application/json").content(payload);
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
