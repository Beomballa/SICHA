package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.GradeCompletionService;
import com.reasoning.common.grading.service.GradeCompletionService.CompletionReceipt;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** 실제 고정 PG16.10·V21·전체 사본·설치·합성 토큰·실제 AES-GCM 완료 원자성을 검사한다. 모델 품질·HTTP·복구 구현 증거가 아니다. */
class GradeCompletionServiceIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static InstalledRuntimeManifestVerifier installation;
    private static long runtime;
    private static CryptoService crypto;
    private static final String CODE = "COMPLETION_SYNTHETIC";
    private static final String VERSION = "a".repeat(64);
    private static final String CANARY = "RAW_COMPLETION_CANARY_NOT_PUBLIC";
    private GradeWorkerCredentials credentials;
    private VerifiedWorker worker;
    private VerifiedWorker otherWorker;
    private GradeCompletionService service;
    private GradeStartService starter;
    private FrozenSnapshot frozen;
    private long creator;
    private long story;
    private long version;
    private long batch;
    private long job;
    private UUID jobKey;
    private String workerKey;
    private byte[] secret;

    /** 실제 설치 지문과 테스트 개인 키 파일은 SQL 밖에서만 생성한다. */
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
        var dictionary = new GradeDictionary(CODE, List.of(new Term("ONE", "개념", "합성")));
        var settings =
                new LocalSemanticEngine.Settings(
                        URI.create("http://127.0.0.1:9"),
                        "qwen3:8b",
                        VERSION,
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
        properties.setCryptoKeyFile(TestKeys.create((byte) 21));
        properties.setSearchKeyFile(TestKeys.create((byte) 22));
        properties.setLimitKeyFile(TestKeys.create((byte) 23));
        crypto = new CryptoService(properties);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 각 작업은 실제 부모·전체 JSONB·권한·임대를 가진 독립 합성 fixture다. 첫 시도는 실제 START만 예약한다. */
    @BeforeEach
    void fixtures() {
        jdbc.update(
                "UPDATE public.grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtime);
        workerKey = token("WORKER");
        secret = secret();
        byte[] otherSecret = secret();
        credentials =
                new GradeWorkerCredentials(
                        List.of(
                                new Registration(
                                        workerKey,
                                        CommonUtil.sha256(secret),
                                        Set.of(CODE),
                                        Set.of(Action.START, Action.COMPLETE)),
                                new Registration(
                                        token("OTHER"),
                                        CommonUtil.sha256(otherSecret),
                                        Set.of(CODE),
                                        Set.of(Action.START, Action.COMPLETE))));
        worker = credentials.authenticate(header(secret));
        otherWorker = credentials.authenticate(header(otherSecret));
        service = service(jdbc, manager, credentials);
        starter = new GradeStartService(jdbc, manager, credentials, Map.of(CODE, installation));
        creator =
                id(
                        "INSERT INTO public.admin_account(account_key,can_review,can_manage) VALUES"
                                + " (?,true,true) RETURNING id",
                        UUID.randomUUID());
        jdbc.update(
                """
                INSERT INTO public.admin_credential(account_id,login_cipher,login_hash,password_hash,
                    mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)
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
                        """
                        INSERT INTO public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'합성 전체 사본','RULE_20260924',?,?) RETURNING id
                        """,
                        story,
                        creator,
                        creator);
        ObjectNode payload = FrozenSnapshotContractTest.complete();
        payload.put("storyCode", code);
        ((ObjectNode) payload.path("sections").path("answer"))
                .put("methodAnswer", "PRIVATE_ANSWER_CANARY");
        frozen = FrozenSnapshotCodec.freeze(payload);
        long snapshot =
                id(
                        """
                        INSERT INTO public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                        VALUES (?,0,?::jsonb,?,?) RETURNING id
                        """,
                        version,
                        text(frozen.payload()),
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
                        INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,
                            state,input_hash,config_hash,rubric_hash,accepted_at,deadline_at,worker_key,lease_gen,lease_until)
                        SELECT ?,?,?,?,'FULL',1,'RUNNING',?,?,?,t.n,t.n+interval '120 seconds',?,1,t.n+interval '30 seconds'
                        FROM t RETURNING id
                        """,
                        jobKey,
                        snapshot,
                        runtime,
                        batch,
                        frozen.inputHash("FULL"),
                        installation.configHash(),
                        frozen.rubricHash(),
                        workerKey);
        assertThat(starter.start(worker, jobKey, 1).attemptNo()).isEqualTo(1);
    }

    @Test
    void completeCalculatesServerScoresAndEncryptsOnlyPrivateDetails() {
        var before = row();
        CompletionReceipt receipt = complete(normal(true));
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.state()).isEqualTo("COMPLETED");
        assertThat(receipt.outcome()).isEqualTo("COMPLETE");
        assertThat(receipt.retryScheduled()).isFalse();
        JsonNode summary = summary();
        assertThat(summary.path("baseScore").intValue()).isEqualTo(100);
        assertThat(summary.path("success").booleanValue()).isTrue();
        assertThat(summary.path("expectationMatched").booleanValue()).isTrue();
        assertThat(summary.path("providerVersionMatched").booleanValue()).isTrue();
        assertBudget(before);
        assertThat(attempt().get("state")).isEqualTo("SUCCEEDED");
        assertThat(attempt().get("ended_at")).isNotNull();
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().get("event_kind")).isEqualTo("COMPLETE_APPLIED");
        assertThat(row().get("worker_key")).isNull();
        assertThat(row().get("lease_until")).isNull();
        String semantic = crypto.decrypt(cipher(attempt(), "output_cipher"), outputAad());
        assertThat(SnapshotJson.encode(parse(semantic)))
                .isEqualTo(SnapshotJson.encode(normal(true).path("semantic")));
        String result = crypto.decrypt(cipher(row(), "result_cipher"), resultAad());
        assertThat(parse(result).path("baseResult").path("baseScore").intValue()).isEqualTo(100);
        assertThat(attempt().get("output_hash")).isEqualTo(SnapshotJson.hash(parse(semantic)));
        assertThat(row().get("result_hash")).isEqualTo(SnapshotJson.hash(parse(result)));
        assertSafe(receipt);
    }

    @Test
    void normalIncorrectMismatchIsCompleteNotRetryOrInventedZero() {
        CompletionReceipt receipt = complete(normal(false));
        assertThat(receipt.state()).isEqualTo("COMPLETED");
        assertThat(receipt.outcome()).isEqualTo("COMPLETE");
        assertThat(receipt.retryScheduled()).isFalse();
        assertThat(summary().path("baseScore").intValue()).isEqualTo(25);
        assertThat(summary().path("success").booleanValue()).isFalse();
        assertThat(summary().path("expectationMatched").booleanValue()).isFalse();
        assertThat(row().get("error_code")).isNull();
        assertThat(events()).hasSize(1);
    }

    @Test
    void absentVersionIsHonestUnknownGradeButNotFixtureAdoption() {
        CompletionReceipt receipt =
                service.complete(
                        worker, jobKey, 1, 1, null, null, text(normal(true)), UUID.randomUUID());
        assertThat(receipt.outcome()).isEqualTo("COMPLETE");
        assertThat(summary().path("baseScore").intValue()).isEqualTo(100);
        assertThat(summary().path("expectationMatched").booleanValue()).isTrue();
        assertThat(summary().path("providerVersionMatched").booleanValue()).isFalse();
        assertThat(summary().path("fixtureAdoptionEligible").booleanValue()).isFalse();
        assertThat(attempt().get("observed_version")).isNull();
    }

    @Test
    void mismatchedVersionIsPreservedWithoutAliasRepairOrApproval() {
        String actual = VERSION + "-different";
        var receipt =
                service.complete(
                        worker,
                        jobKey,
                        1,
                        1,
                        actual,
                        "ref_12345678",
                        text(normal(true)),
                        UUID.randomUUID());
        assertThat(receipt.outcome()).isEqualTo("COMPLETE");
        assertThat(attempt().get("observed_version")).isEqualTo(actual);
        assertThat(summary().path("providerVersionMatched").booleanValue()).isFalse();
        assertThat(summary().path("fixtureAdoptionEligible").booleanValue()).isFalse();
    }

    @Test
    void realGcmRejectsWrongJobAttemptFieldAndTampering() {
        complete(normal(true));
        String output = cipher(attempt(), "output_cipher");
        for (String aad :
                List.of(
                        "grade_attempt/" + (job + 1) + "/1/output/v1",
                        "grade_attempt/" + job + "/2/output/v1",
                        "grade_attempt/" + job + "/1/result/v1",
                        resultAad())) {
            assertThatThrownBy(() -> crypto.decrypt(output, aad)).hasMessage("AUTH_UNAVAILABLE");
        }
        String[] parts = output.split("\\.");
        byte[] encrypted = Base64.getUrlDecoder().decode(parts[3]);
        encrypted[0] ^= 1;
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        assertThatThrownBy(() -> crypto.decrypt(String.join(".", parts), outputAad()))
                .hasMessage("AUTH_UNAVAILABLE");
        assertThatThrownBy(() -> crypto.decrypt(cipher(row(), "result_cipher"), outputAad()))
                .hasMessage("AUTH_UNAVAILABLE");
    }

    @Test
    void errorsRetryTwoTimesThenFailWithoutRefundOrResult() {
        var before = row();
        for (int number = 1; number <= 3; number++) {
            var receipt =
                    service.complete(
                            worker,
                            jobKey,
                            number,
                            number,
                            null,
                            null,
                            text(error("ERROR", "ENGINE_TIMEOUT")),
                            UUID.randomUUID());
            assertThat(receipt.state()).isEqualTo(number < 3 ? "QUEUED" : "FAILED");
            assertThat(receipt.retryScheduled()).isEqualTo(number < 3);
            assertThat(receipt.outcome()).isEqualTo(number < 3 ? null : "SYSTEM_ERROR");
            assertThat(((Number) row().get("call_count")).intValue()).isEqualTo(number);
            assertThat(row().get("deadline_at")).isEqualTo(before.get("deadline_at"));
            assertThat(row().get("accepted_at")).isEqualTo(before.get("accepted_at"));
            assertThat(row().get("result_data")).isNull();
            assertThat(row().get("result_cipher")).isNull();
            assertThat(row().get("result_hash")).isNull();
            assertThat(
                            jdbc.queryForMap(
                                            "SELECT * FROM public.grade_attempt WHERE job_id=? AND"
                                                    + " attempt_no=?",
                                            job,
                                            number)
                                    .get("state"))
                    .isEqualTo("FAILED");
            if (number < 3) reserveNext(number + 1);
        }
        assertThat(row().get("error_code")).isEqualTo("ENGINE_TIMEOUT");
        assertThat(events()).hasSize(3);
    }

    @Test
    void unresolvedFailsAttemptWithoutPartialScore() {
        var receipt = complete(error("UNRESOLVED", "UNRESOLVED_REASONING"));
        assertThat(receipt.state()).isEqualTo("QUEUED");
        assertThat(receipt.reason()).isEqualTo("UNRESOLVED_REASONING");
        assertThat(receipt.outcome()).isNull();
        assertThat(attempt().get("output_cipher")).isNull();
        assertThat(row().get("result_data")).isNull();
    }

    @Test
    void responseLossReturnsExactOriginalAfterCreatorEpochAndDeadlineChanges() {
        ObjectNode body = normal(true);
        CompletionReceipt first = complete(body);
        var beforeJob = row();
        var beforeAttempt = attempt();
        var beforeEvents = events();
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        jdbc.update(
                "UPDATE public.grade_runtime SET epoch=1,state='SUSPENDED' WHERE id=?", runtime);
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                UPDATE public.grade_job SET accepted_at=t.n-interval '120 seconds',deadline_at=t.n,
                    lease_gen=99 FROM t WHERE id=?
                """,
                job);
        var changedJob = row();
        CompletionReceipt replay =
                service.complete(
                        worker,
                        jobKey,
                        1,
                        1,
                        VERSION,
                        "ref_12345678",
                        text(body),
                        UUID.randomUUID());
        assertThat(replay).isEqualTo(first);
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(attempt()).usingRecursiveComparison().isEqualTo(beforeAttempt);
        assertThat(events()).isEqualTo(beforeEvents);
        assertThat(row()).usingRecursiveComparison().isEqualTo(changedJob);
        assertThat(changedJob.get("result_cipher")).isEqualTo(beforeJob.get("result_cipher"));
    }

    @Test
    void retryReceiptReplayIgnoresNewLeaseButDoesNotRevealForeignReceipt() {
        ObjectNode body = error("ERROR", "ENGINE_UNAVAILABLE");
        CompletionReceipt first = complete(body);
        reserveNext(2);
        var jobBefore = row();
        assertThat(
                        service.complete(
                                worker,
                                jobKey,
                                1,
                                1,
                                VERSION,
                                "ref_12345678",
                                text(body),
                                UUID.randomUUID()))
                .isEqualTo(first);
        assertThat(row()).isEqualTo(jobBefore);
        assertThat(events()).hasSize(1);
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        otherWorker,
                                        jobKey,
                                        1,
                                        1,
                                        VERSION,
                                        "ref_12345678",
                                        text(body),
                                        UUID.randomUUID()))
                .hasMessage("WORKER_NOT_AUTHORIZED");
        assertThat(events()).hasSize(1);
    }

    @Test
    void conflictingBodyBeforeCurrentGuardCannotOverwriteReceipt() {
        complete(normal(true));
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        unchanged(() -> complete(normal(false)), "CALLBACK_CONFLICT");
        unchanged(
                () ->
                        service.complete(
                                worker,
                                jobKey,
                                1,
                                1,
                                null,
                                "ref_12345678",
                                text(normal(true)),
                                UUID.randomUUID()),
                "CALLBACK_CONFLICT");
    }

    @Test
    void objectKeyReorderingPreservesCommandButArrayOrderDoesNot() {
        ObjectNode body = normal(true);
        CompletionReceipt first = complete(body);
        String reordered = "{\"semantic\":" + body.get("semantic") + ",\"kind\":\"COMPLETE\"}";
        assertThat(
                        service.complete(
                                worker,
                                jobKey,
                                1,
                                1,
                                VERSION,
                                "ref_12345678",
                                reordered,
                                UUID.randomUUID()))
                .isEqualTo(first);
        var items =
                (com.fasterxml.jackson.databind.node.ArrayNode) body.path("semantic").path("items");
        JsonNode item = items.remove(0);
        items.add(item);
        unchanged(() -> complete(body), "CALLBACK_CONFLICT");
    }

    @Test
    void foreignProofWorkerActionAndRuntimeScopeAreDeniedBeforeWrites() {
        unchanged(
                () ->
                        service.complete(
                                otherWorker,
                                jobKey,
                                1,
                                1,
                                VERSION,
                                null,
                                text(normal(true)),
                                UUID.randomUUID()),
                "WORKER_NOT_AUTHORIZED");
        for (Set<Action> actions : List.of(Set.of(Action.START), Set.of(Action.COMPLETE))) {
            String code = actions.contains(Action.START) ? CODE : "OTHER_RUNTIME";
            var registry =
                    new GradeWorkerCredentials(
                            List.of(
                                    new Registration(
                                            workerKey,
                                            CommonUtil.sha256(secret),
                                            Set.of(code),
                                            actions)));
            var foreign = registry.authenticate(header(secret));
            unchanged(
                    () ->
                            service.complete(
                                    foreign,
                                    jobKey,
                                    1,
                                    1,
                                    VERSION,
                                    null,
                                    text(normal(true)),
                                    UUID.randomUUID()),
                    "WORKER_NOT_AUTHORIZED");
            var actual = service(jdbc, manager, registry);
            unchanged(
                    () ->
                            actual.complete(
                                    foreign,
                                    jobKey,
                                    1,
                                    1,
                                    VERSION,
                                    null,
                                    text(normal(true)),
                                    UUID.randomUUID()),
                    "WORKER_NOT_AUTHORIZED");
        }
    }

    @Test
    void lateOldLeaseRejectsWithAuditOnly() {
        jdbc.update("UPDATE public.grade_job SET lease_gen=2 WHERE id=?", job);
        rejection("STALE_LEASE");
    }

    @Test
    void terminalReservationWithoutReceiptRejectsWithAuditOnly() {
        jdbc.update(
                "UPDATE public.grade_attempt SET state='EXPIRED',ended_at=clock_timestamp() WHERE"
                        + " job_id=?",
                job);
        jdbc.update(
                "UPDATE public.grade_job SET state='FAILED',worker_key=NULL,lease_until=NULL WHERE"
                        + " id=?",
                job);
        rejection("TERMINAL");
    }

    @Test
    void sourceRevocationRejectsWithAuditOnly() {
        jdbc.update(
                "UPDATE public.story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                story,
                creator);
        rejection("SOURCE_REVOKED");
    }

    @Test
    void creatorRevocationRejectsWithAuditOnly() {
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        rejection("SOURCE_REVOKED");
    }

    @Test
    void epochChangeRejectsWithAuditOnly() {
        jdbc.update("UPDATE public.grade_runtime SET epoch=1 WHERE id=?", runtime);
        rejection("RUNTIME_EPOCH_CHANGED");
    }

    /** TEST epoch 분류 추가가 기존 BATCH의 stale 우선순위를 바꾸지 않는다. */
    @Test
    void epochChangeDoesNotOverrideStaleBatchLease() {
        jdbc.update("UPDATE public.grade_runtime SET epoch=1 WHERE id=?", runtime);
        jdbc.update("UPDATE public.grade_job SET lease_gen=2 WHERE id=?", job);
        rejection("STALE_LEASE");
    }

    /** 이미 종료한 BATCH는 epoch 변경에도 신규 실행 결과로 재분류하지 않는다. */
    @Test
    void epochChangeDoesNotOverrideTerminalBatchReservation() {
        jdbc.update("UPDATE public.grade_runtime SET epoch=1 WHERE id=?", runtime);
        jdbc.update(
                "UPDATE public.grade_attempt SET state='EXPIRED',ended_at=clock_timestamp() WHERE"
                        + " job_id=?",
                job);
        jdbc.update(
                "UPDATE public.grade_job SET state='FAILED',worker_key=NULL,lease_until=NULL WHERE"
                        + " id=?",
                job);
        rejection("TERMINAL");
    }

    @Test
    void exactDeadlineRejectsWithAuditOnly() {
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                UPDATE public.grade_job SET accepted_at=t.n-interval '120 seconds',deadline_at=t.n FROM t WHERE id=?
                """,
                job);
        rejection("DEADLINE_EXCEEDED");
    }

    @Test
    void expiredLeaseRejectsWithAuditOnly() {
        jdbc.update("UPDATE public.grade_job SET lease_until=clock_timestamp() WHERE id=?", job);
        rejection("STALE_LEASE");
    }

    @Test
    void batch24HourBoundaryRejectsWithAuditOnly() {
        jdbc.update(
                "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval '24 hours'"
                        + " WHERE id=?",
                batch);
        rejection("DEADLINE_EXCEEDED");
    }

    @Test
    void unknownAttemptOrJobCannotInventParentAudit() {
        unchanged(
                () ->
                        service.complete(
                                worker,
                                jobKey,
                                1,
                                2,
                                VERSION,
                                null,
                                text(normal(true)),
                                UUID.randomUUID()),
                "INVALID_COMPLETION_INPUT");
        unchanged(
                () ->
                        service.complete(
                                worker,
                                UUID.randomUUID(),
                                1,
                                1,
                                VERSION,
                                null,
                                text(normal(true)),
                                UUID.randomUUID()),
                "INVALID_COMPLETION_INPUT");
    }

    @Test
    void malformedClosedResultsUnknownFieldsScoresAndMembershipAreRejected() {
        for (String raw :
                List.of(
                        "null",
                        "{}",
                        "{\"kind\":\"OTHER\"}",
                        "{\"kind\":\"ERROR\",\"errorCode\":\"OTHER\"}",
                        "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\",\"score\":0}",
                        "{\"kind\":\"UNRESOLVED\",\"errorCode\":\"UNRESOLVED_REASONING\",\"semantic\":null}",
                        "{\"kind\":\"COMPLETE\",\"semantic\":{\"status\":\"UNRESOLVED\"}}",
                        "{\"kind\":\"ERROR\",\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                        text(normal(true)) + " {}")) {
            unchanged(
                    () ->
                            service.complete(
                                    worker, jobKey, 1, 1, VERSION, null, raw, UUID.randomUUID()),
                    "INVALID_COMPLETION_INPUT");
        }
        ObjectNode scored = normal(true);
        ((ObjectNode) scored.path("semantic")).put("score", 100);
        unchanged(() -> complete(scored), "INVALID_COMPLETION_INPUT");
        ObjectNode unknown = normal(true);
        ((ObjectNode) unknown.path("semantic").path("items").get(1).path("claims").get(0))
                .put("code", "OTHER");
        unchanged(() -> complete(unknown), "INVALID_COMPLETION_INPUT");
        ObjectNode invalidSpan = normal(true);
        ((ObjectNode)
                        invalidSpan
                                .path("semantic")
                                .path("items")
                                .get(1)
                                .path("claims")
                                .get(0)
                                .path("spans")
                                .get(0))
                .put("end", 999);
        unchanged(() -> complete(invalidSpan), "INVALID_COMPLETION_INPUT");
        ObjectNode missing = normal(true);
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing.path("semantic").path("items"))
                .remove(0);
        unchanged(() -> complete(missing), "INVALID_COMPLETION_INPUT");
    }

    @Test
    void boundedObservationAndReferenceAndServerRequestAreStrict() {
        unchanged(
                () ->
                        service.complete(
                                worker,
                                jobKey,
                                1,
                                1,
                                "😀".repeat(161),
                                null,
                                text(normal(true)),
                                UUID.randomUUID()),
                "INVALID_COMPLETION_INPUT");
        for (String ref : List.of("short", "person@example.com", "white space", "é".repeat(8))) {
            unchanged(
                    () ->
                            service.complete(
                                    worker,
                                    jobKey,
                                    1,
                                    1,
                                    VERSION,
                                    ref,
                                    text(normal(true)),
                                    UUID.randomUUID()),
                    "INVALID_COMPLETION_INPUT");
        }
        unchanged(
                () ->
                        service.complete(
                                worker,
                                jobKey,
                                1,
                                1,
                                VERSION,
                                null,
                                text(normal(true)),
                                new UUID(0, 0)),
                "INVALID_COMPLETION_INPUT");
    }

    @Test
    void concurrentDuplicateCompleteHasOneEventAndOneImmutableReceipt() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var task =
                    (java.util.concurrent.Callable<CompletionReceipt>)
                            () -> {
                                ready.countDown();
                                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                                return complete(normal(true));
                            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(events()).hasSize(1);
            assertThat(((Number) row().get("call_count")).intValue()).isEqualTo(1);
        }
    }

    @Test
    void eventInsertFailureRollsBackEveryResultAttemptAndReceipt() {
        trigger("grade_event", "INSERT", "RAISE EXCEPTION 'RAW_AUDIT_FAILURE_CANARY';");
        try {
            unchanged(() -> complete(normal(true)), "COMPLETION_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_event");
        }
    }

    @Test
    void jobResultUpdateFailureCreatesNoEventAndRollsBackAttempt() {
        trigger("grade_job", "UPDATE", "RAISE EXCEPTION 'RAW_RESULT_FAILURE_CANARY';");
        try {
            unchanged(() -> complete(normal(true)), "COMPLETION_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_job");
        }
    }

    @Test
    void updateTriggerCrossingDeadlineAbortsRatherThanCommittingLate() {
        nearDeadline();
        trigger("grade_job", "UPDATE", "PERFORM pg_sleep(1.0);");
        try {
            unchanged(() -> complete(normal(true)), "COMPLETION_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_job");
        }
    }

    @Test
    void attemptTriggerCrossingLeaseAbortsRatherThanCommittingLate() {
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()+interval '0.8 seconds'"
                        + " WHERE id=?",
                job);
        trigger("grade_attempt", "UPDATE", "PERFORM pg_sleep(1.0);");
        try {
            unchanged(() -> complete(normal(true)), "COMPLETION_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_attempt");
        }
    }

    @Test
    void auditTriggerCrossingDeadlineAlsoRollsBackAllWrites() {
        nearDeadline();
        trigger("grade_event", "INSERT", "PERFORM pg_sleep(1.0);");
        try {
            unchanged(() -> complete(normal(true)), "COMPLETION_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_event");
        }
    }

    @Test
    void callerTransactionAndForeignDatasourceCannotAcquireAuthority() {
        var transaction = new TransactionTemplate(manager);
        unchanged(
                () -> transaction.execute(status -> complete(normal(true))),
                "COMPLETION_REQUIRES_SEPARATE_TRANSACTION");
        var foreignManager =
                new DataSourceTransactionManager(GradeSchemaIT.jdbc(postgres).getDataSource());
        var wrong = service(jdbc, foreignManager, credentials);
        unchanged(
                () ->
                        wrong.complete(
                                worker,
                                jobKey,
                                1,
                                1,
                                VERSION,
                                null,
                                text(normal(true)),
                                UUID.randomUUID()),
                "SOURCE_REQUIRES_WRITE_READ_COMMITTED");
    }

    @Test
    void inputErrorFixtureCannotAcceptProviderCompleteOrProviderError() {
        jdbc.update(
                "UPDATE public.grade_job SET sample_code='INPUT',input_hash=? WHERE id=?",
                frozen.inputHash("INPUT"),
                job);
        unchanged(() -> complete(normal(true)), "INVALID_COMPLETION_INPUT");
        unchanged(() -> complete(error("ERROR", "ENGINE_UNAVAILABLE")), "INVALID_COMPLETION_INPUT");
    }

    @Test
    void storedHashMismatchIsNotMisclassifiedAsStaleSource() {
        jdbc.update("UPDATE public.grade_job SET input_hash=? WHERE id=?", "0".repeat(64), job);
        unchanged(() -> complete(normal(true)), "START_NOT_CURRENT");
    }

    @Test
    void runtimeManifestMismatchIsNotSilentlyClassifiedAsEpochRejection() {
        String original =
                jdbc.queryForObject(
                        "SELECT config_data::text FROM public.grade_runtime WHERE id=?",
                        String.class,
                        runtime);
        jdbc.update(
                "UPDATE public.grade_runtime SET"
                    + " config_data=jsonb_set(config_data,'{modelVersion}','\"different\"'::jsonb)"
                    + " WHERE id=?",
                runtime);
        try {
            unchanged(() -> complete(normal(true)), "INSTALLED_RUNTIME_MISMATCH");
        } finally {
            jdbc.update(
                    "UPDATE public.grade_runtime SET config_data=?::jsonb WHERE id=?",
                    original,
                    runtime);
        }
    }

    @Test
    void postTriggerResultHashMutationRollsBackInsteadOfReturningForgedSuccess() {
        trigger("grade_job", "UPDATE", "NEW.result_hash := repeat('0',64);");
        try {
            unchanged(() -> complete(normal(true)), "COMPLETION_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_job");
        }
    }

    @Test
    void postTriggerSampleIdentityMutationRollsBackAllCompletionWrites() {
        trigger("grade_job", "UPDATE", "NEW.sample_code := 'INPUT';");
        try {
            unchanged(() -> complete(normal(true)), "COMPLETION_STORAGE_FAILURE");
        } finally {
            dropTrigger("grade_job");
        }
    }

    @Test
    void mfaRevocationUsesActualCurrentCredentialNotReservationMetadata() {
        jdbc.update(
                "UPDATE public.admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                creator);
        rejection("SOURCE_REVOKED");
    }

    @Test
    void realCreatorRootLockWaitChecksFreshClockAfterLeaseExpires() throws Exception {
        try (var holder = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            try (var query =
                    holder.prepareStatement(
                            "SELECT id FROM public.admin_account WHERE id=? FOR UPDATE")) {
                query.setLong(1, creator);
                query.executeQuery().close();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> complete(normal(true)));
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
                var receipt = response.get(5, TimeUnit.SECONDS);
                assertThat(receipt.accepted()).isFalse();
                assertThat(receipt.reason()).isEqualTo("STALE_LEASE");
                assertThat(attempt().get("state")).isEqualTo("RUNNING");
                assertThat(attempt().get("completion_data")).isNull();
                assertThat(row().get("result_data")).isNull();
                assertThat(events()).hasSize(1);
            } finally {
                holder.rollback();
            }
        }
    }

    /** 직접 작성한 의미 판정이다. 기대 점수·경로·fixture 라우트를 읽어 명제를 만들지 않는다. */
    private ObjectNode normal(boolean met) {
        ObjectNode semantic = object().put("formatNo", 1).put("status", "COMPLETE");
        var items = semantic.putArray("items");
        ObjectNode culprit = items.addObject().put("rubricCode", "CULPRIT").put("reason", CANARY);
        culprit.putArray("claims");
        var contradictions = culprit.putArray("contradictions");
        for (String code : List.of("CONTRADICT_CULPRIT", "UNSUPPORTED_ACCOMPLICE")) {
            contradictions.addObject().put("code", code).put("met", false).putArray("spans");
        }
        for (String code : List.of("METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            ObjectNode item = items.addObject().put("rubricCode", code).put("reason", CANARY);
            item.putArray("contradictions");
            var claim = item.putArray("claims").addObject().put("code", "CLAIM").put("met", met);
            var spans = claim.putArray("spans");
            if (met) spans.addObject().put("field", "method").put("start", 0).put("end", 1);
        }
        ObjectNode result = object().put("kind", "COMPLETE");
        result.set("semantic", semantic);
        return result;
    }

    private ObjectNode error(String kind, String reason) {
        return object().put("kind", kind).put("errorCode", reason);
    }

    private CompletionReceipt complete(JsonNode result) {
        return service.complete(
                worker, jobKey, 1, 1, VERSION, "ref_12345678", text(result), UUID.randomUUID());
    }

    /** 후속 임대는 coordinator 구현이 아니라 테스트 전용 사실 구성이다. START가 실제 새 시도를 예약한다. */
    private void reserveNext(int number) {
        jdbc.update(
                """
                UPDATE public.grade_job SET state='RUNNING',worker_key=?,lease_gen=?,
                    lease_until=clock_timestamp()+interval '30 seconds' WHERE id=?
                """,
                workerKey,
                number,
                job);
        assertThat(starter.start(worker, jobKey, number).attemptNo()).isEqualTo(number);
    }

    /** 거절은 감사 한 건만 추가하고 작업·시도의 원래 전체 행을 보존해야 한다. */
    private void rejection(String reason) {
        var before = row();
        var child = attempt();
        var receipt = complete(normal(true));
        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.reason()).isEqualTo(reason);
        assertThat(receipt.state()).isEqualTo(before.get("state"));
        assertThat(row()).isEqualTo(before);
        assertThat(attempt()).isEqualTo(child);
        assertThat(events()).hasSize(1);
        assertThat(events().getFirst().get("event_kind")).isEqualTo("COMPLETE_REJECTED");
        assertThat(events().getFirst().get("detail").toString()).doesNotContain(CANARY);
    }

    /** 실패한 TX는 감사까지 모두 원상 보존하고 예외 원인을 제거해야 한다. */
    private void unchanged(Runnable operation, String message) {
        var before = row();
        var children =
                jdbc.queryForList(
                        "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY attempt_no",
                        job);
        var audits = events();
        assertThatThrownBy(operation::run).hasMessage(message).hasNoCause();
        assertThat(row()).usingRecursiveComparison().isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM public.grade_attempt WHERE job_id=? ORDER BY"
                                        + " attempt_no",
                                job))
                .usingRecursiveComparison()
                .isEqualTo(children);
        assertThat(events()).isEqualTo(audits);
    }

    private void assertBudget(Map<String, Object> before) {
        for (String key : List.of("accepted_at", "deadline_at", "call_count", "lease_gen")) {
            assertThat(row().get(key)).as(key).isEqualTo(before.get(key));
        }
    }

    /** private 암호문 외의 모든 새 공개·감사·요약 경계에서 원문 canary가 없어야 한다. */
    private void assertSafe(CompletionReceipt receipt) {
        String safe =
                row().get("result_data")
                        + " "
                        + attempt().get("completion_data")
                        + " "
                        + events()
                        + " "
                        + receipt;
        assertThat(safe)
                .doesNotContain(
                        CANARY,
                        "PRIVATE_ANSWER_CANARY",
                        "SELECTED_REPORT",
                        "기대 근거",
                        "claims",
                        "spans");
        assertThat(cipher(attempt(), "output_cipher")).doesNotContain(CANARY);
        assertThat(cipher(row(), "result_cipher")).doesNotContain(CANARY);
    }

    private GradeCompletionService service(
            JdbcTemplate connection,
            DataSourceTransactionManager tx,
            GradeWorkerCredentials registry) {
        return new GradeCompletionService(
                connection,
                tx,
                registry,
                Map.of(CODE, installation),
                crypto,
                "SYNTHETIC_COORDINATOR");
    }

    private String outputAad() {
        return "grade_attempt/" + job + "/1/output/v1";
    }

    private String resultAad() {
        return "grade_job/" + job + "/result/v1";
    }

    private String cipher(Map<String, Object> row, String field) {
        return new String((byte[]) row.get(field), StandardCharsets.UTF_8);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM public.grade_job WHERE id=?", job);
    }

    private Map<String, Object> attempt() {
        return jdbc.queryForMap(
                "SELECT * FROM public.grade_attempt WHERE job_id=? AND attempt_no=1", job);
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList(
                "SELECT * FROM public.grade_event WHERE job_id=? ORDER BY id", job);
    }

    private JsonNode summary() {
        return parse(row().get("result_data").toString());
    }

    private JsonNode parse(String text) {
        return SnapshotJson.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private String text(JsonNode node) {
        return new String(SnapshotJson.encode(node), StandardCharsets.UTF_8);
    }

    private ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String token(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private byte[] secret() {
        return ByteBuffer.allocate(32)
                .putLong(UUID.randomUUID().getMostSignificantBits())
                .putLong(UUID.randomUUID().getLeastSignificantBits())
                .putLong(1)
                .putLong(2)
                .array();
    }

    private String header(byte[] secret) {
        return "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** 폐기형 PG 테스트 트리거로 실제 SQL 실패·지연을 발생시킨다. */
    private void trigger(String table, String event, String body) {
        jdbc.execute(
                "CREATE FUNCTION public.completion_test_trigger() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN "
                        + body
                        + " RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER completion_test_trigger BEFORE "
                        + event
                        + " ON public."
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION public.completion_test_trigger()");
    }

    private void dropTrigger(String table) {
        jdbc.execute("DROP TRIGGER completion_test_trigger ON public." + table);
        jdbc.execute("DROP FUNCTION public.completion_test_trigger()");
    }

    /** 스키마의 고정 120초 간격을 보존한 실제 근접 마감을 구성한다. */
    private void nearDeadline() {
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                UPDATE public.grade_job SET accepted_at=t.n-interval '119.2 seconds',
                    deadline_at=t.n+interval '0.8 seconds' FROM t WHERE id=?
                """,
                job);
    }

    /** 실제 PG 활동에서 루트 잠금 대기를 확인하며 호스트 시간을 업무 권위로 쓰지 않는다. */
    private void awaitRootWait() throws Exception {
        try (var monitor = postgres.createConnection("");
                var query = monitor.createStatement()) {
            monitor.setAutoCommit(true);
            for (int index = 0; index < 100; index++) {
                try (var rows =
                        query.executeQuery(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                    + " datname=current_database() AND wait_event_type='Lock' AND"
                                    + " position('FROM public.admin_account WHERE id=' in"
                                    + " query)>0)")) {
                    if (rows.next() && rows.getBoolean(1)) return;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new AssertionError("실제 루트 잠금 대기 미관찰");
    }
}
