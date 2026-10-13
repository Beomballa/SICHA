package com.reasoning.web.member.auth;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberAuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** refresh 원문은 좁은 HttpOnly 쿠키로만 보내고 access만 응답한다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class MemberWebAuthController {
    private final MemberAuthService service;

    public MemberWebAuthController(MemberAuthService service) {
        this.service = service;
    }

    public record WebAccessResponse(
            String tokenType,
            String accessToken,
            Instant accessExpiresAt,
            UUID sessionKey,
            Instant sessionAbsoluteExpiresAt,
            UUID webEpoch,
            UUID requestId) {}

    /** 현재 설치된 회원 고지만 쿠키 없는 브라우저 요청에 제공한다. */
    @GetMapping(MemberWebPolicy.BASE + "/notice")
    public ResponseEntity<?> notice(HttpServletRequest request) {
        return ok(service.getNotice(MemberAuthController.id(request)));
    }

    /** 기존 LOCAL 가입 흐름을 시작하며 refresh 쿠키를 요구하지 않는다. */
    @PostMapping(MemberWebPolicy.BASE + "/email/signup")
    public ResponseEntity<?> signup(HttpServletRequest request) {
        var input = MemberAuthController.body(request, "email");
        return ResponseEntity.accepted()
                .body(
                        service.startSignup(
                                MemberAuthController.text(input, "email"),
                                request.getRemoteAddr(),
                                MemberAuthController.id(request)));
    }

    /** 메일 코드와 binder를 기존 단회 확인 흐름에서 검증한다. */
    @PostMapping(MemberWebPolicy.BASE + "/email/verify")
    public ResponseEntity<?> verify(HttpServletRequest request) {
        var input = MemberAuthController.body(request, "flowKey", "flowBinder", "code");
        return ok(
                service.verifyEmail(
                        MemberAuthController.uuid(input, "flowKey"),
                        MemberAuthController.text(input, "flowBinder"),
                        MemberAuthController.text(input, "code"),
                        request.getRemoteAddr(),
                        MemberAuthController.id(request)));
    }

    /** cookie 없는 브라우저 복구를 기존 LOCAL 메일 권위에서 시작한다. */
    @PostMapping(MemberWebPolicy.BASE + "/password/reset")
    public ResponseEntity<?> reset(HttpServletRequest request) {
        var input = MemberAuthController.body(request, "email");
        return ResponseEntity.accepted()
                .body(
                        service.startReset(
                                MemberAuthController.text(input, "email"),
                                request.getRemoteAddr(),
                                MemberAuthController.id(request)));
    }

    /** 복구 확인 후 전체 family를 회수하며 새 쿠키나 access를 발급하지 않는다. */
    @PostMapping(MemberWebPolicy.BASE + "/password/reset/complete")
    public ResponseEntity<?> completeReset(HttpServletRequest request) {
        var input =
                MemberAuthController.body(
                        request, "flowKey", "flowBinder", "newPassword", "requestKey");
        return ok(
                service.completeReset(
                        MemberAuthController.uuid(input, "flowKey"),
                        MemberAuthController.text(input, "flowBinder"),
                        MemberAuthController.text(input, "newPassword"),
                        MemberAuthController.uuid(input, "requestKey"),
                        MemberAuthController.id(request)));
    }

    /** 이전 쿠키 family를 먼저 회수하고 검증된 가입을 단회 소비하여 access만 돌려준다. */
    @PostMapping(MemberWebPolicy.BASE + "/email/signup/complete")
    public ResponseEntity<?> complete(HttpServletRequest request, HttpServletResponse response) {
        var input =
                MemberAuthController.body(
                        request,
                        "flowKey",
                        "flowBinder",
                        "password",
                        "nickname",
                        "policyCode",
                        "noticeHash",
                        "requestKey");
        var epoch = MemberWebPolicy.epoch(request);
        revokePrevious(request, response);
        var issued =
                service.completeSignup(
                        MemberAuthController.uuid(input, "flowKey"),
                        MemberAuthController.text(input, "flowBinder"),
                        MemberAuthController.text(input, "password"),
                        MemberAuthController.text(input, "nickname"),
                        MemberAuthController.text(input, "policyCode"),
                        MemberAuthController.text(input, "noticeHash"),
                        MemberAuthController.uuid(input, "requestKey"),
                        MemberAuthController.id(request));
        return issued(issued, epoch, response, 201);
    }

    /** 기존 쿠키 증명을 먼저 회수하여 계정 교체가 이전 family를 남기지 않는다. */
    @PostMapping(MemberWebPolicy.BASE + "/login/local")
    public ResponseEntity<?> login(HttpServletRequest request, HttpServletResponse response) {
        var input = MemberAuthController.body(request, "email", "password");
        var epoch = MemberWebPolicy.epoch(request);
        revokePrevious(request, response);
        var issued =
                service.login(
                        MemberAuthController.text(input, "email"),
                        MemberAuthController.text(input, "password"),
                        request.getRemoteAddr(),
                        MemberAuthController.id(request));
        return issued(issued, epoch, response, 200);
    }

    /** 계정 교체에 앞서 유효한 이전 proof family를 회수하고 해당 쿠키를 지운다. */
    private void revokePrevious(HttpServletRequest request, HttpServletResponse response) {
        var proof = MemberWebPolicy.proof(request);
        if (proof == null) return;
        try {
            var revoked =
                    service.revokeByRefresh(
                            proof.token(),
                            MemberAuthController.id(request),
                            MemberAuthController.id(request));
            MemberSecurityConfig.principal(revoked.principal());
        } catch (AuthException failure) {
            if (failure.status() != 401) throw failure;
        }
        clear(response);
    }

    /** epoch 불일치는 서비스 호출 전 거절하여 늦은 쿠키가 새 계정으로 회전하지 못한다. */
    @PostMapping(MemberWebPolicy.BASE + "/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
        MemberAuthController.body(request);
        var epoch = MemberWebPolicy.epoch(request);
        var proof = MemberWebPolicy.proof(request);
        if (proof == null) return ResponseEntity.noContent().build();
        if (!proof.epoch().equals(epoch))
            throw new AuthException(409, "WEB_SESSION_CHANGED", "WEB_SESSION_CHANGED");
        return issued(
                service.refresh(proof.token(), MemberAuthController.id(request)),
                epoch,
                response,
                200);
    }

    /** 이전 epoch도 회수만 허용하며 실패를 성공으로 바꾸지 않는다. */
    @PostMapping(MemberWebPolicy.BASE + "/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            var input = MemberAuthController.body(request, "requestKey");
            var key = MemberAuthController.uuid(input, "requestKey");
            var proof = MemberWebPolicy.proof(request);
            if (proof == null)
                return ok(
                        new MemberAuthService.Logout(
                                "NO_SESSION", MemberAuthController.id(request)));
            var revoked =
                    service.revokeByRefresh(proof.token(), key, MemberAuthController.id(request));
            MemberSecurityConfig.principal(revoked.principal());
            return ok(revoked.response());
        } finally {
            clear(response);
        }
    }

    /** 만료를 늘리지 않는 초 내림 쿠키와 access 전용 DTO를 함께 발급한다. */
    private static ResponseEntity<?> issued(
            MemberAuthService.Issued issued, UUID epoch, HttpServletResponse response, int status) {
        var tokens = issued.tokens();
        Instant now = Instant.now();
        long maxAge = Duration.between(now, tokens.refreshExpiresAt()).getSeconds();
        if (maxAge <= 0 || !now.isBefore(tokens.accessExpiresAt()))
            throw AuthException.unavailable("AUTH_UNAVAILABLE");
        response.addHeader(
                "Set-Cookie",
                cookie(epoch + "." + tokens.refreshToken(), maxAge, tokens.refreshExpiresAt()));
        MemberSecurityConfig.principal(issued.principal());
        return ResponseEntity.status(status)
                .body(
                        new WebAccessResponse(
                                tokens.tokenType(),
                                tokens.accessToken(),
                                tokens.accessExpiresAt(),
                                tokens.sessionKey(),
                                tokens.sessionAbsoluteExpiresAt(),
                                epoch,
                                tokens.requestId()));
    }

    private static ResponseEntity<?> ok(Object body) {
        // MemberFilter는 허용·거절 모두에 no-store를 한 번 설정한다.
        return ResponseEntity.ok(body);
    }

    private static String cookie(String value, long maxAge, Instant expiry) {
        return MemberWebPolicy.COOKIE
                + "="
                + value
                + "; Path="
                + MemberWebPolicy.BASE
                + "; Max-Age="
                + maxAge
                + "; Expires="
                + DateTimeFormatter.RFC_1123_DATE_TIME.format(expiry.atZone(ZoneOffset.UTC))
                + "; Secure; HttpOnly; SameSite=Strict";
    }

    private static void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie("", 0, Instant.EPOCH));
    }

    /** 고정 오류만 반환하고 제한 창의 실제 Retry-After를 보존한다. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> failure(AuthException failure, HttpServletRequest request) {
        var result = ResponseEntity.status(failure.status());
        if (failure instanceof MemberAuthService.RateLimited limited)
            result.header("Retry-After", Long.toString(limited.retryAfterSeconds()));
        return result.body(MemberSecurityConfig.errorBody(request, failure.code()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure, HttpServletRequest request) {
        return ResponseEntity.status(503)
                .body(MemberSecurityConfig.errorBody(request, "AUTH_UNAVAILABLE"));
    }
}
