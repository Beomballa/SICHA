package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.CryptoService;
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
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.GradeCompletionService;
import com.reasoning.common.grading.service.GradeRecoveryService;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 폐기형 실제 V17 PostgreSQL·전체 사본·설치·START를 사용하는 만료 복구 시험이다. 실제 추론은 하지 않는다. */
class GradeRecoveryServiceIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static InstalledRuntimeManifestVerifier installation;
    private static long runtime;
    private static CryptoService crypto;
    private static final String CODE = "RECOVERY_SYNTHETIC";
    private GradeWorkerCredentials credentials;
    private VerifiedWorker worker;
    private GradeRecoveryService service;
    private GradeStartService starter;
    private long creator;
    private long story;
    private long version;
    private long snapshot;
    private long batch;
    private long job;
    private UUID key;
    private String workerKey;

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
                .isEqualTo(19);
        var dictionary = new GradeDictionary(CODE, List.of(new Term("ONE", "개념", "합성")));
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
        installation =
                new InstalledRuntimeManifestVerifier(
                        new LocalSemanticEngine(settings),
                        dictionary,
                        new InstalledProfile(CODE, "LOCAL", "1", "RULE_20260924"));
        runtime =
                new GradeRuntimeRepository(jdbc)
                        .registerRuntime(
                                CODE,
                                installation.configHash(),
                                installation.registrationManifest().toString())
                        .id();
        var properties = new AuthProperties();
        properties.setCryptoKeyFile(TestKeys.create((byte) 31));
        properties.setSearchKeyFile(TestKeys.create((byte) 32));
        properties.setLimitKeyFile(TestKeys.create((byte) 33));
        crypto = new CryptoService(properties);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    @AfterEach
    void retireQueuedFixture() {
        jdbc.update(
                "UPDATE public.grade_job SET state='CANCELLED' WHERE id=? AND state='QUEUED'", job);
    }

    /** 신규 fixture는 claim 상태이며 호출 예약은 각 시험에서 실제 START로만 소비한다. */
    @BeforeEach
    void fixture() {
        jdbc.update(
                "UPDATE public.grade_runtime SET"
                    + " state='AVAILABLE',epoch=0,config_hash=?,config_data=?::jsonb WHERE id=?",
                installation.configHash(),
                installation.registrationManifest().toString(),
                runtime);
        workerKey = token("WORKER");
        byte[] secret =
                ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(UUID.randomUUID().getLeastSignificantBits())
                        .putLong(1)
                        .putLong(2)
                        .array();
        credentials =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        CommonUtil.sha256(secret),
                                        Set.of(CODE),
                                        Set.of(Action.START, Action.COMPLETE))));
        worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        service =
                new GradeRecoveryService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of(CODE, installation),
                        "RECOVERY_COORDINATOR");
        starter = new GradeStartService(jdbc, manager, credentials, Map.of(CODE, installation));
        creator =
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
        String code = token("STORY");
        story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        code,
                        creator);
        version =
                id(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'복구 합성','RULE_20260924',?,?) RETURNING id",
                        story,
                        creator,
                        creator);
        ObjectNode payload = FrozenSnapshotContractTest.complete();
        payload.put("storyCode", code);
        var frozen = FrozenSnapshotCodec.freeze(payload);
        snapshot =
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
        batch =
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
        key = UUID.randomUUID();
        job =
                id(
                        """
                        WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                        INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash,accepted_at,deadline_at,worker_key,lease_gen,lease_until)
                        SELECT ?,?,?,?,'FULL',1,'RUNNING',?,?,?,t.n,t.n+interval '120 seconds',?,1,t.n+interval '30 seconds' FROM t RETURNING id
                        """,
                        key,
                        snapshot,
                        runtime,
                        batch,
                        frozen.inputHash("FULL"),
                        installation.configHash(),
                        frozen.rubricHash(),
                        workerKey);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void expiredReservationPreservesBudgetAndNeverFabricatesObservation(int count) {
        reserve(count);
        expire();
        var before = row();
        var result = service.recover(key);
        assertThat(result.changed()).isTrue();
        assertThat(result.state()).isEqualTo(count == 3 ? "FAILED" : "QUEUED");
        assertThat(result.attemptNo()).isEqualTo(count == 0 ? null : count);
        assertThat(result.reason()).isEqualTo(count == 0 ? "STALE_LEASE" : "WORKER_LOST");
        assertBudget(before);
        assertThat(row().get("worker_key")).isNull();
        assertThat(row().get("lease_until")).isNull();
        assertThat(row().get("result_data")).isNull();
        assertThat(row().get("result_cipher")).isNull();
        assertThat(row().get("result_hash")).isNull();
        var events = events();
        assertThat(events).hasSize(Math.max(1, count));
        assertThat(events.getLast().get("actor_kind")).isEqualTo("SYSTEM");
        assertThat(events.getLast().get("actor_key")).isEqualTo("RECOVERY_COORDINATOR");
        assertThat(events.getLast().get("request_id")).isNull();
        assertThat(events.getLast().get("event_kind"))
                .isEqualTo(count == 0 ? "JOB_LEASE_RECLAIMED" : "RECOVERY_EXPIRED");
        if (count > 0) {
            var attempt = attempt(count);
            assertThat(attempt.get("state")).isEqualTo("FAILED");
            assertThat(attempt.get("error_code")).isEqualTo("WORKER_LOST");
            assertThat(attempt.get("ended_at")).isNotNull();
            for (String field :
                    List.of(
                            "completion_data",
                            "observed_version",
                            "provider_ref",
                            "output_cipher",
                            "output_hash")) assertThat(attempt.get(field)).as(field).isNull();
        }
        assertThat(service.recover(key).changed()).isFalse();
        assertThat(events()).isEqualTo(events);
        if (count < 3) {
            var lease =
                    new GradeLeaseRepository(jdbc, manager).claim(workerKey, CODE).orElseThrow();
            assertThat(lease.leaseGen()).isEqualTo(count == 0 ? 2 : count + 1);
            assertThat(starter.start(worker, key, lease.leaseGen()).attemptNo())
                    .isEqualTo(count + 1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void liveLeaseIncludingThirdReservationIsNotFailedOrAudited(int count) {
        reserve(count);
        var before = row();
        var audit = events();
        assertThat(service.recover(key).changed()).isFalse();
        assertThat(row()).isEqualTo(before);
        if (count > 0) assertThat(attempt(count).get("state")).isEqualTo("RUNNING");
        assertThat(events()).isEqualTo(audit);
    }

    @Test
    void unreservedRecoveryDoesNotSpendCallOrCreateReceiptAndReplayIsNoOp() {
        expire();
        var before = row();
        var result = service.recover(key);
        assertThat(result.changed()).isTrue();
        assertThat(result.state()).isEqualTo("QUEUED");
        assertThat(result.attemptNo()).isNull();
        assertThat(result.reason()).isEqualTo("STALE_LEASE");
        assertBudget(before);
        var after = row();
        var audit = events();
        assertThat(service.recover(key).changed()).isFalse();
        assertThat(row()).isEqualTo(after);
        assertThat(events()).isEqualTo(audit);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_attempt WHERE job_id=?",
                                Integer.class,
                                job))
                .isZero();
    }

    @Test
    void revokedUnreservedLeaseUsesAccurateLeaseReclaimedEventWithoutAttempt() {
        expire();
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        var before = row();
        var result = service.recover(key);
        assertThat(result.state()).isEqualTo("CANCELLED");
        assertThat(result.reason()).isEqualTo("SOURCE_REVOKED");
        assertThat(result.attemptNo()).isNull();
        assertBudget(before);
        assertThat(events().getFirst().get("event_kind")).isEqualTo("JOB_LEASE_RECLAIMED");
        assertThat(events().getFirst().get("attempt_no")).isNull();
        var after = row();
        var audit = events();
        assertThat(service.recover(key).changed()).isFalse();
        assertThat(row()).isEqualTo(after);
        assertThat(events()).isEqualTo(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deadline", "batch", "epoch"})
    void unreservedTerminalMaintenanceNeverCreatesAttempt(String change) {
        expire();
        switch (change) {
            case "deadline" ->
                    jdbc.update(
                            "WITH t AS MATERIALIZED(SELECT clock_timestamp() n) UPDATE"
                                + " public.grade_job SET accepted_at=t.n-interval '120"
                                + " seconds',deadline_at=t.n,lease_until=t.n-interval '1 second'"
                                + " FROM t WHERE id=?",
                            job);
            case "batch" ->
                    jdbc.update(
                            "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval"
                                    + " '24 hours' WHERE id=?",
                            batch);
            case "epoch" ->
                    jdbc.update(
                            "UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=?", runtime);
            default -> throw new AssertionError(change);
        }
        var before = row();
        var result = service.recover(key);
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.attemptNo()).isNull();
        assertBudget(before);
        assertThat(events().getFirst().get("event_kind")).isEqualTo("JOB_LEASE_RECLAIMED");
        assertThat(events().getFirst().get("request_id")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_attempt WHERE job_id=?",
                                Integer.class,
                                job))
                .isZero();
        assertThat(row().get("result_data")).isNull();
    }

    @Test
    void differentGenerationUnreservedReclaimPreservesPriorActualCompletionReceipt() {
        reserve(1);
        var completer =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of(CODE, installation),
                        crypto,
                        "RECOVERY_COORDINATOR");
        completer.complete(
                worker,
                key,
                1,
                1,
                null,
                null,
                "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                UUID.randomUUID());
        var original = attempt(1);
        var lease = new GradeLeaseRepository(jdbc, manager).claim(workerKey, CODE).orElseThrow();
        assertThat(lease.leaseGen()).isEqualTo(2);
        expire();
        var before = row();
        var result = service.recover(key);
        assertThat(result.attemptNo()).isNull();
        assertThat(result.reason()).isEqualTo("STALE_LEASE");
        assertThat(result.state()).isEqualTo("QUEUED");
        assertBudget(before);
        assertThat(attempt(1)).isEqualTo(original);
        assertThat(events().getLast().get("event_kind")).isEqualTo("JOB_LEASE_RECLAIMED");
        assertThat(events().getLast().get("attempt_no")).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "epoch,FAILED,RUNTIME_EPOCH_CHANGED",
        "unavailable,CANCELLED,SOURCE_REVOKED",
        "creator,CANCELLED,SOURCE_REVOKED",
        "credential,CANCELLED,SOURCE_REVOKED",
        "enrollment,CANCELLED,SOURCE_REVOKED",
        "global,CANCELLED,SOURCE_REVOKED",
        "scope,CANCELLED,SOURCE_REVOKED",
        "story,CANCELLED,SOURCE_REVOKED",
        "version,CANCELLED,SOURCE_REVOKED",
        "snapshot,CANCELLED,SOURCE_REVOKED",
        "purpose,CANCELLED,SOURCE_REVOKED",
        "deadline,FAILED,DEADLINE_EXCEEDED",
        "batch,FAILED,DEADLINE_EXCEEDED"
    })
    void factualRevocationTerminatesWithoutScores(String change, String state, String reason) {
        reserve(1);
        expire();
        switch (change) {
            case "epoch" ->
                    jdbc.update(
                            "UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=?", runtime);
            case "unavailable" ->
                    jdbc.update(
                            "UPDATE public.grade_runtime SET state='SUSPENDED' WHERE id=?",
                            runtime);
            case "creator" ->
                    jdbc.update(
                            "UPDATE public.admin_account SET active_yn=false WHERE id=?", creator);
            case "credential" ->
                    jdbc.update(
                            "UPDATE public.admin_credential SET mfa_state='RECOVERY' WHERE"
                                    + " account_id=?",
                            creator);
            case "enrollment" ->
                    jdbc.update(
                            "UPDATE public.admin_credential SET"
                                    + " enrolled_at=NULL,mfa_state='PENDING' WHERE account_id=?",
                            creator);
            case "global" ->
                    jdbc.update(
                            "UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
            case "scope" ->
                    jdbc.update(
                            "UPDATE public.story_access SET active_yn=false WHERE story_id=?",
                            story);
            case "story" ->
                    jdbc.update("UPDATE public.story SET active_yn=false WHERE id=?", story);
            case "version" ->
                    jdbc.update(
                            "UPDATE public.story_version SET active_yn=false WHERE id=?", version);
            case "snapshot" -> {
                long replacement =
                        id(
                                "INSERT INTO"
                                    + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                                    + " SELECT version_id,edit_rev,payload,?,created_by FROM"
                                    + " public.review_snapshot WHERE id=? RETURNING id",
                                UUID.randomUUID(),
                                snapshot);
                jdbc.update(
                        "UPDATE public.story_version SET current_snapshot_id=? WHERE id=?",
                        replacement,
                        version);
            }
            case "purpose" ->
                    jdbc.update(
                            "UPDATE public.story_version SET status='READY' WHERE id=?", version);
            case "deadline" ->
                    jdbc.update(
                            "WITH t AS MATERIALIZED(SELECT clock_timestamp() n) UPDATE"
                                + " public.grade_job SET accepted_at=t.n-interval '120"
                                + " seconds',deadline_at=t.n,lease_until=t.n-interval '1 second'"
                                + " FROM t WHERE id=?",
                            job);
            case "batch" ->
                    jdbc.update(
                            "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval"
                                    + " '24 hours' WHERE id=?",
                            batch);
            default -> throw new AssertionError(change);
        }
        var before = row();
        var result = service.recover(key);
        assertThat(result.state()).isEqualTo(state);
        assertThat(result.reason()).isEqualTo(reason);
        assertBudget(before);
        assertThat(attempt(1).get("completion_data")).isNull();
        assertThat(row().get("result_data")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "runtime", "hash", "config", "rubric", "policy", "payload"})
    void IntegrityFailuresDoNotBecomePermissionFallback(String change) {
        reserve(1);
        expire();
        switch (change) {
            case "missing" ->
                    service =
                            new GradeRecoveryService(
                                    jdbc, manager, credentials, Map.of(), "RECOVERY_COORDINATOR");
            case "runtime" ->
                    jdbc.update(
                            "UPDATE public.grade_runtime SET config_data='{}'::jsonb WHERE id=?",
                            runtime);
            case "hash" ->
                    jdbc.update(
                            "UPDATE public.grade_job SET input_hash=? WHERE id=?",
                            "b".repeat(64),
                            job);
            case "config" ->
                    jdbc.update(
                            "UPDATE public.grade_job SET config_hash=? WHERE id=?",
                            "b".repeat(64),
                            job);
            case "rubric" ->
                    jdbc.update(
                            "UPDATE public.grade_job SET rubric_hash=? WHERE id=?",
                            "b".repeat(64),
                            job);
            case "policy" ->
                    jdbc.update(
                            "UPDATE public.story_version SET policy_code='H2' WHERE id=?", version);
            case "payload" ->
                    jdbc.update(
                            "UPDATE public.review_snapshot SET payload='{}'::jsonb WHERE id=?",
                            snapshot);
            default -> throw new AssertionError(change);
        }
        assertRollbackFailure(false);
    }

    @Test
    void oldCompletionIsFencedWithoutWorkerReceipt() {
        reserve(1);
        expire();
        service.recover(key);
        var completer =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of(CODE, installation),
                        crypto,
                        "RECOVERY_COORDINATOR");
        var receipt =
                completer.complete(
                        worker,
                        key,
                        1,
                        1,
                        null,
                        null,
                        "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                        UUID.randomUUID());
        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.reason()).isEqualTo("STALE_LEASE");
        assertThat(attempt(1).get("completion_data")).isNull();
        assertThat(row().get("result_data")).isNull();
        assertThat(row().get("call_count")).isEqualTo(1);
    }

    @Test
    void priorCompletedReceiptAndEventsArePreserved() {
        reserve(1);
        var completer =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of(CODE, installation),
                        crypto,
                        "RECOVERY_COORDINATOR");
        completer.complete(
                worker,
                key,
                1,
                1,
                null,
                null,
                "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                UUID.randomUUID());
        var before = row();
        var child = attempt(1);
        var audit = events();
        assertThat(service.recover(key).changed()).isFalse();
        assertThat(row()).isEqualTo(before);
        assertThat(attempt(1)).isEqualTo(child);
        assertThat(events()).isEqualTo(audit);
    }

    @Test
    void concurrentRecoveryAppliesOnce() throws Exception {
        reserve(1);
        expire();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(1);
            var first =
                    executor.submit(
                            () -> {
                                ready.await();
                                return service.recover(key);
                            });
            var second =
                    executor.submit(
                            () -> {
                                ready.await();
                                return service.recover(key);
                            });
            ready.countDown();
            assertThat(
                            List.of(
                                    first.get(10, TimeUnit.SECONDS).changed(),
                                    second.get(10, TimeUnit.SECONDS).changed()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(events()).hasSize(1);
    }

    @Test
    void completionAndRecoveryRaceClosesReservationOnceWithoutScores() throws Exception {
        reserve(1);
        expire();
        var completer =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of(CODE, installation),
                        crypto,
                        "RECOVERY_COORDINATOR");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(1);
            var recovery =
                    executor.submit(
                            () -> {
                                ready.await();
                                return service.recover(key);
                            });
            var completion =
                    executor.submit(
                            () -> {
                                ready.await();
                                return completer.complete(
                                        worker,
                                        key,
                                        1,
                                        1,
                                        null,
                                        null,
                                        "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                                        UUID.randomUUID());
                            });
            ready.countDown();
            assertThat(recovery.get(10, TimeUnit.SECONDS).changed()).isTrue();
            assertThat(completion.get(10, TimeUnit.SECONDS).accepted()).isFalse();
        }
        assertThat(row().get("state")).isEqualTo("QUEUED");
        assertThat(row().get("call_count")).isEqualTo(1);
        assertThat(attempt(1).get("state")).isEqualTo("FAILED");
        assertThat(attempt(1).get("completion_data")).isNull();
        assertThat(row().get("result_data")).isNull();
        assertThat(
                        events().stream()
                                .filter(event -> "RECOVERY_EXPIRED".equals(event.get("event_kind")))
                                .count())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"grade_event,INSERT", "grade_job,UPDATE", "grade_attempt,UPDATE"})
    void storageFailureRollsBackAllThreeWrites(String table, String operation) {
        reserve(1);
        expire();
        trigger(table, operation, "RAISE EXCEPTION 'RAW_RECOVERY_STORAGE_CANARY';");
        try {
            assertRollbackFailure(true);
        } finally {
            dropTrigger(table);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sample",
                "repeat",
                "source",
                "scope",
                "receipt",
                "event",
                "deadline",
                "epoch",
                "input",
                "provider",
                "payload",
                "ended",
                "delay"
            })
    void auditTriggerMutationAndDelayCannotCommit(String mutation) {
        reserve(1);
        expire();
        String body =
                switch (mutation) {
                    case "sample" ->
                            "UPDATE public.grade_job SET sample_code='OTHER' WHERE id=" + job + ";";
                    case "repeat" ->
                            "UPDATE public.grade_job SET repeat_no=2 WHERE id=" + job + ";";
                    case "source" ->
                            "UPDATE public.admin_account SET can_review=false WHERE id="
                                    + creator
                                    + ";";
                    case "scope" ->
                            "UPDATE public.story_access SET active_yn=false WHERE story_id="
                                    + story
                                    + ";";
                    case "receipt" ->
                            "UPDATE public.grade_attempt SET completion_data='{}'::jsonb WHERE"
                                    + " job_id="
                                    + job
                                    + ";";
                    case "event" -> "NEW.actor_key := 'FORGED';";
                    case "deadline" ->
                            "UPDATE public.grade_job SET accepted_at=accepted_at-interval '1"
                                + " second',deadline_at=deadline_at-interval '1 second' WHERE id="
                                    + job
                                    + ";";
                    case "epoch" ->
                            "UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id="
                                    + runtime
                                    + ";";
                    case "input" ->
                            "UPDATE public.grade_job SET input_hash='"
                                    + "b".repeat(64)
                                    + "' WHERE id="
                                    + job
                                    + ";";
                    case "provider" ->
                            "UPDATE public.grade_attempt SET provider_ref='FAKE_RECEIPT' WHERE"
                                    + " job_id="
                                    + job
                                    + ";";
                    case "payload" ->
                            "UPDATE public.review_snapshot SET payload='{}'::jsonb WHERE id="
                                    + snapshot
                                    + ";";
                    case "ended" ->
                            "UPDATE public.grade_attempt SET ended_at=ended_at+interval '1 second'"
                                    + " WHERE job_id="
                                    + job
                                    + ";";
                    case "delay" -> "PERFORM pg_sleep(1.1);";
                    default -> throw new AssertionError(mutation);
                };
        if ("delay".equals(mutation)) nearDeadline();
        trigger("grade_event", "INSERT", body);
        try {
            assertRollbackFailure(true);
        } finally {
            dropTrigger("grade_event");
        }
    }

    @Test
    void enclosingAndWrongDataSourceTransactionsAreRejected() {
        new TransactionTemplate(manager)
                .execute(
                        status -> {
                            assertThatThrownBy(() -> service.recover(key))
                                    .hasMessage("RECOVERY_REQUIRES_SEPARATE_TRANSACTION")
                                    .hasNoCause();
                            assertThatThrownBy(
                                            () ->
                                                    new GradeRecoveryService(
                                                            jdbc,
                                                            manager,
                                                            credentials,
                                                            Map.of(CODE, installation),
                                                            "RECOVERY_COORDINATOR"))
                                    .hasMessage("RECOVERY_REQUIRES_SEPARATE_TRANSACTION")
                                    .hasNoCause();
                            return null;
                        });
        var other = new DataSourceTransactionManager(GradeSchemaIT.jdbc(postgres).getDataSource());
        assertThatThrownBy(
                        () ->
                                new GradeRecoveryService(
                                        jdbc,
                                        other,
                                        credentials,
                                        Map.of(CODE, installation),
                                        "RECOVERY_COORDINATOR"))
                .hasMessage("INVALID_RECOVERY_TRANSACTION_MANAGER")
                .hasNoCause();
        assertThatThrownBy(() -> service.recover(null))
                .hasMessage("INVALID_RECOVERY_INPUT")
                .hasNoCause();
        assertThatThrownBy(() -> service.recover(new UUID(0, 0)))
                .hasMessage("INVALID_RECOVERY_INPUT")
                .hasNoCause();
        assertThat(events()).isEmpty();
    }

    @Test
    void rootWaitUsesFreshClockAndDoesNotHoldJobBeforeAccount() throws Exception {
        reserve(1);
        nearDeadline();
        try (var connection = postgres.createConnection("");
                var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM public.admin_account WHERE id=? FOR UPDATE")) {
                statement.setLong(1, creator);
                statement.executeQuery().close();
            }
            var future = executor.submit(() -> service.recover(key));
            awaitRootWait();
            // recovery는 아직 account 대기 중이다. 같은 TX가 하위 job을 NOWAIT로 잠글 수 있다.
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM public.grade_job WHERE id=? FOR UPDATE NOWAIT")) {
                statement.setLong(1, job);
                statement.executeQuery().close();
            }
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_sleep(1.1)");
            }
            connection.commit();
            assertThat(future.get(10, TimeUnit.SECONDS).reason()).isEqualTo("DEADLINE_EXCEEDED");
        }
    }

    /** 2·3회는 앞선 실제 예약을 실제 복구한 뒤 재claim하고 START하여 생성한다. */
    private void reserve(int count) {
        for (int number = 1; number <= count; number++) {
            assertThat(starter.start(worker, key, number).attemptNo()).isEqualTo(number);
            assertThat(starter.start(worker, key, number).replay()).isTrue();
            if (number < count) {
                expire();
                assertThat(service.recover(key).state()).isEqualTo("QUEUED");
                new GradeLeaseRepository(jdbc, manager).claim(workerKey, CODE).orElseThrow();
            }
        }
        // 앞선 예약의 진짜 복구 감사는 남기며 각 시험에서 새 전이 개수를 기준으로 비교한다.
    }

    private void expire() {
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '0.01 seconds'"
                        + " WHERE id=?",
                job);
    }

    private void nearDeadline() {
        jdbc.update(
                "WITH t AS MATERIALIZED(SELECT clock_timestamp() n) UPDATE public.grade_job SET"
                        + " accepted_at=t.n-interval '119.2 seconds',deadline_at=t.n+interval '0.8"
                        + " seconds',lease_until=t.n-interval '0.01 seconds' FROM t WHERE id=?",
                job);
    }

    private void assertBudget(Map<String, Object> before) {
        for (String field :
                List.of(
                        "call_count",
                        "lease_gen",
                        "accepted_at",
                        "deadline_at",
                        "input_hash",
                        "config_hash",
                        "rubric_hash"))
            assertThat(row().get(field)).as(field).isEqualTo(before.get(field));
    }

    private void assertRollbackFailure(boolean storage) {
        var before = row();
        var attempts =
                jdbc.queryForList(
                        "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no",
                        job);
        var audit = events();
        var assertion = assertThatThrownBy(() -> service.recover(key)).hasNoCause();
        if (storage) assertion.hasMessage("RECOVERY_STORAGE_FAILURE");
        assertThat(row()).isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY"
                                        + " attempt_no",
                                job))
                .isEqualTo(attempts);
        assertThat(events()).isEqualTo(audit);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job);
    }

    private Map<String, Object> attempt(int number) {
        return jdbc.queryForMap(
                "SELECT * FROM public.grade_attempt WHERE job_id=? AND attempt_no=?", job, number);
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList(
                "SELECT * FROM public.grade_event WHERE job_id=? ORDER BY id", job);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String token(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private void trigger(String table, String operation, String body) {
        jdbc.execute(
                "CREATE FUNCTION public.recovery_test_trigger() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$ BEGIN "
                        + body
                        + " RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER recovery_test_trigger BEFORE "
                        + operation
                        + " ON public."
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION public.recovery_test_trigger()");
    }

    private void dropTrigger(String table) {
        jdbc.execute("DROP TRIGGER recovery_test_trigger ON public." + table);
        jdbc.execute("DROP FUNCTION public.recovery_test_trigger()");
    }

    private void awaitRootWait() throws Exception {
        for (int index = 0; index < 100; index++) {
            if (Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                + " datname=current_database() AND wait_event_type='Lock' AND"
                                + " position('FROM public.admin_account WHERE id=' in query)>0)",
                            Boolean.class))) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("실제 루트 대기 미관찰");
    }
}
