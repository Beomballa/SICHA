package com.reasoning.admin.auth.controller;

import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.AuthModels;
import com.reasoning.common.auth.service.AuthModels.FlowCookie;
import com.reasoning.common.auth.service.AuthModels.SessionPrincipal;
import com.reasoning.common.auth.service.LoginSessionService;
import com.reasoning.common.auth.service.RecoveryService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/auth")
public class RecoveryController {
    private static final String RECOVERY_COOKIE = "__Host-admin-recovery";
    private final RecoveryService recovery;
    private final LoginSessionService login;
    private final AdminSessionAdapter sessions;

    public RecoveryController(RecoveryService recovery, LoginSessionService login, AdminSessionAdapter sessions) {
        this.recovery = recovery;
        this.login = login;
        this.sessions = sessions;
    }

    public record IssueRequest(UUID accountKey, String purpose, String verificationRef) {}
    public record ExchangeRequest(String code, String purpose) {}
    public record PasswordRequest(String password) {}
    public record StartMfaRequest(String loginId, String password, String recoveryCode) {}
    public record TotpRequest(String totp) {}
    public record StageBody(String stage, Instant expiresAt) {}
    public record RecoveryCodesBody(List<String> recoveryCodes) {}

    @PostMapping("/recovery-codes")
    public ResponseEntity<?> issue(@RequestBody IssueRequest body, HttpServletRequest request) {
        CurrentSession current = required(request);
        return response(HttpStatus.CREATED, recovery.issue(actor(current), current.id(), body.accountKey(),
                body.purpose(), body.verificationRef(), UUID.randomUUID()));
    }

    @PostMapping("/recovery/exchange")
    public ResponseEntity<?> exchange(@RequestBody ExchangeRequest body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        AuthModels.RecoveryExchange result = recovery.exchange(body.code(), body.purpose(),
                request.getRemoteAddr(), UUID.randomUUID());
        setRecoveryCookie(servletResponse, result.cookie());
        if ("MFA_RECOVERY".equals(result.purpose())) sessions.clearCookie(request, servletResponse);
        return ok(new AuthModels.RecoveryStage(result.purpose(), result.stage(), result.expiresAt()));
    }

    @GetMapping("/recovery")
    public ResponseEntity<?> stage(HttpServletRequest request) {
        return ok(recovery.stage(cookie(request)));
    }

    @PostMapping("/recovery/password")
    public ResponseEntity<?> resetPassword(@RequestBody PasswordRequest body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        AuthModels.SimpleResult result = recovery.resetPassword(cookie(request), body.password(), UUID.randomUUID());
        clearRecoveryCookie(servletResponse);
        sessions.clearCookie(request, servletResponse);
        return ok(result);
    }

    @PostMapping("/recovery/mfa/start")
    public ResponseEntity<?> startMfa(@RequestBody StartMfaRequest body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        AuthModels.RecoveryExchange result = recovery.startMfa(body.loginId(), body.password(), body.recoveryCode(),
                request.getRemoteAddr(), UUID.randomUUID());
        setRecoveryCookie(servletResponse, result.cookie());
        sessions.clearCookie(request, servletResponse);
        return ok(new AuthModels.RecoveryStage(result.purpose(), result.stage(), result.expiresAt()));
    }

    @PostMapping("/recovery/mfa/setup")
    public ResponseEntity<?> setupMfa(HttpServletRequest request) {
        return ok(recovery.setupMfa(cookie(request), UUID.randomUUID()));
    }

    @PostMapping("/recovery/mfa/verify")
    public ResponseEntity<?> verifyMfa(@RequestBody TotpRequest body, HttpServletRequest request) {
        AuthModels.RecoveryStage result = recovery.verifyMfa(cookie(request), body.totp(),
                request.getRemoteAddr(), UUID.randomUUID());
        return ok(new StageBody(result.stage(), result.expiresAt()));
    }

    @PostMapping("/recovery/mfa/complete")
    public ResponseEntity<?> completeMfa(HttpServletRequest request, HttpServletResponse servletResponse) {
        RecoveryService.Completed result = recovery.completeMfa(cookie(request), UUID.randomUUID());
        clearRecoveryCookie(servletResponse);
        sessions.clearCookie(request, servletResponse);
        return ok(result);
    }

    @DeleteMapping("/recovery")
    public ResponseEntity<Void> cancel(HttpServletRequest request, HttpServletResponse servletResponse) {
        try {
            recovery.cancel(cookie(request), UUID.randomUUID());
        } catch (AuthException failure) {
            if (failure.status() == 503) clearRecoveryCookie(servletResponse);
            throw failure;
        } catch (RuntimeException failure) {
            clearRecoveryCookie(servletResponse);
            throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
        }
        clearRecoveryCookie(servletResponse);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/mfa/recovery-codes")
    public ResponseEntity<?> replaceRecoveryCodes(HttpServletRequest request) {
        CurrentSession current = required(request);
        return ok(new RecoveryCodesBody(recovery.replaceRecoveryCodes(actor(current), current.id(), UUID.randomUUID())));
    }

    private CurrentSession required(HttpServletRequest request) {
        CurrentSession current = sessions.current(request).orElseThrow(() -> AuthException.unauthorized("AUTH_REQUIRED"));
        if (!login.isActive(current.id(), current.principal())) throw AuthException.unauthorized("AUTH_REQUIRED");
        return current;
    }

    private SessionPrincipal actor(CurrentSession current) {
        AuthModels.Me verified = login.me(current.id(), current.principal());
        return new SessionPrincipal(current.principal().accountId(), verified.accountKey(),
                current.principal().sessionKey(), verified.absoluteExpiresAt(), verified.idleExpiresAt(),
                verified.reauthExpiresAt(), false, false, false, false);
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (RECOVERY_COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private static void setRecoveryCookie(HttpServletResponse response, FlowCookie cookie) {
        if (cookie == null || !RECOVERY_COOKIE.equals(cookie.name()))
            throw AuthException.unavailable("AUTH_UNAVAILABLE");
        Duration remaining = Duration.between(Instant.now(), cookie.expiresAt());
        response.addHeader("Set-Cookie", ResponseCookie.from(RECOVERY_COOKIE, cookie.value())
                .secure(true).httpOnly(true).sameSite("Lax").path("/")
                .maxAge(remaining.isNegative() ? Duration.ZERO : remaining).build().toString());
    }

    private static void clearRecoveryCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(RECOVERY_COOKIE, "")
                .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
    }

    private static ResponseEntity<?> ok(Object value) {
        return response(HttpStatus.OK, value);
    }

    private static ResponseEntity<?> response(HttpStatus status, Object value) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(value);
    }
}
