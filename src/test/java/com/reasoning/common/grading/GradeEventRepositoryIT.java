package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeEventRepository;
import com.reasoning.common.grading.repository.GradeEventRepository.AttemptState;
import com.reasoning.common.grading.repository.GradeEventRepository.Detail;
import com.reasoning.common.grading.repository.GradeEventRepository.EventKind;
import com.reasoning.common.grading.repository.GradeEventRepository.JobState;
import com.reasoning.common.grading.repository.GradeEventRepository.Reason;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 실제 폐기 PG V14의 감사·업무 원자성 시험이다. root 승인·모델 의미·HTTP·배포 검증이 아니다. */
class GradeEventRepositoryIT {
    private static final String HASH = GradeSchemaIT.HASH;
    private static final String COORDINATOR = "deployment_coordinator_1";
    private static final UUID ZERO = new UUID(0, 0);
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private GradeWorkerCredentials credentials;
    private VerifiedWorker worker;
    private GradeEventRepository repository;
    private TransactionTemplate transaction;
    private String runtimeCode;
    private long job;

    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** {}는 실제 관계 fixture일 뿐 frozen 입력이나 의미 품질 근거가 아니다. 합성 256비트 토큰으로 실제 인증한다. */
    @BeforeEach
    void fixtures() {
        runtimeCode = "AUDIT_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        credentials = registry(runtimeCode, Set.of(Action.COMPLETE));
        worker = credentials.authenticate(header());
        repository = new GradeEventRepository(jdbc, credentials, COORDINATOR);
        transaction =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        long owner =
                id(
                        "INSERT INTO admin_account(account_key) VALUES (?) RETURNING id",
                        UUID.randomUUID());
        long story =
                id(
                        "INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id",
                        runtimeCode,
                        owner);
        long version =
                id(
                        """
                        INSERT INTO story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'감사 관계 fixture','H2',?,?) RETURNING id
                        """,
                        story,
                        owner,
                        owner);
        long snapshot =
                id(
                        """
                        INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                        VALUES (?,0,'{}',?,?) RETURNING id
                        """,
                        version,
                        UUID.randomUUID(),
                        owner);
        long runtime =
                id(
                        "INSERT INTO grade_runtime(code,config_hash,config_data,state)"
                                + " VALUES (?,?,'{}','AVAILABLE') RETURNING id",
                        runtimeCode,
                        HASH);
        long batch =
                id(
                        """
                        INSERT INTO grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,
                            rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)
                        VALUES (?,?,?,'REVIEW',?,?,?,?,0,'STAGED',3,?) RETURNING id
                        """,
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        HASH,
                        HASH,
                        HASH,
                        HASH,
                        owner);
        job =
                id(
                        """
                        INSERT INTO grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,
                            state,input_hash,config_hash,rubric_hash)
                        VALUES (?,?,?,?,'ONE',1,'STAGED',?,?,?) RETURNING id
                        """,
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        batch,
                        HASH,
                        HASH,
                        HASH);
        for (int attempt : List.of(1, 3)) {
            jdbc.update(
                    "INSERT INTO grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                            + " VALUES (?,?,?,'audit-worker_1','RUNNING')",
                    job,
                    attempt,
                    attempt);
        }
    }

