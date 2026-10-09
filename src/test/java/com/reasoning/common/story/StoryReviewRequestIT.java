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
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.FrozenDatasetValidator;
import com.reasoning.common.migration.EmbeddedSqlResourceProvider;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.StoryFrozenSnapshotProducer;
import com.reasoning.common.story.service.StoryReviewService;
import com.reasoning.common.story.service.StoryReviewService.ReviewResult;
import com.reasoning.common.story.service.StoryService;
import com.reasoning.common.util.CommonUtil;

import jakarta.servlet.http.Cookie;

import org.flywaydb.core.Flyway;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 실제 격리 PostgreSQL·저장 자격·서블릿 경계의 합성 SR-01/02 회귀다. 실제 사건 품질을 주장하지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StoryReviewRequestIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    /** 이 클래스의 폐기형 DB와 합성 키만 등록하며 일반 로컬 DB에는 연결하지 않는다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 101));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 102));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 103));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    @Autowired JdbcTemplate db;
    @Autowired CryptoService crypto;
    @Autowired AdminSessionAdapter sessions;
    @Autowired StoryService stories;
    @Autowired StoryReviewService reviews;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;

    /** 실제 최신 V23 앱 초기화·재실행0과 nullable 근거 FK를 포함한 검수13열을 대조한다. */
    @Test
    void actualV23BootstrapsAndRepeatsZeroWithoutSchemaSubstitutes() {
        var flyway =
                Flyway.configure()
                        .resourceProvider(new EmbeddedSqlResourceProvider())
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .locations("classpath:db/migration")
                        .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("23");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE"
                                        + " table_schema='public' AND table_name='review_record'",
                                Integer.class))
                .isEqualTo(13);
    }

    @Test
    void precheckAuditsReadButChangesNoBusinessRows() {
        Fixture f = fixture();
        var before = business(f);
        int audits = auditCount(f, "CONTENT_READ");
        var result = precheck(f, f.owner(), "1");
        assertThat(result.eligible()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.errorCount()).isZero();
        assertThat(result.editRev()).isEqualTo("1");
        assertThat(result.truncated()).isFalse();
        assertThat(business(f)).isEqualTo(before);
        assertThat(auditCount(f, "CONTENT_READ")).isEqualTo(audits + 1);
        JsonNode detail =
                parse(
                        db.queryForObject(
                                "SELECT detail::text FROM story_audit WHERE version_id=? AND"
                                        + " action='CONTENT_READ' ORDER BY id DESC LIMIT 1",
                                String.class,
                                f.version()));
        assertThat(detail.get("revisionScope").textValue()).isEqualTo("CONTENT");
        assertThat(detail.toString()).doesNotContain("공개 소개", "답안", "SELECTED_REPORT");
    }

    @Test
    void pureGrammarSeedIsNotRelabeledReviewReady() {
        Fixture f = fixture(FrozenSnapshotContractTest.complete());
        var result = precheck(f, f.owner(), "1");
        assertThat(result.eligible()).isFalse();
        assertThat(result.errors())
                .anySatisfy(
                        d -> {
                            assertThat(d.resource()).isEqualTo("persons");
                            assertThat(d.field()).isEqualTo("publicText");
                        });
        assertThat(result.errors())
                .anySatisfy(
                        d -> {
                            assertThat(d.resource()).isEqualTo("hints");
                            assertThat(d.itemKey()).isEqualTo("H3");
                        });
        assertThat(count(f, "review_snapshot")).isZero();
    }

    @Test
    void newRequestStoresEveryActualFieldAndWholeValidatedDatasetPreservingHistoricalChecker() {
        Fixture f = fixture();
        db.update(
                "UPDATE admin_account SET active_yn=false WHERE id=?",
                f.checker().principal().accountId());
        db.update(
                "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                f.checker().principal().accountId());
        var samplesBefore = sampleRows(f);
        UUID key = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ReviewResult result = request(f, f.owner(), "1", key, requestId);
        assertThat(result)
                .isEqualTo(
                        new ReviewResult(
                                result.snapshotId(),
                                "1",
                                "2",
                                "REVIEW",
                                result.snapshotId(),
                                false,
                                requestId));
        var row =
                db.queryForMap(
                        "SELECT version_id,edit_rev,format_no,request_key,created_by,payload::text"
                                + " AS payload FROM review_snapshot WHERE id=?",
                        Long.parseLong(result.snapshotId()));
        assertThat(row.get("version_id")).isEqualTo(f.version());
        assertThat(row.get("edit_rev")).isEqualTo(1L);
        assertThat(((Number) row.get("format_no")).intValue()).isEqualTo(1);
        assertThat(row.get("request_key")).isEqualTo(key);
        assertThat(row.get("created_by")).isEqualTo(f.owner().principal().accountId());
        var actual =
                FrozenSnapshotCodec.decode(SnapshotJson.encode(parse((String) row.get("payload"))));
        var expected =
                StoryFrozenSnapshotProducer.freeze(
                        f.code(),
                        1,
                        1,
                        "RULE_20260924",
                        f.source().get("sections"),
                        f.source().get("resources"));
        assertThat(actual.payloadBytes()).isEqualTo(expected.payloadBytes());
        assertThat(actual.payloadHash()).isEqualTo(expected.payloadHash());
        assertThat(actual.rubricHash()).isEqualTo(expected.rubricHash());
        assertThat(actual.datasetHash()).isEqualTo(expected.datasetHash());
        var dataset = new FrozenDatasetValidator().validate(actual);
        assertThat(actual.inputHash("FULL")).isEqualTo(expected.inputHash("FULL"));
        assertThat(actual.reportHash(dataset.select("FULL").report()))
                .isEqualTo(expected.reportHash(dataset.select("FULL").report()));
        assertThat(dataset.select("INPUT").report()).isNull();
        assertThat(dataset.select("ENGINE").fault().failRuns()).isEqualTo(3);
        assertThat(sampleRows(f)).isEqualTo(samplesBefore);
        assertThat(
                        actual.payload()
                                .get("resources")
                                .get("gradeSamples")
                                .get(0)
                                .get("checkedBy")
                                .textValue())
                .isEqualTo(f.checker().principal().accountKey().toString());
        var record =
                db.queryForMap(
                        "SELECT * FROM review_record WHERE snapshot_id=?",
                        Long.parseLong(result.snapshotId()));
        assertThat(record.get("kind")).isEqualTo("STRUCTURE");
        assertThat(record.get("result")).isEqualTo("PASS");
        assertThat(record.get("reviewer_id")).isEqualTo(f.owner().principal().accountId());
        assertThat(record.get("model_id")).isNull();
        assertThat(record.get("effort")).isNull();
        assertThat(record.get("self_review_yn")).isEqualTo(true);
        assertThat(record.get("request_key")).isNotEqualTo(key);
        assertThat(((UUID) record.get("request_key")).version()).isEqualTo(4);
        JsonNode evidence = parse(record.get("evidence_data").toString());
        assertThat(evidence.size()).isEqualTo(3);
        assertThat(evidence.get("request").get("expectedRev").textValue()).isEqualTo("1");
        assertThat(evidence.get("details").get("checkedRev").textValue()).isEqualTo("1");
        assertThat(evidence.get("details").get("errorCount").intValue()).isZero();
        assertThat(evidence.get("details").get("ruleVersion").textValue())
                .isEqualTo("STORY-REVIEW-01");
        assertThat(record.get("evidence").toString())
                .isNotBlank()
                .doesNotContain("SELECTED_REPORT", "공개 소개");
        assertThat(auditCount(f, "REVIEW_REQUESTED")).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT updated_by FROM story_version WHERE id=?",
                                Long.class,
                                f.version()))
                .isEqualTo(f.owner().principal().accountId());
    }

    @Test
    void activeWholeSourceExceedsHttpPageAndNeverIncludesInactiveHistory() {
        Fixture f = fixture();
        for (int i = 0; i < 125; i++)
            db.update(
                    "INSERT INTO story_person(version_id,code,name,public_text,secret_text) VALUES"
                            + " (?,?,?,?,?)",
                    f.version(),
                    "EXTRA_" + i,
                    "합성 인물",
                    "공개 자료",
                    i % 2 == 0 ? null : "");
        db.update(
                "INSERT INTO story_person(version_id,code,name,public_text,active_yn) VALUES"
                        + " (?,'HIDDEN','과거 인물',NULL,false)",
                f.version());
        ReviewResult result = request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID());
        JsonNode persons = payload(result).get("resources").get("persons");
        assertThat(persons.size()).isEqualTo(127);
        List<String> codes = new ArrayList<>();
        persons.forEach(row -> codes.add(row.get("code").textValue()));
        assertThat(codes)
                .isSortedAccordingTo(CommonUtil::compareCodePoints)
                .doesNotContain("HIDDEN");
        assertThat(persons).anySatisfy(row -> assertThat(row.get("secretText").isNull()).isTrue());
        assertThat(persons)
                .anySatisfy(
                        row ->
                                assertThat(
                                                row.get("secretText").isTextual()
                                                        && row.get("secretText")
                                                                .textValue()
                                                                .isEmpty())
                                        .isTrue());
    }

    @Test
    void exactJsonbNumbersAndMalformedInputArraysAreNotStringifiedOrRepaired() {
        Fixture f = fixture();
        String raw =
                "{\"formatNo\":1,\"report\":{\"number\":1e999,\"claims\":[3,1,2],\"tiny\":1e-1000},\"fault\":null}";
        db.update(
                "UPDATE grade_sample SET input_data=?::jsonb WHERE version_id=? AND code='INPUT'",
                raw,
                f.version());
        var result = request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID());
        JsonNode stored = sample(payload(result), "INPUT").get("inputData");
        assertThat(stored.isObject()).isTrue();
        assertThat(stored.get("report").get("number").decimalValue()).isEqualByComparingTo("1e999");
        assertThat(stored.get("report").get("tiny").decimalValue()).isEqualByComparingTo("1e-1000");
        assertThat(stored.get("report").get("claims")).isEqualTo(parse("[3,1,2]"));
    }

    @Test
    void requestLossReplayAndReturnedDraftUseOriginalSourceButCurrentReceipt() {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        var first = request(f, f.owner(), "1", key, UUID.randomUUID());
        assertThat(request(f, f.owner(), "1", key, UUID.randomUUID()).snapshotId())
                .isEqualTo(first.snapshotId());
        assertThat(request(f, f.owner(), "1", key, UUID.randomUUID()).replayed()).isTrue();
        db.update(
                "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL,edit_rev=3 WHERE"
                        + " id=?",
                f.version());
        db.update("UPDATE story_person SET public_text=NULL WHERE version_id=?", f.version());
        var replay = request(f, f.owner(), "1", key, UUID.randomUUID());
        assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(replay.sourceRev()).isEqualTo("1");
        assertThat(replay.editRev()).isEqualTo("3");
        assertThat(replay.status()).isEqualTo("DRAFT");
        assertThat(replay.currentSnapshotId()).isNull();
        assertThat(count(f, "review_snapshot")).isEqualTo(1);
        assertThat(count(f, "review_record")).isEqualTo(1);
        assertThat(auditCount(f, "REVIEW_REQUESTED")).isEqualTo(1);
        assertFailure(
                () -> request(f, f.owner(), "3", key, UUID.randomUUID()),
                409,
                "REQUEST_KEY_CONFLICT");
        assertFailure(
                () -> request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                409,
                "EDIT_CONFLICT");
    }

    @Test
    void replayRequiresOriginalActorAndStillRequiresCurrentEditAndCredentials() {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        UUID key = UUID.randomUUID();
        request(f, f.owner(), "1", key, UUID.randomUUID());
        assertFailure(
                () -> request(f, editor, "1", key, UUID.randomUUID()), 409, "REQUEST_KEY_CONFLICT");
        db.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                f.story(),
                editor.principal().accountId());
        assertFailure(() -> request(f, editor, "1", key, UUID.randomUUID()), 404, "NOT_FOUND");
        db.update(
                "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?",
                f.owner().principal().accountId());
        assertFailure(
                () -> request(f, f.owner(), "1", key, UUID.randomUUID()), 401, "AUTH_REQUIRED");
    }

    @Test
    void nonAuthorEditorStructureIsNotAutomaticallySelfReviewButContentParticipantIs() {
        for (boolean participant : List.of(false, true)) {
            Fixture f = fixture();
            Account editor = account(false);
            grant(f, editor, "EDIT");
            if (participant)
                db.update(
                        "INSERT INTO"
                            + " story_audit(story_id,version_id,actor_id,action,before_rev,after_rev,detail)"
                            + " VALUES"
                            + " (?,?,?,'SECTION_UPDATED',0,1,'{\"revisionScope\":\"CONTENT\"}'::jsonb)",
                        f.story(),
                        f.version(),
                        editor.principal().accountId());
            var result = request(f, editor, "1", UUID.randomUUID(), UUID.randomUUID());
            assertThat(
                            db.queryForObject(
                                    "SELECT self_review_yn FROM review_record WHERE snapshot_id=?",
                                    Boolean.class,
                                    Long.parseLong(result.snapshotId())))
                    .isEqualTo(participant);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "record", "transition", "audit", "read"})
    void injectedPhysicalFailureRollsBackEveryBusinessWriteAndReadAuditFailsClosed(String phase) {
        Fixture f = fixture();
        var before = business(f);
        String table =
                switch (phase) {
                    case "snapshot" -> "review_snapshot";
                    case "record" -> "review_record";
                    case "transition" -> "story_version";
                    default -> "story_audit";
                };
        String condition =
                switch (phase) {
                    case "record" ->
                            "NEW.snapshot_id IN (SELECT id FROM review_snapshot WHERE version_id="
                                    + f.version()
                                    + ")";
                    case "transition" -> "NEW.id=" + f.version() + " AND NEW.status='REVIEW'";
                    case "read" ->
                            "NEW.version_id=" + f.version() + " AND NEW.action='CONTENT_READ'";
                    case "audit" ->
                            "NEW.version_id=" + f.version() + " AND NEW.action='REVIEW_REQUESTED'";
                    default -> "NEW.version_id=" + f.version();
                };
        String function = "review_fail_" + UUID.randomUUID().toString().replace("-", "");
        db.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
                        + condition
                        + " THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + function
                        + " BEFORE "
                        + (phase.equals("transition") ? "UPDATE" : "INSERT")
                        + " ON "
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            if (phase.equals("read"))
                assertFailure(() -> precheck(f, f.owner(), "1"), 503, "STORY_UNAVAILABLE");
            else
                assertFailure(
                        () -> request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                        503,
                        "STORY_UNAVAILABLE");
            assertThat(business(f)).isEqualTo(before);
        } finally {
            db.execute("DROP TRIGGER " + function + " ON " + table);
            db.execute("DROP FUNCTION " + function + "()");
        }
    }

    @Test
    void successfulSnapshotRevisionMutationCannotCommitFalseReceipt() {
        Fixture f = fixture();
        var before = business(f);
        String function = "review_mutate_" + UUID.randomUUID().toString().replace("-", "");
        db.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.version_id="
                        + f.version()
                        + " THEN NEW.edit_rev:=NEW.edit_rev+1; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + function
                        + " BEFORE INSERT ON review_snapshot FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            assertFailure(
                    () -> request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                    503,
                    "STORY_UNAVAILABLE");
            assertThat(business(f)).isEqualTo(before);
        } finally {
            db.execute("DROP TRIGGER " + function + " ON review_snapshot");
            db.execute("DROP FUNCTION " + function + "()");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "payload",
                "record",
                "recordEvidence",
                "version",
                "source",
                "checker",
                "audit"
            })
    void successfulStoredRowMutationRollsBackWholeReviewRequest(String phase) {
        Fixture f = fixture();
        var before = business(f);
        String table =
                switch (phase) {
                    case "payload" -> "review_snapshot";
                    case "record", "recordEvidence" -> "review_record";
                    case "version", "source", "checker" -> "story_version";
                    default -> "story_audit";
                };
        String condition =
                switch (table) {
                    case "review_record" ->
                            "NEW.snapshot_id IN (SELECT id FROM review_snapshot WHERE version_id="
                                    + f.version()
                                    + ")";
                    case "story_version" -> "NEW.id=" + f.version() + " AND NEW.status='REVIEW'";
                    case "story_audit" ->
                            "NEW.version_id=" + f.version() + " AND NEW.action='REVIEW_REQUESTED'";
                    default -> "NEW.version_id=" + f.version();
                };
        String mutation =
                switch (phase) {
                    case "payload" ->
                            "NEW.payload:=jsonb_set(NEW.payload,'{sections,basic,title}','\"changed\"');";
                    case "record" -> "NEW.kind:='MODEL';";
                    case "recordEvidence" ->
                            "NEW.evidence_data:=jsonb_set(NEW.evidence_data,'{details,errorCount}','1');";
                    case "version" -> "NEW.edit_rev:=NEW.edit_rev+1;";
                    case "source" -> "NEW.intro:='changed';";
                    case "checker" ->
                            "UPDATE grade_sample SET checked_by=NULL WHERE version_id="
                                    + f.version()
                                    + ";";
                    default -> "NEW.after_rev:=NEW.after_rev+1;";
                };
        String function = "review_mutate_" + UUID.randomUUID().toString().replace("-", "");
        db.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
                        + condition
                        + " THEN "
                        + mutation
                        + " END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + function
                        + " BEFORE "
                        + (table.equals("story_version") ? "UPDATE" : "INSERT")
                        + " ON "
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            assertFailure(
                    () -> request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                    503,
                    "STORY_UNAVAILABLE");
            assertThat(business(f)).isEqualTo(before);
        } finally {
            db.execute("DROP TRIGGER " + function + " ON " + table);
            db.execute("DROP FUNCTION " + function + "()");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "intro",
                "publicText",
                "brief",
                "body",
                "answer",
                "reveal",
                "difficulty",
                "limitSec",
                "policy",
                "rule",
                "hint",
                "pair",
                "unchecked",
                "sampleKinds",
                "weights",
                "reference",
                "unsupportedRule",
                "badSample"
            })
    void incompleteAndInconsistentSourceReturnsMetadataOnlyAndCannotRequest(String defect) {
        Fixture f = fixture();
        switch (defect) {
            case "intro" -> db.update("UPDATE story_version SET intro='' WHERE id=?", f.version());
            case "publicText" ->
                    db.update(
                            "UPDATE story_person SET public_text=NULL WHERE version_id=?",
                            f.version());
            case "brief" ->
                    db.update("UPDATE story_role SET brief=' ' WHERE version_id=?", f.version());
            case "body" ->
                    db.update("UPDATE story_clue SET body=NULL WHERE version_id=?", f.version());
            case "answer" ->
                    db.update(
                            "UPDATE story_version SET method_answer=NULL WHERE id=?", f.version());
            case "reveal" ->
                    db.update("UPDATE story_version SET reveal_text=NULL WHERE id=?", f.version());
            case "difficulty" ->
                    db.update("UPDATE story_version SET difficulty=NULL WHERE id=?", f.version());
            case "limitSec" ->
                    db.update("UPDATE story_version SET limit_sec=NULL WHERE id=?", f.version());
            case "policy" ->
                    db.update(
                            "UPDATE story_version SET policy_code='UNSUPPORTED' WHERE id=?",
                            f.version());
            case "rule" ->
                    db.update(
                            "UPDATE story_rubric SET rule_data=NULL WHERE version_id=? AND"
                                    + " code='METHOD'",
                            f.version());
            case "hint" ->
                    db.update(
                            "UPDATE story_hint SET active_yn=false WHERE version_id=? AND level=3",
                            f.version());
            case "pair" ->
                    db.update(
                            "UPDATE story_pair SET active_yn=false WHERE version_id=?",
                            f.version());
            case "unchecked" ->
                    db.update(
                            "UPDATE grade_sample SET checked_by=NULL WHERE version_id=?",
                            f.version());
            case "sampleKinds" ->
                    db.update(
                            "UPDATE grade_sample SET active_yn=false WHERE version_id=? AND"
                                    + " code='INPUT'",
                            f.version());
            case "weights" ->
                    db.update(
                            "UPDATE story_rubric SET active_yn=false WHERE version_id=? AND"
                                    + " code='TIME'",
                            f.version());
            case "reference" ->
                    db.update(
                            "UPDATE story_fact SET active_yn=false WHERE version_id=? AND"
                                    + " code='F1'",
                            f.version());
            case "unsupportedRule" ->
                    db.update(
                            "UPDATE story_rubric SET"
                                    + " rule_data=jsonb_set(rule_data,'{formatNo}','2') WHERE"
                                    + " version_id=? AND code='METHOD'",
                            f.version());
            case "badSample" ->
                    db.update(
                            "UPDATE grade_sample SET expected_score=99 WHERE version_id=? AND"
                                    + " code='FULL'",
                            f.version());
            default -> throw new AssertionError(defect);
        }
        var before = business(f);
        var result = precheck(f, f.owner(), "1");
        assertThat(result.eligible()).isFalse();
        assertThat(result.errorCount()).isPositive();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors())
                .allSatisfy(
                        d -> {
                            assertThat(d.code())
                                    .isIn(
                                            "MISSING_CONTENT",
                                            "SCORE_TOTAL",
                                            "INVALID_INPUT",
                                            "REVIEW_NOT_READY",
                                            "REFERENCE_UNASSIGNED");
                            assertThat(d.toString())
                                    .doesNotContain(
                                            "SELECTED_REPORT",
                                            "synthetic-cipher",
                                            "공개 소개",
                                            "합성 사실 명제");
                        });
        String field =
                switch (defect) {
                    case "intro", "publicText", "brief", "body", "difficulty", "limitSec" -> defect;
                    case "answer" -> "methodAnswer";
                    case "reveal" -> "revealText";
                    case "policy" -> "policyCode";
                    case "rule" -> "ruleData";
                    case "hint" -> "level";
                    case "unchecked" -> "checkedBy";
                    case "weights" -> "maxScore";
                    default -> "";
                };
        assertThat(result.errors()).anySatisfy(d -> assertThat(d.field()).isEqualTo(field));
        if (List.of("sampleKinds", "reference", "unsupportedRule", "badSample").contains(defect))
            assertThat(result.errors())
                    .anySatisfy(
                            d -> {
                                assertThat(d.code()).isEqualTo("REVIEW_NOT_READY");
                                assertThat(d.resource()).isEqualTo("resources");
                            });
        assertThat(business(f)).isEqualTo(before);
        assertFailure(
                () -> request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                422,
                "REVIEW_NOT_READY");
        assertThat(business(f)).isEqualTo(before);
    }

    @Test
    void pairCanCombinePrivateCluesButCannotLoseItsRequiredUnionAccess() {
        Fixture f = fixture();
        // 두 번째 후보에도 R1의 C1과 R3의 C2를 결합하며 어느 역할에도 모든 단서를 강제하지 않는다.
        assertThat(precheck(f, f.owner(), "1").eligible()).isTrue();
        db.update(
                "UPDATE clue_role SET active_yn=false WHERE version_id=? AND role_code='R1'",
                f.version());
        var result = precheck(f, f.owner(), "1");
        assertThat(result.eligible()).isFalse();
        assertThat(result.errors())
                .anySatisfy(
                        d -> {
                            assertThat(d.code()).isEqualTo("REFERENCE_UNASSIGNED");
                            assertThat(d.resource()).isEqualTo("pairs");
                            assertThat(d.field()).isEqualTo("rubricClues");
                        });
    }

    @Test
    void nullRequiredNoticeIsApprovedAndNeverInvented() {
        Fixture f = fixture();
        db.update(
                "UPDATE story_rubric SET rule_data=jsonb_set(rule_data,'{requiredNotice}','null')"
                        + " WHERE version_id=? AND code IN ('METHOD','EVIDENCE')",
                f.version());
        assertThat(precheck(f, f.owner(), "1").eligible()).isTrue();
        var result = request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID());
        for (JsonNode row : payload(result).get("resources").get("rubrics"))
            if (List.of("METHOD", "EVIDENCE").contains(row.get("code").textValue()))
                assertThat(row.get("ruleData").get("requiredNotice").isNull()).isTrue();
    }

    @Test
    void diagnosticsCapIsSeparateAndEligibilityUsesFullCountWithStableOrder() {
        Fixture f = fixture();
        for (int i = 0; i < 225; i++)
            db.update(
                    "INSERT INTO story_person(version_id,code,name) VALUES (?,?,?)",
                    f.version(),
                    String.format("EMPTY_%03d", i),
                    "미작성");
        var result = precheck(f, f.owner(), "1");
        assertThat(result.eligible()).isFalse();
        assertThat(result.errorCount()).isEqualTo(225);
        assertThat(result.errors()).hasSize(200);
        assertThat(result.warningCount()).isZero();
        assertThat(result.truncated()).isTrue();
        Comparator<StoryReviewService.Diagnostic> order =
                Comparator.comparing(
                                StoryReviewService.Diagnostic::code, CommonUtil::compareCodePoints)
                        .thenComparing(
                                StoryReviewService.Diagnostic::resource,
                                CommonUtil::compareCodePoints)
                        .thenComparing(
                                StoryReviewService.Diagnostic::itemKey,
                                CommonUtil::compareCodePoints)
                        .thenComparing(
                                StoryReviewService.Diagnostic::field,
                                CommonUtil::compareCodePoints);
        assertThat(result.errors()).isSortedAccordingTo(order);
        assertThat(result.errors().get(199).itemKey()).isEqualTo("EMPTY_199");
        assertThat(precheck(f, f.owner(), "1").errors()).isEqualTo(result.errors());
    }

    @Test
    void warningOnlyDoesNotClaimQualityAndCanBeFrozenWithRealDifficultyPolicy() {
        Fixture f = fixture();
        db.update(
                "UPDATE story_version SET difficulty=5,est_min=5,est_max=10,limit_sec=777 WHERE"
                        + " id=?",
                f.version());
        var precheck = precheck(f, f.owner(), "1");
        assertThat(precheck.eligible()).isTrue();
        assertThat(precheck.warningCount()).isEqualTo(2);
        assertThat(precheck.warnings())
                .allSatisfy(d -> assertThat(d.code()).isEqualTo("POLICY_TIME_RANGE"));
        var result = request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID());
        JsonNode policy = payload(result).get("policy");
        assertThat(policy.get("attemptLimit").intValue()).isEqualTo(2);
        assertThat(policy.get("hintsPerPerson").intValue()).isEqualTo(1);
        assertThat(policy.get("limitSec").intValue()).isEqualTo(777);
        assertThat(
                        parse(
                                        db.queryForObject(
                                                "SELECT evidence_data::text FROM review_record"
                                                        + " WHERE snapshot_id=?",
                                                String.class,
                                                Long.parseLong(result.snapshotId())))
                                .get("details")
                                .get("warningCount")
                                .intValue())
                .isEqualTo(2);
    }

    @Test
    void revisionOverflowAndCanonicalSixteenMiBBudgetLeaveNoPartialRows() {
        Fixture overflow = fixture();
        db.update(
                "UPDATE story_version SET edit_rev=? WHERE id=?",
                Long.MAX_VALUE,
                overflow.version());
        var before = business(overflow);
        assertFailure(
                () ->
                        request(
                                overflow,
                                overflow.owner(),
                                Long.toString(Long.MAX_VALUE),
                                UUID.randomUUID(),
                                UUID.randomUUID()),
                409,
                "EDIT_CONFLICT");
        assertThat(business(overflow)).isEqualTo(before);
        Fixture huge = fixture();
        for (int i = 0; i < 750; i++)
            db.update(
                    "INSERT INTO story_person(version_id,code,name,public_text) VALUES (?,?,?,?)",
                    huge.version(),
                    "BIG_" + i,
                    "합성",
                    "가".repeat(8000));
        var hugeBefore = business(huge);
        var precheck = precheck(huge, huge.owner(), "1");
        assertThat(precheck.eligible()).isFalse();
        assertThat(precheck.errors())
                .anySatisfy(d -> assertThat(d.code()).isEqualTo("SNAPSHOT_TOO_LARGE"));
        assertFailure(
                () -> request(huge, huge.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                422,
                "SNAPSHOT_TOO_LARGE");
        assertThat(business(huge)).isEqualTo(hugeBefore);
    }

    @Test
    void twoConcurrentNewKeysSerializeAndOnlyOneCanConsumeTheRevision() throws Exception {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<String>> tasks =
                    List.of(
                            () -> race(f, f.owner(), UUID.randomUUID(), start),
                            () -> race(f, editor, UUID.randomUUID(), start));
            var futures = pool.invokeAll(tasks);
            List<String> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("REVIEW", "STATE_CONFLICT");
        }
        assertThat(count(f, "review_snapshot")).isEqualTo(1);
        assertThat(count(f, "review_record")).isEqualTo(1);
        assertThat(auditCount(f, "REVIEW_REQUESTED")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "globalReview",
                "storyReview",
                "blocked",
                "mfa",
                "session",
                "inactive",
                "stale"
            })
    void currentAuthorizationAndStateCannotBeBypassed(String defect) {
        Fixture f = fixture();
        Account actor = f.owner();
        int code = 401;
        String error = "AUTH_REQUIRED";
        switch (defect) {
            case "globalReview" -> {
                actor = account(false);
                db.update(
                        "UPDATE admin_account SET can_review=true WHERE id=?",
                        actor.principal().accountId());
                code = 404;
                error = "NOT_FOUND";
            }
            case "storyReview" -> {
                actor = account(false);
                db.update(
                        "UPDATE admin_account SET can_review=true WHERE id=?",
                        actor.principal().accountId());
                grant(f, actor, "REVIEW");
                code = 403;
                error = "FORBIDDEN";
            }
            case "blocked" ->
                    db.update(
                            "UPDATE admin_account SET active_yn=false WHERE id=?",
                            actor.principal().accountId());
            case "mfa" ->
                    db.update(
                            "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                            actor.principal().accountId());
            case "session" ->
                    db.update(
                            "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp()"
                                    + " WHERE session_key=?",
                            actor.principal().sessionKey());
            case "inactive" -> {
                db.update("UPDATE story_version SET active_yn=false WHERE id=?", f.version());
                code = 409;
                error = "STATE_CONFLICT";
            }
            case "stale" -> {
                db.update("UPDATE story_version SET edit_rev=2 WHERE id=?", f.version());
                code = 409;
                error = "EDIT_CONFLICT";
            }
        }
        Account current = actor;
        var before = business(f);
        assertFailure(() -> precheck(f, current, "1"), code, error);
        assertFailure(
                () -> request(f, current, "1", UUID.randomUUID(), UUID.randomUUID()), code, error);
        assertThat(business(f)).isEqualTo(before);
    }

    @Test
    void concurrentSameKeyRecoversOneOriginalReceiptWithoutDuplicateRecords() throws Exception {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<ReviewResult> call =
                    () -> {
                        start.await(10, TimeUnit.SECONDS);
                        return request(f, f.owner(), "1", key, UUID.randomUUID());
                    };
            var futures = pool.invokeAll(List.of(call, call));
            var a = futures.get(0).get(15, TimeUnit.SECONDS);
            var b = futures.get(1).get(15, TimeUnit.SECONDS);
            assertThat(a.snapshotId()).isEqualTo(b.snapshotId());
            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(false, true);
        }
        assertThat(count(f, "review_snapshot")).isEqualTo(1);
        assertThat(count(f, "review_record")).isEqualTo(1);
        assertThat(auditCount(f, "REVIEW_REQUESTED")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "01", "1.0", "9223372036854775808"})
    void revisionFormatCannotReachSourceAuditOrBusinessWrites(String revision) {
        Fixture f = fixture();
        var before = business(f);
        assertFailure(() -> precheck(f, f.owner(), revision), 400, "INVALID_REQUEST");
        assertFailure(
                () -> request(f, f.owner(), revision, UUID.randomUUID(), UUID.randomUUID()),
                400,
                "INVALID_REQUEST");
        assertThat(business(f)).isEqualTo(before);
        assertThat(auditCount(f, "CONTENT_READ")).isZero();
    }

    @Test
    void legalContentEditAndReviewUseSameParentLocksAndCannotBothConsumeRevision()
            throws Exception {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> edit =
                    () -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            stories.updateStorySection(
                                    editor.sid(),
                                    editor.principal(),
                                    f.code(),
                                    1,
                                    "basic",
                                    "1",
                                    parse("{\"intro\":\"명시적인 합성 원고 수정\"}"),
                                    UUID.randomUUID());
                            return "EDITED";
                        } catch (AuthException rejected) {
                            return rejected.code();
                        }
                    };
            Callable<String> review = () -> race(f, f.owner(), UUID.randomUUID(), start);
            var futures = pool.invokeAll(List.of(edit, review));
            String edited = futures.get(0).get(15, TimeUnit.SECONDS);
            String requested = futures.get(1).get(15, TimeUnit.SECONDS);
            if ("EDITED".equals(edited)) {
                assertThat(requested).isEqualTo("EDIT_CONFLICT");
                assertThat(count(f, "review_snapshot")).isZero();
                assertThat(sampleRows(f))
                        .allSatisfy(row -> assertThat(row.get("checked_by")).isNull());
            } else {
                assertThat(edited).isEqualTo("STATE_CONFLICT");
                assertThat(requested).isEqualTo("REVIEW");
                assertThat(count(f, "review_snapshot")).isEqualTo(1);
                assertThat(sampleRows(f))
                        .allSatisfy(
                                row ->
                                        assertThat(row.get("checked_by"))
                                                .isEqualTo(f.checker().principal().accountId()));
            }
        }
        assertThat(
                        db.queryForObject(
                                "SELECT edit_rev FROM story_version WHERE id=?",
                                Long.class,
                                f.version()))
                .isEqualTo(2L);
    }

    @Test
    void physicallyStoredJsonMissingFieldIsDiagnosedWithoutRepairOrSensitiveCause() {
        Fixture f = fixture();
        db.update(
                "UPDATE grade_sample SET input_data=input_data-'fault' WHERE version_id=? AND"
                        + " code='FULL'",
                f.version());
        var result = precheck(f, f.owner(), "1");
        assertThat(result.eligible()).isFalse();
        assertThat(result.errors())
                .anySatisfy(d -> assertThat(d.code()).isEqualTo("REVIEW_NOT_READY"));
        assertFailure(
                () -> request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                422,
                "REVIEW_NOT_READY");
        assertThat(
                        db.queryForObject(
                                "SELECT jsonb_exists(input_data,'fault') FROM grade_sample WHERE"
                                        + " version_id=? AND code='FULL'",
                                Boolean.class,
                                f.version()))
                .isFalse();
        assertThat(count(f, "review_snapshot")).isZero();
    }

    @Test
    void inconsistentActiveRelationsAreKeptAndWholeValidatorRejectsThem() {
        Fixture f = fixture();
        db.update(
                "UPDATE story_role SET active_yn=false WHERE version_id=? AND code='R3'",
                f.version());
        var result = precheck(f, f.owner(), "1");
        assertThat(result.eligible()).isFalse();
        assertThat(result.errors())
                .anySatisfy(
                        d -> {
                            assertThat(d.resource()).isEqualTo("pairs");
                            assertThat(d.itemKey()).isEqualTo("R1~R3");
                            assertThat(d.field()).isEqualTo("roles");
                        });
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_pair WHERE version_id=? AND role_b='R3'"
                                        + " AND active_yn",
                                Integer.class,
                                f.version()))
                .isEqualTo(1);
        assertFailure(
                () -> request(f, f.owner(), "1", UUID.randomUUID(), UUID.randomUUID()),
                422,
                "REVIEW_NOT_READY");
    }

    @Test
    void historicalNonNullCheckerWithoutAccountIsNotSilentlyTurnedIntoUncheckedNull() {
        Fixture f = fixture();
        // 격리 거래에서만 손상 원본을 주입하며 스키마와 원본 변경은 거래 끝에 모두 되돌린다.
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        transaction -> {
                            db.execute(
                                    "ALTER TABLE grade_sample DROP CONSTRAINT"
                                            + " fk_grade_sample_checker");
                            db.update(
                                    "UPDATE grade_sample SET checked_by=? WHERE version_id=? AND"
                                            + " code='FULL'",
                                    Long.MAX_VALUE,
                                    f.version());
                            var result = precheck(f, f.owner(), "1");
                            assertThat(result.eligible()).isFalse();
                            assertThat(result.errors())
                                    .anySatisfy(
                                            d -> {
                                                assertThat(d.code()).isEqualTo("REVIEW_NOT_READY");
                                                assertThat(d.resource()).isEqualTo("gradeSamples");
                                                assertThat(d.itemKey()).isEqualTo("FULL");
                                                assertThat(d.field()).isEqualTo("checkedBy");
                                            });
                            assertThat(count(f, "review_snapshot")).isZero();
                            transaction.setRollbackOnly();
                        });
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE"
                                        + " conrelid='grade_sample'::regclass AND"
                                        + " conname='fk_grade_sample_checker'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT checked_by FROM grade_sample WHERE version_id=? AND"
                                        + " code='FULL'",
                                Long.class,
                                f.version()))
                .isEqualTo(f.checker().principal().accountId());
    }

    @Test
    void realServletStrictBodyCsrfOriginNoStoreNewAndReplay() throws Exception {
        Fixture f = fixture();
        Cookie session = cookie(f.owner());
        var tokenResponse =
                mvc.perform(get("/admin/api/auth/csrf").secure(true))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrf = tokenResponse.getResponse().getCookie("__Host-admin-csrf");
        String token =
                parse(tokenResponse.getResponse().getContentAsString()).get("token").textValue();
        String root = "/admin/api/stories/" + f.code() + "/versions/1";
        for (String endpoint : List.of("review-precheck", "review-requests")) {
            String path = root + "/" + endpoint;
            String valid =
                    endpoint.equals("review-precheck")
                            ? "{\"expectedRev\":\"1\"}"
                            : "{\"expectedRev\":\"1\",\"requestKey\":\""
                                    + UUID.randomUUID()
                                    + "\"}";
            mvc.perform(
                            httpsPost(path)
                                    .cookie(session)
                                    .contentType("application/json")
                                    .content(valid))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            httpsPost(path)
                                    .cookie(session, csrf)
                                    .header("X-CSRF-TOKEN", token)
                                    .header("Origin", "https://foreign.example")
                                    .contentType("application/json")
                                    .content(valid))
                    .andExpect(status().isForbidden());
            mvc.perform(authorized(path, csrf, token).content(valid))
                    .andExpect(status().isUnauthorized());
            for (String body :
                    List.of(
                            "{}",
                            "{\"expectedRev\":1}",
                            "{\"expectedRev\":null}",
                            "{\"expectedRev\":\"1\",\"extra\":0}",
                            "{\"expectedRev\":\"1\",\"expectedRev\":\"1\"}",
                            valid + " {}"))
                mvc.perform(authorized(path, csrf, token).cookie(session).content(body))
                        .andExpect(status().isBadRequest());
            mvc.perform(
                            authorized(path, csrf, token)
                                    .cookie(session)
                                    .content("{\"expectedRev\":\"" + "1".repeat(8192) + "\"}"))
                    .andExpect(status().isPayloadTooLarge());
        }
        var precheck =
                mvc.perform(
                                authorized(root + "/review-precheck", csrf, token)
                                        .cookie(session)
                                        .content("{\"expectedRev\":\"1\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(precheck.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(
                        parse(precheck.getResponse().getContentAsString())
                                .get("eligible")
                                .booleanValue())
                .isTrue();
        db.update("UPDATE story_version SET intro=NULL WHERE id=?", f.version());
        var incomplete =
                mvc.perform(
                                authorized(root + "/review-precheck", csrf, token)
                                        .cookie(session)
                                        .content("{\"expectedRev\":\"1\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode diagnosticResult = parse(incomplete.getResponse().getContentAsString());
        assertThat(diagnosticResult.get("eligible").booleanValue()).isFalse();
        assertThat(diagnosticResult.get("errors").get(0).size()).isEqualTo(4);
        assertThat(diagnosticResult.get("errors").get(0).get("field").textValue())
                .isEqualTo("intro");
        assertThat(diagnosticResult.get("errorCount").intValue()).isEqualTo(1);
        assertThat(incomplete.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(incomplete.getResponse().getContentAsString())
                .doesNotContain("SELECTED_REPORT", "공개 소개");
        db.update(
                "UPDATE story_version SET intro=? WHERE id=?",
                f.source().get("sections").get("basic").get("intro").textValue(),
                f.version());
        String path = root + "/review-requests";
        for (String key :
                List.of(
                        "not-a-uuid",
                        "00000000-0000-1000-8000-000000000000",
                        "00000000-0000-4000-0000-000000000000"))
            mvc.perform(
                            authorized(path, csrf, token)
                                    .cookie(session)
                                    .content(
                                            "{\"expectedRev\":\"1\",\"requestKey\":\""
                                                    + key
                                                    + "\"}"))
                    .andExpect(status().isBadRequest());
        String body = "{\"expectedRev\":\"1\",\"requestKey\":\"" + UUID.randomUUID() + "\"}";
        var created =
                mvc.perform(authorized(path, csrf, token).cookie(session).content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        var replay =
                mvc.perform(authorized(path, csrf, token).cookie(session).content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(replay.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(parse(replay.getResponse().getContentAsString()).get("snapshotId"))
                .isEqualTo(parse(created.getResponse().getContentAsString()).get("snapshotId"));
        assertThat(created.getResponse().getContentAsString())
                .doesNotContain("SELECTED_REPORT", "공개 소개", "secretText");
        Account outsider = account(false);
        var missing =
                mvc.perform(authorized(path, csrf, token).cookie(cookie(outsider)).content(body))
                        .andExpect(status().isNotFound())
                        .andReturn();
        assertThat(parse(missing.getResponse().getContentAsString()).get("code").textValue())
                .isEqualTo("NOT_FOUND");
        assertThat(missing.getResponse().getContentAsString())
                .doesNotContain(f.code(), "snapshotId", "SELECTED_REPORT");
    }

    /** 순수 문법 seed에 실제 저장할 필수 원고와 모든 후보의 자료를 명시적으로 추가한다. */
    private Fixture fixture() {
        ObjectNode source = FrozenSnapshotContractTest.complete();
        ObjectNode basic = (ObjectNode) source.get("sections").get("basic");
        basic.put("estMin", 15).put("estMax", 30).put("timelineOrigin", "사건 당일 자정");
        for (JsonNode row : source.get("resources").get("persons"))
            ((ObjectNode) row).put("publicText", "공개 소개: 합성 시험용 인물 자료");
        ((ObjectNode) source.get("resources").get("persons").get(0)).putNull("secretText");
        ((ObjectNode) source.get("resources").get("persons").get(1)).put("secretText", "");
        ((ObjectNode) source.get("resources").get("clues").get(1))
                .putNull("personCode")
                .putNull("sourceText");
        for (JsonNode row : source.get("resources").get("hints"))
            ((ObjectNode) row).put("body", "관찰과 연결을 확인하는 합성 힌트");
        ((ArrayNode) source.get("resources").get("hints"))
                .add(parse("{\"code\":\"H3\",\"level\":3,\"body\":\"추론 확인용 합성 힌트\"}"));
        ((ArrayNode) source.get("resources").get("clueRoles"))
                .add(parse("{\"clueCode\":\"C2\",\"roleCode\":\"R3\"}"));
        for (JsonNode row : source.get("resources").get("events"))
            ((ObjectNode) row)
                    .put("startMin", 0)
                    .put("endMin", 1)
                    .put("actualText", "실제 합성 경과")
                    .put("apparentText", "표면 합성 경과");
        for (JsonNode row : source.get("resources").get("facts"))
            ((ObjectNode) row).put("statement", "합성 사실 명제").put("basis", "C1/C2의 합성 연결");
        for (JsonNode row : source.get("resources").get("rubrics"))
            ((ObjectNode) row)
                    .put("acceptedText", "등록 경로를 충족하는 표현")
                    .put("partialText", "등록 부분 단계")
                    .put("rejectText", "등록 모순 기준");
        return fixture(source);
    }

    /**
     * 열한 종류의 실제 컬럼에 충실하게 저장하며 순수 seed를 실제 의미 증거로 취급하지 않는다.
     *
     * @param source null이 아닌 새 합성 문법 원본이며 이 fixture가 코드·checker 참조를 소유한다
     * @return 실제 계정·버전·저장 자료와 비교용 원본
     */
    private Fixture fixture(ObjectNode source) {
        Account owner = account(true);
        Account checker = account(false);
        var created =
                stories.createStory(
                        owner.sid(),
                        owner.principal(),
                        UUID.randomUUID(),
                        "합성 검수 사건",
                        UUID.randomUUID());
        long story =
                db.queryForObject(
                        "SELECT id FROM story WHERE code=?", Long.class, created.storyCode());
        long version =
                db.queryForObject(
                        "SELECT id FROM story_version WHERE story_id=?", Long.class, story);
        source.put("storyCode", created.storyCode()).put("sourceRev", "1");
        for (JsonNode row : source.get("resources").get("gradeSamples"))
            ((ObjectNode) row).put("checkedBy", checker.principal().accountKey().toString());
        JsonNode resources = source.get("resources");
        save(
                version,
                resources,
                "persons",
                "story_person",
                "code,name,public_text,secret_text",
                "code,name,publicText,secretText");
        save(version, resources, "roles", "story_role", "code,name,brief", "code,name,brief");
        save(version, resources, "pairs", "story_pair", "role_a,role_b", "roleA,roleB");
        save(
                version,
                resources,
                "clues",
                "story_clue",
                "code,title,body,person_code,scope,source_text",
                "code,title,body,personCode,scope,sourceText");
        save(
                version,
                resources,
                "clueRoles",
                "clue_role",
                "clue_code,role_code",
                "clueCode,roleCode");
        save(version, resources, "hints", "story_hint", "code,level,body", "code,level,body");
        save(
                version,
                resources,
                "events",
                "story_event",
                "code,start_min,end_min,actual_text,apparent_text",
                "code,startMin,endMin,actualText,apparentText");
        save(
                version,
                resources,
                "facts",
                "story_fact",
                "code,statement,truth,basis",
                "code,statement,truth,basis");
        save(
                version,
                resources,
                "rubrics",
                "story_rubric",
                "code,category,max_score,required_yn,pass_score,accepted_text,partial_text,reject_text,rule_data",
                "code,category,maxScore,requiredYn,passScore,acceptedText,partialText,rejectText,ruleData");
        save(
                version,
                resources,
                "rubricClues",
                "rubric_clue",
                "rubric_code,clue_code,link_text",
                "rubricCode,clueCode,linkText");
        for (JsonNode row : resources.get("gradeSamples"))
            db.update(
                    "INSERT INTO"
                        + " grade_sample(version_id,code,input_data,expect_data,expected_score,expected_success,reason,checked_by)"
                        + " VALUES (?,?,?::jsonb,?::jsonb,?,?,?,?)",
                    version,
                    value(row.get("code")),
                    jsonValue(row.get("inputData")),
                    jsonValue(row.get("expectData")),
                    value(row.get("expectedScore")),
                    value(row.get("expectedSuccess")),
                    value(row.get("reason")),
                    checker.principal().accountId());
        JsonNode basic = source.get("sections").get("basic");
        JsonNode answer = source.get("sections").get("answer");
        db.update(
                "UPDATE story_version SET"
                    + " edit_rev=1,title=?,intro=?,setting=?,difficulty=?,est_min=?,est_max=?,limit_sec=?,timeline_origin=?,culprit_code=?,method_answer=?,time_answer=?,motive_answer=?,reveal_text=?"
                    + " WHERE id=?",
                value(basic.get("title")),
                value(basic.get("intro")),
                value(basic.get("setting")),
                value(basic.get("difficulty")),
                value(basic.get("estMin")),
                value(basic.get("estMax")),
                value(basic.get("limitSec")),
                value(basic.get("timelineOrigin")),
                value(answer.get("culpritCode")),
                value(answer.get("methodAnswer")),
                value(answer.get("timeAnswer")),
                value(answer.get("motiveAnswer")),
                value(source.get("sections").get("reveal").get("revealText")),
                version);
        return new Fixture(owner, checker, created.storyCode(), story, version, source);
    }

    /**
     * 명시적인 대응만 사용하는 합성 저장기이며 제품의 범용 SQL 경로가 아니다.
     *
     * @param version 같은 합성 버전의 양의 내부 ID
     * @param resources null이 아닌 비교용 원본 자원 객체
     * @param resource 아래에서 고정한 자원명
     * @param table 아래에서 고정한 실제 테이블명
     * @param columns 실제 컬럼의 쉼표 목록
     * @param fields 대응하는 사본 필드의 쉼표 목록
     */
    private void save(
            long version,
            JsonNode resources,
            String resource,
            String table,
            String columns,
            String fields) {
        String[] names = fields.split(",");
        List<String> placeholders = new ArrayList<>();
        for (String name : names) placeholders.add(name.equals("ruleData") ? "?::jsonb" : "?");
        for (JsonNode row : resources.get(resource)) {
            List<Object> values = new ArrayList<>();
            values.add(version);
            for (String name : names)
                values.add(
                        name.equals("ruleData") ? jsonValue(row.get(name)) : value(row.get(name)));
            db.update(
                    "INSERT INTO "
                            + table
                            + "(version_id,"
                            + columns
                            + ") VALUES (?,"
                            + String.join(",", placeholders)
                            + ")",
                    values.toArray());
        }
    }

    /**
     * 실제 저장된 계정·자격·Spring Session을 제품 확인기에 전달한다.
     *
     * @param create 사건 생성 권한 여부이며 다른 권한을 자동 부여하지 않는다
     * @return 저장 자격과 결속된 실제 세션의 합성 계정
     */
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

    private void grant(Fixture f, Account actor, String permission) {
        db.update(
                "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                        + " (?,?,?,?)",
                f.story(),
                actor.principal().accountId(),
                permission,
                f.owner().principal().accountId());
    }

    private StoryReviewService.PrecheckResult precheck(Fixture f, Account actor, String rev) {
        return reviews.precheck(
                actor.sid(), actor.principal(), f.code(), 1, rev, UUID.randomUUID());
    }

    private ReviewResult request(Fixture f, Account actor, String rev, UUID key, UUID requestId) {
        return reviews.requestReview(
                actor.sid(), actor.principal(), f.code(), 1, rev, key, requestId);
    }

    private List<Map<String, Object>> sampleRows(Fixture f) {
        return db.queryForList(
                "SELECT * FROM grade_sample WHERE version_id=? ORDER BY code", f.version());
    }

    /** 업무 원고·메타데이터·불변 행·변경 감사를 비교하며 필수 READ만 제외한다. */
    private Map<String, Object> business(Fixture f) {
        return Map.of(
                "version",
                db.queryForMap("SELECT * FROM story_version WHERE id=?", f.version()),
                "samples",
                sampleRows(f),
                "snapshots",
                count(f, "review_snapshot"),
                "records",
                count(f, "review_record"),
                "requests",
                auditCount(f, "REVIEW_REQUESTED"));
    }

    private int count(Fixture f, String table) {
        String sql =
                table.equals("review_record")
                        ? "SELECT count(*) FROM review_record WHERE snapshot_id IN (SELECT id FROM"
                                + " review_snapshot WHERE version_id=?)"
                        : "SELECT count(*) FROM review_snapshot WHERE version_id=?";
        return db.queryForObject(sql, Integer.class, f.version());
    }

    private int auditCount(Fixture f, String action) {
        return db.queryForObject(
                "SELECT count(*) FROM story_audit WHERE version_id=? AND action=?",
                Integer.class,
                f.version(),
                action);
    }

    private JsonNode payload(ReviewResult result) {
        return parse(
                db.queryForObject(
                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                        String.class,
                        Long.parseLong(result.snapshotId())));
    }

    private static JsonNode sample(JsonNode root, String code) {
        for (JsonNode row : root.get("resources").get("gradeSamples"))
            if (code.equals(row.get("code").textValue())) return row;
        throw new AssertionError(code);
    }

    private static JsonNode parse(String value) {
        return SnapshotJson.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Object value(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.intValue();
        throw new AssertionError("합성 저장 자료형");
    }

    private static String jsonValue(JsonNode node) {
        return node.isNull() ? null : new String(SnapshotJson.encode(node), StandardCharsets.UTF_8);
    }

    private static void assertFailure(Callable<?> action, int status, String code) {
        assertThatThrownBy(action::call)
                .isInstanceOfSatisfying(
                        AuthException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(status);
                            assertThat(error.code()).isEqualTo(code);
                        });
    }

    private String race(Fixture f, Account actor, UUID key, CyclicBarrier barrier)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return request(f, actor, "1", key, UUID.randomUUID()).status();
        } catch (AuthException rejected) {
            return rejected.code();
        }
    }

    private Cookie cookie(Account actor) {
        var response = new MockHttpServletResponse();
        sessions.issueCookie(actor.sid(), new MockHttpServletRequest(), response);
        return response.getCookie("__Host-admin-session");
    }

    private static MockHttpServletRequestBuilder authorized(
            String path, Cookie csrf, String token) {
        return httpsPost(path)
                .cookie(csrf)
                .header("X-CSRF-TOKEN", token)
                .header("Origin", "https://localhost")
                .contentType("application/json");
    }

    private static MockHttpServletRequestBuilder httpsPost(String path) {
        return post(path)
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerPort(443);
                            return request;
                        });
    }

    private record Account(String sid, AdminPrincipal principal) {}

    private record Fixture(
            Account owner,
            Account checker,
            String code,
            long story,
            long version,
            ObjectNode source) {}
}
