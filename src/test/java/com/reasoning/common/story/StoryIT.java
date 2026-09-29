package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.story.service.StoryService;
import com.reasoning.common.story.service.StoryPersonService;
import com.reasoning.common.story.service.StoryRoleService;
import com.reasoning.common.story.service.StoryClueService;
import com.reasoning.common.story.service.StoryHintService;
import com.reasoning.common.story.entity.QStory;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StoryIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 81));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 82));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 83));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    @Autowired JdbcTemplate db;
    @Autowired CryptoService crypto;
    @Autowired AdminSessionAdapter sessions;
    @Autowired StoryService stories;
    @Autowired StoryPersonService persons;
    @Autowired StoryRoleService roles;
    @Autowired StoryClueService clues;
    @Autowired StoryHintService hints;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void jdbcWritesAreVisibleToJpaInOneTransactionAndRollbackTogether() {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        Fixture owner = account(true, false, (byte) 32);
        String code = "ST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        Long visible = new TransactionTemplate(transactionManager).execute(status -> {
            db.update("INSERT INTO story(code,owner_id) VALUES (?,?)", code, owner.principal().accountId());
            Long count = new JPAQueryFactory(entityManager).select(QStory.story.id.count())
                    .from(QStory.story).where(QStory.story.code.eq(code)).fetchOne();
            status.setRollbackOnly();
            return count;
        });
        assertThat(visible).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM story WHERE code=?", Long.class, code)).isZero();
    }

    @Test
    void numericDraftChangesPreserveTypesAndInactiveVersionStaysOwnerOnly() {
        Fixture owner = account(true, false, (byte) 33);
        Fixture editor = account(false, false, (byte) 34);
        String code = create(owner, "Numeric");
        JsonNode saved = patch(owner, code, "basic", "0",
                "{\"difficulty\":1,\"estMin\":10,\"estMax\":100,\"limitSec\":600}");
        assertThat(saved.path("changed").asBoolean()).isTrue();
        assertThat(saved.path("editRev").asText()).isEqualTo("1");
        assertThat(saved.path("warnings").toString()).contains("POLICY_TIME_RANGE", "basic.estMax");
        assertThat(saved.path("warnings").toString()).doesNotContain("basic.estMin");
        assertThat(db.queryForObject("SELECT limit_sec FROM story_version WHERE story_id=?", Integer.class,
                storyId(code))).isEqualTo(600);
        JsonNode same = patch(owner, code, "basic", "1", "{\"difficulty\":1,\"estMin\":10,\"limitSec\":600}");
        assertThat(same.path("changed").asBoolean()).isFalse();
        assertThat(versionRev(code)).isEqualTo(1);
        assertThat(patch(owner, code, "basic", "1", "{\"estMin\":2,\"estMax\":10}")
                .path("warnings").toString()).contains("basic.estMin");
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                storyId(code), editor.principal().accountId(), owner.principal().accountId());
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(code));
        denied("NOT_FOUND", () -> stories.getStoryDetail(editor.sid(), editor.principal(), code, 1, UUID.randomUUID()));
        denied("NOT_FOUND", () -> patch(editor, code, "basic", "2", "{\"title\":\"Probe\"}"));
        denied("STATE_CONFLICT", () -> patch(owner, code, "basic", "2", "{\"title\":\"No\"}"));
    }

    @Test
    void storyHttpEnforcesCsrfStrictJsonBodyLimitsAndNoStore() throws Exception {
        Fixture owner = account(true, false, (byte) 31);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        sessions.issueCookie(owner.sid(), new org.springframework.mock.web.MockHttpServletRequest(), response);
        Cookie session = response.getCookie("__Host-admin-session");
        var csrfResult = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        Cookie csrf = csrfResult.getResponse().getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrfResult.getResponse().getContentAsString()).path("token").asText();
        String payload = "{\"createKey\":\"" + UUID.randomUUID() + "\",\"title\":\"한글 사건\"}";
        mvc.perform(post("/admin/api/stories").secure(true).contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/api/stories").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/stories").secure(true)).andExpect(status().isSeeOther());
        mvc.perform(get("/admin/stories").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(result -> assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store"));
        var create = mvc.perform(write(post("/admin/api/stories"), session, csrf, token, payload))
                .andExpect(status().isCreated()).andReturn();
        assertThat(create.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode created = mapper.readTree(create.getResponse().getContentAsString());
        String code = created.path("storyCode").asText();
        mvc.perform(write(post("/admin/api/stories"), session, csrf, token, payload))
                .andExpect(status().isConflict());
        mvc.perform(write(post("/admin/api/stories"), session, csrf, token,
                "{\"createKey\":\"" + UUID.randomUUID() + "\",\"title\":\"a\",\"title\":\"b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/api/stories"), session, csrf, token,
                "{\"createKey\":\"" + UUID.randomUUID() + "\",\"title\":\"" + "X".repeat(8200) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        String detailPath = "/admin/api/stories/" + code + "/versions/1";
        var detail = mvc.perform(get(detailPath).secure(true).cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(detail.getResponse().getContentAsString()).path("sections").path("basic")
                .path("title").asText()).isEqualTo("한글 사건");
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(detailPath + "/sections/reveal"), session, csrf, token,
                "{\"expectedRev\":\"0\",\"changes\":{\"revealText\":\"끝\"}}"))
                .andExpect(status().isOk());
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(detailPath + "/sections/reveal"), session, csrf, token,
                "{\"expectedRev\":\"0\",\"changes\":{\"revealText\":\"재전송\"}}"))
                .andExpect(status().isConflict());
    }

    /** 읽기 전용 협업자의 비노출 버전은 편집 거절보다 먼저 404로 처리한다. */
    @Test
    void reviewerVisibilityPrecedesEditPermissionAndHtmlUsesSameScope() throws Exception {
        Fixture owner = account(true, false, (byte) 40);
        Fixture reviewer = account(false, false, (byte) 41);
        Fixture stranger = account(false, true, (byte) 42);
        String code = create(owner, "Scope");
        db.update("UPDATE admin_account SET can_review=true WHERE id=?", reviewer.principal().accountId());
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'REVIEW',?)",
                storyId(code), reviewer.principal().accountId(), owner.principal().accountId());
        denied("FORBIDDEN", () -> patch(reviewer, code, "basic", "0", "{\"title\":\"No\"}"));
        denied("NOT_FOUND", () -> stories.updateStorySection(reviewer.sid(), reviewer.principal(), code, 2,
                "basic", "0", mapper.createObjectNode().put("title", "No"), UUID.randomUUID()));

        String path = "/admin/stories/" + code + "/versions/1";
        mvc.perform(get(path).secure(true).cookie(cookie(owner))).andExpect(status().isOk());
        mvc.perform(get(path).secure(true).cookie(cookie(reviewer))).andExpect(status().isOk());
        mvc.perform(get(path).secure(true).cookie(cookie(stranger))).andExpect(status().isNotFound());
        mvc.perform(get(path + "9").secure(true).cookie(cookie(owner))).andExpect(status().isNotFound());
        assertThat(auditCount(code, "CONTENT_READ")).isZero();
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(code));
        denied("NOT_FOUND", () -> patch(reviewer, code, "basic", "0", "{\"title\":\"No\"}"));
        mvc.perform(get(path).secure(true).cookie(cookie(reviewer))).andExpect(status().isNotFound());
        mvc.perform(get(path).secure(true).cookie(cookie(owner))).andExpect(status().isOk());
        db.update("UPDATE story_version SET active_yn=true WHERE story_id=?", storyId(code));
        db.update("UPDATE admin_account SET can_review=false WHERE id=?", reviewer.principal().accountId());
        mvc.perform(get(path).secure(true).cookie(cookie(reviewer))).andExpect(status().isNotFound());
    }

    /** 작은 정수·큰 정수의 경계와 null 무변경은 수정번호·이력에 같은 의미로 반영된다. */
    @Test
    void numericBoundsNullAndNoopRetainRevisionAndAudit() {
        Fixture owner = account(true, false, (byte) 43);
        String code = create(owner, "Bounds");
        patch(owner, code, "basic", "0", "{\"difficulty\":5,\"estMin\":32767,\"estMax\":32767,\"limitSec\":2147483647}");
        var before = db.queryForMap("SELECT updated_at,updated_by FROM story_version WHERE story_id=?", storyId(code));
        long audits = auditCount(code, "SECTION_UPDATED");
        assertThat(patch(owner, code, "basic", "1", "{\"estMin\":32767,\"limitSec\":2147483647}")
                .path("changed").asBoolean()).isFalse();
        assertThat(db.queryForMap("SELECT updated_at,updated_by FROM story_version WHERE story_id=?", storyId(code))).isEqualTo(before);
        for (String invalid : java.util.List.of("{\"difficulty\":6}", "{\"estMin\":32768}", "{\"limitSec\":2147483648}",
                "{\"difficulty\":1.5}", "{\"estMin\":\"10\"}", "{\"limitSec\":0}")) {
            denied("INVALID_INPUT", () -> patch(owner, code, "basic", "1", invalid));
        }
        assertThat(auditCount(code, "SECTION_UPDATED")).isEqualTo(audits);
        patch(owner, code, "basic", "1", "{\"estMin\":null,\"estMax\":null,\"difficulty\":null,\"limitSec\":null}");
        assertThat(patch(owner, code, "basic", "2", "{\"estMin\":null}").path("changed").asBoolean()).isFalse();
        db.update("UPDATE story_version SET edit_rev=? WHERE story_id=?", Long.MAX_VALUE, storyId(code));
        denied("EDIT_CONFLICT", () -> patch(owner, code, "basic", Long.toString(Long.MAX_VALUE), "{\"title\":\"Overflow\"}"));
        assertThat(db.queryForObject("SELECT edit_rev FROM story WHERE code=?", Long.class, code)).isZero();
    }

    /** 서로 다른 세션의 두 저장을 동시에 시작해 같은 수정번호에서 정확히 하나만 확정한다. */
    @Test
    void competingEditorsCommitOneRevision() throws Exception {
        Fixture owner = account(true, false, (byte) 44);
        Fixture editor = account(false, false, (byte) 45);
        String code = create(owner, "Concurrent");
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                storyId(code), editor.principal().accountId(), owner.principal().accountId());
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = java.util.List.of(owner, editor).stream().map(actor -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                try {
                    patch(actor, code, "basic", "0", "{\"title\":\"Concurrent-" + actor.principal().accountId() + "\"}");
                    return "SAVED";
                } catch (AuthException error) {
                    return error.code();
                }
            })).toList();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(futures.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS),
                    futures.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "EDIT_CONFLICT");
        }
        assertThat(versionRev(code)).isEqualTo(1);
        assertThat(auditCount(code, "SECTION_UPDATED")).isEqualTo(1);
    }

    /** 인물 CRUD는 부모 수정번호, 예약 키와 범인 참조를 함께 지킨다. */
    @Test
    void personsLifecycleKeepsReservedKeysAndCulpritReferences() throws Exception {
        Fixture owner = account(true, false, (byte) 46);
        String code = create(owner, "Persons");
        var first = persons.createPerson(owner.sid(), owner.principal(), code, 1, "0",
                mapper.readTree("{\"code\":\"A\",\"name\":\"합성 인물\",\"publicText\":\"공개\\r\\n소개\",\"secretText\":\"비밀 원고\"}"), UUID.randomUUID());
        assertThat(first.editRev()).isEqualTo("1");
        assertThat(first.itemKey()).isEqualTo("A");
        var detail = persons.getPersonDetail(owner.sid(), owner.principal(), code, 1, "A", UUID.randomUUID());
        assertThat(detail.item().publicText()).isEqualTo("공개\n소개");
        assertThat(persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "1",
                mapper.readTree("{\"publicText\":\"공개\\n소개\"}"), UUID.randomUUID()).changed()).isFalse();
        assertThat(persons.getPersonDetail(owner.sid(), owner.principal(), code, 1, "A", UUID.randomUUID()).item().updatedAt())
                .isEqualTo(detail.item().updatedAt());
        patch(owner, code, "answer", "1", "{\"culpritCode\":\"A\"}");
        denied("REFERENCE_IN_USE", () -> persons.updatePersonActive(owner.sid(), owner.principal(), code, 1, "A", "2", false, UUID.randomUUID()));
        assertThat(versionRev(code)).isEqualTo(2);
        patch(owner, code, "answer", "2", "{\"culpritCode\":null}");
        persons.updatePersonActive(owner.sid(), owner.principal(), code, 1, "A", "3", false, UUID.randomUUID());
        denied("ITEM_EXISTS", () -> persons.createPerson(owner.sid(), owner.principal(), code, 1, "4",
                mapper.readTree("{\"code\":\"A\",\"name\":\"교체 금지\"}"), UUID.randomUUID()));
        denied("STATE_CONFLICT", () -> persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "4",
                mapper.readTree("{\"name\":\"비활성 수정 금지\"}"), UUID.randomUUID()));
        denied("EDIT_CONFLICT", () -> persons.updatePersonActive(owner.sid(), owner.principal(), code, 1, "A", "3", false, UUID.randomUUID()));
        assertThat(persons.updatePersonActive(owner.sid(), owner.principal(), code, 1, "A", "4", false, UUID.randomUUID()).changed()).isFalse();
        assertThat(persons.getPersonList(owner.sid(), owner.principal(), code, 1, null, null, false).items())
                .extracting(StoryPersonService.PersonKey::code).containsExactly("A");
        persons.updatePersonActive(owner.sid(), owner.principal(), code, 1, "A", "4", true, UUID.randomUUID());
        persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "5",
                mapper.readTree("{\"secretText\":null}"), UUID.randomUUID());
        assertThat(persons.getPersonDetail(owner.sid(), owner.principal(), code, 1, "A", UUID.randomUUID()).item().secretText()).isNull();
        assertThat(versionRev(code)).isEqualTo(6);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? AND detail::text LIKE '%비밀 원고%'",
                Long.class, storyId(code))).isZero();
        assertThat(db.queryForObject("SELECT edit_rev FROM story WHERE id=?", Long.class, storyId(code))).isZero();
    }

    /** 인물 목록은 원문을 제외하고 ASCII 커서를 사용하며 잘못된 입력을 저장하지 않는다. */
    @Test
    void personsAsciiCursorAndStrictFields() throws Exception {
        Fixture owner = account(true, false, (byte) 47);
        String code = create(owner, "Keys");
        int rev = 0;
        for (String key : java.util.List.of("_", "A_", "A0", "A", "0")) {
            persons.createPerson(owner.sid(), owner.principal(), code, 1, Integer.toString(rev++),
                    mapper.createObjectNode().put("code", key).put("name", "표시 이름"), UUID.randomUUID());
        }
        var first = persons.getPersonList(owner.sid(), owner.principal(), code, 1, 2, null, true);
        assertThat(first.items()).extracting(StoryPersonService.PersonKey::code).containsExactly("0", "A");
        assertThat(first.nextAfterKey()).isEqualTo("A");
        assertThat(json(first).toString()).doesNotContain("표시 이름", "secretText");
        var second = persons.getPersonList(owner.sid(), owner.principal(), code, 1, 3, first.nextAfterKey(), true);
        assertThat(second.items()).extracting(StoryPersonService.PersonKey::code).containsExactly("A0", "A_", "_");
        assertThat(second.hasNext()).isFalse();
        assertThat(second.nextAfterKey()).isNull();
        for (String input : java.util.List.of("{\"name\":null}", "{\"name\":\"　\"}", "{\"publicText\":3}")) {
            denied("INVALID_INPUT", () -> persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "5",
                    mapper.readTree(input), UUID.randomUUID()));
        }
        denied("INVALID_INPUT", () -> persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "5",
                mapper.createObjectNode().put("name", "가".repeat(81)), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "5",
                mapper.createObjectNode().put("publicText", "가".repeat(8001)), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "5",
                mapper.createObjectNode().put("publicText", "\0"), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> persons.updatePerson(owner.sid(), owner.principal(), code, 1, "A", "5",
                mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> persons.updatePersonActive(owner.sid(), owner.principal(), code, 1, "A", null, false, UUID.randomUUID()));
        assertThat(versionRev(code)).isEqualTo(5);
    }

    /** 인물 감사 장애가 자료와 부모 수정번호를 함께 롤백하고 조회 원문을 숨긴다. */
    @Test
    void personAuditFailureRollsBackAndScopeIsParentBound() throws Exception {
        Fixture owner = account(true, false, (byte) 48);
        Fixture manager = account(false, true, (byte) 49);
        String code = create(owner, "Audit person");
        String other = create(owner, "Other parent");
        persons.createPerson(owner.sid(), owner.principal(), code, 1, "0",
                mapper.readTree("{\"code\":\"P\",\"name\":\"원고 비노출\"}"), UUID.randomUUID());
        denied("NOT_FOUND", () -> persons.getPersonDetail(manager.sid(), manager.principal(), code, 1, "P", UUID.randomUUID()));
        denied("NOT_FOUND", () -> persons.getPersonDetail(owner.sid(), owner.principal(), other, 1, "P", UUID.randomUUID()));
        db.execute("CREATE FUNCTION fail_person_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.detail->>'resource'='persons' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_person_audit BEFORE INSERT ON story_audit FOR EACH ROW EXECUTE FUNCTION fail_person_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> persons.createPerson(owner.sid(), owner.principal(), code, 1, "1",
                    mapper.readTree("{\"code\":\"FAILED\",\"name\":\"확정 금지\"}"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> persons.updatePerson(owner.sid(), owner.principal(), code, 1, "P", "1",
                    mapper.readTree("{\"name\":\"확정 금지\"}"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> persons.getPersonDetail(owner.sid(), owner.principal(), code, 1, "P", UUID.randomUUID()));
            assertThat(versionRev(code)).isEqualTo(1);
            assertThat(persons.getPersonList(owner.sid(), owner.principal(), code, 1, null, null, true).items()).hasSize(1);
        } finally {
            db.execute("DROP TRIGGER fail_person_audit ON story_audit");
            db.execute("DROP FUNCTION fail_person_audit()");
        }
        assertThat(persons.getPersonDetail(owner.sid(), owner.principal(), code, 1, "P", UUID.randomUUID()).item().name()).isEqualTo("원고 비노출");
    }

    /** 인물 HTTP 경로는 CSRF·엄격 JSON·no-store와 식별자 없는 접근 기록을 적용한다. */
    @Test
    void personHttpContractAndNormalizedAccessHistory() throws Exception {
        Fixture owner = account(true, false, (byte) 50);
        String code = create(owner, "HTTP Person");
        String path = "/admin/api/stories/" + code + "/versions/1/persons";
        var csrf = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrfCookie = csrf.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrf.getContentAsString()).path("token").asText();
        String input = "{\"expectedRev\":\"0\",\"item\":{\"code\":\"P\",\"name\":\"원고\"}}";
        mvc.perform(post(path).secure(true).cookie(cookie(owner)).contentType("application/json").content(input))
                .andExpect(status().isForbidden());
        mvc.perform(write(post(path), cookie(owner), csrfCookie, token, input))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.itemKey").value("P"));
        mvc.perform(get(path + "/P").secure(true).cookie(cookie(owner))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        String stateBody = "{\"expectedRev\":\"1\"}";
        mvc.perform(write(post(path + "/P/deactivate"), cookie(owner), csrfCookie, token, stateBody + " ".repeat(8193 - stateBody.length())))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(write(post(path + "/P/deactivate"), cookie(owner), csrfCookie, token, stateBody + " ".repeat(8192 - stateBody.length())))
                .andExpect(status().isOk());
        String restoreBody = "{\"expectedRev\":\"2\"}";
        mvc.perform(write(post(path + "/P/reactivate"), cookie(owner), csrfCookie, token, restoreBody + " ".repeat(8193 - restoreBody.length())))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(write(post(path + "/P/reactivate"), cookie(owner), csrfCookie, token, restoreBody + " ".repeat(8192 - restoreBody.length())))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route='/admin/api/stories/{storyCode}/versions/{versionNo}/persons/{itemKey}/deactivate' AND http_status=200",
                Long.class, owner.principal().accountKey())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route LIKE ?",
                Long.class, owner.principal().accountKey(), "%" + code + "%")).isZero();
    }

    /** 서로 다른 인물·본문 요청도 같은 부모 수정번호에서 하나만 확정한다. */
    @Test
    void personAndSectionRaceShareParentRevision() throws Exception {
        Fixture owner = account(true, false, (byte) 51);
        Fixture editor = account(false, false, (byte) 52);
        String code = create(owner, "Cross resource");
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                storyId(code), editor.principal().accountId(), owner.principal().accountId());
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = java.util.List.of(owner, editor).stream().map(actor -> pool.submit(() -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    if (actor == owner) {
                        persons.createPerson(actor.sid(), actor.principal(), code, 1, "0",
                                mapper.createObjectNode().put("code", "RACE").put("name", "동시 인물"), UUID.randomUUID());
                    } else {
                        patch(actor, code, "basic", "0", "{\"title\":\"동시 본문\"}");
                    }
                    return "SAVED";
                } catch (AuthException error) {
                    return error.code();
                }
            })).toList();
            assertThat(java.util.List.of(futures.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS),
                    futures.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "EDIT_CONFLICT");
        }
        assertThat(versionRev(code)).isEqualTo(1);
        assertThat(auditCount(code, "SECTION_UPDATED") + auditCount(code, "ITEM_CREATED")).isEqualTo(1);
        db.update("UPDATE story_version SET edit_rev=? WHERE story_id=?", Long.MAX_VALUE, storyId(code));
        denied("EDIT_CONFLICT", () -> persons.createPerson(owner.sid(), owner.principal(), code, 1, Long.toString(Long.MAX_VALUE),
                mapper.createObjectNode().put("code", "OVERFLOW").put("name", "증가 불가"), UUID.randomUUID()));
        assertThat(db.queryForObject("SELECT count(*) FROM story_person WHERE code='OVERFLOW'", Long.class)).isZero();
    }

    /** 역할 원고의 경계와 무변경·예약 키는 부모 수정번호 및 업무 감사를 함께 지킨다. */
    @Test
    void rolesCrudBoundariesNoopAndReservedKey() throws Exception {
        Fixture owner = account(true, false, (byte) 53);
        String story = create(owner, "역할 원고");
        role(owner, story, "A", "이름", "소개\r\n첫 줄", 0);
        var detail = roles.getRoleDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID());
        assertThat(detail.item().brief()).isEqualTo("소개\n첫 줄");
        long before = auditCount(story, "ITEM_UPDATED");
        var times = db.queryForMap("SELECT r.updated_at AS role_time,v.updated_at AS version_time "
                + "FROM story_role r JOIN story_version v ON v.id=r.version_id WHERE v.story_id=? AND r.code='A'", storyId(story));
        assertThat(roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("brief", "소개\n첫 줄"), UUID.randomUUID()).changed()).isFalse();
        assertThat(db.queryForMap("SELECT r.updated_at AS role_time,v.updated_at AS version_time "
                + "FROM story_role r JOIN story_version v ON v.id=r.version_id WHERE v.story_id=? AND r.code='A'", storyId(story)))
                .isEqualTo(times);
        assertThat(auditCount(story, "ITEM_UPDATED")).isEqualTo(before);
        denied("EDIT_CONFLICT", () -> roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "0",
                mapper.createObjectNode().put("brief", "소개\n첫 줄"), UUID.randomUUID()));
        assertThat(roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("name", "가".repeat(80)).put("brief", "한".repeat(4000)), UUID.randomUUID())
                .editRev()).isEqualTo("2");
        assertThat(roles.getRoleDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item().brief())
                .hasSize(4000);
        for (String bad : java.util.List.of("{\"name\":null}", "{\"name\":\"　\"}",
                "{\"name\":\"" + "가".repeat(81) + "\"}",
                "{\"brief\":\"" + "가".repeat(4001) + "\"}", "{\"brief\":3}")) {
            denied("INVALID_INPUT", () -> roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "2",
                    mapper.readTree(bad), UUID.randomUUID()));
        }
        denied("INVALID_REQUEST", () -> roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", null,
                mapper.createObjectNode().putNull("brief"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> roles.createRole(owner.sid(), owner.principal(), story, 1, "2",
                mapper.createObjectNode().put("code", "lower").put("name", "역할"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> roles.createRole(owner.sid(), owner.principal(), story, 1, "2",
                mapper.createObjectNode().put("code", "B").putNull("name"), UUID.randomUUID()));
        assertThat(roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().putNull("brief"), UUID.randomUUID()).editRev()).isEqualTo("3");
        assertThat(roles.getRoleDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item().brief()).isNull();
        roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "A", "3", false, UUID.randomUUID());
        denied("ITEM_EXISTS", () -> role(owner, story, "A", "재사용 금지", null, 4));
        denied("STATE_CONFLICT", () -> roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "4",
                mapper.createObjectNode().put("name", "불가"), UUID.randomUUID()));
        assertThat(versionRev(story)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT edit_rev FROM story WHERE code=?", Long.class, story)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? "
                + "AND detail::text LIKE '%소개%'", Long.class, storyId(story))).isZero();
    }

    /** 조합은 활성 동일 버전 역할만 참조하며 각 끝점의 상태에 따라 복원을 재검사한다. */
    @Test
    void pairsLifecycleReferencesAndTuplePagination() throws Exception {
        Fixture owner = account(true, false, (byte) 54);
        String story = create(owner, "조합");
        String other = create(owner, "다른 버전");
        for (String key : java.util.List.of("A", "A0", "A1", "Z", "_"))
            role(owner, story, key, "비밀 " + key, null, (int) versionRev(story));
        role(owner, other, "OUT", "다른 사건", null, 0);
        denied("INVALID_INPUT", () -> pair(owner, story, "A", "OUT", 5));
        denied("INVALID_INPUT", () -> pair(owner, story, "A", "A", 5));
        denied("INVALID_INPUT", () -> pair(owner, story, "Z", "A", 5));
        for (String[] keys : java.util.List.of(new String[] {"A", "Z"}, new String[] {"A0", "A1"},
                new String[] {"A", "A0"})) pair(owner, story, keys[0], keys[1], (int) versionRev(story));
        var page = roles.getPairList(owner.sid(), owner.principal(), story, 1, 1, null, true);
        assertThat(page.items()).extracting(StoryRoleService.PairKey::itemKey).containsExactly("A~A0");
        assertThat(page.nextAfterKey()).isEqualTo("A~A0");
        assertThat(roles.getPairList(owner.sid(), owner.principal(), story, 1, 2, page.nextAfterKey(), true).items())
                .extracting(StoryRoleService.PairKey::itemKey).containsExactly("A~Z", "A0~A1");
        assertThat(json(page).toString()).doesNotContain("비밀", "roleA", "roleB");
        assertThat(roles.getRoleList(owner.sid(), owner.principal(), story, 1, 5, null, true).items())
                .extracting(StoryRoleService.RoleKey::code).containsExactly("A", "A0", "A1", "Z", "_");
        assertThat(json(roles.getRoleList(owner.sid(), owner.principal(), story, 1, 5, null, true)).toString())
                .doesNotContain("비밀", "brief", "name");
        for (String cursor : java.util.List.of("A~A", "Z~A", "a~Z", "A~Z~X"))
            denied("INVALID_REQUEST", () -> roles.getPairList(owner.sid(), owner.principal(), story, 1, 2, cursor, true));
        denied("INVALID_REQUEST", () -> roles.getRoleList(owner.sid(), owner.principal(), story, 1, 2, "a", true));
        var pairDetail = roles.getPairDetail(owner.sid(), owner.principal(), story, 1, "A~Z", UUID.randomUUID());
        assertThat(pairDetail.item().roleA()).isEqualTo("A");
        assertThat(pairDetail.item().roleB()).isEqualTo("Z");
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? AND action='CONTENT_READ' "
                + "AND detail->>'resource'='pairs'", Long.class, storyId(story))).isEqualTo(1);
        denied("REFERENCE_IN_USE", () -> roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "A", "8", false, UUID.randomUUID()));
        denied("REFERENCE_IN_USE", () -> roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "Z", "8", false, UUID.randomUUID()));
        roles.updatePairActive(owner.sid(), owner.principal(), story, 1, "A~Z", "8", false, UUID.randomUUID());
        denied("ITEM_EXISTS", () -> pair(owner, story, "A", "Z", 9));
        roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "Z", "9", false, UUID.randomUUID());
        denied("INVALID_INPUT", () -> roles.updatePairActive(owner.sid(), owner.principal(), story, 1, "A~Z", "10", true, UUID.randomUUID()));
        roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "Z", "10", true, UUID.randomUUID());
        assertThat(roles.updatePairActive(owner.sid(), owner.principal(), story, 1, "A~Z", "11", true, UUID.randomUUID())
                .editRev()).isEqualTo("12");
        assertThat(roles.getPairList(owner.sid(), owner.principal(), story, 1, null, null, false).items()).isEmpty();
    }

    /** 부모의 비노출·읽기 전용·세션 회수는 역할과 조합 모두에서 변경보다 우선한다. */
    @Test
    void rolesAndPairsRespectParentVisibilityEditAndSession() throws Exception {
        Fixture owner = account(true, false, (byte) 55);
        Fixture reader = account(false, false, (byte) 56);
        Fixture hidden = account(false, false, (byte) 57);
        String story = create(owner, "권한");
        role(owner, story, "A", "원고 비노출", null, 0);
        role(owner, story, "B", "상대", null, 1);
        pair(owner, story, "A", "B", 2);
        db.update("UPDATE admin_account SET can_review=true WHERE id=?", reader.principal().accountId());
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'REVIEW',?)",
                storyId(story), reader.principal().accountId(), owner.principal().accountId());
        assertThat(roles.getRoleList(reader.sid(), reader.principal(), story, 1, null, null, true).items()).hasSize(2);
        assertThat(roles.getPairDetail(reader.sid(), reader.principal(), story, 1, "A~B", UUID.randomUUID()).item().roleB())
                .isEqualTo("B");
        denied("FORBIDDEN", () -> role(reader, story, "C", "거절", null, 3));
        denied("FORBIDDEN", () -> pair(reader, story, "A", "B", 3));
        denied("NOT_FOUND", () -> roles.getRoleDetail(hidden.sid(), hidden.principal(), story, 1, "A", UUID.randomUUID()));
        denied("NOT_FOUND", () -> roles.getPairList(hidden.sid(), hidden.principal(), story, 1, null, null, true));
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(story));
        denied("NOT_FOUND", () -> roles.getRoleList(reader.sid(), reader.principal(), story, 1, null, null, true));
        denied("STATE_CONFLICT", () -> role(owner, story, "C", "거절", null, 3));
        db.update("UPDATE story_version SET active_yn=true WHERE story_id=?", storyId(story));
        db.update("UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                storyId(story), reader.principal().accountId());
        denied("NOT_FOUND", () -> roles.getPairDetail(reader.sid(), reader.principal(), story, 1, "A~B", UUID.randomUUID()));
        db.update("UPDATE admin_session SET state='REVOKED',revoked_at=clock_timestamp() WHERE session_key=?",
                owner.principal().sessionKey());
        denied("AUTH_REQUIRED", () -> roles.getRoleList(owner.sid(), owner.principal(), story, 1, null, null, true));
    }

    /** 필수 감사 장애는 역할·조합 및 공유 수정번호를 롤백하며 본문 수정과 경합한다. */
    @Test
    void rolePairAuditFailureAndSharedRevisionConflict() throws Exception {
        Fixture owner = account(true, false, (byte) 58);
        String story = create(owner, "감사");
        role(owner, story, "A", "첫째", null, 0);
        role(owner, story, "B", "둘째", null, 1);
        patch(owner, story, "basic", "2", "{\"title\":\"본문 변경\"}");
        denied("EDIT_CONFLICT", () -> pair(owner, story, "A", "B", 2));
        pair(owner, story, "A", "B", 3);
        denied("EDIT_CONFLICT", () -> patch(owner, story, "basic", "3", "{\"title\":\"과거\"}"));
        db.execute("CREATE FUNCTION fail_role_pair_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.detail->>'resource' IN ('roles','pairs') THEN RAISE EXCEPTION 'injected'; END IF; "
                + "RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_role_pair_audit BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_role_pair_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> role(owner, story, "C", "롤백", null, 4));
            denied("STORY_UNAVAILABLE", () -> roles.updateRole(owner.sid(), owner.principal(), story, 1, "A", "4",
                    mapper.createObjectNode().put("name", "롤백"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> roles.updatePairActive(owner.sid(), owner.principal(), story, 1,
                    "A~B", "4", false, UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> roles.getRoleDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> roles.getPairDetail(owner.sid(), owner.principal(), story, 1, "A~B", UUID.randomUUID()));
            assertThat(versionRev(story)).isEqualTo(4);
            assertThat(db.queryForObject("SELECT count(*) FROM story_role WHERE version_id=(SELECT id FROM story_version WHERE story_id=?) "
                    + "AND code='C'", Long.class, storyId(story))).isZero();
            assertThat(roles.getPairList(owner.sid(), owner.principal(), story, 1, null, null, true).items()).hasSize(1);
        } finally {
            db.execute("DROP TRIGGER fail_role_pair_audit ON story_audit");
            db.execute("DROP FUNCTION fail_role_pair_audit()");
        }
        assertThat(roles.getRoleDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item().name())
                .isEqualTo("첫째");
    }

    /** 같은 부모 잠금 아래 역할 삭제와 조합 생성의 동시 확정은 하나만 성공한다. */
    @Test
    void roleDeactivateAndPairCreateRaceShareParentLock() throws Exception {
        Fixture owner = account(true, false, (byte) 60);
        Fixture editor = account(false, false, (byte) 61);
        String story = create(owner, "역할 동시성");
        role(owner, story, "A", "첫째", null, 0);
        role(owner, story, "B", "둘째", null, 1);
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                storyId(story), editor.principal().accountId(), owner.principal().accountId());
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var deactivate = pool.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "A", "2", false, UUID.randomUUID());
                    return "SAVED";
                } catch (AuthException error) {
                    return error.code();
                }
            });
            var createPair = pool.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    pair(editor, story, "A", "B", 2);
                    return "SAVED";
                } catch (AuthException error) {
                    return error.code();
                }
            });
            assertThat(java.util.List.of(deactivate.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    createPair.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "EDIT_CONFLICT");
        }
        assertThat(versionRev(story)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM story_pair p JOIN story_role a "
                + "ON a.version_id=p.version_id AND a.code=p.role_a JOIN story_role b "
                + "ON b.version_id=p.version_id AND b.code=p.role_b WHERE p.active_yn AND (NOT a.active_yn OR NOT b.active_yn)",
                Long.class)).isZero();
    }

    /** 상태 무변경도 stale 요청은 거절하며, 비초안은 삭제·복원으로 우회할 수 없다. */
    @Test
    void rolePairStateNoopsAndNonDraftWritesKeepTheirInvariants() throws Exception {
        Fixture owner = account(true, false, (byte) 96);
        String story = create(owner, "상태 경계");
        role(owner, story, "A", "첫째", null, 0);
        role(owner, story, "B", "둘째", null, 1);
        pair(owner, story, "A", "B", 2);
        var before = rolePairState(story);
        assertThat(roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "A", "3", true,
                UUID.randomUUID()).changed()).isFalse();
        assertThat(roles.updatePairActive(owner.sid(), owner.principal(), story, 1, "A~B", "3", true,
                UUID.randomUUID()).changed()).isFalse();
        assertThat(rolePairState(story)).isEqualTo(before);
        roles.updatePairActive(owner.sid(), owner.principal(), story, 1, "A~B", "3", false, UUID.randomUUID());
        before = rolePairState(story);
        assertThat(roles.updatePairActive(owner.sid(), owner.principal(), story, 1, "A~B", "4", false,
                UUID.randomUUID()).changed()).isFalse();
        denied("EDIT_CONFLICT", () -> roles.updatePairActive(owner.sid(), owner.principal(), story, 1,
                "A~B", "3", false, UUID.randomUUID()));
        assertThat(rolePairState(story)).isEqualTo(before);
        roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "B", "4", false, UUID.randomUUID());
        before = rolePairState(story);
        assertThat(roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "B", "5", false,
                UUID.randomUUID()).changed()).isFalse();
        denied("EDIT_CONFLICT", () -> roles.updateRoleActive(owner.sid(), owner.principal(), story, 1,
                "B", "4", false, UUID.randomUUID()));
        denied("INVALID_INPUT", () -> roles.updatePairActive(owner.sid(), owner.principal(), story, 1,
                "A~B", "5", true, UUID.randomUUID()));
        assertThat(rolePairState(story)).isEqualTo(before);
        roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "B", "5", true, UUID.randomUUID());
        roles.updatePairActive(owner.sid(), owner.principal(), story, 1, "A~B", "6", true, UUID.randomUUID());
        long version = db.queryForObject("SELECT id FROM story_version WHERE story_id=?", Long.class, storyId(story));
        long snapshot = db.queryForObject("INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by) "
                + "VALUES (?,7,'{}'::jsonb,?,?) RETURNING id", Long.class,
                version, UUID.randomUUID(), owner.principal().accountId());
        for (String state : java.util.List.of("REVIEW", "READY", "PUBLISHED")) {
            db.update("UPDATE story_version SET status=?,current_snapshot_id=? WHERE id=?", state, snapshot, version);
            before = rolePairState(story);
            denied("STATE_CONFLICT", () -> role(owner, story, "C", "차단", null, 7));
            denied("STATE_CONFLICT", () -> roles.updateRoleActive(owner.sid(), owner.principal(), story, 1,
                    "A", "7", false, UUID.randomUUID()));
            denied("STATE_CONFLICT", () -> roles.updatePairActive(owner.sid(), owner.principal(), story, 1,
                    "A~B", "7", false, UUID.randomUUID()));
            assertThat(rolePairState(story)).isEqualTo(before);
        }
    }

    /**
     * A/B 역할과 A~B 조합의 시각·수정번호·쓰기 감사 불변식을 한 스냅샷으로 읽는다.
     * @param story A/B 역할과 A~B 조합이 준비된 폐기형 시험 사건 코드
     * @return 읽기 감사와 무관한 실제 저장 상태이며 원고는 포함하지 않는다
     */
    private java.util.Map<String, Object> rolePairState(String story) {
        return db.queryForMap("SELECT v.edit_rev,v.updated_at,"
                + "(SELECT count(*) FROM story_audit a WHERE a.version_id=v.id AND action LIKE 'ITEM_%') AS audits,"
                + "(SELECT updated_at FROM story_role WHERE version_id=v.id AND code='A') AS role_a_time,"
                + "(SELECT updated_at FROM story_role WHERE version_id=v.id AND code='B') AS role_b_time,"
                + "(SELECT updated_at FROM story_pair WHERE version_id=v.id AND role_a='A' AND role_b='B') AS pair_time "
                + "FROM story_version v WHERE v.story_id=?", storyId(story));
    }

    /** HTTP 입력 실패와 성공 양쪽에서 조합·역할 서버 접근 경로를 원문 없이 정규화한다. */
    @Test
    void rolePairHttpValidationAndNormalizedHistory() throws Exception {
        Fixture owner = account(true, false, (byte) 59);
        String story = create(owner, "HTTP 역할");
        String base = "/admin/api/stories/" + story + "/versions/1/";
        var csrf = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrfCookie = csrf.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrf.getContentAsString()).path("token").asText();
        Cookie session = cookie(owner);
        String first = "{\"expectedRev\":\"0\",\"item\":{\"code\":\"A\",\"name\":\"원고식별자\"}}";
        mvc.perform(post(base + "roles").secure(true).cookie(session).contentType("application/json").content(first))
                .andExpect(status().isForbidden());
        mvc.perform(write(post(base + "roles"), session, csrfCookie, token, first))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        mvc.perform(write(post(base + "roles"), session, csrfCookie, token,
                "{\"expectedRev\":1,\"item\":{\"code\":\"B\",\"name\":\"실패\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base + "roles"), session, csrfCookie, token,
                "{\"item\":{\"code\":\"B\",\"name\":\"실패\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base + "roles"), session, csrfCookie, token,
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"name\":\"첫째\"},\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base + "roles"), session, csrfCookie, token,
                "{\"expectedRev\":\"1\",\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"name\":\"첫째\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base + "roles"), session, csrfCookie, token,
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"name\":\"" + "가".repeat(180000) + "\"}}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(write(post(base + "roles"), session, csrfCookie, token,
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"name\":\"둘째\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(write(post(base + "pairs"), session, csrfCookie, token,
                "{\"expectedRev\":\"2\",\"item\":{\"roleA\":\"A\",\"roleB\":\"B\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(get(base + "roles/A?afterKey=원문쿼리").secure(true).cookie(session))
                .andExpect(status().isOk());
        mvc.perform(get(base + "pairs/A~B").secure(true).cookie(session)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "pairs/A~B"),
                session, csrfCookie, token, "{\"expectedRev\":\"3\",\"changes\":{}}"))
                .andExpect(status().isBadRequest());
        String pairBody = "{\"expectedRev\":\"3\",\"item\":{\"roleA\":\"A\",\"roleB\":\"B\"}}";
        String boundary = pairBody + " ".repeat(8192 - pairBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        mvc.perform(write(post(base + "pairs"), session, csrfCookie, token, boundary))
                .andExpect(status().isConflict());
        mvc.perform(write(post(base + "pairs"), session, csrfCookie, token, boundary + " "))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get(base + "pairs/A~bad?identifier=원문쿼리").secure(true).cookie(session))
                .andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=? AND http_status=201",
                Long.class, owner.principal().accountKey(),
                "/admin/api/stories/{storyCode}/versions/{versionNo}/pairs")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=? AND http_status=201",
                Long.class, owner.principal().accountKey(),
                "/admin/api/stories/{storyCode}/versions/{versionNo}/roles")).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=? AND http_status=200",
                Long.class, owner.principal().accountKey(),
                "/admin/api/stories/{storyCode}/versions/{versionNo}/pairs/{itemKey}")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=? AND http_status=400",
                Long.class, owner.principal().accountKey(),
                "/admin/api/stories/{storyCode}/versions/{versionNo}/pairs/{itemKey}")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route='UNMATCHED' AND http_status=400",
                Long.class, owner.principal().accountKey())).isGreaterThanOrEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND "
                + "(route LIKE ? OR route LIKE '%원문%' OR route LIKE '%~%' OR route LIKE '%afterKey%')",
                Long.class, owner.principal().accountKey(), "%" + story + "%")).isZero();
    }

    private StoryRoleService.ItemCreated role(Fixture actor, String story, String key, String name, String brief, int rev) {
        return roles.createRole(actor.sid(), actor.principal(), story, 1, Integer.toString(rev),
                mapper.createObjectNode().put("code", key).put("name", name).put("brief", brief), UUID.randomUUID());
    }

    private StoryRoleService.ItemCreated pair(Fixture actor, String story, String a, String b, int rev) {
        return roles.createPair(actor.sid(), actor.principal(), story, 1, Integer.toString(rev),
                mapper.createObjectNode().put("roleA", a).put("roleB", b), UUID.randomUUID());
    }

    private StoryClueService.ItemCreated clue(Fixture actor, String story, String key, int rev) {
        return clues.createClue(actor.sid(), actor.principal(), story, 1, Integer.toString(rev),
                mapper.createObjectNode().put("code", key).put("title", "단서 " + key), UUID.randomUUID());
    }

    private StoryClueService.ItemCreated assignment(Fixture actor, String story, String clue, String role, int rev) {
        return clues.createClueRole(actor.sid(), actor.principal(), story, 1, Integer.toString(rev),
                mapper.createObjectNode().put("clueCode", clue).put("roleCode", role), UUID.randomUUID());
    }

    @Test
    void cluesAndAssignmentsPreserveScopeReferencesAndTuplePagination() {
        Fixture owner = account(true, false, (byte) 101);
        String story = create(owner, "단서 초안");
        String other = create(owner, "다른 버전");
        role(owner, story, "A", "역할", null, 0);
        role(owner, story, "Z", "다른 역할", null, 1);
        role(owner, other, "OUT", "외부", null, 0);
        clue(owner, other, "OUT", 1);
        clue(owner, story, "A", 2);
        clue(owner, story, "A0", 3);
        clue(owner, story, "Z", 4);
        assertThat(clues.getClueDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                .item().scope()).isEqualTo("ROLE");
        assertThat(clues.getClueDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                .item().body()).isNull();
        assertThat(json(clues.getClueList(owner.sid(), owner.principal(), story, 1, 1, null, null)).toString())
                .doesNotContain("단서 A", "body", "title");
        var page = clues.getClueList(owner.sid(), owner.principal(), story, 1, 1, null, true);
        assertThat(page.nextAfterKey()).isEqualTo("A");
        assertThat(clues.getClueList(owner.sid(), owner.principal(), story, 1, 2, "A", true).items())
                .extracting(StoryClueService.ClueKey::code).containsExactly("A0", "Z");
        denied("INVALID_INPUT", () -> assignment(owner, story, "A", "OUT", 5));
        denied("INVALID_INPUT", () -> assignment(owner, story, "OUT", "A", 5));
        assertThat(assignment(owner, story, "A", "A", 5).itemKey()).isEqualTo("A~A");
        assignment(owner, story, "A", "Z", 6);
        assignment(owner, story, "A0", "A", 7);
        var links = clues.getClueRoleList(owner.sid(), owner.principal(), story, 1, 1, null, true);
        assertThat(links.items()).extracting(StoryClueService.AssignmentKey::itemKey).containsExactly("A~A");
        assertThat(clues.getClueRoleList(owner.sid(), owner.principal(), story, 1, 2, links.nextAfterKey(), true).items())
                .extracting(StoryClueService.AssignmentKey::itemKey).containsExactly("A~Z", "A0~A");
        assertThat(json(links).toString()).doesNotContain("title", "body", "roleCode");
        denied("REFERENCE_IN_USE", () -> clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "8",
                mapper.createObjectNode().put("scope", "COMMON"), UUID.randomUUID()));
        denied("REFERENCE_IN_USE", () -> clues.updateClueActive(owner.sid(), owner.principal(), story, 1, "A", "8", false, UUID.randomUUID()));
        denied("REFERENCE_IN_USE", () -> roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "A", "8", false, UUID.randomUUID()));
        clues.updateClueRoleActive(owner.sid(), owner.principal(), story, 1, "A~A", "8", false, UUID.randomUUID());
        clues.updateClueRoleActive(owner.sid(), owner.principal(), story, 1, "A~Z", "9", false, UUID.randomUUID());
        assertThat(clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "10",
                mapper.createObjectNode().put("scope", "COMMON"), UUID.randomUUID()).editRev()).isEqualTo("11");
        denied("INVALID_INPUT", () -> clues.updateClueRoleActive(owner.sid(), owner.principal(), story, 1,
                "A~A", "11", true, UUID.randomUUID()));
        denied("ITEM_EXISTS", () -> assignment(owner, story, "A", "A", 11));
        assertThat(clues.getClueRoleList(owner.sid(), owner.principal(), story, 1, 10, null, false).items())
                .extracting(StoryClueService.AssignmentKey::itemKey).containsExactly("A~A", "A~Z");
        clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "11",
                mapper.createObjectNode().put("scope", "ROLE"), UUID.randomUUID());
        assertThat(clues.updateClueRoleActive(owner.sid(), owner.principal(), story, 1,
                "A~A", "12", true, UUID.randomUUID()).editRev()).isEqualTo("13");
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? AND action='CONTENT_READ' "
                + "AND detail->>'resource'='clues'", Long.class, storyId(story))).isEqualTo(2);
    }

    @Test
    void clueFieldsNoopPersonAndStateValidation() {
        Fixture owner = account(true, false, (byte) 102);
        String story = create(owner, "필드");
        denied("INVALID_INPUT", () -> clues.createClue(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "BAD"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> clues.createClue(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "BAD").put("title", "   "), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> clues.createClue(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "BAD").put("title", "제목").put("personCode", "NO"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> clues.createClue(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "BAD").put("title", "제목").put("body", "가".repeat(12001)), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> clues.createClue(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "BAD").put("title", "제목").put("activeYn", false), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> clues.createClue(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "BAD").put("title", "제목").putNull("scope"), UUID.randomUUID()));
        clue(owner, story, "A", 0);
        var before = db.queryForMap("SELECT v.edit_rev,v.updated_at,c.updated_at AS clue_time FROM story_version v "
                + "JOIN story_clue c ON c.version_id=v.id WHERE v.story_id=? AND c.code='A'", storyId(story));
        long audited = auditCount(story, "ITEM_UPDATED");
        assertThat(clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("title", "단서 A"), UUID.randomUUID()).changed()).isFalse();
        assertThat(db.queryForMap("SELECT v.edit_rev,v.updated_at,c.updated_at AS clue_time FROM story_version v "
                + "JOIN story_clue c ON c.version_id=v.id WHERE v.story_id=? AND c.code='A'", storyId(story))).isEqualTo(before);
        assertThat(auditCount(story, "ITEM_UPDATED")).isEqualTo(audited);
        denied("EDIT_CONFLICT", () -> clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "0",
                mapper.createObjectNode().put("title", "단서 A"), UUID.randomUUID()));
        assertThat(clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("title", "𠮷 단서\n둘째").put("body", "첫째\r\n둘째")
                        .put("sourceText", "자료"), UUID.randomUUID()).editRev()).isEqualTo("2");
        var item = clues.getClueDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item();
        assertThat(item.title()).isEqualTo("𠮷 단서\n둘째");
        assertThat(item.body()).isEqualTo("첫째\n둘째");
        denied("INVALID_INPUT", () -> clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().putNull("scope"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().put("title", "x".repeat(161)), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().put("sourceText", "x".repeat(401)), UUID.randomUUID()));
        assertThat(clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().putNull("body").putNull("sourceText"), UUID.randomUUID()).editRev()).isEqualTo("3");
        assertThat(clues.getClueDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item().body()).isNull();
        assertThat(clues.updateClueActive(owner.sid(), owner.principal(), story, 1, "A", "3", true,
                UUID.randomUUID()).changed()).isFalse();
        db.update("INSERT INTO story_person(version_id,code,name) SELECT id,'P','인물' FROM story_version WHERE story_id=?", storyId(story));
        clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "3",
                mapper.createObjectNode().put("personCode", "P"), UUID.randomUUID());
        denied("REFERENCE_IN_USE", () -> persons.updatePersonActive(owner.sid(), owner.principal(), story, 1,
                "P", "4", false, UUID.randomUUID()));
        clues.updateClueActive(owner.sid(), owner.principal(), story, 1, "A", "4", false, UUID.randomUUID());
        persons.updatePersonActive(owner.sid(), owner.principal(), story, 1, "P", "5", false, UUID.randomUUID());
        denied("INVALID_INPUT", () -> clues.updateClueActive(owner.sid(), owner.principal(), story, 1,
                "A", "6", true, UUID.randomUUID()));
        persons.updatePersonActive(owner.sid(), owner.principal(), story, 1, "P", "6", true, UUID.randomUUID());
        clues.updateClueActive(owner.sid(), owner.principal(), story, 1, "A", "7", true, UUID.randomUUID());
    }

    @Test
    void clueAuditFailureRollsBackAndReadVisibilityRespectsParent() {
        Fixture owner = account(true, false, (byte) 103);
        Fixture reader = account(false, false, (byte) 104);
        Fixture hidden = account(false, true, (byte) 105);
        String story = create(owner, "감사 단서");
        role(owner, story, "A", "역할", null, 0);
        clue(owner, story, "A", 1);
        assignment(owner, story, "A", "A", 2);
        db.update("UPDATE admin_account SET can_review=true WHERE id=?", reader.principal().accountId());
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'REVIEW',?)",
                storyId(story), reader.principal().accountId(), owner.principal().accountId());
        assertThat(clues.getClueDetail(reader.sid(), reader.principal(), story, 1, "A", UUID.randomUUID()).item().code())
                .isEqualTo("A");
        denied("FORBIDDEN", () -> clue(reader, story, "B", 3));
        denied("NOT_FOUND", () -> clues.getClueDetail(hidden.sid(), hidden.principal(), story, 1, "A", UUID.randomUUID()));
        denied("NOT_FOUND", () -> clues.getClueRoleList(hidden.sid(), hidden.principal(), story, 1, null, null, true));
        db.execute("CREATE FUNCTION fail_clue_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.detail->>'resource' IN ('clues','clue-roles') THEN RAISE EXCEPTION 'injected'; END IF; "
                + "RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_clue_audit BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_clue_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> clue(owner, story, "B", 3));
            denied("STORY_UNAVAILABLE", () -> clues.updateClue(owner.sid(), owner.principal(), story, 1, "A", "3",
                    mapper.createObjectNode().put("body", "비밀"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> clues.updateClueRoleActive(owner.sid(), owner.principal(), story, 1,
                    "A~A", "3", false, UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> clues.getClueDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> clues.getClueRoleDetail(owner.sid(), owner.principal(), story, 1, "A~A", UUID.randomUUID()));
            assertThat(versionRev(story)).isEqualTo(3);
            assertThat(db.queryForObject("SELECT count(*) FROM story_clue c JOIN story_version v ON v.id=c.version_id "
                    + "WHERE v.story_id=? AND c.code='B'", Long.class, storyId(story))).isZero();
            assertThat(clues.getClueRoleList(owner.sid(), owner.principal(), story, 1, null, null, true).items()).hasSize(1);
        } finally {
            db.execute("DROP TRIGGER fail_clue_audit ON story_audit");
            db.execute("DROP FUNCTION fail_clue_audit()");
        }
        long version = db.queryForObject("SELECT id FROM story_version WHERE story_id=?", Long.class, storyId(story));
        long snapshot = db.queryForObject("INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by) "
                + "VALUES (?,3,'{}'::jsonb,?,?) RETURNING id", Long.class,
                version, UUID.randomUUID(), owner.principal().accountId());
        db.update("UPDATE story_version SET status='REVIEW',current_snapshot_id=? WHERE id=?", snapshot, version);
        denied("STATE_CONFLICT", () -> clue(owner, story, "B", 3));
        denied("STATE_CONFLICT", () -> clues.updateClueRoleActive(owner.sid(), owner.principal(), story, 1,
                "A~A", "3", false, UUID.randomUUID()));
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(story));
        denied("NOT_FOUND", () -> clues.getClueList(reader.sid(), reader.principal(), story, 1, null, null, true));
        assertThat(clues.getClueList(owner.sid(), owner.principal(), story, 1, null, null, true).items()).hasSize(1);
    }

    @Test
    void clueRoleAndPersonConcurrentMutationsSerializeOnParent() throws Exception {
        Fixture owner = account(true, false, (byte) 106);
        Fixture editor = account(false, false, (byte) 107);
        String story = create(owner, "동시성 단서");
        role(owner, story, "A", "역할", null, 0);
        clue(owner, story, "A", 1);
        db.update("INSERT INTO story_person(version_id,code,name) SELECT id,'P','인물' FROM story_version WHERE story_id=?", storyId(story));
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                storyId(story), editor.principal().accountId(), owner.principal().accountId());
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var deactivate = pool.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    roles.updateRoleActive(owner.sid(), owner.principal(), story, 1, "A", "2", false, UUID.randomUUID());
                    return "SAVED";
                } catch (AuthException error) { return error.code(); }
            });
            var link = pool.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try { assignment(editor, story, "A", "A", 2); return "SAVED"; }
                catch (AuthException error) { return error.code(); }
            });
            assertThat(java.util.List.of(deactivate.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    link.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "EDIT_CONFLICT");
        }
        assertThat(versionRev(story)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM clue_role cr JOIN story_role r "
                + "ON r.version_id=cr.version_id AND r.code=cr.role_code WHERE cr.active_yn AND NOT r.active_yn",
                Long.class)).isZero();
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var deactivate = pool.submit(() -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    persons.updatePersonActive(owner.sid(), owner.principal(), story, 1, "P", "3", false, UUID.randomUUID());
                    return "SAVED";
                } catch (AuthException error) { return error.code(); }
            });
            var attach = pool.submit(() -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    clues.updateClue(editor.sid(), editor.principal(), story, 1, "A", "3",
                            mapper.createObjectNode().put("personCode", "P"), UUID.randomUUID());
                    return "SAVED";
                } catch (AuthException error) { return error.code(); }
            });
            assertThat(java.util.List.of(deactivate.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    attach.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "EDIT_CONFLICT");
        }
        assertThat(versionRev(story)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM story_clue c JOIN story_person p "
                + "ON p.version_id=c.version_id AND p.code=c.person_code WHERE c.active_yn AND NOT p.active_yn",
                Long.class)).isZero();
    }

    @Test
    void clueHttpBoundariesCsrfAndNormalizedHistory() throws Exception {
        Fixture owner = account(true, false, (byte) 108);
        String story = create(owner, "HTTP 단서");
        role(owner, story, "A", "역할", null, 0);
        String base = "/admin/api/stories/" + story + "/versions/1/";
        var csrfResponse = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrfResponse.getContentAsString()).path("token").asText();
        Cookie session = cookie(owner);
        String create = "{\"expectedRev\":\"1\",\"item\":{\"code\":\"A\",\"title\":\"비밀 원고\"}}";
        mvc.perform(post(base + "clues").secure(true).cookie(session).contentType("application/json").content(create))
                .andExpect(status().isForbidden());
        mvc.perform(get(base + "clues").secure(true)).andExpect(status().isUnauthorized());
        var result = mvc.perform(write(post(base + "clues"), session, csrf, token, create))
                .andExpect(status().isCreated()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("itemKey").asText()).isEqualTo("A");
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("requestId").asText()).isNotBlank();
        var clueDetail = mvc.perform(get(base + "clues/A").secure(true).cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(clueDetail.getResponse().getContentAsString()).path("item").path("title").asText())
                .isEqualTo("비밀 원고");
        mvc.perform(write(post(base + "clues"), session, csrf, token,
                "{\"expectedRev\":\"2\",\"item\":{\"code\":\"B\",\"title\":\"" + "가".repeat(180000) + "\"}}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(write(post(base + "clues"), session, csrf, token,
                "{\"expectedRev\":\"2\",\"item\":{\"code\":\"B\",\"title\":\"x\",\"title\":\"y\"}}"))
                .andExpect(status().isBadRequest());
        byte[] invalidUtf8 = "{\"expectedRev\":\"2\",\"item\":{\"code\":\"B\",\"title\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] malformed = Arrays.copyOf(invalidUtf8, invalidUtf8.length + 4);
        malformed[invalidUtf8.length] = (byte) 0xC3;
        malformed[invalidUtf8.length + 1] = '"';
        malformed[invalidUtf8.length + 2] = '}';
        malformed[invalidUtf8.length + 3] = '}';
        String mismatchedJson = "{\"expectedRev\":\"2\",\"item\":{\"code\":\"B\",\"title\":\"다른 단서\"}}";
        byte[] utf16Le = ("\uFEFF" + mismatchedJson).getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] utf32Be = ("\uFEFF" + mismatchedJson).getBytes(java.nio.charset.Charset.forName("UTF-32BE"));
        for (byte[] rejected : java.util.List.of(utf16Le, utf32Be, malformed)) {
            mvc.perform(write(post(base + "clues"), session, csrf, token, "{}")
                    .contentType("application/json;charset=utf-8").content(rejected))
                    .andExpect(status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                            .value("INVALID_REQUEST"));
            assertThat(versionRev(story)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT count(*) FROM story_clue c JOIN story_version v ON v.id=c.version_id "
                    + "WHERE v.story_id=? AND c.code='B'", Long.class, storyId(story))).isZero();
        }
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "clues/A"),
                session, csrf, token, "{\"expectedRev\":\"2\",\"changes\":{\"body\":\"첫째\\n둘째\"}}"))
                .andExpect(status().isOk());
        String cluePatch = "{\"expectedRev\":\"3\",\"changes\":{\"body\":\"첫째\\n둘째\"}}";
        String clueBoundary = cluePatch + " ".repeat(512 * 1024 - cluePatch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "clues/A"),
                session, csrf, token, clueBoundary)).andExpect(status().isOk());
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "clues/A"),
                session, csrf, token, clueBoundary + " ")).andExpect(status().isPayloadTooLarge());
        mvc.perform(get(base + "clues?size=1").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("비밀 원고", "첫째"));
        mvc.perform(get(base + "clues/A?afterKey=원문쿼리").secure(true).cookie(session))
                .andExpect(status().isOk());
        String linkBody = "{\"expectedRev\":\"3\",\"item\":{\"clueCode\":\"A\",\"roleCode\":\"A\"}}";
        mvc.perform(write(post(base + "clue-roles"), session, csrf, token, linkBody))
                .andExpect(status().isCreated());
        mvc.perform(get(base + "clue-roles/A~A").secure(true).cookie(session)).andExpect(status().isOk());
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "clue-roles/A~A"),
                session, csrf, token, "{\"expectedRev\":\"4\",\"changes\":{}}"))
                .andExpect(status().isBadRequest());
        String boundary = linkBody + " ".repeat(8192 - linkBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        mvc.perform(write(post(base + "clue-roles"), session, csrf, token, boundary))
                .andExpect(status().isConflict());
        mvc.perform(write(post(base + "clue-roles"), session, csrf, token, boundary + " "))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(write(post(base + "clue-roles/A~A/deactivate"), session, csrf, token,
                "{\"expectedRev\":\"4\"}"))
                .andExpect(status().isOk());
        String stateBody = "{\"expectedRev\":\"5\"}";
        String stateBoundary = stateBody + " ".repeat(8192 - stateBody.length());
        mvc.perform(write(post(base + "clue-roles/A~A/deactivate"), session, csrf, token, stateBoundary))
                .andExpect(status().isOk());
        mvc.perform(write(post(base + "clue-roles/A~A/deactivate"), session, csrf, token, stateBoundary + " "))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(write(post(base + "clues/A/deactivate"), session, csrf, token,
                "{\"expectedRev\":\"5\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(base + "clue-roles/A~A").secure(true).cookie(session)).andExpect(status().isOk());
        mvc.perform(write(post(base + "clues/A/reactivate"), session, csrf, token,
                "{\"expectedRev\":\"6\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(post(base + "clue-roles/A~A/reactivate"), session, csrf, token,
                "{\"expectedRev\":\"7\"}"))
                .andExpect(status().isOk());
        for (String suffix : java.util.List.of("clues", "clues/{itemKey}", "clues/{itemKey}/deactivate",
                "clues/{itemKey}/reactivate", "clue-roles", "clue-roles/{itemKey}",
                "clue-roles/{itemKey}/deactivate", "clue-roles/{itemKey}/reactivate")) {
            assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=?",
                    Long.class, owner.principal().accountKey(),
                    "/admin/api/stories/{storyCode}/versions/{versionNo}/" + suffix)).isPositive();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND "
                + "(route LIKE ? OR route LIKE '%원문%' OR route LIKE '%~%' OR route LIKE '%afterKey%')",
                Long.class, owner.principal().accountKey(), "%" + story + "%")).isZero();
    }

    /** 힌트의 코드·단계 예약과 무변경 수정번호를 격리 DB에서 확인한다. */
    @Test
    void hintsReserveInactiveCodeAndLevelAndPreserveExplicitBodyChanges() {
        Fixture owner = account(true, false, (byte) 109);
        String story = create(owner, "단계별 힌트");
        var first = mapper.createObjectNode().put("code", "A").put("level", 1).put("body", "첫째\r\n둘째");
        assertThat(hints.createHint(owner.sid(), owner.principal(), story, 1, "0", first, UUID.randomUUID())
                .editRev()).isEqualTo("1");
        assertThat(hints.createHint(owner.sid(), owner.principal(), story, 1, "1",
                mapper.createObjectNode().put("code", "Z").put("level", 2), UUID.randomUUID()).editRev()).isEqualTo("2");
        var page = hints.getHintList(owner.sid(), owner.principal(), story, 1, 1, null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).code()).isEqualTo("A");
        assertThat(page.nextAfterKey()).isEqualTo("A");
        assertThat(json(page).toString()).doesNotContain("첫째", "level", "body");
        assertThat(hints.getHintList(owner.sid(), owner.principal(), story, 1, 1, "A", null)
                .items().get(0).code()).isEqualTo("Z");
        var detail = hints.getHintDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID());
        assertThat(detail.item().body()).isEqualTo("첫째\n둘째");
        assertThat(detail.item().level()).isEqualTo((short) 1);
        assertThat(auditCount(story, "CONTENT_READ")).isEqualTo(1);
        assertThat(hints.updateHintActive(owner.sid(), owner.principal(), story, 1, "A", "2", false,
                UUID.randomUUID()).editRev()).isEqualTo("3");
        assertThat(hints.getHintList(owner.sid(), owner.principal(), story, 1, null, null, false)
                .items().get(0).code()).isEqualTo("A");
        denied("SLOT_CONFLICT", () -> hints.createHint(owner.sid(), owner.principal(), story, 1, "3",
                mapper.createObjectNode().put("code", "B").put("level", 1), UUID.randomUUID()));
        denied("ITEM_EXISTS", () -> hints.createHint(owner.sid(), owner.principal(), story, 1, "3",
                mapper.createObjectNode().put("code", "A").put("level", 3), UUID.randomUUID()));
        denied("SLOT_CONFLICT", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1, "Z", "3",
                mapper.createObjectNode().put("level", 1), UUID.randomUUID()));
        denied("STATE_CONFLICT", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "3",
                mapper.createObjectNode().putNull("body"), UUID.randomUUID()));
        assertThat(hints.updateHintActive(owner.sid(), owner.principal(), story, 1, "A", "3", true,
                UUID.randomUUID()).editRev()).isEqualTo("4");
        assertThat(hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "4",
                mapper.createObjectNode().putNull("body"), UUID.randomUUID()).editRev()).isEqualTo("5");
        long before = auditCount(story, "ITEM_UPDATED");
        var same = hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "5",
                mapper.createObjectNode().putNull("body"), UUID.randomUUID());
        assertThat(same.changed()).isFalse();
        assertThat(same.editRev()).isEqualTo("5");
        assertThat(auditCount(story, "ITEM_UPDATED")).isEqualTo(before);
        denied("EDIT_CONFLICT", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "4",
                mapper.createObjectNode().putNull("body"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "5",
                mapper.createObjectNode().put("level", 1.0), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "5",
                mapper.createObjectNode().putNull("level"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "5",
                mapper.createObjectNode().put("body", "😀".repeat(4001)), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1, "A", "5",
                mapper.createObjectNode().put("code", "NEW"), UUID.randomUUID()));
        assertThat(versionRev(story)).isEqualTo(5);
    }

    /** 필수 감사 실패가 원고 변경을 되돌리고 권한 없는 조회를 차단하는지 확인한다. */
    @Test
    void hintAuditFailureAndParentVisibilityRejectUnauthorizedManuscript() {
        Fixture owner = account(true, false, (byte) 110);
        Fixture editor = account(false, false, (byte) 111);
        Fixture outsider = account(false, true, (byte) 112);
        String story = create(owner, "힌트 감사");
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                storyId(story), editor.principal().accountId(), owner.principal().accountId());
        hints.createHint(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "ONE").put("level", 1).put("body", "제작 원고"), UUID.randomUUID());
        denied("NOT_FOUND", () -> hints.getHintDetail(outsider.sid(), outsider.principal(), story, 1,
                "ONE", UUID.randomUUID()));
        assertThat(hints.getHintDetail(editor.sid(), editor.principal(), story, 1, "ONE", UUID.randomUUID())
                .item().body()).isEqualTo("제작 원고");
        db.execute("CREATE FUNCTION fail_hint_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.action IN ('ITEM_UPDATED','CONTENT_READ') AND NEW.detail->>'resource'='hints' "
                + "THEN RAISE EXCEPTION 'synthetic hint audit outage'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_hint_audit_insert BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_hint_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1,
                    "ONE", "1", mapper.createObjectNode().put("body", "실패 원고"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> hints.getHintDetail(owner.sid(), owner.principal(), story, 1,
                    "ONE", UUID.randomUUID()));
        } finally {
            db.execute("DROP TRIGGER fail_hint_audit_insert ON story_audit");
            db.execute("DROP FUNCTION fail_hint_audit()");
        }
        assertThat(versionRev(story)).isEqualTo(1);
        assertThat(hints.getHintDetail(owner.sid(), owner.principal(), story, 1, "ONE", UUID.randomUUID())
                .item().body()).isEqualTo("제작 원고");
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(story));
        denied("NOT_FOUND", () -> hints.getHintDetail(editor.sid(), editor.principal(), story, 1,
                "ONE", UUID.randomUUID()));
        assertThat(hints.getHintDetail(owner.sid(), owner.principal(), story, 1, "ONE", UUID.randomUUID())
                .item().activeYn()).isTrue();
        denied("STATE_CONFLICT", () -> hints.updateHint(owner.sid(), owner.principal(), story, 1,
                "ONE", "1", mapper.createObjectNode().put("body", "변경"), UUID.randomUUID()));
    }

    /** 관리자 힌트 API의 UTF-8·바이트 상한·CSRF·경로 감사를 확인한다. */
    @Test
    void hintHttpRejectsUnsafeBodiesAndKeepsMetadataListsPrivate() throws Exception {
        Fixture owner = account(true, false, (byte) 113);
        String story = create(owner, "HTTP 힌트");
        String base = "/admin/api/stories/" + story + "/versions/1/hints";
        var csrfResponse = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrfResponse.getContentAsString()).path("token").asText();
        Cookie session = cookie(owner);
        String create = "{\"expectedRev\":\"0\",\"item\":{\"code\":\"A\",\"level\":1,\"body\":\"첫 힌트\"}}";
        mvc.perform(post(base).secure(true).cookie(session).contentType("application/json").content(create))
                .andExpect(status().isForbidden());
        mvc.perform(get(base).secure(true)).andExpect(status().isUnauthorized());
        var created = mvc.perform(write(post(base), session, csrf, token, create))
                .andExpect(status().isCreated()).andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(created.getResponse().getContentAsString()).doesNotContain("첫 힌트");
        mvc.perform(get(base).secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("첫 힌트", "level", "body"));
        mvc.perform(get(base + "/A").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> assertThat(mapper.readTree(r.getResponse().getContentAsString())
                        .path("item").path("body").asText()).isEqualTo("첫 힌트"));
        mvc.perform(write(post(base), session, csrf, token,
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"level\":1}}"))
                .andExpect(status().isConflict())
                .andDo(r -> assertThat(mapper.readTree(r.getResponse().getContentAsString())
                        .path("code").asText()).isEqualTo("SLOT_CONFLICT"));
        mvc.perform(write(post(base), session, csrf, token,
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"level\":1.0}}"))
                .andExpect(status().isUnprocessableEntity());
        String other = "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"level\":2}}";
        byte[] utf16 = ("\uFEFF" + other).getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        mvc.perform(write(post(base), session, csrf, token, "{}")
                .contentType("application/json;charset=utf-8").content(utf16))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base), session, csrf, token,
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"level\":2,\"body\":\""
                        + "가".repeat(180000) + "\"}}"))
                .andExpect(status().isPayloadTooLarge());
        assertThat(versionRev(story)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM story_hint h JOIN story_version v ON v.id=h.version_id "
                + "WHERE v.story_id=? AND h.code='B'", Long.class, storyId(story))).isZero();
        String maxBody = other + " ".repeat(512 * 1024 - other.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        mvc.perform(write(post(base), session, csrf, token, maxBody)).andExpect(status().isCreated());
        mvc.perform(write(post(base), session, csrf, token, maxBody + " "))
                .andExpect(status().isPayloadTooLarge());
        String state = "{\"expectedRev\":\"2\"}";
        mvc.perform(write(post(base + "/B/deactivate"), session, csrf, token, state)).andExpect(status().isOk());
        String padded = "{\"expectedRev\":\"3\"}" + " ".repeat(8192 - "{\"expectedRev\":\"3\"}".length());
        mvc.perform(write(post(base + "/B/deactivate"), session, csrf, token, padded)).andExpect(status().isOk());
        mvc.perform(write(post(base + "/B/deactivate"), session, csrf, token, padded + " "))
                .andExpect(status().isPayloadTooLarge());
        for (String suffix : java.util.List.of("hints", "hints/{itemKey}", "hints/{itemKey}/deactivate")) {
            assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=?",
                    Long.class, owner.principal().accountKey(),
                    "/admin/api/stories/{storyCode}/versions/{versionNo}/" + suffix)).isPositive();
        }
    }

    /** 합성 일반 세션을 제품 어댑터의 보안 쿠키로 변환한다. */
    private Cookie cookie(Fixture actor) {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        sessions.issueCookie(actor.sid(), new org.springframework.mock.web.MockHttpServletRequest(), response);
        return response.getCookie("__Host-admin-session");
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder,
            Cookie session, Cookie csrf, String token, String payload) {
        return builder.secure(true).with(request -> { request.setScheme("https"); request.setServerPort(443); return request; })
                .header("Origin", "https://localhost").header("X-CSRF-TOKEN", token)
                .cookie(session, csrf).contentType("application/json").content(payload);
    }

    @Test
    void creationIsAnIntentAndRequiresCurrentCreateAuthority() {
        Fixture owner = account(true, false, (byte) 1);
        Fixture stranger = account(false, false, (byte) 2);
        Fixture otherCreator = account(true, false, (byte) 8);
        UUID key = UUID.randomUUID();
        JsonNode created = json(stories.createStory(owner.sid(), owner.principal(), key, "  시작 제목  ", UUID.randomUUID()));
        String code = "ST_" + key.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        assertThat(created.path("storyCode").asText()).isEqualTo(code);
        assertThat(created.path("storyRev").asText()).isEqualTo("0");
        assertThat(created.path("editRev").asText()).isEqualTo("0");
        assertThat(created.path("versionNo").asInt()).isEqualTo(1);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(db.queryForObject("SELECT count(*) FROM story WHERE code=? AND owner_id=? AND active_yn "
                + "AND NOT view_yn AND published_id IS NULL AND edit_rev=0", Integer.class, code,
                owner.principal().accountId())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT title FROM story_version WHERE story_id=(SELECT id FROM story WHERE code=?)",
                String.class, code)).isEqualTo("  시작 제목  ");
        assertThat(db.queryForObject("SELECT count(*) FROM story_version v JOIN story s ON s.id=v.story_id "
                + "WHERE s.code=? AND v.version_no=1 AND v.edit_rev=0 AND v.status='DRAFT' "
                + "AND v.active_yn AND v.policy_code='RULE_20260924' AND v.intro IS NULL "
                + "AND v.setting IS NULL AND v.difficulty IS NULL AND v.culprit_code IS NULL "
                + "AND v.current_snapshot_id IS NULL", Integer.class, code)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM story_access WHERE story_id=(SELECT id FROM story WHERE code=?)",
                Integer.class, code)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=(SELECT id FROM story WHERE code=?) "
                + "AND action='STORY_CREATED'", Integer.class, code)).isEqualTo(1);
        denied("CREATE_CONFLICT", () -> stories.createStory(owner.sid(), owner.principal(), key,
                "replace", UUID.randomUUID()));
        denied("CREATE_CONFLICT", () -> stories.createStory(otherCreator.sid(), otherCreator.principal(), key,
                "secret", UUID.randomUUID()));
        denied("FORBIDDEN", () -> stories.createStory(stranger.sid(), stranger.principal(), UUID.randomUUID(),
                "Denied", UUID.randomUUID()));
        assertThat(db.queryForObject("SELECT title FROM story_version WHERE story_id=(SELECT id FROM story WHERE code=?)",
                String.class, code)).isEqualTo("  시작 제목  ");
    }

    @Test
    void listingFiltersBeforeKeysetAndDetailRequiresAnAuditedContentPermission() {
        Fixture owner = account(true, false, (byte) 3);
        Fixture editor = account(false, false, (byte) 4);
        Fixture manager = account(false, true, (byte) 5);
        String first = create(owner, "First");
        String hidden = create(owner, "Hidden");
        String last = create(owner, "Last");
        long firstId = storyId(first);
        long lastId = storyId(last);
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                firstId, editor.principal().accountId(), owner.principal().accountId());
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                lastId, editor.principal().accountId(), owner.principal().accountId());
        JsonNode page = json(stories.getStoryList(editor.sid(), editor.principal(), 1, null, null, null));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("storyCode").asText()).isEqualTo(last);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("nextAfterId").asText()).isEqualTo(Long.toString(lastId));
        JsonNode next = json(stories.getStoryList(editor.sid(), editor.principal(), 1,
                page.path("nextAfterId").asText(), null, null));
        assertThat(next.path("items")).hasSize(1);
        assertThat(next.path("items").get(0).path("storyCode").asText()).isEqualTo(first);
        assertThat(next.path("hasNext").asBoolean()).isFalse();
        assertThat(json(stories.getStoryList(editor.sid(), editor.principal(), 20, null, hidden, null))
                .path("items")).isEmpty();
        assertThat(json(stories.getStoryList(manager.sid(), manager.principal(), 20, null, null, null))
                .path("items")).isEmpty();
        denied("NOT_FOUND", () -> stories.getStoryDetail(editor.sid(), editor.principal(), hidden, 1, UUID.randomUUID()));
        denied("NOT_FOUND", () -> stories.getStoryDetail(manager.sid(), manager.principal(), first, 1, UUID.randomUUID()));
        UUID readRequest = UUID.randomUUID();
        JsonNode detail = json(stories.getStoryDetail(editor.sid(), editor.principal(), first, 1, readRequest));
        assertThat(detail.path("sections").path("basic").path("title").asText()).isEqualTo("First");
        assertThat(json(stories.getStoryDetail(owner.sid(), owner.principal(), first, 1, UUID.randomUUID()))
                .path("storyCode").asText()).isEqualTo(first);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? AND action='CONTENT_READ'",
                Integer.class, firstId)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? AND action='CONTENT_READ' "
                + "AND detail->>'requestId'=?", Integer.class, firstId, readRequest.toString())).isEqualTo(1);
        db.execute("CREATE FUNCTION fail_story_read_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.action='CONTENT_READ' THEN RAISE EXCEPTION 'synthetic audit outage'; END IF; "
                + "RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_story_read BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_story_read_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> stories.getStoryDetail(owner.sid(), owner.principal(), first, 1, UUID.randomUUID()));
        } finally {
            db.execute("DROP TRIGGER fail_story_read ON story_audit");
            db.execute("DROP FUNCTION fail_story_read_audit()");
        }
    }

    @Test
    void sectionPatchDistinguishesMissingAndNullAndNeverCommitsAFailedAudit() throws Exception {
        Fixture owner = account(true, false, (byte) 6);
        Fixture editor = account(false, false, (byte) 7);
        String code = create(owner, "Original");
        db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)",
                storyId(code), editor.principal().accountId(), owner.principal().accountId());
        JsonNode changed = patch(editor, code, "basic", "0", "{\"intro\":\"One\\r\\nTwo\",\"setting\":\"Place\"}");
        assertThat(changed.path("changed").asBoolean()).isTrue();
        assertThat(changed.path("editRev").asText()).isEqualTo("1");
        JsonNode basic = json(stories.getStoryDetail(owner.sid(), owner.principal(), code, 1, UUID.randomUUID()))
                .path("sections").path("basic");
        assertThat(basic.path("title").asText()).isEqualTo("Original");
        assertThat(basic.path("intro").asText()).isEqualTo("One\nTwo");
        assertThat(basic.path("setting").asText()).isEqualTo("Place");
        long audited = auditCount(code, "SECTION_UPDATED");
        JsonNode same = patch(editor, code, "basic", "1", "{\"intro\":\"One\\nTwo\"}");
        assertThat(same.path("changed").asBoolean()).isFalse();
        assertThat(same.path("editRev").asText()).isEqualTo("1");
        assertThat(auditCount(code, "SECTION_UPDATED")).isEqualTo(audited);
        denied("EDIT_CONFLICT", () -> patch(editor, code, "basic", "0", "{\"intro\":\"One\\nTwo\"}"));
        JsonNode cleared = patch(editor, code, "basic", "1", "{\"intro\":null}");
        assertThat(cleared.path("editRev").asText()).isEqualTo("2");
        assertThat(json(stories.getStoryDetail(owner.sid(), owner.principal(), code, 1, UUID.randomUUID()))
                .path("sections").path("basic").path("intro").isNull()).isTrue();
        denied("INVALID_INPUT", () -> patch(editor, code, "basic", "2", "{\"title\":null}"));
        denied("INVALID_REQUEST", () -> patch(editor, code, "basic", "2", "{\"ownerId\":1}"));
        denied("INVALID_INPUT", () -> patch(editor, code, "basic", "2", "{\"title\":\"\\uD800\"}"));
        denied("INVALID_INPUT", () -> patch(editor, code, "basic", "2",
                "{\"title\":\"" + "x".repeat(161) + "\"}"));
        denied("INVALID_INPUT", () -> patch(editor, code, "answer", "2", "{\"culpritCode\":\"MISSING\"}"));
        assertThat(versionRev(code)).isEqualTo(2);
        db.execute("CREATE FUNCTION fail_story_write_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.action='SECTION_UPDATED' THEN RAISE EXCEPTION 'synthetic audit outage'; END IF; "
                + "RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_story_write BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_story_write_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> patch(owner, code, "reveal", "2", "{\"revealText\":\"Secret\"}"));
        } finally {
            db.execute("DROP TRIGGER fail_story_write ON story_audit");
            db.execute("DROP FUNCTION fail_story_write_audit()");
        }
        assertThat(versionRev(code)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT reveal_text FROM story_version WHERE story_id=?", String.class,
                storyId(code))).isNull();
        assertThat(auditCount(code, "SECTION_UPDATED")).isEqualTo(audited + 1);
    }

    private Fixture account(boolean create, boolean manage, byte seed) {
        UUID key = UUID.randomUUID();
        long id = db.queryForObject("INSERT INTO admin_account(account_key,can_create,can_manage) "
                + "VALUES (?,?,?) RETURNING id", Long.class, key, create, manage);
        byte[] loginHash = new byte[32];
        Arrays.fill(loginHash, seed);
        Instant now = db.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
        db.update("INSERT INTO admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,"
                + "mfa_verified_at,last_step,enrolled_at,mfa_state) VALUES (?,?,?,?,?,?,?,?, 'READY')",
                id, "synthetic-cipher", loginHash, "synthetic-hash", "synthetic-mfa", now, 0L, now);
        var prepared = sessions.prepare();
        var principal = new AdminPrincipal(id, key, UUID.randomUUID(), 1);
        sessions.save(prepared, principal);
        db.update("INSERT INTO admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,"
                + "expires_at,reauth_at,activated_at) VALUES (?,?,?,1,'ACTIVE',?,?,?::timestamptz + interval '8 hours',?,?)",
                principal.sessionKey(), id, crypto.sessionHash(prepared.id()), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return new Fixture(prepared.id(), principal);
    }

    private String create(Fixture owner, String title) {
        return json(stories.createStory(owner.sid(), owner.principal(), UUID.randomUUID(), title, UUID.randomUUID()))
                .path("storyCode").asText();
    }

    private JsonNode patch(Fixture actor, String code, String section, String rev, String changes) {
        try {
            return json(stories.updateStorySection(actor.sid(), actor.principal(), code, 1, section, rev,
                    mapper.readTree(changes), UUID.randomUUID()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private long storyId(String code) {
        return db.queryForObject("SELECT id FROM story WHERE code=?", Long.class, code);
    }

    private long versionRev(String code) {
        return db.queryForObject("SELECT v.edit_rev FROM story_version v JOIN story s ON s.id=v.story_id "
                + "WHERE s.code=?", Long.class, code);
    }

    private long auditCount(String code, String action) {
        return db.queryForObject("SELECT count(*) FROM story_audit a JOIN story s ON s.id=a.story_id "
                + "WHERE s.code=? AND a.action=?", Long.class, code, action);
    }

    private static void denied(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(AuthException.class).extracting("code").isEqualTo(code);
    }

    private record Fixture(String sid, AdminPrincipal principal) {}
}
