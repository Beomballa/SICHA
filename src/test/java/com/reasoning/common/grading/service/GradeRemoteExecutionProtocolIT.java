package com.reasoning.common.grading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.GradeSchemaIT;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeDictionary.Term;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.Failure;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 실제 고정 PG16.10·최신 migration·전체 frozen·설치·registry로 서버 경계만 검사한다. provider나 journal은 실행하지 않는다. */
class GradeRemoteExecutionProtocolIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static InstalledRuntimeManifestVerifier installation;
    private static long runtime;
    private static CryptoService crypto;
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

    /** 폐기형 PG·최신 migration·실제 설치와 합성 개인 키 파일을 준비한다. 지문은 TX 밖에서 만들며 endpoint에는 접속하지 않는다. */
    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var dictionary =
                new GradeDictionary("REMOTE_SYNTHETIC", List.of(new Term("ONE", "개념", "합성")));
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:1"),
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
        var registered =
                new GradeRuntimeRepository(jdbc)
                        .registerRuntime(
                                "REMOTE_SYNTHETIC",
                                installation.configHash(),
                                installation.registrationManifest().toString());
        assertThat(registered.state()).isEqualTo("AVAILABLE");
        assertThat(registered.epoch()).isZero();
        runtime = registered.id();
        var properties = new AuthProperties();
        properties.setCryptoKeyFile(TestKeys.create((byte) 41));
        properties.setSearchKeyFile(TestKeys.create((byte) 42));
        properties.setLimitKeyFile(TestKeys.create((byte) 43));
        crypto = new CryptoService(properties);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(version::int) FROM public.flyway_schema_history WHERE"
                                        + " success",
                                Integer.class))
                .isEqualTo(23);
    }

    /** 생성한 폐기형 PostgreSQL 자원만 닫는다. */
    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 전체 fixture와 실제 registry·관계·선택 해시를 조립한다. 폐기형 DB에 부모·작업 SQL을 쓰고 provider는 호출하지 않는다. */
    @BeforeEach
    void fixtures() {
        jdbc.update(
                "UPDATE public.grade_runtime SET"
                    + " state='AVAILABLE',epoch=0,config_hash=?,config_data=?::jsonb WHERE id=?",
                installation.configHash(),
                installation.registrationManifest().toString(),
                runtime);
        workerKey = "W_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        byte[] secret =
                ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(2)
                        .putLong(3)
                        .putLong(4)
                        .array();
        credentials =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        CommonUtil.sha256(secret),
                                        Set.of("REMOTE_SYNTHETIC"),
                                        Set.of(Action.START, Action.RENEW, Action.COMPLETE))));
        worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        service = service(jdbc, manager);
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
        String code = "ST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        code,
                        creator);
        version =
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
        snapshot =
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
        jobKey = UUID.randomUUID();
        job =
                id(
                        "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                            + " public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash,accepted_at,deadline_at,worker_key,lease_gen,lease_until)"
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
    }

    /**
     * 두 실제 서비스의 동시 START가 시도 하나·NEW 하나·REPLAY 하나만 커밋하는지 검사한다.
     *
     * @throws Exception 실제 병렬 작업·5초 대기가 실패하면 발생
     */
    @Test
    void concurrentStartCommitsOneNewAndOneInputlessReplay() throws Exception {
        var other = service(jdbc, manager);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                gate.await();
                                return service.startApproved(worker, jobKey, 1);
                            });
            var second =
                    executor.submit(
                            () -> {
                                gate.await();
                                return other.startApproved(worker, jobKey, 1);
                            });
            gate.countDown();
            var replies = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(replies.stream().filter(value -> value.disposition().equals("NEW")).count())
                    .isEqualTo(1);
            assertThat(
                            replies.stream()
                                    .filter(value -> value.disposition().equals("REPLAY"))
                                    .count())
                    .isEqualTo(1);
            var replay =
                    replies.stream()
                            .filter(value -> value.disposition().equals("REPLAY"))
                            .findFirst()
                            .orElseThrow();
            assertThat(replay.toJson().get("input").isNull()).isTrue();
            assertThat(replay.toJson().get("limits").isNull()).isTrue();
        }
        assertThat(attempts()).hasSize(1);
        assertThat(count()).isEqualTo(1);
        assertThat(attempts().getFirst().get("worker_key")).isEqualTo(workerKey);
        assertThat(((Number) attempts().getFirst().get("lease_gen")).longValue()).isEqualTo(1);
    }

    /** 반복 fence가 성공해도 실제 job·attempt 행을 변경하거나 서버 단회 grant를 만들지 않음을 검사한다. */
    @Test
    void statelessFenceMaySucceedRepeatedlyAndNeverCreatesServerOnceOwnership() {
        var fresh = service.startApproved(worker, jobKey, 1);
        var request = request(fresh);
        var before = jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job);
        var children = attempts();
        var first = service.revalidate(worker, jobKey, request);
        var second = service.revalidate(worker, jobKey, request);
        assertThat(second.toJson().get("originalAttemptHash"))
                .isEqualTo(first.toJson().get("originalAttemptHash"));
        assertThat(first.toJson().toString()).doesNotContain("grant", "nonce", "issued", "token");
        assertThat(jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job))
                .isEqualTo(before);
        assertThat(attempts()).isEqualTo(children);
        var replay = service.startApproved(worker, jobKey, 1);
        assertThat(replay.disposition()).isEqualTo("REPLAY");
        assertThat(replay.toJson().get("input").isNull()).isTrue();
    }

    /**
     * 실제 account 잠금 중 runtime·registry 거절이 모든 원격 방법에서 잠금 전에 끝나는지 검사한다.
     *
     * @throws Exception 실제 연결·병렬 작업·1초 대기가 실패하면 발생
     */
    @Test
    void deniedRuntimePermissionFailsBeforeActualAccountLockForAllRemoteMethods() throws Exception {
        byte[] secret = ByteBuffer.allocate(32).putLong(91).array();
        var denied =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        CommonUtil.sha256(secret),
                                        Set.of("OTHER_RUNTIME"),
                                        Set.of(Action.START, Action.RENEW))));
        var proof =
                denied.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        var remote =
                new GradeStartService(
                        jdbc, manager, denied, Map.of("REMOTE_SYNTHETIC", installation));
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
                                    var tuple = new AttemptRequest(1, 1, "a".repeat(64));
                                    reject(
                                            () -> remote.startApproved(proof, jobKey, 1),
                                            "REMOTE_EXECUTION_FORBIDDEN");
                                    reject(
                                            () -> remote.revalidate(proof, jobKey, tuple),
                                            "REMOTE_EXECUTION_FORBIDDEN");
                                    reject(
                                            () -> remote.renewApproved(proof, jobKey, tuple),
                                            "REMOTE_EXECUTION_FORBIDDEN");
                                    reject(
                                            () -> service.startApproved(proof, jobKey, 1),
                                            "REMOTE_EXECUTION_FORBIDDEN");
                                    return true;
                                });
                assertThat(result.get(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
        assertThat(count()).isZero();
        assertThat(attempts()).isEmpty();
    }

    /** null·영 UUID·무효 세대·null tuple이 실제 행 변경 전에 고정 입력 오류로 거절되는지 검사한다. */
    @Test
    void malformedRemoteInputsRejectBeforeMutatingAnyActualRow() {
        reject(() -> service.startApproved(null, jobKey, 1), "INVALID_REMOTE_REQUEST");
        reject(() -> service.startApproved(worker, null, 1), "INVALID_REMOTE_REQUEST");
        reject(() -> service.startApproved(worker, new UUID(0, 0), 1), "INVALID_REMOTE_REQUEST");
        reject(() -> service.startApproved(worker, jobKey, 0), "INVALID_REMOTE_REQUEST");
        reject(() -> service.revalidate(worker, jobKey, null), "INVALID_REMOTE_REQUEST");
        reject(() -> service.renewApproved(worker, jobKey, null), "INVALID_REMOTE_REQUEST");
        assertThat(count()).isZero();
        assertThat(attempts()).isEmpty();
    }

    /** 실제 로컬 예약의 issued·hook 가드가 권한 실패 후에도 소비되며 remote REPLAY가 소유권을 주지 않음을 검사한다. */
    @Test
    void localIssuerAndHookRemainConsumedEvenWhenFreshAuthorityFails() {
        var local = service.start(worker, jobKey, 1);
        assertThat(service.startApproved(worker, jobKey, 1).disposition()).isEqualTo("REPLAY");
        var hook = service.beforeChat(worker, local);
        assertThatThrownBy(() -> service.beforeChat(worker, local)).hasMessage("START_NOT_CURRENT");
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        assertThatThrownBy(hook::check).hasMessage("SOURCE_NOT_CURRENT");
        restoreAuthority();
        assertThatThrownBy(hook::check).hasMessage("START_NOT_CURRENT");
        assertThat(count()).isEqualTo(1);
        assertThat(attempts()).hasSize(1);
    }

    /** 실제 NEW 바이트를 서버 투영과 비교하고 공개 봉투의 private 필드·다른 fixture 배제를 검사한다. */
    @Test
    void actualModelBytesAreExactAndAllPrivateEnvelopeFieldsStayServerSide() {
        var fresh = service.startApproved(worker, jobKey, 1);
        var input = fresh.toJson().get("input");
        var dataset = new FrozenDatasetValidator().validate(frozen);
        byte[] bytes =
                Base64.getDecoder().decode(input.get("modelInput").get("bytesBase64").textValue());
        assertThat(bytes)
                .isEqualTo(
                        FrozenModelProjection.project(dataset, dataset.select("FULL"))
                                .payloadBytes());
        assertThat(input.get("modelInput").get("sha256").textValue())
                .isEqualTo(CommonUtil.sha256(bytes));
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .contains("SELECTED_REPORT")
                .doesNotContain(
                        "ENGINE_REPORT",
                        "expectData",
                        "expectedScore",
                        "expectedSuccess",
                        "checkedBy",
                        "revealText");
        assertThat(input.get("fault").isNull()).isTrue();
        assertThat(input.toString())
                .doesNotContain(
                        "inputHash",
                        "reportHash",
                        "workerKey",
                        "sourceBinding",
                        "settings",
                        "endpoint",
                        "dictionaryHash",
                        "engineCodeHash",
                        "batchCreatedAt");
        assertThat(fresh.toString()).isEqualTo("StartReply[redacted]");
        assertThat(input.get("runtime").size()).isEqualTo(10);
        assertThat(input.get("runtime").get("epoch").longValue()).isZero();
    }

    /** 실제 선택 SQL로 ENGINE 제어 전용 봉투와 INPUT_ERROR 시도0 경계를 검사한다. provider는 호출하지 않는다. */
    @Test
    void admittedEngineControlIsExplicitAndInputErrorCreatesNoAttempt() {
        select("ENGINE");
        var reply = service.startApproved(worker, jobKey, 1).toJson();
        assertThat(reply.get("input").get("variant").textValue()).isEqualTo("ENGINE_ERROR");
        assertThat(reply.get("input").get("modelInput").isNull()).isTrue();
        assertThat(SnapshotJson.encode(reply.get("input").get("fault")))
                .isEqualTo(
                        "{\"failRuns\":3,\"type\":\"TIMEOUT\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(count()).isEqualTo(1);
        fixtures();
        select("INPUT");
        reject(() -> service.startApproved(worker, jobKey, 1), "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(count()).isZero();
        assertThat(attempts()).isEmpty();
    }

    /** 폐기형 PG의 성공 ROW_COUNT=1 변조 트리거를 설치·제거하고 readback이 두 예약 변경을 롤백하는지 검사한다. */
    @Test
    void successfulRowCountOneTriggerDivergenceRollsBackBothMutationHalves() {
        trigger("grade_job", "UPDATE", "NEW.call_count := 2;");
        try {
            // 실제 UPDATE 자체는 성공하며 ROW_COUNT=1이다. 서비스가 readback으로 거절해야 한다.
            var probe = new TransactionTemplate(manager);
            probe.executeWithoutResult(
                    status -> {
                        assertThat(
                                        jdbc.update(
                                                "UPDATE public.grade_job SET call_count=1 WHERE"
                                                        + " id=?",
                                                job))
                                .isEqualTo(1);
                        assertThat(count()).isEqualTo(2);
                        status.setRollbackOnly();
                    });
            reject(() -> service.startApproved(worker, jobKey, 1), "REMOTE_EXECUTION_NOT_CURRENT");
            assertThat(count()).isZero();
            assertThat(attempts()).isEmpty();
        } finally {
            dropTrigger("grade_job");
        }
        trigger("grade_attempt", "INSERT", "NEW.worker_key := 'OTHER';");
        try {
            reject(() -> service.startApproved(worker, jobKey, 1), "REMOTE_EXECUTION_NOT_CURRENT");
            assertThat(count()).isZero();
            assertThat(attempts()).isEmpty();
        } finally {
            dropTrigger("grade_attempt");
        }
    }

    /** 실제 등록 초기 세대0에서 NEW·fence·renew·REPLAY가 원래 시도 하나와 마감을 유지하는지 검사한다. */
    @Test
    void registeredInitialEpochZeroStartsFencesAndRenewsWithoutNewAttempt() {
        var fresh = service.startApproved(worker, jobKey, 1);
        assertThat(fresh.disposition()).isEqualTo("NEW");
        assertThat(fresh.toJson().get("input").get("runtime").get("epoch").longValue()).isZero();
        var expected = request(fresh);
        var originalDeadline = fresh.toJson().get("limits").get("deadlineAt");
        assertThat(
                        service.revalidate(worker, jobKey, expected)
                                .toJson()
                                .get("originalAttemptHash")
                                .textValue())
                .isEqualTo(expected.originalAttemptHash());
        var renewed = service.renewApproved(worker, jobKey, expected).toJson();
        assertThat(renewed.get("originalAttemptHash").textValue())
                .isEqualTo(expected.originalAttemptHash());
        assertThat(renewed.get("limits").get("deadlineAt")).isEqualTo(originalDeadline);
        service.revalidate(worker, jobKey, expected);
        assertThat(count()).isEqualTo(1);
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().getFirst().get("state")).isEqualTo("RUNNING");
        assertThat(attempts().getFirst().get("completion_data")).isNull();
        var replay = service.startApproved(worker, jobKey, 1);
        assertThat(replay.disposition()).isEqualTo("REPLAY");
        assertThat(replay.toJson().get("input").isNull()).isTrue();
        assertThat(replay.toJson().get("limits").isNull()).isTrue();
        assertThat(count()).isEqualTo(1);
        assertThat(attempts()).hasSize(1);
    }

    /** 실제 creator·credential·story·version·runtime 회수 SQL을 각각 커밋·복원하고 fence 거절과 시도 불변을 검사한다. */
    @Test
    void allCurrentCreatorStoryVersionAndRuntimeRevocationsRejectWithoutAttempts() {
        var mutations =
                List.of(
                        "UPDATE public.admin_account SET active_yn=false WHERE id=" + creator,
                        "UPDATE public.admin_account SET can_review=false WHERE id=" + creator,
                        "UPDATE public.admin_credential SET enrolled_at=NULL,mfa_state='PENDING'"
                                + " WHERE account_id="
                                + creator,
                        "UPDATE public.admin_credential SET mfa_state='RECOVERY' WHERE account_id="
                                + creator,
                        "UPDATE public.story_access SET active_yn=false WHERE story_id=" + story,
                        "UPDATE public.story SET active_yn=false WHERE id=" + story,
                        "UPDATE public.story_version SET active_yn=false WHERE id=" + version,
                        "UPDATE public.story_version SET status='DRAFT',current_snapshot_id=NULL"
                                + " WHERE id="
                                + version,
                        "UPDATE public.grade_runtime SET state='SUSPENDED' WHERE id=" + runtime,
                        "UPDATE public.grade_runtime SET state='RETIRED' WHERE id=" + runtime,
                        "UPDATE public.grade_runtime SET epoch=2 WHERE id=" + runtime);
        var request = request(service.startApproved(worker, jobKey, 1));
        for (String sql : mutations) {
            // 실제 커밋 회수를 각각 적용하고 복원하여 별도 현재 관측을 검사한다.
            jdbc.execute(sql);
            reject(
                    () -> service.revalidate(worker, jobKey, request),
                    "REMOTE_EXECUTION_NOT_CURRENT");
            restoreAuthority();
        }
        assertThat(count()).isEqualTo(1);
        assertThat(attempts()).hasSize(1);
    }

    /** 설치 JSON·전체 frozen·선택·tuple·실제 시도 owner/gen/state 변조가 현재 권위를 얻지 못하는지 검사한다. */
    @Test
    void installationWholeFrozenSourceTupleAndReceiptDriftsCannotAuthorize() {
        var request = request(service.startApproved(worker, jobKey, 1));
        reject(
                () -> service.revalidate(worker, jobKey, new AttemptRequest(1, 1, "b".repeat(64))),
                "REMOTE_EXECUTION_NOT_CURRENT");
        reject(
                () ->
                        service.revalidate(
                                worker,
                                jobKey,
                                new AttemptRequest(2, 1, request.originalAttemptHash())),
                "REMOTE_EXECUTION_NOT_CURRENT");
        reject(
                () ->
                        service.revalidate(
                                worker,
                                jobKey,
                                new AttemptRequest(1, 2, request.originalAttemptHash())),
                "REMOTE_EXECUTION_NOT_CURRENT");
        var originalManifest = installation.registrationManifest().toString();
        jdbc.update(
                "UPDATE public.grade_runtime SET"
                    + " config_data=jsonb_set(config_data,'{engineVersion}','\"changed\"') WHERE"
                    + " id=?",
                runtime);
        reject(() -> service.revalidate(worker, jobKey, request), "REMOTE_EXECUTION_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.grade_runtime SET config_data=?::jsonb WHERE id=?",
                originalManifest,
                runtime);
        var changed = (ObjectNode) frozen.payload();
        ((ObjectNode) changed.get("sections").get("reveal"))
                .put("revealText", "PRIVATE_REVEAL_CANARY");
        jdbc.update(
                "UPDATE public.review_snapshot SET payload=?::jsonb WHERE id=?",
                FrozenSnapshotCodec.freeze(changed).payload().toString(),
                snapshot);
        reject(() -> service.revalidate(worker, jobKey, request), "REMOTE_EXECUTION_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.review_snapshot SET payload=?::jsonb WHERE id=?",
                frozen.payload().toString(),
                snapshot);
        select("ZERO");
        reject(() -> service.revalidate(worker, jobKey, request), "REMOTE_EXECUTION_NOT_CURRENT");
        select("FULL");
        jdbc.update("UPDATE public.grade_attempt SET worker_key='OTHER' WHERE job_id=?", job);
        reject(() -> service.revalidate(worker, jobKey, request), "REMOTE_EXECUTION_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.grade_attempt SET worker_key=?,lease_gen=2 WHERE job_id=?",
                workerKey,
                job);
        reject(() -> service.revalidate(worker, jobKey, request), "REMOTE_EXECUTION_NOT_CURRENT");
        jdbc.update(
                "UPDATE public.grade_attempt SET"
                        + " lease_gen=1,state='FAILED',ended_at=clock_timestamp() WHERE job_id=?",
                job);
        reject(() -> service.revalidate(worker, jobKey, request), "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(count()).isEqualTo(1);
    }

    /** 폐기형 DB에서 receipt·call_count·owner 변조와 attempt 삭제를 수행해 거절이 저장 사실을 수선하지 않음을 검사한다. */
    @Test
    void receiptAbsentAttemptCallCountAndJobOwnerMutationsRejectWithoutRepair() {
        var expected = request(service.startApproved(worker, jobKey, 1));
        // 정상 영수증을 위조하는 서비스가 아니라 schema가 허용한 공격 행 변경이다.
        jdbc.update(
                "UPDATE public.grade_attempt SET completion_data='{}'::jsonb WHERE job_id=?", job);
        reject(() -> service.revalidate(worker, jobKey, expected), "REMOTE_EXECUTION_NOT_CURRENT");
        jdbc.update("UPDATE public.grade_attempt SET completion_data=NULL WHERE job_id=?", job);
        jdbc.update("UPDATE public.grade_job SET call_count=2 WHERE id=?", job);
        reject(() -> service.revalidate(worker, jobKey, expected), "REMOTE_EXECUTION_NOT_CURRENT");
        jdbc.update("UPDATE public.grade_job SET call_count=1,worker_key='OTHER' WHERE id=?", job);
        reject(() -> service.revalidate(worker, jobKey, expected), "REMOTE_EXECUTION_NOT_CURRENT");
        jdbc.update("UPDATE public.grade_job SET worker_key=? WHERE id=?", workerKey, job);
        jdbc.update("DELETE FROM public.grade_attempt WHERE job_id=?", job);
        reject(() -> service.revalidate(worker, jobKey, expected), "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(count()).isEqualTo(1);
        assertThat(attempts()).isEmpty();
    }

    /** terminal fixture SQL로 세대를 진행하여 실제 live third fence·renew 허용과 fourth 예약 거절을 검사한다. */
    @Test
    void liveThirdAttemptFencesAndRenewsButFourthCannotReserve() {
        service.startApproved(worker, jobKey, 1);
        advance(2);
        service.startApproved(worker, jobKey, 2);
        advance(3);
        var third = request(service.startApproved(worker, jobKey, 3));
        assertThat(service.revalidate(worker, jobKey, third).toJson().get("attemptNo").intValue())
                .isEqualTo(3);
        assertThat(
                        service.renewApproved(worker, jobKey, third)
                                .toJson()
                                .get("attemptNo")
                                .intValue())
                .isEqualTo(3);
        assertThat(count()).isEqualTo(3);
        advance(4);
        reject(() -> service.startApproved(worker, jobKey, 4), "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(attempts()).hasSize(3);
    }

    /** 실제 leases.renew가 lease만 연장하고 원래 digest·deadline·attempt를 유지하는지 검사한다. */
    @Test
    void actualRenewalChangesOnlyLeaseAndLeavesOriginalHashDeadlineAndAttempt() {
        var fresh = service.startApproved(worker, jobKey, 1);
        var request = request(fresh);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '5 seconds'"
                        + " WHERE id=?",
                job);
        var before =
                jdbc.queryForObject(
                        "SELECT lease_until FROM public.grade_job WHERE id=?",
                        OffsetDateTime.class,
                        job);
        var children = attempts();
        var reply = service.renewApproved(worker, jobKey, request).toJson();
        var after =
                jdbc.queryForObject(
                        "SELECT lease_until FROM public.grade_job WHERE id=?",
                        OffsetDateTime.class,
                        job);
        assertThat(after).isAfter(before);
        assertThat(reply.get("originalAttemptHash").textValue())
                .isEqualTo(request.originalAttemptHash());
        assertThat(reply.get("limits").get("deadlineAt"))
                .isEqualTo(fresh.toJson().get("limits").get("deadlineAt"));
        assertThat(reply.get("limits").get("remainingLeaseMillis").longValue())
                .isBetween(1L, 30000L);
        assertThat(attempts()).isEqualTo(children);
        assertThat(count()).isEqualTo(1);
    }

    /** 실제 갱신 연결 정리 후 회수 SQL·관측 연결 실패를 주입하여 실패 응답에도 커밋한 extension이 남는지 검사한다. */
    @Test
    void postRenewRevocationAndObservationFailureLeaveCommittedExtensionHonestly() {
        var request = request(service.startApproved(worker, jobKey, 1));
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '5 seconds'"
                        + " WHERE id=?",
                job);
        var before =
                jdbc.queryForObject(
                        "SELECT lease_until FROM public.grade_job WHERE id=?",
                        OffsetDateTime.class,
                        job);
        var committed = new AtomicBoolean();
        var wrapped =
                wrappedJdbc(
                        0,
                        0,
                        null,
                        () -> {
                            if (committed.compareAndSet(false, true))
                                jdbc.update(
                                        "UPDATE public.admin_account SET can_review=false WHERE"
                                                + " id=?",
                                        creator);
                        });
        var remote = service(wrapped, new DataSourceTransactionManager(wrapped.getDataSource()));
        reject(() -> remote.renewApproved(worker, jobKey, request), "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(committed).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_until FROM public.grade_job WHERE id=?",
                                OffsetDateTime.class,
                                job))
                .isAfter(before);
        assertThat(count()).isEqualTo(1);
        restoreAuthority();
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '5 seconds'"
                        + " WHERE id=?",
                job);
        var old =
                jdbc.queryForObject(
                        "SELECT lease_until FROM public.grade_job WHERE id=?",
                        OffsetDateTime.class,
                        job);
        var fail = new AtomicBoolean();
        var failing = wrappedJdbc(0, 0, null, () -> fail.set(true), fail);
        reject(
                () ->
                        service(failing, new DataSourceTransactionManager(failing.getDataSource()))
                                .renewApproved(worker, jobKey, request),
                "REMOTE_EXECUTION_UNAVAILABLE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_until FROM public.grade_job WHERE id=?",
                                OffsetDateTime.class,
                                job))
                .isAfter(old);
        assertThat(attempts()).hasSize(1);
    }

    /** 실제 갱신 TX 정리와 관측 사이에 COMPLETE 서비스를 실행해 영수증·실패 시도와 renew 거절을 검사한다. provider 증거는 아니다. */
    @Test
    void actualCompletionBetweenRenewCommitAndObservationRejectsReply() {
        var expected = request(service.startApproved(worker, jobKey, 1));
        var completion =
                new GradeCompletionService(
                        jdbc,
                        manager,
                        credentials,
                        Map.of("REMOTE_SYNTHETIC", installation),
                        crypto,
                        "REMOTE_COORDINATOR");
        var interleaved = new AtomicBoolean();
        var connection =
                wrappedJdbc(
                        0,
                        0,
                        null,
                        () -> {
                            if (interleaved.compareAndSet(false, true)) {
                                // 실제 COMPLETE 서비스에 합성 worker 오류 결과를 넣는다. provider 호출 증거가 아니다.
                                completion.complete(
                                        worker,
                                        jobKey,
                                        1,
                                        1,
                                        "a".repeat(64),
                                        null,
                                        "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_UNAVAILABLE\"}",
                                        UUID.randomUUID());
                            }
                        });
        reject(
                () ->
                        service(
                                        connection,
                                        new DataSourceTransactionManager(
                                                connection.getDataSource()))
                                .renewApproved(worker, jobKey, expected),
                "REMOTE_EXECUTION_NOT_CURRENT");
        assertThat(interleaved).isTrue();
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().getFirst().get("completion_data")).isNotNull();
        assertThat(attempts().getFirst().get("state")).isEqualTo("FAILED");
        assertThat(count()).isEqualTo(1);
    }

    /** 실제 연결 proxy의 clock 결과 반환100ms·commit150ms 지연이 export 예산에서 차감되는지 검사한다. */
    @Test
    void actualClockQueryReturnAndCommitDelaysAreSubtractedFromWireLimits() {
        var clock = new AtomicReference<OffsetDateTime>();
        var delayed = wrappedJdbc(100, 150, clock, null);
        var remote = service(delayed, new DataSourceTransactionManager(delayed.getDataSource()));
        var limits = remote.startApproved(worker, jobKey, 1).toJson().get("limits");
        long observedLeaseMillis =
                Duration.between(
                                clock.get().toInstant(),
                                java.time.Instant.parse(limits.get("leaseUntil").textValue()))
                        .toMillis();
        assertThat(limits.get("remainingLeaseMillis").longValue())
                .isLessThanOrEqualTo(observedLeaseMillis - 240);
        assertThat(count()).isEqualTo(1);
    }

    /** 실제 1초 lease와 commit1100ms proxy 지연으로 커밋 후 만료를 유발해 소비 환급·가짜 receipt 부재를 검사한다. */
    @Test
    void postCommitBudgetExhaustionDoesNotRefundReservation() {
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '1 second'"
                        + " WHERE id=?",
                job);
        var delayed = wrappedJdbc(0, 1100, null, null);
        reject(
                () ->
                        service(delayed, new DataSourceTransactionManager(delayed.getDataSource()))
                                .startApproved(worker, jobKey, 1),
                "REMOTE_EXECUTION_EXPIRED");
        assertThat(count()).isEqualTo(1);
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().getFirst().get("completion_data")).isNull();
    }

    /**
     * 독립 실제 연결의 account 회수 잠금 대기를 관찰한 뒤 커밋하여 대기 중 fence가 새 회수를 보는지 검사한다.
     *
     * @throws Exception 실제 연결·병렬 작업·잠금 대기 관찰 실패 시 발생
     */
    @Test
    void lockedRootWaitSeesCommittedRevocationNotPrewaitAuthority() throws Exception {
        var request = request(service.startApproved(worker, jobKey, 1));
        try (var holder = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            try (var update =
                    holder.prepareStatement(
                            "UPDATE public.admin_account SET can_review=false WHERE id=?")) {
                update.setLong(1, creator);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var waiting =
                        executor.submit(
                                () -> {
                                    reject(
                                            () -> service.revalidate(worker, jobKey, request),
                                            "REMOTE_EXECUTION_NOT_CURRENT");
                                    return true;
                                });
                awaitWait("admin_account");
                holder.commit();
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
        assertThat(count()).isEqualTo(1);
    }

    /**
     * 독립 실제 runtime 잠금·suspend 커밋 후 대기 중 fence가 새 상태를 보는지 검사한다.
     *
     * @throws Exception 실제 연결·병렬 작업·잠금 대기 관찰 실패 시 발생
     */
    @Test
    void lockedRuntimeWaitSeesCommittedSuspension() throws Exception {
        var expected = request(service.startApproved(worker, jobKey, 1));
        try (var holder = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            try (var update =
                    holder.prepareStatement(
                            "UPDATE public.grade_runtime SET state='SUSPENDED' WHERE id=?")) {
                update.setLong(1, runtime);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var waiting =
                        executor.submit(
                                () -> {
                                    reject(
                                            () -> service.revalidate(worker, jobKey, expected),
                                            "REMOTE_EXECUTION_NOT_CURRENT");
                                    return true;
                                });
                awaitWait("grade_runtime");
                holder.commit();
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
        assertThat(count()).isEqualTo(1);
    }

    /** 실제 ambient·foreign DS·read-only·serializable 조립 거절과 renewal 이전 lease 불변을 검사한다. */
    @Test
    void ambientForeignDatasourceReadonlyAndSerializableFailBeforeWrites() {
        var request = new AttemptRequest(1, 1, "a".repeat(64));
        for (boolean readOnly : List.of(false, true)) {
            var tx = new TransactionTemplate(manager);
            tx.setReadOnly(readOnly);
            tx.executeWithoutResult(
                    status -> {
                        reject(
                                () -> service.startApproved(worker, jobKey, 1),
                                "REMOTE_REQUIRES_SEPARATE_TRANSACTION");
                        reject(
                                () -> service.revalidate(worker, jobKey, request),
                                "REMOTE_REQUIRES_SEPARATE_TRANSACTION");
                        reject(
                                () -> service.renewApproved(worker, jobKey, request),
                                "REMOTE_REQUIRES_SEPARATE_TRANSACTION");
                    });
        }
        var foreign =
                new DataSourceTransactionManager(GradeSchemaIT.jdbc(postgres).getDataSource());
        var originalLease =
                jdbc.queryForObject(
                        "SELECT lease_until FROM public.grade_job WHERE id=?",
                        OffsetDateTime.class,
                        job);
        reject(
                () -> service(jdbc, foreign).startApproved(worker, jobKey, 1),
                "REMOTE_EXECUTION_UNAVAILABLE");
        reject(
                () -> service(jdbc, foreign).renewApproved(worker, jobKey, request),
                "REMOTE_EXECUTION_UNAVAILABLE");
        for (boolean readOnly : List.of(false, true)) {
            var altered =
                    new DataSourceTransactionManager(jdbc.getDataSource()) {
                        @Override
                        protected void doBegin(
                                Object transaction, TransactionDefinition definition) {
                            var actual = new DefaultTransactionDefinition(definition);
                            actual.setReadOnly(readOnly);
                            actual.setIsolationLevel(
                                    readOnly
                                            ? TransactionDefinition.ISOLATION_READ_COMMITTED
                                            : TransactionDefinition.ISOLATION_SERIALIZABLE);
                            super.doBegin(transaction, actual);
                        }
                    };
            reject(
                    () -> service(jdbc, altered).startApproved(worker, jobKey, 1),
                    "REMOTE_EXECUTION_NOT_CURRENT");
            reject(
                    () -> service(jdbc, altered).renewApproved(worker, jobKey, request),
                    "REMOTE_EXECUTION_UNAVAILABLE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT lease_until FROM public.grade_job WHERE id=?",
                                    OffsetDateTime.class,
                                    job))
                    .isEqualTo(originalLease);
        }
        assertThat(count()).isZero();
        assertThat(attempts()).isEmpty();
    }

    /** 실제 lease·deadline·batch 마감 SQL을 적용하여 엄격 만료 또는 원래 digest 불일치 거절을 검사한다. */
    @Test
    void strictExpiredLeaseDeadlineAndBatchCannotFenceOrStart() {
        for (String field : List.of("lease_until", "deadline_at")) {
            fixtures();
            var request = request(service.startApproved(worker, jobKey, 1));
            if (field.equals("deadline_at")) {
                jdbc.update(
                        "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) UPDATE"
                            + " public.grade_job SET deadline_at=t.n,accepted_at=t.n-interval '120"
                            + " seconds' FROM t WHERE id=?",
                        job);
            } else {
                jdbc.update(
                        "UPDATE public.grade_job SET lease_until=clock_timestamp() WHERE id=?",
                        job);
            }
            // deadline는 original digest도 달라지므로 현재성 거절이다. lease는 엄격 만료다.
            reject(
                    () -> service.revalidate(worker, jobKey, request),
                    field.equals("lease_until")
                            ? "REMOTE_EXECUTION_EXPIRED"
                            : "REMOTE_EXECUTION_NOT_CURRENT");
            assertThat(count()).isEqualTo(1);
        }
        fixtures();
        var request = request(service.startApproved(worker, jobKey, 1));
        jdbc.update(
                "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval '24 hours'"
                        + " WHERE id=?",
                batch);
        reject(() -> service.revalidate(worker, jobKey, request), "REMOTE_EXECUTION_EXPIRED");
    }

    /**
     * 실제 fixture registry·설치로 non-bean 서비스를 조립하며 SQL은 실행하지 않는다.
     *
     * @param connection null 아닌 실제 또는 실제 연결 위임 proxy JDBC
     * @param txManager null 아닌 해당 시험의 DS 관리자; 음성 시험은 의도적으로 불일치 가능
     * @return null 아닌 실제 START 서비스
     */
    private GradeStartService service(
            JdbcTemplate connection, DataSourceTransactionManager txManager) {
        return new GradeStartService(
                connection, txManager, credentials, Map.of("REMOTE_SYNTHETIC", installation));
    }

    /**
     * 실제 NEW 응답에서 원래 tuple만 왕복 요청으로 추출한다.
     *
     * @param reply null 아닌 입력이 존재하는 NEW 응답; REPLAY 불가
     * @return 양수 세대·1~3 시도·소문자64hash 요청
     */
    private AttemptRequest request(GradeRemoteExecutionProtocol.StartReply reply) {
        var json = reply.toJson();
        return new AttemptRequest(
                json.get("leaseGen").longValue(),
                json.get("attemptNo").intValue(),
                json.get("input").get("originalAttemptHash").textValue());
    }

    /**
     * 실제 서비스 작업이 원인 없는 정확한 고정 오류로 실패하는지 검사한다.
     *
     * @param operation null 아닌 내부 작업; fixture SQL·실제 서비스 TX 부작용 가능
     * @param code null 아닌 예상 고정 오류 이름
     * @throws AssertionError 타입·메시지·원인이 다르면 검증 실패
     */
    private void reject(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(Failure.class)
                .hasMessage(code)
                .hasNoCause();
    }

    /**
     * 선택 코드와 전체 frozen의 해당 입력 해시를 실제 작업 SQL로 함께 변경한다.
     *
     * @param code null 아닌 전체 fixture의 FULL·ZERO·ENGINE·INPUT 선택 코드
     */
    private void select(String code) {
        jdbc.update(
                "UPDATE public.grade_job SET sample_code=?,input_hash=? WHERE id=?",
                code,
                frozen.inputHash(code),
                job);
    }

    /**
     * 제공자 결과를 만들지 않고 RUNNING 시도를 terminal fixture SQL로 종료하고 다음 세대를 지정한다.
     *
     * @param generation 양수 long 시험 임대 세대; 여기서는 2~4
     */
    private void advance(long generation) {
        jdbc.update(
                "UPDATE public.grade_attempt SET state='FAILED',ended_at=clock_timestamp() WHERE"
                        + " job_id=? AND state='RUNNING'",
                job);
        jdbc.update("UPDATE public.grade_job SET lease_gen=? WHERE id=?", generation, job);
    }

    /** 현재 fixture의 creator·credential·story·version·runtime 권한을 폐기형 DB SQL로 복원한다. */
    private void restoreAuthority() {
        jdbc.update(
                "UPDATE public.admin_account SET active_yn=true,can_review=true WHERE id=?",
                creator);
        jdbc.update(
                "UPDATE public.admin_credential SET enrolled_at=clock_timestamp(),mfa_state='READY'"
                        + " WHERE account_id=?",
                creator);
        jdbc.update("UPDATE public.story_access SET active_yn=true WHERE story_id=?", story);
        jdbc.update("UPDATE public.story SET active_yn=true WHERE id=?", story);
        jdbc.update(
                "UPDATE public.story_version SET"
                        + " active_yn=true,status='REVIEW',current_snapshot_id=? WHERE id=?",
                snapshot,
                version);
        jdbc.update(
                "UPDATE public.grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtime);
    }

    /**
     * 폐기형 DB의 내부 INSERT RETURNING 문장을 실행한다.
     *
     * @param sql null 아닌 내부 고정 INSERT SQL
     * @param args null 아닌 바인딩 배열; SQL이 허용한 개별 null만 가능
     * @return 실제 반환한 양수 식별자
     * @throws org.springframework.dao.DataAccessException 실제 SQL 실패 시 발생
     */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /**
     * @return 현재 실제 fixture 작업에서 읽은 0~3 call_count; SQL 쓰기 없음
     */
    private int count() {
        return jdbc.queryForObject(
                "SELECT call_count FROM public.grade_job WHERE id=?", Integer.class, job);
    }

    /**
     * @return 실제 fixture 시도 행을 번호순으로 읽은 null 아닌 목록; SQL 쓰기 없음
     */
    private List<Map<String, Object>> attempts() {
        return jdbc.queryForList(
                "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no", job);
    }

    /**
     * 성공한 실제 ROW_COUNT 변조를 유발할 시험 트리거·함수만 폐기형 DB에 설치한다.
     *
     * @param table null 아닌 내부 grade_job 또는 grade_attempt
     * @param event null 아닌 내부 UPDATE 또는 INSERT
     * @param body null 아닌 신뢰 시험용 PL/pgSQL 문장; 외부 입력 불가
     */
    private void trigger(String table, String event, String body) {
        jdbc.execute(
                "CREATE FUNCTION public.remote_test_trigger() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$ BEGIN "
                        + body
                        + " RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER remote_test_trigger BEFORE "
                        + event
                        + " ON public."
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION public.remote_test_trigger()");
    }

    /**
     * 이 시험이 만든 트리거·함수만 폐기형 DB에서 제거한다.
     *
     * @param table null 아닌 트리거가 설치된 내부 테이블명
     */
    private void dropTrigger(String table) {
        jdbc.execute("DROP TRIGGER remote_test_trigger ON public." + table);
        jdbc.execute("DROP FUNCTION public.remote_test_trigger()");
    }

    /**
     * 별도 실제 연결에서 PG 잠금 대기를 최대100회 관측하며 각 회차20ms를 기다린다.
     *
     * @param table null 아닌 내부 admin_account 또는 grade_runtime
     * @throws Exception 실제 연결·통계 조회·대기 실패 시 발생
     * @throws AssertionError 제한 안에 실제 정상 루트 대기를 관찰하지 못하면 발생
     */
    private void awaitWait(String table) throws Exception {
        try (var monitor = postgres.createConnection("");
                var query = monitor.createStatement()) {
            for (int i = 0; i < 100; i++) {
                try (var result =
                        query.executeQuery(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                    + " datname=current_database() AND wait_event_type='Lock' AND"
                                    + " position('FROM public."
                                        + table
                                        + " WHERE id=' in query)>0)")) {
                    if (result.next() && result.getBoolean(1)) return;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new AssertionError("실제 정상 루트 잠금 대기 미관찰");
    }

    /**
     * 새 연결 실패 없이 실제 연결 위임 proxy JDBC를 만든다. DB clock 자체를 위조하지 않는다.
     *
     * @param queryDelay clock 쿼리 반환 뒤 지연 밀리초, 0 이상
     * @param commitDelay 실제 commit 반환 뒤 지연 밀리초, 0 이상
     * @param clock 읽은 실제 OffsetDateTime 기록 수신자; null이면 기록하지 않음
     * @param afterCommit 갱신 커밋 연결 정리 후 시험 작업; null이면 실행하지 않음
     * @return null 아닌 시험 전용 proxy JDBC
     */
    private JdbcTemplate wrappedJdbc(
            long queryDelay,
            long commitDelay,
            AtomicReference<OffsetDateTime> clock,
            Runnable afterCommit) {
        return wrappedJdbc(queryDelay, commitDelay, clock, afterCommit, new AtomicBoolean());
    }

    /**
     * 실제 연결에 반환·commit 지연과 갱신 후 연결 실패를 시험에서만 삽입한다. 가짜 holder·clock·grant는 없다.
     *
     * @param queryDelay clock 결과 반환 뒤 지연 밀리초, 0 이상
     * @param commitDelay 실제 commit 반환 뒤 지연 밀리초, 0 이상
     * @param clock 실제 시각 기록 수신자; null 허용
     * @param afterCommit 갱신 커밋 연결 close 후 SQL·실제 서비스 시험 작업; null 허용
     * @param failNewConnections null 아닌 플래그; true이면 새 연결 요청에서 합성 SQLException
     * @return 실제 DS에 위임하는 null 아닌 시험 JDBC; 생성 자체는 SQL을 실행하지 않음
     */
    private JdbcTemplate wrappedJdbc(
            long queryDelay,
            long commitDelay,
            AtomicReference<OffsetDateTime> clock,
            Runnable afterCommit,
            AtomicBoolean failNewConnections) {
        var source =
                new AbstractDataSource() {
                    @Override
                    public Connection getConnection() throws SQLException {
                        if (failNewConnections.get()) throw new SQLException("합성 관측 실패");
                        return wrapConnection(
                                jdbc.getDataSource().getConnection(),
                                queryDelay,
                                commitDelay,
                                clock,
                                afterCommit);
                    }

                    @Override
                    public Connection getConnection(String username, String password)
                            throws SQLException {
                        return getConnection();
                    }
                };
        return new JdbcTemplate(source);
    }

    /**
     * 같은 실제 연결·Statement·ResultSet을 위임하고 시험 지연·관측만 추가한다.
     *
     * @param real null 아닌 실제 열린 JDBC 연결
     * @param queryDelay 실제 clock 결과 반환 뒤 지연 밀리초, 0 이상
     * @param commitDelay 실제 commit 반환 뒤 지연 밀리초, 0 이상
     * @param clock 실제 OffsetDateTime 기록 수신자; null 허용
     * @param afterCommit 갱신 SQL이 있었던 커밋 연결 정리 후 작업; null 허용
     * @return 실제 SQL·commit·close를 그대로 위임하는 시험 전용 연결 proxy
     */
    private Connection wrapConnection(
            Connection real,
            long queryDelay,
            long commitDelay,
            AtomicReference<OffsetDateTime> clock,
            Runnable afterCommit) {
        var committed = new AtomicBoolean();
        var renewed = new AtomicBoolean();
        return (Connection)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            try {
                                Object result = method.invoke(real, args);
                                if (method.getName().equals("commit")) {
                                    committed.set(true);
                                    TimeUnit.MILLISECONDS.sleep(commitDelay);
                                }
                                // manager가 실제 TX 자원을 정리한 뒤에만 별도 서비스의 자체 TX를 시작한다.
                                if (method.getName().equals("close")
                                        && committed.compareAndSet(true, false)
                                        && renewed.get()
                                        && afterCommit != null) afterCommit.run();
                                if (result instanceof Statement statement) {
                                    String preparedSql =
                                            args != null
                                                            && args.length > 0
                                                            && args[0] instanceof String sql
                                                    ? sql
                                                    : null;
                                    Class<?> type =
                                            result instanceof java.sql.PreparedStatement
                                                    ? java.sql.PreparedStatement.class
                                                    : Statement.class;
                                    return Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {type},
                                            (statementProxy, action, parameters) -> {
                                                try {
                                                    Object value =
                                                            action.invoke(statement, parameters);
                                                    String sql =
                                                            preparedSql != null
                                                                    ? preparedSql
                                                                    : parameters != null
                                                                                    && parameters
                                                                                                    .length
                                                                                            > 0
                                                                                    && parameters[0]
                                                                                            instanceof
                                                                                            String
                                                                                                    text
                                                                            ? text
                                                                            : "";
                                                    if (action.getName().startsWith("execute")
                                                            && sql.startsWith(
                                                                    "UPDATE public.grade_job SET"
                                                                            + " lease_until="))
                                                        renewed.set(true);
                                                    if (action.getName().equals("executeQuery")
                                                            && sql.equals(
                                                                    "SELECT clock_timestamp()")
                                                            && value instanceof ResultSet rows) {
                                                        TimeUnit.MILLISECONDS.sleep(queryDelay);
                                                        return Proxy.newProxyInstance(
                                                                getClass().getClassLoader(),
                                                                new Class<?>[] {ResultSet.class},
                                                                (rowProxy, access, fields) -> {
                                                                    try {
                                                                        Object cell =
                                                                                access.invoke(
                                                                                        rows,
                                                                                        fields);
                                                                        if (clock != null
                                                                                && cell
                                                                                        instanceof
                                                                                        OffsetDateTime
                                                                                                time)
                                                                            clock.set(time);
                                                                        return cell;
                                                                    } catch (
                                                                            InvocationTargetException
                                                                                    failure) {
                                                                        throw failure.getCause();
                                                                    }
                                                                });
                                                    }
                                                    return value;
                                                } catch (InvocationTargetException failure) {
                                                    throw failure.getCause();
                                                }
                                            });
                                }
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }
}
