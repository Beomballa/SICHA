package com.reasoning.common.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.reasoning.common.auth.audit.RequestAuditKernel.Actor;
import com.reasoning.common.auth.audit.RequestAuditKernel.ActorKind;
import com.reasoning.common.auth.audit.RequestAuditKernel.Observation;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JDBC 호출 관측의 단위 회귀이며 실제 DB·typed 어댑터·TLS·필수 업무 감사 증거는 아니다. */
@ExtendWith(OutputCaptureExtension.class)
class RequestAuditKernelTest {
    private static final Actor ANONYMOUS = new Actor(ActorKind.ANONYMOUS, null, null);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final RequestAuditKernel kernel = new RequestAuditKernel(db);
    private final List<Object[]> writes = new ArrayList<>();

    /** 사례 간 실제 thread 인증 컨텍스트를 공유하지 않는다. */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 입력 추적값을 새 UUID로 교체하고 하위 체인의 인증·상태만 종료 시 관측한다. */
    @Test
    void overwritesSpoofedCorrelationAndObservesAfterDownstream() throws Exception {
        captureWrites();
        var request = new MockHttpServletRequest("POST", "/synthetic/private-path");
        UUID spoofed = UUID.randomUUID();
        request.setAttribute(RequestAuditKernel.REQUEST_ID_ATTRIBUTE, spoofed);
        request.addHeader("X-Request-Id", spoofed.toString());
        var response = new MockHttpServletResponse();
        var after =
                UsernamePasswordAuthenticationToken.authenticated(new Object(), null, List.of());
        kernel.record(
                request,
                response,
                (req, res) -> {
                    UUID issued = RequestAuditKernel.requestId(request);
                    assertThat(issued).isNotEqualTo(spoofed);
                    assertThat(response.getHeader("X-Request-Id")).isEqualTo(issued.toString());
                    SecurityContextHolder.getContext().setAuthentication(after);
                    response.setStatus(201);
                },
                (authentication, status, uri) -> {
                    assertThat(authentication).isSameAs(after);
                    assertThat(status).isEqualTo(201);
                    assertThat(uri).isEqualTo("/synthetic/private-path");
                    return new Observation("UNMATCHED", ANONYMOUS);
                });
        Object[] row = onlyRow();
        assertThat(row[0]).isEqualTo(RequestAuditKernel.requestId(request));
        assertThat(row[1]).isEqualTo("ANONYMOUS");
        assertThat(row[2]).isNull();
        assertThat(row[3]).isNull();
        assertThat(row[4]).isEqualTo("UNMATCHED");
        assertThat(row[5]).isEqualTo("POST");
        assertThat((Timestamp) row[7]).isAfterOrEqualTo((Timestamp) row[6]);
        assertThat((Long) row[8]).isGreaterThanOrEqualTo(0L);
        assertThat(row[9]).isEqualTo(201);
    }

    /** 하위 예외를 동일 객체로 전파하며 임의 최종 상태를 만들지 않는다. */
    @Test
    void thrownDownstreamRecordsUnknownStatusAndPreservesException() {
        captureWrites();
        var request = new MockHttpServletRequest("GET", "/synthetic");
        var response = new MockHttpServletResponse();
        var failure = new ServletException("SYNTHETIC_PRIVATE_EXCEPTION");
        assertThatThrownBy(
                        () ->
                                kernel.record(
                                        request,
                                        response,
                                        (req, res) -> {
                                            response.setStatus(202);
                                            throw failure;
                                        },
                                        (authentication, status, uri) -> {
                                            assertThat(status).isNull();
                                            return new Observation("UNMATCHED", ANONYMOUS);
                                        }))
                .isSameAs(failure);
        assertThat(onlyRow()[9]).isNull();
        assertThat(response.getHeader("X-Request-Id"))
                .isEqualTo(RequestAuditKernel.requestId(request).toString());
    }

    /** 비동기 시작은 filter 반환 시 최종 응답이나 두 번째 발급을 뜻하지 않는다. */
    @Test
    void asyncStartedRecordsUnknownStatusOnlyOnce() throws Exception {
        captureWrites();
        var request = new MockHttpServletRequest("GET", "/synthetic");
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        kernel.record(
                request,
                response,
                (req, res) -> {
                    request.startAsync();
                    response.setStatus(203);
                },
                (authentication, status, uri) -> {
                    assertThat(status).isNull();
                    return new Observation("UNMATCHED", ANONYMOUS);
                });
        assertThat(onlyRow()[9]).isNull();
        request.getAsyncContext().complete();
        assertThat(writes).hasSize(1);
    }

