package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeEventRepository;
import com.reasoning.common.grading.repository.GradeEventRepository.Detail;
import com.reasoning.common.grading.repository.GradeEventRepository.EventKind;
import com.reasoning.common.grading.repository.GradeEventRepository.JobState;
import com.reasoning.common.grading.repository.GradeEventRepository.Reason;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository.LockedSource;
import com.reasoning.common.grading.security.GradeWorkerCredentials;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 서버 전용 STAGED 접수 경계다. dispatch·모델 호출·집합 생성·HTTP 권한은 제공하지 않는다. */
public final class GradeCoordinatorService {
    private final JdbcTemplate jdbc;
    private final GradeSourceRepository sources;
    private final GradeRuntimeRepository runtimes;
    private final GradeFrozenInputRepository frozen;
    private final GradeEventRepository audit;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final String coordinatorKey;
    private final int capacity;
    private final TransactionTemplate transaction;

    /**
     * 실제 설치 지문 계산은 생성 전에 SQL 밖에서 끝내야 한다.
     *
     * @param jdbc 실제 동일 DataSource 도구, null 불가
     * @param manager 동일 DataSource의 실제 JDBC 관리자, null 불가
     * @param credentials SYSTEM 감사 저장소에만 전달하는 등록, null 불가
     * @param actualInstalledVerifiers manifest configId별 실제 설치, null 불가
     * @param trustedCoordinatorKey 신뢰 배포 SYSTEM 식별자, null 불가
     * @param perRuntimeCapacity runtime별 QUEUED+RUNNING 처리 슬롯 수, 양수 정수
     * @throws IllegalArgumentException 관리자·설치·용량 계약 오류
     * @throws IllegalStateException 외부 트랜잭션 안에서 생성한 경우
     */
    public GradeCoordinatorService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            GradeWorkerCredentials credentials,
            Map<String, InstalledRuntimeManifestVerifier> actualInstalledVerifiers,
            String trustedCoordinatorKey,
            int perRuntimeCapacity) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw boundary();
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!(manager instanceof DataSourceTransactionManager jdbcManager)
                || jdbc.getDataSource() == null
                || jdbcManager.getDataSource() != jdbc.getDataSource()
                || perRuntimeCapacity <= 0) {
            throw new IllegalArgumentException("INVALID_COORDINATOR_CONFIGURATION");
        }
        installations = Map.copyOf(Objects.requireNonNull(actualInstalledVerifiers));
        installations.forEach(
                (code, verifier) -> {
                    if (!code.equals(verifier.registrationManifest().path("configId").textValue()))
                        throw new IllegalArgumentException("INVALID_COORDINATOR_INSTALLATIONS");
                });
        sources = new GradeSourceRepository(jdbc);
        runtimes = new GradeRuntimeRepository(jdbc);
        frozen = new GradeFrozenInputRepository(jdbc);
        audit = new GradeEventRepository(jdbc, credentials, trustedCoordinatorKey);
        coordinatorKey = trustedCoordinatorKey;
        capacity = perRuntimeCapacity;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(false);
        transaction.setTimeout(5);
    }

    /**
     * 실제 상위 루트를 순서대로 잠그고 전체 사본과 현재 권한을 검증한 뒤 한 번만 접수한다.
     *
     * @param jobKey 실제 비영 작업 UUID, null 불가
     * @return 커밋 후 안전한 불변 접수 결과; 미접수 시각은 null
     * @throws IllegalArgumentException 입력·사본 계약 오류, 원문 없음
     * @throws IllegalStateException 외부 TX 또는 실제 설치·출처 증명 오류
     * @throws DataAccessResourceFailureException 원인 없는 COORDINATOR_STORAGE_FAILURE
     */
    public ActivationResult activate(UUID jobKey) {
        if (jobKey == null || jobKey.equals(new UUID(0, 0)))
            throw new IllegalArgumentException("INVALID_COORDINATOR_INPUT");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw boundary();
        try {
            return transaction.execute(status -> apply(jobKey));
        } catch (DataAccessException | TransactionException failure) {
            throw storage();
        } catch (IllegalStateException failure) {
            if ("GRADE_AUDIT_STORAGE_FAILURE".equals(failure.getMessage())) throw storage();
            throw failure;
        }
    }

    /**
     * 실제 runtime 루트 잠금이 인스턴스 사이의 용량 집계를 직렬화한다.
     *
     * @param key 비영 작업 UUID, null 불가
     * @return 실제 저장 상태와 고정 시각만 포함한 결과
     * @throws IllegalStateException 실제 증명·무결성·현재성 오류
     */
    private ActivationResult apply(UUID key) {
        LockedSource root = sources.lockRoots(key);
        Job before = job(root.jobId());
        if (!"STAGED".equals(before.state()) || !"RUNNING".equals(root.batchState()))
            return result(key, before, false, Reason.NONE);
        jdbc.queryForList(
                "SELECT attempt_no FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no FOR"
                        + " UPDATE",
                root.jobId());
        jdbc.queryForList(
                "SELECT id FROM public.grade_event WHERE job_id=? ORDER BY id FOR UPDATE",
                root.jobId());
        requirePristine(root.jobId());
        // 해시·설치·codec 오류는 출처 취소 사유로 변환하지 않는다.
        requireHashes(root.jobId());
        var installation = installations.get(root.runtimeCode());
        if (installation == null) throw new IllegalStateException("INSTALLED_RUNTIME_MISMATCH");
        var verified =
                installation.verify(
                        runtimes.getRuntimeDetail(root.runtimeId())
                                .orElseThrow(GradeCoordinatorService::storage));
        var selected = frozen.dataset(root, verified).select(root.sampleCode());
        boolean inputError = "INPUT_ERROR".equals(selected.kind());
        if (inputError != (selected.report() == null)) throw storage();
        Reason reason = eligibility(root);
        if (reason == null && !inputError) {
            Long occupied =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM public.grade_job WHERE runtime_id=? AND state IN"
                                    + " ('QUEUED','RUNNING')",
                            Long.class,
                            root.runtimeId());
            if (occupied == null) throw storage();
            if (occupied >= capacity) return result(key, before, false, Reason.NONE);
        }
        String invariant = invariant(root.jobId());
        String receipts = receipts(root.jobId(), null);
        ObjectNode data = null;
        String resultHash = null;
        if (reason == null && inputError) {
            // selected.report=null은 전체 validator의 실제 REPORT 거절 증거이며 기대 boolean이 아니다.
            var error =
                    JsonNodeFactory.instance
                            .objectNode()
                            .put("code", "INVALID_REPORT")
                            .put("state", "REJECTED")
                            .putNull("score")
                            .put("attemptDelta", 0);
            if (!Arrays.equals(
                    SnapshotJson.encode(error),
                    SnapshotJson.encode(selected.expectation().get("error")))) throw storage();
            data = JsonNodeFactory.instance.objectNode().put("kind", "INPUT_ERROR");
            data.set("error", error);
            data.put("attemptDelta", 0)
                    .putNull("baseScore")
                    .putNull("baseSuccess")
                    .put("expectationAgreement", true)
                    .put("providerMatch", false)
                    .put("candidateAdoption", false)
                    .put("providerApplicable", false);
        }
        OffsetDateTime accepted = null;
        if (reason == null) {
            // 계산 뒤에도 새 DB 시계와 현재 자격으로 다시 fence한다.
            sources.requireExecutionEligibility(root);
            accepted = now();
            if (!accepted.isBefore(root.batchCreatedAt().plusHours(24))) throw storage();
        }
        if (data != null) {
            data.put("validationSource", "SERVER_REPORT_VALIDATOR")
                    .put(
                            "validatorVersion",
                            installation
                                    .registrationManifest()
                                    .get("reportContractVersion")
                                    .textValue())
                    .put("validatedAt", accepted.toInstant().toString());
            resultHash = SnapshotJson.hash(data);
        }
        OffsetDateTime deadline = accepted == null ? null : accepted.plusSeconds(120);
        JobState after =
                reason == null
                        ? inputError ? JobState.COMPLETED : JobState.QUEUED
                        : reason == Reason.SOURCE_REVOKED ? JobState.CANCELLED : JobState.FAILED;
        Reason auditReason =
                reason == null ? inputError ? Reason.INVALID_REPORT : Reason.NONE : reason;
        EventKind kind =
                reason != null
                        ? EventKind.JOB_SOURCE_CANCELLED
                        : inputError ? EventKind.JOB_INPUT_REJECTED : EventKind.JOB_ACTIVATED;
        OffsetDateTime changedAt = accepted == null ? now() : accepted;
        String json =
                data == null ? null : new String(SnapshotJson.encode(data), StandardCharsets.UTF_8);
        if (jdbc.update(
                        """
                        UPDATE public.grade_job SET state=?,accepted_at=?,deadline_at=?,next_run_at=?,updated_at=?,
                            result_data=?::jsonb,result_hash=?,error_code=?
                        WHERE id=? AND state='STAGED' AND accepted_at IS NULL AND deadline_at IS NULL
                            AND call_count=0 AND lease_gen=0 AND worker_key IS NULL AND lease_until IS NULL
                            AND result_data IS NULL AND result_hash IS NULL AND result_cipher IS NULL
                        """,
                        after.name(),
                        accepted,
                        deadline,
                        changedAt,
                        changedAt,
                        json,
                        resultHash,
                        auditReason == Reason.NONE ? null : auditReason.name(),
                        root.jobId())
                != 1) throw storage();
        var command =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("operation", kind.name())
                        .put("jobKey", key.toString())
                        .put("sampleCode", root.sampleCode())
                        .put("repeatNo", root.repeatNo())
                        .put("runtimeCode", root.runtimeCode())
                        .put("runtimeEpoch", root.runtimeEpoch())
                        .put("inputHash", root.inputHash())
                        .put("payloadHash", root.payloadHash())
                        .put("datasetHash", root.datasetHash())
                        .put("rubricHash", root.rubricHash())
                        .put("configHash", root.configHash())
                        .put("checkedAt", changedAt.toString());
        UUID commandKey = UUID.randomUUID();
        String commandHash = SnapshotJson.hash(command);
        long event =
                audit.recordSystemEvent(
                        root.jobId(),
                        null,
                        kind,
                        commandKey,
                        commandHash,
                        new Detail(
                                0,
                                JobState.STAGED,
                                after,
                                null,
                                null,
                                auditReason,
                                null,
                                resultHash));
        if (!Objects.equals(invariant, invariant(root.jobId()))) throw storage();
        if (!Objects.equals(receipts, receipts(root.jobId(), event))) throw storage();
        Boolean exact =
                jdbc.queryForObject(
                        """
                        SELECT j.state=? AND j.accepted_at IS NOT DISTINCT FROM ?::timestamptz
                            AND j.deadline_at IS NOT DISTINCT FROM ?::timestamptz AND j.next_run_at=? AND j.updated_at=?
                            AND j.result_data IS NOT DISTINCT FROM ?::jsonb AND j.result_hash IS NOT DISTINCT FROM ?::text
                            AND j.error_code IS NOT DISTINCT FROM ?::text
                            AND e.job_id=j.id AND e.attempt_no IS NULL AND e.request_id IS NULL
                            AND e.actor_kind='SYSTEM' AND e.actor_key=? AND e.command_key=? AND e.command_hash=? AND e.event_kind=?
                            AND e.detail=jsonb_build_object('leaseGen',0,'beforeJob','STAGED','afterJob',?::text,
                                'beforeAttempt',NULL,'afterAttempt',NULL,'reason',?::text,'outputHash',NULL,'resultHash',?::text)
                        FROM public.grade_job j JOIN public.grade_event e ON e.id=? WHERE j.id=?
                        """,
                        Boolean.class,
                        after.name(),
                        accepted,
                        deadline,
                        changedAt,
                        changedAt,
                        json,
                        resultHash,
                        auditReason == Reason.NONE ? null : auditReason.name(),
                        coordinatorKey,
                        commandKey,
                        commandHash,
                        kind.name(),
                        after.name(),
                        auditReason.name(),
                        resultHash,
                        event,
                        root.jobId());
        if (!Boolean.TRUE.equals(exact)) throw storage();
        installation.verify(
                runtimes.getRuntimeDetail(root.runtimeId())
                        .orElseThrow(GradeCoordinatorService::storage));
        frozen.dataset(root, verified);
        requireHashes(root.jobId());
        if (reason == null) {
            // COMPLETED는 source 실행 상태가 아니므로 공통 source 증거를 재사용하여 승인하지 않는다.
            requireCurrentParents(root);
            if (!now().isBefore(deadline)) throw storage();
            if (after == JobState.QUEUED) {
                sources.requireExecutionEligibility(root);
                Long occupied =
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_job WHERE runtime_id=? AND state"
                                        + " IN ('QUEUED','RUNNING')",
                                Long.class,
                                root.runtimeId());
                if (occupied == null || occupied > capacity) throw storage();
            }
        }
        return result(key, job(root.jobId()), true, auditReason);
    }

    /**
     * 실제 source 오류만 닫힌 사유로 분류한다. 저장·증거 수명 오류는 그대로 실패한다.
     *
     * @param root 동일 저장소·현재 TX의 잠금 증거, null 불가
     * @return 실제 거절 사유, 현재 자격이 유효하면 null
     * @throws IllegalStateException 증거 수명 또는 분류하지 않은 검증 오류
     */
    private Reason eligibility(LockedSource root) {
        if (!now().isBefore(root.batchCreatedAt().plusHours(24))) return Reason.DEADLINE_EXCEEDED;
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT r.epoch=b.runtime_epoch FROM public.grade_batch b JOIN"
                                + " public.grade_runtime r ON r.id=b.runtime_id WHERE b.id=?",
                        Boolean.class,
                        root.batchId()))) return Reason.RUNTIME_EPOCH_CHANGED;
        try {
            sources.requireExecutionEligibility(root);
        } catch (IllegalStateException failure) {
            if ("SOURCE_NOT_CURRENT".equals(failure.getMessage())) return Reason.SOURCE_REVOKED;
            if ("BATCH_EXPIRED".equals(failure.getMessage())) return Reason.DEADLINE_EXCEEDED;
            throw failure;
        }
        return null;
    }

    /**
     * 정상 접수의 트리거 이후 현재 권한·물리 부모·시간을 상태와 독립적으로 검사한다.
     *
     * @param root 이미 잠근 실제 부모 증거, null 불가
     * @throws DataAccessResourceFailureException 현재 자격·24시간 예산이 바뀐 경우
     */
    private void requireCurrentParents(LockedSource root) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT b.state='RUNNING' AND r.state='AVAILABLE' AND r.epoch=b.runtime_epoch
                            AND c.active_yn AND c.can_review AND d.enrolled_at IS NOT NULL AND d.mfa_state='READY'
                            AND s.active_yn AND v.active_yn AND v.current_snapshot_id=b.snapshot_id
                            AND (v.status='REVIEW' OR (b.purpose='AVAILABILITY' AND v.status IN ('READY','PUBLISHED')))
                            AND EXISTS(SELECT 1 FROM public.story_access x WHERE x.story_id=s.id AND x.admin_id=c.id AND x.permission='REVIEW' AND x.active_yn)
                            AND clock_timestamp()<b.created_at+interval '24 hours'
                        FROM public.grade_batch b JOIN public.grade_runtime r ON r.id=b.runtime_id
                        JOIN public.review_snapshot f ON f.id=b.snapshot_id JOIN public.story_version v ON v.id=f.version_id
                        JOIN public.story s ON s.id=v.story_id JOIN public.admin_account c ON c.id=b.created_by
                        JOIN public.admin_credential d ON d.account_id=c.id WHERE b.id=?
                        """,
                        Boolean.class,
                        root.batchId()))) throw storage();
    }

    /** 선언 해시 불일치를 출처 회수로 숨기지 않는다. */
    private void requireHashes(long id) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT j.config_hash=r.config_hash AND b.config_hash=r.config_hash AND j.rubric_hash=b.rubric_hash
                            AND j.snapshot_id=b.snapshot_id AND j.runtime_id=b.runtime_id
                        FROM public.grade_job j JOIN public.grade_batch b ON b.id=j.batch_id
                        JOIN public.grade_runtime r ON r.id=b.runtime_id WHERE j.id=?
                        """,
                        Boolean.class,
                        id))) throw new IllegalStateException("COORDINATOR_INPUT_NOT_CURRENT");
    }

    /** 미접수 작업에 과거 예산·출력·시도가 있으면 환불하거나 덮어쓰지 않는다. */
    private void requirePristine(long id) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT accepted_at IS NULL AND deadline_at IS NULL AND call_count=0 AND lease_gen=0
                            AND worker_key IS NULL AND lease_until IS NULL AND result_data IS NULL AND result_hash IS NULL
                            AND result_cipher IS NULL AND error_code IS NULL
                            AND NOT EXISTS(SELECT 1 FROM public.grade_attempt a WHERE a.job_id=j.id)
                        FROM public.grade_job j WHERE id=?
                        """,
                        Boolean.class,
                        id))) throw storage();
    }

    /**
     * 전이 필드 외 전체 부모·사본·시도를 내부 비교하고 외부에 노출하지 않는다.
     *
     * @param id 잠근 양수 실제 작업 식별자
     * @return 원문을 포함할 수 있는 TX 내부 비교 문자열, 반환·감사 사용 금지
     */
    private String invariant(long id) {
        return jdbc.queryForObject(
                """
                SELECT jsonb_build_object('job',to_jsonb(j)-ARRAY['state','accepted_at','deadline_at','next_run_at','updated_at','result_data','result_hash','error_code'],
                    'batch',to_jsonb(b),'runtime',to_jsonb(r),'snapshot',to_jsonb(f),'version',to_jsonb(v),'story',to_jsonb(s),
                    'account',to_jsonb(c),'credential',jsonb_build_object('accountId',d.account_id,'enrolledAt',d.enrolled_at,'mfaState',d.mfa_state,'authRev',d.auth_rev),
                    'access',COALESCE((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.admin_id,x.permission) FROM public.story_access x WHERE x.story_id=s.id),'[]'::jsonb),
                    'attempts',COALESCE((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.attempt_no) FROM public.grade_attempt a WHERE a.job_id=j.id),'[]'::jsonb))::text
                FROM public.grade_job j JOIN public.grade_batch b ON b.id=j.batch_id JOIN public.grade_runtime r ON r.id=j.runtime_id
                JOIN public.review_snapshot f ON f.id=j.snapshot_id JOIN public.story_version v ON v.id=f.version_id
                JOIN public.story s ON s.id=v.story_id JOIN public.admin_account c ON c.id=b.created_by
                JOIN public.admin_credential d ON d.account_id=c.id WHERE j.id=?
                """,
                String.class,
                id);
    }

    /** 기존 안전 영수증은 변경·삭제·추가하지 않으며 이번 감사 한 행만 비교에서 제외한다. */
    private String receipts(long id, Long newEvent) {
        return jdbc.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(e) ORDER BY e.id),'[]'::jsonb)::text FROM"
                        + " public.grade_event e WHERE e.job_id=? AND (?::bigint IS NULL OR"
                        + " e.id<>?::bigint)",
                String.class,
                id,
                newEvent,
                newEvent);
    }

    private Job job(long id) {
        return jdbc.queryForObject(
                "SELECT state,accepted_at,deadline_at,error_code FROM public.grade_job WHERE id=?",
                (row, index) ->
                        new Job(
                                row.getString("state"),
                                row.getObject("accepted_at", OffsetDateTime.class),
                                row.getObject("deadline_at", OffsetDateTime.class),
                                row.getString("error_code")),
                id);
    }

    /** 실제 DB 시각만 사용하며 null 시각은 저장 오류다. */
    private OffsetDateTime now() {
        OffsetDateTime clock =
                jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (clock == null) throw storage();
        return clock;
    }

    private static ActivationResult result(UUID key, Job job, boolean changed, Reason reason) {
        return new ActivationResult(
                key,
                job.state(),
                changed,
                job.accepted(),
                job.deadline(),
                "COMPLETED".equals(job.state()) && "INVALID_REPORT".equals(job.error()),
                reason.name());
    }

    private static IllegalStateException boundary() {
        return new IllegalStateException("COORDINATOR_REQUIRES_SEPARATE_TRANSACTION");
    }

    private static DataAccessResourceFailureException storage() {
        return new DataAccessResourceFailureException("COORDINATOR_STORAGE_FAILURE");
    }

    private record Job(
            String state, OffsetDateTime accepted, OffsetDateTime deadline, String error) {}

    /**
     * 원문·기대값·해시·점수·자격증명을 포함하지 않는 결과다.
     *
     * @param jobKey 실제 UUID
     * @param state 실제 저장 상태
     * @param changed 이번 호출의 실제 전이 여부
     * @param acceptedAt 고정 접수 시각, 미접수이면 null
     * @param deadlineAt 접수 후 정확히 120초, 미접수이면 null
     * @param inputRejected 실제 서버 입력 오류 완료 여부
     * @param reason 이번 호출의 닫힌 안전 사유
     */
    public record ActivationResult(
            UUID jobKey,
            String state,
            boolean changed,
            OffsetDateTime acceptedAt,
            OffsetDateTime deadlineAt,
            boolean inputRejected,
            String reason) {}
}
