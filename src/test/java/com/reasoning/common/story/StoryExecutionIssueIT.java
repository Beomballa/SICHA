package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.story.service.StoryBatchService;
import com.reasoning.common.story.service.StoryReviewService;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 최신 V17·JPA 거래·저장 세션·폐기형 PG로 PT-A11 메타데이터 이력만 대조하는 정의다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StoryExecutionIssueIT extends DatabaseContextTest {
    private static final String PRIVATE = "ISSUE_PRIVATE_REPORT_ANSWER_CANARY";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    /** 폐기형 PG와 합성 인증 키만 주입한다. 일반 DB·제공자는 사용하지 않는다. */
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

    @Autowired JdbcTemplate db;
    @Autowired AdminSessionAdapter sessions;
    @Autowired StoryReviewService reviews;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean CryptoService crypto;
    @MockitoSpyBean StoryBatchService batches;

    /** DTO 필드·실제 다른 runtime 대상·비활성 과거 resolver·필수 감사 상관과 전체 불변을 대조한다. */
    @Test
    void exactProjectionHistoricalResolverAndAuditCorrelation() throws Exception {
        Fixture f = fixture();
        Actor resolver = actor();
        long resolved = issue(f, "GRADING");
        resolveFixture(
                f,
                resolved,
                resolver,
                "{\"reasonCode\":\"GRADING_FIX_VERIFIED\",\"verificationRef\":\"CHECK_001\","
                        + "\"private\":\""
                        + PRIVATE
                        + "\",\"report\":{\"answer\":\""
                        + PRIVATE
                        + "\"}}");
        db.update(
                "UPDATE admin_account SET active_yn=false,can_review=false WHERE id=?",
                resolver.principal().accountId());
        db.update(
                "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                resolver.principal().accountId());
        issue(f, "CONTENT");
        id(
                "INSERT INTO"
                    + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                    + " VALUES (?,?,?,?,'OBSERVATION','MINOR','OPEN','OBSERVATION_MISSING')"
                    + " RETURNING id",
                UUID.randomUUID(),
                f.snapshot(),
                f.runtime(),
                f.source());
        String before = preserved(f);
        clearInvocations(crypto, batches);
        JsonNode page = read(f, null);
        assertThat(names(page)).containsExactlyInAnyOrder("items", "nextCursor", "requestId");
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(page.get("items")).hasSize(3);
        for (JsonNode row : page.get("items")) {
            assertThat(names(row))
                    .containsExactlyInAnyOrder(
                            "issueKey",
                            "snapshotId",
                            "runtimeConfigId",
                            "sourceBatchKey",
                            "sourceReviewId",
                            "kind",
                            "severity",
                            "state",
                            "createdAt",
                            "resolvedAt",
                            "resolvedBy",
                            "targetBatchKey",
                            "targetReviewId",
                            "resolution");
            assertThat(row.get("snapshotId").asText()).isEqualTo(Long.toString(f.snapshot()));
            assertThat(row.get("runtimeConfigId").asText()).isEqualTo(f.runtimeCode());
            assertThat(row.get("sourceBatchKey").asText())
                    .isEqualTo(batchKey(f.source()).toString());
            assertThat(row.get("sourceReviewId").isNull()).isTrue();
            assertThat(row.get("targetReviewId").isNull()).isTrue();
            assertThat(java.time.Instant.parse(row.get("createdAt").asText())).isNotNull();
        }
        JsonNode open = page.get("items").get(0);
        assertThat(open.get("kind").asText()).isEqualTo("OBSERVATION");
        assertThat(open.get("severity").asText()).isEqualTo("MINOR");
        for (String name : List.of("resolvedAt", "resolvedBy", "targetBatchKey", "resolution"))
            assertThat(open.get(name).isNull()).isTrue();
        JsonNode closed = page.get("items").get(2);
        assertThat(closed.get("resolvedBy").asText())
                .isEqualTo(resolver.principal().accountKey().toString());
        assertThat(closed.get("targetBatchKey").asText())
                .isEqualTo(batchKey(f.target()).toString());
        assertThat(names(closed.get("resolution")))
                .containsExactlyInAnyOrder("reasonCode", "verificationRef");
        assertThat(closed.get("resolution").get("verificationRef").asText()).isEqualTo("CHECK_001");
        assertThat(page.toString())
                .doesNotContain(
                        PRIVATE,
                        "payload",
                        "reason_code",
                        "resolution_data",
                        "datasetHash",
                        "configHash",
                        "baseScore",
                        "expectData",
                        "REPORT",
                        "accountId");
        assertThat(preserved(f)).isEqualTo(before);
        JsonNode audit =
                parse(
                        db.queryForObject(
                                "SELECT to_jsonb(a)::text FROM story_audit a WHERE version_id=?"
                                        + " AND action='CONTENT_READ'",
                                String.class,
                                f.version()));
        assertThat(names(audit.get("detail")))
                .containsExactlyInAnyOrder("revisionScope", "requestId", "sourceRev", "snapshotId");
        assertThat(audit.get("detail").get("revisionScope").asText()).isEqualTo("CONTENT");
        assertThat(audit.get("detail").get("requestId")).isEqualTo(page.get("requestId"));
        assertThat(audit.get("detail").get("sourceRev").asText()).isEqualTo("0");
        assertThat(audit.get("before_rev").longValue()).isEqualTo(0);
        assertThat(audit.get("after_rev").longValue()).isEqualTo(0);
        var history =
                db.queryForMap(
                        "SELECT route,request_id FROM access_history WHERE request_id=?",
                        UUID.fromString(page.get("requestId").asText()));
        assertThat(history.get("route"))
                .isEqualTo("/admin/api/stories/{storyCode}/versions/{versionNo}/execution-issues");
        verify(crypto, never()).decrypt(anyString(), anyString());
        verify(crypto, never()).encrypt(anyString(), anyString());
        verifyNoInteractions(batches);
    }

    /** size+1·ID 배타 내림차순·상태 생략/필터·기본20·최대100·허용된 빈 목록을 검사한다. */
    @Test
    void allStatesFiltersAndExclusivePaging() throws Exception {
        Fixture f = fixture();
        assertThat(read(f, null).get("items")).isEmpty();
        List<Long> ids = new ArrayList<>();
        for (int n = 0; n < 105; n++) {
            long batch = batch(f.snapshot(), f.runtime(), f.actor());
            ids.add(issue(f.snapshot(), f.runtime(), batch, "INFRA"));
        }
        resolveFixture(
                f,
                ids.getFirst(),
                f.actor(),
                "{\"reasonCode\":\"INFRA_RECOVERED\",\"verificationRef\":\"VERIFY_01\"}");
        JsonNode page = read(f, "size=100");
        assertThat(page.get("items")).hasSize(100);
        assertThat(page.get("items").get(0).get("issueKey").asText())
                .isEqualTo(
                        db.queryForObject(
                                "SELECT issue_key::text FROM execution_issue WHERE id=?",
                                String.class,
                                ids.getLast()));
        assertThat(page.get("nextCursor").asText()).isEqualTo(Long.toString(ids.get(5)));
        JsonNode next = read(f, "size=100&cursor=" + page.get("nextCursor").asText());
        assertThat(next.get("items")).hasSize(5);
        assertThat(next.get("nextCursor").isNull()).isTrue();
        Set<String> keys = new HashSet<>();
        for (JsonNode p : List.of(page, next))
            for (JsonNode row : p.get("items"))
                assertThat(keys.add(row.get("issueKey").asText())).isTrue();
        assertThat(keys).hasSize(105);
        assertThat(read(f, null).get("items")).hasSize(20);
        JsonNode closed = read(f, "state=RESOLVED");
        assertThat(closed.get("items")).hasSize(1);
        assertThat(read(f, "state=OPEN&size=100").get("items")).hasSize(100);
        assertThat(read(f, "cursor=9223372036854775807&state=RESOLVED").get("items")).hasSize(1);
    }

    /** 쿼리의 ID/size/상태는 정규값만 받으며 추가·중복 키도 감사 전에 거절한다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "snapshotId=",
                "snapshotId=0",
                "snapshotId=01",
                "snapshotId=-1",
                "snapshotId=%2B1",
                "snapshotId=1.0",
                "snapshotId=%201",
                "snapshotId=9223372036854775808",
                "snapshotId=1&snapshotId=1",
                "snapshotId=1&state=ALL",
                "snapshotId=1&state=",
                "snapshotId=1&state=OPEN&state=OPEN",
                "snapshotId=1&cursor=01",
                "snapshotId=1&cursor=0",
                "snapshotId=1&cursor=-1",
                "snapshotId=1&cursor=1.0",
                "snapshotId=1&cursor=9223372036854775808",
                "snapshotId=1&cursor=1&cursor=1",
                "snapshotId=1&size=0",
                "snapshotId=1&size=101",
                "snapshotId=1&size=01",
                "snapshotId=1&size=",
                "snapshotId=1&size=1.0",
                "snapshotId=1&size=999999999999999",
                "snapshotId=1&size=1&size=1",
                "snapshotId=1&afterId=1",
                "snapshotId=1&expectedRev=0",
                "snapshotId=1&private=secret",
                ""
            })
    void rejectsMalformedUnknownAndRepeatedQuery(String query) throws Exception {
        Fixture f = fixture();
        mvc.perform(request(f, f.actor(), query)).andExpect(status().isBadRequest());
        assertThat(audits(f)).isZero();
    }

    /** 소유자·EDIT·PUBLISH는 현재 전역·사건 REVIEW 쌍의 대체 자격이 아니다. */
    @ParameterizedTest
    @ValueSource(strings = {"global", "scope", "EDIT", "PUBLISH"})
    void ownerCannotSubstituteReviewPair(String defect) throws Exception {
        Fixture f = fixture();
        issue(f, "CONTENT");
        if (defect.equals("global"))
            db.update(
                    "UPDATE admin_account SET can_review=false WHERE id=?",
                    f.actor().principal().accountId());
        else db.update("UPDATE story_access SET active_yn=false WHERE story_id=?", f.story());
        if (Set.of("EDIT", "PUBLISH").contains(defect)) {
            db.update(
                    "UPDATE admin_account SET can_publish=true WHERE id=?",
                    f.actor().principal().accountId());
            db.update(
                    "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                            + " (?,?,?,?)",
                    f.story(),
                    f.actor().principal().accountId(),
                    defect,
                    f.actor().principal().accountId());
        }
        mvc.perform(request(f, f.actor(), "snapshotId=" + f.snapshot()))
                .andExpect(status().isForbidden());
        assertThat(audits(f)).isZero();
    }

    /** 매 페이지 현재 grant/revoke를 재검사하고 outsider·외부 사본·비활성 부모를 숨긴다. */
    @Test
    void currentGrantRevokeAndNondisclosure() throws Exception {
        Fixture f = fixture();
        Fixture foreign = fixture();
        Actor reader = actor();
        mvc.perform(request(f, reader, "snapshotId=" + f.snapshot()))
                .andExpect(status().isNotFound());
        grant(f, reader);
        mvc.perform(request(f, reader, "snapshotId=" + f.snapshot())).andExpect(status().isOk());
        db.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                f.story(),
                reader.principal().accountId());
        mvc.perform(request(f, reader, "snapshotId=" + f.snapshot()))
                .andExpect(status().isNotFound());
        mvc.perform(request(f, f.actor(), "snapshotId=" + foreign.snapshot()))
                .andExpect(status().isNotFound());
        mvc.perform(request(f, f.actor(), "snapshotId=9223372036854775807"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        https(
                                        get(
                                                root(f).replace("/versions/1/", "/versions/2/")
                                                        + "?snapshotId="
                                                        + f.snapshot()))
                                .cookie(cookie(f.actor())))
                .andExpect(status().isNotFound());
        mvc.perform(
                        https(
                                        get(
                                                "/admin/api/stories/MISSING_PARENT/versions/1/execution-issues?snapshotId="
                                                        + f.snapshot()))
                                .cookie(cookie(f.actor())))
                .andExpect(status().isNotFound());
        for (String table : List.of("story", "story_version")) {
            long id = table.equals("story") ? f.story() : f.version();
            db.update("UPDATE " + table + " SET active_yn=false WHERE id=?", id);
            try {
                mvc.perform(request(f, f.actor(), "snapshotId=" + f.snapshot()))
                        .andExpect(status().isNotFound());
            } finally {
                db.update("UPDATE " + table + " SET active_yn=true WHERE id=?", id);
            }
        }
    }

    /** 실제 account/MFA/authRev/session 루트를 현재 읽으며 과거 principal만으로 통과하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"account", "mfa", "enrolled", "epoch", "session", "idle", "absolute"})
    void currentAuthenticationRootsAreMandatory(String defect) throws Exception {
        Fixture f = fixture();
        long actor = f.actor().principal().accountId();
        switch (defect) {
            case "account" ->
                    db.update("UPDATE admin_account SET active_yn=false WHERE id=?", actor);
            case "mfa" ->
                    db.update(
                            "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                            actor);
            case "enrolled" ->
                    db.update(
                            "UPDATE admin_credential SET enrolled_at=NULL,mfa_state='PENDING' WHERE"
                                    + " account_id=?",
                            actor);
            case "epoch" ->
                    db.update(
                            "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?",
                            actor);
            case "session" ->
                    db.update(
                            "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp()"
                                    + " WHERE account_id=?",
                            actor);
            case "idle" ->
                    db.update(
                            "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) UPDATE"
                                + " admin_session SET started_at=t.n-interval '1"
                                + " hour',expires_at=t.n+interval '7"
                                + " hours',last_action_at=t.n-interval '31 minutes' FROM t WHERE"
                                + " account_id=?",
                            actor);
            default ->
                    db.update(
                            "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) UPDATE"
                                    + " admin_session SET started_at=t.n-interval '9"
                                    + " hours',expires_at=t.n-interval '1 hour' FROM t WHERE"
                                    + " account_id=?",
                            actor);
        }
        mvc.perform(request(f, f.actor(), "snapshotId=" + f.snapshot()))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> list(f)).hasMessage("AUTH_REQUIRED");
        assertThat(audits(f)).isZero();
    }

    /** 역사 사실은 초안/정책/설치/creator/epoch 상실 뒤에도 보되 운영효력을 갱신하지 않는다. */
    @Test
    void historicalAfterAuthorityAndInstallationLossDoesNotRenewExecution() throws Exception {
        Fixture f = fixture();
        issue(f, "INFRA");
        Actor creator = actor();
        db.update(
                "UPDATE grade_batch SET"
                    + " created_by=?,state='COMPLETED',passed_yn=true,ended_at=clock_timestamp()"
                    + " WHERE id=?",
                creator.principal().accountId(),
                f.target());
        db.update(
                "UPDATE admin_account SET active_yn=false,can_review=false WHERE id=?",
                creator.principal().accountId());
        db.update(
                "UPDATE review_snapshot SET created_by=? WHERE id=?",
                creator.principal().accountId(),
                f.snapshot());
        db.update(
                "UPDATE story_version SET"
                    + " status='DRAFT',edit_rev=9,current_snapshot_id=NULL,policy_code='OLD_POLICY'"
                    + " WHERE id=?",
                f.version());
        db.update(
                "UPDATE grade_runtime SET state='RETIRED',epoch=epoch+1 WHERE id IN (?,?)",
                f.runtime(),
                f.targetRuntime());
        AtomicInteger calls = new AtomicInteger();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        provider.start();
        try {
            db.update(
                    "UPDATE grade_runtime SET config_data=config_data ||"
                            + " jsonb_build_object('providerUrl',?) WHERE id=?",
                    "http://127.0.0.1:" + provider.getAddress().getPort(),
                    f.runtime());
            String before = preserved(f);
            clearInvocations(crypto, batches);
            assertThat(read(f, null).get("items")).hasSize(1);
            assertThat(preserved(f)).isEqualTo(before);
            assertThat(
                            db.queryForObject(
                                    "SELECT valid_until FROM grade_batch WHERE id=?",
                                    java.sql.Timestamp.class,
                                    f.target()))
                    .isNull();
            assertThat(
                            db.queryForObject(
                                    "SELECT before_rev FROM story_audit WHERE version_id=? AND"
                                            + " action='CONTENT_READ'",
                                    Long.class,
                                    f.version()))
                    .isEqualTo(9L);
            verify(crypto, never()).decrypt(anyString(), anyString());
            verifyNoInteractions(batches);
            assertThat(calls.get()).isZero();
        } finally {
            provider.stop(0);
        }
    }

    /** 저장 해소는 실제 단회 V17 SQL fixture이며 원문/알 수 없는 타입/사유를 응답하지 않는다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"reasonCode\":\"PRIVATE_UNKNOWN\",\"verificationRef\":\"CHECK_001\"}",
                "{\"reasonCode\":42,\"verificationRef\":\"CHECK_001\"}",
                "{\"reasonCode\":\"INFRA_RECOVERED\",\"verificationRef\":null}",
                "{\"reasonCode\":\"INFRA_RECOVERED\",\"verificationRef\":\"short\"}",
                "{\"reasonCode\":\"INFRA_RECOVERED\",\"verificationRef\":\"private/path\"}"
            })
    void malformedStoredResolutionFailsClosed(String resolution) throws Exception {
        Fixture f = fixture();
        resolveFixture(f, issue(f, "INFRA"), f.actor(), resolution);
        String before = preserved(f);
        mvc.perform(request(f, f.actor(), "snapshotId=" + f.snapshot()))
                .andExpect(status().isServiceUnavailable());
        assertThat(preserved(f)).isEqualTo(before);
        assertThat(audits(f)).isZero();
    }

    /** 필수 감사 실패와 성공 트리거의 필드 변경·추가·삭제는 원래 전체 행 ID까지 되돌린다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "failure",
                "audit",
                "version",
                "job",
                "attempt",
                "runtime",
                "targetRuntime",
                "add",
                "delete",
                "priorAudit",
                "deleteAudit",
                "addIssue",
                "addJob"
            })
    void auditFailureAndSuccessfulMutationRollbackEverything(String defect) throws Exception {
        Fixture f = fixture();
        issue(f, "CONTENT");
        long prior =
                id(
                        "INSERT INTO"
                            + " story_audit(story_id,version_id,actor_id,action,before_rev,after_rev,detail)"
                            + " VALUES"
                            + " (?,?,?,'CONTENT_READ',0,0,'{\"revisionScope\":\"CONTENT\",\"requestId\":\""
                                + UUID.randomUUID()
                                + "\"}'::jsonb) RETURNING id",
                        f.story(),
                        f.version(),
                        f.actor().principal().accountId());
        String before = preserved(f);
        String allAudits = rows("story_audit", "story_id=" + f.story());
        String sessionBefore =
                rows("admin_session", "account_id=" + f.actor().principal().accountId());
        String statement =
                switch (defect) {
                    case "failure" -> "RAISE EXCEPTION 'synthetic private audit failure';";
                    case "audit" -> "NEW.after_rev := 1;";
                    case "version" ->
                            "UPDATE story_version SET title='trigger mutation' WHERE id="
                                    + f.version()
                                    + ";";
                    case "job" ->
                            "UPDATE grade_job SET input_hash=repeat('b',64) WHERE batch_id="
                                    + f.source()
                                    + ";";
                    case "attempt" ->
                            "UPDATE grade_attempt SET provider_ref='trigger mutation' WHERE job_id="
                                    + f.job()
                                    + ";";
                    case "runtime" ->
                            "UPDATE grade_runtime SET epoch=epoch+1 WHERE id=" + f.runtime() + ";";
                    case "targetRuntime" ->
                            "UPDATE grade_runtime SET epoch=epoch+1 WHERE id="
                                    + f.targetRuntime()
                                    + ";";
                    case "add" ->
                            "INSERT INTO story_person(version_id,code,name) VALUES ("
                                    + f.version()
                                    + ",'ADDED','trigger mutation');";
                    case "delete" ->
                            "DELETE FROM story_person WHERE version_id=" + f.version() + ";";
                    case "deleteAudit" -> "DELETE FROM story_audit WHERE id=" + prior + ";";
                    case "addIssue" ->
                            "INSERT INTO"
                                + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                                + " VALUES ('"
                                    + UUID.randomUUID()
                                    + "',"
                                    + f.snapshot()
                                    + ","
                                    + f.runtime()
                                    + ","
                                    + f.source()
                                    + ",'INFRA','CRITICAL','OPEN','ENGINE_TIMEOUT');";
                    case "addJob" ->
                            "INSERT INTO"
                                + " grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)"
                                + " VALUES ('"
                                    + UUID.randomUUID()
                                    + "',"
                                    + f.snapshot()
                                    + ","
                                    + f.runtime()
                                    + ","
                                    + f.source()
                                    + ",'FULL',2,'STAGED',repeat('a',64),repeat('a',64),repeat('a',64));";
                    default ->
                            "UPDATE story_audit SET detail=detail || '{\"private\":\"trigger"
                                    + " mutation\"}'::jsonb WHERE id="
                                    + prior
                                    + ";";
                };
        trigger(f, statement);
        try {
            var result =
                    mvc.perform(request(f, f.actor(), "snapshotId=" + f.snapshot()))
                            .andExpect(status().isServiceUnavailable())
                            .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(PRIVATE, "synthetic", "items", "SQL");
            assertThat(preserved(f)).isEqualTo(before);
            assertThat(rows("story_audit", "story_id=" + f.story())).isEqualTo(allAudits);
            assertThat(rows("admin_session", "account_id=" + f.actor().principal().accountId()))
                    .isEqualTo(sessionBefore);
        } finally {
            removeTrigger(f);
        }
    }

    /**
     * HTTP/HTTPS 미인증과 기존 평문 unsafe 요청 거절을 확인하며 콘텐츠 감사를 남기지 않는다. 수동 주입한 유효 쿠키의 평문 GET은 기존 체인이 차단하지
     * 않는다. Secure 쿠키는 브라우저 전송 정책이며 이 시험이나 새 경로가 서버 TLS 거절을 보장한다는 의미가 아니다.
     *
     * @throws Exception 실제 HTTP 시험 실패 시
     */
    @Test
    void unauthenticatedReadsAndInsecureUnsafeRequestsDoNotRead() throws Exception {
        Fixture f = fixture();
        mvc.perform(https(get(root(f) + "?snapshotId=" + f.snapshot())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(root(f) + "?snapshotId=" + f.snapshot()))
                .andExpect(status().isUnauthorized());
        Cookie session = cookie(f.actor());
        var csrfResponse =
                mvc.perform(https(get("/admin/api/auth/csrf")).cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        Cookie csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String token = parse(csrfResponse.getContentAsString()).get("token").textValue();
        mvc.perform(
                        post("/admin/api/stories/" + f.code() + "/versions/1/review-precheck")
                                .cookie(session, csrf)
                                .header("X-CSRF-TOKEN", token)
                                .header("Origin", "http://localhost")
                                .contentType("application/json")
                                .content("{\"expectedRev\":\"0\"}"))
                .andExpect(status().isForbidden());
        assertThat(audits(f)).isZero();
    }

    /**
     * 첫 보존 지문 뒤 실제 감사 INSERT를 SQL advisory 잠금에서 멈추고 무관한 runtime 변경을 확정한다. 해당 변경은 이력 읽기를 실패시키지 않으며
     * 실제 CONTENT_READ와 관련 행 보존은 유지한다.
     *
     * @param populated true면 실제 OPEN 이력을 만들고 false면 허용된 빈 사본을 조회한다
     * @throws Exception 실제 동시 잠금·이력 조회·감사 대조 실패 시
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unrelatedRuntimeCommitBetweenFingerprintsDoesNotAbortHistory(boolean populated)
            throws Exception {
        Fixture f = fixture();
        if (populated) issue(f, "INFRA");
        long unrelated =
                runtime("UNRELATED_" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
        String before = preserved(f);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int lock = Math.toIntExact(f.version());
        trigger(f, "PERFORM pg_advisory_xact_lock(85011," + lock + ");");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var blocker =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        db.queryForList(
                                                                "SELECT"
                                                                    + " pg_advisory_xact_lock(85011,?)",
                                                                lock);
                                                        held.countDown();
                                                        await(release);
                                                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var read = pool.submit(() -> list(f));
            try {
                waitForAuditAdvisoryLock();
                db.update("UPDATE grade_runtime SET epoch=epoch+1 WHERE id=?", unrelated);
                assertThat(
                                db.queryForObject(
                                        "SELECT epoch FROM grade_runtime WHERE id=?",
                                        Long.class,
                                        unrelated))
                        .isEqualTo(1L);
            } finally {
                release.countDown();
            }
            blocker.get(10, TimeUnit.SECONDS);
            var page = read.get(10, TimeUnit.SECONDS);
            assertThat(page.items()).hasSize(populated ? 1 : 0);
            assertThat(preserved(f)).isEqualTo(before);
            assertThat(audits(f)).isEqualTo(1);
            assertThat(
                            db.queryForObject(
                                    "SELECT detail->>'requestId' FROM story_audit WHERE"
                                        + " version_id=? AND action='CONTENT_READ'",
                                    String.class,
                                    f.version()))
                    .isEqualTo(page.requestId().toString());
        } finally {
            release.countDown();
            removeTrigger(f);
        }
    }

    /**
     * 실제 감사 INSERT가 advisory 잠금을 기다린다는 DB 관측만으로 지문 사이의 중단을 확인한다.
     *
     * @throws Exception 관측 조회·중단 실패 시
     * @throws AssertionError 제한 시간 안에 실제 잠금 대기가 없을 경우
     */
    private void waitForAuditAdvisoryLock() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) {
            if (db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                                + " AND wait_event='advisory' AND query LIKE '%INSERT"
                                + " INTO%story_audit%'",
                            Integer.class)
                    > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("실제 CONTENT_READ 감사의 advisory 잠금 대기가 관측되지 않았습니다.");
    }

    /** 실제 JPA manager의 외부 거래는 별도 소유 읽기 경계에 들어오지 못한다. */
    @Test
    void rejectsAmbientJpaTransactions() {
        Fixture f = fixture();
        assertThat(manager).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
        TransactionTemplate tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> list(f))).hasMessage("STORY_UNAVAILABLE");
        assertThat(audits(f)).isZero();
    }

    /** 실제 JPA 부모 잠금 뒤 확정된 REVIEW 회수는 대기하던 역사 읽기에도 적용된다. */
    @Test
    void grantRevocationUnderParentLockFencesWaitingRead() throws Exception {
        Fixture f = fixture();
        Actor reader = actor();
        grant(f, reader);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoke =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        db.queryForObject(
                                                                "SELECT id FROM story WHERE id=?"
                                                                        + " FOR UPDATE",
                                                                Long.class,
                                                                f.story());
                                                        locked.countDown();
                                                        await(release);
                                                        db.update(
                                                                "UPDATE story_access SET"
                                                                    + " active_yn=false WHERE"
                                                                    + " story_id=? AND admin_id=?",
                                                                f.story(),
                                                                reader.principal().accountId());
                                                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch started = new CountDownLatch(1);
            var read =
                    pool.submit(
                            () -> {
                                started.countDown();
                                return reviews.getExecutionIssueList(
                                        reader.sid(),
                                        reader.principal(),
                                        f.code(),
                                        1,
                                        Long.toString(f.snapshot()),
                                        null,
                                        null,
                                        null,
                                        UUID.randomUUID());
                            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> read.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            revoke.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> read.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.reasoning.common.auth.service.AuthException.class)
                    .satisfies(error -> assertThat(error.getCause()).hasMessage("NOT_FOUND"));
        } finally {
            release.countDown();
        }
        assertThat(audits(f)).isZero();
    }

    /** 실제 저장 확인 참조의 양 끝 길이를 받아들이고64자 초과는 fail-closed다. */
    @Test
    void resolutionUsesActualContractBoundsAndAllClosedReasons() throws Exception {
        for (String reason :
                List.of("GRADING_FIX_VERIFIED", "INFRA_RECOVERED", "OBSERVATION_COMPLETED")) {
            for (int length : List.of(8, 64, 65)) {
                Fixture f = fixture();
                String ref = "A".repeat(length);
                resolveFixture(
                        f,
                        issue(f, "OBSERVATION"),
                        f.actor(),
                        "{\"reasonCode\":\"" + reason + "\",\"verificationRef\":\"" + ref + "\"}");
                if (length == 65) {
                    mvc.perform(request(f, f.actor(), "snapshotId=" + f.snapshot()))
                            .andExpect(status().isServiceUnavailable());
                    assertThat(audits(f)).isZero();
                } else {
                    JsonNode row = read(f, "state=RESOLVED").get("items").get(0);
                    assertThat(row.get("resolution").get("reasonCode").asText()).isEqualTo(reason);
                    assertThat(row.get("resolution").get("verificationRef").asText())
                            .isEqualTo(ref);
                }
            }
        }
    }

    /** 실제 부모/전체 사본은 V13 payload에만, 출처 집합/작업/시도는 실제 FK에 저장한다. */
    private Fixture fixture() {
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
                            + " VALUES (?,1,?,'RULE_20260924',?,?) RETURNING id",
                        story,
                        PRIVATE,
                        actor.principal().accountId(),
                        actor.principal().accountId());
        var payload = FrozenSnapshotContractTest.complete();
        payload.put("storyCode", code);
        long snapshot =
                id(
                        "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,?::jsonb,?,?) RETURNING id",
                        version,
                        new String(SnapshotJson.encode(payload), StandardCharsets.UTF_8),
                        UUID.randomUUID(),
                        actor.principal().accountId());
        String runtimeCode = "ISSUE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        long runtime = runtime(runtimeCode);
        long targetRuntime = runtime(runtimeCode + "_FIX");
        long source = batch(snapshot, runtime, actor);
        long target = batch(snapshot, targetRuntime, actor);
        long job =
                id(
                        "INSERT INTO"
                            + " grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)"
                            + " VALUES"
                            + " (?,?,?,?,'FULL',1,'STAGED',repeat('a',64),repeat('a',64),repeat('a',64))"
                            + " RETURNING id",
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        source);
        db.update(
                "INSERT INTO"
                    + " grade_attempt(job_id,attempt_no,lease_gen,worker_key,state,ended_at,error_code)"
                    + " VALUES"
                    + " (?,1,1,'HISTORY_FIXTURE','FAILED',clock_timestamp(),'ENGINE_TIMEOUT')",
                job);
        db.update(
                "INSERT INTO story_person(version_id,code,name,secret_text) VALUES (?,'P1','합성',?)",
                version,
                PRIVATE);
        db.update(
                "UPDATE story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?",
                snapshot,
                version);
        Fixture f =
                new Fixture(
                        actor,
                        code,
                        story,
                        version,
                        snapshot,
                        runtime,
                        runtimeCode,
                        targetRuntime,
                        source,
                        target,
                        job);
        grant(f, actor);
        return f;
    }

    /**
     * 설치 승인·실행 결과를 제조하지 않는 실제 저장 runtime 메타데이터 fixture다.
     *
     * @param code fixture의 불변 공개 설정 코드
     * @return 실제 runtime 내부 ID
     */
    private long runtime(String code) {
        return id(
                "INSERT INTO grade_runtime(code,config_hash,config_data,state) VALUES"
                    + " (?,repeat('a',64),jsonb_build_object('private',?), 'AVAILABLE') RETURNING"
                    + " id",
                code,
                PRIVATE);
    }

    /**
     * source/target 집합은 같은 실제 사본 FK와 각자의 runtime에 결속한다.
     *
     * @param snapshot 실제 사본 ID
     * @param runtime 실제 설정 ID
     * @param actor fixture 생성 관리자
     * @return 실제 BATCH 내부 ID
     */
    private long batch(long snapshot, long runtime, Actor actor) {
        return id(
                "INSERT INTO"
                    + " grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)"
                    + " VALUES"
                    + " (?,?,?,'REVIEW',repeat('a',64),repeat('a',64),repeat('a',64),repeat('a',64),0,'RUNNING',3,?)"
                    + " RETURNING id",
                UUID.randomUUID(),
                snapshot,
                runtime,
                actor.principal().accountId());
    }

    /**
     * 실제 V17 OPEN 출처 행을 생성하며 집계나 해소 API를 주장하지 않는다.
     *
     * @param f 실제 부모/출처 fixture
     * @param kind V17 지적 종류
     * @return 실제 지적 내부 ID
     */
    private long issue(Fixture f, String kind) {
        return issue(f.snapshot(), f.runtime(), f.source(), kind);
    }

    /**
     * source/kind 유일성과 사본/runtime/BATCH 삼중 FK를 그대로 적용한다.
     *
     * @param snapshot 실제 사본 ID
     * @param runtime 실제 출처 설정 ID
     * @param batch 실제 출처 집합 ID
     * @param kind V17 지적 종류
     * @return 실제 OPEN 지적 ID
     */
    private long issue(long snapshot, long runtime, long batch, String kind) {
        return id(
                "INSERT INTO"
                    + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                    + " VALUES (?,?,?,?,?,'CRITICAL','OPEN','PRIVATE_SOURCE_REASON') RETURNING id",
                UUID.randomUUID(),
                snapshot,
                runtime,
                batch,
                kind);
    }

    /**
     * V17 단회 OPEN→RESOLVED의 구조 시험 자료다. 운영 검증·효력·해소 서비스가 아니다.
     *
     * @param f 동일 사본의 실제 대상 집합을 가진 fixture
     * @param issue 실제 OPEN 지적 ID
     * @param resolver 당시 관리자
     * @param resolution 저장할 구조 시험 JSON 객체
     */
    private void resolveFixture(Fixture f, long issue, Actor resolver, String resolution) {
        db.update(
                "UPDATE execution_issue SET state='RESOLVED',resolved_batch_id=?,resolved_by=?,"
                        + "resolved_at=clock_timestamp(),resolution_data=?::jsonb WHERE id=?",
                f.target(),
                resolver.principal().accountId(),
                resolution,
                issue);
    }

    /** 실제 계정·등록 자격·Spring Session·업무 세션을 동일 행위자에 결속한다. */
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
                "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO admin_session"
                    + "(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " SELECT ?,?,?,1,'ACTIVE',n,n,n+interval '8 hours',n,n FROM t",
                principal.sessionKey(),
                id,
                crypto.sessionHash(prepared.id()));
        return new Actor(prepared.id(), principal);
    }

    /**
     * 현재 REVIEW 관계만 부여한다.
     *
     * @param f 실제 사건 fixture
     * @param actor 현재 관계를 부여할 관리자
     */
    private void grant(Fixture f, Actor actor) {
        db.update(
                "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                        + " (?,?,'REVIEW',?)",
                f.story(),
                actor.principal().accountId(),
                f.actor().principal().accountId());
    }

    /**
     * 실제 HTTP 성공·no-store를 확인하고 JSON만 반환한다.
     *
     * @param f 조회 부모와 행위자 fixture
     * @param query snapshotId 외 추가 query 또는 null
     * @return 실제 성공 응답 JSON
     * @throws Exception HTTP 시험 실패 시
     */
    private JsonNode read(Fixture f, String query) throws Exception {
        var response =
                mvc.perform(
                                request(
                                        f,
                                        f.actor(),
                                        "snapshotId="
                                                + f.snapshot()
                                                + (query == null ? "" : "&" + query)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return parse(response.getContentAsString());
    }

    /**
     * 실제 저장 principal로 서비스의 현재 인가 경계에 진입한다.
     *
     * @param f 조회 부모와 행위자 fixture
     * @return 실제 서비스 페이지
     * @throws com.reasoning.common.auth.service.AuthException 현재 자격·감사·거래 경계 실패 시
     */
    private StoryReviewService.ExecutionIssuePage list(Fixture f) {
        return reviews.getExecutionIssueList(
                f.actor().sid(),
                f.actor().principal(),
                f.code(),
                1,
                Long.toString(f.snapshot()),
                null,
                null,
                null,
                UUID.randomUUID());
    }

    /**
     * 실제 저장 어댑터가 발급하는 __Host 쿠키만 사용한다.
     *
     * @param actor 실제 저장 세션의 관리자
     * @return 실제 __Host-admin-session 쿠키
     */
    private Cookie cookie(Actor actor) {
        var response = new MockHttpServletResponse();
        sessions.issueCookie(actor.sid(), new MockHttpServletRequest(), response);
        return response.getCookie("__Host-admin-session");
    }

    /**
     * query를 그대로 서버에 전달하여 반복 키도 실제 서블릿에서 검증한다.
     *
     * @param f 조회 부모 fixture
     * @param actor 실제 세션 관리자
     * @param query 반복 키를 포함할 수 있는 시험 query
     * @return HTTPS 요청 builder
     */
    private MockHttpServletRequestBuilder request(Fixture f, Actor actor, String query) {
        return https(get(root(f) + "?" + query)).cookie(cookie(actor));
    }

    /**
     * HTTPS 처리 조건을 기존 보안 체인에 제공한다.
     *
     * @param request 시험 요청 builder
     * @return HTTPS scheme/port를 지정한 요청
     */
    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder request) {
        return request.secure(true)
                .with(
                        r -> {
                            r.setScheme("https");
                            r.setServerPort(443);
                            return r;
                        });
    }

    /**
     * PT-A11만 가리키며 해소 경로는 만들지 않는다.
     *
     * @param f 조회 부모 fixture
     * @return 정확한 실행 지적 목록 경로
     */
    private static String root(Fixture f) {
        return "/admin/api/stories/" + f.code() + "/versions/1/execution-issues";
    }

    /**
     * 시험 소유의 감사 INSERT 트리거만 만들며 해당 부모에 한정한다.
     *
     * @param f 실패를 주입할 부모 fixture
     * @param statement 시험 소유 SQL 변경 또는 실패 구문
     */
    private void trigger(Fixture f, String statement) {
        String name = "issue_read_" + f.version();
        db.execute(
                "CREATE FUNCTION "
                        + name
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.version_id="
                        + f.version()
                        + " THEN "
                        + statement
                        + " END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + name
                        + " BEFORE INSERT ON story_audit FOR EACH ROW EXECUTE FUNCTION "
                        + name
                        + "()");
    }

    /**
     * 시험이 만든 객체만 finally에서 제거한다.
     *
     * @param f 트리거를 만든 부모 fixture
     */
    private void removeTrigger(Fixture f) {
        String name = "issue_read_" + f.version();
        db.execute("DROP TRIGGER " + name + " ON story_audit");
        db.execute("DROP FUNCTION " + name + "()");
    }

    /**
     * 원문을 포함한 모든 보존 행/ID를 시험 안에서 직접 대조하며 실제 읽기 감사만 제외한다.
     *
     * @param f 보존을 확인할 부모/출처 fixture
     * @return 전체 저장 행의 시험 전용 비교 문자열
     */
    private String preserved(Fixture f) {
        List<String> state = new ArrayList<>();
        state.add(rows("story", "id=" + f.story()));
        state.add(rows("story_version", "id=" + f.version()));
        for (String table :
                List.of(
                        "story_person",
                        "story_role",
                        "story_pair",
                        "story_clue",
                        "clue_role",
                        "story_hint",
                        "story_event",
                        "story_fact",
                        "story_rubric",
                        "rubric_clue",
                        "grade_sample",
                        "review_snapshot")) state.add(rows(table, "version_id=" + f.version()));
        for (String table : List.of("grade_batch", "grade_job", "execution_issue", "review_record"))
            state.add(rows(table, "snapshot_id=" + f.snapshot()));
        state.add(
                rows(
                        "grade_attempt",
                        "job_id IN (SELECT id FROM grade_job WHERE snapshot_id="
                                + f.snapshot()
                                + ")"));
        state.add(
                rows(
                        "grade_event",
                        "job_id IN (SELECT id FROM grade_job WHERE snapshot_id="
                                + f.snapshot()
                                + ")"));
        state.add(rows("grade_runtime", "id IN (" + f.runtime() + "," + f.targetRuntime() + ")"));
        state.add(rows("test_action", "scope_key='version:" + f.version() + "'"));
        state.add(rows("test_audit", "scope_key='version:" + f.version() + "'"));
        return String.join("\n", state);
    }

    /**
     * 동일 실제 행 전체를 JSONB 정렬로 대조한다.
     *
     * @param table 시험 코드가 지정한 실제 테이블
     * @param predicate 시험 코드의 고정 부모 조건
     * @return 실제 전체 행 배열 문자열
     */
    private String rows(String table, String predicate) {
        return db.queryForObject(
                "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY"
                        + " to_jsonb(t)::text),'[]'::jsonb)::text FROM "
                        + table
                        + " t WHERE "
                        + predicate,
                String.class);
    }

    private long id(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    private UUID batchKey(long batch) {
        return db.queryForObject("SELECT batch_key FROM grade_batch WHERE id=?", UUID.class, batch);
    }

    private int audits(Fixture f) {
        return db.queryForObject(
                "SELECT count(*) FROM story_audit WHERE version_id=? AND action='CONTENT_READ'",
                Integer.class,
                f.version());
    }

    private static JsonNode parse(String json) {
        return SnapshotJson.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> names(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /**
     * 잠금 시험을 유한 시간 안에 해제하며 중단 상태를 보존한다.
     *
     * @param latch 해제 신호
     * @throws AssertionError 신호가10초 안에 오지 않거나 중단된 경우
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("합성 부모 잠금 해제 상한");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("합성 부모 잠금 대기 중단");
        }
    }

    private record Actor(String sid, AdminPrincipal principal) {}

    private record Fixture(
            Actor actor,
            String code,
            long story,
            long version,
            long snapshot,
            long runtime,
            String runtimeCode,
            long targetRuntime,
            long source,
            long target,
            long job) {}
}
