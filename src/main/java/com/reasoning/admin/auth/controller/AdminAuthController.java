package com.reasoning.admin.auth.controller;

import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.AuthModels;
import com.reasoning.common.auth.service.AuthModels.FlowCookie;
import com.reasoning.common.auth.service.AuthModels.SessionPrincipal;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.LoginSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/auth")
public class AdminAuthController {
    private static final String ENROLL_COOKIE = "__Host-admin-enroll";
    private static final String MFA_COOKIE = "__Host-admin-mfa";
    private final EnrollmentService enrollment;
    private final LoginSessionService login;
    private final AdminSessionAdapter sessions;

    public AdminAuthController(EnrollmentService enrollment, LoginSessionService login, AdminSessionAdapter sessions) {
        this.enrollment = enrollment;
        this.login = login;
        this.sessions = sessions;
    }

    public record InvitationRequest(UUID registrationKey, String loginId, String verificationRef) {}
    public record ReissueRequest(int expectedGeneration, String verificationRef) {}
    public record RevokeRequest(int expectedGeneration) {}
    public record CodeRequest(String code) {}
    public record PasswordRequest(String password) {}
    public record TotpRequest(String totp) {}
    public record LoginRequest(String loginId, String password) {}
    public record ReauthRequest(String password, String totp) {}
    public record AuthenticatedBody(String result, Instant absoluteExpiresAt) {}
    public record ReauthBody(Instant reauthExpiresAt, Instant absoluteExpiresAt) {}

    @PostMapping("/invitations")
    public ResponseEntity<?> invite(@RequestBody InvitationRequest body, HttpServletRequest request) {
        CurrentSession current = required(request);
        return response(HttpStatus.CREATED, enrollment.issueInvitation(actor(current), current.id(),
                body.registrationKey(), body.loginId(), body.verificationRef(), UUID.randomUUID()));
    }

    @GetMapping("/invitations/{registrationKey}")
    public ResponseEntity<?> invitation(@PathVariable UUID registrationKey, HttpServletRequest request) {
        CurrentSession current = required(request);
        return ok(enrollment.inspectInvitation(actor(current), current.id(), registrationKey, UUID.randomUUID()));
    }

    @PostMapping("/invitations/{registrationKey}/reissue")
    public ResponseEntity<?> reissue(@PathVariable UUID registrationKey, @RequestBody ReissueRequest body,
            HttpServletRequest request) {
        CurrentSession current = required(request);
        return ok(enrollment.reissueInvitation(actor(current), current.id(), registrationKey,
                body.expectedGeneration(), body.verificationRef(), UUID.randomUUID()));
    }

    @DeleteMapping("/invitations/{registrationKey}")
    public ResponseEntity<Void> revoke(@PathVariable UUID registrationKey, @RequestBody RevokeRequest body,
            HttpServletRequest request) {
        CurrentSession current = required(request);
        enrollment.revokeInvitation(actor(current), current.id(), registrationKey, body.expectedGeneration(), UUID.randomUUID());
        return noContent();
    }

    @PostMapping("/enrollment/exchange")
    public ResponseEntity<?> exchange(@RequestBody CodeRequest body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        AuthModels.EnrollExchange result = enrollment.exchange(body.code(), cookie(request, ENROLL_COOKIE),
                request.getRemoteAddr(), UUID.randomUUID());
        setFlowCookie(servletResponse, result.cookie(), ENROLL_COOKIE);
        return ok(new AuthModels.EnrollStage(result.stage(), result.expiresAt()));
    }

    @GetMapping("/enrollment")
    public ResponseEntity<?> enrollment(HttpServletRequest request) {
        return ok(enrollment.stage(cookie(request, ENROLL_COOKIE)));
    }

    @PutMapping("/enrollment/password")
    public ResponseEntity<?> enrollPassword(@RequestBody PasswordRequest body, HttpServletRequest request) {
        return ok(enrollment.setPassword(cookie(request, ENROLL_COOKIE), body.password(), UUID.randomUUID()));
    }

    @PostMapping("/enrollment/mfa/setup")
    public ResponseEntity<?> enrollMfaSetup(HttpServletRequest request) {
        return ok(enrollment.prepareMfa(cookie(request, ENROLL_COOKIE), UUID.randomUUID()));
    }

    @PostMapping("/enrollment/mfa/verify")
    public ResponseEntity<?> enrollMfaVerify(@RequestBody TotpRequest body, HttpServletRequest request) {
        return ok(enrollment.verifyMfa(cookie(request, ENROLL_COOKIE), body.totp(),
                request.getRemoteAddr(), UUID.randomUUID()));
    }

    @PostMapping("/enrollment/complete")
    public ResponseEntity<?> enrollComplete(HttpServletRequest request, HttpServletResponse servletResponse) {
        AuthModels.EnrollComplete result = enrollment.complete(cookie(request, ENROLL_COOKIE), UUID.randomUUID());
        clearCookie(servletResponse, ENROLL_COOKIE);
        return ok(result);
    }

