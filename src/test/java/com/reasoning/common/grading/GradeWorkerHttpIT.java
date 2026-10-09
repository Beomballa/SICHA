package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.ReasoningApplication;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal.Scope;
import com.reasoning.common.grading.config.WorkerGradeProfileLoader;
import com.reasoning.common.grading.controller.GradeWorkerController.Assembly;
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
import com.reasoning.common.grading.service.FrozenDatasetValidator;
import com.reasoning.common.grading.service.GradeCalculator;
import com.reasoning.common.grading.service.GradeCompletionService;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;
import com.reasoning.common.grading.service.GradeResultValidator;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.util.CommonUtil;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** 폐기형 PG16.10·실제 직접 TLS·원본 HTTP·실제 서비스와 단회 owner를 결합한다. 운영 활성화나 품질 승인이 아니다. */
@SpringBootTest(
        classes = ReasoningApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GradeWorkerHttpIT.HttpAssembly.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class GradeWorkerHttpIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(GradeSchemaIT.IMAGE)
                            .asCompatibleSubstituteFor("postgres"));

    private static final Connector plaintext =
            new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    private static final String CODE = "HTTP_SYNTHETIC";
    private static final String WORKER = "HTTP_SYNTHETIC_WORKER";
    private static final byte[] SECRET =
            ByteBuffer.allocate(32).putLong(101).putLong(102).putLong(103).putLong(104).array();
    private static final byte[] RESTRICTED =
            ByteBuffer.allocate(32).putLong(201).putLong(202).putLong(203).putLong(204).array();
    private static final byte[] WRONG_RUNTIME =
            ByteBuffer.allocate(32).putLong(301).putLong(302).putLong(303).putLong(304).array();
    private static final List<String> order = new CopyOnWriteArrayList<>();
    private static final AtomicInteger chats = new AtomicInteger();
    private static final Fixture fixture = Fixture.create();
    private static volatile Path activeJournal;
    private static volatile JsonNode providerSemantic;

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired CryptoService crypto;
    @Autowired Assembly assembly;
    @Autowired AdminSessionAdapter sessions;
    private HttpClient client;
    private long runtime;
    private long job;
    private long creator;
    private long story;
    private UUID jobKey;
    private FrozenSnapshotCodec.FrozenSnapshot frozen;

    /** 운영 접속값 대신 container와 합성 키/TLS만 명시한다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        fixture.properties().forEach((key, value) -> properties.add(key, () -> value));
    }

    /** 별도 실제 context에는 운영 components만 스캔하고 모든 테스트 assembly를 제외한다. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
    @ComponentScan(
            basePackages = "com.reasoning",
            excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = TestConfiguration.class),
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = ReasoningApplication.class)
            })
    static class DisabledHttpApplication {}

    /** assembly는 테스트에서만 생성하며 같은 registry·DS·JDBC 관리자·실제 설치를 사용한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HttpAssembly {
        @Bean
        Assembly workerAssembly(
                JdbcTemplate db,
                CryptoService crypto,
                PlaytestPolicyGate policy,
                MemberPolicyGate memberPolicy) {
            var registry = registry();
            var manager = new DataSourceTransactionManager(db.getDataSource());
            var installed = Map.of(CODE, fixture.installation);
            return Assembly.forTest(
                    registry,
                    db,
                    manager,
                    installed,
                    policy,
                    memberPolicy,
                    crypto,
                    "HTTP_SYNTHETIC_SYSTEM");
        }

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> plaintextConnector() {
            return factory -> {
                plaintext.setPort(0);
                plaintext.setProperty("address", "127.0.0.1");
                factory.addAdditionalTomcatConnectors(plaintext);
            };
        }
    }

    /** container에 실제 full frozen roots와 원래 120초/30초 임대를 새로 만든다. */
    @BeforeEach
    void fixtures() throws Exception {
        client = validatingClient(fixture.store);
        assertThat(
                        db.queryForObject(
                                "SELECT version FROM flyway_schema_history WHERE success ORDER BY"
                                        + " installed_rank DESC LIMIT 1",
                                String.class))
                .isEqualTo("23");
        var registered =
                new GradeRuntimeRepository(db)
                        .registerRuntime(
                                CODE,
                                fixture.installation.configHash(),
                                fixture.installation.registrationManifest().toString());
        runtime = registered.id();
        db.update("UPDATE grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtime);
        creator =
                id(
                        "INSERT INTO admin_account(account_key,can_review,can_manage) VALUES"
                                + " (?,true,true) RETURNING id",
                        UUID.randomUUID());
        db.update(
                "INSERT INTO"
                    + " admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                    + " VALUES"
                    + " (?,'fixture-only',?,'fixture-only','fixture-only',clock_timestamp(),0,clock_timestamp(),'READY')",
                creator,
                ByteBuffer.allocate(32).putLong(creator).array());
        String code = "ST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        story = id("INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id", code, creator);
        long version =
                id(
                        "INSERT INTO"
                            + " story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 HTTP 전체 사본','RULE_20260924',?,?) RETURNING id",
                        story,
                        creator,
                        creator);
        var payload = FrozenSnapshotContractTest.complete();
        payload.put("storyCode", code);
        frozen = FrozenSnapshotCodec.freeze(payload);
        long snapshot =
                id(
                        "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,?::jsonb,?,?) RETURNING id",
                        version,
                        new String(frozen.payloadBytes(), StandardCharsets.UTF_8),
                        UUID.randomUUID(),
                        creator);
        db.update(
                "UPDATE story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?",
                snapshot,
                version);
        db.update(
                "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                        + " (?,?,'REVIEW',?)",
                story,
                creator,
                creator);
        long batch =
                id(
                        "INSERT INTO"
                            + " grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)"
                            + " VALUES (?,?,?,'REVIEW',?,?,?,?,0,'RUNNING',12,?) RETURNING id",
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        frozen.datasetHash(),
                        frozen.rubricHash(),
                        frozen.payloadHash(),
                        fixture.installation.configHash(),
                        creator);
        jobKey = UUID.randomUUID();
        job =
                id(
                        "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                            + " grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash,accepted_at,deadline_at,worker_key,lease_gen,lease_until)"
                            + " SELECT ?,?,?,?,'FULL',1,'RUNNING',?,?,?,t.n,t.n+interval '120"
                            + " seconds',?,1,t.n+interval '30 seconds' FROM t RETURNING id",
                        jobKey,
                        snapshot,
                        runtime,
                        batch,
                        frozen.inputHash("FULL"),
                        fixture.installation.configHash(),
                        frozen.rubricHash(),
                        WORKER);
        order.clear();
        chats.set(0);
        activeJournal = null;
        providerSemantic = null;
    }

    /** 실제 연결을 닫고 해당 폐기형 fixture의 남은 임대만 해제하여 다음 case의 worker 유일성을 유지한다. */
    @AfterEach
    void closeClient() {
        if (client != null) client.close();
        db.update(
                "UPDATE grade_job SET state='CANCELLED',worker_key=NULL,lease_until=NULL WHERE id=?"
                        + " AND state='RUNNING'",
                job);
    }

    /** NEW 원본 bytes·입력 없는 REPLAY·tuple fence/renew·runtime currentness를 HTTP로 검사한다. */
    @Test
    void startReplayFenceRenewAndRuntimeCurrentness() throws Exception {
        var original =
                db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", job);
        var first = post("start", "{\"leaseGen\":1}");
        var start = body(first, 200);
        assertThat(start.path("disposition").asText()).isEqualTo("NEW");
        assertThat(fields(start))
                .containsExactlyInAnyOrder(
                        "formatNo",
                        "disposition",
                        "jobKey",
                        "leaseGen",
                        "attemptNo",
                        "input",
                        "limits");
        assertThat(
                        Base64.getDecoder()
                                .decode(
                                        start.path("input")
                                                .path("modelInput")
                                                .path("bytesBase64")
                                                .asText()))
                .isNotEmpty();
        var replay = body(post("start", "{\"leaseGen\":1}"), 200);
        assertThat(replay.path("disposition").asText()).isEqualTo("REPLAY");
        assertThat(replay.path("input").isNull()).isTrue();
        assertThat(replay.path("limits").isNull()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE id=?", Integer.class, job))
                .isEqualTo(1);
        String tuple = tuple(start);
        assertThat(body(post("before-chat", tuple), 200).path("originalAttemptHash"))
                .isEqualTo(start.path("input").path("originalAttemptHash"));
        assertThat(
                        body(post("renew", tuple), 200)
                                .path("limits")
                                .path("remainingLeaseMillis")
                                .longValue())
                .isBetween(1L, 30000L);
        assertThat(db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", job))
                .isEqualTo(original);
        db.update("UPDATE grade_runtime SET epoch=1 WHERE id=?", runtime);
        error(post("before-chat", tuple), 409, "REMOTE_EXECUTION_NOT_CURRENT");
        error(post("renew", tuple), 409, "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(chats.get()).isZero();
        assertAudit(first, "WORKER", WORKER, "/internal/api/grading/jobs/{jobKey}/start");
    }

    /** 서버 Calculator만 score를 결정하며 AES-GCM 결과와 원래 receipt를 HTTP replay에서도 보존한다. */
    @Test
    void encryptedSemanticCalculatorTruthReplayAndConflict() throws Exception {
        body(post("start", "{\"leaseGen\":1}"), 200);
        ObjectNode semantic = semantic();
        String envelope = complete(semantic);
        var accepted = post("complete", envelope);
        var receipt = body(accepted, 200);
        assertThat(fields(receipt))
                .containsExactlyInAnyOrder(
                        "jobKey",
                        "attemptNo",
                        "accepted",
                        "state",
                        "retryScheduled",
                        "outcome",
                        "reason",
                        "requestId");
        assertThat(receipt.path("accepted").booleanValue()).isTrue();
        assertThat(receipt.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(receipt.path("requestId").asText()).isEqualTo(requestId(accepted).toString());
        assertCalculatorTruth(semantic.path("semantic"));
        var replay = post("complete", envelope);
        assertThat(body(replay, 200)).isEqualTo(receipt);
        assertThat(requestId(replay)).isNotEqualTo(requestId(accepted));
        assertAudit(replay, "WORKER", WORKER, "/internal/api/grading/jobs/{jobKey}/complete");
        error(
                post("complete", envelope.replace("HTTP_DIAGNOSTIC_CANARY", "DIFFERENT_CANARY")),
                409,
                "CALLBACK_CONFLICT");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=?",
                                Integer.class,
                                job))
                .isEqualTo(1);
        assertNoAuditCanary();
    }

    /** stale accepted:false와 분류 가능한 ERROR retry를 HTTP200만으로 채점 성공이라고 판정하지 않는다. */
    @Test
    void staleAndClassifiableErrorRemainDomainReceipts() throws Exception {
        body(post("start", "{\"leaseGen\":1}"), 200);
        db.update(
                "UPDATE grade_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
                job);
        var stale = body(post("complete", complete(semantic())), 200);
        assertThat(stale.path("accepted").booleanValue()).isFalse();
        assertThat(stale.path("reason").asText()).isEqualTo("STALE_LEASE");
        assertThat(
                        db.queryForObject(
                                "SELECT completion_data IS NULL FROM grade_attempt WHERE job_id=?"
                                        + " AND attempt_no=1",
                                Boolean.class,
                                job))
                .isTrue();
        db.update(
                "UPDATE grade_job SET lease_until=clock_timestamp()+interval '30 seconds' WHERE"
                        + " id=?",
                job);
        var retry =
                body(
                        post(
                                "complete",
                                complete(
                                        object().put("kind", "ERROR")
                                                .put("errorCode", "ENGINE_TIMEOUT"))),
                        200);
        assertThat(retry.path("accepted").booleanValue()).isTrue();
        assertThat(retry.path("state").asText()).isEqualTo("QUEUED");
        assertThat(retry.path("retryScheduled").booleanValue()).isTrue();
        assertThat(retry.path("outcome").isNull()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT result_cipher IS NULL FROM grade_job WHERE id=?",
                                Boolean.class,
                                job))
                .isTrue();
    }

    /** 첫 두 retry의 원래 deadline을 보존하며 세 번째 시도의 fence/renew와 최종 실패를 검사한다. */
    @Test
    void thirdAttemptUsesLiveTupleWithoutRestartingJobBudget() throws Exception {
        var original =
                db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", job);
        for (int number = 1; number <= 3; number++) {
            var start = body(post("start", "{\"leaseGen\":" + number + "}"), 200);
            assertThat(start.path("attemptNo").intValue()).isEqualTo(number);
            var tuple =
                    object().put("leaseGen", number)
                            .put("attemptNo", number)
                            .put(
                                    "originalAttemptHash",
                                    start.path("input").path("originalAttemptHash").asText());
            body(post("before-chat", tuple.toString()), 200);
            body(post("renew", tuple.toString()), 200);
            var envelope =
                    object().put("leaseGen", number)
                            .put("attemptNo", number)
                            .putNull("observedProviderVersion")
                            .putNull("providerResponseRef");
            envelope.set(
                    "result", object().put("kind", "ERROR").put("errorCode", "ENGINE_TIMEOUT"));
            var receipt = body(post("complete", envelope.toString()), 200);
            assertThat(receipt.path("accepted").booleanValue()).isTrue();
            assertThat(receipt.path("retryScheduled").booleanValue()).isEqualTo(number < 3);
            assertThat(receipt.path("state").asText()).isEqualTo(number < 3 ? "QUEUED" : "FAILED");
            assertThat(
                            db.queryForMap(
                                    "SELECT accepted_at,deadline_at FROM grade_job WHERE id=?",
                                    job))
                    .isEqualTo(original);
            if (number < 3)
                assertThat(
                                db.update(
                                        "UPDATE grade_job SET"
                                            + " state='RUNNING',worker_key=?,lease_gen=?,lease_until=clock_timestamp()+interval"
                                            + " '30 seconds' WHERE id=? AND state='QUEUED'",
                                        WORKER,
                                        number + 1,
                                        job))
                        .isEqualTo(1);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=?",
                                Integer.class,
                                job))
                .isEqualTo(3);
        assertThat(chats.get()).isZero();
    }

    /** creator 실권이 철회되면 COMPLETE는 안전한 거절 영수증만 기록한다. */
    @Test
    void completionObservesActualCreatorPermissionRevocation() throws Exception {
        body(post("start", "{\"leaseGen\":1}"), 200);
        db.update("UPDATE admin_account SET can_review=false WHERE id=?", creator);
        var receipt = body(post("complete", complete(semantic())), 200);
        assertThat(receipt.path("accepted").booleanValue()).isFalse();
        assertThat(receipt.path("reason").asText()).isEqualTo("SOURCE_REVOKED");
        assertThat(
                        db.queryForObject(
                                "SELECT result_cipher IS NULL FROM grade_job WHERE id=?",
                                Boolean.class,
                                job))
                .isTrue();
    }

    /** 불변 action/runtime 허용을 실제 registry에서 검사하고 creator 실권 철회도 실제 currentness로 관측한다. */
    @Test
    void actionRuntimeAndRevokedCreatorPermissionDoNotExecute() throws Exception {
        error(
                send(
                        client,
                        uri("start"),
                        "POST",
                        bytes("{\"leaseGen\":1}"),
                        List.of(header(WRONG_RUNTIME)),
                        Map.of()),
                403,
                "REMOTE_EXECUTION_FORBIDDEN");
        db.update("UPDATE grade_job SET worker_key='HTTP_START_ONLY' WHERE id=?", job);
        var start =
                send(
                        client,
                        uri("start"),
                        "POST",
                        bytes("{\"leaseGen\":1}"),
                        List.of(header(RESTRICTED)),
                        Map.of());
        body(start, 200);
        error(
                send(
                        client,
                        uri("renew"),
                        "POST",
                        bytes(tuple(body(start, 200))),
                        List.of(header(RESTRICTED)),
                        Map.of()),
                403,
                "REMOTE_EXECUTION_FORBIDDEN");
        error(
                send(
                        client,
                        uri("complete"),
                        "POST",
                        bytes(complete(semantic())),
                        List.of(header(RESTRICTED)),
                        Map.of()),
                403,
                "REMOTE_EXECUTION_FORBIDDEN");
        db.update("UPDATE admin_account SET can_review=false WHERE id=?", creator);
        error(
                send(
                        client,
                        uri("before-chat"),
                        "POST",
                        bytes(tuple(body(start, 200))),
                        List.of(header(RESTRICTED)),
                        Map.of()),
                409,
                "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(chats.get()).isZero();
    }

    /** 필수event 저장 실패는완료TX를rollback하지만 독립접근이력 실패는commit을 되돌리지 않는다. */
    @Test
    void mandatoryEventFaultRollsBackIndependentAccessFaultDoesNot(CapturedOutput output)
            throws Exception {
        body(post("start", "{\"leaseGen\":1}"), 200);
        trigger("grade_event", "RAISE EXCEPTION 'STORAGE_CANARY';");
        try {
            error(post("complete", complete(semantic())), 503, "REMOTE_EXECUTION_UNAVAILABLE");
            assertThat(
                            db.queryForObject(
                                    "SELECT state FROM grade_job WHERE id=?", String.class, job))
                    .isEqualTo("RUNNING");
            assertThat(
                            db.queryForObject(
                                    "SELECT output_cipher IS NULL AND completion_data IS NULL FROM"
                                            + " grade_attempt WHERE job_id=? AND attempt_no=1",
                                    Boolean.class,
                                    job))
                    .isTrue();
            assertThat(
                            db.queryForObject(
                                    "SELECT result_cipher IS NULL FROM grade_job WHERE id=?",
                                    Boolean.class,
                                    job))
                    .isTrue();
        } finally {
            dropTrigger("grade_event");
        }
        trigger("access_history", "RAISE EXCEPTION 'ACCESS_CANARY';");
        try {
            var accepted = post("complete", complete(semantic()));
            assertThat(body(accepted, 200).path("accepted").booleanValue()).isTrue();
            assertThat(
                            db.queryForObject(
                                    "SELECT state FROM grade_job WHERE id=?", String.class, job))
                    .isEqualTo("COMPLETED");
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM access_history WHERE request_id=?",
                                    Integer.class,
                                    requestId(accepted)))
                    .isZero();
            assertCalculatorTruth(semantic().path("semantic"));
            long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!output.getAll()
                            .contains(
                                    "Access history write failed; operational investigation"
                                            + " required")
                    && System.nanoTime() < end) Thread.sleep(10);
            assertThat(output.getAll())
                    .contains("Access history write failed; operational investigation required")
                    .doesNotContain("ACCESS_CANARY", "STORAGE_CANARY", header(SECRET));
        } finally {
            dropTrigger("access_history");
        }
    }

    /** 실TLS listener에서cookie/중복/비정규token·다른method/route·원본body 거절과anon이력을 검사한다. */
    @Test
    void realHttpsAuthenticationRoutesRawNegativesAndAudit() throws Exception {
        for (List<String> auth :
                List.of(
                        List.<String>of(),
                        List.of(header(SECRET), header(SECRET)),
                        List.of(header(SECRET) + ", " + header(SECRET)),
                        List.of(header(SECRET).replace("Bearer", "bearer")),
                        List.of(header(SECRET) + "="),
                        List.of("Bearer CANARY"))) {
            var response =
                    send(
                            client,
                            uri("start"),
                            "POST",
                            bytes("{\"leaseGen\":1}"),
                            auth,
                            Map.of("Cookie", "__Host-admin-session=ADMIN_CANARY"));
            error(response, 401, "WORKER_AUTH_REQUIRED");
            assertAudit(response, "ANONYMOUS", null, "/internal/api/grading/jobs/{jobKey}/start");
        }
        for (String method : List.of("GET", "PUT", "DELETE", "OPTIONS")) {
            var response =
                    send(
                            client,
                            uri("start"),
                            method,
                            bytes("{}"),
                            List.of(header(SECRET)),
                            Map.of());
            error(response, 403, "REMOTE_EXECUTION_FORBIDDEN");
            assertAudit(response, "WORKER", WORKER, "/internal/api/grading/jobs/{jobKey}/start");
        }
        var unknown =
                send(
                        client,
                        URI.create("https://localhost:" + port + "/internal/api/grading/CANARY"),
                        "POST",
                        bytes("{}"),
                        List.of(header(SECRET)),
                        Map.of());
        error(unknown, 403, "REMOTE_EXECUTION_FORBIDDEN");
        assertAudit(unknown, "WORKER", WORKER, "UNMATCHED");
        for (String body :
                List.of(
                        "{\"leaseGen\":1,\"lease\\u0047en\":1}",
                        "{\"leaseGen\":1}{}",
                        "{\"leaseGen\":1.0}",
                        "{\"leaseGen\":9223372036854775808}",
                        "{\"leaseGen\":1,\"CANARY\":0}"))
            error(post("start", body), 400, "INVALID_REMOTE_REQUEST");
        error(
                send(
                        client,
                        uri("start"),
                        "POST",
                        new byte[] {(byte) 0xff},
                        List.of(header(SECRET)),
                        Map.of()),
                400,
                "INVALID_REMOTE_REQUEST");
        error(
                send(
                        client,
                        URI.create(uri("start") + "?CANARY=1"),
                        "POST",
                        bytes("{\"leaseGen\":1}"),
                        List.of(header(SECRET)),
                        Map.of()),
                400,
                "INVALID_REMOTE_REQUEST");
        error(
                send(
                        client,
                        uri("start"),
                        "POST",
                        bytes("{\"leaseGen\":1}"),
                        List.of(header(SECRET)),
                        Map.of("Content-Encoding", "identity")),
                400,
                "INVALID_REMOTE_REQUEST");
        error(
                send(
                        client,
                        uri("start"),
                        "POST",
                        bytes("{\"leaseGen\":1}"),
                        List.of(header(SECRET)),
                        Map.of("Content-Type", "text/plain")),
                400,
                "INVALID_REMOTE_REQUEST");
        error(
                post(
                        "start",
                        "{\"leaseGen\":1}" + " ".repeat(8193 - bytes("{\"leaseGen\":1}").length)),
                413,
                "PAYLOAD_TOO_LARGE");
        error(
                post(
                        "complete",
                        complete(semantic())
                                + " ".repeat(262145 - bytes(complete(semantic())).length)),
                413,
                "PAYLOAD_TOO_LARGE");
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE id=?", Integer.class, job))
                .isZero();
        assertNoAuditCanary();
        String start = "{\"leaseGen\":1}";
        body(post("start", start + " ".repeat(8192 - bytes(start).length)), 200);
        String complete = complete(semantic());
        assertThat(
                        body(
                                        post(
                                                "complete",
                                                complete
                                                        + " "
                                                                .repeat(
                                                                        262144
                                                                                - bytes(complete)
                                                                                        .length)),
                                        200)
                                .path("accepted")
                                .booleanValue())
                .isTrue();
    }

    /** 실제 저장된 관리자 session은 worker cookie-only 인증이나 관리자 CSRF 우회가 아니다. */
    @Test
    void actualAdminCookieDoesNotCrossWorkerBoundaryAndAdminCsrfRemains() throws Exception {
        var prepared = sessions.prepare();
        var principal =
                new AdminPrincipal(
                        creator,
                        db.queryForObject(
                                "SELECT account_key FROM admin_account WHERE id=?",
                                UUID.class,
                                creator),
                        UUID.randomUUID(),
                        1);
        sessions.save(prepared, principal);
        Instant now = Instant.now();
        db.update(
                "INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " VALUES (?,?,?,1,'ACTIVE',?,?,?::timestamptz+interval '8 hours',?,?)",
                principal.sessionKey(),
                creator,
                crypto.sessionHash(prepared.id()),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        String cookie =
                "__Host-admin-session=" + Base64.getEncoder().encodeToString(bytes(prepared.id()));
        var me = URI.create("https://localhost:" + port + "/admin/api/auth/me");
        assertThat(
                        send(client, me, "GET", new byte[0], List.of(), Map.of("Cookie", cookie))
                                .statusCode())
                .isEqualTo(200);
        var worker =
                send(
                        client,
                        uri("start"),
                        "POST",
                        bytes("{\"leaseGen\":1}"),
                        List.of(),
                        Map.of("Cookie", cookie));
        error(worker, 401, "WORKER_AUTH_REQUIRED");
        assertAudit(worker, "ANONYMOUS", null, "/internal/api/grading/jobs/{jobKey}/start");
        var logout =
                send(
                        client,
                        URI.create("https://localhost:" + port + "/admin/api/auth/logout"),
                        "POST",
                        bytes("{}"),
                        List.of(),
                        Map.of("Cookie", cookie));
        assertThat(logout.statusCode()).isEqualTo(403);
        assertThat(
                        send(client, me, "GET", new byte[0], List.of(), Map.of("Cookie", cookie))
                                .statusCode())
                .isEqualTo(200);
        var csrf =
                send(
                        client,
                        URI.create("https://localhost:" + port + "/admin/api/auth/csrf"),
                        "GET",
                        new byte[0],
                        List.of(),
                        Map.of());
        assertThat(csrf.statusCode()).isEqualTo(200);
        assertThat(csrf.headers().allValues("Set-Cookie"))
                .anySatisfy(
                        value ->
                                assertThat(value)
                                        .contains(
                                                "__Host-admin-csrf=",
                                                "Secure",
                                                "HttpOnly",
                                                "SameSite=Lax"));
        var accepted =
                send(
                        client,
                        uri("start"),
                        "POST",
                        bytes("{\"leaseGen\":1}"),
                        List.of(header(SECRET)),
                        Map.of("Cookie", cookie));
        body(accepted, 200);
        assertAudit(accepted, "WORKER", WORKER, "/internal/api/grading/jobs/{jobKey}/start");
    }

    /** real plaintext connector는 forwarded spoof나Bearer에도TLS보다 먼저 고정403을 반환한다. */
    @Test
    void plaintextAndForwardedSpoofsNeverBecomeTls() throws Exception {
        assertThat(plaintext.getLocalPort()).isPositive();
        var plain = URI.create("http://127.0.0.1:" + plaintext.getLocalPort() + path("start"));
        for (Map<String, String> headers :
                List.of(
                        Map.<String, String>of(),
                        Map.of(
                                "Forwarded",
                                "proto=https;host=localhost",
                                "X-Forwarded-Proto",
                                "https",
                                "X-Forwarded-Ssl",
                                "on"))) {
            var response =
                    send(
                            client,
                            plain,
                            "POST",
                            bytes("{\"leaseGen\":1}"),
                            List.of(header(SECRET)),
                            headers);
            error(response, 403, "WORKER_TLS_REQUIRED");
            assertThat(response.headers().firstValue("Location")).isEmpty();
            assertAudit(response, "ANONYMOUS", null, "/internal/api/grading/jobs/{jobKey}/start");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE id=?", Integer.class, job))
                .isZero();
    }

    /** 실제 HTTPS에서 TEST poll의 인증·행동 허용과 BATCH 출처 격리를 한 번씩 확인한다. */
    @Test
    void testPollRequiresPermissionAndNeverClaimsBatch() throws Exception {
        URI poll = URI.create("https://localhost:" + port + "/internal/api/grading/poll/" + CODE);
        assertThat(assembly.supportsTest()).isTrue();
        var anonymous = send(client, poll, "POST", new byte[0], List.of(), Map.of());
        error(anonymous, 401, "WORKER_AUTH_REQUIRED");
        assertAudit(anonymous, "ANONYMOUS", null, "/internal/api/grading/poll/{runtimeCode}");
        var denied = send(client, poll, "POST", new byte[0], List.of(header(RESTRICTED)), Map.of());
        error(denied, 403, "REMOTE_EXECUTION_FORBIDDEN");
        assertAudit(
                denied, "WORKER", "HTTP_START_ONLY", "/internal/api/grading/poll/{runtimeCode}");
        var method = send(client, poll, "GET", new byte[0], List.of(header(SECRET)), Map.of());
        error(method, 403, "REMOTE_EXECUTION_FORBIDDEN");
        var running = send(client, poll, "POST", new byte[0], List.of(header(SECRET)), Map.of());
        assertNoContent(running);
        assertAudit(running, "WORKER", WORKER, "/internal/api/grading/poll/{runtimeCode}");
        assertThat(
                        db.queryForMap(
                                "SELECT state,batch_id,report_id,lease_gen,call_count FROM"
                                        + " grade_job WHERE id=?",
                                job))
                .containsEntry("state", "RUNNING")
                .containsEntry("report_id", null)
                .containsEntry("lease_gen", 1L)
                .containsEntry("call_count", 0);
        assertThat(
                        db.queryForObject(
                                "SELECT batch_id IS NOT NULL FROM grade_job WHERE id=?",
                                Boolean.class,
                                job))
                .isTrue();
        db.update(
                "UPDATE grade_job SET state='QUEUED',worker_key=NULL,lease_until=NULL,"
                        + "next_run_at=clock_timestamp() WHERE id=?",
                job);
        var empty = send(client, poll, "POST", new byte[0], List.of(header(SECRET)), Map.of());
        assertNoContent(empty);
        assertThat(
                        db.queryForMap(
                                "SELECT state,worker_key,lease_gen,call_count FROM grade_job WHERE"
                                        + " id=?",
                                job))
                .containsEntry("state", "QUEUED")
                .containsEntry("worker_key", null)
                .containsEntry("lease_gen", 1L)
                .containsEntry("call_count", 0);
    }

    /** 실제 PG/HTTPS 복구 격리: 다른 runtime의 누락된 필수 루트는 건강한 runtime의 임대 취득을 막지 않는다. */
    @Test
    void testPollIsolatesUnrelatedRecoveryFailureWithoutExtendingBudget() throws Exception {
        db.update(
                "UPDATE grade_job SET state='CANCELLED',worker_key=NULL,lease_until=NULL WHERE"
                        + " id=?",
                job);
        long otherRuntime =
                id(
                        "INSERT INTO grade_runtime(code,config_hash,config_data,state)"
                                + " VALUES (?,?,'{}','AVAILABLE') RETURNING id",
                        "HTTP_OTHER_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                        fixture.installation.configHash());
        long failed = testPollingJob(otherRuntime, true);
        long healthy = testPollingJob(runtime, false);
        var failedBefore = db.queryForMap("SELECT * FROM grade_job WHERE id=?", failed);
        var reportBefore =
                db.queryForMap(
                        "SELECT * FROM test_report WHERE id=?", failedBefore.get("report_id"));
        var testBefore =
                db.queryForMap("SELECT * FROM play_test WHERE id=?", reportBefore.get("test_id"));
        var budgetBefore =
                db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", healthy);
        try {
            String otherCode =
                    db.queryForObject(
                            "SELECT code FROM grade_runtime WHERE id=?",
                            String.class,
                            otherRuntime);
            var forbidden =
                    send(
                            client,
                            URI.create(
                                    "https://localhost:"
                                            + port
                                            + "/internal/api/grading/poll/"
                                            + otherCode),
                            "POST",
                            new byte[0],
                            List.of(header(SECRET)),
                            Map.of());
            error(forbidden, 403, "REMOTE_EXECUTION_FORBIDDEN");
            assertAudit(forbidden, "WORKER", WORKER, "/internal/api/grading/poll/{runtimeCode}");
            URI poll =
                    URI.create("https://localhost:" + port + "/internal/api/grading/poll/" + CODE);
            var response =
                    send(client, poll, "POST", new byte[0], List.of(header(SECRET)), Map.of());
            JsonNode lease = body(response, 200);
            assertThat(lease.path("jobKey").asText())
                    .isEqualTo(
                            db.queryForObject(
                                            "SELECT job_key FROM grade_job WHERE id=?",
                                            UUID.class,
                                            healthy)
                                    .toString());
            assertThat(lease.path("leaseGen").asLong()).isEqualTo(1);
            assertThat(Instant.parse(lease.path("deadline").asText()))
                    .isEqualTo(((Timestamp) budgetBefore.get("deadline_at")).toInstant());
            assertAudit(response, "WORKER", WORKER, "/internal/api/grading/poll/{runtimeCode}");
            assertThat(
                            db.queryForMap(
                                    "SELECT accepted_at,deadline_at FROM grade_job WHERE id=?",
                                    healthy))
                    .isEqualTo(budgetBefore);
            assertThat(
                            db.queryForObject(
                                    "SELECT deadline_at=accepted_at+interval '120 seconds'"
                                            + " AND call_count=0 FROM grade_job WHERE id=?",
                                    Boolean.class,
                                    healthy))
                    .isTrue();
            assertThat(db.queryForMap("SELECT * FROM grade_job WHERE id=?", failed))
                    .isEqualTo(failedBefore);
            assertThatThrownBy(
                            () ->
                                    assembly.testRecovery()
                                            .recover((UUID) failedBefore.get("job_key")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("SOURCE_NOT_CURRENT");
            assertThat(db.queryForMap("SELECT * FROM grade_job WHERE id=?", failed))
                    .isEqualTo(failedBefore);
            assertThat(
                            db.queryForMap(
                                    "SELECT * FROM test_report WHERE id=?",
                                    failedBefore.get("report_id")))
                    .usingRecursiveComparison()
                    .isEqualTo(reportBefore);
            assertThat(
                            db.queryForMap(
                                    "SELECT * FROM play_test WHERE id=?",
                                    reportBefore.get("test_id")))
                    .usingRecursiveComparison()
                    .isEqualTo(testBefore);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM grade_attempt WHERE job_id IN (?,?)",
                                    Integer.class,
                                    failed,
                                    healthy))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM grade_event WHERE job_id=?",
                                    Integer.class,
                                    failed))
                    .isZero();
        } finally {
            db.update(
                    "UPDATE grade_job SET state='CANCELLED',worker_key=NULL,lease_until=NULL WHERE"
                            + " id=?",
                    healthy);
        }
    }

    /** 같은 runtime의 실패를 성공으로 숨기지 않고 실제 HTTP 503과 mandatory 접근 감사에 남긴다. */
    @Test
    void testPollReportsSameRuntimeRecoveryFailureWithoutMutation() throws Exception {
        long failed = testPollingJob(runtime, true);
        var before = db.queryForMap("SELECT * FROM grade_job WHERE id=?", failed);
        try {
            URI poll =
                    URI.create("https://localhost:" + port + "/internal/api/grading/poll/" + CODE);
            var response =
                    send(client, poll, "POST", new byte[0], List.of(header(SECRET)), Map.of());
            error(response, 503, "REMOTE_EXECUTION_UNAVAILABLE");
            assertAudit(response, "WORKER", WORKER, "/internal/api/grading/poll/{runtimeCode}");
            assertThat(db.queryForMap("SELECT * FROM grade_job WHERE id=?", failed))
                    .isEqualTo(before);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM grade_event WHERE job_id=?",
                                    Integer.class,
                                    failed))
                    .isZero();
        } finally {
            db.update(
                    "UPDATE grade_job SET state='CANCELLED',worker_key=NULL,lease_until=NULL WHERE"
                            + " id=?",
                    failed);
        }
    }

    /** trust를 생성 인증서로 제한하고 일반 client의 인증서 거절과 별도 인증서의 hostname 거절을 실제 TLS로 검사한다. */
    @Test
    void certificateAndHostnameValidationAreNotBypassed() throws Exception {
        try (var untrusted =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            assertThatThrownBy(
                            () ->
                                    send(
                                            untrusted,
                                            uri("start"),
                                            "POST",
                                            bytes("{}"),
                                            List.of(header(SECRET)),
                                            Map.of()))
                    .isInstanceOf(IOException.class);
        }
        var badStore = fixture.createStore("hostname.p12", "SAN=dns:wrong.invalid");
        var server =
                com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(
                new com.sun.net.httpserver.HttpsConfigurator(serverContext(badStore)));
        AtomicInteger dispatched = new AtomicInteger();
        server.createContext(
                "/",
                exchange -> {
                    dispatched.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try (var trustingBadCert = validatingClient(badStore)) {
            var mismatch = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertThatThrownBy(
                            () ->
                                    send(
                                            trustingBadCert,
                                            mismatch,
                                            "POST",
                                            bytes("{}"),
                                            List.of(),
                                            Map.of()))
                    .isInstanceOf(IOException.class);
            assertThat(dispatched.get()).isZero();
        } finally {
            server.stop(0);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE id=?", Integer.class, job))
                .isZero();
    }

    /** 미활성 실제 context는 registry 존재와 무관하게 503이며 미조립 서비스를 생성하지 않는다. */
    @Test
    void disabledAssemblyIsUnavailableAndMisassembliesRejectEagerly() throws Exception {
        var application = new SpringApplication(DisabledHttpApplication.class);
        List<String> args = new ArrayList<>();
        var properties = fixture.properties();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("server.port", "0");
        properties.forEach((key, value) -> args.add("--" + key + "=" + value));
        try (var context = application.run(args.toArray(String[]::new))) {
            assertThat(context.getBeansOfType(Assembly.class)).isEmpty();
            int disabledPort =
                    ((org.springframework.boot.web.servlet.context
                                            .ServletWebServerApplicationContext)
                                    context)
                            .getWebServer()
                            .getPort();
            error(
                    send(
                            client,
                            URI.create("https://localhost:" + disabledPort + path("start")),
                            "POST",
                            bytes("{\"leaseGen\":1}"),
                            List.of(header(SECRET)),
                            Map.of()),
                    503,
                    "REMOTE_EXECUTION_UNAVAILABLE");
            error(
                    send(
                            client,
                            URI.create(
                                    "https://localhost:"
                                            + disabledPort
                                            + "/internal/api/grading/poll/"
                                            + CODE),
                            "POST",
                            new byte[0],
                            List.of(header(SECRET)),
                            Map.of()),
                    503,
                    "REMOTE_EXECUTION_UNAVAILABLE");
        }
        var otherRegistry = registry();
        assertThatThrownBy(
                        () -> new Assembly(otherRegistry, assembly.start(), assembly.completion()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REMOTE_ASSEMBLY_MISMATCH");
        var otherDs =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var manager = new DataSourceTransactionManager(db.getDataSource());
        var installed = Map.of(CODE, fixture.installation);
        var wrongDsCompletion =
                new GradeCompletionService(
                        new JdbcTemplate(otherDs),
                        new DataSourceTransactionManager(otherDs),
                        assembly.credentials(),
                        installed,
                        crypto,
                        "HTTP_SYNTHETIC_SYSTEM");
        assertThatThrownBy(
                        () ->
                                new Assembly(
                                        assembly.credentials(),
                                        assembly.start(),
                                        wrongDsCompletion))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongManagerStart =
                new GradeStartService(
                        db,
                        new DataSourceTransactionManager(otherDs),
                        assembly.credentials(),
                        installed);
        assertThatThrownBy(
                        () ->
                                new Assembly(
                                        assembly.credentials(),
                                        wrongManagerStart,
                                        assembly.completion()))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongManagerCompletion =
                new GradeCompletionService(
                        db,
                        new DataSourceTransactionManager(otherDs),
                        assembly.credentials(),
                        installed,
                        crypto,
                        "HTTP_SYNTHETIC_SYSTEM");
        assertThatThrownBy(
                        () ->
                                new Assembly(
                                        assembly.credentials(),
                                        assembly.start(),
                                        wrongManagerCompletion))
                .isInstanceOf(IllegalArgumentException.class);
        var missingInstallation =
                new GradeCompletionService(
                        db,
                        manager,
                        assembly.credentials(),
                        Map.of(),
                        crypto,
                        "HTTP_SYNTHETIC_SYSTEM");
        assertThatThrownBy(
                        () ->
                                new Assembly(
                                        assembly.credentials(),
                                        assembly.start(),
                                        missingInstallation))
                .isInstanceOf(IllegalArgumentException.class);
        var nonJdbcStart =
                new GradeStartService(
                        db,
                        new org.springframework.orm.jpa.JpaTransactionManager(),
                        assembly.credentials(),
                        installed);
        assertThatThrownBy(
                        () ->
                                new Assembly(
                                        assembly.credentials(),
                                        nonJdbcStart,
                                        assembly.completion()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 실제 owner는 HTTPS 전체 bytes와 보호 profile/journal을 소비하고 새 fence 뒤 한 번만 chat한다. */
    @Test
    void realHttpsOwningWorkerConsumesWholeResponsesAndDurableIntents() throws Exception {
        db.update(
                "UPDATE grade_job SET lease_until=clock_timestamp()+interval '8 seconds' WHERE"
                        + " id=?",
                job);
        var original =
                db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", job);
        Path descriptor = journal();
        Scope scope = scope();
        var transport = new HttpsTransport(client, "https://localhost:" + port, scope);
        var profiles = new WorkerGradeProfileLoader().load(fixture.profile.toString());
        try (var owner =
                new GradeRemoteBatchWorker(
                        GradeRemoteOnceJournal.open(descriptor.toString()),
                        transport,
                        new GradeRemoteBatchWorker.TransportBounds(
                                8 * 1024 * 1024, Duration.ofSeconds(10)),
                        profiles)) {
            var outcome = owner.runOnce(jobKey, 1);
            assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
            assertThat(outcome.receipt().accepted()).isTrue();
            assertThat(outcome.receipt().state()).isEqualTo("COMPLETED");
            assertThat(transport.completionBytes).isNotEmpty();
            assertThat(SnapshotJson.parse(transport.completionBytes).path("requestId").asText())
                    .isEqualTo(outcome.receipt().requestId().toString());
            assertThat(transport.maximumWaits)
                    .allSatisfy(
                            wait ->
                                    assertThat(wait)
                                            .isPositive()
                                            .isLessThanOrEqualTo(Duration.ofSeconds(10).toNanos()));
            assertThat(transport.waitEvidence)
                    .isNotEmpty()
                    .allSatisfy(
                            evidence ->
                                    assertThat(evidence.waitNanos())
                                            .isPositive()
                                            .isLessThanOrEqualTo(
                                                    evidence.remainingNanos()
                                                            + TimeUnit.MILLISECONDS.toNanos(2)));
            assertThat(transport.waitEvidence.getFirst().waitNanos())
                    .isLessThan(transport.originalLeaseNanos - transport.startRttNanos);
        }
        assertThat(db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", job))
                .isEqualTo(original);
        assertThat(chats.get()).isEqualTo(1);
        assertThat(order.stream().filter(action -> !action.equals("renew")).toList())
                .containsExactly(
                        "start",
                        "tags",
                        "show",
                        "before-chat",
                        "chat-intent",
                        "chat",
                        "tags",
                        "show",
                        "complete");
        assertThat(journalStates(activeJournal)).containsExactly(0, 1, 3, 4, 5);
        assertCalculatorTruth(providerSemantic);
        try (var reopened =
                new GradeRemoteBatchWorker(
                        GradeRemoteOnceJournal.open(descriptor.toString()),
                        transport,
                        new GradeRemoteBatchWorker.TransportBounds(
                                8 * 1024 * 1024, Duration.ofSeconds(10)),
                        profiles)) {
            assertThat(reopened.runOnce(jobKey, 1).state())
                    .isEqualTo(GradeRemoteBatchWorker.State.RECOVERY);
        }
        assertThat(chats.get()).isEqualTo(1);
        assertThat(transport.calls.get()).isEqualTo(3 + transport.renewals.get());
        assertNoAuditCanary();
    }

    /** 실제 TLS listener의 부분 body에서 기다리며 취소를 actual sendAsync까지 전파한다. */
    @Test
    void transportCancellationReachesActualSendAsync() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        var executor = Executors.newCachedThreadPool();
        var server =
                com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(
                new com.sun.net.httpserver.HttpsConfigurator(serverContext(fixture.store)));
        server.setExecutor(executor);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 2);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write('{');
                        stream.flush();
                        entered.countDown();
                        release.await();
                        stream.write('}');
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var transport =
                new HttpsTransport(
                        client, "https://localhost:" + server.getAddress().getPort(), scope());
        try {
            var returned = transport.start(jobKey, 1, Duration.ofSeconds(5));
            var wire = transport.lastWire;
            var observedBody = transport.lastBody;
            var cancellation = transport.lastCancellation;
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(observedBody.firstByte.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(observedBody.received.get()).isEqualTo(1);
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(observedBody.failed.isDone()).isFalse();
            assertThat(observedBody.completed.get()).isZero();
            assertThat(returned.isDone()).isFalse();
            assertThat(wire.isDone()).isFalse();
            assertThat(returned.cancel(true)).isTrue();
            assertThat(returned.isCancelled()).isTrue();
            assertThat(cancellation.get(3, TimeUnit.SECONDS)).isTrue();
            Throwable failure = wire.handle((response, error) -> error).get(3, TimeUnit.SECONDS);
            while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                    && failure.getCause() != null) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(CancellationException.class);
            assertThat(observedBody.failed.get(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(observedBody.completed.get()).isZero();
            assertThat(observedBody.received.get()).isEqualTo(1);
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(transport.calls.get()).isEqualTo(1);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(transport.calls.get()).isEqualTo(1);
        assertThat(requests.get()).isEqualTo(1);
    }

    /** 실제 TLS redirect 응답을 따르거나 요청을 재시도하지 않는다. */
    @Test
    void transportNeverFollowsRedirectOrRetries() throws Exception {
        var server =
                com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(
                new com.sun.net.httpserver.HttpsConfigurator(serverContext(fixture.store)));
        var requests = new AtomicInteger();
        var redirects = new AtomicInteger();
        server.createContext(
                "/internal/",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Location", "/redirect-target");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/redirect-target",
                exchange -> {
                    redirects.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            var transport =
                    new HttpsTransport(
                            client, "https://localhost:" + server.getAddress().getPort(), scope());
            var body = transport.start(jobKey, 1, Duration.ofSeconds(5));
            assertThatThrownBy(() -> body.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThat(requests.get()).isEqualTo(1);
            assertThat(redirects.get()).isZero();
            assertThat(transport.calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    /** 실제 request-start·원래 J/P·확인된 L의 남은 시간과 전달한 wait의 관측값이다. */
    private record WaitEvidence(String action, long waitNanos, long remainingNanos) {}

    /** 실제 본문 구독을 그대로 전달하며 첫 바이트와 종료를 관측한다. 구독을 독립적으로 취소하지 않는다. */
    private static final class ObservedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private final CountDownLatch firstByte = new CountDownLatch(1);
        private final AtomicInteger received = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final CompletableFuture<Throwable> failed = new CompletableFuture<>();

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            int count = 0;
            for (var buffer : buffers) {
                count = Math.addExact(count, buffer.remaining());
            }
            received.addAndGet(count);
            delegate.onNext(buffers);
            if (count > 0) firstByte.countDown();
        }

        @Override
        public void onError(Throwable failure) {
            delegate.onError(failure);
            failed.complete(failure);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
            completed.incrementAndGet();
        }
    }

    /** 실제서버body만받고각request의TLS·redirect금지·전체bodyfuture와취소를결속한다. */
    private static final class HttpsTransport implements GradeRemoteBatchWorker.BoundTransport {
        private final HttpClient client;
        private final String base;
        private final Scope scope;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger renewals = new AtomicInteger();
        private final List<Long> maximumWaits = new CopyOnWriteArrayList<>();
        private final List<WaitEvidence> waitEvidence = new CopyOnWriteArrayList<>();
        private volatile long jobExpiry = Long.MAX_VALUE;
        private volatile long leaseExpiry = Long.MAX_VALUE;
        private volatile long providerExpiry = Long.MAX_VALUE;
        private volatile long originalLeaseNanos;
        private volatile long startRttNanos;
        private volatile CompletableFuture<HttpResponse<byte[]>> lastWire;
        private volatile ObservedBody lastBody;
        private volatile CompletableFuture<Boolean> lastCancellation;
        private volatile byte[] completionBytes;

        private HttpsTransport(HttpClient client, String base, Scope scope) {
            this.client = client;
            this.base = base;
            this.scope = scope;
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        }

        public Scope scope() {
            return scope;
        }

        public CompletableFuture<byte[]> start(UUID key, long gen, Duration wait) {
            return post(key, "start", object().put("leaseGen", gen), wait);
        }

        public CompletableFuture<byte[]> fence(UUID key, AttemptRequest original, Duration wait) {
            return post(key, "before-chat", attempt(original), wait);
        }

        public CompletableFuture<byte[]> renew(UUID key, AttemptRequest original, Duration wait) {
            renewals.incrementAndGet();
            return post(key, "renew", attempt(original), wait);
        }

        public CompletableFuture<byte[]> complete(
                UUID key, GradeRemoteBatchWorker.CompletionCommand command, Duration wait) {
            var node =
                    object().put("leaseGen", command.leaseGen())
                            .put("attemptNo", command.attemptNo())
                            .put("observedProviderVersion", command.observedProviderVersion())
                            .put("providerResponseRef", command.providerResponseRef());
            node.set("result", SnapshotJson.parse(bytes(command.resultJson())));
            return post(key, "complete", node, wait);
        }

        private static ObjectNode attempt(AttemptRequest original) {
            return object().put("leaseGen", original.leaseGen())
                    .put("attemptNo", original.attemptNo())
                    .put("originalAttemptHash", original.originalAttemptHash());
        }

        /** thenApply취소는upstream을취소하지않으므로명시적으로actualfuture를취소한다. */
        private CompletableFuture<byte[]> post(
                UUID key, String action, ObjectNode body, Duration wait) {
            calls.incrementAndGet();
            maximumWaits.add(wait.toNanos());
            order.add(action);
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            base
                                                    + "/internal/api/grading/jobs/"
                                                    + key
                                                    + "/"
                                                    + action))
                            .timeout(wait)
                            .header("Authorization", header(SECRET))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(SnapshotJson.encode(body)))
                            .build();
            long started = System.nanoTime();
            if (!action.equals("start"))
                waitEvidence.add(
                        new WaitEvidence(
                                action,
                                wait.toNanos(),
                                Math.min(providerExpiry, Math.min(jobExpiry, leaseExpiry))
                                        - started));
            var observedBody = new ObservedBody();
            var cancellation = new CompletableFuture<Boolean>();
            lastBody = observedBody;
            lastCancellation = cancellation;
            var wire = client.sendAsync(request, responseInfo -> observedBody);
            lastWire = wire;
            var whole = new CompletableFuture<byte[]>();
            whole.whenComplete(
                    (value, failure) -> {
                        if (whole.isCancelled()) cancellation.complete(wire.cancel(true));
                    });
            wire.whenComplete(
                    (response, failure) -> {
                        if (failure != null) whole.completeExceptionally(failure);
                        else if (response.statusCode() != 200
                                || response.body().length > 8 * 1024 * 1024)
                            whole.completeExceptionally(new IOException("HTTP_TRANSPORT_REFUSED"));
                        else {
                            try {
                                if (!action.equals("complete")) {
                                    var limits = SnapshotJson.parse(response.body()).path("limits");
                                    if (limits.isObject()) {
                                        long observedJob =
                                                started
                                                        + TimeUnit.MILLISECONDS.toNanos(
                                                                limits.path("remainingBudgetMillis")
                                                                        .longValue());
                                        long observedLease =
                                                started
                                                        + TimeUnit.MILLISECONDS.toNanos(
                                                                limits.path("remainingLeaseMillis")
                                                                        .longValue());
                                        jobExpiry = Math.min(jobExpiry, observedJob);
                                        leaseExpiry =
                                                action.equals("renew")
                                                        ? observedLease
                                                        : Math.min(leaseExpiry, observedLease);
                                        if (action.equals("start")) {
                                            originalLeaseNanos = observedLease - started;
                                            providerExpiry =
                                                    started
                                                            + fixture.installationSettings
                                                                    .executionTimeout()
                                                                    .toNanos();
                                            startRttNanos = System.nanoTime() - started;
                                        }
                                    }
                                }
                                if (action.equals("complete")) completionBytes = response.body();
                                whole.complete(response.body());
                            } catch (RuntimeException exception) {
                                whole.completeExceptionally(
                                        new IOException("HTTP_TRANSPORT_REFUSED"));
                            }
                        }
                    });
            return whole;
        }
    }

    /** 서버에 저장된원본semantic과암호화BaseResult를실제Calculator출력과대조한다. */
    private void assertCalculatorTruth(JsonNode semantic) {
        var dataset = new FrozenDatasetValidator().validate(frozen);
        var selected = dataset.select("FULL");
        var validated =
                new GradeResultValidator()
                        .validate(
                                semantic.toString(), selected.report(), dataset.gradingSnapshot());
        var calculated =
                new GradeCalculator()
                        .calculate(selected.report(), dataset.gradingSnapshot(), validated);
        String output =
                decrypt(
                        "SELECT output_cipher FROM grade_attempt WHERE job_id=? AND attempt_no=1",
                        "grade_attempt/" + job + "/1/output/v1");
        assertThat(SnapshotJson.encode(SnapshotJson.parse(bytes(output))))
                .isEqualTo(SnapshotJson.encode(semantic));
        String result =
                decrypt(
                        "SELECT result_cipher FROM grade_job WHERE id=?",
                        "grade_job/" + job + "/result/v1");
        var saved = SnapshotJson.parse(bytes(result));
        assertThat(fields(saved)).containsExactlyInAnyOrder("formatNo", "baseResult");
        assertThat(saved.path("formatNo").isIntegralNumber()).isTrue();
        assertThat(saved.path("formatNo").bigIntegerValue()).isEqualTo(java.math.BigInteger.ONE);
        assertPersistedBaseResult(saved.path("baseResult"), calculated);
        assertThat(output).doesNotContain("PRIVATE_THINKING_CANARY");
        assertThat(
                        db.queryForObject(
                                "SELECT result_data::text FROM grade_job WHERE id=?",
                                String.class,
                                job))
                .doesNotContain("CANARY");
    }

    /**
     * 암호화 결과의 모든 필드·배열·정확한 수치를 별도로 계산한 서버 결과와 대조한다.
     *
     * @param saved 실제 복호화한 baseResult, null 불가
     * @param calculated 원래 전체 사본과 검증 semantic에서 계산한 결과, null 불가
     */
    private static void assertPersistedBaseResult(
            JsonNode saved, com.reasoning.common.grading.model.GradeModels.BaseResult calculated) {
        assertThat(saved.path("baseScore").isIntegralNumber()).isTrue();
        assertThat(saved.path("success").isBoolean()).isTrue();
        JsonNode expected =
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(calculated);
        assertThat(SnapshotJson.encode(saved)).isEqualTo(SnapshotJson.encode(expected));
    }

    /** 전체 결과 oracle은 타입·overflow와 내역·구간·이유의 변경을 하나도 생략하지 않는다. */
    @Test
    void persistedBaseResultOracleRejectsTypeMagnitudeAndNestedDrift() {
        var dataset = new FrozenDatasetValidator().validate(frozen);
        var selected = dataset.select("FULL");
        var validated =
                new GradeResultValidator()
                        .validate(
                                semantic().path("semantic").toString(),
                                selected.report(),
                                dataset.gradingSnapshot());
        var calculated =
                new GradeCalculator()
                        .calculate(selected.report(), dataset.gradingSnapshot(), validated);
        ObjectNode original =
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(calculated);
        assertPersistedBaseResult(original, calculated);
        java.util.List<java.util.function.Consumer<ObjectNode>> changes =
                java.util.List.of(
                        node ->
                                node.put(
                                        "baseScore",
                                        java.math.BigInteger.valueOf(calculated.baseScore())
                                                .add(java.math.BigInteger.ONE.shiftLeft(32))),
                        node ->
                                node.put(
                                        "baseScore",
                                        java.math.BigDecimal.valueOf(calculated.baseScore())
                                                .add(new java.math.BigDecimal("0.5"))),
                        node -> node.put("baseScore", calculated.baseScore().toString()),
                        node -> node.putNull("success"),
                        node -> node.put("success", !calculated.success()),
                        node -> node.put("status", "UNRESOLVED"),
                        node -> node.putArray("items"),
                        node -> ((ObjectNode) node.path("items").get(0)).put("score", 4294967296L),
                        node -> ((ObjectNode) node.path("items").get(0)).putNull("requiredMet"),
                        node -> ((ObjectNode) node.path("items").get(0)).putArray("claims"),
                        node -> ((ObjectNode) node.path("items").get(0)).putArray("contradictions"),
                        node -> ((ObjectNode) node.path("items").get(0)).put("reason", "변경된 이유"),
                        node ->
                                ((ObjectNode) node.path("items").get(0).path("claims").get(0))
                                        .putArray("spans")
                                        .addObject()
                                        .put("field", "method")
                                        .put("start", 0)
                                        .put("end", 1),
                        node -> node.put("unexpected", true));
        for (var change : changes) {
            ObjectNode modified = original.deepCopy();
            change.accept(modified);
            assertThatThrownBy(() -> assertPersistedBaseResult(modified, calculated))
                    .isInstanceOf(AssertionError.class);
        }
    }

    private String decrypt(String query, String aad) {
        return crypto.decrypt(
                new String(db.queryForObject(query, byte[].class, job), StandardCharsets.UTF_8),
                aad);
    }

    /** 새 서버 UUID로 이력 삽입 완료를 유한 대기하고 주체·상태·고정 경로를 실제 DB에서 검사한다. */
    private void assertAudit(
            HttpResponse<byte[]> response, String actor, String worker, String route)
            throws Exception {
        UUID request = requestId(response);
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        List<Map<String, Object>> rows;
        do {
            rows =
                    db.queryForList(
                            "SELECT kind,actor_kind,actor_key,worker_key,route,http_status FROM"
                                    + " access_history WHERE request_id=?",
                            request);
            if (!rows.isEmpty()) break;
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.get("kind")).isEqualTo("SERVER");
        assertThat(row.get("actor_kind")).isEqualTo(actor);
        assertThat(row.get("actor_key")).isNull();
        assertThat(row.get("worker_key")).isEqualTo(worker);
        assertThat(row.get("route")).isEqualTo(route);
        assertThat(row.get("http_status")).isEqualTo(response.statusCode());
    }

    private void assertNoAuditCanary() {
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM access_history WHERE"
                                        + " row_to_json(access_history)::text LIKE '%CANARY%'",
                                Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_event WHERE"
                                        + " row_to_json(grade_event)::text LIKE '%CANARY%'",
                                Integer.class))
                .isZero();
    }

    private HttpResponse<byte[]> post(String action, String body) throws Exception {
        return send(client, uri(action), "POST", bytes(body), List.of(header(SECRET)), Map.of());
    }

    /** 원본 bytes를 stream publisher로 전송하여 Content-Length 없는 chunked 경계도 실제 HTTP로 소비한다. */
    private static HttpResponse<byte[]> send(
            HttpClient client,
            URI uri,
            String method,
            byte[] body,
            List<String> authorization,
            Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        authorization.forEach(value -> builder.header("Authorization", value));
        if (!headers.containsKey("Content-Type"))
            builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(
                method,
                HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(body)));
        var request = builder.build();
        assertThat(request.bodyPublisher().orElseThrow().contentLength()).isEqualTo(-1);
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode body(HttpResponse<byte[]> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("X-CSRF-TOKEN")).isEmpty();
        requestId(response);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .doesNotContain(
                        "CANARY",
                        header(SECRET),
                        CommonUtil.sha256(SECRET),
                        fixture.password,
                        "fixture-only",
                        fixture.store.toString());
        return SnapshotJson.parse(response.body());
    }

    /** TEST poll의 빈 응답은 실제 전송 본문과 세션 발급이 모두 없어야 한다. */
    private static void assertNoContent(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        requestId(response);
    }

    private void error(HttpResponse<byte[]> response, int status, String code) {
        var error = body(response, status);
        assertThat(fields(error)).containsExactlyInAnyOrder("code", "message", "requestId");
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("requestId").asText()).isEqualTo(requestId(response).toString());
        assertThat(error)
                .isEqualTo(
                        com.reasoning.common.grading.security.GradeWorkerSecurityConfig.errorBody(
                                code, requestId(response)));
    }

    private URI uri(String action) {
        return URI.create("https://localhost:" + port + path(action));
    }

    private String path(String action) {
        return "/internal/api/grading/jobs/" + jobKey + "/" + action;
    }

    private static UUID requestId(HttpResponse<byte[]> response) {
        var values = response.headers().allValues("X-Request-Id");
        assertThat(values).hasSize(1);
        UUID value = UUID.fromString(values.getFirst());
        assertThat(value.version()).isEqualTo(4);
        return value;
    }

    private static List<String> fields(JsonNode node) {
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static String tuple(JsonNode start) {
        return object().put("leaseGen", 1)
                .put("attemptNo", start.path("attemptNo").intValue())
                .put(
                        "originalAttemptHash",
                        start.path("input").path("originalAttemptHash").asText())
                .toString();
    }

    private static String complete(ObjectNode result) {
        var node =
                object().put("leaseGen", 1)
                        .put("attemptNo", 1)
                        .putNull("observedProviderVersion")
                        .putNull("providerResponseRef");
        node.set("result", result);
        return node.toString();
    }

    /** 정답과점수없이fullfixture의semantic좌표만false로작성한다. */
    private static ObjectNode semantic() {
        var result = object().put("kind", "COMPLETE");
        var items =
                result.putObject("semantic")
                        .put("formatNo", 1)
                        .put("status", "COMPLETE")
                        .putArray("items");
        var culprit =
                items.addObject()
                        .put("rubricCode", "CULPRIT")
                        .put("reason", "HTTP_DIAGNOSTIC_CANARY");
        culprit.putArray("claims");
        var contradictions = culprit.putArray("contradictions");
        for (String code : List.of("CONTRADICT_CULPRIT", "UNSUPPORTED_ACCOMPLICE"))
            contradictions.addObject().put("code", code).put("met", false).putArray("spans");
        for (String code : List.of("METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            var item =
                    items.addObject()
                            .put("rubricCode", code)
                            .put("reason", "HTTP_DIAGNOSTIC_CANARY");
            item.putArray("contradictions");
            item.putArray("claims")
                    .addObject()
                    .put("code", "CLAIM")
                    .put("met", false)
                    .putArray("spans");
        }
        return result;
    }

    /**
     * 실제 TEST FK와 수락 시점의 불변 120초를 갖는 polling fixture다. 의도적으로 retention·회원 정책 루트가 없어 복구는
     * SOURCE_NOT_CURRENT로 닫힌다. 임대 취득 격리만 검증하며 실행 자격 증명 fixture는 아니다.
     */
    private long testPollingJob(long runtimeId, boolean expiredLease) {
        long snapshot =
                db.queryForObject("SELECT snapshot_id FROM grade_job WHERE id=?", Long.class, job);
        long version =
                db.queryForObject(
                        "SELECT version_id FROM review_snapshot WHERE id=?", Long.class, snapshot);
        db.update(
                "INSERT INTO story_role(version_id,code,name) VALUES (?,'R1','R1'),(?,'R2','R2') ON"
                        + " CONFLICT DO NOTHING",
                version,
                version);
        db.update(
                "INSERT INTO story_pair(version_id,role_a,role_b) VALUES (?,'R1','R2') ON CONFLICT"
                        + " DO NOTHING",
                version);
        long test =
                id(
                        "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                            + " play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,"
                            + " runtime_epoch,role_a,role_b,mode,state,invite_until,started_at,deadline_at,created_by,created_at,updated_at)"
                            + " SELECT ?,?,?,?,?,0,'R1','R2','FUNCTIONAL','RUNNING',t.n+interval '7"
                            + " days', t.n,t.n+interval '15 minutes',?,t.n,t.n FROM t RETURNING id",
                        UUID.randomUUID(),
                        version,
                        snapshot,
                        runtimeId,
                        fixture.installation.configHash(),
                        creator);
        long proposer =
                id(
                        "INSERT INTO member_account(member_key,state) VALUES (?,'ACTIVE') RETURNING"
                                + " id",
                        UUID.randomUUID());
        long acceptor =
                id(
                        "INSERT INTO member_account(member_key,state) VALUES (?,'ACTIVE') RETURNING"
                                + " id",
                        UUID.randomUUID());
        db.update(
                "INSERT INTO test_member(test_id,member_id,slot) VALUES (?,?,1),(?,?,2)",
                test,
                proposer,
                test,
                acceptor);
        long report =
                id(
                        "INSERT INTO"
                            + " test_report(report_key,test_id,snapshot_id,runtime_id,config_hash,"
                            + " runtime_epoch,source_draft_rev,proposer_id,payload_cipher,payload_hash)"
                            + " VALUES (?,?,?,?,?,0,0,?,decode(repeat('00',32),'hex'),?) RETURNING"
                            + " id",
                        UUID.randomUUID(),
                        test,
                        snapshot,
                        runtimeId,
                        fixture.installation.configHash(),
                        proposer,
                        GradeSchemaIT.HASH);
        db.update(
                "UPDATE test_report SET state='ACCEPTED',accepted_by=?,submit_no=1,"
                        + " accepted_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",
                acceptor,
                report);
        long queued =
                id(
                        "INSERT INTO"
                            + " grade_job(job_key,snapshot_id,runtime_id,report_id,report_hash,state,"
                            + " config_hash,rubric_hash,accepted_at,deadline_at) SELECT"
                            + " ?,?,?,r.id,?,'QUEUED',?,?,r.accepted_at,r.accepted_at+interval '120"
                            + " seconds' FROM test_report r WHERE r.id=? RETURNING id",
                        UUID.randomUUID(),
                        snapshot,
                        runtimeId,
                        GradeSchemaIT.HASH,
                        fixture.installation.configHash(),
                        frozen.rubricHash(),
                        report);
        if (expiredLease)
            db.update(
                    "UPDATE grade_job SET state='RUNNING',worker_key=?,lease_gen=1,"
                            + " lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
                    "HTTP_FAILED_" + test,
                    queued);
        return queued;
    }

    private long id(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    /** 폐기DB에만고정trigger를설치한다.실제 SQL 실패이며service mocking이아니다. */
    private void trigger(String table, String operation) {
        db.execute(
                "CREATE FUNCTION public.http_fixture_fault() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN "
                        + operation
                        + " RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER http_fixture_fault BEFORE INSERT ON public."
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION public.http_fixture_fault()");
    }

    private void dropTrigger(String table) {
        db.execute("DROP TRIGGER http_fixture_fault ON public." + table);
        db.execute("DROP FUNCTION public.http_fixture_fault()");
    }

    private static GradeWorkerCredentials registry() {
        return new GradeWorkerCredentials(
                List.of(
                        new Registration(
                                WORKER,
                                CommonUtil.sha256(SECRET),
                                Set.of(CODE),
                                Set.of(Action.CLAIM, Action.START, Action.RENEW, Action.COMPLETE)),
                        new Registration(
                                "HTTP_START_ONLY",
                                CommonUtil.sha256(RESTRICTED),
                                Set.of(CODE),
                                Set.of(Action.START)),
                        new Registration(
                                "HTTP_WRONG_RUNTIME",
                                CommonUtil.sha256(WRONG_RUNTIME),
                                Set.of("OTHER_SYNTHETIC"),
                                Set.of(Action.START))));
    }

    private static String header(byte[] secret) {
        return "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static Scope scope() {
        return new Scope(fixture.serverScope, WORKER, CommonUtil.sha256(SECRET));
    }

    /** 0700/0600 기존 descriptor·journal·lock과 강제 저장한 헤더를 실제 journal에 공급한다. */
    private static Path journal() throws Exception {
        Path root =
                Files.createTempDirectory(
                                fixture.root,
                                "once-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        Path descriptor = protectedFile(root.resolve("descriptor.json"));
        activeJournal = protectedFile(root.resolve("journal.bin"));
        protectedFile(root.resolve("owner.lock"));
        try (var file = FileChannel.open(activeJournal, StandardOpenOption.WRITE)) {
            var bytes = ByteBuffer.wrap(GradeRemoteOnceJournal.provisionedHeader(scope()));
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
        Files.write(
                descriptor,
                SnapshotJson.encode(
                        object().put("formatNo", 1)
                                .put("serverScopeId", fixture.serverScope.toString())
                                .put("workerKey", WORKER)
                                .put("credentialSha256", CommonUtil.sha256(SECRET))
                                .put("journalFile", "journal.bin")
                                .put("lockFile", "owner.lock")
                                .put("capacityBytes", 65536)));
        return descriptor;
    }

    private static List<Integer> journalStates(Path path) throws Exception {
        var buffer = ByteBuffer.wrap(Files.readAllBytes(path));
        buffer.position(GradeRemoteOnceJournal.provisionedHeader(scope()).length);
        List<Integer> states = new ArrayList<>();
        while (buffer.hasRemaining()) {
            assertThat(buffer.getInt()).isEqualTo(90);
            byte[] payload = new byte[90];
            buffer.get(payload);
            byte[] checksum = new byte[32];
            buffer.get(checksum);
            assertThat(checksum)
                    .isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
            states.add(Byte.toUnsignedInt(payload[24]));
        }
        return states;
    }

    private static Path protectedFile(Path path) throws IOException {
        return Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    /** trust store에 생성 인증서만 추가하고 기본 hostname 검증을 유지한다. */
    private static HttpClient validatingClient(Path store) throws Exception {
        KeyStore source = loadStore(store);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("test", source.getCertificate("test"));
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, managers.getTrustManagers(), null);
        return HttpClient.newBuilder()
                .sslContext(ssl)
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static KeyStore loadStore(Path path) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(path)) {
            store.load(stream, fixture.password.toCharArray());
        }
        return store;
    }

    private static SSLContext serverContext(Path path) throws Exception {
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(loadStore(path), fixture.password.toCharArray());
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        return ssl;
    }

    @AfterAll
    static void closeFixtures() throws Exception {
        fixture.provider.stop(0);
        try (var paths = Files.walk(fixture.root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    /** 파일·모델endpoint는테스트수명에만존재하고운영자격·defaultactivation을사용하지않는다. */
    private static final class Fixture {
        private final Path root;
        private final String password = UUID.randomUUID().toString();
        private final UUID serverScope = UUID.randomUUID();
        private final Path store;
        private final Path profile;
        private final com.sun.net.httpserver.HttpServer provider;
        private final InstalledRuntimeManifestVerifier installation;
        private final LocalSemanticEngine.Settings installationSettings;
        private final String cryptoKey = TestKeys.create((byte) 71);
        private final String searchKey = TestKeys.create((byte) 72);
        private final String limitKey = TestKeys.create((byte) 73);
        private final String corpus = TestKeys.createCorpus();

        private Fixture() throws Exception {
            root =
                    Files.createTempDirectory(
                                    "worker-http-",
                                    PosixFilePermissions.asFileAttribute(
                                            PosixFilePermissions.fromString("rwx------")))
                            .toRealPath();
            store = createStore("test.p12", "SAN=dns:localhost,ip:127.0.0.1");
            provider =
                    com.sun.net.httpserver.HttpServer.create(
                            new InetSocketAddress("127.0.0.1", 0), 0);
            installProvider();
            provider.start();
            var dictionary = new GradeDictionary(CODE, List.of(new Term("ONE", "개념", "합성")));
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
            installationSettings = settings;
            installation =
                    new InstalledRuntimeManifestVerifier(
                            new LocalSemanticEngine(settings),
                            dictionary,
                            new InstalledProfile(CODE, "LOCAL", "1", "RULE_20260924"));
            profile = protectedFile(root.resolve("profiles.json"));
            var document = object().put("formatNo", 1);
            var row = document.putArray("profiles").addObject();
            row.putObject("profile")
                    .put("configId", CODE)
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
            Files.write(profile, SnapshotJson.encode(document));
        }

        private static Fixture create() {
            try {
                return new Fixture();
            } catch (Exception exception) {
                throw new IllegalStateException("SYNTHETIC_HTTP_FIXTURE_FAILURE", exception);
            }
        }

        /** keytool는테스트실행때만private0700경로에합성PKCS12를생성한다. */
        private Path createStore(String name, String san) throws Exception {
            Path path = root.resolve(name);
            var command =
                    new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                            "-genkeypair",
                            "-alias",
                            "test",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "1",
                            "-dname",
                            "CN=localhost",
                            "-ext",
                            san,
                            "-storetype",
                            "PKCS12",
                            "-keystore",
                            path.toString(),
                            "-storepass:env",
                            "HTTP_STORE_PASSWORD");
            command.environment().put("HTTP_STORE_PASSWORD", password);
            Process process = command.start();
            boolean ended = process.waitFor(30, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            if (!ended || process.exitValue() != 0)
                throw new IllegalStateException("SYNTHETIC_TLS_FAILURE");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            return path;
        }

        private Map<String, String> properties() {
            return new java.util.HashMap<>(
                    Map.ofEntries(
                            Map.entry("app.auth.crypto-key-file", cryptoKey),
                                    Map.entry("app.auth.search-key-file", searchKey),
                            Map.entry("app.auth.limit-key-file", limitKey),
                                    Map.entry("app.auth.breached-hashes-file", corpus),
                            Map.entry("server.address", "127.0.0.1"),
                                    Map.entry("server.ssl.enabled", "true"),
                            Map.entry("server.ssl.key-store", store.toUri().toString()),
                                    Map.entry("server.ssl.key-store-password", password),
                            Map.entry("server.ssl.key-store-type", "PKCS12"),
                                    Map.entry("server.forward-headers-strategy", "none")));
        }

        /** provider는request schema좌표만false/empty spans로응답하고정답·기대점수를주입하지않는다. */
        private void installProvider() {
            provider.createContext(
                    "/api/tags",
                    exchange -> {
                        order.add("tags");
                        reply(
                                exchange,
                                "{\"models\":[{\"name\":\"qwen3:8b\",\"digest\":\""
                                        + "a".repeat(64)
                                        + "\"}]}");
                    });
            provider.createContext(
                    "/api/show",
                    exchange -> {
                        exchange.getRequestBody().readAllBytes();
                        order.add("show");
                        var show =
                                object().put(
                                                "template",
                                                "{{ .System }}{{ .Prompt }}{{ .Response }}");
                        show.putObject("thinking").putArray("values").add(false).add(true);
                        reply(exchange, show.toString());
                    });
            provider.createContext(
                    "/api/chat",
                    exchange -> {
                        try {
                            var request =
                                    SnapshotJson.parse(exchange.getRequestBody().readAllBytes());
                            assertThat(journalStates(activeJournal)).containsExactly(0, 1, 3);
                            order.add("chat-intent");
                            order.add("chat");
                            chats.incrementAndGet();
                            var projection =
                                    SnapshotJson.parse(
                                            bytes(
                                                    request.path("messages")
                                                            .get(1)
                                                            .path("content")
                                                            .asText()));
                            assertThat(projection.path("input").has("expected")).isFalse();
                            var semantic = object().put("formatNo", 1).put("status", "COMPLETE");
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
                                                .put("reason", "HTTP_DIAGNOSTIC_CANARY");
                                for (String field : List.of("claims", "contradictions")) {
                                    var propositions = item.putArray(field);
                                    for (var tuple : properties.path(field).path("items"))
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
                            providerSemantic = semantic;
                            var envelope =
                                    object().put("model", "qwen3:8b")
                                            .put("done", true)
                                            .put("done_reason", "stop");
                            envelope.putObject("message")
                                    .put("role", "assistant")
                                    .put("thinking", "PRIVATE_THINKING_CANARY")
                                    .put("content", semantic.toString());
                            reply(exchange, envelope.toString());
                        } catch (Exception exception) {
                            exchange.close();
                            throw new IOException("SYNTHETIC_PROVIDER_FAILURE", exception);
                        }
                    });
        }

        private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body)
                throws IOException {
            byte[] bytes = bytes(body);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
