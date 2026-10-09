package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.repository.GradeLeaseRepository.Lease;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 폐기형 고정 PG16.10에서 임대만 검증한다. 인증·정식 start·coordinator·모델 품질 검증은 아니다. */
class GradeLeaseRepositoryIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static GradeLeaseRepository repository;
    private long owner;
    private long version;
    private long snapshot;
    private RuntimeFixture runtime;

    @BeforeAll
    static void open() {
        // 기존 V1~V13 resource migration과 digest로 고정된 16.10 이미지를 그대로 사용한다.
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        repository = new GradeLeaseRepository(jdbc, manager);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void fixtures() {
        // 이전 임대를 지우되 V22의 삭제 금지 보고서가 참조하는 runtime은 유지한다.
        jdbc.update("DELETE FROM public.grade_attempt");
        jdbc.update("DELETE FROM public.grade_job");
        jdbc.update("DELETE FROM public.grade_batch");
        owner =
                id(
                        "INSERT INTO public.admin_account(account_key) VALUES (?) RETURNING id",
                        UUID.randomUUID());
        long story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        token(),
                        owner);
        version =
                id(
                        """
                        INSERT INTO public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'격리 임대','H2',?,?) RETURNING id
                        """,
                        story,
                        owner,
                        owner);
        snapshot =
                id(
                        """
                        INSERT INTO public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                        VALUES (?,0,'{}',?,?) RETURNING id
                        """,
                        version,
                        UUID.randomUUID(),
                        owner);
        runtime = runtime();
    }

    @Test
    void sameWorkerConcurrentClaimsHaveExactlyOneIdenticalReplay() throws Exception {
        long first = queued(runtime);
        long second = queued(runtime);
        String worker = token();
        List<Optional<Lease>> leases =
                concurrentClaims(List.of(worker, worker, worker, worker), runtime.code());
        Lease lease = leases.getFirst().orElseThrow();
        assertThat(leases).allSatisfy(value -> assertThat(value).contains(lease));
        assertThat(lease.jobId()).isIn(first, second);
        assertThat(repository.claim(worker, runtime.code())).contains(lease);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_job WHERE state='RUNNING'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_job WHERE state='QUEUED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(lease.snapshotId()).isEqualTo(snapshot);
        assertThat(lease.batchId()).isEqualTo(runtime.batch());
        assertThat(lease.runtimeId()).isEqualTo(runtime.id());
        assertThat(lease.leaseGen()).isEqualTo(1);
    }

    @Test
    void differentWorkersClaimDistinctJobsConcurrently() throws Exception {
        long first = queued(runtime);
        long second = queued(runtime);
        var leases = concurrentClaims(List.of(token(), token()), runtime.code());
        assertThat(leases).allSatisfy(value -> assertThat(value).isPresent());
        assertThat(leases.stream().map(value -> value.orElseThrow().jobId()).toList())
                .containsExactlyInAnyOrder(first, second);
    }

    /** 출처별 후보와 재전송을 분리하면서 worker당 하나의 임대만 유지한다. */
    @Test
    void testClaimNeverConsumesBatchAndBatchClaimNeverConsumesTest() {
        long batch = queued(runtime);
        long test = queuedTest(runtime);
        String testWorker = token();
        Lease testLease = repository.claimTest(testWorker, runtime.code()).orElseThrow();
        assertThat(testLease.jobId()).isEqualTo(test);
        assertThat(testLease.batchId()).isNull();
        assertThat(repository.claimTest(testWorker, runtime.code())).isEmpty();
        assertThat(repository.claim(testWorker, runtime.code())).isEmpty();
        assertThat(row(batch)).containsEntry("state", "QUEUED").containsEntry("worker_key", null);

        String batchWorker = token();
        Lease batchLease = repository.claim(batchWorker, runtime.code()).orElseThrow();
        assertThat(batchLease.jobId()).isEqualTo(batch);
        assertThat(batchLease.batchId()).isEqualTo(runtime.batch());
        assertThat(repository.claim(batchWorker, runtime.code())).contains(batchLease);
        assertThat(repository.claimTest(batchWorker, runtime.code())).isEmpty();
        assertThat(row(test))
                .containsEntry("batch_id", null)
                .containsEntry("sample_code", null)
                .containsEntry("repeat_no", null)
                .containsEntry("input_hash", null)
                .containsEntry("call_count", 0);
        assertThat(((java.sql.Timestamp) row(test).get("deadline_at")).toInstant())
                .isEqualTo(testLease.deadlineAt().toInstant());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.grade_attempt", Integer.class))
                .isZero();
    }

    /** 세 번째 호출을 위한 두 회 소비 상태는 임대 가능하며 원래 접수 마감만 사용한다. */
    @Test
    void testClaimPreservesOriginalDeadlineAndLeavesExpiredQueueForRecovery() {
        long expired = queuedTest(runtime, true);
        long exhausted = queuedTest(runtime);
        jdbc.update("UPDATE public.grade_job SET call_count=3 WHERE id=?", exhausted);
        long eligible = queuedTest(runtime);
        jdbc.update("UPDATE public.grade_job SET call_count=2,lease_gen=8 WHERE id=?", eligible);
        var expiredBefore = row(expired);
        var exhaustedBefore = row(exhausted);
        var before = row(eligible);
        Lease lease = repository.claimTest(token(), runtime.code()).orElseThrow();
        assertThat(lease.jobId()).isEqualTo(eligible);
        assertThat(lease.leaseGen()).isEqualTo(9);
        assertThat(lease.deadlineAt().toInstant())
                .isEqualTo(((java.sql.Timestamp) before.get("deadline_at")).toInstant());
        assertThat(row(eligible))
                .containsEntry("accepted_at", before.get("accepted_at"))
                .containsEntry("deadline_at", before.get("deadline_at"))
                .containsEntry("call_count", 2);
        assertThat(row(expired)).isEqualTo(expiredBefore);
        assertThat(row(exhausted)).isEqualTo(exhaustedBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deadline_at=accepted_at+interval '120 seconds' FROM"
                                        + " public.grade_job WHERE id=?",
                                Boolean.class,
                                eligible))
                .isTrue();
        assertThat(repository.getExpiredTestQueueList(100)).contains(expired);
        assertThat(repository.claimTest(token(), runtime.code())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.grade_attempt", Integer.class))
                .isZero();
    }

    /** runtime별 bounded TEST 발견은 다른 runtime과 BATCH를 건드리지 않으며 글로벌 조회도 유지한다. */
    @Test
    void expiredTestRuntimeCandidatesAreBoundedReadOnlyAndSourceScoped() {
        long expiredQueue = queuedTest(runtime, true);
        long expiredLease = queuedTest(runtime);
        Lease running = repository.claimTest(token(), runtime.code()).orElseThrow();
        assertThat(running.jobId()).isEqualTo(expiredLease);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE id=?",
                expiredLease);
        long healthy = queuedTest(runtime);
        RuntimeFixture other = runtime();
        long unrelated = queuedTest(other, true);
        long batch = queued(runtime);
        Lease batchLease = repository.claim(token(), runtime.code()).orElseThrow();
        assertThat(batchLease.jobId()).isEqualTo(batch);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE id=?",
                batch);
        var before =
                List.of(
                        row(expiredQueue),
                        row(expiredLease),
                        row(healthy),
                        row(unrelated),
                        row(batch));
        var keys = repository.getExpiredTestRuntimeList(runtime.code(), 100);
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        (UUID) row(expiredQueue).get("job_key"), running.jobKey());
        assertThat(repository.getExpiredTestRuntimeList(runtime.code(), 1)).hasSize(1);
        assertThat(repository.getExpiredTestRuntimeList(other.code(), 100))
                .containsExactly((UUID) row(unrelated).get("job_key"));
        assertThat(repository.getExpiredTestRuntimeList("UNKNOWN", 100)).isEmpty();
        assertThatThrownBy(() -> keys.add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(repository.getExpiredTestQueueList(100)).contains(expiredQueue, unrelated);
        assertThat(repository.getExpiredLeaseList(100)).contains(expiredLease, batch);
        assertThat(
                        List.of(
                                row(expiredQueue),
                                row(expiredLease),
                                row(healthy),
                                row(unrelated),
                                row(batch)))
                .isEqualTo(before);
    }

    @Test
    void existingLeaseIsNotOverwrittenForOtherRuntimeOrExpiredOutstandingWorker() {
        long job = queued(runtime);
        RuntimeFixture other = runtime();
        queued(other);
        String worker = token();
        Lease lease = repository.claim(worker, runtime.code()).orElseThrow();
        var before = row(job);
        assertThat(repository.claim(worker, other.code())).isEmpty();
        assertThat(row(job)).isEqualTo(before);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE id=?",
                job);
        before = row(job);
        assertThat(repository.claim(worker, runtime.code())).isEmpty();
        assertThat(repository.claim(worker, other.code())).isEmpty();
        assertThat(row(job)).isEqualTo(before);
        assertThat(lease.leaseGen()).isEqualTo(1);
    }

    @Test
    void stagedFutureExpiredAndExhaustedJobsAreNotClaimedAndRuntimeStateIsNotBusinessValidated() {
        long staged = staged(runtime);
        long future = queued(runtime);
        jdbc.update(
                "UPDATE public.grade_job SET next_run_at=clock_timestamp()+interval '1 hour' WHERE"
                        + " id=?",
                future);
        long expired = queued(runtime);
        expireDeadline(expired);
        long exhausted = queued(runtime);
        jdbc.update("UPDATE public.grade_job SET call_count=3 WHERE id=?", exhausted);
        var before = List.of(row(staged), row(future), row(expired), row(exhausted));
        assertThat(repository.claim(token(), runtime.code())).isEmpty();
        assertThat(repository.claim(token(), Long.toString(runtime.id()))).isEmpty();
        assertThat(repository.claim(token(), "UNKNOWN")).isEmpty();
        assertThat(List.of(row(staged), row(future), row(expired), row(exhausted)))
                .isEqualTo(before);
        long eligible = queued(runtime);
        jdbc.update(
                "UPDATE public.grade_runtime SET state='SUSPENDED',epoch=7 WHERE id=?",
                runtime.id());
        assertThat(repository.claim(token(), runtime.code()).orElseThrow().jobId())
                .isEqualTo(eligible);
    }

    @Test
    void generationIncrementsWithoutConsumingCallsOrChangingFixedAdmission() {
        long job = queued(runtime);
        jdbc.update("UPDATE public.grade_job SET lease_gen=8,call_count=2 WHERE id=?", job);
        var before = row(job);
        Lease lease = repository.claim("검증 worker / 자유키", runtime.code()).orElseThrow();
        assertThat(lease.leaseGen()).isEqualTo(9);
        assertThat(lease.workerKey()).isEqualTo("검증 worker / 자유키");
        assertThat(row(job))
                .containsEntry("call_count", before.get("call_count"))
                .containsEntry("accepted_at", before.get("accepted_at"))
                .containsEntry("deadline_at", before.get("deadline_at"))
                .containsEntry("result_data", null)
                .containsEntry("result_hash", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deadline_at=accepted_at+interval '120 seconds' AND"
                                        + " lease_until=updated_at+interval '30 seconds' FROM"
                                        + " public.grade_job WHERE id=?",
                                Boolean.class,
                                job))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.grade_attempt", Integer.class))
                .isZero();
    }

    /** 잘못된 worker·세대·시한의 갱신을 거절하고 세 번째 실행의 호출 수는 보존한다. */
    @Test
    void renewFencesWrongWorkerStaleGenerationExpiredTerminalAndKeepsThirdCall() {
        long job = queued(runtime);
        String worker = token();
        Lease lease = repository.claim(worker, runtime.code()).orElseThrow();
        var before = row(job);
        assertThat(repository.renew(lease.jobKey(), token(), lease.leaseGen())).isEmpty();
        assertThat(repository.renew(lease.jobKey(), worker, lease.leaseGen() + 1)).isEmpty();
        assertThat(repository.renew(UUID.randomUUID(), worker, lease.leaseGen())).isEmpty();
        assertThat(row(job)).isEqualTo(before);
        jdbc.update("UPDATE public.grade_job SET call_count=3 WHERE id=?", job);
        Lease renewed = repository.renew(lease.jobKey(), worker, lease.leaseGen()).orElseThrow();
        assertThat(renewed.leaseGen()).isEqualTo(lease.leaseGen());
        assertThat(renewed.deadlineAt()).isEqualTo(lease.deadlineAt());
        assertThat(renewed.leaseUntil()).isAfterOrEqualTo(lease.leaseUntil());
        assertThat(row(job)).containsEntry("call_count", 3);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE id=?",
                job);
        before = row(job);
        assertThat(repository.renew(lease.jobKey(), worker, lease.leaseGen())).isEmpty();
        assertThat(row(job)).isEqualTo(before);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '1 hour' WHERE"
                        + " id=?",
                job);
        expireDeadline(job);
        before = row(job);
        assertThat(repository.renew(lease.jobKey(), worker, lease.leaseGen())).isEmpty();
        assertThat(row(job)).isEqualTo(before);
        for (String state : List.of("COMPLETED", "FAILED", "CANCELLED", "QUEUED")) {
            jdbc.update(
                    "UPDATE public.grade_job SET state=?,worker_key=NULL,lease_until=NULL WHERE"
                            + " id=?",
                    state,
                    job);
            before = row(job);
            assertThat(repository.renew(lease.jobKey(), worker, lease.leaseGen())).isEmpty();
            assertThat(row(job)).isEqualTo(before);
        }
    }

    /** 최초 임대와 갱신이 동일한 고정 deadline을 넘지 않는지 확인한다. */
    @Test
    void claimAndRenewCapLeaseAtDeadline() {
        long job = queued(runtime);
        // 충분히 남은 20초 시한을 DB에서 설정한다. 실제 만료를 기다리지 않는다.
        setDeadlineIn(job, 20);
        Lease lease = repository.claim(token(), runtime.code()).orElseThrow();
        assertThat(lease.leaseUntil()).isEqualTo(lease.deadlineAt());
        Lease renewed =
                repository.renew(lease.jobKey(), lease.workerKey(), lease.leaseGen()).orElseThrow();
        assertThat(renewed.leaseUntil()).isEqualTo(lease.deadlineAt());
        assertThat(row(job)).containsEntry("call_count", 0);
    }

    @Test
    void lockedCandidateIsSkippedAndRuntimeIsNeverLocked() throws Exception {
        long first = queued(runtime);
        long second = queued(runtime);
        try (Connection lock = postgres.createConnection("")) {
            lock.setAutoCommit(false);
            try (var statement =
                    lock.prepareStatement(
                            "SELECT id FROM public.grade_job WHERE id=? FOR UPDATE")) {
                statement.setLong(1, first);
                statement.executeQuery().close();
            }
            // runtime이 별도 연결에 잠겨 있어도 JOIN 읽기와 job 할당은 진행되어야 한다.
            try (var statement =
                    lock.prepareStatement(
                            "SELECT id FROM public.grade_runtime WHERE id=? FOR UPDATE")) {
                statement.setLong(1, runtime.id());
                statement.executeQuery().close();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                Lease lease =
                        executor.submit(
                                        () ->
                                                repository
                                                        .claim(token(), runtime.code())
                                                        .orElseThrow())
                                .get(3, TimeUnit.SECONDS);
                assertThat(lease.jobId()).isEqualTo(second);
            } finally {
                lock.rollback();
            }
        }
        assertThat(repository.claim(token(), runtime.code()).orElseThrow().jobId())
                .isEqualTo(first);
    }

    @Test
    void renewReadsDatabaseTimeAfterWaitingForRowLock() throws Exception {
        long job = queued(runtime);
        Lease lease = repository.claim(token(), runtime.code()).orElseThrow();
        var postLockJdbc =
                new JdbcTemplate(jdbc.getDataSource()) {
                    @Override
                    public <T> T queryForObject(String sql, Class<T> type) {
                        if (sql.equals("SELECT clock_timestamp()")) assertJobLocked(job);
                        return super.queryForObject(sql, type);
                    }
                };
        var postLockRepository = new GradeLeaseRepository(postLockJdbc, manager);
        try (Connection lock = postgres.createConnection("")) {
            lock.setAutoCommit(false);
            try (var statement =
                    lock.prepareStatement(
                            "SELECT id FROM public.grade_job WHERE id=? FOR UPDATE")) {
                statement.setLong(1, job);
                statement.executeQuery().close();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var renewal =
                        executor.submit(
                                () ->
                                        postLockRepository.renew(
                                                lease.jobKey(),
                                                lease.workerKey(),
                                                lease.leaseGen()));
                try {
                    awaitJobLockWait();
                    try (var statement =
                            lock.prepareStatement(
                                    "UPDATE public.grade_job SET"
                                        + " lease_until=clock_timestamp()-interval '1 second' WHERE"
                                        + " id=?")) {
                        statement.setLong(1, job);
                        statement.executeUpdate();
                    }
                    lock.commit();
                    assertThat(renewal.get(5, TimeUnit.SECONDS)).isEmpty();
                } finally {
                    lock.rollback();
                }
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_until<clock_timestamp() AND lease_gen=1 FROM"
                                        + " public.grade_job WHERE id=?",
                                Boolean.class,
                                job))
                .isTrue();
    }

    /** 실제 잠금 뒤 DB 처리 지연으로 소진된 시한을 DB 시계로 재검사하는지 확인한다. */
    @Test
    void candidateIsRecheckedUsingActualDbClockAfterItsLock() {
        long job = queued(runtime);
        setDeadlineIn(job, 1);
        var clockRead = new AtomicBoolean();
        // 잠긴 행의 시한을 사후 변경하지 않는다. 짧은 실제 DB 지연 뒤 원래 시한의 경과를 관찰한다.
        var boundaryJdbc =
                new JdbcTemplate(jdbc.getDataSource()) {
                    @Override
                    public <T> T queryForObject(String sql, Class<T> type) {
                        if (sql.equals("SELECT clock_timestamp()")) {
                            assertJobLocked(job);
                            execute("SELECT pg_sleep(1.1)");
                            clockRead.set(true);
                        }
                        return super.queryForObject(sql, type);
                    }
                };
        var boundaryRepository = new GradeLeaseRepository(boundaryJdbc, manager);
        assertThat(boundaryRepository.claim(token(), runtime.code())).isEmpty();
        assertThat(clockRead).isTrue();
        assertThat(row(job))
                .containsEntry("state", "QUEUED")
                .containsEntry("lease_gen", 0L)
                .containsEntry("worker_key", null)
                .containsEntry("lease_until", null);
    }

    @Test
    void expiredCandidateLookupIsBoundedReadOnlyAndDoesNotWaitForSourceOrJobLocks()
            throws Exception {
        long first = queued(runtime);
        long second = queued(runtime);
        long queuedExpired = queued(runtime);
        Lease one = repository.claim(token(), runtime.code()).orElseThrow();
        Lease two = repository.claim(token(), runtime.code()).orElseThrow();
        assertThat(List.of(one.jobId(), two.jobId())).containsExactlyInAnyOrder(first, second);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '2 seconds'"
                        + " WHERE id=?",
                first);
        expireDeadline(second);
        expireDeadline(queuedExpired);
        var before = List.of(row(first), row(second), row(queuedExpired));
        try (Connection lock = postgres.createConnection("")) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.execute("SELECT id FROM public.grade_job FOR UPDATE");
                statement.execute("SELECT id FROM public.grade_runtime FOR UPDATE");
                statement.execute("SELECT id FROM public.review_snapshot FOR UPDATE");
                statement.execute("SELECT id FROM public.admin_account FOR UPDATE");
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                List<Long> ids =
                        executor.submit(() -> repository.getExpiredLeaseList(100))
                                .get(3, TimeUnit.SECONDS);
                assertThat(ids).containsExactlyInAnyOrder(first, second);
                assertThatThrownBy(() -> ids.add(1L))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThat(repository.getExpiredLeaseList(1)).hasSize(1);
            } finally {
                lock.rollback();
            }
        }
        assertThat(List.of(row(first), row(second), row(queuedExpired))).isEqualTo(before);
    }

    @Test
    void failedClaimAndRenewRollBackAllWritesAndLeaveWorkerUsable() {
        long job = queued(runtime);
        var before = row(job);
        var failingJdbc =
                new JdbcTemplate(jdbc.getDataSource()) {
                    @Override
                    public int update(String sql, Object... args) {
                        int affected = super.update(sql, args);
                        throw new DataAccessResourceFailureException("합성 저장 후 실패");
                    }
                };
        var failing = new GradeLeaseRepository(failingJdbc, manager);
        String worker = token();
        assertThatThrownBy(() -> failing.claim(worker, runtime.code()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(row(job)).isEqualTo(before);
        Lease lease = repository.claim(worker, runtime.code()).orElseThrow();
        before = row(job);
        assertThatThrownBy(() -> failing.renew(lease.jobKey(), worker, lease.leaseGen()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(row(job)).isEqualTo(before);
        assertThat(repository.claim(worker, runtime.code())).contains(lease);

        long test = queuedTest(runtime);
        var testBefore = row(test);
        assertThatThrownBy(() -> failing.claimTest(token(), runtime.code()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(row(test)).isEqualTo(testBefore);
        assertThat(repository.claimTest(token(), runtime.code()).orElseThrow().jobId())
                .isEqualTo(test);
    }

    @Test
    void ownedTransactionsUseReadCommittedLocalTimeoutAndReadOnlyCandidateScan() {
        queued(runtime);
        var settings = new ArrayList<String>();
        var observingJdbc =
                new JdbcTemplate(jdbc.getDataSource()) {
                    @Override
                    public void execute(String sql) {
                        super.execute(sql);
                        if (sql.equals("SET LOCAL lock_timeout='5s'")) {
                            settings.add(
                                    jdbc.queryForObject(
                                            """
                                            SELECT current_setting('transaction_isolation')||'|'||
                                                current_setting('lock_timeout')||'|'||current_setting('transaction_read_only')
                                            """,
                                            String.class));
                        }
                    }
                };
        var observed = new GradeLeaseRepository(observingJdbc, manager);
        Lease lease = observed.claim(token(), runtime.code()).orElseThrow();
        assertThat(observed.renew(lease.jobKey(), lease.workerKey(), lease.leaseGen())).isPresent();
        assertThat(observed.getExpiredLeaseList(1)).isEmpty();
        assertThat(observed.getExpiredTestRuntimeList(runtime.code(), 1)).isEmpty();
        assertThat(settings)
                .containsExactly(
                        "read committed|5s|off",
                        "read committed|5s|off",
                        "read committed|5s|on",
                        "read committed|5s|on");
        assertThat(jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class))
                .isEqualTo("0");
    }

    @Test
    void invalidInputsAndNestedTransactionsAreRejectedWithoutMutation() {
        long job = queued(runtime);
        var before = row(job);
        for (String bad : List.of("", "   ", "x".repeat(81))) {
            assertThatThrownBy(() -> repository.claim(bad, runtime.code()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.claim(token(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.claimTest(bad, runtime.code()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.claimTest(token(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.renew(UUID.randomUUID(), bad, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> repository.claim(null, runtime.code()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claim(token(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claimTest(null, runtime.code()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claimTest(token(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.renew(null, token(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.renew(UUID.randomUUID(), null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        for (long generation : List.of(0L, -1L)) {
            assertThatThrownBy(() -> repository.renew(UUID.randomUUID(), token(), generation))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int limit : List.of(0, -1, 101)) {
            assertThatThrownBy(() -> repository.getExpiredLeaseList(limit))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.getExpiredTestRuntimeList(runtime.code(), limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : List.of("", "   ", "x".repeat(81)))
            assertThatThrownBy(() -> repository.getExpiredTestRuntimeList(bad, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.getExpiredTestRuntimeList(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        var transaction = new TransactionTemplate(manager);
        for (boolean readOnly : List.of(false, true)) {
            transaction.setReadOnly(readOnly);
            transaction.executeWithoutResult(
                    status -> {
                        assertThatThrownBy(() -> repository.claim(token(), runtime.code()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("LEASE_REQUIRES_SEPARATE_TRANSACTION");
                        assertThatThrownBy(() -> repository.claimTest(token(), runtime.code()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("LEASE_REQUIRES_SEPARATE_TRANSACTION");
                        assertThatThrownBy(() -> repository.renew(UUID.randomUUID(), token(), 1))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("LEASE_REQUIRES_SEPARATE_TRANSACTION");
                        assertThatThrownBy(() -> repository.getExpiredLeaseList(1))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("LEASE_REQUIRES_SEPARATE_TRANSACTION");
                        assertThatThrownBy(
                                        () ->
                                                repository.getExpiredTestRuntimeList(
                                                        runtime.code(), 1))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("LEASE_REQUIRES_SEPARATE_TRANSACTION");
                    });
        }
        assertThat(row(job)).isEqualTo(before);
    }

    /** 라치로 동시 시작만 제어하며 신원 검증을 대신하지 않는 worker 시험 입력을 사용한다. */
    private List<Optional<Lease>> concurrentClaims(List<String> workers, String code)
            throws Exception {
        var ready = new CountDownLatch(workers.size());
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers.size())) {
            var futures = new ArrayList<java.util.concurrent.Future<Optional<Lease>>>();
            for (String worker : workers) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!release.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("동시 시작 실패");
                                    return repository.claim(worker, code);
                                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            var values = new ArrayList<Optional<Lease>>();
            for (var future : futures) values.add(future.get(15, TimeUnit.SECONDS));
            return values;
        }
    }

    /** DB 시각을 읽는 순간 실제 job 잠금이 존재하는지 독립 연결의 NOWAIT로 확인한다. */
    private void assertJobLocked(long job) {
        try (Connection connection = postgres.createConnection("");
                var statement =
                        connection.prepareStatement(
                                "SELECT id FROM public.grade_job WHERE id=? FOR UPDATE NOWAIT")) {
            statement.setLong(1, job);
            assertThatThrownBy(statement::executeQuery)
                    .isInstanceOf(java.sql.SQLException.class)
                    .satisfies(
                            error ->
                                    assertThat(((java.sql.SQLException) error).getSQLState())
                                            .isEqualTo("55P03"));
        } catch (java.sql.SQLException failure) {
            throw new AssertionError("독립 잠금 확인 연결 실패", failure);
        }
    }

    /** 잠금 대기 진입만 짧게 제한 조회한다. 시한 소진을 기다리는 긴 sleep이나 busy spin은 없다. */
    private void awaitJobLockWait() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Boolean waiting =
                    jdbc.queryForObject(
                            """
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                                AND wait_event_type='Lock' AND query LIKE '%WHERE j.job_key=%FOR UPDATE OF j%')
                            """,
                            Boolean.class);
            if (Boolean.TRUE.equals(waiting)) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("갱신 job 잠금 대기 진입 실패");
    }

    /** 실제 FK 부모가 완비된 runtime/batch를 만든다. */
    private RuntimeFixture runtime() {
        String code = token();
        long runtimeId =
                id(
                        "INSERT INTO public.grade_runtime(code,config_hash,config_data,state)"
                                + " VALUES (?,?,'{}','AVAILABLE') RETURNING id",
                        code,
                        GradeSchemaIT.HASH);
        long batch =
                id(
                        """
                        INSERT INTO public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,
                            rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)
                        VALUES (?,?,?,'REVIEW',?,?,?,?,0,'STAGED',3,?) RETURNING id
                        """,
                        UUID.randomUUID(),
                        snapshot,
                        runtimeId,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH,
                        owner);
        return new RuntimeFixture(runtimeId, code, batch);
    }

    /** 미활성 fixture는 NULL 접수 시각으로 만들며 실제 source FK를 사용한다. */
    private long staged(RuntimeFixture fixture) {
        return id(
                """
                INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,
                    repeat_no,state,input_hash,config_hash,rubric_hash)
                VALUES (?,?,?,?,?,1,'STAGED',?,?,?) RETURNING id
                """,
                UUID.randomUUID(),
                snapshot,
                fixture.id(),
                fixture.batch(),
                UUID.randomUUID().toString().replace("-", ""),
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH);
    }

    /** fixture activation을 흉내내되 임대 저장소 자체에는 activation 책임을 추가하지 않는다. */
    private long queued(RuntimeFixture fixture) {
        long job = staged(fixture);
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                UPDATE public.grade_job SET state='QUEUED',accepted_at=t.n,
                    deadline_at=t.n+interval '120 seconds',next_run_at=t.n FROM t WHERE id=?
                """,
                job);
        return job;
    }

    /** 실제 TEST 부모와 두 회원의 접수 보고서를 만들어 불변 접수 시각으로 단일 대기 작업을 생성한다. */
    private long queuedTest(RuntimeFixture fixture) {
        return queuedTest(fixture, false);
    }

    /** 마감 fixture도 수락 시점에 시각을 고정해 V22의 접수 출처 불변 규칙을 지킨다. */
    private long queuedTest(RuntimeFixture fixture, boolean expired) {
        jdbc.update(
                "INSERT INTO public.story_role(version_id,code,name) VALUES"
                        + " (?,'R1','R1'),(?,'R2','R2') ON CONFLICT DO NOTHING",
                version,
                version);
        jdbc.update(
                "INSERT INTO public.story_pair(version_id,role_a,role_b) VALUES (?,'R1','R2')"
                        + " ON CONFLICT DO NOTHING",
                version);
        long test =
                id(
                        """
                        WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                        INSERT INTO public.play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,
                            runtime_epoch,role_a,role_b,mode,state,invite_until,started_at,deadline_at,
                            created_by,created_at,updated_at)
                        SELECT ?,?,?,?,?,0,'R1','R2','FUNCTIONAL','RUNNING',t.n+interval '7 days',
                            t.n,t.n+interval '15 minutes',?,t.n,t.n FROM t RETURNING id
                        """,
                        UUID.randomUUID(),
                        version,
                        snapshot,
                        fixture.id(),
                        GradeSchemaIT.HASH,
                        owner);
        long proposer =
                id(
                        "INSERT INTO public.member_account(member_key,state)"
                                + " VALUES (?,'ACTIVE') RETURNING id",
                        UUID.randomUUID());
        long acceptor =
                id(
                        "INSERT INTO public.member_account(member_key,state)"
                                + " VALUES (?,'ACTIVE') RETURNING id",
                        UUID.randomUUID());
        jdbc.update(
                "INSERT INTO public.test_member(test_id,member_id,slot) VALUES (?,?,1),(?,?,2)",
                test,
                proposer,
                test,
                acceptor);
        long report =
                id(
                        """
                        WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                        INSERT INTO public.test_report(report_key,test_id,snapshot_id,runtime_id,config_hash,
                            runtime_epoch,source_draft_rev,proposer_id,payload_cipher,payload_hash,
                            created_at,updated_at)
                        SELECT ?,?,?,?, ?,0,0,?,decode(repeat('00',32),'hex'),?,
                            t.n-interval '125 seconds',t.n-interval '125 seconds' FROM t RETURNING id
                        """,
                        UUID.randomUUID(),
                        test,
                        snapshot,
                        fixture.id(),
                        GradeSchemaIT.HASH,
                        proposer,
                        GradeSchemaIT.HASH);
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                UPDATE public.test_report SET state='ACCEPTED',accepted_by=?,submit_no=1,
                    accepted_at=t.n-?*interval '1 second',updated_at=t.n
                FROM t WHERE id=?
                """,
                acceptor,
                expired ? 121 : 0,
                report);
        return id(
                """
                INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,report_id,report_hash,
                    state,config_hash,rubric_hash,accepted_at,deadline_at)
                SELECT ?,?,?,r.id,?,'QUEUED',?,?,r.accepted_at,
                    r.accepted_at+interval '120 seconds' FROM public.test_report r WHERE r.id=?
                RETURNING id
                """,
                UUID.randomUUID(),
                snapshot,
                fixture.id(),
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH,
                report);
    }

    /** 단일 실제 DB 시각으로 고정 120초를 유지하며 남은 시한을 설정한다. */
    private void setDeadlineIn(long job, int seconds) {
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp()+?*interval '1 second' d)
                UPDATE public.grade_job SET accepted_at=t.d-interval '120 seconds',deadline_at=t.d
                FROM t WHERE id=?
                """,
                seconds,
                job);
    }

    /** 잠든 시간이 아니라 이미 만료된 실제 DB fixture를 설정한다. */
    private void expireDeadline(long job) {
        setDeadlineIn(job, -1);
    }

    /** 결과·호출 예산·접수 시각을 포함한 전체 행의 불변성 비교용 사본이다. */
    private java.util.Map<String, Object> row(long job) {
        return jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job);
    }

    /** 테스트 INSERT의 내부 식별자를 읽는다. */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 인증 시스템을 대체하지 않는 고유 시험 키다. */
    private String token() {
        return "L_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /** 실제 runtime과 같은 사본에 묶인 batch 관계다. */
    private record RuntimeFixture(long id, String code, long batch) {}
}
