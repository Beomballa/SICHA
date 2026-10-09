package com.reasoning.common.grading.service;

import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.VerifiedRuntime;
import com.reasoning.common.grading.engine.LocalSemanticEngine.BeforeChat;
import com.reasoning.common.grading.engine.LocalSemanticEngine.JobDeadline;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository.LockedSource;
import com.reasoning.common.grading.repository.GradeSourceRepository.TestPreparation;
import com.reasoning.common.grading.repository.GradeSourceRepository.TestRootEvidence;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.FrozenDatasetValidator.SelectedSample;
import com.reasoning.common.grading.service.FrozenDatasetValidator.ValidatedDataset;
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

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * INTERNAL 서버 전용 호출 예약 경계다. HTTP 자동 DTO·API 활성화·모델 호출·완료 처리를 제공하지 않는다. 실제 설치 설정이 없으므로 자동 서비스 등록도 하지
 * 않는다. 모델 실행은 반환 후 커밋된 신규 예약에만 허용된다.
 */
public final class GradeStartService {
    private final JdbcTemplate jdbc;
    private final GradeSourceRepository sources;
    private final GradeRuntimeRepository runtimes;
    private final GradeFrozenInputRepository frozenInputs;
    private final GradeWorkerCredentials credentials;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final PlatformTransactionManager manager;
    private final TransactionTemplate transaction;
    private final GradeLeaseRepository leases;
    private final boolean remoteAssembly;
    private final PlaytestPolicyGate testPolicy;
    private final CryptoService crypto;

