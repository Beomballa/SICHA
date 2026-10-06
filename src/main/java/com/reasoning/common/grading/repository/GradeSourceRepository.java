package com.reasoning.common.grading.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 실제 BATCH 루트 잠금과 신규 실행의 현재 출처 자격을 분리하는 경계다. */
@Repository
public class GradeSourceRepository {
    private static final String RELATIONSHIPS =
            """
            SELECT j.id AS job_id,j.batch_id,j.snapshot_id,j.runtime_id,
                b.created_by AS creator_id,b.runtime_id AS batch_runtime_id,
                b.snapshot_id AS batch_snapshot_id,s.version_id,v.story_id
            FROM public.grade_job j
            JOIN public.grade_batch b ON b.id=j.batch_id
            JOIN public.review_snapshot s ON s.id=b.snapshot_id
            JOIN public.story_version v ON v.id=s.version_id
            WHERE j.job_key=?
            """;
    private static final String VERSION_COLUMNS =
            "story_id,active_yn,status,current_snapshot_id,edit_rev,policy_code";
    private static final String RUNTIME_COLUMNS = "code,config_hash,state,epoch";
    private static final String BATCH_COLUMNS =
            "batch_key,snapshot_id,runtime_id,created_by,purpose,state,runtime_epoch,"
                    + "config_hash,rubric_hash,payload_hash,dataset_hash,created_at";
    private static final String JOB_COLUMNS =
            "job_key,batch_id,snapshot_id,runtime_id,state,config_hash,rubric_hash,"
                    + "input_hash,sample_code,repeat_no";
    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 호출자 트랜잭션과 같은 DataSource의 DB 도구, null 불가
     */
    public GradeSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * 호출자 소유 쓰기 READ COMMITTED 트랜잭션에서 실제 account → credential → runtime → story → version → batch →
     * job을 별도 문장으로 잠근다. 호출자는 하위 job/lease 잠금을 먼저 보유하지 않아야 한다. 발견 관계가 바뀌면 새 루트를 추가하지 않고 거절한다. 회수된
     * 출처와 종료된 작업도 실제 루트가 존재하면 잠근다. 반환값은 사실 증거이며 신규 실행 허가나 영수증 접근 권한이 아니다. 외부 호출·시도 생성·임대 변경·예산 소비를
     * 하지 않는다. 원문이나 비밀을 조회하지 않는다.
     *
     * @param jobKey 존재하는 BATCH 작업 UUID, null 불가
     * @return 이 저장소와 현재 연결 자원에 결속된 불변 사실 증거
     * @throws IllegalArgumentException jobKey가 null인 경우
     * @throws IllegalStateException 트랜잭션 조건 또는 실제 루트 관계가 잘못된 경우
     * @throws DataAccessResourceFailureException 민감한 원인을 제외한 고정 DB 실패
     */
    public LockedSource lockRoots(UUID jobKey) {
        if (jobKey == null) throw new IllegalArgumentException("INVALID_SOURCE_KEY");
        try {
            ConnectionHolder holder = requireTransaction();
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            Hints hints = discover(jobKey);
            Map<String, Object> account =
                    locked("admin_account", "id", hints.creatorId(), "active_yn,can_review");
            Map<String, Object> credential =
                    locked(
                            "admin_credential",
                            "account_id",
                            hints.creatorId(),
                            "enrolled_at,mfa_state");
            Map<String, Object> runtime =
                    locked("grade_runtime", "id", hints.batchRuntimeId(), RUNTIME_COLUMNS);
            Map<String, Object> story = locked("story", "id", hints.storyId(), "active_yn");
            Map<String, Object> version =
                    locked("story_version", "id", hints.versionId(), VERSION_COLUMNS);
            Map<String, Object> batch = locked("grade_batch", "id", hints.batchId(), BATCH_COLUMNS);
            Map<String, Object> job = locked("grade_job", "id", hints.jobId(), JOB_COLUMNS);
            if (!hints.equals(discover(jobKey)) || !jobKey.equals(job.get("job_key"))) reject();
            // 자원 holder가 재사용돼도 종료된 TX의 증거를 새 TX로 옮기지 못하게 한다.
            LockLifetime transaction = new LockLifetime();
            TransactionSynchronizationManager.registerSynchronization(transaction);
            return new LockedSource(
                    this,
                    holder,
                    transaction,
                    hints,
                    evidence(
                            hints,
                            jobKey,
                            runtime,
                            version,
                            batch,
                            job,
                            snapshot(hints),
                            createdAt(hints),
                            null),
                    (String) runtime.get("state"),
                    (String) batch.get("state"),
                    (String) job.get("state"),
                    Boolean.TRUE.equals(account.get("active_yn")),
                    Boolean.TRUE.equals(account.get("can_review")),
                    credential.get("enrolled_at") != null,
                    (String) credential.get("mfa_state"),
                    Boolean.TRUE.equals(story.get("active_yn")),
                    Boolean.TRUE.equals(version.get("active_yn")));
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("SOURCE_STORAGE_FAILURE");
        }
    }