    /** 완료 정책 실패는 응답을 바꾸거나 INSERT를 재시도하지 않는다. */
    @Test
    void policyFailureSkipsInsertAndLogsNoPrivateCause(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/synthetic");
        var response = new MockHttpServletResponse();
        kernel.record(
                request,
                response,
                (req, res) -> response.setStatus(201),
                (authentication, status, uri) -> {
                    throw new DataAccessResourceFailureException("SYNTHETIC_POLICY_SECRET");
                });
        verifyNoInteractions(db);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(output.getAll())
                .contains("Access history write failed; operational investigation required")
                .doesNotContain("SYNTHETIC_POLICY_SECRET");
    }

    /** 일반 접근 INSERT 실패는 하위 성공을 되돌리거나 비밀 인자를 출력하지 않는다. */
    @Test
    void accessInsertFailureDoesNotChangeResponseOrRetry(CapturedOutput output) throws Exception {
        doThrow(new DataAccessResourceFailureException("SYNTHETIC_SQL_SECRET"))
                .when(db)
                .update(anyString(), any(Object[].class));
        var request = new MockHttpServletRequest("GET", "/synthetic");
        var response = new MockHttpServletResponse();
        kernel.record(
                request,
                response,
                (req, res) -> response.setStatus(204),
                (authentication, status, uri) -> new Observation("UNMATCHED", ANONYMOUS));
        verify(db).update(anyString(), any(Object[].class));
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(output.getAll())
                .contains("Access history write failed; operational investigation required")
                .doesNotContain("SYNTHETIC_SQL_SECRET");
    }

    /** 허용 목록 밖 메서드를 원문 대신 UNKNOWN으로 기록한다. */
    @Test
    void unknownMethodIsNotRecordedVerbatim() throws Exception {
        captureWrites();
        var request = new MockHttpServletRequest("SYNTHETIC_PRIVATE_METHOD", "/synthetic");
        kernel.record(
                request,
                new MockHttpServletResponse(),
                (req, res) -> {},
                (authentication, status, uri) -> new Observation("UNMATCHED", ANONYMOUS));
        assertThat(onlyRow()[5]).isEqualTo("UNKNOWN");
    }

    /** 필수 정책 누락을 기본 주체나 UUID 발급으로 숨기지 않는다. */
    @Test
    void missingPolicyFailsBeforeIssuanceOrStorage() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> kernel.record(request, response, (req, res) -> {}, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(request.getAttribute(RequestAuditKernel.REQUEST_ID_ATTRIBUTE)).isNull();
        assertThat(response.getHeader("X-Request-Id")).isNull();
        verifyNoInteractions(db);
    }

    /** 닫힌 주체의 누락·혼합 키나 누락된 관측 값을 기본값으로 바꾸지 않는다. */
    @Test
    void actorAndObservationRejectMissingOrMixedIdentity() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> new Actor(ActorKind.ADMIN, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.ADMIN, key, "worker"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.MEMBER, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.MEMBER, key, "worker"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.WORKER, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.WORKER, key, "worker"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.ANONYMOUS, key, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(ActorKind.ANONYMOUS, null, "worker"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Observation(null, ANONYMOUS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Observation("UNMATCHED", null))
                .isInstanceOf(NullPointerException.class);
    }

    /** MEMBER의 실제 UUID와 닫힌 경로만 접근 SQL에 투영하며 worker 주체로 바꾸지 않는다. */
    @Test
    void memberObservationUsesActualUuidWithoutWorkerIdentity() throws Exception {
        captureWrites();
        UUID memberKey = UUID.randomUUID();
        var request = new MockHttpServletRequest("GET", "/api/member/auth/me");
        kernel.record(
                request,
                new MockHttpServletResponse(),
                (req, res) -> {},
                (authentication, status, uri) ->
                        new Observation(uri, new Actor(ActorKind.MEMBER, memberKey, null)));
        assertThat(onlyRow()[1]).isEqualTo("MEMBER");
        assertThat(onlyRow()[2]).isEqualTo(memberKey);
        assertThat(onlyRow()[3]).isNull();
        assertThat(onlyRow()[4]).isEqualTo("/api/member/auth/me");
    }

    /** 실제 SQL 호출 인자를 캡처할 뿐 JDBC 저장 성공을 가장하지 않는다. */
    private void captureWrites() {
        doAnswer(
                        invocation -> {
                            writes.add(((Object[]) invocation.getRawArguments()[1]).clone());
                            return 1;
                        })
                .when(db)
                .update(anyString(), any(Object[].class));
    }

    /** 중복 발급·저장 여부와 기존 열 순서를 함께 검사한다. */
    private Object[] onlyRow() {
        assertThat(writes).hasSize(1);
        assertThat(writes.getFirst()).hasSize(10);
        return writes.getFirst();
    }
}
