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
import com.reasoning.common.story.service.StoryEventService;
import com.reasoning.common.story.service.StoryFactService;
import com.reasoning.common.story.service.StoryRubricService;
import com.reasoning.common.story.service.StoryRubricClueService;
import com.reasoning.common.story.service.StoryGradeSampleService;
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
    @Autowired StoryEventService events;
    @Autowired StoryFactService facts;
    @Autowired StoryRubricService rubrics;
    @Autowired StoryRubricClueService rubricClues;
    @Autowired StoryGradeSampleService gradeSamples;
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

    /** 시간선의 nullable 시각·원고, 코드포인트 경계 및 병합 PATCH를 검사한다. */
    @Test
    void eventsNullableFieldsMinuteBoundsAndMergedPatch() {
        Fixture owner = account(true, false, (byte) 114);
        String story = create(owner, "시간선 필드");
        var created = events.createEvent(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A"), UUID.randomUUID());
        assertThat(created.editRev()).isEqualTo("1");
        assertThat(created.warnings()).isNotEmpty();
        var empty = events.getEventDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item();
        assertThat(empty.startMin()).isNull();
        assertThat(empty.endMin()).isNull();
        assertThat(empty.actualText()).isNull();
        assertThat(empty.apparentText()).isNull();
        String manuscript = "😀".repeat(8000);
        assertThat(events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("startMin", 0).put("endMin", Integer.MAX_VALUE)
                        .put("actualText", manuscript).put("apparentText", "첫째\r\n둘째"), UUID.randomUUID())
                .editRev()).isEqualTo("2");
        var detail = events.getEventDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item();
        assertThat(detail.startMin()).isZero();
        assertThat(detail.endMin()).isEqualTo(Integer.MAX_VALUE);
        assertThat(detail.actualText()).isEqualTo(manuscript);
        assertThat(detail.apparentText()).isEqualTo("첫째\n둘째");
        denied("INVALID_INPUT", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().put("startMin", 1).put("endMin", 0), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().putNull("startMin"), UUID.randomUUID()));
        assertThat(events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "2",
                mapper.createObjectNode().putNull("startMin").putNull("endMin").put("actualText", "")
                        .putNull("apparentText"), UUID.randomUUID()).editRev()).isEqualTo("3");
        var cleared = events.getEventDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item();
        assertThat(cleared.startMin()).isNull();
        assertThat(cleared.endMin()).isNull();
        assertThat(cleared.actualText()).isEmpty();
        assertThat(cleared.apparentText()).isNull();
        denied("INVALID_INPUT", () -> events.createEvent(owner.sid(), owner.principal(), story, 1, "3",
                mapper.createObjectNode().put("code", "END").put("endMin", 0), UUID.randomUUID()));
        for (var invalid : java.util.List.of(mapper.createObjectNode().put("startMin", -1),
                mapper.createObjectNode().put("startMin", 1.5),
                mapper.createObjectNode().put("startMin", 2147483648L),
                mapper.createObjectNode().put("actualText", "😀".repeat(8001)))) {
            denied("INVALID_INPUT", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "3",
                    invalid, UUID.randomUUID()));
        }
        denied("INVALID_INPUT", () -> events.createEvent(owner.sid(), owner.principal(), story, 1, "3",
                mapper.createObjectNode().put("code", "REVERSED").put("startMin", 2).put("endMin", 1), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "3",
                mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "3",
                mapper.createObjectNode(), UUID.randomUUID()));
        assertThat(versionRev(story)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT detail::text FROM story_audit WHERE story_id=? AND action='ITEM_UPDATED' "
                + "ORDER BY id DESC LIMIT 1", String.class, storyId(story))).doesNotContain("첫째", "😀");
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? "
                + "AND (detail::text LIKE '%첫째%' OR detail::text LIKE '%둘째%')",
                Long.class, storyId(story))).isZero();
    }

    /** 목록은 원고 없는 ASCII 키셋이며 비활성 키도 예약된다. */
    @Test
    void eventsAsciiPaginationAndInactiveReservedCode() {
        Fixture owner = account(true, false, (byte) 115);
        String story = create(owner, "시간선 목록");
        for (int i = 0; i < 21; i++) {
            String key = String.format("E%02d", i);
            events.createEvent(owner.sid(), owner.principal(), story, 1, Integer.toString(i),
                    mapper.createObjectNode().put("code", key).put("actualText", "비공개 원고"), UUID.randomUUID());
        }
        var first = events.getEventList(owner.sid(), owner.principal(), story, 1, null, null, null);
        assertThat(first.items()).hasSize(20);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.nextAfterKey()).isEqualTo("E19");
        assertThat(json(first).toString()).doesNotContain("비공개 원고", "actualText", "startMin");
        assertThat(events.getEventList(owner.sid(), owner.principal(), story, 1, 1, first.nextAfterKey(), null)
                .items().get(0).code()).isEqualTo("E20");
        assertThat(events.getEventList(owner.sid(), owner.principal(), story, 1, 100, null, null).items()).hasSize(21);
        for (int size : new int[] {0, 101}) {
            denied("INVALID_REQUEST", () -> events.getEventList(owner.sid(), owner.principal(), story, 1,
                    size, null, null));
        }
        denied("INVALID_REQUEST", () -> events.getEventList(owner.sid(), owner.principal(), story, 1,
                1, "lower", null));
        var deactivated = events.updateEventActive(owner.sid(), owner.principal(), story, 1, "E00", "21", false,
                UUID.randomUUID());
        assertThat(deactivated.editRev()).isEqualTo("22");
        assertThat(events.getEventList(owner.sid(), owner.principal(), story, 1, null, null, false)
                .items().get(0).code()).isEqualTo("E00");
        assertThat(events.getEventList(owner.sid(), owner.principal(), story, 1, null, null, true).items()).hasSize(20);
        denied("ITEM_EXISTS", () -> events.createEvent(owner.sid(), owner.principal(), story, 1, "22",
                mapper.createObjectNode().put("code", "E00"), UUID.randomUUID()));
        denied("STATE_CONFLICT", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1, "E00", "22",
                mapper.createObjectNode().put("actualText", "변경"), UUID.randomUUID()));
    }

    /** 실제 상태 변경만 시각·수정번호·감사를 갱신하고 stale 무변경도 거절한다. */
    @Test
    void eventsToggleNoopAndStaleRevision() {
        Fixture owner = account(true, false, (byte) 116);
        String story = create(owner, "시간선 상태");
        events.createEvent(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A"), UUID.randomUUID());
        Instant initial = eventUpdatedAt(story, "A");
        long audit = auditCount(story, "ITEM_UPDATED");
        var same = events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().putNull("actualText"), UUID.randomUUID());
        assertThat(same.changed()).isFalse();
        assertThat(same.editRev()).isEqualTo("1");
        assertThat(eventUpdatedAt(story, "A")).isEqualTo(initial);
        assertThat(auditCount(story, "ITEM_UPDATED")).isEqualTo(audit);
        denied("EDIT_CONFLICT", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1, "A", "0",
                mapper.createObjectNode().putNull("actualText"), UUID.randomUUID()));
        assertThat(events.updateEventActive(owner.sid(), owner.principal(), story, 1, "A", "1", false,
                UUID.randomUUID()).editRev()).isEqualTo("2");
        Instant inactive = eventUpdatedAt(story, "A");
        long offAudit = auditCount(story, "ITEM_DEACTIVATED");
        assertThat(events.updateEventActive(owner.sid(), owner.principal(), story, 1, "A", "2", false,
                UUID.randomUUID()).changed()).isFalse();
        assertThat(eventUpdatedAt(story, "A")).isEqualTo(inactive);
        assertThat(auditCount(story, "ITEM_DEACTIVATED")).isEqualTo(offAudit);
        denied("EDIT_CONFLICT", () -> events.updateEventActive(owner.sid(), owner.principal(), story, 1,
                "A", "1", false, UUID.randomUUID()));
        assertThat(events.updateEventActive(owner.sid(), owner.principal(), story, 1, "A", "2", true,
                UUID.randomUUID()).editRev()).isEqualTo("3");
        Instant restored = eventUpdatedAt(story, "A");
        assertThat(events.updateEventActive(owner.sid(), owner.principal(), story, 1, "A", "3", true,
                UUID.randomUUID()).changed()).isFalse();
        assertThat(eventUpdatedAt(story, "A")).isEqualTo(restored);
        assertThat(auditCount(story, "ITEM_REACTIVATED")).isEqualTo(1);
        assertThat(versionRev(story)).isEqualTo(3);
    }

    /** 전역 자격과 사건 관계를 모두 재검사하며 비활성 부모는 소유자에게만 보인다. */
    @Test
    void eventsPermissionsRevocationAndParentState() {
        Fixture owner = account(true, false, (byte) 117);
        Fixture editor = account(false, false, (byte) 118);
        Fixture reviewer = account(false, false, (byte) 119);
        Fixture publisher = account(false, false, (byte) 120);
        Fixture outsider = account(false, true, (byte) 121);
        String story = create(owner, "시간선 권한");
        events.createEvent(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A").put("actualText", "원고"), UUID.randomUUID());
        db.update("UPDATE admin_account SET can_review=true WHERE id=?", reviewer.principal().accountId());
        db.update("UPDATE admin_account SET can_publish=true WHERE id=?", publisher.principal().accountId());
        for (var relation : java.util.List.of(java.util.Map.entry(editor, "EDIT"),
                java.util.Map.entry(reviewer, "REVIEW"), java.util.Map.entry(publisher, "PUBLISH"))) {
            db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,?,?)",
                    storyId(story), relation.getKey().principal().accountId(), relation.getValue(),
                    owner.principal().accountId());
            assertThat(events.getEventDetail(relation.getKey().sid(), relation.getKey().principal(), story, 1,
                    "A", UUID.randomUUID()).item().actualText()).isEqualTo("원고");
        }
        denied("NOT_FOUND", () -> events.getEventDetail(outsider.sid(), outsider.principal(), story, 1,
                "A", UUID.randomUUID()));
        denied("NOT_FOUND", () -> events.getEventList(outsider.sid(), outsider.principal(), story, 1,
                null, null, null));
        for (Fixture reader : java.util.List.of(reviewer, publisher)) {
            denied("FORBIDDEN", () -> events.createEvent(reader.sid(), reader.principal(), story, 1, "1",
                    mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
            denied("FORBIDDEN", () -> events.updateEventActive(reader.sid(), reader.principal(), story, 1,
                    "A", "1", false, UUID.randomUUID()));
        }
        assertThat(events.updateEvent(editor.sid(), editor.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("actualText", "편집"), UUID.randomUUID()).editRev()).isEqualTo("2");
        db.update("UPDATE admin_account SET can_review=false WHERE id=?", reviewer.principal().accountId());
        denied("NOT_FOUND", () -> events.getEventDetail(reviewer.sid(), reviewer.principal(), story, 1,
                "A", UUID.randomUUID()));
        db.update("UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                storyId(story), editor.principal().accountId());
        denied("NOT_FOUND", () -> events.getEventList(editor.sid(), editor.principal(), story, 1,
                null, null, null));
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(story));
        denied("NOT_FOUND", () -> events.getEventDetail(publisher.sid(), publisher.principal(), story, 1,
                "A", UUID.randomUUID()));
        assertThat(events.getEventDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                .item().actualText()).isEqualTo("편집");
        denied("STATE_CONFLICT", () -> events.updateEventActive(owner.sid(), owner.principal(), story, 1,
                "A", "2", false, UUID.randomUUID()));
        long version = db.queryForObject("SELECT id FROM story_version WHERE story_id=?", Long.class, storyId(story));
        long snapshot = db.queryForObject("INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by) "
                + "VALUES (?,2,'{}'::jsonb,?,?) RETURNING id", Long.class,
                version, UUID.randomUUID(), owner.principal().accountId());
        db.update("UPDATE story_version SET active_yn=true,status='REVIEW',current_snapshot_id=? WHERE id=?", snapshot, version);
        denied("STATE_CONFLICT", () -> events.createEvent(owner.sid(), owner.principal(), story, 1, "2",
                mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
        denied("STATE_CONFLICT", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1,
                "A", "2", mapper.createObjectNode().put("actualText", "거절"), UUID.randomUUID()));
        denied("STATE_CONFLICT", () -> events.updateEventActive(owner.sid(), owner.principal(), story, 1,
                "A", "2", false, UUID.randomUUID()));
        db.update("UPDATE story_version SET status='DRAFT',current_snapshot_id=null WHERE story_id=?", storyId(story));
        db.update("UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?", owner.principal().accountId());
        denied("AUTH_REQUIRED", () -> events.getEventDetail(owner.sid(), owner.principal(), story, 1,
                "A", UUID.randomUUID()));
    }

    /** 필수 감사 장애 시 조회와 생성·수정·상태 변경을 모두 실패시키고 자료를 롤백한다. */
    @Test
    void eventsMandatoryAuditFailureRollsBackEveryWrite() {
        Fixture owner = account(true, false, (byte) 122);
        String story = create(owner, "시간선 감사");
        events.createEvent(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A").put("actualText", "원본"), UUID.randomUUID());
        Instant original = eventUpdatedAt(story, "A");
        db.execute("CREATE FUNCTION fail_event_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.detail->>'resource'='events' THEN RAISE EXCEPTION 'synthetic event audit outage'; "
                + "END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_event_audit_insert BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_event_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> events.getEventDetail(owner.sid(), owner.principal(), story, 1,
                    "A", UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> events.createEvent(owner.sid(), owner.principal(), story, 1, "1",
                    mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> events.updateEvent(owner.sid(), owner.principal(), story, 1,
                    "A", "1", mapper.createObjectNode().put("actualText", "실패 원고"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> events.updateEventActive(owner.sid(), owner.principal(), story, 1,
                    "A", "1", false, UUID.randomUUID()));
            assertThat(versionRev(story)).isEqualTo(1);
            assertThat(eventUpdatedAt(story, "A")).isEqualTo(original);
            assertThat(db.queryForObject("SELECT count(*) FROM story_event e JOIN story_version v ON v.id=e.version_id "
                    + "WHERE v.story_id=? AND e.code='B'", Long.class, storyId(story))).isZero();
        } finally {
            db.execute("DROP TRIGGER fail_event_audit_insert ON story_audit");
            db.execute("DROP FUNCTION fail_event_audit()");
        }
        assertThat(events.getEventDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                .item().actualText()).isEqualTo("원본");
        assertThat(events.updateEventActive(owner.sid(), owner.principal(), story, 1, "A", "1", false,
                UUID.randomUUID()).editRev()).isEqualTo("2");
        assertThat(auditCount(story, "ITEM_DEACTIVATED")).isEqualTo(1);
    }

    /** HTTP 고정 경로·엄격 JSON·두 바이트 상한과 정규화된 접근 기록을 검사한다. */
    @Test
    void eventsHttpFixedRoutesStrictBodyAndAccessHistory() throws Exception {
        Fixture owner = account(true, false, (byte) 123);
        String story = create(owner, "HTTP 시간선");
        String base = "/admin/api/stories/" + story + "/versions/1/events";
        var csrfResponse = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrfResponse.getContentAsString()).path("token").asText();
        Cookie session = cookie(owner);
        String first = "{\"expectedRev\":\"0\",\"item\":{\"code\":\"A\",\"actualText\":\"비밀 원고\"}}";
        mvc.perform(post(base).secure(true).cookie(session).contentType("application/json").content(first))
                .andExpect(status().isForbidden());
        mvc.perform(get(base).secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(write(post(base), session, csrf, token, first)).andExpect(status().isCreated())
                .andDo(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("비밀 원고"));
        mvc.perform(get(base + "?size=1").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("비밀 원고"));
        mvc.perform(get(base + "/A").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> assertThat(mapper.readTree(r.getResponse().getContentAsString())
                        .path("item").path("actualText").asText()).isEqualTo("비밀 원고"));
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "/A"), session, csrf, token,
                "{\"expectedRev\":\"1\",\"changes\":{\"startMin\":0,\"endMin\":1}}"))
                .andExpect(status().isOk());
        mvc.perform(write(post(base + "/A/deactivate"), session, csrf, token, "{\"expectedRev\":\"2\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(post(base + "/A/reactivate"), session, csrf, token, "{\"expectedRev\":\"3\"}"))
                .andExpect(status().isOk());
        String second = "{\"expectedRev\":\"4\",\"item\":{\"code\":\"B\"}}";
        byte[] utf16 = ("\uFEFF" + second).getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        mvc.perform(write(post(base), session, csrf, token, "{}")
                .contentType("application/json;charset=utf-8").content(utf16)).andExpect(status().isBadRequest());
        mvc.perform(write(post(base), session, csrf, token, "{}")
                .content(new byte[] {'{', '"', (byte) 0xc3, (byte) 0x28, '"', '}'}))
                .andExpect(status().isBadRequest());
        for (String invalid : java.util.List.of(
                "{\"expectedRev\":\"4\",\"expectedRev\":\"4\",\"item\":{\"code\":\"B\"}}",
                "{\"expectedRev\":\"4\",\"item\":{\"code\":\"B\",\"code\":\"C\"}}",
                "{\"expectedRev\":\"4\",\"item\":{\"code\":\"B\",\"extra\":1}}",
                "{\"expectedRev\":\"4\",\"unknown\":1,\"item\":{\"code\":\"B\"}}")) {
            mvc.perform(write(post(base), session, csrf, token, invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "/A"), session, csrf, token,
                "{\"expectedRev\":\"4\",\"changes\":{\"unknown\":1}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base + "/A/deactivate"), session, csrf, token,
                "{\"expectedRev\":\"4\",\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base), session, csrf, token, second + " ".repeat(512 * 1024 - second.length())))
                .andExpect(status().isCreated());
        mvc.perform(write(post(base), session, csrf, token, second + " ".repeat(512 * 1024 - second.length() + 1)))
                .andExpect(status().isPayloadTooLarge());
        String state = "{\"expectedRev\":\"5\"}";
        mvc.perform(write(post(base + "/B/deactivate"), session, csrf, token,
                state + " ".repeat(8192 - state.length()))).andExpect(status().isOk());
        mvc.perform(write(post(base + "/B/reactivate"), session, csrf, token,
                "{\"expectedRev\":\"6\"}" + " ".repeat(8192))).andExpect(status().isPayloadTooLarge());
        assertThat(versionRev(story)).isEqualTo(6);
        for (String suffix : java.util.List.of("events", "events/{itemKey}",
                "events/{itemKey}/deactivate", "events/{itemKey}/reactivate")) {
            assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=?",
                    Long.class, owner.principal().accountKey(),
                    "/admin/api/stories/{storyCode}/versions/{versionNo}/" + suffix)).isPositive();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND "
                + "(route LIKE ? OR route LIKE '%비밀%' OR route LIKE '%afterKey%')",
                Long.class, owner.principal().accountKey(), "%" + story + "%")).isZero();
    }

    /** 원본 행 시각을 조회해 무변경 저장의 부작용을 구별한다. */
    private Instant eventUpdatedAt(String story, String key) {
        return db.queryForObject("SELECT e.updated_at FROM story_event e JOIN story_version v ON v.id=e.version_id "
                + "WHERE v.story_id=? AND e.code=?", (rs, row) -> rs.getTimestamp(1).toInstant(), storyId(story), key);
    }

    /** 사실의 nullable 원고·분류 네 상태와 코드포인트/Unicode 경계를 확인한다. */
    @Test
    void factsNullableTruthsTextBoundsAndStrictFields() {
        Fixture owner = account(true, false, (byte) 124);
        String story = create(owner, "사실 원장");
        var created = facts.createFact(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A"), UUID.randomUUID());
        assertThat(created.editRev()).isEqualTo("1");
        assertThat(created.warnings()).isNotEmpty();
        var empty = facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item();
        assertThat(empty.statement()).isNull();
        assertThat(empty.truth()).isNull();
        assertThat(empty.basis()).isNull();
        String statement = "𐐀".repeat(4000);
        String basis = "𐐀".repeat(8000);
        assertThat(facts.updateFact(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("statement", statement).put("truth", "TRUE")
                        .put("basis", basis), UUID.randomUUID()).editRev()).isEqualTo("2");
        var detail = facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item();
        assertThat(detail.statement()).isEqualTo(statement);
        assertThat(detail.truth()).isEqualTo("TRUE");
        assertThat(detail.basis()).isEqualTo(basis);
        for (String truth : java.util.List.of("FALSE", "MISREAD")) {
            facts.updateFact(owner.sid(), owner.principal(), story, 1, "A", Long.toString(versionRev(story)),
                    mapper.createObjectNode().put("truth", truth), UUID.randomUUID());
            assertThat(facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                    .item().truth()).isEqualTo(truth);
        }
        assertThat(facts.updateFact(owner.sid(), owner.principal(), story, 1, "A", "4",
                mapper.createObjectNode().put("statement", "첫째\r\n둘째").put("basis", "")
                        .putNull("truth"), UUID.randomUUID()).editRev()).isEqualTo("5");
        detail = facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID()).item();
        assertThat(detail.statement()).isEqualTo("첫째\n둘째");
        assertThat(detail.basis()).isEmpty();
        assertThat(detail.truth()).isNull();
        assertThat(facts.updateFact(owner.sid(), owner.principal(), story, 1, "A", "5",
                mapper.createObjectNode().put("basis", "근거만"), UUID.randomUUID()).editRev()).isEqualTo("6");
        assertThat(facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                .item().statement()).isEqualTo("첫째\n둘째");
        for (var bad : java.util.List.of(mapper.createObjectNode().put("truth", true),
                mapper.createObjectNode().put("truth", "true"),
                mapper.createObjectNode().put("truth", ""),
                mapper.createObjectNode().put("truth", "UNKNOWN"),
                mapper.createObjectNode().put("statement", "𐐀".repeat(4001)),
                mapper.createObjectNode().put("basis", "𐐀".repeat(8001)),
                mapper.createObjectNode().put("statement", "\uD800"),
                mapper.createObjectNode().put("basis", "\0"),
                mapper.createObjectNode().put("basis", false))) {
            denied("INVALID_INPUT", () -> facts.updateFact(owner.sid(), owner.principal(), story, 1,
                    "A", "6", bad, UUID.randomUUID()));
        }
        denied("INVALID_REQUEST", () -> facts.updateFact(owner.sid(), owner.principal(), story, 1,
                "A", "6", mapper.createObjectNode(), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> facts.updateFact(owner.sid(), owner.principal(), story, 1,
                "A", "6", mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> facts.createFact(owner.sid(), owner.principal(), story, 1, "6",
                mapper.createObjectNode().put("code", "lower"), UUID.randomUUID()));
        denied("INVALID_REQUEST", () -> facts.createFact(owner.sid(), owner.principal(), story, 1, "6",
                mapper.createObjectNode().put("code", "B").put("activeYn", false), UUID.randomUUID()));
        assertThat(versionRev(story)).isEqualTo(6);
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? "
                + "AND (detail::text LIKE '%근거만%' OR detail::text LIKE '%첫째%' OR detail::text LIKE '%𐐀%')",
                Long.class, storyId(story))).isZero();
    }

    /** 원고 없는 ASCII 목록의 기본 크기와 비활성 코드 예약·복원을 확인한다. */
    @Test
    void factsAsciiPaginationAndInactiveReservation() {
        Fixture owner = account(true, false, (byte) 125);
        String story = create(owner, "사실 목록");
        for (int i = 0; i < 21; i++) {
            facts.createFact(owner.sid(), owner.principal(), story, 1, Integer.toString(i),
                    mapper.createObjectNode().put("code", String.format("F%02d", i))
                            .put("statement", "비밀 명제").put("basis", "없는 단서"), UUID.randomUUID());
        }
        var page = facts.getFactList(owner.sid(), owner.principal(), story, 1, null, null, null);
        assertThat(page.items()).hasSize(20);
        assertThat(page.nextAfterKey()).isEqualTo("F19");
        assertThat(page.hasNext()).isTrue();
        assertThat(json(page).toString()).doesNotContain("비밀 명제", "없는 단서", "statement", "truth", "basis");
        assertThat(facts.getFactList(owner.sid(), owner.principal(), story, 1, 1, "F19", true).items())
                .extracting(StoryFactService.FactKey::code).containsExactly("F20");
        assertThat(facts.getFactList(owner.sid(), owner.principal(), story, 1, 100, null, true).items()).hasSize(21);
        for (int size : new int[] {0, 101})
            denied("INVALID_REQUEST", () -> facts.getFactList(owner.sid(), owner.principal(), story, 1,
                    size, null, null));
        denied("INVALID_REQUEST", () -> facts.getFactList(owner.sid(), owner.principal(), story, 1,
                1, "lower", null));
        assertThat(facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "F00", "21", false,
                UUID.randomUUID()).editRev()).isEqualTo("22");
        assertThat(facts.getFactList(owner.sid(), owner.principal(), story, 1, null, null, false).items())
                .extracting(StoryFactService.FactKey::code).containsExactly("F00");
        assertThat(facts.getFactList(owner.sid(), owner.principal(), story, 1, null, null, true).items()).hasSize(20);
        denied("ITEM_EXISTS", () -> facts.createFact(owner.sid(), owner.principal(), story, 1, "22",
                mapper.createObjectNode().put("code", "F00"), UUID.randomUUID()));
        denied("STATE_CONFLICT", () -> facts.updateFact(owner.sid(), owner.principal(), story, 1, "F00", "22",
                mapper.createObjectNode().putNull("statement"), UUID.randomUUID()));
        assertThat(facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "F00", "22", true,
                UUID.randomUUID()).editRev()).isEqualTo("23");
    }

    /** 무변경과 오래된 수정번호는 시각·부모 번호·업무 감사를 보존한다. */
    @Test
    void factsNoopTimestampRevisionAndStaleWrites() {
        Fixture owner = account(true, false, (byte) 126);
        String story = create(owner, "사실 무변경");
        facts.createFact(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A"), UUID.randomUUID());
        var before = factState(story, "A");
        assertThat(facts.updateFact(owner.sid(), owner.principal(), story, 1, "A", "1",
                mapper.createObjectNode().putNull("statement"), UUID.randomUUID()).changed()).isFalse();
        assertThat(facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "A", "1", true,
                UUID.randomUUID()).changed()).isFalse();
        assertThat(factState(story, "A")).isEqualTo(before);
        denied("EDIT_CONFLICT", () -> facts.updateFact(owner.sid(), owner.principal(), story, 1, "A", "0",
                mapper.createObjectNode().putNull("statement"), UUID.randomUUID()));
        denied("EDIT_CONFLICT", () -> facts.updateFactActive(owner.sid(), owner.principal(), story, 1,
                "A", "0", true, UUID.randomUUID()));
        assertThat(factState(story, "A")).isEqualTo(before);
        facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "A", "1", false, UUID.randomUUID());
        before = factState(story, "A");
        assertThat(facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "A", "2", false,
                UUID.randomUUID()).changed()).isFalse();
        assertThat(factState(story, "A")).isEqualTo(before);
        denied("EDIT_CONFLICT", () -> facts.updateFactActive(owner.sid(), owner.principal(), story, 1,
                "A", "1", false, UUID.randomUUID()));
        assertThat(factState(story, "A")).isEqualTo(before);
        facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "A", "2", true, UUID.randomUUID());
        before = factState(story, "A");
        assertThat(facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "A", "3", true,
                UUID.randomUUID()).changed()).isFalse();
        assertThat(factState(story, "A")).isEqualTo(before);
    }

    /** 단서 코드는 근거 설명에 쓰인 문서 문자열일 뿐 단서 삭제·복원을 막지 않는다. */
    @Test
    void factsDocumentaryBasisDoesNotCreateClueReference() {
        Fixture owner = account(true, false, (byte) 127);
        String story = create(owner, "근거 설명");
        String basis = "MISSING_CLUE, REAL_CLUE 근거\n연결 설명";
        facts.createFact(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "F").put("basis", basis), UUID.randomUUID());
        clue(owner, story, "REAL_CLUE", 1);
        assertThat(clues.updateClueActive(owner.sid(), owner.principal(), story, 1, "REAL_CLUE", "2", false,
                UUID.randomUUID()).editRev()).isEqualTo("3");
        assertThat(facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "F", UUID.randomUUID())
                .item().basis()).isEqualTo(basis);
        clues.updateClueActive(owner.sid(), owner.principal(), story, 1, "REAL_CLUE", "3", true, UUID.randomUUID());
        assertThat(facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "F", UUID.randomUUID())
                .item().basis()).isEqualTo(basis);
        assertThat(db.queryForObject("SELECT basis FROM story_fact f JOIN story_version v ON v.id=f.version_id "
                + "WHERE v.story_id=? AND f.code='F'", String.class, storyId(story))).isEqualTo(basis);
    }

    /** 관계 및 자격 회수는 즉시 반영하고 비초안은 실제 사본과 결속해 차단한다. */
    @Test
    void factsPermissionsParentStateAndRevocation() {
        Fixture owner = account(true, false, (byte) -128);
        Fixture editor = account(false, false, (byte) -127);
        Fixture reviewer = account(false, false, (byte) -126);
        Fixture publisher = account(false, false, (byte) -125);
        Fixture manager = account(false, true, (byte) -124);
        String story = create(owner, "사실 권한");
        facts.createFact(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A").put("basis", "비공개"), UUID.randomUUID());
        db.update("UPDATE admin_account SET can_review=true WHERE id=?", reviewer.principal().accountId());
        db.update("UPDATE admin_account SET can_publish=true WHERE id=?", publisher.principal().accountId());
        for (var relation : java.util.List.of(java.util.Map.entry(editor, "EDIT"),
                java.util.Map.entry(reviewer, "REVIEW"), java.util.Map.entry(publisher, "PUBLISH"))) {
            db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,?,?)",
                    storyId(story), relation.getKey().principal().accountId(), relation.getValue(),
                    owner.principal().accountId());
            assertThat(facts.getFactDetail(relation.getKey().sid(), relation.getKey().principal(), story, 1,
                    "A", UUID.randomUUID()).item().basis()).isEqualTo("비공개");
        }
        denied("NOT_FOUND", () -> facts.getFactDetail(manager.sid(), manager.principal(), story, 1,
                "A", UUID.randomUUID()));
        for (Fixture reader : java.util.List.of(reviewer, publisher)) {
            denied("FORBIDDEN", () -> facts.createFact(reader.sid(), reader.principal(), story, 1, "1",
                    mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
            denied("FORBIDDEN", () -> facts.updateFactActive(reader.sid(), reader.principal(), story, 1,
                    "A", "1", false, UUID.randomUUID()));
        }
        assertThat(facts.updateFact(editor.sid(), editor.principal(), story, 1, "A", "1",
                mapper.createObjectNode().put("truth", "FALSE"), UUID.randomUUID()).editRev()).isEqualTo("2");
        db.update("UPDATE admin_account SET can_review=false WHERE id=?", reviewer.principal().accountId());
        denied("NOT_FOUND", () -> facts.getFactDetail(reviewer.sid(), reviewer.principal(), story, 1,
                "A", UUID.randomUUID()));
        db.update("UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                storyId(story), editor.principal().accountId());
        denied("NOT_FOUND", () -> facts.getFactList(editor.sid(), editor.principal(), story, 1,
                null, null, null));
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(story));
        denied("NOT_FOUND", () -> facts.getFactDetail(publisher.sid(), publisher.principal(), story, 1,
                "A", UUID.randomUUID()));
        assertThat(facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                .item().truth()).isEqualTo("FALSE");
        denied("STATE_CONFLICT", () -> facts.updateFactActive(owner.sid(), owner.principal(), story, 1,
                "A", "2", false, UUID.randomUUID()));
        long version = db.queryForObject("SELECT id FROM story_version WHERE story_id=?", Long.class, storyId(story));
        long snapshot = db.queryForObject("INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by) "
                + "VALUES (?,2,'{}'::jsonb,?,?) RETURNING id", Long.class,
                version, UUID.randomUUID(), owner.principal().accountId());
        for (String state : java.util.List.of("REVIEW", "READY", "PUBLISHED")) {
            db.update("UPDATE story_version SET active_yn=true,status=?,current_snapshot_id=? WHERE id=?",
                    state, snapshot, version);
            denied("STATE_CONFLICT", () -> facts.createFact(owner.sid(), owner.principal(), story, 1, "2",
                    mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
            denied("STATE_CONFLICT", () -> facts.updateFact(owner.sid(), owner.principal(), story, 1, "A", "2",
                    mapper.createObjectNode().putNull("basis"), UUID.randomUUID()));
            denied("STATE_CONFLICT", () -> facts.updateFactActive(owner.sid(), owner.principal(), story, 1,
                    "A", "2", false, UUID.randomUUID()));
        }
        db.update("UPDATE story_version SET status='DRAFT',current_snapshot_id=null WHERE id=?", version);
        db.update("UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?", owner.principal().accountId());
        denied("AUTH_REQUIRED", () -> facts.getFactDetail(owner.sid(), owner.principal(), story, 1,
                "A", UUID.randomUUID()));
    }

    /** 읽기 및 모든 쓰기 감사 장애가 원고와 부모 수정번호를 함께 롤백한다. */
    @Test
    void factsMandatoryAuditFailureRollsBackAllWrites() {
        Fixture owner = account(true, false, (byte) -123);
        String story = create(owner, "사실 감사");
        facts.createFact(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A").put("statement", "원본"), UUID.randomUUID());
        var original = factState(story, "A");
        db.execute("CREATE FUNCTION fail_fact_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.detail->>'resource'='facts' THEN RAISE EXCEPTION 'synthetic fact audit outage'; "
                + "END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_fact_audit_insert BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_fact_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> facts.getFactDetail(owner.sid(), owner.principal(), story, 1,
                    "A", UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> facts.createFact(owner.sid(), owner.principal(), story, 1, "1",
                    mapper.createObjectNode().put("code", "B"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> facts.updateFact(owner.sid(), owner.principal(), story, 1,
                    "A", "1", mapper.createObjectNode().put("statement", "실패 원고"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> facts.updateFactActive(owner.sid(), owner.principal(), story, 1,
                    "A", "1", false, UUID.randomUUID()));
            assertThat(factState(story, "A")).isEqualTo(original);
            assertThat(db.queryForObject("SELECT count(*) FROM story_fact f JOIN story_version v ON v.id=f.version_id "
                    + "WHERE v.story_id=? AND f.code='B'", Long.class, storyId(story))).isZero();
        } finally {
            db.execute("DROP TRIGGER fail_fact_audit_insert ON story_audit");
            db.execute("DROP FUNCTION fail_fact_audit()");
        }
        assertThat(facts.getFactDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID())
                .item().statement()).isEqualTo("원본");
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE story_id=? "
                + "AND detail::text LIKE '%원본%'", Long.class, storyId(story))).isZero();
    }

    /** 고정 HTTP 경로의 엄격 JSON·바이트 상한·감사 경로와 원고 비노출을 확인한다. */
    @Test
    void factsHttpFixedRoutesStrictBodyAndAccessHistory() throws Exception {
        Fixture owner = account(true, false, (byte) -122);
        String story = create(owner, "HTTP 사실 원장");
        String base = "/admin/api/stories/" + story + "/versions/1/facts";
        var csrfResponse = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrfResponse.getContentAsString()).path("token").asText();
        Cookie session = cookie(owner);
        String first = "{\"expectedRev\":\"0\",\"item\":{\"code\":\"A\",\"statement\":\"비밀 명제\","
                + "\"truth\":\"MISREAD\",\"basis\":\"합성 근거\"}}";
        mvc.perform(post(base).secure(true).cookie(session).contentType("application/json").content(first))
                .andExpect(status().isForbidden());
        mvc.perform(get(base).secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(write(post(base), session, csrf, token, first)).andExpect(status().isCreated())
                .andDo(r -> {
                    assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
                    assertThat(r.getResponse().getContentAsString()).doesNotContain("비밀 명제", "합성 근거");
                });
        long reads = auditCount(story, "CONTENT_READ");
        mvc.perform(get(base + "?size=1&afterKey=0").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> {
                    assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
                    assertThat(mapper.readTree(r.getResponse().getContentAsString()).path("items").get(0).size())
                            .isEqualTo(3);
                    assertThat(r.getResponse().getContentAsString()).doesNotContain("statement", "truth", "basis");
                });
        assertThat(auditCount(story, "CONTENT_READ")).isEqualTo(reads);
        mvc.perform(get(base + "/A").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> {
                    assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
                    assertThat(mapper.readTree(r.getResponse().getContentAsString())
                            .path("item").path("statement").asText()).isEqualTo("비밀 명제");
                });
        assertThat(auditCount(story, "CONTENT_READ")).isEqualTo(reads + 1);
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "/A"),
                session, csrf, token, "{\"expectedRev\":\"1\",\"changes\":{\"truth\":null,\"basis\":\"\"}}"))
                .andExpect(status().isOk());
        mvc.perform(write(post(base + "/A/deactivate"), session, csrf, token, "{\"expectedRev\":\"2\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(post(base + "/A/reactivate"), session, csrf, token, "{\"expectedRev\":\"3\"}"))
                .andExpect(status().isOk());
        String second = "{\"expectedRev\":\"4\",\"item\":{\"code\":\"B\"}}";
        mvc.perform(write(post(base), session, csrf, token, "{}")
                .content(("\uFEFF" + second).getBytes(java.nio.charset.StandardCharsets.UTF_16LE)))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base), session, csrf, token, "{}")
                .content(new byte[] {'{', '"', (byte) 0xc3, (byte) 0x28, '"', '}'}))
                .andExpect(status().isBadRequest());
        for (String invalid : java.util.List.of(
                "{\"expectedRev\":\"4\",\"expectedRev\":\"4\",\"item\":{\"code\":\"B\"}}",
                "{\"expectedRev\":\"4\",\"item\":{\"code\":\"B\",\"code\":\"C\"}}",
                "{\"expectedRev\":\"4\",\"item\":{\"code\":\"B\",\"extra\":1}}",
                "{\"expectedRev\":\"4\",\"unknown\":1,\"item\":{\"code\":\"B\"}}")) {
            mvc.perform(write(post(base), session, csrf, token, invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "/A"),
                session, csrf, token, "{\"expectedRev\":\"4\",\"changes\":{\"truth\":true}}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "/A"),
                session, csrf, token, "{\"expectedRev\":\"4\",\"changes\":{\"unknown\":1}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(base + "/A/deactivate"), session, csrf, token,
                "{\"expectedRev\":\"4\",\"unknown\":1}")).andExpect(status().isBadRequest());
        mvc.perform(write(post(base), session, csrf, token, second + " ".repeat(512 * 1024 - second.length())))
                .andExpect(status().isCreated());
        mvc.perform(write(post(base), session, csrf, token, second + " ".repeat(512 * 1024 - second.length() + 1)))
                .andExpect(status().isPayloadTooLarge());
        String state = "{\"expectedRev\":\"5\"}";
        mvc.perform(write(post(base + "/B/deactivate"), session, csrf, token,
                state + " ".repeat(8192 - state.length()))).andExpect(status().isOk());
        state = "{\"expectedRev\":\"6\"}";
        mvc.perform(write(post(base + "/B/reactivate"), session, csrf, token,
                state + " ".repeat(8192 - state.length() + 1))).andExpect(status().isPayloadTooLarge());
        assertThat(versionRev(story)).isEqualTo(6);
        for (String suffix : java.util.List.of("facts", "facts/{itemKey}",
                "facts/{itemKey}/deactivate", "facts/{itemKey}/reactivate")) {
            assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=?",
                    Long.class, owner.principal().accountKey(),
                    "/admin/api/stories/{storyCode}/versions/{versionNo}/" + suffix)).isPositive();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND "
                + "(route LIKE ? OR route LIKE '%비밀%' OR route LIKE '%afterKey%')",
                Long.class, owner.principal().accountKey(), "%" + story + "%")).isZero();
    }

    @Test
    void rubricsDraftNullableFieldsMergedScoresAndCulpritProtection() {
        Fixture owner = account(true, false, (byte) -110);
        String story = create(owner, "소항목 초안");
        assertThat(rubric(owner, story, "R", "METHOD", 0).warnings().toString())
                .contains("SCORE_TOTAL", "MISSING_CONTENT", "rubrics.R.maxScore", "rubrics.R.ruleData");
        assertThat(rubrics.getRubricDetail(owner.sid(), owner.principal(), story, 1, "R", UUID.randomUUID())
                .item().maxScore()).isNull();
        denied("INVALID_INPUT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "1",
                mapper.createObjectNode().put("passScore", 1), UUID.randomUUID()));
        var result = rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "1",
                mapper.createObjectNode().put("maxScore", 10).put("requiredYn", true).put("passScore", 5), UUID.randomUUID());
        assertThat(result.editRev()).isEqualTo("2");
        assertThat(result.warnings().toString()).contains("rubrics.R.passScore", "rubrics.EVIDENCE.requiredYn");
        denied("INVALID_INPUT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "2",
                mapper.createObjectNode().put("maxScore", 4), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "2",
                mapper.createObjectNode().put("requiredYn", false), UUID.randomUUID()));
        rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "2",
                mapper.createObjectNode().putNull("passScore"), UUID.randomUUID());
        var culprit = rubrics.createRubric(owner.sid(), owner.principal(), story, 1, "3",
                mapper.createObjectNode().put("code", "CUL").put("category", "CULPRIT"), UUID.randomUUID());
        var fixed = rubrics.getRubricDetail(owner.sid(), owner.principal(), story, 1, "CUL", UUID.randomUUID()).item();
        assertThat(fixed.maxScore()).isEqualTo(25);
        assertThat(fixed.passScore()).isEqualTo(25);
        assertThat(fixed.requiredYn()).isTrue();
        assertThat(fixed.ruleData().toString()).contains("SELECTED_CULPRIT", "CONTRADICT_CULPRIT", "UNSUPPORTED_ACCOMPLICE");
        var same = mapper.createObjectNode().put("maxScore", 25).put("requiredYn", true).put("passScore", 25);
        same.set("ruleData", fixed.ruleData());
        assertThat(rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "CUL", "4", same,
                UUID.randomUUID()).changed()).isFalse();
        denied("INVALID_INPUT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "CUL", "4",
                mapper.createObjectNode().putNull("ruleData"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "CUL", "4",
                mapper.createObjectNode().put("category", "MOTIVE"), UUID.randomUUID()));
        rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "CUL", "4",
                mapper.createObjectNode().put("category", "MOTIVE").putNull("ruleData")
                        .putNull("passScore"), UUID.randomUUID());
        assertThat(culprit.itemKey()).isEqualTo("CUL");
    }

    @Test
    void rubricCluesCreateNullRuleThenValidateLiveReferencesAndUnlinkInOrder() {
        Fixture owner = account(true, false, (byte) -109);
        String story = create(owner, "규칙 참조");
        rubric(owner, story, "R", "EVIDENCE", 0);
        rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "1",
                mapper.createObjectNode().put("maxScore", 10), UUID.randomUUID());
        clue(owner, story, "C", 2);
        fact(owner, story, "F", 3);
        JsonNode optionalRule = rubricRule("F", "C");
        ((com.fasterxml.jackson.databind.node.ObjectNode) optionalRule).putNull("requiredNotice");
        denied("INVALID_INPUT", () -> rulePatch(owner, story, "R", 4, optionalRule));
        var relation = rubricClues.createRubricClue(owner.sid(), owner.principal(), story, 1, "4",
                mapper.createObjectNode().put("rubricCode", "R").put("clueCode", "C").put("linkText", "연결"), UUID.randomUUID());
        assertThat(relation.itemKey()).isEqualTo("R~C");
        assertThat(rulePatch(owner, story, "R", 5, optionalRule).editRev()).isEqualTo("6");
        denied("REFERENCE_IN_USE", () -> facts.updateFactActive(owner.sid(), owner.principal(), story, 1,
                "F", "6", false, UUID.randomUUID()));
        denied("REFERENCE_IN_USE", () -> clues.updateClueActive(owner.sid(), owner.principal(), story, 1,
                "C", "6", false, UUID.randomUUID()));
        denied("REFERENCE_IN_USE", () -> rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1,
                "R~C", "6", false, UUID.randomUUID()));
        denied("REFERENCE_IN_USE", () -> rubrics.updateRubricActive(owner.sid(), owner.principal(), story, 1,
                "R", "6", false, UUID.randomUUID()));
        rulePatch(owner, story, "R", 6, null);
        rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1,
                "R~C", "7", false, UUID.randomUUID());
        clues.updateClueActive(owner.sid(), owner.principal(), story, 1, "C", "8", false, UUID.randomUUID());
        denied("INVALID_INPUT", () -> rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1,
                "R~C", "9", true, UUID.randomUUID()));
        rubrics.updateRubricActive(owner.sid(), owner.principal(), story, 1, "R", "9", false, UUID.randomUUID());
        facts.updateFactActive(owner.sid(), owner.principal(), story, 1, "F", "10", false, UUID.randomUUID());
        denied("ITEM_EXISTS", () -> rubric(owner, story, "R", "METHOD", 11));
        denied("ITEM_EXISTS", () -> rubricClues.createRubricClue(owner.sid(), owner.principal(), story, 1, "11",
                mapper.createObjectNode().put("rubricCode", "R").put("clueCode", "C"), UUID.randomUUID()));
    }

    @Test
    void rubricsRuleSchemaAndReferenceFailuresDoNotAdvanceRevision() {
        Fixture owner = account(true, false, (byte) -108);
        String story = create(owner, "규칙 형식");
        rubric(owner, story, "R", "METHOD", 0);
        rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "1",
                mapper.createObjectNode().put("maxScore", 10).put("requiredYn", true), UUID.randomUUID());
        for (JsonNode invalid : java.util.List.of(mapper.createArrayNode(), mapper.createObjectNode(),
                rubricRule("MISSING", null), rubricRule("F", "MISSING")))
            denied("INVALID_INPUT", () -> rulePatch(owner, story, "R", 2, invalid));
        fact(owner, story, "F", 2);
        JsonNode malformed = rubricRule("F", null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed.path("claims").get(0)).put("extra", true);
        denied("INVALID_REQUEST", () -> rulePatch(owner, story, "R", 3, malformed));
        for (String field : java.util.List.of("formatNo", "requiredNotice", "claims", "levels", "contradictions")) {
            JsonNode missing = rubricRule("F", null);
            ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove(field);
            denied("INVALID_INPUT", () -> rulePatch(owner, story, "R", 3, missing));
        }
        JsonNode bad = rubricRule("F", null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) bad.path("levels").get(1)).putArray("routes").addArray().add("MISSING");
        denied("INVALID_INPUT", () -> rulePatch(owner, story, "R", 3, bad));
        assertThat(versionRev(story)).isEqualTo(3);
        assertThat(rulePatch(owner, story, "R", 3, rubricRule("F", null)).editRev()).isEqualTo("4");
        assertThat(rubrics.getRubricDetail(owner.sid(), owner.principal(), story, 1, "R", UUID.randomUUID())
                .item().ruleData().path("claims").get(0).path("factCodes").get(0).asText()).isEqualTo("F");
    }

    @Test
    void rubricsWarningsReflectCurrentRowsAndClearWhenCompleted() {
        Fixture owner = account(true, false, (byte) -107);
        String story = create(owner, "규칙 경고");
        rubric(owner, story, "R", "METHOD", 0);
        var warnings = rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "1",
                mapper.createObjectNode().put("maxScore", 20).put("requiredYn", true).put("passScore", 10),
                UUID.randomUUID()).warnings().toString();
        assertThat(warnings).contains("rubrics.R.ruleData", "rubrics.R.passScore", "rubrics.EVIDENCE.requiredYn");
        fact(owner, story, "F", 2);
        JsonNode complete = rubricRule("F", null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) complete.path("levels").get(1)).put("score", 20);
        ((com.fasterxml.jackson.databind.node.ArrayNode) complete.path("levels")).insertObject(1).put("code", "HALF").put("score", 10)
                .putArray("routes").addArray().add("CLAIM");
        var saved = rulePatch(owner, story, "R", 3, complete);
        assertThat(saved.warnings().toString()).doesNotContain("rubrics.R.ruleData", "rubrics.R.passScore",
                "rubrics.METHOD.requiredYn");
        assertThat(saved.warnings().toString()).contains("rubrics.EVIDENCE.requiredYn");
        var removed = rulePatch(owner, story, "R", 4, null);
        assertThat(removed.warnings().toString()).contains("rubrics.R.ruleData", "rubrics.R.passScore");
    }

    @Test
    void rubricCluesSameStringTupleAsciiPaginationAndMetadataOnlyLists() {
        Fixture owner = account(true, false, (byte) -106);
        String story = create(owner, "튜플 페이지");
        rubric(owner, story, "A", "METHOD", 0);
        rubric(owner, story, "A_", "EVIDENCE", 1);
        clue(owner, story, "A", 2);
        clue(owner, story, "Z", 3);
        for (String[] key : new String[][] {{"A", "A"}, {"A", "Z"}, {"A_", "A"}})
            rubricClues.createRubricClue(owner.sid(), owner.principal(), story, 1,
                    Long.toString(versionRev(story)), mapper.createObjectNode().put("rubricCode", key[0])
                            .put("clueCode", key[1]).put("linkText", "비공개 " + key[0] + key[1]), UUID.randomUUID());
        var list = rubricClues.getRubricClueList(owner.sid(), owner.principal(), story, 1, 1, null, null);
        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).itemKey()).isEqualTo("A~A");
        assertThat(mapper.valueToTree(list).toString()).doesNotContain("비공개", "linkText");
        assertThat(rubricClues.getRubricClueList(owner.sid(), owner.principal(), story, 1, 1,
                list.nextAfterKey(), null).items().get(0).itemKey()).isEqualTo("A~Z");
        assertThat(rubricClues.getRubricClueList(owner.sid(), owner.principal(), story, 1, null,
                "A~Z", null).items().get(0).itemKey()).isEqualTo("A_~A");
        assertThat(rubricClues.getRubricClueDetail(owner.sid(), owner.principal(), story, 1,
                "A~A", UUID.randomUUID()).item().linkText()).isEqualTo("비공개 AA");
        assertThat(mapper.valueToTree(rubrics.getRubricList(owner.sid(), owner.principal(), story, 1,
                null, null, null)).toString()).doesNotContain("ruleData", "acceptedText");
        assertThat(rubrics.getRubricList(owner.sid(), owner.principal(), story, 1, 1,
                "A", null).items().get(0).code()).isEqualTo("A_");
        for (Integer size : java.util.List.of(0, 101)) {
            denied("INVALID_REQUEST", () -> rubricClues.getRubricClueList(owner.sid(), owner.principal(), story, 1,
                    size, null, null));
            denied("INVALID_REQUEST", () -> rubrics.getRubricList(owner.sid(), owner.principal(), story, 1,
                    size, null, null));
        }
        denied("INVALID_REQUEST", () -> rubricClues.getRubricClueList(owner.sid(), owner.principal(), story, 1,
                20, "a~A", null));
    }

    @Test
    void rubricsAndRubricCluesNoopRejectStaleRevisionWithoutTouchingAuditOrTime() {
        Fixture owner = account(true, false, (byte) -105);
        String story = create(owner, "무변경 규칙");
        rubric(owner, story, "R", "METHOD", 0);
        clue(owner, story, "C", 1);
        rubricClues.createRubricClue(owner.sid(), owner.principal(), story, 1, "2",
                mapper.createObjectNode().put("rubricCode", "R").put("clueCode", "C"), UUID.randomUUID());
        var before = db.queryForMap("SELECT v.edit_rev,v.updated_at,r.updated_at AS rubric_time,rc.updated_at AS link_time,"
                + "(SELECT count(*) FROM story_audit a WHERE a.version_id=v.id AND a.action LIKE 'ITEM_%') AS audits "
                + "FROM story_version v JOIN story_rubric r ON r.version_id=v.id JOIN rubric_clue rc "
                + "ON rc.version_id=v.id AND rc.rubric_code=r.code WHERE v.story_id=?", storyId(story));
        assertThat(rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "3",
                mapper.createObjectNode().putNull("acceptedText"), UUID.randomUUID()).changed()).isFalse();
        assertThat(rubrics.updateRubricActive(owner.sid(), owner.principal(), story, 1, "R", "3",
                true, UUID.randomUUID()).changed()).isFalse();
        assertThat(rubricClues.updateRubricClue(owner.sid(), owner.principal(), story, 1, "R~C", "3",
                mapper.createObjectNode().putNull("linkText"), UUID.randomUUID()).changed()).isFalse();
        assertThat(rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1, "R~C", "3",
                true, UUID.randomUUID()).changed()).isFalse();
        denied("EDIT_CONFLICT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", "2",
                mapper.createObjectNode().putNull("acceptedText"), UUID.randomUUID()));
        denied("EDIT_CONFLICT", () -> rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1,
                "R~C", "2", true, UUID.randomUUID()));
        assertThat(db.queryForMap("SELECT v.edit_rev,v.updated_at,r.updated_at AS rubric_time,rc.updated_at AS link_time,"
                + "(SELECT count(*) FROM story_audit a WHERE a.version_id=v.id AND a.action LIKE 'ITEM_%') AS audits "
                + "FROM story_version v JOIN story_rubric r ON r.version_id=v.id JOIN rubric_clue rc "
                + "ON rc.version_id=v.id AND rc.rubric_code=r.code WHERE v.story_id=?", storyId(story))).isEqualTo(before);
    }

    @Test
    void rubricsAndRubricCluesAuditFailureRollsBackAllMutationsAndReads() {
        Fixture owner = account(true, false, (byte) -104);
        String story = create(owner, "규칙 감사");
        rubric(owner, story, "R", "METHOD", 0);
        clue(owner, story, "C", 1);
        rubricClues.createRubricClue(owner.sid(), owner.principal(), story, 1, "2",
                mapper.createObjectNode().put("rubricCode", "R").put("clueCode", "C"), UUID.randomUUID());
        db.execute("CREATE FUNCTION fail_rubric_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.detail->>'resource' IN ('rubrics','rubric-clues') THEN "
                + "RAISE EXCEPTION 'synthetic rubric audit outage'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_rubric_audit_insert BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_rubric_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> rubrics.getRubricDetail(owner.sid(), owner.principal(), story, 1,
                    "R", UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> rubricClues.getRubricClueDetail(owner.sid(), owner.principal(), story, 1,
                    "R~C", UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> rubric(owner, story, "NEW", "METHOD", 3));
            denied("STORY_UNAVAILABLE", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1,
                    "R", "3", mapper.createObjectNode().put("acceptedText", "실패"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> rubricClues.updateRubricClue(owner.sid(), owner.principal(), story, 1,
                    "R~C", "3", mapper.createObjectNode().put("linkText", "실패"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1,
                    "R~C", "3", false, UUID.randomUUID()));
        } finally {
            db.execute("DROP TRIGGER fail_rubric_audit_insert ON story_audit");
            db.execute("DROP FUNCTION fail_rubric_audit()");
        }
        assertThat(versionRev(story)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM story_rubric r JOIN story_version v ON v.id=r.version_id "
                + "WHERE v.story_id=? AND r.code='NEW'", Long.class, storyId(story))).isZero();
        assertThat(rubrics.getRubricDetail(owner.sid(), owner.principal(), story, 1, "R", UUID.randomUUID())
                .item().acceptedText()).isNull();
        assertThat(rubricClues.getRubricClueDetail(owner.sid(), owner.principal(), story, 1, "R~C", UUID.randomUUID())
                .item().linkText()).isNull();
    }

    @Test
    void rubricsAndRubricCluesReadScopeRevocationsAndSnapshotGatedWrites() {
        Fixture owner = account(true, false, (byte) -103);
        Fixture editor = account(false, false, (byte) -102);
        Fixture reviewer = account(false, false, (byte) -101);
        Fixture publisher = account(false, false, (byte) -100);
        Fixture manager = account(false, true, (byte) -99);
        String story = create(owner, "규칙 인가");
        rubric(owner, story, "R", "METHOD", 0);
        clue(owner, story, "C", 1);
        rubricClues.createRubricClue(owner.sid(), owner.principal(), story, 1, "2",
                mapper.createObjectNode().put("rubricCode", "R").put("clueCode", "C"), UUID.randomUUID());
        db.update("UPDATE admin_account SET can_review=true WHERE id=?", reviewer.principal().accountId());
        db.update("UPDATE admin_account SET can_publish=true WHERE id=?", publisher.principal().accountId());
        for (var relation : java.util.List.of(java.util.Map.entry(editor, "EDIT"),
                java.util.Map.entry(reviewer, "REVIEW"), java.util.Map.entry(publisher, "PUBLISH"))) {
            db.update("INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES (?,?,?,?)",
                    storyId(story), relation.getKey().principal().accountId(), relation.getValue(),
                    owner.principal().accountId());
            assertThat(rubrics.getRubricDetail(relation.getKey().sid(), relation.getKey().principal(), story, 1,
                    "R", UUID.randomUUID()).item().code()).isEqualTo("R");
            assertThat(rubricClues.getRubricClueDetail(relation.getKey().sid(), relation.getKey().principal(), story, 1,
                    "R~C", UUID.randomUUID()).item().clueCode()).isEqualTo("C");
        }
        denied("NOT_FOUND", () -> rubrics.getRubricList(manager.sid(), manager.principal(), story, 1,
                null, null, null));
        for (Fixture reader : java.util.List.of(reviewer, publisher)) {
            denied("FORBIDDEN", () -> rubric(reader, story, "NEW", "METHOD", 3));
            denied("FORBIDDEN", () -> rubricClues.updateRubricClueActive(reader.sid(), reader.principal(), story, 1,
                    "R~C", "3", false, UUID.randomUUID()));
        }
        assertThat(rubrics.updateRubric(editor.sid(), editor.principal(), story, 1, "R", "3",
                mapper.createObjectNode().put("acceptedText", "editor"), UUID.randomUUID()).editRev()).isEqualTo("4");
        db.update("UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                storyId(story), editor.principal().accountId());
        denied("NOT_FOUND", () -> rubrics.getRubricList(editor.sid(), editor.principal(), story, 1,
                null, null, null));
        db.update("UPDATE admin_account SET can_review=false WHERE id=?", reviewer.principal().accountId());
        denied("NOT_FOUND", () -> rubricClues.getRubricClueList(reviewer.sid(), reviewer.principal(), story, 1,
                null, null, null));
        db.update("UPDATE story_version SET active_yn=false WHERE story_id=?", storyId(story));
        denied("NOT_FOUND", () -> rubrics.getRubricList(publisher.sid(), publisher.principal(), story, 1,
                null, null, null));
        assertThat(rubrics.getRubricDetail(owner.sid(), owner.principal(), story, 1, "R", UUID.randomUUID())
                .item().acceptedText()).isEqualTo("editor");
        denied("STATE_CONFLICT", () -> rubrics.updateRubricActive(owner.sid(), owner.principal(), story, 1,
                "R", "4", false, UUID.randomUUID()));
        long version = db.queryForObject("SELECT id FROM story_version WHERE story_id=?", Long.class, storyId(story));
        long snapshot = db.queryForObject("INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by) "
                + "VALUES (?,4,'{}'::jsonb,?,?) RETURNING id", Long.class,
                version, UUID.randomUUID(), owner.principal().accountId());
        for (String state : java.util.List.of("REVIEW", "READY", "PUBLISHED")) {
            db.update("UPDATE story_version SET active_yn=true,status=?,current_snapshot_id=? WHERE id=?",
                    state, snapshot, version);
            denied("STATE_CONFLICT", () -> rubric(owner, story, "NEW", "METHOD", 4));
            denied("STATE_CONFLICT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1,
                    "R", "4", mapper.createObjectNode().put("acceptedText", "forbidden"), UUID.randomUUID()));
            denied("STATE_CONFLICT", () -> rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1,
                    "R~C", "4", false, UUID.randomUUID()));
        }
        db.update("UPDATE story_version SET status='DRAFT',current_snapshot_id=null WHERE id=?", version);
        db.update("UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE account_id=?", owner.principal().accountId());
        denied("AUTH_REQUIRED", () -> rubrics.getRubricDetail(owner.sid(), owner.principal(), story, 1,
                "R", UUID.randomUUID()));
    }

    @Test
    void rubricsAndRubricCluesUnicodeTextBoundsAndReservedKeys() {
        Fixture owner = account(true, false, (byte) -98);
        String story = create(owner, "유니코드 경계");
        rubric(owner, story, "R", "METHOD", 0);
        clue(owner, story, "C", 1);
        var fields = new String[] {"acceptedText", "partialText", "rejectText"};
        int rev = 2;
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            int length = i == 2 ? 8000 : 12000;
            String content = "𐐀".repeat(length);
            assertThat(rubrics.updateRubric(owner.sid(), owner.principal(), story, 1, "R", Integer.toString(rev),
                    mapper.createObjectNode().put(field, content), UUID.randomUUID()).changed()).isTrue();
            rev++;
            int current = rev;
            denied("INVALID_INPUT", () -> rubrics.updateRubric(owner.sid(), owner.principal(), story, 1,
                    "R", Integer.toString(current), mapper.createObjectNode().put(field, content + "𐐀"), UUID.randomUUID()));
        }
        rubricClues.createRubricClue(owner.sid(), owner.principal(), story, 1, "5",
                mapper.createObjectNode().put("rubricCode", "R").put("clueCode", "C")
                        .put("linkText", "𐐀".repeat(4000)), UUID.randomUUID());
        denied("INVALID_INPUT", () -> rubricClues.updateRubricClue(owner.sid(), owner.principal(), story, 1,
                "R~C", "6", mapper.createObjectNode().put("linkText", "𐐀".repeat(4001)), UUID.randomUUID()));
        assertThat(rubricClues.getRubricClueDetail(owner.sid(), owner.principal(), story, 1, "R~C", UUID.randomUUID())
                .item().linkText().codePointCount(0, 8000)).isEqualTo(4000);
        rubricClues.updateRubricClueActive(owner.sid(), owner.principal(), story, 1, "R~C", "6", false, UUID.randomUUID());
        rubrics.updateRubricActive(owner.sid(), owner.principal(), story, 1, "R", "7", false, UUID.randomUUID());
        denied("ITEM_EXISTS", () -> rubric(owner, story, "R", "METHOD", 8));
    }

    private StoryRubricService.ItemCreated rubric(Fixture actor, String story, String code, String category, int rev) {
        return rubrics.createRubric(actor.sid(), actor.principal(), story, 1, Integer.toString(rev),
                mapper.createObjectNode().put("code", code).put("category", category), UUID.randomUUID());
    }

    private StoryFactService.ItemCreated fact(Fixture actor, String story, String code, int rev) {
        return facts.createFact(actor.sid(), actor.principal(), story, 1, Integer.toString(rev),
                mapper.createObjectNode().put("code", code), UUID.randomUUID());
    }

    private com.reasoning.common.story.service.StoryService.ContentResult rulePatch(
            Fixture actor, String story, String code, int rev, JsonNode rule) {
        var changes = mapper.createObjectNode();
        if (rule == null) changes.putNull("ruleData");
        else changes.set("ruleData", rule);
        return rubrics.updateRubric(actor.sid(), actor.principal(), story, 1, code, Integer.toString(rev),
                changes, UUID.randomUUID());
    }

    private JsonNode rubricRule(String fact, String clue) {
        var rule = mapper.createObjectNode().put("formatNo", 1).put("requiredNotice", "증명하라");
        var claim = rule.putArray("claims").addObject().put("code", "CLAIM").put("meaning", "사실을 입증한다");
        claim.putArray("factCodes").add(fact);
        var examples = claim.putArray("exampleClueRoutes");
        if (clue != null) examples.addArray().add(clue);
        rule.putArray("levels").addObject().put("code", "ZERO").put("score", 0).putArray("routes");
        rule.withArray("levels").addObject().put("code", "FULL").put("score", 10)
                .putArray("routes").addArray().add("CLAIM");
        rule.putArray("contradictions");
        return rule;
    }

    @Test
    void gradeSamplesStoreDraftFixturesAndInvalidateHumanChecksOnRealContentChanges() {
        Fixture owner = account(true, false, (byte) -94);
        String story = create(owner, "검증 예시");
        var created = gradeSamples.createGradeSample(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "BAD"), UUID.randomUUID());
        assertThat(created.itemKey()).isEqualTo("BAD");
        assertThat(gradeSamples.getGradeSampleList(owner.sid(), owner.principal(), story, 1,
                null, null, true).items()).hasSize(1);
        assertThat(gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1,
                "BAD", UUID.randomUUID()).item().inputData()).isNull();
        assertThat(gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "BAD", "1", mapper.createObjectNode().putNull("inputData"), UUID.randomUUID()).changed()).isFalse();
        assertThat(versionRev(story)).isEqualTo(1);

        var input = mapper.createObjectNode().put("formatNo", 1);
        input.putObject("report").put("culpritCode", 47).putNull("method");
        var expectation = mapper.createObjectNode().put("formatNo", 1).put("kind", "INPUT_ERROR");
        expectation.putObject("error").put("code", "INVALID_REPORT").put("state", "REJECTED")
                .putNull("score").put("attemptDelta", 0);
        var changes = mapper.createObjectNode().put("reason", "오류 검증 자료");
        changes.set("inputData", input);
        changes.set("expectData", expectation);
        assertThat(gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "BAD", "1", changes, UUID.randomUUID()).editRev()).isEqualTo("2");
        assertThat(gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1,
                "BAD", UUID.randomUUID()).item().inputData()).isEqualTo(input);
        assertThat(gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "BAD", "2", changes, UUID.randomUUID()).changed()).isFalse();
        denied("ITEM_EXISTS", () -> gradeSamples.createGradeSample(owner.sid(), owner.principal(), story, 1,
                "2", mapper.createObjectNode().put("code", "BAD"), UUID.randomUUID()));
        denied("INVALID_INPUT", () -> gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "BAD", "2", mapper.createObjectNode().put("expectedScore", 50), UUID.randomUUID()));

        long version = db.queryForObject("SELECT id FROM story_version WHERE story_id=?", Long.class, storyId(story));
        db.update("UPDATE grade_sample SET checked_by=? WHERE version_id=?", owner.principal().accountId(), version);
        assertThat(gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "BAD", "2", changes, UUID.randomUUID()).changed()).isFalse();
        assertThat(gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1,
                "BAD", UUID.randomUUID()).item().checkedBy()).isEqualTo(owner.principal().accountKey());
        patch(owner, story, "basic", "2", "{\"title\":\"검증 자료 변경\"}");
        assertThat(gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1,
                "BAD", UUID.randomUUID()).item().checkedBy()).isNull();

        db.update("UPDATE grade_sample SET checked_by=? WHERE version_id=?", owner.principal().accountId(), version);
        fact(owner, story, "F", 3);
        assertThat(gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1,
                "BAD", UUID.randomUUID()).item().checkedBy()).isNull();
        assertThat(db.queryForObject("SELECT count(*) FROM story_audit WHERE version_id=? AND "
                + "detail::text LIKE '%오류 검증 자료%'", Long.class, version)).isZero();
        gradeSamples.updateGradeSampleActive(owner.sid(), owner.principal(), story, 1, "BAD", "4", false, UUID.randomUUID());
        denied("ITEM_EXISTS", () -> gradeSamples.createGradeSample(owner.sid(), owner.principal(), story, 1,
                "5", mapper.createObjectNode().put("code", "BAD"), UUID.randomUUID()));
        gradeSamples.updateGradeSampleActive(owner.sid(), owner.principal(), story, 1, "BAD", "5", true, UUID.randomUUID());
    }

    @Test
    void gradeSampleAuditFailuresRollbackRowsRevisionsAndHumanCheckInvalidation() {
        Fixture owner = account(true, false, (byte) -93);
        String story = create(owner, "검증 감사");
        gradeSamples.createGradeSample(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A"), UUID.randomUUID());
        long version = db.queryForObject("SELECT id FROM story_version WHERE story_id=?", Long.class, storyId(story));
        db.update("UPDATE grade_sample SET checked_by=? WHERE version_id=?", owner.principal().accountId(), version);
        db.execute("CREATE FUNCTION fail_grade_sample_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.detail->>'resource'='grade-samples' OR NEW.action='SECTION_UPDATED' THEN "
                + "RAISE EXCEPTION 'synthetic sample audit outage'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_grade_sample_audit_insert BEFORE INSERT ON story_audit FOR EACH ROW "
                + "EXECUTE FUNCTION fail_grade_sample_audit()");
        try {
            denied("STORY_UNAVAILABLE", () -> gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(),
                    story, 1, "A", UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> gradeSamples.updateGradeSample(owner.sid(), owner.principal(),
                    story, 1, "A", "1", mapper.createObjectNode().put("reason", "롤백"), UUID.randomUUID()));
            denied("STORY_UNAVAILABLE", () -> patch(owner, story, "basic", "1", "{\"title\":\"롤백\"}"));
        } finally {
            db.execute("DROP TRIGGER fail_grade_sample_audit_insert ON story_audit");
            db.execute("DROP FUNCTION fail_grade_sample_audit()");
        }
        assertThat(versionRev(story)).isEqualTo(1);
        var detail = gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID());
        assertThat(detail.item().reason()).isNull();
        assertThat(detail.item().checkedBy()).isEqualTo(owner.principal().accountKey());
    }

    @Test
    void gradeSamplesHttpProtectsFullFixturesAndNormalizesHistory() throws Exception {
        Fixture owner = account(true, false, (byte) -92);
        Fixture stranger = account(false, true, (byte) -91);
        String story = create(owner, "HTTP 검증 예시");
        String base = "/admin/api/stories/" + story + "/versions/1/grade-samples";
        var csrfResponse = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrfResponse.getContentAsString()).path("token").asText();
        Cookie session = cookie(owner);
        String first = "{\"expectedRev\":\"0\",\"item\":{\"code\":\"A\",\"reason\":\"비밀 기대값\"}}";
        mvc.perform(post(base).secure(true).cookie(session).contentType("application/json").content(first))
                .andExpect(status().isForbidden());
        mvc.perform(get(base).secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(write(post(base), session, csrf, token, first)).andExpect(status().isCreated())
                .andDo(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("비밀 기대값"));
        long reads = auditCount(story, "CONTENT_READ");
        mvc.perform(get(base + "?size=1").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> {
                    assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
                    assertThat(r.getResponse().getContentAsString()).doesNotContain("비밀 기대값", "reason", "checkedBy");
                });
        assertThat(auditCount(story, "CONTENT_READ")).isEqualTo(reads);
        mvc.perform(get(base + "/A").secure(true).cookie(cookie(stranger))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/A").secure(true).cookie(session)).andExpect(status().isOk())
                .andDo(r -> assertThat(r.getResponse().getContentAsString()).contains("비밀 기대값"));
        assertThat(auditCount(story, "CONTENT_READ")).isEqualTo(reads + 1);
        for (String invalid : java.util.List.of(
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"inputData\":{\"formatNo\":1,\"formatNo\":2}}}",
                "{\"expectedRev\":\"1\",\"item\":{\"code\":\"B\",\"checkedBy\":true}}")) {
            mvc.perform(write(post(base), session, csrf, token, invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "/A"),
                session, csrf, token, "{\"expectedRev\":\"1\",\"changes\":{\"expectedScore\":101}}"))
                .andExpect(status().isUnprocessableEntity());
        for (String field : java.util.List.of("inputData", "expectData")) {
            int limit = field.equals("inputData") ? 128 * 1024 : 64 * 1024;
            String padded = "{\"expectedRev\":\"1\",\"changes\":{\"" + field
                    + "\":{\"formatNo\":1" + " ".repeat(limit) + "}}}";
            mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base + "/A"),
                    session, csrf, token, padded)).andExpect(status().isUnprocessableEntity());
        }
        assertThat(versionRev(story)).isEqualTo(1);
        mvc.perform(write(post(base + "/A/deactivate"), session, csrf, token,
                "{\"expectedRev\":\"1\"}")).andExpect(status().isOk());
        mvc.perform(write(post(base + "/A/reactivate"), session, csrf, token,
                "{\"expectedRev\":\"2\"}")).andExpect(status().isOk());
        for (String suffix : java.util.List.of("grade-samples", "grade-samples/{itemKey}",
                "grade-samples/{itemKey}/deactivate", "grade-samples/{itemKey}/reactivate")) {
            assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND route=?",
                    Long.class, owner.principal().accountKey(),
                    "/admin/api/stories/{storyCode}/versions/{versionNo}/" + suffix)).isPositive();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM access_history WHERE actor_key=? AND "
                + "(route LIKE ? OR route LIKE '%비밀%')", Long.class,
                owner.principal().accountKey(), "%" + story + "%")).isZero();
    }

    @Test
    void gradeSamplesRejectWrongKindUnknownReferencesAndJsonbByteOverflow() throws Exception {
        Fixture owner = account(true, false, (byte) -90);
        String story = create(owner, "검증 자료 경계");
        gradeSamples.createGradeSample(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A"), UUID.randomUUID());
        var error = mapper.createObjectNode().put("formatNo", 1).put("kind", "ENGINE_ERROR");
        error.putObject("error").put("code", "INVALID_REPORT").put("state", "REJECTED")
                .putNull("score").put("attemptDelta", 0);
        denied("INVALID_INPUT", () -> gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "A", "1", mapper.createObjectNode().set("expectData", error), UUID.randomUUID()));
        var graded = mapper.createObjectNode().put("formatNo", 1).put("kind", "GRADED");
        graded.putArray("items").addObject().put("rubricCode", "NO_SUCH_RULE").put("score", 0)
                .putNull("requiredMet").put("reason", "근거");
        denied("INVALID_INPUT", () -> gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "A", "1", mapper.createObjectNode().set("expectData", graded), UUID.randomUUID()));
        var input = mapper.createObjectNode().put("formatNo", 1);
        input.putObject("report").put("culpritCode", "MISSING").put("method", "")
                .put("time", "").put("motive", "").put("evidence", "");
        denied("INVALID_INPUT", () -> gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "A", "1", mapper.createObjectNode().set("inputData", input), UUID.randomUUID()));
        var inputError = mapper.createObjectNode().put("formatNo", 1).put("kind", "INPUT_ERROR");
        inputError.putObject("error").put("code", "INVALID_REPORT").put("state", "REJECTED")
                .putNull("score").put("attemptDelta", 0);
        var large = mapper.createObjectNode().put("formatNo", 1);
        large.put("report", "x".repeat(130900));
        var values = mapper.createObjectNode();
        values.set("inputData", large);
        values.set("expectData", inputError);
        assertThat(gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "A", "1", values, UUID.randomUUID()).changed()).isTrue();
        large.put("report", "x".repeat(131072));
        denied("INVALID_INPUT", () -> gradeSamples.updateGradeSample(owner.sid(), owner.principal(), story, 1,
                "A", "2", mapper.createObjectNode().set("inputData", large), UUID.randomUUID()));
        assertThat(versionRev(story)).isEqualTo(2);
        assertThat(gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1,
                "A", UUID.randomUUID()).item().inputData().path("report").asText()).hasSize(130900);
    }

    @Test
    void gradeSamplesHttpAcceptsExactRawSubfieldLimitAndRejectsNextByte() throws Exception {
        Fixture owner = account(true, false, (byte) -89);
        String story = create(owner, "원본 JSON 바이트 경계");
        gradeSamples.createGradeSample(owner.sid(), owner.principal(), story, 1, "0",
                mapper.createObjectNode().put("code", "A"), UUID.randomUUID());
        String base = "/admin/api/stories/" + story + "/versions/1/grade-samples/A";
        var csrfResponse = mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        Cookie csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String token = mapper.readTree(csrfResponse.getContentAsString()).path("token").asText();
        Cookie session = cookie(owner);
        long rev = 1;
        for (String field : java.util.List.of("inputData", "expectData")) {
            int limit = field.equals("inputData") ? 128 * 1024 : 64 * 1024;
            String raw = "{\"formatNo\":1" + " ".repeat(limit - "{\"formatNo\":1}".length()) + "}";
            assertThat(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(limit);
            String request = "{\"expectedRev\":\"" + rev + "\",\"changes\":{\"" + field + "\":" + raw + "}}";
            mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base),
                    session, csrf, token, request)).andExpect(status().isOk());
            rev++;
            String exceeded = "{\"expectedRev\":\"" + rev + "\",\"changes\":{\"" + field
                    + "\":" + raw.substring(0, raw.length() - 1) + " " + "}" + "}}";
            mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base),
                    session, csrf, token, exceeded)).andExpect(status().isUnprocessableEntity());
            assertThat(versionRev(story)).isEqualTo(rev);
        }
        var sample = gradeSamples.getGradeSampleDetail(owner.sid(), owner.principal(), story, 1, "A", UUID.randomUUID());
        assertThat(sample.item().inputData().path("formatNo").asInt()).isEqualTo(1);
        assertThat(sample.item().expectData().path("formatNo").asInt()).isEqualTo(1);
    }

    /** 전체 자식 원고·부모 시각·수정번호·쓰기 감사를 비교하고 읽기 감사는 제외한다. */
    private java.util.Map<String, Object> factState(String story, String key) {
        return db.queryForMap("SELECT v.edit_rev,v.updated_at,f.updated_at AS fact_time,f.active_yn,f.statement,f.truth,f.basis,"
                + "(SELECT count(*) FROM story_audit a WHERE a.version_id=v.id AND a.action LIKE 'ITEM_%') AS audits "
                + "FROM story_version v JOIN story_fact f ON f.version_id=v.id WHERE v.story_id=? AND f.code=?",
                storyId(story), key);
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
