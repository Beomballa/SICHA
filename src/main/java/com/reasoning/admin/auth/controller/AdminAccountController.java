package com.reasoning.admin.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.admin.auth.service.AdminAccountService;
import com.reasoning.common.auth.service.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/accounts")
public class AdminAccountController {
    private static final Set<String> CHANGE_FIELDS = Set.of("expectedRev", "permissions", "reasonCode", "verificationRef");
    private static final Set<String> ACTIVE_FIELDS = Set.of("expectedRev", "reasonCode", "verificationRef");
    private static final Set<String> REACTIVATE_FIELDS = Set.of("expectedRev", "reasonCode", "verificationRef", "impactHash");
    private final AdminAccountService accounts;
    private final AdminSessionAdapter sessions;

    public AdminAccountController(AdminAccountService accounts, AdminSessionAdapter sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    /**
     * Lists minimum account states using the documented id-descending keyset cursor.
     * @param size page size, 1–100; null defaults to 20
     * @param afterId positive decimal id cursor or null for the first page
     * @param accountKey optional exact account UUID
     * @param activeYn optional active flag
     * @param enrolled optional completed-enrollment flag
     * @param permission optional single CREATE/REVIEW/PUBLISH/MANAGE filter
     * @param request current authenticated browser session; never a source for account filters
     * @return no-store account page without login IDs or credentials
     * @throws AuthException if live MANAGE or recent reauthentication is missing
     */
    @GetMapping
    public ResponseEntity<?> getAccountList(@RequestParam(required = false) Integer size,
            @RequestParam(required = false) String afterId, @RequestParam(required = false) UUID accountKey,
            @RequestParam(required = false) Boolean activeYn, @RequestParam(required = false) Boolean enrolled,
            @RequestParam(required = false) String permission, HttpServletRequest request) {
        CurrentSession current = current(request);
        return ok(accounts.getAccountList(current.id(), current.principal(), size, afterId,
                accountKey, activeYn, enrolled, permission));
    }

    /**
     * Reads only the allowed current state of an exact account after live authorization.
     * @param accountKey existing administrator UUID; not an authorization credential
     * @param request current authenticated browser session
     * @return no-store AccountView
     * @throws AuthException on missing authority or an unknown account
     */
    @GetMapping("/{accountKey}")
    public ResponseEntity<?> getAccountDetail(@PathVariable UUID accountKey, HttpServletRequest request) {
        CurrentSession current = current(request);
        return ok(accounts.getAccountDetail(current.id(), current.principal(), accountKey));
    }

    /**
     * Grants the requested distinct global permissions without changing unrelated flags.
     * @param accountKey exact administrator UUID
     * @param body JSON with string expectedRev, permissions, reasonCode and verificationRef
     * @param request authenticated browser session
     * @param response cookie headers are prepared before a self-change response is committed
     * @return ChangeResult including new revision and required credential action
     * @throws AuthException on invalid input, stale revision, missing authority or audit failure
     */
    @PostMapping("/{accountKey}/permissions/grant")
    public ResponseEntity<?> grant(@PathVariable UUID accountKey, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        CurrentSession current = current(request);
        ChangeInput input = parse(body, CHANGE_FIELDS);
        return changed(accounts.grantPermissions(current.id(), current.principal(), accountKey,
                input.expectedRev(), input.permissions(), input.reasonCode(), input.verificationRef(), UUID.randomUUID()),
                request, response);
    }

    /**
     * Revokes the requested permissions; only an audit-write failure allows the separately checked shrinking path.
     * @param accountKey exact administrator UUID
     * @param body JSON with string expectedRev, permissions, reasonCode and verificationRef
     * @param request authenticated browser session
     * @param response cookie headers for a committed self-change
     * @return ChangeResult distinguishing a recorded audit from an unconfirmed emergency audit
     * @throws AuthException when revision, last-manager protection or revocation confirmation fails
     */
    @PostMapping("/{accountKey}/permissions/revoke")
    public ResponseEntity<?> revoke(@PathVariable UUID accountKey, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        CurrentSession current = current(request);
        ChangeInput input = parse(body, CHANGE_FIELDS);
        return changed(accounts.revokePermissions(current.id(), current.principal(), accountKey,
                input.expectedRev(), input.permissions(), input.reasonCode(), input.verificationRef(), UUID.randomUUID()),
                request, response);
    }

    /**
     * Deactivates an account without erasing its global permissions or any future story relationships.
     * @param accountKey exact administrator UUID
     * @param body JSON with string expectedRev, reasonCode and verificationRef
     * @param request authenticated browser session
     * @param response cookie headers for a committed self-change
     * @return ChangeResult indicating whether state was actually changed
     * @throws AuthException on stale revision, last-manager protection or unconfirmed revocation
     */
    @PostMapping("/{accountKey}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable UUID accountKey, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        CurrentSession current = current(request);
        ChangeInput input = parse(body, ACTIVE_FIELDS);
        return changed(accounts.deactivate(current.id(), current.principal(), accountKey,
                input.expectedRev(), input.reasonCode(), input.verificationRef(), UUID.randomUUID()), request, response);
    }

    /**
     * Shows a bounded sample while committing to every retained relation in the impact hash.
     * @param accountKey exact inactive administrator UUID
     * @param request recently reauthenticated MANAGE session
     * @return no-store impact preview; no incident content or answers
     * @throws AuthException for unauthorized access, absent or active target
     */
    @GetMapping("/{accountKey}/reactivation-preview")
    public ResponseEntity<?> getReactivationPreview(@PathVariable UUID accountKey, HttpServletRequest request) {
        CurrentSession current = current(request);
        return ok(accounts.getReactivationPreview(current.id(), current.principal(), accountKey));
    }

    /**
     * Reactivates an account only if the full relation set still matches a separately confirmed preview.
     * @param accountKey exact administrator UUID
     * @param body JSON with string expectedRev, impactHash, RETURN_TO_WORK and verificationRef
     * @param request recently reauthenticated MANAGE session
     * @param response clears current browser cookies for a self-change
     * @return a ChangeResult after the mandatory audit commits
     * @throws AuthException when the revision or impact changed, or audit cannot commit
     */
    @PostMapping("/{accountKey}/reactivate")
    public ResponseEntity<?> reactivate(@PathVariable UUID accountKey, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        CurrentSession current = current(request);
        ChangeInput input = parse(body, REACTIVATE_FIELDS);
        if (!body.get("impactHash").isTextual()) throw AuthException.badRequest("INVALID_IMPACT_HASH");
        return changed(accounts.reactivate(current.id(), current.principal(), accountKey,
                input.expectedRev(), body.get("impactHash").textValue(), input.reasonCode(),
                input.verificationRef(), UUID.randomUUID()), request, response);
    }

    private CurrentSession current(HttpServletRequest request) {
        return sessions.current(request).orElseThrow(() -> AuthException.unauthorized("AUTH_REQUIRED"));
    }

    private ResponseEntity<?> changed(AdminAccountService.ChangeResult result, HttpServletRequest request,
            HttpServletResponse response) {
        if (result.changed() && "LOGIN".equals(result.nextAction())) {
            sessions.clearCookie(request, response);
            for (String name : List.of("__Host-admin-enroll", "__Host-admin-mfa", "__Host-admin-recovery")) {
                response.addHeader("Set-Cookie", ResponseCookie.from(name, "")
                        .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(0).build().toString());
            }
        }
        return ok(result);
    }

    private static ChangeInput parse(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || body.size() != allowed.size())
            throw AuthException.badRequest("INVALID_REQUEST");
        for (String name : allowed) if (!body.has(name)) throw AuthException.badRequest("INVALID_REQUEST");
        if (!body.path("expectedRev").isTextual() || !body.path("reasonCode").isTextual()
                || !body.path("verificationRef").isTextual()) throw AuthException.badRequest("INVALID_REQUEST");
        List<String> permissions = List.of();
        if (allowed.contains("permissions")) {
            JsonNode values = body.path("permissions");
            if (!values.isArray() || values.size() < 1 || values.size() > 4)
                throw AuthException.badRequest("INVALID_PERMISSIONS");
            List<String> parsed = new ArrayList<>(values.size());
            for (JsonNode value : values) {
                if (!value.isTextual()) throw AuthException.badRequest("INVALID_PERMISSIONS");
                parsed.add(value.textValue());
            }
            permissions = List.copyOf(parsed);
        }
        return new ChangeInput(body.get("expectedRev").textValue(), permissions,
                body.get("reasonCode").textValue(), body.get("verificationRef").textValue());
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private record ChangeInput(String expectedRev, List<String> permissions, String reasonCode, String verificationRef) {}
}
