package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.util.GreenMail;

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

    /** 소개 화면의 정확한 HTTPS 경로만 요청하며 계정용 웹 헤더를 붙이지 않는다. */
    private MockHttpServletRequestBuilder publicPage(MockHttpServletRequestBuilder request) {
        return request.secure(true)
                .with(
                        servlet -> {
                            servlet.setScheme("https");
                            servlet.setServerName("player.example.invalid");
                            servlet.setServerPort(443);
                            return servlet;
                        });
    }

    /** Flutter 자산 없이 계정 문서로 연결되는 소개와 별도 보안 헤더를 확인한다. */
    @Test
    void publicIntroductionWithoutFlutterAssets() throws Exception {
        var result =
                mvc.perform(publicPage(get("/")).header("Cookie", "JSESSIONID=ignored"))
                        .andExpect(status().isOk())
                        .andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("시차 · 서비스 소개", "혼자 살펴보기", "둘이 협력하기")
                .contains("href=\"/player-public.css\"")
                .contains(
                        "href=\"/account#login\"",
                        "href=\"/account#signup\"",
                        "href=\"/account#recover\"")
                .doesNotContain(
                        "href=\"/player/#/login\"",
                        "href=\"/player/#/signup\"",
                        "href=\"/player/#/recover\"");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .isEqualTo(
                        "default-src 'self'; script-src 'none'; style-src 'self';"
                                + " frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        mvc.perform(publicPage(head("/"))).andExpect(status().isOk());
    }

    /** 쿼리·변경 메서드·HTTP는 헤더가 붙은 403이고 CSS 이외의 자산은 공개하지 않는다. */
    @Test
    void publicIntroductionAdmissionIsNarrow() throws Exception {
        for (var request :
                List.of(
                        publicPage(get("/?preview=1")),
                        publicPage(post("/")),
                        publicPage(get("/privacy?token=forbidden")),
                        publicPage(post("/privacy")),
                        get("/privacy"),
                        publicPage(get("/player-public.css?version=1")),
                        publicPage(post("/player-public.css")),
                        get("/"),
                        get("/player-public.css"))) {
            var rejected = mvc.perform(request).andExpect(status().isForbidden()).andReturn();
            assertThat(rejected.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(rejected.getResponse().getHeader("Referrer-Policy"))
                    .isEqualTo("no-referrer");
            assertThat(rejected.getResponse().getHeader("Content-Security-Policy"))
                    .contains("script-src 'none'", "style-src 'self'");
            assertThat(rejected.getResponse().getHeaders("Set-Cookie")).isEmpty();
        }
        var css =
                mvc.perform(publicPage(get("/player-public.css")))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(css.getResponse().getContentType()).contains("text/css");
        assertThat(css.getResponse().getContentAsString()).contains("--accent: #ffc700");
        assertThat(css.getResponse().getHeaders("Set-Cookie")).isEmpty();
        mvc.perform(publicPage(head("/player-public.css"))).andExpect(status().isOk());
        mvc.perform(publicPage(get("/player-public.js"))).andExpect(status().is4xxClientError());
    }

    /** 공통 모듈만 HTTPS 읽기로 제공하고 변경·쿼리·임의 자산은 차단한다. */
    @Test
    void sharedWebModulesAreSecureStatelessResources() throws Exception {
        for (String path :
                List.of(
                        "/player-common/web-api.mjs",
                        "/player-common/web-session.mjs",
                        "/player-common/account.mjs")) {
            var result = mvc.perform(publicPage(get(path))).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsString()).contains("createWeb");
            if (!path.endsWith("/account.mjs")) {
                assertThat(result.getResponse().getContentAsString()).contains("export");
            }
            assertThat(result.getResponse().getContentType())
                    .isIn("text/javascript", "application/javascript");
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
            mvc.perform(publicPage(head(path))).andExpect(status().isOk());
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(publicPage(post(path))).andExpect(status().isForbidden());
            mvc.perform(publicPage(get(path + "?token=forbidden")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(publicPage(get("/player-common/private.mjs")))
                .andExpect(status().is4xxClientError());
    }

    /** 계정 문서는 외부 실행·기본 폼 전송·쿼리를 열지 않고 서버 비밀을 포함하지 않는다. */
    @Test
    void accountDocumentHasStrictHeadersAndNoServerCredentials() throws Exception {
        var result =
                mvc.perform(publicPage(get("/account"))).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("type=\"module\"", "src=\"/player-common/account.mjs\"", "회원 계정")
                .doesNotContain("accessToken", "flowBinder", "reauthGrant", "<script>");
        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .contains(
                        "script-src 'self'",
                        "connect-src 'self'",
                        "object-src 'none'",
                        "form-action 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        mvc.perform(publicPage(head("/account"))).andExpect(status().isOk());
        mvc.perform(get("/account")).andExpect(status().isForbidden());
        mvc.perform(publicPage(post("/account"))).andExpect(status().isForbidden());
        mvc.perform(publicPage(get("/account?token=forbidden"))).andExpect(status().isForbidden());
    }

    /** 단독 문서와 세 모듈만 HTTPS 읽기로 제공하고 query/변경/원문 주입을 막는다. */
    @Test
    void soloDocumentAndAssetsStayStatelessAndNarrow() throws Exception {
        var result = mvc.perform(publicPage(get("/play"))).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("/player-common/play-page.mjs", "단독·협동 사건 조사", "서버 저장본")
                .doesNotContain("accessToken", "<script>");
        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'self'", "connect-src 'self'", "form-action 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval");
        for (String path :
                List.of(
                        "/play",
                        "/coop-invite",
                        "/player-common/game-model.mjs",
                        "/player-common/game-client.mjs",
                        "/player-common/play-page.mjs",
                        "/player-common/invite-bootstrap.js")) {
            var asset = mvc.perform(publicPage(get(path))).andExpect(status().isOk()).andReturn();
            assertThat(asset.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(asset.getResponse().getHeaders("Set-Cookie")).isEmpty();
            if (path.endsWith(".mjs"))
                assertThat(asset.getResponse().getContentType())
                        .isIn("text/javascript", "application/javascript");
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(publicPage(post(path))).andExpect(status().isForbidden());
            mvc.perform(publicPage(get(path + "?secret=forbidden")))
                    .andExpect(status().isForbidden());
        }
    }

    /** PUBLIC 웹 경로도 동일 원점/무쿠키/정확 query를 유지하며 실제 수집 미준비는 503이다. */
    @Test
    void publicBrowserAdmissionKeepsOriginCookieAndQueryGuards() throws Exception {
        configuration.setFriendlyCollectionEnabled(false);
        var issued = account("public-web-route-" + UUID.randomUUID() + "@example.invalid");
        String token = issued.path("accessToken").asText();
        mvc.perform(
                        publicPage(get("/api/stories?size=20"))
                                .header("X-Sicha-Player-Web", "1")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
        for (String[] headers :
                List.of(
                        new String[] {"Origin", "https://foreign.example.invalid"},
                        new String[] {"Cookie", "JSESSIONID=synthetic"},
                        new String[] {"Sec-Fetch-Site", "cross-site"})) {
            var request =
                    publicPage(get("/api/stories"))
                            .header("X-Sicha-Player-Web", "1")
                            .header("Authorization", "Bearer " + token);
            if (!headers[0].equals("Sec-Fetch-Site"))
                request.header("Sec-Fetch-Site", "same-origin");
            mvc.perform(request.header(headers[0], headers[1])).andExpect(status().isForbidden());
        }
        mvc.perform(
                        publicPage(get("/api/stories?size=20&size=30"))
                                .header("X-Sicha-Player-Web", "1")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** 공개 고지는 기존 정책 권위의 현재 값만 표시하고 동의나 회원 세션을 만들지 않는다. */
    @Test
    void publicNoticeUsesCurrentPolicyAuthority() throws Exception {
        var notice = service.getNotice(UUID.randomUUID());
        var result =
                mvc.perform(publicPage(get("/privacy"))).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("회원 수집 고지", notice.notice().version(), notice.contact())
                .doesNotContain("<script", "flowBinder", "receiptToken");
        assertThat(result.getModelAndView().getModel().get("notice"))
                .usingRecursiveComparison()
                .ignoringFields("requestId")
                .isEqualTo(notice);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    /** 수집이 중단되면 공개 페이지도 이전 고지 본문을 재사용하지 않고 503으로 닫는다. */
    @Test
    void publicNoticeDoesNotRenderStalePolicyWhenCollectionStops() throws Exception {
        String notice = service.getNotice(UUID.randomUUID()).notice().body();
        configuration.setCollectionEnabled(false);
        try {
            var unavailable =
                    mvc.perform(publicPage(get("/privacy")))
                            .andExpect(status().isServiceUnavailable())
                            .andReturn();
            assertThat(unavailable.getResponse().getContentAsString()).doesNotContain(notice);
            assertThat(unavailable.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(unavailable.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        } finally {
            configuration.setCollectionEnabled(true);
        }
    }

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

    /** 같은 TLS·Origin 경계지만 cookie 대신 정확한 Bearer 업무 경로로 보낸다. */
    private MockHttpServletRequestBuilder business(String suffix, String access, Object input)
            throws Exception {
        return web(suffix, UUID.randomUUID(), JSON.writeValueAsString(input))
                .with(
                        request -> {
                            request.setRequestURI("/api/member/auth" + suffix);
                            return request;
                        })
                .header("Authorization", "Bearer " + access);
    }

    /** 상태 영수증은 인증 경로가 아닌 웹 공개 POST이며 출처·epoch만 전송한다. */
    private MockHttpServletRequestBuilder erasureStatus(UUID epoch, Object input) throws Exception {
        return web("/unused", epoch, JSON.writeValueAsString(input))
                .with(
                        request -> {
                            request.setRequestURI("/api/member/privacy/erase/status");
                            return request;
                        });
    }

    /** 현재 브라우저 Bearer의 삭제 권리와 독립 영수증 조회를 실제 HTTP 경계에서 확인한다. */
    @Test
    void deletionRightsAndCookieFreeReceiptRemainAvailableWithoutCollection() throws Exception {
        String access =
                account("web-erasure-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID identity =
                service.getMe(access, UUID.randomUUID()).identities().getFirst().identityKey();
        var deletionProof =
                Map.of("purpose", "DELETE_ACCOUNT", "identityKey", identity, "password", PASSWORD);
        configuration.setCollectionEnabled(false);
        try {
            for (String purpose : List.of("PASSWORD_CHANGE", "LOGOUT_ALL")) {
                var gated =
                        mvc.perform(
                                        business(
                                                "/reauth/local",
                                                access,
                                                Map.of(
                                                        "purpose",
                                                        purpose,
                                                        "identityKey",
                                                        identity,
                                                        "password",
                                                        PASSWORD)))
                                .andExpect(status().isServiceUnavailable())
                                .andReturn();
                assertThat(body(gated).path("code").asText()).isEqualTo("COLLECTION_NOT_READY");
                assertThat(gated.getResponse().getHeaders("Set-Cookie")).isEmpty();
            }
            var granted =
                    mvc.perform(business("/reauth/local", access, deletionProof))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(body(granted).path("purpose").asText()).isEqualTo("DELETE_ACCOUNT");
            String proof = body(granted).path("reauthGrant").asText();
            var command = Map.of("reauthGrant", proof, "requestKey", UUID.randomUUID());
            mvc.perform(
                            business("/account/delete", access, command)
                                    .header("Cookie", COOKIE + "=fake"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            business("/account/delete", access, command)
                                    .header("Origin", "https://foreign.example.invalid"))
                    .andExpect(status().isForbidden());
            var deleted =
                    mvc.perform(business("/account/delete", access, command))
                            .andExpect(status().isAccepted())
                            .andReturn();
            assertThat(body(deleted).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder(
                            "state", "eraseKey", "receiptToken", "receiptExpiresAt", "requestId");
            assertThat(body(deleted).path("state").asText()).isEqualTo("ACCESS_REVOKED");
            assertThat(deleted.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(deleted.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            String key = body(deleted).path("eraseKey").asText();
            String receipt = body(deleted).path("receiptToken").asText();
            assertThat(receipt).matches("[A-Za-z0-9_-]{43}");
            mvc.perform(business("/account/delete", access, command))
                    .andExpect(status().isUnauthorized());
            mvc.perform(me(access)).andExpect(status().isServiceUnavailable());
            configuration.setCollectionEnabled(true);
            mvc.perform(me(access)).andExpect(status().isUnauthorized());
            configuration.setCollectionEnabled(false);

            UUID epoch = UUID.randomUUID();
            var lookup = Map.of("eraseKey", key, "receiptToken", receipt);
            var pending =
                    mvc.perform(erasureStatus(epoch, lookup))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(body(pending).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder(
                            "state",
                            "accessRevoked",
                            "primaryDone",
                            "externalDone",
                            "backupDone",
                            "completedAt",
                            "requestId");
            assertThat(body(pending).path("state").asText()).isEqualTo("PROCESSING");
            assertThat(body(pending).path("accessRevoked").asBoolean()).isTrue();
            assertThat(body(pending).path("primaryDone").asBoolean()).isFalse();
            assertThat(body(pending).path("externalDone").asBoolean()).isFalse();
            assertThat(body(pending).path("backupDone").asBoolean()).isFalse();
            assertThat(body(pending).path("completedAt").isNull()).isTrue();
            assertThat(pending.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(pending.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            for (var invalid :
                    List.of(
                            Map.of(
                                    "eraseKey",
                                    UUID.randomUUID().toString(),
                                    "receiptToken",
                                    receipt),
                            Map.of("eraseKey", key, "receiptToken", "A".repeat(43)))) {
                var denied =
                        mvc.perform(erasureStatus(UUID.randomUUID(), invalid))
                                .andExpect(status().isUnauthorized())
                                .andReturn();
                assertThat(body(denied).path("code").asText()).isEqualTo("RECEIPT_UNAVAILABLE");
                assertThat(denied.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            }
            var nativeLookup =
                    mvc.perform(
                                    post("/api/member/privacy/erase/status")
                                            .secure(true)
                                            .contentType("application/json")
                                            .content(JSON.writeValueAsBytes(lookup)))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(body(nativeLookup).path("state").asText()).isEqualTo("PROCESSING");
            assertThat(nativeLookup.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(nativeLookup.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        } finally {
            configuration.setCollectionEnabled(true);
        }
    }

    /** 조회 본문과 웹 전송 경계는 토큰을 URL·쿠키·Bearer나 미승인 경로로 우회하지 않는다. */
    @Test
    void erasureReceiptRejectsLeakyAndMalformedRequests() throws Exception {
        UUID epoch = UUID.randomUUID();
        var lookup =
                Map.of("eraseKey", UUID.randomUUID().toString(), "receiptToken", "A".repeat(43));
        for (var request :
                List.of(
                        erasureStatus(epoch, lookup)
                                .header("Origin", "https://foreign.example.invalid"),
                        erasureStatus(epoch, lookup).header("Cookie", COOKIE + "=fake"),
                        erasureStatus(epoch, lookup)
                                .header("Authorization", "Bearer " + "A".repeat(43)))) {
            var denied = mvc.perform(request).andExpect(status().isForbidden()).andReturn();
            assertThat(denied.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(denied.getResponse().getHeaders("Set-Cookie")).isEmpty();
        }
        var query =
                mvc.perform(erasureStatus(epoch, lookup).queryParam("receiptToken", "secret"))
                        .andExpect(status().isBadRequest())
                        .andReturn();
        assertThat(query.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        for (var invalid :
                List.of(
                        Map.of("eraseKey", "not-a-uuid", "receiptToken", "A".repeat(43)),
                        Map.of(
                                "eraseKey",
                                UUID.randomUUID().toString(),
                                "receiptToken",
                                "A".repeat(43),
                                "extra",
                                "unapproved"))) {
            mvc.perform(erasureStatus(epoch, invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(
                        erasureStatus(epoch, lookup)
                                .with(
                                        request -> {
                                            request.removeHeader("X-Sicha-Player-Web-Epoch");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(web("/unknown-worker", epoch, "{}")).andExpect(status().isForbidden());
        mvc.perform(
                        web("/unused", epoch, "{}")
                                .with(
                                        request -> {
                                            request.setRequestURI(
                                                    "/api/member/privacy/erase/worker");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/member/privacy/erase/status")
                                .secure(true)
                                .header("Origin", ORIGIN)
                                .contentType("application/json")
                                .content(JSON.writeValueAsBytes(lookup)))
                .andExpect(status().isForbidden());
    }

    /** 네이티브는 Origin 없이 현재 Bearer만 허용하며 웹 출처를 섞거나 본문을 늘리면 거절한다. */
    @Test
    void nativeDeletionNeedsCurrentBearerAndExactBody() throws Exception {
        String access =
                account("native-erasure-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID identity =
                service.getMe(access, UUID.randomUUID()).identities().getFirst().identityKey();
        var proof =
                Map.of("purpose", "DELETE_ACCOUNT", "identityKey", identity, "password", PASSWORD);
        var nativeReauth =
                mvc.perform(
                                post("/api/member/auth/reauth/local")
                                        .secure(true)
                                        .header("Authorization", "Bearer " + access)
                                        .contentType("application/json")
                                        .content(JSON.writeValueAsBytes(proof)))
                        .andExpect(status().isOk())
                        .andReturn();
        var command =
                Map.of(
                        "reauthGrant",
                        body(nativeReauth).path("reauthGrant").asText(),
                        "requestKey",
                        UUID.randomUUID());
        mvc.perform(
                        post("/api/member/auth/account/delete")
                                .secure(true)
                                .header("Origin", ORIGIN)
                                .header("Authorization", "Bearer " + access)
                                .contentType("application/json")
                                .content(JSON.writeValueAsBytes(command)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/member/auth/account/delete")
                                .secure(true)
                                .contentType("application/json")
                                .content(JSON.writeValueAsBytes(command)))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/member/auth/account/delete")
                                .secure(true)
                                .header("Authorization", "Bearer " + access)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                Map.of(
                                                        "reauthGrant",
                                                        command.get("reauthGrant"),
                                                        "requestKey",
                                                        command.get("requestKey"),
                                                        "extra",
                                                        true))))
                .andExpect(status().isBadRequest());
        var accepted =
                mvc.perform(
                                post("/api/member/auth/account/delete")
                                        .secure(true)
                                        .header("Authorization", "Bearer " + access)
                                        .contentType("application/json")
                                        .content(JSON.writeValueAsBytes(command)))
                        .andExpect(status().isAccepted())
                        .andReturn();
        assertThat(body(accepted).path("state").asText()).isEqualTo("ACCESS_REVOKED");
        assertThat(body(accepted).path("receiptToken").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(accepted.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(accepted.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    /** 브라우저 최근본인확인·암호변경은 cookie를 거부하고 전송 자격과 업무 grant를 함께 요구한다. */
    @Test
    void recentVerificationAndPasswordChangeUseCookieFreeBearerOnly() throws Exception {
        String email = "web-recent-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        String access = issued.path("accessToken").asText();
        var identity =
                service.getMe(access, UUID.randomUUID()).identities().getFirst().identityKey();
        var input =
                Map.of("purpose", "PASSWORD_CHANGE", "identityKey", identity, "password", PASSWORD);
        mvc.perform(business("/reauth/local", access, input).header("Cookie", COOKIE + "=fake"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        business("/reauth/local", access, input)
                                .header("Origin", "https://foreign.example.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(web("/reauth/local", UUID.randomUUID(), JSON.writeValueAsString(input)))
                .andExpect(status().isForbidden());
        var granted =
                mvc.perform(business("/reauth/local", access, input))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(granted.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(granted.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(body(granted).size()).isEqualTo(4);
        var command =
                Map.of(
                        "reauthGrant",
                        body(granted).path("reauthGrant").asText(),
                        "newPassword",
                        "Synthetic-web-new-password!",
                        "requestKey",
                        UUID.randomUUID());
        var changed =
                mvc.perform(business("/password/change", access, command))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(changed).size()).isEqualTo(2);
        assertThat(body(changed).path("state").asText()).isEqualTo("LOGIN_REQUIRED");
        assertThat(changed.getResponse().getHeaders("Set-Cookie")).isEmpty();
        mvc.perform(business("/password/change", access, command))
                .andExpect(status().isUnauthorized());
        assertThat(
                        service.login(
                                        email,
                                        "Synthetic-web-new-password!",
                                        "198.19.1.1",
                                        UUID.randomUUID())
                                .principal()
                                .authRev())
                .isEqualTo(1);
    }

    /** GET 고지도 동일한 TLS authority·웹 표지·epoch 경계에서 요청한다. */
    private MockHttpServletRequestBuilder webNotice(UUID epoch) {
        return get(BASE + "/notice")
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerName("player.example.invalid");
                            request.setServerPort(443);
                            return request;
                        })
                .header("X-Sicha-Player-Web", "1")
                .header("X-Sicha-Player-Web-Epoch", epoch.toString())
                .header("Sec-Fetch-Site", "same-origin");
    }

    /** 상위 폐기형 SMTP fixture가 받은 검증 코드만 사용한다. */
    private static GreenMail smtpFixture() {
        return (GreenMail)
                org.springframework.test.util.ReflectionTestUtils.getField(
                        LocalMemberAuthIT.class, "SMTP");
    }

    /** 실제 격리 메일로 검증한 flow를 브라우저 가입 완료에 전달한다. */
    private Map<String, Object> verifiedWebSignup(String email, UUID epoch) throws Exception {
        var smtp = smtpFixture();
        int expected = smtp.getReceivedMessages().length + 1;
        var started =
                mvc.perform(
                                web(
                                        "/email/signup",
                                        epoch,
                                        JSON.writeValueAsString(Map.of("email", email))))
                        .andExpect(status().isAccepted())
                        .andReturn();
        assertThat(started.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(started.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(smtp.waitForIncomingEmail(5000, expected)).isTrue();
        String content = smtp.getReceivedMessages()[expected - 1].getContent().toString();
        var code = java.util.regex.Pattern.compile("[0-9]{8}").matcher(content);
        assertThat(code.find()).isTrue();
        String flowKey = body(started).path("flowKey").asText();
        String binder = body(started).path("flowBinder").asText();
        var verified =
                mvc.perform(
                                web(
                                        "/email/verify",
                                        epoch,
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "flowKey",
                                                        flowKey,
                                                        "flowBinder",
                                                        binder,
                                                        "code",
                                                        code.group()))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(verified).path("state").asText()).isEqualTo("VERIFIED");
        assertThat(verified.getResponse().getHeaders("Set-Cookie")).isEmpty();
        var notice = mvc.perform(webNotice(epoch)).andExpect(status().isOk()).andReturn();
        assertThat(notice.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(notice.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(body(notice).has("evidence")).isFalse();
        return Map.of(
                "flowKey",
                flowKey,
                "flowBinder",
                binder,
                "password",
                PASSWORD,
                "nickname",
                " 합성회원 ",
                "policyCode",
                body(notice).path("policyCode").asText(),
                "noticeHash",
                body(notice).path("noticeHash").asText(),
                "requestKey",
                UUID.randomUUID().toString());
    }

    /** 웹 고지·실제 메일 확인·단회 가입은 refresh JSON 없이 좁은 쿠키와 일곱 access 필드만 낸다. */
    @Test
    void webSignupWithIsolatedMailAndSingleUseCompletion() throws Exception {
        UUID epoch = UUID.randomUUID();
        var input = verifiedWebSignup("web-new-" + UUID.randomUUID() + "@example.invalid", epoch);
        var completed =
                mvc.perform(web("/email/signup/complete", epoch, JSON.writeValueAsString(input)))
                        .andExpect(status().isCreated())
                        .andReturn();
        assertThat(completed.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(body(completed).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "tokenType",
                        "accessToken",
                        "accessExpiresAt",
                        "sessionKey",
                        "sessionAbsoluteExpiresAt",
                        "webEpoch",
                        "requestId");
        assertThat(body(completed).path("webEpoch").asText()).isEqualTo(epoch.toString());
        assertThat(body(completed).toString()).doesNotContain("refreshToken", "flowBinder");
        assertThat(completed.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(completed.getResponse().getHeader("Set-Cookie"))
                .contains("Path=" + BASE, "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(cookie(completed)).matches(COOKIE + "=" + epoch + "\\.[A-Za-z0-9_-]{43}");
        mvc.perform(me(body(completed).path("accessToken").asText())).andExpect(status().isOk());
        var replay =
                mvc.perform(web("/email/signup/complete", epoch, JSON.writeValueAsString(input)))
                        .andExpect(status().is4xxClientError())
                        .andReturn();
        assertThat(replay.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    /** 가입 완료 전에 기존 로그인 family를 회수하며 공개 flow는 어떤 쿠키도 받지 않는다. */
    @Test
    void signupReplacementRevokesOldFamilyAndFlowRejectsCredentials() throws Exception {
        String previous = "web-old-" + UUID.randomUUID() + "@example.invalid";
        account(previous);
        UUID oldEpoch = UUID.randomUUID();
        var old = login(previous, oldEpoch);
        UUID epoch = UUID.randomUUID();
        var input =
                verifiedWebSignup(
                        "web-replacement-" + UUID.randomUUID() + "@example.invalid", epoch);
        for (String suffix : List.of("/email/signup", "/email/verify")) {
            mvc.perform(web(suffix, epoch, "{}").header("Cookie", cookie(old)))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            web(suffix, epoch, "{}")
                                    .header("Authorization", "Bearer " + "a".repeat(43)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(webNotice(epoch).header("Cookie", cookie(old)))
                .andExpect(status().isForbidden());
        mvc.perform(webNotice(epoch).header("Authorization", "Bearer " + "a".repeat(43)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        web("/email/signup/complete", epoch, "{}")
                                .header("Authorization", "Bearer " + "a".repeat(43)))
                .andExpect(status().isForbidden());
        var completed =
                mvc.perform(
                                web("/email/signup/complete", epoch, JSON.writeValueAsString(input))
                                        .header("Cookie", cookie(old)))
                        .andExpect(status().isCreated())
                        .andReturn();
        assertThat(completed.getResponse().getHeaders("Set-Cookie")).hasSize(2);
        assertThat(completed.getResponse().getHeaders("Set-Cookie").getFirst())
                .contains("Max-Age=0", "Path=" + BASE);
        mvc.perform(me(body(old).path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(web("/refresh", epoch, "{}").header("Cookie", cookie(completed)))
                .andExpect(status().isOk());
        mvc.perform(web("/refresh", epoch, "{}").header("Cookie", cookie(old)))
                .andExpect(status().isConflict());
        mvc.perform(web("/refresh", oldEpoch, "{}").header("Cookie", cookie(old)))
                .andExpect(status().isUnauthorized());
    }

    /** 공개 요청도 strict 본문·exact 경로·origin·기본 비활성 규칙을 따른다. */
    @Test
    void signupRoutesRejectMalformedNativeAndUnknownRequests() throws Exception {
        UUID epoch = UUID.randomUUID();
        mvc.perform(webNotice(epoch)).andExpect(status().isOk());
        mvc.perform(webNotice(epoch).header("Origin", "https://foreign.example.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        webNotice(epoch)
                                .with(
                                        request -> {
                                            request.removeHeader("Sec-Fetch-Site");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(webNotice(epoch).queryParam("unused", "1")).andExpect(status().isBadRequest());
        mvc.perform(
                        web("/email/signup", epoch, "{}")
                                .with(
                                        request -> {
                                            request.removeHeader("Origin");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        for (String suffix : List.of("/email/signup", "/email/verify", "/email/signup/complete")) {
            mvc.perform(web(suffix, epoch, "{}").queryParam("secret", "x"))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            web(suffix, epoch, "{}")
                                    .header("Origin", "https://foreign.example.invalid"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            web(suffix, epoch, "{}")
                                    .with(
                                            request -> {
                                                request.removeHeader("X-Sicha-Player-Web-Epoch");
                                                request.addHeader(
                                                        "X-Sicha-Player-Web-Epoch", "INVALID");
                                                return request;
                                            }))
                    .andExpect(status().isBadRequest());
        }
        for (String input :
                List.of(
                        "{\"email\":\"a@example.invalid\",\"email\":\"b@example.invalid\"}",
                        "{\"email\":7}",
                        "{\"email\":\"a@example.invalid\",\"extra\":1}",
                        "{} {}",
                        "[1]"))
            mvc.perform(web("/email/signup", epoch, input)).andExpect(status().isBadRequest());
        mvc.perform(web("/email/signup", epoch, " ".repeat(16385)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(
                        web(
                                "/email/verify",
                                epoch,
                                "{\"flowKey\":\"x\",\"flowBinder\":\"x\",\"code\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(web("/email/signup/complete", epoch, "{\"flowKey\":\"x\",\"flowKey\":\"y\"}"))
                .andExpect(status().isBadRequest());
        for (String suffix :
                List.of(
                        "/email/signup",
                        "/email/verify",
                        "/email/signup/complete",
                        "/unknown",
                        "/email/signup/complete/")) {
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .request(org.springframework.http.HttpMethod.GET, BASE + suffix)
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
                                    .header("Sec-Fetch-Site", "same-origin"))
                    .andExpect(status().isForbidden());
            mvc.perform(web(suffix, epoch, "{}"))
                    .andExpect(
                            status().is(
                                            suffix.equals("/unknown") || suffix.endsWith("/")
                                                    ? 403
                                                    : 400));
        }
        mvc.perform(web("/notice", epoch, "{}")).andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/member/privacy/policies/member-auth")
                                .secure(true)
                                .header("Origin", ORIGIN)
                                .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isForbidden());
        var disabled = new com.reasoning.web.member.auth.MemberWebPolicy(false, "");
        var admission =
                com.reasoning.web.member.auth.MemberWebPolicy.class.getDeclaredMethod(
                        "admit", jakarta.servlet.http.HttpServletRequest.class, boolean.class);
        admission.setAccessible(true);
        var request =
                webNotice(epoch)
                        .buildRequest(new org.springframework.mock.web.MockServletContext());
        assertThatThrownBy(() -> admission.invoke(disabled, request, false))
                .hasCauseInstanceOf(com.reasoning.common.auth.service.AuthException.class);
    }

    /** 단일 43자 access 자격으로 웹 회원 확인 요청을 만든다. */
    private MockHttpServletRequestBuilder me(String access) {
        return protectedWeb("GET", "/api/member/auth/me", access);
    }

    /**
     * 실제 HTTPS authority와 단일 웹 Bearer로 보호 요청을 만든다.
     *
     * @param method 검사할 HTTP 메서드(대문자, null 불가)
     * @param path query를 포함할 수 있는 내부 API 경로(null 불가)
     * @param access 실제 로그인으로 발급받은 43자 접근 자격(null 불가)
     * @return 실제 dispatcher에 전달할 요청
     */
    private MockHttpServletRequestBuilder protectedWeb(String method, String path, String access) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method),
                        java.net.URI.create(path))
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

    /**
     * 실제 dispatcher의 JSON 응답을 읽으며 변이 본문은 원래 값 그대로 전달한다.
     *
     * @param method GET 또는 POST
     * @param path query를 포함할 수 있는 내부 API 경로
     * @param access 실제 로그인으로 발급받은 접근 자격
     * @param value POST의 실제 의도 본문이며 GET이면 null
     * @return 성공 응답의 JSON 원문 구조
     * @throws Exception 요청 실행 또는 JSON 해석에 실패한 경우
     */
    private JsonNode protectedJson(String method, String path, String access, Object value)
            throws Exception {
        var request = protectedWeb(method, path, access);
        if (value != null)
            request.contentType("application/json").content(JSON.writeValueAsBytes(value));
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        return body(result);
    }

    /** 웹 조사·보고서 허용 목록과 raw 커서·중복 헤더를 실제 PG 회원 자격으로 검사한다. */
    @Test
    void browserBearerAllowlistAndRawCursorBoundary() throws Exception {
        playtestPolicy();
        String email = "web-boundary-" + UUID.randomUUID() + "@example.invalid";
        var nativeIssued = account(email);
        String access = body(login(email, UUID.randomUUID())).path("accessToken").asText();
        var identity = protectedJson("GET", "/api/playtests/identity", access, null);
        assertThat(identity.path("memberKey").asText())
                .isEqualTo(
                        invitations
                                .getMemberIdentity(
                                        nativeIssued.path("accessToken").asText(),
                                        UUID.randomUUID())
                                .memberKey()
                                .toString());
        for (String query :
                List.of(
                        "",
                        "?cursor=1",
                        "?cursor=9223372036854775807",
                        "?size=1",
                        "?size=20",
                        "?size=100",
                        "?cursor=1&size=20",
                        "?size=100&cursor=9223372036854775807")) {
            assertThat(
                            protectedJson("GET", "/api/playtests/invitations" + query, access, null)
                                    .path("items"))
                    .isEmpty();
        }
        for (String query :
                List.of(
                        "cursor=0",
                        "cursor=-1",
                        "cursor=01",
                        "cursor=+1",
                        "cursor=1&cursor=2",
                        "cursor=%31",
                        "cursor=9223372036854775808",
                        "cursor=10000000000000000000",
                        "cursor=",
                        "size=0",
                        "size=101",
                        "size=01",
                        "size=%32%30",
                        "size=20&size=20",
                        "cursor=1&size=20&size=20",
                        "size=10000000000000000000",
                        "other=1")) {
            mvc.perform(protectedWeb("GET", "/api/playtests/invitations?" + query, access))
                    .andExpect(status().isBadRequest());
        }
        String key = UUID.randomUUID().toString();
        for (String suffix : List.of("", "/policy-notice", "/materials", "/report", "/result")) {
            mvc.perform(protectedWeb("GET", "/api/playtests/" + key + suffix, access))
                    .andExpect(status().isNotFound());
        }
        String base = "/api/playtests/" + key;
        String proposalKey = UUID.randomUUID().toString();
        for (String path :
                List.of(
                        base + "/report",
                        base + "/report/proposals",
                        base + "/report/proposals/" + proposalKey + "/respond",
                        base + "/forfeit",
                        base + "/feedback")) {
            String method = path.equals(base + "/report") ? "PATCH" : "POST";
            mvc.perform(
                            protectedWeb(method, path, access)
                                    .contentType("application/json")
                                    .content(""))
                    .andExpect(status().isBadRequest());
            mvc.perform(protectedWeb(method, path + "?unexpected=1", access))
                    .andExpect(status().isBadRequest());
            for (String wrong : List.of("PUT", "DELETE", "HEAD", "OPTIONS")) {
                mvc.perform(protectedWeb(wrong, path, access)).andExpect(status().isForbidden());
            }
            mvc.perform(protectedWeb(method, path + "/", access)).andExpect(status().isForbidden());
        }
        for (String path : List.of(base + "/report", base + "/result")) {
            mvc.perform(protectedWeb("GET", path + "?unexpected=1", access))
                    .andExpect(status().isBadRequest());
        }
        for (String path :
                List.of(
                        base + "/report/proposals",
                        base + "/report/proposals/" + proposalKey + "/respond",
                        base + "/forfeit",
                        base + "/feedback")) {
            mvc.perform(protectedWeb("GET", path, access)).andExpect(status().isForbidden());
        }
        mvc.perform(protectedWeb("POST", base + "/report", access))
                .andExpect(status().isForbidden());
        mvc.perform(protectedWeb("PATCH", base + "/result", access))
                .andExpect(status().isForbidden());
        for (String path :
                List.of(
                        base + "/report/proposals/not-a-uuid/respond",
                        base + "/report/proposals/" + proposalKey.toUpperCase() + "/respond",
                        base + "/report/proposals/11111111-1111-1111-8111-111111111111/respond",
                        base + "/report/proposals/" + proposalKey + "/respond/",
                        base + "/report/",
                        base + "/result/",
                        base + "/forfeit/",
                        base + "/feedback/",
                        "/api/playtests/" + key.toUpperCase() + "/report",
                        "/api/playtests/11111111-1111-1111-8111-111111111111/report",
                        "/api/playtests/not-a-uuid/result",
                        "/api/playtests/" + key + "/hints/4/open",
                        "/api/playtests/" + key.toUpperCase(),
                        "/api/playtests/11111111-1111-1111-8111-111111111111",
                        "/api/playtests/not-a-uuid")) {
            for (String method : List.of("GET", "POST", "PATCH")) {
                mvc.perform(
                                protectedWeb(method, path, access)
                                        .contentType("application/json")
                                        .content("{}"))
                        .andExpect(status().isForbidden());
            }
        }
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            mvc.perform(protectedWeb(method, "/api/playtests/identity", access))
                    .andExpect(status().isForbidden());
        }
        for (String suffix :
                List.of(
                        "accept",
                        "ready",
                        "start",
                        "heartbeat",
                        "hints/1/open",
                        "hints/2/open",
                        "hints/3/open")) {
            String path = "/api/playtests/" + key + "/" + suffix;
            for (String method : List.of("GET", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
                mvc.perform(protectedWeb(method, path, access)).andExpect(status().isForbidden());
            }
        }
        for (String suffix : List.of("", "/policy-notice", "/materials")) {
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
                mvc.perform(protectedWeb(method, "/api/playtests/" + key + suffix, access))
                        .andExpect(status().isForbidden());
            }
        }
        for (int level : List.of(2, 3)) {
            mvc.perform(
                            protectedWeb(
                                            "POST",
                                            "/api/playtests/" + key + "/hints/" + level + "/open",
                                            access)
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(protectedWeb("GET", "/api/playtests/identity?cursor=1", access))
                .andExpect(status().isBadRequest());
        mvc.perform(protectedWeb("GET", base + "/report", access).header("Cookie", "probe=1"))
                .andExpect(status().isForbidden());
        mvc.perform(me(access).header("Cookie", "nonsecret=probe"))
                .andExpect(status().isForbidden());
        mvc.perform(me(access).header("Origin", "https://foreign.example.invalid"))
                .andExpect(status().isForbidden());
        for (String header : List.of("Authorization", "X-Sicha-Player-Web", "Sec-Fetch-Site")) {
            String value =
                    switch (header) {
                        case "Authorization" -> "Bearer " + access;
                        case "X-Sicha-Player-Web" -> "1";
                        default -> "same-origin";
                    };
            mvc.perform(me(access).header(header, value))
                    .andExpect(status().is(header.equals("Authorization") ? 401 : 403));
        }
        mvc.perform(
                        me(access)
                                .with(
                                        request -> {
                                            request.removeHeader("X-Sicha-Player-Web");
                                            request.addHeader("X-Sicha-Player-Web", "0");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(me(access).header("Origin", ORIGIN, ORIGIN)).andExpect(status().isForbidden());
        mvc.perform(
                        me(access)
                                .with(
                                        request -> {
                                            request.removeHeader("Sec-Fetch-Site");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(me(access).header("Sec-Fetch-Mode", "navigate"))
                .andExpect(status().isForbidden());
        mvc.perform(me(access).header("Sec-Fetch-Dest", "document"))
                .andExpect(status().isForbidden());
        mvc.perform(me(access).header("Origin", ORIGIN)).andExpect(status().isOk());
    }

    /** 실제 웹 발급 자격 두 개로 고지부터 역할 자료·힌트까지 거래·감사를 검증한다. */
    @Test
    void pairedBrowserConsentReadyStartAndRoleIsolation() throws Exception {
        playtestPolicy();
        String emailA = "web-pair-a-" + UUID.randomUUID() + "@example.invalid";
        String emailB = "web-pair-b-" + UUID.randomUUID() + "@example.invalid";
        var nativeA = account(emailA);
        var nativeB = account(emailB);
        UUID key =
                invitation(
                        List.of(
                                invitations
                                        .getMemberIdentity(
                                                nativeA.path("accessToken").asText(),
                                                UUID.randomUUID())
                                        .memberKey(),
                                invitations
                                        .getMemberIdentity(
                                                nativeB.path("accessToken").asText(),
                                                UUID.randomUUID())
                                        .memberKey()));
        String a = body(login(emailA, UUID.randomUUID())).path("accessToken").asText();
        String b = body(login(emailB, UUID.randomUUID())).path("accessToken").asText();
        String base = "/api/playtests/" + key;
        Map<String, Object> original = null;
        for (String access : List.of(a, b)) {
            var notice = protectedJson("GET", base + "/policy-notice", access, null);
            var intent =
                    Map.<String, Object>of(
                            "expectedRev",
                            notice.path("revision").asText(),
                            "inviteGen",
                            notice.path("generation").asInt(),
                            "blindDeclared",
                            false,
                            "policyCode",
                            notice.path("policyCode").asText(),
                            "noticeHash",
                            notice.path("noticeHash").asText(),
                            "requestKey",
                            UUID.randomUUID().toString());
            if (original == null) original = intent;
            var accepted = protectedJson("POST", base + "/accept", access, intent);
            assertThat(accepted.path("action").asText()).isEqualTo("INVITATION_ACCEPT");
            assertThat(accepted.path("changed").asBoolean()).isTrue();
            assertThat(accepted.path("replayed").asBoolean()).isFalse();
        }
        for (String access : List.of(a, b)) {
            mvc.perform(
                            protectedWeb("POST", base + "/heartbeat", access)
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isNoContent());
        }
        var replay = protectedJson("POST", base + "/accept", a, original);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("original").path("rev").asText()).isEqualTo("1");
        assertThat(replay.path("current").path("rev").asText()).isEqualTo("2");
        for (String access : List.of(a, b)) {
            String rev = protectedJson("GET", base, access, null).path("rev").asText();
            var ready =
                    protectedJson(
                            "POST",
                            base + "/ready",
                            access,
                            Map.of(
                                    "expectedRev",
                                    rev,
                                    "ready",
                                    true,
                                    "requestKey",
                                    UUID.randomUUID().toString()));
            assertThat(ready.path("action").asText()).isEqualTo("TEST_READY");
        }
        var started =
                protectedJson(
                        "POST",
                        base + "/start",
                        a,
                        Map.of("expectedRev", "4", "requestKey", UUID.randomUUID().toString()));
        assertThat(started.path("action").asText()).isEqualTo("TEST_START");
        assertThat(started.path("current").path("state").asText()).isEqualTo("RUNNING");
        var materialsA = protectedJson("GET", base + "/materials", a, null);
        var materialsB = protectedJson("GET", base + "/materials", b, null);
        assertThat(materialsA.path("role").path("code"))
                .isNotEqualTo(materialsB.path("role").path("code"));
        for (var material : List.of(materialsA, materialsB)) {
            String role = material.path("role").path("code").asText();
            assertThat(role).isIn("R1", "R2");
            assertThat(material.path("clues")).hasSize(1);
            assertThat(material.path("clues").get(0).path("code").asText())
                    .isEqualTo(role.equals("R1") ? "C1" : "C2");
            assertThat(material.toString())
                    .doesNotContain("secretText", "sourceText", "gradeSamples", "ruleData");
        }
        var hint =
                protectedJson(
                        "POST",
                        base + "/hints/1/open",
                        a,
                        Map.of("expectedRev", "5", "requestKey", UUID.randomUUID().toString()));
        assertThat(hint.path("action").asText()).isEqualTo("HINT_OPEN");
        assertThat(protectedJson("GET", base + "/materials", a, null).path("openedHints"))
                .hasSize(1);
        assertThat(protectedJson("GET", base + "/materials", b, null).path("openedHints"))
                .isEmpty();
        for (String table : List.of("test_action", "test_audit")) {
            for (var expected :
                    Map.of("INVITATION_ACCEPT", 2, "TEST_READY", 2, "TEST_START", 1, "HINT_OPEN", 1)
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
