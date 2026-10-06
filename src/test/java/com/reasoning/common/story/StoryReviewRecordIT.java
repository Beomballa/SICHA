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
import com.reasoning.common.story.service.StoryAccessService;
import com.reasoning.common.story.service.StoryReviewService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 실제 SR02 사본·PostgreSQL·저장 세션·서블릿을 사용하는 수동 근거·반환 회귀 정의다. 외부 실행은 하지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StoryReviewRecordIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    /** 폐기형 PostgreSQL와 합성 키만 사용한다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 121));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 122));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 123));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    @Autowired JdbcTemplate db;
    @Autowired CryptoService crypto;
    @Autowired AdminSessionAdapter sessions;
    @Autowired StoryService stories;
    @Autowired StoryAccessService access;
    @Autowired StoryReviewService reviews;
    @Autowired MockMvc mvc;

    @Test
    void modelAppendExactReplayAndAuditKeepContentRevisionAndCheckers() {
        Fixture f = fixture();
        JsonNode before = preserved(f);
        ObjectNode body = body("MODEL", "PASS");
        body.put("evidence", "별도 실제 보관 근거\r\n검토 의견");
        ((ObjectNode) body.get("evidenceData")).put("notes", "검토\r\n메모");
        UUID request = UUID.randomUUID();
        var created = record(f, f.owner(), body, request);
        assertThat(created.current()).isTrue();
        assertThat(created.replayed()).isFalse();
        JsonNode stored =
                parse(
                        db.queryForObject(
                                "SELECT to_jsonb(r)::text FROM review_record r WHERE id=?",
                                String.class,
                                Long.parseLong(created.recordId())));
        assertThat(stored.get("evidence").textValue()).isEqualTo("별도 실제 보관 근거\n검토 의견");
        assertThat(stored.get("self_review_yn").booleanValue()).isTrue();
        assertThat(stored.get("evidence_data").get("request").get("expectedRev").textValue())
                .isEqualTo("2");
        assertThat(preserved(f)).isEqualTo(before);
        assertThat(auditCount(f, "REVIEW_RECORDED")).isEqualTo(1);
        JsonNode audit =
                parse(
                        db.queryForObject(
                                "SELECT detail::text FROM story_audit WHERE detail->>'requestId'=?",
                                String.class,
                                request.toString()));
        assertThat(audit.toString()).doesNotContain("실제 보관", "메모", "gpt-6-astra", "run-review");
        ((ObjectNode) body.get("evidenceData")).put("formatNo", 1.0).put("criticalOpenCount", 0.0);
        var replay = record(f, f.owner(), body, UUID.randomUUID());
        assertThat(replay.recordId()).isEqualTo(created.recordId());
        assertThat(replay.replayed()).isTrue();
        assertThat(auditCount(f, "REVIEW_RECORDED")).isEqualTo(1);
        assertThat(preserved(f)).isEqualTo(before);
    }

    @Test
    void approvalAndExplicitNullIncompleteDoNotInventExecutionOrIndependentReviewer() {
        Fixture f = fixture();
        Account reviewer = account(false);
        grant(f, reviewer, "REVIEW");
        var approval = record(f, reviewer, body("APPROVAL", "PASS"), UUID.randomUUID());
        ObjectNode incomplete = body("MODEL", "INCOMPLETE");
        incomplete.putNull("modelId").putNull("effort");
        ObjectNode evidence = (ObjectNode) incomplete.get("evidenceData");
        for (String field :
                List.of(
                        "evidenceRef",
                        "checkedAt",
                        "criticalOpenCount",
                        "runRef",
                        "separateContext")) evidence.putNull(field);
        evidence.put("notes", "아직 실제 실행 근거가 없습니다.");
        var missing = record(f, reviewer, incomplete, UUID.randomUUID());
        var page = page(f, f.owner(), null, null);
        assertThat(page.items())
                .extracting(StoryReviewService.RecordItem::recordId)
                .contains(approval.recordId(), missing.recordId());
        var item =
                page.items().stream()
                        .filter(row -> row.recordId().equals(approval.recordId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(item.selfReviewYn()).isFalse();
        assertThat(item.modelId()).isNull();
        assertThat(item.evidenceData().get("runRef").isNull()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT edit_rev FROM story_version WHERE id=?",
                                Long.class,
                                f.version()))
                .isEqualTo(2L);
        evidence.put("formatNo", 2).putArray("resolves");
        failure(
                () -> record(f, reviewer, incomplete, UUID.randomUUID()),
                409,
                "REQUEST_KEY_CONFLICT");
        incomplete.put("requestKey", UUID.randomUUID().toString());
        assertThat(record(f, reviewer, incomplete, UUID.randomUUID()).replayed()).isFalse();
    }

    @Test
    void exactLargeIntegerCountsAndUnicodeCodePointBoundariesArePreserved() {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "FAIL");
        body.put("evidence", "𐐀".repeat(20000));
        ((ObjectNode) body.get("evidenceData"))
                .put("notes", "𐐀".repeat(4000))
                .set("criticalOpenCount", parse("123456789012345678901234567890"));
        var result = record(f, f.owner(), body, UUID.randomUUID());
        JsonNode saved =
                parse(
                        db.queryForObject(
                                "SELECT evidence_data::text FROM review_record WHERE id=?",
                                String.class,
                                Long.parseLong(result.recordId())));
        assertThat(saved.get("details").get("criticalOpenCount").bigIntegerValue())
                .isEqualTo(new java.math.BigInteger("123456789012345678901234567890"));
        body.put("evidence", "𐐀".repeat(20001)).put("requestKey", UUID.randomUUID().toString());
        failure(() -> record(f, f.owner(), body, UUID.randomUUID()), 422, "INVALID_INPUT");
        ObjectNode decimal = body("MODEL", "FAIL");
        ((ObjectNode) decimal.get("evidenceData")).set("criticalOpenCount", parse("2147483648.0"));
        assertThat(record(f, f.owner(), decimal, UUID.randomUUID()).replayed()).isFalse();
    }

    @Test
    void servletDecimalPrecisionSurvivesNormalizationStorageAndReplay() throws Exception {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "FAIL");
        ((ObjectNode) body.get("evidenceData"))
                .set("criticalOpenCount", parse("123456789012345678901234567890.0"));
        String suffix = "/review-snapshots/" + f.snapshot() + "/records";
        mvc.perform(mutation(f, suffix, body.toString())).andExpect(status().isCreated());
        ((ObjectNode) body.get("evidenceData"))
                .set("criticalOpenCount", parse("123456789012345678901234567890"));
        mvc.perform(mutation(f, suffix, body.toString())).andExpect(status().isOk());
        ((ObjectNode) body.get("evidenceData"))
                .set("criticalOpenCount", parse("123456789012345678901234567891.0"));
        mvc.perform(mutation(f, suffix, body.toString())).andExpect(status().isConflict());
    }

    @Test
    void knownUtcOffsetIsAcceptedButItsOriginalSpellingIsRetainedForReplay() {
        Fixture f = fixture();
        ObjectNode body = body("APPROVAL", "PASS");
        ((ObjectNode) body.get("evidenceData")).put("checkedAt", "2020-01-01T00:00:00+00:00");
        record(f, f.owner(), body, UUID.randomUUID());
        assertThat(record(f, f.owner(), body, UUID.randomUUID()).replayed()).isTrue();
        ((ObjectNode) body.get("evidenceData")).put("checkedAt", "2020-01-01T00:00:00Z");
        failure(() -> record(f, f.owner(), body, UUID.randomUUID()), 409, "REQUEST_KEY_CONFLICT");
    }

    @Test
    void formatTwoAllowsOneHundredUniquePriorFailuresAndRetainsArrayOrderForReplay() {
        Fixture f = fixture();
        ArrayNode resolves = (ArrayNode) parse("[]");
        for (int i = 0; i < 100; i++) {
            var failed = record(f, f.owner(), body("APPROVAL", "FAIL"), UUID.randomUUID());
            resolves.add(resolution(failed.recordId()));
        }
        ObjectNode body = body("APPROVAL", "PASS");
        ((ObjectNode) body.get("evidenceData")).put("formatNo", 2).set("resolves", resolves);
        var result = record(f, f.owner(), body, UUID.randomUUID());
        assertThat(record(f, f.owner(), body, UUID.randomUUID()).recordId())
                .isEqualTo(result.recordId());
        JsonNode first = resolves.get(0);
        JsonNode last = resolves.get(99);
        resolves.set(0, last);
        resolves.set(99, first);
        failure(() -> record(f, f.owner(), body, UUID.randomUUID()), 409, "REQUEST_KEY_CONFLICT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTION", "REASON", "REFERENCE", "REV_OVERFLOW", "SNAPSHOT_ZERO"})
    void invalidReturnContractNeverWrites(String mode) {
        Fixture f = fixture();
        JsonNode before = all(f);
        String action = "ACTION".equals(mode) ? "PUBLISH" : "WITHDRAW";
        String reason = "REASON".equals(mode) ? "CONTENT_DEFECT" : "AUTHOR_REVISION";
        String reference = "REFERENCE".equals(mode) ? "unsafe/path" : "return-check-01";
        String revision = "REV_OVERFLOW".equals(mode) ? "9223372036854775808" : "2";
        String snapshot = "SNAPSHOT_ZERO".equals(mode) ? "0" : f.snapshot();
        failure(
                () ->
                        reviews.returnToDraft(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                1,
                                revision,
                                snapshot,
                                action,
                                reason,
                                reference,
                                UUID.randomUUID()),
                400,
                "INVALID_REQUEST");
        assertThat(all(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "MODEL_NAME",
                "EFFORT",
                "NO_RUN",
                "FALSE_CONTEXT",
                "CRITICAL",
                "FUTURE",
                "NULL_PARTIAL",
                "BLANK",
                "NUL",
                "SURROGATE",
                "FORMAT3",
                "UNKNOWN",
                "MISSING",
                "DUPLICATE_RESOLVE",
                "NONINTEGRAL",
                "NEGATIVE",
                "REF",
                "PLAYTEST",
                "GRADE",
                "STRUCTURE",
                "NOTES_SIZE",
                "OFFSET_TIME",
                "UNKNOWN_OFFSET",
                "LEAP_SECOND",
                "RESOLVE_101",
                "INCOMPLETE_RESOLVE",
                "FAIL_RESOLVE"
            })
    void rejectedEvidenceNeverWritesOrChangesAnything(String scenario) {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "PASS");
        ObjectNode data = (ObjectNode) body.get("evidenceData");
        switch (scenario) {
            case "MODEL_NAME" -> body.put("modelId", "gpt-6-sol");
            case "EFFORT" -> body.put("effort", "high");
            case "NO_RUN" -> data.putNull("runRef");
            case "FALSE_CONTEXT" -> data.put("separateContext", false);
            case "CRITICAL" -> data.put("criticalOpenCount", 1);
            case "FUTURE" -> data.put("checkedAt", "2999-01-01T00:00:00Z");
            case "NULL_PARTIAL" -> data.putNull("evidenceRef");
            case "BLANK" -> body.put("evidence", "  ");
            case "NUL" -> body.put("evidence", "검토\0");
            case "SURROGATE" -> body.put("evidence", String.valueOf((char) 0xD800));
            case "FORMAT3" -> data.put("formatNo", 3);
            case "UNKNOWN" -> data.put("unapproved", true);
            case "MISSING" -> data.remove("notes");
            case "DUPLICATE_RESOLVE" -> {
                data.put("formatNo", 2);
                data.putArray("resolves").add(resolution("1")).add(resolution("1"));
            }
            case "NONINTEGRAL" -> data.put("criticalOpenCount", 0.5);
            case "NEGATIVE" -> data.put("criticalOpenCount", -1);
            case "REF" -> data.put("evidenceRef", "https://private.example");
            case "PLAYTEST", "GRADE", "STRUCTURE" -> body.put("kind", scenario);
            case "NOTES_SIZE" -> data.put("notes", "검".repeat(4001));
            case "OFFSET_TIME" -> data.put("checkedAt", "2020-01-01T00:00:00+09:00");
            case "UNKNOWN_OFFSET" -> data.put("checkedAt", "2020-01-01T00:00:00-00:00");
            case "LEAP_SECOND" -> data.put("checkedAt", "2020-01-01T00:00:60Z");
            case "RESOLVE_101" -> {
                data.put("formatNo", 2);
                ArrayNode rows = data.putArray("resolves");
                for (int i = 1; i <= 101; i++) rows.add(resolution(Integer.toString(i)));
            }
            case "INCOMPLETE_RESOLVE" -> {
                body.put("result", "INCOMPLETE");
                data.put("formatNo", 2).putArray("resolves").add(resolution("1"));
            }
            case "FAIL_RESOLVE" -> {
                body.put("result", "FAIL");
                data.put("formatNo", 2).putArray("resolves").add(resolution("1"));
            }
            default -> throw new AssertionError(scenario);
        }
        JsonNode before = all(f);
        assertThatThrownBy(() -> record(f, f.owner(), body, UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThat(all(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "expectedRev",
                "kind",
                "result",
                "modelId",
                "effort",
                "evidence",
                "evidenceData",
                "ACTOR"
            })
    void sameKeyAnyDifferentNormalizedInputOrActorConflicts(String field) {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "FAIL");
        record(f, f.owner(), body, UUID.randomUUID());
        Account actor = f.owner();
        switch (field) {
            case "expectedRev" -> body.put(field, "1");
            case "kind" -> {
                body.put(field, "APPROVAL").putNull("modelId").putNull("effort");
                ((ObjectNode) body.get("evidenceData"))
                        .putNull("runRef")
                        .putNull("separateContext");
            }
            case "result" -> body.put(field, "INCOMPLETE");
            case "modelId" -> body.put(field, "gpt-6-sol");
            case "effort" -> body.put(field, "high");
            case "evidence" -> body.put(field, "다른 실제 검토 의견");
            case "evidenceData" -> ((ObjectNode) body.get(field)).put("notes", "다른 메모");
            case "ACTOR" -> {
                actor = account(false);
                grant(f, actor, "REVIEW");
            }
            default -> throw new AssertionError(field);
        }
        Account selected = actor;
        JsonNode before = all(f);
        failure(() -> record(f, selected, body, UUID.randomUUID()), 409, "REQUEST_KEY_CONFLICT");
        assertThat(all(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT", "MFA", "AUTH_REV", "SESSION", "GLOBAL", "STORY"})
    void currentRevocationBlocksReplayBeforeHistoricalStateLookup(String mode) {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "PASS");
        record(f, f.owner(), body, UUID.randomUUID());
        withdraw(f, f.owner());
        switch (mode) {
            case "ACCOUNT" ->
                    db.update(
                            "UPDATE admin_account SET active_yn=false WHERE id=?",
                            f.owner().principal().accountId());
            case "MFA" ->
                    db.update(
                            "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                            f.owner().principal().accountId());
            case "AUTH_REV" ->
                    db.update(
                            "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?",
                            f.owner().principal().accountId());
            case "SESSION" ->
                    db.update(
                            "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp()"
                                    + " WHERE session_key=?",
                            f.owner().principal().sessionKey());
            case "GLOBAL" ->
                    db.update(
                            "UPDATE admin_account SET can_review=false WHERE id=?",
                            f.owner().principal().accountId());
            case "STORY" ->
                    db.update(
                            "UPDATE story_access SET active_yn=false WHERE story_id=? AND"
                                    + " permission='REVIEW'",
                            f.story());
            default -> throw new AssertionError(mode);
        }
        JsonNode before = all(f);
        failure(
                () -> record(f, f.owner(), body, UUID.randomUUID()),
                Set.of("GLOBAL", "STORY").contains(mode) ? 403 : 401,
                Set.of("GLOBAL", "STORY").contains(mode) ? "FORBIDDEN" : "AUTH_REQUIRED");
        assertThat(all(f)).isEqualTo(before);
    }

    @Test
    void returnPreservesHistoryThenReplayPrecedesOldRevisionAndSnapshotChecks() {
        Fixture f = fixture();
        ObjectNode body = body("APPROVAL", "PASS");
        var original = record(f, f.owner(), body, UUID.randomUUID());
        JsonNode source = storedPayload(f);
        JsonNode children = preserved(f).get("grade_sample");
        var returned = withdraw(f, f.owner());
        assertThat(returned.status()).isEqualTo("DRAFT");
        assertThat(returned.editRev()).isEqualTo("3");
        assertThat(returned.currentSnapshotId()).isNull();
        assertThat(returned.snapshotId()).isEqualTo(f.snapshot());
        assertThat(storedPayload(f)).isEqualTo(source);
        assertThat(preserved(f).get("grade_sample")).isEqualTo(children);
        assertThat(auditCount(f, "REVIEW_WITHDRAWN")).isEqualTo(1);
        JsonNode before = all(f);
        var replay = record(f, f.owner(), body, UUID.randomUUID());
        assertThat(replay.recordId()).isEqualTo(original.recordId());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.current()).isFalse();
        assertThat(all(f)).isEqualTo(before);
        failure(() -> withdraw(f, f.owner()), 409, "STATE_CONFLICT");
        ObjectNode newBody = body("APPROVAL", "PASS");
        failure(() -> record(f, f.owner(), newBody, UUID.randomUUID()), 409, "STATE_CONFLICT");
        String newSnapshot =
                reviews.requestReview(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                1,
                                "3",
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .snapshotId();
        assertThat(newSnapshot).isNotEqualTo(f.snapshot());
        newBody.put("expectedRev", "4");
        failure(() -> record(f, f.owner(), newBody, UUID.randomUUID()), 409, "SNAPSHOT_CONFLICT");
        assertThat(record(f, f.owner(), body, UUID.randomUUID()).replayed()).isTrue();
    }

    @Test
    void formatTwoPassOnlyResolvesEarlierSameSnapshotSameKindFailure() {
        Fixture f = fixture();
        var failed = record(f, f.owner(), body("MODEL", "FAIL"), UUID.randomUUID());
        ObjectNode pass = body("MODEL", "PASS");
        ObjectNode data = (ObjectNode) pass.get("evidenceData");
        data.put("formatNo", 2).putArray("resolves").add(resolution(failed.recordId()));
        var success = record(f, f.owner(), pass, UUID.randomUUID());
        assertThat(Long.parseLong(success.recordId()))
                .isGreaterThan(Long.parseLong(failed.recordId()));
        var page = page(f, f.owner(), null, null);
        assertThat(
                        page.items().stream()
                                .filter(row -> row.recordId().equals(failed.recordId()))
                                .findFirst()
                                .orElseThrow()
                                .result())
                .isEqualTo("FAIL");
        for (String id : List.of(success.recordId(), "9223372036854775807")) {
            ObjectNode bad = body("MODEL", "PASS");
            ((ObjectNode) bad.get("evidenceData"))
                    .put("formatNo", 2)
                    .putArray("resolves")
                    .add(resolution(id));
            failure(() -> record(f, f.owner(), bad, UUID.randomUUID()), 422, "REVIEW_NOT_READY");
        }
        var wrongKind = record(f, f.owner(), body("APPROVAL", "FAIL"), UUID.randomUUID());
        ObjectNode bad = body("MODEL", "PASS");
        ((ObjectNode) bad.get("evidenceData"))
                .put("formatNo", 2)
                .putArray("resolves")
                .add(resolution(wrongKind.recordId()));
        failure(() -> record(f, f.owner(), bad, UUID.randomUUID()), 422, "REVIEW_NOT_READY");
        Fixture foreign = fixture();
        var foreignFailure =
                record(foreign, foreign.owner(), body("MODEL", "FAIL"), UUID.randomUUID());
        ((ArrayNode) bad.get("evidenceData").get("resolves"))
                .removeAll()
                .add(resolution(foreignFailure.recordId()));
        failure(() -> record(f, f.owner(), bad, UUID.randomUUID()), 422, "REVIEW_NOT_READY");
    }

    @Test
    void historicalReadUsesCurrentCreatorMaterialAccessAndRetainsInactiveReviewerUuid() {
        Fixture f = fixture();
        Account reviewer = account(false);
        grant(f, reviewer, "REVIEW");
        var record = record(f, reviewer, body("APPROVAL", "PASS"), UUID.randomUUID());
        withdraw(f, f.owner());
        db.update(
                "UPDATE admin_account SET active_yn=false WHERE id=?",
                reviewer.principal().accountId());
        db.update(
                "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                reviewer.principal().accountId());
        Account publisher = account(false);
        grant(f, publisher, "PUBLISH");
        var page = page(f, publisher, null, null);
        assertThat(page.current()).isFalse();
        assertThat(
                        page.items().stream()
                                .filter(row -> row.recordId().equals(record.recordId()))
                                .findFirst()
                                .orElseThrow()
                                .reviewerAccountKey())
                .isEqualTo(reviewer.principal().accountKey());
        Account editor = account(false);
        grant(f, editor, "EDIT");
        assertThat(page(f, editor, null, null).items()).hasSize(2);
        db.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                f.story(),
                editor.principal().accountId());
        failure(() -> page(f, editor, null, null), 404, "NOT_FOUND");
        Fixture other = fixture();
        failure(
                () ->
                        reviews.getReviewRecordList(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                1,
                                other.snapshot(),
                                null,
                                null,
                                UUID.randomUUID()),
                404,
                "NOT_FOUND");
        db.update("UPDATE story_version SET active_yn=false WHERE id=?", f.version());
        failure(() -> page(f, publisher, null, null), 404, "NOT_FOUND");
        assertThat(page(f, f.owner(), null, null).items()).hasSize(2);
    }

    @Test
    void moreThanHundredRecordsUseExclusiveDescendingCursorWithSizePlusOne() {
        Fixture f = fixture();
        ObjectNode body = body("APPROVAL", "FAIL");
        for (int i = 0; i < 105; i++) {
            body.put("requestKey", UUID.randomUUID().toString());
            record(f, f.owner(), body, UUID.randomUUID());
        }
        var first = page(f, f.owner(), 100, null);
        assertThat(first.items()).hasSize(100);
        assertThat(first.hasNext()).isTrue();
        body.put("requestKey", UUID.randomUUID().toString());
        record(f, f.owner(), body, UUID.randomUUID());
        var second = page(f, f.owner(), 100, first.nextAfterId());
        assertThat(second.items()).hasSize(6);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.nextAfterId()).isNull();
        Set<String> ids = new HashSet<>();
        first.items().forEach(row -> assertThat(ids.add(row.recordId())).isTrue());
        second.items().forEach(row -> assertThat(ids.add(row.recordId())).isTrue());
        assertThat(ids).hasSize(106);
        assertThat(page(f, f.owner(), null, null).items()).hasSize(20);
    }

    @Test
    void returnPermissionsAreActionSpecificAndReadyIsAllowedButPublishedIsNot() {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        failure(() -> changes(f, editor, "2", f.snapshot()), 403, "FORBIDDEN");
        db.update("UPDATE story_version SET status='READY' WHERE id=?", f.version());
        assertThat(withdraw(f, editor).status()).isEqualTo("DRAFT");
        Fixture review = fixture();
        Account reviewer = account(false);
        grant(review, reviewer, "REVIEW");
        failure(() -> withdraw(review, reviewer), 403, "FORBIDDEN");
        failure(() -> changes(review, reviewer, "1", review.snapshot()), 409, "EDIT_CONFLICT");
        failure(() -> changes(review, reviewer, "2", f.snapshot()), 409, "SNAPSHOT_CONFLICT");
        assertThat(changes(review, reviewer, "2", review.snapshot()).status()).isEqualTo("DRAFT");
        assertThat(auditCount(review, "REVIEW_RETURNED")).isEqualTo(1);
        Fixture published = fixture();
        db.update("UPDATE story_version SET status='PUBLISHED' WHERE id=?", published.version());
        failure(() -> withdraw(published, published.owner()), 409, "STATE_CONFLICT");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"REVIEW_RECORDED", "REVIEW_WITHDRAWN", "REVIEW_RETURNED", "CONTENT_READ"})
    void mandatoryAuditFailureBlocksWritesAndSensitiveReads(String event) {
        Fixture f = fixture();
        JsonNode before = all(f);
        String function = "h3_record_audit_" + UUID.randomUUID().toString().replace("-", "");
        db.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='"
                        + event
                        + "' THEN RETURN NULL; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + function
                        + " BEFORE INSERT ON story_audit FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            Callable<?> action =
                    switch (event) {
                        case "REVIEW_RECORDED" ->
                                () ->
                                        record(
                                                f,
                                                f.owner(),
                                                body("MODEL", "PASS"),
                                                UUID.randomUUID());
                        case "REVIEW_WITHDRAWN" -> () -> withdraw(f, f.owner());
                        case "REVIEW_RETURNED" -> () -> changes(f, f.owner(), "2", f.snapshot());
                        case "CONTENT_READ" -> () -> page(f, f.owner(), null, null);
                        default -> throw new AssertionError(event);
                    };
            failure(action, 503, "STORY_UNAVAILABLE");
            assertThat(all(f)).isEqualTo(before);
        } finally {
            db.execute("DROP TRIGGER " + function + " ON story_audit");
            db.execute("DROP FUNCTION " + function + "()");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "RESULT",
                "ACTOR",
                "SNAPSHOT",
                "WRAPPER",
                "SELF",
                "EVIDENCE",
                "CONTENT",
                "CHECKER",
                "OLD_RECORD",
                "ADD_FACT",
                "DELETE_HINT",
                "ADD_RECORD",
                "DELETE_RECORD",
                "AUDIT"
            })
    void successfulRecordTriggerCorruptionRollsBackWholeTransaction(String mode) {
        Fixture f = fixture();
        JsonNode before = all(f);
        String function = "h3_record_mutate_" + UUID.randomUUID().toString().replace("-", "");
        String mutation =
                switch (mode) {
                    case "RESULT" -> "NEW.result='FAIL';";
                    case "ACTOR" -> "NEW.reviewer_id=" + f.checker().principal().accountId() + ";";
                    case "SNAPSHOT" ->
                            "UPDATE review_snapshot SET edit_rev=edit_rev+1 WHERE"
                                    + " id=NEW.snapshot_id;";
                    case "WRAPPER" ->
                            "NEW.evidence_data=jsonb_set(NEW.evidence_data,'{request,expectedRev}','\"1\"');";
                    case "SELF" -> "NEW.self_review_yn=false;";
                    case "EVIDENCE" -> "NEW.evidence='변조';";
                    case "CONTENT" ->
                            "UPDATE story_version SET title='변조' WHERE id=" + f.version() + ";";
                    case "CHECKER" ->
                            "UPDATE grade_sample SET checked_by=NULL WHERE version_id="
                                    + f.version()
                                    + ";";
                    case "OLD_RECORD" ->
                            "UPDATE review_record SET evidence='변조' WHERE kind='STRUCTURE' AND"
                                    + " snapshot_id=NEW.snapshot_id;";
                    case "ADD_FACT" ->
                            "INSERT INTO story_fact(version_id,code,statement,truth,basis) VALUES ("
                                    + f.version()
                                    + ",'ADDED','합성 추가','TRUE','합성 근거');";
                    case "DELETE_HINT" ->
                            "DELETE FROM story_hint WHERE version_id="
                                    + f.version()
                                    + " AND code='H3';";
                    case "ADD_RECORD" ->
                            "INSERT INTO"
                                + " review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence,self_review_yn)"
                                + " SELECT snapshot_id,'APPROVAL','"
                                    + UUID.randomUUID()
                                    + "',evidence_data,'PASS',reviewer_id,evidence,self_review_yn"
                                    + " FROM review_record WHERE snapshot_id=NEW.snapshot_id AND"
                                    + " kind='STRUCTURE';";
                    case "DELETE_RECORD" ->
                            "DELETE FROM review_record WHERE snapshot_id=NEW.snapshot_id AND"
                                + " kind='STRUCTURE';";
                    case "AUDIT" ->
                            "UPDATE story_audit SET after_rev=99 WHERE version_id="
                                    + f.version()
                                    + ";";
                    default -> throw new AssertionError(mode);
                };
        db.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.kind='MODEL' THEN"
                        + " "
                        + mutation
                        + " END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + function
                        + " BEFORE INSERT ON review_record FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            failure(
                    () -> record(f, f.owner(), body("MODEL", "PASS"), UUID.randomUUID()),
                    503,
                    "STORY_UNAVAILABLE");
            assertThat(all(f)).isEqualTo(before);
        } finally {
            db.execute("DROP TRIGGER " + function + " ON review_record");
            db.execute("DROP FUNCTION " + function + "()");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "REV",
                "TITLE",
                "POINTER",
                "CHECKER",
                "SNAPSHOT",
                "OLD_RECORD",
                "ADD_FACT",
                "DELETE_HINT",
                "ADD_RECORD",
                "DELETE_RECORD",
                "TIME"
            })
    void successfulReturnTriggerCorruptionRollsBackHistoryAndVersion(String mode) {
        Fixture f = fixture();
        JsonNode before = all(f);
        String function = "h3_return_mutate_" + UUID.randomUUID().toString().replace("-", "");
        String mutation =
                switch (mode) {
                    case "REV" -> "NEW.edit_rev=NEW.edit_rev+1;";
                    case "TITLE" -> "NEW.title='변조';";
                    case "POINTER" -> "NEW.current_snapshot_id=OLD.current_snapshot_id;";
                    case "CHECKER" ->
                            "UPDATE grade_sample SET checked_by=NULL WHERE version_id=NEW.id;";
                    case "SNAPSHOT" ->
                            "UPDATE review_snapshot SET edit_rev=99 WHERE version_id=NEW.id;";
                    case "OLD_RECORD" ->
                            "UPDATE review_record SET evidence='변조' WHERE"
                                    + " snapshot_id=OLD.current_snapshot_id;";
                    case "ADD_FACT" ->
                            "INSERT INTO story_fact(version_id,code,statement,truth,basis) VALUES ("
                                    + f.version()
                                    + ",'ADDED','합성 추가','TRUE','합성 근거');";
                    case "DELETE_HINT" ->
                            "DELETE FROM story_hint WHERE version_id=NEW.id AND code='H3';";
                    case "ADD_RECORD" ->
                            "INSERT INTO"
                                + " review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence,self_review_yn)"
                                + " SELECT snapshot_id,'APPROVAL','"
                                    + UUID.randomUUID()
                                    + "',evidence_data,'PASS',reviewer_id,evidence,self_review_yn"
                                    + " FROM review_record WHERE"
                                    + " snapshot_id=OLD.current_snapshot_id AND kind='STRUCTURE';";
                    case "DELETE_RECORD" ->
                            "DELETE FROM review_record WHERE snapshot_id=OLD.current_snapshot_id"
                                + " AND kind='STRUCTURE';";
                    case "TIME" -> "NEW.updated_at='2000-01-01T00:00:00Z';";
                    default -> throw new AssertionError(mode);
                };
        db.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='DRAFT'"
                        + " AND OLD.status='REVIEW' THEN "
                        + mutation
                        + " END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + function
                        + " BEFORE UPDATE ON story_version FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            failure(() -> withdraw(f, f.owner()), 503, "STORY_UNAVAILABLE");
            assertThat(all(f)).isEqualTo(before);
        } finally {
            db.execute("DROP TRIGGER " + function + " ON story_version");
            db.execute("DROP FUNCTION " + function + "()");
        }
    }

    @Test
    void simultaneousSameKeyAppendsOnceAndReturnVersusRecordSerializes() throws Exception {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "PASS");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<StoryReviewService.RecordResult> action =
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return record(f, f.owner(), body, UUID.randomUUID());
                    };
            var a = executor.submit(action);
            var b = executor.submit(action);
            var one = a.get(20, TimeUnit.SECONDS);
            var two = b.get(20, TimeUnit.SECONDS);
            assertThat(one.recordId()).isEqualTo(two.recordId());
            assertThat(one.replayed()).isNotEqualTo(two.replayed());
            assertThat(auditCount(f, "REVIEW_RECORDED")).isEqualTo(1);
        }
        CyclicBarrier race = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var returned =
                    executor.submit(
                            () -> {
                                race.await(10, TimeUnit.SECONDS);
                                return withdraw(f, f.owner());
                            });
            var recorded =
                    executor.submit(
                            () -> {
                                race.await(10, TimeUnit.SECONDS);
                                try {
                                    return record(
                                                    f,
                                                    f.owner(),
                                                    body("APPROVAL", "PASS"),
                                                    UUID.randomUUID())
                                            .current();
                                } catch (AuthException conflict) {
                                    assertThat(conflict.code()).isEqualTo("STATE_CONFLICT");
                                    return false;
                                }
                            });
            assertThat(returned.get(20, TimeUnit.SECONDS).status()).isEqualTo("DRAFT");
            boolean first = recorded.get(20, TimeUnit.SECONDS);
            assertThat(auditCount(f, "REVIEW_RECORDED")).isEqualTo(first ? 2 : 1);
            assertThat(
                            db.queryForObject(
                                    "SELECT edit_rev FROM story_version WHERE id=?",
                                    Long.class,
                                    f.version()))
                    .isEqualTo(3L);
        }
    }

    @Test
    void reviewPermissionRevocationRaceNeverAuthorizesFromHistoricalReceipt() throws Exception {
        Fixture f = fixture();
        Account reviewer = account(false);
        grant(f, reviewer, "EDIT");
        grant(f, reviewer, "REVIEW");
        Account manager = account(false);
        db.update(
                "UPDATE admin_account SET can_manage=true WHERE id=?",
                manager.principal().accountId());
        String storyRev =
                db.queryForObject("SELECT edit_rev FROM story WHERE id=?", Long.class, f.story())
                        .toString();
        ObjectNode body = body("MODEL", "PASS");
        JsonNode before = preserved(f);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revoke =
                    executor.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                return access.revokeAccess(
                                        manager.sid(),
                                        manager.principal(),
                                        f.code(),
                                        storyRev,
                                        reviewer.principal().accountKey(),
                                        "REVIEW",
                                        "ACCESS_REVIEW",
                                        "access-check-01",
                                        UUID.randomUUID());
                            });
            var record =
                    executor.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                try {
                                    return record(f, reviewer, body, UUID.randomUUID()).recordId();
                                } catch (AuthException denied) {
                                    assertThat(denied.code()).isEqualTo("FORBIDDEN");
                                    return null;
                                }
                            });
            assertThat(revoke.get(20, TimeUnit.SECONDS)).isNotNull();
            String accepted = record.get(20, TimeUnit.SECONDS);
            assertThat(auditCount(f, "REVIEW_RECORDED")).isEqualTo(accepted == null ? 0 : 1);
        }
        failure(() -> record(f, reviewer, body, UUID.randomUUID()), 403, "FORBIDDEN");
        failure(() -> changes(f, reviewer, "2", f.snapshot()), 403, "FORBIDDEN");
        assertThat(preserved(f)).isEqualTo(before);
    }

    @Test
    void realServletRecordsListsAndReturnsWithExactDtosNoStoreAndCsrf() throws Exception {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "PASS");
        var created =
                mvc.perform(
                                mutation(
                                        f,
                                        "/review-snapshots/" + f.snapshot() + "/records",
                                        body.toString()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        assertThat(created.getHeader("Cache-Control")).contains("no-store");
        assertThat(names(parse(created.getContentAsString())))
                .containsExactlyInAnyOrder(
                        "recordId", "snapshotId", "current", "replayed", "requestId");
        mvc.perform(mutation(f, "/review-snapshots/" + f.snapshot() + "/records", body.toString()))
                .andExpect(status().isOk());
        var list =
                mvc.perform(
                                https(
                                                get(
                                                        root(f)
                                                                + "/review-snapshots/"
                                                                + f.snapshot()
                                                                + "/records"))
                                        .cookie(cookie(f.owner())))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        JsonNode page = parse(list.getContentAsString());
        assertThat(names(page))
                .containsExactlyInAnyOrder(
                        "snapshotId", "current", "items", "hasNext", "nextAfterId");
        assertThat(names(page.get("items").get(0)))
                .containsExactlyInAnyOrder(
                        "recordId",
                        "kind",
                        "result",
                        "reviewerAccountKey",
                        "modelId",
                        "effort",
                        "evidence",
                        "evidenceData",
                        "selfReviewYn",
                        "createdAt");
        assertThat(page.get("items").get(0).get("evidenceData").has("request")).isFalse();
        ObjectNode returned =
                parseObject(
                        "{\"expectedRev\":\"2\",\"expectedSnapshotId\":\""
                                + f.snapshot()
                                + "\",\"action\":\"WITHDRAW\",\"reasonCode\":\"AUTHOR_REVISION\",\"verificationRef\":\"return-check-01\"}");
        mvc.perform(mutation(f, "/return-to-draft", returned.toString()))
                .andExpect(status().isOk());
        mvc.perform(mutation(f, "/return-to-draft", returned.toString()))
                .andExpect(status().isConflict());
        mvc.perform(
                        https(post(root(f) + "/return-to-draft"))
                                .cookie(cookie(f.owner()))
                                .contentType("application/json")
                                .content(returned.toString()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DUPLICATE",
                "UNKNOWN",
                "MODEL_TYPE",
                "SIZE",
                "QUERY",
                "FORMAT",
                "FORMAT1_RESOLVES",
                "UTF8"
            })
    void servletStrictInputsRejectWithoutBusinessWrites(String mode) throws Exception {
        Fixture f = fixture();
        ObjectNode body = body("MODEL", "PASS");
        String raw = body.toString();
        String suffix = "/review-snapshots/" + f.snapshot() + "/records";
        int expected = 400;
        switch (mode) {
            case "DUPLICATE" -> raw = "{\"kind\":\"MODEL\"," + raw.substring(1);
            case "UNKNOWN" -> {
                body.put("extra", true);
                raw = body.toString();
            }
            case "MODEL_TYPE" -> {
                body.put("modelId", 7);
                raw = body.toString();
            }
            case "SIZE" -> {
                raw = " ".repeat(131073);
                expected = 413;
            }
            case "QUERY" -> suffix += "?extra=1";
            case "FORMAT" -> {
                ((ObjectNode) body.get("evidenceData")).put("formatNo", 3);
                raw = body.toString();
            }
            case "FORMAT1_RESOLVES" -> {
                ((ObjectNode) body.get("evidenceData")).putArray("resolves");
                raw = body.toString();
            }
            case "UTF8" -> {}
            default -> throw new AssertionError(mode);
        }
        JsonNode before = all(f);
        var request = mutation(f, suffix, raw);
        if ("UTF8".equals(mode)) request.content(new byte[] {(byte) 0xC3, (byte) 0x28});
        mvc.perform(request).andExpect(status().is(expected));
        assertThat(all(f)).isEqualTo(before);
        mvc.perform(
                        https(
                                        get(
                                                root(f)
                                                        + "/review-snapshots/"
                                                        + f.snapshot()
                                                        + "/records?size=1&size=2"))
                                .cookie(cookie(f.owner())))
                .andExpect(status().isBadRequest());
    }

    /** 기존 요청/읽기 IT의 완전 사본 fixture와 같은 실제 열한 자원을 SR02로 고정한다. */
    private Fixture fixture() {
        ObjectNode source = FrozenSnapshotContractTest.complete();
        ObjectNode basic = (ObjectNode) source.get("sections").get("basic");
        basic.put("estMin", 15).put("estMax", 30).put("timelineOrigin", "합성 시간선");
        ObjectNode answer = (ObjectNode) source.get("sections").get("answer");
        answer.put("methodAnswer", "합성 수법").put("timeAnswer", "합성 시각").put("motiveAnswer", "합성 동기");
        ((ObjectNode) source.get("sections").get("reveal")).put("revealText", "합성 해설");
        JsonNode resources = source.get("resources");
        for (JsonNode row : resources.get("persons"))
            ((ObjectNode) row).put("publicText", "합성 공개 소개");
        for (JsonNode row : resources.get("hints")) ((ObjectNode) row).put("body", "합성 힌트 본문");
        ((ArrayNode) resources.get("hints"))
                .add(parse("{\"code\":\"H3\",\"level\":3,\"body\":\"합성 힌트\"}"));
        ((ArrayNode) resources.get("clueRoles"))
                .add(parse("{\"clueCode\":\"C2\",\"roleCode\":\"R3\"}"));
        for (JsonNode row : resources.get("events"))
            ((ObjectNode) row)
                    .put("startMin", 0)
                    .put("endMin", 1)
                    .put("actualText", "실제 합성 사건")
                    .put("apparentText", "표면 합성 사건");
        for (JsonNode row : resources.get("facts"))
            ((ObjectNode) row).put("statement", "합성 사실").put("basis", "합성 근거");
        for (JsonNode row : resources.get("rubrics"))
            ((ObjectNode) row)
                    .put("acceptedText", "합성 정상")
                    .put("partialText", "합성 부분")
                    .put("rejectText", "합성 모순");
        for (JsonNode row : resources.get("rubricClues"))
            ((ObjectNode) row).put("linkText", "합성 연결");
        for (JsonNode row : resources.get("gradeSamples"))
            ((ObjectNode) row).put("reason", "합성 기대 근거");
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
        String[][] mappings = {
            {
                "persons",
                "story_person",
                "code,name,public_text,secret_text",
                "code,name,publicText,secretText"
            },
            {"roles", "story_role", "code,name,brief", "code,name,brief"},
            {"pairs", "story_pair", "role_a,role_b", "roleA,roleB"},
            {
                "clues",
                "story_clue",
                "code,title,body,person_code,scope,source_text",
                "code,title,body,personCode,scope,sourceText"
            },
            {"clueRoles", "clue_role", "clue_code,role_code", "clueCode,roleCode"},
            {"hints", "story_hint", "code,level,body", "code,level,body"},
            {
                "events",
                "story_event",
                "code,start_min,end_min,actual_text,apparent_text",
                "code,startMin,endMin,actualText,apparentText"
            },
            {"facts", "story_fact", "code,statement,truth,basis", "code,statement,truth,basis"},
            {
                "rubrics",
                "story_rubric",
                "code,category,max_score,required_yn,pass_score,accepted_text,partial_text,reject_text,rule_data",
                "code,category,maxScore,requiredYn,passScore,acceptedText,partialText,rejectText,ruleData"
            },
            {
                "rubricClues",
                "rubric_clue",
                "rubric_code,clue_code,link_text",
                "rubricCode,clueCode,linkText"
            }
        };
        for (String[] mapping : mappings)
            save(version, resources.get(mapping[0]), mapping[1], mapping[2], mapping[3]);
        for (JsonNode row : resources.get("gradeSamples"))
            db.update(
                    "INSERT INTO"
                        + " grade_sample(version_id,code,input_data,expect_data,expected_score,expected_success,reason,checked_by)"
                        + " VALUES (?,?,?::jsonb,?::jsonb,?,?,?,?)",
                    version,
                    value(row.get("code")),
                    row.get("inputData").toString(),
                    row.get("expectData").toString(),
                    value(row.get("expectedScore")),
                    value(row.get("expectedSuccess")),
                    value(row.get("reason")),
                    checker.principal().accountId());
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
        String snapshot =
                reviews.requestReview(
                                owner.sid(),
                                owner.principal(),
                                created.storyCode(),
                                1,
                                "1",
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .snapshotId();
        Fixture fixture =
                new Fixture(owner, checker, created.storyCode(), story, version, snapshot);
        grant(fixture, owner, "REVIEW");
        return fixture;
    }

    /** 고정 fixture 매핑만 저장하며 식별자에는 사용자 입력을 사용하지 않는다. */
    private void save(long version, JsonNode rows, String table, String columns, String fields) {
        String[] names = fields.split(",");
        List<String> placeholders = new ArrayList<>();
        for (String name : names) placeholders.add("ruleData".equals(name) ? "?::jsonb" : "?");
        for (JsonNode row : rows) {
            List<Object> values = new ArrayList<>();
            values.add(version);
            for (String name : names)
                values.add(
                        "ruleData".equals(name) ? row.get(name).toString() : value(row.get(name)));
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

    /** 실제 현재 자격·Spring Session·업무 세션을 합성 계정에 결속한다. */
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
                now,
                now,
                now,
                now,
                now);
        return new Account(prepared.id(), principal);
    }

    private void grant(Fixture f, Account actor, String permission) {
        if ("REVIEW".equals(permission))
            db.update(
                    "UPDATE admin_account SET can_review=true WHERE id=?",
                    actor.principal().accountId());
        if ("PUBLISH".equals(permission))
            db.update(
                    "UPDATE admin_account SET can_publish=true WHERE id=?",
                    actor.principal().accountId());
        db.update(
                "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                        + " (?,?,?,?)",
                f.story(),
                actor.principal().accountId(),
                permission,
                f.owner().principal().accountId());
    }

    private StoryReviewService.RecordResult record(
            Fixture f, Account actor, ObjectNode body, UUID requestId) {
        return reviews.createReviewRecord(
                actor.sid(),
                actor.principal(),
                f.code(),
                1,
                f.snapshot(),
                body.get("expectedRev").textValue(),
                UUID.fromString(body.get("requestKey").textValue()),
                body.get("kind").textValue(),
                body.get("result").textValue(),
                nullable(body.get("modelId")),
                nullable(body.get("effort")),
                body.get("evidence").textValue(),
                body.get("evidenceData"),
                requestId);
    }

    private StoryReviewService.RecordPage page(
            Fixture f, Account actor, Integer size, String cursor) {
        return reviews.getReviewRecordList(
                actor.sid(),
                actor.principal(),
                f.code(),
                1,
                f.snapshot(),
                size,
                cursor,
                UUID.randomUUID());
    }

    private StoryReviewService.ReviewResult withdraw(Fixture f, Account actor) {
        return reviews.returnToDraft(
                actor.sid(),
                actor.principal(),
                f.code(),
                1,
                "2",
                f.snapshot(),
                "WITHDRAW",
                "AUTHOR_REVISION",
                "return-check-01",
                UUID.randomUUID());
    }

    private StoryReviewService.ReviewResult changes(
            Fixture f, Account actor, String rev, String snapshot) {
        return reviews.returnToDraft(
                actor.sid(),
                actor.principal(),
                f.code(),
                1,
                rev,
                snapshot,
                "CHANGES_REQUIRED",
                "CONTENT_DEFECT",
                "return-check-01",
                UUID.randomUUID());
    }

    private static ObjectNode body(String kind, String result) {
        ObjectNode body =
                parseObject(
                        "{\"expectedRev\":\"2\",\"requestKey\":\""
                                + UUID.randomUUID()
                                + "\",\"kind\":\""
                                + kind
                                + "\",\"result\":\""
                                + result
                                + "\",\"modelId\":null,\"effort\":null,\"evidence\":\"확인자가 보관한 합성"
                                + " 검토 의견\",\"evidenceData\":{\"formatNo\":1,\"evidenceRef\":\"review/evidence-01\",\"checkedAt\":\"2020-01-01T00:00:00Z\",\"criticalOpenCount\":0,\"notes\":\"확인자"
                                + " 합성 메모\",\"runRef\":null,\"separateContext\":null}}");
        if ("MODEL".equals(kind)) {
            body.put("modelId", "gpt-6-astra").put("effort", "medium");
            ((ObjectNode) body.get("evidenceData"))
                    .put("runRef", "run-review-01")
                    .put("separateContext", true);
        }
        return body;
    }

    private static JsonNode resolution(String id) {
        return parse(
                "{\"recordId\":\""
                        + id
                        + "\",\"reasonCode\":\"ISSUE_VERIFIED\",\"verificationRef\":\"issue-checked-01\"}");
    }

    /** 감사 외 모든 실제 버전·원고·체커·사본을 값으로 비교한다. */
    private ObjectNode preserved(Fixture f) {
        ObjectNode state = parseObject("{}");
        state.set(
                "version",
                parse(
                        db.queryForObject(
                                "SELECT to_jsonb(v)::text FROM story_version v WHERE id=?",
                                String.class,
                                f.version())));
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
                        "review_snapshot"))
            state.set(
                    table,
                    parse(
                            db.queryForObject(
                                    "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY"
                                            + " to_jsonb(t)::text COLLATE \"C\"),'[]'::jsonb)::text"
                                            + " FROM "
                                            + table
                                            + " t WHERE version_id=?",
                                    String.class,
                                    f.version())));
        return state;
    }

    private JsonNode all(Fixture f) {
        ObjectNode state = preserved(f);
        state.set(
                "records",
                parse(
                        db.queryForObject(
                                "SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY"
                                        + " r.id),'[]'::jsonb)::text FROM review_record r JOIN"
                                        + " review_snapshot s ON s.id=r.snapshot_id WHERE"
                                        + " s.version_id=?",
                                String.class,
                                f.version())));
        state.set(
                "audits",
                parse(
                        db.queryForObject(
                                "SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY"
                                        + " id),'[]'::jsonb)::text FROM story_audit a WHERE"
                                        + " version_id=?",
                                String.class,
                                f.version())));
        return state;
    }

    private JsonNode storedPayload(Fixture f) {
        return parse(
                db.queryForObject(
                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                        String.class,
                        Long.parseLong(f.snapshot())));
    }

    private int auditCount(Fixture f, String event) {
        return db.queryForObject(
                "SELECT count(*) FROM story_audit WHERE version_id=? AND action=?",
                Integer.class,
                f.version(),
                event);
    }

    private MockHttpServletRequestBuilder mutation(Fixture f, String suffix, String body)
            throws Exception {
        var response =
                mvc.perform(get("/admin/api/auth/csrf").secure(true))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return https(post(root(f) + suffix))
                .cookie(cookie(f.owner()), response.getCookie("__Host-admin-csrf"))
                .header(
                        "X-CSRF-TOKEN",
                        parse(response.getContentAsString()).get("token").textValue())
                .header("Origin", "https://localhost")
                .contentType("application/json")
                .content(body);
    }

    private static MockHttpServletRequestBuilder https(MockHttpServletRequestBuilder builder) {
        return builder.secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerPort(443);
                            return request;
                        });
    }

    private Cookie cookie(Account actor) {
        var response = new MockHttpServletResponse();
        sessions.issueCookie(actor.sid(), new MockHttpServletRequest(), response);
        return response.getCookie("__Host-admin-session");
    }

    private static String root(Fixture f) {
        return "/admin/api/stories/" + f.code() + "/versions/1";
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode parse(String text) {
        return SnapshotJson.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode parseObject(String text) {
        return (ObjectNode) parse(text);
    }

    private static Object value(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.intValue();
        throw new AssertionError("합성 저장 자료형");
    }

    private static String nullable(JsonNode node) {
        return node.isNull() ? null : node.textValue();
    }

    private static void failure(Callable<?> action, int status, String code) {
        assertThatThrownBy(action::call)
                .isInstanceOfSatisfying(
                        AuthException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(status);
                            assertThat(error.code()).isEqualTo(code);
                            assertThat(error.getCause()).isNull();
                        });
    }

    private record Account(String sid, AdminPrincipal principal) {}

    private record Fixture(
            Account owner,
            Account checker,
            String code,
            long story,
            long version,
            String snapshot) {}
}