    /**
     * 같은 호출자 트랜잭션의 실제 잠금 증거에 신규 실행의 현재 자격을 적용한다. 제한된 codec/manifest 검증 뒤 호출해야 한다. 잠근 행도 다시 읽어 같은
     * 트랜잭션의 변경을 과거 자격으로 재사용하지 않으며 매번 clock_timestamp()를 새로 읽는다. 현재 활성·등록 완료·MFA READY·전역 REVIEW·사건
     * REVIEW를 요구한다. 소유권·EDIT·MANAGE·최초 세션·최초 auth_rev로 대신하지 않는다. worker 인증/runtime 허용, 실제
     * 입력·dataset·rubric·config 해시 검증, lease/deadline fencing과 호출 예약은 별도 필수 단계다.
     *
     * @param source 이 저장소가 현재 트랜잭션에서 만든 사실 증거, null 불가
     * @return 현재 출처 자격을 검사한 메타데이터와 DB 실제 검사 시각, dispatch 허가는 아님
     * @throws IllegalArgumentException source가 null인 경우
     * @throws IllegalStateException 증거 소유권·수명·현재 자격 또는 24시간 효력이 잘못된 경우
     * @throws DataAccessResourceFailureException 민감한 원인을 제외한 고정 DB 실패
     */
    public RootEvidence requireExecutionEligibility(LockedSource source) {
        if (source == null) throw new IllegalArgumentException("INVALID_SOURCE_EVIDENCE");
        try {
            ConnectionHolder holder = requireTransaction();
            if (source.owner != this
                    || source.holder != holder
                    || !source.transaction.valid
                    || !TransactionSynchronizationManager.getSynchronizations()
                            .contains(source.transaction)) {
                throw new IllegalStateException("SOURCE_EVIDENCE_TRANSACTION_MISMATCH");
            }
            Hints hints = source.hints;
            UUID jobKey = source.jobKey();
            if (!hints.equals(discover(jobKey))) reject();
            Map<String, Object> account =
                    current("admin_account", "id", hints.creatorId(), "active_yn,can_review");
            Map<String, Object> credential =
                    current(
                            "admin_credential",
                            "account_id",
                            hints.creatorId(),
                            "enrolled_at,mfa_state");
            Map<String, Object> runtime =
                    current("grade_runtime", "id", hints.batchRuntimeId(), RUNTIME_COLUMNS);
            Map<String, Object> story = current("story", "id", hints.storyId(), "active_yn");
            Map<String, Object> version =
                    current("story_version", "id", hints.versionId(), VERSION_COLUMNS);
            Map<String, Object> batch =
                    current("grade_batch", "id", hints.batchId(), BATCH_COLUMNS);
            Map<String, Object> job = current("grade_job", "id", hints.jobId(), JOB_COLUMNS);
            if (!jobKey.equals(job.get("job_key"))) reject();
            Boolean review =
                    jdbc.queryForObject(
                            """
                            SELECT EXISTS(SELECT 1 FROM public.story_access
                                WHERE story_id=? AND admin_id=? AND permission='REVIEW' AND active_yn)
                            """,
                            Boolean.class,
                            hints.storyId(),
                            hints.creatorId());
            if (!Boolean.TRUE.equals(account.get("active_yn"))
                    || !Boolean.TRUE.equals(account.get("can_review"))
                    || credential.get("enrolled_at") == null
                    || !"READY".equals(credential.get("mfa_state"))
                    || !Boolean.TRUE.equals(review)) reject();
            String status = (String) version.get("status");
            String purpose = (String) batch.get("purpose");
            if (!Boolean.TRUE.equals(story.get("active_yn"))
                    || !Boolean.TRUE.equals(version.get("active_yn"))
                    || !Objects.equals(version.get("current_snapshot_id"), hints.snapshotId())
                    || hints.snapshotId() != hints.batchSnapshotId()
                    || hints.runtimeId() != hints.batchRuntimeId()
                    || !"RUNNING".equals(batch.get("state"))
                    || !"AVAILABLE".equals(runtime.get("state"))
                    || !runtime.get("epoch").equals(batch.get("runtime_epoch"))
                    || !runtime.get("config_hash").equals(batch.get("config_hash"))
                    || !runtime.get("config_hash").equals(job.get("config_hash"))
                    || !batch.get("rubric_hash").equals(job.get("rubric_hash"))
                    || !("STAGED".equals(job.get("state"))
                            || "QUEUED".equals(job.get("state"))
                            || "RUNNING".equals(job.get("state")))
                    || !("REVIEW".equals(status)
                            || ("AVAILABILITY".equals(purpose)
                                    && ("READY".equals(status) || "PUBLISHED".equals(status)))))
                reject();
            Map<String, Object> snapshot = snapshot(hints);
            OffsetDateTime created = createdAt(hints);
            OffsetDateTime now =
                    jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            if (now == null || created == null || !now.isBefore(created.plusHours(24))) {
                throw new IllegalStateException("BATCH_EXPIRED");
            }
            return evidence(hints, jobKey, runtime, version, batch, job, snapshot, created, now);
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("SOURCE_STORAGE_FAILURE");
        }
    }

