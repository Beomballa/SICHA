package com.reasoning.admin.story.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryCloneService.WorkNotice;
import com.reasoning.common.story.service.StoryCloneService.WorkVersionExists;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

/** 엄격한 속성 조회의 단위 증거이며 서버 UUID 발급·DB 감사 상관관계의 통합 증거는 아니다. */
class StoryCorrelationTest {
    private static final UUID CURRENT_UUID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SPOOFED_UUID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final WorkNotice NOTICE = new WorkNotice(2, "/admin/stories/ST_TEST/versions/2");
    private final StoryErrors errors = new StoryErrors();

    /** 누락·문자열 UUID·다른 타입·헤더만 있는 요청은 새로운 감사 ID를 얻지 못한다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "stringUuid", "wrongType", "headerOnly"})
    void requestIdRejectsUnavailableAttribute(String defect) {
        var request = invalidRequest(defect);
        assertThatThrownBy(() -> StoryHttpSupport.requestId(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REQUEST_ID_UNAVAILABLE")
                .hasNoCause();
    }

    /** 알려진 오류도 실제 UUID 속성 없이 별도 응답 ID를 만들지 못한다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "stringUuid", "wrongType", "headerOnly"})
    void knownRejectsUnavailableAttribute(String defect) {
        var request = invalidRequest(defect);
        assertThatThrownBy(() -> errors.known(AuthException.forbidden("STORY_FORBIDDEN"), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REQUEST_ID_UNAVAILABLE")
                .hasNoCause();
    }

    /** 예외의 내용 없이도 서비스 장애 응답은 같은 엄격한 속성 계약을 따른다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "stringUuid", "wrongType", "headerOnly"})
    void unavailableRejectsUnavailableAttribute(String defect) {
        var request = invalidRequest(defect);
        assertThatThrownBy(() -> errors.unavailable(new Exception(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REQUEST_ID_UNAVAILABLE")
                .hasNoCause();
    }

    /** 작업본 안내가 없는 409 경로도 감사 ID 누락을 숨기지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "stringUuid", "wrongType", "headerOnly"})
    void cloneConflictWithoutNoticeRejectsUnavailableAttribute(String defect) {
        var request = invalidRequest(defect);
        assertThatThrownBy(() -> errors.cloneConflict(new WorkVersionExists(null), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REQUEST_ID_UNAVAILABLE")
                .hasNoCause();
    }

    /** 작업본 안내가 있는 409 경로도 감사 ID 누락을 숨기지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "stringUuid", "wrongType", "headerOnly"})
    void cloneConflictWithNoticeRejectsUnavailableAttribute(String defect) {
        var request = invalidRequest(defect);
        assertThatThrownBy(() -> errors.cloneConflict(new WorkVersionExists(NOTICE), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REQUEST_ID_UNAVAILABLE")
                .hasNoCause();
    }

    /** 클라이언트 헤더 대신 실제 UUID 속성 객체를 그대로 반환한다. */
    @Test
    void requestIdReturnsActualUuidAttribute() {
        assertThat(StoryHttpSupport.requestId(validRequest())).isSameAs(CURRENT_UUID);
    }

    /** 기존 상태별 고정 메시지·오류 코드·캐시 금지와 현재 UUID를 보존한다. */
    @ParameterizedTest
    @CsvSource({
        "400, INVALID_REQUEST, 요청 입력을 확인해 주세요.",
        "401, AUTH_REQUIRED, 운영자 인증이 필요합니다.",
        "403, STORY_FORBIDDEN, 작업 권한이 없습니다.",
        "409, STORY_CONFLICT, 현재 상태를 다시 확인해 주세요.",
        "422, INVALID_INPUT, 요청 입력을 확인해 주세요.",
        "503, STORY_UNAVAILABLE, 작업의 확정 상태를 다시 확인해 주세요."
    })
    void knownRetainsFixedErrorContract(int status, String code, String message) {
        AuthException exception =
                switch (status) {
                    case 400 -> AuthException.badRequest(code);
                    case 401 -> AuthException.unauthorized(code);
                    case 403 -> AuthException.forbidden(code);
                    case 409 -> AuthException.conflict(code);
                    case 422 -> AuthException.unprocessable(code);
                    case 503 -> AuthException.unavailable(code);
                    default -> throw new IllegalArgumentException();
                };
        var response = errors.known(exception, validRequest());
        assertError(response, status, code, message);
    }

    /** 서비스 장애는 예외 내용과 무관하게 기존 503 규격과 현재 UUID를 유지한다. */
    @Test
    void unavailableRetainsFixedErrorContract() {
        var response = errors.unavailable(new Exception(), validRequest());
        assertError(response, 503, "STORY_UNAVAILABLE", "작업의 확정 상태를 다시 확인해 주세요.");
    }

    /** 안내가 없으면 기존 오류 본문, 있으면 기존 안전 안내 본문을 유지한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cloneConflictRetainsFixedErrorContract(boolean withNotice) {
        var response =
                errors.cloneConflict(
                        new WorkVersionExists(withNotice ? NOTICE : null), validRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        if (withNotice) {
            assertThat(response.getBody())
                    .isInstanceOfSatisfying(
                            StoryErrors.CloneConflictBody.class,
                            body -> {
                                assertThat(body.code()).isEqualTo("WORK_VERSION_EXISTS");
                                assertThat(body.message()).isEqualTo("현재 상태를 다시 확인해 주세요.");
                                assertThat(body.requestId()).isSameAs(CURRENT_UUID);
                                assertThat(body.notice()).isSameAs(NOTICE);
                            });
        } else {
            assertThat(response.getBody())
                    .isInstanceOfSatisfying(
                            StoryErrors.ErrorBody.class,
                            body -> {
                                assertThat(body.code()).isEqualTo("WORK_VERSION_EXISTS");
                                assertThat(body.message()).isEqualTo("현재 상태를 다시 확인해 주세요.");
                                assertThat(body.requestId()).isSameAs(CURRENT_UUID);
                            });
        }
    }

    /** 실제 UUID와 다른 헤더를 함께 두어 속성만 신뢰하는지 확인한다. */
    private static MockHttpServletRequest validRequest() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestAuditKernel.REQUEST_ID_ATTRIBUTE, CURRENT_UUID);
        request.addHeader("X-Request-Id", SPOOFED_UUID.toString());
        return request;
    }

    /** 각 결함을 독립적으로 구성하며 문자열 UUID도 실제 UUID 속성으로 취급하지 않는다. */
    private static MockHttpServletRequest invalidRequest(String defect) {
        var request = new MockHttpServletRequest();
        switch (defect) {
            case "missing" -> {}
            case "stringUuid" ->
                    request.setAttribute(
                            RequestAuditKernel.REQUEST_ID_ATTRIBUTE, CURRENT_UUID.toString());
            case "wrongType" -> request.setAttribute(RequestAuditKernel.REQUEST_ID_ATTRIBUTE, 7);
            case "headerOnly" -> request.addHeader("X-Request-Id", SPOOFED_UUID.toString());
            default -> throw new IllegalArgumentException();
        }
        return request;
    }

    /** 응답의 기존 고정 필드와 현재 UUID 객체를 함께 대조한다. */
    private static void assertError(
            ResponseEntity<StoryErrors.ErrorBody> response,
            int status,
            String code,
            String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).isEqualTo(message);
        assertThat(response.getBody().requestId()).isSameAs(CURRENT_UUID);
    }
}
