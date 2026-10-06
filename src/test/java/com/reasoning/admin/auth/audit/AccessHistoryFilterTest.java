package com.reasoning.admin.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.audit.RequestAuditKernel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** typed 관리자 어댑터와 JDBC 호출 순서의 단위 증거이며 실제 세션 효력/DB 적용 증거는 아니다. */
class AccessHistoryFilterTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final List<String> sql = new ArrayList<>();
    private final List<Object[]> arguments = new ArrayList<>();
    private final AdminPrincipal admin =
            new AdminPrincipal(42L, UUID.randomUUID(), UUID.randomUUID(), 7L);

    /** 사례가 종료되면 현재 thread의 인증 증명을 폐기한다. */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 정상 업무 성공만 기존 세션 갱신 뒤 같은 실제 관리자 UUID의 접근 기록을 만든다. */
    @ParameterizedTest
    @CsvSource({
        "200,true",
        "201,true",
        "204,true",
        "400,false",
        "401,false",
        "403,false",
        "404,false",
        "409,false",
        "503,false"
    })
    void activityRefreshRequiresObservedBusinessSuccess(int status, boolean refresh)
            throws Exception {
        capture(false);
        var request = new MockHttpServletRequest("POST", "/admin/api/stories/ST_SYNTHETIC/drafts");
        var response = new MockHttpServletResponse();
        new AccessHistoryFilter(db)
                .doFilter(
                        request,
                        response,
                        (req, res) -> {
                            authenticate(admin);
                            response.setStatus(status);
                        });
        assertThat(sql).hasSize(refresh ? 2 : 1);
        if (refresh) {
            assertThat(sql.getFirst())
                    .startsWith("UPDATE admin_session SET")
                    .contains(
                            "session_key=? AND account_id=? AND auth_rev=?",
                            "state='ACTIVE'",
                            "clock_timestamp()<last_action_at+interval '30 minutes'",
                            "clock_timestamp()<expires_at",
                            "c.auth_rev=admin_session.auth_rev",
                            "c.enrolled_at IS NOT NULL",
                            "c.mfa_state='READY' AND a.active_yn");
            assertThat(arguments.getFirst())
                    .containsExactly(admin.sessionKey(), admin.accountId(), admin.authRev());
        }
        assertThat(sql.getLast()).startsWith("INSERT INTO access_history");
        Object[] row = arguments.getLast();
        assertThat(row[0]).isEqualTo(RequestAuditKernel.requestId(request));
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(row[0].toString());
        assertThat(row[1]).isEqualTo("ADMIN");
        assertThat(row[2]).isEqualTo(admin.accountKey());
        assertThat(row[3]).isNull();
        assertThat(row[4]).isEqualTo("/admin/api/stories/{storyCode}/drafts");
        assertThat(row[9]).isEqualTo(status);
    }

    /** 세션 갱신 실패 뒤 접근 INSERT를 별도 재시도하지 않으며 원래 응답을 유지한다. */
    @Test
    void activityFailurePreventsSubsequentInsertWithoutChangingResponse() throws Exception {
        capture(true);
        var request = new MockHttpServletRequest("POST", "/admin/api/stories/ST_SYNTHETIC/drafts");
        var response = new MockHttpServletResponse();
        new AccessHistoryFilter(db)
                .doFilter(
                        request,
                        response,
                        (req, res) -> {
                            authenticate(admin);
                            response.setStatus(200);
                        });
        assertThat(sql).hasSize(1);
        assertThat(sql.getFirst()).startsWith("UPDATE admin_session SET");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** unrelated principal이나 업무가 아닌 경로는 관리자 활동을 얻지 않는다. */
    @Test
    void unrelatedPrincipalCannotBecomeAdminAndNonBusinessDoesNotRefresh() throws Exception {
        capture(false);
        var request = new MockHttpServletRequest("GET", "/admin/api/auth/me");
        new AccessHistoryFilter(db)
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> authenticate("SYNTHETIC_NOT_ADMIN"));
        assertThat(sql).hasSize(1);
        assertThat(arguments.getFirst()[1]).isEqualTo("ANONYMOUS");
        assertThat(arguments.getFirst()[2]).isNull();
        assertThat(arguments.getFirst()[3]).isNull();
        sql.clear();
        arguments.clear();
        var second = new MockHttpServletRequest("GET", "/admin/api/auth/me");
        new AccessHistoryFilter(db)
                .doFilter(second, new MockHttpServletResponse(), (req, res) -> authenticate(admin));
        assertThat(sql).hasSize(1);
        assertThat(sql.getFirst()).startsWith("INSERT INTO access_history");
        assertThat(arguments.getFirst()[1]).isEqualTo("ADMIN");
    }

    /** JDBC 인자를 관측하고 합성 활동 실패만 주입하며 물리 저장 성공을 주장하지 않는다. */
    private void capture(boolean failActivity) {
        doAnswer(
                        invocation -> {
                            String query = (String) invocation.getRawArguments()[0];
                            sql.add(query);
                            arguments.add(((Object[]) invocation.getRawArguments()[1]).clone());
                            if (failActivity && query.startsWith("UPDATE admin_session SET")) {
                                throw new DataAccessResourceFailureException(
                                        "SYNTHETIC_ACTIVITY_FAILURE");
                            }
                            return 1;
                        })
                .when(db)
                .update(anyString(), any(Object[].class));
    }

    /** 테스트 하위 체인에만 인증을 두어 실제 어댑터의 타입 확인을 검사한다. */
    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal, null, List.of()));
    }
}
