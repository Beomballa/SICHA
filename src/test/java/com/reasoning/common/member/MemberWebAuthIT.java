package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 기존 폐기 PostgreSQL·SMTP fixture로 브라우저 경계와 네이티브 회귀를 함께 검사한다. */
@SpringBootTest(
        properties = {
            "app.member-web.enabled=true",
            "app.member-web.origin=https://player.example.invalid"
        })
public class MemberWebAuthIT extends LocalMemberAuthIT {
    private static final String BASE = "/api/member/browser-auth";
    private static final String ORIGIN = "https://player.example.invalid";
    private static final String COOKIE = "__Secure-sicha_player_refresh";
    private static final String PASSWORD = "Synthetic-member-password-987!";

    /** 실제 connector authority를 표현하며 forwarded 헤더로 대체하지 않는다. */
    private MockHttpServletRequestBuilder web(String suffix, UUID epoch, String body) {
        return post(BASE + suffix)
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerName("player.example.invalid");
                            request.setServerPort(443);
                            return request;
                        })
                .header("Origin", ORIGIN)
                .header("X-Sicha-Player-Web", "1")
                .header("X-Sicha-Player-Web-Epoch", epoch.toString())
                .header("Sec-Fetch-Site", "same-origin")
                .contentType("application/json")
                .content(body);
    }

    private MockHttpServletRequestBuilder me(String access) {
        return get("/api/member/auth/me")
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerName("player.example.invalid");
                            request.setServerPort(443);
                            return request;
                        })
                .header("X-Sicha-Player-Web", "1")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Authorization", "Bearer " + access);
    }

    private MvcResult login(String email, UUID epoch) throws Exception {
        return mvc.perform(
                        web(
                                "/login/local",
                                epoch,
                                JSON.writeValueAsString(
                                        Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String cookie(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(COOKIE + "=") && !value.contains("Max-Age=0;"))
                .reduce((first, second) -> second)
                .orElseThrow()
                .split(";", 2)[0];
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    /** 일곱 access 필드·좁은 쿠키·복원·epoch 장벽을 실제 dispatcher로 확인한다. */
    @Test
    void accessOnlyCookieRestoreAndEpochBarrier() throws Exception {
        String email = "web-" + UUID.randomUUID() + "@example.invalid";
        account(email);
        UUID epoch = UUID.randomUUID();
        Instant beforeLogin = Instant.now();
        var logged = login(email, epoch);
        assertThat(body(logged).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "tokenType",
                        "accessToken",
                        "accessExpiresAt",
                        "sessionKey",
                        "sessionAbsoluteExpiresAt",
                        "webEpoch",
                        "requestId");
        assertThat(body(logged).path("webEpoch").asText()).isEqualTo(epoch.toString());
        String setCookie = logged.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .contains("Path=" + BASE, "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain=");
        long maxAge =
                Long.parseLong(
                        java.util.regex.Pattern.compile("Max-Age=([0-9]+)")
                                .matcher(setCookie)
                                .results()
                                .findFirst()
                                .orElseThrow()
                                .group(1));
        Instant refreshDeadline =
                db.queryForObject(
                                "SELECT min(t.expires_at) FROM member_token t JOIN member_session s"
                                        + " ON s.id=t.session_id WHERE s.session_key=? AND"
                                        + " t.kind='REFRESH'",
                                java.sql.Timestamp.class,
                                UUID.fromString(body(logged).path("sessionKey").asText()))
                        .toInstant();
        assertThat(maxAge).isPositive().isLessThanOrEqualTo(2592000L);
        assertThat(maxAge)
                .isLessThanOrEqualTo(
                        java.time.Duration.between(beforeLogin, refreshDeadline).getSeconds());
        assertThat(Instant.parse(body(logged).path("sessionAbsoluteExpiresAt").asText()))
                .isAfterOrEqualTo(refreshDeadline);
        assertThat(cookie(logged)).matches(COOKIE + "=" + epoch + "\\.[A-Za-z0-9_-]{43}");
        mvc.perform(me(body(logged).path("accessToken").asText())).andExpect(status().isOk());
        mvc.perform(me(body(logged).path("accessToken").asText()).header("Cookie", cookie(logged)))
                .andExpect(status().isForbidden());
        var stale =
                mvc.perform(
                                web("/refresh", UUID.randomUUID(), "{}")
                                        .header("Cookie", cookie(logged)))
                        .andExpect(status().isConflict())
                        .andReturn();
        assertThat(body(stale).path("code").asText()).isEqualTo("WEB_SESSION_CHANGED");
        assertThat(stale.getResponse().getHeaders("Set-Cookie")).isEmpty();
        var restored =
                mvc.perform(web("/refresh", epoch, "{}").header("Cookie", cookie(logged)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(restored).path("sessionKey")).isEqualTo(body(logged).path("sessionKey"));
        assertThat(cookie(restored)).isNotEqualTo(cookie(logged));
        assertThat(Instant.parse(body(restored).path("sessionAbsoluteExpiresAt").asText()))
                .isEqualTo(Instant.parse(body(logged).path("sessionAbsoluteExpiresAt").asText()));
    }

    /** 쿠키 없는 복원·회수는 비행 없이 명시적인 무세션 응답을 보낸다. */
    @Test
    void noSessionAndStrictBodies() throws Exception {
        UUID epoch = UUID.randomUUID();
        var result =
                mvc.perform(web("/refresh", epoch, "{}"))
                        .andExpect(status().isNoContent())
                        .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        var logout =
                mvc.perform(
                                web(
                                        "/logout",
                                        epoch,
                                        JSON.writeValueAsString(
                                                Map.of("requestKey", UUID.randomUUID()))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(logout).path("state").asText()).isEqualTo("NO_SESSION");
        assertThat(logout.getResponse().getHeader("Set-Cookie"))
                .contains("Max-Age=0", "Path=" + BASE);
        for (String input : List.of("{\"refreshToken\":\"x\"}", "{} {}", "[]", "{\"a\":1,\"a\":2}"))
            mvc.perform(web("/refresh", epoch, input)).andExpect(status().isBadRequest());
        mvc.perform(
                        web(
                                "/login/local",
                                epoch,
                                "{\"email\":\"x\",\"email\":\"y\",\"password\":\"z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(web("/refresh", epoch, " ".repeat(16385)))
                .andExpect(status().isPayloadTooLarge());
    }

    /** 출처·TLS·헤더 중복·미승인 경로·관리자 CSRF는 fail closed다. */
    @Test
    void originAndTransportIsolation() throws Exception {
        UUID epoch = UUID.randomUUID();
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .with(
                                        request -> {
                                            request.setScheme("http");
                                            request.setSecure(false);
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .header("Origin", "https://foreign.example.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .with(
                                        request -> {
                                            request.removeHeader("Origin");
                                            request.addHeader(
                                                    "Origin", "https://foreign.example.invalid");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .with(
                                        request -> {
                                            request.removeHeader("Origin");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(web("/refresh", epoch, "{}").header("X-Sicha-Player-Web", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .header("X-Sicha-Player-Web-Epoch", epoch.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .header("Authorization", "Bearer " + "a".repeat(43)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .with(
                                        request -> {
                                            request.setServerName("foreign.example.invalid");
                                            return request;
                                        })
                                .header("X-Forwarded-Host", "player.example.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .with(
                                        request -> {
                                            request.removeHeader("Sec-Fetch-Site");
                                            request.addHeader("Sec-Fetch-Site", "cross-site");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(web("/refresh", epoch, "{}").queryParam("token", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/member/auth/login/local")
                                .secure(true)
                                .header("Origin", ORIGIN)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/playtests/identity").secure(true).header("X-Sicha-Player-Web", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/playtests/identity")
                                .secure(true)
                                .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/api/auth/login")
                                .secure(true)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(web("/unknown", epoch, "{}")).andExpect(status().isForbidden());
    }

    /** raw Cookie 줄과 합쳐진 쿠키의 중복은 회전 없이 거절한다. */
    @Test
    void duplicateCookiesAndMalformedEpoch() throws Exception {
        UUID epoch = UUID.randomUUID();
        String proof =
                COOKIE
                        + "="
                        + epoch
                        + "."
                        + java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(new byte[32]);
        mvc.perform(web("/refresh", epoch, "{}").header("Cookie", proof, proof))
                .andExpect(status().isBadRequest());
        mvc.perform(web("/refresh", epoch, "{}").header("Cookie", proof + "; " + proof))
                .andExpect(status().isBadRequest());
        mvc.perform(web("/refresh", epoch, "{}").header("Cookie", COOKIE + "=bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        web("/refresh", epoch, "{}")
                                .with(
                                        request -> {
                                            request.removeHeader("X-Sicha-Player-Web-Epoch");
                                            request.addHeader(
                                                    "X-Sicha-Player-Web-Epoch",
                                                    "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA");
                                            return request;
                                        }))
                .andExpect(status().isBadRequest());
    }

    /** USED 증명과 이전 epoch는 회수에만 허용되고 재회수는 성공을 주장하지 않는다. */
    @Test
    void usedProofLogoutAndReplacementRevokeFamilies() throws Exception {
        String email = "used-web-" + UUID.randomUUID() + "@example.invalid";
        account(email);
        UUID epoch = UUID.randomUUID();
        var logged = login(email, epoch);
        var rotated =
                mvc.perform(web("/refresh", epoch, "{}").header("Cookie", cookie(logged)))
                        .andExpect(status().isOk())
                        .andReturn();
        configuration.setCollectionEnabled(false);
        var revoked =
                mvc.perform(
                                web(
                                                "/logout",
                                                UUID.randomUUID(),
                                                JSON.writeValueAsString(
                                                        Map.of("requestKey", UUID.randomUUID())))
                                        .header("Cookie", cookie(logged)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(revoked).path("state").asText()).isEqualTo("LOGGED_OUT");
        configuration.setCollectionEnabled(true);
        mvc.perform(
                        get("/api/member/auth/me")
                                .secure(true)
                                .header(
                                        "Authorization",
                                        "Bearer " + body(rotated).path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        web(
                                        "/logout",
                                        epoch,
                                        JSON.writeValueAsString(
                                                Map.of("requestKey", UUID.randomUUID())))
                                .header("Cookie", cookie(logged)))
                .andExpect(status().isUnauthorized());
        var first = login(email, UUID.randomUUID());
        var replacement =
                mvc.perform(
                                web(
                                                "/login/local",
                                                UUID.randomUUID(),
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                email,
                                                                "password",
                                                                PASSWORD)))
                                        .header("Cookie", cookie(first)))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(replacement).path("sessionKey"))
                .isNotEqualTo(body(first).path("sessionKey"));
        mvc.perform(
                        get("/api/member/auth/me")
                                .secure(true)
                                .header(
                                        "Authorization",
                                        "Bearer " + body(first).path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    /** 필수 발급 감사 실패는 회전을 롤백하지만 보안 회수는 독립 감사 실패에도 완료된다. */
    @Test
    void auditFailureDoesNotFakeIssuanceOrBlockRevocation() throws Exception {
        String email = "audit-web-" + UUID.randomUUID() + "@example.invalid";
        account(email);
        UUID epoch = UUID.randomUUID();
        var logged = login(email, epoch);
        db.execute(
                "CREATE FUNCTION synthetic_web_audit_failure() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END; $$");
        db.execute(
                "CREATE TRIGGER synthetic_web_audit_failure BEFORE INSERT ON member_auth_audit FOR"
                        + " EACH ROW EXECUTE FUNCTION synthetic_web_audit_failure()");
        try {
            var failed =
                    mvc.perform(web("/refresh", epoch, "{}").header("Cookie", cookie(logged)))
                            .andExpect(status().isServiceUnavailable())
                            .andReturn();
            assertThat(failed.getResponse().getHeaders("Set-Cookie")).isEmpty();
            mvc.perform(
                            web(
                                            "/logout",
                                            epoch,
                                            JSON.writeValueAsString(
                                                    Map.of("requestKey", UUID.randomUUID())))
                                    .header("Cookie", cookie(logged)))
                    .andExpect(status().isOk());
        } finally {
            db.execute("DROP TRIGGER synthetic_web_audit_failure ON member_auth_audit");
            db.execute("DROP FUNCTION synthetic_web_audit_failure()");
        }
        mvc.perform(me(body(logged).path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    /** 비활성 기본 정책은 요청을 닫고 활성 정책의 비정규 출처는 설치를 거절한다. */
    @Test
    void disabledAndInvalidOriginFailClosed() throws Exception {
        var disabled = new com.reasoning.web.member.auth.MemberWebPolicy(false, "");
        var admission =
                com.reasoning.web.member.auth.MemberWebPolicy.class.getDeclaredMethod(
                        "admit", jakarta.servlet.http.HttpServletRequest.class, boolean.class);
        admission.setAccessible(true);
        var request =
                web("/refresh", UUID.randomUUID(), "{}")
                        .buildRequest(new org.springframework.mock.web.MockServletContext());
        assertThatThrownBy(() -> admission.invoke(disabled, request, true))
                .hasCauseInstanceOf(com.reasoning.common.auth.service.AuthException.class);
        for (String origin :
                List.of(
                        "",
                        "http://player.example.invalid",
                        ORIGIN + "/",
                        ORIGIN + ":443",
                        "https://user@player.example.invalid",
                        ORIGIN + "?query=x",
                        "https://PLAYER.example.invalid"))
            assertThatThrownBy(
                            () -> new com.reasoning.web.member.auth.MemberWebPolicy(true, origin))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