    /** 실제 호출자 연결 자원과 쓰기 READ COMMITTED 조건을 확인한다. */
    private ConnectionHolder requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        }
        Object resource =
                TransactionSynchronizationManager.getResource(
                        Objects.requireNonNull(jdbc.getDataSource()));
        if (!(resource instanceof ConnectionHolder holder)
                || holder.getConnectionHandle() == null) {
            throw new IllegalStateException("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        }
        Boolean bound =
                jdbc.execute(
                        (ConnectionCallback<Boolean>)
                                connection -> {
                                    // 종료 억제 프록시가 아닌 실제 연결과 바인딩된 자원을 대조한다.
                                    var target = DataSourceUtils.getTargetConnection(connection);
                                    return DataSourceUtils.isConnectionTransactional(
                                                    target, jdbc.getDataSource())
                                            && target
                                                    == DataSourceUtils.getTargetConnection(
                                                            holder.getConnection())
                                            && !target.getAutoCommit();
                                });
        Boolean validTransaction =
                jdbc.queryForObject(
                        """
                        SELECT current_setting('transaction_isolation')='read committed'
                            AND current_setting('transaction_read_only')='off'
                        """,
                        Boolean.class);
        if (!Boolean.TRUE.equals(bound) || !Boolean.TRUE.equals(validTransaction)) {
            throw new IllegalStateException("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        }
        return holder;
    }

    /** JOIN은 발견만 하며 어떤 하위 행도 미리 잠그지 않는다. */
    private Hints discover(UUID key) {
        var rows =
                jdbc.query(
                        RELATIONSHIPS,
                        (row, index) ->
                                new Hints(
                                        row.getLong("job_id"),
                                        row.getLong("batch_id"),
                                        row.getLong("snapshot_id"),
                                        row.getLong("runtime_id"),
                                        row.getLong("creator_id"),
                                        row.getLong("batch_runtime_id"),
                                        row.getLong("batch_snapshot_id"),
                                        row.getLong("version_id"),
                                        row.getLong("story_id")),
                        key);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    /** 테이블·컬럼은 내부 고정 상수만 사용하며 한 실제 루트씩 잠근다. */
    private Map<String, Object> locked(String table, String key, long id, String columns) {
        var rows =
                jdbc.queryForList(
                        "SELECT "
                                + columns
                                + " FROM public."
                                + table
                                + " WHERE "
                                + key
                                + "=? FOR UPDATE",
                        id);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    /** 이미 잠근 실제 행을 다시 읽으며 새 루트 잠금을 추가하지 않는다. */
    private Map<String, Object> current(String table, String key, long id, String columns) {
        var rows =
                jdbc.queryForList(
                        "SELECT " + columns + " FROM public." + table + " WHERE " + key + "=?", id);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    /** 불변 사본의 실제 부모와 원문 없는 메타데이터만 읽는다. */
    private Map<String, Object> snapshot(Hints hints) {
        Map<String, Object> snapshot =
                jdbc.queryForMap(
                        "SELECT version_id,edit_rev,format_no FROM public.review_snapshot WHERE"
                                + " id=?",
                        hints.snapshotId());
        if (number(snapshot, "version_id") != hints.versionId()) reject();
        return snapshot;
    }

    /** JDBC 시각 변환은 드라이버의 OffsetDateTime 매핑을 사용한다. */
    private OffsetDateTime createdAt(Hints hints) {
        return jdbc.queryForObject(
                "SELECT created_at FROM public.grade_batch WHERE id=?",
                OffsetDateTime.class,
                hints.batchId());
    }

    /** 원문 없는 고정 메타데이터를 구성하며 저장된 해시의 진위를 주장하지 않는다. */
    private RootEvidence evidence(
            Hints hints,
            UUID jobKey,
            Map<String, Object> runtime,
            Map<String, Object> version,
            Map<String, Object> batch,
            Map<String, Object> job,
            Map<String, Object> snapshot,
            OffsetDateTime created,
            OffsetDateTime now) {
        return new RootEvidence(
                hints.jobId(),
                jobKey,
                hints.batchId(),
                (UUID) batch.get("batch_key"),
                hints.creatorId(),
                hints.storyId(),
                hints.versionId(),
                hints.snapshotId(),
                hints.batchRuntimeId(),
                (String) runtime.get("code"),
                (String) runtime.get("config_hash"),
                number(runtime, "epoch"),
                (String) batch.get("purpose"),
                (String) version.get("status"),
                number(version, "edit_rev"),
                number(snapshot, "edit_rev"),
                number(snapshot, "format_no"),
                (String) version.get("policy_code"),
                (String) batch.get("payload_hash"),
                (String) batch.get("dataset_hash"),
                (String) batch.get("rubric_hash"),
                (String) job.get("input_hash"),
                (String) job.get("sample_code"),
                number(job, "repeat_no"),
                created,
                now);
    }

    /** JDBC 정수 폭 차이만 흡수한다. */
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** 권한·출처 거절에 SQL 데이터나 원문을 포함하지 않는다. */
    private static void reject() {
        throw new IllegalStateException("SOURCE_NOT_CURRENT");
    }

    private record Hints(
            long jobId,
            long batchId,
            long snapshotId,
            long runtimeId,
            long creatorId,
            long batchRuntimeId,
            long batchSnapshotId,
            long versionId,
            long storyId) {}

    /** 관리된 저장점 롤백은 실제 잠금을 해제할 수 있으므로 모든 기존 잠금 증거를 보수적으로 무효화한다. */
    private static final class LockLifetime implements TransactionSynchronization {
        private boolean valid = true;

        @Override
        public void savepointRollback(Object savepoint) {
            valid = false;
        }

        @Override
        public void afterCompletion(int status) {
            valid = false;
        }
    }

    /** 사실 증거만 보관하며 연결 자원·생성자를 외부에 노출하거나 공개 생성하지 않는다. */
    public static final class LockedSource {
        private final GradeSourceRepository owner;
        private final ConnectionHolder holder;
        private final LockLifetime transaction;
        private final Hints hints;
        private final RootEvidence facts;
        private final String runtimeState;
        private final String batchState;
        private final String jobState;
        private final boolean creatorActive;
        private final boolean creatorCanReview;
        private final boolean credentialEnrolled;
        private final String credentialMfaState;
        private final boolean storyActive;
        private final boolean versionActive;

        private LockedSource(
                GradeSourceRepository owner,
                ConnectionHolder holder,
                LockLifetime transaction,
                Hints hints,
                RootEvidence facts,
                String runtimeState,
                String batchState,
                String jobState,
                boolean creatorActive,
                boolean creatorCanReview,
                boolean credentialEnrolled,
                String credentialMfaState,
                boolean storyActive,
                boolean versionActive) {
            this.owner = owner;
            this.holder = Objects.requireNonNull(holder);
            this.transaction = Objects.requireNonNull(transaction);
            this.hints = hints;
            this.facts = facts;
            this.runtimeState = runtimeState;
            this.batchState = batchState;
            this.jobState = jobState;
            this.creatorActive = creatorActive;
            this.creatorCanReview = creatorCanReview;
            this.credentialEnrolled = credentialEnrolled;
            this.credentialMfaState = credentialMfaState;
            this.storyActive = storyActive;
            this.versionActive = versionActive;
        }

        public long jobId() {
            return facts.jobId();
        }

        public UUID jobKey() {
            return facts.jobKey();
        }

        public long batchId() {
            return facts.batchId();
        }

        public UUID batchKey() {
            return facts.batchKey();
        }

        public long creatorId() {
            return facts.creatorId();
        }

        public long storyId() {
            return facts.storyId();
        }

        public long versionId() {
            return facts.versionId();
        }

        public long snapshotId() {
            return facts.snapshotId();
        }

        public long runtimeId() {
            return facts.runtimeId();
        }

        public String runtimeCode() {
            return facts.runtimeCode();
        }

        public String configHash() {
            return facts.configHash();
        }

        public long runtimeEpoch() {
            return facts.runtimeEpoch();
        }

        public String purpose() {
            return facts.purpose();
        }

        public String versionStatus() {
            return facts.versionStatus();
        }

        public long versionRev() {
            return facts.versionRev();
        }

        public long snapshotRev() {
            return facts.snapshotRev();
        }

        public long snapshotFormat() {
            return facts.snapshotFormat();
        }

        public String policyCode() {
            return facts.policyCode();
        }

        public String payloadHash() {
            return facts.payloadHash();
        }

        public String datasetHash() {
            return facts.datasetHash();
        }

        public String rubricHash() {
            return facts.rubricHash();
        }

        public String inputHash() {
            return facts.inputHash();
        }

        public String sampleCode() {
            return facts.sampleCode();
        }

        public long repeatNo() {
            return facts.repeatNo();
        }

        public OffsetDateTime batchCreatedAt() {
            return facts.batchCreatedAt();
        }

        public String runtimeState() {
            return runtimeState;
        }

        public String batchState() {
            return batchState;
        }

        public String jobState() {
            return jobState;
        }

        public boolean creatorActive() {
            return creatorActive;
        }

        public boolean creatorCanReview() {
            return creatorCanReview;
        }

        public boolean credentialEnrolled() {
            return credentialEnrolled;
        }

        public String credentialMfaState() {
            return credentialMfaState;
        }

        public boolean storyActive() {
            return storyActive;
        }

        public boolean versionActive() {
            return versionActive;
        }

        @Override
        public String toString() {
            return "LockedSource[jobId="
                    + jobId()
                    + ", batchId="
                    + batchId()
                    + ", snapshotId="
                    + snapshotId()
                    + "]";
        }
    }

    /** 저장된 해시는 미검증 선언값이며 검사 시각은 이후 예약의 시각을 대신하지 않는다. */
    public record RootEvidence(
            long jobId,
            UUID jobKey,
            long batchId,
            UUID batchKey,
            long creatorId,
            long storyId,
            long versionId,
            long snapshotId,
            long runtimeId,
            String runtimeCode,
            String configHash,
            long runtimeEpoch,
            String purpose,
            String versionStatus,
            long versionRev,
            long snapshotRev,
            long snapshotFormat,
            String policyCode,
            String payloadHash,
            String datasetHash,
            String rubricHash,
            String inputHash,
            String sampleCode,
            long repeatNo,
            OffsetDateTime batchCreatedAt,
            OffsetDateTime checkedAt) {
        @Override
        public String toString() {
            return "RootEvidence[jobId="
                    + jobId
                    + ", batchId="
                    + batchId
                    + ", snapshotId="
                    + snapshotId
                    + "]";
        }
    }
}
