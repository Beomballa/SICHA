package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.story.service.StoryGradeSampleService;
import com.reasoning.common.story.service.StoryRubricService;
import com.reasoning.common.story.service.StorySampleCheckService;
import com.reasoning.common.story.service.StorySampleCheckService.CheckResult;
import com.reasoning.common.story.service.StoryService;

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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 합성 자료만 사용하는 SR-09 회귀이며 실제 사건의 사람 검토나 모델 실행을 주장하지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StorySampleCheckIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    /** 클래스 전용 PostgreSQL과 합성 키 파일만 등록하며 로컬 DB를 사용하지 않는다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 91));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 92));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 93));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    @Autowired JdbcTemplate db;
    @Autowired CryptoService crypto;
    @Autowired AdminSessionAdapter sessions;
    @Autowired StoryService stories;
    @Autowired StoryRubricService rubrics;
    @Autowired StoryGradeSampleService samples;
    @Autowired StorySampleCheckService checker;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test
    void explicitConfirmationPreservesContentAndSameActorIsNoChange() {
        Dataset data = dataset();
        List<Map<String, Object>> content = content(data);
        Map<String, Object> versionContent = versionContent(data);
        UUID requestId = UUID.randomUUID();
        CheckResult checked = check(data, data.owner(), "1", requestId);
        assertThat(checked).isEqualTo(new CheckResult("2", 5, true, requestId));
        assertThat(content(data)).isEqualTo(content);
        assertThat(versionContent(data)).isEqualTo(versionContent);
        assertMarks(data, data.owner().principal().accountId());
        Map<String, Object> metadata =
                db.queryForMap(
                        "SELECT edit_rev,updated_by,updated_at FROM story_version WHERE id=?",
                        data.version());
        List<Map<String, Object>> sampleMetadata = sampleMetadata(data);
        UUID againId = UUID.randomUUID();
        assertThat(check(data, data.owner(), "2", againId))
                .isEqualTo(new CheckResult("2", 5, false, againId));
        assertThat(
                        db.queryForMap(
                                "SELECT edit_rev,updated_by,updated_at FROM story_version WHERE"
                                        + " id=?",
                                data.version()))
                .isEqualTo(metadata);
        assertThat(sampleMetadata(data)).isEqualTo(sampleMetadata);
        assertThat(auditCount(data)).isEqualTo(1);
        JsonNode detail =
                json(
                        db.queryForObject(
                                "SELECT detail::text FROM story_audit WHERE version_id=? AND"
                                        + " action='SAMPLES_CHECKED'",
                                String.class,
                                data.version()));
        assertThat(detail.path("checkedCount").intValue()).isEqualTo(5);
        assertThat(detail.path("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(detail.toString()).doesNotContain("synthetic report", "합성 근거");
    }

    @Test
    void differentEditorReplacesMarksOnceAndContentChangeInvalidatesAll() {
        Dataset data = dataset();
        Account editor = account(false);
        grant(data, editor, "EDIT");
        check(data, data.owner(), "1", UUID.randomUUID());
        assertThat(check(data, editor, "2", UUID.randomUUID()).editRev()).isEqualTo("3");
        assertMarks(data, editor.principal().accountId());
        assertThat(auditCount(data)).isEqualTo(2);
        stories.updateStorySection(
                editor.sid(),
                editor.principal(),
                data.code(),
                1,
                "basic",
                "3",
                mapper.createObjectNode().put("title", "변경된 합성 제목"),
                UUID.randomUUID());
        assertThat(revision(data)).isEqualTo(4);
        assertUnmarked(data);
    }

    @Test
    void sampleContentChangeAlsoClearsMarks() {
        Dataset data = dataset();
        check(data, data.owner(), "1", UUID.randomUUID());
        samples.updateGradeSample(
                data.owner().sid(),
                data.owner().principal(),
                data.code(),
                1,
                "FULL",
                "2",
                mapper.createObjectNode().put("reason", "변경된 합성 근거"),
                UUID.randomUUID());
        assertThat(revision(data)).isEqualTo(3);
        assertUnmarked(data);
    }

    @Test
    void staleRevisionRejectedEvenForPreviouslyCheckedActor() {
        Dataset data = dataset();
        check(data, data.owner(), "1", UUID.randomUUID());
        denied("EDIT_CONFLICT", () -> check(data, data.owner(), "1", UUID.randomUUID()));
        assertThat(revision(data)).isEqualTo(2);
        assertThat(auditCount(data)).isEqualTo(1);
        assertMarks(data, data.owner().principal().accountId());
    }

    /** 같은 수정번호의 병렬 확인은 부모 잠금으로 직렬화되어 한 요청만 확정된다. */
    @Test
    void concurrentConfirmationsUseOneLockedRevision() throws Exception {
        Dataset data = dataset();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<String> attempt =
                () -> {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("합성 시작 신호 지연");
                    try {
                        CheckResult result = check(data, data.owner(), "1", UUID.randomUUID());
                        assertThat(result.changed()).isTrue();
                        return result.editRev();
                    } catch (AuthException failure) {
                        return failure.code();
                    }
                };
        try {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(
                            List.of(
                                    first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                                    second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("2", "EDIT_CONFLICT");
        } finally {
            executor.shutdownNow();
        }
        assertThat(revision(data)).isEqualTo(2);
        assertThat(auditCount(data)).isEqualTo(1);
        assertMarks(data, data.owner().principal().accountId());
    }

    @Test
    void auditFailureRollsBackMarksRevisionAndTimestamps() {
        Dataset data = dataset();
        Map<String, Object> before =
                db.queryForMap(
                        "SELECT edit_rev,updated_by,updated_at FROM story_version WHERE id=?",
                        data.version());
        List<Map<String, Object>> samplesBefore = sampleMetadata(data);
        db.execute(
                "CREATE FUNCTION fail_sample_check_audit() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN IF NEW.action='SAMPLES_CHECKED' THEN RAISE EXCEPTION 'synthetic"
                        + " outage'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER fail_sample_check_audit BEFORE INSERT ON story_audit FOR EACH ROW"
                        + " EXECUTE FUNCTION fail_sample_check_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> check(data, data.owner(), "1", UUID.randomUUID()));
        } finally {
            db.execute("DROP TRIGGER fail_sample_check_audit ON story_audit");
            db.execute("DROP FUNCTION fail_sample_check_audit()");
        }
        assertThat(
                        db.queryForMap(
                                "SELECT edit_rev,updated_by,updated_at FROM story_version WHERE"
                                        + " id=?",
                                data.version()))
                .isEqualTo(before);
        assertThat(sampleMetadata(data)).isEqualTo(samplesBefore);
        assertThat(auditCount(data)).isZero();
        assertUnmarked(data);
    }

    /** 정상 입력을 오류 기대값으로 잘못 표시한 자료는 사람 확인 대상으로 확정하지 않는다. */
    @Test
    void validReportCannotBeConfirmedAsInputError() {
        Dataset data = dataset();
        db.update(
                "UPDATE grade_sample SET input_data=(SELECT input_data FROM grade_sample WHERE"
                    + " version_id=? AND code='FULL') WHERE version_id=? AND code='INPUT'",
                data.version(),
                data.version());

        denied("SAMPLE_NOT_READY", () -> check(data, data.owner(), "1", UUID.randomUUID()));
        assertThat(revision(data)).isEqualTo(1);
        assertThat(auditCount(data)).isZero();
        assertUnmarked(data);
    }

    @Test
    void overflowIsSafeButSameActorNoChangeDoesNotNeedIncrement() {
        Dataset data = dataset();
        db.update("UPDATE story_version SET edit_rev=? WHERE id=?", Long.MAX_VALUE, data.version());
        denied(
                "EDIT_CONFLICT",
                () -> check(data, data.owner(), Long.toString(Long.MAX_VALUE), UUID.randomUUID()));
        assertUnmarked(data);
        assertThat(revision(data)).isEqualTo(Long.MAX_VALUE);
        db.update(
                "UPDATE grade_sample SET checked_by=? WHERE version_id=?",
                data.owner().principal().accountId(),
                data.version());
        assertThat(
                        check(data, data.owner(), Long.toString(Long.MAX_VALUE), UUID.randomUUID())
                                .changed())
                .isFalse();
        assertThat(auditCount(data)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "revokedEdit",
                "reviewOnly",
                "expired",
                "authRev",
                "inactiveAccount",
                "inactiveStory",
                "inactiveVersion",
                "reviewState"
            })
    void currentPrivilegesSessionAndDraftStateAreRequired(String condition) {
        Dataset data = dataset();
        Account editor = account(false);
        grant(data, editor, "EDIT");
        String error;
        switch (condition) {
            case "revokedEdit" -> {
                db.update("UPDATE story_access SET active_yn=false WHERE story_id=?", data.story());
                error = "NOT_FOUND";
            }
            case "reviewOnly" -> {
                db.update(
                        "UPDATE admin_account SET can_review=true WHERE id=?",
                        editor.principal().accountId());
                db.update("UPDATE story_access SET active_yn=false WHERE story_id=?", data.story());
                grant(data, editor, "REVIEW");
                error = "FORBIDDEN";
            }
            case "expired" -> {
                db.update(
                        "UPDATE admin_session SET started_at=now()-interval '9 hours',"
                                + " expires_at=now()-interval '1 hour'"
                                + " WHERE session_key=?",
                        editor.principal().sessionKey());
                error = "AUTH_REQUIRED";
            }
            case "authRev" -> {
                db.update(
                        "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?",
                        editor.principal().accountId());
                error = "AUTH_REQUIRED";
            }
            case "inactiveAccount" -> {
                db.update(
                        "UPDATE admin_account SET active_yn=false WHERE id=?",
                        editor.principal().accountId());
                error = "AUTH_REQUIRED";
            }
            case "inactiveStory" -> {
                db.update("UPDATE story SET active_yn=false WHERE id=?", data.story());
                error = "NOT_FOUND";
            }
            case "inactiveVersion" -> {
                db.update("UPDATE story_version SET active_yn=false WHERE id=?", data.version());
                error = "NOT_FOUND";
            }
            case "reviewState" -> {
                long snapshot =
                        db.queryForObject(
                                "INSERT INTO"
                                    + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                                    + " VALUES (?,1,'{}'::jsonb,?,?) RETURNING id",
                                Long.class,
                                data.version(),
                                UUID.randomUUID(),
                                data.owner().principal().accountId());
                db.update(
                        "UPDATE story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?",
                        snapshot,
                        data.version());
                error = "STATE_CONFLICT";
            }
            default -> throw new IllegalArgumentException(condition);
        }
        denied(error, () -> check(data, editor, "1", UUID.randomUUID()));
        assertThat(revision(data)).isEqualTo(1);
        assertUnmarked(data);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "empty",
                "missingInputError",
                "missingEngineError",
                "missingStage",
                "nullInput",
                "nullExpect",
                "missingFormat",
                "wrongFormat",
                "missingReport",
                "missingReportField",
                "unknownCulprit",
                "inactiveCulprit",
                "unknownInputField",
                "missingItem",
                "duplicateItem",
                "unknownItem",
                "unregisteredScore",
                "requiredMismatch",
                "optionalMismatch",
                "sumMismatch",
                "successMismatch",
                "blankItemReason",
                "longItemReason",
                "blankReason",
                "errorItems",
                "errorScore",
                "errorSuccess",
                "errorCode",
                "errorState",
                "errorAttempt",
                "missingFault",
                "wrongFault",
                "wrongRuns",
                "normalFault",
                "nullRule",
                "inactiveFact",
                "inactiveClueLink",
                "badWeights",
                "missingRequired",
                "unregisteredPass"
            })
    void incompleteOrInconsistentDatasetNeverPartiallyMarks(String defect) {
        Dataset data = dataset();
        mutate(data, defect);
        List<Map<String, Object>> contentBefore = content(data);
        denied("SAMPLE_NOT_READY", () -> check(data, data.owner(), "1", UUID.randomUUID()));
        assertUnmarked(data);
        assertThat(revision(data)).isEqualTo(1);
        assertThat(auditCount(data)).isZero();
        assertThat(content(data)).isEqualTo(contentBefore);
    }

    @Test
    void inactiveDraftIsIgnoredAndMalformedInputErrorReportIsPreserved() {
        Dataset data = dataset();
        db.update(
                "INSERT INTO grade_sample(version_id,code,active_yn) VALUES (?,'IGNORED',false)",
                data.version());
        ObjectNode malformed = (ObjectNode) stored(data, "INPUT", "input_data");
        malformed.set(
                "report",
                mapper.createObjectNode().put("culpritCode", "UNREGISTERED").put("method", 42));
        saveJson(data, "INPUT", "input_data", malformed);
        List<Map<String, Object>> before = content(data);
        assertThat(check(data, data.owner(), "1", UUID.randomUUID()).checkedCount()).isEqualTo(5);
        assertThat(content(data)).isEqualTo(before);
        assertThat(
                        db.queryForObject(
                                "SELECT checked_by FROM grade_sample WHERE version_id=? AND"
                                        + " code='IGNORED'",
                                Long.class,
                                data.version()))
                .isNull();
    }

    @Test
    void validationStillRunsForAlreadyMarkedDataset() {
        Dataset data = dataset();
        check(data, data.owner(), "1", UUID.randomUUID());
        mutate(data, "missingStage");
        denied("SAMPLE_NOT_READY", () -> check(data, data.owner(), "2", UUID.randomUUID()));
        assertThat(revision(data)).isEqualTo(2);
        assertThat(auditCount(data)).isEqualTo(1);
        assertMarks(data, data.owner().principal().accountId());
    }

    @Test
    void httpUsesStrictEightKiBBodyCurrentSessionCsrfOriginAndNoStore() throws Exception {
        Dataset data = dataset();
        MockHttpServletResponse response = new MockHttpServletResponse();
        sessions.issueCookie(data.owner().sid(), new MockHttpServletRequest(), response);
        Cookie session = response.getCookie("__Host-admin-session");
        var csrfResult =
                mvc.perform(get("/admin/api/auth/csrf").secure(true))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrf = csrfResult.getResponse().getCookie("__Host-admin-csrf");
        String token = json(csrfResult.getResponse().getContentAsString()).path("token").asText();
        String path = "/admin/api/stories/" + data.code() + "/versions/1/grade-samples/check";
        mvc.perform(
                        httpsPost(path)
                                .cookie(session)
                                .contentType("application/json")
                                .content("{\"expectedRev\":\"1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        httpsPost(path)
                                .cookie(session, csrf)
                                .header("X-CSRF-TOKEN", token)
                                .header("Origin", "https://foreign.example")
                                .contentType("application/json")
                                .content("{\"expectedRev\":\"1\"}"))
                .andExpect(status().isForbidden());
        for (String body :
                List.of(
                        "{\"expectedRev\":\"1\",\"extra\":0}",
                        "{\"expectedRev\":\"1\",\"expectedRev\":\"1\"}",
                        "{\"expectedRev\":1}",
                        "{\"expectedRev\":null}",
                        "{\"expectedRev\":\"1\"} {}")) {
            mvc.perform(
                            httpsPost(path)
                                    .cookie(session, csrf)
                                    .header("X-CSRF-TOKEN", token)
                                    .header("Origin", "https://localhost")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        httpsPost(path)
                                .cookie(session, csrf)
                                .header("X-CSRF-TOKEN", token)
                                .header("Origin", "https://localhost")
                                .contentType("application/json")
                                .content("{\"expectedRev\":\"" + "1".repeat(8192) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(
                        httpsPost(path)
                                .cookie(csrf)
                                .header("X-CSRF-TOKEN", token)
                                .header("Origin", "https://localhost")
                                .contentType("application/json")
                                .content("{\"expectedRev\":\"1\"}"))
                .andExpect(status().isUnauthorized());
        var checked =
                mvc.perform(
                                httpsPost(path)
                                        .cookie(session, csrf)
                                        .header("X-CSRF-TOKEN", token)
                                        .header("Origin", "https://localhost")
                                        .contentType("application/json")
                                        .content("{\"expectedRev\":\"1\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(checked.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(json(checked.getResponse().getContentAsString()).path("checkedCount").intValue())
                .isEqualTo(5);
        var conflict =
                mvc.perform(
                                httpsPost(path)
                                        .cookie(session, csrf)
                                        .header("X-CSRF-TOKEN", token)
                                        .header("Origin", "https://localhost")
                                        .contentType("application/json")
                                        .content("{\"expectedRev\":\"1\"}"))
                        .andExpect(status().isConflict())
                        .andReturn();
        assertThat(json(conflict.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("EDIT_CONFLICT");
        assertThat(conflict.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    /** HTTPS의 출처 대조에 사용하는 요청 scheme과 기본 포트를 함께 설정한다. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            httpsPost(String path) {
        return post(path)
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerPort(443);
                            return request;
                        });
    }

    /** 하나의 합성 소항목 집합과 0점·만점·방법 중간 단계·두 오류를 만들며 실제 사건은 읽지 않는다. */
    private Dataset dataset() {
        Account owner = account(true);
        String code =
                stories.createStory(
                                owner.sid(),
                                owner.principal(),
                                UUID.randomUUID(),
                                "SR09 합성 사건",
                                UUID.randomUUID())
                        .storyCode();
        long story = db.queryForObject("SELECT id FROM story WHERE code=?", Long.class, code);
        long version =
                db.queryForObject(
                        "SELECT id FROM story_version WHERE story_id=?", Long.class, story);
        Dataset data = new Dataset(owner, code, story, version);
        db.update("INSERT INTO story_person(version_id,code,name) VALUES (?,'P','합성 인물')", version);
        db.update(
                "INSERT INTO story_fact(version_id,code,statement,truth,basis) VALUES (?,'F','합성"
                        + " 명제','TRUE','합성 근거')",
                version);
        db.update(
                "INSERT INTO story_clue(version_id,code,scope,title,body) VALUES"
                        + " (?,'C','COMMON','합성 단서','합성 본문')",
                version);
        rubrics.createRubric(
                owner.sid(),
                owner.principal(),
                code,
                1,
                "0",
                mapper.createObjectNode().put("code", "CULPRIT").put("category", "CULPRIT"),
                UUID.randomUUID());
        for (String category : List.of("METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            int max = Map.of("METHOD", 20, "TIME", 15, "MOTIVE", 10, "EVIDENCE", 30).get(category);
            boolean required = category.equals("METHOD") || category.equals("EVIDENCE");
            db.update(
                    "INSERT INTO"
                        + " story_rubric(version_id,code,category,max_score,required_yn,pass_score,rule_data)"
                        + " VALUES (?,?,?,?,?,?,?::jsonb)",
                    version,
                    category,
                    category,
                    max,
                    required,
                    required ? max : null,
                    rule(max, required, category.equals("METHOD")).toString());
            db.update(
                    "INSERT INTO rubric_clue(version_id,rubric_code,clue_code,link_text) VALUES"
                            + " (?,?,'C','합성 연결')",
                    version,
                    category);
        }
        insertSample(data, "ZERO", "GRADED", 0);
        insertSample(data, "FULL", "GRADED", 100);
        insertSample(data, "MID", "GRADED", 90);
        insertSample(data, "INPUT", "INPUT_ERROR", null);
        insertSample(data, "ENGINE", "ENGINE_ERROR", null);
        return data;
    }

    /** 합성 사실·단서만 참조하는 0/만점과 선택적인 방법 10점의 유한 규칙을 만든다. */
    private ObjectNode rule(int max, boolean required, boolean partial) {
        ObjectNode rule = mapper.createObjectNode().put("formatNo", 1);
        if (required) rule.put("requiredNotice", "합성 필수 목표");
        else rule.putNull("requiredNotice");
        ObjectNode claim =
                rule.putArray("claims").addObject().put("code", "CLAIM").put("meaning", "합성 명제");
        claim.putArray("factCodes").add("F");
        claim.putArray("exampleClueRoutes").addArray().add("C");
        ArrayNode levels = rule.putArray("levels");
        levels.addObject().put("code", "ZERO").put("score", 0).putArray("routes");
        if (partial)
            levels.addObject()
                    .put("code", "PARTIAL")
                    .put("score", 10)
                    .putArray("routes")
                    .addArray()
                    .add("CLAIM");
        levels.addObject()
                .put("code", "FULL")
                .put("score", max)
                .putArray("routes")
                .addArray()
                .add("CLAIM");
        rule.putArray("contradictions");
        return rule;
    }

    /** 기대 점수는 합성 단계 값이며 실제 보고서의 의미 정답으로 추론하거나 실행하지 않는다. */
    private void insertSample(Dataset data, String code, String kind, Integer total) {
        ObjectNode input = mapper.createObjectNode().put("formatNo", 1);
        if (kind.equals("INPUT_ERROR")) input.putNull("report");
        else
            input.set(
                    "report",
                    mapper.createObjectNode()
                            .put("culpritCode", "P")
                            .put("method", "synthetic report")
                            .put("time", "")
                            .put("motive", "")
                            .put("evidence", ""));
        if (kind.equals("ENGINE_ERROR"))
            input.set("fault", mapper.createObjectNode().put("type", "TIMEOUT").put("failRuns", 3));
        else input.putNull("fault");
        ObjectNode expect = mapper.createObjectNode().put("formatNo", 1).put("kind", kind);
        if (kind.equals("GRADED")) {
            ArrayNode items = expect.putArray("items");
            for (String category : List.of("CULPRIT", "METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
                int max =
                        Map.of(
                                        "CULPRIT",
                                        25,
                                        "METHOD",
                                        20,
                                        "TIME",
                                        15,
                                        "MOTIVE",
                                        10,
                                        "EVIDENCE",
                                        30)
                                .get(category);
                int score = total == 0 ? 0 : total == 90 && category.equals("METHOD") ? 10 : max;
                ObjectNode item =
                        items.addObject()
                                .put("rubricCode", category)
                                .put("score", score)
                                .put("reason", "합성 항목 근거");
                if (category.equals("TIME") || category.equals("MOTIVE"))
                    item.putNull("requiredMet");
                else item.put("requiredMet", score == max);
            }
            expect.putNull("error");
        } else {
            expect.putNull("items");
            expect.set(
                    "error",
                    mapper.createObjectNode()
                            .put(
                                    "code",
                                    kind.equals("INPUT_ERROR")
                                            ? "INVALID_REPORT"
                                            : "GRADING_UNAVAILABLE")
                            .put("state", kind.equals("INPUT_ERROR") ? "REJECTED" : "SYSTEM_ERROR")
                            .putNull("score")
                            .put("attemptDelta", 0));
        }
        db.update(
                "INSERT INTO"
                    + " grade_sample(version_id,code,input_data,expect_data,expected_score,expected_success,reason)"
                    + " VALUES (?,?,?::jsonb,?::jsonb,?,?,?)",
                data.version(),
                code,
                input.toString(),
                expect.toString(),
                total,
                total == null ? null : total == 100,
                "합성 근거");
    }

    /** 합성 계정의 실제 저장 세션·자격증명을 등록해 제품 인증 검증기를 그대로 사용한다. */
    private Account account(boolean create) {
        UUID key = UUID.randomUUID();
        long id =
                db.queryForObject(
                        "INSERT INTO admin_account(account_key,can_create) VALUES (?,?) RETURNING"
                                + " id",
                        Long.class,
                        key,
                        create);
        byte[] hash =
                ByteBuffer.allocate(32)
                        .putLong(key.getMostSignificantBits())
                        .putLong(key.getLeastSignificantBits())
                        .array();
        Instant now =
                db.queryForObject(
                        "SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
        db.update(
                "INSERT INTO"
                    + " admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                    + " VALUES (?,?,?,?,?,?,?,?,'READY')",
                id,
                "synthetic-cipher",
                hash,
                "synthetic-hash",
                "synthetic-mfa",
                now,
                0L,
                now);
        var prepared = sessions.prepare();
        var principal = new AdminPrincipal(id, key, UUID.randomUUID(), 1);
        sessions.save(prepared, principal);
        db.update(
                "INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " VALUES (?,?,?,1,'ACTIVE',?,?,?::timestamptz+interval '8 hours',?,?)",
                principal.sessionKey(),
                id,
                crypto.sessionHash(prepared.id()),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        return new Account(prepared.id(), principal);
    }

    /** 활성 협업 관계를 합성 사건에만 지정한다. */
    private void grant(Dataset data, Account actor, String permission) {
        db.update(
                "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                        + " (?,?,?,?)",
                data.story(),
                actor.principal().accountId(),
                permission,
                data.owner().principal().accountId());
    }

    /** 제품 확인 API를 명시적으로 호출하며 테스트 자동 실행 결과를 사람 확인 근거로 저장하지 않는다. */
    private CheckResult check(Dataset data, Account actor, String rev, UUID id) {
        return checker.checkGradeSamples(actor.sid(), actor.principal(), data.code(), 1, rev, id);
    }

    /** 합성 저장 값을 한 조건만 불완전하게 만들어 관찰 가능한 거절 분기를 검사한다. */
    private void mutate(Dataset data, String defect) {
        switch (defect) {
            case "empty" ->
                    db.update(
                            "UPDATE grade_sample SET active_yn=false WHERE version_id=?",
                            data.version());
            case "missingInputError" -> deactivate(data, "INPUT");
            case "missingEngineError" -> deactivate(data, "ENGINE");
            case "missingStage" -> deactivate(data, "MID");
            case "nullInput" ->
                    db.update(
                            "UPDATE grade_sample SET input_data=NULL WHERE version_id=? AND"
                                    + " code='FULL'",
                            data.version());
            case "nullExpect" ->
                    db.update(
                            "UPDATE grade_sample SET expect_data=NULL WHERE version_id=? AND"
                                    + " code='FULL'",
                            data.version());
            case "inactiveCulprit" ->
                    db.update(
                            "UPDATE story_person SET active_yn=false WHERE version_id=?",
                            data.version());
            case "blankReason" ->
                    db.update(
                            "UPDATE grade_sample SET reason=' ' WHERE version_id=? AND code='FULL'",
                            data.version());
            case "sumMismatch" ->
                    db.update(
                            "UPDATE grade_sample SET expected_score=99 WHERE version_id=? AND"
                                    + " code='FULL'",
                            data.version());
            case "successMismatch" ->
                    db.update(
                            "UPDATE grade_sample SET expected_success=false WHERE version_id=? AND"
                                    + " code='FULL'",
                            data.version());
            case "errorScore" ->
                    db.update(
                            "UPDATE grade_sample SET expected_score=0 WHERE version_id=? AND"
                                    + " code='INPUT'",
                            data.version());
            case "errorSuccess" ->
                    db.update(
                            "UPDATE grade_sample SET expected_success=false WHERE version_id=? AND"
                                    + " code='INPUT'",
                            data.version());
            case "nullRule" ->
                    db.update(
                            "UPDATE story_rubric SET rule_data=NULL WHERE version_id=? AND"
                                    + " code='METHOD'",
                            data.version());
            case "inactiveFact" ->
                    db.update(
                            "UPDATE story_fact SET active_yn=false WHERE version_id=?",
                            data.version());
            case "inactiveClueLink" ->
                    db.update(
                            "UPDATE rubric_clue SET active_yn=false WHERE version_id=? AND"
                                    + " rubric_code='METHOD'",
                            data.version());
            case "badWeights" ->
                    db.update(
                            "UPDATE story_rubric SET active_yn=false WHERE version_id=? AND"
                                    + " code='TIME'",
                            data.version());
            case "missingRequired" -> {
                db.update(
                        "UPDATE story_rubric SET"
                            + " required_yn=false,pass_score=NULL,rule_data=jsonb_set(rule_data,'{requiredNotice}','null')"
                            + " WHERE version_id=? AND code='METHOD'",
                        data.version());
            }
            case "unregisteredPass" ->
                    db.update(
                            "UPDATE story_rubric SET pass_score=11 WHERE version_id=? AND"
                                    + " code='METHOD'",
                            data.version());
            default -> mutateJson(data, defect);
        }
    }

    /** JSON 형식·누락·점수·오류 기대값의 단일 합성 결함을 작성한다. */
    private void mutateJson(Dataset data, String defect) {
        String sample = SetHolder.ERROR_DEFECTS.contains(defect) ? "ENGINE" : "FULL";
        String column = SetHolder.INPUT_DEFECTS.contains(defect) ? "input_data" : "expect_data";
        ObjectNode value = (ObjectNode) stored(data, sample, column);
        switch (defect) {
            case "missingFormat" -> value.remove("formatNo");
            case "wrongFormat" -> value.put("formatNo", 2);
            case "missingReport" -> value.remove("report");
            case "missingReportField" -> ((ObjectNode) value.get("report")).remove("time");
            case "unknownCulprit" ->
                    ((ObjectNode) value.get("report")).put("culpritCode", "UNKNOWN");
            case "unknownInputField" -> value.put("unknown", 1);
            case "missingItem" -> ((ArrayNode) value.get("items")).remove(0);
            case "duplicateItem" ->
                    ((ObjectNode) value.get("items").get(1)).put("rubricCode", "CULPRIT");
            case "unknownItem" ->
                    ((ObjectNode) value.get("items").get(1)).put("rubricCode", "UNKNOWN");
            case "unregisteredScore" -> ((ObjectNode) value.get("items").get(1)).put("score", 19);
            case "requiredMismatch" ->
                    ((ObjectNode) value.get("items").get(1)).put("requiredMet", false);
            case "optionalMismatch" ->
                    ((ObjectNode) value.get("items").get(2)).put("requiredMet", true);
            case "blankItemReason" -> ((ObjectNode) value.get("items").get(1)).put("reason", " ");
            case "longItemReason" ->
                    ((ObjectNode) value.get("items").get(1)).put("reason", "가".repeat(1001));
            case "errorItems" -> value.putArray("items");
            case "errorCode" -> ((ObjectNode) value.get("error")).put("code", "INVALID_REPORT");
            case "errorState" -> ((ObjectNode) value.get("error")).put("state", "REJECTED");
            case "errorAttempt" -> ((ObjectNode) value.get("error")).put("attemptDelta", 1);
            case "missingFault" -> value.putNull("fault");
            case "wrongFault" -> ((ObjectNode) value.get("fault")).put("type", "OTHER");
            case "wrongRuns" -> ((ObjectNode) value.get("fault")).put("failRuns", 2);
            case "normalFault" ->
                    value.set(
                            "fault",
                            mapper.createObjectNode().put("type", "TIMEOUT").put("failRuns", 3));
            default -> throw new IllegalArgumentException(defect);
        }
        saveJson(data, sample, column, value);
    }

    /** 테스트가 지정한 예시만 비활성화하며 실제 자료를 삭제하지 않는다. */
    private void deactivate(Dataset data, String code) {
        db.update(
                "UPDATE grade_sample SET active_yn=false WHERE version_id=? AND code=?",
                data.version(),
                code);
    }

    /** 고정 테스트 호출자가 지정한 JSON 컬럼만 읽는다. */
    private JsonNode stored(Dataset data, String code, String column) {
        return json(
                db.queryForObject(
                        "SELECT "
                                + column
                                + "::text FROM grade_sample WHERE version_id=? AND code=?",
                        String.class,
                        data.version(),
                        code));
    }

    /** 고정 테스트 컬럼의 합성 JSON을 갱신하며 제품 저장 경계를 대체하지 않는다. */
    private void saveJson(Dataset data, String code, String column, JsonNode value) {
        db.update(
                "UPDATE grade_sample SET " + column + "=?::jsonb WHERE version_id=? AND code=?",
                value.toString(),
                data.version(),
                code);
    }

    /** null이 아닌 합성 JSON 문자열을 읽으며 잘못된 테스트 자료는 즉시 실패한다. */
    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    /** 확인 전후 비교할 콘텐츠만 반환하고 변경 가능한 확인 메타데이터는 제외한다. */
    private List<Map<String, Object>> content(Dataset data) {
        return db.queryForList(
                "SELECT"
                    + " code,input_data::text,expect_data::text,expected_score,expected_success,reason,active_yn"
                    + " FROM grade_sample WHERE version_id=? ORDER BY code",
                data.version());
    }

    /** 확인으로 수정하면 안 되는 버전 원고와 상태를 함께 대조한다. */
    private Map<String, Object> versionContent(Dataset data) {
        return db.queryForMap(
                "SELECT"
                    + " title,intro,setting,difficulty,est_min,est_max,limit_sec,policy_code,culprit_code,method_answer,time_answer,motive_answer,timeline_origin,reveal_text,status,current_snapshot_id,active_yn"
                    + " FROM story_version WHERE id=?",
                data.version());
    }

    /** 무변경·감사 롤백의 확인자 및 갱신 시각을 비교한다. */
    private List<Map<String, Object>> sampleMetadata(Dataset data) {
        return db.queryForList(
                "SELECT code,checked_by,updated_at FROM grade_sample WHERE version_id=? ORDER BY"
                        + " code",
                data.version());
    }

    /** 합성 버전의 현재 콘텐츠·검수 수정번호를 읽는다. */
    private long revision(Dataset data) {
        return db.queryForObject(
                "SELECT edit_rev FROM story_version WHERE id=?", Long.class, data.version());
    }

    /** 대상 합성 버전의 사람 확인 감사만 센다. */
    private long auditCount(Dataset data) {
        return db.queryForObject(
                "SELECT count(*) FROM story_audit WHERE version_id=? AND action='SAMPLES_CHECKED'",
                Long.class,
                data.version());
    }

    /** 활성·비활성 여부와 무관하게 잘못 남은 확인 표시가 없음을 검사한다. */
    private void assertUnmarked(Dataset data) {
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_sample WHERE version_id=? AND"
                                        + " checked_by IS NOT NULL",
                                Long.class,
                                data.version()))
                .isZero();
    }

    /** 모든 활성 예시가 지정한 내부 합성 행위자로 확인되었는지 검사한다. */
    private void assertMarks(Dataset data, long actorId) {
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_sample WHERE version_id=? AND active_yn"
                                        + " AND checked_by=?",
                                Long.class,
                                data.version(),
                                actorId))
                .isEqualTo(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_sample WHERE version_id=? AND"
                                        + " active_yn",
                                Long.class,
                                data.version()));
    }

    /** 제품 오류가 원문 대신 지정한 안정적인 코드로 반환되는지 검사한다. */
    private static void denied(
            String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(code);
    }

    private record Account(String sid, AdminPrincipal principal) {}

    private record Dataset(Account owner, String code, long story, long version) {}

    private static final class SetHolder {
        private static final java.util.Set<String> ERROR_DEFECTS =
                java.util.Set.of(
                        "errorItems",
                        "errorCode",
                        "errorState",
                        "errorAttempt",
                        "missingFault",
                        "wrongFault",
                        "wrongRuns");
        private static final java.util.Set<String> INPUT_DEFECTS =
                java.util.Set.of(
                        "missingFormat",
                        "wrongFormat",
                        "missingReport",
                        "missingReportField",
                        "unknownCulprit",
                        "unknownInputField",
                        "missingFault",
                        "wrongFault",
                        "wrongRuns",
                        "normalFault");
    }
}
