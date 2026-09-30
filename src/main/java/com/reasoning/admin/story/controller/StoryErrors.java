package com.reasoning.admin.story.controller;

import com.reasoning.admin.auth.audit.AccessHistoryFilter;
import com.reasoning.common.auth.service.AuthException;

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
            StoryRoleController.class
        })
public final class StoryErrors {
    public record ErrorBody(String code, String message, UUID requestId) {}

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
        Object correlation = request.getAttribute(AccessHistoryFilter.REQUEST_ID_ATTRIBUTE);
        UUID requestId = correlation instanceof UUID id ? id : UUID.randomUUID();
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorBody(code, message, requestId));
    }
}
