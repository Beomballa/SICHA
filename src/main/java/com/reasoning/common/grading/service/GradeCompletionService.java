package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.model.GradeModels.BaseResult;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeEventRepository;
import com.reasoning.common.grading.repository.GradeEventRepository.AttemptState;
import com.reasoning.common.grading.repository.GradeEventRepository.Detail;
import com.reasoning.common.grading.repository.GradeEventRepository.EventKind;
import com.reasoning.common.grading.repository.GradeEventRepository.JobState;
import com.reasoning.common.grading.repository.GradeEventRepository.Reason;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository.LockedSource;
import com.reasoning.common.grading.repository.GradeSourceRepository.LockedTestSource;
import com.reasoning.common.grading.repository.GradeSourceRepository.TestPreparation;
import com.reasoning.common.grading.repository.GradeSourceRepository.TestRootEvidence;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.FrozenDatasetValidator.SelectedSample;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 영속 예약의 내부 완료 경계다. HTTP·모델 호출·복구·coordinator 또는 품질 승인 기능은 제공하지 않는다. */
public final class GradeCompletionService {
    private static final Set<String> ERRORS =
            Set.of(
                    "ENGINE_TIMEOUT",
                    "ENGINE_UNAVAILABLE",
                    "INVALID_OUTPUT",
                    "UNRESOLVED_REASONING",
                    "WORKER_LOST");
    private static final ObjectMapper INTERNAL = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final GradeWorkerCredentials credentials;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final PlatformTransactionManager manager;
    private final String coordinatorKey;
    private final CryptoService crypto;
    private final GradeSourceRepository sources;
    private final GradeRuntimeRepository runtimes;
    private final GradeFrozenInputRepository frozen;
    private final GradeEventRepository audit;
    private final PlaytestPolicyGate testPolicy;
    private final TransactionTemplate transaction;

    /** 동일 실제 레지스트리·DataSource 조립인지 예약 전에 확인하며 비밀 getter는 제공하지 않는다. */
    void requireAssembly(GradeWorkerCredentials registry, javax.sql.DataSource source) {
        if (credentials != registry || jdbc.getDataSource() != source)
            throw new IllegalArgumentException("RUNNER_ASSEMBLY_MISMATCH");
    }

    /**
     * 같은 HTTP 조립의 소유를 대조하며 기존 로컬/JPA 조립 계약은 변경하지 않는다.
     *
     * @param registry 실제 동일 인증 registry, null 불가
     * @param source START와 동일 DataSource 인스턴스, null 불가
     * @param expectedManager START와 동일 JDBC TX 관리자, null 불가
     * @param expectedInstallations START가 소유한 실제 불변 설치 map, null 불가
     * @throws IllegalArgumentException 동일 소유 또는 JDBC 관리자 불일치의 고정 오류
     */
    void requireHttpAssembly(
            GradeWorkerCredentials registry,
            javax.sql.DataSource source,
            PlatformTransactionManager expectedManager,
            Map<String, InstalledRuntimeManifestVerifier> expectedInstallations) {
        if (credentials != registry
                || jdbc.getDataSource() != source
                || manager != expectedManager
                || !(transaction.getTransactionManager()
                        instanceof
                        org.springframework.jdbc.datasource.DataSourceTransactionManager manager)
                || manager.getDataSource() != source
                || !sameInstallations(expectedInstallations))
            throw new IllegalArgumentException("REMOTE_ASSEMBLY_MISMATCH");
    }

    /** 이전 BATCH 조립에는 TEST 완료 권위를 허용하지 않는다. */
    public void requireBatchAssembly() {
        if (testPolicy != null) throw new IllegalArgumentException("REMOTE_ASSEMBLY_MISMATCH");
    }

    /** TEST 서비스가 같은 실제 암호화·정책·coordinator를 소유하는지 대조한다. */
    public void requireTestAssembly(
            GradeWorkerCredentials registry,
            javax.sql.DataSource source,
            PlatformTransactionManager expectedManager,
            Map<String, InstalledRuntimeManifestVerifier> expectedInstallations,
            PlaytestPolicyGate expectedPolicy,
            CryptoService expectedCrypto,
            String expectedCoordinator) {
        requireHttpAssembly(registry, source, expectedManager, expectedInstallations);
        if (testPolicy == null
                || testPolicy != expectedPolicy
                || crypto != expectedCrypto
                || !coordinatorKey.equals(expectedCoordinator))
            throw new IllegalArgumentException("TEST_ASSEMBLY_MISMATCH");
    }

    /** 동일 configId라도 다른 설치 객체로 교체한 조립은 허용하지 않는다. */
    private boolean sameInstallations(Map<String, InstalledRuntimeManifestVerifier> expected) {
        return expected != null
                && installations.size() == expected.size()
                && installations.entrySet().stream()
                        .allMatch(entry -> entry.getValue() == expected.get(entry.getKey()));
    }

    /** 원래 완료 명령과 다른 replay만 구분하는 고정 오류이며 저장·조립 실패를 대신하지 않는다. */
    public static final class CallbackConflictException extends IllegalStateException {
        private CallbackConflictException() {
            super("CALLBACK_CONFLICT");
        }
    }

