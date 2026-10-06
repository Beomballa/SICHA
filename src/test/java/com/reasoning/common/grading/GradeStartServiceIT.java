package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeDictionary.Term;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.grading.service.GradeStartService.Reservation;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.util.CommonUtil;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 고정 PG16.10·V1~V13·전체 사본·설치·토큰으로 예약만 검사한다. 완료·실제 모델 품질 증거는 아니다. */
class GradeStartServiceIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static InstalledRuntimeManifestVerifier installation;
    private static long runtime;
    private static HttpServer server;
    private static final AtomicInteger requests = new AtomicInteger();
    private GradeWorkerCredentials credentials;
    private VerifiedWorker worker;
    private GradeStartService service;
    private FrozenSnapshot frozen;
    private long creator;
    private long story;
    private long version;
    private long snapshot;
    private long batch;
    private long job;
    private UUID jobKey;
    private String workerKey;
    private String header;

    /** 폐기형 고정 DB와 실제 설치 엔진을 생성하며 모든 설치 지문 계산은 SQL TX 밖에서 수행한다. */
    @BeforeAll
    static void open() throws Exception {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        var dictionary =
                new GradeDictionary("START_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
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
                        new InstalledProfile("START_SYNTHETIC", "LOCAL", "1", "RULE_20260924"));
        runtime =
                new GradeRuntimeRepository(jdbc)
                        .registerRuntime(
                                "START_SYNTHETIC",
                                installation.configHash(),
                                installation.registrationManifest().toString())
                        .id();
    }

    /** 합성 loopback에 start가 한 번도 요청하지 않았음을 검사한 뒤 자원을 종료한다. */
    @AfterAll
    static void close() {
        try {
            assertThat(requests.get()).isZero();
        } finally {
            if (server != null) server.stop(0);
            if (postgres != null) postgres.close();
        }
    }

    /** 기존 전체 합성 fixture를 그대로 재사용하되 실제 부모 식별자·JSONB·선언 해시를 결속한다. */
    @BeforeEach
    void fixtures() {
        workerKey = token("W");
        byte[] secret =
                ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(UUID.randomUUID().getLeastSignificantBits())
                        .putLong(1)
                        .putLong(2)
                        .array();
        header = "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        credentials = registry(workerKey, secret, "START_SYNTHETIC", Action.START);
        worker = credentials.authenticate(header);
        service = service(jdbc, credentials);
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
                ByteBuffer.allocate(32).putLong(creator).array());
        String code = token("ST");
        story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        code,
                        creator);
        version =
                id(
                        """
                        INSERT INTO public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'합성 전체 사본','RULE_20260924',?,?) RETURNING id
                        """,
                        story,
                        creator,
                        creator);
        ObjectNode complete = FrozenSnapshotContractTest.complete();
        complete.put("storyCode", code);
        frozen = FrozenSnapshotCodec.freeze(complete);
        snapshot =
                id(
                        """
                        INSERT INTO public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                        VALUES (?,0,?::jsonb,?,?) RETURNING id
                        """,
                        version,
                        new String(frozen.payloadBytes(), StandardCharsets.UTF_8),
                        UUID.randomUUID(),
                        creator);
        jdbc.update(
                "UPDATE public.story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?",
                snapshot,
                version);
        jdbc.update(
                "INSERT INTO public.story_access(story_id,admin_id,permission,granted_by)"
                        + " VALUES (?,?,'REVIEW',?)",
                story,
                creator,
                creator);
        batch =
                id(
                        """
                        INSERT INTO public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,
                            rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)
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
        jobKey = UUID.randomUUID();
        job =
                id(
                        """
                        WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                        INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,
                            repeat_no,state,input_hash,config_hash,rubric_hash,accepted_at,deadline_at,
                            worker_key,lease_gen,lease_until)
                        SELECT ?,?,?,?,'FULL',1,'RUNNING',?,?,?,t.n,t.n+interval '120 seconds',?,1,
                            t.n+interval '30 seconds' FROM t RETURNING id
                        """,
                        jobKey,
                        snapshot,
                        runtime,
                        batch,
                        frozen.inputHash("FULL"),
                        installation.configHash(),
                        frozen.rubricHash(),
                        workerKey);
    }

    @Test
    void firstReservationAndSameLeaseReplayRetainInputDeadlinesAndExactlyOneCall() {
        Reservation first = start();
        Map<String, Object> before = row();
        var attemptsBefore = attempts();
        Reservation replay = start();
        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.attemptNo()).isEqualTo(first.attemptNo()).isEqualTo(1);
        assertThat(replay.jobKey()).isEqualTo(jobKey);
        assertThat(replay.jobId()).isEqualTo(job);
        assertThat(replay.batchId()).isEqualTo(batch);
        assertThat(replay.snapshotId()).isEqualTo(snapshot);
        assertThat(replay.runtimeId()).isEqualTo(runtime);
        assertThat(replay.leaseGen()).isEqualTo(1);
        assertThat(replay.leaseUntil()).isEqualTo(first.leaseUntil());
        assertThat(replay.deadlineAt()).isEqualTo(first.deadlineAt());
        assertThat(replay.projectionBytes()).isEqualTo(first.projectionBytes());
        assertThat(replay.dataset().frozenSnapshot().payloadBytes())
                .isEqualTo(frozen.payloadBytes());
        byte[] altered = replay.projectionBytes();
        altered[0] = 0;
        assertThat(replay.projectionBytes()).isEqualTo(first.projectionBytes());
        assertThat(row()).isEqualTo(before);
        assertThat(((Number) row().get("call_count")).intValue()).isEqualTo(1);
        assertThat(attempts()).isEqualTo(attemptsBefore).hasSize(1);
        assertThat(first.toString())
                .doesNotContain(
                        "SELECTED_REPORT",
                        "expected",
                        "payload",
                        "settings",
                        "culprit",
                        "fixture-only");
        assertThat(requests.get()).isZero();
    }

    @Test
    void twoServiceInstancesSerializeOneReservationAndOneReplay() throws Exception {
        var other = service(jdbc, credentials);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                gate.await();
                                return start();
                            });
            var b =
                    executor.submit(
                            () -> {
                                gate.await();
                                return other.start(worker, jobKey, 1);
                            });
            gate.countDown();
            var results = List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Reservation::replay).count()).isEqualTo(1);
            assertThat(results).allSatisfy(value -> assertThat(value.attemptNo()).isEqualTo(1));
        }
        assertThat(attempts()).hasSize(1);
        assertThat(((Number) row().get("call_count")).intValue()).isEqualTo(1);
    }

    @Test
    void thirdRunningAttemptReplaysButFourthGenerationCannotReserve() {
        assertThat(start().attemptNo()).isEqualTo(1);
        terminalAndAdvance(2);
        assertThat(service.start(worker, jobKey, 2).attemptNo()).isEqualTo(2);
        terminalAndAdvance(3);
        assertThat(service.start(worker, jobKey, 3).attemptNo()).isEqualTo(3);
        assertThat(service.start(worker, jobKey, 3).replay()).isTrue();
        terminalAndAdvance(4);
        assertThatThrownBy(() -> service.start(worker, jobKey, 4))
                .hasMessage("START_BUDGET_EXHAUSTED")
                .hasNoCause();
        assertThat(attempts()).hasSize(3);
        assertThat(((Number) row().get("call_count")).intValue()).isEqualTo(3);
    }

    @Test
    void terminalSameLeaseCannotReplay() {
        start();
        jdbc.update(
                "UPDATE public.grade_attempt SET state='FAILED',ended_at=clock_timestamp()"
                        + " WHERE job_id=?",
                job);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
    }

    @Test
    void queuedAndTerminalJobsAreNotLeaseAuthorityOrImplicitAdmission() {
        for (String state : List.of("QUEUED", "FAILED", "CANCELLED", "COMPLETED")) {
            jdbc.update(
                    "UPDATE public.grade_job SET state=?,worker_key=NULL,lease_until=NULL WHERE"
                        + " id=?",
                    state,
                    job);
            unchangedRejection(() -> start(), "START_NOT_CURRENT");
        }
        assertThat(attempts()).isEmpty();
    }

    @Test
    void wrongWorkerRegistryActionScopeAndGenerationDoNotMutate() {
        byte[] secret = ByteBuffer.allocate(32).putLong(17).array();
        String authorization =
                "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var wrong = registry("other-worker", secret, "START_SYNTHETIC", Action.START);
        unchangedRejection(
                () -> service.start(wrong.authenticate(authorization), jobKey, 1),
                "WORKER_NOT_AUTHORIZED");
        var noScope = registry(workerKey, secret, "OTHER", Action.START);
        unchangedRejection(
                () -> service(jdbc, noScope).start(noScope.authenticate(authorization), jobKey, 1),
                "WORKER_NOT_AUTHORIZED");
        var noAction = registry(workerKey, secret, "START_SYNTHETIC", Action.CLAIM);
        unchangedRejection(
                () ->
                        service(jdbc, noAction)
                                .start(noAction.authenticate(authorization), jobKey, 1),
                "WORKER_NOT_AUTHORIZED");
        var otherWorker = registry("other-worker", secret, "START_SYNTHETIC", Action.START);
        unchangedRejection(
                () ->
                        service(jdbc, otherWorker)
                                .start(otherWorker.authenticate(authorization), jobKey, 1),
                "START_NOT_CURRENT");
        unchangedRejection(() -> service.start(worker, jobKey, 2), "START_NOT_CURRENT");
    }

    @Test
    void deniedScopeNeverAttemptsRootLocks() throws Exception {
        byte[] secret = ByteBuffer.allocate(32).putLong(18).array();
        var denied = registry(workerKey, secret, "OTHER", Action.START);
        var proof =
                denied.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        try (var holder = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            try (var lock =
                    holder.prepareStatement(
                            "SELECT id FROM public.admin_account WHERE id=? FOR UPDATE")) {
                lock.setLong(1, creator);
                lock.executeQuery().close();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var result =
                        executor.submit(
                                () -> {
                                    unchangedRejection(
                                            () -> service(jdbc, denied).start(proof, jobKey, 1),
                                            "WORKER_NOT_AUTHORIZED");
                                    return true;
                                });
                assertThat(result.get(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
    }

    @Test
    void nullBadKeysAndNonpositiveGenerationAreRejectedWithoutChanges() {
        unchangedRejection(() -> service.start(null, jobKey, 1), "INVALID_START_INPUT");
        unchangedRejection(() -> service.start(worker, null, 1), "INVALID_START_INPUT");
        unchangedRejection(() -> service.start(worker, new UUID(0, 0), 1), "INVALID_START_INPUT");
        unchangedRejection(() -> service.start(worker, jobKey, 0), "INVALID_START_INPUT");
        unchangedRejection(() -> service.start(worker, jobKey, -1), "INVALID_START_INPUT");
        unchangedRejection(() -> service.start(worker, UUID.randomUUID(), 1), "START_NOT_CURRENT");
    }

    @Test
    void enclosingWritableAndReadonlyTransactionsAreRejected() {
        for (boolean readOnly : List.of(false, true)) {
            var tx = new TransactionTemplate(manager);
            tx.setReadOnly(readOnly);
            tx.executeWithoutResult(
                    status ->
                            unchangedRejection(
                                    () -> start(), "START_REQUIRES_SEPARATE_TRANSACTION"));
        }
    }

    @Test
    void creatorRevocationAlsoBlocksReplay() {
        start();
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
    }

    @Test
    void missingEnrollmentAndMfaReadinessBlockReservation() {
        jdbc.update(
                "UPDATE public.admin_credential SET enrolled_at=NULL,mfa_state='PENDING' WHERE"
                    + " account_id=?",
                creator);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.admin_credential SET"
                    + " enrolled_at=clock_timestamp(),mfa_state='RECOVERY' WHERE account_id=?",
                creator);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
    }

    @Test
    void revokedStoryReviewAndInactiveCreatorBlockReservation() {
        jdbc.update("UPDATE public.story_access SET active_yn=false WHERE story_id=?", story);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
        jdbc.update("UPDATE public.story_access SET active_yn=true WHERE story_id=?", story);
        jdbc.update("UPDATE public.admin_account SET active_yn=false WHERE id=?", creator);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
    }

    @Test
    void replacedCurrentSnapshotAndReturnedVersionBlockReservation() {
        long replacement =
                id(
                        """
                        INSERT INTO public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                        VALUES (?,0,?::jsonb,?,?) RETURNING id
                        """,
                        version,
                        new String(frozen.payloadBytes(), StandardCharsets.UTF_8),
                        UUID.randomUUID(),
                        creator);
        jdbc.update(
                "UPDATE public.story_version SET current_snapshot_id=? WHERE id=?",
                replacement,
                version);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.story_version SET status='DRAFT',current_snapshot_id=NULL WHERE"
                    + " id=?",
                version);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
    }

    @Test
    void runtimeEpochAndStateMismatchBlockReservation() {
        try {
            jdbc.update("UPDATE public.grade_runtime SET epoch=1 WHERE id=?", runtime);
            unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
            jdbc.update(
                    "UPDATE public.grade_runtime SET epoch=0,state='SUSPENDED' WHERE id=?",
                    runtime);
            unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
        } finally {
            jdbc.update(
                    "UPDATE public.grade_runtime SET epoch=0,state='AVAILABLE' WHERE id=?",
                    runtime);
        }
    }

    @Test
    void actualManifestMutationAndHashMutationBlockReservation() {
        String manifest = installation.registrationManifest().toString();
        try {
            jdbc.update(
                    "UPDATE public.grade_runtime SET"
                        + " config_data=jsonb_set(config_data,'{engineVersion}','\"changed\"')"
                        + " WHERE id=?",
                    runtime);
            unchangedRejection(() -> start(), "INSTALLED_RUNTIME_MISMATCH");
            jdbc.update(
                    "UPDATE public.grade_runtime SET config_data=?::jsonb,config_hash=? WHERE id=?",
                    manifest,
                    "b".repeat(64),
                    runtime);
            unchangedRejection(() -> start(), "INSTALLED_RUNTIME_MISMATCH");
        } finally {
            jdbc.update(
                    "UPDATE public.grade_runtime SET config_data=?::jsonb,config_hash=? WHERE id=?",
                    manifest,
                    installation.configHash(),
                    runtime);
        }
    }

    @Test
    void uninstalledRuntimeAndIncorrectInstallationMapKeyAreRejected() {
        var empty = new GradeStartService(jdbc, manager, credentials, Map.of());
        unchangedRejection(() -> empty.start(worker, jobKey, 1), "INSTALLED_RUNTIME_MISMATCH");
        assertThatThrownBy(
                        () ->
                                new GradeStartService(
                                        jdbc, manager, credentials, Map.of("WRONG", installation)))
                .hasMessage("INVALID_START_INSTALLATIONS");
        var mutable = new java.util.HashMap<String, InstalledRuntimeManifestVerifier>();
        mutable.put("START_SYNTHETIC", installation);
        var copied = new GradeStartService(jdbc, manager, credentials, mutable);
        mutable.clear();
        assertThat(copied.start(worker, jobKey, 1).attemptNo()).isEqualTo(1);
    }

    @Test
    void frozenPayloadMutationRejectsEvenWhenStillValidWholeDataset() {
        ObjectNode changed = (ObjectNode) frozen.payload();
        ((ObjectNode) changed.get("sections").get("reveal")).put("revealText", "변경된 사본");
        var replacement = FrozenSnapshotCodec.freeze(changed);
        jdbc.update(
                "UPDATE public.review_snapshot SET payload=?::jsonb WHERE id=?",
                new String(replacement.payloadBytes(), StandardCharsets.UTF_8),
                snapshot);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
    }

    @Test
    void malformedFullDatasetRejectsBeforeReservation() {
        jdbc.update("UPDATE public.review_snapshot SET payload='{}'::jsonb WHERE id=?", snapshot);
        unchangedRejection(() -> start(), "INVALID_FROZEN_SNAPSHOT");
    }

    @Test
    void codecValidButUnconfirmedWholeDatasetCannotReserveSelectedValidSample() {
        ObjectNode changed = (ObjectNode) frozen.payload();
        for (var sample : changed.get("resources").get("gradeSamples")) {
            if ("ZERO".equals(sample.get("code").textValue())) {
                ((ObjectNode) sample).putNull("checkedBy");
            }
        }
        var replacement = FrozenSnapshotCodec.freeze(changed);
        jdbc.update(
                "UPDATE public.review_snapshot SET payload=?::jsonb WHERE id=?",
                new String(replacement.payloadBytes(), StandardCharsets.UTF_8),
                snapshot);
        unchangedRejection(() -> start(), "INVALID_FROZEN_DATASET");
    }

    @Test
    void snapshotFormatMetadataMustMatchActualCodecFormat() {
        jdbc.update("UPDATE public.review_snapshot SET format_no=2 WHERE id=?", snapshot);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
    }

    @Test
    void jobAndBatchConfigurationMustMatchActualLockedInstalledRuntime() {
        jdbc.update("UPDATE public.grade_job SET config_hash=? WHERE id=?", "b".repeat(64), job);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.grade_job SET config_hash=? WHERE id=?",
                installation.configHash(),
                job);
        jdbc.update(
                "UPDATE public.grade_batch SET config_hash=? WHERE id=?", "b".repeat(64), batch);
        unchangedRejection(() -> start(), "SOURCE_NOT_CURRENT");
    }

    @Test
    void frozenHashDeclarationsAreNotTrusted() {
        for (String column : List.of("payload_hash", "rubric_hash", "dataset_hash")) {
            String original =
                    column.equals("payload_hash")
                            ? frozen.payloadHash()
                            : column.equals("rubric_hash")
                                    ? frozen.rubricHash()
                                    : frozen.datasetHash();
            jdbc.update(
                    "UPDATE public.grade_batch SET " + column + "=? WHERE id=?",
                    "b".repeat(64),
                    batch);
            unchangedRejection(() -> start(), "START_NOT_CURRENT");
            jdbc.update(
                    "UPDATE public.grade_batch SET " + column + "=? WHERE id=?", original, batch);
        }
        jdbc.update("UPDATE public.grade_job SET input_hash=? WHERE id=?", "b".repeat(64), job);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
    }

    @Test
    void physicalIdentityPolicyAndSnapshotRevisionCannotOverrideFrozenIdentity() {
        jdbc.update("UPDATE public.review_snapshot SET edit_rev=1 WHERE id=?", snapshot);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
        jdbc.update("UPDATE public.review_snapshot SET edit_rev=0 WHERE id=?", snapshot);
        jdbc.update("UPDATE public.story_version SET version_no=2 WHERE id=?", version);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.story_version SET version_no=1,policy_code='H2' WHERE id=?",
                version);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.story_version SET policy_code='RULE_20260924' WHERE id=?", version);
        jdbc.update("UPDATE public.story SET code=? WHERE id=?", token("OTHER"), story);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
    }

    @Test
    void inputErrorIsCoordinatorOnlyAndEngineErrorReservesNormalReportWithoutInvocation() {
        selectSample("INPUT");
        unchangedRejection(() -> start(), "COORDINATOR_ONLY_FIXTURE");
        assertThat(attempts()).isEmpty();
        selectSample("ENGINE");
        var reservation = start();
        assertThat(reservation.selection().report()).isNotNull();
        assertThat(reservation.selection().fault().failRuns()).isEqualTo(3);
        assertThat(new String(reservation.projectionBytes(), StandardCharsets.UTF_8))
                .doesNotContain("fault", "failRuns", "TIMEOUT", "SYSTEM_ERROR", "expectedScore");
        assertThat(requests.get()).isZero();
    }

    @Test
    void expiredLeaseDeadlineAndBatchEachBlockReservation() {
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                    + " WHERE id=?",
                job);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                UPDATE public.grade_job SET accepted_at=t.n-interval '121 seconds',
                    deadline_at=t.n-interval '1 second',
                    lease_until=t.n-interval '2 seconds' FROM t WHERE id=?
                """,
                job);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                UPDATE public.grade_job SET accepted_at=t.n,deadline_at=t.n+interval '120 seconds',
                    lease_until=t.n+interval '30 seconds' FROM t WHERE id=?
                """,
                job);
        jdbc.update(
                "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval '24 hours'"
                    + " WHERE id=?",
                batch);
        unchangedRejection(() -> start(), "BATCH_EXPIRED");
    }

    @Test
    void expiredReplayDoesNotDispatchOrIncrement() {
        start();
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                    + " WHERE id=?",
                job);
        unchangedRejection(() -> start(), "START_NOT_CURRENT");
    }

    @Test
    void realCreatorLockWaitUsesFreshClockAfterLeaseExpires() throws Exception {
        try (var holder = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            try (var lock =
                    holder.prepareStatement(
                            "SELECT id FROM public.admin_account WHERE id=? FOR UPDATE")) {
                lock.setLong(1, creator);
                lock.executeQuery().close();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var result =
                        executor.submit(
                                () -> {
                                    assertThatThrownBy(() -> start())
                                            .hasMessage("START_NOT_CURRENT");
                                    return true;
                                });
                awaitRootWait();
                try (var expire =
                        holder.prepareStatement(
                                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval"
                                    + " '0.3 seconds' WHERE id=?")) {
                    expire.setLong(1, job);
                    expire.executeUpdate();
                }
                try (var delay = holder.createStatement()) {
                    delay.execute("SELECT pg_sleep(0.4)");
                }
                holder.commit();
                assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
        assertThat(attempts()).isEmpty();
        assertThat(((Number) row().get("call_count")).intValue()).isZero();
    }

    @Test
    void realInsertTriggerFailureRollsBackEverythingAndRedactsCause() {
        trigger("grade_attempt", "INSERT", "RAISE EXCEPTION 'secret report payload';");
        try {
            unchangedRejection(() -> start(), "START_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_attempt");
        }
        assertThat(start().attemptNo()).isEqualTo(1);
    }

    @Test
    void realUpdateTriggerFailureRollsBackPreviouslyInsertedAttempt() {
        trigger("grade_job", "UPDATE", "RAISE EXCEPTION 'secret settings';");
        try {
            unchangedRejection(() -> start(), "START_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_job");
        }
        assertThat(attempts()).isEmpty();
        assertThat(start().attemptNo()).isEqualTo(1);
    }

    @Test
    void sqlDelayDuringInsertCrossesLeaseBoundaryAndGuardedUpdateRollsBack() {
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '0.8 seconds'"
                    + " WHERE id=?",
                job);
        trigger("grade_attempt", "INSERT", "PERFORM pg_sleep(1.0);");
        try {
            unchangedRejection(() -> start(), "START_NOT_CURRENT");
        } finally {
            dropTrigger("grade_attempt");
        }
        assertThat(attempts()).isEmpty();
    }

    @Test
    void sqlDelayDuringUpdateCrossesLeaseBoundaryAndReturningGuardRollsBack() {
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '0.8 seconds'"
                    + " WHERE id=?",
                job);
        trigger("grade_job", "UPDATE", "PERFORM pg_sleep(1.0);");
        try {
            unchangedRejection(() -> start(), "START_NOT_CURRENT");
        } finally {
            dropTrigger("grade_job");
        }
        assertThat(attempts()).isEmpty();
    }

    @Test
    void foreignDatasourceManagerCannotCreateUsableRootEvidence() {
        var foreign =
                new DataSourceTransactionManager(GradeSchemaIT.jdbc(postgres).getDataSource());
        var wrong =
                new GradeStartService(
                        jdbc, foreign, credentials, Map.of("START_SYNTHETIC", installation));
        unchangedRejection(
                () -> wrong.start(worker, jobKey, 1), "SOURCE_REQUIRES_WRITE_READ_COMMITTED");
    }

    /** 실제 인증 등록을 만들며 외부 생성 VerifiedWorker·반사·콜백 승인 경로는 없다. */
    private GradeWorkerCredentials registry(String key, byte[] secret, String code, Action action) {
        return new GradeWorkerCredentials(
                List.of(
                        new Registration(
                                key, CommonUtil.sha256(secret), Set.of(code), Set.of(action))));
    }

    /** 같은 DB 도구와 TX 관리자로 실제 서비스를 구성한다. */
    private GradeStartService service(JdbcTemplate connection, GradeWorkerCredentials registry) {
        return new GradeStartService(
                connection, manager, registry, Map.of("START_SYNTHETIC", installation));
    }

    /** 현재 fixture의 실제 START 요청이다. */
    private Reservation start() {
        return service.start(worker, jobKey, 1);
    }

    /** 거절 전후 전체 job와 attempt 행이 정확히 같은지 검사한다. */
    private void unchangedRejection(Runnable operation, String message) {
        var before = row();
        var children = attempts();
        assertThatThrownBy(operation::run).hasMessage(message).hasNoCause();
        assertThat(row()).isEqualTo(before);
        assertThat(attempts()).isEqualTo(children);
    }

    /** 완료 구현을 대신하지 않는 명시적 합성 복구 fixture 변경이다. */
    private void terminalAndAdvance(long generation) {
        jdbc.update(
                "UPDATE public.grade_attempt SET state='FAILED',ended_at=clock_timestamp() WHERE"
                    + " job_id=? AND state='RUNNING'",
                job);
        jdbc.update("UPDATE public.grade_job SET lease_gen=? WHERE id=?", generation, job);
    }

    /** 기대값 변경 없이 실제 선택 코드와 전체 결속 입력 해시를 함께 변경한다. */
    private void selectSample(String code) {
        jdbc.update(
                "UPDATE public.grade_job SET sample_code=?,input_hash=? WHERE id=?",
                code,
                frozen.inputHash(code),
                job);
    }

    /** 테스트 전용 실제 PostgreSQL 트리거로 DB 실패 또는 SQL 지연을 유발한다. */
    private void trigger(String table, String event, String body) {
        jdbc.execute(
                "CREATE FUNCTION public.start_test_trigger() RETURNS trigger LANGUAGE plpgsql AS $$"
                    + " BEGIN "
                        + body
                        + " RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER start_test_trigger BEFORE "
                        + event
                        + " ON public."
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION public.start_test_trigger()");
    }

    /** 테스트 전용 트리거만 제거한다. */
    private void dropTrigger(String table) {
        jdbc.execute("DROP TRIGGER start_test_trigger ON public." + table);
        jdbc.execute("DROP FUNCTION public.start_test_trigger()");
    }

    /** 실제 root 잠금 대기를 독립 통계 연결의 새 사본으로 관찰한다. */
    private void awaitRootWait() throws Exception {
        try (var monitor = postgres.createConnection("");
                var query = monitor.createStatement()) {
            monitor.setAutoCommit(true);
            for (int i = 0; i < 100; i++) {
                try (var result =
                        query.executeQuery(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                    + " datname=current_database() AND wait_event_type='Lock' AND"
                                    + " position('FROM public.admin_account WHERE id=' in"
                                    + " query)>0)")) {
                    if (result.next() && result.getBoolean(1)) return;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new AssertionError("실제 root 잠금 대기 미관찰");
    }

    /** 실제 DB INSERT 반환 식별자다. */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 전체 실제 job 행 사본이다. */
    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job);
    }

    /** 실제 attempt 행을 기본키 순서로 읽는다. */
    private List<Map<String, Object>> attempts() {
        return jdbc.queryForList(
                "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no", job);
    }

    /** 비밀이 아닌 유일 fixture 코드다. */
    private String token(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
