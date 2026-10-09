package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeDictionary.Term;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.service.GradeBatchAggregationService;
import com.reasoning.common.grading.service.GradeCompletionService;
import com.reasoning.common.grading.service.GradeCoordinatorService;
import com.reasoning.common.grading.service.GradeRecoveryService;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.story.service.StoryBatchService;
import com.reasoning.common.story.service.StoryBatchService.ActionResult;
import com.reasoning.common.story.service.StoryGradeEvidenceService;
import com.reasoning.common.story.service.StoryGradeEvidenceService.EvidenceResult;
import com.reasoning.common.story.service.StoryIssueResolutionService;
import com.reasoning.common.story.service.StoryService;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 고정 폐기형 PG·실제 저장 세션·HTTP·설치로 접수/재생/상세만 검사한다. 실제 모델 품질 증거는 아니다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(StoryBatchIT.Installation.class)
class StoryBatchIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    private static final AtomicInteger providerCalls = new AtomicInteger();
    private static HttpServer provider;
    private static InstalledRuntimeManifestVerifier installation;
    @Autowired JdbcTemplate db;
    @Autowired CryptoService crypto;
    @Autowired AdminSessionAdapter sessions;
    @Autowired StoryService stories;
    @Autowired StoryBatchService batches;
    @Autowired StoryIssueResolutionService resolutions;
    @Autowired StoryGradeEvidenceService evidence;
    @Autowired GradeRuntimeRepository runtimes;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    private String runtimeCode;
    private long runtimeId;

    /** 이 클래스의 폐기형 PG와 합성 키만 사용한다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 111));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 112));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 113));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    /** 실제 설치 빈은 파일 지문을 SQL 밖에서 계산하며 모델 응답을 제조하지 않는다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Installation {
        /** 실제 설치 descriptor·파일 지문을 가진 테스트 전용 불변 빈을 제공한다. */
        @Bean
        @Primary
        @Qualifier("gradeInstallations")
        Map<String, InstalledRuntimeManifestVerifier> batchTestInstallations() throws Exception {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext(
                    "/",
                    exchange -> {
                        providerCalls.incrementAndGet();
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                    });
            provider.start();
            var dictionary =
                    new GradeDictionary("BATCH_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
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
                            new InstalledProfile("BATCH_SYNTHETIC", "LOCAL", "1", "RULE_20260924"));
            return Map.of("BATCH_SYNTHETIC", installation);
        }
    }

    /** 각 시험은 실제 불변 runtime 등록을 재사용하며 등록 상태 변경은 시험 finally에서 복구한다. */
    @BeforeEach
    void register() {
        runtimeCode = "BATCH_SYNTHETIC";
        runtimeId =
                runtimes.registerRuntime(
                                runtimeCode,
                                installation.configHash(),
                                installation.registrationManifest().toString())
                        .id();
    }

    /** loopback 공급자에는 접수/조회가 한 번도 전송하지 않았음을 검사한다. */
    @AfterAll
    static void closeProvider() {
        try {
            assertThat(providerCalls.get()).isZero();
        } finally {
            if (provider != null) provider.stop(0);
        }
    }

    /** 전체 3*N과 실제 저장 해시·초기 예산·24시간·부모 불변·필수 원자 행을 대조한다. */
    @Test
    void createsEveryFrozenFixtureWithThreeStagedJobsAndNoContentMutation() {
        Fixture f = fixture();
        String before = content(f);
        UUID key = UUID.randomUUID();
        var result = create(f, key);
        assertThat(result.action()).isEqualTo("BATCH_CREATE");
        assertThat(result.replayed()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.current()).isEqualTo(result.original());
        long batch = batchId(result);
        var stored = db.queryForMap("SELECT * FROM grade_batch WHERE id=?", batch);
        assertThat(stored.get("snapshot_id")).isEqualTo(f.snapshot());
        assertThat(stored.get("runtime_id")).isEqualTo(runtimeId);
        assertThat(stored.get("runtime_epoch")).isEqualTo(0L);
        assertThat(stored.get("payload_hash")).isEqualTo(f.frozen().payloadHash());
        assertThat(stored.get("dataset_hash")).isEqualTo(f.frozen().datasetHash());
        assertThat(stored.get("rubric_hash")).isEqualTo(f.frozen().rubricHash());
        assertThat(stored.get("config_hash")).isEqualTo(installation.configHash());
        assertThat(stored.get("state")).isEqualTo("RUNNING");
        assertThat(stored.get("passed_yn")).isNull();
        assertThat(stored.get("valid_until")).isNull();
        assertThat(stored.get("ended_at")).isNull();
        List<Map<String, Object>> jobs =
                db.queryForList(
                        "SELECT * FROM grade_job WHERE batch_id=? ORDER BY sample_code,repeat_no",
                        batch);
        int n = f.frozen().payload().path("resources").path("gradeSamples").size();
        assertThat(jobs).hasSize(3 * n);
        for (JsonNode sample : f.frozen().payload().path("resources").path("gradeSamples")) {
            String code = sample.path("code").textValue();
            var repeats = jobs.stream().filter(j -> code.equals(j.get("sample_code"))).toList();
            assertThat(repeats)
                    .extracting(j -> ((Number) j.get("repeat_no")).intValue())
                    .containsExactly(1, 2, 3);
            for (var job : repeats) {
                assertThat(job.get("input_hash")).isEqualTo(f.frozen().inputHash(code));
                assertThat(job.get("snapshot_id")).isEqualTo(f.snapshot());
                assertThat(job.get("runtime_id")).isEqualTo(runtimeId);
                assertThat(job.get("rubric_hash")).isEqualTo(f.frozen().rubricHash());
                assertThat(job.get("config_hash")).isEqualTo(installation.configHash());
                assertThat(job.get("state")).isEqualTo("STAGED");
                for (String field :
                        List.of(
                                "accepted_at",
                                "deadline_at",
                                "worker_key",
                                "lease_until",
                                "result_data",
                                "result_cipher",
                                "error_code")) assertThat(job.get(field)).isNull();
                assertThat(((Number) job.get("call_count")).intValue()).isZero();
                assertThat(job.get("lease_gen")).isEqualTo(0L);
                assertThat(job.get("created_at")).isEqualTo(stored.get("created_at"));
            }
        }
        assertThat(content(f)).isEqualTo(before);
        assertThat(count("test_action", "request_key=?", key)).isEqualTo(1);
        assertThat(
                        count(
                                "test_audit",
                                "scope_key=? AND action='BATCH_CREATE'",
                                "version:" + f.version()))
                .isEqualTo(1);
        var detail = detail(f, result);
        assertThat(detail.totalJobs()).isEqualTo(3 * n);
        assertThat(detail.completedJobs()).isZero();
        assertThat(detail.failedComparisons()).isZero();
        assertThat(detail.unresolvedJobs()).isEqualTo(3 * n);
        assertThat(detail.batchDeadline()).isEqualTo(detail.createdAt().plusSeconds(86400));
        assertThat(detail.items())
                .allSatisfy(item -> assertThat(item.comparison()).isEqualTo("PENDING"));
        assertThat(providerCalls.get()).isZero();
    }

    /** 재생은 현재 회수만 재검사하고 과거 수정번호·상태·현재 포인터·runtime 상태를 재요구하지 않는다. */
    @Test
    void replayPreservesOriginalAfterRevisionStatePointerAndRuntimeChanges() {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        var first = create(f, key);
        db.update(
                "UPDATE story_version SET edit_rev=7,status='DRAFT',current_snapshot_id=NULL WHERE"
                        + " id=?",
                f.version());
        db.update("UPDATE grade_runtime SET state='SUSPENDED',epoch=epoch+1 WHERE id=?", runtimeId);
        try {
            StoryBatchService empty =
                    new StoryBatchService(stories, db, runtimes, crypto, Map.of());
            var replay =
                    empty.createBatch(
                            f.actor().sid(),
                            f.actor().principal(),
                            f.code(),
                            1,
                            "0",
                            Long.toString(f.snapshot()),
                            runtimeCode,
                            "REVIEW",
                            key,
                            UUID.randomUUID());
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.changed()).isFalse();
            assertThat(replay.original()).isEqualTo(first.original());
            assertThat(replay.current().editRev()).isEqualTo("7");
            assertThat(replay.current().createdAt()).isEqualTo(first.original().createdAt());
            assertThat(
                            empty.getBatchDetail(
                                            f.actor().sid(),
                                            f.actor().principal(),
                                            f.code(),
                                            1,
                                            first.original().batchKey(),
                                            UUID.randomUUID())
                                    .batchDeadline())
                    .isEqualTo(first.original().createdAt().plusSeconds(86400));
            assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isEqualTo(1);
            assertThat(count("test_action", "request_key=?", key)).isEqualTo(1);
            assertThat(
                            count(
                                    "test_audit",
                                    "scope_key=? AND action='BATCH_CREATE'",
                                    "version:" + f.version()))
                    .isEqualTo(1);
        } finally {
            db.update("UPDATE grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtimeId);
        }
    }

    /** 다른 새 의도는 과거 집합·작업·최초 영수증을 그대로 보존하며 성공 트리거의 과거 변조도 롤백한다. */
    @Test
    void preservesOldRowsAndReceiptsAcrossNewIntentAndTriggerMutation() {
        Fixture f = fixture();
        UUID originalKey = UUID.randomUUID();
        var first = create(f, originalKey);
        String firstRows = batchState(first);
        String receipt =
                db.queryForObject(
                        "SELECT to_jsonb(t)::text FROM test_action t WHERE request_key=?",
                        String.class,
                        originalKey);
        create(f, UUID.randomUUID());
        assertThat(batchState(first)).isEqualTo(firstRows);
        assertThat(
                        db.queryForObject(
                                "SELECT to_jsonb(t)::text FROM test_action t WHERE request_key=?",
                                String.class,
                                originalKey))
                .isEqualTo(receipt);
        trigger(
                "test_audit",
                "UPDATE grade_job SET input_hash=repeat('d',64) WHERE batch_id="
                        + batchId(first)
                        + ";");
        UUID rejected = UUID.randomUUID();
        try {
            assertThatThrownBy(() -> create(f, rejected))
                    .hasMessage("STORY_UNAVAILABLE")
                    .hasNoCause();
            assertThat(batchState(first)).isEqualTo(firstRows);
            assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isEqualTo(2);
            assertThat(count("test_action", "request_key=?", rejected)).isZero();
        } finally {
            removeTrigger("test_audit");
        }
        assertThat(create(f, originalKey).original()).isEqualTo(first.original());
    }

    /** 소유자도 현재 계정·세션·전역/사건 REVIEW 없이 전역 영수증을 조회하지 못한다. */
    @ParameterizedTest
    @ValueSource(
            strings = {"account", "authRev", "mfa", "enrollment", "session", "global", "scope"})
    void revokedReplayDeniesBeforeGlobalReceiptRead(String defect) {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        create(f, key);
        switch (defect) {
            case "account" ->
                    db.update(
                            "UPDATE admin_account SET active_yn=false WHERE id=?",
                            f.actor().principal().accountId());
            case "authRev" ->
                    db.update(
                            "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?",
                            f.actor().principal().accountId());
            case "mfa" ->
                    db.update(
                            "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                            f.actor().principal().accountId());
            case "enrollment" ->
                    db.update(
                            "UPDATE admin_credential SET enrolled_at=NULL,mfa_state='PENDING' WHERE"
                                    + " account_id=?",
                            f.actor().principal().accountId());
            case "session" ->
                    db.update(
                            "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp()"
                                    + " WHERE session_key=?",
                            f.actor().principal().sessionKey());
            case "global" ->
                    db.update(
                            "UPDATE admin_account SET can_review=false WHERE id=?",
                            f.actor().principal().accountId());
            case "scope" ->
                    db.update(
                            "UPDATE story_access SET active_yn=false WHERE story_id=?", f.story());
            default -> throw new AssertionError();
        }
        // SELECT 열 권한을 제거하면 조기 인가가 누락된 경우 다른 저장 오류로 관측된다.
        db.execute("ALTER TABLE test_action RENAME TO batch_receipt_temporarily_unavailable");
        try {
            assertThatThrownBy(() -> create(f, key))
                    .hasMessage(
                            Set.of("global", "scope").contains(defect)
                                    ? "FORBIDDEN"
                                    : "AUTH_REQUIRED");
        } finally {
            db.execute("ALTER TABLE batch_receipt_temporarily_unavailable RENAME TO test_action");
        }
    }

    /** 생성·상세의 확정 직전 자격 변경은 신규 업무·감사와 함께 롤백하고 기존 결과를 보존한다. */
    @ParameterizedTest
    @CsvSource({
        "session,false", "absolute,false", "idle,false", "authRev,false", "mfa,false",
                "global,false", "scope,false",
        "session,true", "absolute,true", "idle,true", "authRev,true", "mfa,true", "global,true",
                "scope,true"
    })
    void authorizationChangesDuringBatchRollbackBeforeCommit(String defect, boolean read) {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        ActionResult original = read ? create(f, key) : null;
        String originalState = read ? batchState(original) : null;
        long jobCount = count("grade_job", "snapshot_id=?", f.snapshot());
        long account = f.actor().principal().accountId();
        String change =
                switch (defect) {
                    case "session" ->
                            "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp()"
                                    + " WHERE account_id="
                                    + account
                                    + ";";
                    case "absolute" ->
                            "UPDATE admin_session SET started_at=expired.n-interval '8"
                                    + " hours',expires_at=expired.n FROM (SELECT"
                                    + " clock_timestamp()-interval '1 second' AS n) expired WHERE"
                                    + " account_id="
                                    + account
                                    + ";";
                    case "idle" ->
                            "UPDATE admin_session SET started_at=started_at-interval '1"
                                + " hour',expires_at=expires_at-interval '1"
                                + " hour',last_action_at=clock_timestamp()-interval '31 minutes'"
                                + " WHERE account_id="
                                    + account
                                    + ";";
                    case "authRev" ->
                            "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id="
                                    + account
                                    + ";";
                    case "mfa" ->
                            "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id="
                                    + account
                                    + ";";
                    case "global" ->
                            "UPDATE admin_account SET can_review=false WHERE id=" + account + ";";
                    case "scope" ->
                            "UPDATE story_access SET active_yn=false WHERE story_id="
                                    + f.story()
                                    + ";";
                    default -> throw new AssertionError();
                };
        String before = content(f);
        trigger("test_audit", change);
        try {
            assertThatThrownBy(
                            () -> {
                                if (read) detail(f, original);
                                else create(f, key);
                            })
                    .hasMessage(
                            Set.of("global", "scope").contains(defect)
                                    ? "FORBIDDEN"
                                    : "AUTH_REQUIRED")
                    .hasNoCause();
            assertThat(content(f)).isEqualTo(before);
            assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isEqualTo(read ? 1 : 0);
            assertThat(count("grade_job", "snapshot_id=?", f.snapshot())).isEqualTo(jobCount);
            assertThat(count("test_action", "request_key=?", key)).isEqualTo(read ? 1 : 0);
            assertThat(
                            count(
                                    "test_audit",
                                    "actor_ref=?",
                                    f.actor().principal().accountKey().toString()))
                    .isEqualTo(read ? 1 : 0);
            if (read) assertThat(batchState(original)).isEqualTo(originalState);
        } finally {
            removeTrigger("test_audit");
        }
        // 자격 변경도 롤백되어 같은 의도는 명시적으로 다시 실행할 수 있다.
        if (read)
            assertThat(detail(f, original).batchKey()).isEqualTo(original.original().batchKey());
        else assertThat(create(f, key).replayed()).isFalse();
    }

    /** 현재 부모 REVIEW가 없는 owner/EDIT는 신규 접수도 허용하지 않는다. */
    @Test
    void ownerWithoutScopedReviewCannotCreate() {
        Fixture f = fixture();
        db.update("UPDATE story_access SET active_yn=false WHERE story_id=?", f.story());
        assertThatThrownBy(() -> create(f, UUID.randomUUID())).hasMessage("FORBIDDEN");
        assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isZero();
    }

    /** 행위자·정규 입력·다른 부모는 전역 같은 키의 승자를 임의로 반환하지 않는다. */
    @Test
    void actorInputAndOtherParentConflictWithGlobalKey() {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        create(f, key);
        assertThatThrownBy(
                        () ->
                                batches.createBatch(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        runtimeCode,
                                        "AVAILABILITY",
                                        key,
                                        UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        Actor other = actor();
        grant(f, other);
        assertThatThrownBy(
                        () ->
                                batches.createBatch(
                                        other.sid(),
                                        other.principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        runtimeCode,
                                        "REVIEW",
                                        key,
                                        UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        Fixture parent = fixture();
        assertThatThrownBy(() -> create(parent, key)).hasMessage("REQUEST_KEY_CONFLICT");
        assertThat(count("grade_batch", "snapshot_id=?", parent.snapshot())).isZero();
    }

    /** 회원 소유 키는 관리자 생성·해소의 영수증이 아니며 새 업무 행도 만들지 않는다. */
    @Test
    void memberReceiptCannotAuthorizeAdministrativeCommands() {
        Fixture f = fixture();
        long member = receiptMember();
        UUID key = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " test_action(request_key,member_id,action,scope_key,request_hash,result_data)"
                    + " VALUES (?,?,'INVITATION_ACCEPT',?,repeat('a',64),'{}'::jsonb)",
                key,
                member,
                "test:" + UUID.randomUUID());
        assertThatThrownBy(() -> create(f, key)).hasMessage("REQUEST_KEY_CONFLICT");
        assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isZero();
        ResolutionFixture resolution = resolutionFixture("INFRA", true);
        String before = issueState(resolution);
        assertThatThrownBy(() -> resolve(resolution, key, "0", UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        assertThat(issueState(resolution)).isEqualTo(before);
    }

    /** 키 충돌 음성 fixture의 FK만 구성하며 가입·실제 회원 인증 증거로 사용하지 않는다. */
    private long receiptMember() {
        return id(
                "INSERT INTO member_account(member_key,state) VALUES (?,'ACTIVE') RETURNING id",
                UUID.randomUUID());
    }

    /** 서로 다른 관리자·부모의 전역 키 경쟁은 실패 거래 롤백 뒤 현재 인가로 재진입한다. */
    @Test
    void concurrentGlobalParentRaceCommitsExactlyOneWholeBatch() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        UUID key = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> race(a, key, barrier));
            var right = executor.submit(() -> race(b, key, barrier));
            var outcomes = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("ACCEPTED", "REQUEST_KEY_CONFLICT");
            assertThat(count("test_action", "request_key=?", key)).isEqualTo(1);
            assertThat(count("grade_batch", "snapshot_id IN (?,?)", a.snapshot(), b.snapshot()))
                    .isEqualTo(1);
            int n = a.frozen().payload().path("resources").path("gradeSamples").size();
            assertThat(count("grade_job", "snapshot_id IN (?,?)", a.snapshot(), b.snapshot()))
                    .isEqualTo(3 * n);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 서로 다른 실제 runtime 잠금의 두 신규 접수가 유니크 INSERT에서 경쟁하고 패자는 전체 거래를 롤백한다. */
    @Test
    void actualUniqueInsertRaceRollsBackLoserBeforeAuthorizedReceiptReentry() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        String secondCode =
                "BATCH_SECOND_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        var dictionary =
                new GradeDictionary("BATCH_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
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
        var second =
                new InstalledRuntimeManifestVerifier(
                        new LocalSemanticEngine(settings),
                        dictionary,
                        new InstalledProfile(secondCode, "LOCAL", "1", "RULE_20260924"));
        runtimes.registerRuntime(
                secondCode, second.configHash(), second.registrationManifest().toString());
        var admission =
                new StoryBatchService(
                        stories,
                        db,
                        runtimes,
                        crypto,
                        Map.of(runtimeCode, installation, secondCode, second));
        UUID key = UUID.randomUUID();
        int advisory = Math.abs(key.hashCode() % 1000000);
        var executor = Executors.newFixedThreadPool(3);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        trigger("test_action", "PERFORM pg_advisory_xact_lock(85006," + advisory + ");");
        try {
            var blocker =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(
                                                    new DataSourceTransactionManager(
                                                            db.getDataSource()))
                                            .execute(
                                                    status -> {
                                                        db.queryForList(
                                                                "SELECT"
                                                                    + " pg_advisory_xact_lock(85006,?)",
                                                                advisory);
                                                        held.countDown();
                                                        await(release);
                                                        return null;
                                                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var left =
                    executor.submit(
                            () -> {
                                try {
                                    admission.createBatch(
                                            a.actor().sid(),
                                            a.actor().principal(),
                                            a.code(),
                                            1,
                                            "0",
                                            Long.toString(a.snapshot()),
                                            runtimeCode,
                                            "REVIEW",
                                            key,
                                            UUID.randomUUID());
                                    return "ACCEPTED";
                                } catch (com.reasoning.common.auth.service.AuthException failure) {
                                    return failure.getMessage();
                                }
                            });
            var right =
                    executor.submit(
                            () -> {
                                try {
                                    admission.createBatch(
                                            b.actor().sid(),
                                            b.actor().principal(),
                                            b.code(),
                                            1,
                                            "0",
                                            Long.toString(b.snapshot()),
                                            secondCode,
                                            "REVIEW",
                                            key,
                                            UUID.randomUUID());
                                    return "ACCEPTED";
                                } catch (com.reasoning.common.auth.service.AuthException failure) {
                                    return failure.getMessage();
                                }
                            });
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            boolean bothAtInsert = false;
            while (System.nanoTime() < end) {
                if (db.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE"
                                    + " datname=current_database() AND wait_event='advisory' AND"
                                    + " query LIKE '%INSERT INTO test_action%'",
                                Integer.class)
                        == 2) {
                    bothAtInsert = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(bothAtInsert).isTrue();
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "REQUEST_KEY_CONFLICT");
            assertThat(count("test_action", "request_key=?", key)).isEqualTo(1);
            assertThat(count("grade_batch", "snapshot_id IN (?,?)", a.snapshot(), b.snapshot()))
                    .isEqualTo(1);
            assertThat(count("grade_job", "snapshot_id IN (?,?)", a.snapshot(), b.snapshot()))
                    .isEqualTo(
                            a.frozen().payload().path("resources").path("gradeSamples").size() * 3);
            assertThat(
                            count(
                                    "test_audit",
                                    "action='BATCH_CREATE' AND scope_key IN (?,?)",
                                    "version:" + a.version(),
                                    "version:" + b.version()))
                    .isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            removeTrigger("test_action");
        }
    }

    /** 동일 의도의 병렬 도착은 한 생성과 한 재생이며 전체 작업 수를 늘리지 않는다. */
    @Test
    void concurrentSameIntentReturnsOriginalOnce() throws Exception {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left =
                    executor.submit(
                            () -> {
                                barrier.await();
                                return create(f, key);
                            });
            var right =
                    executor.submit(
                            () -> {
                                barrier.await();
                                return create(f, key);
                            });
            var a = left.get(15, TimeUnit.SECONDS);
            var b = right.get(15, TimeUnit.SECONDS);
            assertThat(a.replayed()).isNotEqualTo(b.replayed());
            assertThat(a.original()).isEqualTo(b.original());
            assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** runtime 대기 중 부모를 아직 잠그지 않았음을 별도 연결 NOWAIT로 관측한다. */
    @Test
    void runtimeLockPrecedesStoryAndVersionLocks() throws Exception {
        Fixture f = fixture();
        var executor = Executors.newFixedThreadPool(2);
        var runtimeHeld = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var lock =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(
                                                    new DataSourceTransactionManager(
                                                            db.getDataSource()))
                                            .execute(
                                                    status -> {
                                                        db.queryForObject(
                                                                "SELECT id FROM grade_runtime WHERE"
                                                                        + " id=? FOR UPDATE",
                                                                Long.class,
                                                                runtimeId);
                                                        runtimeHeld.countDown();
                                                        await(release);
                                                        return null;
                                                    }));
            assertThat(runtimeHeld.await(5, TimeUnit.SECONDS)).isTrue();
            var admission = executor.submit(() -> create(f, UUID.randomUUID()));
            waitForRuntimeWait();
            new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()))
                    .execute(
                            status -> {
                                db.queryForObject(
                                        "SELECT id FROM story WHERE id=? FOR UPDATE NOWAIT",
                                        Long.class,
                                        f.story());
                                db.queryForObject(
                                        "SELECT id FROM story_version WHERE id=? FOR UPDATE NOWAIT",
                                        Long.class,
                                        f.version());
                                return null;
                            });
            release.countDown();
            lock.get(10, TimeUnit.SECONDS);
            assertThat(admission.get(10, TimeUnit.SECONDS).changed()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** read-only·강한 격리를 포함한 모든 외부 거래를 거절하여 실제 소유 경계를 보존한다. */
    @ParameterizedTest
    @ValueSource(strings = {"readOnly", "serializable", "ordinary"})
    void rejectsEnclosingTransactions(String kind) {
        Fixture f = fixture();
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setReadOnly("readOnly".equals(kind));
        if ("serializable".equals(kind))
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThatThrownBy(() -> tx.execute(status -> create(f, UUID.randomUUID())))
                .hasMessage("STORY_UNAVAILABLE");
        assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isZero();
    }

    /** 실제 설치가 없거나 잠근 불변 manifest와 다르면 신규 접수를 닫고 부분 행을 남기지 않는다. */
    @Test
    void absentInstallationAndManifestTamperDenyNewAdmission() {
        Fixture f = fixture();
        var absent = new StoryBatchService(stories, db, runtimes, crypto, Map.of());
        assertThatThrownBy(
                        () ->
                                absent.createBatch(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        runtimeCode,
                                        "REVIEW",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("RUNTIME_UNAVAILABLE");
        String badCode = "BAD_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        var badManifest = (ObjectNode) installation.registrationManifest();
        badManifest.put("configId", badCode).put("promptHash", "b".repeat(64));
        runtimes.registerRuntime(badCode, SnapshotJson.hash(badManifest), badManifest.toString());
        var mismatch =
                new StoryBatchService(stories, db, runtimes, crypto, Map.of(badCode, installation));
        assertThatThrownBy(
                        () ->
                                mismatch.createBatch(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        badCode,
                                        "REVIEW",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("STORY_UNAVAILABLE");
        assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isZero();
    }

    /** 전체 사람 확인·모든 기대단계·물리 사본 식별자 오류는 접수 전에 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"unchecked", "incomplete", "wrongSource", "wrongPolicy"})
    void rejectsUncheckedIncompleteOrMisboundFrozenSource(String defect) {
        ObjectNode source = FrozenSnapshotContractTest.complete();
        switch (defect) {
            case "unchecked" ->
                    ((ObjectNode) source.path("resources").path("gradeSamples").get(0))
                            .putNull("checkedBy");
            case "incomplete" ->
                    ((ArrayNode) source.path("resources").path("gradeSamples")).remove(0);
            case "wrongSource" -> source.put("sourceRev", "6");
            case "wrongPolicy" -> ((ObjectNode) source.path("policy")).put("policyCode", "OTHER");
            default -> throw new AssertionError();
        }
        if ("wrongPolicy".equals(defect)) {
            assertThatThrownBy(() -> FrozenSnapshotCodec.freeze(source)).hasNoCause();
            return;
        }
        Fixture f = fixture(source);
        assertThatThrownBy(() -> create(f, UUID.randomUUID()))
                .hasMessage("STORY_UNAVAILABLE")
                .hasNoCause();
        assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isZero();
    }

    /** 필수 작업·영수증·감사 쓰기 실패는 집합 전체를 롤백한다. */
    @ParameterizedTest
    @ValueSource(strings = {"grade_job", "test_action", "test_audit"})
    void mandatoryWriteFailuresRollbackWholeBatch(String table) {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        trigger(
                table,
                "grade_job".equals(table)
                        ? "IF NEW.repeat_no=3 THEN RAISE EXCEPTION 'synthetic mandatory failure';"
                                + " END IF;"
                        : "RAISE EXCEPTION 'synthetic mandatory failure';");
        try {
            assertThatThrownBy(() -> create(f, key)).hasMessage("STORY_UNAVAILABLE").hasNoCause();
            assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isZero();
            assertThat(count("grade_job", "snapshot_id=?", f.snapshot())).isZero();
            assertThat(count("test_action", "request_key=?", key)).isZero();
            assertThat(count("test_audit", "scope_key=?", "version:" + f.version())).isZero();
        } finally {
            removeTrigger(table);
        }
    }

    /** 성공 트리거라도 실제 저장 식별자·해시·예산·감사 변조를 감지하여 모두 롤백한다. */
    @ParameterizedTest
    @ValueSource(strings = {"batch", "job", "receipt", "audit", "content"})
    void successfulTriggerMutationRollsBack(String defect) {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        String before = content(f);
        String table =
                switch (defect) {
                    case "batch" -> "grade_batch";
                    case "job" -> "grade_job";
                    case "receipt" -> "test_action";
                    default -> "test_audit";
                };
        String statement =
                switch (defect) {
                    case "batch" -> "NEW.dataset_hash := repeat('b',64);";
                    case "job" -> "NEW.call_count := 1;";
                    case "receipt" -> "NEW.request_hash := repeat('c',64);";
                    case "audit" -> "NEW.actor_ref := 'WRONG';";
                    default ->
                            "UPDATE story_version SET title='changed by successful trigger' WHERE"
                                    + " id="
                                    + f.version()
                                    + ";";
                };
        trigger(table, statement);
        try {
            assertThatThrownBy(() -> create(f, key)).hasMessage("STORY_UNAVAILABLE").hasNoCause();
            assertThat(content(f)).isEqualTo(before);
            assertThat(count("grade_batch", "snapshot_id=?", f.snapshot())).isZero();
            assertThat(count("test_action", "request_key=?", key)).isZero();
        } finally {
            removeTrigger(table);
        }
    }

    /** GET은 terminal 조작·원본 해시 손상을 통과시키지 않으며 집계 상태도 변경하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"hash", "failedWithoutEvidence", "summaryWithoutEvidence"})
    void detailFailsClosedOnCorruptTerminalOrSourceEvidence(String defect) {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        long job =
                db.queryForObject(
                        "SELECT min(id) FROM grade_job WHERE batch_id=?",
                        Long.class,
                        batchId(result));
        switch (defect) {
            case "hash" ->
                    db.update("UPDATE grade_job SET input_hash=? WHERE id=?", "e".repeat(64), job);
            case "failedWithoutEvidence" ->
                    db.update(
                            "UPDATE grade_job SET state='FAILED',error_code='ENGINE_TIMEOUT' WHERE"
                                    + " id=?",
                            job);
            case "summaryWithoutEvidence" ->
                    db.update(
                            "UPDATE grade_job SET"
                                    + " state='FAILED',error_code='ENGINE_TIMEOUT',result_data='{}'"
                                    + " WHERE id=?",
                            job);
            default -> throw new AssertionError();
        }
        String before = batchState(result);
        assertThatThrownBy(() -> detail(f, result)).hasMessage("STORY_UNAVAILABLE").hasNoCause();
        assertThat(batchState(result)).isEqualTo(before);
    }

    /** 직접 작성한 합성 의미 입력을 실제 예약·계산·완료에 보내며 정상 음성 기대도 비교 PASS로 구분한다. */
    @ParameterizedTest
    @ValueSource(strings = {"FULL_MATCH", "FULL_MISMATCH", "ZERO_MATCH", "ZERO_MISMATCH"})
    void genuineNormalCompletionComparesOnlyExpectedResultFields(String mode) {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        Worker services = workerServices();
        String sample = mode.startsWith("FULL") ? "FULL" : "ZERO";
        UUID jobKey =
                db.queryForObject(
                        "SELECT job_key FROM grade_job WHERE batch_id=? AND sample_code=? AND"
                                + " repeat_no=1",
                        UUID.class,
                        batchId(result),
                        sample);
        assertThat(services.coordinator().activate(jobKey).changed()).isTrue();
        var lease =
                services.leases().claim(services.identity().workerKey(), runtimeCode).orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(jobKey);
        var reservation = services.start().start(services.identity(), jobKey, lease.leaseGen());
        assertThat(reservation.attemptNo()).isEqualTo(1);
        ObjectNode protocol =
                syntheticNormal(
                        mode.equals("FULL_MATCH") || mode.equals("ZERO_MISMATCH"),
                        mode.equals("ZERO_MATCH"));
        var receipt =
                services.completion()
                        .complete(
                                services.identity(),
                                jobKey,
                                lease.leaseGen(),
                                1,
                                null,
                                null,
                                new String(SnapshotJson.encode(protocol), StandardCharsets.UTF_8),
                                UUID.randomUUID());
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.outcome()).isEqualTo("COMPLETE");
        assertThat(receipt.retryScheduled()).isFalse();
        int score =
                switch (mode) {
                    case "FULL_MATCH", "ZERO_MISMATCH" -> 100;
                    case "FULL_MISMATCH" -> 25;
                    case "ZERO_MATCH" -> 0;
                    default -> throw new AssertionError("알 수 없는 합성 시험");
                };
        JsonNode summary =
                SnapshotJson.parse(
                        db.queryForObject(
                                        "SELECT result_data::text FROM grade_job WHERE job_key=?",
                                        String.class,
                                        jobKey)
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(summary.path("baseScore").intValue()).isEqualTo(score);
        assertThat(summary.path("success").booleanValue()).isEqualTo(score == 100);
        boolean matched = mode.equals("FULL_MATCH") || mode.equals("ZERO_MATCH");
        assertThat(summary.path("expectationMatched").booleanValue()).isEqualTo(matched);
        var detail = detail(f, result);
        assertThat(detail.items())
                .filteredOn(item -> item.jobKey().equals(jobKey))
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.state()).isEqualTo("COMPLETED");
                            assertThat(item.comparison()).isEqualTo(matched ? "PASS" : "FAIL");
                        });
        assertThat(detail.completedJobs()).isEqualTo(1);
        assertThat(detail.failedComparisons()).isEqualTo(matched ? 0 : 1);
        assertThat(detail.unresolvedJobs()).isEqualTo(detail.totalJobs() - 1);
        assertThat(detail.state()).isEqualTo("RUNNING");
        assertThat(detail.passed()).isNull();
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE job_key=?",
                                Integer.class,
                                jobKey))
                .isEqualTo(1);
        assertThat(providerCalls.get()).isZero();
    }

    /** 실제 정상 완료 뒤 손상된 JSON 수를 int로 잘라 정상 비교로 승인하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"formatFraction", "formatOverflow", "scoreOverflow", "baseOverflow"})
    void normalSummaryRejectsNonIntegralAndOverflowingNumbers(String defect) {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        Worker services = workerServices();
        UUID jobKey =
                db.queryForObject(
                        "SELECT job_key FROM grade_job WHERE batch_id=? AND sample_code='FULL' AND"
                                + " repeat_no=1",
                        UUID.class,
                        batchId(result));
        services.coordinator().activate(jobKey);
        var lease =
                services.leases().claim(services.identity().workerKey(), runtimeCode).orElseThrow();
        services.start().start(services.identity(), jobKey, lease.leaseGen());
        assertThat(
                        services.completion()
                                .complete(
                                        services.identity(),
                                        jobKey,
                                        lease.leaseGen(),
                                        1,
                                        null,
                                        null,
                                        new String(
                                                SnapshotJson.encode(syntheticNormal(true, false)),
                                                StandardCharsets.UTF_8),
                                        UUID.randomUUID())
                                .accepted())
                .isTrue();
        ObjectNode summary =
                (ObjectNode)
                        SnapshotJson.parse(
                                db.queryForObject(
                                                "SELECT result_data::text FROM grade_job WHERE"
                                                        + " job_key=?",
                                                String.class,
                                                jobKey)
                                        .getBytes(StandardCharsets.UTF_8));
        java.math.BigInteger wrap = java.math.BigInteger.ONE.shiftLeft(32);
        switch (defect) {
            case "formatFraction" -> summary.put("formatNo", new java.math.BigDecimal("1.1"));
            case "formatOverflow" -> summary.put("formatNo", wrap.add(java.math.BigInteger.ONE));
            case "baseOverflow" ->
                    summary.put("baseScore", wrap.add(java.math.BigInteger.valueOf(100)));
            case "scoreOverflow" -> {
                ObjectNode item = (ObjectNode) summary.path("items").get(0);
                item.put("score", wrap.add(item.path("score").bigIntegerValue()));
            }
            default -> throw new AssertionError("알 수 없는 수 손상 시험");
        }
        db.update(
                "UPDATE grade_job SET result_data=?::jsonb WHERE job_key=?",
                new String(SnapshotJson.encode(summary), StandardCharsets.UTF_8),
                jobKey);
        int before = db.queryForObject("SELECT count(*) FROM test_audit", Integer.class);
        assertThatThrownBy(() -> detail(f, result)).hasMessage("STORY_UNAVAILABLE");
        assertThat(db.queryForObject("SELECT count(*) FROM test_audit", Integer.class))
                .isEqualTo(before);
        assertThat(providerCalls.get()).isZero();
    }

    /** 실제 불일치 완료의 암호문·해시·영수증을 둔 채 일관된 다른 요약으로 PASS를 만들 수 없다. */
    @Test
    void internallyConsistentSummarySubstitutionCannotChangeAuthenticatedComparison() {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        Worker services = workerServices();
        UUID jobKey =
                db.queryForObject(
                        "SELECT job_key FROM grade_job WHERE batch_id=? AND sample_code='FULL' AND"
                                + " repeat_no=1",
                        UUID.class,
                        batchId(result));
        services.coordinator().activate(jobKey);
        var lease =
                services.leases().claim(services.identity().workerKey(), runtimeCode).orElseThrow();
        services.start().start(services.identity(), jobKey, lease.leaseGen());
        assertThat(
                        services.completion()
                                .complete(
                                        services.identity(),
                                        jobKey,
                                        lease.leaseGen(),
                                        1,
                                        null,
                                        null,
                                        new String(
                                                SnapshotJson.encode(syntheticNormal(false, false)),
                                                StandardCharsets.UTF_8),
                                        UUID.randomUUID())
                                .accepted())
                .isTrue();
        assertThat(detail(f, result).items())
                .filteredOn(item -> item.jobKey().equals(jobKey))
                .singleElement()
                .satisfies(item -> assertThat(item.comparison()).isEqualTo("FAIL"));
        var authenticated =
                db.queryForMap(
                        "SELECT result_hash,result_cipher FROM grade_job WHERE job_key=?", jobKey);
        ObjectNode summary =
                (ObjectNode)
                        SnapshotJson.parse(
                                db.queryForObject(
                                                "SELECT result_data::text FROM grade_job WHERE"
                                                        + " job_key=?",
                                                String.class,
                                                jobKey)
                                        .getBytes(StandardCharsets.UTF_8));
        // 테스트 소유 SQL 손상 주입이다. 제공자 호출이나 의미/점수 body 주입은 아니다.
        for (JsonNode row : summary.path("items")) {
            int score =
                    switch (row.path("rubricCode").textValue()) {
                        case "CULPRIT" -> 25;
                        case "METHOD" -> 20;
                        case "TIME" -> 15;
                        case "MOTIVE" -> 10;
                        case "EVIDENCE" -> 30;
                        default -> throw new AssertionError("알 수 없는 합성 항목");
                    };
            ((ObjectNode) row).put("score", score);
            if (row.path("requiredMet").isBoolean()) ((ObjectNode) row).put("requiredMet", true);
        }
        summary.put("baseScore", 100).put("success", true).put("expectationMatched", true);
        db.update(
                "UPDATE grade_job SET result_data=?::jsonb WHERE job_key=?",
                new String(SnapshotJson.encode(summary), StandardCharsets.UTF_8),
                jobKey);
        int before = db.queryForObject("SELECT count(*) FROM test_audit", Integer.class);
        assertThatThrownBy(() -> detail(f, result)).hasMessage("STORY_UNAVAILABLE");
        assertThat(db.queryForObject("SELECT count(*) FROM test_audit", Integer.class))
                .isEqualTo(before);
        assertThat(
                        db.queryForMap(
                                "SELECT result_hash,result_cipher FROM grade_job WHERE job_key=?",
                                jobKey))
                .usingRecursiveComparison()
                .isEqualTo(authenticated);
        assertThat(providerCalls.get()).isZero();
    }

    /** 실제 정상 완료의 AAD·GCM 태그·원래 비공개 결과 해시는 요약과 별개로 모두 결속되어야 한다. */
    @ParameterizedTest
    @ValueSource(strings = {"wrongAad", "tag", "privateHash"})
    void normalComparisonRejectsUnauthenticatedOrChangedPrivateResult(String defect) {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        Worker services = workerServices();
        UUID jobKey =
                db.queryForObject(
                        "SELECT job_key FROM grade_job WHERE batch_id=? AND sample_code='FULL' AND"
                                + " repeat_no=1",
                        UUID.class,
                        batchId(result));
        services.coordinator().activate(jobKey);
        var lease =
                services.leases().claim(services.identity().workerKey(), runtimeCode).orElseThrow();
        services.start().start(services.identity(), jobKey, lease.leaseGen());
        assertThat(
                        services.completion()
                                .complete(
                                        services.identity(),
                                        jobKey,
                                        lease.leaseGen(),
                                        1,
                                        null,
                                        null,
                                        new String(
                                                SnapshotJson.encode(syntheticNormal(true, false)),
                                                StandardCharsets.UTF_8),
                                        UUID.randomUUID())
                                .accepted())
                .isTrue();
        long id = db.queryForObject("SELECT id FROM grade_job WHERE job_key=?", Long.class, jobKey);
        String aad = "grade_job/" + id + "/result/v1";
        String envelope =
                new String(
                        db.queryForObject(
                                "SELECT result_cipher FROM grade_job WHERE job_key=?",
                                byte[].class,
                                jobKey),
                        StandardCharsets.UTF_8);
        String damaged;
        if (defect.equals("tag")) {
            int first = envelope.lastIndexOf('.') + 1;
            damaged =
                    envelope.substring(0, first)
                            + (envelope.charAt(first) == 'A' ? 'B' : 'A')
                            + envelope.substring(first + 1);
        } else {
            String plain = crypto.decrypt(envelope, aad);
            if (defect.equals("privateHash")) {
                ObjectNode privateResult =
                        (ObjectNode) SnapshotJson.parse(plain.getBytes(StandardCharsets.UTF_8));
                ((ObjectNode) privateResult.path("baseResult").path("items").get(0))
                        .put("reason", "합성 비공개 저장 변조");
                plain = new String(SnapshotJson.encode(privateResult), StandardCharsets.UTF_8);
            }
            damaged =
                    crypto.encrypt(
                            plain,
                            defect.equals("wrongAad")
                                    ? "grade_job/" + (id + 1) + "/result/v1"
                                    : aad);
        }
        db.update(
                "UPDATE grade_job SET result_cipher=? WHERE job_key=?",
                damaged.getBytes(StandardCharsets.UTF_8),
                jobKey);
        int before = db.queryForObject("SELECT count(*) FROM test_audit", Integer.class);
        assertThatThrownBy(() -> detail(f, result)).hasMessage("STORY_UNAVAILABLE");
        assertThat(db.queryForObject("SELECT count(*) FROM test_audit", Integer.class))
                .isEqualTo(before);
        assertThat(providerCalls.get()).isZero();
    }

    /** 실제 coordinator 입력 거절 요약은 외부 호출 없이 기대 INPUT_ERROR와 PASS로 비교한다. */
    @Test
    void genuineCoordinatorInputErrorIsPassWithoutProviderOrFakeAttempt() {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        var credentials = new GradeWorkerCredentials(List.of());
        var coordinator =
                new GradeCoordinatorService(
                        db,
                        new DataSourceTransactionManager(db.getDataSource()),
                        credentials,
                        Map.of(runtimeCode, installation),
                        "BATCH_TEST_COORDINATOR",
                        10);
        UUID key =
                db.queryForObject(
                        "SELECT job_key FROM grade_job WHERE batch_id=? AND sample_code='INPUT' AND"
                                + " repeat_no=1",
                        UUID.class,
                        batchId(result));
        var activation = coordinator.activate(key);
        assertThat(activation.changed()).isTrue();
        var detail = detail(f, result);
        assertThat(detail.completedJobs()).isEqualTo(1);
        assertThat(detail.items())
                .filteredOn(item -> item.jobKey().equals(key))
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.comparison()).isEqualTo("PASS");
                            assertThat(item.errorCode()).isEqualTo("INVALID_REPORT");
                        });
        assertThat(detail.passed()).isNull();
        assertThat(detail.state()).isEqualTo("RUNNING");
        assertThat(count("grade_attempt", "job_id=(SELECT id FROM grade_job WHERE job_key=?)", key))
                .isZero();
        assertThat(providerCalls.get()).isZero();
    }

    /**
     * 명시한 합성 fault의 실제 인증 worker 접수·예약·오류 콜백 세 번으로 내구 SYSTEM_ERROR를 만든다. 모델 점수/출력은 제조하지 않으며 기대
     * 오류·실제 오류가 다르면 FAIL이다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ENGINE_TIMEOUT", "ENGINE_UNAVAILABLE"})
    void durableErrorReceiptsCompareActualFaultAndBudget(String error) {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        Worker services = workerServices();
        UUID jobKey =
                db.queryForObject(
                        "SELECT job_key FROM grade_job WHERE batch_id=? AND sample_code='ENGINE'"
                                + " AND repeat_no=1",
                        UUID.class,
                        batchId(result));
        assertThat(services.coordinator().activate(jobKey).changed()).isTrue();
        Instant originalDeadline =
                db.queryForObject(
                                "SELECT deadline_at FROM grade_job WHERE job_key=?",
                                Timestamp.class,
                                jobKey)
                        .toInstant();
        for (int number = 1; number <= 3; number++) {
            var lease =
                    services.leases()
                            .claim(services.identity().workerKey(), runtimeCode)
                            .orElseThrow();
            assertThat(lease.jobKey()).isEqualTo(jobKey);
            var reservation = services.start().start(services.identity(), jobKey, lease.leaseGen());
            assertThat(reservation.attemptNo()).isEqualTo(number);
            var receipt =
                    services.completion()
                            .complete(
                                    services.identity(),
                                    jobKey,
                                    lease.leaseGen(),
                                    number,
                                    null,
                                    null,
                                    "{\"kind\":\"ERROR\",\"errorCode\":\"" + error + "\"}",
                                    UUID.randomUUID());
            assertThat(receipt.accepted()).isTrue();
            assertThat(receipt.retryScheduled()).isEqualTo(number < 3);
            assertThat(receipt.outcome()).isEqualTo(number == 3 ? "SYSTEM_ERROR" : null);
        }
        var detail = detail(f, result);
        assertThat(detail.items())
                .filteredOn(item -> item.jobKey().equals(jobKey))
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.state()).isEqualTo("FAILED");
                            assertThat(item.errorCode()).isEqualTo(error);
                            assertThat(item.comparison())
                                    .isEqualTo("ENGINE_TIMEOUT".equals(error) ? "PASS" : "FAIL");
                        });
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE job_key=?",
                                Integer.class,
                                jobKey))
                .isEqualTo(3);
        assertThat(
                        db.queryForObject(
                                        "SELECT deadline_at FROM grade_job WHERE job_key=?",
                                        Timestamp.class,
                                        jobKey)
                                .toInstant())
                .isEqualTo(originalDeadline);
        assertThat(detail.state()).isEqualTo("RUNNING");
        assertThat(detail.passed()).isNull();
        assertThat(providerCalls.get()).isZero();
    }

    /** 실제 SYSTEM 복구 감사의 WORKER_LOST 세 번은 기대 TIMEOUT 재현 PASS가 아니다. */
    @Test
    void actualRecoveryExhaustionDoesNotBecomeExpectedEnginePass() {
        Fixture f = fixture();
        var result = create(f, UUID.randomUUID());
        Worker services = workerServices();
        UUID jobKey =
                db.queryForObject(
                        "SELECT job_key FROM grade_job WHERE batch_id=? AND sample_code='ENGINE'"
                                + " AND repeat_no=1",
                        UUID.class,
                        batchId(result));
        services.coordinator().activate(jobKey);
        var recovery =
                new GradeRecoveryService(
                        db,
                        new DataSourceTransactionManager(db.getDataSource()),
                        services.credentials(),
                        Map.of(runtimeCode, installation),
                        "BATCH_TEST_COORDINATOR");
        for (int number = 1; number <= 3; number++) {
            var lease =
                    services.leases()
                            .claim(services.identity().workerKey(), runtimeCode)
                            .orElseThrow();
            assertThat(lease.jobKey()).isEqualTo(jobKey);
            assertThat(
                            services.start()
                                    .start(services.identity(), jobKey, lease.leaseGen())
                                    .attemptNo())
                    .isEqualTo(number);
            db.update(
                    "UPDATE grade_job SET lease_until=clock_timestamp()-interval '1 second' WHERE"
                            + " job_key=?",
                    jobKey);
            assertThat(recovery.recover(jobKey).changed()).isTrue();
        }
        var detail = detail(f, result);
        assertThat(detail.items())
                .filteredOn(item -> item.jobKey().equals(jobKey))
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.state()).isEqualTo("FAILED");
                            assertThat(item.errorCode()).isEqualTo("WORKER_LOST");
                            assertThat(item.comparison()).isEqualTo("FAIL");
                        });
        assertThat(
                        count(
                                "grade_attempt",
                                "job_id=(SELECT id FROM grade_job WHERE job_key=?) AND"
                                        + " completion_data IS NOT NULL",
                                jobKey))
                .isZero();
        assertThat(
                        count(
                                "grade_event",
                                "job_id=(SELECT id FROM grade_job WHERE job_key=?) AND"
                                        + " event_kind='RECOVERY_EXPIRED'",
                                jobKey))
                .isEqualTo(3);
    }

    /** 신규 REVIEW와 AVAILABILITY의 현재 상태·사본·수정번호 조건을 명시적으로 구분한다. */
    @ParameterizedTest
    @ValueSource(strings = {"REVIEW", "READY", "PUBLISHED"})
    void availabilityUsesOnlyActualCurrentEligibleSnapshot(String status) {
        Fixture f = fixture();
        db.update("UPDATE story_version SET status=? WHERE id=?", status, f.version());
        if (!"REVIEW".equals(status))
            assertThatThrownBy(() -> create(f, UUID.randomUUID())).hasMessage("STATE_CONFLICT");
        var accepted =
                batches.createBatch(
                        f.actor().sid(),
                        f.actor().principal(),
                        f.code(),
                        1,
                        "0",
                        Long.toString(f.snapshot()),
                        runtimeCode,
                        "AVAILABILITY",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        assertThat(detail(f, accepted).purpose()).isEqualTo("AVAILABILITY");
        db.update(
                "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL WHERE id=?",
                f.version());
        assertThatThrownBy(
                        () ->
                                batches.createBatch(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        runtimeCode,
                                        "AVAILABILITY",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("STATE_CONFLICT");
    }

    /** 다른 부모의 batch/snapshot은404이며 조회 감사 실패도 원문 없는 응답을 차단한다. */
    @Test
    void scopesParentsAndRequiresContentReadAudit() {
        Fixture a = fixture();
        Fixture b = fixture();
        var result = create(a, UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                batches.getBatchDetail(
                                        b.actor().sid(),
                                        b.actor().principal(),
                                        b.code(),
                                        1,
                                        result.original().batchKey(),
                                        UUID.randomUUID()))
                .hasMessage("NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                batches.createBatch(
                                        b.actor().sid(),
                                        b.actor().principal(),
                                        b.code(),
                                        1,
                                        "0",
                                        Long.toString(a.snapshot()),
                                        runtimeCode,
                                        "REVIEW",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("NOT_FOUND");
        trigger("test_audit", "RAISE EXCEPTION 'synthetic read audit failure';");
        try {
            assertThatThrownBy(() -> detail(a, result))
                    .hasMessage("STORY_UNAVAILABLE")
                    .hasNoCause();
        } finally {
            removeTrigger("test_audit");
        }
        assertThat(
                        count(
                                "test_audit",
                                "action='CONTENT_READ' AND scope_key=?",
                                "batch:" + result.original().batchKey()))
                .isZero();
    }

    /** 기존 HTTPS 세션·CSRF로 정확한 다섯 필드·201/200·no-store·안전 상세·정규 감사 경로를 관측한다. */
    @Test
    void httpExactFiveFieldsNewReplayDetailAndNormalizedRoutes() throws Exception {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        String body = body(f, key);
        Cookie session = sessionCookie(f.actor());
        var csrfResult =
                mvc.perform(httpsGet("/admin/api/auth/csrf").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrf = csrfResult.getResponse().getCookie("__Host-admin-csrf");
        String token =
                parse(csrfResult.getResponse().getContentAsString()).path("token").textValue();
        String root = "/admin/api/stories/" + f.code() + "/versions/1/regressions";
        var first =
                mvc.perform(authorized(root, session, csrf, token).content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode accepted = parse(first.getResponse().getContentAsString());
        assertThat(accepted.size()).isEqualTo(6);
        assertThat(accepted.path("action").asText()).isEqualTo("BATCH_CREATE");
        assertThat(first.getResponse().getHeader("Cache-Control")).contains("no-store");
        var replay =
                mvc.perform(authorized(root, session, csrf, token).content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(parse(replay.getResponse().getContentAsString()).path("original"))
                .isEqualTo(accepted.path("original"));
        assertThat(parse(replay.getResponse().getContentAsString()).path("replayed").booleanValue())
                .isTrue();
        String batchKey = accepted.path("original").path("batchKey").asText();
        var read =
                mvc.perform(httpsGet(root + "/" + batchKey).cookie(session))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode detail = parse(read.getResponse().getContentAsString());
        assertThat(detail.size()).isEqualTo(19);
        assertThat(fields(detail))
                .containsExactlyInAnyOrder(
                        "batchKey",
                        "purpose",
                        "snapshotId",
                        "runtimeConfigId",
                        "runtimeEpoch",
                        "datasetHash",
                        "state",
                        "passed",
                        "repeatCount",
                        "totalJobs",
                        "completedJobs",
                        "failedComparisons",
                        "unresolvedJobs",
                        "createdAt",
                        "batchDeadline",
                        "completedAt",
                        "validUntil",
                        "items",
                        "requestId");
        assertThat(detail.path("items"))
                .allSatisfy(
                        item ->
                                assertThat(fields(item))
                                        .containsExactlyInAnyOrder(
                                                "sampleCode",
                                                "repeatNo",
                                                "jobKey",
                                                "state",
                                                "comparison",
                                                "errorCode"));
        assertThat(read.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(read.getResponse().getContentAsString())
                .doesNotContain(
                        "SELECTED_REPORT",
                        "config_data",
                        "cipher",
                        "baseScore",
                        "expectData",
                        "answer");
        for (String invalid :
                List.of(
                        body.replace(
                                "\"purpose\":\"REVIEW\"", "\"purpose\":\"REVIEW\",\"extra\":1"),
                        body.replace("\"expectedRev\":\"0\"", "\"expectedRev\":0"),
                        body.replace(key.toString(), "00000000-0000-1000-8000-000000000000"),
                        body.replace(
                                "\"purpose\":\"REVIEW\"",
                                "\"purpose\":\"REVIEW\",\"purpose\":\"REVIEW\"")))
            mvc.perform(authorized(root, session, csrf, token).content(invalid))
                    .andExpect(status().isBadRequest());
        mvc.perform(authorized(root + "?unexpected=1", session, csrf, token).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(httpsGet(root + "/" + batchKey + "?unexpected=1").cookie(session))
                .andExpect(status().isBadRequest());
        mvc.perform(authorized(root, session, csrf, token).content(" ".repeat(8193)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(
                        db.queryForList(
                                "SELECT route FROM access_history WHERE actor_key=? AND route LIKE"
                                        + " '%regressions%'",
                                String.class, f.actor().principal().accountKey()))
                .contains(
                        "/admin/api/stories/{storyCode}/versions/{versionNo}/regressions",
                        "/admin/api/stories/{storyCode}/versions/{versionNo}/regressions/{batchKey}");
    }

    /** 두 지적·두 목적의 실제 전체 세 반복 증거로만 해소하고 원본과 운영효력 NULL을 보존한다. */
    @ParameterizedTest
    @CsvSource({"INFRA,REVIEW", "INFRA,AVAILABILITY", "GRADING,REVIEW", "GRADING,AVAILABILITY"})
    void resolvesCurrentReviewIssueFromAuthenticatedFullThreeRepeatEvidence(
            String kind, String purpose) {
        ResolutionFixture r = resolutionFixture(kind, true, purpose);
        String source = resolutionEvidence(r.source());
        String target = resolutionEvidence(r.target());
        String frozen =
                db.queryForObject(
                        "SELECT to_jsonb(s)::text FROM review_snapshot s WHERE id=?",
                        String.class,
                        r.fixture().snapshot());
        UUID key = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var result = resolve(r, key, "0", request);
        assertThat(result.action()).isEqualTo("ISSUE_RESOLVE");
        assertThat(result.replayed()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.requestId()).isEqualTo(request);
        assertThat(result.original()).isEqualTo(result.current());
        assertThat(result.current().issueKey()).isEqualTo(r.issue());
        assertThat(result.current().snapshotId()).isEqualTo(Long.toString(r.fixture().snapshot()));
        assertThat(result.current().state()).isEqualTo("RESOLVED");
        assertThat(result.current().editRev()).isEqualTo("0");
        assertThat(result.current().targetBatchKey()).isEqualTo(r.target().original().batchKey());
        assertThat(result.current().resolvedAt()).isNotNull();
        assertThat(resolutionEvidence(r.source())).isEqualTo(source);
        assertThat(resolutionEvidence(r.target())).isEqualTo(target);
        assertThat(
                        db.queryForObject(
                                "SELECT to_jsonb(s)::text FROM review_snapshot s WHERE id=?",
                                String.class,
                                r.fixture().snapshot()))
                .isEqualTo(frozen);
        assertThat(count("test_action", "request_key=?", key)).isEqualTo(1);
        assertThat(count("test_audit", "request_id=? AND action='ISSUE_RESOLVE'", request))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT valid_until FROM grade_batch WHERE id=?",
                                Timestamp.class,
                                batchId(r.target())))
                .isNull();
        assertThat(providerCalls.get()).isZero();
    }

    /** 최초 성공 이후 상태·수정번호·runtime이 바뀌어도 현재 인가한 원래 영수증만 재생한다. */
    @Test
    void resolutionReplayKeepsOriginalAfterRevisionStateAndTargetRuntimeChanges() {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        UUID key = UUID.randomUUID();
        var first = resolve(r, key, "0", UUID.randomUUID());
        db.update(
                "UPDATE story_version SET"
                        + " edit_rev=edit_rev+1,status='DRAFT',current_snapshot_id=NULL WHERE id=?",
                r.fixture().version());
        db.update(
                "UPDATE grade_runtime SET epoch=epoch+1,state='RETIRED' WHERE id=?",
                r.targetRuntime());
        try {
            var replay = resolve(r, key, "0", UUID.randomUUID());
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.changed()).isFalse();
            assertThat(replay.original()).isEqualTo(first.original());
            assertThat(replay.current().editRev()).isEqualTo("1");
            assertThat(count("test_action", "request_key=?", key)).isEqualTo(1);
        } finally {
            db.update(
                    "UPDATE grade_runtime SET epoch=0,state='AVAILABLE' WHERE id=?",
                    r.targetRuntime());
        }
    }

    /** 신규와 재생 모두 현재 자격을 확인하며 소유권이나 과거 성공으로 우회하지 않는다. */
    @ParameterizedTest
    @CsvSource({
        "scope,false",
        "global,false",
        "session,false",
        "account,false",
        "mfa,false",
        "scope,true",
        "global,true",
        "session,true",
        "account,true",
        "mfa,true"
    })
    void resolutionReplayRequiresCurrentAuthorizationBeforeReceipt(String defect, boolean replay) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        UUID key = UUID.randomUUID();
        if (replay) resolve(r, key, "0", UUID.randomUUID());
        Fixture f = r.fixture();
        switch (defect) {
            case "scope" ->
                    db.update(
                            "UPDATE story_access SET active_yn=false WHERE story_id=?", f.story());
            case "global" ->
                    db.update(
                            "UPDATE admin_account SET can_review=false WHERE id=?",
                            f.actor().principal().accountId());
            case "session" ->
                    db.update(
                            "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp()"
                                    + " WHERE session_key=?",
                            f.actor().principal().sessionKey());
            case "account" ->
                    db.update(
                            "UPDATE admin_account SET active_yn=false WHERE id=?",
                            f.actor().principal().accountId());
            default ->
                    db.update(
                            "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                            f.actor().principal().accountId());
        }
        assertThatThrownBy(() -> resolve(r, key, "0", UUID.randomUUID()))
                .hasMessage(
                        "scope".equals(defect) || "global".equals(defect)
                                ? "FORBIDDEN"
                                : "AUTH_REQUIRED");
        assertThat(count("test_action", "request_key=?", key)).isEqualTo(replay ? 1 : 0);
    }

    /** 입력·주체가 바뀐 전역 키와 이미 해소된 새 의도를 각각 거절한다. */
    @Test
    void resolutionConflictsDistinguishRevisionCanonicalBodyAndResolvedState() {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> resolve(r, key, "1", UUID.randomUUID()))
                .hasMessage("EDIT_CONFLICT");
        resolve(r, key, "0", UUID.randomUUID());
        assertThatThrownBy(() -> resolve(r, key, "1", UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                r.fixture().actor().sid(),
                                                r.fixture().actor().principal(),
                                                r.fixture().code(),
                                                1,
                                                r.issue(),
                                                "0",
                                                key,
                                                "INFRA_RECOVERED",
                                                "OTHER_REF_123",
                                                r.target().original().batchKey(),
                                                null,
                                                UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                r.fixture().actor().sid(),
                                                r.fixture().actor().principal(),
                                                r.fixture().code(),
                                                1,
                                                r.issue(),
                                                "0",
                                                key,
                                                "INFRA_RECOVERED",
                                                "VERIFY_REF_123",
                                                r.source().original().batchKey(),
                                                null,
                                                UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                r.fixture().actor().sid(),
                                                r.fixture().actor().principal(),
                                                r.fixture().code(),
                                                1,
                                                r.issue(),
                                                "0",
                                                key,
                                                "INFRA_RECOVERED",
                                                "VERIFY_REF_123",
                                                null,
                                                "1",
                                                UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                r.fixture().actor().sid(),
                                                r.fixture().actor().principal(),
                                                r.fixture().code(),
                                                1,
                                                UUID.randomUUID(),
                                                "0",
                                                key,
                                                "INFRA_RECOVERED",
                                                "VERIFY_REF_123",
                                                r.target().original().batchKey(),
                                                null,
                                                UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        Actor other = actor();
        grant(r.fixture(), other);
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                other.sid(),
                                                other.principal(),
                                                r.fixture().code(),
                                                1,
                                                r.issue(),
                                                "0",
                                                key,
                                                "INFRA_RECOVERED",
                                                "VERIFY_REF_123",
                                                r.target().original().batchKey(),
                                                null,
                                                UUID.randomUUID()))
                .hasMessage("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(() -> resolve(r, UUID.randomUUID(), "1", UUID.randomUUID()))
                .hasMessage("STATE_CONFLICT");
    }

    /** 새 해소는 현재 회차와 실제 부모에 한정하고 다른 사건의 증거를 숨긴다. */
    @ParameterizedTest
    @ValueSource(strings = {"state", "snapshot", "parent"})
    void newResolutionRequiresCurrentReviewCurrentSnapshotAndActualParent(String defect) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        String issue = issueState(r);
        UUID key = UUID.randomUUID();
        if ("state".equals(defect)) {
            db.update(
                    "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL WHERE id=?",
                    r.fixture().version());
        } else if ("snapshot".equals(defect)) {
            long successor =
                    id(
                            "INSERT INTO"
                                + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                                + " VALUES (?,0,?::jsonb,?,?) RETURNING id",
                            r.fixture().version(),
                            new String(r.fixture().frozen().payloadBytes(), StandardCharsets.UTF_8),
                            UUID.randomUUID(),
                            r.fixture().actor().principal().accountId());
            db.update(
                    "UPDATE story_version SET current_snapshot_id=? WHERE id=?",
                    successor,
                    r.fixture().version());
        }
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                r.fixture().actor().sid(),
                                                r.fixture().actor().principal(),
                                                r.fixture().code(),
                                                "parent".equals(defect) ? 2 : 1,
                                                r.issue(),
                                                "0",
                                                key,
                                                "INFRA_RECOVERED",
                                                "VERIFY_REF_123",
                                                r.target().original().batchKey(),
                                                null,
                                                UUID.randomUUID()))
                .hasMessage("parent".equals(defect) ? "NOT_FOUND" : "STATE_CONFLICT");
        assertThat(issueState(r)).isEqualTo(issue);
        assertThat(count("test_action", "request_key=?", key)).isZero();
    }

    /** 동일 설정 재실행과 configId만 다른 별칭은 판정 교정 증거로 채택하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"same", "alias"})
    void gradingResolutionRejectsSameConfigurationAndConfigIdOnlyAlias(String mode) {
        ResolutionFixture r = resolutionFixture("GRADING", false);
        UUID target = r.target().original().batchKey();
        StoryIssueResolutionService service = r.service();
        if ("alias".equals(mode)) {
            String code =
                    "BATCH_ALIAS_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            var verifier = resolutionInstallation(code, false);
            runtimes.registerRuntime(
                    code, verifier.configHash(), verifier.registrationManifest().toString());
            var registry = Map.of(runtimeCode, installation, code, verifier);
            var admission = new StoryBatchService(stories, db, runtimes, crypto, registry);
            var alias = resolutionBatch(r.fixture(), admission, code, "AVAILABILITY");
            finishResolutionBatch(alias, code, verifier, "PASS");
            target = alias.original().batchKey();
            service = new StoryIssueResolutionService(stories, db, runtimes, admission);
            ObjectNode original = ((ObjectNode) installation.registrationManifest()).deepCopy();
            ObjectNode aliased = ((ObjectNode) verifier.registrationManifest()).deepCopy();
            original.remove("configId");
            aliased.remove("configId");
            assertThat(aliased).isEqualTo(original);
        }
        UUID selected = target;
        StoryIssueResolutionService selectedService = service;
        assertThatThrownBy(
                        () ->
                                selectedService.resolveIssue(
                                        r.fixture().actor().sid(),
                                        r.fixture().actor().principal(),
                                        r.fixture().code(),
                                        1,
                                        r.issue(),
                                        "0",
                                        UUID.randomUUID(),
                                        "GRADING_FIX_VERIFIED",
                                        "VERIFY_REF_123",
                                        selected,
                                        null,
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(issueState(r)).contains("\"state\": \"OPEN\"");
    }

    /** 과거 실패 runtime의 폐기는 실제 새 설정의 교정 증거를 차단하지 않는다. */
    @Test
    void gradingResolutionAllowsHistoricalSourceRuntimeRetirement() {
        ResolutionFixture r = resolutionFixture("GRADING", true);
        db.update("UPDATE grade_runtime SET state='RETIRED',epoch=epoch+1 WHERE id=?", runtimeId);
        try {
            assertThat(resolve(r, UUID.randomUUID(), "0", UUID.randomUUID()).changed()).isTrue();
        } finally {
            db.update("UPDATE grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtimeId);
        }
    }

    /** 설치·epoch·전체 비교·인증된 원문 손상은 해소 실패로 닫는다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "epoch",
                "retired",
                "installation",
                "partial",
                "mismatch",
                "falsePass",
                "cipher",
                "summary",
                "crossSnapshot"
            })
    void resolutionRejectsUnavailableOrIncompleteAuthenticatedTargetEvidence(String defect) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        String before = issueState(r);
        StoryIssueResolutionService service = r.service();
        UUID target = r.target().original().batchKey();
        switch (defect) {
            case "epoch" ->
                    db.update(
                            "UPDATE grade_runtime SET epoch=epoch+1 WHERE id=?", r.targetRuntime());
            case "retired" ->
                    db.update(
                            "UPDATE grade_runtime SET state='RETIRED' WHERE id=?",
                            r.targetRuntime());
            case "installation" ->
                    service =
                            new StoryIssueResolutionService(
                                    stories,
                                    db,
                                    runtimes,
                                    new StoryBatchService(stories, db, runtimes, crypto, Map.of()));
            case "partial" ->
                    target =
                            resolutionBatch(r.fixture(), batches, runtimeCode, "REVIEW")
                                    .original()
                                    .batchKey();
            case "mismatch", "falsePass" -> {
                var failed = resolutionBatch(r.fixture(), batches, runtimeCode, "REVIEW");
                finishResolutionBatch(failed, runtimeCode, installation, "GRADING");
                if ("falsePass".equals(defect))
                    db.update("UPDATE grade_batch SET passed_yn=true WHERE id=?", batchId(failed));
                target = failed.original().batchKey();
            }
            case "cipher" ->
                    db.update(
                            "UPDATE grade_job SET result_cipher=decode(repeat('00',48),'hex') WHERE"
                                    + " batch_id=? AND sample_code='FULL' AND repeat_no=1",
                            batchId(r.target()));
            case "summary" ->
                    db.update(
                            "UPDATE grade_job SET"
                                    + " result_data=jsonb_set(result_data,'{baseScore}','0'::jsonb)"
                                    + " WHERE batch_id=? AND sample_code='FULL' AND repeat_no=1",
                            batchId(r.target()));
            default -> {
                Fixture other = fixture();
                var foreign = create(other, UUID.randomUUID());
                finishResolutionBatch(foreign, runtimeCode, installation, "PASS");
                target = foreign.original().batchKey();
            }
        }
        StoryIssueResolutionService selectedService = service;
        UUID selected = target;
        UUID key = UUID.randomUUID();
        try {
            assertThatThrownBy(
                            () ->
                                    selectedService.resolveIssue(
                                            r.fixture().actor().sid(),
                                            r.fixture().actor().principal(),
                                            r.fixture().code(),
                                            1,
                                            r.issue(),
                                            "0",
                                            key,
                                            "INFRA_RECOVERED",
                                            "VERIFY_REF_123",
                                            selected,
                                            null,
                                            UUID.randomUUID()))
                    .hasMessage(
                            switch (defect) {
                                case "epoch", "retired", "installation" -> "RUNTIME_UNAVAILABLE";
                                case "cipher", "summary", "falsePass" -> "STORY_UNAVAILABLE";
                                case "crossSnapshot" -> "NOT_FOUND";
                                default -> "EVIDENCE_INCOMPLETE";
                            })
                    .hasNoCause();
            assertThat(issueState(r)).isEqualTo(before);
            assertThat(count("test_action", "request_key=?", key)).isZero();
        } finally {
            db.update(
                    "UPDATE grade_runtime SET epoch=0,state='AVAILABLE' WHERE id=?",
                    r.targetRuntime());
        }
    }

    /** 실제 점수가 맞아도 공급자 관측 버전이 없거나 다르면 후속 근거로 채택하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "mismatched"})
    void resolutionRequiresProviderVersionAdoptionDespiteAuthenticatedPassingScores(String defect) {
        ResolutionFixture original = resolutionFixture("INFRA", true);
        var target = resolutionBatch(original.fixture(), batches, runtimeCode, "REVIEW");
        finishResolutionBatch(
                target,
                runtimeCode,
                installation,
                "PASS",
                "missing".equals(defect) ? null : "c".repeat(64));
        ResolutionFixture r =
                new ResolutionFixture(
                        original.fixture(),
                        original.source(),
                        target,
                        original.issue(),
                        original.kind(),
                        original.targetRuntime(),
                        original.service());
        var detail = detail(r.fixture(), target);
        assertThat(detail.passed()).isTrue();
        assertThat(detail.items())
                .allSatisfy(item -> assertThat(item.comparison()).isEqualTo("PASS"));
        UUID key = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String issue = issueState(r);
        String source = resolutionEvidence(r.source());
        String evidence = resolutionEvidence(target);
        assertThatThrownBy(() -> resolve(r, key, "0", request))
                .hasMessage("EVIDENCE_INCOMPLETE")
                .hasNoCause();
        assertThat(issueState(r)).isEqualTo(issue);
        assertThat(resolutionEvidence(r.source())).isEqualTo(source);
        assertThat(resolutionEvidence(target)).isEqualTo(evidence);
        assertThat(count("test_action", "request_key=?", key)).isZero();
        assertThat(count("test_audit", "request_id=?", request)).isZero();
    }

    /** 사람·내용·관측 자료를 실제 후속 BATCH만으로 지어내어 해소하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"CONTENT", "OBSERVATION", "reviewTarget", "reason"})
    void resolutionRejectsUnsupportedIssueKindReviewTargetAndReasonKind(String defect) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        if (Set.of("CONTENT", "OBSERVATION").contains(defect))
            withResolutionHistoryGuardDisabled(
                    "execution_issue",
                    "tr_execution_issue_history",
                    () ->
                            db.update(
                                    "UPDATE execution_issue SET kind=? WHERE issue_key=?",
                                    defect,
                                    r.issue()));
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                r.fixture().actor().sid(),
                                                r.fixture().actor().principal(),
                                                r.fixture().code(),
                                                1,
                                                r.issue(),
                                                "0",
                                                UUID.randomUUID(),
                                                "reason".equals(defect)
                                                        ? "GRADING_FIX_VERIFIED"
                                                        : "INFRA_RECOVERED",
                                                "VERIFY_REF_123",
                                                "reviewTarget".equals(defect)
                                                        ? null
                                                        : r.target().original().batchKey(),
                                                "reviewTarget".equals(defect) ? "1" : null,
                                                UUID.randomUUID()))
                .hasMessage("ISSUE_NOT_RESOLVABLE");
    }

    /** 배타 대상·정규 수정번호·UUID·비개인 확인 참조의 닫힌 입력 경계를 검사한다. */
    @ParameterizedTest
    @ValueSource(strings = {"both", "neither", "reference", "reason", "revision", "uuid"})
    void resolutionRejectsMalformedSixFieldIntentWithoutWrites(String defect) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        UUID key = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                r.service()
                                        .resolveIssue(
                                                r.fixture().actor().sid(),
                                                r.fixture().actor().principal(),
                                                r.fixture().code(),
                                                1,
                                                r.issue(),
                                                "revision".equals(defect) ? "01" : "0",
                                                "uuid".equals(defect) ? new UUID(0, 0) : key,
                                                "reason".equals(defect)
                                                        ? "UNKNOWN"
                                                        : "INFRA_RECOVERED",
                                                "reference".equals(defect)
                                                        ? "bad ref"
                                                        : "VERIFY_REF_123",
                                                "neither".equals(defect)
                                                        ? null
                                                        : r.target().original().batchKey(),
                                                "both".equals(defect) ? "1" : null,
                                                UUID.randomUUID()))
                .hasMessage("INVALID_REQUEST");
        assertThat(count("test_action", "request_key=?", key)).isZero();
    }

    /** 후속 성공 트리거가 앞선 읽기 감사를 훼손해도 전체 해소를 롤백한다. */
    @ParameterizedTest
    @ValueSource(strings = {"corrupt", "remove"})
    void resolutionRechecksBothContentReadAuditsAfterFinalWrites(String defect) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        UUID key = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String issue = issueState(r);
        String parent = content(r.fixture());
        String source = resolutionEvidence(r.source());
        String target = resolutionEvidence(r.target());
        String mutation =
                "corrupt".equals(defect)
                        ? "UPDATE test_audit SET detail=jsonb_build_object('batchKey','WRONG')"
                        : "DELETE FROM test_audit";
        trigger(
                "test_audit",
                "IF NEW.action='ISSUE_RESOLVE' THEN "
                        + mutation
                        + " WHERE request_id=NEW.request_id AND action='CONTENT_READ'; END IF;");
        try {
            withResolutionHistoryGuardDisabled(
                    "test_audit",
                    "tr_test_audit_immutable",
                    () -> {
                        assertThatThrownBy(() -> resolve(r, key, "0", request))
                                .hasMessage("STORY_UNAVAILABLE")
                                .hasNoCause();
                        assertThat(issueState(r)).isEqualTo(issue);
                        assertThat(content(r.fixture())).isEqualTo(parent);
                        assertThat(resolutionEvidence(r.source())).isEqualTo(source);
                        assertThat(resolutionEvidence(r.target())).isEqualTo(target);
                        assertThat(count("test_action", "request_key=?", key)).isZero();
                        assertThat(count("test_audit", "request_id=?", request)).isZero();
                    });
        } finally {
            removeTrigger("test_audit");
        }
    }

    /** 고정24시간 실행 마감의 정확한 경계와 초과 시각은 실제 완료 증거가 있어도 거절한다. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void resolutionRejectsTargetCompletionAtOrBeyondFixedBatchDeadline(int lateSeconds) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        withResolutionHistoryGuardDisabled(
                "execution_issue",
                "tr_execution_issue_history",
                () ->
                        db.update(
                                "UPDATE execution_issue SET created_at=clock_timestamp()-interval"
                                        + " '27 hours' WHERE issue_key=?",
                                r.issue()));
        db.update(
                "UPDATE grade_batch SET created_at=clock_timestamp()-interval '26 hours' WHERE"
                        + " id=?",
                batchId(r.target()));
        db.update(
                "UPDATE grade_batch SET ended_at=created_at+interval '24 hours'+(? * interval '1"
                        + " second') WHERE id=?",
                lateSeconds,
                batchId(r.target()));
        assertThat(
                        db.queryForObject(
                                "SELECT ended_at < clock_timestamp() FROM grade_batch WHERE id=?",
                                Boolean.class,
                                batchId(r.target())))
                .isTrue();
        UUID key = UUID.randomUUID();
        String issue = issueState(r);
        assertThatThrownBy(() -> resolve(r, key, "0", UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE")
                .hasNoCause();
        assertThat(issueState(r)).isEqualTo(issue);
        assertThat(count("test_action", "request_key=?", key)).isZero();
    }

    /** 원래 투영 수정번호나 영수증 확정 시각을 훼손한 재생도 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"revision", "createdAt"})
    void resolutionReplayRejectsCorruptedOriginalRevisionAndReceiptTimestamp(String defect) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        UUID key = UUID.randomUUID();
        resolve(r, key, "0", UUID.randomUUID());
        withResolutionHistoryGuardDisabled(
                "test_action",
                "tr_test_action_immutable",
                () -> {
                    if ("revision".equals(defect))
                        db.update(
                                "UPDATE test_action SET"
                                    + " result_data=jsonb_set(jsonb_set(result_data,'{original,editRev}','\"7\"'::jsonb),'{current,editRev}','\"7\"'::jsonb)"
                                    + " WHERE request_key=?",
                                key);
                    else
                        db.update(
                                "UPDATE test_action SET created_at=created_at+interval '1 second'"
                                        + " WHERE request_key=?",
                                key);
                });
        String receipt =
                db.queryForObject(
                        "SELECT to_jsonb(t)::text FROM test_action t WHERE request_key=?",
                        String.class,
                        key);
        String issue = issueState(r);
        UUID request = UUID.randomUUID();
        assertThatThrownBy(() -> resolve(r, key, "0", request))
                .hasMessage("STORY_UNAVAILABLE")
                .hasNoCause();
        assertThat(issueState(r)).isEqualTo(issue);
        assertThat(
                        db.queryForObject(
                                "SELECT to_jsonb(t)::text FROM test_action t WHERE request_key=?",
                                String.class,
                                key))
                .isEqualTo(receipt);
        assertThat(count("test_audit", "request_id=?", request)).isZero();
    }

    /** 폐기형 PG 소유 연결에서만 기존 역사 방어를 잠시 해제하며 성공·실패 모두 원상 복구한다. */
    private void withResolutionHistoryGuardDisabled(String table, String guard, Runnable action) {
        db.execute("ALTER TABLE public." + table + " DISABLE TRIGGER " + guard);
        try {
            action.run();
        } finally {
            db.execute("ALTER TABLE public." + table + " ENABLE TRIGGER " + guard);
        }
    }

    /** 필수 영수증·감사 실패는 해소 업무와 함께 롤백한다. */
    @ParameterizedTest
    @ValueSource(strings = {"test_action", "test_audit"})
    void mandatoryResolutionReceiptAndAuditFailuresRollbackIssueAndRevision(String table) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        String issue = issueState(r);
        String parent = content(r.fixture());
        UUID key = UUID.randomUUID();
        trigger(
                table,
                "IF NEW.action='ISSUE_RESOLVE' THEN RAISE EXCEPTION 'synthetic resolution mandatory"
                        + " failure'; END IF;");
        try {
            assertThatThrownBy(() -> resolve(r, key, "0", UUID.randomUUID()))
                    .hasMessage("STORY_UNAVAILABLE")
                    .hasNoCause();
            assertThat(issueState(r)).isEqualTo(issue);
            assertThat(content(r.fixture())).isEqualTo(parent);
            assertThat(count("test_action", "request_key=?", key)).isZero();
        } finally {
            removeTrigger(table);
        }
    }

    /** 성공으로 반환한 저장 트리거라도 원본·사본·현재 권한 변조를 허용하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"receipt", "audit", "source", "snapshot", "authorization"})
    void successfulResolutionTriggerMutationsRollbackAllEffects(String defect) {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        String issue = issueState(r);
        String parent = content(r.fixture());
        String source = resolutionEvidence(r.source());
        UUID key = UUID.randomUUID();
        String table = "receipt".equals(defect) ? "test_action" : "test_audit";
        String mutation =
                switch (defect) {
                    case "receipt" -> "NEW.request_hash := repeat('d',64);";
                    case "audit" -> "NEW.actor_ref := 'WRONG';";
                    case "source" ->
                            "UPDATE grade_job SET input_hash=repeat('d',64) WHERE batch_id="
                                    + batchId(r.source())
                                    + ";";
                    case "snapshot" ->
                            "UPDATE review_snapshot SET"
                                    + " payload=jsonb_set(payload,'{title}','\"tampered\"'::jsonb)"
                                    + " WHERE id="
                                    + r.fixture().snapshot()
                                    + ";";
                    default ->
                            "UPDATE story_access SET active_yn=false WHERE story_id="
                                    + r.fixture().story()
                                    + ";";
                };
        trigger(table, "IF NEW.action='ISSUE_RESOLVE' THEN " + mutation + " END IF;");
        try {
            assertThatThrownBy(() -> resolve(r, key, "0", UUID.randomUUID()))
                    .hasMessage("authorization".equals(defect) ? "FORBIDDEN" : "STORY_UNAVAILABLE")
                    .hasNoCause();
            assertThat(issueState(r)).isEqualTo(issue);
            assertThat(content(r.fixture())).isEqualTo(parent);
            assertThat(resolutionEvidence(r.source())).isEqualTo(source);
            assertThat(count("test_action", "request_key=?", key)).isZero();
        } finally {
            removeTrigger(table);
        }
    }

    /** 같은 키는 최초/재생, 다른 키는 최초/충돌로 한 지적의 해소를 직렬화한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentResolutionCommitsOneIssueTransitionAndPreservesSource(boolean sameKey)
            throws Exception {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        String source = resolutionEvidence(r.source());
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = sameKey ? firstKey : UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> resolutionRace(r, firstKey, barrier));
            var b = executor.submit(() -> resolutionRace(r, secondKey, barrier));
            List<String> results =
                    List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results).contains("NEW");
            if (sameKey) assertThat(results).containsExactlyInAnyOrder("NEW", "REPLAY");
            else assertThat(results).containsExactlyInAnyOrder("NEW", "STATE_CONFLICT");
            assertThat(count("test_action", "request_key IN (?,?)", firstKey, secondKey))
                    .isEqualTo(1);
            assertThat(resolutionEvidence(r.source())).isEqualTo(source);
            assertThat(
                            db.queryForObject(
                                    "SELECT edit_rev FROM story_version WHERE id=?",
                                    Long.class,
                                    r.fixture().version()))
                    .isEqualTo(0L);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 두 실제 REVIEW 관리자의 경합도 한 행위자·한 영수증·한 해소만 확정한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void twoReviewersResolveOnlyOnceWithoutRetargeting(boolean sameKey) throws Exception {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        Actor other = actor();
        grant(r.fixture(), other);
        Fixture f = r.fixture();
        Fixture otherParent =
                new Fixture(other, f.code(), f.story(), f.version(), f.snapshot(), f.frozen());
        ResolutionFixture second =
                new ResolutionFixture(
                        otherParent,
                        r.source(),
                        r.target(),
                        r.issue(),
                        r.kind(),
                        r.targetRuntime(),
                        r.service());
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = sameKey ? firstKey : UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        String source = resolutionEvidence(r.source());
        try {
            var first = executor.submit(() -> resolutionRace(r, firstKey, barrier));
            var next = executor.submit(() -> resolutionRace(second, secondKey, barrier));
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), next.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            "NEW", sameKey ? "REQUEST_KEY_CONFLICT" : "STATE_CONFLICT");
            assertThat(count("test_action", "request_key IN (?,?)", firstKey, secondKey))
                    .isEqualTo(1);
            assertThat(
                            db.queryForObject(
                                    "SELECT i.resolved_by=a.admin_id FROM execution_issue i JOIN"
                                            + " test_action a ON"
                                            + " a.scope_key='issue:'||i.issue_key::text AND"
                                            + " a.action='ISSUE_RESOLVE' WHERE i.issue_key=?",
                                    Boolean.class,
                                    r.issue()))
                    .isTrue();
            assertThat(resolutionEvidence(r.source())).isEqualTo(source);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 낮은 runtime은 원본/대상 역할과 무관하게 부모보다 먼저 잠근다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolutionLocksRuntimeIdsInAscendingOrderBeforeEitherParent(boolean reverse)
            throws Exception {
        ResolutionFixture selected;
        if (!reverse) {
            selected = resolutionFixture("GRADING", true);
        } else {
            Fixture f = fixture();
            String code =
                    "BATCH_REVERSE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            var verifier = resolutionInstallation(code, true);
            runtimes.registerRuntime(
                    code, verifier.configHash(), verifier.registrationManifest().toString());
            var admission =
                    new StoryBatchService(
                            stories,
                            db,
                            runtimes,
                            crypto,
                            Map.of(runtimeCode, installation, code, verifier));
            var source = resolutionBatch(f, admission, code, "REVIEW");
            finishResolutionBatch(source, code, verifier, "GRADING");
            UUID issue =
                    db.queryForObject(
                            "SELECT issue_key FROM execution_issue WHERE batch_id=? AND"
                                    + " kind='GRADING'",
                            UUID.class,
                            batchId(source));
            var target = resolutionBatch(f, admission, runtimeCode, "REVIEW");
            finishResolutionBatch(target, runtimeCode, installation, "PASS");
            selected =
                    new ResolutionFixture(
                            f,
                            source,
                            target,
                            issue,
                            "GRADING",
                            runtimeId,
                            new StoryIssueResolutionService(stories, db, runtimes, admission));
        }
        ResolutionFixture r = selected;
        long sourceRuntime =
                db.queryForObject(
                        "SELECT runtime_id FROM grade_batch WHERE id=?",
                        Long.class,
                        batchId(r.source()));
        long low = Math.min(sourceRuntime, r.targetRuntime());
        long high = Math.max(sourceRuntime, r.targetRuntime());
        assertThat(low).isLessThan(high);
        var executor = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var blocker =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(
                                                    new DataSourceTransactionManager(
                                                            db.getDataSource()))
                                            .execute(
                                                    status -> {
                                                        db.queryForObject(
                                                                "SELECT id FROM grade_runtime WHERE"
                                                                        + " id=? FOR UPDATE",
                                                                Long.class,
                                                                low);
                                                        held.countDown();
                                                        await(release);
                                                        return null;
                                                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var resolution =
                    executor.submit(() -> resolve(r, UUID.randomUUID(), "0", UUID.randomUUID()));
            waitForRuntimeWait();
            new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()))
                    .execute(
                            status -> {
                                db.queryForObject(
                                        "SELECT id FROM grade_runtime WHERE id=? FOR UPDATE NOWAIT",
                                        Long.class,
                                        high);
                                db.queryForObject(
                                        "SELECT id FROM story WHERE id=? FOR UPDATE NOWAIT",
                                        Long.class,
                                        r.fixture().story());
                                db.queryForObject(
                                        "SELECT id FROM story_version WHERE id=? FOR UPDATE NOWAIT",
                                        Long.class,
                                        r.fixture().version());
                                return null;
                            });
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(resolution.get(10, TimeUnit.SECONDS).changed()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** 잠금 대기 사이 epoch 변경 또는 다른 REVIEW 관리자의 실제 반려가 선행되면 새 해소를 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"epoch", "return"})
    void resolutionRechecksEpochAndReviewReturnAfterRuntimeWait(String transition)
            throws Exception {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        Actor reviewer = actor();
        grant(r.fixture(), reviewer);
        UUID key = UUID.randomUUID();
        String original = issueState(r);
        var executor = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var blocker =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(
                                                    new DataSourceTransactionManager(
                                                            db.getDataSource()))
                                            .execute(
                                                    status -> {
                                                        db.queryForObject(
                                                                "SELECT id FROM grade_runtime WHERE"
                                                                        + " id=? FOR UPDATE",
                                                                Long.class,
                                                                runtimeId);
                                                        held.countDown();
                                                        await(release);
                                                        if ("epoch".equals(transition))
                                                            db.update(
                                                                    "UPDATE grade_runtime SET"
                                                                            + " epoch=epoch+1 WHERE"
                                                                            + " id=?",
                                                                    runtimeId);
                                                        return null;
                                                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var resolution =
                    executor.submit(
                            () -> {
                                try {
                                    resolve(r, key, "0", UUID.randomUUID());
                                    return "ACCEPTED";
                                } catch (com.reasoning.common.auth.service.AuthException failure) {
                                    return failure.getMessage();
                                }
                            });
            waitForRuntimeWait();
            if ("return".equals(transition))
                new com.reasoning.common.story.service.StoryReviewService(stories)
                        .returnToDraft(
                                reviewer.sid(),
                                reviewer.principal(),
                                r.fixture().code(),
                                1,
                                "0",
                                Long.toString(r.fixture().snapshot()),
                                "CHANGES_REQUIRED",
                                "GRADING_ISSUE",
                                "RETURN_REF_01",
                                UUID.randomUUID());
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(resolution.get(10, TimeUnit.SECONDS))
                    .isEqualTo(
                            "epoch".equals(transition) ? "RUNTIME_UNAVAILABLE" : "EDIT_CONFLICT");
            assertThat(issueState(r)).isEqualTo(original);
            assertThat(count("test_action", "request_key=?", key)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            db.update("UPDATE grade_runtime SET epoch=0,state='AVAILABLE' WHERE id=?", runtimeId);
        }
    }

    /** 실제 저장 세션·CSRF의 엄격 여섯 필드와 비원문 영수증·정규 감사 경로를 확인한다. */
    @Test
    void httpResolutionUsesExactSixFieldsCanonicalPathCsrfAndNoStore() throws Exception {
        ResolutionFixture r = resolutionFixture("INFRA", true);
        Cookie session = sessionCookie(r.fixture().actor());
        var issued =
                mvc.perform(httpsGet("/admin/api/auth/csrf").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrf = issued.getResponse().getCookie("__Host-admin-csrf");
        String token = parse(issued.getResponse().getContentAsString()).path("token").textValue();
        String root = "/admin/api/stories/" + r.fixture().code() + "/versions/1/execution-issues/";
        String route = root + r.issue() + "/resolve";
        UUID key = UUID.randomUUID();
        String body = resolutionBody(r, key);
        mvc.perform(
                        post(route)
                                .secure(true)
                                .header("Host", "localhost")
                                .cookie(session)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        for (String invalid :
                List.of(
                        body.replace(
                                "\"targetReviewId\":null", "\"targetReviewId\":null,\"extra\":1"),
                        body.replace(",\"targetReviewId\":null", ""),
                        body.replace("\"expectedRev\":\"0\"", "\"expectedRev\":0"),
                        body.replace(
                                "\"targetReviewId\":null",
                                "\"targetReviewId\":null,\"expectedRev\":\"0\""),
                        body.replace(key.toString(), "00000000-0000-1000-8000-000000000000"))) {
            mvc.perform(authorized(route, session, csrf, token).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        authorized(
                                        root + r.issue().toString().toUpperCase() + "/resolve",
                                        session,
                                        csrf,
                                        token)
                                .content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(authorized(route + "?unexpected=1", session, csrf, token).content(body))
                .andExpect(status().isBadRequest());
        var first =
                mvc.perform(authorized(route, session, csrf, token).content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode result = parse(first.getResponse().getContentAsString());
        assertThat(fields(result))
                .containsExactlyInAnyOrder(
                        "action", "replayed", "changed", "original", "current", "requestId");
        assertThat(fields(result.path("original")))
                .containsExactlyInAnyOrder(
                        "issueKey",
                        "snapshotId",
                        "editRev",
                        "state",
                        "targetBatchKey",
                        "resolvedAt");
        assertThat(result.path("action").asText()).isEqualTo("ISSUE_RESOLVE");
        assertThat(result.path("changed").booleanValue()).isTrue();
        assertThat(result.path("original")).isEqualTo(result.path("current"));
        assertThat(first.getResponse().getHeader("Cache-Control")).contains("no-store");
        var replay =
                mvc.perform(authorized(route, session, csrf, token).content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(parse(replay.getResponse().getContentAsString()).path("original"))
                .isEqualTo(result.path("original"));
        assertThat(parse(replay.getResponse().getContentAsString()).path("replayed").booleanValue())
                .isTrue();
        assertThat(
                        db.queryForList(
                                "SELECT route FROM access_history WHERE actor_key=? AND route LIKE"
                                        + " '%execution-issues%'",
                                String.class, r.fixture().actor().principal().accountKey()))
                .contains(
                        "/admin/api/stories/{storyCode}/versions/{versionNo}/execution-issues/{issueKey}/resolve");
    }

    /** 전체 실제 완료를 원자 근거·검수·전역 영수증에 결속하며 원고와 출처는 보존한다. */
    @Test
    void gradeEvidenceCreatesAtomicServerEvidenceFromActualCompletionPath() {
        Fixture f = fixture();
        ActionResult batch = gradePass(f);
        String before = content(f);
        UUID key = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        EvidenceResult result = grade(f, List.of(batch.original().batchKey()), key, request);
        assertThat(result.action()).isEqualTo("EVIDENCE_CREATE");
        assertThat(result.changed()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.original().result()).isEqualTo("PASS");
        assertThat(result.original().editRev()).isEqualTo("0");
        assertThat(result.current().available()).isTrue();
        assertThat(content(f)).isEqualTo(before);
        var set =
                db.queryForMap(
                        "SELECT * FROM evidence_set WHERE set_key=?", result.original().setKey());
        assertThat(set).hasSize(13);
        assertThat(set.get("snapshot_id")).isEqualTo(f.snapshot());
        assertThat(set.get("runtime_id")).isEqualTo(runtimeId);
        assertThat(set.get("runtime_epoch")).isEqualTo(0L);
        assertThat(set.get("invalidated_at")).isNull();
        assertThat(set.get("invalidated_issue_id")).isNull();
        for (String flag : List.of("providerVersionMatched", "fixtureAdoptionEligible"))
            assertThat(
                            count(
                                    "grade_job",
                                    "batch_id=? AND sample_code IN ('FULL','ZERO') AND"
                                            + " result_data->>?='true'",
                                    batchId(batch),
                                    flag))
                    .isEqualTo(6);
        JsonNode summary = parse(set.get("summary_data").toString());
        assertThat(summary.path("formatNo").intValue()).isEqualTo(1);
        assertThat(summary.path("batchCount").intValue()).isEqualTo(1);
        assertThat(summary.path("totalJobCount").intValue()).isEqualTo(12);
        assertThat(set.get("evidence_hash").toString().trim())
                .isEqualTo(SnapshotJson.hash(summary));
        assertThat(summary.path("payload_hash").asText()).isEqualTo(f.frozen().payloadHash());
        assertThat(summary.path("dataset_hash").asText()).isEqualTo(f.frozen().datasetHash());
        assertThat(summary.path("rubric_hash").asText()).isEqualTo(f.frozen().rubricHash());
        assertThat(summary.path("config_hash").asText()).isEqualTo(installation.configHash());
        assertThat(
                        count(
                                "evidence_item",
                                "set_id=? AND snapshot_id=? AND runtime_id=? AND batch_id=?",
                                set.get("id"),
                                f.snapshot(),
                                runtimeId,
                                batchId(batch)))
                .isEqualTo(1);
        var record =
                db.queryForMap(
                        "SELECT * FROM review_record WHERE id=?",
                        Long.parseLong(result.original().recordId()));
        assertThat(record).hasSize(13);
        assertThat(record.get("evidence_set_id")).isEqualTo(set.get("id"));
        assertThat(record.get("model_id")).isNull();
        assertThat(record.get("effort")).isNull();
        assertThat(record.get("reviewer_id")).isEqualTo(f.actor().principal().accountId());
        assertThat(record.get("self_review_yn")).isEqualTo(false);
        JsonNode wrapper = parse(record.get("evidence_data").toString());
        assertThat(wrapper.path("formatNo").intValue()).isEqualTo(3);
        assertThat(wrapper.path("request").path("expectedRev").asText()).isEqualTo("0");
        assertThat(count("test_action", "request_key=? AND action='EVIDENCE_CREATE'", key))
                .isEqualTo(1);
        assertThat(count("test_audit", "request_id=? AND action='CONTENT_READ'", request))
                .isEqualTo(1);
        assertThat(
                        count(
                                "test_audit",
                                "request_id=? AND action='EVIDENCE_CREATE' AND phase='RESULT'",
                                request))
                .isEqualTo(1);
    }

    /** 과거 성공 재생은 현재 회차·수정번호·설치 자격과 독립이며 아무 행도 추가하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"draft", "snapshot", "revision", "offline", "epoch"})
    void gradeReplayPreservesOriginalAfterCurrentContextChanges(String change) {
        Fixture f = fixture();
        ActionResult batch = gradePass(f);
        UUID key = UUID.randomUUID();
        var first = grade(f, List.of(batch.original().batchKey()), key, UUID.randomUUID());
        try {
            switch (change) {
                case "draft" ->
                        db.update(
                                "UPDATE story_version SET"
                                    + " status='DRAFT',current_snapshot_id=NULL,edit_rev=1 WHERE"
                                    + " id=?",
                                f.version());
                case "snapshot" -> replaceGradeCurrentSnapshot(f);
                case "revision" ->
                        db.update("UPDATE story_version SET edit_rev=1 WHERE id=?", f.version());
                case "offline" ->
                        db.update(
                                "UPDATE grade_runtime SET state='SUSPENDED' WHERE id=?", runtimeId);
                case "epoch" ->
                        db.update("UPDATE grade_runtime SET epoch=epoch+1 WHERE id=?", runtimeId);
            }
            String history = gradeHistory(f);
            UUID request = UUID.randomUUID();
            var replay = grade(f, List.of(batch.original().batchKey()), key, request);
            assertThat(replay.original()).isEqualTo(first.original());
            assertThat(replay.requestId()).isEqualTo(request);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.changed()).isFalse();
            assertThat(replay.current().available()).isTrue();
            assertThat(replay.current().current())
                    .isEqualTo(!Set.of("draft", "snapshot").contains(change));
            assertThat(gradeHistory(f)).isEqualTo(history);
        } finally {
            db.update("UPDATE grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtimeId);
        }
    }

    /** 배열 순서는 의도가 아니며 재생은 원래 집합과 필수 읽기 감사를 중복하지 않는다. */
    @Test
    void gradeReplayCanonicalizesSelectedUuidOrderWithoutNewRows() {
        Fixture f = fixture();
        var a = gradePass(f);
        var b = gradePass(f);
        UUID key = UUID.randomUUID();
        var first =
                grade(
                        f,
                        List.of(a.original().batchKey(), b.original().batchKey()),
                        key,
                        UUID.randomUUID());
        String history = gradeHistory(f);
        var replay =
                grade(
                        f,
                        List.of(b.original().batchKey(), a.original().batchKey()),
                        key,
                        UUID.randomUUID());
        assertThat(replay.original()).isEqualTo(first.original());
        assertThat(replay.replayed()).isTrue();
        assertThat(gradeHistory(f)).isEqualTo(history);
    }

    /** 선택 외 미완료 실행도 숨기지 않으며 정상 관측 버전의 적용 자격을 재검사한다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "pending",
                "unselectedPending",
                "availability",
                "wrongVersion",
                "epoch",
                "openIssue",
                "mismatch"
            })
    void gradeRejectsIncompleteOrUnqualifiedActualExecutions(String defect) {
        Fixture f = fixture();
        var batch =
                "availability".equals(defect)
                        ? resolutionBatch(f, batches, runtimeCode, "AVAILABILITY")
                        : create(f, UUID.randomUUID());
        if (!"pending".equals(defect))
            finishResolutionBatch(
                    batch,
                    runtimeCode,
                    installation,
                    "mismatch".equals(defect) ? "GRADING" : "PASS",
                    "wrongVersion".equals(defect)
                            ? "b".repeat(64)
                            : installation.registrationManifest().path("modelVersion").textValue());
        if ("unselectedPending".equals(defect)) create(f, UUID.randomUUID());
        if ("openIssue".equals(defect)) {
            var failure = create(f, UUID.randomUUID());
            finishResolutionBatch(failure, runtimeCode, installation, "GRADING");
        }
        try {
            if ("epoch".equals(defect))
                db.update("UPDATE grade_runtime SET epoch=epoch+1 WHERE id=?", runtimeId);
            String history = gradeHistory(f);
            assertThatThrownBy(
                            () ->
                                    grade(
                                            f,
                                            List.of(batch.original().batchKey()),
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .hasMessage(
                            "epoch".equals(defect) ? "RUNTIME_UNAVAILABLE" : "EVIDENCE_INCOMPLETE");
            assertThat(gradeHistory(f)).isEqualTo(history);
        } finally {
            db.update("UPDATE grade_runtime SET epoch=0 WHERE id=?", runtimeId);
        }
    }

    /** 실제 완료 이후 집합 시각만 이동하는 경계 fixture이며 단말 실행 행을 제조하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"boundary", "future", "historical"})
    void gradeUsesOriginalStrictCompletionDeadlineNotOperationalAge(String clock) {
        Fixture f = fixture();
        var batch = gradePass(f);
        if ("boundary".equals(clock))
            db.update(
                    "UPDATE grade_batch SET ended_at=created_at+interval '24 hours' WHERE id=?",
                    batchId(batch));
        if ("future".equals(clock))
            db.update(
                    "UPDATE grade_batch SET ended_at=clock_timestamp()+interval '1 hour' WHERE"
                            + " id=?",
                    batchId(batch));
        if ("historical".equals(clock)) {
            // 집합 보관 나이만 이동한다. 실제 완료·GCM·이벤트·영수증은 그대로 검증한다.
            db.update(
                    "UPDATE grade_batch SET created_at=created_at-interval '2"
                            + " days',ended_at=ended_at-interval '2 days' WHERE id=?",
                    batchId(batch));
            assertThat(
                            grade(
                                            f,
                                            List.of(batch.original().batchKey()),
                                            UUID.randomUUID(),
                                            UUID.randomUUID())
                                    .current()
                                    .available())
                    .isTrue();
        } else {
            assertThatThrownBy(
                            () ->
                                    grade(
                                            f,
                                            List.of(batch.original().batchKey()),
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .hasMessage("EVIDENCE_INCOMPLETE");
        }
        assertThat(count("evidence_set", "snapshot_id=?", f.snapshot()))
                .isEqualTo("historical".equals(clock) ? 1 : 0);
    }

    /** 성공 저장 트리거의 변조·삭제·인가 회수도 전역 영수증을 포함해 전체 롤백한다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "set",
                "item",
                "record",
                "receipt",
                "result",
                "readDelete",
                "readMutate",
                "attempt",
                "completion",
                "event",
                "auth"
            })
    void gradeRollsBackSuccessfulStorageAndLateSourceTampering(String fault) {
        Fixture f = fixture();
        var batch = gradePass(f);
        String before = gradeHistory(f);
        String source = resolutionEvidence(batch);
        String content = content(f);
        UUID key = UUID.randomUUID();
        String table =
                switch (fault) {
                    case "set" -> "evidence_set";
                    case "item" -> "evidence_item";
                    case "record" -> "review_record";
                    case "receipt",
                            "readDelete",
                            "readMutate",
                            "attempt",
                            "completion",
                            "event",
                            "auth" ->
                            "test_action";
                    default -> "test_audit";
                };
        String mutation =
                switch (fault) {
                    case "set", "item" -> "NEW.evidence_hash := repeat('b',64);";
                    case "record" -> "NEW.evidence := 'changed';";
                    case "receipt" ->
                            "NEW.result_data :="
                                    + " jsonb_set(NEW.result_data,'{original,editRev}','\"1\"');";
                    case "result" ->
                            "IF NEW.action='EVIDENCE_CREATE' THEN NEW.detail := '{}'::jsonb; END"
                                    + " IF;";
                    case "readDelete" ->
                            "DELETE FROM test_audit WHERE action='CONTENT_READ' AND"
                                    + " scope_key='batch:"
                                    + batch.original().batchKey()
                                    + "';";
                    case "readMutate" ->
                            "UPDATE test_audit SET detail='{}' WHERE action='CONTENT_READ' AND"
                                    + " scope_key='batch:"
                                    + batch.original().batchKey()
                                    + "';";
                    case "attempt" ->
                            "UPDATE grade_attempt SET provider_ref='changed' WHERE job_id IN"
                                    + " (SELECT id FROM grade_job WHERE batch_id="
                                    + batchId(batch)
                                    + ");";
                    case "completion" ->
                            "UPDATE grade_attempt SET"
                                    + " completion_data=jsonb_set(completion_data,'{extra}','true')"
                                    + " WHERE job_id IN (SELECT id FROM grade_job WHERE batch_id="
                                    + batchId(batch)
                                    + ");";
                    case "event" ->
                            "UPDATE grade_event SET detail='{}' WHERE job_id IN (SELECT id FROM"
                                    + " grade_job WHERE batch_id="
                                    + batchId(batch)
                                    + ");";
                    default ->
                            "UPDATE admin_account SET can_review=false WHERE id="
                                    + f.actor().principal().accountId()
                                    + ";";
                };
        trigger(table, mutation);
        try {
            assertThatThrownBy(
                            () ->
                                    grade(
                                            f,
                                            List.of(batch.original().batchKey()),
                                            key,
                                            UUID.randomUUID()))
                    .hasMessage("auth".equals(fault) ? "FORBIDDEN" : "STORY_UNAVAILABLE");
            assertThat(gradeHistory(f)).isEqualTo(before);
            assertThat(resolutionEvidence(batch)).isEqualTo(source);
            assertThat(content(f)).isEqualTo(content);
            assertThat(count("test_action", "request_key=?", key)).isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT can_review FROM admin_account WHERE id=?",
                                    Boolean.class,
                                    f.actor().principal().accountId()))
                    .isTrue();
        } finally {
            removeTrigger(table);
        }
    }

    /** 함께 바꾼 receipt·record revision도 request_hash가 같다고 재생하지 않는다. */
    @Test
    void gradeReplayRejectsCoordinatedRevisionCorruptionWithUnchangedRequestHash() {
        Fixture f = fixture();
        var batch = gradePass(f);
        UUID key = UUID.randomUUID();
        var result = grade(f, List.of(batch.original().batchKey()), key, UUID.randomUUID());
        String hash =
                db.queryForObject(
                        "SELECT request_hash FROM test_action WHERE request_key=?",
                        String.class,
                        key);
        db.execute("ALTER TABLE review_record DISABLE TRIGGER tr_review_record_immutable");
        try {
            db.update(
                    "UPDATE review_record SET"
                        + " evidence_data=jsonb_set(evidence_data,'{request,expectedRev}','\"1\"')"
                        + " WHERE id=?",
                    Long.parseLong(result.original().recordId()));
        } finally {
            db.execute("ALTER TABLE review_record ENABLE TRIGGER tr_review_record_immutable");
        }
        withResolutionHistoryGuardDisabled(
                "test_action",
                "tr_test_action_immutable",
                () ->
                        db.update(
                                "UPDATE test_action SET"
                                    + " result_data=jsonb_set(result_data,'{original,editRev}','\"1\"')"
                                    + " WHERE request_key=?",
                                key));
        String before = gradeHistory(f);
        assertThatThrownBy(
                        () ->
                                grade(
                                        f,
                                        List.of(batch.original().batchKey()),
                                        key,
                                        UUID.randomUUID()))
                .hasMessage("STORY_UNAVAILABLE");
        assertThat(
                        db.queryForObject(
                                "SELECT request_hash FROM test_action WHERE request_key=?",
                                String.class,
                                key))
                .isEqualTo(hash);
        assertThat(gradeHistory(f)).isEqualTo(before);
    }

    /** 첫 OPEN 지적은 다른 설정의 모든 집합도 무효화하며 해소 뒤에도 첫 사유를 보존한다. */
    @ParameterizedTest
    @ValueSource(strings = {"GRADING", "INFRA"})
    void actualNewIssueInvalidatesEveryRuntimeEvidenceAndNeverRevives(String kind) {
        Fixture f = fixture();
        var pass = gradePass(f);
        UUID key = UUID.randomUUID();
        var original = grade(f, List.of(pass.original().batchKey()), key, UUID.randomUUID());
        String code = "GRADE_FIXED_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        var installed = resolutionInstallation(code, true);
        runtimes.registerRuntime(
                code, installed.configHash(), installed.registrationManifest().toString());
        var admission =
                new StoryBatchService(
                        stories,
                        db,
                        runtimes,
                        crypto,
                        Map.of(runtimeCode, installation, code, installed));
        var other = resolutionBatch(f, admission, code, "REVIEW");
        finishResolutionBatch(other, code, installed, "PASS");
        var otherEvidence =
                new StoryGradeEvidenceService(stories, db, runtimes, admission)
                        .createGradeEvidence(
                                f.actor().sid(),
                                f.actor().principal(),
                                f.code(),
                                1,
                                "0",
                                Long.toString(f.snapshot()),
                                code,
                                "GRADE",
                                List.of(other.original().batchKey()),
                                List.of(),
                                parse("[]"),
                                UUID.randomUUID(),
                                UUID.randomUUID());
        String records =
                db.queryForObject(
                        "SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM review_record r WHERE"
                                + " snapshot_id=?",
                        String.class,
                        f.snapshot());
        var failure = create(f, UUID.randomUUID());
        finishResolutionBatch(failure, runtimeCode, installation, kind);
        long issue =
                id(
                        "SELECT id FROM execution_issue WHERE batch_id=? AND kind=?",
                        batchId(failure),
                        kind);
        assertThat(
                        count(
                                "evidence_set",
                                "snapshot_id=? AND NOT available_yn AND invalidated_issue_id=?",
                                f.snapshot(),
                                issue))
                .isEqualTo(2);
        assertThat(
                        count(
                                "test_audit",
                                "action='EVIDENCE_INVALIDATE' AND scope_key=?",
                                "batch:" + failure.original().batchKey()))
                .isEqualTo(1);
        JsonNode detail =
                parse(
                        db.queryForObject(
                                "SELECT detail::text FROM test_audit WHERE"
                                        + " action='EVIDENCE_INVALIDATE' AND scope_key=?",
                                String.class,
                                "batch:" + failure.original().batchKey()));
        assertThat(fields(detail)).containsExactlyInAnyOrder("issueId", "snapshotId", "count");
        assertThat(detail.path("count").intValue()).isEqualTo(2);
        var replay = grade(f, List.of(pass.original().batchKey()), key, UUID.randomUUID());
        assertThat(replay.original()).isEqualTo(original.original());
        assertThat(replay.original().result()).isEqualTo("PASS");
        assertThat(replay.current().available()).isFalse();
        String invalidated = evidenceSets(f);
        new GradeBatchAggregationService(
                        db,
                        new DataSourceTransactionManager(db.getDataSource()),
                        crypto,
                        Map.of(runtimeCode, installation),
                        "BATCH_TEST_COORDINATOR")
                .aggregate(failure.original().batchKey());
        assertThat(evidenceSets(f)).isEqualTo(invalidated);
        UUID issueKey =
                db.queryForObject(
                        "SELECT issue_key FROM execution_issue WHERE id=?", UUID.class, issue);
        var correction = resolutionBatch(f, admission, code, "REVIEW");
        finishResolutionBatch(correction, code, installed, "PASS");
        new StoryIssueResolutionService(stories, db, runtimes, admission)
                .resolveIssue(
                        f.actor().sid(),
                        f.actor().principal(),
                        f.code(),
                        1,
                        issueKey,
                        "0",
                        UUID.randomUUID(),
                        "INFRA".equals(kind) ? "INFRA_RECOVERED" : "GRADING_FIX_VERIFIED",
                        "VERIFY_REF_123",
                        correction.original().batchKey(),
                        null,
                        UUID.randomUUID());
        assertThat(evidenceSets(f)).isEqualTo(invalidated);
        assertThat(
                        db.queryForObject(
                                "SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM review_record"
                                        + " r WHERE snapshot_id=?",
                                String.class,
                                f.snapshot()))
                .isEqualTo(records);
        var gradeService = new StoryGradeEvidenceService(stories, db, runtimes, admission);
        JsonNode firstLinks = gradeLinks(original, otherEvidence);
        String resolvedHistory = gradeHistory(f);
        assertThatThrownBy(
                        () ->
                                gradeService.createGradeEvidence(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        code,
                                        "GRADE",
                                        List.of(other.original().batchKey()),
                                        List.of(),
                                        firstLinks,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(gradeHistory(f)).isEqualTo(resolvedHistory);
        assertThatThrownBy(
                        () ->
                                gradeService.createGradeEvidence(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        code,
                                        "GRADE",
                                        List.of(correction.original().batchKey()),
                                        List.of(),
                                        parse("[]"),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(gradeHistory(f)).isEqualTo(resolvedHistory);
        var fresh =
                gradeService.createGradeEvidence(
                        f.actor().sid(),
                        f.actor().principal(),
                        f.code(),
                        1,
                        "0",
                        Long.toString(f.snapshot()),
                        code,
                        "GRADE",
                        List.of(other.original().batchKey(), correction.original().batchKey()),
                        List.of(),
                        firstLinks,
                        UUID.randomUUID(),
                        UUID.randomUUID());
        assertThat(fresh.current().available()).isTrue();
        assertThat(fresh.original().setKey()).isNotEqualTo(otherEvidence.original().setKey());
        var secondFailure = create(f, UUID.randomUUID());
        finishResolutionBatch(secondFailure, runtimeCode, installation, kind);
        assertThat(
                        count(
                                "evidence_set",
                                "snapshot_id=? AND invalidated_issue_id=?",
                                f.snapshot(),
                                issue))
                .isEqualTo(2);
        assertThat(count("evidence_set", "snapshot_id=? AND available_yn", f.snapshot())).isZero();
        var intermediate = resolutionBatch(f, admission, code, "REVIEW");
        finishResolutionBatch(intermediate, code, installed, "PASS");
        String firstReasons = evidenceSets(f);
        var thirdFailure = create(f, UUID.randomUUID());
        finishResolutionBatch(thirdFailure, runtimeCode, installation, kind);
        assertThat(evidenceSets(f)).isEqualTo(firstReasons);
        JsonNode zero =
                parse(
                        db.queryForObject(
                                "SELECT detail::text FROM test_audit WHERE"
                                        + " action='EVIDENCE_INVALIDATE' AND scope_key=?",
                                String.class,
                                "batch:" + thirdFailure.original().batchKey()));
        assertThat(zero.path("count").intValue()).isZero();
        assertThat(zero.path("snapshotId").asText()).isEqualTo(Long.toString(f.snapshot()));
        var latestCorrection = resolutionBatch(f, admission, code, "REVIEW");
        finishResolutionBatch(latestCorrection, code, installed, "PASS");
        for (var failed : List.of(secondFailure, thirdFailure)) {
            UUID nextIssue =
                    db.queryForObject(
                            "SELECT issue_key FROM execution_issue WHERE batch_id=? AND kind=?",
                            UUID.class,
                            batchId(failed),
                            kind);
            new StoryIssueResolutionService(stories, db, runtimes, admission)
                    .resolveIssue(
                            f.actor().sid(),
                            f.actor().principal(),
                            f.code(),
                            1,
                            nextIssue,
                            "0",
                            UUID.randomUUID(),
                            "INFRA".equals(kind) ? "INFRA_RECOVERED" : "GRADING_FIX_VERIFIED",
                            "VERIFY_REF_123",
                            latestCorrection.original().batchKey(),
                            null,
                            UUID.randomUUID());
        }
        String latestHistory = gradeHistory(f);
        assertThatThrownBy(
                        () ->
                                gradeService.createGradeEvidence(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        code,
                                        "GRADE",
                                        List.of(intermediate.original().batchKey()),
                                        List.of(),
                                        gradeLinks(fresh),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(gradeHistory(f)).isEqualTo(latestHistory);
        assertThatThrownBy(
                        () ->
                                gradeService.createGradeEvidence(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        code,
                                        "GRADE",
                                        List.of(latestCorrection.original().batchKey()),
                                        List.of(),
                                        parse("[]"),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(gradeHistory(f)).isEqualTo(latestHistory);
        assertThat(
                        gradeService
                                .createGradeEvidence(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "0",
                                        Long.toString(f.snapshot()),
                                        code,
                                        "GRADE",
                                        List.of(
                                                intermediate.original().batchKey(),
                                                latestCorrection.original().batchKey()),
                                        List.of(),
                                        gradeLinks(fresh),
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .current()
                                .available())
                .isTrue();
        assertThat(
                        count(
                                "evidence_set",
                                "set_key IN (?, ?, ?) AND NOT available_yn",
                                original.original().setKey(),
                                otherEvidence.original().setKey(),
                                fresh.original().setKey()))
                .isEqualTo(3);
        assertThat(grade(f, List.of(pass.original().batchKey()), key, UUID.randomUUID()).original())
                .isEqualTo(original.original());
        assertThat(
                        grade(f, List.of(pass.original().batchKey()), key, UUID.randomUUID())
                                .current()
                                .available())
                .isFalse();
    }

    /** 합성 실제 A→B→C 발급의 ACK는 B 철회 뒤에도 유지하며 독립 지적만 별도로 요구한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualWithdrawnPassAcknowledgmentsPersistAcrossSuccessorInvalidation(boolean independent) {
        Fixture f = fixture();
        var aBatch = gradePass(f);
        UUID aKey = UUID.randomUUID();
        var a = grade(f, List.of(aBatch.original().batchKey()), aKey, UUID.randomUUID());
        var bBatch = withdrawAndResolveGrade(f);
        String unacknowledged = gradeHistory(f);
        assertThatThrownBy(
                        () ->
                                grade(
                                        f,
                                        List.of(bBatch.original().batchKey()),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(gradeHistory(f)).isEqualTo(unacknowledged);
        UUID bKey = UUID.randomUUID();
        var b =
                grade(
                        f,
                        List.of(bBatch.original().batchKey()),
                        gradeLinks(a),
                        bKey,
                        UUID.randomUUID());
        EvidenceResult separate =
                independent
                        ? grade(
                                f,
                                List.of(bBatch.original().batchKey()),
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        : null;
        var cBatch = withdrawAndResolveGrade(f);
        String firstMarkers = evidenceSets(f);
        String before = gradeHistory(f);
        assertThatThrownBy(
                        () ->
                                grade(
                                        f,
                                        List.of(cBatch.original().batchKey()),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(gradeHistory(f)).isEqualTo(before);
        if (independent) {
            assertThatThrownBy(
                            () ->
                                    grade(
                                            f,
                                            List.of(cBatch.original().batchKey()),
                                            gradeLinks(b),
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .hasMessage("EVIDENCE_INCOMPLETE");
            assertThat(gradeHistory(f)).isEqualTo(before);
        }
        JsonNode cLinks = independent ? gradeLinks(b, separate) : gradeLinks(b);
        var c =
                grade(
                        f,
                        List.of(cBatch.original().batchKey()),
                        cLinks,
                        UUID.randomUUID(),
                        UUID.randomUUID());
        assertThat(c.current().available()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT jsonb_agg(to_jsonb(e) ORDER BY id)::text FROM evidence_set"
                                        + " e WHERE snapshot_id=? AND set_key<>?",
                                String.class,
                                f.snapshot(),
                                c.original().setKey()))
                .isEqualTo(firstMarkers);
        assertThat(
                        parse(
                                        db.queryForObject(
                                                "SELECT evidence_data::text FROM review_record"
                                                        + " WHERE id=?",
                                                String.class,
                                                Long.parseLong(c.original().recordId())))
                                .path("details")
                                .path("resolves"))
                .isEqualTo(cLinks);
        assertThat(
                        count(
                                "evidence_set",
                                "set_key IN (?, ?) AND NOT available_yn",
                                a.original().setKey(),
                                b.original().setKey()))
                .isEqualTo(2);
        assertThat(
                        count(
                                "review_record",
                                "id IN (?, ?) AND result='PASS'",
                                Long.parseLong(a.original().recordId()),
                                Long.parseLong(b.original().recordId())))
                .isEqualTo(2);
        assertThat(
                        grade(
                                        f,
                                        List.of(bBatch.original().batchKey()),
                                        gradeLinks(a),
                                        bKey,
                                        UUID.randomUUID())
                                .original())
                .isEqualTo(b.original());
        db.update(
                "UPDATE story_version SET"
                        + " status='DRAFT',current_snapshot_id=NULL,edit_rev=edit_rev+1 WHERE id=?",
                f.version());
        db.update("UPDATE grade_runtime SET state='SUSPENDED',epoch=epoch+1 WHERE id=?", runtimeId);
        try {
            String changed = gradeHistory(f);
            var replayA = grade(f, List.of(aBatch.original().batchKey()), aKey, UUID.randomUUID());
            var replayB =
                    grade(
                            f,
                            List.of(bBatch.original().batchKey()),
                            gradeLinks(a),
                            bKey,
                            UUID.randomUUID());
            assertThat(replayA.original()).isEqualTo(a.original());
            assertThat(replayB.original()).isEqualTo(b.original());
            assertThat(replayA.current().available()).isFalse();
            assertThat(replayB.current().available()).isFalse();
            assertThat(replayB.current().current()).isFalse();
            assertThat(gradeHistory(f)).isEqualTo(changed);
        } finally {
            db.update("UPDATE grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtimeId);
        }
    }

    /** 실제 발급·PT12 뒤 저장 provenance만 훼손한 경우 신규/재생은 원자적으로 닫힌다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "createAudit",
                "readAudit",
                "invalidateAudit",
                "openAudit",
                "count",
                "metadata",
                "chronology",
                "receipt",
                "item",
                "nonlower"
            })
    void actualWithdrawnPassResolutionRejectsCorruptProvenance(String fault) {
        Fixture f = fixture();
        var aBatch = gradePass(f);
        var a =
                grade(
                        f,
                        List.of(aBatch.original().batchKey()),
                        UUID.randomUUID(),
                        UUID.randomUUID());
        var bBatch = withdrawAndResolveGrade(f);
        UUID bKey = UUID.randomUUID();
        var b =
                grade(
                        f,
                        List.of(bBatch.original().batchKey()),
                        gradeLinks(a),
                        bKey,
                        UUID.randomUUID());
        long issue =
                id(
                        "SELECT invalidated_issue_id FROM evidence_set WHERE set_key=?",
                        a.original().setKey());
        switch (fault) {
            case "createAudit" ->
                    withResolutionHistoryGuardDisabled(
                            "test_audit",
                            "tr_test_audit_immutable",
                            () ->
                                    db.update(
                                            "DELETE FROM test_audit WHERE action='EVIDENCE_CREATE'"
                                                    + " AND detail->>'recordId'=?",
                                            a.original().recordId()));
            case "readAudit" ->
                    withResolutionHistoryGuardDisabled(
                            "test_audit",
                            "tr_test_audit_immutable",
                            () ->
                                    db.update(
                                            "DELETE FROM test_audit WHERE action='CONTENT_READ' AND"
                                                    + " scope_key=?",
                                            "batch:" + aBatch.original().batchKey()));
            case "invalidateAudit" ->
                    withResolutionHistoryGuardDisabled(
                            "test_audit",
                            "tr_test_audit_immutable",
                            () ->
                                    db.update(
                                            "DELETE FROM test_audit WHERE"
                                                    + " action='EVIDENCE_INVALIDATE' AND"
                                                    + " detail->>'issueId'=?",
                                            Long.toString(issue)));
            case "openAudit" ->
                    withResolutionHistoryGuardDisabled(
                            "test_audit",
                            "tr_test_audit_immutable",
                            () ->
                                    db.update(
                                            "DELETE FROM test_audit WHERE action='BATCH_ISSUE' AND"
                                                    + " detail->>'issueId'=?",
                                            Long.toString(issue)));
            case "count" ->
                    withResolutionHistoryGuardDisabled(
                            "test_audit",
                            "tr_test_audit_immutable",
                            () ->
                                    db.update(
                                            "UPDATE test_audit SET"
                                                + " detail=jsonb_set(detail,'{count}','0') WHERE"
                                                + " action='EVIDENCE_INVALIDATE' AND"
                                                + " detail->>'issueId'=?",
                                            Long.toString(issue)));
            case "receipt" ->
                    withResolutionHistoryGuardDisabled(
                            "test_action",
                            "tr_test_action_immutable",
                            () ->
                                    db.update(
                                            "DELETE FROM test_action WHERE"
                                                    + " result_data->'original'->>'recordId'=? AND"
                                                    + " action='EVIDENCE_CREATE'",
                                            a.original().recordId()));
            case "item" ->
                    withResolutionHistoryGuardDisabled(
                            "evidence_item",
                            "tr_evidence_item_immutable",
                            () ->
                                    db.update(
                                            "DELETE FROM evidence_item WHERE set_id=(SELECT id FROM"
                                                    + " evidence_set WHERE set_key=?)",
                                            a.original().setKey()));
            case "nonlower" ->
                    withResolutionHistoryGuardDisabled(
                            "review_record",
                            "tr_review_record_immutable",
                            () ->
                                    db.update(
                                            "UPDATE review_record SET"
                                                + " evidence_data=jsonb_set(evidence_data,'{details,resolves}',?::jsonb)"
                                                + " WHERE id=?",
                                            gradeLinks(b).toString(),
                                            Long.parseLong(b.original().recordId())));
            case "metadata" ->
                    withResolutionHistoryGuardDisabled(
                            "review_record",
                            "tr_review_record_immutable",
                            () ->
                                    db.update(
                                            "UPDATE review_record SET"
                                                + " evidence_data=jsonb_set(evidence_data,'{details,reviewerRef}',to_jsonb(?::text))"
                                                + " WHERE id=?",
                                            UUID.randomUUID().toString(),
                                            Long.parseLong(a.original().recordId())));
            case "chronology" -> {
                // 실제 A→B 발급 이후에만 타임스탬프를 훼손한다. ID순은 그대로지만 B 발급보다 늦은 철회다.
                withResolutionHistoryGuardDisabled(
                        "evidence_set",
                        "tr_evidence_set_history",
                        () ->
                                db.update(
                                        "UPDATE evidence_set SET invalidated_at=(SELECT"
                                                + " created_at+interval '1 microsecond' FROM"
                                                + " review_record WHERE id=?) WHERE set_key=?",
                                        Long.parseLong(b.original().recordId()),
                                        a.original().setKey()));
                withResolutionHistoryGuardDisabled(
                        "test_audit",
                        "tr_test_audit_immutable",
                        () ->
                                db.update(
                                        "UPDATE test_audit SET created_at=(SELECT"
                                                + " created_at+interval '2 microseconds' FROM"
                                                + " review_record WHERE id=?) WHERE"
                                                + " action='EVIDENCE_INVALIDATE' AND"
                                                + " detail->>'issueId'=?",
                                        Long.parseLong(b.original().recordId()),
                                        Long.toString(issue)));
            }
            default -> throw new AssertionError(fault);
        }
        String corrupt = gradeHistory(f);
        String source = resolutionEvidence(bBatch);
        assertThatThrownBy(
                        () ->
                                grade(
                                        f,
                                        List.of(bBatch.original().batchKey()),
                                        gradeLinks(a),
                                        bKey,
                                        UUID.randomUUID()))
                .hasMessage("STORY_UNAVAILABLE");
        assertThat(gradeHistory(f)).isEqualTo(corrupt);
        assertThatThrownBy(
                        () ->
                                grade(
                                        f,
                                        List.of(bBatch.original().batchKey()),
                                        gradeLinks(a),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("STORY_UNAVAILABLE");
        assertThat(gradeHistory(f)).isEqualTo(corrupt);
        assertThat(resolutionEvidence(bBatch)).isEqualTo(source);
    }

    /** 이용 가능한 PASS·다른 사본의 철회 PASS·실제 수동 MODEL 기록은 GRADE 결함이 아니다. */
    @ParameterizedTest
    @ValueSource(strings = {"available", "foreignSnapshot", "wrongKind"})
    void actualGradeResolutionRejectsNonDefectTargets(String target) {
        Fixture f = fixture();
        var batch = gradePass(f);
        String recordId;
        if ("wrongKind".equals(target)) {
            new com.reasoning.common.story.service.StoryReviewService(stories)
                    .createReviewRecord(
                            f.actor().sid(),
                            f.actor().principal(),
                            f.code(),
                            1,
                            Long.toString(f.snapshot()),
                            "0",
                            UUID.randomUUID(),
                            "MODEL",
                            "INCOMPLETE",
                            null,
                            null,
                            "합성 수동 검수; 실제 모델 품질 증거 아님",
                            parse(
                                    "{\"formatNo\":1,\"evidenceRef\":null,\"checkedAt\":null,\"criticalOpenCount\":null,\"notes\":\"합성"
                                        + " 미완료\",\"runRef\":null,\"separateContext\":null}"),
                            UUID.randomUUID());
            recordId =
                    Long.toString(
                            id(
                                    "SELECT id FROM review_record WHERE snapshot_id=? AND"
                                            + " kind='MODEL'",
                                    f.snapshot()));
        } else {
            Fixture owner = "foreignSnapshot".equals(target) ? fixture() : f;
            var ownerBatch = owner == f ? batch : gradePass(owner);
            var prior =
                    grade(
                            owner,
                            List.of(ownerBatch.original().batchKey()),
                            UUID.randomUUID(),
                            UUID.randomUUID());
            if (owner != f) withdrawAndResolveGrade(owner);
            recordId = prior.original().recordId();
        }
        JsonNode links =
                parse(
                        "[{\"recordId\":\""
                                + recordId
                                + "\",\"reasonCode\":\"RECORD_CORRECTION\",\"verificationRef\":\"SYNTHETIC_ACK_123\"}]");
        String history = gradeHistory(f);
        String source = resolutionEvidence(batch);
        assertThatThrownBy(
                        () ->
                                grade(
                                        f,
                                        List.of(batch.original().batchKey()),
                                        links,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage("EVIDENCE_INCOMPLETE");
        assertThat(gradeHistory(f)).isEqualTo(history);
        assertThat(resolutionEvidence(batch)).isEqualTo(source);
    }

    /** 현재 권한은 재생에도 필수이며 전역 키를 다른 행위자·업무 의도로 재사용할 수 없다. */
    @ParameterizedTest
    @ValueSource(strings = {"global", "scoped", "actor", "input", "action", "member"})
    void gradeReplayReauthorizesAndRejectsGlobalIntentConflicts(String defect) {
        Fixture f = fixture();
        var batch = gradePass(f);
        UUID key = UUID.randomUUID();
        grade(f, List.of(batch.original().batchKey()), key, UUID.randomUUID());
        Actor actor = f.actor();
        if ("global".equals(defect))
            db.update(
                    "UPDATE admin_account SET can_review=false WHERE id=?",
                    actor.principal().accountId());
        if ("scoped".equals(defect))
            db.update(
                    "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                    f.story(),
                    actor.principal().accountId());
        if ("actor".equals(defect)) {
            actor = actor();
            grant(f, actor);
        }
        if ("action".equals(defect))
            withResolutionHistoryGuardDisabled(
                    "test_action",
                    "tr_test_action_immutable",
                    () ->
                            db.update(
                                    "UPDATE test_action SET action='BATCH_CREATE' WHERE"
                                            + " request_key=?",
                                    key));
        if ("member".equals(defect)) {
            long member = receiptMember();
            withResolutionHistoryGuardDisabled(
                    "test_action",
                    "tr_test_action_immutable",
                    () ->
                            db.update(
                                    "UPDATE test_action SET admin_id=NULL,member_id=? WHERE"
                                            + " request_key=?",
                                    member,
                                    key));
        }
        Actor caller = actor;
        String before = gradeHistory(f);
        assertThatThrownBy(
                        () ->
                                evidence.createGradeEvidence(
                                        caller.sid(),
                                        caller.principal(),
                                        f.code(),
                                        1,
                                        "input".equals(defect) ? "1" : "0",
                                        Long.toString(f.snapshot()),
                                        runtimeCode,
                                        "GRADE",
                                        List.of(batch.original().batchKey()),
                                        List.of(),
                                        parse("[]"),
                                        key,
                                        UUID.randomUUID()))
                .hasMessage(
                        Set.of("global", "scoped").contains(defect)
                                ? "FORBIDDEN"
                                : "REQUEST_KEY_CONFLICT");
        assertThat(gradeHistory(f)).isEqualTo(before);
    }

    /** 실제 세션·CSRF 경계에서 엄격 여덟 필드·32KiB·201/no-store와 닫힌 응답을 확인한다. */
    @Test
    void httpGradeEvidenceUsesExactEightFieldsAndClosedCreatedReplay() throws Exception {
        Fixture f = fixture();
        var batch = gradePass(f);
        Cookie session = sessionCookie(f.actor());
        var issued =
                mvc.perform(httpsGet("/admin/api/auth/csrf").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrf = issued.getResponse().getCookie("__Host-admin-csrf");
        String token = parse(issued.getResponse().getContentAsString()).path("token").asText();
        String route = "/admin/api/stories/" + f.code() + "/versions/1/evidence";
        UUID key = UUID.randomUUID();
        String body = gradeBody(f, batch, key);
        mvc.perform(
                        post(route)
                                .secure(true)
                                .header("Host", "localhost")
                                .cookie(session)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        authorized(route, session, csrf, token)
                                .with(
                                        request -> {
                                            request.setCookies(csrf);
                                            return request;
                                        })
                                .content(body))
                .andExpect(status().isUnauthorized());
        for (String invalid :
                List.of(
                        body.replace("\"reviewIds\":[]", "\"reviewIds\":[],\"extra\":1"),
                        body.replace(",\"reviewIds\":[]", ""),
                        body.replace("\"expectedRev\":\"0\"", "\"expectedRev\":0"),
                        body.replace("\"resolves\":[]", "\"resolves\":[],\"resolves\":[]"))) {
            mvc.perform(authorized(route, session, csrf, token).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(authorized(route, session, csrf, token).content(" ".repeat(32769)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(
                        authorized(route, session, csrf, token)
                                .content(body.replace("\"GRADE\"", "\"PLAYTEST\"")))
                .andExpect(status().isUnprocessableEntity());
        var created =
                mvc.perform(authorized(route, session, csrf, token).content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode result = parse(created.getResponse().getContentAsString());
        assertThat(fields(result))
                .containsExactlyInAnyOrder(
                        "action", "replayed", "changed", "original", "current", "requestId");
        assertThat(fields(result.path("original")))
                .containsExactlyInAnyOrder(
                        "setKey",
                        "recordId",
                        "snapshotId",
                        "runtimeConfigId",
                        "editRev",
                        "kind",
                        "result",
                        "createdAt");
        assertThat(fields(result.path("current")))
                .containsExactlyInAnyOrder("snapshotId", "current", "editRev", "available");
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
        String history = gradeHistory(f);
        var replay =
                mvc.perform(authorized(route, session, csrf, token).content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        assertThat(parse(replay.getResponse().getContentAsString()).path("original"))
                .isEqualTo(result.path("original"));
        assertThat(replay.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(gradeHistory(f)).isEqualTo(history);
    }

    /** 늦은 성공 트리거가 무효화·감사·과거 기록을 고치면 실제 새 지적과 집계도 롤백한다. */
    @ParameterizedTest
    @ValueSource(strings = {"set", "audit", "record", "item", "oldAttempt", "invalidationTime"})
    void issueInvalidationRollsBackSuccessfulLateTampering(String fault) {
        Fixture f = fixture();
        var pass = gradePass(f);
        var receipt =
                grade(f, List.of(pass.original().batchKey()), UUID.randomUUID(), UUID.randomUUID());
        var failed = create(f, UUID.randomUUID());
        finishResolutionBatch(
                failed,
                runtimeCode,
                installation,
                "GRADING",
                installation.registrationManifest().path("modelVersion").textValue(),
                false);
        String history = gradeHistory(f);
        String failedSource = resolutionEvidence(failed);
        String passSource = resolutionEvidence(pass);
        long set = id("SELECT id FROM evidence_set WHERE set_key=?", receipt.original().setKey());
        String mutation =
                switch (fault) {
                    case "set" -> "UPDATE evidence_set SET summary_data='{}' WHERE id=" + set + ";";
                    case "audit" ->
                            "UPDATE test_audit SET detail='{}' WHERE action='EVIDENCE_INVALIDATE'"
                                    + " AND scope_key='batch:"
                                    + failed.original().batchKey()
                                    + "';";
                    case "record" ->
                            "DELETE FROM review_record WHERE id="
                                    + receipt.original().recordId()
                                    + ";";
                    case "item" -> "DELETE FROM evidence_item WHERE set_id=" + set + ";";
                    default ->
                            "UPDATE grade_attempt SET provider_ref='changed' WHERE job_id IN"
                                    + " (SELECT id FROM grade_job WHERE batch_id="
                                    + batchId(pass)
                                    + ");";
                };
        String guardTable =
                switch (fault) {
                    case "set" -> "evidence_set";
                    case "record" -> "review_record";
                    case "item" -> "evidence_item";
                    default -> null;
                };
        String guard =
                switch (fault) {
                    case "set" -> "tr_evidence_set_history";
                    case "record" -> "tr_review_record_immutable";
                    case "item" -> "tr_evidence_item_immutable";
                    default -> null;
                };
        // 명시적 privileged DDL adversary만 보호를 잠시 우회하며 finally에서 반드시 복원한다.
        if (guardTable != null)
            db.execute("ALTER TABLE " + guardTable + " DISABLE TRIGGER " + guard);
        if ("invalidationTime".equals(fault)) {
            db.execute(
                    "CREATE FUNCTION batch_test_mutation() RETURNS trigger LANGUAGE plpgsql AS $$"
                            + " BEGIN NEW.invalidated_at := NEW.invalidated_at+interval '1 second';"
                            + " RETURN NEW; END $$");
            db.execute(
                    "CREATE TRIGGER batch_test_mutation BEFORE UPDATE ON evidence_set FOR EACH ROW"
                            + " EXECUTE FUNCTION batch_test_mutation()");
        } else
            trigger("test_audit", "IF NEW.action='BATCH_AGGREGATE' THEN " + mutation + " END IF;");
        try {
            assertThatThrownBy(
                            () ->
                                    new GradeBatchAggregationService(
                                                    db,
                                                    new DataSourceTransactionManager(
                                                            db.getDataSource()),
                                                    crypto,
                                                    Map.of(runtimeCode, installation),
                                                    "BATCH_TEST_COORDINATOR")
                                            .aggregate(failed.original().batchKey()))
                    .hasMessage("AGGREGATION_STORAGE_FAILURE");
            assertThat(gradeHistory(f)).isEqualTo(history);
            assertThat(resolutionEvidence(failed)).isEqualTo(failedSource);
            assertThat(resolutionEvidence(pass)).isEqualTo(passSource);
            assertThat(count("execution_issue", "batch_id=?", batchId(failed))).isZero();
        } finally {
            removeTrigger("invalidationTime".equals(fault) ? "evidence_set" : "test_audit");
            if (guardTable != null)
                db.execute("ALTER TABLE " + guardTable + " ENABLE TRIGGER " + guard);
        }
    }

    /** 같은 사본이라도 READY/PUBLISHED 또는 다른 현재 회차의 지적은 REVIEW 근거를 건드리지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"READY", "PUBLISHED", "ARCHIVED"})
    void issueCreationOutsideCurrentReviewDoesNotInvalidateEvidence(String state) {
        Fixture f = fixture();
        var pass = gradePass(f);
        grade(f, List.of(pass.original().batchKey()), UUID.randomUUID(), UUID.randomUUID());
        var failed = create(f, UUID.randomUUID());
        finishResolutionBatch(
                failed,
                runtimeCode,
                installation,
                "GRADING",
                installation.registrationManifest().path("modelVersion").textValue(),
                false);
        String sets = evidenceSets(f);
        if ("ARCHIVED".equals(state)) replaceGradeCurrentSnapshot(f);
        else db.update("UPDATE story_version SET status=? WHERE id=?", state, f.version());
        new GradeBatchAggregationService(
                        db,
                        new DataSourceTransactionManager(db.getDataSource()),
                        crypto,
                        Map.of(runtimeCode, installation),
                        "BATCH_TEST_COORDINATOR")
                .aggregate(failed.original().batchKey());
        assertThat(count("execution_issue", "batch_id=?", batchId(failed))).isPositive();
        assertThat(evidenceSets(f)).isEqualTo(sets);
        assertThat(
                        count(
                                "test_audit",
                                "action='EVIDENCE_INVALIDATE' AND scope_key=?",
                                "batch:" + failed.original().batchKey()))
                .isZero();
    }

    /** extra 행 및 과거 전체 행의 성공 변조도 exclusion으로 숨기지 않는다. */
    @ParameterizedTest
    @ValueSource(
            strings = {"extraRecord", "extraItem", "oldRecord", "oldItem", "oldSet", "oldRead"})
    void gradeReadbackRejectsExtraRowsAndHistoricalChanges(String fault) {
        Fixture f = fixture();
        var a = gradePass(f);
        var old = grade(f, List.of(a.original().batchKey()), UUID.randomUUID(), UUID.randomUUID());
        var b = gradePass(f);
        String history = gradeHistory(f);
        String source = resolutionEvidence(a);
        long set = id("SELECT id FROM evidence_set WHERE set_key=?", old.original().setKey());
        String mutation =
                switch (fault) {
                    case "extraRecord" ->
                            "INSERT INTO"
                                + " review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence,self_review_yn)"
                                + " VALUES ("
                                    + f.snapshot()
                                    + ",'MODEL',gen_random_uuid(),'{}','INCOMPLETE',"
                                    + f.actor().principal().accountId()
                                    + ",'합성 적대 저장 행',false);";
                    case "extraItem" ->
                            "INSERT INTO"
                                + " evidence_item(set_id,snapshot_id,runtime_id,batch_id,evidence_hash)"
                                + " SELECT e.id,e.snapshot_id,e.runtime_id,"
                                    + batchId(a)
                                    + ",repeat('b',64) FROM evidence_set e WHERE e.snapshot_id="
                                    + f.snapshot()
                                    + " AND e.id<>"
                                    + set
                                    + ";";
                    case "oldRecord" ->
                            "UPDATE review_record SET evidence='changed' WHERE id="
                                    + old.original().recordId()
                                    + ";";
                    case "oldItem" ->
                            "UPDATE evidence_item SET evidence_hash=repeat('b',64) WHERE set_id="
                                    + set
                                    + ";";
                    case "oldSet" ->
                            "UPDATE evidence_set SET summary_data='{}' WHERE id=" + set + ";";
                    default ->
                            "DELETE FROM test_audit WHERE action='CONTENT_READ' AND"
                                    + " scope_key='batch:"
                                    + a.original().batchKey()
                                    + "';";
                };
        String table =
                switch (fault) {
                    case "oldRecord" -> "review_record";
                    case "oldItem", "extraItem" -> "evidence_item";
                    case "oldSet" -> "evidence_set";
                    default -> null;
                };
        String guard =
                switch (fault) {
                    case "oldRecord" -> "tr_review_record_immutable";
                    case "oldItem" -> "tr_evidence_item_immutable";
                    case "extraItem" -> "tr_evidence_item_membership";
                    case "oldSet" -> "tr_evidence_set_history";
                    default -> null;
                };
        if (table != null) db.execute("ALTER TABLE " + table + " DISABLE TRIGGER " + guard);
        trigger("test_audit", "IF NEW.action='EVIDENCE_CREATE' THEN " + mutation + " END IF;");
        try {
            assertThatThrownBy(
                            () ->
                                    grade(
                                            f,
                                            List.of(b.original().batchKey()),
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .hasMessage("STORY_UNAVAILABLE");
            assertThat(gradeHistory(f)).isEqualTo(history);
            assertThat(resolutionEvidence(a)).isEqualTo(source);
        } finally {
            removeTrigger("test_audit");
            if (table != null) db.execute("ALTER TABLE " + table + " ENABLE TRIGGER " + guard);
        }
    }

    /** 실제 서비스 거래와 PostgreSQL advisory 직렬화로 전역 키 신규·재생 경쟁을 확인한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void gradeGlobalRequestCompetitionUsesActualTransactions(boolean sameKey) throws Exception {
        Fixture f = fixture();
        var batch = gradePass(f);
        UUID key = UUID.randomUUID();
        UUID second = sameKey ? key : UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        trigger(
                "test_action",
                "IF NEW.action='EVIDENCE_CREATE' THEN PERFORM pg_advisory_xact_lock(813618); END"
                        + " IF;");
        try {
            var a =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return grade(
                                        f,
                                        List.of(batch.original().batchKey()),
                                        key,
                                        UUID.randomUUID());
                            });
            var b =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return grade(
                                        f,
                                        List.of(batch.original().batchKey()),
                                        second,
                                        UUID.randomUUID());
                            });
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(EvidenceResult::replayed).count())
                    .isEqualTo(sameKey ? 1 : 0);
            assertThat(count("evidence_set", "snapshot_id=?", f.snapshot()))
                    .isEqualTo(sameKey ? 1 : 2);
            assertThat(count("review_record", "snapshot_id=? AND kind='GRADE'", f.snapshot()))
                    .isEqualTo(sameKey ? 1 : 2);
            if (sameKey)
                assertThat(results.getFirst().original()).isEqualTo(results.getLast().original());
        } finally {
            executor.shutdownNow();
            removeTrigger("test_action");
        }
    }

    /** 외부 사본 부모·새 수정번호·닫힌 해소 링크 및 수동 GRADE 우회는 fail closed다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "wrongParent",
                "revision",
                "snapshot",
                "resolveId",
                "resolveKind",
                "resolveMissing",
                "resolvePass",
                "playtest",
                "reviewIds",
                "duplicate"
            })
    void gradeRejectsForeignParentsAndInvalidClosedInputs(String defect) {
        Fixture f = fixture();
        var batch = gradePass(f);
        List<UUID> refs = List.of(batch.original().batchKey());
        if ("wrongParent".equals(defect))
            refs = List.of(gradePass(fixture()).original().batchKey());
        JsonNode resolves =
                switch (defect) {
                    case "resolveId" ->
                            parse(
                                    "[{\"recordId\":\"01\",\"reasonCode\":\"ISSUE_VERIFIED\",\"verificationRef\":\"VERIFY_REF_123\"}]");
                    case "resolveKind" ->
                            parse(
                                    "[{\"recordId\":\"1\",\"kind\":\"MODEL\",\"reasonCode\":\"GRADING_FIX_VERIFIED\",\"verificationRef\":\"VERIFY_REF_123\"}]");
                    case "resolveMissing" ->
                            parse(
                                    "[{\"recordId\":\"9223372036854775807\",\"reasonCode\":\"ISSUE_VERIFIED\",\"verificationRef\":\"VERIFY_REF_123\"}]");
                    default -> parse("[]");
                };
        if ("resolvePass".equals(defect)) {
            var prior = grade(f, refs, UUID.randomUUID(), UUID.randomUUID());
            resolves =
                    parse(
                            "[{\"recordId\":\""
                                    + prior.original().recordId()
                                    + "\",\"reasonCode\":\"RECORD_CORRECTION\",\"verificationRef\":\"VERIFY_REF_123\"}]");
        }
        JsonNode links = resolves;
        List<UUID> selected =
                "duplicate".equals(defect) ? List.of(refs.getFirst(), refs.getFirst()) : refs;
        String before = gradeHistory(f);
        assertThatThrownBy(
                        () ->
                                evidence.createGradeEvidence(
                                        f.actor().sid(),
                                        f.actor().principal(),
                                        f.code(),
                                        1,
                                        "revision".equals(defect) ? "1" : "0",
                                        "snapshot".equals(defect)
                                                ? "9223372036854775807"
                                                : Long.toString(f.snapshot()),
                                        runtimeCode,
                                        "playtest".equals(defect) ? "PLAYTEST" : "GRADE",
                                        selected,
                                        "reviewIds".equals(defect) ? List.of("1") : List.of(),
                                        links,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessage(
                        switch (defect) {
                            case "wrongParent" -> "NOT_FOUND";
                            case "revision" -> "EDIT_CONFLICT";
                            case "snapshot" -> "STATE_CONFLICT";
                            case "resolveMissing", "resolvePass" -> "EVIDENCE_INCOMPLETE";
                            case "playtest" -> "EVIDENCE_NOT_CONNECTED";
                            default -> "INVALID_REQUEST";
                        });
        assertThat(gradeHistory(f)).isEqualTo(before);
    }

    /** 다른 현재 인가 scope의 실제 UNIQUE 충돌은 새 거래의 재인가 뒤 충돌로 매핑한다. */
    @Test
    void gradeGlobalUniqueRaceAcrossAuthorizedScopesRollsBackLoser() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        var a = gradePass(f);
        String otherCode =
                "GRADE_RACE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        var installed = resolutionInstallation(otherCode, false);
        runtimes.registerRuntime(
                otherCode, installed.configHash(), installed.registrationManifest().toString());
        var admission =
                new StoryBatchService(stories, db, runtimes, crypto, Map.of(otherCode, installed));
        var b = resolutionBatch(other, admission, otherCode, "REVIEW");
        finishResolutionBatch(b, otherCode, installed, "PASS");
        var otherService = new StoryGradeEvidenceService(stories, db, runtimes, admission);
        UUID key = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(3);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        trigger(
                "test_action",
                "IF NEW.action='EVIDENCE_CREATE' THEN PERFORM pg_advisory_xact_lock(813619); END"
                        + " IF;");
        try {
            var blocker =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(
                                                    new DataSourceTransactionManager(
                                                            db.getDataSource()))
                                            .execute(
                                                    status -> {
                                                        db.queryForObject(
                                                                "SELECT"
                                                                    + " pg_advisory_xact_lock(813619)",
                                                                Object.class);
                                                        held.countDown();
                                                        await(release);
                                                        return null;
                                                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var first = executor.submit(() -> gradeRace(f, a, key, barrier));
            var second =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                try {
                                    return otherService
                                                    .createGradeEvidence(
                                                            other.actor().sid(),
                                                            other.actor().principal(),
                                                            other.code(),
                                                            1,
                                                            "0",
                                                            Long.toString(other.snapshot()),
                                                            otherCode,
                                                            "GRADE",
                                                            List.of(b.original().batchKey()),
                                                            List.of(),
                                                            parse("[]"),
                                                            key,
                                                            UUID.randomUUID())
                                                    .replayed()
                                            ? "REPLAY"
                                            : "NEW";
                                } catch (com.reasoning.common.auth.service.AuthException failure) {
                                    return failure.getMessage();
                                }
                            });
            waitForGradeReceiptCompetition();
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("NEW", "REQUEST_KEY_CONFLICT");
            assertThat(count("test_action", "request_key=?", key)).isEqualTo(1);
            assertThat(
                            count(
                                    "evidence_set",
                                    "snapshot_id IN (?,?)",
                                    f.snapshot(),
                                    other.snapshot()))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    "review_record",
                                    "snapshot_id IN (?,?) AND kind='GRADE'",
                                    f.snapshot(),
                                    other.snapshot()))
                    .isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            removeTrigger("test_action");
        }
    }

    /** 두 거래 모두 영수증 INSERT에 도달한 실제 PG 대기를 관측한 뒤 UNIQUE 경쟁을 연다. */
    private void waitForGradeReceiptCompetition() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            if (db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                                + " AND wait_event_type='Lock' AND lower(wait_event)='advisory' AND"
                                + " query LIKE '%INSERT INTO test_action%'",
                            Integer.class)
                    == 2) return;
            Thread.sleep(10);
        }
        throw new AssertionError("두 실제 GRADE 영수증 INSERT의 advisory 대기가 관측되지 않았습니다.");
    }

    /** 동일한 현재 인가 호출의 실제 경쟁 결과만 안전 코드로 반환한다. */
    private String gradeRace(Fixture f, ActionResult batch, UUID key, CyclicBarrier barrier)
            throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            return grade(f, List.of(batch.original().batchKey()), key, UUID.randomUUID()).replayed()
                    ? "REPLAY"
                    : "NEW";
        } catch (com.reasoning.common.auth.service.AuthException failure) {
            return failure.getMessage();
        }
    }

    /** 수동 SR05는 실제 근거 API가 생겨도 GRADE PASS를 만들어낼 수 없다. */
    @Test
    void manualReviewRecordStillRejectsGradeBackdoor() {
        Fixture f = fixture();
        assertThatThrownBy(
                        () ->
                                new com.reasoning.common.story.service.StoryReviewService(stories)
                                        .createReviewRecord(
                                                f.actor().sid(),
                                                f.actor().principal(),
                                                f.code(),
                                                1,
                                                Long.toString(f.snapshot()),
                                                "0",
                                                UUID.randomUUID(),
                                                "GRADE",
                                                "PASS",
                                                null,
                                                null,
                                                "합성 수동 우회는 실제 실행 근거가 아님",
                                                parse("{}"),
                                                UUID.randomUUID()))
                .hasMessage("EVIDENCE_NOT_CONNECTED");
        assertThat(count("review_record", "snapshot_id=?", f.snapshot())).isZero();
    }

    /** 새 근거에는 현재 설치·정책·상태가 필요하며 alias나 위조 적용 플래그를 채택하지 않는다. */
    @ParameterizedTest
    @ValueSource(
            strings = {"offline", "install", "policy", "alias", "providerFlag", "adoptionFlag"})
    void gradeRejectsCurrentRuntimePolicyAndProviderQualificationDefects(String defect) {
        Fixture f = fixture();
        var batch = gradePass(f);
        try {
            StoryGradeEvidenceService service = evidence;
            String code = runtimeCode;
            if ("offline".equals(defect))
                db.update("UPDATE grade_runtime SET state='SUSPENDED' WHERE id=?", runtimeId);
            if ("install".equals(defect))
                service =
                        new StoryGradeEvidenceService(
                                stories,
                                db,
                                runtimes,
                                new StoryBatchService(stories, db, runtimes, crypto, Map.of()));
            if ("policy".equals(defect))
                db.update("UPDATE story_version SET policy_code='OTHER' WHERE id=?", f.version());
            if ("alias".equals(defect)) {
                code = "GRADE_ALIAS_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
                var installed = resolutionInstallation(code, false);
                runtimes.registerRuntime(
                        code, installed.configHash(), installed.registrationManifest().toString());
                service =
                        new StoryGradeEvidenceService(
                                stories,
                                db,
                                runtimes,
                                new StoryBatchService(
                                        stories, db, runtimes, crypto, Map.of(code, installed)));
            }
            if (Set.of("providerFlag", "adoptionFlag").contains(defect)) {
                String flag =
                        "providerFlag".equals(defect)
                                ? "providerVersionMatched"
                                : "fixtureAdoptionEligible";
                db.update(
                        "UPDATE grade_job SET"
                            + " result_data=jsonb_set(result_data,ARRAY[?]::text[],'false') WHERE"
                            + " batch_id=? AND sample_code='FULL'",
                        flag,
                        batchId(batch));
            }
            StoryGradeEvidenceService selected = service;
            String selectedCode = code;
            String before = gradeHistory(f);
            assertThatThrownBy(
                            () ->
                                    selected.createGradeEvidence(
                                            f.actor().sid(),
                                            f.actor().principal(),
                                            f.code(),
                                            1,
                                            "0",
                                            Long.toString(f.snapshot()),
                                            selectedCode,
                                            "GRADE",
                                            List.of(batch.original().batchKey()),
                                            List.of(),
                                            parse("[]"),
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .hasMessage(
                            switch (defect) {
                                case "offline", "install" -> "RUNTIME_UNAVAILABLE";
                                case "policy", "alias" -> "EVIDENCE_INCOMPLETE";
                                default -> "STORY_UNAVAILABLE";
                            });
            assertThat(gradeHistory(f)).isEqualTo(before);
        } finally {
            db.update("UPDATE grade_runtime SET state='AVAILABLE' WHERE id=?", runtimeId);
        }
    }

    /** 기존 읽기·수동 NULL FK·반려 경계도 합법적인 과거 근거 첫 무효화를 커밋하지 못한다. */
    @ParameterizedTest
    @ValueSource(strings = {"read", "manual", "transition"})
    void ordinaryReviewEntriesRollBackLegalHistoricalEvidenceInvalidation(String entry) {
        Fixture f = fixture();
        var pass = gradePass(f);
        var grade =
                grade(f, List.of(pass.original().batchKey()), UUID.randomUUID(), UUID.randomUUID());
        var failed = create(f, UUID.randomUUID());
        finishResolutionBatch(
                failed,
                runtimeCode,
                installation,
                "GRADING",
                installation.registrationManifest().path("modelVersion").textValue(),
                false);
        // 실제 DRAFT 중 집계가 만든 OPEN 지적이다. 품질 승인이나 SQL 단말 실행 행을 제조하지 않는다.
        db.update(
                "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL WHERE id=?",
                f.version());
        new GradeBatchAggregationService(
                        db,
                        new DataSourceTransactionManager(db.getDataSource()),
                        crypto,
                        Map.of(runtimeCode, installation),
                        "BATCH_TEST_COORDINATOR")
                .aggregate(failed.original().batchKey());
        long issue =
                id(
                        "SELECT id FROM execution_issue WHERE batch_id=? AND kind='INFRA' AND"
                                + " state='OPEN'",
                        batchId(failed));
        assertThat(count("evidence_set", "set_key=? AND available_yn", grade.original().setKey()))
                .isEqualTo(1);
        db.update(
                "UPDATE story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?",
                f.snapshot(),
                f.version());
        String content = content(f);
        String history = gradeHistory(f);
        String failure = resolutionEvidence(failed);
        String action =
                switch (entry) {
                    case "read" -> "CONTENT_READ";
                    case "manual" -> "REVIEW_RECORDED";
                    default -> "REVIEW_RETURNED";
                };
        trigger(
                "story_audit",
                "IF NEW.version_id="
                        + f.version()
                        + " AND NEW.action='"
                        + action
                        + "' THEN UPDATE evidence_set SET"
                        + " available_yn=false,invalidated_at=clock_timestamp(),invalidated_issue_id="
                        + issue
                        + " WHERE set_key='"
                        + grade.original().setKey()
                        + "'; END IF;");
        var reviews = new com.reasoning.common.story.service.StoryReviewService(stories);
        try {
            assertThatThrownBy(
                            () -> {
                                switch (entry) {
                                    case "read" ->
                                            reviews.getReviewRecordList(
                                                    f.actor().sid(),
                                                    f.actor().principal(),
                                                    f.code(),
                                                    1,
                                                    Long.toString(f.snapshot()),
                                                    20,
                                                    null,
                                                    UUID.randomUUID());
                                    case "manual" ->
                                            reviews.createReviewRecord(
                                                    f.actor().sid(),
                                                    f.actor().principal(),
                                                    f.code(),
                                                    1,
                                                    Long.toString(f.snapshot()),
                                                    "0",
                                                    UUID.randomUUID(),
                                                    "MODEL",
                                                    "INCOMPLETE",
                                                    null,
                                                    null,
                                                    "합성 검수 미완료; 실제 모델 품질 증거 아님",
                                                    parse(
                                                            "{\"formatNo\":1,\"evidenceRef\":null,\"checkedAt\":null,\"criticalOpenCount\":null,\"notes\":\"합성"
                                                                + " 미완료\",\"runRef\":null,\"separateContext\":null}"),
                                                    UUID.randomUUID());
                                    default ->
                                            reviews.returnToDraft(
                                                    f.actor().sid(),
                                                    f.actor().principal(),
                                                    f.code(),
                                                    1,
                                                    "0",
                                                    Long.toString(f.snapshot()),
                                                    "CHANGES_REQUIRED",
                                                    "GRADING_ISSUE",
                                                    "RETURN_REF_123",
                                                    UUID.randomUUID());
                                }
                            })
                    .hasMessage("STORY_UNAVAILABLE");
            assertThat(gradeHistory(f)).isEqualTo(history);
            assertThat(content(f)).isEqualTo(content);
            assertThat(resolutionEvidence(failed)).isEqualTo(failure);
            assertThat(
                            count(
                                    "evidence_set",
                                    "set_key=? AND available_yn AND invalidated_at IS NULL AND"
                                            + " invalidated_issue_id IS NULL",
                                    grade.original().setKey()))
                    .isEqualTo(1);
        } finally {
            removeTrigger("story_audit");
        }
    }

    /** 기존 실제 coordinator→lease→예약→완료 GCM→집계 경로를 그대로 사용한다. */
    private ActionResult gradePass(Fixture f) {
        var result = create(f, UUID.randomUUID());
        finishResolutionBatch(result, runtimeCode, installation, "PASS");
        return result;
    }

    /** 같은 실제 부모의 새 사본으로만 현재 포인터를 바꾸며 REVIEW의 NULL/FK 제약을 훼손하지 않는다. */
    private void replaceGradeCurrentSnapshot(Fixture f) {
        long replacement =
                id(
                        "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,?::jsonb,?,?) RETURNING id",
                        f.version(),
                        new String(f.frozen().payloadBytes(), StandardCharsets.UTF_8),
                        UUID.randomUUID(),
                        f.actor().principal().accountId());
        db.update(
                "UPDATE story_version SET current_snapshot_id=? WHERE id=?",
                replacement,
                f.version());
    }

    /** 실제 INFRA OPEN→전체 후속 PASS→PT12만 사용한다. 근거 연결은 issue를 고치지 않는다. */
    private ActionResult withdrawAndResolveGrade(Fixture f) {
        var failure = create(f, UUID.randomUUID());
        finishResolutionBatch(failure, runtimeCode, installation, "INFRA");
        UUID issue =
                db.queryForObject(
                        "SELECT issue_key FROM execution_issue WHERE batch_id=? AND kind='INFRA'",
                        UUID.class,
                        batchId(failure));
        var correction = gradePass(f);
        resolutions.resolveIssue(
                f.actor().sid(),
                f.actor().principal(),
                f.code(),
                1,
                issue,
                "0",
                UUID.randomUUID(),
                "INFRA_RECOVERED",
                "SYNTHETIC_FIX_123",
                correction.original().batchKey(),
                null,
                UUID.randomUUID());
        return correction;
    }

    /** 닫힌 GRADE 입력만 전송하고 서버의 실제 인가 행위자를 사용한다. */
    private EvidenceResult grade(Fixture f, List<UUID> refs, UUID key, UUID request) {
        return grade(f, refs, parse("[]"), key, request);
    }

    /** 정규 닫힌 연결을 실제 GRADE 서비스에 전달한다. */
    private EvidenceResult grade(
            Fixture f, List<UUID> refs, JsonNode links, UUID key, UUID request) {
        return evidence.createGradeEvidence(
                f.actor().sid(),
                f.actor().principal(),
                f.code(),
                1,
                "0",
                Long.toString(f.snapshot()),
                runtimeCode,
                "GRADE",
                refs,
                List.of(),
                links,
                key,
                request);
    }

    /** 실제 서버 영수증 ID만 사용하며 외부 모델 품질을 주장하지 않는다. */
    private JsonNode gradeLinks(EvidenceResult... records) {
        var links = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        java.util.Arrays.stream(records)
                .sorted(
                        java.util.Comparator.comparingLong(
                                record -> Long.parseLong(record.original().recordId())))
                .forEach(
                        record ->
                                links.add(
                                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                                .objectNode()
                                                .put("recordId", record.original().recordId())
                                                .put("reasonCode", "ISSUE_VERIFIED")
                                                .put("verificationRef", "SYNTHETIC_ACK_123")));
        return links;
    }

    /** 해당 버전의 전체 근거·구성원·기록·영수증·감사를 추가/삭제까지 대조한다. */
    private String gradeHistory(Fixture f) {
        return db.queryForObject(
                "SELECT jsonb_build_object('sets',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM"
                    + " evidence_set e WHERE snapshot_id IN (SELECT id FROM review_snapshot WHERE"
                    + " version_id=?)),'items',(SELECT jsonb_agg(to_jsonb(i) ORDER BY"
                    + " set_id,batch_id) FROM evidence_item i WHERE"
                    + " snapshot_id=?),'records',(SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM"
                    + " review_record r WHERE snapshot_id=?),'receipts',(SELECT"
                    + " jsonb_agg(to_jsonb(a) ORDER BY id) FROM test_action a WHERE"
                    + " scope_key=?),'storyAudits',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM"
                    + " story_audit a WHERE version_id="
                        + f.version()
                        + "),'audits',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM test_audit a"
                        + " WHERE scope_key=? OR scope_key IN (SELECT 'batch:'||batch_key::text"
                        + " FROM grade_batch WHERE snapshot_id=?)))::text",
                String.class,
                f.version(),
                f.snapshot(),
                f.snapshot(),
                "version:" + f.version(),
                "version:" + f.version(),
                f.snapshot());
    }

    /** 첫 무효화의 전체13열을 시간·사유까지 보존한다. */
    private String evidenceSets(Fixture f) {
        return db.queryForObject(
                "SELECT jsonb_agg(to_jsonb(e) ORDER BY id)::text FROM evidence_set e WHERE"
                        + " snapshot_id=?",
                String.class,
                f.snapshot());
    }

    /** HTTP 신규·재생 모두 같은 여덟 필드 의도를 사용한다. */
    private String gradeBody(Fixture f, ActionResult batch, UUID key) {
        return "{\"expectedRev\":\"0\",\"snapshotId\":\""
                + f.snapshot()
                + "\",\"runtimeConfigId\":\""
                + runtimeCode
                + "\",\"kind\":\"GRADE\",\"executionRefs\":[\""
                + batch.original().batchKey()
                + "\"],\"reviewIds\":[],\"resolves\":[],\"requestKey\":\""
                + key
                + "\"}";
    }

    /** 전체 fixture×3의 실제 완료·집계만 사용하며 단말 행을 삽입하지 않는다. */
    private ResolutionFixture resolutionFixture(String kind, boolean changedConfig) {
        return resolutionFixture(kind, changedConfig, "AVAILABILITY");
    }

    /** 실제 오류·교정 설정·후속 목적을 고정한 같은 사본의 해소 fixture다. */
    private ResolutionFixture resolutionFixture(
            String kind, boolean changedConfig, String purpose) {
        Fixture f = fixture();
        var source =
                resolutionBatch(
                        f,
                        batches,
                        runtimeCode,
                        "REVIEW".equals(purpose) ? "AVAILABILITY" : "REVIEW");
        finishResolutionBatch(source, runtimeCode, installation, kind);
        UUID issue =
                db.queryForObject(
                        "SELECT issue_key FROM execution_issue WHERE batch_id=? AND kind=?",
                        UUID.class,
                        batchId(source),
                        kind);
        assertThat(
                        count(
                                "test_audit",
                                "action='BATCH_ISSUE' AND scope_key=?",
                                "batch:" + source.original().batchKey()))
                .isPositive();
        String code = runtimeCode;
        InstalledRuntimeManifestVerifier verifier = installation;
        StoryBatchService admission = batches;
        long targetRuntime = runtimeId;
        if ("GRADING".equals(kind) && changedConfig) {
            code = "BATCH_FIXED_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            verifier = resolutionInstallation(code, true);
            targetRuntime =
                    runtimes.registerRuntime(
                                    code,
                                    verifier.configHash(),
                                    verifier.registrationManifest().toString())
                            .id();
            admission =
                    new StoryBatchService(
                            stories,
                            db,
                            runtimes,
                            crypto,
                            Map.of(runtimeCode, installation, code, verifier));
        }
        var target = resolutionBatch(f, admission, code, purpose);
        finishResolutionBatch(target, code, verifier, "PASS");
        return new ResolutionFixture(
                f,
                source,
                target,
                issue,
                kind,
                targetRuntime,
                admission == batches
                        ? resolutions
                        : new StoryIssueResolutionService(stories, db, runtimes, admission));
    }

    /** 파일 지문이 있는 실제 설치를 새 코드와 선택한 모델 버전 변경에만 결속한다. */
    private InstalledRuntimeManifestVerifier resolutionInstallation(String code, boolean changed) {
        var dictionary =
                new GradeDictionary("BATCH_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:" + provider.getAddress().getPort()),
                        "qwen3:8b",
                        (changed ? "b" : "a").repeat(64),
                        dictionary.sha256(),
                        "{{ .System }}{{ .Prompt }}{{ .Response }}",
                        65536,
                        4096,
                        0,
                        1,
                        false,
                        Duration.ofSeconds(30));
        return new InstalledRuntimeManifestVerifier(
                new LocalSemanticEngine(settings),
                dictionary,
                new InstalledProfile(code, "LOCAL", "1", "RULE_20260924"));
    }

    /** 현재 사본을 실제 접수 서비스와 명시적 설정·목적으로 생성한다. */
    private ActionResult resolutionBatch(
            Fixture f, StoryBatchService admission, String code, String purpose) {
        return admission.createBatch(
                f.actor().sid(),
                f.actor().principal(),
                f.code(),
                1,
                "0",
                Long.toString(f.snapshot()),
                code,
                purpose,
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    /** 정상 콜백은 실제 설치 모델 버전으로 관측하며 기대 오류는 적용하지 않는다. */
    private void finishResolutionBatch(
            ActionResult batch,
            String code,
            InstalledRuntimeManifestVerifier verifier,
            String outcome) {
        finishResolutionBatch(
                batch,
                code,
                verifier,
                outcome,
                verifier.registrationManifest().path("modelVersion").textValue());
    }

    /** 전체12작업의 실제 예약·완료·집계를 수행하고 관측 버전 결함만 명시적으로 주입한다. */
    private void finishResolutionBatch(
            ActionResult batch,
            String code,
            InstalledRuntimeManifestVerifier verifier,
            String outcome,
            String observedVersion) {
        finishResolutionBatch(batch, code, verifier, outcome, observedVersion, true);
    }

    /** 실제 완료와 집계 거래를 분리하여 집계 직전 원행을 독립적으로 캡처한다. */
    private void finishResolutionBatch(
            ActionResult batch,
            String code,
            InstalledRuntimeManifestVerifier verifier,
            String outcome,
            String observedVersion,
            boolean aggregateNow) {
        Worker worker = workerServices(code, verifier);
        for (var row :
                db.queryForList(
                        "SELECT job_key,sample_code,repeat_no FROM grade_job WHERE batch_id=? ORDER"
                                + " BY id",
                        batchId(batch))) {
            UUID job = (UUID) row.get("job_key");
            String sample = (String) row.get("sample_code");
            assertThat(worker.coordinator().activate(job).changed()).isTrue();
            if ("INPUT".equals(sample)) continue;
            boolean infra = "INFRA".equals(outcome) && "FULL".equals(sample);
            boolean engine = "ENGINE".equals(sample);
            for (int attempt = 1; attempt <= (engine || infra ? 3 : 1); attempt++) {
                var lease =
                        worker.leases().claim(worker.identity().workerKey(), code).orElseThrow();
                assertThat(lease.jobKey()).isEqualTo(job);
                var reservation = worker.start().start(worker.identity(), job, lease.leaseGen());
                String protocol =
                        engine || infra
                                ? "{\"kind\":\"ERROR\",\"errorCode\":\""
                                        + (infra ? "ENGINE_UNAVAILABLE" : "ENGINE_TIMEOUT")
                                        + "\"}"
                                : new String(
                                        SnapshotJson.encode(
                                                syntheticNormal(
                                                        "FULL".equals(sample)
                                                                && !"GRADING".equals(outcome),
                                                        "ZERO".equals(sample))),
                                        StandardCharsets.UTF_8);
                assertThat(
                                worker.completion()
                                        .complete(
                                                worker.identity(),
                                                job,
                                                lease.leaseGen(),
                                                reservation.attemptNo(),
                                                engine || infra ? null : observedVersion,
                                                null,
                                                protocol,
                                                UUID.randomUUID())
                                        .accepted())
                        .isTrue();
            }
        }
        if (!aggregateNow) return;
        var aggregate =
                new GradeBatchAggregationService(
                                db,
                                new DataSourceTransactionManager(db.getDataSource()),
                                crypto,
                                Map.of(code, verifier),
                                "BATCH_TEST_COORDINATOR")
                        .aggregate(batch.original().batchKey());
        assertThat(aggregate.state()).isEqualTo("INFRA".equals(outcome) ? "FAILED" : "COMPLETED");
        assertThat(aggregate.passed())
                .isEqualTo("INFRA".equals(outcome) ? null : "PASS".equals(outcome));
        assertThat(count("grade_job", "batch_id=?", batchId(batch))).isEqualTo(12);
        assertThat(
                        count(
                                "grade_job",
                                "batch_id=? AND sample_code='INPUT' AND state='COMPLETED' AND"
                                        + " call_count=0",
                                batchId(batch)))
                .isEqualTo(3);
        assertThat(
                        count(
                                "grade_job",
                                "batch_id=? AND sample_code='ENGINE' AND state='FAILED' AND"
                                        + " call_count=3",
                                batchId(batch)))
                .isEqualTo(3);
        assertThat(providerCalls.get()).isZero();
    }

    /** 원래 종류·대상·비개인 참조를 같은 서버 해소 입력에 결속한다. */
    private StoryIssueResolutionService.ResolutionResult resolve(
            ResolutionFixture r, UUID key, String rev, UUID request) {
        return r.service()
                .resolveIssue(
                        r.fixture().actor().sid(),
                        r.fixture().actor().principal(),
                        r.fixture().code(),
                        1,
                        r.issue(),
                        rev,
                        key,
                        "INFRA".equals(r.kind()) ? "INFRA_RECOVERED" : "GRADING_FIX_VERIFIED",
                        "VERIFY_REF_123",
                        r.target().original().batchKey(),
                        null,
                        request);
    }

    /** bounded 동시 시작 뒤 최초·재생 또는 실제 업무 실패만 구분한다. */
    private String resolutionRace(ResolutionFixture r, UUID key, CyclicBarrier barrier)
            throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            return resolve(r, key, "0", UUID.randomUUID()).replayed() ? "REPLAY" : "NEW";
        } catch (com.reasoning.common.auth.service.AuthException failure) {
            return failure.getMessage();
        }
    }

    /** 시험 소유 지적 전체 행을 읽어 변경 전후의 실제 보존을 대조한다. */
    private String issueState(ResolutionFixture r) {
        return db.queryForObject(
                "SELECT to_jsonb(i)::text FROM execution_issue i WHERE issue_key=?",
                String.class,
                r.issue());
    }

    /** 부모·작업·시도·이벤트·자동 지적 감사의 원행을 순서까지 보존한다. */
    private String resolutionEvidence(ActionResult batch) {
        return db.queryForObject(
                "SELECT jsonb_build_object('batch',to_jsonb(b),'jobs',(SELECT jsonb_agg(to_jsonb(j)"
                    + " ORDER BY id) FROM grade_job j WHERE batch_id=b.id),'attempts',(SELECT"
                    + " jsonb_agg(to_jsonb(a) ORDER BY job_id,attempt_no) FROM grade_attempt a"
                    + " WHERE job_id IN (SELECT id FROM grade_job WHERE"
                    + " batch_id=b.id)),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM"
                    + " grade_event e WHERE job_id IN (SELECT id FROM grade_job WHERE"
                    + " batch_id=b.id)),'audits',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM"
                    + " test_audit t WHERE actor_kind='SYSTEM' AND"
                    + " scope_key='batch:'||b.batch_key::text),'receipts',(SELECT"
                    + " jsonb_agg(to_jsonb(a) ORDER BY id) FROM test_action a WHERE"
                    + " action='BATCH_CREATE' AND scope_key='version:'||(SELECT version_id FROM"
                    + " review_snapshot WHERE id=b.snapshot_id)::text))::text FROM grade_batch b"
                    + " WHERE id=?",
                String.class,
                batchId(batch));
    }

    /** HTTP의 정확한 여섯 필드와 명시적 targetReviewId null을 직렬화한다. */
    private String resolutionBody(ResolutionFixture r, UUID key) {
        return "{\"expectedRev\":\"0\",\"requestKey\":\""
                + key
                + "\",\"reasonCode\":\"INFRA_RECOVERED\","
                + "\"verificationRef\":\"VERIFY_REF_123\",\"targetBatchKey\":\""
                + r.target().original().batchKey()
                + "\",\"targetReviewId\":null}";
    }

    private record ResolutionFixture(
            Fixture fixture,
            ActionResult source,
            ActionResult target,
            UUID issue,
            String kind,
            long targetRuntime,
            StoryIssueResolutionService service) {}

    /** 실제 전체 사본을 소유 부모·사람 checker·정규 저장 payload에 결속한다. */
    private Fixture fixture() {
        return fixture(FrozenSnapshotContractTest.complete());
    }

    /** 전체 입력은 review_snapshot.payload에만 저장하며 제조된 판정 결과·가짜 작업 부모를 만들지 않는다. */
    private Fixture fixture(ObjectNode source) {
        Actor actor = actor();
        String code = "ST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        long story =
                id(
                        "INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id",
                        code,
                        actor.principal().accountId());
        long version =
                id(
                        "INSERT INTO"
                            + " story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 전체 사본','RULE_20260924',?,?) RETURNING id",
                        story,
                        actor.principal().accountId(),
                        actor.principal().accountId());
        source.put("storyCode", code);
        for (JsonNode row : source.path("resources").path("gradeSamples"))
            if (!row.path("checkedBy").isNull())
                ((ObjectNode) row).put("checkedBy", actor.principal().accountKey().toString());
        FrozenSnapshot frozen = FrozenSnapshotCodec.freeze(source);
        long snapshot =
                id(
                        "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,?::jsonb,?,?) RETURNING id",
                        version,
                        new String(frozen.payloadBytes(), StandardCharsets.UTF_8),
                        UUID.randomUUID(),
                        actor.principal().accountId());
        db.update(
                "UPDATE story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?",
                snapshot,
                version);
        Fixture result = new Fixture(actor, code, story, version, snapshot, frozen);
        grant(result, actor);
        return result;
    }

    /** 실제 현재 계정/MFA/저장 Spring Session/앱 세션을 같은 행위자에 결속한다. */
    private Actor actor() {
        UUID key = UUID.randomUUID();
        long id =
                id(
                        "INSERT INTO admin_account(account_key,can_review) VALUES (?,true)"
                                + " RETURNING id",
                        key);
        db.update(
                "INSERT INTO"
                    + " admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                    + " VALUES"
                    + " (?,'synthetic',?,'synthetic','synthetic',clock_timestamp(),0,clock_timestamp(),'READY')",
                id,
                ByteBuffer.allocate(32).putLong(id).array());
        var prepared = sessions.prepare();
        var principal = new AdminPrincipal(id, key, UUID.randomUUID(), 1);
        sessions.save(prepared, principal);
        db.update(
                "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " SELECT ?,?,?,1,'ACTIVE',n,n,n+interval '8 hours',n,n FROM t",
                principal.sessionKey(),
                id,
                crypto.sessionHash(prepared.id()));
        return new Actor(prepared.id(), principal);
    }

    /** 현재 실제 부모의 명시 REVIEW 관계만 부여한다. */
    private void grant(Fixture f, Actor actor) {
        db.update(
                "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                        + " (?,?,'REVIEW',?)",
                f.story(),
                actor.principal().accountId(),
                f.actor().principal().accountId());
    }

    /** 인증 레지스트리·실제 접수/임대/예약/완료 서비스를 동일 폐기형 DB에 조립한다. */
    private Worker workerServices() {
        return workerServices(runtimeCode, installation);
    }

    private Worker workerServices(String code, InstalledRuntimeManifestVerifier verifier) {
        byte[] token =
                ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(1)
                        .putLong(2)
                        .putLong(3)
                        .array();
        var registration =
                new GradeWorkerCredentials.Registration(
                        "BATCH_W_" + UUID.randomUUID().toString().replace("-", ""),
                        com.reasoning.common.util.CommonUtil.sha256(token),
                        Set.of(code),
                        Set.of(
                                GradeWorkerCredentials.Action.CLAIM,
                                GradeWorkerCredentials.Action.START,
                                GradeWorkerCredentials.Action.COMPLETE,
                                GradeWorkerCredentials.Action.RENEW));
        var credentials = new GradeWorkerCredentials(List.of(registration));
        var identity =
                credentials.authenticate(
                        "Bearer "
                                + java.util.Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(token));
        var tx = new DataSourceTransactionManager(db.getDataSource());
        var installed = Map.of(code, verifier);
        return new Worker(
                credentials,
                identity,
                new GradeCoordinatorService(
                        db, tx, credentials, installed, "BATCH_TEST_COORDINATOR", 10),
                new GradeLeaseRepository(db, tx),
                new GradeStartService(db, tx, credentials, installed),
                new GradeCompletionService(
                        db, tx, credentials, installed, crypto, "BATCH_TEST_COORDINATOR"));
    }

    /**
     * 기대값·fixture 경로를 읽지 않은 합성 의미 입력을 작성한다. 실제 모델 출력이나 품질 승인 증거가 아니다.
     *
     * @param met 네 설명 항목의 명제 충족 여부
     * @param contradicted 범인 모순 명제의 충족 여부
     * @return 점수·기대값이 없는 닫힌 COMPLETE 프로토콜 객체
     */
    private static ObjectNode syntheticNormal(boolean met, boolean contradicted) {
        ObjectNode semantic =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("formatNo", 1)
                        .put("status", "COMPLETE");
        var items = semantic.putArray("items");
        ObjectNode culprit =
                items.addObject()
                        .put("rubricCode", "CULPRIT")
                        .put("reason", "합성 의미 입력이며 실제 추론이 아님");
        culprit.putArray("claims");
        var contradictions = culprit.putArray("contradictions");
        for (String code : List.of("CONTRADICT_CULPRIT", "UNSUPPORTED_ACCOMPLICE")) {
            boolean active = contradicted && code.equals("CONTRADICT_CULPRIT");
            var spans =
                    contradictions
                            .addObject()
                            .put("code", code)
                            .put("met", active)
                            .putArray("spans");
            if (active) spans.addObject().put("field", "method").put("start", 0).put("end", 1);
        }
        for (String code : List.of("METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            ObjectNode item =
                    items.addObject().put("rubricCode", code).put("reason", "합성 의미 입력이며 실제 추론이 아님");
            item.putArray("contradictions");
            var spans =
                    item.putArray("claims")
                            .addObject()
                            .put("code", "CLAIM")
                            .put("met", met)
                            .putArray("spans");
            if (met) spans.addObject().put("field", "method").put("start", 0).put("end", 1);
        }
        ObjectNode protocol =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("kind", "COMPLETE");
        protocol.set("semantic", semantic);
        return protocol;
    }

    /** 테스트 기본의 정확한 다섯 입력으로만 서비스에 접수한다. */
    private ActionResult create(Fixture f, UUID key) {
        return batches.createBatch(
                f.actor().sid(),
                f.actor().principal(),
                f.code(),
                1,
                "0",
                Long.toString(f.snapshot()),
                runtimeCode,
                "REVIEW",
                key,
                UUID.randomUUID());
    }

    /** 같은 부모의 현재 인가 상세만 읽는다. */
    private StoryBatchService.BatchDetail detail(Fixture f, ActionResult result) {
        return batches.getBatchDetail(
                f.actor().sid(),
                f.actor().principal(),
                f.code(),
                1,
                result.original().batchKey(),
                UUID.randomUUID());
    }

    /** 동시 전역 키 결과는 성공 또는 실제 cause-free 업무 오류로 구분한다. */
    private String race(Fixture f, UUID key, CyclicBarrier barrier) throws Exception {
        barrier.await();
        try {
            create(f, key);
            return "ACCEPTED";
        } catch (com.reasoning.common.auth.service.AuthException failure) {
            return failure.getMessage();
        }
    }

    /** bounded PG 잠금 대기를 실제 pg_stat_activity로 관측하고 임의 sleep으로 성공을 가정하지 않는다. */
    private void waitForRuntimeWait() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) {
            if (db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                                + " AND wait_event_type='Lock' AND query LIKE '%grade_runtime%FOR"
                                + " UPDATE%'",
                            Integer.class)
                    > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("실제 runtime 잠금 대기가 관측되지 않았습니다.");
    }

    /** 래치 대기도 고정 상한 뒤 실패한다. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("잠금 해제 상한");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** 시험 소유 트리거만 생성하여 실제 JDBC 저장 실패/성공 변조를 유발한다. */
    private void trigger(String table, String statement) {
        db.execute(
                "CREATE FUNCTION batch_test_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " "
                        + statement
                        + " RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER batch_test_mutation BEFORE INSERT ON "
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION batch_test_mutation()");
    }

    /** 시험이 만든 트리거·함수만 finally에서 제거한다. */
    private void removeTrigger(String table) {
        db.execute("DROP TRIGGER batch_test_mutation ON " + table);
        db.execute("DROP FUNCTION batch_test_mutation()");
    }

    /** 조회에서 집합/작업은 한 필드도 바뀌지 않아야 한다. */
    private String batchState(ActionResult result) {
        return db.queryForObject(
                "SELECT jsonb_build_object('batch',to_jsonb(b),'jobs',(SELECT jsonb_agg(to_jsonb(j)"
                        + " ORDER BY id) FROM grade_job j WHERE j.batch_id=b.id))::text FROM"
                        + " grade_batch b WHERE id=?",
                String.class,
                batchId(result));
    }

    /** 실제 부모·사본 내용과 시각을 전체 JSONB로 대조한다. */
    private String content(Fixture f) {
        return db.queryForObject(
                "SELECT"
                    + " jsonb_build_object('story',to_jsonb(s),'version',to_jsonb(v),'snapshot',to_jsonb(r))::text"
                    + " FROM story s JOIN story_version v ON v.story_id=s.id JOIN review_snapshot r"
                    + " ON r.version_id=v.id WHERE r.id=?",
                String.class,
                f.snapshot());
    }

    private long batchId(ActionResult result) {
        return id("SELECT id FROM grade_batch WHERE batch_key=?", result.original().batchKey());
    }

    private long id(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    private int count(String table, String predicate, Object... args) {
        return db.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + predicate, Integer.class, args);
    }

    private static JsonNode parse(String value) {
        return SnapshotJson.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 정확한 고정 다섯 필드와 UUIDv4 의도만 HTTP에 보낸다. */
    private String body(Fixture f, UUID key) {
        return "{\"expectedRev\":\"0\",\"snapshotId\":\""
                + f.snapshot()
                + "\",\"runtimeConfigId\":\""
                + runtimeCode
                + "\",\"purpose\":\"REVIEW\",\"requestKey\":\""
                + key
                + "\"}";
    }

    /** 저장 어댑터가 발급한 실제 __Host 쿠키를 읽는다. */
    private Cookie sessionCookie(Actor actor) {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        sessions.issueCookie(actor.sid(), request, response);
        return response.getCookie("__Host-admin-session");
    }

    /** 기존 동일출처·CSRF·저장 세션 경계를 그대로 적용한다. */
    private static MockHttpServletRequestBuilder authorized(
            String path, Cookie session, Cookie csrf, String token) {
        return post(path)
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerPort(443);
                            return request;
                        })
                .cookie(session, csrf)
                .header("Origin", "https://localhost")
                .header("X-CSRF-TOKEN", token)
                .contentType("application/json");
    }

    private static MockHttpServletRequestBuilder httpsGet(String path) {
        return get(path)
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerPort(443);
                            return request;
                        });
    }

    private static List<String> fields(JsonNode node) {
        List<String> fields = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private record Actor(String sid, AdminPrincipal principal) {}

    private record Worker(
            GradeWorkerCredentials credentials,
            GradeWorkerCredentials.VerifiedWorker identity,
            GradeCoordinatorService coordinator,
            GradeLeaseRepository leases,
            GradeStartService start,
            GradeCompletionService completion) {}

    private record Fixture(
            Actor actor,
            String code,
            long story,
            long version,
            long snapshot,
            FrozenSnapshot frozen) {}
}