    @Test
    void workerKindsActualIdentityActorRequestClockAndFixedCanonicalDetail() {
        for (EventKind kind : List.of(EventKind.COMPLETE_APPLIED, EventKind.COMPLETE_REJECTED)) {
            UUID command = UUID.randomUUID();
            UUID request = UUID.randomUUID();
            Detail detail = detail();
            long event =
                    transaction.execute(
                            status -> {
                                jdbc.queryForList("SELECT pg_sleep(0.02)");
                                long result =
                                        repository.recordWorkerEvent(
                                                worker, job, 3, kind, command, request, HASH,
                                                detail);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT created_at>now() AND"
                                                            + " created_at<=clock_timestamp() FROM"
                                                            + " grade_event WHERE id=?",
                                                        Boolean.class,
                                                        result))
                                        .isTrue();
                                return result;
                            });
            assertThat(event).isPositive();
            var row =
                    jdbc.queryForMap(
                            "SELECT *,octet_length(detail::text) AS bytes FROM grade_event WHERE"
                                + " id=?",
                            event);
            assertThat(row)
                    .containsEntry("job_id", job)
                    .containsEntry("actor_kind", "WORKER")
                    .containsEntry("actor_key", worker.workerKey())
                    .containsEntry("command_key", command)
                    .containsEntry("request_id", request)
                    .containsEntry("event_kind", kind.name())
                    .containsEntry("command_hash", HASH);
            assertThat((Integer) row.get("bytes")).isLessThanOrEqualTo(4096);
            var parsed =
                    SnapshotJson.parse(
                            row.get("detail").toString().getBytes(StandardCharsets.UTF_8));
            assertThat(parsed.size()).isEqualTo(8);
            assertThat(new String(SnapshotJson.encode(parsed), StandardCharsets.UTF_8))
                    .isEqualTo(
                            "{\"afterAttempt\":\"SUCCEEDED\",\"afterJob\":\"COMPLETED\",\"beforeAttempt\":\"RUNNING\","
                                + "\"beforeJob\":\"RUNNING\",\"leaseGen\":3,\"outputHash\":null,\"reason\":\"NONE\",\"resultHash\":null}");
            assertThat(detail.toString()).doesNotContain("outputHash", "resultHash", HASH);
        }
    }

    @Test
    void systemClosedShapesUseConstructorActorAndNullRequest() {
        for (EventKind kind :
                List.of(
                        EventKind.RECOVERY_EXPIRED,
                        EventKind.JOB_ACTIVATED,
                        EventKind.JOB_INPUT_REJECTED,
                        EventKind.JOB_SOURCE_CANCELLED)) {
            Integer attempt = kind == EventKind.RECOVERY_EXPIRED ? 1 : null;
            long event =
                    transaction.execute(
                            status ->
                                    repository.recordSystemEvent(
                                            job,
                                            attempt,
                                            kind,
                                            UUID.randomUUID(),
                                            HASH,
                                            new Detail(
                                                    0,
                                                    JobState.STAGED,
                                                    JobState.CANCELLED,
                                                    null,
                                                    null,
                                                    Reason.SOURCE_REVOKED,
                                                    HASH,
                                                    HASH)));
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT actor_kind,actor_key,attempt_no,request_id FROM"
                                        + " grade_event WHERE id=?",
                                    event))
                    .containsEntry("actor_kind", "SYSTEM")
                    .containsEntry("actor_key", COORDINATOR)
                    .containsEntry("attempt_no", attempt)
                    .containsEntry("request_id", null);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT detail->'beforeAttempt'='null'::jsonb AND"
                                        + " detail->'afterAttempt'='null'::jsonb FROM grade_event"
                                        + " WHERE id=?",
                                    Boolean.class,
                                    event))
                    .isTrue();
        }
    }

    @Test
    void foreignRegistryAndActualRuntimeOrActionScopeDenyWithoutEvent() {
        VerifiedWorker foreign =
                registry(runtimeCode, Set.of(Action.COMPLETE)).authenticate(header());
        denied(repository, foreign);
        denied(repository, null);
        for (GradeWorkerCredentials restricted :
                List.of(
                        registry("OTHER_RUNTIME", Set.of(Action.COMPLETE)),
                        registry(runtimeCode, Set.of(Action.START)))) {
            denied(
                    new GradeEventRepository(jdbc, restricted, COORDINATOR),
                    restricted.authenticate(header()));
        }
        assertThat(count()).isZero();
    }

    @Test
    void typedDetailAndCoordinatorInvalidValuesAreCauseFree() {
        for (String key : new String[] {null, "", "한글", "a b", "a.b", "a".repeat(81)}) {
            invalid(() -> new GradeEventRepository(jdbc, credentials, key));
        }
        invalid(
                () ->
                        new Detail(
                                -1,
                                JobState.STAGED,
                                JobState.QUEUED,
                                null,
                                null,
                                Reason.NONE,
                                null,
                                null));
        invalid(() -> new Detail(0, null, JobState.QUEUED, null, null, Reason.NONE, null, null));
        invalid(() -> new Detail(0, JobState.STAGED, null, null, null, Reason.NONE, null, null));
        invalid(
                () ->
                        new Detail(
                                0, JobState.STAGED, JobState.QUEUED, null, null, null, null, null));
        for (String hash :
                List.of("A".repeat(64), "g".repeat(64), "a".repeat(63), "answer\nsecret")) {
            invalid(
                    () ->
                            new Detail(
                                    0,
                                    JobState.STAGED,
                                    JobState.QUEUED,
                                    null,
                                    null,
                                    Reason.NONE,
                                    hash,
                                    null));
            invalid(
                    () ->
                            new Detail(
                                    0,
                                    JobState.STAGED,
                                    JobState.QUEUED,
                                    null,
                                    null,
                                    Reason.NONE,
                                    null,
                                    hash));
        }
        assertThat(count()).isZero();
    }

    @Test
    void invalidAppendFieldsAndAllActorKindAttemptShapesHaveNoEvents() {
        transaction.executeWithoutResult(
                status -> {
                    for (EventKind kind : EventKind.values()) {
                        for (Integer attempt : new Integer[] {null, 0, 1, 3, 4}) {
                            boolean systemValid =
                                    kind == EventKind.RECOVERY_EXPIRED
                                            ? attempt != null && attempt >= 1 && attempt <= 3
                                            : kind.name().startsWith("JOB_") && attempt == null;
                            if (!systemValid)
                                invalid(
                                        () ->
                                                repository.recordSystemEvent(
                                                        job,
                                                        attempt,
                                                        kind,
                                                        UUID.randomUUID(),
                                                        HASH,
                                                        detail()));
                            if (attempt != null
                                    && (!kind.name().startsWith("COMPLETE_")
                                            || attempt < 1
                                            || attempt > 3)) {
                                invalid(
                                        () ->
                                                repository.recordWorkerEvent(
                                                        worker,
                                                        job,
                                                        attempt,
                                                        kind,
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID(),
                                                        HASH,
                                                        detail()));
                            }
                        }
                    }
                    invalid(
                            () ->
                                    repository.recordSystemEvent(
                                            0,
                                            null,
                                            EventKind.JOB_ACTIVATED,
                                            UUID.randomUUID(),
                                            HASH,
                                            detail()));
                    invalid(
                            () ->
                                    repository.recordSystemEvent(
                                            job, null, null, UUID.randomUUID(), HASH, detail()));
                    for (UUID command : new UUID[] {null, ZERO}) {
                        invalid(
                                () ->
                                        repository.recordSystemEvent(
                                                job,
                                                null,
                                                EventKind.JOB_ACTIVATED,
                                                command,
                                                HASH,
                                                detail()));
                        invalid(
                                () ->
                                        repository.recordWorkerEvent(
                                                worker,
                                                job,
                                                1,
                                                EventKind.COMPLETE_APPLIED,
                                                command,
                                                UUID.randomUUID(),
                                                HASH,
                                                detail()));
                    }
                    for (UUID request : new UUID[] {null, ZERO}) {
                        invalid(
                                () ->
                                        repository.recordWorkerEvent(
                                                worker,
                                                job,
                                                1,
                                                EventKind.COMPLETE_APPLIED,
                                                UUID.randomUUID(),
                                                request,
                                                HASH,
                                                detail()));
                    }
                    for (String hash :
                            new String[] {
                                null, "", "A".repeat(64), "g".repeat(64), "a".repeat(63)
                            }) {
                        invalid(
                                () ->
                                        repository.recordSystemEvent(
                                                job,
                                                null,
                                                EventKind.JOB_ACTIVATED,
                                                UUID.randomUUID(),
                                                hash,
                                                detail()));
                    }
                    invalid(
                            () ->
                                    repository.recordSystemEvent(
                                            job,
                                            null,
                                            EventKind.JOB_ACTIVATED,
                                            UUID.randomUUID(),
                                            HASH,
                                            null));
                });
        assertThat(count()).isZero();
    }

    @Test
    void noTransactionReadOnlyWrongDatasourceAndWrongIsolationReject() {
        boundary(this::system);
        transaction.setReadOnly(true);
        try {
            transaction.executeWithoutResult(status -> boundary(this::system));
        } finally {
            transaction.setReadOnly(false);
        }
        // 동일 URL도 다른 datasource 자원이며 다른 DB 연결을 획득해서는 안 된다.
        var other =
                new GradeEventRepository(GradeSchemaIT.jdbc(postgres), credentials, COORDINATOR);
        transaction.executeWithoutResult(
                status ->
                        boundary(
                                () ->
                                        other.recordSystemEvent(
                                                job,
                                                null,
                                                EventKind.JOB_ACTIVATED,
                                                UUID.randomUUID(),
                                                HASH,
                                                detail())));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        try {
            transaction.executeWithoutResult(status -> boundary(this::system));
        } finally {
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
        assertThat(count()).isZero();
    }

    @Test
    void boundButAutocommitOrUnsynchronizedResourcesRejectWithoutInsert() throws Exception {
        try (Connection connection = postgres.createConnection("")) {
            var holder = new ConnectionHolder(connection);
            TransactionSynchronizationManager.bindResource(jdbc.getDataSource(), holder);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                boundary(this::system);
                holder.setSynchronizedWithTransaction(true);
                boundary(this::system);
                connection.setAutoCommit(false);
                TransactionSynchronizationManager.clearSynchronization();
                boundary(this::system);
                TransactionSynchronizationManager.initSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
                boundary(this::system);
                TransactionSynchronizationManager.setActualTransactionActive(true);
                connection.setReadOnly(true);
                boundary(this::system);
                connection.rollback();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.unbindResource(jdbc.getDataSource());
            }
        }
        assertThat(count()).isZero();
    }

    @Test
    void wrongDatasourceDoesNotAcquireConnection() {
        var unusable =
                new DriverManagerDataSource() {
                    @Override
                    public Connection getConnection() {
                        throw new AssertionError("AUDIT_MUST_NOT_ACQUIRE_UNBOUND_CONNECTION");
                    }
                };
        var other = new GradeEventRepository(new JdbcTemplate(unusable), credentials, COORDINATOR);
        transaction.executeWithoutResult(
                status ->
                        boundary(
                                () ->
                                        other.recordSystemEvent(
                                                job,
                                                null,
                                                EventKind.JOB_ACTIVATED,
                                                UUID.randomUUID(),
                                                HASH,
                                                detail())));
        assertThat(count()).isZero();
    }

    @Test
    void commitPersistsEventAndOuterBusinessUpdateInSameTransaction() {
        long event =
                transaction.execute(
                        status -> {
                            cancelJob();
                            return system();
                        });
        assertThat(count()).isEqualTo(1);
        assertThat(event).isPositive();
        assertThat(state()).isEqualTo("CANCELLED");
    }

    @Test
    void outerRollbackRemovesBusinessUpdateAndEvent() {
        transaction.executeWithoutResult(
                status -> {
                    cancelJob();
                    system();
                    assertThat(count()).isEqualTo(1);
                    status.setRollbackOnly();
                });
        assertThat(count()).isZero();
        assertThat(state()).isEqualTo("STAGED");
    }

    @Test
    void missingActualJobOrAttemptFailsCauseFreeAndRollsBackBusiness() {
        for (Runnable append :
                List.<Runnable>of(
                        () ->
                                repository.recordSystemEvent(
                                        Long.MAX_VALUE,
                                        null,
                                        EventKind.JOB_ACTIVATED,
                                        UUID.randomUUID(),
                                        HASH,
                                        detail()),
                        () ->
                                repository.recordSystemEvent(
                                        job,
                                        2,
                                        EventKind.RECOVERY_EXPIRED,
                                        UUID.randomUUID(),
                                        HASH,
                                        detail()),
                        () ->
                                repository.recordWorkerEvent(
                                        worker,
                                        job,
                                        2,
                                        EventKind.COMPLETE_APPLIED,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        HASH,
                                        detail()),
                        () ->
                                repository.recordWorkerEvent(
                                        worker,
                                        Long.MAX_VALUE,
                                        1,
                                        EventKind.COMPLETE_APPLIED,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        HASH,
                                        detail()))) {
            storage(
                    () ->
                            transaction.executeWithoutResult(
                                    status -> {
                                        cancelJob();
                                        append.run();
                                    }));
            assertThat(count()).isZero();
            assertThat(state()).isEqualTo("STAGED");
        }
    }

    @Test
    void realInsertFailureTriggerLeavesNoBusinessOrAuditChanges() {
        jdbc.execute(
                """
                CREATE FUNCTION audit_repository_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic sensitive failure'; END; $$
                """);
        jdbc.execute(
                "CREATE TRIGGER audit_repository_fail BEFORE INSERT ON grade_event"
                        + " FOR EACH ROW EXECUTE FUNCTION audit_repository_fail()");
        try {
            storage(
                    () ->
                            transaction.executeWithoutResult(
                                    status -> {
                                        cancelJob();
                                        system();
                                    }));
            assertThat(count()).isZero();
            assertThat(state()).isEqualTo("STAGED");
        } finally {
            jdbc.execute("DROP TRIGGER audit_repository_fail ON grade_event");
            jdbc.execute("DROP FUNCTION audit_repository_fail()");
        }
    }

    @Test
    void duplicateCommandFailsRatherThanReturningReceiptAndRollsBackEntireTransaction() {
        UUID command = UUID.randomUUID();
        storage(
                () ->
                        transaction.executeWithoutResult(
                                status -> {
                                    cancelJob();
                                    repository.recordWorkerEvent(
                                            worker,
                                            job,
                                            1,
                                            EventKind.COMPLETE_APPLIED,
                                            command,
                                            UUID.randomUUID(),
                                            HASH,
                                            detail());
                                    repository.recordWorkerEvent(
                                            worker,
                                            job,
                                            1,
                                            EventKind.COMPLETE_REJECTED,
                                            command,
                                            UUID.randomUUID(),
                                            HASH,
                                            detail());
                                }));
        assertThat(count()).isZero();
        assertThat(state()).isEqualTo("STAGED");
        transaction.executeWithoutResult(
                status ->
                        repository.recordSystemEvent(
                                job, null, EventKind.JOB_ACTIVATED, command, HASH, detail()));
        storage(
                () ->
                        transaction.executeWithoutResult(
                                status -> {
                                    cancelJob();
                                    repository.recordSystemEvent(
                                            job,
                                            null,
                                            EventKind.JOB_INPUT_REJECTED,
                                            command,
                                            HASH,
                                            detail());
                                }));
        assertThat(count()).isEqualTo(1);
        assertThat(state()).isEqualTo("STAGED");
    }

    /** 원본 토큰 없이 합성 등록을 만든다. 고정 바이트는 실제 사용자 자격이 아니다. */
    private static GradeWorkerCredentials registry(String code, Set<Action> actions) {
        return new GradeWorkerCredentials(
                List.of(
                        new Registration(
                                "audit-worker_1",
                                CommonUtil.sha256(tokenBytes()),
                                Set.of(code),
                                actions)));
    }

    private static byte[] tokenBytes() {
        byte[] token = new byte[32];
        for (int index = 0; index < token.length; index++) token[index] = (byte) (index + 1);
        return token;
    }

    private static String header() {
        return "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes());
    }

    private static Detail detail() {
        return new Detail(
                3,
                JobState.RUNNING,
                JobState.COMPLETED,
                AttemptState.RUNNING,
                AttemptState.SUCCEEDED,
                Reason.NONE,
                null,
                null);
    }

    private long system() {
        return repository.recordSystemEvent(
                job, null, EventKind.JOB_ACTIVATED, UUID.randomUUID(), HASH, detail());
    }

    private void cancelJob() {
        assertThat(jdbc.update("UPDATE grade_job SET state='CANCELLED' WHERE id=?", job))
                .isEqualTo(1);
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM grade_job WHERE id=?", String.class, job);
    }

    private long count() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM grade_event WHERE job_id=?", Long.class, job);
    }

    private static long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void denied(GradeEventRepository target, VerifiedWorker proof) {
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                target.recordWorkerEvent(
                                                        proof,
                                                        job,
                                                        1,
                                                        EventKind.COMPLETE_APPLIED,
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID(),
                                                        HASH,
                                                        detail())))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKER_NOT_AUTHORIZED")
                .hasNoCause();
    }

    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_GRADE_AUDIT")
                .hasNoCause();
    }

    private static void boundary(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GRADE_AUDIT_REQUIRES_WRITE_READ_COMMITTED")
                .hasNoCause();
    }

    private static void storage(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GRADE_AUDIT_STORAGE_FAILURE")
                .hasNoCause();
    }
}
