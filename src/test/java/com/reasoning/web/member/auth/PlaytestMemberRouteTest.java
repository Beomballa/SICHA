package com.reasoning.web.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.PlaytestInvitationService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

/** 경로 허용표와 입력 거절이 서비스 읽기 전에 적용되는지 확인한다. */
class PlaytestMemberRouteTest {
    private static final String KEY = "11111111-1111-4111-8111-111111111111";
    private static final String ROOT = "/api/playtests";
    private final PlaytestInvitationService service = mock(PlaytestInvitationService.class);
    private final PlaytestMemberController controller =
            new PlaytestMemberController(
                    service,
                    mock(com.reasoning.common.story.service.PlaytestInvestigationService.class),
                    mock(com.reasoning.common.story.service.PlaytestReportService.class),
                    new ObjectMapper());

    @ParameterizedTest
    @CsvSource({
        "GET, /api/playtests/identity, true",
        "GET, /api/playtests/invitations, true",
        "GET, /api/playtests/11111111-1111-4111-8111-111111111111, true",
        "GET, /api/playtests/11111111-1111-4111-8111-111111111111/policy-notice, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/accept, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/ready, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/start, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/heartbeat, true",
        "GET, /api/playtests/11111111-1111-4111-8111-111111111111/materials, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/hints/3/open, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/hints/4/open, false",
        "GET, /api/playtests/11111111-1111-4111-8111-111111111111/ready, false",
        "GET, /api/playtests/11111111-1111-4111-8111-111111111111/report, true",
        "PATCH, /api/playtests/11111111-1111-4111-8111-111111111111/report, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/report/proposals, true",
        "POST,"
            + " /api/playtests/11111111-1111-4111-8111-111111111111/report/proposals/22222222-2222-4222-8222-222222222222/respond,"
            + " true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/forfeit, true",
        "GET, /api/playtests/11111111-1111-4111-8111-111111111111/result, true",
        "POST, /api/playtests/11111111-1111-4111-8111-111111111111/feedback, true",
        "GET, /api/playtests/11111111-1111-4111-8111-111111111111/reveal, false",
        "GET, /api/playtests/22222222-2222-4222-8222-222222222222/review-materials, false",
        "GET, /api/playtests/identity/extra, false",
        "DELETE, /api/playtests/invitations, false",
        "GET, /api/member/auth/me, true",
        "POST, /api/member/auth/login/local, true",
        "GET, /api/member/auth/login/local, false"
    })
    void onlyExactMethodsAndPathsArePermitted(String method, String path, boolean permitted) {
        assertThat(MemberSecurityConfig.route(new MockHttpServletRequest(method, path)))
                .isEqualTo(permitted);
    }

    @ParameterizedTest
    @CsvSource({
        "cursor=1&cursor=2",
        "size=1&other=2",
        "size=0",
        "size=101",
        "cursor=-1",
        "cursor=9223372036854775808",
        "size=abc",
        "cursor="
    })
    void invalidInvitationQueryCannotReadService(String query) {
        MockHttpServletRequest request = request("GET", ROOT + "/invitations", query);
        assertThatThrownBy(() -> controller.list(request))
                .isInstanceOf(AuthException.class)
                .satisfies(
                        failure ->
                                assertThat(((AuthException) failure).code())
                                        .isEqualTo("INVALID_REQUEST"));
        verify(service, never())
                .getMemberInvitationList(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validInvitationQueryUsesExactCursorAndPageSize() {
        MockHttpServletRequest request = request("GET", ROOT + "/invitations", "cursor=4&size=2");
        controller.list(request);
        verify(service)
                .getMemberInvitationList(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(4L),
                        org.mockito.ArgumentMatchers.eq(2),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void maximumPageSizeUsesCanonicalCursor() {
        controller.list(request("GET", ROOT + "/invitations", "cursor=9&size=100"));
        verify(service)
                .getMemberInvitationList(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9L),
                        org.mockito.ArgumentMatchers.eq(100), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void otherReadCannotCarryQuery() {
        MockHttpServletRequest request = request("GET", ROOT + "/" + KEY, "cursor=4");
        assertThatThrownBy(() -> controller.state(KEY, request)).isInstanceOf(AuthException.class);
        verify(service, never())
                .getMemberInvitationDetail(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @CsvSource({"true", "1", "null"})
    void blindOrMistypedDeclarationCannotReachConsent(String declared) {
        MockHttpServletRequest request = request("POST", ROOT + "/" + KEY + "/accept", null);
        request.setContentType("application/json");
        request.setContent(
                ("{\"expectedRev\":0,\"inviteGen\":1,\"blindDeclared\":"
                                + declared
                                + ",\"policyCode\":\"P\",\"noticeHash\":\"H\",\"requestKey\":\""
                                + KEY
                                + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.accept(KEY, request)).isInstanceOf(AuthException.class);
        verify(service, never())
                .acceptInvitation(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void functionalConsentPreservesTypedRevisionAndGeneration() {
        MockHttpServletRequest request = request("POST", ROOT + "/" + KEY + "/accept", null);
        request.setContentType("application/json");
        request.setContent(
                ("{\"expectedRev\":\"3\",\"inviteGen\":2,\"blindDeclared\":false,"
                                + "\"policyCode\":\"P\",\"noticeHash\":\"H\",\"requestKey\":\""
                                + KEY
                                + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        controller.accept(KEY, request);
        verify(service)
                .acceptInvitation(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(UUID.fromString(KEY)),
                        org.mockito.ArgumentMatchers.eq(2),
                        org.mockito.ArgumentMatchers.eq(3L),
                        org.mockito.ArgumentMatchers.eq("P"),
                        org.mockito.ArgumentMatchers.eq("H"),
                        org.mockito.ArgumentMatchers.eq(UUID.fromString(KEY)),
                        org.mockito.ArgumentMatchers.eq(UUID.fromString(KEY)));
    }

    @Test
    void duplicateJsonFieldCannotReachConsent() {
        MockHttpServletRequest request = request("POST", ROOT + "/" + KEY + "/accept", null);
        request.setContentType("application/json");
        request.setContent(
                ("{\"expectedRev\":3,\"expectedRev\":4,\"inviteGen\":2,"
                     + "\"blindDeclared\":false,\"policyCode\":\"P\",\"noticeHash\":\"H\","
                     + "\"requestKey\":\""
                                + KEY
                                + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.accept(KEY, request)).isInstanceOf(AuthException.class);
        verify(service, never())
                .acceptInvitation(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest request(String method, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setSecure(true);
        request.addHeader("Authorization", "Bearer " + "A".repeat(43));
        request.setAttribute(RequestAuditKernel.REQUEST_ID_ATTRIBUTE, UUID.fromString(KEY));
        if (query != null) {
            request.setQueryString(query);
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                request.addParameter(pair[0], pair.length == 2 ? pair[1] : "");
            }
        }
        return request;
    }
}