    /**
     * SQL 밖에서 지문을 계산한 실제 설치 인스턴스만 고정한다. 원격 방법은 JDBC와 동일 DataSource 인스턴스의
     * DataSourceTransactionManager만 지원한다. 다른 관리자 조립의 원격 입구는 SQL 전에 거절하며 기존 로컬 start의 관리자 수용·JPA 관련
     * 동작은 변경하지 않는다.
     *
     * @param jdbc 같은 DataSource를 사용할 DB 도구, null 불가
     * @param manager 해당 DataSource의 트랜잭션 관리자, null 불가
     * @param credentials 실제 배포 자격증명 등록, null 불가
     * @param installations 생성된 manifest의 configId를 키로 하는 신뢰 배포 설치, null 키·값 불가
     * @throws IllegalArgumentException 설치 키와 실제 manifest 식별자가 다르면 고정 오류
     * @throws NullPointerException 필수 의존성이 null인 경우
     */
    public GradeStartService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            GradeWorkerCredentials credentials,
            Map<String, InstalledRuntimeManifestVerifier> installations) {
        this(jdbc, manager, credentials, installations, null, null, null);
    }

    /** TEST 지원은 실제 PLAYTEST 정책과 암호화 서비스를 함께 제공한 조립에서만 사용한다. */
    public GradeStartService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            GradeWorkerCredentials credentials,
            Map<String, InstalledRuntimeManifestVerifier> installations,
            PlaytestPolicyGate testPolicy,
            MemberPolicyGate memberPolicy,
            CryptoService crypto) {
        if ((testPolicy == null) != (crypto == null)
                || (testPolicy == null) != (memberPolicy == null))
            throw new IllegalArgumentException("INVALID_TEST_START_ASSEMBLY");
        this.testPolicy = testPolicy;
        this.crypto = crypto;
        this.jdbc = Objects.requireNonNull(jdbc);
        this.manager = Objects.requireNonNull(manager);
        remoteAssembly =
                manager
                                instanceof
                                org.springframework.jdbc.datasource.DataSourceTransactionManager
                                        owner
                        && owner.getDataSource() == jdbc.getDataSource();
        this.credentials = Objects.requireNonNull(credentials);
        this.installations = Map.copyOf(Objects.requireNonNull(installations));
        this.installations.forEach(
                (code, verifier) -> {
                    if (!code.equals(
                            verifier.registrationManifest().path("configId").textValue())) {
                        throw new IllegalArgumentException("INVALID_START_INSTALLATIONS");
                    }
                });
        sources = new GradeSourceRepository(jdbc, memberPolicy);
        runtimes = new GradeRuntimeRepository(jdbc);
        frozenInputs = new GradeFrozenInputRepository(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(false);
        transaction.setTimeout(5);
        var leaseJdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        leaseJdbc.setQueryTimeout(5);
        leases = new GradeLeaseRepository(leaseJdbc, manager);
    }

    /** 동일 레지스트리·DB 조립만 허용하며 비밀 값을 반환하지 않는다. */
    void requireAssembly(GradeWorkerCredentials registry, GradeCompletionService completion) {
        if (registry != credentials) throw new IllegalArgumentException("RUNNER_ASSEMBLY_MISMATCH");
        completion.requireAssembly(credentials, jdbc.getDataSource());
    }

    /**
     * HTTP 조립의 실제 registry·DataSource·JDBC 관리자·설치 소유를 SQL 없이 검사한다. 실행 권위나 비밀 값을 반환하지 않는다.
     *
     * @param registry 두 서비스와 인증 체인이 사용하는 동일 registry, null 불가
     * @param completion 동일 실제 저장소와 설치로 만든 완료 서비스, null 불가
     * @throws IllegalArgumentException 원인 없는 REMOTE_ASSEMBLY_MISMATCH
     */
    public void requireHttpAssembly(
            GradeWorkerCredentials registry, GradeCompletionService completion) {
        if (!remoteAssembly || registry != credentials || completion == null)
            throw new IllegalArgumentException("REMOTE_ASSEMBLY_MISMATCH");
        completion.requireHttpAssembly(registry, jdbc.getDataSource(), manager, installations);
    }

    /** 이전 BATCH 조립은 TEST 정책과 암호화 서비스를 포함할 수 없다. */
    public void requireBatchAssembly(GradeCompletionService completion) {
        if (testPolicy != null || crypto != null)
            throw new IllegalArgumentException("REMOTE_ASSEMBLY_MISMATCH");
        completion.requireBatchAssembly();
    }

    /** TEST 조립의 정책·암호화·관리자·설치 인스턴스를 SQL 없이 대조한다. */
    public void requireTestAssembly(
            GradeWorkerCredentials registry,
            javax.sql.DataSource source,
            PlatformTransactionManager expectedManager,
            Map<String, InstalledRuntimeManifestVerifier> expectedInstallations,
            PlaytestPolicyGate expectedPolicy,
            CryptoService expectedCrypto) {
        if (!remoteAssembly
                || registry != credentials
                || jdbc.getDataSource() != source
                || manager != expectedManager
                || testPolicy == null
                || testPolicy != expectedPolicy
                || crypto == null
                || crypto != expectedCrypto
                || !sameInstallations(expectedInstallations))
            throw new IllegalArgumentException("TEST_ASSEMBLY_MISMATCH");
    }

    /** 설치 맵의 동등성이 아니라 검증기 객체의 동일 소유권을 검사한다. */
    private boolean sameInstallations(Map<String, InstalledRuntimeManifestVerifier> expected) {
        return expected != null
                && installations.size() == expected.size()
                && installations.entrySet().stream()
                        .allMatch(entry -> entry.getValue() == expected.get(entry.getKey()));
    }

    /**
     * @return START와 같은 실제 DB·TX 관리자로 구성한 임대 저장소
     */
    GradeLeaseRepository leases() {
        return leases;
    }

    /** 발견 코드는 권위가 아니며 예약 전 세 행동의 실제 증명만 검사한다. */
    void requireRunnerPermissions(VerifiedWorker worker, UUID key) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("START_REQUIRES_SEPARATE_TRANSACTION");
        var codes =
                jdbc.queryForList(
                        "SELECT r.code FROM public.grade_job j JOIN public.grade_runtime r ON"
                                + " r.id=j.runtime_id WHERE j.job_key=?",
                        String.class,
                        key);
        if (codes.size() != 1) throw rejected();
        for (Action action : new Action[] {Action.START, Action.RENEW, Action.COMPLETE})
            credentials.requirePermission(worker, action, codes.getFirst());
    }

    /**
     * 신규 예약에 한 번만 훅을 발행한다. SQL 커밋과 외부 전송은 원자적이지 않으며 커밋 후 회수 경쟁은 남는다.
     *
     * @param worker 동일 레지스트리의 실제 소유자, null 불가
     * @param reservation 이 서비스의 신규 예약, null 불가; replay·외부 발행·중복 사용 거절
     * @return DB 권위를 매번 새 TX에서 검사하고 실패해도 소모하는 단일 사용 훅
     */
    BeforeChat beforeChat(VerifiedWorker worker, Reservation reservation) {
        handoff(worker, reservation);
        var consumed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!consumed.compareAndSet(false, true)) throw rejected();
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("START_REQUIRES_SEPARATE_TRANSACTION");
            try {
                transaction.executeWithoutResult(status -> fence(worker, reservation));
            } catch (DataAccessException | TransactionException failure) {
                throw new DataAccessResourceFailureException("START_STORAGE_FAILURE");
            }
        };
    }

    /**
     * 로컬 훅과 remote NEW가 같은 실제 신규 예약의 발행 가드를 한 번만 소비한다.
     *
     * @param worker 동일 registry의 실제 소유자, null 불가
     * @param reservation 이 서비스의 커밋한 신규 예약, null·재생·이미 발행한 값 불가
     * @throws IllegalStateException 발행자·소유자·단회 가드가 다르면 원문 없는 거절
     * @throws SecurityException 실제 START 허용이 없으면 가드 소비 후 거절
     */
    private void handoff(VerifiedWorker worker, Reservation reservation) {
        if (reservation == null
                || reservation.issuer != this
                || reservation.replay
                || worker == null
                || !worker.workerKey().equals(reservation.workerKey)
                || !reservation.issued.compareAndSet(false, true)) throw rejected();
        credentials.requirePermission(
                worker, Action.START, reservation.runtime.profile().configId());
    }

    /**
     * 신규 정상 루트 다음 실제 시도를 잠그고 제한 검증 이후 DB 시각으로 엄격히 검사한다. 자체 5초 TX 밖에서만 호출한다.
     *
     * @param worker 실제 발행 레지스트리 소유자, null 불가
     * @param reserved 이 소유자의 신규 예약, null 불가; 원래 마감·다섯 해시·선택은 변경 불가
     * @throws IllegalStateException 원래 결속·현재 실행 권한·임대·시도가 불일치한 경우
     */
    private void fence(VerifiedWorker worker, Reservation reserved) {
        LockedSource root = sources.lockRoots(reserved.jobKey);
        credentials.requirePermission(worker, Action.START, root.runtimeCode());
        if (!binding(root).equals(reserved.binding)) throw rejected();
        var installed = installations.get(root.runtimeCode());
        if (installed == null) throw rejected();
        var runtime =
                installed.verify(
                        runtimes.getRuntimeDetail(root.runtimeId())
                                .orElseThrow(GradeStartService::rejected));
        var dataset = frozenInputs.dataset(root, runtime);
        var selected = dataset.select(root.sampleCode());
        if (!java.util.Arrays.equals(
                        dataset.frozenSnapshot().payloadBytes(),
                        reserved.dataset.frozenSnapshot().payloadBytes())
                || !java.util.Arrays.equals(
                        FrozenModelProjection.project(dataset, selected).payloadBytes(),
                        reserved.projectionBytes())) throw rejected();
        sources.requireExecutionEligibility(root);
        var rows =
                jdbc.queryForList(
                        "SELECT attempt_no,worker_key,lease_gen,state,completion_data FROM"
                            + " public.grade_attempt WHERE job_id=? AND attempt_no=? FOR UPDATE",
                        root.jobId(),
                        reserved.attemptNo);
        if (rows.size() != 1) throw rejected();
        var attempt = rows.getFirst();
        Job current = job(root.jobId());
        requireOwner(current, worker, reserved.leaseGen);
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (!"RUNNING".equals(attempt.get("state"))
                || attempt.get("completion_data") != null
                || !reserved.workerKey.equals(attempt.get("worker_key"))
                || ((Number) attempt.get("lease_gen")).longValue() != reserved.leaseGen
                || current.callCount() != reserved.attemptNo
                || !reserved.deadlineAt.equals(current.deadlineAt())
                || now == null
                || current.leaseUntil() == null
                || !now.isBefore(current.leaseUntil())
                || !now.isBefore(current.deadlineAt())
                || !now.isBefore(root.batchCreatedAt().plusHours(24))) throw rejected();
    }

    /** 예약 당시 물리 식별자·다섯 해시·선택·epoch·효력 시각을 불변 사본으로 결속한다. */
    private static java.util.List<Object> binding(LockedSource root) {
        return java.util.List.of(
                root.jobId(),
                root.jobKey(),
                root.batchId(),
                root.batchKey(),
                root.creatorId(),
                root.storyId(),
                root.versionId(),
                root.snapshotId(),
                root.runtimeId(),
                root.runtimeCode(),
                root.runtimeEpoch(),
                root.configHash(),
                root.payloadHash(),
                root.datasetHash(),
                root.rubricHash(),
                root.inputHash(),
                root.sampleCode(),
                root.repeatNo(),
                root.purpose(),
                root.versionRev(),
                root.snapshotRev(),
                root.snapshotFormat(),
                root.policyCode(),
                root.batchCreatedAt());
    }

    /**
     * 커밋한 실제 신규 예약만 승인 입력으로 내보낸다. 재생은 입력·예산·소유권이 없다.
     *
     * @param worker 동일 registry의 START 증명, null 불가
     * @param jobKey 영 UUID가 아닌 실제 BATCH 작업
     * @param leaseGen 양수 현재 임대 세대
     * @return 실제 커밋 후 NEW 또는 입력 없는 REPLAY
     * @throws GradeRemoteExecutionProtocol.Failure 원인 없는 고정 거절; 커밋 뒤 실패는 환급하지 않음
     */
    public GradeRemoteExecutionProtocol.StartReply startApproved(
            VerifiedWorker worker, UUID jobKey, long leaseGen) {
        remoteEntry(worker, jobKey, leaseGen);
        if (testJob(jobKey)) return testStartApproved(worker, jobKey, leaseGen);
        return remoteCall(
                () -> {
                    discoverPermission(worker, jobKey, Action.START);
                    var reserved =
                            transaction.execute(status -> reserve(worker, jobKey, leaseGen, true));
                    if (reserved == null) throw rejected();
                    if (reserved.replay)
                        return GradeRemoteExecutionProtocol.start(
                                jobKey, leaseGen, reserved.attemptNo, null, null);
                    handoff(worker, reserved);
                    var input =
                            approved(
                                    reserved.binding,
                                    reserved.jobKey,
                                    reserved.leaseGen,
                                    reserved.attemptNo,
                                    reserved.workerKey,
                                    reserved.deadlineAt,
                                    reserved.dataset,
                                    reserved.selection,
                                    reserved.runtime);
                    return GradeRemoteExecutionProtocol.start(
                            jobKey,
                            leaseGen,
                            reserved.attemptNo,
                            input,
                            reserved.observation.export());
                });
    }

    /**
     * 현재 출처·설치·선택·실제 시도를 새 정상 루트 TX에서 확인한다. 반복 성공은 단회 grant가 아니다.
     *
     * @param worker 동일 registry의 START 증명
     * @param jobKey 실제 BATCH 작업 UUID
     * @param expected 원래 양수 세대·1~3 시도·소문자64hash, null 불가
     * @return 관측 커밋 이후 줄어든 제한; 새 시도나 소유권 없음
     * @throws GradeRemoteExecutionProtocol.Failure 현재성·만료·저장소의 고정 거절
     */
    public GradeRemoteExecutionProtocol.FenceReply revalidate(
            VerifiedWorker worker,
            UUID jobKey,
            GradeRemoteExecutionProtocol.AttemptRequest expected) {
        remoteRequest(worker, jobKey, expected);
        if (testJob(jobKey)) return testFence(worker, jobKey, expected);
        return remoteCall(
                () -> {
                    discoverPermission(worker, jobKey, Action.START);
                    var observation =
                            transaction.execute(
                                    status ->
                                            remoteObservation(
                                                    worker, jobKey, expected, Action.START, null));
                    if (observation == null) throw rejected();
                    return GradeRemoteExecutionProtocol.fence(
                            jobKey, expected, observation.export());
                });
    }

    /**
     * 실제 임대 저장소의 짧은 갱신 TX 뒤 별도 정상 루트 관측을 한다. 관측 실패는 이미 커밋한 연장을 되돌리지 않는다.
     *
     * @param worker 동일 registry의 RENEW 증명
     * @param jobKey 실제 BATCH 작업 UUID
     * @param expected 원래 시도의 닫힌 tuple, null 불가
     * @return 갱신 후 관측한 제한이며 chat 권위가 아님
     * @throws GradeRemoteExecutionProtocol.Failure 실패해도 이미 연장한 임대가 남을 수 있음
     */
    public GradeRemoteExecutionProtocol.RenewReply renewApproved(
            VerifiedWorker worker,
            UUID jobKey,
            GradeRemoteExecutionProtocol.AttemptRequest expected) {
        remoteRequest(worker, jobKey, expected);
        if (testJob(jobKey)) return testRenew(worker, jobKey, expected);
        return remoteCall(
                () -> {
                    discoverPermission(worker, jobKey, Action.RENEW);
                    transaction.executeWithoutResult(status -> renewalPreflight());
                    var renewed =
                            leases.renew(jobKey, worker.workerKey(), expected.leaseGen())
                                    .orElseThrow(GradeStartService::rejected);
                    var observation =
                            transaction.execute(
                                    status ->
                                            remoteObservation(
                                                    worker,
                                                    jobKey,
                                                    expected,
                                                    Action.RENEW,
                                                    renewed));
                    if (observation == null) throw rejected();
                    return GradeRemoteExecutionProtocol.renew(
                            jobKey, expected, observation.export());
                });
    }

    /** 출처 종류만 발견한다. 이 조회는 잠금·인가가 아니며 두 경로 모두 잠근 부모를 다시 검사한다. */
    private boolean testJob(UUID key) {
        return remoteCall(
                () -> {
                    var kinds =
                            jdbc.queryForList(
                                    "SELECT report_id FROM public.grade_job WHERE job_key=?",
                                    Long.class,
                                    key);
                    if (kinds.size() != 1) throw rejected();
                    return kinds.getFirst() != null;
                });
    }

    private TestPreparation testPreparation() {
        if (testPolicy == null || crypto == null) throw rejected();
        return sources.prepareTest(testPolicy);
    }

    private GradeRemoteExecutionProtocol.StartReply testStartApproved(
            VerifiedWorker worker, UUID key, long generation) {
        return remoteCall(
                () -> {
                    var prepared = testPreparation();
                    discoverPermission(worker, key, Action.START);
                    var reserved =
                            transaction.execute(
                                    status -> reserveTest(worker, key, generation, prepared));
                    if (reserved == null) throw rejected();
                    if (reserved.replay())
                        return GradeRemoteExecutionProtocol.start(
                                key, generation, reserved.attempt(), null, null);
                    credentials.requirePermission(
                            worker, Action.START, reserved.root().runtimeCode());
                    return GradeRemoteExecutionProtocol.start(
                            key,
                            generation,
                            reserved.attempt(),
                            reserved.input(),
                            reserved.observation().export());
                });
    }

    private GradeRemoteExecutionProtocol.FenceReply testFence(
            VerifiedWorker worker, UUID key, GradeRemoteExecutionProtocol.AttemptRequest expected) {
        return remoteCall(
                () -> {
                    var prepared = testPreparation();
                    discoverPermission(worker, key, Action.START);
                    var observation =
                            transaction.execute(
                                    status ->
                                            observeTest(
                                                    worker,
                                                    key,
                                                    expected,
                                                    Action.START,
                                                    prepared,
                                                    null));
                    if (observation == null) throw rejected();
                    return GradeRemoteExecutionProtocol.fence(key, expected, observation.export());
                });
    }

    private GradeRemoteExecutionProtocol.RenewReply testRenew(
            VerifiedWorker worker, UUID key, GradeRemoteExecutionProtocol.AttemptRequest expected) {
        return remoteCall(
                () -> {
                    var prepared = testPreparation();
                    discoverPermission(worker, key, Action.RENEW);
                    transaction.executeWithoutResult(status -> renewalPreflight());
                    var renewed =
                            leases.renew(key, worker.workerKey(), expected.leaseGen())
                                    .orElseThrow(GradeStartService::rejected);
                    var observation =
                            transaction.execute(
                                    status ->
                                            observeTest(
                                                    worker,
                                                    key,
                                                    expected,
                                                    Action.RENEW,
                                                    prepared,
                                                    renewed));
                    if (observation == null) throw rejected();
                    return GradeRemoteExecutionProtocol.renew(key, expected, observation.export());
                });
    }

    private GradeRemoteExecutionProtocol.ApprovedExecutionInput approvedTest(
            TestRootEvidence root,
            Job current,
            VerifiedRuntime runtime,
            byte[] bytes,
            String snapshotHash,
            String owner,
            long generation,
            int attempt) {
        if (current.deadlineAt() == null) throw rejected();
        var installed = installations.get(root.runtimeCode());
        if (installed == null) throw rejected();
        var wireRuntime =
                GradeRemoteExecutionProtocol.runtime(
                        installed.registrationManifest(),
                        runtime.configHash(),
                        root.runtimeEpoch());
        var model = GradeRemoteExecutionProtocol.model(bytes);
        var hash =
                GradeRemoteExecutionProtocol.testOriginalHash(
                        root,
                        owner,
                        generation,
                        attempt,
                        current.deadlineAt().toInstant(),
                        wireRuntime,
                        model);
        return GradeRemoteExecutionProtocol.testInput(
                root.jobKey(),
                generation,
                attempt,
                current.deadlineAt().toInstant(),
                root.snapshotId(),
                snapshotHash,
                root.rubricHash(),
                root.reportHash(),
                wireRuntime,
                model,
                hash);
    }

    private TestMaterial testMaterial(
            UUID key, TestPreparation prepared, VerifiedWorker worker, Action action) {
        var locked = sources.lockTestRoots(key, testPolicy, prepared);
        var root = sources.requireTestExecutionEligibility(locked);
        credentials.requirePermission(worker, action, root.runtimeCode());
        var installed = installations.get(root.runtimeCode());
        if (installed == null) throw rejected();
        var runtime =
                installed.verify(
                        runtimes.getRuntimeDetail(root.runtimeId())
                                .orElseThrow(GradeStartService::rejected));
        var verified = frozenInputs.verifiedTestInput(locked, runtime, crypto);
        if (!sameTestSource(root, verified.root())) throw rejected();
        var semantic =
                FrozenModelProjection.projectTest(verified.frozenSnapshot(), verified.report());
        if (!semantic.report().equals(verified.report())) throw rejected();
        return new TestMaterial(
                root,
                runtime,
                semantic.payloadBytes(),
                verified.frozenSnapshot().payloadHash(),
                locked);
    }

    private static boolean sameTestSource(TestRootEvidence a, TestRootEvidence b) {
        return a.jobId() == b.jobId()
                && a.jobKey().equals(b.jobKey())
                && a.reportId() == b.reportId()
                && a.testId() == b.testId()
                && a.storyId() == b.storyId()
                && a.versionId() == b.versionId()
                && a.snapshotId() == b.snapshotId()
                && a.runtimeId() == b.runtimeId()
                && a.runtimeCode().equals(b.runtimeCode())
                && a.configHash().equals(b.configHash())
                && a.runtimeEpoch() == b.runtimeEpoch()
                && a.snapshotRev() == b.snapshotRev()
                && a.snapshotFormat() == b.snapshotFormat()
                && a.policyCode().equals(b.policyCode())
                && a.payloadHash().equals(b.payloadHash())
                && a.reportHash().equals(b.reportHash())
                && a.rubricHash().equals(b.rubricHash())
                && a.sourceDraftRev() == b.sourceDraftRev();
    }

    private void requireTestDeadline(TestRootEvidence root, Job current) {
        var accepted =
                jdbc.queryForObject(
                        "SELECT accepted_at FROM public.test_report WHERE id=?",
                        OffsetDateTime.class,
                        root.reportId());
        if (accepted == null
                || current.acceptedAt() == null
                || !accepted.toInstant().equals(current.acceptedAt().toInstant())
                || current.deadlineAt() == null
                || !current.deadlineAt().toInstant().equals(accepted.plusSeconds(120).toInstant()))
            throw rejected();
    }

    private Observation observeTestClock(TestRootEvidence root, Job current) {
        requireTestDeadline(root, current);
        long queryNano = System.nanoTime();
        var now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        var observation =
                new Observation(
                        now == null ? null : now.toInstant(),
                        current.deadlineAt().toInstant(),
                        current.leaseUntil() == null ? null : current.leaseUntil().toInstant(),
                        current.deadlineAt().toInstant(),
                        queryNano);
        observation.export();
        return observation;
    }

    private record TestMaterial(
            TestRootEvidence root,
            VerifiedRuntime runtime,
            byte[] bytes,
            String snapshotHash,
            com.reasoning.common.grading.repository.GradeSourceRepository.LockedTestSource
                    locked) {}

    private record TestReservation(
            TestRootEvidence root,
            int attempt,
            boolean replay,
            GradeRemoteExecutionProtocol.ApprovedExecutionInput input,
            Observation observation) {}

    private TestReservation reserveTest(
            VerifiedWorker worker, UUID key, long generation, TestPreparation prepared) {
        var material = testMaterial(key, prepared, worker, Action.START);
        var root = material.root();
        Job current = job(root.jobId());
        requireOwner(current, worker, generation);
        requireTestDeadline(root, current);
        var attempts =
                jdbc.query(
                        "SELECT attempt_no,lease_gen,worker_key,state FROM public.grade_attempt"
                                + " WHERE job_id=? ORDER BY attempt_no FOR UPDATE",
                        (row, index) ->
                                new Attempt(
                                        row.getInt("attempt_no"),
                                        row.getLong("lease_gen"),
                                        row.getString("worker_key"),
                                        row.getString("state")),
                        root.jobId());
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (now == null
                || current.leaseUntil() == null
                || current.deadlineAt() == null
                || !now.isBefore(current.leaseUntil())
                || !now.isBefore(current.deadlineAt())
                || current.callCount() < 0
                || current.callCount() > 3)
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_EXPIRED);
        for (Attempt attempt : attempts) {
            if (attempt.generation() == generation) {
                if (!worker.workerKey().equals(attempt.worker())
                        || !"RUNNING".equals(attempt.state())
                        || attempt.number() != current.callCount()) throw rejected();
                return new TestReservation(root, attempt.number(), true, null, null);
            }
        }
        if (attempts.size() != current.callCount()
                || attempts.stream().anyMatch(attempt -> "RUNNING".equals(attempt.state()))
                || current.callCount() >= 3) throw rejected();
        int number = current.callCount() + 1;
        var approved =
                approvedTest(
                        root,
                        current,
                        material.runtime(),
                        material.bytes(),
                        material.snapshotHash(),
                        worker.workerKey(),
                        generation,
                        number);
        int inserted =
                jdbc.update(
                        "INSERT INTO"
                            + " public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,started_at,state)"
                            + " SELECT id,?,lease_gen,worker_key,?,'RUNNING' FROM public.grade_job"
                            + " WHERE id=? AND report_id=? AND state='RUNNING' AND worker_key=? AND"
                            + " lease_gen=? AND call_count=? AND clock_timestamp()<lease_until AND"
                            + " clock_timestamp()<deadline_at",
                        number,
                        now,
                        root.jobId(),
                        root.reportId(),
                        worker.workerKey(),
                        generation,
                        current.callCount());
        if (inserted != 1) throw rejected();
        var updated =
                jdbc.query(
                        "UPDATE public.grade_job SET call_count=call_count+1,updated_at=? WHERE"
                            + " id=? AND report_id=? AND state='RUNNING' AND worker_key=? AND"
                            + " lease_gen=? AND call_count=? AND clock_timestamp()<lease_until AND"
                            + " clock_timestamp()<deadline_at RETURNING call_count",
                        (row, index) -> row.getInt(1),
                        now,
                        root.jobId(),
                        root.reportId(),
                        worker.workerKey(),
                        generation,
                        current.callCount());
        if (updated.size() != 1 || updated.getFirst() != number) throw rejected();
        sources.requireTestExecutionEligibility(material.locked());
        var reread = job(root.jobId());
        requireOwner(reread, worker, generation);
        requireTestDeadline(root, reread);
        if (reread.callCount() != number
                || !current.deadlineAt().equals(reread.deadlineAt())
                || !current.leaseUntil().equals(reread.leaseUntil())) throw rejected();
        var rows =
                jdbc.query(
                        "SELECT lease_gen,worker_key,state,completion_data,started_at FROM"
                                + " public.grade_attempt WHERE job_id=? AND attempt_no=?",
                        (row, index) ->
                                new Readback(
                                        row.getString("worker_key"),
                                        row.getLong("lease_gen"),
                                        number,
                                        row.getString("state"),
                                        row.getObject("completion_data") != null,
                                        row.getObject("started_at", OffsetDateTime.class)),
                        root.jobId(),
                        number);
        if (rows.size() != 1
                || !"RUNNING".equals(rows.getFirst().state())
                || rows.getFirst().receipt()
                || !worker.workerKey().equals(rows.getFirst().worker())
                || rows.getFirst().generation() != generation
                || !now.equals(rows.getFirst().started())) throw rejected();
        return new TestReservation(root, number, false, approved, observeTestClock(root, reread));
    }

    private Observation observeTest(
            VerifiedWorker worker,
            UUID key,
            GradeRemoteExecutionProtocol.AttemptRequest expected,
            Action action,
            TestPreparation prepared,
            GradeLeaseRepository.Lease renewed) {
        var material = testMaterial(key, prepared, worker, action);
        var root = material.root();
        var rows =
                jdbc.queryForList(
                        "SELECT worker_key,lease_gen,state,completion_data FROM"
                            + " public.grade_attempt WHERE job_id=? AND attempt_no=? FOR UPDATE",
                        root.jobId(),
                        expected.attemptNo());
        if (rows.size() != 1) throw rejected();
        Job current = job(root.jobId());
        requireOwner(current, worker, expected.leaseGen());
        requireTestDeadline(root, current);
        var attempt = rows.getFirst();
        if (!"RUNNING".equals(attempt.get("state"))
                || attempt.get("completion_data") != null
                || !worker.workerKey().equals(attempt.get("worker_key"))
                || ((Number) attempt.get("lease_gen")).longValue() != expected.leaseGen()
                || current.callCount() != expected.attemptNo()) throw rejected();
        var approved =
                approvedTest(
                        root,
                        current,
                        material.runtime(),
                        material.bytes(),
                        material.snapshotHash(),
                        worker.workerKey(),
                        expected.leaseGen(),
                        expected.attemptNo());
        if (!expected.originalAttemptHash().equals(approved.originalAttemptHash()))
            throw rejected();
        if (renewed != null
                && (renewed.jobId() != root.jobId()
                        || !key.equals(renewed.jobKey())
                        || !worker.workerKey().equals(renewed.workerKey())
                        || renewed.leaseGen() != expected.leaseGen()
                        || !renewed.deadlineAt().equals(current.deadlineAt())
                        || !renewed.leaseUntil().equals(current.leaseUntil()))) throw rejected();
        sources.requireTestExecutionEligibility(material.locked());
        return observeTestClock(root, current);
    }

    /**
     * 발견 SQL 전에 tuple·ambient TX·원격 DS 조립을 검사한다. body는 신원을 선택하지 않는다.
     *
     * @param worker null 불가인 실제 registry 증명; 허용은 후속 검사
     * @param key null·영 UUID 아닌 BATCH 작업 식별자
     * @param generation 양수 long 임대 세대
     * @throws GradeRemoteExecutionProtocol.Failure 형식은 INVALID_REMOTE_REQUEST, ambient TX는
     *     REMOTE_REQUIRES_SEPARATE_TRANSACTION, 미지원 관리자·DS는 REMOTE_EXECUTION_UNAVAILABLE
     */
    private void remoteEntry(VerifiedWorker worker, UUID key, long generation) {
        GradeRemoteExecutionProtocol.identity(key, generation, 1);
        if (worker == null)
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.INVALID_REMOTE_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_REQUIRES_SEPARATE_TRANSACTION);
        if (!remoteAssembly)
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_UNAVAILABLE);
    }

    /**
     * null 요청과 원격 입구 조건을 SQL 전에 검사한다.
     *
     * @param worker null 불가인 실제 registry 증명
     * @param key null·영 UUID 아닌 BATCH 작업 식별자
     * @param request null 아닌 양수 세대·1~3 시도·소문자64hash tuple
     * @throws GradeRemoteExecutionProtocol.Failure null은 INVALID_REMOTE_REQUEST; 나머지는 remoteEntry
     *     고정 오류
     */
    private void remoteRequest(
            VerifiedWorker worker, UUID key, GradeRemoteExecutionProtocol.AttemptRequest request) {
        if (request == null)
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.INVALID_REMOTE_REQUEST);
        remoteEntry(worker, key, request.leaseGen());
    }

    /**
     * 갱신 전에 잠금 없는 자체 TX의 실제 연결·모드를 확인한다. 이후 별도 TX의 성공을 보장하는 증명은 아니다.
     *
     * @throws GradeRemoteExecutionProtocol.Failure 같은 DS의 writable READ_COMMITTED 연결이 아니면 저장소 거절
     */
    private void renewalPreflight() {
        var source = jdbc.getDataSource();
        Object resource =
                TransactionSynchronizationManager.getResource(Objects.requireNonNull(source));
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(resource
                        instanceof org.springframework.jdbc.datasource.ConnectionHolder holder)
                || holder.getConnectionHandle() == null)
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_UNAVAILABLE);
        Boolean bound =
                jdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                                connection -> {
                                    var target =
                                            org.springframework.jdbc.datasource.DataSourceUtils
                                                    .getTargetConnection(connection);
                                    return org.springframework.jdbc.datasource.DataSourceUtils
                                                    .isConnectionTransactional(target, source)
                                            && target
                                                    == org.springframework.jdbc.datasource
                                                            .DataSourceUtils.getTargetConnection(
                                                            holder.getConnection())
                                            && !target.getAutoCommit();
                                });
        Boolean mode =
                jdbc.queryForObject(
                        "SELECT current_setting('transaction_isolation')='read committed'"
                                + " AND current_setting('transaction_read_only')='off'",
                        Boolean.class);
        if (!Boolean.TRUE.equals(bound) || !Boolean.TRUE.equals(mode))
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_UNAVAILABLE);
    }

    /**
     * 코드 하나를 비잠금 조회하고 같은 registry의 허용을 정상 루트 잠금 전에 검사한다.
     *
     * @param worker null 아닌 실제 registry 증명
     * @param key null·영 UUID 아닌 실제 BATCH 작업 식별자
     * @param action null 아닌 START 또는 RENEW
     * @throws IllegalStateException 발견 행이 하나가 아니면 START_NOT_CURRENT
     * @throws SecurityException registry·행동·runtime 허용이 다르면 WORKER_NOT_AUTHORIZED
     * @throws DataAccessException 발견 SQL이 실패하면 발생하며 원격 입구에서 고정 오류로 변환
     */
    private void discoverPermission(VerifiedWorker worker, UUID key, Action action) {
        var codes =
                jdbc.queryForList(
                        "SELECT r.code FROM public.grade_job j JOIN public.grade_runtime r"
                                + " ON r.id=j.runtime_id WHERE j.job_key=?",
                        String.class,
                        key);
        if (codes.size() != 1) throw rejected();
        credentials.requirePermission(worker, action, codes.getFirst());
    }

    /**
     * 실제 예외 타입을 원인 없는 원격 오류로 변환하며 메시지를 분류·반사하지 않는다.
     *
     * @param <T> 원격 작업의 반환 타입
     * @param operation null 아닌 내부 작업; 실제 예약·관측·갱신 부작용을 수행할 수 있음
     * @return 작업이 반환한 값 그대로
     * @throws GradeRemoteExecutionProtocol.Failure 기존 고정 오류는 유지; 보안은 FORBIDDEN, DB·TX는 UNAVAILABLE,
     *     상태·입력은 NOT_CURRENT인 REMOTE_EXECUTION 고정 코드로 변환
     */
    private static <T> T remoteCall(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (GradeRemoteExecutionProtocol.Failure failure) {
            throw failure;
        } catch (SecurityException failure) {
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_FORBIDDEN);
        } catch (com.reasoning.common.auth.service.AuthException failure) {
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_NOT_CURRENT);
        } catch (DataAccessException | TransactionException failure) {
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_UNAVAILABLE);
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_NOT_CURRENT);
        }
    }

    /**
     * 이미 잠근 부모의 현재 자격·24개 결속·설치를 plain read로 대조하며 다른 부모를 추가로 잠그지 않는다.
     *
     * @param root null 아닌 같은 source 저장소·현재 writable READ_COMMITTED TX의 실제 잠금 증거
     * @throws GradeRemoteExecutionProtocol.Failure batch 시각이 없거나 만료면 REMOTE_EXECUTION_EXPIRED
     * @throws IllegalStateException 현재 자격·결속·설치 불일치면 원문 없는 source·start·설치 고정 오류
     * @throws DataAccessException 실제 clock·runtime 조회 실패; 원격 입구에서 UNAVAILABLE로 변환
     */
    private void requireFreshBinding(LockedSource root) {
        var now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (now == null || !now.isBefore(root.batchCreatedAt().plusHours(24)))
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_EXPIRED);
        var fresh = sources.requireExecutionEligibility(root);
        var facts =
                java.util.List.<Object>of(
                        fresh.jobId(),
                        fresh.jobKey(),
                        fresh.batchId(),
                        fresh.batchKey(),
                        fresh.creatorId(),
                        fresh.storyId(),
                        fresh.versionId(),
                        fresh.snapshotId(),
                        fresh.runtimeId(),
                        fresh.runtimeCode(),
                        fresh.runtimeEpoch(),
                        fresh.configHash(),
                        fresh.payloadHash(),
                        fresh.datasetHash(),
                        fresh.rubricHash(),
                        fresh.inputHash(),
                        fresh.sampleCode(),
                        fresh.repeatNo(),
                        fresh.purpose(),
                        fresh.versionRev(),
                        fresh.snapshotRev(),
                        fresh.snapshotFormat(),
                        fresh.policyCode(),
                        fresh.batchCreatedAt());
        if (!binding(root).equals(facts)) throw rejected();
        var installed = installations.get(root.runtimeCode());
        if (installed == null) throw rejected();
        installed.verify(
                runtimes.getRuntimeDetail(root.runtimeId())
                        .orElseThrow(GradeStartService::rejected));
    }

    /**
     * 정상 루트·전체 frozen·설치 검증 뒤 실제 번호 시도를 잠그고 원래 digest와 현재 임대를 대조한다.
     *
     * @param worker null 아닌 같은 registry의 실제 증명
     * @param key null·영 UUID 아닌 BATCH 작업 식별자
     * @param expected null 아닌 원래 양수 세대·1~3 시도·소문자64hash tuple
     * @param action null 아닌 START 또는 RENEW
     * @param renewed 실제 갱신 저장소의 반환 임대; fence이면 null
     * @return 커밋 전에 확인한 private clock 관측; 외부 반환 전 다시 export해야 함
     * @throws SecurityException 현재 locked runtime 허용 불일치의 고정 registry 오류
     * @throws IllegalStateException 원래 출처·설치·tuple·시도·갱신 사실 불일치의 고정 오류
     * @throws GradeRemoteExecutionProtocol.Failure 만료·예산 계약 불일치의 고정 오류
     * @throws DataAccessException 실제 SQL 실패; 원격 입구에서 UNAVAILABLE로 변환
     */
    private Observation remoteObservation(
            VerifiedWorker worker,
            UUID key,
            GradeRemoteExecutionProtocol.AttemptRequest expected,
            Action action,
            GradeLeaseRepository.Lease renewed) {
        var root = sources.lockRoots(key);
        credentials.requirePermission(worker, action, root.runtimeCode());
        var installed = installations.get(root.runtimeCode());
        if (installed == null) throw rejected();
        var runtime =
                installed.verify(
                        runtimes.getRuntimeDetail(root.runtimeId())
                                .orElseThrow(GradeStartService::rejected));
        var dataset = frozenInputs.dataset(root, runtime);
        var selected = dataset.select(root.sampleCode());
        requireFreshBinding(root);
        var rows =
                jdbc.queryForList(
                        "SELECT worker_key,lease_gen,state,completion_data FROM"
                            + " public.grade_attempt WHERE job_id=? AND attempt_no=? FOR UPDATE",
                        root.jobId(),
                        expected.attemptNo());
        if (rows.size() != 1) throw rejected();
        var attempt = rows.getFirst();
        var current = job(root.jobId());
        requireOwner(current, worker, expected.leaseGen());
        if (!"RUNNING".equals(attempt.get("state"))
                || attempt.get("completion_data") != null
                || !worker.workerKey().equals(attempt.get("worker_key"))
                || ((Number) attempt.get("lease_gen")).longValue() != expected.leaseGen()
                || current.callCount() != expected.attemptNo()) throw rejected();
        var input =
                approved(
                        binding(root),
                        key,
                        expected.leaseGen(),
                        expected.attemptNo(),
                        worker.workerKey(),
                        current.deadlineAt(),
                        dataset,
                        selected,
                        runtime);
        if (!expected.originalAttemptHash().equals(input.originalAttemptHash())) throw rejected();
        if (renewed != null
                && (renewed.jobId() != root.jobId()
                        || !key.equals(renewed.jobKey())
                        || !worker.workerKey().equals(renewed.workerKey())
                        || renewed.leaseGen() != expected.leaseGen()
                        || !renewed.deadlineAt().equals(current.deadlineAt())
                        || !renewed.leaseUntil().equals(current.leaseUntil()))) throw rejected();
        return observe(root, current);
    }

    /**
     * 메모리의 실제 선택에서 MODEL 투영 또는 ENGINE_ERROR 제어만 만들고 private digest를 계산한다.
     *
     * @param facts null 아닌 원래 순서 24개 서버 결속 사실
     * @param key null·영 UUID 아닌 작업 식별자
     * @param generation 양수 long 원래 임대 세대
     * @param attempt 1~3 실제 시도 번호
     * @param owner null·공백 아닌 실제 소유자 키
     * @param deadline null 아닌 원래 DB 마감
     * @param dataset null 아닌 전체 검증 frozen 집합
     * @param selected null 아닌 같은 집합의 승인 선택; INPUT_ERROR 불가
     * @param runtime null 아닌 실제 설치 검증값
     * @return private 출처·기대값을 제외한 승인 BATCH 봉투
     * @throws IllegalStateException 선택·제어·설치·양수 epoch가 다르면 START_NOT_CURRENT
     * @throws GradeRemoteExecutionProtocol.Failure 봉투 값 계약이 다르면 INVALID_REMOTE_REQUEST
     * @throws IllegalArgumentException 실제 projection·canonical 인코딩이 실패하면 고정 codec 오류
     */
    private GradeRemoteExecutionProtocol.ApprovedExecutionInput approved(
            java.util.List<Object> facts,
            UUID key,
            long generation,
            int attempt,
            String owner,
            OffsetDateTime deadline,
            ValidatedDataset dataset,
            SelectedSample selected,
            VerifiedRuntime runtime) {
        if (selected.report() == null || "INPUT_ERROR".equals(selected.kind()) || deadline == null)
            throw rejected();
        var installed = installations.get((String) facts.get(9));
        if (installed == null) throw rejected();
        if (((Number) facts.get(10)).longValue() < 0) throw rejected();
        var wireRuntime =
                GradeRemoteExecutionProtocol.runtime(
                        installed.registrationManifest(),
                        runtime.configHash(),
                        ((Number) facts.get(10)).longValue());
        GradeRemoteExecutionProtocol.CanonicalModelInput model = null;
        GradeRemoteExecutionProtocol.Fault fault = null;
        if ("ENGINE_ERROR".equals(selected.kind())) {
            if (selected.fault() == null) throw rejected();
            fault =
                    GradeRemoteExecutionProtocol.fault(
                            selected.fault().type(), selected.fault().failRuns());
        } else {
            if (selected.fault() != null) throw rejected();
            model =
                    GradeRemoteExecutionProtocol.model(
                            FrozenModelProjection.project(dataset, selected).payloadBytes());
        }
        var hash =
                GradeRemoteExecutionProtocol.originalHash(
                        facts,
                        owner,
                        generation,
                        attempt,
                        deadline.toInstant(),
                        wireRuntime,
                        model,
                        fault);
        return GradeRemoteExecutionProtocol.input(
                key,
                generation,
                attempt,
                deadline.toInstant(),
                ((Number) facts.get(7)).longValue(),
                (String) facts.get(12),
                (String) facts.get(14),
                (String) facts.get(13),
                wireRuntime,
                model,
                fault,
                hash);
    }

    /**
     * 잠금 대기 이후 최종 실제 clock 쿼리 직전에 단조 anchor를 잡고 보수적 예산을 확인한다.
     *
     * @param root null 아닌 현재 TX의 실제 잠금 증거; batch 생성 시각은 null 불가
     * @param current null 아닌 이미 잠근 job의 현재 행; null deadline·lease는 export에서 거절
     * @return wire로 노출하지 않는 실제 시각·단조 anchor 관측
     * @throws GradeRemoteExecutionProtocol.Failure null·만료·범위·산술 실패면 REMOTE_EXECUTION_EXPIRED
     * @throws DataAccessException 실제 DB clock 조회 실패
     */
    private Observation observe(LockedSource root, Job current) {
        long queryNano = System.nanoTime();
        var now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        var observation =
                new Observation(
                        now == null ? null : now.toInstant(),
                        current.deadlineAt() == null ? null : current.deadlineAt().toInstant(),
                        current.leaseUntil() == null ? null : current.leaseUntil().toInstant(),
                        root.batchCreatedAt().plusHours(24).toInstant(),
                        queryNano);
        observation.export();
        return observation;
    }

    /** private 시계 사실은 wire로 내보내지 않는다. */
    private record Observation(
            java.time.Instant now,
            java.time.Instant deadline,
            java.time.Instant lease,
            java.time.Instant batchDeadline,
            long queryNano) {
        /**
         * 쿼리 직전부터 현재까지 실제 경과를 차감한다. 커밋·응답 조립 지연도 호출 시점에 포함한다.
         *
         * @return job 1~120000ms·lease 1~30000ms이며 lease가 job 이하인 내림 제한
         * @throws GradeRemoteExecutionProtocol.Failure null 시각·만료·음수 경과·overflow·1ms 미만이면
         *     REMOTE_EXECUTION_EXPIRED; 이미 커밋한 소비를 환급하지 않음
         */
        private GradeRemoteExecutionProtocol.Limits export() {
            long elapsed;
            try {
                elapsed = Math.subtractExact(System.nanoTime(), queryNano);
            } catch (ArithmeticException failure) {
                throw GradeRemoteExecutionProtocol.failure(
                        GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_EXPIRED);
            }
            return GradeRemoteExecutionProtocol.limits(
                    now, deadline, lease, batchDeadline, elapsed);
        }

        @Override
        public String toString() {
            return "Observation[redacted]";
        }
    }

    /**
     * 현재 출처·설치·전체 사본·실제 임대 증명을 확인한 뒤 호출 예산을 정확히 한 번 영속 예약한다. 같은 활성 임대 재전송은 기존 RUNNING 예약만 반환하며 재호출
     * 허가가 아니다.
     *
     * @param worker 이 서비스의 레지스트리로 실제 인증한 증명, null 불가
     * @param jobKey 요청한 작업 UUID, null 및 영 UUID 불가
     * @param leaseGen 현재 양수 임대 세대
     * @return 자체 트랜잭션 커밋 뒤에만 반환하는 불변 내부 예약
     * @throws IllegalArgumentException 입력 또는 전체 사본 계약이 잘못된 경우, 원문 없는 고정 오류
     * @throws SecurityException 실제 worker·행동·runtime 허용 범위가 불일치한 경우
     * @throws IllegalStateException 외부 TX·출처·설치·임대·예산이 유효하지 않은 경우
     * @throws DataAccessResourceFailureException 원인 없는 START_STORAGE_FAILURE
     */
    public Reservation start(VerifiedWorker worker, UUID jobKey, long leaseGen) {
        if (worker == null || jobKey == null || jobKey.equals(new UUID(0, 0)) || leaseGen <= 0) {
            throw new IllegalArgumentException("INVALID_START_INPUT");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("START_REQUIRES_SEPARATE_TRANSACTION");
        }
        try {
            // 이 발견 조회는 코드 하나만 읽으며 허용 범위 실패는 루트 잠금을 취득하지 않는다.
            var codes =
                    jdbc.queryForList(
                            """
                            SELECT r.code FROM public.grade_job j
                            JOIN public.grade_runtime r ON r.id=j.runtime_id WHERE j.job_key=?
                            """,
                            String.class,
                            jobKey);
            if (codes.size() != 1) throw rejected();
            credentials.requirePermission(worker, Action.START, codes.getFirst());
            return transaction.execute(status -> reserve(worker, jobKey, leaseGen, false));
        } catch (DataAccessException | TransactionException failure) {
            throw new DataAccessResourceFailureException("START_STORAGE_FAILURE");
        }
    }

    /**
     * 동일 JDBC 실제 루트를 잠그고 제한된 검증 뒤 현재 권한을 재검사한다. 새 상위 잠금은 추가하지 않는다.
     *
     * @param worker null 아닌 실제 인증 증명
     * @param key null 아닌 요청 UUID
     * @param generation 양수 임대 세대
     * @param remote true이면 같은 TX에서 atomic readback과 private 상대 예산 관측을 추가함
     * @return 신규 또는 재전송 내부 예약
     * @throws IllegalStateException 현재 출처·설치·임대·예산 불일치
     */
    private Reservation reserve(VerifiedWorker worker, UUID key, long generation, boolean remote) {
        LockedSource root = sources.lockRoots(key);
        credentials.requirePermission(worker, Action.START, root.runtimeCode());
        Job initial = job(root.jobId());
        requireOwner(initial, worker, generation);
        var installation = installations.get(root.runtimeCode());
        if (installation == null) throw new IllegalStateException("INSTALLED_RUNTIME_MISMATCH");
        VerifiedRuntime runtime =
                installation.verify(
                        runtimes.getRuntimeDetail(root.runtimeId())
                                .orElseThrow(GradeStartService::rejected));
        ValidatedDataset dataset = frozenInputs.dataset(root, runtime);
        SelectedSample selection = dataset.select(root.sampleCode());
        if (selection.report() == null || "INPUT_ERROR".equals(selection.kind())) {
            throw new IllegalStateException("COORDINATOR_ONLY_FIXTURE");
        }
        // 공개 메타데이터가 아니라 이 저장소·이 TX가 만든 실제 LockedSource만 승인에 사용한다.
        if (remote) requireFreshBinding(root);
        else sources.requireExecutionEligibility(root);
        Job current = job(root.jobId());
        var attempts =
                jdbc.query(
                        """
                        SELECT attempt_no,lease_gen,worker_key,state FROM public.grade_attempt
                        WHERE job_id=? ORDER BY attempt_no FOR UPDATE
                        """,
                        (row, index) ->
                                new Attempt(
                                        row.getInt("attempt_no"),
                                        row.getLong("lease_gen"),
                                        row.getString("worker_key"),
                                        row.getString("state")),
                        root.jobId());
        long queryStartedNano = System.nanoTime();
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        requireOwner(current, worker, generation);
        if (remote
                && (now == null
                        || current.leaseUntil() == null
                        || current.deadlineAt() == null
                        || !now.isBefore(current.leaseUntil())
                        || !now.isBefore(current.deadlineAt())
                        || !now.isBefore(root.batchCreatedAt().plusHours(24))))
            throw GradeRemoteExecutionProtocol.failure(
                    GradeRemoteExecutionProtocol.FailureCode.REMOTE_EXECUTION_EXPIRED);
        if (now == null
                || current.leaseUntil() == null
                || current.deadlineAt() == null
                || !now.isBefore(current.leaseUntil())
                || !now.isBefore(current.deadlineAt())
                || !now.isBefore(root.batchCreatedAt().plusHours(24))
                || current.callCount() < 0
                || current.callCount() > 3) throw rejected();
        JobDeadline deadline =
                JobDeadline.fromDatabaseClock(
                        now.toInstant(), current.deadlineAt().toInstant(), queryStartedNano);
        for (Attempt attempt : attempts) {
            if (attempt.generation() == generation) {
                if (!worker.workerKey().equals(attempt.worker())
                        || !"RUNNING".equals(attempt.state())
                        || attempt.number() != current.callCount()) throw rejected();
                return new Reservation(
                        this,
                        root,
                        attempt.number(),
                        generation,
                        current,
                        true,
                        dataset,
                        selection,
                        runtime,
                        deadline,
                        null);
            }
        }
        if (attempts.size() != current.callCount()
                || attempts.stream().anyMatch(attempt -> "RUNNING".equals(attempt.state()))) {
            throw rejected();
        }
        if (current.callCount() >= 3) throw new IllegalStateException("START_BUDGET_EXHAUSTED");
        int number = current.callCount() + 1;
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,started_at,state)
                        SELECT id,?,lease_gen,worker_key,?,'RUNNING' FROM public.grade_job
                        WHERE id=? AND state='RUNNING' AND worker_key=? AND lease_gen=? AND call_count=?
                            AND clock_timestamp()<lease_until AND clock_timestamp()<deadline_at
                            AND clock_timestamp()<?
                        """,
                        number,
                        now,
                        root.jobId(),
                        worker.workerKey(),
                        generation,
                        current.callCount(),
                        root.batchCreatedAt().plusHours(24));
        if (inserted != 1) throw rejected();
        var updated =
                jdbc.query(
                        """
                        UPDATE public.grade_job SET call_count=call_count+1,updated_at=?
                        WHERE id=? AND state='RUNNING' AND worker_key=? AND lease_gen=? AND call_count=?
                            AND clock_timestamp()<lease_until AND clock_timestamp()<deadline_at
                            AND clock_timestamp()<?
                        RETURNING clock_timestamp()<lease_until AND clock_timestamp()<deadline_at
                            AND clock_timestamp()<?
                        """,
                        (row, index) -> row.getBoolean(1),
                        now,
                        root.jobId(),
                        worker.workerKey(),
                        generation,
                        current.callCount(),
                        root.batchCreatedAt().plusHours(24),
                        root.batchCreatedAt().plusHours(24));
        if (updated.size() != 1 || !Boolean.TRUE.equals(updated.getFirst())) throw rejected();
        Observation observation = null;
        if (remote) {
            requireFreshBinding(root);
            var readback = job(root.jobId());
            requireOwner(readback, worker, generation);
            var actual =
                    jdbc.queryForObject(
                            "SELECT"
                                + " worker_key,lease_gen,attempt_no,state,completion_data,started_at"
                                + " FROM public.grade_attempt WHERE job_id=? AND attempt_no=?",
                            (row, index) ->
                                    new Readback(
                                            row.getString("worker_key"),
                                            row.getLong("lease_gen"),
                                            row.getInt("attempt_no"),
                                            row.getString("state"),
                                            row.getObject("completion_data") != null,
                                            row.getObject("started_at", OffsetDateTime.class)),
                            root.jobId(),
                            number);
            if (readback.callCount() != number
                    || !current.deadlineAt().equals(readback.deadlineAt())
                    || !current.leaseUntil().equals(readback.leaseUntil())
                    || actual == null
                    || !worker.workerKey().equals(actual.worker())
                    || actual.generation() != generation
                    || actual.number() != number
                    || !"RUNNING".equals(actual.state())
                    || actual.receipt()
                    || !now.equals(actual.started())) throw rejected();
            var freshDataset = frozenInputs.dataset(root, runtime);
            if (!java.util.Arrays.equals(
                    dataset.frozenSnapshot().payloadBytes(),
                    freshDataset.frozenSnapshot().payloadBytes())) throw rejected();
            observation = observe(root, readback);
        }
        return new Reservation(
                this,
                root,
                number,
                generation,
                current,
                false,
                dataset,
                selection,
                runtime,
                deadline,
                observation);
    }

    /**
     * 이미 잠근 job의 실제 현재 임대·예산만 다시 읽는다.
     *
     * @param id 실제 잠금 증거의 양수 job 식별자
     * @return 실제 job 임대 사본, null이면 소유자 검사에서 거절
     * @throws DataAccessException 실제 행이 없거나 저장소 조회가 실패하면 호출 입구에서 원인 제거
     */
    private Job job(long id) {
        return jdbc.queryForObject(
                """
                SELECT state,worker_key,lease_gen,lease_until,deadline_at,call_count,accepted_at
                FROM public.grade_job WHERE id=?
                """,
                (row, index) ->
                        new Job(
                                row.getString("state"),
                                row.getString("worker_key"),
                                row.getLong("lease_gen"),
                                row.getObject("lease_until", OffsetDateTime.class),
                                row.getObject("deadline_at", OffsetDateTime.class),
                                row.getInt("call_count"),
                                row.getObject("accepted_at", OffsetDateTime.class)),
                id);
    }

    /**
     * 출처의 STAGED·QUEUED 허용을 실행 임대 권위로 사용하지 않는다.
     *
     * @param job 현재 실제 행, null이면 거절
     * @param worker 입구에서 검사한 실제 증명, null 불가
     * @param generation 양수 요청 임대 세대
     * @throws IllegalStateException 현재 RUNNING 소유자·세대가 다르면 START_NOT_CURRENT
     */
    private static void requireOwner(Job job, VerifiedWorker worker, long generation) {
        if (job == null
                || !"RUNNING".equals(job.state())
                || !worker.workerKey().equals(job.worker())
                || generation != job.generation()) {
            throw rejected();
        }
    }

    /** 원문·해시·설정 없이 거절한다. */
    private static IllegalStateException rejected() {
        return new IllegalStateException("START_NOT_CURRENT");
    }

    private record Job(
            String state,
            String worker,
            long generation,
            OffsetDateTime leaseUntil,
            OffsetDateTime deadlineAt,
            int callCount,
            OffsetDateTime acceptedAt) {}

    private record Attempt(int number, long generation, String worker, String state) {}

    /** 실제 readback의 원문 없는 상태이며 completion 원문은 보관하지 않는다. */
    private record Readback(
            String worker,
            long generation,
            int number,
            String state,
            boolean receipt,
            OffsetDateTime started) {}

    /**
     * 비공개 생성한 INTERNAL 서버 값이다. HTTP 자동 DTO가 아니다. runtime/deadline 자체는 dispatch 권한이 아니며 replay=true인
     * 예약은 이미 예약한 시도를 다시 전송해서는 안 된다. 전체 기대값은 서버 내부에만 유지한다.
     */
    public static final class Reservation {
        private final GradeStartService issuer;
        private final java.util.List<Object> binding;
        private final java.util.concurrent.atomic.AtomicBoolean issued =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final long jobId;
        private final UUID jobKey;
        private final long batchId;
        private final UUID batchKey;
        private final long snapshotId;
        private final long runtimeId;
        private final long runtimeEpoch;
        private final int attemptNo;
        private final long leaseGen;
        private final String workerKey;
        private final OffsetDateTime leaseUntil;
        private final OffsetDateTime deadlineAt;
        private final boolean replay;
        private final ValidatedDataset dataset;
        private final SelectedSample selection;
        private final VerifiedRuntime runtime;
        private final JobDeadline deadline;
        private final Observation observation;

        /**
         * 동일 TX에서 검증·예약한 내부 값만 결합한다. observation만 로컬·재생에서 null이며 나머지 참조는 내부 계약상 null 불가다.
         *
         * @param issuer 이 신규 예약의 실제 START 발행자, null 불가
         * @param root 현재 실제 루트 증거
         * @param number 예약한 1~3 시도 번호
         * @param generation 예약한 양수 임대 세대
         * @param job 같은 TX의 현재 job 사본
         * @param replay 기존 예약 재전송 여부
         * @param dataset 전체 검증한 서버 집합
         * @param selection 해당 집합의 정상 보고서 선택
         * @param runtime 실제 설치 검증값
         * @param deadline 실제 DB 마감으로 생성한 단조 시계 예산
         * @param observation remote 신규 예약의 실제 clock 관측; 로컬·재생은 null
         */
        private Reservation(
                GradeStartService issuer,
                LockedSource root,
                int number,
                long generation,
                Job job,
                boolean replay,
                ValidatedDataset dataset,
                SelectedSample selection,
                VerifiedRuntime runtime,
                JobDeadline deadline,
                Observation observation) {
            this.issuer = issuer;
            binding = binding(root);
            jobId = root.jobId();
            jobKey = root.jobKey();
            batchId = root.batchId();
            batchKey = root.batchKey();
            snapshotId = root.snapshotId();
            runtimeId = root.runtimeId();
            runtimeEpoch = root.runtimeEpoch();
            attemptNo = number;
            leaseGen = generation;
            workerKey = job.worker();
            leaseUntil = job.leaseUntil();
            deadlineAt = job.deadlineAt();
            this.replay = replay;
            this.dataset = dataset;
            this.selection = selection;
            this.runtime = runtime;
            this.deadline = deadline;
            this.observation = observation;
        }

        /**
         * @return 실제 작업 내부 식별자
         */
        public long jobId() {
            return jobId;
        }

        /**
         * @return 실제 작업 UUID, null 아님
         */
        public UUID jobKey() {
            return jobKey;
        }

        /**
         * @return 실제 집합 내부 식별자
         */
        public long batchId() {
            return batchId;
        }

        /**
         * @return 실제 집합 UUID, null 아님
         */
        public UUID batchKey() {
            return batchKey;
        }

        /**
         * @return 실제 사본 내부 식별자
         */
        public long snapshotId() {
            return snapshotId;
        }

        /**
         * @return 실제 설치 등록 내부 식별자
         */
        public long runtimeId() {
            return runtimeId;
        }

        /**
         * @return 예약 당시 잠근 실제 runtime 세대이며 후속 현재성 검사를 대신하지 않음
         */
        public long runtimeEpoch() {
            return runtimeEpoch;
        }

        /**
         * @return 영속 예약한 1~3 시도 번호
         */
        public int attemptNo() {
            return attemptNo;
        }

        /**
         * @return 현재 양수 임대 세대
         */
        public long leaseGen() {
            return leaseGen;
        }

        /**
         * @return 실제 인증한 임대 worker 키, null 아님
         */
        public String workerKey() {
            return workerKey;
        }

        /**
         * @return 고정 임대 만료 시각, null 아님
         */
        public OffsetDateTime leaseUntil() {
            return leaseUntil;
        }

        /**
         * @return 고정 전체 작업 마감 시각, null 아님
         */
        public OffsetDateTime deadlineAt() {
            return deadlineAt;
        }

        /**
         * @return true이면 기존 예약 재전송이며 모델 재호출은 금지
         */
        public boolean replay() {
            return replay;
        }

        /**
         * @return 전체 검증한 불변 서버 집합, null 아님
         */
        public ValidatedDataset dataset() {
            return dataset;
        }

        /**
         * @return 같은 집합에 속한 정상 보고서 선택, null 아님
         */
        public SelectedSample selection() {
            return selection;
        }

        /**
         * @return 실제 설치 인스턴스 결속값, 자체 dispatch 권한 아님
         */
        public VerifiedRuntime runtime() {
            return runtime;
        }

        /**
         * @return 실제 DB 마감에 결속한 단조 시계 예산, 자체 dispatch 권한 아님
         */
        public JobDeadline deadline() {
            return deadline;
        }

        /**
         * @return 기대값·장애 제어를 제외한 매번 새로 투영한 모델 입력 바이트
         */
        public byte[] projectionBytes() {
            return FrozenModelProjection.project(dataset, selection).payloadBytes();
        }

        /**
         * @return 보고서·정답·기대값·사본·설정을 포함하지 않는 예약 식별자
         */
        @Override
        public String toString() {
            return "Reservation[jobId="
                    + jobId
                    + ", attemptNo="
                    + attemptNo
                    + ", leaseGen="
                    + leaseGen
                    + ", replay="
                    + replay
                    + "]";
        }
    }
}
