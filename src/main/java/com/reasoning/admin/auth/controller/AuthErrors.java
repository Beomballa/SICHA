package com.reasoning.admin.auth.controller;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.admin.auth.audit.NavigationController;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {AdminAuthController.class, AdminAccountController.class,
        RecoveryController.class, NavigationController.class})
public class AuthErrors {
    public record ErrorBody(String code, String message, UUID requestId) {}

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorBody> auth(AuthException exception) {
        int status = exception.status();
        String code = status == 503 && !"REVOCATION_UNCONFIRMED".equals(exception.code())
                && !"ADMIN_CHANGE_BUSY".equals(exception.code())
                ? "AUTH_UNAVAILABLE" : exception.code();
        String message = switch (status) {
            case 400 -> "요청을 확인할 수 없습니다.";
            case 401 -> "인증 정보가 유효하지 않습니다.";
            case 403 -> "요청을 수행할 권한이 없습니다.";
            case 404 -> "대상을 찾을 수 없습니다.";
            case 409 -> "현재 상태에서 요청을 처리할 수 없습니다.";
            case 422 -> "입력값이 유효하지 않습니다.";
            default -> "현재 요청을 처리할 수 없습니다.";
        };
        return response(status, code, message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorBody> invalidRequest(Exception exception) {
        return response(400, "INVALID_REQUEST", "요청을 확인할 수 없습니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unavailable(Exception exception) {
        return response(503, "AUTH_UNAVAILABLE", "현재 요청을 처리할 수 없습니다.");
    }

    private static ResponseEntity<ErrorBody> response(int status, String code, String message) {
        return ResponseEntity.status(HttpStatus.valueOf(status)).cacheControl(CacheControl.noStore())
                .body(new ErrorBody(code, message, UUID.randomUUID()));
    }
}
