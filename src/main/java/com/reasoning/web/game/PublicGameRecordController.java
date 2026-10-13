package com.reasoning.web.game;

import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.game.service.GameRecordService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** 현재 Bearer 회원에게만 공개 종료 기록을 제공한다. 쿠키나 caller 목적 값은 권위가 아니다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/member/records")
public final class PublicGameRecordController {
    private final GameRecordService records;

    public PublicGameRecordController(GameRecordService records) {
        this.records = records;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        query(request, Set.of("cursor", "size"));
        String raw = request.getParameter("size");
        int size = raw == null ? 20 : size(raw);
        return ok(
                records.getRecordList(
                        bearer(request), request.getParameter("cursor"), size, id(request)));
    }

    @GetMapping("/{recordKey}")
    public ResponseEntity<?> detail(@PathVariable String recordKey, HttpServletRequest request) {
        noQuery(request);
        UUID requestId = id(request);
        return ok(records.getRecordDetail(bearer(request), key(recordKey), requestId));
    }

    @GetMapping("/{recordKey}/reports/{reportKey}")
    public ResponseEntity<?> report(
            @PathVariable String recordKey,
            @PathVariable String reportKey,
            HttpServletRequest request) {
        noQuery(request);
        return ok(
                records.getReportDetail(
                        bearer(request), key(recordKey), key(reportKey), id(request)));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> known(AuthException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status())
                .cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("code", failure.code(), "requestId", id(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure, HttpServletRequest request) {
        return ResponseEntity.status(503)
                .cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("code", "DEPENDENCY_UNAVAILABLE", "requestId", id(request)));
    }

    private static UUID key(String raw) {
        if (raw == null
                || !raw.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        return UUID.fromString(raw);
    }

    private static int size(String raw) {
        if (!raw.matches("[1-9][0-9]{0,2}")) throw AuthException.badRequest("INVALID_REQUEST");
        int parsed = Integer.parseInt(raw);
        if (parsed > 100) throw AuthException.badRequest("INVALID_REQUEST");
        return parsed;
    }

    private static String bearer(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1 || !headers.getFirst().matches("Bearer [A-Za-z0-9_-]{43}"))
            throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
        return headers.getFirst().substring(7);
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        if (request.getQueryString() != null && request.getQueryString().isEmpty())
            throw AuthException.badRequest("INVALID_REQUEST");
        request.getParameterMap()
                .forEach(
                        (name, values) -> {
                            if (!allowed.contains(name) || values == null || values.length != 1)
                                throw AuthException.badRequest("INVALID_REQUEST");
                        });
    }

    private static UUID id(HttpServletRequest request) {
        return RequestAuditKernel.requestId(request);
    }

    private static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
