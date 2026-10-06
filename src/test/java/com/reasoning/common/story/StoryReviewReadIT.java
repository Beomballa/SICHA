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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 실제 저장 자격·세션·부모 잠금·PostgreSQL·서블릿의 SR-03/04/08 합성 회귀 정의다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StoryReviewReadIT extends DatabaseContextTest {
    private static final String SECRET = "H3_READ_SECRET_CANARY";
    private static final String LIVE = "H3_LIVE_EDIT_CANARY";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    /** 폐기형 PostgreSQL과 합성 키만 등록하며 일반 DB를 사용하지 않는다. */
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
    @Autowired CryptoService crypto;
    @Autowired AdminSessionAdapter sessions;
    @Autowired StoryService stories;
    @Autowired StoryReviewService reviews;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;

    @Test
    void realSr02ServletCreatesSnapshotThenExactReadRoutesNeedNoCsrf() throws Exception {
        Fixture f = fixture();
        var tokenResponse =
                mvc.perform(get("/admin/api/auth/csrf").secure(true))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie csrf = tokenResponse.getResponse().getCookie("__Host-admin-csrf");
        String token =
                parse(tokenResponse.getResponse().getContentAsString()).get("token").textValue();
        var created =
                mvc.perform(
                                https(post(root(f) + "/review-requests"))
                                        .cookie(cookie(f.owner()), csrf)
                                        .header("X-CSRF-TOKEN", token)
                                        .header("Origin", "https://localhost")
                                        .contentType("application/json")
                                        .content(
                                                "{\"expectedRev\":\"1\",\"requestKey\":\""
                                                        + UUID.randomUUID()
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String id = parse(created.getResponse().getContentAsString()).get("snapshotId").textValue();
        var before = business(f);
        JsonNode list = read(f, "/review-snapshots");
        assertThat(names(list)).containsExactlyInAnyOrder("items", "hasNext", "nextAfterId");
        JsonNode summary = list.get("items").get(0);
        assertSummary(summary, id, f.owner().principal().accountKey(), true);
        assertThat(list.toString())
                .doesNotContain(SECRET, "payload", "accountId", "session", "authRev");
        JsonNode detail = read(f, "/review-snapshots/" + id);
        assertThat(names(detail)).containsExactlyInAnyOrder("snapshot", "payload");
        assertThat(detail.get("payload")).isEqualTo(stored(id));
        assertThat(detail.get("snapshot")).isEqualTo(summary);
        assertThat(detail.get("payload").get("resources").size()).isEqualTo(11);
        assertThat(business(f)).isEqualTo(before);
        assertThat(audits(f)).isEqualTo(2);
    }

    @Test
    void moreThanOneHundredHistoricalRowsUseExclusiveDescendingCursorNotNewestCurrent() {
        Fixture f = fixture();
        String current = snapshot(f);
        Account historical = account(false);
        for (int i = 0; i < 105; i++) {
            db.update(
                    "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,1,?::jsonb,?,?)",
                    f.version(),
                    stored(current).toString(),
                    UUID.randomUUID(),
                    historical.principal().accountId());
        }
        db.update(
                "UPDATE admin_account SET active_yn=false WHERE id=?",
                historical.principal().accountId());
        db.update(
                "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                historical.principal().accountId());
        var before = business(f);
        var page =
                reviews.getSnapshotList(
                        f.owner().sid(),
                        f.owner().principal(),
                        f.code(),
                        1,
                        100,
                        null,
                        UUID.randomUUID());
        assertThat(page.items()).hasSize(100);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextAfterId()).isEqualTo(page.items().get(99).snapshotId());
        assertThat(page.items())
                .allSatisfy(
                        row -> {
                            assertThat(row.createdBy())
                                    .isEqualTo(historical.principal().accountKey());
                            assertThat(row.current()).isFalse();
                        });
        assertThat(page.items().stream().map(row -> Long.parseLong(row.snapshotId())).toList())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // 다른 최신 행을 추가해도 배타적인 다음 페이지에는 들어가지 않는다.
        db.update(
                "INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                        + " VALUES (?,1,?::jsonb,?,?)",
                f.version(),
                stored(current).toString(),
                UUID.randomUUID(),
                historical.principal().accountId());
        var next =
                reviews.getSnapshotList(
                        f.owner().sid(),
                        f.owner().principal(),
                        f.code(),
                        1,
                        100,
                        page.nextAfterId(),
                        UUID.randomUUID());
        assertThat(next.items()).hasSize(6);
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextAfterId()).isNull();
        assertThat(next.items().get(5).snapshotId()).isEqualTo(current);
        assertThat(next.items().get(5).current()).isTrue();
        assertThat(next.items().get(5).createdBy()).isEqualTo(f.owner().principal().accountKey());
        Set<String> ids = new HashSet<>();
        page.items().forEach(row -> assertThat(ids.add(row.snapshotId())).isTrue());
        next.items().forEach(row -> assertThat(ids.add(row.snapshotId())).isTrue());
        assertThat(ids).hasSize(106);
        assertThat(business(f).get("version")).isEqualTo(before.get("version"));
        assertThat(business(f).get("samples")).isEqualTo(before.get("samples"));
        assertThat(
                        reviews.getSnapshotList(
                                        f.owner().sid(),
                                        f.owner().principal(),
                                        f.code(),
                                        1,
                                        null,
                                        null,
                                        UUID.randomUUID())
                                .items())
                .hasSize(20);
    }

    @Test
    void savedHistoryNeverInheritsLiveContentOrHistoricalCreatorPrivileges() throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        JsonNode original = stored(id);
        Account reader = account(false);
        grant(f, reader, "EDIT");
        db.update(
                "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                f.checker().principal().accountId());
        db.update(
                "UPDATE admin_account SET active_yn=false WHERE id=?",
                f.checker().principal().accountId());
        returnDraft(f);
        stories.updateStorySection(
                f.owner().sid(),
                f.owner().principal(),
                f.code(),
                1,
                "basic",
                "3",
                parse("{\"title\":\"" + LIVE + "\"}"),
                UUID.randomUUID());
        db.update(
                "UPDATE story_role SET name=? WHERE version_id=? AND code='R1'", LIVE, f.version());
        db.update("UPDATE story_person SET public_text=? WHERE version_id=?", LIVE, f.version());
        var before = business(f);
        JsonNode detail = read(f, reader, "/review-snapshots/" + id);
        assertThat(detail.get("payload")).isEqualTo(original);
        assertThat(detail.get("snapshot").get("current").booleanValue()).isFalse();
        JsonNode preview =
                read(
                        f,
                        reader,
                        "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=ROLE&roleCode=R1");
        assertPreview(preview, "SNAPSHOT", "1", id, "ROLE", "R1");
        assertThat(preview.toString()).doesNotContain(LIVE, SECRET);
        assertThat(preview.get("data").get("role").get("name"))
                .isEqualTo(original.get("resources").get("roles").get(0).get("name"));
        JsonNode current = read(f, "/preview?source=DRAFT&expectedRev=4&mode=ROLE&roleCode=R1");
        assertThat(current.toString()).contains(LIVE).doesNotContain(SECRET);
        assertThat(business(f)).isEqualTo(before);
    }

    @Test
    void roleAllowlistUsesActiveUnionDedupCodeOrderAndAllPublicPersons() throws Exception {
        Fixture f = fixture();
        db.update(
                "INSERT INTO story_clue(version_id,code,scope,title,body) VALUES (?,"
                        + " 'C0','COMMON',?,?)",
                f.version(),
                "공통 단서",
                "공통 공개 본문");
        db.update(
                "INSERT INTO story_clue(version_id,code,scope,title,body) VALUES (?,"
                        + " 'C3','ROLE',?,?)",
                f.version(),
                "선택 역할 단서",
                "선택 역할 공개 본문");
        db.update(
                "INSERT INTO clue_role(version_id,clue_code,role_code) VALUES (?,'C3','R1')",
                f.version());
        db.update(
                "INSERT INTO story_clue(version_id,code,scope,title,body) VALUES (?,"
                        + " 'C4','ROLE',?,?)",
                f.version(),
                SECRET + "_OTHER_CLUE",
                SECRET + "_OTHER_BODY");
        db.update(
                "INSERT INTO clue_role(version_id,clue_code,role_code) VALUES (?,'C4','R2')",
                f.version());
        db.update(
                "INSERT INTO story_clue(version_id,code,scope,title,body,active_yn) VALUES (?,"
                        + " 'C5','COMMON',?,?,false)",
                f.version(),
                SECRET + "_INACTIVE",
                SECRET);
        db.update(
                "INSERT INTO story_clue(version_id,code,scope,title,body) VALUES (?,"
                        + " 'C6','ROLE',?,?)",
                f.version(),
                SECRET + "_INACTIVE_LINK",
                SECRET);
        db.update(
                "INSERT INTO clue_role(version_id,clue_code,role_code,active_yn) VALUES"
                        + " (?,'C6','R1',false)",
                f.version());
        db.update(
                "INSERT INTO clue_role(version_id,clue_code,role_code) VALUES (?,'C3','R2')",
                f.version());
        db.update(
                "INSERT INTO story_person(version_id,code,name,public_text,secret_text,active_yn)"
                        + " VALUES (?,'P0',?,?,?,false)",
                f.version(),
                SECRET,
                SECRET,
                SECRET);
        db.update(
                "UPDATE story_role SET name=?,brief=? WHERE version_id=? AND code='R2'",
                SECRET,
                SECRET,
                f.version());
        var before = business(f);
        JsonNode result = read(f, "/preview?source=DRAFT&expectedRev=1&mode=ROLE&roleCode=R1");
        assertPreview(result, "DRAFT", "1", null, "ROLE", "R1");
        assertRole(result.get("data"));
        assertThat(result.toString())
                .doesNotContain(
                        SECRET,
                        "secretText",
                        "sourceText",
                        "timelineOrigin",
                        "answer",
                        "facts",
                        "rubrics",
                        "gradeSamples",
                        "hints",
                        "pairs",
                        "reveal",
                        "checkedBy",
                        "payload");
        List<String> clues = codes(result.get("data").get("clues"));
        assertThat(clues).contains("C0", "C1", "C3").doesNotContain("C4", "C5", "C6");
        assertThat(clues).doesNotHaveDuplicates().isSorted();
        assertThat(codes(result.get("data").get("persons")))
                .containsExactlyElementsOf(codes(f.source().get("resources").get("persons")));
        assertThat(business(f)).isEqualTo(before);
        JsonNode detail = auditDetail(f);
        assertThat(names(detail))
                .containsExactlyInAnyOrder(
                        "revisionScope", "requestId", "sourceRev", "mode", "roleCode");
        assertThat(detail.get("sourceRev").textValue()).isEqualTo("1");
        assertThat(detail.toString()).doesNotContain(SECRET, "공개 본문");
        assertThat(detail.toString().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4096);
    }

    @Test
    void draftPreviewReadsBeyondHttpPageLimitAndKeepsPartialNullWithoutPolicyDefaults()
            throws Exception {
        Fixture f = fixture();
        for (int i = 0; i < 105; i++) {
            db.update(
                    "INSERT INTO story_person(version_id,code,name,public_text) VALUES"
                            + " (?,?,?,NULL)",
                    f.version(),
                    String.format("Z%03d", i),
                    "합성 인물 " + i);
        }
        db.update(
                "UPDATE story_version SET"
                    + " intro=NULL,setting=NULL,difficulty=NULL,est_min=NULL,est_max=NULL,limit_sec=NULL,reveal_text=NULL"
                    + " WHERE id=?",
                f.version());
        db.update("UPDATE story_role SET brief=NULL WHERE version_id=? AND code='R1'", f.version());
        db.update("UPDATE story_clue SET body=NULL WHERE version_id=? AND code='C1'", f.version());
        JsonNode result = read(f, "/preview?source=DRAFT&expectedRev=1&mode=ROLE&roleCode=R1");
        assertThat(result.get("data").get("persons").size())
                .isEqualTo(105 + f.source().get("resources").get("persons").size());
        for (String field :
                List.of("intro", "setting", "difficulty", "estMin", "estMax", "limitSec"))
            assertThat(result.get("data").get("basic").get(field).isNull()).isTrue();
        assertThat(result.get("data").get("role").get("name").textValue()).isEqualTo("R1");
        assertThat(result.get("data").get("role").get("brief").isNull()).isTrue();
        assertThat(result.get("data").get("clues").get(0).get("body").isNull()).isTrue();
        assertThat(result.has("warnings")).isFalse();
        JsonNode reveal = read(f, "/preview?source=DRAFT&expectedRev=1&mode=REVEAL");
        assertPreview(reveal, "DRAFT", "1", null, "REVEAL", null);
        assertThat(names(reveal.get("data"))).containsExactlyInAnyOrder("title", "revealText");
        assertThat(reveal.get("data").get("revealText").isNull()).isTrue();
    }

    @Test
    void snapshotRoleAndRevealUseOnlySavedInputsEvenAfterLiveRoleDeletion() throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        returnDraft(f);
        db.update(
                "UPDATE story_role SET active_yn=false WHERE version_id=? AND code='R1'",
                f.version());
        db.update("UPDATE story_version SET reveal_text=? WHERE id=?", LIVE, f.version());
        JsonNode role =
                read(f, "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=ROLE&roleCode=R1");
        assertRole(role.get("data"));
        assertThat(role.toString()).doesNotContain(SECRET, LIVE);
        JsonNode reveal = read(f, "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL");
        assertPreview(reveal, "SNAPSHOT", "1", id, "REVEAL", null);
        assertThat(names(reveal.get("data"))).containsExactlyInAnyOrder("title", "revealText");
        assertThat(reveal.get("data").get("revealText"))
                .isEqualTo(stored(id).get("sections").get("reveal").get("revealText"));
        assertThat(reveal.toString()).doesNotContain(LIVE);
        mvc.perform(readRequest(f, "/preview?source=DRAFT&expectedRev=3&mode=ROLE&roleCode=R1"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        readRequest(
                                f,
                                "/preview?source=SNAPSHOT&snapshotId="
                                        + id
                                        + "&mode=ROLE&roleCode=MISSING"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVIEW", "PUBLISH", "EDIT"})
    void ordinaryProducerAccessNotOriginalCreatorOrMandatoryEdit(String permission)
            throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        Account reader = account(false);
        grant(f, reader, permission);
        if (!"EDIT".equals(permission))
            db.update(
                    "UPDATE admin_account SET "
                            + ("REVIEW".equals(permission) ? "can_review" : "can_publish")
                            + "=true WHERE id=?",
                    reader.principal().accountId());
        read(f, reader, "/review-snapshots");
        read(f, reader, "/review-snapshots/" + id);
        read(f, reader, "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=ROLE&roleCode=R1");
        returnDraft(f);
        read(f, reader, "/preview?source=DRAFT&expectedRev=3&mode=REVEAL");
        int before = audits(f);
        if (!"EDIT".equals(permission)) {
            db.update(
                    "UPDATE admin_account SET "
                            + ("REVIEW".equals(permission) ? "can_review" : "can_publish")
                            + "=false WHERE id=?",
                    reader.principal().accountId());
            mvc.perform(readRequest(f, reader, "/review-snapshots"))
                    .andExpect(status().isNotFound());
        }
        db.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                f.story(),
                reader.principal().accountId());
        for (String route :
                List.of(
                        "/review-snapshots",
                        "/review-snapshots/" + id,
                        "/preview?source=DRAFT&expectedRev=3&mode=REVEAL"))
            mvc.perform(readRequest(f, reader, route)).andExpect(status().isNotFound());
        assertThat(audits(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"story", "version"})
    void inactiveParentsAllowExistingOwnerManagementReadOnly(String parent) throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        Account reader = account(false);
        grant(f, reader, "EDIT");
        db.update(
                "UPDATE "
                        + (parent.equals("story") ? "story" : "story_version")
                        + " SET active_yn=false WHERE id=?",
                parent.equals("story") ? f.story() : f.version());
        read(f, "/review-snapshots/" + id);
        read(f, "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL");
        mvc.perform(readRequest(f, reader, "/review-snapshots/" + id))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mfa", "account", "credential", "session"})
    void readsRecheckActualStoredAuthenticationInsteadOfStaleActor(String invalid)
            throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        switch (invalid) {
            case "mfa" ->
                    db.update(
                            "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                            f.owner().principal().accountId());
            case "account" ->
                    db.update(
                            "UPDATE admin_account SET active_yn=false WHERE id=?",
                            f.owner().principal().accountId());
            case "credential" ->
                    db.update(
                            "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?",
                            f.owner().principal().accountId());
            case "session" ->
                    db.update(
                            "UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp()"
                                    + " WHERE session_key=?",
                            f.owner().principal().sessionKey());
        }
        var before = business(f);
        for (String route :
                List.of(
                        "/review-snapshots",
                        "/review-snapshots/" + id,
                        "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL"))
            mvc.perform(readRequest(f, route)).andExpect(status().isUnauthorized());
        assertFailure(
                () ->
                        reviews.getSnapshotDetail(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                1,
                                id,
                                UUID.randomUUID()),
                401,
                "AUTH_REQUIRED");
        assertThat(audits(f)).isZero();
        assertThat(business(f)).isEqualTo(before);
    }

    @Test
    void foreignSnapshotIdsAndUnauthorizedParentsRemainUndisclosed() throws Exception {
        Fixture f = fixture();
        Fixture foreign = fixture();
        String id = snapshot(foreign);
        for (String route :
                List.of(
                        "/review-snapshots/" + id,
                        "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL")) {
            var response =
                    mvc.perform(readRequest(f, route)).andExpect(status().isNotFound()).andReturn();
            assertThat(response.getResponse().getContentAsString())
                    .doesNotContain(SECRET, foreign.code(), "payload");
        }
        Account outsider = account(false);
        mvc.perform(readRequest(f, outsider, "/review-snapshots")).andExpect(status().isNotFound());
        assertThat(audits(f)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "01", "-1", "+1", "1.0", " 1", "9223372036854775808"})
    void invalidSnapshotAndAfterIdsFailBeforeReadAudit(String id) throws Exception {
        Fixture f = fixture();
        var before = business(f);
        mvc.perform(
                        https(get(root(f) + "/review-snapshots"))
                                .cookie(cookie(f.owner()))
                                .param("afterId", id))
                .andExpect(status().isBadRequest());
        assertFailure(
                () ->
                        reviews.getSnapshotDetail(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                1,
                                id,
                                UUID.randomUUID()),
                400,
                "INVALID_REQUEST");
        mvc.perform(
                        https(get(root(f) + "/preview"))
                                .cookie(cookie(f.owner()))
                                .param("source", "SNAPSHOT")
                                .param("snapshotId", id)
                                .param("mode", "REVEAL"))
                .andExpect(status().isBadRequest());
        assertThat(audits(f)).isZero();
        assertThat(business(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "01", "1.0", "", "99999999999999999"})
    void invalidSizeFailsBeforeAnyContentAudit(String size) throws Exception {
        Fixture f = fixture();
        mvc.perform(
                        https(get(root(f) + "/review-snapshots"))
                                .cookie(cookie(f.owner()))
                                .param("size", size))
                .andExpect(status().isBadRequest());
        assertThat(audits(f)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "source=DRAFT&mode=REVEAL",
                "source=SNAPSHOT&mode=REVEAL",
                "source=DRAFT&expectedRev=1&snapshotId=1&mode=REVEAL",
                "source=SNAPSHOT&snapshotId=1&expectedRev=1&mode=REVEAL",
                "source=DRAFT&expectedRev=1&mode=ROLE",
                "source=DRAFT&expectedRev=1&mode=REVEAL&roleCode=R1",
                "source=DRAFT&expectedRev=1&mode=REVEAL&roleCode=",
                "source=DRAFT&expectedRev=1&mode=ROLE&roleCode=R1&reference=1",
                "source=DRAFT&expectedRev=1&mode=REVEAL&hidden=secret",
                "source=DRAFT&source=DRAFT&expectedRev=1&mode=REVEAL",
                "source=DRAFT&expectedRev=1&expectedRev=1&mode=REVEAL",
                "source=DRAFT&expectedRev=1&mode=REVEAL&mode=REVEAL",
                "source=DRAFT&expectedRev=1&mode=ROLE&roleCode=R1&roleCode=R1",
                "source=SNAPSHOT&snapshotId=1&snapshotId=1&mode=REVEAL",
                "source=LIVE&expectedRev=1&mode=REVEAL",
                "source=DRAFT&expectedRev=1&mode=ALL",
                "source=DRAFT&expectedRev=01&mode=REVEAL",
                "source=DRAFT&expectedRev=-1&mode=REVEAL"
            })
    void previewQueryHasExactExclusiveReferencesAndNoIgnoredKeys(String query) throws Exception {
        Fixture f = fixture();
        mvc.perform(readRequest(f, "/preview?" + query)).andExpect(status().isBadRequest());
        assertThat(audits(f)).isZero();
    }

    @Test
    void listAndDetailAlsoRejectUnknownOrDuplicateQueryKeys() throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        for (String suffix :
                List.of(
                        "/review-snapshots?size=1&size=1",
                        "/review-snapshots?afterId=1&afterId=1",
                        "/review-snapshots?payload=true",
                        "/review-snapshots/" + id + "?size=1"))
            mvc.perform(readRequest(f, suffix)).andExpect(status().isBadRequest());
        assertThat(audits(f)).isZero();
    }

    @Test
    void unauthenticatedGetDoesNotReachAnySensitiveAudit() throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        for (String suffix :
                List.of(
                        "/review-snapshots",
                        "/review-snapshots/" + id,
                        "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL")) {
            var response =
                    mvc.perform(https(get(root(f) + suffix)))
                            .andExpect(status().isUnauthorized())
                            .andReturn();
            assertThat(response.getResponse().getContentAsString())
                    .doesNotContain(SECRET, "payload");
        }
        assertThat(audits(f)).isZero();
    }

    @Test
    void unresolvedHistoricalCreatorCannotBecomeNullOrFabricatedUuid() {
        Fixture f = fixture();
        String id = snapshot(f);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            db.execute(
                                    "ALTER TABLE review_snapshot DROP CONSTRAINT"
                                            + " fk_review_snapshot_creator");
                            db.update(
                                    "UPDATE review_snapshot SET created_by=? WHERE id=?",
                                    Long.MAX_VALUE,
                                    Long.parseLong(id));
                            assertFailure(
                                    () ->
                                            reviews.getSnapshotList(
                                                    f.owner().sid(),
                                                    f.owner().principal(),
                                                    f.code(),
                                                    1,
                                                    20,
                                                    null,
                                                    UUID.randomUUID()),
                                    503,
                                    "STORY_UNAVAILABLE");
                            assertFailure(
                                    () ->
                                            reviews.getSnapshotDetail(
                                                    f.owner().sid(),
                                                    f.owner().principal(),
                                                    f.code(),
                                                    1,
                                                    id,
                                                    UUID.randomUUID()),
                                    503,
                                    "STORY_UNAVAILABLE");
                            assertThat(audits(f)).isZero();
                            tx.setRollbackOnly();
                        });
        assertThat(
                        reviews.getSnapshotDetail(
                                        f.owner().sid(),
                                        f.owner().principal(),
                                        f.code(),
                                        1,
                                        id,
                                        UUID.randomUUID())
                                .snapshot()
                                .createdBy())
                .isEqualTo(f.owner().principal().accountKey());
    }

    @Test
    void detailKeepsActualFormatAndPayloadWhilePreviewRejectsUnknownFormat() throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        JsonNode payload = stored(id);
        db.update("UPDATE review_snapshot SET format_no=2 WHERE id=?", Long.parseLong(id));
        JsonNode detail = read(f, "/review-snapshots/" + id);
        assertThat(detail.get("snapshot").get("formatNo").intValue()).isEqualTo(2);
        assertThat(detail.get("payload")).isEqualTo(payload);
        mvc.perform(readRequest(f, "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL"))
                .andExpect(status().isServiceUnavailable());
        assertThat(audits(f)).isEqualTo(1);
    }

    @Test
    void historicalPreviewAuditUsesOriginalSourceButUnchangedCurrentRevision() throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        returnDraft(f);
        JsonNode preview =
                read(f, "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=ROLE&roleCode=R1");
        JsonNode detail = auditDetail(f);
        assertThat(detail.get("sourceRev").textValue()).isEqualTo("1");
        assertThat(detail.get("snapshotId").textValue()).isEqualTo(id);
        assertThat(detail.get("requestId")).isEqualTo(preview.get("requestId"));
        assertThat(names(detail))
                .containsExactlyInAnyOrder(
                        "revisionScope",
                        "requestId",
                        "sourceRev",
                        "snapshotId",
                        "mode",
                        "roleCode");
        var row =
                db.queryForMap(
                        "SELECT before_rev,after_rev FROM story_audit WHERE version_id=?"
                                + " AND action='CONTENT_READ' ORDER BY id DESC LIMIT 1",
                        f.version());
        assertThat(row.get("before_rev")).isEqualTo(3L);
        assertThat(row.get("after_rev")).isEqualTo(3L);
        assertThat(detail.toString()).doesNotContain(SECRET, "payload");
    }

    @Test
    void draftLatestRevisionAndCurrentDraftAreCheckedWithoutEditAuthority() throws Exception {
        Fixture f = fixture();
        Account reader = account(false);
        grant(f, reader, "REVIEW");
        db.update(
                "UPDATE admin_account SET can_review=true WHERE id=?",
                reader.principal().accountId());
        mvc.perform(readRequest(f, reader, "/preview?source=DRAFT&expectedRev=0&mode=REVEAL"))
                .andExpect(status().isConflict());
        String id = snapshot(f);
        mvc.perform(readRequest(f, reader, "/preview?source=DRAFT&expectedRev=2&mode=REVEAL"))
                .andExpect(status().isConflict());
        read(f, reader, "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL");
        returnDraft(f);
        read(f, reader, "/preview?source=DRAFT&expectedRev=3&mode=REVEAL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"format", "root", "revision", "owner"})
    void snapshotProjectionFailsClosedOnUnsupportedOrTamperedStoredPayload(String tamper)
            throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        switch (tamper) {
            case "format" ->
                    db.update(
                            "UPDATE review_snapshot SET format_no=2 WHERE id=?",
                            Long.parseLong(id));
            case "root" ->
                    db.update(
                            "UPDATE review_snapshot SET payload=payload || '{\"hidden\":\""
                                    + SECRET
                                    + "\"}'::jsonb WHERE id=?",
                            Long.parseLong(id));
            case "revision" ->
                    db.update(
                            "UPDATE review_snapshot SET edit_rev=9 WHERE id=?", Long.parseLong(id));
            case "owner" ->
                    db.update(
                            "UPDATE review_snapshot SET"
                                + " payload=jsonb_set(payload,'{storyCode}','\"FOREIGN\"'::jsonb)"
                                + " WHERE id=?",
                            Long.parseLong(id));
        }
        var before = business(f);
        var response =
                mvc.perform(
                                readRequest(
                                        f,
                                        "/preview?source=SNAPSHOT&snapshotId="
                                                + id
                                                + "&mode=ROLE&roleCode=R1"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn();
        assertThat(response.getResponse().getContentAsString())
                .doesNotContain(SECRET, "payload", "SQL", "Frozen");
        assertThat(audits(f)).isZero();
        assertThat(business(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "detail", "draft", "snapshot"})
    void mandatoryAuditFailureReturnsNoDataAndRollsBackEveryBusinessAndSessionRow(String route)
            throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        if (route.equals("draft")) returnDraft(f);
        var before = business(f);
        Map<String, Object> session =
                db.queryForMap(
                        "SELECT * FROM admin_session WHERE session_key=?",
                        f.owner().principal().sessionKey());
        String suffix =
                switch (route) {
                    case "list" -> "/review-snapshots";
                    case "detail" -> "/review-snapshots/" + id;
                    case "draft" -> "/preview?source=DRAFT&expectedRev=3&mode=ROLE&roleCode=R1";
                    default -> "/preview?source=SNAPSHOT&snapshotId=" + id + "&mode=REVEAL";
                };
        String trigger = "read_audit_" + f.version();
        db.execute(
                "CREATE FUNCTION "
                        + trigger
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " IF NEW.version_id="
                        + f.version()
                        + " AND NEW.action='CONTENT_READ' THEN"
                        + " RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON story_audit FOR EACH ROW EXECUTE FUNCTION "
                        + trigger
                        + "()");
        try {
            var response =
                    mvc.perform(readRequest(f, suffix))
                            .andExpect(status().isServiceUnavailable())
                            .andReturn();
            assertThat(response.getResponse().getContentAsString())
                    .doesNotContain(SECRET, "payload", "data", "synthetic audit failure");
            assertThat(business(f)).isEqualTo(before);
            assertThat(audits(f)).isZero();
            assertThat(
                            db.queryForMap(
                                    "SELECT * FROM admin_session WHERE session_key=?",
                                    f.owner().principal().sessionKey()))
                    .usingRecursiveComparison()
                    .isEqualTo(session);
        } finally {
            db.execute("DROP TRIGGER " + trigger + " ON story_audit");
            db.execute("DROP FUNCTION " + trigger + "()");
        }
    }

    @Test
    void parentLockWaitsForLegalEditThenRejectsStaleDraftInsteadOfMixedResponse() throws Exception {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var edit =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        stories.updateStorySection(
                                                                editor.sid(),
                                                                editor.principal(),
                                                                f.code(),
                                                                1,
                                                                "basic",
                                                                "1",
                                                                parse("{\"intro\":\"명시적 법적 편집\"}"),
                                                                UUID.randomUUID());
                                                        locked.countDown();
                                                        await(release);
                                                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch started = new CountDownLatch(1);
            var read =
                    pool.submit(
                            () -> {
                                started.countDown();
                                return reviews.getPreview(
                                        f.owner().sid(),
                                        f.owner().principal(),
                                        f.code(),
                                        1,
                                        "DRAFT",
                                        "1",
                                        null,
                                        "ROLE",
                                        "R1",
                                        UUID.randomUUID());
                            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> read.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            edit.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> read.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AuthException.class)
                    .satisfies(
                            error ->
                                    assertThat(((AuthException) error.getCause()).code())
                                            .isEqualTo("EDIT_CONFLICT"));
        } finally {
            release.countDown();
        }
        assertThat(audits(f)).isZero();
    }

    @Test
    void revokeUnderSameStoryLockPreventsStaleSnapshotDataResponse() throws Exception {
        Fixture f = fixture();
        String id = snapshot(f);
        Account reader = account(false);
        grant(f, reader, "EDIT");
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
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch started = new CountDownLatch(1);
            var read =
                    pool.submit(
                            () -> {
                                started.countDown();
                                return reviews.getSnapshotDetail(
                                        reader.sid(),
                                        reader.principal(),
                                        f.code(),
                                        1,
                                        id,
                                        UUID.randomUUID());
                            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> read.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            revoke.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> read.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AuthException.class)
                    .satisfies(
                            error ->
                                    assertThat(((AuthException) error.getCause()).status())
                                            .isEqualTo(404));
        } finally {
            release.countDown();
        }
        assertThat(audits(f)).isZero();
    }

    /** SR-02 완성 fixture와 동일한 필수 원고 보완을 하되 모든 비공개 영역에 합성 표식을 저장한다. */
    private Fixture fixture() {
        ObjectNode source = FrozenSnapshotContractTest.complete();
        ((ObjectNode) source.get("sections").get("basic"))
                .put("estMin", 15)
                .put("estMax", 30)
                .put("timelineOrigin", SECRET + "_TIMELINE");
        ObjectNode answer = (ObjectNode) source.get("sections").get("answer");
        answer.put("methodAnswer", SECRET + "_METHOD")
                .put("timeAnswer", SECRET + "_TIME")
                .put("motiveAnswer", SECRET + "_MOTIVE");
        ((ObjectNode) source.get("sections").get("reveal")).put("revealText", SECRET + "_REVEAL");
        JsonNode resources = source.get("resources");
        for (JsonNode row : resources.get("persons"))
            ((ObjectNode) row)
                    .put("publicText", "공개 합성 인물 소개")
                    .put("secretText", SECRET + "_PERSON");
        for (JsonNode row : resources.get("clues"))
            ((ObjectNode) row).put("sourceText", SECRET + "_CLUE_SOURCE");
        ((ObjectNode) resources.get("roles").get(1))
                .put("name", SECRET + "_OTHER_ROLE")
                .put("brief", SECRET + "_OTHER_BRIEF");
        for (JsonNode row : resources.get("hints"))
            ((ObjectNode) row).put("body", SECRET + "_HINT");
        ((ArrayNode) resources.get("hints"))
                .add(parse("{\"code\":\"H3\",\"level\":3,\"body\":\"" + SECRET + "_H3\"}"));
        ((ArrayNode) resources.get("clueRoles"))
                .add(parse("{\"clueCode\":\"C2\",\"roleCode\":\"R3\"}"));
        for (JsonNode row : resources.get("events"))
            ((ObjectNode) row)
                    .put("startMin", 0)
                    .put("endMin", 1)
                    .put("actualText", SECRET + "_ACTUAL")
                    .put("apparentText", SECRET + "_APPARENT");
        for (JsonNode row : resources.get("facts"))
            ((ObjectNode) row).put("statement", SECRET + "_FACT").put("basis", SECRET + "_BASIS");
        for (JsonNode row : resources.get("rubrics")) {
            ((ObjectNode) row)
                    .put("acceptedText", SECRET + "_ACCEPTED")
                    .put("partialText", SECRET + "_PARTIAL")
                    .put("rejectText", SECRET + "_REJECTED");
            if (!"CULPRIT".equals(row.get("category").textValue())) {
                for (JsonNode claim : row.get("ruleData").get("claims"))
                    ((ObjectNode) claim).put("meaning", SECRET + "_RULE");
            }
        }
        for (JsonNode row : resources.get("rubricClues"))
            ((ObjectNode) row).put("linkText", SECRET + "_LINK");
        for (JsonNode row : resources.get("gradeSamples"))
            ((ObjectNode) row).put("reason", SECRET + "_SAMPLE");
        Account owner = account(true);
        Account checker = account(false);
        var created =
                stories.createStory(
                        owner.sid(),
                        owner.principal(),
                        UUID.randomUUID(),
                        "합성 읽기 사건",
                        UUID.randomUUID());
        long story =
                db.queryForObject(
                        "SELECT id FROM story WHERE code=?", Long.class, created.storyCode());
        long version =
                db.queryForObject(
                        "SELECT id FROM story_version WHERE story_id=?", Long.class, story);
        source.put("storyCode", created.storyCode()).put("sourceRev", "1");
        for (JsonNode row : resources.get("gradeSamples"))
            ((ObjectNode) row).put("checkedBy", checker.principal().accountKey().toString());
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

    /** 고정 매핑의 열한 실제 자원을 저장하며 제품 SQL 진입점을 추가하지 않는다. */
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

    /** 실제 계정·MFA READY 자격·저장 Spring Session과 업무 세션을 결속한다. */
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

    private String snapshot(Fixture f) {
        return reviews.requestReview(
                        f.owner().sid(),
                        f.owner().principal(),
                        f.code(),
                        1,
                        "1",
                        UUID.randomUUID(),
                        UUID.randomUUID())
                .snapshotId();
    }

    /** 반환 API 구현을 주장하지 않는 명시적 저장 이력 fixture다. */
    private void returnDraft(Fixture f) {
        db.update(
                "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL,edit_rev=3 WHERE"
                        + " id=?",
                f.version());
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

    private Map<String, Object> business(Fixture f) {
        return Map.of(
                "version",
                db.queryForMap("SELECT * FROM story_version WHERE id=?", f.version()),
                "samples",
                db.queryForList(
                        "SELECT * FROM grade_sample WHERE version_id=? ORDER BY code", f.version()),
                "snapshots",
                db.queryForList(
                        "SELECT * FROM review_snapshot WHERE version_id=? ORDER BY id",
                        f.version()),
                "records",
                db.queryForList(
                        "SELECT * FROM review_record WHERE snapshot_id IN (SELECT id FROM"
                                + " review_snapshot WHERE version_id=?) ORDER BY id",
                        f.version()));
    }

    private int audits(Fixture f) {
        return db.queryForObject(
                "SELECT count(*) FROM story_audit WHERE version_id=? AND action='CONTENT_READ'",
                Integer.class,
                f.version());
    }

    private JsonNode auditDetail(Fixture f) {
        return parse(
                db.queryForObject(
                        "SELECT detail::text FROM story_audit WHERE version_id=? AND"
                                + " action='CONTENT_READ' ORDER BY id DESC LIMIT 1",
                        String.class,
                        f.version()));
    }

    private JsonNode stored(String id) {
        return parse(
                db.queryForObject(
                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                        String.class,
                        Long.parseLong(id)));
    }

    private JsonNode read(Fixture f, String suffix) throws Exception {
        return read(f, f.owner(), suffix);
    }

    private JsonNode read(Fixture f, Account actor, String suffix) throws Exception {
        var result =
                mvc.perform(readRequest(f, actor, suffix)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        return parse(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder readRequest(Fixture f, String suffix) {
        return readRequest(f, f.owner(), suffix);
    }

    private MockHttpServletRequestBuilder readRequest(Fixture f, Account actor, String suffix) {
        return https(get(root(f) + suffix)).cookie(cookie(actor));
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

    private static List<String> codes(JsonNode rows) {
        List<String> codes = new ArrayList<>();
        rows.forEach(row -> codes.add(row.get("code").textValue()));
        return codes;
    }

    private static void assertSummary(JsonNode row, String id, UUID creator, boolean current) {
        assertThat(names(row))
                .containsExactlyInAnyOrder(
                        "snapshotId", "sourceRev", "formatNo", "createdAt", "createdBy", "current");
        assertThat(row.get("snapshotId").textValue()).isEqualTo(id);
        assertThat(row.get("sourceRev").textValue()).isEqualTo("1");
        assertThat(row.get("formatNo").intValue()).isEqualTo(1);
        assertThat(row.get("createdBy").textValue()).isEqualTo(creator.toString());
        assertThat(row.get("current").booleanValue()).isEqualTo(current);
        assertThat(Instant.parse(row.get("createdAt").textValue())).isNotNull();
    }

    private static void assertPreview(
            JsonNode row, String source, String revision, String id, String mode, String role) {
        assertThat(names(row))
                .containsExactlyInAnyOrder(
                        "source",
                        "sourceRev",
                        "snapshotId",
                        "mode",
                        "roleCode",
                        "previewOnly",
                        "data",
                        "requestId");
        assertThat(row.get("source").textValue()).isEqualTo(source);
        assertThat(row.get("sourceRev").textValue()).isEqualTo(revision);
        assertThat(row.get("snapshotId").isNull() ? null : row.get("snapshotId").textValue())
                .isEqualTo(id);
        assertThat(row.get("mode").textValue()).isEqualTo(mode);
        assertThat(row.get("roleCode").isNull() ? null : row.get("roleCode").textValue())
                .isEqualTo(role);
        assertThat(row.get("previewOnly").booleanValue()).isTrue();
        assertThat(UUID.fromString(row.get("requestId").textValue())).isNotNull();
    }

    private static void assertRole(JsonNode data) {
        assertThat(names(data)).containsExactlyInAnyOrder("basic", "role", "persons", "clues");
        assertThat(names(data.get("basic")))
                .containsExactlyInAnyOrder(
                        "title", "intro", "setting", "difficulty", "estMin", "estMax", "limitSec");
        assertThat(names(data.get("role"))).containsExactlyInAnyOrder("code", "name", "brief");
        data.get("persons")
                .forEach(
                        row ->
                                assertThat(names(row))
                                        .containsExactlyInAnyOrder("code", "name", "publicText"));
        data.get("clues")
                .forEach(
                        row ->
                                assertThat(names(row))
                                        .containsExactlyInAnyOrder(
                                                "code", "title", "body", "personCode"));
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
                            assertThat(error.getCause()).isNull();
                        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("합성 잠금 대기 상한");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("합성 잠금 대기 중단");
        }
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
