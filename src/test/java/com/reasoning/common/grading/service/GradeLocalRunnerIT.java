package com.reasoning.common.grading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.GradeSchemaIT;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.GradeLocalRunner.State;
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

/** 실제 고정 V16 PG·전체 사본·loopback provider로 단일 실행 경계를 검사한다. 실제 모델 품질 승인은 아니다. */
class GradeLocalRunnerIT {
    private static final String CODE = "RUNNER_SYNTHETIC";
    private static final String DIGEST = "a".repeat(64);
    private static final String TEMPLATE = "{{ .System }}{{ .Prompt }}{{ .Response }}";
    private static final String CANARY = "PRIVATE_SEMANTIC_CANARY";
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static CryptoService crypto;
    private static InstalledRuntimeManifestVerifier installation;
    private static HttpServer server;
    private static java.util.concurrent.ExecutorService httpThreads;
    private static long runtime;
    private static final AtomicInteger chats = new AtomicInteger();
    private static final AtomicInteger requests = new AtomicInteger();
    private static volatile String semantic;
    private static volatile String chatBody;
    private static volatile boolean disconnect;
    private static volatile boolean drift;
    private static volatile long chatDelay;
    private static volatile CountDownLatch showEntered;
    private static volatile CountDownLatch releaseShow;
    private static volatile CountDownLatch chatEntered;
    private GradeWorkerCredentials credentials;
    private VerifiedWorker worker;
    private GradeStartService start;
    private GradeCompletionService completion;
    private GradeLocalRunner runner;
    private long creator;
    private long story;
    private long job;
    private long batch;
    private UUID key;
    private String workerKey;
    private byte[] secret;
    private FrozenSnapshot frozen;

