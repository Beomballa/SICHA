package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
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

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 서버 전용 만료 임대 복구 경계다. worker callback·HTTP·모델 호출·집합 집계 권한을 제공하지 않는다. */
public final class GradeRecoveryService {
    private final JdbcTemplate jdbc;
    private final GradeSourceRepository sources;
    private final GradeRuntimeRepository runtimes;
    private final GradeFrozenInputRepository frozen;
    private final GradeEventRepository audit;
    private final String coordinatorKey;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final TransactionTemplate transaction;

    /**
     * 실제 설치의 파일 지문은 이 생성자에 전달하기 전에 SQL 밖에서 계산한다.
     *
     * @param jdbc 실제 동일 DataSource JDBC 도구, null 불가
     * @param manager 동일 DataSource의 실제 JDBC TX 관리자, null 불가
     * @param credentials 실제 배포 자격증명 레지스트리, null 불가
     * @param installations manifest configId별 실제 설치의 불변 사본, null 불가
     * @param trustedCoordinatorKey SYSTEM 감사의 실제 신뢰 배포 ASCII 식별자, null 불가
     */
    public GradeRecoveryService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            GradeWorkerCredentials credentials,
            Map<String, InstalledRuntimeManifestVerifier> installations,
            String trustedCoordinatorKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw boundary();
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!(manager instanceof DataSourceTransactionManager jdbcManager)
                || jdbc.getDataSource() == null
                || jdbcManager.getDataSource() != jdbc.getDataSource()) {
            throw new IllegalArgumentException("INVALID_RECOVERY_TRANSACTION_MANAGER");
        }
        this.installations = Map.copyOf(Objects.requireNonNull(installations));
        this.installations.forEach(
                (code, verifier) -> {
                    if (!code.equals(
                            verifier.registrationManifest().path("configId").textValue())) {
                        throw new IllegalArgumentException("INVALID_RECOVERY_INSTALLATIONS");
                    }
                });
        sources = new GradeSourceRepository(jdbc);
        runtimes = new GradeRuntimeRepository(jdbc);
        frozen = new GradeFrozenInputRepository(jdbc);
        audit = new GradeEventRepository(jdbc, credentials, trustedCoordinatorKey);
        coordinatorKey = trustedCoordinatorKey;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(false);
        transaction.setTimeout(5);
    }

    /**
     * 상위 실제 루트 다음에 시도를 잠그고 새 DB 시계로 만료를 확인한다. 살아 있는 임대와 종료 작업은 감사도 추가하지 않는다.
     *
     * @param jobKey 실제 비영 작업 UUID, null 불가
     * @return 자체 TX 커밋 뒤 반환하는 원문·점수 없는 불변 결과
     * @throws IllegalArgumentException UUID 또는 구성 오류의 원인 없는 고정 예외
     * @throws IllegalStateException 외부 TX 또는 실제 설치·사본·출처 증명 오류
     * @throws DataAccessResourceFailureException 원인 없는 RECOVERY_STORAGE_FAILURE
     */
    public RecoveryResult recover(UUID jobKey) {
        if (jobKey == null || jobKey.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException("INVALID_RECOVERY_INPUT");
        }
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
     * 호출 횟수를 환불하지 않고 불확실한 전송의 실제 예약만 WORKER_LOST로 닫는다.
     *
     * @param key 실제 비영 작업 UUID
     * @return 최초 전이 또는 안전한 무변경 결과
     */
    private RecoveryResult apply(UUID key) {
        LockedSource root = sources.lockRoots(key);
        Job before = job(root.jobId());
        if (!"RUNNING".equals(before.state())) return unchanged(key, before);
        var attempts =
                jdbc.queryForList(
                        "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no FOR"
                            + " UPDATE",
                        root.jobId());
        OffsetDateTime now = now();
        before = job(root.jobId());
        if (!"RUNNING".equals(before.state()) || now.isBefore(before.leaseUntil())) {
            return unchanged(key, before);
        }
        if (before.count() < 0 || before.count() > 3 || attempts.size() != before.count()) {
            throw storage();
        }
        Map<String, Object> current = null;
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            if (number(attempt, "attempt_no") != index + 1) throw storage();
            if (number(attempt, "lease_gen") == before.generation()) {
                if (current != null
                        || index + 1 != before.count()
                        || !Objects.equals(before.worker(), attempt.get("worker_key"))
                        || !"RUNNING".equals(attempt.get("state"))
                        || attempt.get("completion_data") != null
                        || attempt.get("ended_at") != null
                        || attempt.get("error_code") != null
                        || attempt.get("observed_version") != null
                        || attempt.get("provider_ref") != null
                        || attempt.get("output_hash") != null
                        || attempt.get("output_cipher") != null) throw storage();
                current = attempt;
            } else if (number(attempt, "lease_gen") > before.generation()
                    || "RUNNING".equals(attempt.get("state"))
                    || "SUCCEEDED".equals(attempt.get("state"))) throw storage();
        }
        if (before.worker() == null
                || before.generation() <= 0
                || before.deadlineAt() == null
                || before.acceptedAt() == null
                || !before.deadlineAt().equals(before.acceptedAt().plusSeconds(120)))
            throw storage();
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT result_data IS NULL AND result_hash IS NULL AND result_cipher IS"
                            + " NULL FROM public.grade_job WHERE id=?",
                        Boolean.class,
                        root.jobId()))) throw storage();
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT j.config_hash=r.config_hash AND b.config_hash=r.config_hash
                            AND j.rubric_hash=b.rubric_hash AND j.snapshot_id=b.snapshot_id
                            AND j.runtime_id=b.runtime_id
                        FROM public.grade_job j JOIN public.grade_batch b ON b.id=j.batch_id
                        JOIN public.grade_runtime r ON r.id=b.runtime_id WHERE j.id=?
                        """,
                        Boolean.class,
                        root.jobId()))) {
            throw new IllegalStateException("RECOVERY_INPUT_NOT_CURRENT");
        }

        // manifest·codec·선택·물리 식별자 오류는 출처 회수로 분류하지 않는다.
        var installation = installations.get(root.runtimeCode());
        if (installation == null) throw new IllegalStateException("INSTALLED_RUNTIME_MISMATCH");
        var verified =
                installation.verify(
                        runtimes.getRuntimeDetail(root.runtimeId())
                                .orElseThrow(GradeRecoveryService::storage));
        var selected = frozen.dataset(root, verified).select(root.sampleCode());
        if (selected.report() == null || "INPUT_ERROR".equals(selected.kind())) {
            throw new IllegalStateException("COORDINATOR_ONLY_FIXTURE");
        }
        Reason reason = eligibility(root, before, now());
        JobState after =
                reason == null && before.count() < 3
                        ? JobState.QUEUED
                        : reason == Reason.SOURCE_REVOKED ? JobState.CANCELLED : JobState.FAILED;
        if (reason == null) reason = current == null ? Reason.STALE_LEASE : Reason.WORKER_LOST;
        String invariant = invariant(root.jobId());
        Integer attemptNo = current == null ? null : before.count();
        OffsetDateTime ended = null;
        if (current != null) {
            ended = now();
            int closed =
                    jdbc.update(
                            """
                            UPDATE public.grade_attempt SET state='FAILED',error_code='WORKER_LOST',ended_at=?
                            WHERE job_id=? AND attempt_no=? AND state='RUNNING' AND completion_data IS NULL
                                AND worker_key=? AND lease_gen=?
                            """,
                            ended,
                            root.jobId(),
                            attemptNo,
                            before.worker(),
                            before.generation());
            if (closed != 1) throw storage();
        }
        now = now();
        if (after == JobState.QUEUED && eligibility(root, before, now) != null) throw storage();
        String error =
                after == JobState.QUEUED
                        ? null
                        : after == JobState.CANCELLED
                                ? "SOURCE_REVOKED"
                                : reason == Reason.WORKER_LOST ? "WORKER_LOST" : reason.name();
        if (jdbc.update(
                        """
                        UPDATE public.grade_job SET state=?,worker_key=NULL,lease_until=NULL,error_code=?,
                            next_run_at=?,updated_at=?
                        WHERE id=? AND state='RUNNING' AND worker_key=? AND lease_gen=? AND call_count=?
                            AND lease_until<=clock_timestamp()
                            AND (?<>'QUEUED' OR EXISTS(
                                SELECT 1 FROM public.grade_batch b JOIN public.grade_runtime r ON r.id=b.runtime_id
                                JOIN public.review_snapshot f ON f.id=b.snapshot_id JOIN public.story_version v ON v.id=f.version_id
                                JOIN public.story s ON s.id=v.story_id JOIN public.admin_account c ON c.id=b.created_by
                                JOIN public.admin_credential d ON d.account_id=c.id
                                WHERE b.id=grade_job.batch_id AND b.state='RUNNING' AND r.state='AVAILABLE'
                                    AND r.epoch=b.runtime_epoch AND r.config_hash=b.config_hash
                                    AND b.snapshot_id=grade_job.snapshot_id AND b.runtime_id=grade_job.runtime_id
                                    AND r.config_hash=grade_job.config_hash AND b.rubric_hash=grade_job.rubric_hash
                                    AND v.current_snapshot_id=b.snapshot_id AND v.active_yn AND s.active_yn
                                    AND (v.status='REVIEW' OR (b.purpose='AVAILABILITY' AND v.status IN ('READY','PUBLISHED')))
                                    AND c.active_yn AND c.can_review AND d.enrolled_at IS NOT NULL AND d.mfa_state='READY'
                                    AND EXISTS(SELECT 1 FROM public.story_access x WHERE x.story_id=s.id
                                        AND x.admin_id=c.id AND x.permission='REVIEW' AND x.active_yn)
                                    AND clock_timestamp()<grade_job.deadline_at AND clock_timestamp()<b.created_at+interval '24 hours'))
                        """,
                        after.name(),
                        error,
                        now,
                        now,
                        root.jobId(),
                        before.worker(),
                        before.generation(),
                        before.count(),
                        after.name())
                != 1) throw storage();
        EventKind kind =
                current == null ? EventKind.JOB_LEASE_RECLAIMED : EventKind.RECOVERY_EXPIRED;
        var command =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("operation", kind.name())
                        .put("jobKey", key.toString())
                        .put("leaseGen", before.generation())
                        .put("attemptNo", attemptNo)
                        .put("callCount", before.count())
                        .put("beforeState", "RUNNING")
                        .put("afterState", after.name())
                        .put("reason", reason.name())
                        .put("checkedAt", now.toString());
        String hash = SnapshotJson.hash(command);
        UUID commandKey = UUID.randomUUID();
        long event =
                audit.recordSystemEvent(
                        root.jobId(),
                        attemptNo,
                        kind,
                        commandKey,
                        hash,
                        new Detail(
                                before.generation(),
                                JobState.RUNNING,
                                after,
                                current == null ? null : AttemptState.RUNNING,
                                current == null ? null : AttemptState.FAILED,
                                reason,
                                null,
                                null));
        requireStored(
                root,
                before,
                after,
                error,
                invariant,
                attemptNo,
                ended,
                now,
                event,
                commandKey,
                hash,
                reason);
        // 트리거가 payload·manifest를 바꿔도 최초 검증값으로 커밋하지 않는다.
        try {
            installation.verify(
                    runtimes.getRuntimeDetail(root.runtimeId())
                            .orElseThrow(GradeRecoveryService::storage));
            frozen.dataset(root, verified);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw storage();
        }
        if (after == JobState.QUEUED) {
            try {
                sources.requireExecutionEligibility(root);
            } catch (IllegalStateException failure) {
                if ("SOURCE_NOT_CURRENT".equals(failure.getMessage())
                        || "BATCH_EXPIRED".equals(failure.getMessage())) throw storage();
                throw failure;
            }
            if (!now().isBefore(before.deadlineAt())) throw storage();
        }
        return new RecoveryResult(true, key, after.name(), attemptNo, reason.name());
    }

    /**
     * 실제 실효 사유만 분류하며 증거 수명·codec·저장 장애는 그대로 거절한다.
     *
     * @param root 현재 TX의 실제 잠금 증거, null 불가
     * @param job 잠근 실제 작업의 고정 예산·마감, null 불가
     * @param clock 검증 직전에 읽은 실제 DB 시각, null 불가
     * @return 시간·epoch·출처 거절 사유 또는 자격이 유효하면 null
     */
    private Reason eligibility(LockedSource root, Job job, OffsetDateTime clock) {
        if (!clock.isBefore(job.deadlineAt())
                || !clock.isBefore(root.batchCreatedAt().plusHours(24)))
            return Reason.DEADLINE_EXCEEDED;
        var epoch =
                jdbc.queryForMap(
                        "SELECT r.epoch,b.runtime_epoch FROM public.grade_runtime r JOIN"
                            + " public.grade_batch b ON b.runtime_id=r.id WHERE b.id=?",
                        root.batchId());
        if (number(epoch, "epoch") != number(epoch, "runtime_epoch"))
            return Reason.RUNTIME_EPOCH_CHANGED;
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
     * 원래 전체 관계·예산·시각과 예약의 비관측 필드를 트리거 이후에도 정확히 보존한다.
     *
     * @param id 현재 TX에서 이미 잠근 실제 작업 식별자
     * @return 전이에서 바꿀 필드만 제외한 내부 JSONB 비교값, 외부 반환·감사에 사용하지 않음
     */
    private String invariant(long id) {
        return jdbc.queryForObject(
                """
                SELECT jsonb_build_object(
                    'job',to_jsonb(j)-ARRAY['state','worker_key','lease_until','error_code','next_run_at','updated_at'],
                    'batch',to_jsonb(b),'runtime',to_jsonb(r),
                    'snapshot',to_jsonb(f)-'payload','version',to_jsonb(v),'story',to_jsonb(s),
                    'account',to_jsonb(c),'credential',jsonb_build_object('accountId',d.account_id,
                        'enrolledAt',d.enrolled_at,'mfaState',d.mfa_state,'authRev',d.auth_rev),
                    'attempts',COALESCE((SELECT jsonb_agg(CASE WHEN a.lease_gen=j.lease_gen
                        THEN to_jsonb(a)-ARRAY['state','ended_at','error_code'] ELSE to_jsonb(a) END ORDER BY attempt_no)
                        FROM public.grade_attempt a WHERE a.job_id=j.id),'[]'::jsonb))::text
                FROM public.grade_job j JOIN public.grade_batch b ON b.id=j.batch_id
                JOIN public.grade_runtime r ON r.id=j.runtime_id JOIN public.review_snapshot f ON f.id=j.snapshot_id
                JOIN public.story_version v ON v.id=f.version_id JOIN public.story s ON s.id=v.story_id
                JOIN public.admin_account c ON c.id=b.created_by JOIN public.admin_credential d ON d.account_id=c.id WHERE j.id=?
                """,
                String.class,
                id);
    }

    /**
     * 감사·시도·작업의 최종 고정 상태를 검사한다. 의도적으로 FAILED인 작업에는 실행 자격 검사를 적용하지 않는다.
     *
     * @param root 현재 TX의 실제 잠금 증거
     * @param before 변경 전 실제 작업의 불변 예산·시각
     * @param after 기록할 작업 상태
     * @param error 기록할 고정 오류 코드, QUEUED이면 null
     * @param expected 변경 전 관계·예산·시각의 내부 JSONB 비교값
     * @param attemptNo 실제 닫은 예약 번호, 미예약이면 null
     * @param ended 기록한 실제 DB 종료 시각, 미예약이면 null
     * @param next 기록한 실제 DB 다음 실행·갱신 시각
     * @param event 새로 기록한 감사 식별자
     * @param command 서버 생성 비영 명령 UUID
     * @param hash 정규 안전 명령 해시
     * @param reason 기록할 닫힌 감사 사유
     */
    private void requireStored(
            LockedSource root,
            Job before,
            JobState after,
            String error,
            String expected,
            Integer attemptNo,
            OffsetDateTime ended,
            OffsetDateTime next,
            long event,
            UUID command,
            String hash,
            Reason reason) {
        if (!Objects.equals(expected, invariant(root.jobId()))) throw storage();
        Boolean valid =
                jdbc.queryForObject(
                        """
                        SELECT j.state=? AND j.worker_key IS NULL AND j.lease_until IS NULL
                            AND j.error_code IS NOT DISTINCT FROM ? AND j.next_run_at=? AND j.updated_at=j.next_run_at
                            AND e.job_id=j.id AND e.attempt_no IS NOT DISTINCT FROM ?
                            AND e.actor_kind='SYSTEM' AND e.actor_key=? AND e.request_id IS NULL AND e.command_key=? AND e.command_hash=?
                            AND e.event_kind=? AND e.detail=jsonb_build_object('leaseGen',?::bigint,
                                'beforeJob','RUNNING','afterJob',?::text,'beforeAttempt',?::text,'afterAttempt',?::text,
                                'reason',?::text,'outputHash',NULL,'resultHash',NULL)
                        FROM public.grade_job j JOIN public.grade_event e ON e.id=? WHERE j.id=?
                        """,
                        Boolean.class,
                        after.name(),
                        error,
                        next,
                        attemptNo,
                        coordinatorKey,
                        command,
                        hash,
                        attemptNo == null ? "JOB_LEASE_RECLAIMED" : "RECOVERY_EXPIRED",
                        before.generation(),
                        after.name(),
                        attemptNo == null ? null : "RUNNING",
                        attemptNo == null ? null : "FAILED",
                        reason.name(),
                        event,
                        root.jobId());
        if (!Boolean.TRUE.equals(valid)) throw storage();
        if (attemptNo != null
                && !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                """
                                SELECT state='FAILED' AND error_code='WORKER_LOST' AND ended_at=? AND ended_at>=started_at
                                    AND completion_data IS NULL AND observed_version IS NULL AND provider_ref IS NULL
                                    AND output_hash IS NULL AND output_cipher IS NULL
                                FROM public.grade_attempt WHERE job_id=? AND attempt_no=?
                                """,
                                Boolean.class,
                                ended,
                                root.jobId(),
                                attemptNo))) throw storage();
    }

    /** 잠근 작업의 실제 임대와 고정 예산만 읽는다. */
    private Job job(long id) {
        return jdbc.queryForObject(
                "SELECT state,worker_key,lease_gen,call_count,lease_until,deadline_at,accepted_at"
                    + " FROM public.grade_job WHERE id=?",
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

    /** 매 검사마다 실제 DB 시계를 새로 읽는다. */
    private OffsetDateTime now() {
        OffsetDateTime clock =
                jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (clock == null) throw storage();
        return clock;
    }

    private static RecoveryResult unchanged(UUID key, Job job) {
        return new RecoveryResult(false, key, job.state(), null, Reason.NONE.name());
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static IllegalStateException boundary() {
        return new IllegalStateException("RECOVERY_REQUIRES_SEPARATE_TRANSACTION");
    }

    private static DataAccessResourceFailureException storage() {
        return new DataAccessResourceFailureException("RECOVERY_STORAGE_FAILURE");
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
     * 점수·해시·worker·사본·자격증명을 노출하지 않는 서버 내부 불변 결과다.
     *
     * @param changed 실제 전이 여부
     * @param jobKey 실제 작업 UUID
     * @param state 저장된 작업 상태
     * @param attemptNo 닫은 실제 예약 번호, 예약 없거나 무변경이면 null
     * @param reason 닫힌 안전 사유
     */
    public record RecoveryResult(
            boolean changed, UUID jobKey, String state, Integer attemptNo, String reason) {}
}
