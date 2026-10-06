package com.reasoning.common.grading.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.security.GradeWorkerSecurityConfig;
import com.reasoning.common.grading.service.GradeCompletionService;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol;
import com.reasoning.common.grading.service.GradeStartService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 명시 조립된 실제 BATCH 서비스만 호출하며 별도 업무 TX·제공자·스케줄러를 만들지 않는다. */
@RestController
@RequestMapping("/internal/api/grading/jobs/{jobKey}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class GradeWorkerController {
    private final ObjectProvider<Assembly> assemblies;
    private final GradeWorkerRequestDecoder decoder = new GradeWorkerRequestDecoder();

    /**
     * 신뢰 호출자가 동일 registry·실제 DS·TX 관리자·설치·crypto·coordinator로 만든 서비스를 공급한다. registry 존재만으로 활성화되지
     * 않으며 기본 서비스·신원·키 생성은 없다.
     *
     * @param credentials start·completion이 사용하는 동일 실제 registry, null 불가
     * @param start 동일 실제 DataSource의 DataSourceTransactionManager로 만든 서비스, null 불가
     * @param completion 동일 registry·DS·TX 관리자와 명시 실제 crypto·coordinator의 서비스, null 불가
     */
    public record Assembly(
            GradeWorkerCredentials credentials,
            GradeStartService start,
            GradeCompletionService completion) {
        /**
         * 누락된 명시 의존성을 기본값으로 대체하지 않는다. 내부 identity 대조는 실제 서비스가 수행한다.
         *
         * @param credentials 실제 동일 registry, null 불가
         * @param start 실제 예약·관측 서비스, null 불가
         * @param completion 실제 완료 서비스, null 불가
         * @throws NullPointerException 필수 서비스가 누락된 경우
         * @throws IllegalArgumentException 실제 registry·DataSource·JDBC 관리자·설치 소유가 일치하지 않는 경우
         */
        public Assembly {
            Objects.requireNonNull(credentials);
            Objects.requireNonNull(start);
            Objects.requireNonNull(completion);
            start.requireHttpAssembly(credentials, completion);
        }

        /**
         * @return 설정·서비스 내부 값을 포함하지 않는 고정 표현
         */
        @Override
        public String toString() {
            return "Assembly[explicit]";
        }
    }

    /**
     * 명시 assembly의 선택적 공급자만 보관한다.
     *
     * @param assemblies null 불가, assembly 없으면 모든 실행 불가
     */
    public GradeWorkerController(ObjectProvider<Assembly> assemblies) {
        this.assemblies = Objects.requireNonNull(assemblies);
    }

    /**
     * 실제 신규 시도 예약 또는 입력 없는 REPLAY를 반환한다.
     *
     * @param jobKey canonical 비영 UUID v4 경로
     * @param request 인증 체인을 통과한 8KiB 이하 UTF-8 JSON
     * @return 정확한 StartReply 또는 원문 없는 고정 오류
     */
    @PostMapping("/start")
    public ResponseEntity<ObjectNode> start(
            @PathVariable("jobKey") String jobKey, HttpServletRequest request) {
        return invoke(
                request,
                () ->
                        assembly()
                                .start()
                                .startApproved(
                                        worker(), decoder.jobKey(jobKey), decoder.start(request))
                                .toJson());
    }

    /**
     * 원래 시도의 현재성만 재검증하며 단회 grant를 생성하지 않는다.
     *
     * @param jobKey canonical 비영 UUID v4 경로
     * @param request 인증 체인을 통과한 8KiB 이하 AttemptRequest
     * @return 정확한 FenceReply 또는 원문 없는 고정 오류
     */
    @PostMapping("/before-chat")
    public ResponseEntity<ObjectNode> beforeChat(
            @PathVariable("jobKey") String jobKey, HttpServletRequest request) {
        return invoke(
                request,
                () ->
                        assembly()
                                .start()
                                .revalidate(
                                        worker(), decoder.jobKey(jobKey), decoder.attempt(request))
                                .toJson());
    }

    /**
     * 기존 갱신 TX와 후속 현재성 관측을 그대로 소비한다.
     *
     * @param jobKey canonical 비영 UUID v4 경로
     * @param request 인증 체인을 통과한 8KiB 이하 AttemptRequest
     * @return 정확한 RenewReply 또는 원문 없는 고정 오류
     */
    @PostMapping("/renew")
    public ResponseEntity<ObjectNode> renew(
            @PathVariable("jobKey") String jobKey, HttpServletRequest request) {
        return invoke(
                request,
                () ->
                        assembly()
                                .start()
                                .renewApproved(
                                        worker(), decoder.jobKey(jobKey), decoder.attempt(request))
                                .toJson());
    }

    /**
     * 서버 계산·필수 감사·영수증의 기존 원자 완료를 호출한다. replay의 과거 requestId는 바꾸지 않는다.
     *
     * @param jobKey canonical 비영 UUID v4 경로
     * @param request 인증 체인의 새 서버 UUID와 전체 256KiB 이하 완료 봉투
     * @return 여덟 필드 CompletionReceipt 또는 원문 없는 고정 오류; 200은 채점 적용 보장이 아님
     */
    @PostMapping("/complete")
    public ResponseEntity<ObjectNode> complete(
            @PathVariable("jobKey") String jobKey, HttpServletRequest request) {
        return invoke(
                request,
                () -> {
                    UUID key = decoder.jobKey(jobKey);
                    var input = decoder.complete(request);
                    var receipt =
                            assembly()
                                    .completion()
                                    .complete(
                                            worker(),
                                            key,
                                            input.leaseGen(),
                                            input.attemptNo(),
                                            input.observedProviderVersion(),
                                            input.providerResponseRef(),
                                            input.resultJson(),
                                            requestId(request));
                    return JsonNodeFactory.instance
                            .objectNode()
                            .put("jobKey", receipt.jobKey().toString())
                            .put("attemptNo", receipt.attemptNo())
                            .put("accepted", receipt.accepted())
                            .put("state", receipt.state())
                            .put("retryScheduled", receipt.retryScheduled())
                            .put("outcome", receipt.outcome())
                            .put("reason", receipt.reason())
                            .put("requestId", receipt.requestId().toString());
                });
    }

    /**
     * endpoint 내부의 알려진 오류 타입만 고정 상태로 매핑한다. 임의 예외 메시지는 읽지 않는다.
     *
     * @param request 서버 요청 UUID가 설정된 요청
     * @param operation 실제 서비스 호출, null 불가
     * @return no-store 닫힌 JSON 응답
     * @throws IllegalStateException 서버 UUID가 없는 비정상 체인 조립
     */
    private static ResponseEntity<ObjectNode> invoke(
            HttpServletRequest request, Supplier<ObjectNode> operation) {
        int status;
        String code;
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(operation.get());
        } catch (GradeWorkerRequestDecoder.Failure failure) {
            status = failure.oversized() ? 413 : 400;
            code = failure.oversized() ? "PAYLOAD_TOO_LARGE" : "INVALID_REMOTE_REQUEST";
        } catch (GradeRemoteExecutionProtocol.Failure failure) {
            code = failure.code().name();
            status =
                    switch (failure.code()) {
                        case INVALID_REMOTE_REQUEST -> 400;
                        case REMOTE_EXECUTION_FORBIDDEN -> 403;
                        case REMOTE_EXECUTION_NOT_CURRENT, REMOTE_EXECUTION_EXPIRED -> 409;
                        case REMOTE_EXECUTION_UNAVAILABLE, REMOTE_REQUIRES_SEPARATE_TRANSACTION ->
                                503;
                    };
            if (status == 503) code = "REMOTE_EXECUTION_UNAVAILABLE";
        } catch (SecurityException failure) {
            status = 403;
            code = "REMOTE_EXECUTION_FORBIDDEN";
        } catch (GradeCompletionService.CallbackConflictException failure) {
            status = 409;
            code = "CALLBACK_CONFLICT";
        } catch (IllegalArgumentException failure) {
            status = 400;
            code = "INVALID_REMOTE_REQUEST";
        } catch (RuntimeException failure) {
            status = 503;
            code = "REMOTE_EXECUTION_UNAVAILABLE";
        }
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(GradeWorkerSecurityConfig.errorBody(code, requestId(request)));
    }

    /**
     * 실제 인증 증명만 읽으며 쿠키·헤더·임의 신원으로 대체하지 않는다.
     *
     * @return 동일 worker 체인이 인증한 증명
     * @throws SecurityException 인증된 worker가 없으면 고정 거절
     */
    private static VerifiedWorker worker() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof VerifiedWorker worker))
            throw new SecurityException("WORKER_AUTH_REQUIRED");
        return worker;
    }

    /**
     * 실행 직전에 명시 서비스를 요구한다.
     *
     * @return 신뢰 호출자가 공급한 assembly
     * @throws IllegalStateException assembly 부재 또는 다중 조립이면 실행 불가
     */
    private Assembly assembly() {
        Assembly assembly = assemblies.getIfAvailable();
        if (assembly == null) throw new IllegalStateException("REMOTE_EXECUTION_UNAVAILABLE");
        return assembly;
    }

    /**
     * 클라이언트 값이 아닌 이력 필터 UUID만 읽는다.
     *
     * @param request 이력 필터를 통과한 요청
     * @return 현재 서버 요청 UUID
     * @throws IllegalStateException 필터 UUID가 없으면 조립 오류
     */
    private static UUID requestId(HttpServletRequest request) {
        return RequestAuditKernel.requestId(request);
    }
}
