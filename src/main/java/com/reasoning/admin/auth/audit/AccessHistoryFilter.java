package com.reasoning.admin.auth.audit;

import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Install inside the security chain before origin/CSRF checks, not as a servlet filter bean. */
public final class AccessHistoryFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE = AccessHistoryFilter.class.getName() + ".requestId";
    private static final Logger log = LoggerFactory.getLogger(AccessHistoryFilter.class);
    private static final String UNMATCHED = "UNMATCHED";
    private static final Pattern INVITATION = Pattern.compile("/admin/api/auth/invitations/[A-Za-z0-9_-]+");
    private static final Pattern INVITATION_REISSUE = Pattern.compile("/admin/api/auth/invitations/[A-Za-z0-9_-]+/reissue");
    private static final Pattern ACCOUNT = Pattern.compile("/admin/accounts/[A-Za-z0-9_-]+");
    private static final Pattern ACCOUNT_API = Pattern.compile("/admin/api/accounts/[A-Za-z0-9_-]+(/permissions/(?:grant|revoke)|/deactivate|/reactivate|/reactivation-preview)?");
    private static final Pattern STORY = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+(/(?:deactivate|reactivate|access(?:/(?:grant|revoke))?)|/versions/[0-9]+(?:/sections/(?:basic|answer|reveal))?)?");
    private static final Pattern STORY_PAGE_ACCESS = Pattern.compile("/admin/stories/[A-Za-z0-9_-]+/access");
    private static final Pattern STORY_PAGE_EDITOR = Pattern.compile("/admin/stories/[A-Za-z0-9_-]+/versions/[0-9]+");
    private static final Pattern STORY_PERSON = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/persons(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_ROLE = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/roles(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_PAIR = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/pairs(?:/([A-Z0-9_]{1,32}~[A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_CLUE = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/clues(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_CLUE_ROLE = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/clue-roles(?:/([A-Z0-9_]{1,32}~[A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_HINT = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/hints(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_EVENT = Pattern.compile("/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/events(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private final JdbcTemplate db;

    public AccessHistoryFilter(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID requestId = UUID.randomUUID();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        Instant started = Instant.now();
        long startTick = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            Instant ended = Instant.now();
            long duration = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTick));
            // The active-session filter ran downstream; SecurityContextHolder is cleared only after our return.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            UUID actor = authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof AdminPrincipal principal ? principal.accountKey() : null;
            // An exception or async continuation means that the eventual HTTP status is not yet observable.
            Integer status = completed && !request.isAsyncStarted() ? response.getStatus() : null;
            String route = route(request.getRequestURI());
            try {
                if (actor != null && status != null && status >= 200 && status < 300
                        && businessRoute(route) && authentication.getPrincipal() instanceof AdminPrincipal principal) {
                    db.update("UPDATE admin_session SET last_action_at=clock_timestamp(),updated_at=clock_timestamp() "
                                    + "WHERE session_key=? AND account_id=? AND auth_rev=? AND state='ACTIVE' "
                                    + "AND clock_timestamp()<last_action_at+interval '30 minutes' "
                                    + "AND clock_timestamp()<expires_at "
                                    + "AND EXISTS (SELECT 1 FROM admin_credential c JOIN admin_account a ON a.id=c.account_id "
                                    + "WHERE c.account_id=admin_session.account_id AND c.auth_rev=admin_session.auth_rev "
                                    + "AND c.enrolled_at IS NOT NULL AND c.mfa_state='READY' AND a.active_yn)",
                            principal.sessionKey(), principal.accountId(), principal.authRev());
                }
                db.update("INSERT INTO access_history (kind,request_id,actor_kind,actor_key,route,method,"
                                + "started_at,ended_at,duration_ms,http_status) "
                                + "VALUES ('SERVER',?,?,?,?,?,?,?,?,?)", requestId,
                        actor == null ? "ANONYMOUS" : "ADMIN", actor, route,
                        method(request.getMethod()), Timestamp.from(started), Timestamp.from(ended), duration, status);
            } catch (RuntimeException failure) {
                // Never include the exception or request contents: JDBC exceptions can embed SQL parameter values.
                log.warn("Access history write failed; operational investigation required");
            }
        }
    }

    private static boolean businessRoute(String route) {
        return route.startsWith("/admin/api/auth/invitations")
                || route.equals("/admin/api/auth/recovery-codes")
                || route.equals("/admin/api/auth/mfa/recovery-codes")
                || route.startsWith("/admin/api/accounts") || route.startsWith("/admin/api/stories");
    }

    private static String method(String method) {
        return switch (method) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE" -> method;
            default -> "UNKNOWN";
        };
    }

    /** 실제 식별자를 제거하고 승인된 서버 경로 틀만 반환하며 미등록 경로는 UNMATCHED다. */
    private static String route(String path) {
        // 서버가 고정한 경로 틀만 저장하며 요청 경로의 식별자·원문은 기록하지 않는다.
        switch (path) {
            case "/admin", "/admin/auth/manage", "/admin/accounts", "/admin/stories",
                    "/admin/login", "/admin/login/mfa", "/admin/enroll", "/admin/recovery/password",
                    "/admin/recovery/mfa", "/admin/api/auth/csrf", "/admin/api/auth/login",
                    "/admin/api/auth/login/mfa", "/admin/api/auth/login/status",
                    "/admin/api/auth/me", "/admin/api/auth/reauth", "/admin/api/auth/logout", "/admin/api/auth/logout-all",
                    "/admin/api/auth/invitations", "/admin/api/auth/recovery-codes",
                    "/admin/api/auth/mfa/recovery-codes", "/admin/api/auth/recovery",
                    "/admin/api/auth/recovery/exchange", "/admin/api/auth/recovery/password",
                    "/admin/api/auth/recovery/mfa/start", "/admin/api/auth/recovery/mfa/setup",
                    "/admin/api/auth/recovery/mfa/verify", "/admin/api/auth/recovery/mfa/complete",
                    "/admin/api/auth/enrollment/exchange",
                    "/admin/api/auth/enrollment/password", "/admin/api/auth/enrollment/mfa/setup",
                    "/admin/api/auth/enrollment/mfa/verify", "/admin/api/auth/enrollment/complete",
                    "/admin/api/auth/enrollment", "/admin/api/history/navigation",
                    "/admin/api/stories", "/admin/api/accounts" -> { return path; }
            default -> { }
        }
        if (INVITATION_REISSUE.matcher(path).matches()) return "/admin/api/auth/invitations/{registrationKey}/reissue";
        if (INVITATION.matcher(path).matches()) return "/admin/api/auth/invitations/{registrationKey}";
        if (ACCOUNT.matcher(path).matches()) return "/admin/accounts/{accountKey}";
        Matcher account = ACCOUNT_API.matcher(path);
        if (account.matches()) return "/admin/api/accounts/{accountKey}" + (account.group(1) == null ? "" : account.group(1));
        if (STORY_PAGE_ACCESS.matcher(path).matches()) return "/admin/stories/{storyCode}/access";
        if (STORY_PAGE_EDITOR.matcher(path).matches()) return "/admin/stories/{storyCode}/versions/{versionNo}";
        Matcher person = STORY_PERSON.matcher(path);
        if (person.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/persons"
                    + (person.group(1) == null ? "" : "/{itemKey}")
                    + (person.group(2) == null ? "" : "/" + person.group(2));
        }
        // 역할 단일 키와 조합 복합 키는 각각 승인된 모양만 기록하며 원문·쿼리는 남기지 않는다.
        Matcher role = STORY_ROLE.matcher(path);
        if (role.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/roles"
                    + (role.group(1) == null ? "" : "/{itemKey}")
                    + (role.group(2) == null ? "" : "/" + role.group(2));
        }
        Matcher pair = STORY_PAIR.matcher(path);
        if (pair.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/pairs"
                    + (pair.group(1) == null ? "" : "/{itemKey}")
                    + (pair.group(2) == null ? "" : "/" + pair.group(2));
        }
        Matcher clue = STORY_CLUE.matcher(path);
        if (clue.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/clues"
                    + (clue.group(1) == null ? "" : "/{itemKey}")
                    + (clue.group(2) == null ? "" : "/" + clue.group(2));
        }
        Matcher assignment = STORY_CLUE_ROLE.matcher(path);
        if (assignment.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/clue-roles"
                    + (assignment.group(1) == null ? "" : "/{itemKey}")
                    + (assignment.group(2) == null ? "" : "/" + assignment.group(2));
        }
        Matcher hint = STORY_HINT.matcher(path);
        if (hint.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/hints"
                    + (hint.group(1) == null ? "" : "/{itemKey}")
                    + (hint.group(2) == null ? "" : "/" + hint.group(2));
        }
        Matcher event = STORY_EVENT.matcher(path);
        if (event.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/events"
                    + (event.group(1) == null ? "" : "/{itemKey}")
                    + (event.group(2) == null ? "" : "/" + event.group(2));
        }
        Matcher story = STORY.matcher(path);
        if (story.matches()) {
            String suffix = story.group(1);
            if (suffix == null) return "/admin/api/stories/{storyCode}";
            if (suffix.startsWith("/versions/")) {
                return "/admin/api/stories/{storyCode}/versions/{versionNo}"
                        + (suffix.contains("/sections/") ? "/sections/" + suffix.substring(suffix.lastIndexOf('/') + 1) : "");
            }
            return "/admin/api/stories/{storyCode}" + suffix;
        }
        return UNMATCHED;
    }
}