    /**
     * 신뢰 배포가 이미 SQL 밖에서 생성한 실제 설치만 고정한다. 자동 서비스 등록·키 생성·기본 권한은 없다.
     *
     * @param jdbc 같은 DataSource의 JDBC 도구, null 불가
     * @param manager 같은 DataSource의 TX 관리자, null 불가
     * @param credentials 실제 worker 레지스트리, null 불가
     * @param installations 실제 manifest configId를 키로 하는 설치, null 키·값 불가
     * @param crypto 기존 개인 키 파일로 구성한 암호 서비스, null 불가
     * @param trustedCoordinatorKey 감사 저장소의 신뢰 배포 coordinator ASCII 키, null 불가
     * @throws IllegalArgumentException 설치 식별자 불일치 시 고정 입력 오류
     * @throws IllegalStateException 활성 SQL TX 안에서 조립한 경우 고정 경계 오류
     */
    public GradeCompletionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            GradeWorkerCredentials credentials,
            Map<String, InstalledRuntimeManifestVerifier> installations,
            CryptoService crypto,
            String trustedCoordinatorKey) {
        this(jdbc, manager, credentials, installations, crypto, trustedCoordinatorKey, null, null);
    }

    /** TEST 완료는 같은 PLAYTEST 정책 증거를 별도로 받으며 기존 생성자는 BATCH 전용으로 둔다. */
    public GradeCompletionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            GradeWorkerCredentials credentials,
            Map<String, InstalledRuntimeManifestVerifier> installations,
            CryptoService crypto,
            String trustedCoordinatorKey,
            PlaytestPolicyGate testPolicy,
            MemberPolicyGate memberPolicy) {
        if ((testPolicy == null) != (memberPolicy == null))
            throw new IllegalArgumentException("INVALID_TEST_COMPLETION_ASSEMBLY");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw boundary();
        this.jdbc = Objects.requireNonNull(jdbc);
        this.manager = Objects.requireNonNull(manager);
        this.coordinatorKey = Objects.requireNonNull(trustedCoordinatorKey);
        this.credentials = Objects.requireNonNull(credentials);
        this.installations = Map.copyOf(Objects.requireNonNull(installations));
        this.crypto = Objects.requireNonNull(crypto);
        this.testPolicy = testPolicy;
        this.installations.forEach(
                (code, verifier) -> {
                    if (!code.equals(verifier.registrationManifest().path("configId").textValue()))
                        throw invalid();
                });
        sources = new GradeSourceRepository(jdbc, memberPolicy);
        runtimes = new GradeRuntimeRepository(jdbc);
        frozen = new GradeFrozenInputRepository(jdbc);
        audit = new GradeEventRepository(jdbc, credentials, trustedCoordinatorKey);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(false);
        transaction.setTimeout(5);
    }

    /**
     * 실제 예약 소유자만 완료하며 원문·서버 계산 결과·감사·영수증을 한 TX에서 저장한다. 동일 명령 재전송은 현재 권한 검사 후 최초 영수증을 그대로 반환한다.
     *
     * @param worker 이 레지스트리가 실제 인증한 증명, null 불가
     * @param jobKey 비영 작업 UUID, null 불가
     * @param leaseGen 양수 예약 세대
     * @param attemptNo 예약 번호 1~3
     * @param observedProviderVersion 관측값, null 허용, 최대 160 코드포인트; provider 증명은 아님
     * @param providerResponseRef 비개인 ASCII 참조 8~120자, null 허용
     * @param resultJson 닫힌 COMPLETE·UNRESOLVED·ERROR JSON, null 불가; 전송 바이트 상한은 HTTP 책임
     * @param requestId 서버 생성 비영 UUID, null 불가; 명령 식별 해시에 포함하지 않음
     * @return 커밋 뒤에만 반환하는 원문 없는 불변 영수증
     * @throws IllegalArgumentException 원인 없는 INVALID_COMPLETION_INPUT
     * @throws SecurityException 실제 worker·COMPLETE·runtime 권한 또는 예약 소유자 불일치
     * @throws CallbackConflictException 기존 완료 명령과 다른 replay
     * @throws IllegalStateException 외부 TX·실제 연결 증명 오류
     * @throws DataAccessResourceFailureException 원인 없는 COMPLETION_STORAGE_FAILURE
     */
    public CompletionReceipt complete(
            VerifiedWorker worker,
            UUID jobKey,
            long leaseGen,
            int attemptNo,
            String observedProviderVersion,
            String providerResponseRef,
            String resultJson,
            UUID requestId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw boundary();
        if (worker == null
                || !uuid(jobKey)
                || leaseGen <= 0
                || attemptNo < 1
                || attemptNo > 3
                || !uuid(requestId)
                || (observedProviderVersion != null
                        && observedProviderVersion.codePointCount(
                                        0, observedProviderVersion.length())
                                > 160)
                || (providerResponseRef != null
                        && !providerResponseRef.matches("[A-Za-z0-9_./-]{8,120}"))) throw invalid();
        JsonNode result = result(resultJson);
        ObjectNode command =
                object().put("jobKey", jobKey.toString())
                        .put("leaseGen", leaseGen)
                        .put("attemptNo", attemptNo)
                        .put("observedProviderVersion", observedProviderVersion)
                        .put("providerResponseRef", providerResponseRef);
        command.set("result", result);
        String commandHash;
        try {
            commandHash = SnapshotJson.hash(command);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
        try {
            var codes =
                    jdbc.queryForList(
                            """
                            SELECT r.code FROM public.grade_job j JOIN public.grade_runtime r ON r.id=j.runtime_id
                            WHERE j.job_key=?
                            """,
                            String.class,
                            jobKey);
            if (codes.size() != 1) throw invalid();
            credentials.requirePermission(worker, Action.COMPLETE, codes.getFirst());
            var kinds =
                    jdbc.queryForList(
                            "SELECT report_id FROM public.grade_job WHERE job_key=?",
                            Long.class,
                            jobKey);
            if (kinds.size() != 1) throw invalid();
            if (kinds.getFirst() != null) {
                if (testPolicy == null)
                    throw new IllegalStateException("TEST_COMPLETION_NOT_CONFIGURED");
                CompletionReceipt inspected =
                        transaction.execute(
                                status ->
                                        inspectTest(
                                                worker,
                                                jobKey,
                                                leaseGen,
                                                attemptNo,
                                                requestId,
                                                commandHash,
                                                false));
                if (inspected != null) return inspected;
                try {
                    var prepared = sources.prepareTest(testPolicy);
                    return transaction.execute(
                            status ->
                                    applyTest(
                                            worker,
                                            jobKey,
                                            leaseGen,
                                            attemptNo,
                                            observedProviderVersion,
                                            providerResponseRef,
                                            result,
                                            requestId,
                                            commandHash,
                                            prepared));
                } catch (AuthException failure) {
                    return transaction.execute(
                            status ->
                                    inspectTest(
                                            worker,
                                            jobKey,
                                            leaseGen,
                                            attemptNo,
                                            requestId,
                                            commandHash,
                                            true));
                } catch (IllegalStateException failure) {
                    if (!"SOURCE_NOT_CURRENT".equals(failure.getMessage())) throw failure;
                    return transaction.execute(
                            status ->
                                    inspectTest(
                                            worker,
                                            jobKey,
                                            leaseGen,
                                            attemptNo,
                                            requestId,
                                            commandHash,
                                            true));
                }
            }
            return transaction.execute(
                    status ->
                            apply(
                                    worker,
                                    jobKey,
                                    leaseGen,
                                    attemptNo,
                                    observedProviderVersion,
                                    providerResponseRef,
                                    result,
                                    requestId,
                                    commandHash));
        } catch (DataAccessException | TransactionException failure) {
            throw storage();
        } catch (IllegalStateException failure) {
            if ("GRADE_AUDIT_STORAGE_FAILURE".equals(failure.getMessage())) throw storage();
            throw failure;
        }
    }

    /** 역사 루트는 현재 실행 권한 없이 원래 worker·시도와 저장된 명령만 검사한다. 신규 정산은 별도 거래에서 수행한다. */
    private CompletionReceipt inspectTest(
            VerifiedWorker worker,
            UUID key,
            long gen,
            int number,
            UUID request,
            String hash,
            boolean unavailable) {
        LockedTestSource locked = sources.lockTestRootsForSettlement(key);
        TestRootEvidence root = locked.lockedFacts(jdbc.getDataSource());
        credentials.requirePermission(worker, Action.COMPLETE, root.runtimeCode());
        var attempts =
                jdbc.queryForList(
                        """
                        SELECT worker_key,lease_gen,state,completion_data::text AS completion
                        FROM public.grade_attempt WHERE job_id=? AND attempt_no=? FOR UPDATE
                        """,
                        root.jobId(),
                        number);
        if (attempts.size() != 1) throw invalid();
        Map<String, Object> attempt = attempts.getFirst();
        if (!worker.workerKey().equals(attempt.get("worker_key")))
            throw new SecurityException("WORKER_NOT_AUTHORIZED");
        if (attempt.get("completion") != null) {
            JsonNode envelope = stored((String) attempt.get("completion"));
            if (!hash.equals(envelope.path("commandHash").textValue()))
                throw new CallbackConflictException();
            return receipt(envelope.path("receipt"), key, number);
        }
        Job job = job(root.jobId());
        Reason reason =
                gen != number(attempt, "lease_gen")
                        ? Reason.STALE_LEASE
                        : testRejection(job, attempt, worker, gen, number, now());
        if (reason == null) reason = testEpochRejection(root);
        if (reason != null || unavailable)
            return rejectTest(
                    worker,
                    root,
                    job,
                    attempt,
                    gen,
                    number,
                    request,
                    hash,
                    reason == null ? Reason.SOURCE_REVOKED : reason);
        return null;
    }

    /**
     * 실제 상위 루트 다음에 요청한 시도 하나만 잠그고 영수증 재생을 신규 결과 검증보다 먼저 처리한다.
     *
     * @param worker 실제 인증 증명, null 불가
     * @param key 비영 작업 UUID, null 불가
     * @param gen 양수 임대 세대
     * @param number 시도 번호 1~3
     * @param observed 실제 관측 0~160 코드포인트, null 허용
     * @param reference 비개인 ASCII 참조 8~120자, null 허용
     * @param result 닫힌 원본 결과, null 불가
     * @param request 서버 요청 UUID, null 불가
     * @param hash 정규 명령 SHA-256 64자, null 불가
     * @return 최초 또는 정확히 재생한 안전 영수증
     * @throws IllegalArgumentException 입력·보고서 계약 불일치 시 고정 오류
     * @throws CallbackConflictException 기존 완료 명령과 다른 replay
     * @throws IllegalStateException 실제 연결·설치 증명 오류
     * @throws DataAccessResourceFailureException 쓰기·트리거·감사 실패 시 원인 없는 저장 오류
     */
    private CompletionReceipt apply(
            VerifiedWorker worker,
            UUID key,
            long gen,
            int number,
            String observed,
            String reference,
            JsonNode result,
            UUID request,
            String hash) {
        LockedSource root = sources.lockRoots(key);
        credentials.requirePermission(worker, Action.COMPLETE, root.runtimeCode());
        var attempts =
                jdbc.queryForList(
                        """
                        SELECT worker_key,lease_gen,state,completion_data::text AS completion
                        FROM public.grade_attempt WHERE job_id=? AND attempt_no=? FOR UPDATE
                        """,
                        root.jobId(),
                        number);
        if (attempts.size() != 1) throw invalid();
        Map<String, Object> attempt = attempts.getFirst();
        if (!worker.workerKey().equals(attempt.get("worker_key")))
            throw new SecurityException("WORKER_NOT_AUTHORIZED");
        if (attempt.get("completion") != null) {
            JsonNode envelope = stored((String) attempt.get("completion"));
            if (!hash.equals(envelope.path("commandHash").textValue()))
                throw new CallbackConflictException();
            return receipt(envelope.path("receipt"), key, number);
        }
        if (gen != number(attempt, "lease_gen")) throw invalid();
        Job job = job(root.jobId());
        Reason rejection = rejection(root, job, attempt, worker, gen, number, now());
        if (rejection != null)
            return reject(worker, root, job, attempt, gen, number, request, hash, rejection);

        var installation = installations.get(root.runtimeCode());
        if (installation == null) throw new IllegalStateException("INSTALLED_RUNTIME_MISMATCH");
        var registered =
                runtimes.getRuntimeDetail(root.runtimeId())
                        .orElseThrow(GradeCompletionService::invalid);
        var verified = installation.verify(registered);
        var dataset = frozen.dataset(root, verified);
        SelectedSample selected = dataset.select(root.sampleCode());
        if (selected.report() == null || "INPUT_ERROR".equals(selected.kind())) throw invalid();

        BaseResult base = null;
        String error =
                "COMPLETE".equals(result.path("kind").textValue())
                        ? null
                        : result.path("errorCode").textValue();
        if (error == null) {
            try {
                var semantic =
                        new GradeResultValidator()
                                .validate(
                                        text(result.get("semantic")),
                                        selected.report(),
                                        dataset.gradingSnapshot());
                base =
                        new GradeCalculator()
                                .calculate(selected.report(), dataset.gradingSnapshot(), semantic);
            } catch (IllegalArgumentException failure) {
                throw invalid();
            }
        }
        boolean providerVersionMatched =
                observed != null
                        && observed.equals(
                                installation
                                        .registrationManifest()
                                        .path("modelVersion")
                                        .textValue());
        ObjectNode summary = base == null ? null : summary(base, selected, providerVersionMatched);
        ObjectNode privateResult = null;
        if (base != null) {
            privateResult = object().put("formatNo", 1);
            privateResult.set("baseResult", INTERNAL.valueToTree(base));
        }
        String outputHash = base == null ? null : SnapshotJson.hash(result.get("semantic"));
        String resultHash = privateResult == null ? null : SnapshotJson.hash(privateResult);
        byte[] outputCipher =
                base == null
                        ? null
                        : bytes(
                                crypto.encrypt(
                                        text(result.get("semantic")),
                                        "grade_attempt/"
                                                + root.jobId()
                                                + "/"
                                                + number
                                                + "/output/v1"));
        byte[] resultCipher =
                privateResult == null
                        ? null
                        : bytes(
                                crypto.encrypt(
                                        text(privateResult),
                                        "grade_job/" + root.jobId() + "/result/v1"));

        // 모든 읽기·검증·계산·암호화 뒤 마지막 실제 DB 시계를 사용한다. 예산·마감·세대는 변경하지 않는다.
        Job current = job(root.jobId());
        OffsetDateTime now = now();
        rejection = rejection(root, current, attempt, worker, gen, number, now);
        if (rejection != null)
            return reject(worker, root, current, attempt, gen, number, request, hash, rejection);
        now = now();
        rejection = timeRejection(root, current, now);
        if (rejection != null)
            return reject(worker, root, current, attempt, gen, number, request, hash, rejection);
        JobState after =
                base != null ? JobState.COMPLETED : number < 3 ? JobState.QUEUED : JobState.FAILED;
        AttemptState afterAttempt = base != null ? AttemptState.SUCCEEDED : AttemptState.FAILED;
        Reason reason = error == null ? Reason.NONE : Reason.valueOf(error);
        CompletionReceipt receipt =
                new CompletionReceipt(
                        key,
                        number,
                        true,
                        after.name(),
                        after == JobState.QUEUED,
                        base != null
                                ? "COMPLETE"
                                : after == JobState.FAILED ? "SYSTEM_ERROR" : null,
                        reason.name(),
                        request);
        updateAttempt(
                root,
                worker,
                gen,
                number,
                afterAttempt,
                error,
                observed,
                reference,
                outputHash,
                outputCipher);
        updateJob(
                root,
                worker,
                gen,
                number,
                current,
                after,
                error,
                summary,
                resultHash,
                resultCipher,
                now);
        long event =
                audit.recordWorkerEvent(
                        worker,
                        root.jobId(),
                        number,
                        EventKind.COMPLETE_APPLIED,
                        UUID.randomUUID(),
                        request,
                        hash,
                        new Detail(
                                gen,
                                JobState.RUNNING,
                                after,
                                AttemptState.RUNNING,
                                afterAttempt,
                                reason,
                                outputHash,
                                resultHash));
        ObjectNode envelope = object().put("auditEventId", event).put("commandHash", hash);
        envelope.set("receipt", receiptJson(receipt));
        int saved =
                jdbc.update(
                        """
                        UPDATE public.grade_attempt SET completion_data=?::jsonb
                        WHERE job_id=? AND attempt_no=? AND worker_key=? AND lease_gen=? AND state=?
                            AND completion_data IS NULL AND clock_timestamp()<? AND clock_timestamp()<?
                            AND clock_timestamp()<?
                        """,
                        text(envelope),
                        root.jobId(),
                        number,
                        worker.workerKey(),
                        gen,
                        afterAttempt.name(),
                        current.leaseUntil(),
                        current.deadlineAt(),
                        root.batchCreatedAt().plusHours(24));
        if (saved != 1) throw storage();
        // 감사 또는 영수증 트리거 지연도 커밋 전에 엄격히 검사하며 부분 완료를 허용하지 않는다.
        requireStoredOutcome(
                root,
                number,
                error,
                observed,
                reference,
                outputHash,
                outputCipher,
                resultHash,
                resultCipher,
                summary,
                envelope);
        installation.verify(
                runtimes.getRuntimeDetail(root.runtimeId())
                        .orElseThrow(GradeCompletionService::storage));
        frozen.dataset(root, verified);
        requirePostWrite(root, current, after, afterAttempt, worker, gen, number);
        return receipt;
    }

    /** TEST는 같은 예약·명령 영수증을 사용하지만 보고서와 플레이 부모만 잠그고 정산한다. */
    private CompletionReceipt applyTest(
            VerifiedWorker worker,
            UUID key,
            long gen,
            int number,
            String observed,
            String reference,
            JsonNode result,
            UUID request,
            String hash,
            TestPreparation prepared) {
        LockedTestSource locked = sources.lockTestRoots(key, testPolicy, prepared);
        TestRootEvidence root = locked.lockedFacts(jdbc.getDataSource());
        credentials.requirePermission(worker, Action.COMPLETE, root.runtimeCode());
        var attempts =
                jdbc.queryForList(
                        """
                        SELECT worker_key,lease_gen,state,completion_data::text AS completion
                        FROM public.grade_attempt WHERE job_id=? AND attempt_no=? FOR UPDATE
                        """,
                        root.jobId(),
                        number);
        if (attempts.size() != 1) throw invalid();
        Map<String, Object> attempt = attempts.getFirst();
        if (!worker.workerKey().equals(attempt.get("worker_key")))
            throw new SecurityException("WORKER_NOT_AUTHORIZED");
        Job before = job(root.jobId());
        if (attempt.get("completion") != null) {
            JsonNode envelope = stored((String) attempt.get("completion"));
            if (!hash.equals(envelope.path("commandHash").textValue()))
                throw new CallbackConflictException();
            return receipt(envelope.path("receipt"), key, number);
        }
        Reason rejection =
                gen != number(attempt, "lease_gen")
                        ? Reason.STALE_LEASE
                        : testRejection(before, attempt, worker, gen, number, now());
        if (rejection != null)
            return rejectTest(worker, root, before, attempt, gen, number, request, hash, rejection);
        rejection = testEpochRejection(root);
        if (rejection != null)
            return rejectTest(worker, root, before, attempt, gen, number, request, hash, rejection);
        try {
            sources.requireTestExecutionEligibility(locked);
        } catch (IllegalStateException failure) {
            if ("SOURCE_NOT_CURRENT".equals(failure.getMessage()))
                return rejectTest(
                        worker,
                        root,
                        before,
                        attempt,
                        gen,
                        number,
                        request,
                        hash,
                        Reason.SOURCE_REVOKED);
            throw failure;
        }
        var priorCounters =
                jdbc.queryForMap(
                        "SELECT attempt_count,wrong_count FROM public.play_test WHERE id=?",
                        root.testId());
        int normalAttempts = ((Number) priorCounters.get("attempt_count")).intValue();
        int wrongAttempts = ((Number) priorCounters.get("wrong_count")).intValue();

        var installation = installations.get(root.runtimeCode());
        if (installation == null) throw new IllegalStateException("INSTALLED_RUNTIME_MISMATCH");
        var registered =
                runtimes.getRuntimeDetail(root.runtimeId())
                        .orElseThrow(GradeCompletionService::invalid);
        var verified = installation.verify(registered);
        GradeFrozenInputRepository.TestGradeInput input;
        try {
            input = frozen.verifiedTestInput(locked, verified, crypto);
        } catch (IllegalStateException failure) {
            if ("TEST_INPUT_NOT_CURRENT".equals(failure.getMessage()))
                return rejectTest(
                        worker,
                        root,
                        before,
                        attempt,
                        gen,
                        number,
                        request,
                        hash,
                        Reason.SOURCE_REVOKED);
            throw failure;
        }
        JsonNode policy = input.frozenSnapshot().payload().path("policy");
        JsonNode limitValue = policy.path("attemptLimit");
        JsonNode penaltyValue = policy.path("wrongPenalty");
        if (!limitValue.isIntegralNumber()
                || !limitValue.canConvertToInt()
                || limitValue.intValue() < 1
                || limitValue.intValue() > 5
                || !penaltyValue.isIntegralNumber()
                || !penaltyValue.canConvertToInt()
                || penaltyValue.intValue() < 0
                || penaltyValue.intValue() > 100) throw invalid();
        int attemptLimit = limitValue.intValue();
        int wrongPenalty = penaltyValue.intValue();
        BaseResult base = null;
        String error =
                "COMPLETE".equals(result.path("kind").textValue())
                        ? null
                        : result.path("errorCode").textValue();
        if (error == null) {
            try {
                var semantic =
                        new GradeResultValidator()
                                .validate(
                                        text(result.get("semantic")),
                                        input.report(),
                                        input.snapshot());
                base = new GradeCalculator().calculate(input.report(), input.snapshot(), semantic);
            } catch (IllegalArgumentException failure) {
                throw invalid();
            }
        }
        ObjectNode privateResult = null;
        ObjectNode summary = null;
        if (base != null) {
            privateResult = object().put("formatNo", 1);
            privateResult.set("baseResult", INTERNAL.valueToTree(base));
            summary =
                    object().put("formatNo", 1)
                            .put("baseScore", base.baseScore())
                            .put("success", base.success());
        }
        String outputHash = base == null ? null : SnapshotJson.hash(result.get("semantic"));
        String resultHash = privateResult == null ? null : SnapshotJson.hash(privateResult);
        byte[] outputCipher =
                base == null
                        ? null
                        : bytes(
                                crypto.encrypt(
                                        text(result.get("semantic")),
                                        "grade_attempt/"
                                                + root.jobId()
                                                + "/"
                                                + number
                                                + "/output/v1"));
        byte[] resultCipher =
                privateResult == null
                        ? null
                        : bytes(
                                crypto.encrypt(
                                        text(privateResult),
                                        "grade_job/" + root.jobId() + "/result/v1"));

        Job current = job(root.jobId());
        OffsetDateTime clock = now();
        rejection = testRejection(current, attempt, worker, gen, number, clock);
        if (rejection != null)
            return rejectTest(
                    worker, root, current, attempt, gen, number, request, hash, rejection);
        rejection = testEpochRejection(root);
        if (rejection != null)
            return rejectTest(
                    worker, root, current, attempt, gen, number, request, hash, rejection);
        try {
            sources.requireTestExecutionEligibility(locked);
        } catch (IllegalStateException failure) {
            if ("SOURCE_NOT_CURRENT".equals(failure.getMessage()))
                return rejectTest(
                        worker,
                        root,
                        current,
                        attempt,
                        gen,
                        number,
                        request,
                        hash,
                        Reason.SOURCE_REVOKED);
            throw failure;
        }
        installation.verify(
                runtimes.getRuntimeDetail(root.runtimeId())
                        .orElseThrow(GradeCompletionService::storage));
        try {
            frozen.verifiedTestInput(locked, verified, crypto);
        } catch (IllegalStateException failure) {
            if ("TEST_INPUT_NOT_CURRENT".equals(failure.getMessage()))
                return rejectTest(
                        worker,
                        root,
                        current,
                        attempt,
                        gen,
                        number,
                        request,
                        hash,
                        Reason.SOURCE_REVOKED);
            throw failure;
        }
        clock = now();
        rejection = testTimeRejection(current, clock);
        if (rejection != null)
            return rejectTest(
                    worker, root, current, attempt, gen, number, request, hash, rejection);

        JobState after =
                base != null ? JobState.COMPLETED : number < 3 ? JobState.QUEUED : JobState.FAILED;
        AttemptState attemptState = base != null ? AttemptState.SUCCEEDED : AttemptState.FAILED;
        Reason reason = error == null ? Reason.NONE : Reason.valueOf(error);
        String outcome =
                base != null ? "COMPLETE" : after == JobState.FAILED ? "SYSTEM_ERROR" : null;
        CompletionReceipt receipt =
                new CompletionReceipt(
                        key,
                        number,
                        true,
                        after.name(),
                        after == JobState.QUEUED,
                        outcome,
                        reason.name(),
                        request);

        var savedAttempt =
                jdbc.query(
                        """
                        UPDATE public.grade_attempt a SET state=?,ended_at=clock_timestamp(),error_code=?,
                            observed_version=?,provider_ref=?,output_hash=?,output_cipher=?
                        WHERE a.job_id=? AND a.attempt_no=? AND a.worker_key=? AND a.lease_gen=?
                            AND a.state='RUNNING' AND a.completion_data IS NULL
                            AND EXISTS(SELECT 1 FROM public.grade_job j WHERE j.id=a.job_id
                                AND j.state='RUNNING' AND j.worker_key=? AND j.lease_gen=? AND j.call_count=?
                                AND clock_timestamp()<j.lease_until AND clock_timestamp()<j.deadline_at)
                        RETURNING ended_at
                        """,
                        (row, index) -> row.getObject(1, OffsetDateTime.class),
                        attemptState.name(),
                        error,
                        observed,
                        reference,
                        outputHash,
                        outputCipher,
                        root.jobId(),
                        number,
                        worker.workerKey(),
                        gen,
                        worker.workerKey(),
                        gen,
                        number);
        if (savedAttempt.size() != 1
                || !savedAttempt.getFirst().isBefore(current.leaseUntil())
                || !savedAttempt.getFirst().isBefore(current.deadlineAt())) throw storage();

        int changed =
                jdbc.update(
                        """
                        UPDATE public.grade_job SET state=?,worker_key=NULL,lease_until=NULL,error_code=?,
                            result_data=?::jsonb,result_hash=?,result_cipher=?,next_run_at=?,updated_at=clock_timestamp()
                        WHERE id=? AND job_key=? AND report_id=? AND report_hash=? AND batch_id IS NULL
                            AND snapshot_id=? AND runtime_id=? AND config_hash=? AND rubric_hash=?
                            AND state='RUNNING' AND worker_key=? AND lease_gen=? AND call_count=?
                            AND accepted_at=? AND deadline_at=?
                            AND clock_timestamp()<lease_until AND clock_timestamp()<deadline_at
                        """,
                        after.name(),
                        error,
                        summary == null ? null : text(summary),
                        resultHash,
                        resultCipher,
                        clock,
                        root.jobId(),
                        key,
                        root.reportId(),
                        root.reportHash(),
                        root.snapshotId(),
                        root.runtimeId(),
                        root.configHash(),
                        root.rubricHash(),
                        worker.workerKey(),
                        gen,
                        number,
                        current.acceptedAt(),
                        current.deadlineAt());
        if (changed != 1) throw storage();

        String testOutcome = null;
        int score = -1;
        if (base != null) {
            var tests =
                    jdbc.query(
                            """
                            SELECT attempt_count,wrong_count,deadline_at,state FROM public.play_test WHERE id=?
                            """,
                            (row, index) ->
                                    Map.<String, Object>of(
                                            "attempt_count", row.getInt("attempt_count"),
                                            "wrong_count", row.getInt("wrong_count"),
                                            "deadline_at",
                                                    row.getObject(
                                                            "deadline_at", OffsetDateTime.class),
                                            "state", row.getString("state")),
                            root.testId());
            if (tests.size() != 1 || !"RUNNING".equals(tests.getFirst().get("state")))
                throw storage();
            Map<String, Object> test = tests.getFirst();
            int next = normalAttempts + 1;
            int wrong = wrongAttempts + (base.success() ? 0 : 1);
            if (((Number) test.get("attempt_count")).intValue() != normalAttempts
                    || ((Number) test.get("wrong_count")).intValue() != wrongAttempts)
                throw storage();
            normalAttempts = next;
            wrongAttempts = wrong;
            OffsetDateTime playDeadline = (OffsetDateTime) test.get("deadline_at");
            if (next > attemptLimit || wrong > next || playDeadline == null) throw storage();
            testOutcome =
                    base.success()
                            ? "SUCCESS"
                            : !clock.isBefore(playDeadline)
                                    ? "TIME_LIMIT"
                                    : next >= attemptLimit ? "ATTEMPTS_EXHAUSTED" : null;
            score = Math.max(0, base.baseScore() - wrong * wrongPenalty);
            if (jdbc.update(
                            """
                            UPDATE public.test_report SET state='GRADED',updated_at=clock_timestamp()
                            WHERE id=? AND test_id=? AND state='ACCEPTED' AND accepted_at=?
                                AND payload_hash=? AND purged_at IS NULL
                            """,
                            root.reportId(),
                            root.testId(),
                            current.acceptedAt(),
                            root.payloadHash())
                    != 1) throw storage();
            OffsetDateTime ended = testOutcome == null ? null : now();
            changed =
                    jdbc.update(
                            """
                            UPDATE public.play_test SET attempt_count=?,wrong_count=?,
                                state=CASE WHEN ?::text IS NULL THEN 'RUNNING' ELSE 'ENDED' END,
                                outcome=?,final_score=CASE WHEN ?::text IS NULL THEN NULL ELSE ? END,
                                ended_at=?,result_until=?,
                                updated_at=clock_timestamp(),rev=rev+1
                            WHERE id=? AND state='RUNNING' AND attempt_count=? AND wrong_count=?
                            """,
                            next,
                            wrong,
                            testOutcome,
                            testOutcome,
                            testOutcome,
                            score,
                            ended,
                            ended == null ? null : ended.plusHours(24),
                            root.testId(),
                            next - 1,
                            wrong - (base.success() ? 0 : 1));
            if (changed != 1) throw storage();
        } else if (after == JobState.FAILED) {
            if (jdbc.update(
                            """
                            UPDATE public.test_report SET state='UNGRADABLE',updated_at=clock_timestamp()
                            WHERE id=? AND test_id=? AND state='ACCEPTED' AND payload_hash=?
                            """,
                            root.reportId(),
                            root.testId(),
                            root.payloadHash())
                    != 1) throw storage();
            OffsetDateTime ended = now();
            if (jdbc.update(
                            """
                            UPDATE public.play_test SET state='ENDED',outcome='SYSTEM_ERROR',
                                final_score=NULL,ended_at=?,result_until=?,updated_at=clock_timestamp(),rev=rev+1
                            WHERE id=? AND state='RUNNING'
                            """,
                            ended,
                            ended.plusHours(24),
                            root.testId())
                    != 1) throw storage();
            testOutcome = "SYSTEM_ERROR";
        }

        long event =
                audit.recordWorkerEvent(
                        worker,
                        root.jobId(),
                        number,
                        EventKind.COMPLETE_APPLIED,
                        UUID.randomUUID(),
                        request,
                        hash,
                        new Detail(
                                gen,
                                JobState.RUNNING,
                                after,
                                AttemptState.RUNNING,
                                attemptState,
                                reason,
                                outputHash,
                                resultHash));
        Long testAudit =
                jdbc.queryForObject(
                        """
                        INSERT INTO public.test_audit(event_key,actor_kind,actor_ref,action,scope_kind,
                            scope_key,request_id,phase,business_result,detail)
                        VALUES (?,'WORKER',?,'GRADE_COMPLETE','TEST',?,?,'RESULT',?,?::jsonb)
                        RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        worker.workerKey(),
                        key.toString(),
                        request,
                        after == JobState.COMPLETED
                                ? "GRADED"
                                : after == JobState.FAILED ? "UNGRADABLE" : "RETRY",
                        text(
                                object().put("jobKey", key.toString())
                                        .put("attemptNo", number)
                                        .put("gradeEventId", event)
                                        .put("commandHash", hash)));
        if (testAudit == null || testAudit <= 0) throw storage();
        ObjectNode envelope = object().put("auditEventId", event).put("commandHash", hash);
        envelope.set("receipt", receiptJson(receipt));
        if (jdbc.update(
                        """
                        UPDATE public.grade_attempt SET completion_data=?::jsonb
                        WHERE job_id=? AND attempt_no=? AND worker_key=? AND lease_gen=? AND state=?
                            AND completion_data IS NULL AND clock_timestamp()<? AND clock_timestamp()<?
                        """,
                        text(envelope),
                        root.jobId(),
                        number,
                        worker.workerKey(),
                        gen,
                        attemptState.name(),
                        current.leaseUntil(),
                        current.deadlineAt())
                != 1) throw storage();
        requireTestStored(
                root,
                current,
                worker,
                gen,
                number,
                after,
                attemptState,
                error,
                observed,
                reference,
                outputHash,
                outputCipher,
                resultHash,
                resultCipher,
                summary,
                envelope,
                base != null,
                testOutcome,
                score,
                normalAttempts,
                wrongAttempts,
                testAudit,
                request);
        return receipt;
    }

    /** 거절은 플레이 상태나 예약 예산을 변경하지 않고 실제 작업 감사만 쓴다. */
    private CompletionReceipt rejectTest(
            VerifiedWorker worker,
            TestRootEvidence root,
            Job job,
            Map<String, Object> attempt,
            long gen,
            int number,
            UUID request,
            String hash,
            Reason reason) {
        JobState state = JobState.valueOf(job.state());
        AttemptState attemptState = AttemptState.valueOf((String) attempt.get("state"));
        audit.recordWorkerEvent(
                worker,
                root.jobId(),
                number,
                EventKind.COMPLETE_REJECTED,
                UUID.randomUUID(),
                request,
                hash,
                new Detail(gen, state, state, attemptState, attemptState, reason, null, null));
        return new CompletionReceipt(
                root.jobKey(), number, false, state.name(), false, null, reason.name(), request);
    }

    /** TEST 예약은 접수 당시 마감과 실제 lease를 별도로 보존한다. */
    private Reason testRejection(
            Job job,
            Map<String, Object> attempt,
            VerifiedWorker worker,
            long gen,
            int number,
            OffsetDateTime clock) {
        if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(job.state()))
            return Reason.TERMINAL;
        if (!"RUNNING".equals(job.state())
                || !"RUNNING".equals(attempt.get("state"))
                || !worker.workerKey().equals(job.worker())
                || gen != job.generation()
                || number != job.count()) return Reason.STALE_LEASE;
        return testTimeRejection(job, clock);
    }

    private static Reason testTimeRejection(Job job, OffsetDateTime clock) {
        if (job.deadlineAt() == null || !clock.isBefore(job.deadlineAt()))
            return Reason.DEADLINE_EXCEEDED;
        if (job.leaseUntil() == null || !clock.isBefore(job.leaseUntil()))
            return Reason.STALE_LEASE;
        return null;
    }

    private Reason testEpochRejection(TestRootEvidence root) {
        Boolean same =
                jdbc.queryForObject(
                        "SELECT r.epoch=t.runtime_epoch AND r.epoch=p.runtime_epoch FROM"
                            + " public.grade_runtime r JOIN public.play_test t ON t.runtime_id=r.id"
                            + " JOIN public.test_report p ON p.test_id=t.id WHERE t.id=? AND"
                            + " p.id=?",
                        Boolean.class,
                        root.testId(),
                        root.reportId());
        return Boolean.TRUE.equals(same) ? null : Reason.RUNTIME_EPOCH_CHANGED;
    }

    /** 완료·영수증·감사와 플레이 횟수·24시간 종료를 한 연결에서 독립적으로 다시 읽는다. */
    private void requireTestStored(
            TestRootEvidence root,
            Job before,
            VerifiedWorker worker,
            long gen,
            int number,
            JobState after,
            AttemptState attemptState,
            String error,
            String observed,
            String reference,
            String outputHash,
            byte[] outputCipher,
            String resultHash,
            byte[] resultCipher,
            ObjectNode summary,
            ObjectNode envelope,
            boolean normal,
            String outcome,
            int score,
            int normalAttempts,
            int wrongAttempts,
            long testAudit,
            UUID request) {
        Boolean valid =
                jdbc.queryForObject(
                        """
                        SELECT j.state=? AND j.worker_key IS NULL AND j.lease_until IS NULL
                            AND j.lease_gen=? AND j.call_count=? AND j.accepted_at=? AND j.deadline_at=?
                            AND j.batch_id IS NULL AND j.report_id=? AND j.report_hash=?
                            AND j.snapshot_id=? AND j.runtime_id=? AND j.config_hash=? AND j.rubric_hash=?
                            AND j.result_hash IS NOT DISTINCT FROM ? AND j.result_cipher IS NOT DISTINCT FROM ?
                            AND j.result_data IS NOT DISTINCT FROM ?::jsonb AND j.error_code IS NOT DISTINCT FROM ?
                            AND a.state=? AND a.worker_key=? AND a.lease_gen=?
                            AND a.output_hash IS NOT DISTINCT FROM ? AND a.output_cipher IS NOT DISTINCT FROM ?
                            AND a.error_code IS NOT DISTINCT FROM ? AND a.observed_version IS NOT DISTINCT FROM ?
                            AND a.provider_ref IS NOT DISTINCT FROM ? AND a.completion_data=?::jsonb
                            AND a.ended_at IS NOT NULL AND a.ended_at>=a.started_at
                            AND r.test_id=? AND r.snapshot_id=? AND r.runtime_id=? AND r.payload_hash=?
                            AND r.state=? AND r.accepted_at=j.accepted_at
                            AND t.snapshot_id=j.snapshot_id AND t.runtime_id=j.runtime_id
                            AND t.config_hash=j.config_hash AND t.runtime_epoch=rt.epoch
                            AND rt.state='AVAILABLE' AND rt.config_hash=j.config_hash AND rt.epoch=?
                            AND r.config_hash=j.config_hash AND r.runtime_epoch=rt.epoch
                            AND v.current_snapshot_id=j.snapshot_id AND v.active_yn AND v.status='REVIEW'
                            AND s.active_yn AND f.version_id=v.id AND f.edit_rev=? AND f.format_no=?
                            AND clock_timestamp()<j.deadline_at AND clock_timestamp()<?
                            AND t.outcome IS NOT DISTINCT FROM ? AND t.state=?
                            AND t.attempt_count=? AND t.wrong_count=?
                            AND (NOT ? OR t.final_score IS NOT DISTINCT FROM ?)
                            AND (t.state<>'ENDED' OR (t.ended_at IS NOT NULL
                                AND t.result_until=t.ended_at+interval '24 hours'))
                            AND EXISTS(SELECT 1 FROM public.test_audit x WHERE x.id=?
                                AND x.actor_kind='WORKER' AND x.actor_ref=? AND x.scope_kind='TEST'
                                AND x.scope_key=? AND x.action='GRADE_COMPLETE' AND x.phase='RESULT'
                                AND x.request_id=? AND x.detail->>'gradeEventId'=?
                                AND x.detail->>'commandHash'=?)
                            AND EXISTS(SELECT 1 FROM public.grade_event e WHERE e.id=?
                                AND e.job_id=j.id AND e.attempt_no=? AND e.command_hash=?
                                AND e.event_kind='COMPLETE_APPLIED' AND e.actor_kind='WORKER'
                                AND e.actor_key=? AND e.request_id=?)
                        FROM public.grade_job j JOIN public.grade_attempt a ON a.job_id=j.id AND a.attempt_no=?
                        JOIN public.test_report r ON r.id=j.report_id JOIN public.play_test t ON t.id=r.test_id
                        JOIN public.grade_runtime rt ON rt.id=j.runtime_id
                        JOIN public.review_snapshot f ON f.id=j.snapshot_id
                        JOIN public.story_version v ON v.id=f.version_id
                        JOIN public.story s ON s.id=v.story_id WHERE j.id=? AND j.job_key=?
                        """,
                        Boolean.class,
                        after.name(),
                        gen,
                        number,
                        before.acceptedAt(),
                        before.deadlineAt(),
                        root.reportId(),
                        root.reportHash(),
                        root.snapshotId(),
                        root.runtimeId(),
                        root.configHash(),
                        root.rubricHash(),
                        resultHash,
                        resultCipher,
                        summary == null ? null : text(summary),
                        error,
                        attemptState.name(),
                        worker.workerKey(),
                        gen,
                        outputHash,
                        outputCipher,
                        error,
                        observed,
                        reference,
                        text(envelope),
                        root.testId(),
                        root.snapshotId(),
                        root.runtimeId(),
                        root.payloadHash(),
                        normal ? "GRADED" : after == JobState.FAILED ? "UNGRADABLE" : "ACCEPTED",
                        root.runtimeEpoch(),
                        root.snapshotRev(),
                        root.snapshotFormat(),
                        before.leaseUntil(),
                        outcome,
                        outcome == null ? "RUNNING" : "ENDED",
                        normalAttempts,
                        wrongAttempts,
                        normal && outcome != null,
                        score < 0 ? null : score,
                        testAudit,
                        worker.workerKey(),
                        root.jobKey().toString(),
                        request,
                        Long.toString(envelope.path("auditEventId").longValue()),
                        envelope.path("commandHash").textValue(),
                        envelope.path("auditEventId").longValue(),
                        number,
                        envelope.path("commandHash").textValue(),
                        worker.workerKey(),
                        UUID.fromString(envelope.path("receipt").path("requestId").textValue()),
                        number,
                        root.jobId(),
                        root.jobKey());
        if (!Boolean.TRUE.equals(valid)) throw storage();
    }

    /**
     * 종료·임대·시각·현재 epoch·출처 실패만 고정 거절 사유로 분류하며 TX/저장/codec 오류는 숨기지 않는다.
     *
     * @param root 이 source 저장소가 현재 TX에서 만든 잠금 증명, null 불가
     * @param job 잠근 실제 작업 사본, null 불가
     * @param attempt 잠근 실제 시도 사본, null 불가
     * @param worker 실제 인증 증명, null 불가
     * @param gen 양수 임대 세대
     * @param number 시도 번호 1~3
     * @param now 실제 DB 검사 시각, null 불가
     * @return 닫힌 거절 사유, 신규 완료가 가능하면 null
     * @throws IllegalStateException TX·잠금 증명 오류는 원래 고정 오류로 전파
     */
    private Reason rejection(
            LockedSource root,
            Job job,
            Map<String, Object> attempt,
            VerifiedWorker worker,
            long gen,
            int number,
            OffsetDateTime now) {
        if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(job.state()))
            return Reason.TERMINAL;
        if (!"RUNNING".equals(job.state())
                || !"RUNNING".equals(attempt.get("state"))
                || !worker.workerKey().equals(job.worker())
                || gen != job.generation()
                || number != job.count()) return Reason.STALE_LEASE;
        Reason expired = timeRejection(root, job, now);
        if (expired != null) return expired;
        var runtime =
                jdbc.queryForMap(
                        """
                        SELECT r.epoch,r.state,r.config_hash,b.runtime_epoch,b.config_hash AS batch_config
                        FROM public.grade_runtime r JOIN public.grade_batch b ON b.runtime_id=r.id WHERE b.id=?
                        """,
                        root.batchId());
        if (number(runtime, "epoch") != number(runtime, "runtime_epoch"))
            return Reason.RUNTIME_EPOCH_CHANGED;
        try {
            sources.requireExecutionEligibility(root);
        } catch (IllegalStateException failure) {
            if ("SOURCE_NOT_CURRENT".equals(failure.getMessage())
                    || "BATCH_EXPIRED".equals(failure.getMessage())) return Reason.SOURCE_REVOKED;
            throw failure;
        }
        return null;
    }

    /** 읽기·검증 완료 직후 실제 시각만 최종 검사한다. 경계와 같은 시각은 만료다. */
    private static Reason timeRejection(LockedSource root, Job job, OffsetDateTime now) {
        if (job.deadlineAt() == null
                || !now.isBefore(job.deadlineAt())
                || !now.isBefore(root.batchCreatedAt().plusHours(24)))
            return Reason.DEADLINE_EXCEEDED;
        if (job.leaseUntil() == null || !now.isBefore(job.leaseUntil())) return Reason.STALE_LEASE;
        return null;
    }

    /** 거절은 실제 부모 감사만 추가하며 원문·종료 영수증·예산·결과를 저장하거나 복구하지 않는다. */
    private CompletionReceipt reject(
            VerifiedWorker worker,
            LockedSource root,
            Job job,
            Map<String, Object> attempt,
            long gen,
            int number,
            UUID request,
            String hash,
            Reason reason) {
        JobState state = JobState.valueOf(job.state());
        AttemptState attemptState = AttemptState.valueOf((String) attempt.get("state"));
        audit.recordWorkerEvent(
                worker,
                root.jobId(),
                number,
                EventKind.COMPLETE_REJECTED,
                UUID.randomUUID(),
                request,
                hash,
                new Detail(gen, state, state, attemptState, attemptState, reason, null, null));
        return new CompletionReceipt(
                root.jobKey(), number, false, state.name(), false, null, reason.name(), request);
    }

    /**
     * 실제 예약과 현재 작업의 전체 fencing 조건을 WHERE와 트리거 이후 RETURNING 양쪽에서 검사한다.
     *
     * @param root 현재 TX의 실제 루트 증명, null 불가
     * @param worker 예약 소유 인증 증명, null 불가
     * @param gen 양수 원래 예약 세대
     * @param number 원래 예약 번호 1~3
     * @param state SUCCEEDED 또는 FAILED, null 불가
     * @param error 닫힌 오류 코드, 성공이면 null
     * @param observed 실제 관측 문자열 최대 160 코드포인트, null 허용
     * @param reference 실제 비개인 ASCII 참조, null 허용
     * @param hash 원본 의미 결과 정규 SHA-256, 오류이면 null
     * @param cipher 기존 AES-GCM 봉투 UTF-8 바이트, 오류이면 null
     * @throws DataAccessResourceFailureException 시간 경계·쓰기 결과 불일치 시 원인 없는 저장 오류
     */
    private void updateAttempt(
            LockedSource root,
            VerifiedWorker worker,
            long gen,
            int number,
            AttemptState state,
            String error,
            String observed,
            String reference,
            String hash,
            byte[] cipher) {
        var updated =
                jdbc.query(
                        """
                        UPDATE public.grade_attempt a SET state=?,ended_at=clock_timestamp(),error_code=?,
                            observed_version=?,provider_ref=?,output_hash=?,output_cipher=?
                        WHERE a.job_id=? AND a.attempt_no=? AND a.worker_key=? AND a.lease_gen=?
                            AND a.state='RUNNING' AND a.completion_data IS NULL
                            AND EXISTS(SELECT 1 FROM public.grade_job j WHERE j.id=a.job_id
                                AND j.state='RUNNING' AND j.worker_key=? AND j.lease_gen=? AND j.call_count=?
                                AND clock_timestamp()<j.lease_until AND clock_timestamp()<j.deadline_at)
                            AND clock_timestamp()<?
                        RETURNING EXISTS(SELECT 1 FROM public.grade_job j WHERE j.id=a.job_id
                            AND j.state='RUNNING' AND j.worker_key=? AND j.lease_gen=? AND j.call_count=?
                            AND clock_timestamp()<j.lease_until AND clock_timestamp()<j.deadline_at)
                            AND clock_timestamp()<?
                        """,
                        (row, index) -> row.getBoolean(1),
                        state.name(),
                        error,
                        observed,
                        reference,
                        hash,
                        cipher,
                        root.jobId(),
                        number,
                        worker.workerKey(),
                        gen,
                        worker.workerKey(),
                        gen,
                        number,
                        root.batchCreatedAt().plusHours(24),
                        worker.workerKey(),
                        gen,
                        number,
                        root.batchCreatedAt().plusHours(24));
        if (updated.size() != 1 || !updated.getFirst()) throw storage();
    }

    /**
     * 작업 결과만 전이하며 accepted_at·deadline_at·call_count·lease_gen을 보존한다.
     *
     * @param root 현재 TX의 실제 루트 증명, null 불가
     * @param worker 원래 예약 소유 인증 증명, null 불가
     * @param gen 원래 양수 임대 세대
     * @param number 실제 예약 번호 1~3
     * @param before 변경 직전 실제 작업 사본, null 불가
     * @param after COMPLETED·QUEUED·FAILED, null 불가
     * @param error 닫힌 실제 오류, 성공이면 null
     * @param summary 점수·충족·비교 플래그만 포함한 안전 요약, 오류이면 null
     * @param hash 비공개 서버 계산 정규 SHA-256, 오류이면 null
     * @param cipher 비공개 계산 AES-GCM 봉투 UTF-8 바이트, 오류이면 null
     * @param now 검증 뒤 실제 DB 시각, null 불가
     * @throws DataAccessResourceFailureException 시간 경계·쓰기 불일치 시 원인 없는 저장 오류
     */
    private void updateJob(
            LockedSource root,
            VerifiedWorker worker,
            long gen,
            int number,
            Job before,
            JobState after,
            String error,
            ObjectNode summary,
            String hash,
            byte[] cipher,
            OffsetDateTime now) {
        var updated =
                jdbc.query(
                        """
                        UPDATE public.grade_job SET state=?,worker_key=NULL,lease_until=NULL,error_code=?,
                            result_data=?::jsonb,result_hash=?,result_cipher=?,next_run_at=?,updated_at=clock_timestamp()
                        WHERE id=? AND state='RUNNING' AND worker_key=? AND lease_gen=? AND call_count=?
                            AND config_hash=? AND rubric_hash=? AND input_hash=?
                            AND clock_timestamp()<lease_until AND clock_timestamp()<deadline_at AND clock_timestamp()<?
                        RETURNING clock_timestamp()<? AND clock_timestamp()<deadline_at AND clock_timestamp()<?
                        """,
                        (row, index) -> row.getBoolean(1),
                        after.name(),
                        error,
                        summary == null ? null : text(summary),
                        hash,
                        cipher,
                        now,
                        root.jobId(),
                        worker.workerKey(),
                        gen,
                        number,
                        root.configHash(),
                        root.rubricHash(),
                        root.inputHash(),
                        root.batchCreatedAt().plusHours(24),
                        before.leaseUntil(),
                        root.batchCreatedAt().plusHours(24));
        if (updated.size() != 1 || !updated.getFirst()) throw storage();
    }

    /** 실제 트리거 실행 뒤 원래 시계·예산·루트와 새 결과 상태를 검사한다. 새 상위 잠금을 추가하지 않는다. */
    private void requirePostWrite(
            LockedSource root,
            Job before,
            JobState after,
            AttemptState attempt,
            VerifiedWorker worker,
            long gen,
            int number) {
        Boolean valid =
                jdbc.queryForObject(
                        """
                        SELECT j.state=? AND j.worker_key IS NULL AND j.lease_until IS NULL
                            AND j.lease_gen=? AND j.call_count=? AND j.deadline_at=? AND j.accepted_at=?
                            AND j.input_hash=? AND j.config_hash=? AND j.rubric_hash=?
                            AND j.job_key=? AND j.batch_id=? AND j.runtime_id=? AND j.snapshot_id=?
                            AND j.sample_code=? AND j.repeat_no=?
                            AND b.batch_key=? AND b.purpose=? AND b.created_by=? AND b.created_at=?
                            AND r.code=? AND f.version_id=? AND f.edit_rev=? AND f.format_no=?
                            AND v.story_id=? AND v.edit_rev=?
                            AND a.state=? AND a.worker_key=? AND a.lease_gen=? AND a.completion_data IS NOT NULL
                            AND r.epoch=b.runtime_epoch AND r.epoch=? AND r.state='AVAILABLE'
                            AND r.config_hash=j.config_hash AND b.config_hash=j.config_hash
                            AND b.state='RUNNING' AND b.payload_hash=? AND b.dataset_hash=?
                            AND b.snapshot_id=j.snapshot_id AND b.runtime_id=j.runtime_id
                            AND v.current_snapshot_id=j.snapshot_id AND v.active_yn AND s.active_yn
                            AND (v.status='REVIEW' OR (b.purpose='AVAILABILITY' AND v.status IN ('READY','PUBLISHED')))
                            AND c.active_yn AND c.can_review AND d.enrolled_at IS NOT NULL AND d.mfa_state='READY'
                            AND EXISTS(SELECT 1 FROM public.story_access x WHERE x.story_id=s.id
                                AND x.admin_id=c.id AND x.permission='REVIEW' AND x.active_yn)
                            AND clock_timestamp()<? AND clock_timestamp()<j.deadline_at
                            AND clock_timestamp()<b.created_at+interval '24 hours'
                        FROM public.grade_job j JOIN public.grade_attempt a ON a.job_id=j.id AND a.attempt_no=?
                        JOIN public.grade_batch b ON b.id=j.batch_id JOIN public.grade_runtime r ON r.id=j.runtime_id
                        JOIN public.review_snapshot f ON f.id=j.snapshot_id JOIN public.story_version v ON v.id=f.version_id
                        JOIN public.story s ON s.id=v.story_id JOIN public.admin_account c ON c.id=b.created_by
                        JOIN public.admin_credential d ON d.account_id=c.id WHERE j.id=?
                        """,
                        Boolean.class,
                        after.name(),
                        gen,
                        number,
                        before.deadlineAt(),
                        before.acceptedAt(),
                        root.inputHash(),
                        root.configHash(),
                        root.rubricHash(),
                        root.jobKey(),
                        root.batchId(),
                        root.runtimeId(),
                        root.snapshotId(),
                        root.sampleCode(),
                        root.repeatNo(),
                        root.batchKey(),
                        root.purpose(),
                        root.creatorId(),
                        root.batchCreatedAt(),
                        root.runtimeCode(),
                        root.versionId(),
                        root.snapshotRev(),
                        root.snapshotFormat(),
                        root.storyId(),
                        root.versionRev(),
                        attempt.name(),
                        worker.workerKey(),
                        gen,
                        root.runtimeEpoch(),
                        root.payloadHash(),
                        root.datasetHash(),
                        before.leaseUntil(),
                        number,
                        root.jobId());
        if (!Boolean.TRUE.equals(valid)) throw storage();
    }

    /** 트리거가 암호문·해시·영수증 또는 관측값을 바꾸면 반환 전에 전체 TX를 중단한다. */
    private void requireStoredOutcome(
            LockedSource root,
            int number,
            String error,
            String observed,
            String reference,
            String outputHash,
            byte[] outputCipher,
            String resultHash,
            byte[] resultCipher,
            ObjectNode summary,
            ObjectNode envelope) {
        Boolean valid =
                jdbc.queryForObject(
                        """
                        SELECT a.output_hash IS NOT DISTINCT FROM ? AND a.output_cipher IS NOT DISTINCT FROM ?
                            AND a.error_code IS NOT DISTINCT FROM ? AND a.observed_version IS NOT DISTINCT FROM ?
                            AND a.provider_ref IS NOT DISTINCT FROM ? AND a.completion_data=?::jsonb
                            AND a.ended_at IS NOT NULL AND a.ended_at>=a.started_at
                            AND j.result_hash IS NOT DISTINCT FROM ? AND j.result_cipher IS NOT DISTINCT FROM ?
                            AND j.result_data IS NOT DISTINCT FROM ?::jsonb AND j.error_code IS NOT DISTINCT FROM ?
                        FROM public.grade_job j JOIN public.grade_attempt a ON a.job_id=j.id
                        WHERE j.id=? AND a.attempt_no=?
                        """,
                        Boolean.class,
                        outputHash,
                        outputCipher,
                        error,
                        observed,
                        reference,
                        text(envelope),
                        resultHash,
                        resultCipher,
                        summary == null ? null : text(summary),
                        error,
                        root.jobId(),
                        number);
        if (!Boolean.TRUE.equals(valid)) throw storage();
    }

    /** 현재 잠근 작업의 실제 임대·시간·횟수를 읽는다. */
    private Job job(long id) {
        return jdbc.queryForObject(
                """
                SELECT state,worker_key,lease_gen,call_count,lease_until,deadline_at,accepted_at
                FROM public.grade_job WHERE id=?
                """,
                (row, index) ->
                        new Job(
                                row.getString("state"),
                                row.getString("worker_key"),
                                row.getLong("lease_gen"),
                                row.getInt("call_count"),
                                row.getObject("lease_until", OffsetDateTime.class),
                                row.getObject("deadline_at", OffsetDateTime.class),
                                row.getObject("accepted_at", OffsetDateTime.class)),
                id);
    }

    /**
     * @return 실제 DB의 새 벽시계, null이면 저장 오류
     */
    private OffsetDateTime now() {
        OffsetDateTime value =
                jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (value == null) throw storage();
        return value;
    }

    /**
     * 기대값은 계산 뒤 점수·필수 충족·성공의 전체 집합만 비교한다. 이유·정답·원문은 요약에 넣지 않는다.
     *
     * @param base 실제 서버 계산 완료 결과, null 불가
     * @param selected 실제 전체 검증 집합의 선택값, null 불가
     * @param providerVersionMatched 실제 비null 관측과 등록 버전의 정확한 문자열 일치; provider 증명은 아님
     * @return 원문 없이 새로 소유한 안전 요약
     */
    private static ObjectNode summary(
            BaseResult base, SelectedSample selected, boolean providerVersionMatched) {
        ObjectNode node =
                object().put("formatNo", 1)
                        .put("baseScore", base.baseScore())
                        .put("success", base.success());
        var items = node.putArray("items");
        boolean matched =
                Objects.equals(base.baseScore(), selected.expectedScore())
                        && Objects.equals(base.success(), selected.expectedSuccess())
                        && "GRADED".equals(selected.kind());
        JsonNode expected = selected.expectation().path("items");
        for (var item : base.items()) {
            ObjectNode safe =
                    items.addObject()
                            .put("rubricCode", item.rubricCode())
                            .put("score", item.score());
            if (item.requiredMet() == null) safe.putNull("requiredMet");
            else safe.put("requiredMet", item.requiredMet());
            boolean found = false;
            for (JsonNode value : expected) {
                if (item.rubricCode().equals(value.path("rubricCode").textValue())) {
                    found =
                            item.score() == value.path("score").intValue()
                                    && (item.requiredMet() == null
                                            ? value.path("requiredMet").isNull()
                                            : value.path("requiredMet").isBoolean()
                                                    && item.requiredMet()
                                                            == value.path("requiredMet")
                                                                    .booleanValue());
                    break;
                }
            }
            matched &= found;
        }
        node.put("expectationMatched", matched);
        node.put("providerVersionMatched", providerVersionMatched);
        // 관측 문자열 일치는 provider attestation 또는 사람 품질 검수를 증명하지 않는다.
        node.put("fixtureAdoptionEligible", matched && providerVersionMatched);
        return node;
    }

    /** 닫힌 봉투를 SnapshotJson으로만 읽는다. 객체 키만 정렬하고 배열 순서와 명시적 null은 보존한다. */
    private static JsonNode result(String json) {
        try {
            if (json == null) throw invalid();
            JsonNode root = SnapshotJson.parse(bytes(json));
            String kind = root.path("kind").textValue();
            if ("COMPLETE".equals(kind)) {
                fields(root, Set.of("kind", "semantic"));
                if (!root.path("semantic").isObject()
                        || !"COMPLETE".equals(root.path("semantic").path("status").textValue()))
                    throw invalid();
            } else if ("UNRESOLVED".equals(kind) || "ERROR".equals(kind)) {
                fields(root, Set.of("kind", "errorCode"));
                if (!ERRORS.contains(root.path("errorCode").asText())) throw invalid();
            } else throw invalid();
            return root;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /** 저장 영수증의 오류도 원문이나 원인 없이 fail closed 한다. */
    private static JsonNode stored(String json) {
        try {
            JsonNode value = SnapshotJson.parse(bytes(json));
            fields(value, Set.of("receipt", "auditEventId", "commandHash"));
            if (!value.path("auditEventId").isIntegralNumber()
                    || value.path("auditEventId").longValue() <= 0
                    || !value.path("commandHash").asText().matches("[0-9a-f]{64}")) throw storage();
            return value;
        } catch (IllegalArgumentException failure) {
            throw storage();
        }
    }

    /** 최초 서버 영수증만 해석하며 새 requestId나 현재 상태로 대체하지 않는다. */
    private static CompletionReceipt receipt(JsonNode node, UUID key, int number) {
        try {
            fields(
                    node,
                    Set.of(
                            "jobKey",
                            "attemptNo",
                            "accepted",
                            "state",
                            "retryScheduled",
                            "outcome",
                            "reason",
                            "requestId"));
            UUID storedKey = UUID.fromString(node.path("jobKey").textValue());
            UUID request = UUID.fromString(node.path("requestId").textValue());
            if (!storedKey.equals(key)
                    || !node.path("attemptNo").isIntegralNumber()
                    || node.path("attemptNo").intValue() != number
                    || !node.path("accepted").isBoolean()
                    || !node.path("accepted").booleanValue()
                    || !node.path("retryScheduled").isBoolean()
                    || !uuid(request)) throw storage();
            JobState.valueOf(node.path("state").textValue());
            Reason.valueOf(node.path("reason").textValue());
            if (!node.path("outcome").isNull()
                    && !Set.of("COMPLETE", "SYSTEM_ERROR")
                            .contains(node.path("outcome").textValue())) throw storage();
            return new CompletionReceipt(
                    key,
                    number,
                    true,
                    node.path("state").textValue(),
                    node.path("retryScheduled").booleanValue(),
                    node.path("outcome").textValue(),
                    node.path("reason").textValue(),
                    request);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw storage();
        }
    }

    /** 여덟 공개 안전 필드만 비공개 영수증 봉투에 기록한다. */
    private static ObjectNode receiptJson(CompletionReceipt receipt) {
        return object().put("jobKey", receipt.jobKey().toString())
                .put("attemptNo", receipt.attemptNo())
                .put("accepted", receipt.accepted())
                .put("state", receipt.state())
                .put("retryScheduled", receipt.retryScheduled())
                .put("outcome", receipt.outcome())
                .put("reason", receipt.reason())
                .put("requestId", receipt.requestId().toString());
    }

    /** 정확한 필드 집합만 승인한다. */
    private static void fields(JsonNode value, Set<String> fields) {
        if (!value.isObject() || value.size() != fields.size()) throw invalid();
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw invalid();
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static byte[] bytes(String value) {
        try {
            var encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException failure) {
            throw invalid();
        }
    }

    private static String text(JsonNode node) {
        return new String(SnapshotJson.encode(node), StandardCharsets.UTF_8);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static boolean uuid(UUID value) {
        return value != null
                && (value.getMostSignificantBits() != 0 || value.getLeastSignificantBits() != 0);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_COMPLETION_INPUT");
    }

    private static IllegalStateException boundary() {
        return new IllegalStateException("COMPLETION_REQUIRES_SEPARATE_TRANSACTION");
    }

    private static DataAccessResourceFailureException storage() {
        return new DataAccessResourceFailureException("COMPLETION_STORAGE_FAILURE");
    }

    private record Job(
            String state,
            String worker,
            long generation,
            int count,
            OffsetDateTime leaseUntil,
            OffsetDateTime deadlineAt,
            OffsetDateTime acceptedAt) {}

    /**
     * 원문·점수를 포함하지 않는 내부 불변 영수증이다. outcome은 COMPLETE 또는 소진 SYSTEM_ERROR 또는 재시도·거절 시 null이며 품질
     * 승인·provider 증명이 아니다.
     *
     * @param jobKey 실제 작업 UUID, null 불가
     * @param attemptNo 실제 예약 1~3
     * @param accepted 실제 완료 적용 여부
     * @param state 실제 저장 작업 상태, null 불가
     * @param retryScheduled QUEUED 전이 여부; 호출 예산 재충전은 아님
     * @param outcome COMPLETE·소진 SYSTEM_ERROR 또는 재시도·거절 시 null
     * @param reason 닫힌 감사 사유, null 불가
     * @param requestId 최초 서버 요청 UUID, null 불가
     */
    public record CompletionReceipt(
            UUID jobKey,
            int attemptNo,
            boolean accepted,
            String state,
            boolean retryScheduled,
            String outcome,
            String reason,
            UUID requestId) {}
}
