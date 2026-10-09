package com.reasoning.common.grading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 고정 폐기형 PG16.10·최신 실제 V21·독립 합성 의미 튜플로 수동 역사 집계를 검사한다. 모델·사람·운영 승인 증거가 아니다. */
class GradeBatchAggregationIT {
    private static final String CODE = "AGGREGATION_SYNTHETIC";
    private static final String VERSION = "a".repeat(64);
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static CryptoService crypto;
    private static InstalledRuntimeManifestVerifier installation;
    private static long runtime;
    private GradeWorkerCredentials credentials;
    private GradeWorkerCredentials.VerifiedWorker worker;
    private GradeCoordinatorService coordinator;
    private GradeStartService starter;
    private GradeCompletionService completion;
    private GradeLeaseRepository leases;
    private GradeBatchAggregationService aggregator;
    private String workerKey;
    private long creator;
    private long story;
    private long version;
    private long snapshot;
    private long batch;
    private UUID key;
    private FrozenSnapshot frozen;

    /** 기존 helper의 digest 고정 PostgreSQL16.10과 최신 실제 V21을 사용한다. 키·설치는 SQL 밖에서 조립한다. */
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
        var dictionary =
                new GradeDictionary(CODE, List.of(new GradeDictionary.Term("ONE", "개념", "합성")));
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
                        new InstalledRuntimeManifestVerifier.InstalledProfile(
                                CODE, "LOCAL", "1", "RULE_20260924"));
        runtime =
                new GradeRuntimeRepository(jdbc)
                        .registerRuntime(
                                CODE,
                                installation.configHash(),
                                installation.registrationManifest().toString())
                        .id();
        var properties = new AuthProperties();
        properties.setCryptoKeyFile(TestKeys.create((byte) 121));
        properties.setSearchKeyFile(TestKeys.create((byte) 122));
        properties.setLimitKeyFile(TestKeys.create((byte) 123));
        crypto = new CryptoService(properties);
    }

    /** 폐기형 시험 자원만 닫는다. */
    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 실제 부모·불변 전체 payload·모든 STAGED 반복을 준비하며 결과·기대 점수는 작업에 주입하지 않는다. */
    @BeforeEach
    void fixture() {
        jdbc.update(
                "UPDATE public.grade_runtime SET state='AVAILABLE',epoch=0 WHERE id=?", runtime);
        byte[] token =
                ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(1)
                        .putLong(2)
                        .putLong(3)
                        .array();
        workerKey = "AGG_W_" + UUID.randomUUID().toString().replace("-", "");
        credentials =
                new GradeWorkerCredentials(
                        List.of(
                                new GradeWorkerCredentials.Registration(
                                        workerKey,
                                        CommonUtil.sha256(token),
                                        Set.of(CODE),
                                        Set.of(
                                                GradeWorkerCredentials.Action.START,
                                                GradeWorkerCredentials.Action.COMPLETE,
                                                GradeWorkerCredentials.Action.CLAIM))));
        worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(token));
        var installed = Map.of(CODE, installation);
        coordinator =
                new GradeCoordinatorService(
                        jdbc, manager, credentials, installed, "AGG_TEST_SYSTEM", 1000);
        starter = new GradeStartService(jdbc, manager, credentials, installed);
        completion =
                new GradeCompletionService(
                        jdbc, manager, credentials, installed, crypto, "AGG_TEST_SYSTEM");
        leases = new GradeLeaseRepository(jdbc, manager);
        aggregator = aggregation(installed);
        UUID accountKey = UUID.randomUUID();
        creator =
                id(
                        "INSERT INTO public.admin_account(account_key,can_review) VALUES (?,true)"
                                + " RETURNING id",
                        accountKey);
        jdbc.update(
                """
                INSERT INTO public.admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)
                VALUES (?,'synthetic',?,'synthetic','synthetic',clock_timestamp(),0,clock_timestamp(),'READY')
                """,
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
                        """
                        INSERT INTO public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'합성 전체 사본','RULE_20260924',?,?) RETURNING id
                        """,
                        story,
                        creator,
                        creator);
        ObjectNode payload = FrozenSnapshotContractTest.complete();
        payload.put("storyCode", code);
        for (JsonNode sample : payload.path("resources").path("gradeSamples"))
            ((ObjectNode) sample).put("checkedBy", accountKey.toString());
        frozen = FrozenSnapshotCodec.freeze(payload);
        snapshot =
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
        key = UUID.randomUUID();
        int count = frozen.payload().path("resources").path("gradeSamples").size() * 3;
        batch =
                id(
                        """
                        INSERT INTO public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)
                        VALUES (?,?,?,'REVIEW',?,?,?,?,0,'RUNNING',?,?) RETURNING id
                        """,
                        key,
                        snapshot,
                        runtime,
                        frozen.datasetHash(),
                        frozen.rubricHash(),
                        frozen.payloadHash(),
                        installation.configHash(),
                        count,
                        creator);
        for (JsonNode sample : frozen.payload().path("resources").path("gradeSamples")) {
            String sampleCode = sample.path("code").textValue();
            for (int repeat = 1; repeat <= 3; repeat++)
                id(
                        """
                        INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)
                        VALUES (?,?,?,?,?,?,'STAGED',?,?,?) RETURNING id
                        """,
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        batch,
                        sampleCode,
                        repeat,
                        frozen.inputHash(sampleCode),
                        installation.configHash(),
                        frozen.rubricHash());
        }
    }

    /** 실제 완료 집합은 기대 참/거짓 모두 역사 완료이며 NULL 효력을 유지한다. 기대 음성도 PASS다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void qualifiedWholeSetCompletesWithoutOperationalValidity(boolean correct) {
        finishAll(correct);
        String before = completionEvidence();
        var result = aggregator.aggregate(key);
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.passed()).isEqualTo(correct);
        assertThat(batchRow().get("valid_until")).isNull();
        assertThat(batchRow().get("ended_at")).isNotNull();
        assertThat(completionEvidence()).isEqualTo(before);
        assertThat(issueCount("GRADING")).isEqualTo(correct ? 0 : 1);
        assertThat(issueCount("INFRA")).isZero();
        assertThat(compare(job("ZERO", 1)).comparison()).isEqualTo("PASS");
        assertThat(compare(job("ENGINE", 1)).comparison()).isEqualTo("PASS");
        assertThat(compare(job("INPUT", 1)).comparison()).isEqualTo("PASS");
    }

    /** 부분 정상 불일치는 즉시 단일 불변 지적이며 이후 기술 만료는 INFRA도 보존한다. */
    @Test
    void partialMismatchImmediatelyPersistsAndLaterExpiryRetainsBothKinds() {
        completeOne("FULL", 1, normal(false, false));
        var first = aggregator.aggregate(key);
        assertThat(first.state()).isEqualTo("RUNNING");
        assertThat(issueCount("GRADING")).isEqualTo(1);
        String issue = issueRows();
        aggregator.aggregate(key);
        assertThat(issueRows()).isEqualTo(issue);
        expire();
        assertThat(aggregation(Map.of()).aggregate(key).state()).isEqualTo("FAILED");
        assertThat(issueCount("GRADING")).isEqualTo(1);
        assertThat(issueCount("INFRA")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason_code FROM public.execution_issue WHERE batch_id=?"
                                        + " AND kind='GRADING'",
                                String.class,
                                batch))
                .isEqualTo("EXPECTATION_MISMATCH");
        assertThat(jobRow(job("FULL", 1)).get("state")).isEqualTo("COMPLETED");
    }

    /** 설치 부재는 새 성공이나 가짜 출처 회수가 아니며 인증된 부분 불일치는 보존한다. */
    @Test
    void missingInstallationReportsFixedFailureAndDoesNotLoseKnownMismatch() {
        completeOne("FULL", 1, normal(false, false));
        var result = aggregation(Map.of()).aggregate(key);
        assertThat(result.state()).isEqualTo("RUNNING");
        assertThat(result.reason()).isEqualTo("INSTALLED_RUNTIME_MISMATCH");
        assertThat(result.passed()).isNull();
        assertThat(issueCount("GRADING")).isEqualTo(1);
        finishRemaining(true);
        assertThat(aggregation(Map.of()).aggregate(key).state()).isEqualTo("RUNNING");
        assertThat(batchRow().get("passed_yn")).isNull();
    }

    /** 역사 사본 정책은 현재 초안 정책과 분리되지만 기존 실행 소비자는 여전히 거절한다. */
    @Test
    void savedFactsSurviveChangedDraftPolicyButExecutionConsumerRejects() {
        jdbc.update(
                "UPDATE public.story_version SET policy_code='RULE_20260925' WHERE id=?", version);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            var repository = new GradeFrozenInputRepository(jdbc);
                            assertThat(repository.loadSavedFacts(snapshot).policyCode())
                                    .isEqualTo("RULE_20260924");
                            var root = new GradeSourceRepository(jdbc).lockRoots(job("FULL", 1));
                            assertThatThrownBy(
                                            () ->
                                                    repository.dataset(
                                                            root,
                                                            installation.verify(
                                                                    new GradeRuntimeRepository(jdbc)
                                                                            .getRuntimeDetail(
                                                                                    runtime)
                                                                            .orElseThrow())))
                                    .hasMessage("START_NOT_CURRENT");
                        });
    }

    /** 설치 없이도 실제 세 독립 정비 사유를 적용하고 STAGED 예산은 발명하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"BATCH_EXPIRED", "SOURCE_REVOKED", "RUNTIME_EPOCH_CHANGED"})
    void factualCleanupWithEmptyInstallationPreservesStagedBudgets(String cause) {
        String before = immutableJobs();
        if ("BATCH_EXPIRED".equals(cause)) expire();
        else if ("SOURCE_REVOKED".equals(cause))
            jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        else jdbc.update("UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=?", runtime);
        var result = aggregation(Map.of()).aggregate(key);
        assertThat(result.state())
                .isEqualTo("SOURCE_REVOKED".equals(cause) ? "CANCELLED" : "FAILED");
        assertThat(result.passed()).isNull();
        assertThat(immutableJobs()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_attempt WHERE job_id IN (SELECT"
                                        + " id FROM public.grade_job WHERE batch_id=?)",
                                Long.class,
                                batch))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.test_audit WHERE scope_key=? AND"
                                        + " action='BATCH_CHILD_EFFECT'",
                                Long.class,
                                "batch:" + key))
                .isEqualTo(12);
        assertThat(compare(job("FULL", 1)).failureKind())
                .isEqualTo(GradeBatchComparison.FailureKind.INFRA);
    }

    /** 실제 RUNNING 예약만 정확한 정비 상태로 닫고 세 번째 호출도 집계 전에는 살아 있다. */
    @ParameterizedTest
    @ValueSource(strings = {"BATCH_EXPIRED", "SOURCE_REVOKED", "RUNTIME_EPOCH_CHANGED"})
    void cleanupClosesActualThirdAttemptWithoutBudgetRefund(String cause) {
        UUID target = job("ENGINE", 1);
        coordinator.activate(target);
        for (int attempt = 1; attempt <= 2; attempt++) {
            var lease = reserve(target);
            completion.complete(
                    worker,
                    target,
                    lease.leaseGen(),
                    attempt,
                    VERSION,
                    null,
                    text(error()),
                    UUID.randomUUID());
            due(target);
        }
        var third = reserve(target);
        assertThat(third.leaseGen()).isEqualTo(3);
        assertThat(aggregator.aggregate(key).state()).isEqualTo("RUNNING");
        assertThat(jobRow(target).get("state")).isEqualTo("RUNNING");
        String evidence = immutableJobs();
        if ("BATCH_EXPIRED".equals(cause)) expire();
        else if ("SOURCE_REVOKED".equals(cause))
            jdbc.update("UPDATE public.story_access SET active_yn=false WHERE story_id=?", story);
        else jdbc.update("UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=?", runtime);
        aggregation(Map.of()).aggregate(key);
        assertThat(immutableJobs()).isEqualTo(evidence);
        var attempt =
                jdbc.queryForMap(
                        "SELECT * FROM public.grade_attempt WHERE job_id=? AND attempt_no=3",
                        third.jobId());
        assertThat(attempt.get("state"))
                .isEqualTo("BATCH_EXPIRED".equals(cause) ? "EXPIRED" : "FAILED");
        assertThat(attempt.get("error_code"))
                .isEqualTo("BATCH_EXPIRED".equals(cause) ? "DEADLINE_EXCEEDED" : cause);
        assertThat(attempt.get("completion_data")).isNull();
        assertThat(compare(target).failureKind()).isEqualTo(GradeBatchComparison.FailureKind.INFRA);
    }

    /** 실제 worker 복구는 기대 ENGINE_ERROR 성공이 아니며 기존 영수증은 바꾸지 않는다. */
    @Test
    void recoveryExhaustionIsInfraAndCancelsOtherUnfinishedJobsTruthfully() {
        UUID target = job("FULL", 1);
        coordinator.activate(target);
        var lease = reserve(target);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE job_key=?",
                target);
        var recovery =
                new GradeRecoveryService(
                        jdbc, manager, credentials, Map.of(CODE, installation), "AGG_TEST_SYSTEM");
        recovery.recover(target);
        due(target);
        lease = reserve(target);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE job_key=?",
                target);
        recovery.recover(target);
        due(target);
        lease = reserve(target);
        jdbc.update(
                "UPDATE public.grade_job SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE job_key=?",
                target);
        recovery.recover(target);
        assertThat(compare(target).failureKind()).isEqualTo(GradeBatchComparison.FailureKind.INFRA);
        var result = aggregator.aggregate(key);
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.passed()).isNull();
        assertThat(result.reason()).isEqualTo("COMPARISON_UNAVAILABLE");
        assertThat(issueCount("INFRA")).isEqualTo(1);
        assertThat(issueCount("GRADING")).isZero();
        assertThat(jobRow(job("ZERO", 1)).get("error_code")).isEqualTo("TERMINAL");
        assertThat(compare(job("ZERO", 1)).comparison()).isEqualTo("PENDING");
    }

    /** 종료 재생은 현재 설치·인가·epoch를 복구하거나 감사·효력을 추가하지 않는다. */
    @Test
    void terminalReplayAfterInstallationAuthorizationAndEpochLossWritesNothing() {
        finishAll(true);
        aggregator.aggregate(key);
        jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        jdbc.update(
                "UPDATE public.grade_runtime SET epoch=epoch+1,state='SUSPENDED' WHERE id=?",
                runtime);
        String before = allEvidence();
        var result = aggregation(Map.of()).aggregate(key);
        assertThat(result.changed()).isFalse();
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.passed()).isTrue();
        assertThat(allEvidence()).isEqualTo(before);
    }

    /** 실제 JDBC 저장 세션·현재 REVIEW를 가진 Story 상세가 SYSTEM 정비 효과와 저장 정책을 읽는다. */
    @Test
    void authorizedStoryDetailReadsSystemEffectsAfterLivePolicyChange() {
        UUID target = job("FULL", 1);
        coordinator.activate(target);
        reserve(target);
        expire();
        aggregation(Map.of()).aggregate(key);
        jdbc.update(
                "UPDATE public.story_version SET policy_code='RULE_20260925' WHERE id=?", version);
        var repository =
                new org.springframework.session.jdbc.JdbcIndexedSessionRepository(
                        jdbc, new TransactionTemplate(manager));
        var sessions =
                new com.reasoning.admin.auth.session.AdminSessionAdapter(
                        repository,
                        new org.springframework.session.web.http.DefaultCookieSerializer(),
                        manager);
        UUID accountKey =
                jdbc.queryForObject(
                        "SELECT account_key FROM public.admin_account WHERE id=?",
                        UUID.class,
                        creator);
        var principal =
                new com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal(
                        creator, accountKey, UUID.randomUUID(), 1);
        var prepared = sessions.prepare();
        sessions.save(prepared, principal);
        jdbc.update(
                """
                WITH t AS MATERIALIZED (SELECT clock_timestamp() n)
                INSERT INTO public.admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)
                SELECT ?,?,?,1,'ACTIVE',n,n,n+interval '8 hours',n,n FROM t
                """,
                principal.sessionKey(),
                creator,
                crypto.sessionHash(prepared.id()));
        // 이 조회는 JPA 경로를 사용하지 않는다. 현재 권한·감사·사본·결과는 모두 실제 JDBC 경계다.
        var stories =
                new com.reasoning.common.story.service.StoryService(
                        jdbc,
                        manager,
                        sessions,
                        crypto,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class));
        var detailService =
                new com.reasoning.common.story.service.StoryBatchService(
                        stories, jdbc, new GradeRuntimeRepository(jdbc), crypto, Map.of());
        String code =
                jdbc.queryForObject(
                        "SELECT code FROM public.story WHERE id=?", String.class, story);
        var detail =
                detailService.getBatchDetail(
                        prepared.id(), principal, code, 1, key, UUID.randomUUID());
        assertThat(detail.state()).isEqualTo("FAILED");
        assertThat(detail.items()).hasSize(12);
        assertThat(detail.items()).allMatch(item -> "PENDING".equals(item.comparison()));
        assertThat(detail.validUntil()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.test_audit WHERE actor_kind='ADMIN'"
                                        + " AND action='CONTENT_READ' AND scope_key=?",
                                Long.class,
                                "batch:" + key))
                .isEqualTo(1);
    }

    /** 설치는 실제 사전 조립 값을 대조하며 다른 설정을 성공이나 출처 회수로 대신하지 않는다. */
    @Test
    void mismatchedInstallationCannotFinalizeButRealExpiryStillCleans() {
        finishAll(true);
        var service = aggregation(Map.of(CODE, mismatchedInstallation()));
        assertThat(service.aggregate(key).reason()).isEqualTo("INSTALLED_RUNTIME_MISMATCH");
        assertThat(batchRow().get("state")).isEqualTo("RUNNING");
        assertThat(batchRow().get("passed_yn")).isNull();
        expire();
        assertThat(service.aggregate(key).reason()).isEqualTo("BATCH_EXPIRED");
    }

    /**
     * 실제 설치 불일치도 독립 만료·출처·epoch 정비를 막지 않는다.
     *
     * @param cause 실제 DB에 구성할 독립 정비 사유
     */
    @ParameterizedTest
    @ValueSource(strings = {"BATCH_EXPIRED", "SOURCE_REVOKED", "RUNTIME_EPOCH_CHANGED"})
    void mismatchedInstallationStillAllowsFactualCleanup(String cause) {
        var service = aggregation(Map.of(CODE, mismatchedInstallation()));
        if ("BATCH_EXPIRED".equals(cause)) expire();
        else if ("SOURCE_REVOKED".equals(cause))
            jdbc.update("UPDATE public.admin_account SET can_review=false WHERE id=?", creator);
        else jdbc.update("UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=?", runtime);
        var result = service.aggregate(key);
        assertThat(result.reason()).isEqualTo(cause);
        assertThat(result.passed()).isNull();
        assertThat(batchRow().get("valid_until")).isNull();
    }

    /**
     * SQL 밖에서 실제 다른 사전 지문을 가진 설치를 미리 조립한다.
     *
     * @return 같은 코드이나 다른 불변 manifest를 가진 실제 검증자
     */
    private InstalledRuntimeManifestVerifier mismatchedInstallation() {
        var dictionary =
                new GradeDictionary(CODE, List.of(new GradeDictionary.Term("TWO", "다른 개념", "합성")));
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
        return new InstalledRuntimeManifestVerifier(
                new LocalSemanticEngine(settings),
                dictionary,
                new InstalledRuntimeManifestVerifier.InstalledProfile(
                        CODE, "LOCAL", "1", "RULE_20260924"));
    }

    /** 새 지적 자체 저장 실패도 부분 정상 완료를 취소하지 않고 집계만 롤백한다. */
    @Test
    void issueInsertFailureRollsBackAggregation() {
        completeOne("FULL", 1, normal(false, false));
        String before = allEvidence();
        trigger("execution_issue", "INSERT", "RAISE EXCEPTION 'synthetic';");
        try {
            assertThatThrownBy(() -> aggregator.aggregate(key))
                    .hasMessage("AGGREGATION_STORAGE_FAILURE");
            assertThat(allEvidence()).isEqualTo(before);
        } finally {
            drop("execution_issue");
        }
    }

    /** 성공한 트리거의 실제 자식 추가·삭제도 전체 ID 집합 대조에서 롤백한다. */
    @ParameterizedTest
    @ValueSource(strings = {"addition", "deletion"})
    void successfulIdentityAdditionOrDeletionRollsBack(String mode) {
        expire();
        String before = allEvidence();
        String mutation =
                "deletion".equals(mode)
                        ? "DELETE FROM public.grade_job WHERE id=(SELECT max(id) FROM"
                                + " public.grade_job WHERE batch_id="
                                + batch
                                + ");"
                        : "INSERT INTO"
                              + " public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)"
                              + " SELECT"
                              + " gen_random_uuid(),snapshot_id,runtime_id,batch_id,'EXTRA',1,'STAGED',input_hash,config_hash,rubric_hash"
                              + " FROM public.grade_job WHERE batch_id="
                                + batch
                                + " ORDER BY id LIMIT 1;";
        trigger(
                "test_audit",
                "INSERT",
                "IF NEW.action='BATCH_AGGREGATE' THEN " + mutation + " END IF;");
        try {
            assertThatThrownBy(() -> aggregator.aggregate(key))
                    .hasMessage("AGGREGATION_STORAGE_FAILURE");
            assertThat(allEvidence()).isEqualTo(before);
        } finally {
            drop("test_audit");
        }
    }

    /** 앵커 밖 자식의 누락·다른 설정·입력 변조도 전체 집합 검증에서 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "input", "runtime"})
    void wholeSetChecksEveryNonAnchorBinding(String mode) {
        UUID target = job("ZERO", 3);
        if ("missing".equals(mode))
            jdbc.update("DELETE FROM public.grade_job WHERE job_key=?", target);
        else if ("input".equals(mode))
            jdbc.update(
                    "UPDATE public.grade_job SET input_hash=? WHERE job_key=?",
                    "b".repeat(64),
                    target);
        else {
            long other =
                    new GradeRuntimeRepository(jdbc)
                            .registerRuntime("OTHER_AGG_RUNTIME", "c".repeat(64), "{}")
                            .id();
            jdbc.update("UPDATE public.grade_job SET runtime_id=? WHERE job_key=?", other, target);
        }
        String before = allEvidence();
        assertThatThrownBy(() -> aggregator.aggregate(key))
                .hasMessage("AGGREGATION_STORAGE_FAILURE");
        assertThat(allEvidence()).isEqualTo(before);
    }

    /** 실제 잘못된 범위·단계·결과 감사는 복호화 순서 증거로 사용할 수 없다. */
    @ParameterizedTest
    @ValueSource(strings = {"scope", "phase", "result"})
    void actualAuditMustHaveCanonicalScopeAndSuccessfulResultPhase(String mode) {
        completeOne("FULL", 1, normal(true, false));
        long audit =
                id(
                        """
                        INSERT INTO public.test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)
                        VALUES (?,'SYSTEM','AGG_TEST_SYSTEM','CONTENT_READ','batch',?,?,?,?,'{}') RETURNING id
                        """,
                        UUID.randomUUID(),
                        "scope".equals(mode) ? "batch:" + UUID.randomUUID() : "batch:" + key,
                        UUID.randomUUID(),
                        "phase".equals(mode) ? "ATTEMPT" : "RESULT",
                        "result".equals(mode) ? "FAILURE" : "SUCCESS");
        assertThatThrownBy(() -> compareWithAudit(job("FULL", 1), audit))
                .hasMessage("BATCH_COMPARISON_STORAGE_FAILURE");
    }

    /** 주변 거래는 생성·호출 모두 거절한다. 동일 관리자를 별도 거래로만 사용한다. */
    @Test
    void rejectsAmbientTransactions() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            assertThatThrownBy(() -> aggregator.aggregate(key))
                                    .hasMessage("AGGREGATION_REQUIRES_SEPARATE_TRANSACTION");
                            assertThatThrownBy(() -> aggregation(Map.of()))
                                    .hasMessage("AGGREGATION_REQUIRES_SEPARATE_TRANSACTION");
                        });
    }

    /** 필수 읽기·지적·최종 감사 실패는 이전 커밋 완료를 포함한 기존 전체 행을 보존한다. */
    @ParameterizedTest
    @ValueSource(
            strings = {"CONTENT_READ", "BATCH_ISSUE", "BATCH_AGGREGATE", "EVIDENCE_INVALIDATE"})
    void mandatoryAuditFailureRollsBackOnlyAggregation(String action) {
        completeOne("FULL", 1, normal(false, false));
        expire();
        String before = allEvidence();
        trigger(
                "test_audit",
                "INSERT",
                "IF NEW.action='" + action + "' THEN RAISE EXCEPTION 'synthetic'; END IF;");
        try {
            assertThatThrownBy(() -> aggregator.aggregate(key))
                    .hasMessage("AGGREGATION_STORAGE_FAILURE")
                    .hasNoCause();
            assertThat(allEvidence()).isEqualTo(before);
        } finally {
            drop("test_audit");
        }
    }

    /** 종료 뒤에도 실제 단일 자식에 적용된 트리거의 예산·부모·시각 변조를 전체 보존 대조에서 롤백한다. */
    @ParameterizedTest
    @ValueSource(strings = {"job", "snapshot", "attempt"})
    void successfulPostWriteDivergenceRollsBack(String surface) {
        UUID target = job("FULL", 1);
        coordinator.activate(target);
        reserve(target);
        expire();
        String before = allEvidence();
        long targetId = ((Number) jobRow(target).get("id")).longValue();
        String effect =
                switch (surface) {
                    case "job" ->
                            "UPDATE public.grade_job SET call_count=call_count+1 WHERE id="
                                    + targetId
                                    + "; GET DIAGNOSTICS changed_rows = ROW_COUNT; IF"
                                    + " changed_rows<>1 THEN RAISE EXCEPTION"
                                    + " 'INJECTION_NOT_APPLIED'; END IF;";
                    case "snapshot" ->
                            "UPDATE public.review_snapshot SET edit_rev=edit_rev+1 WHERE id="
                                    + snapshot
                                    + ";";
                    default ->
                            "UPDATE public.grade_attempt SET provider_ref='injected' WHERE job_id="
                                    + targetId
                                    + " AND attempt_no=1;";
                };
        trigger(
                "test_audit",
                "INSERT",
                "IF NEW.action='BATCH_AGGREGATE' THEN " + effect + " END IF;");
        try {
            assertThatThrownBy(() -> aggregator.aggregate(key))
                    .hasMessage("AGGREGATION_STORAGE_FAILURE")
                    .hasNoCause();
            assertThat(allEvidence()).isEqualTo(before);
        } finally {
            drop("test_audit");
        }
    }

    /** 정상 암호·원래 해시·정확한 수치와 필수 실제 감사 순서를 하나의 비교자가 검사한다. */
    @ParameterizedTest
    @ValueSource(strings = {"summary", "numeric", "range", "level", "aad", "hash", "audit"})
    void sharedComparatorRejectsForgedEvidence(String kind) {
        completeOne("FULL", 1, normal(true, false));
        UUID target = job("FULL", 1);
        if ("summary".equals(kind))
            jdbc.update(
                    "UPDATE public.grade_job SET"
                        + " result_data=jsonb_set(result_data,'{expectationMatched}','false') WHERE"
                        + " job_key=?",
                    target);
        if ("numeric".equals(kind))
            jdbc.update(
                    "UPDATE public.grade_job SET"
                            + " result_data=jsonb_set(result_data,'{baseScore}','\"100\"') WHERE"
                            + " job_key=?",
                    target);
        if ("range".equals(kind))
            jdbc.update(
                    "UPDATE public.grade_job SET"
                            + " result_data=jsonb_set(result_data,'{baseScore}','2147483648') WHERE"
                            + " job_key=?",
                    target);
        if ("level".equals(kind))
            jdbc.update(
                    "UPDATE public.grade_job SET"
                            + " result_data=jsonb_set(result_data,'{items,1,score}','19') WHERE"
                            + " job_key=?",
                    target);
        if ("hash".equals(kind))
            jdbc.update(
                    "UPDATE public.grade_job SET result_hash=? WHERE job_key=?",
                    "b".repeat(64),
                    target);
        if ("aad".equals(kind)) {
            var row = jobRow(target);
            String plain =
                    crypto.decrypt(
                            new String((byte[]) row.get("result_cipher"), StandardCharsets.UTF_8),
                            "grade_job/" + row.get("id") + "/result/v1");
            jdbc.update(
                    "UPDATE public.grade_job SET result_cipher=? WHERE job_key=?",
                    crypto.encrypt(plain, "grade_job/999999/result/v1")
                            .getBytes(StandardCharsets.UTF_8),
                    target);
        }
        if ("audit".equals(kind))
            assertThatThrownBy(() -> compareWithAudit(target, 0))
                    .hasMessage("BATCH_COMPARISON_STORAGE_FAILURE");
        else
            assertThatThrownBy(() -> compare(target))
                    .hasMessage("BATCH_COMPARISON_STORAGE_FAILURE");
    }

    /** 같은 루트의 두 집계자는 한 번만 완료하고 뒤 호출은 감사 없는 재생이다. */
    @Test
    void twoAggregatorsSerializeOneTerminalOutcome() throws Exception {
        finishAll(true);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var one =
                    threads.submit(
                            () -> {
                                barrier.await();
                                return aggregator.aggregate(key);
                            });
            var two =
                    threads.submit(
                            () -> {
                                barrier.await();
                                return aggregation(Map.of(CODE, installation)).aggregate(key);
                            });
            var a = one.get(10, TimeUnit.SECONDS);
            var b = two.get(10, TimeUnit.SECONDS);
            assertThat(a.changed() ^ b.changed()).isTrue();
            assertThat(a.state()).isEqualTo("COMPLETED");
            assertThat(b.state()).isEqualTo("COMPLETED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.test_audit WHERE scope_key=? AND"
                                            + " action='BATCH_AGGREGATE'",
                                    Long.class,
                                    "batch:" + key))
                    .isEqualTo(1);
        }
    }

    /** 최종 완료와 실제 집계의 루트 경쟁은 원래 완료 암호·영수증을 잃지 않는다. */
    @Test
    void completionRacesAggregationWithoutLostReceipt() throws Exception {
        finishRemaining(true, "FULL", 3);
        UUID target = job("FULL", 3);
        coordinator.activate(target);
        var lease = reserve(target);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var collect =
                    threads.submit(
                            () -> {
                                barrier.await();
                                return aggregator.aggregate(key);
                            });
            var complete =
                    threads.submit(
                            () -> {
                                barrier.await();
                                return completion.complete(
                                        worker,
                                        target,
                                        lease.leaseGen(),
                                        1,
                                        VERSION,
                                        null,
                                        text(normal(true, false)),
                                        UUID.randomUUID());
                            });
            assertThat(complete.get(10, TimeUnit.SECONDS).accepted()).isTrue();
            assertThat(collect.get(10, TimeUnit.SECONDS).state()).isIn("RUNNING", "COMPLETED");
        }
        String evidence = completionEvidence();
        assertThat(aggregator.aggregate(key).passed()).isTrue();
        assertThat(completionEvidence()).isEqualTo(evidence);
    }

    /** 실제 계정 루트 대기 이후 읽은 새 시각으로 24시간 경계를 검사한다. */
    @Test
    void rootWaitUsesFreshLockedDatabaseClock() throws Exception {
        try (var holder = postgres.createConnection("")) {
            holder.setAutoCommit(false);
            try (var lock =
                    holder.prepareStatement(
                            "SELECT id FROM public.admin_account WHERE id=? FOR UPDATE")) {
                lock.setLong(1, creator);
                lock.executeQuery().close();
            }
            try (var threads = Executors.newSingleThreadExecutor()) {
                var result = threads.submit(() -> aggregator.aggregate(key));
                boolean waiting = false;
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!waiting && System.nanoTime() < end)
                    waiting =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                                    + " wait_event_type='Lock' AND query LIKE"
                                                    + " '%admin_account%')",
                                            Boolean.class));
                assertThat(waiting).isTrue();
                try (var change =
                        holder.prepareStatement(
                                "UPDATE public.grade_batch SET"
                                        + " created_at=clock_timestamp()-interval '24 hours' WHERE"
                                        + " id=?")) {
                    change.setLong(1, batch);
                    change.executeUpdate();
                }
                holder.commit();
                assertThat(result.get(10, TimeUnit.SECONDS).reason()).isEqualTo("BATCH_EXPIRED");
            } finally {
                holder.rollback();
            }
        }
    }

    /**
     * 같은 전체 fixture의 실제 예약·완료만 집계 입력으로 사용한다.
     *
     * @param correct FULL 기대와의 합성 일치 여부, 점수는 주입하지 않는다
     */
    private void finishAll(boolean correct) {
        finishRemaining(correct);
    }

    /**
     * 미완료 실제 작업만 처리하며 이미 커밋한 완료는 바꾸지 않는다.
     *
     * @param correct FULL 합성 명제 충족 여부
     */
    private void finishRemaining(boolean correct) {
        finishRemaining(correct, null, 0);
    }

    /**
     * 전체 fixture를 코드·반복으로 실행하며 선택한 마지막 작업만 경쟁 시험용으로 남긴다.
     *
     * @param correct FULL 합성 명제 충족 여부
     * @param skip 제외할 실제 sample 코드, null 허용
     * @param repeat 제외할 반복 번호
     */
    private void finishRemaining(boolean correct, String skip, int repeat) {
        for (var row :
                jdbc.queryForList(
                        "SELECT sample_code,repeat_no,state FROM public.grade_job WHERE batch_id=?"
                                + " ORDER BY id",
                        batch)) {
            String code = (String) row.get("sample_code");
            int n = ((Number) row.get("repeat_no")).intValue();
            if (!"STAGED".equals(row.get("state")) || code.equals(skip) && n == repeat) continue;
            if ("INPUT".equals(code)) coordinator.activate(job(code, n));
            else if ("ENGINE".equals(code)) {
                UUID target = job(code, n);
                coordinator.activate(target);
                for (int attempt = 1; attempt <= 3; attempt++) {
                    var lease = reserve(target);
                    assertThat(
                                    completion
                                            .complete(
                                                    worker,
                                                    target,
                                                    lease.leaseGen(),
                                                    attempt,
                                                    VERSION,
                                                    null,
                                                    text(error()),
                                                    UUID.randomUUID())
                                            .accepted())
                            .isTrue();
                    if (attempt < 3) due(target);
                }
            } else
                completeOne(code, n, normal("FULL".equals(code) && correct, "ZERO".equals(code)));
        }
    }

    /**
     * 기대 점수·fixture 경로를 읽지 않고 명제·모순 튜플만 직접 작성한다.
     *
     * @param met 네 설명 명제 충족
     * @param contradicted 범인 모순 충족
     * @return 점수·기대값 없는 COMPLETE 프로토콜
     */
    private static ObjectNode normal(boolean met, boolean contradicted) {
        ObjectNode semantic = object().put("formatNo", 1).put("status", "COMPLETE");
        var items = semantic.putArray("items");
        ObjectNode culprit =
                items.addObject().put("rubricCode", "CULPRIT").put("reason", "합성 의미이며 모델 출력 아님");
        culprit.putArray("claims");
        var contradictions = culprit.putArray("contradictions");
        for (String code : List.of("CONTRADICT_CULPRIT", "UNSUPPORTED_ACCOMPLICE")) {
            boolean active = contradicted && "CONTRADICT_CULPRIT".equals(code);
            var spans =
                    contradictions
                            .addObject()
                            .put("code", code)
                            .put("met", active)
                            .putArray("spans");
            if (active) spans.addObject().put("field", "method").put("start", 0).put("end", 1);
        }
        for (String code : List.of("METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            var item = items.addObject().put("rubricCode", code).put("reason", "독립 합성 명제");
            item.putArray("contradictions");
            var spans =
                    item.putArray("claims")
                            .addObject()
                            .put("code", "CLAIM")
                            .put("met", met)
                            .putArray("spans");
            if (met) spans.addObject().put("field", "method").put("start", 0).put("end", 1);
        }
        ObjectNode result = object().put("kind", "COMPLETE");
        result.set("semantic", semantic);
        return result;
    }

    /** 실제 synthetic timeout callback이며 영수증은 완료 서비스가 만든다. */
    private static ObjectNode error() {
        return object().put("kind", "ERROR").put("errorCode", "ENGINE_TIMEOUT");
    }

    /**
     * 실제 접수→임대→START→완료를 사용한다.
     *
     * @param code 실제 sample 코드
     * @param repeat 실제 반복
     * @param body 점수 없는 합성 의미 입력
     */
    private void completeOne(String code, int repeat, JsonNode body) {
        UUID target = job(code, repeat);
        coordinator.activate(target);
        var lease = reserve(target);
        assertThat(
                        completion
                                .complete(
                                        worker,
                                        target,
                                        lease.leaseGen(),
                                        1,
                                        VERSION,
                                        null,
                                        text(body),
                                        UUID.randomUUID())
                                .accepted())
                .isTrue();
    }

    /**
     * 실제 worker claim과 START가 실제 같은 출처 예약을 만든다.
     *
     * @param target 실제 작업 UUID
     * @return 실제 임대
     */
    private GradeLeaseRepository.Lease reserve(UUID target) {
        var lease = leases.claim(workerKey, CODE).orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(target);
        starter.start(worker, target, lease.leaseGen());
        return lease;
    }

    /** 재시도 backoff 시계만 시험에서 앞당기며 예산·영수증은 바꾸지 않는다. */
    private void due(UUID target) {
        jdbc.update(
                "UPDATE public.grade_job SET next_run_at=clock_timestamp() WHERE job_key=?",
                target);
    }

    /** 실제 집합 생성 시각만 경계 시험에 사용한다. */
    private void expire() {
        jdbc.update(
                "UPDATE public.grade_batch SET created_at=clock_timestamp()-interval '24 hours'"
                        + " WHERE id=?",
                batch);
    }

    /** 명시적 SYSTEM 서비스만 생성한다. */
    private GradeBatchAggregationService aggregation(
            Map<String, InstalledRuntimeManifestVerifier> installed) {
        return new GradeBatchAggregationService(
                jdbc, manager, crypto, installed, "AGG_TEST_SYSTEM");
    }

    /** 실제 필수 SYSTEM 감사 이후 공유 비교자를 호출한다. */
    private GradeBatchComparison.Comparison compare(UUID target) {
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            long audit =
                                    id(
                                            """
                                            INSERT INTO public.test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)
                                            VALUES (?,'SYSTEM','AGG_TEST_SYSTEM','CONTENT_READ','batch',?,?,'RESULT','SUCCESS','{}') RETURNING id
                                            """,
                                            UUID.randomUUID(),
                                            "batch:" + key,
                                            UUID.randomUUID());
                            return compareInside(target, audit);
                        });
    }

    /** 부정 순서 시험용 실제 감사 식별자를 그대로 전달한다. */
    private GradeBatchComparison.Comparison compareWithAudit(UUID target, long audit) {
        return new TransactionTemplate(manager).execute(status -> compareInside(target, audit));
    }

    /** 같은 거래의 실제 전체 작업·사본·runtime으로 비교한다. */
    private GradeBatchComparison.Comparison compareInside(UUID target, long audit) {
        var source = new GradeSourceRepository(jdbc).lockRoots(target);
        var row =
                jdbc.queryForMap(
                        "SELECT *,result_cipher IS NOT NULL AS has_result_cipher FROM"
                                + " public.grade_job WHERE id=?",
                        source.jobId());
        var saved = new GradeFrozenInputRepository(jdbc).loadSavedFacts(snapshot);
        return new GradeBatchComparison(jdbc, crypto)
                .compare(
                        row,
                        saved.dataset().select((String) row.get("sample_code")),
                        new GradeRuntimeRepository(jdbc).getRuntimeDetail(runtime).orElseThrow(),
                        saved.dataset().gradingSnapshot(),
                        audit);
    }

    /** 실제 존재하는 작업 UUID만 선택한다. */
    private UUID job(String code, int repeat) {
        return jdbc.queryForObject(
                "SELECT job_key FROM public.grade_job WHERE batch_id=? AND sample_code=? AND"
                        + " repeat_no=?",
                UUID.class,
                batch,
                code,
                repeat);
    }

    /** 실제 전체 작업을 시험 내부에서만 읽는다. */
    private Map<String, Object> jobRow(UUID target) {
        return jdbc.queryForMap("SELECT * FROM public.grade_job WHERE job_key=?", target);
    }

    /** 실제 집합 전체 행이다. */
    private Map<String, Object> batchRow() {
        return jdbc.queryForMap("SELECT * FROM public.grade_batch WHERE id=?", batch);
    }

    /** 최초 출처별 실제 지적 개수다. */
    private long issueCount(String kind) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.execution_issue WHERE batch_id=? AND kind=?",
                Long.class,
                batch,
                kind);
    }

    /** 불변 지적 전체 행의 반복 보존을 검사한다. */
    private String issueRows() {
        return jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]')::text FROM"
                        + " public.execution_issue t WHERE batch_id=?",
                String.class,
                batch);
    }

    /** 원래 예산·해시·암호·수집 영수증의 전체 보존을 비교한다. */
    private String immutableJobs() {
        return jdbc.queryForObject(
                "SELECT"
                    + " jsonb_agg(to_jsonb(t)-ARRAY['state','error_code','worker_key','lease_until','updated_at']"
                    + " ORDER BY id)::text FROM public.grade_job t WHERE batch_id=?",
                String.class,
                batch);
    }

    /** 이전 커밋 완료·시도·감사 전체를 보존한다. */
    private String completionEvidence() {
        return jdbc.queryForObject(
                """
                SELECT jsonb_build_object('jobs',(SELECT jsonb_agg(to_jsonb(j) ORDER BY id) FROM public.grade_job j WHERE batch_id=? AND state='COMPLETED'),
                    'attempts',(SELECT jsonb_agg(to_jsonb(a) ORDER BY job_id,attempt_no) FROM public.grade_attempt a WHERE job_id IN (SELECT id FROM public.grade_job WHERE batch_id=?)),
                    'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.grade_event e WHERE job_id IN (SELECT id FROM public.grade_job WHERE batch_id=?)))::text
                """,
                String.class,
                batch,
                batch,
                batch);
    }

    /** 집계 거래 롤백은 모든 실제 원래 행·식별자를 보존해야 한다. */
    private String allEvidence() {
        return jdbc.queryForObject(
                """
                SELECT jsonb_build_object('batch',(SELECT to_jsonb(b) FROM public.grade_batch b WHERE id=?),
                    'jobs',(SELECT jsonb_agg(to_jsonb(j) ORDER BY id) FROM public.grade_job j WHERE batch_id=?),
                    'attempts',(SELECT jsonb_agg(to_jsonb(a) ORDER BY job_id,attempt_no) FROM public.grade_attempt a WHERE job_id IN (SELECT id FROM public.grade_job WHERE batch_id=?)),
                    'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.grade_event e WHERE job_id IN (SELECT id FROM public.grade_job WHERE batch_id=?)),
                    'audits',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.test_audit t WHERE scope_key=?),
                    'issues',(SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM public.execution_issue i WHERE batch_id=?),
                    'snapshot',(SELECT to_jsonb(f) FROM public.review_snapshot f WHERE id=?))::text
                """,
                String.class,
                batch,
                batch,
                batch,
                batch,
                "batch:" + key,
                batch,
                snapshot);
    }

    /** 폐기형 DB의 시험 전용 트리거를 설치하며 실제 주입 행 수를 검사할 지역 변수를 제공한다. */
    private void trigger(String table, String operation, String body) {
        jdbc.execute(
                "CREATE FUNCTION public.agg_test_trigger() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " DECLARE changed_rows bigint; BEGIN "
                        + body
                        + " RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER agg_test_trigger BEFORE "
                        + operation
                        + " ON public."
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION public.agg_test_trigger()");
    }

    /** 시험에서 만든 트리거만 제거한다. */
    private void drop(String table) {
        jdbc.execute("DROP TRIGGER agg_test_trigger ON public." + table);
        jdbc.execute("DROP FUNCTION public.agg_test_trigger()");
    }

    /** 실제 DB identity를 사용한다. */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 공유 정규 JSON을 사용한다. */
    private static String text(JsonNode value) {
        return new String(SnapshotJson.encode(value), StandardCharsets.UTF_8);
    }

    /** 독립 안전 객체다. */
    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }
}
