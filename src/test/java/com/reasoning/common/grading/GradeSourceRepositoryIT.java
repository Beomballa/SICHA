package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.repository.GradeSourceRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository.LockedSource;
import com.reasoning.common.grading.repository.GradeSourceRepository.RootEvidence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 실제 V1~V13과 고정 PG16.10의 출처 잠금 시험이다. {} 사본은 관계 fixture일 뿐 의미/GRADE 근거가 아니다. */
class GradeSourceRepositoryIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static GradeSourceRepository repository;
    private long creator;
    private long story;
    private long version;
    private long snapshot;
    private long runtime;
    private long batch;
    private long job;
    private UUID jobKey;

    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        repository = new GradeSourceRepository(jdbc);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void fixtures() {
        creator =
                id(
                        "INSERT INTO public.admin_account(account_key,can_review,can_manage)"
                                + " VALUES (?,true,true) RETURNING id",
                        UUID.randomUUID());
        jdbc.update(
                """
                INSERT INTO public.admin_credential(account_id,login_cipher,login_hash,
                    password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)
                VALUES (?,'fixture-only',?,'fixture-only','fixture-only',clock_timestamp(),0,
                    clock_timestamp(),'READY')
                """,
                creator,
                hashBytes());
        story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        token(),
                        creator);
        version =
                id(
                        """
                        INSERT INTO public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'관계 전용 사본','H2',?,?) RETURNING id
                        """,
                        story,
                        creator,
                        creator);
        snapshot = snapshot(version);
        jdbc.update(
                "UPDATE public.story_version SET status='REVIEW',current_snapshot_id=?,"
                        + "edit_rev=10 WHERE id=?",
                snapshot,
                version);
        jdbc.update(
                "INSERT INTO public.story_access(story_id,admin_id,permission,granted_by)"
                        + " VALUES (?,?,'REVIEW',?)",
                story,
                creator,
                creator);
        runtime = runtime();
        batch = batch(snapshot, runtime, creator);
        jobKey = UUID.randomUUID();
        job =
                id(
                        """
                        INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,
                            repeat_no,state,input_hash,config_hash,rubric_hash)
                        VALUES (?,?,?,?,'FIXTURE',1,'STAGED',?,?,?) RETURNING id
                        """,
                        jobKey,
                        snapshot,
                        runtime,
                        batch,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH);
    }

    @Test
    void reviewEvidenceIsSafeAndDoesNotMutateJobLeaseBudgetOrAttempts() {
        Map<String, Object> before = row();
        RootEvidence evidence = source();
        assertThat(evidence.creatorId()).isEqualTo(creator);
        assertThat(evidence.storyId()).isEqualTo(story);
        assertThat(evidence.versionId()).isEqualTo(version);
        assertThat(evidence.snapshotId()).isEqualTo(snapshot);
        assertThat(evidence.runtimeId()).isEqualTo(runtime);
        assertThat(evidence.runtimeEpoch()).isZero();
        assertThat(evidence.configHash()).isEqualTo(GradeSchemaIT.HASH);
        assertThat(evidence.purpose()).isEqualTo("REVIEW");
        assertThat(evidence.versionRev()).isEqualTo(10);
        assertThat(evidence.snapshotRev()).isZero();
        assertThat(evidence.snapshotFormat()).isEqualTo(1);
        assertThat(evidence.checkedAt()).isAfterOrEqualTo(evidence.batchCreatedAt());
        assertThat(evidence.toString()).doesNotContain("fixture-only", GradeSchemaIT.HASH, "{}");
        assertThat(row()).isEqualTo(before);
        noAttempts();
        // 최초 세션 자체를 생성하지 않았다. 원래 세션이나 최초 auth_rev는 전제 조건이 아니다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.admin_session WHERE account_id=?",
                                Long.class,
                                creator))
                .isZero();
        jdbc.update("UPDATE public.admin_credential SET auth_rev=99 WHERE account_id=?", creator);
        assertThat(source().creatorId()).isEqualTo(creator);
    }

    @Test
    void availabilityAcceptsCurrentReviewReadyAndPublishedWithoutVisibilityOrPriorGrade() {
        jdbc.update(
                "UPDATE public.grade_batch SET purpose='AVAILABILITY',"
                        + "valid_until=clock_timestamp()-interval '1 day' WHERE id=?",
                batch);
        for (String status : List.of("REVIEW", "READY", "PUBLISHED")) {
            jdbc.update("UPDATE public.story_version SET status=? WHERE id=?", status, version);
            assertThat(source().versionStatus()).isEqualTo(status);
        }
        assertThat(
                        jdbc.queryForMap(
                                "SELECT view_yn,published_id FROM public.story WHERE id=?", story))
                .containsEntry("view_yn", false)
                .containsEntry("published_id", null);
        noAttempts();
    }

    @Test
    void currentCreatorAuthorityCannotBeReplacedByOwnershipEditOrManage() {
        rejectMutation("UPDATE public.admin_account SET active_yn=false WHERE id=?", creator);
        rejectMutation("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        rejectMutation(
                "UPDATE public.admin_credential SET enrolled_at=NULL,mfa_state='PENDING'"
                        + " WHERE account_id=?",
                creator);
        rejectMutation(
                "UPDATE public.admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                creator);
        rejectMutation("DELETE FROM public.admin_credential WHERE account_id=?", creator);
        rejectMutation("UPDATE public.story_access SET active_yn=false WHERE story_id=?", story);
        rejectMutation("UPDATE public.story_access SET permission='EDIT' WHERE story_id=?", story);
        rejectMutation("DELETE FROM public.story_access WHERE story_id=?", story);
        assertThat(source().creatorId()).isEqualTo(creator);
    }

    @Test
    void returnedReplacedInactiveAndWrongPurposeSourcesAreRejected() {
        rejectMutation(
                "UPDATE public.story_version SET status='DRAFT',current_snapshot_id=NULL WHERE"
                        + " id=?",
                version);
        long replacement = snapshot(version);
        rejectMutation(
                "UPDATE public.story_version SET current_snapshot_id=? WHERE id=?",
                replacement,
                version);
        rejectMutation("UPDATE public.story SET active_yn=false WHERE id=?", story);
        rejectMutation("UPDATE public.story_version SET active_yn=false WHERE id=?", version);
        for (String status : List.of("READY", "PUBLISHED")) {
            rejectMutation("UPDATE public.story_version SET status=? WHERE id=?", status, version);
        }
        // 다른 실제 부모 사본을 참조해도 현재 출처로 인정하지 않는다.
        long otherStory =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        token(),
                        creator);
        long otherVersion =
                id(
                        """
                        INSERT INTO public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'다른 관계','H2',?,?) RETURNING id
                        """,
                        otherStory,
                        creator,
                        creator);
        long otherSnapshot = snapshot(otherVersion);
        transaction()
                .executeWithoutResult(
                        status -> {
                            jdbc.update(
                                    "UPDATE public.grade_job SET snapshot_id=?,batch_id=? WHERE"
                                            + " id=?",
                                    otherSnapshot,
                                    batch(otherSnapshot, runtime, creator),
                                    job);
                            assertRejected(repository);
                            status.setRollbackOnly();
                        });
    }

    @Test
    void runtimeEpochReferencesAndStoredHashConsistencyAreRequired() {
        rejectMutation("UPDATE public.grade_runtime SET state='SUSPENDED' WHERE id=?", runtime);
        rejectMutation("UPDATE public.grade_runtime SET epoch=1 WHERE id=?", runtime);
        rejectMutation("UPDATE public.grade_batch SET runtime_epoch=1 WHERE id=?", batch);
        rejectMutation("UPDATE public.grade_batch SET state='STAGED' WHERE id=?", batch);
        for (String state : List.of("COMPLETED", "FAILED", "CANCELLED")) {
            rejectMutation(
                    "UPDATE public.grade_batch SET state=?,ended_at=clock_timestamp() WHERE id=?",
                    state,
                    batch);
        }
        long otherRuntime = runtime();
        rejectMutation("UPDATE public.grade_job SET runtime_id=? WHERE id=?", otherRuntime, job);
        rejectMutation(
                "UPDATE public.grade_batch SET runtime_id=? WHERE id=?", otherRuntime, batch);
        for (String table : List.of("grade_runtime", "grade_batch", "grade_job")) {
            long target =
                    table.equals("grade_runtime")
                            ? runtime
                            : table.equals("grade_batch") ? batch : job;
            rejectMutation(
                    "UPDATE public." + table + " SET config_hash=? WHERE id=?",
                    "b".repeat(64),
                    target);
        }
        rejectMutation("UPDATE public.grade_job SET rubric_hash=? WHERE id=?", "b".repeat(64), job);
        rejectMutation(
                "UPDATE public.grade_batch SET rubric_hash=? WHERE id=?", "b".repeat(64), batch);
    }

    @Test
    void stagedQueuedAndRunningAreAcceptedButTerminalJobsAreNot() {
        for (String state :
                List.of("STAGED", "QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")) {
            transaction()
                    .executeWithoutResult(
                            status -> {
                                if (!state.equals("STAGED")) {
                                    jdbc.update(
                                            """
                                            WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                                            UPDATE public.grade_job SET state=?,accepted_at=t.n,
                                                deadline_at=t.n+interval '120 seconds',
                                                worker_key=CASE WHEN ?='RUNNING' THEN 'fixture-worker' ELSE NULL END,
                                                lease_until=CASE WHEN ?='RUNNING' THEN t.n+interval '30 seconds' ELSE NULL END,
                                                lease_gen=2,call_count=3 FROM t WHERE id=?
                                            """,
                                            state,
                                            state,
                                            state,
                                            job);
                                }
                                Map<String, Object> before = row();
                                LockedSource roots = repository.lockRoots(jobKey);
                                assertThat(roots.jobState()).isEqualTo(state);
                                for (Root root : roots()) assertLocked(root);
                                if (List.of("STAGED", "QUEUED", "RUNNING").contains(state)) {
                                    assertThat(
                                                    repository
                                                            .requireExecutionEligibility(roots)
                                                            .jobId())
                                            .isEqualTo(job);
                                } else
                                    assertThatThrownBy(
                                                    () ->
                                                            repository.requireExecutionEligibility(
                                                                    roots))
                                            .hasMessage("SOURCE_NOT_CURRENT")
                                            .hasNoCause();
                                assertThat(row()).isEqualTo(before);
                                noAttempts();
                                status.setRollbackOnly();
                            });
        }
    }

    @Test
    void sourceEvidenceDoesNotAuthenticateHashesOrGrantExpiredLeaseDispatch() {
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp()-interval '1 hour' n)
                UPDATE public.grade_job SET state='RUNNING',accepted_at=t.n,
                    deadline_at=t.n+interval '120 seconds',worker_key='expired-fixture-worker',
                    lease_until=t.n+interval '30 seconds',lease_gen=3,call_count=3,
                    input_hash=? FROM t WHERE id=?
                """,
                "b".repeat(64),
                job);
        jdbc.update(
                "UPDATE public.grade_batch SET payload_hash=?,dataset_hash=?,"
                        + "valid_until=clock_timestamp()-interval '1 hour' WHERE id=?",
                "c".repeat(64),
                "d".repeat(64),
                batch);
        Map<String, Object> before = row();
        RootEvidence evidence = source();
        assertThat(evidence.inputHash()).isEqualTo("b".repeat(64));
        assertThat(evidence.payloadHash()).isEqualTo("c".repeat(64));
        assertThat(evidence.datasetHash()).isEqualTo("d".repeat(64));
        assertThat(row()).isEqualTo(before);
        noAttempts();
    }

    @Test
    void absentReadOnlyWrongIsolationAndWrongDataSourceTransactionsAreRejected() {
        assertThatThrownBy(() -> repository.lockRoots(jobKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        var read = transaction();
        read.setReadOnly(true);
        read.executeWithoutResult(
                status ->
                        assertThatThrownBy(() -> repository.lockRoots(jobKey))
                                .hasMessage("SOURCE_REQUIRES_WRITE_READ_COMMITTED"));
        for (int isolation :
                List.of(
                        TransactionDefinition.ISOLATION_REPEATABLE_READ,
                        TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            var wrong = transaction();
            wrong.setIsolationLevel(isolation);
            wrong.executeWithoutResult(
                    status ->
                            assertThatThrownBy(() -> repository.lockRoots(jobKey))
                                    .hasMessage("SOURCE_REQUIRES_WRITE_READ_COMMITTED"));
        }
        transaction()
                .executeWithoutResult(
                        status -> {
                            var unbound = new GradeSourceRepository(GradeSchemaIT.jdbc(postgres));
                            assertThatThrownBy(() -> unbound.lockRoots(jobKey))
                                    .hasMessage("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
                            assertThatThrownBy(() -> repository.lockRoots(null))
                                    .isInstanceOf(IllegalArgumentException.class);
                            assertThatThrownBy(() -> repository.lockRoots(UUID.randomUUID()))
                                    .hasMessage("SOURCE_NOT_CURRENT");
                        });
        noAttempts();
    }

    @Test
    void lockOrderUsesSeparateStatementsAndLocalTimeoutAndReleasesOnCommit() {
        var locks = new java.util.ArrayList<String>();
        var observedJdbc =
                new JdbcTemplate(jdbc.getDataSource()) {
                    @Override
                    public List<Map<String, Object>> queryForList(String sql, Object... args) {
                        if (sql.endsWith("FOR UPDATE")) locks.add(sql);
                        return super.queryForList(sql, args);
                    }
                };
        var observed = new GradeSourceRepository(observedJdbc);
        transaction()
                .executeWithoutResult(
                        status -> {
                            observed.lockRoots(jobKey);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT current_setting('lock_timeout')",
                                                    String.class))
                                    .isEqualTo("5s");
                            for (Root root : roots()) assertLocked(root);
                        });
        assertThat(locks).hasSize(7);
        assertThat(
                        locks.stream()
                                .map(
                                        sql ->
                                                sql.substring(
                                                        sql.indexOf("public.") + 7,
                                                        sql.indexOf(" WHERE")))
                                .toList())
                .containsExactly(
                        "admin_account",
                        "admin_credential",
                        "grade_runtime",
                        "story",
                        "story_version",
                        "grade_batch",
                        "grade_job");
        assertThat(locks).allSatisfy(sql -> assertThat(sql).doesNotContain("JOIN"));
        assertThat(jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class))
                .isEqualTo("0");
        for (Root root : roots()) assertUnlocked(root);
    }

    @Test
    void eachRootSerializesActualChangesAndRollbackReleasesAllLocks() throws Exception {
        for (Root root : roots()) {
            try (var executor = Executors.newSingleThreadExecutor()) {
                var result =
                        transaction()
                                .execute(
                                        status -> {
                                            repository.lockRoots(jobKey);
                                            var change =
                                                    executor.submit(
                                                            () ->
                                                                    jdbc.update(
                                                                            "/*source-change-probe*/"
                                                                                + " UPDATE public."
                                                                                    + root.table()
                                                                                    + " SET "
                                                                                    + root.change()
                                                                                    + " WHERE "
                                                                                    + root.key()
                                                                                    + "=?",
                                                                            root.id()));
                                            try {
                                                awaitWait("source-change-probe");
                                                assertThat(change.isDone()).isFalse();
                                            } catch (InterruptedException failure) {
                                                Thread.currentThread().interrupt();
                                                throw new AssertionError(failure);
                                            }
                                            // 호출자 변경도 같이 롤백되는지 확인한다.
                                            jdbc.update(
                                                    "UPDATE public.grade_job SET call_count=2 WHERE"
                                                            + " id=?",
                                                    job);
                                            status.setRollbackOnly();
                                            return change;
                                        });
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            }
            assertThat(row()).containsEntry("call_count", 0);
            for (Root release : roots()) assertUnlocked(release);
            noAttempts();
        }
    }

    @Test
    void databaseClockAfterGenuineCreatorLockWaitRejectsTwentyFourHourBoundary() throws Exception {
        try (Connection holder = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            lock(holder, new Root("admin_account", "id", creator, "edit_rev=edit_rev"), false);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var waiting =
                        executor.submit(
                                () -> {
                                    assertThatThrownBy(this::source).hasMessage("BATCH_EXPIRED");
                                    return true;
                                });
                awaitWait("FROM public.admin_account WHERE id=");
                try (var age =
                        holder.prepareStatement(
                                "UPDATE public.grade_batch SET"
                                    + " created_at=clock_timestamp()-interval '24 hours'+interval"
                                    + " '1 second' WHERE id=?")) {
                    age.setLong(1, batch);
                    age.executeUpdate();
                }
                try (var delay = holder.createStatement()) {
                    delay.execute("SELECT pg_sleep(1.2)");
                }
                holder.commit();
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
        assertThat(row()).containsEntry("state", "STAGED");
        noAttempts();
    }

    @Test
    void revokedCreatorRuntimeAndSourceAndTerminalBatchStillLockAllActualRoots() {
        List<String> mutations =
                List.of(
                        "UPDATE public.admin_account SET active_yn=false WHERE id=" + creator,
                        "UPDATE public.admin_account SET can_review=false WHERE id=" + creator,
                        "UPDATE public.admin_credential SET mfa_state='RECOVERY' WHERE account_id="
                                + creator,
                        "UPDATE public.story_access SET active_yn=false WHERE story_id=" + story,
                        "UPDATE public.grade_runtime SET state='SUSPENDED' WHERE id=" + runtime,
                        "UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=" + runtime,
                        "UPDATE public.story SET active_yn=false WHERE id=" + story,
                        "UPDATE public.story_version SET status='DRAFT',current_snapshot_id=NULL"
                                + " WHERE id="
                                + version);
        for (String sql : mutations) assertFactualLocksWithoutEligibility(sql);
        for (String state : List.of("COMPLETED", "FAILED", "CANCELLED")) {
            assertFactualLocksWithoutEligibility(
                    "UPDATE public.grade_batch SET state='"
                            + state
                            + "',ended_at=clock_timestamp() WHERE id="
                            + batch);
        }
    }

    @Test
    void factualEvidenceCannotAuthorizeExecutionAfterItsTransactionEnds() {
        LockedSource captured = transaction().execute(status -> repository.lockRoots(jobKey));
        assertThat(captured.jobId()).isEqualTo(job);
        assertThatThrownBy(() -> repository.requireExecutionEligibility(captured))
                .hasMessage("SOURCE_REQUIRES_WRITE_READ_COMMITTED")
                .hasNoCause();
        transaction()
                .executeWithoutResult(
                        status ->
                                assertThatThrownBy(
                                                () ->
                                                        repository.requireExecutionEligibility(
                                                                captured))
                                        .hasMessage("SOURCE_EVIDENCE_TRANSACTION_MISMATCH")
                                        .hasNoCause());
        noAttempts();
    }

    @Test
    void suspendedTransactionEvidenceCannotAuthorizeDifferentTransactionOrRepository() {
        var independent = transaction();
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction()
                .executeWithoutResult(
                        status -> {
                            LockedSource captured = repository.lockRoots(jobKey);
                            independent.executeWithoutResult(
                                    other ->
                                            assertThatThrownBy(
                                                            () ->
                                                                    repository
                                                                            .requireExecutionEligibility(
                                                                                    captured))
                                                    .hasMessage(
                                                            "SOURCE_EVIDENCE_TRANSACTION_MISMATCH")
                                                    .hasNoCause());
                            var otherRepository = new GradeSourceRepository(jdbc);
                            assertThatThrownBy(
                                            () ->
                                                    otherRepository.requireExecutionEligibility(
                                                            captured))
                                    .hasMessage("SOURCE_EVIDENCE_TRANSACTION_MISMATCH")
                                    .hasNoCause();
                            assertThat(repository.requireExecutionEligibility(captured).jobId())
                                    .isEqualTo(job);
                            assertThatThrownBy(() -> repository.requireExecutionEligibility(null))
                                    .isInstanceOf(IllegalArgumentException.class)
                                    .hasMessage("INVALID_SOURCE_EVIDENCE");
                        });
        noAttempts();
    }

    /** 저장점 롤백으로 실제 잠금이 사라지면 같은 외부 TX라도 과거 증거를 재사용할 수 없다. */
    @Test
    void nestedRollbackInvalidatesEvidenceAfterActualRootLocksAreReleased() {
        var nested = transaction();
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transaction()
                .executeWithoutResult(
                        status -> {
                            LockedSource captured =
                                    nested.execute(
                                            inner -> {
                                                LockedSource roots = repository.lockRoots(jobKey);
                                                inner.setRollbackOnly();
                                                return roots;
                                            });
                            for (Root root : roots()) assertUnlocked(root);
                            assertThat(captured.jobId()).isEqualTo(job);
                            assertThatThrownBy(
                                            () -> repository.requireExecutionEligibility(captured))
                                    .hasMessage("SOURCE_EVIDENCE_TRANSACTION_MISMATCH")
                                    .hasNoCause();
                        });
        noAttempts();
    }

    /** 저장점을 정상 확정한 잠금은 외부 TX가 끝날 때까지 계속 효력 검사를 지원한다. */
    @Test
    void nestedCommitKeepsEvidenceInItsOwningOuterTransaction() {
        var nested = transaction();
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transaction()
                .executeWithoutResult(
                        status -> {
                            LockedSource captured =
                                    nested.execute(inner -> repository.lockRoots(jobKey));
                            assertThat(repository.requireExecutionEligibility(captured).jobId())
                                    .isEqualTo(job);
                        });
        for (Root root : roots()) assertUnlocked(root);
        noAttempts();
    }

    /** 실제 잠금이 살아남더라도 후속 저장점 롤백은 과거 증거를 보수적으로 재사용하지 않는다. */
    @Test
    void laterSavepointRollbackConservativelyInvalidatesEarlierEvidence() {
        var nested = transaction();
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transaction()
                .executeWithoutResult(
                        status -> {
                            LockedSource captured = repository.lockRoots(jobKey);
                            nested.executeWithoutResult(inner -> inner.setRollbackOnly());
                            assertThatThrownBy(
                                            () -> repository.requireExecutionEligibility(captured))
                                    .hasMessage("SOURCE_EVIDENCE_TRANSACTION_MISMATCH")
                                    .hasNoCause();
                        });
        noAttempts();
    }

    @Test
    void eligibilityRereadsRevocationInsideTheOwningTransaction() {
        transaction()
                .executeWithoutResult(
                        status -> {
                            LockedSource roots = repository.lockRoots(jobKey);
                            assertThat(repository.requireExecutionEligibility(roots).jobId())
                                    .isEqualTo(job);
                            jdbc.update(
                                    "UPDATE public.admin_account SET can_review=false WHERE id=?",
                                    creator);
                            assertThatThrownBy(() -> repository.requireExecutionEligibility(roots))
                                    .hasMessage("SOURCE_NOT_CURRENT")
                                    .hasNoCause();
                            status.setRollbackOnly();
                        });
        noAttempts();
    }

    @Test
    void eligibilityClockIsFreshAfterBoundedValidationCrossesBatchBoundary() {
        Map<String, Object> before = row();
        transaction()
                .executeWithoutResult(
                        status -> {
                            jdbc.update(
                                    "UPDATE public.grade_batch SET created_at=clock_timestamp()"
                                            + "-interval '24 hours'+interval '1 second' WHERE id=?",
                                    batch);
                            LockedSource roots = repository.lockRoots(jobKey);
                            assertThat(repository.requireExecutionEligibility(roots).checkedAt())
                                    .isBefore(roots.batchCreatedAt().plusHours(24));
                            // 제한된 입력 검증 시간을 실제 DB에서 경과시킨다. TX 시작 now()로는 거절할 수 없다.
                            jdbc.execute("SELECT pg_sleep(1.2)");
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT now()", java.time.OffsetDateTime.class))
                                    .isBefore(roots.batchCreatedAt().plusHours(24));
                            assertThatThrownBy(() -> repository.requireExecutionEligibility(roots))
                                    .hasMessage("BATCH_EXPIRED")
                                    .hasNoCause();
                            status.setRollbackOnly();
                        });
        assertThat(row()).isEqualTo(before);
        noAttempts();
    }

    /** 회수·종료된 실제 부모를 정상 순서로 잠그되 신규 실행은 거절하고 전체 변경을 롤백한다. */
    private void assertFactualLocksWithoutEligibility(String sql) {
        Map<String, Object> before = row();
        transaction()
                .executeWithoutResult(
                        status -> {
                            jdbc.update(sql);
                            LockedSource roots = repository.lockRoots(jobKey);
                            assertThat(roots.jobId()).isEqualTo(job);
                            assertThat(roots.creatorId()).isEqualTo(creator);
                            assertThat(roots.toString())
                                    .doesNotContain(
                                            "fixture-only", GradeSchemaIT.HASH, "{}", "FIXTURE");
                            for (Root root : roots()) assertLocked(root);
                            assertThatThrownBy(() -> repository.requireExecutionEligibility(roots))
                                    .hasMessage("SOURCE_NOT_CURRENT")
                                    .hasNoCause();
                            assertThat(row()).isEqualTo(before);
                            noAttempts();
                            status.setRollbackOnly();
                        });
        assertThat(row()).isEqualTo(before);
        for (Root root : roots()) assertUnlocked(root);
    }

    @Test
    void changedDiscoveryHintsAreRejectedWithoutLockingNewEarlierRoots() throws Exception {
        long otherCreator =
                id(
                        "INSERT INTO public.admin_account(account_key) VALUES (?) RETURNING id",
                        UUID.randomUUID());
        try (Connection holder = postgres.createConnection("");
                Connection newRoot = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            newRoot.setAutoCommit(false);
            lock(holder, new Root("admin_account", "id", creator, "edit_rev=edit_rev"), false);
            // FK의 KEY SHARE는 허용하지만 출처의 FOR UPDATE는 막는 독립 새 루트 잠금이다.
            try (var statement =
                    newRoot.prepareStatement(
                            "SELECT id FROM public.admin_account WHERE id=? FOR NO KEY UPDATE")) {
                statement.setLong(1, otherCreator);
                statement.executeQuery().close();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var waiting =
                        executor.submit(
                                () -> {
                                    assertThatThrownBy(this::source)
                                            .hasMessage("SOURCE_NOT_CURRENT");
                                    return true;
                                });
                awaitWait("FROM public.admin_account WHERE id=");
                jdbc.update(
                        "UPDATE public.grade_batch SET created_by=? WHERE id=?",
                        otherCreator,
                        batch);
                // 이전 루트만 해제하고 새 루트는 독립 연결로 유지한다.
                holder.commit();
                assertThat(waiting.get(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
                newRoot.rollback();
            }
        }
        noAttempts();
    }

    @Test
    void databaseFailuresHaveFixedMessagesWithoutSensitiveCausesAndRollbackReleasesRoots() {
        var failingJdbc =
                new JdbcTemplate(jdbc.getDataSource()) {
                    @Override
                    public <T> T queryForObject(String sql, Class<T> type) {
                        if (sql.equals("SELECT clock_timestamp()")) {
                            throw new DataAccessResourceFailureException(
                                    "secret config report SQL detail");
                        }
                        return super.queryForObject(sql, type);
                    }
                };
        Map<String, Object> before = row();
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                status -> {
                                                    var failing =
                                                            new GradeSourceRepository(failingJdbc);
                                                    return failing.requireExecutionEligibility(
                                                            failing.lockRoots(jobKey));
                                                }))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("SOURCE_STORAGE_FAILURE")
                .hasNoCause();
        assertThat(row()).isEqualTo(before);
        for (Root root : roots()) assertUnlocked(root);
        assertThat(source().jobId()).isEqualTo(job);
        noAttempts();
    }

    @Test
    void factualRootStorageFailureIsRedactedAndDoesNotFallbackToJobOnlyLocks() {
        var failingJdbc =
                new JdbcTemplate(jdbc.getDataSource()) {
                    @Override
                    public List<Map<String, Object>> queryForList(String sql, Object... args) {
                        if (sql.contains("FROM public.grade_runtime")) {
                            throw new DataAccessResourceFailureException(
                                    "secret runtime config SQL detail");
                        }
                        return super.queryForList(sql, args);
                    }
                };
        Map<String, Object> before = row();
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                status ->
                                                        new GradeSourceRepository(failingJdbc)
                                                                .lockRoots(jobKey)))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("SOURCE_STORAGE_FAILURE")
                .hasNoCause();
        assertThat(row()).isEqualTo(before);
        for (Root root : roots()) assertUnlocked(root);
        noAttempts();
    }

    /** 하나의 실제 관계 조건만 변경하고 거절·전체 행 불변성·롤백을 확인한다. */
    private void rejectMutation(String sql, Object... args) {
        Map<String, Object> before = row();
        transaction()
                .executeWithoutResult(
                        status -> {
                            jdbc.update(sql, args);
                            assertRejected(repository);
                            status.setRollbackOnly();
                        });
        assertThat(row()).isEqualTo(before);
        noAttempts();
    }

    /** 권한/효력 실패는 고정 메시지만 노출한다. */
    private void assertRejected(GradeSourceRepository target) {
        assertThatThrownBy(() -> target.requireExecutionEligibility(target.lockRoots(jobKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SOURCE_NOT_CURRENT")
                .hasNoCause();
    }

    /** 호출자 소유 트랜잭션에서만 출처를 얻는다. */
    private RootEvidence source() {
        return transaction()
                .execute(
                        status ->
                                repository.requireExecutionEligibility(
                                        repository.lockRoots(jobKey)));
    }

    /** 원본 저장소와 같은 DataSource, 명시적 READ COMMITTED를 사용한다. */
    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    /** 현재 원문 없는 시험 루트와 비효력 메타데이터 변경이다. */
    private List<Root> roots() {
        return List.of(
                new Root("admin_account", "id", creator, "edit_rev=edit_rev+1"),
                new Root("admin_credential", "account_id", creator, "auth_rev=auth_rev+1"),
                new Root("grade_runtime", "id", runtime, "updated_at=clock_timestamp()"),
                new Root("story", "id", story, "edit_rev=edit_rev+1"),
                new Root("story_version", "id", version, "edit_rev=edit_rev+1"),
                new Root("grade_batch", "id", batch, "valid_until=clock_timestamp()"),
                new Root("grade_job", "id", job, "updated_at=clock_timestamp()"));
    }

    /** 별도 연결 NOWAIT로 실제 행 잠금을 입증한다. */
    private void assertLocked(Root root) {
        try (Connection connection = postgres.createConnection("")) {
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> lock(connection, root, true))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            error ->
                                    assertThat(((SQLException) error).getSQLState())
                                            .isEqualTo("55P03"));
            connection.rollback();
        } catch (SQLException failure) {
            throw new AssertionError("독립 잠금 확인 실패", failure);
        }
    }

    /** 커밋/롤백 뒤 같은 행 잠금이 즉시 가능한지 검사한다. */
    private void assertUnlocked(Root root) {
        try (Connection connection = postgres.createConnection("")) {
            connection.setAutoCommit(false);
            lock(connection, root, true);
            connection.rollback();
        } catch (SQLException failure) {
            throw new AssertionError("잠금 해제 확인 실패", failure);
        }
    }

    /** 시험의 고정 테이블/키에 대한 한 행 잠금이다. */
    private void lock(Connection connection, Root root, boolean nowait) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT "
                                + root.key()
                                + " FROM public."
                                + root.table()
                                + " WHERE "
                                + root.key()
                                + "=? FOR UPDATE"
                                + (nowait ? " NOWAIT" : ""))) {
            statement.setLong(1, root.id());
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    /** 독립 auto-commit 연결로 통계 사본을 새로 읽으며 실제 잠금 대기를 제한 시간 안에 관찰한다. */
    private void awaitWait(String marker) throws InterruptedException {
        try (Connection monitor = postgres.createConnection("");
                var statement =
                        monitor.prepareStatement(
                                """
                                SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                                    AND wait_event_type='Lock' AND position(? in query)>0)
                                """)) {
            monitor.setAutoCommit(true);
            statement.setString(1, marker);
            for (int attempt = 0; attempt < 100; attempt++) {
                try (var result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) return;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        } catch (SQLException failure) {
            throw new AssertionError("독립 잠금 대기 관찰 실패", failure);
        }
        throw new AssertionError("실제 잠금 대기 미관찰");
    }

    /** FK가 실제로 존재하는 원문 없는 사본 fixture다. */
    private long snapshot(long targetVersion) {
        return id(
                """
                INSERT INTO public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                VALUES (?,0,'{}',?,?) RETURNING id
                """,
                targetVersion,
                UUID.randomUUID(),
                creator);
    }

    /** 실제 불변 runtime 관계 fixture다. */
    private long runtime() {
        return id(
                "INSERT INTO public.grade_runtime(code,config_hash,config_data,state)"
                        + " VALUES (?,?,'{}','AVAILABLE') RETURNING id",
                token(),
                GradeSchemaIT.HASH);
    }

    /** 실행 성공을 주장하지 않는 RUNNING 관계 fixture다. */
    private long batch(long targetSnapshot, long targetRuntime, long targetCreator) {
        return id(
                """
                INSERT INTO public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,
                    rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)
                VALUES (?,?,?,'REVIEW',?,?,?,?,0,'RUNNING',3,?) RETURNING id
                """,
                UUID.randomUUID(),
                targetSnapshot,
                targetRuntime,
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH,
                targetCreator);
    }

    /** 전체 job 사본으로 임대·호출 수·결과의 무변경을 확인한다. */
    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job);
    }

    /** 이 출처는 attempt를 만들지 않는다. */
    private void noAttempts() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_attempt WHERE job_id=?",
                                Long.class,
                                job))
                .isZero();
    }

    /** 시험 INSERT의 실제 식별자다. */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 유일성 충돌을 막는 비밀 아닌 fixture 키다. */
    private String token() {
        return "S_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /** 실제 credential CHECK에 맞춘 합성 비밀 검색 해시다. */
    private byte[] hashBytes() {
        return java.nio.ByteBuffer.allocate(32).putLong(creator).array();
    }

    private record Root(String table, String key, long id, String change) {}
}
