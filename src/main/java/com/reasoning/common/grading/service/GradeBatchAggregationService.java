package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository.SavedDataset;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository.LockedSource;
import com.reasoning.common.grading.service.GradeBatchComparison.FailureKind;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 명시적 수동 역사 집계·실효 정비만 수행한다. 빈·스케줄러·실행·운영 가용성 권한은 제공하지 않는다. */
public final class GradeBatchAggregationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final GradeSourceRepository sources;
    private final GradeRuntimeRepository runtimes;
    private final GradeFrozenInputRepository frozen;
    private final GradeBatchComparison comparison;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final String coordinator;

    /**
     * 설치 파일 지문은 SQL 밖에서 미리 계산하며 비어 있는 설치는 사실 정비에만 사용할 수 있다.
     *
     * @param jdbc 실제 JDBC 도구, null 불가
     * @param manager 같은 DataSource의 JDBC 관리자, null 불가
     * @param crypto 실제 개인 결과 GCM 서비스, null 불가
     * @param installations 실제 설치의 불변 코드별 사본, null 불가
     * @param trustedCoordinatorKey 신뢰 배포 SYSTEM ASCII 식별자 1~80자, 기본값 없음
     * @throws IllegalArgumentException 의존성·설치·신뢰 식별자 불일치
     * @throws IllegalStateException 주변 거래가 존재하는 경우
     */
    public GradeBatchAggregationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            CryptoService crypto,
            Map<String, InstalledRuntimeManifestVerifier> installations,
            String trustedCoordinatorKey) {
        requireSeparate();
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!(manager instanceof DataSourceTransactionManager tx)
                || jdbc.getDataSource() == null
                || tx.getDataSource() != jdbc.getDataSource())
            throw new IllegalArgumentException("INVALID_AGGREGATION_TRANSACTION_MANAGER");
        if (trustedCoordinatorKey == null || !trustedCoordinatorKey.matches("[!-~]{1,80}"))
            throw new IllegalArgumentException("INVALID_AGGREGATION_COORDINATOR");
        coordinator = trustedCoordinatorKey;
        this.installations = Map.copyOf(Objects.requireNonNull(installations));
        this.installations.forEach(
                (code, verifier) -> {
                    if (!code.equals(verifier.registrationManifest().path("configId").textValue()))
                        throw new IllegalArgumentException("INVALID_AGGREGATION_INSTALLATIONS");
                });
        sources = new GradeSourceRepository(jdbc);
        runtimes = new GradeRuntimeRepository(jdbc);
        frozen = new GradeFrozenInputRepository(jdbc);
        comparison = new GradeBatchComparison(jdbc, Objects.requireNonNull(crypto));
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(false);
        transaction.setTimeout(5);
    }

    /**
     * 최소 실제 자식으로 정상 루트 순서를 지킨 자체 거래에서 전체 집합을 집계한다.
     *
     * @param batchKey 실제 비영 집합 UUID, null 불가
     * @return 커밋된 역사 결과만 포함하는 안전 사본
     * @throws IllegalStateException 주변 거래 경계 실패
     * @throws DataAccessResourceFailureException 필수 감사·증거·최종 fence 불일치
     */
    public AggregationResult aggregate(UUID batchKey) {
        requireSeparate();
        if (batchKey == null || batchKey.equals(new UUID(0, 0)))
            throw new IllegalArgumentException("INVALID_AGGREGATION_INPUT");
        try {
            return transaction.execute(status -> apply(batchKey));
        } catch (DataAccessException | TransactionException failure) {
            throw storage();
        }
    }

    /**
     * 모든 작업을 id 정순으로 먼저 잠근 뒤 시도·감사·지적을 검사한다.
     *
     * @param key 실제 집합 UUID
     * @return 실제 저장 결과
     * @throws DataAccessResourceFailureException 전체 집합 또는 보존 증거 불일치
     */
    private AggregationResult apply(UUID key) {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        var anchor =
                jdbc.queryForList(
                        """
                        SELECT j.id,j.job_key FROM public.grade_job j JOIN public.grade_batch b ON b.id=j.batch_id
                        WHERE b.batch_key=? ORDER BY j.id LIMIT 1
                        """,
                        key);
        if (anchor.size() != 1) throw storage();
        LockedSource root = sources.lockRoots((UUID) anchor.getFirst().get("job_key"));
        var batch = jdbc.queryForMap("SELECT * FROM public.grade_batch WHERE id=?", root.batchId());
        if (!key.equals(batch.get("batch_key")) || !key.equals(root.batchKey())) throw storage();
        var jobs = jobs(root.batchId());
        if (jobs.isEmpty()
                || number(jobs.getFirst(), "id") != root.jobId()
                || number(anchor.getFirst(), "id") != root.jobId()) throw storage();
        if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(batch.get("state")))
            return result(false, batch);
        if (!"RUNNING".equals(batch.get("state"))) throw storage();
        if (batch.get("passed_yn") != null
                || batch.get("ended_at") != null
                || batch.get("valid_until") != null) throw storage();

        SavedDataset saved = frozen.loadSavedFacts(root.snapshotId());
        var runtime =
                runtimes.getRuntimeDetail(root.runtimeId())
                        .orElseThrow(GradeBatchAggregationService::storage);
        requireSet(root, batch, jobs, saved, runtime.configHash());
        for (var job : jobs)
            jdbc.queryForList(
                    "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no FOR"
                            + " UPDATE",
                    number(job, "id"));
        String cause = cause(root, batch, now());
        Set<Long> changeable = new HashSet<>();
        for (var job : jobs)
            if (Set.of("STAGED", "QUEUED", "RUNNING").contains(job.get("state")))
                changeable.add(number(job, "id"));
        String preserved = invariant(root, changeable);
        Preservation rows = new Preservation(root);
        List<Long> newIssues = new ArrayList<>();
        UUID request = UUID.randomUUID();
        long read =
                audit(key, request, "CONTENT_READ", object().put("batchKey", key.toString()), rows);
        int pending = 0;
        boolean mismatch = false;
        boolean technical = false;
        for (var job : jobs) {
            var actual =
                    comparison.compare(
                            job,
                            saved.dataset().select((String) job.get("sample_code")),
                            runtime,
                            saved.dataset().gradingSnapshot(),
                            read);
            mismatch |= actual.failureKind() == FailureKind.GRADING;
            technical |= actual.failureKind() == FailureKind.INFRA;
            if ("PENDING".equals(actual.comparison())) pending++;
        }
        if (mismatch)
            issue(batch, key, request, "GRADING", "EXPECTATION_MISMATCH", newIssues, rows);
        // 사실 정비는 현재 설치·실행 자격에 의존하지 않는다. 새로운 성공은 실제 설치와 저장 정책을 요구한다.
        boolean qualified = cause != null;
        if (cause == null) {
            var installed = installations.get(runtime.code());
            if (installed != null) {
                try {
                    var verified = installed.verify(runtime);
                    qualified =
                            saved.policyCode().equals(verified.profile().policyCode())
                                    && saved.policyCode().equals(root.policyCode());
                } catch (IllegalStateException failure) {
                    qualified = false;
                }
            }
        }
        String state = null;
        Boolean passed = null;
        String reason = "NONE";
        if (cause != null) {
            reason = cause;
            state = "SOURCE_REVOKED".equals(cause) ? "CANCELLED" : "FAILED";
            for (var job : jobs) {
                if (!Set.of("STAGED", "QUEUED", "RUNNING").contains(job.get("state"))) continue;
                close(job, key, request, cause, rows);
            }
            technical = true;
        } else if (qualified && technical) {
            // 실제 실행 실패는 전체 완료 가능성이 없으며 정상 기대 불일치와 분리한다.
            state = "FAILED";
            reason = "COMPARISON_UNAVAILABLE";
            for (var job : jobs)
                if (changeable.contains(number(job, "id")))
                    close(job, key, request, "TERMINAL", rows);
        } else if (qualified && pending == 0) {
            state = "COMPLETED";
            passed = !mismatch;
            reason = mismatch ? "EXPECTATION_MISMATCH" : "NONE";
        }
        if (technical)
            issue(
                    batch,
                    key,
                    request,
                    "INFRA",
                    cause == null ? "COMPARISON_UNAVAILABLE" : reason,
                    newIssues,
                    rows);
        if (state != null) {
            OffsetDateTime ended = now();
            if (!Objects.equals(cause, cause(root, batch, ended))) throw storage();
            if (jdbc.update(
                            """
                            UPDATE public.grade_batch SET state=?,passed_yn=?,ended_at=?,valid_until=NULL
                            WHERE id=? AND state='RUNNING'
                            """,
                            state,
                            passed,
                            ended,
                            root.batchId())
                    != 1) throw storage();
            rows.replace("grade_batch", "id=" + root.batchId());
            audit(
                    key,
                    request,
                    "BATCH_AGGREGATE",
                    object().put("batchKey", key.toString())
                            .put("state", state)
                            .put("reason", reason)
                            .put("passed", passed),
                    rows);
            var stored =
                    jdbc.queryForMap("SELECT * FROM public.grade_batch WHERE id=?", root.batchId());
            if (!state.equals(stored.get("state"))
                    || !Objects.equals(passed, stored.get("passed_yn"))
                    || stored.get("valid_until") != null
                    || !ended.toInstant().equals(instant(stored.get("ended_at")))) throw storage();
            batch = stored;
        }
        if (!preserved.equals(invariant(root, changeable))) throw storage();
        if (!Objects.equals(cause, cause(root, batch, now()))) throw storage();
        requireSet(
                root,
                batch,
                jobs(root.batchId()),
                frozen.loadSavedFacts(root.snapshotId()),
                runtime.configHash());
        if (cause == null && qualified)
            installations
                    .get(runtime.code())
                    .verify(
                            runtimes.getRuntimeDetail(root.runtimeId())
                                    .orElseThrow(GradeBatchAggregationService::storage));
        rows.verify();
        if (!Objects.equals(cause, cause(root, batch, now()))) throw storage();
        return new AggregationResult(
                state != null || !newIssues.isEmpty(),
                key,
                (String) batch.get("state"),
                (Boolean) batch.get("passed_yn"),
                cause == null && !qualified ? "INSTALLED_RUNTIME_MISMATCH" : reason);
    }

    /**
     * 전체 저장 sample×세 반복과 모든 실제 물리 결속·선언 해시를 비교한다.
     *
     * @param root 실제 루트
     * @param batch 실제 집합
     * @param jobs 모든 잠근 자식
     * @param saved 전체 저장 사실
     * @param config 실제 runtime 구성 해시
     * @throws DataAccessResourceFailureException 누락·중복·추가·다른 부모·해시
     */
    private void requireSet(
            LockedSource root,
            Map<String, Object> batch,
            List<Map<String, Object>> jobs,
            SavedDataset saved,
            String config) {
        var f = saved.frozen();
        Set<String> expected = new HashSet<>();
        for (JsonNode sample : f.payload().path("resources").path("gradeSamples"))
            for (int repeat = 1; repeat <= 3; repeat++)
                expected.add(sample.path("code").textValue() + ":" + repeat);
        if (saved.versionId() != root.versionId()
                || saved.storyId() != root.storyId()
                || saved.snapshotId() != number(batch, "snapshot_id")
                || number(batch, "runtime_id") != root.runtimeId()
                || number(batch, "created_by") != root.creatorId()
                || number(batch, "repeat_count") != 3
                || number(batch, "expected_count") != expected.size()
                || jobs.size() != expected.size()
                || !f.payloadHash().equals(batch.get("payload_hash"))
                || !f.datasetHash().equals(batch.get("dataset_hash"))
                || !f.rubricHash().equals(batch.get("rubric_hash"))
                || !config.equals(batch.get("config_hash"))) throw storage();
        long last = 0;
        for (var job : jobs) {
            String code = (String) job.get("sample_code");
            if (number(job, "id") <= last
                    || !expected.remove(code + ":" + number(job, "repeat_no"))
                    || number(job, "batch_id") != root.batchId()
                    || number(job, "snapshot_id") != saved.snapshotId()
                    || number(job, "runtime_id") != root.runtimeId()
                    || !config.equals(job.get("config_hash"))
                    || !f.rubricHash().equals(job.get("rubric_hash"))
                    || !f.inputHash(code).equals(job.get("input_hash"))) throw storage();
            last = number(job, "id");
        }
        if (!expected.isEmpty()) throw storage();
    }

    /**
     * 새로운 DB 시각·현재 epoch·실제 승인 부모만으로 정비 사유를 결정한다.
     *
     * @param root 이미 잠근 실제 루트
     * @param batch 실제 집합, 종료 후에도 최초 생성 사실을 사용
     * @param clock 새 DB 시각
     * @return 독립 정비 사유, 자격이 유효하면 null
     */
    private String cause(LockedSource root, Map<String, Object> batch, OffsetDateTime clock) {
        if (!clock.toInstant().isBefore(instant(batch.get("created_at")).plusSeconds(86400)))
            return "BATCH_EXPIRED";
        long epoch =
                jdbc.queryForObject(
                        "SELECT epoch FROM public.grade_runtime WHERE id=?",
                        Long.class,
                        root.runtimeId());
        if (epoch != number(batch, "runtime_epoch")) return "RUNTIME_EPOCH_CHANGED";
        Boolean current =
                jdbc.queryForObject(
                        """
                        SELECT c.active_yn AND c.can_review AND d.enrolled_at IS NOT NULL AND d.mfa_state='READY'
                            AND s.active_yn AND v.active_yn AND v.current_snapshot_id=b.snapshot_id
                            AND r.state='AVAILABLE' AND (v.status='REVIEW' OR (b.purpose='AVAILABILITY' AND v.status IN ('READY','PUBLISHED')))
                            AND EXISTS(SELECT 1 FROM public.story_access x WHERE x.story_id=s.id AND x.admin_id=c.id
                                AND x.permission='REVIEW' AND x.active_yn)
                        FROM public.grade_batch b JOIN public.review_snapshot f ON f.id=b.snapshot_id
                        JOIN public.story_version v ON v.id=f.version_id JOIN public.story s ON s.id=v.story_id
                        JOIN public.grade_runtime r ON r.id=b.runtime_id JOIN public.admin_account c ON c.id=b.created_by
                        JOIN public.admin_credential d ON d.account_id=c.id WHERE b.id=?
                        """,
                        Boolean.class,
                        root.batchId());
        return Boolean.TRUE.equals(current) ? null : "SOURCE_REVOKED";
    }

    /**
     * 실제 미종료 자식과 실제 RUNNING 예약만 닫고 최초 예산·암호·영수증은 보존한다.
     *
     * @param job 실제 잠근 작업
     * @param key 실제 집합 UUID
     * @param request 서버 감사 연결 UUID
     * @param cause 독립 정비 사유
     * @param rows 거래 전체 행 보존 사본
     * @throws DataAccessResourceFailureException 시도·최종 저장 불일치
     */
    private void close(
            Map<String, Object> job, UUID key, UUID request, String cause, Preservation rows) {
        String reason = "BATCH_EXPIRED".equals(cause) ? "DEADLINE_EXCEEDED" : cause;
        String state =
                Set.of("SOURCE_REVOKED", "TERMINAL").contains(cause) ? "CANCELLED" : "FAILED";
        String attemptState = "BATCH_EXPIRED".equals(cause) ? "EXPIRED" : "FAILED";
        long id = number(job, "id");
        if (job.get("result_cipher") != null
                || job.get("result_data") != null
                || job.get("result_hash") != null) throw storage();
        var attempts =
                jdbc.queryForList(
                        "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no",
                        id);
        if (attempts.size() != number(job, "call_count")) throw storage();
        Map<String, Object> active = null;
        for (int i = 0; i < attempts.size(); i++) {
            var a = attempts.get(i);
            if (number(a, "attempt_no") != i + 1) throw storage();
            if ("RUNNING".equals(a.get("state"))) {
                if (active != null
                        || !"RUNNING".equals(job.get("state"))
                        || number(a, "lease_gen") != number(job, "lease_gen")
                        || !Objects.equals(a.get("worker_key"), job.get("worker_key"))
                        || i + 1 != attempts.size()
                        || a.get("completion_data") != null
                        || a.get("output_cipher") != null
                        || a.get("output_hash") != null
                        || a.get("ended_at") != null
                        || a.get("error_code") != null
                        || a.get("observed_version") != null
                        || a.get("provider_ref") != null) throw storage();
                active = a;
            }
        }
        if ("RUNNING".equals(job.get("state")) && active == null) throw storage();
        if ("STAGED".equals(job.get("state"))
                && (!attempts.isEmpty()
                        || number(job, "lease_gen") != 0
                        || job.get("accepted_at") != null
                        || job.get("deadline_at") != null
                        || job.get("error_code") != null)) throw storage();
        if (!"STAGED".equals(job.get("state"))
                && (job.get("accepted_at") == null
                        || job.get("deadline_at") == null
                        || !instant(job.get("deadline_at"))
                                .equals(instant(job.get("accepted_at")).plusSeconds(120))))
            throw storage();
        OffsetDateTime ended = now();
        Integer attemptNo = active == null ? null : (int) number(active, "attempt_no");
        if (active != null
                && jdbc.update(
                                """
                                UPDATE public.grade_attempt SET state=?,error_code=?,ended_at=?
                                WHERE job_id=? AND attempt_no=? AND state='RUNNING' AND lease_gen=?
                                """,
                                attemptState,
                                reason,
                                ended,
                                id,
                                attemptNo,
                                number(job, "lease_gen"))
                        != 1) throw storage();
        if (jdbc.update(
                        """
                        UPDATE public.grade_job SET state=?,error_code=?,worker_key=NULL,lease_until=NULL,updated_at=?
                        WHERE id=? AND state=?
                        """,
                        state,
                        reason,
                        ended,
                        id,
                        job.get("state"))
                != 1) throw storage();
        ObjectNode effect =
                object().put("jobId", Long.toString(id))
                        .put("attemptNo", attemptNo)
                        .put("leaseGen", number(job, "lease_gen"))
                        .put("beforeJob", (String) job.get("state"))
                        .put("afterJob", state)
                        .put("beforeAttempt", active == null ? null : "RUNNING")
                        .put("afterAttempt", active == null ? null : attemptState)
                        .put("reason", reason)
                        .put("endedAt", ended.toInstant().toString());
        rows.replace("grade_job", "id=" + id);
        if (active != null)
            rows.replace("grade_attempt", "job_id=" + id + " AND attempt_no=" + attemptNo);
        audit(key, request, "BATCH_CHILD_EFFECT", effect, rows);
        Boolean valid =
                jdbc.queryForObject(
                        """
                        SELECT state=? AND error_code=? AND worker_key IS NULL AND lease_until IS NULL AND updated_at=?
                        FROM public.grade_job WHERE id=?
                        """,
                        Boolean.class,
                        state,
                        reason,
                        ended,
                        id);
        if (!Boolean.TRUE.equals(valid)) throw storage();
        if (active != null
                && !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                """
                                SELECT state=? AND error_code=? AND ended_at=? AND ended_at>=started_at AND completion_data IS NULL
                                FROM public.grade_attempt WHERE job_id=? AND attempt_no=?
                                """,
                                Boolean.class,
                                attemptState,
                                reason,
                                ended,
                                id,
                                attemptNo))) throw storage();
    }

    /**
     * 최초 출처·종류 지적을 보존하고 새 지적만 필수 SYSTEM 감사와 함께 추가한다.
     *
     * @param batch 실제 집합
     * @param key 실제 UUID
     * @param request 서버 UUID
     * @param kind GRADING 또는 INFRA
     * @param reason 고정 비민감 사유
     * @param issues 새 지적 ID 수집
     * @param rows 거래 전체 행 보존 사본
     */
    private void issue(
            Map<String, Object> batch,
            UUID key,
            UUID request,
            String kind,
            String reason,
            List<Long> issues,
            Preservation rows) {
        var old =
                jdbc.queryForList(
                        "SELECT * FROM public.execution_issue WHERE batch_id=? AND kind=? FOR"
                                + " UPDATE",
                        number(batch, "id"),
                        kind);
        if (!old.isEmpty()) return;
        UUID issueKey = UUID.randomUUID();
        OffsetDateTime created = now();
        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO public.execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code,created_at)
                        VALUES (?,?,?,?,?,'CRITICAL','OPEN',?,?) RETURNING id
                        """,
                        Long.class,
                        issueKey,
                        number(batch, "snapshot_id"),
                        number(batch, "runtime_id"),
                        number(batch, "id"),
                        kind,
                        reason,
                        created);
        if (id == null) throw storage();
        var row = jdbc.queryForMap("SELECT * FROM public.execution_issue WHERE id=?", id);
        if (!issueKey.equals(row.get("issue_key"))
                || number(row, "snapshot_id") != number(batch, "snapshot_id")
                || number(row, "runtime_id") != number(batch, "runtime_id")
                || number(row, "batch_id") != number(batch, "id")
                || !kind.equals(row.get("kind"))
                || !reason.equals(row.get("reason_code"))
                || !"CRITICAL".equals(row.get("severity"))
                || !"OPEN".equals(row.get("state"))
                || !created.toInstant().equals(instant(row.get("created_at")))
                || row.get("resolved_at") != null
                || row.get("resolved_by") != null
                || row.get("resolved_batch_id") != null
                || row.get("resolution_data") != null) throw storage();
        rows.add("execution_issue", "id=" + id);
        issues.add(id);
        audit(
                key,
                request,
                "BATCH_ISSUE",
                object().put("issueId", Long.toString(id)).put("kind", kind).put("reason", reason),
                rows);
        invalidateEvidence(batch, key, request, id, created, rows);
    }

    /**
     * 현재 REVIEW 사본의 새 실제 OPEN 지적만 모든 설정의 근거를 비가역적으로 무효화한다.
     *
     * @param batch 이미 잠근 실제 출처 집합
     * @param key 실제 집합 UUID
     * @param request 서버 감사 연결 UUID
     * @param issueId 독립 검증 및 필수 감사가 끝난 새 OPEN 지적 ID
     * @param issueCreated 검증한 실제 지적 생성 시각
     * @param rows 쓰기 전 전체 행 보존 사본
     */
    private void invalidateEvidence(
            Map<String, Object> batch,
            UUID key,
            UUID request,
            long issueId,
            OffsetDateTime issueCreated,
            Preservation rows) {
        long snapshot = number(batch, "snapshot_id");
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT v.status='REVIEW' AND v.current_snapshot_id=? FROM"
                            + " public.story_version v JOIN public.review_snapshot s ON"
                            + " s.version_id=v.id WHERE s.id=?",
                        Boolean.class,
                        snapshot,
                        snapshot))) return;
        var sets =
                jdbc.queryForList(
                        "SELECT * FROM public.evidence_set WHERE snapshot_id=? AND available_yn"
                                + " ORDER BY id FOR UPDATE",
                        snapshot);
        OffsetDateTime invalidated = now();
        if (invalidated.toInstant().isBefore(issueCreated.toInstant())) throw storage();
        for (var set : sets) {
            long id = number(set, "id");
            if (invalidated.toInstant().isBefore(instant(set.get("created_at")))) throw storage();
            if (!rows.expected.get("evidence_set").containsKey(Long.toString(id))) throw storage();
            ObjectNode expected =
                    ((ObjectNode) rows.expected.get("evidence_set").get(Long.toString(id)))
                            .deepCopy();
            expected.put("available_yn", false);
            expected.put("invalidated_issue_id", issueId);
            // 실제 PostgreSQL JSON 시각 표현은 독립 매개변수 변환으로 만든다.
            expected.set(
                    "invalidated_at",
                    parse(
                            jdbc.queryForObject(
                                    "SELECT to_jsonb(?::timestamptz)::text",
                                    String.class,
                                    invalidated)));
            if (jdbc.update(
                            "UPDATE public.evidence_set SET available_yn=false,invalidated_at=?,"
                                    + "invalidated_issue_id=? WHERE id=? AND available_yn",
                            invalidated,
                            issueId,
                            id)
                    != 1) throw storage();
            var actual = rows.read("evidence_set", "id=" + id);
            if (actual.size() != 1
                    || !java.util.Arrays.equals(
                            SnapshotJson.encode(expected),
                            SnapshotJson.encode(actual.get(Long.toString(id))))) throw storage();
            rows.expected.get("evidence_set").put(Long.toString(id), actual.get(Long.toString(id)));
        }
        audit(
                key,
                request,
                "EVIDENCE_INVALIDATE",
                object().put("issueId", Long.toString(issueId))
                        .put("snapshotId", Long.toString(snapshot))
                        .put("count", sets.size()),
                rows);
    }

    /**
     * 실제 저장한 감사 전체 행을 읽어 대조한다. JSONB 정수 폭은 공유 정규 바이트로 비교하며 원문이나 임의 scope를 받지 않는다.
     *
     * @param key 실제 집합 UUID
     * @param request 서버 UUID
     * @param action 고정 동작
     * @param detail 허용한 비민감 객체
     * @param rows 거래 전체 행 보존 사본
     * @return 실제 감사 ID
     * @throws DataAccessResourceFailureException 저장·읽기 불일치
     */
    private long audit(
            UUID key, UUID request, String action, ObjectNode detail, Preservation rows) {
        UUID event = UUID.randomUUID();
        OffsetDateTime created = now();
        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO public.test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,
                            request_id,phase,business_result,detail,created_at)
                        VALUES (?,'SYSTEM',?,?,'batch',?,?,'RESULT','SUCCESS',?::jsonb,?) RETURNING id
                        """,
                        Long.class,
                        event,
                        coordinator,
                        action,
                        "batch:" + key,
                        request,
                        encode(detail),
                        created);
        if (id == null) throw storage();
        var row = jdbc.queryForMap("SELECT * FROM public.test_audit WHERE id=?", id);
        if (!event.equals(row.get("event_key"))
                || !"SYSTEM".equals(row.get("actor_kind"))
                || !coordinator.equals(row.get("actor_ref"))
                || !action.equals(row.get("action"))
                || !"batch".equals(row.get("scope_kind"))
                || !("batch:" + key).equals(row.get("scope_key"))
                || !request.equals(row.get("request_id"))
                || !"RESULT".equals(row.get("phase"))
                || !"SUCCESS".equals(row.get("business_result"))
                || !java.util.Arrays.equals(
                        SnapshotJson.encode(detail), SnapshotJson.encode(parse(row.get("detail"))))
                || !created.toInstant().equals(instant(row.get("created_at")))) throw storage();
        rows.add("test_audit", "id=" + id);
        return id;
    }

    /**
     * 실제 부모·전체 자식의 비변경 열을 ID와 함께 보존한다. 추가·삭제도 전체 집합 대조로 검출한다.
     *
     * @param root 잠근 실제 부모
     * @param changeable 최초 미종료 실제 작업 ID 집합, 다른 행은 전체 열 보존
     * @return 원문 없는 내부 해시 객체의 정규 문자열
     */
    private String invariant(LockedSource root, Set<Long> changeable) {
        ObjectNode result = object();
        for (String table :
                List.of(
                        "story_person",
                        "story_role",
                        "story_pair",
                        "story_clue",
                        "clue_role",
                        "story_hint",
                        "story_event",
                        "story_fact",
                        "story_rubric",
                        "rubric_clue",
                        "grade_sample",
                        "review_snapshot"))
            result.put(table, digest(table, "version_id=?", root.versionId(), null));
        result.put(
                "reviews",
                digest(
                        "review_record",
                        "snapshot_id IN (SELECT id FROM public.review_snapshot WHERE version_id=?)",
                        root.versionId(),
                        null));
        result.put("story", digest("story", "id=?", root.storyId(), null));
        result.put("version", digest("story_version", "id=?", root.versionId(), null));
        result.put("runtime", digest("grade_runtime", "id=?", root.runtimeId(), null));
        result.put("account", digest("admin_account", "id=?", root.creatorId(), null));
        result.put(
                "credential", digest("admin_credential", "account_id=?", root.creatorId(), null));
        result.put("access", digest("story_access", "story_id=?", root.storyId(), null));
        result.put("storyAudits", digest("story_audit", "story_id=?", root.storyId(), null));
        result.put("storyActions", digest("story_action", "story_id=?", root.storyId(), null));
        result.put("transfers", digest("story_transfer", "story_id=?", root.storyId(), null));
        result.put(
                "batch",
                digest(
                        "grade_batch",
                        "id=?",
                        root.batchId(),
                        "to_jsonb(t)-ARRAY['state','passed_yn','ended_at','valid_until']"));
        String ids =
                changeable.isEmpty()
                        ? "0"
                        : changeable.stream()
                                .sorted()
                                .map(Object::toString)
                                .collect(java.util.stream.Collectors.joining(","));
        result.put(
                "jobs",
                digest(
                        "grade_job",
                        "batch_id=?",
                        root.batchId(),
                        "CASE WHEN t.id IN ("
                                + ids
                                + ") THEN"
                                + " to_jsonb(t)-ARRAY['state','error_code','worker_key','lease_until','updated_at']"
                                + " ELSE to_jsonb(t) END"));
        result.put(
                "attempts",
                digest(
                        "grade_attempt",
                        "job_id IN (SELECT id FROM public.grade_job WHERE batch_id=?)",
                        root.batchId(),
                        "CASE WHEN t.job_id IN ("
                                + ids
                                + ") AND t.completion_data IS NULL AND t.lease_gen=(SELECT"
                                + " lease_gen FROM public.grade_job WHERE id=t.job_id) THEN"
                                + " to_jsonb(t)-ARRAY['state','error_code','ended_at'] ELSE"
                                + " to_jsonb(t) END"));
        result.put(
                "events",
                digest(
                        "grade_event",
                        "job_id IN (SELECT id FROM public.grade_job WHERE batch_id=?)",
                        root.batchId(),
                        null));
        return encode(result);
    }

    /**
     * 내부 고정 테이블만 전체 행 해시로 비교하며 원문을 반환하지 않는다.
     *
     * @param table 내부 고정 테이블명
     * @param where 내부 고정 SQL 조건
     * @param id 실제 부모 ID
     * @param projection 허용 변경 열 제외식, null이면 전체 열
     * @return 순서·식별자를 포함한 내부 해시 배열
     */
    private String digest(String table, String where, long id, String projection) {
        String value = projection == null ? "to_jsonb(t)" : projection;
        return jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(encode(sha256(convert_to(("
                        + value
                        + ")::text,'UTF8')),'hex')"
                        + " ORDER BY ("
                        + value
                        + ")::text COLLATE \"C\"),'[]'::jsonb)::text FROM public."
                        + table
                        + " t WHERE "
                        + where,
                String.class,
                id);
    }

    /** 최종 전체 행 대조를 위한 거래 내부 사본이다. 외부 결과나 감사에는 원문을 노출하지 않는다. */
    private final class Preservation {
        private final Map<String, String> predicates = new java.util.LinkedHashMap<>();
        private final Map<String, Map<String, JsonNode>> expected = new java.util.LinkedHashMap<>();

        /**
         * @param root 이미 잠근 실제 루트, 같은 거래에서만 사용
         */
        private Preservation(LockedSource root) {
            String snapshots =
                    "snapshot_id IN (SELECT id FROM public.review_snapshot WHERE version_id="
                            + root.versionId()
                            + ")";
            predicates.put("grade_batch", snapshots);
            predicates.put("grade_job", snapshots);
            String jobs = "job_id IN (SELECT id FROM public.grade_job WHERE " + snapshots + ")";
            predicates.put("grade_attempt", jobs);
            predicates.put("grade_event", jobs);
            predicates.put("execution_issue", snapshots);
            predicates.put("evidence_set", snapshots);
            predicates.put("evidence_item", snapshots);
            predicates.put("review_record", snapshots);
            predicates.put("test_action", "scope_key='version:" + root.versionId() + "'");
            predicates.put(
                    "test_audit",
                    "scope_key IN ('version:"
                            + root.versionId()
                            + "','batch:"
                            + root.batchKey()
                            + "') OR scope_key IN (SELECT 'batch:'||batch_key::text FROM"
                            + " public.grade_batch WHERE "
                            + snapshots
                            + ")");
            predicates.forEach((table, predicate) -> expected.put(table, read(table, predicate)));
        }

        /**
         * 내부 고정 테이블의 전체 실제 행과 복합 식별자를 읽는다.
         *
         * @param table 내부 고정 테이블명
         * @param predicate 내부 실제 양의 ID 조건
         * @return 동일성을 보존하는 전체 행 사본
         */
        private Map<String, JsonNode> read(String table, String predicate) {
            Map<String, JsonNode> result = new java.util.LinkedHashMap<>();
            for (String text :
                    jdbc.queryForList(
                            "SELECT to_jsonb(t)::text FROM public."
                                    + table
                                    + " t WHERE "
                                    + predicate,
                            String.class)) {
                JsonNode row = parse(text);
                String id =
                        "grade_attempt".equals(table)
                                ? row.path("job_id").asText()
                                        + ":"
                                        + row.path("attempt_no").asText()
                                : "evidence_item".equals(table)
                                        ? row.path("set_id").asText()
                                                + ":"
                                                + row.path("batch_id").asText()
                                        : row.path("id").asText();
                if (result.put(id, row) != null) throw storage();
            }
            return result;
        }

        /**
         * 검증한 실제 전이 행 하나만 예상 사본으로 교체한다.
         *
         * @param table 내부 고정 테이블
         * @param predicate 단일 실제 행 조건
         */
        private void replace(String table, String predicate) {
            var actual = read(table, predicate);
            if (actual.size() != 1 || !expected.get(table).keySet().containsAll(actual.keySet()))
                throw storage();
            List<String> mutable =
                    switch (table) {
                        case "grade_batch" ->
                                List.of("state", "passed_yn", "ended_at", "valid_until");
                        case "grade_job" ->
                                List.of(
                                        "state",
                                        "error_code",
                                        "worker_key",
                                        "lease_until",
                                        "updated_at");
                        case "grade_attempt" -> List.of("state", "error_code", "ended_at");
                        default -> throw storage();
                    };
            actual.forEach(
                    (id, row) -> {
                        ObjectNode old = ((ObjectNode) expected.get(table).get(id)).deepCopy();
                        ObjectNode current = ((ObjectNode) row).deepCopy();
                        old.remove(mutable);
                        current.remove(mutable);
                        if (!old.equals(current)) throw storage();
                    });
            expected.get(table).putAll(actual);
        }

        /**
         * 검증한 새 필수 행 하나만 예상 전체 집합에 추가한다.
         *
         * @param table 내부 고정 테이블
         * @param predicate 새 실제 ID 조건
         */
        private void add(String table, String predicate) {
            var actual = read(table, predicate);
            if (actual.size() != 1
                    || actual.keySet().stream().anyMatch(expected.get(table)::containsKey))
                throw storage();
            expected.get(table).putAll(actual);
        }

        /** 마지막 쓰기 뒤 전체 행·식별자의 추가·삭제·변경을 재검사한다. */
        private void verify() {
            predicates.forEach(
                    (table, predicate) -> {
                        if (!expected.get(table).equals(read(table, predicate))) throw storage();
                    });
        }
    }

    /** 실제 모든 자식을 id 정순으로 잠그며 원문 없는 비교에 필요한 암호만 내부에 유지한다. */
    private List<Map<String, Object>> jobs(long id) {
        return jdbc.queryForList(
                "SELECT *,result_cipher IS NOT NULL AS has_result_cipher FROM public.grade_job"
                        + " WHERE batch_id=? ORDER BY id FOR UPDATE",
                id);
    }

    /** 매 검사 직전에 실제 DB 시계를 새로 읽는다. */
    private OffsetDateTime now() {
        var clock = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (clock == null) throw storage();
        return clock;
    }

    /** 모든 주변 거래·동기화·바인딩을 거절한다. */
    private static void requireSeparate() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())
            throw new IllegalStateException("AGGREGATION_REQUIRES_SEPARATE_TRANSACTION");
    }

    /** 저장된 역사 결과만 반환하며 운영 효력을 발급하지 않는다. */
    private static AggregationResult result(boolean changed, Map<String, Object> batch) {
        return new AggregationResult(
                changed,
                (UUID) batch.get("batch_key"),
                (String) batch.get("state"),
                (Boolean) batch.get("passed_yn"),
                "NONE");
    }

    /** 숫자형 실제 식별자는 문자열로 추측하지 않는다. */
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** JDBC 저장 시각을 동일 순간으로 비교한다. */
    private static java.time.Instant instant(Object value) {
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof java.sql.Timestamp time) return time.toInstant();
        throw storage();
    }

    /** 공유 엄격 parser만 사용한다. */
    private static JsonNode parse(Object value) {
        return SnapshotJson.parse(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 안전 JSON만 정규 인코딩한다. */
    private static String encode(JsonNode value) {
        return new String(SnapshotJson.encode(value), StandardCharsets.UTF_8);
    }

    /** 새 안전 객체를 소유한다. */
    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** 저장 오류에 SQL·원문·원인을 포함하지 않는다. */
    private static DataAccessResourceFailureException storage() {
        return new DataAccessResourceFailureException("AGGREGATION_STORAGE_FAILURE");
    }

    /**
     * 역사 결과이며 실행 권한·가용성·효력 정보는 포함하지 않는다.
     *
     * @param changed 실제 결과·지적 전이 여부, 필수 읽기 감사만 추가하면 false
     * @param batchKey 실제 집합 UUID
     * @param state 실제 저장 상태
     * @param passed 실제 역사 비교 결과, 미발급·기술 실패이면 null
     * @param reason 고정 안전 사유, 설치 부재는 INSTALLED_RUNTIME_MISMATCH
     */
    public record AggregationResult(
            boolean changed, UUID batchKey, String state, Boolean passed, String reason) {}
}
