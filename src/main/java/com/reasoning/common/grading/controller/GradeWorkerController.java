package com.reasoning.common.grading.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.security.GradeWorkerSecurityConfig;
import com.reasoning.common.grading.service.GradeCompletionService;
import com.reasoning.common.grading.service.GradeRecoveryService;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 명시 조립된 실제 서비스를 호출하며 별도 업무 TX·제공자·스케줄러를 만들지 않는다. */
@RestController
@RequestMapping("/internal/api/grading")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class GradeWorkerController {
    private final ObjectProvider<Assembly> assemblies;
    private final GradeWorkerRequestDecoder decoder = new GradeWorkerRequestDecoder();

    /** 신뢰 호출자의 명시 조립만 저장한다. registry 존재만으로 활성화되지 않으며 기본 신원·키는 없다. */
    public static final class Assembly {
        private final GradeWorkerCredentials credentials;
        private final GradeStartService start;
        private final GradeCompletionService completion;
        private final GradeRecoveryService recovery;
        private final GradeLeaseRepository testLeases;
        private final JdbcTemplate testJdbc;

        /**
         * 이전 BATCH 조립만 받으며 TEST 정책을 포함한 서비스는 복구 없는 활성화를 막기 위해 거절한다.
         *
         * @param credentials 실제 동일 registry, null 불가
         * @param start 실제 예약·관측 서비스, null 불가
         * @param completion 실제 완료 서비스, null 불가
         * @throws NullPointerException 필수 서비스가 누락된 경우
         * @throws IllegalArgumentException 실제 registry·DataSource·JDBC 관리자·설치 소유가 일치하지 않는 경우
         */
        public Assembly(
                GradeWorkerCredentials credentials,
                GradeStartService start,
                GradeCompletionService completion) {
            this.credentials = Objects.requireNonNull(credentials);
            this.start = Objects.requireNonNull(start);
            this.completion = Objects.requireNonNull(completion);
            this.recovery = null;
            this.testLeases = null;
            this.testJdbc = null;
            start.requireHttpAssembly(credentials, completion);
            start.requireBatchAssembly(completion);
        }

        /**
         * 명시 공급된 동일 실제 소유자로만 TEST 예약·완료·복구를 함께 구성한다. 빈 등록과 복구 호출은 수행하지 않는다.
         *
         * @param credentials 인증 체인이 사용하는 실제 worker 레지스트리, null 불가
         * @param jdbc 실제 저장소의 JDBC 도구, null 불가
         * @param manager 동일 DataSource의 JDBC 관리자, null 불가
         * @param installations 실제 configId별 설치 검증기, null 불가
         * @param policy 실제 PLAYTEST 정책 경계, null 불가
         * @param crypto 실제 보고서 암호화 경계, null 불가
         * @param trustedCoordinatorKey 감사의 신뢰 배포 식별자, null 불가
         * @return 모든 TEST 경계가 같은 실제 소유권을 가진 명시 조립
         * @throws IllegalArgumentException 소유권·구성 불일치
         * @throws IllegalStateException 외부 거래 안에서 조립한 경우
         */
        public static Assembly forTest(
                GradeWorkerCredentials credentials,
                JdbcTemplate jdbc,
                PlatformTransactionManager manager,
                Map<String, InstalledRuntimeManifestVerifier> installations,
                PlaytestPolicyGate policy,
                MemberPolicyGate memberPolicy,
                CryptoService crypto,
                String trustedCoordinatorKey) {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("TEST_ASSEMBLY_REQUIRES_SEPARATE_TRANSACTION");
            Objects.requireNonNull(credentials);
            Objects.requireNonNull(jdbc);
            Objects.requireNonNull(manager);
            Objects.requireNonNull(installations);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(memberPolicy);
            Objects.requireNonNull(crypto);
            Objects.requireNonNull(trustedCoordinatorKey);
            if (!(manager
                            instanceof
                            org.springframework.jdbc.datasource.DataSourceTransactionManager owner)
                    || jdbc.getDataSource() == null
                    || owner.getDataSource() != jdbc.getDataSource())
                throw new IllegalArgumentException("TEST_ASSEMBLY_MISMATCH");
            var start =
                    new GradeStartService(
                            jdbc,
                            manager,
                            credentials,
                            installations,
                            policy,
                            memberPolicy,
                            crypto);
            var completion =
                    new GradeCompletionService(
                            jdbc,
                            manager,
                            credentials,
                            installations,
                            crypto,
                            trustedCoordinatorKey,
                            policy,
                            memberPolicy);
            var recovery =
                    new GradeRecoveryService(
                            jdbc,
                            manager,
                            credentials,
                            installations,
                            trustedCoordinatorKey,
                            policy,
                            memberPolicy,
                            crypto);
            start.requireHttpAssembly(credentials, completion);
            start.requireTestAssembly(
                    credentials, jdbc.getDataSource(), manager, installations, policy, crypto);
            completion.requireTestAssembly(
                    credentials,
                    jdbc.getDataSource(),
                    manager,
                    installations,
                    policy,
                    crypto,
                    trustedCoordinatorKey);
            recovery.requireTestAssembly(
                    credentials,
                    jdbc.getDataSource(),
                    manager,
                    installations,
                    policy,
                    memberPolicy,
                    crypto,
                    trustedCoordinatorKey);
            return new Assembly(
                    credentials,
                    start,
                    completion,
                    recovery,
                    new GradeLeaseRepository(jdbc, manager),
                    jdbc);
        }

        private Assembly(
                GradeWorkerCredentials credentials,
                GradeStartService start,
                GradeCompletionService completion,
                GradeRecoveryService recovery,
                GradeLeaseRepository testLeases,
                JdbcTemplate testJdbc) {
            this.credentials = credentials;
            this.start = start;
            this.completion = completion;
            this.recovery = recovery;
            this.testLeases = testLeases;
            this.testJdbc = testJdbc;
        }

        /**
         * @return 실제 TEST 삼중 조립이 완료된 경우에만 참
         */
        public boolean supportsTest() {
            return recovery != null;
        }

        /**
         * @return 명시 TEST 조립의 복구 서비스; BATCH 조립에는 null, 호출·스케줄링은 소유자 책임
         */
        public GradeRecoveryService testRecovery() {
            return recovery;
        }

        public GradeWorkerCredentials credentials() {
            return credentials;
        }

        public GradeStartService start() {
            return start;
        }

        public GradeCompletionService completion() {
            return completion;
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
     * 인증된 worker에게 허용된 runtime의 TEST 작업만 할당한다. 복구는 임대 거래 밖에서 먼저 수행한다.
     *
     * @param runtimeCode 등록된 대문자 runtime 코드, null 불가
     * @param request body·query 없는 보안 체인 요청, null 불가
     * @return TEST 임대 세 필드; 작업이 없거나 소유 중이면 204
     */
    @PostMapping("/poll/{runtimeCode}")
    public ResponseEntity<ObjectNode> poll(
            @PathVariable("runtimeCode") String runtimeCode, HttpServletRequest request) {
        ResponseEntity<ObjectNode> response =
                invoke(
                        request,
                        () -> {
                            if (request.getQueryString() != null
                                    || request.getHeader("Content-Encoding") != null
                                    || !runtimeCode.matches("[A-Z0-9_]{1,80}"))
                                throw new IllegalArgumentException("INVALID_REMOTE_REQUEST");
                            try {
                                if (request.getInputStream().read() != -1)
                                    throw new IllegalArgumentException("INVALID_REMOTE_REQUEST");
                            } catch (IOException failure) {
                                throw new IllegalArgumentException("INVALID_REMOTE_REQUEST");
                            }
                            Assembly ready = assembly();
                            if (!ready.supportsTest())
                                throw new IllegalStateException("TEST_NOT_CONFIGURED");
                            VerifiedWorker verified = worker();
                            for (Action action :
                                    new Action[] {
                                        Action.CLAIM, Action.START, Action.RENEW, Action.COMPLETE
                                    })
                                ready.credentials()
                                        .requirePermission(verified, action, runtimeCode);
                            // 인증된 runtime만 복구한다. 실패는 503/접근 감사로 관측되며 다른 runtime은
                            // 명시 coordinator 복구 경계에 남긴다.
                            ready.recovery.recoverExpiredTestRuntime(runtimeCode, 20);
                            // 이미 실행 중인 소유자에게 저장소의 기존 임대 재전송을 노출하지 않는다.
                            if (!ready.testJdbc
                                    .queryForList(
                                            "SELECT id FROM public.grade_job WHERE state='RUNNING'"
                                                    + " AND worker_key=? LIMIT 1",
                                            Long.class,
                                            verified.workerKey())
                                    .isEmpty()) return null;
                            return ready.testLeases
                                    .claimTest(verified.workerKey(), runtimeCode)
                                    .map(
                                            lease ->
                                                    JsonNodeFactory.instance
                                                            .objectNode()
                                                            .put(
                                                                    "jobKey",
                                                                    lease.jobKey().toString())
                                                            .put("leaseGen", lease.leaseGen())
                                                            .put(
                                                                    "deadline",
                                                                    lease.deadlineAt()
                                                                            .toInstant()
                                                                            .toString()))
                                    .orElse(null);
                        });
        return response.getStatusCode().value() == 200 && response.getBody() == null
                ? ResponseEntity.noContent().header("Cache-Control", "no-store").build()
                : response;
    }

    /**
     * 실제 신규 시도 예약 또는 입력 없는 REPLAY를 반환한다.
     *
     * @param jobKey canonical 비영 UUID v4 경로
     * @param request 인증 체인을 통과한 8KiB 이하 UTF-8 JSON
     * @return 정확한 StartReply 또는 원문 없는 고정 오류
     */
    @PostMapping("/jobs/{jobKey}/start")
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
    @PostMapping("/jobs/{jobKey}/before-chat")
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
    @PostMapping("/jobs/{jobKey}/renew")
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
    @PostMapping("/jobs/{jobKey}/complete")
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
            ObjectNode body = operation.get();
            if (body == null)
                return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
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
