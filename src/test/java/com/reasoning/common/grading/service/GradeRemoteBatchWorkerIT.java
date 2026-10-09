package com.reasoning.common.grading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.GradeSchemaIT;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal.Scope;
import com.reasoning.common.grading.config.WorkerGradeProfileLoader;
import com.reasoning.common.grading.config.WorkerGradeProfileLoader.Profiles;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeDictionary.Term;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker.BoundTransport;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker.PollReply;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker.PollingTransport;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker.TransportBounds;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * 실제 PG16.10 서버 서비스·같은 registry·보호 profile/journal·프로세스 lock·통제 loopback provider의 단회 결합 시험이다. 추론
 * 품질·production HTTP/TLS·전원 손실 durability·GRADE 인가 증거가 아니다. 실행은 부모의 폐기형 PG gate에서만 한다.
 */
class GradeRemoteBatchWorkerIT {
    @TempDir private static Path fileRoot;

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static InstalledRuntimeManifestVerifier installation;
    private static long runtime;
    private static CryptoService crypto;
    private static Profiles profiles;
    private static Path profilePath;
    private static com.sun.net.httpserver.HttpServer provider;
    private static java.util.concurrent.ExecutorService providerExecutor;
    private static final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final AtomicInteger chats = new AtomicInteger();
    private static volatile Runnable afterShow = () -> {};
    private static volatile String semanticStatus = "COMPLETE";
    private static volatile int providerStatus = 200;
    private static volatile String providerPostDigest = "a".repeat(64);
    private static volatile long providerDelayMillis;
    private static volatile CountDownLatch providerEntered = new CountDownLatch(1);
    private static volatile CountDownLatch providerRelease = new CountDownLatch(0);
    private static volatile boolean streamHeldBody;
    private static volatile Store activeStore;
    private static volatile UUID activeJobKey;
    private static volatile long activeGeneration;
    private GradeWorkerCredentials credentials;
    private VerifiedWorker worker;
    private GradeStartService service;
    private Scope scope;
    private UUID jobKey;
    private long job;
    private long generation;
    private FrozenSnapshotCodec.FrozenSnapshot frozen;

    /** 실제 폐기형 PG·migration·동일 설치를 조립하며 endpoint에는 접속하지 않는다. */
    @BeforeAll
    static void open() throws Exception {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var dictionary =
                new GradeDictionary("REMOTE_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
        provider =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerExecutor = Executors.newCachedThreadPool();
        provider.setExecutor(providerExecutor);
        installProvider();
        provider.start();
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:" + provider.getAddress().getPort()),
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
                        new InstalledProfile("REMOTE_SYNTHETIC", "LOCAL", "1", "RULE_20260924"));
        profilePath = writeProfile(settings, dictionary);
        profiles = new WorkerGradeProfileLoader().load(profilePath.toString());
        var registered =
                new GradeRuntimeRepository(jdbc)
                        .registerRuntime(
                                "REMOTE_SYNTHETIC",
                                installation.configHash(),
                                installation.registrationManifest().toString());
        assertThat(registered.epoch()).isZero();
        runtime = registered.id();
        var properties = new AuthProperties();
        properties.setCryptoKeyFile(TestKeys.create((byte) 41));
        properties.setSearchKeyFile(TestKeys.create((byte) 42));
        properties.setLimitKeyFile(TestKeys.create((byte) 43));
        crypto = new CryptoService(properties);
    }

    /** 이 시험의 폐기형 DB만 닫는다. */
    @AfterAll
    static void closeDatabase() {
        if (provider != null) provider.stop(0);
        if (providerExecutor != null) providerExecutor.shutdownNow();
        if (postgres != null) postgres.close();
    }

