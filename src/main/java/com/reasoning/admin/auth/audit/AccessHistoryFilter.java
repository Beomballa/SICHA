package com.reasoning.admin.auth.audit;

import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.audit.RequestAuditKernel.Actor;
import com.reasoning.common.auth.audit.RequestAuditKernel.ActorKind;
import com.reasoning.common.auth.audit.RequestAuditKernel.Observation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 관리자 인증 체인의 origin/CSRF 검사 앞에 설치하며 servlet filter bean으로 등록하지 않는다. */
public final class AccessHistoryFilter extends OncePerRequestFilter {
    private static final String UNMATCHED = "UNMATCHED";
    private static final Pattern INVITATION =
            Pattern.compile("/admin/api/auth/invitations/[A-Za-z0-9_-]+");
    private static final Pattern INVITATION_REISSUE =
            Pattern.compile("/admin/api/auth/invitations/[A-Za-z0-9_-]+/reissue");
    private static final Pattern ACCOUNT = Pattern.compile("/admin/accounts/[A-Za-z0-9_-]+");
    private static final Pattern ACCOUNT_API =
            Pattern.compile(
                    "/admin/api/accounts/[A-Za-z0-9_-]+(/permissions/(?:grant|revoke)|/deactivate|/reactivate|/reactivation-preview)?");
    private static final Pattern STORY =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+(/(?:deactivate|reactivate|drafts|access(?:/(?:grant|revoke))?)|/versions/[0-9]+(?:/sections/(?:basic|answer|reveal))?)?");
    private static final Pattern STORY_OWNERSHIP =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/ownership(/requests(?:/([A-Za-z0-9_-]+)/(accept|close))?|/override)?");
    private static final Pattern STORY_PAGE_ACCESS =
            Pattern.compile("/admin/stories/[A-Za-z0-9_-]+/access");
    private static final Pattern STORY_PAGE_EDITOR =
            Pattern.compile("/admin/stories/[A-Za-z0-9_-]+/versions/[0-9]+");
    private static final Pattern STORY_PERSON =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/persons(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_ROLE =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/roles(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_PAIR =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/pairs(?:/([A-Z0-9_]{1,32}~[A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_CLUE =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/clues(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_CLUE_ROLE =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/clue-roles(?:/([A-Z0-9_]{1,32}~[A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_HINT =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/hints(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_EVENT =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/events(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_FACT =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/facts(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_RUBRIC =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/rubrics(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_RUBRIC_CLUE =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/rubric-clues(?:/([A-Z0-9_]{1,32}~[A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_GRADE_SAMPLE =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/grade-samples(?:/([A-Z0-9_]{1,32})(?:/(deactivate|reactivate))?)?");
    private static final Pattern STORY_REVIEW_COMMAND =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/(review-precheck|review-requests|return-to-draft|preview|grade-samples/check|evidence)");
    private static final Pattern STORY_REVIEW_SNAPSHOT =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/review-snapshots(?:/([0-9]+)(/records)?)?");
    private static final Pattern STORY_BATCH =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/regressions(?:/([A-Za-z0-9_-]+))?");
    private static final Pattern STORY_EXECUTION_ISSUES =
            Pattern.compile(
                    "/admin/api/stories/[A-Za-z0-9_-]+/versions/[0-9]+/execution-issues(?:/([A-Za-z0-9_-]+)/resolve)?");
    private final JdbcTemplate db;
    private final RequestAuditKernel kernel;

    /**
     * 기존 독립 접근 이력 저장 도구를 보관한다.
     *
     * @param db 접근 이력 DB 도구, null 불가
     */
    public AccessHistoryFilter(JdbcTemplate db) {
        this.db = db;
        this.kernel = new RequestAuditKernel(db);
    }

    /**
     * 공통 요청 관측에 관리자 경로·주체·활동 갱신 정책을 연결한다.
     *
     * @param request 클라이언트 추적 입력을 신뢰하지 않는 실제 요청
     * @param response 새 X-Request-Id를 제공할 실제 응답
     * @param chain 이력 뒤의 인증·인가·업무 체인
     * @throws ServletException 하위 체인 실패
     * @throws IOException 하위 전송 실패; 이력 저장 실패는 기존 안전 경고만 남김
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        kernel.record(request, response, chain, this::observe);
    }

    /**
     * 실제 인증된 관리자만 기록하고 관측된 업무 성공에서 기존 세션 활동 조건을 그대로 적용한다.
     *
     * @param authentication 하위 인증 증명, 부재 시 null
     * @param status 정상 동기 완료 상태, 예외·비동기 진행 시 null
     * @param uri query 없는 요청 URI, null 불가
     * @return 관리자 정규화 경로와 실제 ADMIN 또는 ANONYMOUS 주체
     * @throws RuntimeException 활동 갱신 실패; 공통 비치명적 경계에서 후속 INSERT도 생략
     */
    private Observation observe(Authentication authentication, Integer status, String uri) {
        String route = route(uri);
        AdminPrincipal principal =
                authentication != null
                                && authentication.isAuthenticated()
                                && authentication.getPrincipal() instanceof AdminPrincipal admin
                        ? admin
                        : null;
        Actor actor =
                principal == null
                        ? new Actor(ActorKind.ANONYMOUS, null, null)
                        : new Actor(ActorKind.ADMIN, principal.accountKey(), null);
        if (principal != null
                && status != null
                && status >= 200
                && status < 300
                && businessRoute(route)) {
            db.update(
                    "UPDATE admin_session SET"
                            + " last_action_at=clock_timestamp(),updated_at=clock_timestamp()"
                            + " WHERE session_key=? AND account_id=? AND auth_rev=? AND"
                            + " state='ACTIVE' AND clock_timestamp()<last_action_at+interval"
                            + " '30 minutes' AND clock_timestamp()<expires_at AND EXISTS"
                            + " (SELECT 1 FROM admin_credential c JOIN admin_account a ON"
                            + " a.id=c.account_id WHERE c.account_id=admin_session.account_id"
                            + " AND c.auth_rev=admin_session.auth_rev AND c.enrolled_at IS NOT"
                            + " NULL AND c.mfa_state='READY' AND a.active_yn)",
                    principal.sessionKey(),
                    principal.accountId(),
                    principal.authRev());
        }
        return new Observation(route, actor);
    }

    /**
     * 관리자 활동 시간을 갱신할 기존 업무 경로만 선택한다.
     *
     * @param route 정규화된 관리자 경로, null 불가
     * @return 기존 활동 갱신 대상이면 true
     */
    private static boolean businessRoute(String route) {
        return route.startsWith("/admin/api/auth/invitations")
                || route.equals("/admin/api/auth/recovery-codes")
                || route.equals("/admin/api/auth/mfa/recovery-codes")
                || route.startsWith("/admin/api/accounts")
                || route.startsWith("/admin/api/stories");
    }

    /**
     * 실제 식별자를 제거하고 승인된 서버 경로 틀만 반환한다.
     *
     * @param path 쿼리가 없는 요청 URI
     * @return 승인된 경로 틀 또는 UNMATCHED
     */
    private static String route(String path) {
        // 서버가 고정한 경로 틀만 저장하며 요청 경로의 식별자·원문은 기록하지 않는다.
        switch (path) {
            case "/admin",
                    "/admin/auth/manage",
                    "/admin/accounts",
                    "/admin/stories",
                    "/admin/login",
                    "/admin/login/mfa",
                    "/admin/enroll",
                    "/admin/recovery/password",
                    "/admin/recovery/mfa",
                    "/admin/api/auth/csrf",
                    "/admin/api/auth/login",
                    "/admin/api/auth/login/mfa",
                    "/admin/api/auth/login/status",
                    "/admin/api/auth/me",
                    "/admin/api/auth/reauth",
                    "/admin/api/auth/logout",
                    "/admin/api/auth/logout-all",
                    "/admin/api/auth/invitations",
                    "/admin/api/auth/recovery-codes",
                    "/admin/api/auth/mfa/recovery-codes",
                    "/admin/api/auth/recovery",
                    "/admin/api/auth/recovery/exchange",
                    "/admin/api/auth/recovery/password",
                    "/admin/api/auth/recovery/mfa/start",
                    "/admin/api/auth/recovery/mfa/setup",
                    "/admin/api/auth/recovery/mfa/verify",
                    "/admin/api/auth/recovery/mfa/complete",
                    "/admin/api/auth/enrollment/exchange",
                    "/admin/api/auth/enrollment/password",
                    "/admin/api/auth/enrollment/mfa/setup",
                    "/admin/api/auth/enrollment/mfa/verify",
                    "/admin/api/auth/enrollment/complete",
                    "/admin/api/auth/enrollment",
                    "/admin/api/history/navigation",
                    "/admin/api/stories",
                    "/admin/api/accounts" -> {
                return path;
            }
            default -> {}
        }
        if (INVITATION_REISSUE.matcher(path).matches())
            return "/admin/api/auth/invitations/{registrationKey}/reissue";
        if (INVITATION.matcher(path).matches())
            return "/admin/api/auth/invitations/{registrationKey}";
        if (ACCOUNT.matcher(path).matches()) return "/admin/accounts/{accountKey}";
        Matcher account = ACCOUNT_API.matcher(path);
        if (account.matches())
            return "/admin/api/accounts/{accountKey}"
                    + (account.group(1) == null ? "" : account.group(1));
        if (STORY_PAGE_ACCESS.matcher(path).matches()) return "/admin/stories/{storyCode}/access";
        if (STORY_PAGE_EDITOR.matcher(path).matches())
            return "/admin/stories/{storyCode}/versions/{versionNo}";
        Matcher review = STORY_REVIEW_COMMAND.matcher(path);
        if (review.matches())
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/" + review.group(1);
        Matcher snapshot = STORY_REVIEW_SNAPSHOT.matcher(path);
        if (snapshot.matches())
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/review-snapshots"
                    + (snapshot.group(1) == null ? "" : "/{snapshotId}")
                    + (snapshot.group(2) == null ? "" : "/records");
        Matcher batch = STORY_BATCH.matcher(path);
        Matcher issue = STORY_EXECUTION_ISSUES.matcher(path);
        if (issue.matches())
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/execution-issues"
                    + (issue.group(1) == null ? "" : "/{issueKey}/resolve");

        if (batch.matches())
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/regressions"
                    + (batch.group(1) == null ? "" : "/{batchKey}");
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
        Matcher fact = STORY_FACT.matcher(path);
        if (fact.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/facts"
                    + (fact.group(1) == null ? "" : "/{itemKey}")
                    + (fact.group(2) == null ? "" : "/" + fact.group(2));
        }
        Matcher rubric = STORY_RUBRIC.matcher(path);
        if (rubric.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/rubrics"
                    + (rubric.group(1) == null ? "" : "/{itemKey}")
                    + (rubric.group(2) == null ? "" : "/" + rubric.group(2));
        }
        Matcher rubricClue = STORY_RUBRIC_CLUE.matcher(path);
        if (rubricClue.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/rubric-clues"
                    + (rubricClue.group(1) == null ? "" : "/{itemKey}")
                    + (rubricClue.group(2) == null ? "" : "/" + rubricClue.group(2));
        }
        Matcher gradeSample = STORY_GRADE_SAMPLE.matcher(path);
        if (gradeSample.matches()) {
            return "/admin/api/stories/{storyCode}/versions/{versionNo}/grade-samples"
                    + (gradeSample.group(1) == null ? "" : "/{itemKey}")
                    + (gradeSample.group(2) == null ? "" : "/" + gradeSample.group(2));
        }
        Matcher ownership = STORY_OWNERSHIP.matcher(path);
        if (ownership.matches()) {
            return "/admin/api/stories/{storyCode}/ownership"
                    + (ownership.group(1) == null
                            ? ""
                            : ownership.group(2) == null
                                    ? ownership.group(1)
                                    : "/requests/{transferKey}/" + ownership.group(3));
        }
        Matcher story = STORY.matcher(path);
        if (story.matches()) {
            String suffix = story.group(1);
            if (suffix == null) return "/admin/api/stories/{storyCode}";
            if (suffix.startsWith("/versions/")) {
                return "/admin/api/stories/{storyCode}/versions/{versionNo}"
                        + (suffix.contains("/sections/")
                                ? "/sections/" + suffix.substring(suffix.lastIndexOf('/') + 1)
                                : "");
            }
            return "/admin/api/stories/{storyCode}" + suffix;
        }
        return UNMATCHED;
    }
}
