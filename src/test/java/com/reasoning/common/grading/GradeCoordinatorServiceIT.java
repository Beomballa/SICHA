package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeDictionary.Term;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.service.GradeCoordinatorService;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 폐기형 pinned V21·전체 codec·실제 설치·REPORT 경계 시험이다. 모델 추론·GRADE 승인은 없다. */
class GradeCoordinatorServiceIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private Fixture fixture;
    private GradeWorkerCredentials credentials;
    private GradeCoordinatorService service;
    private String workerKey;
    private UUID key;
    private long job;
    private final byte[] secret =
            ByteBuffer.allocate(32).putLong(11).putLong(12).putLong(13).putLong(14).array();
    private static final String CANARY = "MALFORMED_REPORT_PRIVATE_CANARY";

    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(version::int) FROM public.flyway_schema_history WHERE"
                                        + " success",
                                Integer.class))
                .isEqualTo(23);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 이전 사례의 살아 있는 lease를 다른 runtime의 새 worker로 재사용하지 않는다. */
    @BeforeEach
    void setup() {
        fixture = fixture();
        workerKey = "COORDINATOR_" + UUID.randomUUID();
        credentials =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        CommonUtil.sha256(secret),
                                        Set.of(fixture.code()),
                                        Set.of(Action.START, Action.COMPLETE))));
        service = service(fixture, 1);
        key = UUID.randomUUID();
        job = staged(fixture, key, "FULL", 1);
    }

    @AfterEach
    void removeTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS coordinator_job_fault ON public.grade_job");
        jdbc.execute("DROP TRIGGER IF EXISTS coordinator_event_fault ON public.grade_event");
        jdbc.execute("DROP FUNCTION IF EXISTS public.coordinator_fault()");
    }

    @Test
    void stagedCannotBeClaimedThenAdmissionCanBeStartedExactlyOnce() {
        var leases = new GradeLeaseRepository(jdbc, manager);
        assertThat(leases.claim(workerKey, fixture.code())).isEmpty();
        var activated = service.activate(key);
        assertThat(activated.changed()).isTrue();
        assertThat(activated.state()).isEqualTo("QUEUED");
        assertThat(activated.deadlineAt()).isEqualTo(activated.acceptedAt().plusSeconds(120));
        assertThat(activated.inputRejected()).isFalse();
        assertThat(attempts(job)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT call_count FROM public.grade_job WHERE id=?",
                                Integer.class,
                                job))
                .isZero();
        String stored = jobJson(job);
        var replay = service.activate(key);
        assertThat(replay.changed()).isFalse();
        assertThat(replay.acceptedAt()).isEqualTo(activated.acceptedAt());
        assertThat(jobJson(job)).isEqualTo(stored);
        var lease = leases.claim(workerKey, fixture.code()).orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(key);
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        var reservation =
                new GradeStartService(
                                jdbc,
                                manager,
                                credentials,
                                Map.of(fixture.code(), fixture.installation()))
                        .start(worker, key, lease.leaseGen());
        assertThat(reservation.replay()).isFalse();
        assertThat(reservation.attemptNo()).isEqualTo(1);
        assertThat(reservation.deadlineAt()).isEqualTo(activated.deadlineAt());
        assertThat(reservation.selection().kind()).isEqualTo("GRADED");
        assertThat(events(job)).isEqualTo(1);
        assertSystemEvent(job, "JOB_ACTIVATED", "QUEUED", "NONE");
    }

    @Test
    void capacityOneCountsQueuedAndRunningAndDoesNotStartClockForWaitingJob() {
        service.activate(key);
        UUID second = UUID.randomUUID();
        long secondId = staged(fixture, second, "ZERO", 1);
        String before = jobJson(secondId);
        var blocked = service.activate(second);
        assertThat(blocked.state()).isEqualTo("STAGED");
        assertThat(blocked.changed()).isFalse();
        assertThat(blocked.acceptedAt()).isNull();
        assertThat(jobJson(secondId)).isEqualTo(before);
        assertThat(events(secondId)).isZero();
        new GradeLeaseRepository(jdbc, manager).claim(workerKey, fixture.code()).orElseThrow();
        assertThat(service.activate(second).changed()).isFalse();
        assertThat(jobJson(secondId)).isEqualTo(before);
    }

    @Test
    void capacityTwoAndTwoInstancesSerializeConcurrentAdmission() throws Exception {
        var one = service(fixture, 2);
        var two = service(fixture, 2);
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        staged(fixture, second, "ZERO", 1);
        staged(fixture, third, "ENGINE", 1);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var firstFuture =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                go.await();
                                return one.activate(key);
                            });
            var secondFuture =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                go.await();
                                return two.activate(second);
                            });
            var thirdFuture =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                go.await();
                                return one.activate(third);
                            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var results =
                    List.of(
                            firstFuture.get(10, TimeUnit.SECONDS),
                            secondFuture.get(10, TimeUnit.SECONDS),
                            thirdFuture.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(value -> value.changed()).count()).isEqualTo(2);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_job WHERE runtime_id=? AND state"
                                        + " IN ('QUEUED','RUNNING')",
                                Long.class,
                                fixture.runtime()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_event e JOIN public.grade_job j"
                                        + " ON j.id=e.job_id WHERE j.runtime_id=?",
                                Long.class,
                                fixture.runtime()))
                .isEqualTo(2);
    }

    @Test
    void capacityOneAcrossTwoInstancesDoesNotOversubscribe() throws Exception {
        UUID second = UUID.randomUUID();
        staged(fixture, second, "ZERO", 1);
        var other = service(fixture, 1);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                go.await();
                                return service.activate(key);
                            });
            var b =
                    pool.submit(
                            () -> {
                                go.await();
                                return other.activate(second);
                            });
            go.countDown();
            assertThat(
                            List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
                                    .stream()
                                    .filter(value -> value.changed())
                                    .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void independentRuntimeHasIndependentCapacity() {
        service.activate(key);
        Fixture independent = fixture();
        UUID another = UUID.randomUUID();
        staged(independent, another, "FULL", 1);
        assertThat(service(independent, 1).activate(another).state()).isEqualTo("QUEUED");
    }

    @Test
    void inputErrorExecutesActualServerValidationDespiteFullProviderCapacity() {
        service.activate(key);
        UUID input = UUID.randomUUID();
        long inputId = staged(fixture, input, "INPUT", 1);
        var result = service.activate(input);
        assertThat(result.changed()).isTrue();
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.reason()).isEqualTo("INVALID_REPORT");
        assertThat(result.inputRejected()).isTrue();
        assertThat(result.deadlineAt()).isEqualTo(result.acceptedAt().plusSeconds(120));
        String data =
                jdbc.queryForObject(
                        "SELECT result_data::text FROM public.grade_job WHERE id=?",
                        String.class,
                        inputId);
        var safe = SnapshotJson.parse(data.getBytes(StandardCharsets.UTF_8));
        assertThat(safe.get("kind").textValue()).isEqualTo("INPUT_ERROR");
        assertThat(safe.get("validationSource").textValue()).isEqualTo("SERVER_REPORT_VALIDATOR");
        assertThat(safe.get("validatorVersion").textValue()).isEqualTo("REPORT-1");
        assertThat(java.time.Instant.parse(safe.get("validatedAt").textValue()))
                .isEqualTo(result.acceptedAt().toInstant());
        assertThat(safe.get("error").get("code").textValue()).isEqualTo("INVALID_REPORT");
        assertThat(safe.get("error").get("state").textValue()).isEqualTo("REJECTED");
        assertThat(safe.get("error").get("score").isNull()).isTrue();
        assertThat(safe.get("baseScore").isNull()).isTrue();
        assertThat(safe.get("baseSuccess").isNull()).isTrue();
        assertThat(safe.get("attemptDelta").intValue()).isZero();
        assertThat(safe.get("expectationAgreement").booleanValue()).isTrue();
        assertThat(safe.get("providerMatch").booleanValue()).isFalse();
        assertThat(safe.get("candidateAdoption").booleanValue()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_hash FROM public.grade_job WHERE id=?",
                                String.class,
                                inputId))
                .isEqualTo(SnapshotJson.hash(safe));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT call_count=0 AND lease_gen=0 AND worker_key IS NULL AND"
                                        + " lease_until IS NULL AND result_cipher IS NULL FROM"
                                        + " public.grade_job WHERE id=?",
                                Boolean.class,
                                inputId))
                .isTrue();
        assertThat(attempts(inputId)).isZero();
        assertSystemEvent(inputId, "JOB_INPUT_REJECTED", "COMPLETED", "INVALID_REPORT");
        assertThat(data + result + eventJson(inputId))
                .doesNotContain(
                        CANARY, "ENGINE_REPORT", "expectData", "culpritCode", "methodAnswer");
        String before = jobJson(inputId);
        assertThat(service.activate(input).changed()).isFalse();
        assertThat(jobJson(inputId)).isEqualTo(before);
        assertThat(events(inputId)).isEqualTo(1);
    }

    @Test
    void engineFaultIsKeptServerSideAndDoesNotRunOrRejectInput() {
        UUID engine = UUID.randomUUID();
        long id = staged(fixture, engine, "ENGINE", 1);
        var result = service.activate(engine);
        assertThat(result.state()).isEqualTo("QUEUED");
        assertThat(result.inputRejected()).isFalse();
        assertThat(attempts(id)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_data IS NULL AND result_hash IS NULL AND"
                                        + " result_cipher IS NULL FROM public.grade_job WHERE id=?",
                                Boolean.class,
                                id))
                .isTrue();
        assertThat(result.toString() + eventJson(id))
                .doesNotContain(
                        "TIMEOUT", "UNAVAILABLE", "failRuns", "ENGINE_REPORT", "expectData");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "account",
                "globalReview",
                "scopedReview",
                "mfa",
                "enrollment",
                "story",
                "version",
                "status",
                "runtime"
            })
    void revokedSourceCancelsWithoutAdmissionClockOrAttempts(String change) {
        switch (change) {
            case "account" ->
                    jdbc.update(
                            "UPDATE public.admin_account SET active_yn=false WHERE id=?",
                            fixture.creator());
            case "globalReview" ->
                    jdbc.update(
                            "UPDATE public.admin_account SET can_review=false WHERE id=?",
                            fixture.creator());
            case "scopedReview" ->
                    jdbc.update(
                            "UPDATE public.story_access SET active_yn=false WHERE story_id=? AND"
                                    + " permission='REVIEW'",
                            fixture.story());
            case "mfa" ->
                    jdbc.update(
                            "UPDATE public.admin_credential SET mfa_state='RECOVERY' WHERE"
                                    + " account_id=?",
                            fixture.creator());
            case "enrollment" ->
                    jdbc.update(
                            "UPDATE public.admin_credential SET"
                                    + " enrolled_at=NULL,mfa_state='PENDING' WHERE account_id=?",
                            fixture.creator());
            case "story" ->
                    jdbc.update(
                            "UPDATE public.story SET active_yn=false WHERE id=?", fixture.story());
            case "version" ->
                    jdbc.update(
                            "UPDATE public.story_version SET active_yn=false WHERE id=?",
                            fixture.version());
            case "status" ->
                    jdbc.update(
                            "UPDATE public.story_version SET status='READY' WHERE id=?",
                            fixture.version());
            case "runtime" ->
                    jdbc.update(
                            "UPDATE public.grade_runtime SET state='SUSPENDED' WHERE id=?",
                            fixture.runtime());
            default -> throw new AssertionError(change);
        }
        var result = service.activate(key);
        assertThat(result.state()).isEqualTo("CANCELLED");
        assertThat(result.reason()).isEqualTo("SOURCE_REVOKED");
        assertThat(result.acceptedAt()).isNull();
        assertThat(result.deadlineAt()).isNull();
        assertThat(attempts(job)).isZero();
        assertSystemEvent(job, "JOB_SOURCE_CANCELLED", "CANCELLED", "SOURCE_REVOKED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"epoch", "age"})
    void expiredOrDifferentEpochFailsWithoutInventedAdmissionClock(String change) {
        if (change.equals("epoch"))
            jdbc.update("UPDATE public.grade_runtime SET epoch=1 WHERE id=?", fixture.runtime());
        else
            jdbc.update(
                    "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval '24 hours'"
                            + " WHERE id=?",
                    fixture.batch());
        var result = service.activate(key);
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.reason())
                .isEqualTo(change.equals("epoch") ? "RUNTIME_EPOCH_CHANGED" : "DEADLINE_EXCEEDED");
        assertThat(result.acceptedAt()).isNull();
        assertThat(attempts(job)).isZero();
    }

    @Test
    void stagedBatchIsNotActivatedAsSideEffect() {
        jdbc.update("UPDATE public.grade_batch SET state='STAGED' WHERE id=?", fixture.batch());
        String before = jobJson(job);
        assertThat(service.activate(key).changed()).isFalse();
        assertThat(jobJson(job)).isEqualTo(before);
        assertThat(events(job)).isZero();
    }

    @Test
    void historicalSourceChangesDoNotResetAlreadyAdmittedJob() {
        var accepted = service.activate(key);
        jdbc.update(
                "UPDATE public.admin_account SET can_review=false WHERE id=?", fixture.creator());
        jdbc.update("UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=?", fixture.runtime());
        String before = jobJson(job);
        var replay = service.activate(key);
        assertThat(replay.changed()).isFalse();
        assertThat(replay.acceptedAt()).isEqualTo(accepted.acceptedAt());
        assertThat(replay.deadlineAt()).isEqualTo(accepted.deadlineAt());
        assertThat(jobJson(job)).isEqualTo(before);
        assertThat(events(job)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "FAILED", "COMPLETED"})
    void terminalJobNeverReactivates(String state) {
        jdbc.update(
                "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) UPDATE public.grade_job SET"
                    + " state=?,accepted_at=t.n,deadline_at=t.n+interval '120 seconds' FROM t WHERE"
                    + " id=?",
                state,
                job);
        String before = jobJson(job);
        assertThat(service.activate(key).changed()).isFalse();
        assertThat(jobJson(job)).isEqualTo(before);
        assertThat(events(job)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jobConfig",
                "batchConfig",
                "inputHash",
                "datasetHash",
                "rubricHash",
                "payloadHash",
                "policy",
                "format",
                "revision",
                "manifest",
                "runtimeCode"
            })
    void actualStorageOrManifestMismatchFailsClosedNotSourceFallback(String change) {
        switch (change) {
            case "jobConfig" ->
                    jdbc.update(
                            "UPDATE public.grade_job SET config_hash=? WHERE id=?",
                            "b".repeat(64),
                            job);
            case "batchConfig" ->
                    jdbc.update(
                            "UPDATE public.grade_batch SET config_hash=? WHERE id=?",
                            "b".repeat(64),
                            fixture.batch());
            case "inputHash" ->
                    jdbc.update(
                            "UPDATE public.grade_job SET input_hash=? WHERE id=?",
                            "b".repeat(64),
                            job);
            case "datasetHash" ->
                    jdbc.update(
                            "UPDATE public.grade_batch SET dataset_hash=? WHERE id=?",
                            "b".repeat(64),
                            fixture.batch());
            case "rubricHash" ->
                    jdbc.update(
                            "UPDATE public.grade_batch SET rubric_hash=? WHERE id=?",
                            "b".repeat(64),
                            fixture.batch());
            case "payloadHash" ->
                    jdbc.update(
                            "UPDATE public.grade_batch SET payload_hash=? WHERE id=?",
                            "b".repeat(64),
                            fixture.batch());
            case "policy" ->
                    jdbc.update(
                            "UPDATE public.story_version SET policy_code='WRONG_POLICY' WHERE id=?",
                            fixture.version());
            case "format" ->
                    jdbc.update(
                            "UPDATE public.review_snapshot SET format_no=2 WHERE id=?",
                            fixture.snapshot());
            case "revision" ->
                    jdbc.update(
                            "UPDATE public.review_snapshot SET edit_rev=1 WHERE id=?",
                            fixture.snapshot());
            case "manifest" ->
                    jdbc.update(
                            "UPDATE public.grade_runtime SET"
                                    + " config_data=jsonb_set(config_data,'{model}',"
                                    + " '\"DIFFERENT\"'::jsonb) WHERE id=?",
                            fixture.runtime());
            case "runtimeCode" ->
                    jdbc.update(
                            "UPDATE public.grade_runtime SET code='OTHER_RUNTIME' WHERE id=?",
                            fixture.runtime());
            default -> throw new AssertionError(change);
        }
        String before = jobJson(job);
        assertThatThrownBy(() -> service.activate(key))
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        assertThat(jobJson(job)).isEqualTo(before);
        assertThat(events(job)).isZero();
    }

    @Test
    void malformedGradedFixtureCannotBeFalselyInputRejected() {
        jdbc.update(
                "UPDATE public.review_snapshot SET"
                    + " payload=jsonb_set(payload,'{resources,gradeSamples,0,inputData,report}',jsonb_build_object('method',NULL))"
                    + " WHERE id=?",
                fixture.snapshot());
        assertThatThrownBy(() -> service.activate(key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state(job)).isEqualTo("STAGED");
        assertThat(events(job)).isZero();
    }

    @Test
    void wrongManagerAndEnclosingTransactionAreRejected() {
        var other =
                new DataSourceTransactionManager(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()));
        assertThatThrownBy(
                        () ->
                                new GradeCoordinatorService(
                                        jdbc,
                                        other,
                                        credentials,
                                        Map.of(fixture.code(), fixture.installation()),
                                        "TEST_COORDINATOR",
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(fixture, 0)).isInstanceOf(IllegalArgumentException.class);
        new TransactionTemplate(manager)
                .execute(
                        status -> {
                            assertThatThrownBy(() -> service.activate(key))
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessage("COORDINATOR_REQUIRES_SEPARATE_TRANSACTION");
                            assertThatThrownBy(() -> service(fixture, 1))
                                    .isInstanceOf(IllegalStateException.class);
                            return null;
                        });
        assertThat(state(job)).isEqualTo("STAGED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "updateFailure",
                "auditFailure",
                "sample",
                "repeat",
                "identity",
                "clock",
                "output",
                "auditIdentity",
                "delay",
                "revoke"
            })
    void actualSqlErrorsAndTriggerMutationRollBackJobAndAudit(String fault) {
        String body =
                switch (fault) {
                    case "updateFailure", "auditFailure" -> "RAISE EXCEPTION 'PRIVATE_SQL_CANARY';";
                    case "sample" -> "NEW.sample_code='ZERO';";
                    case "repeat" -> "NEW.repeat_no=2;";
                    case "identity" -> "NEW.job_key=gen_random_uuid();";
                    case "clock" ->
                            "NEW.accepted_at=NEW.accepted_at+interval '1 second';"
                                    + " NEW.deadline_at=NEW.deadline_at+interval '1 second';";
                    case "output" -> "NEW.result_data='{}'::jsonb;";
                    case "auditIdentity" -> "NEW.command_hash=repeat('b',64);";
                    case "delay" ->
                            "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval"
                                    + " '24 hours' WHERE id="
                                    + fixture.batch()
                                    + "; PERFORM pg_sleep(0.05);";
                    case "revoke" ->
                            "UPDATE public.story_access SET active_yn=false WHERE story_id="
                                    + fixture.story()
                                    + ";";
                    default -> throw new AssertionError(fault);
                };
        boolean onAudit = fault.equals("auditFailure") || fault.equals("auditIdentity");
        trigger(onAudit, body);
        String before = jobJson(job);
        assertThatThrownBy(() -> service.activate(key))
                .isInstanceOfAny(
                        IllegalStateException.class,
                        org.springframework.dao.DataAccessResourceFailureException.class)
                .hasMessageNotContaining("PRIVATE_SQL_CANARY")
                .hasCause(null);
        assertThat(jobJson(job)).isEqualTo(before);
        assertThat(events(job)).isZero();
        assertThat(attempts(job)).isZero();
    }

    @Test
    void inputErrorAuditFailureRollsBackSafeResultAndAdmission() {
        UUID input = UUID.randomUUID();
        long inputId = staged(fixture, input, "INPUT", 1);
        trigger(true, "RAISE EXCEPTION 'PRIVATE_REPORT_CANARY';");
        String before = jobJson(inputId);
        assertThatThrownBy(() -> service.activate(input))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
                .hasCause(null);
        assertThat(jobJson(inputId)).isEqualTo(before);
        assertThat(events(inputId)).isZero();
    }

    @Test
    void actualDelayPastUnchangedBatchExpiryRollsBackAdmission() {
        trigger(false, "PERFORM pg_sleep(2.1);");
        jdbc.update(
                "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval '24"
                        + " hours'+interval '2 seconds' WHERE id=?",
                fixture.batch());
        String before = jobJson(job);
        assertThatThrownBy(() -> service.activate(key))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
                .hasCause(null);
        assertThat(jobJson(job)).isEqualTo(before);
        assertThat(events(job)).isZero();
    }

    @Test
    void completedInputErrorRechecksActualAuthorizationAfterTrigger() {
        UUID input = UUID.randomUUID();
        long inputId = staged(fixture, input, "INPUT", 1);
        trigger(
                true,
                "UPDATE public.admin_account SET can_review=false WHERE id="
                        + fixture.creator()
                        + ";");
        String before = jobJson(inputId);
        assertThatThrownBy(() -> service.activate(input))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(jobJson(inputId)).isEqualTo(before);
        assertThat(events(inputId)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT can_review FROM public.admin_account WHERE id=?",
                                Boolean.class,
                                fixture.creator()))
                .isTrue();
    }

    @Test
    void currentSnapshotReplacementIsActualRevocationNotHashFallback() {
        long replacement =
                id(
                        "INSERT INTO"
                            + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " SELECT version_id,edit_rev,payload,?,created_by FROM"
                            + " public.review_snapshot WHERE id=? RETURNING id",
                        UUID.randomUUID(),
                        fixture.snapshot());
        jdbc.update(
                "UPDATE public.story_version SET current_snapshot_id=? WHERE id=?",
                replacement,
                fixture.version());
        var result = service.activate(key);
        assertThat(result.state()).isEqualTo("CANCELLED");
        assertThat(result.reason()).isEqualTo("SOURCE_REVOKED");
        assertThat(result.acceptedAt()).isNull();
    }

    @Test
    void availabilityPurposeAllowsReadyButDoesNotPublishOrCompleteProviderFixture() {
        jdbc.update(
                "UPDATE public.grade_batch SET purpose='AVAILABILITY' WHERE id=?", fixture.batch());
        jdbc.update("UPDATE public.story_version SET status='READY' WHERE id=?", fixture.version());
        assertThat(service.activate(key).state()).isEqualTo("QUEUED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_id IS NULL AND NOT view_yn FROM public.story"
                                        + " WHERE id=?",
                                Boolean.class,
                                fixture.story()))
                .isTrue();
    }

    @Test
    void emptyWorkerRegistryDoesNotInventWorkerAuthorizationForServerAdmission() {
        var empty = new GradeWorkerCredentials(List.of());
        var coordinator =
                new GradeCoordinatorService(
                        jdbc,
                        manager,
                        empty,
                        Map.of(fixture.code(), fixture.installation()),
                        "TEST_COORDINATOR",
                        1);
        assertThat(coordinator.activate(key).state()).isEqualTo("QUEUED");
        assertThatThrownBy(
                        () ->
                                empty.authenticate(
                                        "Bearer "
                                                + Base64.getUrlEncoder()
                                                        .withoutPadding()
                                                        .encodeToString(secret)))
                .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "generation", "result"})
    void stagedPriorBudgetOrOutputIsNeverRefundedOrReplaced(String corruption) {
        switch (corruption) {
            case "count" -> jdbc.update("UPDATE public.grade_job SET call_count=1 WHERE id=?", job);
            case "generation" ->
                    jdbc.update("UPDATE public.grade_job SET lease_gen=1 WHERE id=?", job);
            case "result" ->
                    jdbc.update(
                            "UPDATE public.grade_job SET result_data='{}'::jsonb,result_hash=?"
                                    + " WHERE id=?",
                            "b".repeat(64),
                            job);
            default -> throw new AssertionError(corruption);
        }
        String before = jobJson(job);
        assertThatThrownBy(() -> service.activate(key))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(jobJson(job)).isEqualTo(before);
        assertThat(events(job)).isZero();
    }

    @Test
    void clockIsReadAfterHeldRuntimeRootInsteadOfTransactionStart() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    status -> {
                                                        jdbc.queryForList(
                                                                "SELECT id FROM"
                                                                    + " public.grade_runtime WHERE"
                                                                    + " id=? FOR UPDATE",
                                                                fixture.runtime());
                                                        held.countDown();
                                                        try {
                                                            if (!release.await(5, TimeUnit.SECONDS))
                                                                throw new AssertionError(
                                                                        "root release timeout");
                                                        } catch (InterruptedException failure) {
                                                            Thread.currentThread().interrupt();
                                                            throw new AssertionError(failure);
                                                        }
                                                        return null;
                                                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var activating = pool.submit(() -> service.activate(key));
            // A DB clock read immediately before releasing the held root is a lower admission
            // bound.
            OffsetDateTime bound =
                    jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(activating.get(10, TimeUnit.SECONDS).acceptedAt()).isAfterOrEqualTo(bound);
        }
    }

    /** 신규 실제 물리 부모와 검증 가능한 전체 사본을 생성한다. 일반 DB는 사용하지 않는다. */
    private Fixture fixture() {
        String code = token("COORD");
        var dictionary = new GradeDictionary(code, List.of(new Term("ONE", "개념", "합성")));
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:9"),
                        "qwen3:8b",
                        "a".repeat(64),
                        dictionary.sha256(),
                        "{{ .System }}{{ .Prompt }}{{ .Response }}",
                        65536,
                        4096,
                        0,
                        1,
                        false,
                        Duration.ofSeconds(30));
        var installation =
                new InstalledRuntimeManifestVerifier(
                        new LocalSemanticEngine(settings),
                        dictionary,
                        new InstalledProfile(code, "LOCAL", "1", "RULE_20260924"));
        long runtime =
                new GradeRuntimeRepository(jdbc)
                        .registerRuntime(
                                code,
                                installation.configHash(),
                                installation.registrationManifest().toString())
                        .id();
        long creator =
                id(
                        "INSERT INTO public.admin_account(account_key,can_review,can_manage) VALUES"
                                + " (?,true,true) RETURNING id",
                        UUID.randomUUID());
        jdbc.update(
                """
                INSERT INTO public.admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)
                VALUES (?,'fixture-only',?,'fixture-only','fixture-only',clock_timestamp(),0,clock_timestamp(),'READY')
                """,
                creator,
                ByteBuffer.allocate(32).putLong(creator).array());
        String storyCode = token("STORY");
        long story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        storyCode,
                        creator);
        long version =
                id(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'접수 합성','RULE_20260924',?,?) RETURNING id",
                        story,
                        creator,
                        creator);
        ObjectNode payload = FrozenSnapshotContractTest.complete();
        payload.put("storyCode", storyCode);
        ObjectNode malformed =
                (ObjectNode)
                        payload.get("resources")
                                .get("gradeSamples")
                                .get(2)
                                .get("inputData")
                                .get("report");
        malformed.put("method", CANARY);
        FrozenSnapshot frozen = FrozenSnapshotCodec.freeze(payload);
        long snapshot =
                id(
                        "INSERT INTO"
                            + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,?::jsonb,?,?) RETURNING id",
                        version,
                        new String(SnapshotJson.encode(frozen.payload()), StandardCharsets.UTF_8),
                        UUID.randomUUID(),
                        creator);
        jdbc.update(
                "UPDATE public.story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?",
                snapshot,
                version);
        jdbc.update(
                "INSERT INTO public.story_access(story_id,admin_id,permission,granted_by) VALUES"
                        + " (?,?,'REVIEW',?)",
                story,
                creator,
                creator);
        long batch =
                id(
                        """
                        INSERT INTO public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)
                        VALUES (?,?,?,'REVIEW',?,?,?,?,0,'RUNNING',12,?) RETURNING id
                        """,
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        frozen.datasetHash(),
                        frozen.rubricHash(),
                        frozen.payloadHash(),
                        installation.configHash(),
                        creator);
        return new Fixture(
                code, installation, runtime, creator, story, version, snapshot, batch, frozen);
    }

    private GradeCoordinatorService service(Fixture source, int capacity) {
        return new GradeCoordinatorService(
                jdbc,
                manager,
                credentials,
                Map.of(source.code(), source.installation()),
                "TEST_COORDINATOR",
                capacity);
    }

    private long staged(Fixture source, UUID key, String sample, int repeat) {
        return id(
                """
                INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)
                VALUES (?,?,?,?,?,?,'STAGED',?,?,?) RETURNING id
                """,
                key,
                source.snapshot(),
                source.runtime(),
                source.batch(),
                sample,
                repeat,
                source.frozen().inputHash(sample),
                source.installation().configHash(),
                source.frozen().rubricHash());
    }

    private void trigger(boolean event, String body) {
        jdbc.execute(
                "CREATE FUNCTION public.coordinator_fault() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN "
                        + body
                        + " RETURN NEW; END $$");
        jdbc.execute(
                event
                        ? "CREATE TRIGGER coordinator_event_fault BEFORE INSERT ON"
                                + " public.grade_event FOR EACH ROW EXECUTE FUNCTION"
                                + " public.coordinator_fault()"
                        : "CREATE TRIGGER coordinator_job_fault BEFORE UPDATE ON public.grade_job"
                                + " FOR EACH ROW WHEN (OLD.state='STAGED') EXECUTE FUNCTION"
                                + " public.coordinator_fault()");
    }

    private void assertSystemEvent(long id, String kind, String after, String reason) {
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT actor_kind='SYSTEM' AND actor_key='TEST_COORDINATOR' AND attempt_no IS NULL AND request_id IS NULL
                                    AND event_kind=? AND detail->>'beforeJob'='STAGED' AND detail->>'afterJob'=? AND detail->>'reason'=?
                                    AND detail->'beforeAttempt'='null'::jsonb AND detail->'afterAttempt'='null'::jsonb
                                FROM public.grade_event WHERE job_id=?
                                """,
                                Boolean.class,
                                kind,
                                after,
                                reason,
                                id))
                .isTrue();
    }

    private static String token(String prefix) {
        return prefix
                + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    private static long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static long attempts(long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.grade_attempt WHERE job_id=?", Long.class, id);
    }

    private static long events(long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.grade_event WHERE job_id=?", Long.class, id);
    }

    private static String state(long id) {
        return jdbc.queryForObject(
                "SELECT state FROM public.grade_job WHERE id=?", String.class, id);
    }

    private static String jobJson(long id) {
        return jdbc.queryForObject(
                "SELECT to_jsonb(j)::text FROM public.grade_job j WHERE id=?", String.class, id);
    }

    private static String eventJson(long id) {
        return jdbc.queryForObject(
                "SELECT jsonb_agg(to_jsonb(e))::text FROM public.grade_event e WHERE job_id=?",
                String.class,
                id);
    }

    private record Fixture(
            String code,
            InstalledRuntimeManifestVerifier installation,
            long runtime,
            long creator,
            long story,
            long version,
            long snapshot,
            long batch,
            FrozenSnapshot frozen) {}
}