    /** 폐기형 고정 DB와 SQL 밖 실제 설치·키를 생성한다. */
    @BeforeAll
    static void open() throws Exception {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(version::int) FROM public.flyway_schema_history WHERE"
                                        + " success",
                                Integer.class))
                .isEqualTo(23);
        var properties = new AuthProperties();
        properties.setCryptoKeyFile(TestKeys.create((byte) 41));
        properties.setSearchKeyFile(TestKeys.create((byte) 42));
        properties.setLimitKeyFile(TestKeys.create((byte) 43));
        crypto = new CryptoService(properties);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newCachedThreadPool();
        server.setExecutor(httpThreads);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    String path = exchange.getRequestURI().getPath();
                    ObjectNode body = JsonNodeFactory.instance.objectNode();
                    try {
                        if (path.equals("/api/tags")) {
                            body.putArray("models")
                                    .addObject()
                                    .put("name", "qwen3:8b")
                                    .put(
                                            "digest",
                                            drift && chats.get() > 0 ? "b".repeat(64) : DIGEST);
                        } else if (path.equals("/api/show")) {
                            showEntered.countDown();
                            if (!releaseShow.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException();
                            body.put("template", TEMPLATE)
                                    .putObject("thinking")
                                    .putArray("values")
                                    .add(false)
                                    .add(true);
                        } else if (path.equals("/api/chat")) {
                            chats.incrementAndGet();
                            chatBody =
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8);
                            chatEntered.countDown();
                            if (disconnect) {
                                exchange.close();
                                return;
                            }
                            if (chatDelay > 0) Thread.sleep(chatDelay);
                            body.put("model", "qwen3:8b")
                                    .put("done", true)
                                    .put("done_reason", "stop");
                            body.putObject("message")
                                    .put("role", "assistant")
                                    .put("content", semantic)
                                    .put("thinking", "PRIVATE_THINKING_CANARY");
                        } else throw new IllegalStateException();
                        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var dictionary =
                new GradeDictionary(CODE, List.of(new GradeDictionary.Term("ONE", "개념", "합성")));
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "qwen3:8b",
                        DIGEST,
                        dictionary.sha256(),
                        TEMPLATE,
                        65536,
                        4096,
                        0,
                        1,
                        false,
                        Duration.ofSeconds(120));
        installation =
                new InstalledRuntimeManifestVerifier(
                        new LocalSemanticEngine(settings),
                        dictionary,
                        new InstalledRuntimeManifestVerifier.InstalledProfile(
                                CODE, "LOCAL", "1", "RULE_20260924"));
        runtime =
                new GradeRuntimeRepository(jdbc)
                        .registerRuntime(
                                CODE,
                                installation.configHash(),
                                installation.registrationManifest().toString())
                        .id();
    }

    /** 호출·heartbeat·HTTP 자원을 시험 수명 안에서 종료한다. */
    @AfterAll
    static void close() throws Exception {
        if (server != null) server.stop(0);
        if (httpThreads != null) {
            httpThreads.shutdownNow();
            httpThreads.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (postgres != null) postgres.close();
    }

    /** GradeStartServiceIT의 전체 fixture·물리 부모·선언 해시를 그대로 결속한다. */
    @BeforeEach
    void fixture() {
        chats.set(0);
        requests.set(0);
        chatBody = null;
        disconnect = false;
        drift = false;
        chatDelay = 0;
        showEntered = new CountDownLatch(1);
        releaseShow = new CountDownLatch(0);
        chatEntered = new CountDownLatch(1);
        semantic = completeSemantic();
        jdbc.update(
                "UPDATE public.grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtime);
        workerKey = token("WORKER");
        secret =
                ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(UUID.randomUUID().getLeastSignificantBits())
                        .putLong(1)
                        .putLong(2)
                        .array();
        assemble(Set.of(Action.START, Action.RENEW, Action.COMPLETE));
        creator =
                id(
                        "INSERT INTO public.admin_account(account_key,can_review,can_manage) VALUES"
                                + " (?,true,true) RETURNING id",
                        UUID.randomUUID());
        jdbc.update(
                "INSERT INTO"
                    + " public.admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                    + " VALUES"
                    + " (?,'fixture-only',?,'fixture-only','fixture-only',clock_timestamp(),0,clock_timestamp(),'READY')",
                creator,
                ByteBuffer.allocate(32).putLong(creator).array());
        String code = token("STORY");
        story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        code,
                        creator);
        long version =
                id(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 전체 사본','RULE_20260924',?,?) RETURNING id",
                        story,
                        creator,
                        creator);
        ObjectNode payload = FrozenSnapshotContractTest.complete();
        payload.put("storyCode", code);
        frozen = FrozenSnapshotCodec.freeze(payload);
        long snapshot =
                id(
                        "INSERT INTO"
                            + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,?::jsonb,?,?) RETURNING id",
                        version,
                        new String(frozen.payloadBytes(), StandardCharsets.UTF_8),
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
                        "INSERT INTO"
                            + " public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)"
                            + " VALUES (?,?,?,'REVIEW',?,?,?,?,0,'RUNNING',12,?) RETURNING id",
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
                        "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                            + " public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash,accepted_at,deadline_at,worker_key,lease_gen,lease_until)"
                            + " SELECT ?,?,?,?,'FULL',1,'RUNNING',?,?,?,t.n,t.n+interval '120"
                            + " seconds',?,1,t.n+interval '30 seconds' FROM t RETURNING id",
                        key,
                        snapshot,
                        runtime,
                        batch,
                        frozen.inputHash("FULL"),
                        installation.configHash(),
                        frozen.rubricHash(),
                        workerKey);
    }

    @Test
    void completePersistsActualSemanticEncryptedWithActualObservationAndNoReference() {
        jdbc.update(
                "UPDATE public.grade_runtime SET config_data=?::jsonb WHERE id=?",
                installation
                        .registrationManifest()
                        .toString()
                        .replace("\"formatNo\":1", "\"formatNo\":1.0"),
                runtime);
        var outcome = runner.runOnce(worker, key, 1);
        assertThat(outcome.state()).isEqualTo(State.RECEIPT);
        assertThat(outcome.receipt().accepted()).isTrue();
        assertThat(outcome.toString()).doesNotContain(CANARY, "PRIVATE_THINKING_CANARY");
        var attempt = attempt();
        assertThat(attempt.get("observed_version")).isEqualTo(DIGEST);
        assertThat(attempt.get("provider_ref")).isNull();
        assertThat(attempt.get("completion_data")).isNotNull();
        String decoded =
                crypto.decrypt(
                        new String((byte[]) attempt.get("output_cipher"), StandardCharsets.UTF_8),
                        "grade_attempt/" + job + "/1/output/v1");
        assertThat(
                        SnapshotJson.encode(
                                SnapshotJson.parse(decoded.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(
                        SnapshotJson.encode(
                                SnapshotJson.parse(semantic.getBytes(StandardCharsets.UTF_8))));
        assertThat(row().get("result_cipher")).isNotNull();
        var events = jdbc.queryForList("SELECT * FROM public.grade_event WHERE job_id=?", job);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("event_kind")).isEqualTo("COMPLETE_APPLIED");
        assertThat(events.toString()).doesNotContain(CANARY, "PRIVATE_THINKING_CANARY");
        assertOne();
        assertThat(chatBody)
                .doesNotContain("expectedScore", "checkedBy", "expectData", "fault", CANARY);
        assertNoHeartbeat();
    }

    @Test
    void unresolvedUsesActualValidatorAndClosedCompletion() {
        semantic = "{\"formatNo\":1,\"status\":\"UNRESOLVED\",\"items\":null}";
        var outcome = runner.runOnce(worker, key, 1);
        assertThat(outcome.receipt().accepted()).isTrue();
        assertThat(attempt().get("error_code")).isEqualTo("UNRESOLVED_REASONING");
        assertThat(attempt().get("output_cipher")).isNull();
        assertOne();
    }

    @Test
    void concurrentReplayHasZeroAdditionalProviderCalls() throws Exception {
        releaseShow = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> runner.runOnce(worker, key, 1));
            assertThat(showEntered.await(5, TimeUnit.SECONDS)).isTrue();
            int before = requests.get();
            assertThat(runner.runOnce(worker, key, 1).state()).isEqualTo(State.REPLAY);
            assertThat(requests.get()).isEqualTo(before);
            assertThat(start.start(worker, key, 1).replay()).isTrue();
            releaseShow.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).receipt().accepted()).isTrue();
        } finally {
            releaseShow.countDown();
        }
        assertOne();
    }

    @Test
    void revocationDuringProbesRejectsBeforeChatWithoutFakeCompletion() throws Exception {
        for (int change = 0; change < 6; change++) {
            if (change > 0) fixture();
            final int scenario = change;
            blockedShow(
                    () -> {
                        switch (scenario) {
                            case 0 ->
                                    jdbc.update(
                                            "UPDATE public.admin_account SET can_review=false WHERE"
                                                    + " id=?",
                                            creator);
                            case 1 ->
                                    jdbc.update(
                                            "UPDATE public.story_access SET active_yn=false WHERE"
                                                    + " story_id=?",
                                            story);
                            case 2 ->
                                    jdbc.update(
                                            "UPDATE public.grade_runtime SET epoch=1 WHERE id=?",
                                            runtime);
                            case 3 ->
                                    jdbc.update(
                                            "UPDATE public.grade_job SET"
                                                    + " lease_until=clock_timestamp()-interval '1"
                                                    + " second' WHERE id=?",
                                            job);
                            case 4 ->
                                    jdbc.update(
                                            "UPDATE public.grade_job SET lease_gen=2 WHERE id=?",
                                            job);
                            case 5 ->
                                    jdbc.update(
                                            "UPDATE public.grade_attempt SET"
                                                + " state='FAILED',ended_at=clock_timestamp() WHERE"
                                                + " job_id=?",
                                            job);
                            default -> throw new AssertionError();
                        }
                    });
            assertThat(chats.get()).isZero();
            assertThat(row().get("call_count")).isEqualTo(1);
            assertThat(attempt().get("completion_data")).isNull();
            assertNoHeartbeat();
        }
    }

    @Test
    void missingRenewOrCompletePreventsReservation() {
        for (var actions :
                List.of(
                        Set.of(Action.START, Action.COMPLETE),
                        Set.of(Action.START, Action.RENEW))) {
            assemble(actions);
            assertThatThrownBy(() -> runner.runOnce(worker, key, 1))
                    .hasMessage("WORKER_NOT_AUTHORIZED");
            assertThat(row().get("call_count")).isEqualTo(0);
            assertThat(requests.get()).isZero();
        }
    }

    @Test
    void slowThirdChatRenewsWithoutChangingOriginalDeadlineOrBudget() throws Exception {
        for (int number = 1; number <= 2; number++) {
            start.start(worker, key, number);
            jdbc.update(
                    "UPDATE public.grade_attempt SET state='FAILED',ended_at=clock_timestamp()"
                            + " WHERE job_id=? AND attempt_no=?",
                    job,
                    number);
            jdbc.update("UPDATE public.grade_job SET lease_gen=? WHERE id=?", number + 1, job);
        }
        var before = row();
        chatDelay = 32000;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> runner.runOnce(worker, key, 3));
            assertThat(chatEntered.await(5, TimeUnit.SECONDS)).isTrue();
            TimeUnit.SECONDS.sleep(11);
            var live = row();
            assertThat(live.get("lease_until")).isNotEqualTo(before.get("lease_until"));
            assertThat(live.get("deadline_at")).isEqualTo(before.get("deadline_at"));
            assertThat(live.get("accepted_at")).isEqualTo(before.get("accepted_at"));
            assertThat(live.get("call_count")).isEqualTo(3);
            assertThat(result.get(30, TimeUnit.SECONDS).receipt().accepted()).isTrue();
        }
        assertThat(chats.get()).isEqualTo(1);
        assertNoHeartbeat();
    }

    @Test
    void acceptedChatConnectionLossInvalidOutputAndDriftNeverResend() {
        for (int scenario = 0; scenario < 3; scenario++) {
            if (scenario > 0) fixture();
            if (scenario == 0) disconnect = true;
            if (scenario == 1) semantic = "{\"private\":\"" + CANARY + "\"}";
            if (scenario == 2) drift = true;
            var result = runner.runOnce(worker, key, 1);
            assertThat(result.toString()).doesNotContain(CANARY, "Exception", "thinking");
            assertOne();
            if (scenario < 2) {
                assertThat(result.state()).isEqualTo(State.RECEIPT);
                assertThat(result.receipt().accepted()).isTrue();
                assertThat(attempt().get("error_code"))
                        .isEqualTo(scenario == 0 ? "ENGINE_UNAVAILABLE" : "INVALID_OUTPUT");
                assertThat(attempt().get("state")).isEqualTo("FAILED");
                assertThat(attempt().get("completion_data")).isNotNull();
                assertThat(attempt().get("observed_version")).isNull();
            }
            if (scenario == 2) {
                assertThat(result.state()).isEqualTo(State.RECOVERY);
                int before = requests.get();
                assertThat(runner.runOnce(worker, key, 1).state()).isEqualTo(State.REPLAY);
                assertThat(requests.get()).isEqualTo(before);
                assertThat(attempt().get("completion_data")).isNull();
            }
            assertNoHeartbeat();
        }
    }

    @Test
    void renewFailureCancelsPendingChatAndStopsOwnedThread() throws Exception {
        releaseShow = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> runner.runOnce(worker, key, 1));
            assertThat(showEntered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE public.grade_job SET lease_gen=2 WHERE id=?", job);
            assertThat(result.get(10, TimeUnit.SECONDS).state()).isEqualTo(State.RECOVERY);
        } finally {
            releaseShow.countDown();
        }
        assertThat(chats.get()).isZero();
        assertThat(attempt().get("completion_data")).isNull();
        assertNoHeartbeat();
    }

    /** 실제 job 잠금으로 갱신을 지연시켜 제한 시간 후 소유 thread 종료·interrupt 보존·무위조 완료를 확인한다. */
    @Test
    void blockedRenewalStopsOwnedHeartbeatWithoutCompletionOrResend() throws Exception {
        chatDelay = 15000;
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result =
                    executor.submit(
                            () -> {
                                var outcome = runner.runOnce(worker, key, 1);
                                interrupted.set(Thread.currentThread().isInterrupted());
                                return outcome;
                            });
            assertThat(chatEntered.await(5, TimeUnit.SECONDS)).isTrue();
            try (var blocker = jdbc.getDataSource().getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement =
                        blocker.prepareStatement(
                                "SELECT id FROM public.grade_job WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, job);
                    statement.setQueryTimeout(2);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                assertThat(result.get(17, TimeUnit.SECONDS).state()).isEqualTo(State.RECOVERY);
                assertNoHeartbeat();
                blocker.rollback();
            }
        }
        assertThat(interrupted.get()).isTrue();
        assertOne();
        assertThat(row().get("state")).isEqualTo("RUNNING");
        assertThat(attempt().get("state")).isEqualTo("RUNNING");
        assertThat(attempt().get("completion_data")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_event WHERE job_id=?",
                                Integer.class,
                                job))
                .isZero();
        int before = requests.get();
        assertThat(runner.runOnce(worker, key, 1).state()).isEqualTo(State.REPLAY);
        assertThat(requests.get()).isEqualTo(before);
    }

    @Test
    void ambientAndWrongAssemblyFailBeforeReservation() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status ->
                                assertThatThrownBy(() -> runner.runOnce(worker, key, 1))
                                        .hasMessage("START_REQUIRES_SEPARATE_TRANSACTION"));
        var foreign =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        CommonUtil.sha256(secret),
                                        Set.of(CODE),
                                        Set.of(Action.START, Action.RENEW, Action.COMPLETE))));
        assertThatThrownBy(() -> new GradeLocalRunner(foreign, start, completion))
                .hasMessage("RUNNER_ASSEMBLY_MISMATCH");
        assertThat(row().get("call_count")).isEqualTo(0);
    }

    @Test
    void callbackForeignReplayDuplicateIssuanceAndConsumptionAreRejected() {
        var reservation = start.start(worker, key, 1);
        var foreign = new GradeStartService(jdbc, manager, credentials, Map.of(CODE, installation));
        assertThatThrownBy(() -> foreign.beforeChat(worker, reservation))
                .hasMessage("START_NOT_CURRENT");
        assertThatThrownBy(() -> start.beforeChat(worker, start.start(worker, key, 1)))
                .hasMessage("START_NOT_CURRENT");
        var callback = start.beforeChat(worker, reservation);
        assertThatThrownBy(() -> start.beforeChat(worker, reservation))
                .hasMessage("START_NOT_CURRENT");
        callback.check();
        assertThatThrownBy(callback::check).hasMessage("START_NOT_CURRENT");
        assertThat(chats.get()).isZero();
    }

    @Test
    void rejectedCallbackCannotRearmAfterAuthorityRestored() {
        var reservation = start.start(worker, key, 1);
        var callback = start.beforeChat(worker, reservation);
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        assertThatThrownBy(callback::check).hasMessage("SOURCE_NOT_CURRENT");
        jdbc.update("UPDATE public.admin_account SET can_review=true WHERE id=?", creator);
        assertThatThrownBy(callback::check).hasMessage("START_NOT_CURRENT");
        assertThat(attempt().get("completion_data")).isNull();
    }

    @Test
    void engineRetainsOriginalContentButNeverPrintsSemanticOrThinking() {
        var reservation = start.start(worker, key, 1);
        var result =
                reservation
                        .runtime()
                        .evaluate(
                                reservation.dataset(),
                                reservation.selection(),
                                reservation.deadline(),
                                start.beforeChat(worker, reservation));
        assertThat(result.semanticJson()).isEqualTo(semantic);
        assertThat(result.toString())
                .doesNotContain(CANARY, "PRIVATE_THINKING_CANARY", "items", "report");
        assertOne();
    }

    @Test
    void engineExceptionHasOnlyFixedCodeAndActualSubmissionPhase() {
        semantic = "{\"private\":\"" + CANARY + "\"}";
        var reservation = start.start(worker, key, 1);
        assertThatThrownBy(
                        () ->
                                reservation
                                        .runtime()
                                        .evaluate(
                                                reservation.dataset(),
                                                reservation.selection(),
                                                reservation.deadline(),
                                                start.beforeChat(worker, reservation)))
                .isInstanceOfSatisfying(
                        LocalSemanticEngine.EngineException.class,
                        failure -> {
                            assertThat(failure.phase())
                                    .isEqualTo(LocalSemanticEngine.FailurePhase.MAY_HAVE_SUBMITTED);
                            assertThat(failure.getMessage()).isEqualTo("INVALID_LOCAL_OUTPUT");
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.toString())
                                    .doesNotContain(CANARY, "PRIVATE_THINKING_CANARY");
                        });
        assertOne();
    }

    @Test
    void revokedSourceAfterChatReturnsActualRejectedReceiptWithoutRepair() throws Exception {
        chatDelay = 800;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> runner.runOnce(worker, key, 1));
            assertThat(chatEntered.await(5, TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(manager)
                    .executeWithoutResult(
                            status -> {
                                jdbc.execute("SET LOCAL lock_timeout='1s'");
                                jdbc.queryForList(
                                        "SELECT id FROM public.admin_account WHERE id=? FOR UPDATE",
                                        creator);
                                jdbc.update(
                                        "UPDATE public.admin_account SET can_review=false WHERE"
                                                + " id=?",
                                        creator);
                            });
            var outcome = result.get(10, TimeUnit.SECONDS);
            assertThat(outcome.state()).isEqualTo(State.RECEIPT);
            assertThat(outcome.receipt().accepted()).isFalse();
        }
        assertOne();
        assertThat(attempt().get("completion_data")).isNull();
        assertThat(attempt().get("state")).isEqualTo("RUNNING");
    }

    @Test
    void callerInterruptCancelsInFlightWithoutReceiptOrThreadLeak() throws Exception {
        chatDelay = 2000;
        var answer = new java.util.concurrent.atomic.AtomicReference<GradeLocalRunner.Outcome>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller =
                Thread.ofPlatform()
                        .unstarted(
                                () -> {
                                    answer.set(runner.runOnce(worker, key, 1));
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                });
        caller.start();
        assertThat(chatEntered.await(5, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(10000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(interrupted.get()).isTrue();
        assertThat(answer.get().state()).isEqualTo(State.RECOVERY);
        assertThat(attempt().get("completion_data")).isNull();
        assertOne();
        TimeUnit.MILLISECONDS.sleep(2200);
        assertNoHeartbeat();
    }

    @Test
    void originalDeadlineConsumedDuringChatHasNoReplacementOrRefund() throws Exception {
        jdbc.update(
                "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) UPDATE public.grade_job SET"
                        + " accepted_at=t.n-interval '118 seconds',deadline_at=t.n+interval '2"
                        + " seconds',lease_until=t.n+interval '2 seconds' FROM t WHERE id=?",
                job);
        var original = row();
        chatDelay = 2600;
        var result = runner.runOnce(worker, key, 1);
        assertThat(result.state()).isIn(State.RECEIPT, State.RECOVERY);
        if (result.receipt() != null) assertThat(result.receipt().accepted()).isFalse();
        assertOne();
        assertThat(row().get("deadline_at")).isEqualTo(original.get("deadline_at"));
        assertThat(attempt().get("completion_data")).isNull();
        TimeUnit.MILLISECONDS.sleep(2800);
    }

    @Test
    void actualRenewStorageErrorFailsClosedWithoutProviderCompletion() throws Exception {
        releaseShow = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> runner.runOnce(worker, key, 1));
            assertThat(showEntered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.execute(
                    "CREATE FUNCTION public.runner_renew_error() RETURNS trigger LANGUAGE plpgsql"
                            + " AS $$ BEGIN RAISE EXCEPTION 'PRIVATE_STORAGE_CANARY'; END $$");
            jdbc.execute(
                    "CREATE TRIGGER runner_renew_error BEFORE UPDATE ON public.grade_job FOR EACH"
                            + " ROW EXECUTE FUNCTION public.runner_renew_error()");
            try {
                assertThat(result.get(10, TimeUnit.SECONDS).state()).isEqualTo(State.RECOVERY);
            } finally {
                jdbc.execute("DROP TRIGGER runner_renew_error ON public.grade_job");
                jdbc.execute("DROP FUNCTION public.runner_renew_error()");
                releaseShow.countDown();
            }
        } finally {
            releaseShow.countDown();
        }
        assertThat(chats.get()).isZero();
        assertThat(attempt().get("completion_data")).isNull();
        assertNoHeartbeat();
    }

    @Test
    void mismatchedActualRenewRuntimeCancelsWithoutResending() throws Exception {
        releaseShow = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> runner.runOnce(worker, key, 1));
            assertThat(showEntered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update(
                    "UPDATE public.grade_runtime SET code='CHANGED_RUNTIME' WHERE id=?", runtime);
            try {
                assertThat(result.get(10, TimeUnit.SECONDS).state()).isEqualTo(State.RECOVERY);
            } finally {
                jdbc.update("UPDATE public.grade_runtime SET code=? WHERE id=?", CODE, runtime);
                releaseShow.countDown();
            }
        } finally {
            releaseShow.countDown();
        }
        assertThat(chats.get()).isZero();
        assertThat(attempt().get("completion_data")).isNull();
        assertNoHeartbeat();
    }

    @Test
    void currentPermissibilityCannotReplaceOriginalSelectedInput() {
        var reservation = start.start(worker, key, 1);
        var callback = start.beforeChat(worker, reservation);
        jdbc.update(
                "UPDATE public.grade_job SET sample_code='ZERO',input_hash=? WHERE id=?",
                frozen.inputHash("ZERO"),
                job);
        assertThatThrownBy(callback::check).hasMessage("START_NOT_CURRENT");
        assertThat(chats.get()).isZero();
        assertThat(row().get("call_count")).isEqualTo(1);
        assertThat(attempt().get("completion_data")).isNull();
    }

    @Test
    void wrongDatasourceTransactionManagerNeverObtainsFenceAuthority() {
        var wrongManager =
                new DataSourceTransactionManager(GradeSchemaIT.jdbc(postgres).getDataSource());
        var wrong =
                new GradeStartService(jdbc, wrongManager, credentials, Map.of(CODE, installation));
        var wrongRunner = new GradeLocalRunner(credentials, wrong, completion);
        assertThatThrownBy(() -> wrongRunner.runOnce(worker, key, 1))
                .hasMessage("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        assertThat(requests.get()).isZero();
        assertThat(row().get("call_count")).isEqualTo(0);
    }

    /** provider를 막은 동안 독립 연결로 실제 상위 루트를 잠글 수 있다. */
    private void blockedShow(Runnable change) throws Exception {
        releaseShow = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> runner.runOnce(worker, key, 1));
            assertThat(showEntered.await(5, TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(manager)
                    .executeWithoutResult(
                            status -> {
                                jdbc.execute("SET LOCAL lock_timeout='1s'");
                                jdbc.queryForList(
                                        "SELECT id FROM public.admin_account WHERE id=? FOR UPDATE",
                                        creator);
                                change.run();
                            });
            releaseShow.countDown();
            assertThat(result.get(10, TimeUnit.SECONDS).state()).isEqualTo(State.RECOVERY);
        } finally {
            releaseShow.countDown();
        }
    }

    /** 기대 답안에서 생성하지 않는 직접 등록 명제의 완전한 의미 JSON이다. */
    private static String completeSemantic() {
        var root =
                JsonNodeFactory.instance.objectNode().put("formatNo", 1).put("status", "COMPLETE");
        var items = root.putArray("items");
        var culprit = items.addObject().put("rubricCode", "CULPRIT").put("reason", CANARY);
        culprit.putArray("claims");
        var contradictions = culprit.putArray("contradictions");
        for (String code : List.of("CONTRADICT_CULPRIT", "UNSUPPORTED_ACCOMPLICE"))
            contradictions.addObject().put("code", code).put("met", false).putArray("spans");
        for (String code : List.of("METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            var item = items.addObject().put("rubricCode", code).put("reason", CANARY);
            item.putArray("contradictions");
            item.putArray("claims")
                    .addObject()
                    .put("code", "CLAIM")
                    .put("met", false)
                    .putArray("spans");
        }
        return root.toString();
    }

    /** 같은 실제 의존성을 재조립하고 실제 토큰을 인증한다. */
    private void assemble(Set<Action> actions) {
        credentials =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        CommonUtil.sha256(secret),
                                        Set.of(CODE),
                                        actions)));
        worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        start = new GradeStartService(jdbc, manager, credentials, Map.of(CODE, installation));
        completion =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of(CODE, installation),
                        crypto,
                        "runner_coordinator");
        runner = new GradeLocalRunner(credentials, start, completion);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String token(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job);
    }

    private Map<String, Object> attempt() {
        return jdbc.queryForMap(
                "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no DESC LIMIT"
                        + " 1",
                job);
    }

    private void assertOne() {
        assertThat(chats.get()).isEqualTo(1);
        assertThat(row().get("call_count")).isEqualTo(1);
    }

    private static void assertNoHeartbeat() {
        assertThat(
                        Thread.getAllStackTraces().keySet().stream()
                                .filter(
                                        thread ->
                                                thread.getName().equals("grade-local-heartbeat")
                                                        && thread.isAlive()))
                .isEmpty();
    }
}