    /** 실제 full frozen 사본·정상 roots·authenticated registry·live BATCH를 만든다. */
    @BeforeEach
    void fixtures() {
        order.clear();
        chats.set(0);
        afterShow = () -> {};
        semanticStatus = "COMPLETE";
        providerStatus = 200;
        providerPostDigest = "a".repeat(64);
        providerDelayMillis = 0;
        providerEntered = new CountDownLatch(1);
        providerRelease = new CountDownLatch(0);
        streamHeldBody = false;
        activeStore = null;
        String workerKey = "W_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        byte[] secret =
                ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(2)
                        .putLong(3)
                        .putLong(4)
                        .array();
        scope = new Scope(UUID.randomUUID(), workerKey, CommonUtil.sha256(secret));
        credentials =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        scope.credentialSha256(),
                                        Set.of(
                                                installation
                                                        .registrationManifest()
                                                        .path("configId")
                                                        .asText()),
                                        Set.of(
                                                Action.CLAIM,
                                                Action.START,
                                                Action.RENEW,
                                                Action.COMPLETE))));
        worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        service =
                new GradeStartService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of(
                                installation.registrationManifest().path("configId").asText(),
                                installation));
        long creator =
                id(
                        "INSERT INTO public.admin_account(account_key,can_review,can_manage)"
                                + " VALUES (?,true,true) RETURNING id",
                        UUID.randomUUID());
        jdbc.update(
                "INSERT INTO"
                    + " public.admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                    + " VALUES"
                    + " (?,'fixture-only',?,'fixture-only','fixture-only',clock_timestamp(),0,clock_timestamp(),'READY')",
                creator,
                ByteBuffer.allocate(32).putLong(creator).array());
        String code = "ST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        long story =
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
        var payload = FrozenSnapshotContractTest.complete();
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
                "INSERT INTO public.story_access(story_id,admin_id,permission,granted_by)"
                        + " VALUES (?,?,'REVIEW',?)",
                story,
                creator,
                creator);
        long batch =
                id(
                        "INSERT INTO"
                            + " public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,"
                            + "rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)"
                            + " VALUES (?,?,?,'REVIEW',?,?,?,?,0,'RUNNING',12,?) RETURNING id",
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        frozen.datasetHash(),
                        frozen.rubricHash(),
                        frozen.payloadHash(),
                        installation.configHash(),
                        creator);
        jobKey = UUID.randomUUID();
        generation = 1;
        job =
                id(
                        "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                            + " public.grade_job(job_key,"
                            + "snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash,accepted_at,deadline_at,worker_key,lease_gen,lease_until)"
                            + " SELECT ?,?,?,?,'FULL',1,'RUNNING',?,?,?,t.n,t.n+interval '120"
                            + " seconds',?,1,t.n+interval '30 seconds' FROM t RETURNING id",
                        jobKey,
                        snapshot,
                        runtime,
                        batch,
                        frozen.inputHash("FULL"),
                        installation.configHash(),
                        frozen.rubricHash(),
                        workerKey);
        activeJobKey = jobKey;
        activeGeneration = generation;
    }

    /** 실제 MODEL core·원래 서버 currentness·저널·완료·비공개 출력의 단회 결합을 검사한다. */
    @Test
    void consumedModelUsesActualFenceForcedIntentAndCompletion() throws Exception {
        activeStore = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        try (var owner = owner(activeStore, transport)) {
            var outcome = owner.runOnce(jobKey, generation);
            assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
            assertThat(outcome.receipt()).isEqualTo(transport.actualReceipt);
            assertThat(outcome.receipt().accepted()).isTrue();
            assertThat(outcome.receipt().state()).isEqualTo("COMPLETED");
            assertThat(outcome.receipt().reason()).isEqualTo("NONE");
            assertThat(transport.completedCommand.observedProviderVersion())
                    .isEqualTo("a".repeat(64));
            assertThat(transport.completedCommand.providerResponseRef()).isNull();
            assertThat(transport.completedCommand.resultJson())
                    .doesNotContain("PRIVATE_THINKING_CANARY");
            assertThat(transport.completedCommand.toString()).doesNotContain("CANARY");
        }
        assertThat(order)
                .containsExactly(
                        "tags",
                        "show",
                        "fence",
                        "forced-chat-intent",
                        "chat",
                        "tags",
                        "show",
                        "complete");
        assertThat(chats.get()).isEqualTo(1);
        assertThat(transport.starts.get()).isEqualTo(1);
        assertThat(transport.completions.get()).isEqualTo(1);
        assertThat(journalStates(activeStore)).containsExactly(0, 1, 3, 4, 5);
        var attempt =
                jdbc.queryForMap(
                        "SELECT output_cipher,completion_data FROM public.grade_attempt WHERE"
                                + " job_id=? AND attempt_no=1",
                        job);
        assertThat(attempt.get("output_cipher")).isNotNull();
        String output =
                crypto.decrypt(
                        new String((byte[]) attempt.get("output_cipher"), StandardCharsets.UTF_8),
                        "grade_attempt/" + job + "/1/output/v1");
        assertThat(output)
                .contains("CONTROLLED_DIAGNOSTIC_CANARY")
                .doesNotContain("PRIVATE_THINKING_CANARY");
        var saved =
                FrozenSnapshotCodec.freeze(
                        SnapshotJson.parse(
                                jdbc.queryForObject(
                                                "SELECT s.payload::text FROM public.review_snapshot"
                                                        + " s JOIN public.grade_job j ON"
                                                        + " j.snapshot_id=s.id WHERE j.id=?",
                                                String.class,
                                                job)
                                        .getBytes(StandardCharsets.UTF_8)));
        var dataset = new FrozenDatasetValidator().validate(saved);
        var selected = dataset.select("FULL");
        var semantic =
                new GradeResultValidator()
                        .validate(output, selected.report(), dataset.gradingSnapshot());
        var calculated =
                new GradeCalculator()
                        .calculate(selected.report(), dataset.gradingSnapshot(), semantic);
        byte[] resultCipher =
                jdbc.queryForObject(
                        "SELECT result_cipher FROM public.grade_job WHERE id=?", byte[].class, job);
        var privateResult =
                SnapshotJson.parse(
                        crypto.decrypt(
                                        new String(resultCipher, StandardCharsets.UTF_8),
                                        "grade_job/" + job + "/result/v1")
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(SnapshotJson.encode(privateResult.path("baseResult")))
                .isEqualTo(
                        SnapshotJson.encode(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .valueToTree(calculated)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT call_count FROM public.grade_job WHERE id=?",
                                Integer.class,
                                job))
                .isEqualTo(1);
        assertRestartRefused(activeStore);
    }

    /** 실제 TEST lease가 없는 서버 저장소의 204는 START나 저널 기록을 만들지 않는다. */
    @Test
    void explicitPollWithoutTestLeaseDoesNotRunBatchJob() throws Exception {
        activeStore = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        try (var owner = owner(activeStore, transport)) {
            assertThat(owner.pollAndRunOnce("REMOTE_SYNTHETIC")).isNull();
            assertThat(owner.pollAndRunOnce("REMOTE_SYNTHETIC")).isNull();
        }
        assertThat(transport.polls.get()).isEqualTo(2);
        assertThat(transport.starts.get()).isZero();
        assertThat(transport.completions.get()).isZero();
        assertThat(journalStates(activeStore)).isEmpty();
        assertNoProviderOrCompletion();
    }

    /** 기존 BATCH-only transport는 poll 권위를 암묵적으로 얻지 않는다. */
    @Test
    void batchOnlyTransportCannotPoll() throws Exception {
        activeStore = provision(scope, 65536);
        var delegate = new ServerTransport(scope);
        BoundTransport batchOnly =
                new BoundTransport() {
                    public Scope scope() {
                        return delegate.scope();
                    }

                    public CompletableFuture<byte[]> start(UUID key, long gen, Duration wait) {
                        return delegate.start(key, gen, wait);
                    }

                    public CompletableFuture<byte[]> fence(
                            UUID key, AttemptRequest original, Duration wait) {
                        return delegate.fence(key, original, wait);
                    }

                    public CompletableFuture<byte[]> renew(
                            UUID key, AttemptRequest original, Duration wait) {
                        return delegate.renew(key, original, wait);
                    }

                    public CompletableFuture<byte[]> complete(
                            UUID key,
                            GradeRemoteBatchWorker.CompletionCommand command,
                            Duration wait) {
                        return delegate.complete(key, command, wait);
                    }
                };
        try (var owner = owner(activeStore, batchOnly)) {
            assertThatThrownBy(() -> owner.pollAndRunOnce("REMOTE_SYNTHETIC"))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        assertThat(delegate.polls.get()).isZero();
        assertThat(delegate.starts.get()).isZero();
        assertThat(journalStates(activeStore)).isEmpty();
    }

    /** 전송된 잘못된 200 body는 임대를 추측하거나 재poll하지 않고 owner를 닫는다. */
    @Test
    void malformedPollCannotStartOrRetry() throws Exception {
        activeStore = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        transport.pollMutation =
                reply ->
                        new PollReply(
                                200,
                                "{\"jobKey\":\"BAD\",\"leaseGen\":1,\"deadline\":\"2026-10-09T00:00:00Z\"}"
                                        .getBytes(StandardCharsets.UTF_8));
        try (var owner = owner(activeStore, transport)) {
            assertThatThrownBy(() -> owner.pollAndRunOnce("REMOTE_SYNTHETIC"))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThatThrownBy(() -> owner.pollAndRunOnce("REMOTE_SYNTHETIC"))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        assertThat(transport.polls.get()).isEqualTo(1);
        assertThat(transport.starts.get()).isZero();
        assertThat(journalStates(activeStore)).isEmpty();
    }

    /** 미완료 poll 동안 run/중복 poll은 거절되고 close는 실제 전체 응답 future를 취소한다. */
    @Test
    void outstandingPollExcludesRunAndCloseCancelsResponse() throws Exception {
        activeStore = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        transport.holdPollBody = true;
        var owner = owner(activeStore, transport);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var polling = executor.submit(() -> owner.pollAndRunOnce("REMOTE_SYNTHETIC"));
            assertThat(transport.pollEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> owner.runOnce(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThatThrownBy(() -> owner.pollAndRunOnce("REMOTE_SYNTHETIC"))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            owner.close();
            assertThatThrownBy(() -> polling.get(5, TimeUnit.SECONDS))
                    .rootCause()
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(transport.pollBodyFuture.isCancelled()).isTrue();
        } finally {
            owner.close();
        }
        assertThat(transport.polls.get()).isEqualTo(1);
        assertThat(transport.starts.get()).isZero();
        assertThat(journalStates(activeStore)).isEmpty();
    }

    /** 실제 probes 이후 자격 회수 또는 CHAT_INTENT force 실패는 chat·완료를 만들지 않는다. */
    @Test
    void revokedCurrentnessAndFailedSendIntentHaveZeroChat() throws Exception {
        activeStore = provision(scope, 65536);
        afterShow =
                () -> jdbc.update("UPDATE public.grade_runtime SET epoch=1 WHERE id=?", runtime);
        try {
            var transport = new ServerTransport(scope);
            try (var owner = owner(activeStore, transport)) {
                assertThat(owner.runOnce(jobKey, generation).state())
                        .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
            }
            assertThat(chats.get()).isZero();
            assertThat(transport.fences.get()).isEqualTo(1);
            assertThat(transport.completions.get()).isZero();
            assertNoProviderOrCompletion();
            assertRestartRefused(activeStore);
        } finally {
            jdbc.update("UPDATE public.grade_runtime SET epoch=0 WHERE id=?", runtime);
        }
        fixtures();
        activeStore = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        var journal = openWithForceFailure(activeStore, new AtomicInteger(), 4);
        var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles);
        assertThat(owner.runOnce(jobKey, generation).state())
                .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
        assertThat(chats.get()).isZero();
        assertThat(transport.completions.get()).isZero();
        assertNoProviderOrCompletion();
        owner.close();
        assertRestartRefused(activeStore);
    }

    /** genuine UNRESOLVED와 MAY HTTP 실패만 서버 오류 완료에 연결하고 관측 null을 보존한다. */
    @Test
    void genuineUnresolvedAndClassifiableProviderFailureAreNotScores() throws Exception {
        for (boolean unavailable : List.of(false, true)) {
            fixtures();
            activeStore = provision(scope, 65536);
            semanticStatus = "UNRESOLVED";
            providerStatus = unavailable ? 503 : 200;
            var transport = new ServerTransport(scope);
            try (var owner = owner(activeStore, transport)) {
                var outcome = owner.runOnce(jobKey, generation);
                assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
                assertThat(outcome.receipt().accepted()).isTrue();
                assertThat(outcome.receipt().retryScheduled()).isTrue();
            }
            assertThat(chats.get()).isEqualTo(1);
            var result =
                    SnapshotJson.parse(
                            transport
                                    .completedCommand
                                    .resultJson()
                                    .getBytes(StandardCharsets.UTF_8));
            assertThat(result.path("kind").asText())
                    .isEqualTo(unavailable ? "ERROR" : "UNRESOLVED");
            assertThat(result.path("errorCode").asText())
                    .isEqualTo(unavailable ? "ENGINE_UNAVAILABLE" : "UNRESOLVED_REASONING");
            assertThat(transport.completedCommand.observedProviderVersion())
                    .isEqualTo(unavailable ? null : "a".repeat(64));
            assertThat(transport.completedCommand.providerResponseRef()).isNull();
            assertThat(journalStates(activeStore)).containsExactly(0, 1, 3, 4, 5);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT result_cipher FROM public.grade_job WHERE id=?",
                                    byte[].class,
                                    job))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT output_cipher FROM public.grade_attempt WHERE job_id=?"
                                            + " AND attempt_no=1",
                                    byte[].class,
                                    job))
                    .isNull();
            assertRestartRefused(activeStore);
        }
    }

    /** 실제 완료 처리 뒤 응답 유실·형식 손상·현재성 거절을 재전송·성공 채점으로 바꾸지 않는다. */
    @Test
    void completionLossMalformedAndAcceptedFalseNeverResend() throws Exception {
        for (String mode : List.of("lost", "malformed", "refused")) {
            fixtures();
            activeStore = provision(scope, 65536);
            var transport = new ServerTransport(scope);
            transport.loseCompletion = mode.equals("lost");
            if (mode.equals("malformed"))
                transport.completionMutation = bytes -> "{}".getBytes(StandardCharsets.UTF_8);
            if (mode.equals("refused"))
                transport.beforeCompletion =
                        () ->
                                jdbc.update(
                                        "UPDATE public.grade_runtime SET epoch=1 WHERE id=?",
                                        runtime);
            try {
                try (var owner = owner(activeStore, transport)) {
                    var outcome = owner.runOnce(jobKey, generation);
                    if (mode.equals("refused")) {
                        assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
                        assertThat(outcome.receipt()).isEqualTo(transport.actualReceipt);
                        assertThat(outcome.receipt().accepted()).isFalse();
                        assertThat(outcome.receipt().outcome()).isNull();
                        assertThat(outcome.receipt().reason()).isEqualTo("RUNTIME_EPOCH_CHANGED");
                        assertThat(outcome.receipt().retryScheduled()).isFalse();
                    } else {
                        assertThat(outcome.state())
                                .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
                        assertThat(outcome.receipt()).isNull();
                        assertThat(transport.actualReceipt.accepted()).isTrue();
                    }
                }
                assertThat(chats.get()).isEqualTo(1);
                assertThat(transport.completions.get()).isEqualTo(1);
                assertThat(journalStates(activeStore))
                        .containsExactly(0, 1, 3, 4, mode.equals("refused") ? 5 : 2);
                assertRestartRefused(activeStore);
            } finally {
                jdbc.update("UPDATE public.grade_runtime SET epoch=0 WHERE id=?", runtime);
            }
        }
    }

    /** 보호된 실제 다른 Settings 설치는 원래 NEW를 소비한 뒤에도 provider·완료 권위를 만들지 않는다. */
    @Test
    void realProtectedProfileMismatchFailsBeforeAnyProviderIo() throws Exception {
        activeStore = provision(scope, 65536);
        var dictionary =
                new GradeDictionary("REMOTE_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
        var changed =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:" + provider.getAddress().getPort()),
                        "qwen3:8b",
                        "a".repeat(64),
                        dictionary.sha256(),
                        "{{ .System }}{{ .Prompt }}{{ .Response }}",
                        65536,
                        4096,
                        0,
                        2,
                        false,
                        Duration.ofSeconds(30));
        var changedProfiles =
                new WorkerGradeProfileLoader().load(writeProfile(changed, dictionary).toString());
        var transport = new ServerTransport(scope);
        try (var owner =
                new GradeRemoteBatchWorker(
                        GradeRemoteOnceJournal.open(activeStore.descriptor().toString()),
                        transport,
                        bounds(),
                        changedProfiles)) {
            assertThat(owner.runOnce(jobKey, generation).state())
                    .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
        }
        assertThat(transport.starts.get()).isEqualTo(1);
        assertThat(transport.fences.get()).isZero();
        assertThat(transport.completions.get()).isZero();
        assertThat(chats.get()).isZero();
        assertThat(order).isEmpty();
        assertNoProviderOrCompletion();
        assertThat(journalStates(activeStore)).containsExactly(0, 1, 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT call_count FROM public.grade_job WHERE id=?",
                                Integer.class,
                                job))
                .isEqualTo(1);
        assertRestartRefused(activeStore);
    }

    /** 실제 postflight 설치 drift는 점수·classifiable provider 오류 완료를 만들지 않는다. */
    @Test
    void postflightModelDriftCannotCreateProviderErrorOrScore() throws Exception {
        activeStore = provision(scope, 65536);
        providerPostDigest = "0".repeat(64);
        var transport = new ServerTransport(scope);
        try (var owner = owner(activeStore, transport)) {
            var outcome = owner.runOnce(jobKey, generation);
            assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
            assertThat(outcome.receipt()).isNull();
        }
        assertThat(chats.get()).isEqualTo(1);
        assertThat(transport.completions.get()).isZero();
        assertThat(order)
                .containsExactly("tags", "show", "fence", "forced-chat-intent", "chat", "tags");
        assertNoProviderOrCompletion();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_cipher FROM public.grade_job WHERE id=?",
                                byte[].class,
                                job))
                .isNull();
        assertThat(journalStates(activeStore)).containsExactly(0, 1, 3, 2);
        assertRestartRefused(activeStore);
    }

    /** 실제 서버 완료 뒤 전체 receipt body가 old lease 밖에 도착하면 취소·복구하며 재완료하지 않는다. */
    @Test
    void lateFullCompletionBodyCannotRestartOriginalLease() throws Exception {
        activeStore = provision(scope, 65536);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '2 seconds'"
                        + " WHERE id=?",
                job);
        var transport = new ServerTransport(scope);
        transport.holdCompletionBody = true;
        try (var owner = owner(activeStore, transport);
                var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> owner.runOnce(jobKey, generation));
            assertThat(transport.completionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(transport.actualReceipt.accepted()).isTrue();
            var outcome = future.get(5, TimeUnit.SECONDS);
            assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
            assertThat(outcome.receipt()).isNull();
            assertThat(transport.completionBodyFuture.isCancelled()).isTrue();
            assertThat(transport.completionBodyFuture.complete(transport.heldCompletionBody))
                    .isFalse();
        }
        assertThat(chats.get()).isEqualTo(1);
        assertThat(transport.completions.get()).isEqualTo(1);
        assertThat(journalStates(activeStore)).containsExactly(0, 1, 3, 4, 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT call_count FROM public.grade_job WHERE id=?",
                                Integer.class,
                                job))
                .isEqualTo(1);
        assertRestartRefused(activeStore);
    }

    /** 완료 의도·종료 force 실패는 provider 오류나 재완료로 바꾸지 않으며 실제 서버 사실만 보존한다. */
    @Test
    void completionIntentAndClosedForceFailuresNeverFakeOrResendCallbacks() throws Exception {
        for (int failAt : List.of(5, 6)) {
            fixtures();
            activeStore = provision(scope, 65536);
            var transport = new ServerTransport(scope);
            var journal = openWithForceFailure(activeStore, new AtomicInteger(), failAt);
            try (var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles)) {
                var outcome = owner.runOnce(jobKey, generation);
                assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
                assertThat(outcome.receipt()).isNull();
            }
            assertThat(chats.get()).isEqualTo(1);
            assertThat(transport.completions.get()).isEqualTo(failAt == 5 ? 0 : 1);
            if (failAt == 5) {
                assertNoProviderOrCompletion();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT result_cipher FROM public.grade_job WHERE id=?",
                                        byte[].class,
                                        job))
                        .isNull();
            } else {
                assertThat(transport.actualReceipt.accepted()).isTrue();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM public.grade_attempt WHERE job_id=?"
                                                + " AND completion_data IS NOT NULL",
                                        Integer.class,
                                        job))
                        .isEqualTo(1);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT call_count FROM public.grade_job WHERE id=?",
                                    Integer.class,
                                    job))
                    .isEqualTo(1);
            assertRestartRefused(activeStore);
        }
    }

    /** 실제 START의 전체 RTT 또는 OWNED force 동안 old lease가 소진되면 수신시각으로 재시작하지 않는다. */
    @Test
    void consumedStartRoundTripAndOwnedForceCannotResetOwnership() throws Exception {
        for (boolean forceDelay : List.of(false, true)) {
            fixtures();
            activeStore = provision(scope, 65536);
            jdbc.update(
                    "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '2 seconds'"
                            + " WHERE id=?",
                    job);
            var transport = new ServerTransport(scope);
            AtomicInteger forces = new AtomicInteger();
            var journal =
                    openWithForce(
                            activeStore,
                            channel -> {
                                if (forces.incrementAndGet() == 3 && forceDelay) Thread.sleep(2200);
                                channel.force(true);
                            });
            if (!forceDelay)
                transport.startMutation =
                        body -> {
                            java.util.concurrent.locks.LockSupport.parkNanos(
                                    Duration.ofMillis(2200).toNanos());
                            return body;
                        };
            try (var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles)) {
                assertThat(owner.runOnce(jobKey, generation).state())
                        .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
            }
            assertThat(transport.starts.get()).isEqualTo(1);
            if (forceDelay) assertThat(forces.get()).isGreaterThanOrEqualTo(3);
            assertThat(chats.get()).isZero();
            assertThat(transport.fences.get()).isZero();
            assertThat(transport.completions.get()).isZero();
            assertThat(order).isEmpty();
            assertNoProviderOrCompletion();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT call_count FROM public.grade_job WHERE id=?",
                                    Integer.class,
                                    job))
                    .isEqualTo(1);
            assertRestartRefused(activeStore);
        }
    }

    /** active run의 공개 factual 훅·동시 run은 두 번째 chat 권위를 만들지 않고 실제 renew만 L을 연장한다. */
    @Test
    void activeRunRejectsDuplicateEntryAndActualRenewalSpansOneChat() throws Exception {
        activeStore = provision(scope, 65536);
        providerRelease = new CountDownLatch(1);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '2 seconds'"
                        + " WHERE id=?",
                job);
        var transport = new ServerTransport(scope);
        try (var owner = owner(activeStore, transport);
                var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> owner.runOnce(jobKey, generation));
            try {
                assertThat(providerEntered.await(1, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(owner::revalidateReservation)
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThatThrownBy(owner::renewReservation)
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThatThrownBy(() -> owner.runOnce(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                Thread.sleep(2200);
                assertThat(transport.renewals.get()).isPositive();
                providerRelease.countDown();
                assertThat(future.get(5, TimeUnit.SECONDS).state())
                        .isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
            } finally {
                providerRelease.countDown();
            }
        }
        assertThat(chats.get()).isEqualTo(1);
        assertThat(transport.completions.get()).isEqualTo(1);
        assertThat(journalStates(activeStore)).containsExactly(0, 1, 3, 4, 5);
        assertRestartRefused(activeStore);
    }

    /** 실제 renew 전체 body를 보류해도 독립 old-lease alarm은 스트림을 취소하고 late body는 되살리지 않는다. */
    @Test
    void blockedActualRenewalCannotHoldLeaseAlarmOrFabricateCompletion() throws Exception {
        activeStore = provision(scope, 65536);
        providerRelease = new CountDownLatch(1);
        streamHeldBody = true;
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '2 seconds'"
                        + " WHERE id=?",
                job);
        var transport = new ServerTransport(scope);
        transport.holdRenewBody = true;
        try (var owner = owner(activeStore, transport);
                var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> owner.runOnce(jobKey, generation));
            try {
                assertThat(providerEntered.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(transport.observedEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(future.get(5, TimeUnit.SECONDS).state())
                        .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
                assertThat(transport.observedBodyFuture.isCancelled()).isTrue();
                assertThat(transport.observedBodyFuture.complete(transport.heldObservedBody))
                        .isFalse();
                assertThat(transport.completions.get()).isZero();
                assertThat(chats.get()).isEqualTo(1);
                assertThat(order).doesNotContain("complete");
                assertNoProviderOrCompletion();
            } finally {
                providerRelease.countDown();
            }
        }
        assertThat(journalStates(activeStore)).containsExactly(0, 1, 3, 2);
        assertRestartRefused(activeStore);
    }

    /** IN_FLIGHT force 뒤 scope에 멈춘 admitted 예약은 close가 lock을 보유하고 기다리며 START/future를 게시하지 못한다. */
    @Test
    void closeWaitsForForcedReservationScopeAndPreventsStartPublication() throws Exception {
        Store store = provision(scope, 65536);
        var journal = GradeRemoteOnceJournal.open(store.descriptor().toString());
        var transport = new ServerTransport(scope);
        var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles);
        transport.holdScopeCall = 2;
        transport.holdBody = true;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reservation = executor.submit(() -> owner.beginReservation(jobKey, generation));
            try {
                assertThat(transport.scopeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(journalStates(store)).containsExactly(0);
                var closing =
                        executor.submit(
                                () -> {
                                    owner.close();
                                    return true;
                                });
                assertThatThrownBy(() -> closing.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                journal.requireHeld();
                assertThatThrownBy(() -> GradeRemoteOnceJournal.open(store.descriptor().toString()))
                        .hasMessage("INVALID_REMOTE_JOURNAL");
                transport.scopeRelease.countDown();
                assertThatThrownBy(() -> reservation.get(5, TimeUnit.SECONDS))
                        .rootCause()
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThat(closing.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(transport.starts.get()).isZero();
                assertThat(transport.bodyFuture).isNull();
                assertThat(transport.completions.get()).isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT call_count FROM public.grade_job WHERE id=?",
                                        Integer.class,
                                        job))
                        .isZero();
                assertNoProviderOrCompletion();
                try (var reopened = GradeRemoteOnceJournal.open(store.descriptor().toString())) {
                    reopened.requireHeld();
                    assertThatThrownBy(() -> reopened.inFlight(jobKey, generation))
                            .hasMessage("INVALID_REMOTE_JOURNAL");
                }
            } finally {
                transport.scopeRelease.countDown();
                owner.close();
            }
        }
        assertThat(chats.get()).isZero();
    }

    /**
     * 예약 정리 전에 이미 admitted된 공개 관측도 직렬화하고 그 실제 future를 close가 취소한 뒤 lock을 해제한다.
     *
     * @param renew true는 실제 renew, false는 실제 fence이며 private 훅이나 상태를 주입하지 않음
     * @throws Exception 실제 서비스·파일·thread 동기화 실패
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admittedObservationWaitsForReservationCleanupAndCloseCancelsItsBody(boolean renew)
            throws Exception {
        activeStore = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        var owner = owner(activeStore, transport);
        transport.holdScopeCall = 2;
        transport.holdObservedBody = true;
        var reservationResult = new CompletableFuture<Void>();
        var observationResult = new CompletableFuture<Void>();
        var reservationThread =
                Thread.ofPlatform()
                        .unstarted(
                                () -> {
                                    try {
                                        owner.beginReservation(jobKey, generation);
                                        reservationResult.complete(null);
                                    } catch (Throwable exception) {
                                        reservationResult.completeExceptionally(exception);
                                    }
                                });
        var observationThread =
                Thread.ofPlatform()
                        .unstarted(
                                () -> {
                                    try {
                                        if (renew) owner.renewReservation();
                                        else owner.revalidateReservation();
                                        observationResult.complete(null);
                                    } catch (Throwable exception) {
                                        observationResult.completeExceptionally(exception);
                                    }
                                });
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                reservationThread.start();
                assertThat(transport.scopeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(journalStates(activeStore)).containsExactly(0);
                observationThread.start();
                var threads = java.lang.management.ManagementFactory.getThreadMXBean();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                java.lang.management.ThreadInfo blocked;
                do {
                    blocked = threads.getThreadInfo(observationThread.threadId());
                    if (blocked != null
                            && blocked.getThreadState() == Thread.State.BLOCKED
                            && blocked.getLockOwnerId() == reservationThread.threadId()) break;
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            TimeUnit.MILLISECONDS.toNanos(1));
                } while (System.nanoTime() < until);
                assertThat(blocked).isNotNull();
                assertThat(blocked.getThreadState()).isEqualTo(Thread.State.BLOCKED);
                assertThat(blocked.getLockOwnerId()).isEqualTo(reservationThread.threadId());
                assertThat(transport.fences.get()).isZero();
                assertThat(transport.renewals.get()).isZero();
                transport.scopeRelease.countDown();
                reservationResult.get(5, TimeUnit.SECONDS);
                assertThat(transport.observedEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(journalStates(activeStore)).containsExactly(0, 1);
                var closing =
                        executor.submit(
                                () -> {
                                    owner.close();
                                    return true;
                                });
                assertThat(closing.get(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> observationResult.get(5, TimeUnit.SECONDS))
                        .rootCause()
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThat(transport.observedBodyFuture.isCancelled()).isTrue();
                assertThat(transport.observedBodyFuture.complete(transport.heldObservedBody))
                        .isFalse();
                assertThat(transport.starts.get()).isEqualTo(1);
                assertThat(transport.fences.get()).isEqualTo(renew ? 0 : 1);
                assertThat(transport.renewals.get()).isEqualTo(renew ? 1 : 0);
                assertThat(transport.completions.get()).isZero();
                assertThat(chats.get()).isZero();
                assertNoProviderOrCompletion();
                assertRestartRefused(activeStore);
            } finally {
                transport.scopeRelease.countDown();
                owner.close();
                reservationThread.join(5000);
                observationThread.join(5000);
                assertThat(reservationThread.isAlive()).isFalse();
                assertThat(observationThread.isAlive()).isFalse();
            }
        }
    }

    /**
     * 실제 관측·provider·완료의 full body를 취소하고 admitted 호출이 종료된 뒤에만 lock을 재취득할 수 있다.
     *
     * @param phase 보류할 실제 경계 observation/provider/completion, null 불가
     * @throws Exception 실제 서비스·파일·latch 동기화 실패
     */
    @ParameterizedTest
    @ValueSource(strings = {"observation", "provider", "completion"})
    void closeQuiescesActualObservationProviderAndCompletionBodies(String phase) throws Exception {
        activeStore = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        transport.holdObservedBody = phase.equals("observation");
        transport.holdCompletionBody = phase.equals("completion");
        if (phase.equals("provider")) {
            providerRelease = new CountDownLatch(1);
            streamHeldBody = true;
        }
        var owner = owner(activeStore, transport);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> operation;
            if (phase.equals("observation")) {
                owner.beginReservation(jobKey, generation);
                operation = executor.submit(owner::revalidateReservation);
            } else operation = executor.submit(() -> owner.runOnce(jobKey, generation));
            try {
                var entered =
                        phase.equals("observation")
                                ? transport.observedEntered
                                : phase.equals("completion")
                                        ? transport.completionEntered
                                        : providerEntered;
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var closing =
                        executor.submit(
                                () -> {
                                    owner.close();
                                    return true;
                                });
                assertThat(closing.get(5, TimeUnit.SECONDS)).isTrue();
                if (phase.equals("observation")) {
                    assertThatThrownBy(() -> operation.get(5, TimeUnit.SECONDS))
                            .rootCause()
                            .hasMessage("REMOTE_RESERVATION_REFUSED");
                    assertThat(transport.observedBodyFuture.isCancelled()).isTrue();
                    assertThat(transport.observedBodyFuture.complete(transport.heldObservedBody))
                            .isFalse();
                } else {
                    var outcome =
                            (GradeRemoteBatchWorker.Outcome) operation.get(5, TimeUnit.SECONDS);
                    assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
                    assertThat(outcome.receipt()).isNull();
                    if (phase.equals("completion")) {
                        assertThat(transport.actualReceipt.accepted()).isTrue();
                        assertThat(transport.completionBodyFuture.isCancelled()).isTrue();
                        assertThat(
                                        transport.completionBodyFuture.complete(
                                                transport.heldCompletionBody))
                                .isFalse();
                    }
                }
                assertThat(operation.isDone()).isTrue();
                assertThat(transport.starts.get()).isEqualTo(1);
                assertThat(transport.completions.get())
                        .isEqualTo(phase.equals("completion") ? 1 : 0);
                assertThat(chats.get()).isEqualTo(phase.equals("observation") ? 0 : 1);
                if (!phase.equals("completion")) assertNoProviderOrCompletion();
                assertThatThrownBy(owner::revalidateReservation)
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThatThrownBy(owner::renewReservation)
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThatThrownBy(() -> owner.runOnce(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertRestartRefused(activeStore);
            } finally {
                providerRelease.countDown();
                owner.close();
            }
        }
    }

    /** 실제 짧은 보호 P와 긴 J/L에서 stalled chat만 ENGINE_TIMEOUT 완료가 되며 scheduler가 소유 실패로 바꾸지 않는다. */
    @Test
    void shortProtectedProfileTimeoutWithLiveOwnershipCompletesGenuineEngineTimeout()
            throws Exception {
        var previousInstallation = installation;
        long previousRuntime = runtime;
        var previousProfiles = profiles;
        var dictionary =
                new GradeDictionary("REMOTE_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:" + provider.getAddress().getPort()),
                        "qwen3:8b",
                        "a".repeat(64),
                        dictionary.sha256(),
                        "{{ .System }}{{ .Prompt }}{{ .Response }}",
                        65536,
                        4096,
                        0,
                        1,
                        false,
                        Duration.ofSeconds(2));
        try {
            installation =
                    new InstalledRuntimeManifestVerifier(
                            new LocalSemanticEngine(settings),
                            dictionary,
                            new InstalledProfile(
                                    "REMOTE_SHORT_TIMEOUT", "LOCAL", "1", "RULE_20260924"));
            var registered =
                    new GradeRuntimeRepository(jdbc)
                            .registerRuntime(
                                    "REMOTE_SHORT_TIMEOUT",
                                    installation.configHash(),
                                    installation.registrationManifest().toString());
            assertThat(registered.epoch()).isZero();
            runtime = registered.id();
            profiles =
                    new WorkerGradeProfileLoader()
                            .load(
                                    writeProfile(settings, dictionary, "REMOTE_SHORT_TIMEOUT")
                                            .toString());
            fixtures();
            activeStore = provision(scope, 65536);
            providerRelease = new CountDownLatch(1);
            streamHeldBody = true;
            var transport = new ServerTransport(scope);
            try (var owner = owner(activeStore, transport);
                    var executor = Executors.newSingleThreadExecutor()) {
                var run = executor.submit(() -> owner.runOnce(jobKey, generation));
                try {
                    assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    var outcome = run.get(5, TimeUnit.SECONDS);
                    assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
                    assertThat(outcome.receipt()).isEqualTo(transport.actualReceipt);
                    assertThat(outcome.receipt().accepted()).isTrue();
                    assertThat(outcome.receipt().retryScheduled()).isTrue();
                    assertThat(transport.renewals.get()).isPositive();
                    var result =
                            SnapshotJson.parse(
                                    transport
                                            .completedCommand
                                            .resultJson()
                                            .getBytes(StandardCharsets.UTF_8));
                    assertThat(result.path("kind").asText()).isEqualTo("ERROR");
                    assertThat(result.path("errorCode").asText()).isEqualTo("ENGINE_TIMEOUT");
                    assertThat(transport.completedCommand.observedProviderVersion()).isNull();
                    assertThat(transport.completedCommand.providerResponseRef()).isNull();
                } finally {
                    providerRelease.countDown();
                }
            }
            assertThat(chats.get()).isEqualTo(1);
            assertThat(transport.starts.get()).isEqualTo(1);
            assertThat(transport.completions.get()).isEqualTo(1);
            assertThat(order)
                    .containsExactly(
                            "tags", "show", "fence", "forced-chat-intent", "chat", "complete");
            assertThat(journalStates(activeStore)).containsExactly(0, 1, 3, 4, 5);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT result_cipher FROM public.grade_job WHERE id=?",
                                    byte[].class,
                                    job))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT output_cipher FROM public.grade_attempt WHERE job_id=?"
                                            + " AND attempt_no=1",
                                    byte[].class,
                                    job))
                    .isNull();
            assertRestartRefused(activeStore);
        } finally {
            providerRelease.countDown();
            installation = previousInstallation;
            runtime = previousRuntime;
            profiles = previousProfiles;
        }
    }

    /** 실제 ENGINE_ERROR의 세 예약·완료를 소비하며 어떤 모델 I/O도 수행하지 않는다. */
    @Test
    void consumedEngineErrorThreeAttemptsUseActualServiceWithoutModelIo() throws Exception {
        jdbc.update(
                "UPDATE public.grade_job SET sample_code='ENGINE',input_hash=? WHERE id=?",
                frozen.inputHash("ENGINE"),
                job);
        for (int number = 1; number <= 3; number++) {
            Store store = provision(scope, 65536);
            activeGeneration = generation;
            var transport = new ServerTransport(scope);
            try (var owner = owner(store, transport)) {
                var outcome = owner.runOnce(jobKey, generation);
                assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
                assertThat(outcome.receipt().attemptNo()).isEqualTo(number);
                assertThat(outcome.receipt().accepted()).isTrue();
                assertThat(outcome.receipt().retryScheduled()).isEqualTo(number < 3);
                assertThat(transport.completedCommand.observedProviderVersion()).isNull();
                assertThat(transport.completedCommand.providerResponseRef()).isNull();
                assertThat(transport.completedCommand.resultJson()).contains("ENGINE_TIMEOUT");
            }
            assertThat(transport.completions.get()).isEqualTo(1);
            assertThat(order).doesNotContain("tags", "show", "chat");
            assertThat(chats.get()).isZero();
            assertThat(journalStates(store)).containsExactly(0, 1, 4, 5);
            assertRestartRefused(store);
            if (number < 3) {
                generation++;
                assertThat(
                                jdbc.update(
                                        "UPDATE public.grade_job SET"
                                            + " state='RUNNING',worker_key=?,lease_gen=?,lease_until=clock_timestamp()+interval"
                                            + " '30 seconds' WHERE id=? AND state='QUEUED'",
                                        scope.workerKey(),
                                        generation,
                                        job))
                        .isEqualTo(1);
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT call_count FROM public.grade_job WHERE id=?",
                                Integer.class,
                                job))
                .isEqualTo(3);
    }

    /**
     * 닫힘·거절·불확실 결과의 모든 실제 key는 재시작 START 전에 거절된다.
     *
     * @param store 동일 실제 journal/lock/header
     * @throws Exception 보호 경로·close 실패
     */
    private void assertRestartRefused(Store store) throws Exception {
        var restarted = new ServerTransport(scope);
        try (var owner = owner(store, restarted)) {
            assertThat(owner.runOnce(jobKey, generation).state())
                    .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
            assertThat(restarted.starts.get()).isZero();
            assertThat(restarted.completions.get()).isZero();
        }
    }

    /** 실제 NEW/epoch0·현재 tuple·fence·renew를 먼저 통과시켜 음성 fixture의 기반을 확인한다. */
    @Test
    void originalNewOwnsOnlyThisInvocationAndClosePreservesTombstone() throws Exception {
        Store store = provision(scope, 65536);
        ServerTransport transport = new ServerTransport(scope);
        AtomicInteger acknowledgements = new AtomicInteger();
        transport.forceAcknowledgements = acknowledgements;
        var journal =
                openWithForce(
                        store,
                        channel -> {
                            channel.force(true);
                            acknowledgements.incrementAndGet();
                        });
        try (var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles)) {
            owner.beginReservation(jobKey, generation);
            var input = owner.reservedInput();
            assertThat(input.runtime().epoch()).isZero();
            assertThat(input.identity().jobKey()).isEqualTo(jobKey);
            assertThat(input.identity().leaseGen()).isEqualTo(generation);
            assertThat(input.identity().attemptNo()).isEqualTo(1);
            assertThat(CommonUtil.sha256(input.modelInput().bytes()))
                    .isEqualTo(input.modelInput().hash());
            assertThat(owner.requestStartedNano()).isLessThanOrEqualTo(System.nanoTime());
            owner.revalidateReservation();
            owner.renewReservation();
            assertThat(transport.starts.get()).isEqualTo(1);
            assertThat(transport.fences.get()).isEqualTo(1);
            assertThat(transport.renewals.get()).isEqualTo(1);
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        assertNoProviderOrCompletion();
        ServerTransport restarted = new ServerTransport(scope);
        try (var owner = owner(store, restarted)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(restarted.starts.get()).isZero();
            assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
        }
    }

    /** 앞선 두 실패는 실제 COMPLETE fixture로 종료하며 세대만 정상 fixture로 재할당한다. provider 증거가 아니다. */
    @Test
    void actualServerAttemptThreeIsNotRejectedOrBudgetReset() throws Exception {
        var completion =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of("REMOTE_SYNTHETIC", installation),
                        crypto,
                        "REMOTE_COORDINATOR");
        for (int number = 1; number <= 2; number++) {
            var reply = service.startApproved(worker, jobKey, generation);
            assertThat(reply.attemptNo()).isEqualTo(number);
            completion.complete(
                    worker,
                    jobKey,
                    generation,
                    number,
                    null,
                    null,
                    "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_UNAVAILABLE\"}",
                    UUID.randomUUID());
            generation++;
            assertThat(
                            jdbc.update(
                                    "UPDATE public.grade_job SET"
                                        + " state='RUNNING',worker_key=?,lease_gen=?,lease_until=clock_timestamp()+interval"
                                        + " '30 seconds' WHERE id=? AND state='QUEUED'",
                                    scope.workerKey(),
                                    generation,
                                    job))
                    .isEqualTo(1);
        }
        var transport = new ServerTransport(scope);
        try (var owner = owner(provision(scope, 65536), transport)) {
            owner.beginReservation(jobKey, generation);
            assertThat(owner.reservedInput().identity().attemptNo()).isEqualTo(3);
            owner.revalidateReservation();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT call_count FROM public.grade_job WHERE id=?",
                                Integer.class,
                                job))
                .isEqualTo(3);
        assertThat(transport.starts.get()).isEqualTo(1);
    }

    /**
     * 실제 완료 fixture의 재대기·세 번째 소진 영수증을 raw decoder로 대조한다. 추론 실패 실행 증거는 아니다.
     *
     * @throws Exception 실제 폐기형 서버 완료·응답 해석 실패
     */
    @Test
    void actualCompletionReceiptsKeepExactNullsAndTerminalCombinations() throws Exception {
        var completion =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of("REMOTE_SYNTHETIC", installation),
                        crypto,
                        "REMOTE_COORDINATOR");
        for (int number = 1; number <= 3; number++) {
            service.startApproved(worker, jobKey, generation);
            var receipt =
                    completion.complete(
                            worker,
                            jobKey,
                            generation,
                            number,
                            null,
                            null,
                            "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_UNAVAILABLE\"}",
                            UUID.randomUUID());
            ObjectNode actual = receiptNode(receipt);
            assertThat(GradeRemoteReplyDecoder.decodeCompletion(SnapshotJson.encode(actual)))
                    .isEqualTo(receipt);
            String original = new String(SnapshotJson.encode(actual), StandardCharsets.UTF_8);
            String coordinate = "\"attemptNo\":" + number + ",";
            assertThat(original).contains(coordinate);
            for (String floating : List.of(number + ".0", number + "e0")) {
                byte[] raw =
                        original.replace(coordinate, "\"attemptNo\":" + floating + ",")
                                .getBytes(StandardCharsets.UTF_8);
                assertThatThrownBy(() -> GradeRemoteReplyDecoder.decodeCompletion(raw))
                        .hasMessage("INVALID_REMOTE_REPLY")
                        .hasNoCause();
            }
            for (var mutation :
                    List.<UnaryOperator<ObjectNode>>of(
                            node -> node.put("extra", true),
                            node -> node.put("attemptNo", 4294967297L),
                            node -> node.put("accepted", "true"),
                            node -> node.put("accepted", false),
                            node -> node.put("retryScheduled", !receipt.retryScheduled()),
                            node -> node.put("reason", "NONE"),
                            node -> node.put("state", "UNKNOWN"),
                            node -> node.putNull("state"),
                            node -> node.put("outcome", 1),
                            node -> node.put("requestId", new UUID(0, 0).toString()))) {
                assertThatThrownBy(
                                () ->
                                        GradeRemoteReplyDecoder.decodeCompletion(
                                                SnapshotJson.encode(
                                                        mutation.apply(actual.deepCopy()))))
                        .hasMessage("INVALID_REMOTE_REPLY")
                        .hasNoCause();
            }
            if (number < 3) {
                generation++;
                assertThat(
                                jdbc.update(
                                        "UPDATE public.grade_job SET"
                                            + " state='RUNNING',worker_key=?,lease_gen=?,lease_until=clock_timestamp()+interval"
                                            + " '30 seconds' WHERE id=? AND state='QUEUED'",
                                        scope.workerKey(),
                                        generation,
                                        job))
                        .isEqualTo(1);
            }
        }
    }

    /**
     * 실제 서버 영수증을 공개 여덟 필드로만 인코딩한다. 새 성공/실패 값을 제조하지 않는다.
     *
     * @param receipt null 불가인 실제 서비스 반환 영수증
     * @return 실제 nullable outcome을 유지한 원본 응답 객체
     */
    private static ObjectNode receiptNode(GradeCompletionService.CompletionReceipt receipt) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .put("jobKey", receipt.jobKey().toString())
                .put("attemptNo", receipt.attemptNo())
                .put("accepted", receipt.accepted())
                .put("state", receipt.state())
                .put("retryScheduled", receipt.retryScheduled())
                .put("outcome", receipt.outcome())
                .put("reason", receipt.reason())
                .put("requestId", receipt.requestId().toString());
    }

    /** 실제 ENGINE_ERROR fixture는 예약만 소유하며 fault 조합을 검사하고 provider/COMPLETE로 전달하지 않는다. */
    @Test
    void actualEngineErrorAndIllegalVariantCombinationsAreSeparated() throws Exception {
        jdbc.update(
                "UPDATE public.grade_job SET sample_code='ENGINE',input_hash=? WHERE id=?",
                frozen.inputHash("ENGINE"),
                job);
        try (var owner = owner(provision(scope, 65536), new ServerTransport(scope))) {
            owner.beginReservation(jobKey, generation);
            assertThat(owner.reservedInput().variant()).isEqualTo("ENGINE_ERROR");
            assertThat(owner.reservedInput().modelInput()).isNull();
            assertThat(owner.reservedInput().fault().failRuns()).isEqualTo(3);
            owner.revalidateReservation();
        }
        assertNoProviderOrCompletion();
        for (String field : List.of("type", "failRuns", "modelInput")) {
            fixtures();
            jdbc.update(
                    "UPDATE public.grade_job SET sample_code='ENGINE',input_hash=? WHERE id=?",
                    frozen.inputHash("ENGINE"),
                    job);
            var transport = new ServerTransport(scope);
            transport.startMutation =
                    bytes -> {
                        var node = (ObjectNode) SnapshotJson.parse(bytes);
                        if (field.equals("type"))
                            change(node, "input.fault", "type", "INPUT_ERROR");
                        if (field.equals("failRuns")) change(node, "input.fault", "failRuns", 2);
                        if (field.equals("modelInput"))
                            ((ObjectNode) node.get("input")).putObject("modelInput");
                        return SnapshotJson.encode(node);
                    };
            try (var owner = owner(provision(scope, 65536), transport)) {
                assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
            }
        }
    }

    /**
     * 실제 원본 NEW의 설치 좌표 문법 위반은 OWNED 상태에 들어가지 못해야 한다.
     *
     * @param coordinate 기존 설치 좌표의 합성 단일 변조 종류, null 불가
     * @throws Exception 실제 서버·보호 파일 조립 실패
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "engineVersion",
                "engineVersionLength",
                "modelTag",
                "modelLength",
                "cloud",
                "cloudUpper",
                "modelDigest",
                "pinMode",
                "reportContract"
            })
    void invalidRuntimeCoordinatesNeverBecomeOwned(String coordinate) throws Exception {
        try (var positive = owner(provision(scope, 65536), new ServerTransport(scope))) {
            positive.beginReservation(jobKey, generation);
            assertThat(positive.reservedInput().runtime().epoch()).isZero();
        }
        fixtures();
        var transport = new ServerTransport(scope);
        transport.startMutation =
                bytes -> {
                    var node = (ObjectNode) SnapshotJson.parse(bytes);
                    switch (coordinate) {
                        case "engineVersion" ->
                                change(node, "input.runtime", "engineVersion", "x y");
                        case "engineVersionLength" ->
                                change(node, "input.runtime", "engineVersion", "x".repeat(101));
                        case "modelTag" -> change(node, "input.runtime", "modelId", "qwen3");
                        case "modelLength" ->
                                change(node, "input.runtime", "modelId", "a".repeat(199) + ":b");
                        case "cloud" -> change(node, "input.runtime", "modelId", "cloud:tag");
                        case "cloudUpper" -> change(node, "input.runtime", "modelId", "CLOUD:tag");
                        case "modelDigest" ->
                                change(node, "input.runtime", "modelVersion", "not-a-digest");
                        case "pinMode" -> change(node, "input.runtime", "pinMode", "anything");
                        case "reportContract" ->
                                change(
                                        node,
                                        "input.runtime",
                                        "reportContractVersion",
                                        "REPORT-999");
                        default -> throw new IllegalArgumentException("INVALID_TEST_MUTATION");
                    }
                    return SnapshotJson.encode(node);
                };
        try (var owner = owner(provision(scope, 65536), transport)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED")
                    .hasNoCause();
            assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        assertThat(transport.starts.get()).isEqualTo(1);
        assertNoProviderOrCompletion();
    }

    /**
     * 실제 관측 body를 기다리는 동안 journal lock이 해제되면 관측 응답을 채택하지 않는다.
     *
     * @param renewal true이면 실제 RENEW, false이면 실제 FENCE
     * @throws Exception 실제 서버·파일·완전 응답 동기화 실패
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void journalLossBeforeObservedBodyCompletionRefusesAdoption(boolean renewal) throws Exception {
        Store store = provision(scope, 65536);
        var journal = GradeRemoteOnceJournal.open(store.descriptor().toString());
        var transport = new ServerTransport(scope);
        transport.holdObservedBody = true;
        try (var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles);
                var executor = Executors.newSingleThreadExecutor()) {
            owner.beginReservation(jobKey, generation);
            var observation =
                    executor.submit(
                            () -> {
                                if (renewal) owner.renewReservation();
                                else owner.revalidateReservation();
                            });
            assertThat(transport.observedEntered.await(5, TimeUnit.SECONDS)).isTrue();
            journal.close();
            transport.observedBodyFuture.complete(transport.heldObservedBody);
            assertThatThrownBy(() -> observation.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessage("REMOTE_RESERVATION_REFUSED")
                    .hasNoCause();
            assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        var restarted = new ServerTransport(scope);
        try (var owner = owner(store, restarted)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(restarted.starts.get()).isZero();
        }
        assertNoProviderOrCompletion();
    }

    /** 외부 resource alias가 lock 수명을 끝내도 기존 private 예약을 다시 사용할 수 없다. */
    @Test
    void releasedJournalDestroysInMemoryReservationAuthority() throws Exception {
        Store store = provision(scope, 65536);
        var journal = GradeRemoteOnceJournal.open(store.descriptor().toString());
        var transport = new ServerTransport(scope);
        try (var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles)) {
            owner.beginReservation(jobKey, generation);
            journal.close();
            assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThatThrownBy(owner::revalidateReservation)
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(transport.fences.get()).isZero();
        }
        var restarted = new ServerTransport(scope);
        try (var owner = owner(store, restarted)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(restarted.starts.get()).isZero();
        }
    }

    /** 이미 실제 서버가 예약한 REPLAY는 명시 null이며 새 저널에서도 소유권을 주지 않는다. */
    @Test
    void serverReplayAndLostOriginalResponseNeverOwnOrResend() throws Exception {
        service.startApproved(worker, jobKey, generation);
        Store replayStore = provision(scope, 65536);
        var replay = new ServerTransport(scope);
        try (var owner = owner(replayStore, replay)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        assertThat(replay.starts.get()).isEqualTo(1);
        fixtures();
        Store lostStore = provision(scope, 65536);
        var lost = new ServerTransport(scope);
        lost.loseStart = true;
        try (var owner = owner(lostStore, lost)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        var restarted = new ServerTransport(scope);
        try (var owner = owner(lostStore, restarted)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(restarted.starts.get()).isZero();
        }
        assertNoProviderOrCompletion();
    }

    /** 실제 channel force 이전 START=0, OWNED force 실패 뒤 NEW 소유권도 파기되는지 확인한다. */
    @Test
    void failingActualForceBoundaryNeverBestEffortContinues() throws Exception {
        for (int failingForce : List.of(2, 3)) {
            fixtures();
            Store store = provision(scope, 65536);
            var transport = new ServerTransport(scope);
            AtomicInteger forces = new AtomicInteger();
            GradeRemoteOnceJournal journal = openWithForceFailure(store, forces, failingForce);
            try (var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles)) {
                assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThat(transport.starts.get()).isEqualTo(failingForce == 2 ? 0 : 1);
                assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
            }
            assertThat(Files.size(store.journal()))
                    .isGreaterThan(GradeRemoteOnceJournal.provisionedHeader(scope).length);
            var restarted = new ServerTransport(scope);
            try (var owner = owner(store, restarted)) {
                assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThat(restarted.starts.get()).isZero();
            }
        }
    }

    /** open/terminal force 실패도 새 START나 복원 가능한 실행 상태를 만들지 않는다. */
    @Test
    void openAndTerminalForceFailuresRemainClosed() throws Exception {
        Store opening = provision(scope, 65536);
        assertThatThrownBy(() -> openWithForceFailure(opening, new AtomicInteger(), 1))
                .hasMessage("INVALID_REMOTE_JOURNAL");
        Store terminal = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        var journal = openWithForceFailure(terminal, new AtomicInteger(), 4);
        var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), profiles);
        owner.beginReservation(jobKey, generation);
        assertThatThrownBy(owner::close).hasMessage("INVALID_REMOTE_JOURNAL");
        assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
        var restarted = new ServerTransport(scope);
        try (var next = owner(terminal, restarted)) {
            assertThatThrownBy(() -> next.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(restarted.starts.get()).isZero();
        }
    }

    /** frame·header 손상/잘림/빈 replacement와 capacity 부족은 수선·삭제·START 없이 거절한다. */
    @Test
    void corruptTruncatedEmptyAndCapacityFilesFailClosedWithoutRepair() throws Exception {
        for (int damage : List.of(0, 1, 2, 3)) {
            Store store = provision(scope, 65536);
            byte[] header = Files.readAllBytes(store.journal());
            if (damage == 0) header[header.length - 1] ^= 1;
            if (damage == 1) header = java.util.Arrays.copyOf(header, header.length - 1);
            if (damage == 2) header = new byte[0];
            if (damage == 3)
                header =
                        ByteBuffer.allocate(header.length + 3).put(header).put(new byte[3]).array();
            Files.write(store.journal(), header);
            byte[] before = Files.readAllBytes(store.journal());
            assertThatThrownBy(() -> GradeRemoteOnceJournal.open(store.descriptor().toString()))
                    .hasMessage("INVALID_REMOTE_JOURNAL");
            assertThat(Files.readAllBytes(store.journal())).isEqualTo(before);
        }
        int headerBytes = GradeRemoteOnceJournal.provisionedHeader(scope).length;
        Store small = provision(scope, headerBytes + 125);
        var transport = new ServerTransport(scope);
        try (var owner = owner(small, transport)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(transport.starts.get()).isZero();
            assertThatThrownBy(() -> owner.beginReservation(UUID.randomUUID(), 1))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        assertThat(Files.size(small.journal())).isEqualTo(headerBytes);
        Store damagedRecord = provision(scope, 65536);
        try (var owner = owner(damagedRecord, new ServerTransport(scope))) {
            owner.beginReservation(jobKey, generation);
        }
        byte[] records = Files.readAllBytes(damagedRecord.journal());
        records[headerBytes + 12] ^= 1;
        Files.write(damagedRecord.journal(), records);
        assertThatThrownBy(() -> GradeRemoteOnceJournal.open(damagedRecord.descriptor().toString()))
                .hasMessage("INVALID_REMOTE_JOURNAL");
    }

    /** 실제 두 thread와 교체 digest가 같은 inode lock·고정 헤더에 경쟁한다. 자격 폐기를 증명하지 않는다. */
    @Test
    void threadsAndCredentialRotationUseOneStableLockAndHeader() throws Exception {
        Store store = provision(scope, 65536);
        byte[] header = Files.readAllBytes(store.journal());
        Scope rotated = new Scope(scope.serverScopeId(), scope.workerKey(), "b".repeat(64));
        try (var original = GradeRemoteOnceJournal.open(store.descriptor().toString());
                var executor = Executors.newSingleThreadExecutor()) {
            var denial =
                    executor.submit(
                            () -> {
                                assertThatThrownBy(
                                                () ->
                                                        GradeRemoteOnceJournal.open(
                                                                store.descriptor().toString()))
                                        .hasMessage("INVALID_REMOTE_JOURNAL");
                            });
            denial.get(5, TimeUnit.SECONDS);
            writeDescriptor(store, rotated, 65536);
            assertThatThrownBy(() -> GradeRemoteOnceJournal.open(store.descriptor().toString()))
                    .hasMessage("INVALID_REMOTE_JOURNAL");
            original.inFlight(jobKey, generation);
        }
        try (var reopened = GradeRemoteOnceJournal.open(store.descriptor().toString())) {
            assertThat(reopened.scope()).isEqualTo(rotated);
            assertThatThrownBy(() -> reopened.inFlight(jobKey, generation))
                    .hasMessage("INVALID_REMOTE_JOURNAL");
        }
        assertThat(java.util.Arrays.copyOf(Files.readAllBytes(store.journal()), header.length))
                .isEqualTo(header);
    }

    /** 실제 positive NEW를 변조한 각 body가 owner의 입력 경계에서 거절되고 tombstone만 남는지 검사한다. */
    @Test
    void closedEnvelopeWrongTypesNullsTuplesAndEncodingNeverOwn() throws Exception {
        List<UnaryOperator<ObjectNode>> mutations =
                List.of(
                        node -> node.put("extra", true),
                        node -> node.put("formatNo", "1"),
                        node -> node.put("leaseGen", 0),
                        node -> node.put("leaseGen", "1"),
                        node ->
                                node.put(
                                        "leaseGen",
                                        new java.math.BigInteger("9223372036854775808")),
                        node -> node.put("attemptNo", 4),
                        node -> node.put("jobKey", new UUID(0, 0).toString()),
                        node -> node.put("jobKey", UUID.randomUUID().toString()),
                        node -> node.putNull("input"),
                        node -> node.putNull("limits"),
                        node -> node.put("disposition", "REPLAY"),
                        node -> {
                            node.remove("limits");
                            return node;
                        },
                        node -> change(node, "input", "leaseGen", 2),
                        node -> change(node, "input", "attemptNo", 2),
                        node -> change(node, "input", "snapshotId", "9223372036854775808"),
                        node -> change(node, "input", "snapshotId", "01"),
                        node -> change(node, "input", "sourceKind", "PILOT"),
                        node -> change(node, "input", "variant", "INPUT_ERROR"),
                        node -> change(node, "input", "payloadHash", "A".repeat(64)),
                        node -> change(node, "input.runtime", "epoch", -1),
                        node -> change(node, "input.runtime", "epoch", "0"),
                        node -> change(node, "input.runtime", "epoch", 0.5),
                        node ->
                                change(
                                        node,
                                        "input.runtime",
                                        "epoch",
                                        new java.math.BigInteger("9223372036854775808")),
                        node -> change(node, "input.runtime", "extra", "wrong"),
                        node -> change(node, "limits", "remainingLeaseMillis", 0),
                        node -> change(node, "limits", "remainingBudgetMillis", 120001),
                        node -> change(node, "limits", "remainingBudgetMillis", 1.0),
                        node ->
                                change(
                                        node,
                                        "limits",
                                        "deadlineAt",
                                        java.time.Instant.parse(
                                                        node.get("input")
                                                                .get("deadlineAt")
                                                                .textValue())
                                                .plusSeconds(1)
                                                .toString()),
                        node -> change(node, "limits", "leaseUntil", "2026-10-04T00:00:00+00:00"),
                        node -> change(node, "input.modelInput", "encoding", "UTF8"),
                        node -> change(node, "input.modelInput", "bytesBase64", "e30"),
                        node -> change(node, "input.modelInput", "sha256", "0".repeat(64)),
                        node -> change(node, "input.modelInput", "bytesBase64", "@@=="));
        for (var mutation : mutations) {
            fixtures();
            var transport = new ServerTransport(scope);
            transport.startMutation =
                    bytes ->
                            SnapshotJson.encode(
                                    mutation.apply((ObjectNode) SnapshotJson.parse(bytes)));
            try (var owner = owner(provision(scope, 65536), transport)) {
                assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
            }
            assertThat(transport.starts.get()).isEqualTo(1);
            assertNoProviderOrCompletion();
        }
    }

    /** decoded escaped duplicate·후행 토큰·invalid UTF8와 REPLAY 명시 null 위반을 실제 경로에서 거절한다. */
    @Test
    void strictRawBytesAndReplayNonNullReject() throws Exception {
        List<UnaryOperator<byte[]>> mutations =
                List.of(
                        bytes ->
                                (new String(bytes, StandardCharsets.UTF_8) + " {}")
                                        .getBytes(StandardCharsets.UTF_8),
                        bytes ->
                                ("{\"\\u0066ormatNo\":1,"
                                                + new String(bytes, StandardCharsets.UTF_8)
                                                        .substring(1))
                                        .getBytes(StandardCharsets.UTF_8),
                        bytes ->
                                ByteBuffer.allocate(bytes.length + 1)
                                        .put(bytes)
                                        .put((byte) 0xff)
                                        .array());
        for (var mutation : mutations) {
            fixtures();
            var transport = new ServerTransport(scope);
            transport.startMutation = mutation;
            try (var owner = owner(provision(scope, 65536), transport)) {
                assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
            }
        }
        fixtures();
        var first = service.startApproved(worker, jobKey, generation).toJson();
        var transport = new ServerTransport(scope);
        transport.startMutation =
                bytes -> {
                    var replay = (ObjectNode) SnapshotJson.parse(bytes);
                    replay.set("input", first.get("input"));
                    replay.set("limits", first.get("limits"));
                    return SnapshotJson.encode(replay);
                };
        try (var owner = owner(provision(scope, 65536), transport)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
    }

    /** 실제 fence/renew body의 원래 tuple/hash/deadline 위반은 소유권을 종료한다. */
    @Test
    void observedMismatchCannotReviveOrExtendOriginalOwnership() throws Exception {
        for (boolean renew : List.of(false, true)) {
            for (String field :
                    List.of(
                            "jobKey",
                            "leaseGen",
                            "attemptNo",
                            "originalAttemptHash",
                            "deadlineAt")) {
                fixtures();
                var transport = new ServerTransport(scope);
                transport.observedMutation =
                        bytes -> {
                            var node = (ObjectNode) SnapshotJson.parse(bytes);
                            if (field.equals("jobKey"))
                                node.put(field, UUID.randomUUID().toString());
                            else if (field.equals("leaseGen")) node.put(field, 2);
                            else if (field.equals("attemptNo")) node.put(field, 2);
                            else if (field.equals("originalAttemptHash"))
                                node.put(field, "0".repeat(64));
                            else
                                ((ObjectNode) node.get("limits"))
                                        .put(
                                                field,
                                                java.time.Instant.parse(
                                                                node.get("limits")
                                                                        .get(field)
                                                                        .textValue())
                                                        .plusSeconds(1)
                                                        .toString());
                            return SnapshotJson.encode(node);
                        };
                try (var owner = owner(provision(scope, 65536), transport)) {
                    owner.beginReservation(jobKey, generation);
                    assertThatThrownBy(
                                    () -> {
                                        if (renew) owner.renewReservation();
                                        else owner.revalidateReservation();
                                    })
                            .hasMessage("REMOTE_RESERVATION_REFUSED");
                    assertThatThrownBy(owner::reservedInput)
                            .hasMessage("REMOTE_RESERVATION_REFUSED");
                }
                assertNoProviderOrCompletion();
            }
        }
    }

    /** 더 큰 fence는 J/L을 늘리지 못하며 이전 lease 이후 도착한 실제 renew도 소유권을 살리지 못한다. */
    @Test
    void sameOriginalCapsSurviveFenceAndLateRenewCannotRevive() throws Exception {
        var fence = new ServerTransport(scope);
        fence.observedMutation =
                bytes -> {
                    var node = (ObjectNode) SnapshotJson.parse(bytes);
                    ((ObjectNode) node.get("limits"))
                            .put("remainingBudgetMillis", 120000)
                            .put("remainingLeaseMillis", 30000);
                    return SnapshotJson.encode(node);
                };
        try (var owner = owner(provision(scope, 65536), fence)) {
            owner.beginReservation(jobKey, generation);
            long originalJob = ownerExpiry(owner, "jobExpiry");
            long originalLease = ownerExpiry(owner, "leaseExpiry");
            owner.revalidateReservation();
            assertThat(ownerExpiry(owner, "jobExpiry")).isEqualTo(originalJob);
            assertThat(ownerExpiry(owner, "leaseExpiry")).isEqualTo(originalLease);
        }
        fixtures();
        var renew = new ServerTransport(scope);
        renew.startMutation =
                bytes -> {
                    var node = (ObjectNode) SnapshotJson.parse(bytes);
                    ((ObjectNode) node.get("limits")).put("remainingLeaseMillis", 500);
                    return SnapshotJson.encode(node);
                };
        renew.observedMutation =
                bytes -> {
                    java.util.concurrent.locks.LockSupport.parkNanos(600_000_000L);
                    return bytes;
                };
        try (var owner = owner(provision(scope, 65536), renew)) {
            owner.beginReservation(jobKey, generation);
            assertThatThrownBy(owner::renewReservation).hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(renew.renewals.get()).isEqualTo(1);
            assertThatThrownBy(owner::reservedInput).hasMessage("REMOTE_RESERVATION_REFUSED");
        }
    }

    /** 전체 body 지연·OWNED force 지연은 수신시각으로 예산을 재시작하지 않는다. */
    @Test
    void bodyParsingAndForceConsumeRequestStartBudget() throws Exception {
        Store store = provision(scope, 65536);
        var transport = new ServerTransport(scope);
        transport.startMutation =
                bytes -> {
                    var node = (ObjectNode) SnapshotJson.parse(bytes);
                    ((ObjectNode) node.get("limits"))
                            .put("remainingBudgetMillis", 1)
                            .put("remainingLeaseMillis", 1);
                    return SnapshotJson.encode(node);
                };
        try (var owner = owner(store, transport)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        fixtures();
        Store delayed = provision(scope, 65536);
        AtomicInteger forces = new AtomicInteger();
        var slowJournal =
                openWithForce(
                        delayed,
                        channel -> {
                            if (forces.incrementAndGet() == 3) Thread.sleep(40);
                            channel.force(true);
                        });
        var slow = new ServerTransport(scope);
        slow.startMutation =
                bytes -> {
                    var node = (ObjectNode) SnapshotJson.parse(bytes);
                    ((ObjectNode) node.get("limits"))
                            .put("remainingBudgetMillis", 30)
                            .put("remainingLeaseMillis", 30);
                    return SnapshotJson.encode(node);
                };
        try (var owner = new GradeRemoteBatchWorker(slowJournal, slow, bounds(), profiles)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
        }
        fixtures();
        var pending = new ServerTransport(scope);
        pending.holdBody = true;
        try (var owner =
                new GradeRemoteBatchWorker(
                        GradeRemoteOnceJournal.open(
                                provision(scope, 65536).descriptor().toString()),
                        pending,
                        new TransportBounds(8 * 1024 * 1024, Duration.ofMillis(100)),
                        profiles)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(pending.bodyFuture.isCancelled()).isTrue();
        }
    }

    /** 부호·overflow·정확한 1ms 경계는 receipt-time reset이나 clamp 없이 검사한다. */
    @Test
    void checkedMonotonicMathRejectsAmbiguousAndSubmillisecondValues() throws Exception {
        assertThat(ownerMath("expiry", new Class<?>[] {long.class, long.class}, 10L, 1L))
                .isEqualTo(1_000_010L);
        assertThatThrownBy(
                        () ->
                                ownerMath(
                                        "expiry",
                                        new Class<?>[] {long.class, long.class},
                                        Long.MAX_VALUE,
                                        1L))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(
                        () ->
                                ownerMath(
                                        "expiry",
                                        new Class<?>[] {long.class, long.class},
                                        0L,
                                        Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(
                        () ->
                                ownerMath(
                                        "elapsed",
                                        new Class<?>[] {long.class, long.class},
                                        10L,
                                        9L))
                .hasMessage("REMOTE_RESERVATION_REFUSED");
        assertThatThrownBy(
                        () ->
                                ownerMath(
                                        "elapsed",
                                        new Class<?>[] {long.class, long.class},
                                        Long.MIN_VALUE,
                                        Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(
                        () ->
                                ownerMath(
                                        "requireMillisecond",
                                        new Class<?>[] {long.class},
                                        999_999L))
                .hasMessage("REMOTE_RESERVATION_REFUSED");
        ownerMath("requireMillisecond", new Class<?>[] {long.class}, 1_000_000L);
        assertThatThrownBy(() -> new TransportBounds(1, Duration.ZERO))
                .hasMessage("INVALID_REMOTE_OWNER");
        assertThatThrownBy(() -> new TransportBounds(0, Duration.ofSeconds(1)))
                .hasMessage("INVALID_REMOTE_OWNER");
        assertThatThrownBy(() -> new TransportBounds(1, Duration.ofSeconds(Long.MAX_VALUE)))
                .hasMessage("INVALID_REMOTE_OWNER");
    }

    /** 명시적 adapter byte 상한과 신뢰 조립 scope 불일치는 실행 권위를 주지 않는다. */
    @Test
    void assemblyMismatchAndExplicitCompleteBodyBoundNeverOwn() throws Exception {
        Store store = provision(scope, 65536);
        try (var journal = GradeRemoteOnceJournal.open(store.descriptor().toString())) {
            var foreign =
                    new ServerTransport(
                            new Scope(
                                    UUID.randomUUID(),
                                    scope.workerKey(),
                                    scope.credentialSha256()));
            assertThatThrownBy(
                            () -> new GradeRemoteBatchWorker(journal, foreign, bounds(), profiles))
                    .hasMessage("INVALID_REMOTE_OWNER");
            assertThat(foreign.starts.get()).isZero();
        }
        var transport = new ServerTransport(scope);
        try (var owner =
                new GradeRemoteBatchWorker(
                        GradeRemoteOnceJournal.open(store.descriptor().toString()),
                        transport,
                        new TransportBounds(1, Duration.ofSeconds(10)),
                        profiles)) {
            assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                    .hasMessage("REMOTE_RESERVATION_REFUSED");
            assertThat(transport.starts.get()).isEqualTo(1);
        }
    }

    /** actual file/UID/mode 보호 검사와 닫힌 descriptor의 범위를 확인한다. */
    @Test
    void protectedProvisioningRejectsPermissionsSymlinksAndOpenFields() throws Exception {
        for (String field : List.of("unknown", "capacityBytes", "journalFile", "serverScopeId")) {
            Store store = provision(scope, 65536);
            var node = (ObjectNode) SnapshotJson.parse(Files.readAllBytes(store.descriptor()));
            if (field.equals("unknown")) node.put(field, true);
            if (field.equals("capacityBytes")) node.put(field, 0);
            if (field.equals("journalFile")) node.put(field, "../journal");
            if (field.equals("serverScopeId")) node.put(field, new UUID(0, 0).toString());
            Files.write(store.descriptor(), SnapshotJson.encode(node));
            assertThatThrownBy(() -> GradeRemoteOnceJournal.open(store.descriptor().toString()))
                    .hasMessage("INVALID_REMOTE_JOURNAL");
        }
        Store mode = provision(scope, 65536);
        Files.setPosixFilePermissions(mode.journal(), PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> GradeRemoteOnceJournal.open(mode.descriptor().toString()))
                .hasMessage("INVALID_REMOTE_JOURNAL");
        Store parent = provision(scope, 65536);
        Files.setPosixFilePermissions(
                parent.directory(), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> GradeRemoteOnceJournal.open(parent.descriptor().toString()))
                .hasMessage("INVALID_REMOTE_JOURNAL");
        Store linked = provision(scope, 65536);
        Path alias = linked.directory().resolve("alias.json");
        Files.createSymbolicLink(alias, linked.descriptor());
        assertThatThrownBy(() -> GradeRemoteOnceJournal.open(alias.toString()))
                .hasMessage("INVALID_REMOTE_JOURNAL");
    }

    /** 두 프로세스 lock 경쟁과 IN_FLIGHT/OWNED 뒤 강제 종료의 재시작 refusal만 증명한다. 전원 손실 시험이 아니다. */
    @Test
    void actualChildProcessExclusionAndCrashNeverReconstructReservation() throws Exception {
        for (boolean own : List.of(false, true)) {
            fixtures();
            Store store = provision(scope, 65536);
            Process child = child(store);
            try (var output =
                            new BufferedReader(
                                    new InputStreamReader(
                                            child.getInputStream(), StandardCharsets.UTF_8));
                    var input =
                            new PrintWriter(
                                    child.getOutputStream(), true, StandardCharsets.UTF_8)) {
                assertThat(readChild(output, child)).isEqualTo("START");
                assertThatThrownBy(() -> GradeRemoteOnceJournal.open(store.descriptor().toString()))
                        .hasMessage("INVALID_REMOTE_JOURNAL");
                if (own) {
                    byte[] body =
                            SnapshotJson.encode(
                                    service.startApproved(worker, jobKey, generation).toJson());
                    input.println(Base64.getEncoder().encodeToString(body));
                    assertThat(readChild(output, child)).isEqualTo("OWNED");
                }
                child.destroyForcibly();
                assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                child.destroyForcibly();
                assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            var transport = new ServerTransport(scope);
            try (var owner = owner(store, transport)) {
                assertThatThrownBy(() -> owner.beginReservation(jobKey, generation))
                        .hasMessage("REMOTE_RESERVATION_REFUSED");
                assertThat(transport.starts.get()).isZero();
            }
            assertNoProviderOrCompletion();
        }
    }

    /**
     * child fixture는 부모의 실제 server-service START complete bytes만 IPC로 받는다. 네트워크 인증 증거가 아니다.
     *
     * @param args 보호 descriptor·작업 UUID·실제 세대·보호 profile 경로 네 문자열, null 불가
     * @throws Exception 파일/IPC/owner 실패 시 child 종료
     */
    public static void main(String[] args) throws Exception {
        var journal = GradeRemoteOnceJournal.open(args[0]);
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var transport =
                new BoundTransport() {
                    public Scope scope() {
                        return journal.scope();
                    }

                    public CompletableFuture<byte[]> start(UUID key, long gen, Duration wait) {
                        System.out.println("START");
                        System.out.flush();
                        return CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return Base64.getDecoder().decode(reader.readLine());
                                    } catch (Exception exception) {
                                        throw new IllegalStateException("CHILD_BODY_LOST");
                                    }
                                });
                    }

                    public CompletableFuture<byte[]> fence(
                            UUID key, AttemptRequest request, Duration wait) {
                        throw new IllegalStateException("CHILD_FENCE_NOT_REQUESTED");
                    }

                    public CompletableFuture<byte[]> renew(
                            UUID key, AttemptRequest request, Duration wait) {
                        throw new IllegalStateException("CHILD_RENEW_NOT_REQUESTED");
                    }

                    /** 부모의 실제 완료 서비스가 만든 전체 영수증만 IPC로 받는다. */
                    public CompletableFuture<byte[]> complete(
                            UUID key,
                            GradeRemoteBatchWorker.CompletionCommand command,
                            Duration wait) {
                        var node =
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                        .objectNode()
                                        .put("jobKey", key.toString())
                                        .put("leaseGen", command.leaseGen())
                                        .put("attemptNo", command.attemptNo())
                                        .put(
                                                "observedProviderVersion",
                                                command.observedProviderVersion())
                                        .put("providerResponseRef", command.providerResponseRef())
                                        .put("resultJson", command.resultJson());
                        System.out.println(
                                "COMPLETE "
                                        + Base64.getEncoder()
                                                .encodeToString(SnapshotJson.encode(node)));
                        System.out.flush();
                        return CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return Base64.getDecoder().decode(reader.readLine());
                                    } catch (Exception exception) {
                                        throw new IllegalStateException("CHILD_BODY_LOST");
                                    }
                                });
                    }
                };
        var childProfiles = new WorkerGradeProfileLoader().load(args[3]);
        try (var owner = new GradeRemoteBatchWorker(journal, transport, bounds(), childProfiles)) {
            owner.beginReservation(UUID.fromString(args[1]), Long.parseLong(args[2]));
            System.out.println("OWNED");
            System.out.flush();
            reader.readLine();
        }
    }

    /**
     * 실제 설치와 동일한 전체 설정·사전을 외부 0700/0600 경로에 저장한다.
     *
     * @param settings 서버가 사용하는 명시 설정
     * @param dictionary 서버의 전체 불변 사전
     * @return 실제 reader가 검사할 canonical 문서 경로
     * @throws Exception 보호 파일 생성·쓰기 실패
     */
    private static Path writeProfile(
            LocalSemanticEngine.Settings settings, GradeDictionary dictionary) throws Exception {
        return writeProfile(settings, dictionary, "REMOTE_SYNTHETIC");
    }

    /**
     * 실제 timeout 회귀의 별도 불변 runtime 코드까지 동일 보호 문서와 서버 설치에 결속한다.
     *
     * @param settings 실제 명시 Settings, null 불가
     * @param dictionary 전체 실제 사전, null 불가
     * @param code 서버에 실제 등록한 profile 코드, null 불가
     * @return canonical 보호 문서 경로
     * @throws Exception 실제 파일 생성·쓰기 실패
     */
    private static Path writeProfile(
            LocalSemanticEngine.Settings settings, GradeDictionary dictionary, String code)
            throws Exception {
        Path root =
                Files.createTempDirectory(
                                fileRoot,
                                "worker-profile-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        Path path =
                Files.createFile(
                        root.resolve("profiles.json"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        var node =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("formatNo", 1);
        var row = node.putArray("profiles").addObject();
        row.putObject("profile")
                .put("configId", code)
                .put("engineId", "LOCAL")
                .put("engineVersion", "1")
                .put("policyCode", "RULE_20260924");
        row.putObject("settings")
                .put("endpoint", settings.endpoint().toString())
                .put("model", settings.model())
                .put("modelDigest", settings.modelDigest())
                .put("dictionaryHash", settings.dictionaryHash())
                .put("modelTemplate", settings.modelTemplate())
                .put("numCtx", settings.numCtx())
                .put("numPredict", settings.numPredict())
                .put("temperature", settings.temperature())
                .put("seed", settings.seed())
                .put("thinking", settings.thinking())
                .put("executionTimeoutMillis", settings.executionTimeout().toMillis());
        row.put("dictionaryCode", dictionary.dictionaryCode());
        var terms = row.putArray("dictionaryTerms");
        for (var term : dictionary.terms())
            terms.addObject()
                    .put("conceptCode", term.conceptCode())
                    .put("canonical", term.canonical())
                    .put("alias", term.alias());
        Files.write(path, SnapshotJson.encode(node));
        return path;
    }

    /** 제공자는 요청 schema 좌표만 사용한 통제 데이터이며 정답·기대 점수·추론을 주입하지 않는다. */
    private static void installProvider() {
        provider.createContext(
                "/api/tags",
                exchange -> {
                    order.add("tags");
                    String digest = order.contains("chat") ? providerPostDigest : "a".repeat(64);
                    providerReply(
                            exchange,
                            200,
                            "{\"models\":[{\"name\":\"qwen3:8b\",\"digest\":\"" + digest + "\"}]}");
                });
        provider.createContext(
                "/api/show",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    order.add("show");
                    afterShow.run();
                    var node =
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                    .objectNode()
                                    .put("template", "{{ .System }}{{ .Prompt }}{{ .Response }}");
                    node.putObject("thinking").putArray("values").add(false).add(true);
                    providerReply(exchange, 200, node.toString());
                });
        provider.createContext(
                "/api/chat",
                exchange -> {
                    var request = SnapshotJson.parse(exchange.getRequestBody().readAllBytes());
                    chats.incrementAndGet();
                    try {
                        assertThat(journalStates(activeStore)).containsExactly(0, 1, 3);
                        order.add("forced-chat-intent");
                        order.add("chat");
                        var document =
                                SnapshotJson.parse(
                                        request.path("messages")
                                                .get(1)
                                                .path("content")
                                                .asText()
                                                .getBytes(StandardCharsets.UTF_8));
                        assertThat(document.path("input").has("expected")).isFalse();
                        var semantic =
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                        .objectNode()
                                        .put("formatNo", 1)
                                        .put("status", semanticStatus);
                        if ("UNRESOLVED".equals(semanticStatus)) semantic.putNull("items");
                        else {
                            var items = semantic.putArray("items");
                            for (var coordinate :
                                    request.path("format")
                                            .path("properties")
                                            .path("items")
                                            .path("anyOf")
                                            .get(1)
                                            .path("items")) {
                                var properties = coordinate.path("properties");
                                var item =
                                        items.addObject()
                                                .put(
                                                        "rubricCode",
                                                        properties
                                                                .path("rubricCode")
                                                                .path("const")
                                                                .asText())
                                                .put("reason", "CONTROLLED_DIAGNOSTIC_CANARY");
                                for (String field : List.of("claims", "contradictions")) {
                                    var propositions = item.putArray(field);
                                    for (var tuple : properties.path(field).path("items")) {
                                        propositions
                                                .addObject()
                                                .put(
                                                        "code",
                                                        tuple.path("oneOf")
                                                                .get(0)
                                                                .path("properties")
                                                                .path("code")
                                                                .path("const")
                                                                .asText())
                                                .put("met", false)
                                                .putArray("spans");
                                    }
                                }
                            }
                        }
                        var envelope =
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                        .objectNode()
                                        .put("model", "qwen3:8b")
                                        .put("done", true)
                                        .put("done_reason", "stop");
                        envelope.putObject("message")
                                .put("role", "assistant")
                                .put("thinking", "PRIVATE_THINKING_CANARY")
                                .put("content", semantic.toString());
                        providerEntered.countDown();
                        if (streamHeldBody) {
                            byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(providerStatus, bytes.length);
                            try (var stream = exchange.getResponseBody()) {
                                stream.write(bytes, 0, 1);
                                stream.flush();
                                providerRelease.await(10, TimeUnit.SECONDS);
                                stream.write(bytes, 1, bytes.length - 1);
                            }
                        } else {
                            providerRelease.await(10, TimeUnit.SECONDS);
                            if (providerDelayMillis > 0) Thread.sleep(providerDelayMillis);
                            providerReply(exchange, providerStatus, envelope.toString());
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                    } catch (Exception exception) {
                        exchange.close();
                        throw new java.io.IOException("TEST_PROVIDER_FAILURE", exception);
                    }
                });
    }

    /**
     * 완전한 통제 응답을 전송한다.
     *
     * @param exchange 합성 loopback 요청
     * @param status 명시 HTTP 상태
     * @param body 전체 UTF-8 JSON
     * @throws java.io.IOException 전송·취소 실패
     */
    private static void providerReply(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    /**
     * 실제 frame90·checksum·원래 tuple를 검사하며 상태만 읽는다.
     *
     * @param store 현재 실제 저널 fixture
     * @return 기록 순서의 상태 번호
     * @throws Exception 파일·해시 실패
     */
    private static List<Integer> journalStates(Store store) throws Exception {
        byte[] bytes = Files.readAllBytes(store.journal());
        var buffer = ByteBuffer.wrap(bytes);
        byte[] header =
                GradeRemoteOnceJournal.provisionedHeader(
                        new Scope(
                                UUID.fromString(
                                        SnapshotJson.parse(Files.readAllBytes(store.descriptor()))
                                                .path("serverScopeId")
                                                .asText()),
                                SnapshotJson.parse(Files.readAllBytes(store.descriptor()))
                                        .path("workerKey")
                                        .asText(),
                                SnapshotJson.parse(Files.readAllBytes(store.descriptor()))
                                        .path("credentialSha256")
                                        .asText()));
        byte[] actualHeader = new byte[header.length];
        buffer.get(actualHeader);
        assertThat(actualHeader).isEqualTo(header);
        List<Integer> states = new java.util.ArrayList<>();
        UUID originalKey = null;
        Integer originalAttempt = 0;
        byte[] ownedHash = null;
        while (buffer.hasRemaining()) {
            assertThat(buffer.getInt()).isEqualTo(90);
            byte[] payload = new byte[90];
            buffer.get(payload);
            byte[] checksum = new byte[32];
            buffer.get(checksum);
            assertThat(checksum)
                    .isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
            var record = ByteBuffer.wrap(payload);
            UUID key = new UUID(record.getLong(), record.getLong());
            if (originalKey == null) originalKey = key;
            assertThat(key).isEqualTo(originalKey);
            assertThat(key).isEqualTo(activeJobKey);
            assertThat(record.getLong()).isEqualTo(activeGeneration);
            int state = Byte.toUnsignedInt(record.get());
            int attemptNo = Byte.toUnsignedInt(record.get());
            if (state == 1) originalAttempt = attemptNo;
            assertThat(attemptNo).isEqualTo(state == 0 ? 0 : originalAttempt);
            byte[] credential = new byte[32];
            record.get(credential);
            assertThat(java.util.HexFormat.of().formatHex(credential))
                    .isEqualTo(
                            SnapshotJson.parse(Files.readAllBytes(store.descriptor()))
                                    .path("credentialSha256")
                                    .asText());
            byte[] originalHash = new byte[32];
            record.get(originalHash);
            if (state == 0) assertThat(originalHash).isEqualTo(new byte[32]);
            if (state == 1) {
                ownedHash = originalHash;
                assertThat(originalHash).isNotEqualTo(new byte[32]);
            }
            if (state > 1) assertThat(originalHash).isEqualTo(ownedHash);
            states.add(state);
        }
        return states;
    }

    /** 같은 registry·설치·crypto·source repository를 사용하는 실제 완료 서비스다. */
    private GradeCompletionService completionService() {
        return new GradeCompletionService(
                jdbc,
                manager,
                credentials,
                Map.of(installation.registrationManifest().path("configId").asText(), installation),
                crypto,
                "REMOTE_COORDINATOR");
    }

    private record Store(Path directory, Path descriptor, Path journal, Path lock) {}

    /**
     * JUnit이 정리하는 시험 전용 경로에 canonical 외부 경로·0700/0600 파일과 forced 헤더를 provision한다.
     *
     * @param scope 실제 test registry와 결속한 좌표, null 불가
     * @param capacity 양수 long 명시적 journal bytes
     * @return 기존 파일만 여는 운영 경계에 전달할 fixture
     * @throws Exception 실제 파일시스템 기능 실패
     */
    private static Store provision(Scope scope, long capacity) throws Exception {
        Path directory =
                Files.createTempDirectory(
                                fileRoot,
                                "grade-once-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        Store store =
                new Store(
                        directory,
                        directory.resolve("descriptor.json"),
                        directory.resolve("journal.bin"),
                        directory.resolve("owner.lock"));
        for (Path path : List.of(store.descriptor(), store.journal(), store.lock())) {
            Files.createFile(
                    path,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        }
        byte[] header = GradeRemoteOnceJournal.provisionedHeader(scope);
        try (var file = FileChannel.open(store.journal(), StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(header);
            while (bytes.hasRemaining()) assertThat(file.write(bytes)).isPositive();
            file.force(true);
        }
        writeDescriptor(store, scope, capacity);
        return store;
    }

    /**
     * 이미 존재하는 시험 descriptor만 갱신하며 파일 inode·mode를 유지한다.
     *
     * @param store null 불가인 provisioned 시험 파일
     * @param scope null 불가인 신뢰된 시험 좌표
     * @param capacity 양수 long 명시적 journal byte 상한
     * @throws Exception 실제 파일 쓰기 실패
     */
    private static void writeDescriptor(Store store, Scope scope, long capacity) throws Exception {
        var node =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("formatNo", 1)
                        .put("serverScopeId", scope.serverScopeId().toString())
                        .put("workerKey", scope.workerKey())
                        .put("credentialSha256", scope.credentialSha256())
                        .put("journalFile", store.journal().getFileName().toString())
                        .put("lockFile", store.lock().getFileName().toString())
                        .put("capacityBytes", capacity);
        Files.write(store.descriptor(), SnapshotJson.encode(node));
    }

    /**
     * @return 합성 fixture에만 적용할 8MiB/10초 유한 상한이며 운영 기본값이 아님
     */
    private static TransportBounds bounds() {
        // 이 숫자는 합성 test transport만의 명시적 상한이며 운영 default/승인이 아니다.
        return new TransportBounds(8 * 1024 * 1024, Duration.ofSeconds(10));
    }

    /**
     * 실제 보호 descriptor와 같은 등록의 test transport를 조립한다.
     *
     * @param store null 불가인 provisioned fixture
     * @param transport null 불가인 같은 scope test transport
     * @return 실제 lock을 소유한 예약 경계
     * @throws Exception 보호/조립 실패
     */
    private static GradeRemoteBatchWorker owner(Store store, BoundTransport transport)
            throws Exception {
        return new GradeRemoteBatchWorker(
                GradeRemoteOnceJournal.open(store.descriptor().toString()),
                transport,
                bounds(),
                profiles);
    }

    /** 실제 파일 channel에만 적용할 force 실패·지연 fixture 경계다. */
    @FunctionalInterface
    private interface TestForce {
        void apply(FileChannel channel) throws Exception;
    }

    /**
     * 실제 channel force의 지정 번째 호출만 실패시키고 나머지는 force(true)를 수행한다.
     *
     * @param store null 불가인 보호 fixture
     * @param count null 불가인 actual force 호출 수
     * @param failAt 양수 실패 호출 번호; open=1, IN_FLIGHT=2, OWNED=3, abandon=4
     * @return 실제 파일/lock 소유자
     * @throws Exception 실제 보호 또는 지정된 force 실패
     */
    private static GradeRemoteOnceJournal openWithForceFailure(
            Store store, AtomicInteger count, int failAt) throws Exception {
        return openWithForce(
                store,
                channel -> {
                    if (count.incrementAndGet() == failAt)
                        throw new java.io.IOException("TEST_FORCE_FAILURE");
                    channel.force(true);
                });
    }

    /**
     * config의 package-private force 시험 seam만 reflection으로 접근한다. protected reader 우회는 하지 않는다.
     *
     * @param store null 불가인 운영과 동일한 보호 fixture
     * @param force null 불가인 actual channel 조작
     * @return 기존 파일/lock을 소유한 journal
     * @throws Exception 실제 보호/force 경계 실패
     */
    private static GradeRemoteOnceJournal openWithForce(Store store, TestForce force)
            throws Exception {
        Class<?> type =
                Class.forName("com.reasoning.common.grading.config.GradeRemoteOnceJournal$Force");
        Object proxy =
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (object, method, args) -> {
                            force.apply((FileChannel) args[0]);
                            return null;
                        });
        var method = GradeRemoteOnceJournal.class.getDeclaredMethod("open", String.class, type);
        method.setAccessible(true);
        try {
            return (GradeRemoteOnceJournal)
                    method.invoke(null, store.descriptor().toString(), proxy);
        } catch (InvocationTargetException exception) {
            throw (Exception) exception.getCause();
        }
    }

    /**
     * 현 invocation의 private 앵커를 읽기만 한다. 직렬화·복구·state 주입은 하지 않는다.
     *
     * @param owner null 불가인 live fixture 소유자
     * @param name null 불가인 jobExpiry 또는 leaseExpiry
     * @return 현 invocation 만료 나노초
     * @throws Exception reflection 실패
     */
    private static long ownerExpiry(GradeRemoteBatchWorker owner, String name) throws Exception {
        var field = GradeRemoteBatchWorker.class.getDeclaredField("attempt");
        field.setAccessible(true);
        Object attempt = field.get(owner);
        var expiry = attempt.getClass().getDeclaredField(name);
        expiry.setAccessible(true);
        return expiry.getLong(attempt);
    }

    /**
     * 실제 owner가 소비하는 private 산술만 직접 대조한다. authority/state를 주입하지 않는다.
     *
     * @param name null 불가인 고정 helper 이름
     * @param types null 불가인 primitive 인자 타입 목록
     * @param args null 불가인 시험 경계 값 목록
     * @return 실제 helper 반환값이며 void이면 null
     * @throws Exception 실제 helper의 산술·고정 오류 또는 reflection 실패
     */
    private static Object ownerMath(String name, Class<?>[] types, Object... args)
            throws Exception {
        var method = GradeRemoteBatchWorker.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException exception) {
            throw (Exception) exception.getCause();
        }
    }

    /**
     * 실제 서비스 출력에만 지정된 공격 값을 넣는다. 정상 응답을 새로 manufacture하지 않는다.
     *
     * @param node null 불가인 실제 응답 사본
     * @param path null 불가인 기존 중첩 객체 경로
     * @param key null 불가인 공격 필드
     * @param value null 불가인 공격 스칼라
     * @return 변조한 실제 출력 사본
     */
    private static ObjectNode change(ObjectNode node, String path, String key, Object value) {
        ObjectNode target = node;
        for (String component : path.split("\\.")) target = (ObjectNode) target.get(component);
        if (value instanceof String text) target.put(key, text);
        else if (value instanceof Integer number) target.put(key, number);
        else if (value instanceof Double number) target.put(key, number);
        else if (value instanceof java.math.BigInteger number) target.put(key, number);
        else throw new IllegalArgumentException("INVALID_TEST_MUTATION");
        return node;
    }

    /**
     * 폐기형 PG fixture에만 INSERT RETURNING을 수행한다.
     *
     * @param sql null 불가인 내부 고정 fixture SQL
     * @param args null 불가인 바인딩 값 목록
     * @return 실제 PG 양수 식별자
     */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 예약만 시험했으므로 실제 server attempt에 completion body가 없음을 검사한다. provider는 생성하지 않는다. */
    private void assertNoProviderOrCompletion() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_attempt WHERE job_id=? AND"
                                        + " completion_data IS NOT NULL",
                                Integer.class,
                                job))
                .isZero();
    }

    /** 실제 same-registry VerifiedWorker로 서비스에 진입하며 body mutation은 공격 시험에만 쓰인다. */
    private final class ServerTransport implements PollingTransport {
        private final Scope binding;
        private final AtomicInteger polls = new AtomicInteger();
        private UnaryOperator<PollReply> pollMutation = UnaryOperator.identity();
        private boolean holdPollBody;
        private final CountDownLatch pollEntered = new CountDownLatch(1);
        private final CompletableFuture<PollReply> pollBodyFuture = new CompletableFuture<>();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger fences = new AtomicInteger();
        private final AtomicInteger renewals = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private UnaryOperator<byte[]> completionMutation = UnaryOperator.identity();
        private boolean loseCompletion;
        private boolean holdCompletionBody;
        private final CountDownLatch completionEntered = new CountDownLatch(1);
        private final CompletableFuture<byte[]> completionBodyFuture = new CompletableFuture<>();
        private byte[] heldCompletionBody;
        private Runnable beforeCompletion = () -> {};
        private GradeRemoteBatchWorker.CompletionCommand completedCommand;
        private GradeCompletionService.CompletionReceipt actualReceipt;
        private UnaryOperator<byte[]> startMutation = UnaryOperator.identity();
        private UnaryOperator<byte[]> observedMutation = UnaryOperator.identity();
        private boolean loseStart;
        private boolean holdBody;
        private CompletableFuture<byte[]> bodyFuture;
        private AtomicInteger forceAcknowledgements;
        private boolean holdObservedBody;
        private boolean holdRenewBody;
        private final CountDownLatch observedEntered = new CountDownLatch(1);
        private final CompletableFuture<byte[]> observedBodyFuture = new CompletableFuture<>();
        private byte[] heldObservedBody;
        private final AtomicInteger scopeCalls = new AtomicInteger();
        private volatile int holdScopeCall;
        private final CountDownLatch scopeEntered = new CountDownLatch(1);
        private final CountDownLatch scopeRelease = new CountDownLatch(1);

        private ServerTransport(Scope binding) {
            this.binding = binding;
        }

        /** 생성자 이후 지정한 scope 관측만 latch로 보류하며 신원 자체는 변경하지 않는다. */
        public Scope scope() {
            if (scopeCalls.incrementAndGet() == holdScopeCall) {
                scopeEntered.countDown();
                try {
                    if (!scopeRelease.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("TEST_SCOPE_TIMEOUT");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("TEST_SCOPE_INTERRUPTED");
                }
            }
            return binding;
        }

        /** 동일 registry의 실제 저장소에서 TEST만 임대하며 결과 데이터를 만들지 않는다. */
        public CompletableFuture<PollReply> poll(String runtimeCode, Duration maximumWait) {
            polls.incrementAndGet();
            credentials.requirePermission(worker, Action.CLAIM, runtimeCode);
            var lease =
                    new com.reasoning.common.grading.repository.GradeLeaseRepository(jdbc, manager)
                            .claimTest(worker.workerKey(), runtimeCode);
            PollReply reply =
                    lease.map(
                                    value ->
                                            new PollReply(
                                                    200,
                                                    SnapshotJson.encode(
                                                            com.fasterxml.jackson.databind.node
                                                                    .JsonNodeFactory.instance
                                                                    .objectNode()
                                                                    .put(
                                                                            "jobKey",
                                                                            value.jobKey()
                                                                                    .toString())
                                                                    .put(
                                                                            "leaseGen",
                                                                            value.leaseGen())
                                                                    .put(
                                                                            "deadline",
                                                                            value.deadlineAt()
                                                                                    .toInstant()
                                                                                    .toString()))))
                            .orElseGet(() -> new PollReply(204, new byte[0]));
            if (holdPollBody) {
                pollEntered.countDown();
                return pollBodyFuture;
            }
            return CompletableFuture.completedFuture(pollMutation.apply(reply));
        }

        public CompletableFuture<byte[]> start(UUID key, long gen, Duration maximumWait) {
            if (forceAcknowledgements != null) assertThat(forceAcknowledgements.get()).isEqualTo(2);
            starts.incrementAndGet();
            byte[] body =
                    startMutation.apply(
                            SnapshotJson.encode(service.startApproved(worker, key, gen).toJson()));
            if (loseStart)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("TEST_ORIGINAL_RESPONSE_LOST"));
            if (holdBody) {
                bodyFuture = new CompletableFuture<>();
                return bodyFuture;
            }
            return CompletableFuture.completedFuture(body);
        }

        public CompletableFuture<byte[]> fence(
                UUID key, AttemptRequest original, Duration maximumWait) {
            fences.incrementAndGet();
            order.add("fence");
            return completeObservation(
                    SnapshotJson.encode(service.revalidate(worker, key, original).toJson()));
        }

        public CompletableFuture<byte[]> renew(
                UUID key, AttemptRequest original, Duration maximumWait) {
            renewals.incrementAndGet();
            byte[] body =
                    SnapshotJson.encode(service.renewApproved(worker, key, original).toJson());
            if (holdRenewBody) {
                heldObservedBody = body;
                observedEntered.countDown();
                return observedBodyFuture;
            }
            return completeObservation(body);
        }

        /** 원래 명령 getter만 실제 서버에 전달하고 서버가 생성한 UUID 영수증을 인코딩한다. */
        public CompletableFuture<byte[]> complete(
                UUID key, GradeRemoteBatchWorker.CompletionCommand command, Duration maximumWait) {
            completions.incrementAndGet();
            order.add("complete");
            beforeCompletion.run();
            completedCommand = command;
            actualReceipt =
                    completionService()
                            .complete(
                                    worker,
                                    key,
                                    command.leaseGen(),
                                    command.attemptNo(),
                                    command.observedProviderVersion(),
                                    command.providerResponseRef(),
                                    command.resultJson(),
                                    UUID.randomUUID());
            byte[] bytes =
                    completionMutation.apply(SnapshotJson.encode(receiptNode(actualReceipt)));
            if (loseCompletion)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("TEST_COMPLETION_LOST"));
            if (holdCompletionBody) {
                heldCompletionBody = bytes;
                completionEntered.countDown();
                return completionBodyFuture;
            }
            return CompletableFuture.completedFuture(bytes);
        }

        /**
         * 실제 서버의 관측 body만 변조 또는 보류하며 latch 완료 전에는 전체 수신을 완료하지 않는다.
         *
         * @param body null 불가인 실제 server-service의 완전 응답
         * @return 실제 body 또는 명시적으로 보류된 전체 응답 future
         */
        private CompletableFuture<byte[]> completeObservation(byte[] body) {
            byte[] bytes = observedMutation.apply(body);
            if (!holdObservedBody) return CompletableFuture.completedFuture(bytes);
            heldObservedBody = bytes;
            observedEntered.countDown();
            return observedBodyFuture;
        }
    }

    /**
     * Gradle의 application loader URL도 포함하여 실제 child JVM을 실행한다.
     *
     * @param store null 불가인 실제 provisioned 파일
     * @return START marker에서 부모의 actual server bytes를 기다리는 child
     * @throws Exception 프로세스 생성/클래스 경로 실패
     */
    private Process child(Store store) throws Exception {
        Set<String> paths =
                new LinkedHashSet<>(
                        List.of(
                                System.getProperty("java.class.path")
                                        .split(java.io.File.pathSeparator)));
        for (ClassLoader loader = getClass().getClassLoader();
                loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs())
                    if (url.getProtocol().equals("file"))
                        paths.add(Path.of(url.toURI()).toString());
            }
        }
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        String.join(java.io.File.pathSeparator, paths),
                        getClass().getName(),
                        store.descriptor().toString(),
                        jobKey.toString(),
                        Long.toString(generation),
                        profilePath.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    /**
     * child stdout를 무한 대기하지 않고 5초 내 marker를 읽으며 완료 IPC는 실제 서버에서 처리한다.
     *
     * @param reader null 불가인 child stdout
     * @param child null 불가인 실제 child; timeout이면 resource close 전에 종료한다
     * @return null 아닌 marker; EOF는 assertion 실패
     * @throws Exception IPC timeout/읽기 실패
     */
    private String readChild(BufferedReader reader, Process child) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future =
                    executor.submit(
                            () -> {
                                while (true) {
                                    String line = reader.readLine();
                                    assertThat(line).isNotNull();
                                    if (!line.startsWith("COMPLETE ")) return line;
                                    var command =
                                            SnapshotJson.parse(
                                                    Base64.getDecoder().decode(line.substring(9)));
                                    var receipt =
                                            completionService()
                                                    .complete(
                                                            worker,
                                                            UUID.fromString(
                                                                    command.path("jobKey")
                                                                            .asText()),
                                                            command.path("leaseGen").longValue(),
                                                            command.path("attemptNo").intValue(),
                                                            command.path("observedProviderVersion")
                                                                            .isNull()
                                                                    ? null
                                                                    : command.path(
                                                                                    "observedProviderVersion")
                                                                            .asText(),
                                                            command.path("providerResponseRef")
                                                                            .isNull()
                                                                    ? null
                                                                    : command.path(
                                                                                    "providerResponseRef")
                                                                            .asText(),
                                                            command.path("resultJson").asText(),
                                                            UUID.randomUUID());
                                    var writer =
                                            new PrintWriter(
                                                    child.getOutputStream(),
                                                    true,
                                                    StandardCharsets.UTF_8);
                                    writer.println(
                                            Base64.getEncoder()
                                                    .encodeToString(
                                                            SnapshotJson.encode(
                                                                    receiptNode(receipt))));
                                }
                            });
            try {
                String line = future.get(5, TimeUnit.SECONDS);
                assertThat(line).isNotNull();
                return line;
            } catch (Exception exception) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
                throw exception;
            } finally {
                future.cancel(true);
                executor.shutdownNow();
            }
        }
    }
}