    @DeleteMapping("/enrollment")
    public ResponseEntity<Void> enrollCancel(HttpServletRequest request, HttpServletResponse servletResponse) {
        try {
            enrollment.cancel(cookie(request, ENROLL_COOKIE), UUID.randomUUID());
        } catch (AuthException failure) {
            if (failure.status() == 503) clearCookie(servletResponse, ENROLL_COOKIE);
            throw failure;
        } catch (RuntimeException failure) {
            clearCookie(servletResponse, ENROLL_COOKIE);
            throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
        }
        clearCookie(servletResponse, ENROLL_COOKIE);
        return noContent();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        CurrentSession current = sessions.current(request).orElse(null);
        AuthModels.LoginStart result = login.login(body.loginId(), body.password(), cookie(request, MFA_COOKIE),
                current == null ? null : current.id(), request.getRemoteAddr(), UUID.randomUUID());
        setFlowCookie(servletResponse, result.cookie(), MFA_COOKIE);
        return ok(new AuthModels.EnrollStage(result.stage(), result.expiresAt()));
    }

    @PostMapping("/login/mfa")
    public ResponseEntity<?> loginMfa(@RequestBody TotpRequest body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        AuthModels.Authenticated result = login.authenticate(cookie(request, MFA_COOKIE), body.totp(),
                request.getRemoteAddr(), UUID.randomUUID());
        sessions.issueCookie(result.cookie().value(), request, servletResponse);
        clearCookie(servletResponse, MFA_COOKIE);
        return ok(new AuthenticatedBody(result.result(), result.absoluteExpiresAt()));
    }

    @GetMapping("/login/status")
    public ResponseEntity<?> loginStatus(HttpServletRequest request) {
        AuthModels.LoginStart result = login.status(cookie(request, MFA_COOKIE));
        return ok(new AuthModels.EnrollStage(result.stage(), result.expiresAt()));
    }

    @DeleteMapping("/login")
    public ResponseEntity<Void> loginCancel(HttpServletRequest request, HttpServletResponse servletResponse) {
        try {
            login.cancel(cookie(request, MFA_COOKIE), UUID.randomUUID());
        } catch (AuthException failure) {
            if (failure.status() == 503) clearCookie(servletResponse, MFA_COOKIE);
            throw failure;
        } catch (RuntimeException failure) {
            clearCookie(servletResponse, MFA_COOKIE);
            throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
        }
        clearCookie(servletResponse, MFA_COOKIE);
        return noContent();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        CurrentSession current = required(request);
        return ok(login.me(current.id(), current.principal()));
    }

    @PostMapping("/reauth")
    public ResponseEntity<?> reauth(@RequestBody ReauthRequest body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        CurrentSession current = required(request);
        AuthModels.ReauthResult result = login.reauth(current.id(), current.principal(), body.password(),
                body.totp(), request.getRemoteAddr(), UUID.randomUUID());
        sessions.issueCookie(result.cookie().value(), request, servletResponse);
        return ok(new ReauthBody(result.reauthExpiresAt(), result.absoluteExpiresAt()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse servletResponse) {
        try {
            CurrentSession current = sessions.current(request).orElse(null);
            login.logout(current == null ? null : current.id(), current == null ? null : current.principal(),
                    UUID.randomUUID());
        } catch (AuthException failure) {
            if (failure.status() == 503) sessions.clearCookie(request, servletResponse);
            throw failure;
        } catch (RuntimeException failure) {
            sessions.clearCookie(request, servletResponse);
            throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
        }
        sessions.clearCookie(request, servletResponse);
        return noContent();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(HttpServletRequest request, HttpServletResponse servletResponse) {
        CurrentSession current = required(request);
        try {
            login.logoutAll(current.id(), current.principal(), UUID.randomUUID());
        } catch (AuthException failure) {
            if (failure.status() == 503) sessions.clearCookie(request, servletResponse);
            throw failure;
        } catch (RuntimeException failure) {
            sessions.clearCookie(request, servletResponse);
            throw AuthException.unavailable("REVOCATION_UNCONFIRMED");
        }
        sessions.clearCookie(request, servletResponse);
        return noContent();
    }

    private CurrentSession required(HttpServletRequest request) {
        CurrentSession current = sessions.current(request).orElseThrow(() -> AuthException.unauthorized("AUTH_REQUIRED"));
        if (!login.isActive(current.id(), current.principal())) throw AuthException.unauthorized("AUTH_REQUIRED");
        return current;
    }

    private SessionPrincipal actor(CurrentSession current) {
        // Me rechecks app session and account state; invitations recheck authority and reauthentication under lock.
        AuthModels.Me verified = login.me(current.id(), current.principal());
        return new SessionPrincipal(current.principal().accountId(), verified.accountKey(),
                current.principal().sessionKey(), verified.absoluteExpiresAt(), verified.idleExpiresAt(),
                verified.reauthExpiresAt(), false, false, false, false);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private static void setFlowCookie(HttpServletResponse response, FlowCookie cookie, String expectedName) {
        if (cookie == null || !expectedName.equals(cookie.name())) throw AuthException.unavailable("AUTH_UNAVAILABLE");
        Duration remaining = Duration.between(Instant.now(), cookie.expiresAt());
        response.addHeader("Set-Cookie", ResponseCookie.from(expectedName, cookie.value())
                .secure(true).httpOnly(true).sameSite("Lax").path("/")
                .maxAge(remaining.isNegative() ? Duration.ZERO : remaining)
                .build().toString());
    }

    private static void clearCookie(HttpServletResponse response, String name) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, "")
                .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
    }

    private static ResponseEntity<?> ok(Object value) {
        return response(HttpStatus.OK, value);
    }

    private static ResponseEntity<?> response(HttpStatus status, Object value) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(value);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
