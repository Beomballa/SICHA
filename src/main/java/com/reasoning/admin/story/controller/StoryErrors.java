package com.reasoning.admin.story.controller;

import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryCloneService.WorkNotice;
import com.reasoning.common.story.service.StoryCloneService.WorkVersionExists;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

@RestControllerAdvice(
        assignableTypes = {
            StoryController.class,
            StoryPersonController.class,
            StoryRoleController.class,
            StoryReviewController.class
        })
public final class StoryErrors {
    public record ErrorBody(String code, String message, UUID requestId) {}

    public record CloneConflictBody(
            String code, String message, UUID requestId, WorkNotice notice) {}

    /**
     * 현재 접근 가능한 작업본의 번호·경로만 안내하고 비활성 비소유자에게는 notice 자체를 생략한다.
     *
     * @param exception 부모 인가와 잠금 뒤 확정된 복제 충돌
     * @param request 현재 요청 상관 ID를 가진 요청
     * @return 기존 409 오류 규격과 선택적인 안전 작업본 안내
     */
    @ExceptionHandler(WorkVersionExists.class)
    public ResponseEntity<?> cloneConflict(
            WorkVersionExists exception, HttpServletRequest request) {
        ResponseEntity<ErrorBody> response = error(409, "WORK_VERSION_EXISTS", request);
        if (exception.notice() == null) return response;
        ErrorBody body = response.getBody();
        return ResponseEntity.status(409)
                .cacheControl(CacheControl.noStore())
                .body(
                        new CloneConflictBody(
                                body.code(), body.message(), body.requestId(), exception.notice()));
    }

    /** 원고·SQL·계정 식별자를 반영하지 않고 안정적인 사건 오류 코드를 반환한다. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorBody> known(AuthException exception, HttpServletRequest request) {
        return error(exception.status(), exception.code(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> invalidPath(
            MethodArgumentTypeMismatchException ignored, HttpServletRequest request) {
        return error(400, "INVALID_REQUEST", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unavailable(Exception ignored, HttpServletRequest request) {
        return error(503, "STORY_UNAVAILABLE", request);
    }

    /**
     * 현재 이력 UUID와 고정 문구만 오류에 결속하고 누락된 상관관계를 새 ID로 숨기지 않는다.
     *
     * @param status 실제 HTTP 오류 상태
     * @param code 원문을 포함하지 않는 고정 오류 코드
     * @param request 접근 이력 생산자의 UUID 속성을 가진 요청
     * @return 캐시 금지 오류 본문
     * @throws IllegalStateException 실제 UUID가 없으면 REQUEST_ID_UNAVAILABLE
     */
    private static ResponseEntity<ErrorBody> error(
            int status, String code, HttpServletRequest request) {
        String message =
                switch (status) {
                    case 400, 413, 422 -> "요청 입력을 확인해 주세요.";
                    case 401 -> "운영자 인증이 필요합니다.";
                    case 403 -> "작업 권한이 없습니다.";
                    case 404 -> "대상을 찾을 수 없습니다.";
                    case 409 -> "현재 상태를 다시 확인해 주세요.";
                    default -> "작업의 확정 상태를 다시 확인해 주세요.";
                };
        UUID requestId = RequestAuditKernel.requestId(request);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorBody(code, message, requestId));
    }
}
