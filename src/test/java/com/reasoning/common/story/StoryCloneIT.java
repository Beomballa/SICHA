package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.migration.EmbeddedSqlResourceProvider;
import com.reasoning.common.story.entity.StoryVersion;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.service.StoryCloneService;
import com.reasoning.common.story.service.StoryCloneService.ActionResult;
import com.reasoning.common.story.service.StoryCloneService.WorkVersionExists;
import com.reasoning.common.story.service.StoryService;

import jakarta.persistence.EntityManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 실제 격리 PG·HTTP·저장 인증·JPA의 SP-05 합성 회귀다. 합성 PUBLISHED는 실제 공개·사람 승인 증거가 아니다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StoryCloneIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    private static final List<Mapping> MAPPINGS =
            List.of(
                    new Mapping(
                            "persons",
                            "story_person",
                            "code,name,public_text,secret_text",
                            "code,name,publicText,secretText"),
                    new Mapping("roles", "story_role", "code,name,brief", "code,name,brief"),
                    new Mapping("pairs", "story_pair", "role_a,role_b", "roleA,roleB"),
                    new Mapping(
                            "clues",
                            "story_clue",
                            "code,title,body,person_code,scope,source_text",
                            "code,title,body,personCode,scope,sourceText"),
                    new Mapping(
                            "clueRoles", "clue_role", "clue_code,role_code", "clueCode,roleCode"),
                    new Mapping("hints", "story_hint", "code,level,body", "code,level,body"),
                    new Mapping(
                            "events",
                            "story_event",
                            "code,start_min,end_min,actual_text,apparent_text",
                            "code,startMin,endMin,actualText,apparentText"),
                    new Mapping(
                            "facts",
                            "story_fact",
                            "code,statement,truth,basis",
                            "code,statement,truth,basis"),
                    new Mapping(
                            "rubrics",
                            "story_rubric",
                            "code,category,max_score,required_yn,pass_score,accepted_text,partial_text,reject_text,rule_data",
                            "code,category,maxScore,requiredYn,passScore,acceptedText,partialText,rejectText,ruleData"),
                    new Mapping(
                            "rubricClues",
                            "rubric_clue",
                            "rubric_code,clue_code,link_text",
                            "rubricCode,clueCode,linkText"),
                    new Mapping(
                            "gradeSamples",
                            "grade_sample",
                            "code,input_data,expect_data,expected_score,expected_success,reason,checked_by",
                            "code,inputData,expectData,expectedScore,expectedSuccess,reason,checkedBy"));

    /** 폐기형 컨테이너와 합성 보안 키만 사용하며 reasoning 일반 DB는 참조하지 않는다. */
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
    @Autowired StoryCloneService clones;
    @Autowired MockMvc mvc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager manager;

    /** 실제 V1~19 초기화 재실행과 새 버전의 JPA 투영을 검사하며 스키마 대체를 만들지 않는다. */
    @Test
    void actualV19BootstrapRepeatsZeroAndJpaReadsClonedDraft() {
        var flyway =
                Flyway.configure()
                        .resourceProvider(new EmbeddedSqlResourceProvider())
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .locations("classpath:db/migration")
                        .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        Fixture f = fixture();
        ActionResult result = clone(f, f.owner(), UUID.randomUUID());
        long id = draftId(f);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            entities.clear();
                            StoryVersion version = entities.find(StoryVersion.class, id);
                            assertThat(version.storyId).isEqualTo(f.story());
                            assertThat(version.versionNo).isEqualTo(2);
                            assertThat(version.status).isEqualTo("DRAFT");
                            assertThat(version.editRev).isZero();
                            assertThat(version.currentSnapshotId).isNull();
                            assertThat(version.policyCode).isEqualTo("RULE_20260924");
                        });
        assertThat(result.current().get("storyRev")).isEqualTo("8");
        assertThat(
                        db.queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE"
                                        + " table_schema='public' AND table_name IN"
                                        + " ('story_release','evidence_set','evidence_item')",
                                String.class))
                .containsExactlyInAnyOrder("evidence_set", "evidence_item");
    }

    /** 공개 사본만 복제하며 변경된 원본 행·비활성 행·검수 기록을 새 초안으로 승계하지 않는다. */
    @Test
    void immutablePayloadAllElevenCollectionsExactNullUnicodeNumbersAndRealForeignKeys() {
        Fixture f = fixture();
        db.update(
                "UPDATE story_version SET"
                    + " title='MUTABLE_TITLE',method_answer='MUTABLE_ANSWER',policy_code='MUTABLE_POLICY'"
                    + " WHERE id=?",
                f.version());
        db.update(
                "UPDATE story_person SET name='MUTABLE_PERSON' WHERE version_id=? AND code='P1'",
                f.version());
        db.update(
                "UPDATE story_fact SET active_yn=false WHERE version_id=? AND code='F2'",
                f.version());
        db.update(
                "INSERT INTO story_person(version_id,code,name,active_yn) VALUES (?,'INACTIVE','비활성"
                        + " 원본',false)",
                f.version());
        db.update(
                "UPDATE admin_account SET active_yn=false WHERE id=?",
                f.checker().principal().accountId());
        db.update(
                "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE account_id=?",
                f.checker().principal().accountId());
        Map<String, Object> sourceBefore = source(f);
        Map<String, Object> storyBefore =
                db.queryForMap("SELECT * FROM story WHERE id=?", f.story());
        ActionResult result = clone(f, f.owner(), UUID.randomUUID());
        long draft = draftId(f);
        assertThat(draft).isNotEqualTo(f.version());
        assertThat(source(f)).isEqualTo(sourceBefore);
        assertChildren(f, draft);
        JsonNode sections =
                parse(
                        db.queryForObject(
                                "SELECT"
                                    + " jsonb_build_object('basic',jsonb_build_object('title',title,'intro',intro,'setting',setting,'difficulty',difficulty,'estMin',est_min,'estMax',est_max,'limitSec',limit_sec,'timelineOrigin',timeline_origin),'answer',jsonb_build_object('culpritCode',culprit_code,'methodAnswer',method_answer,'timeAnswer',time_answer,'motiveAnswer',motive_answer),'reveal',jsonb_build_object('revealText',reveal_text))::text"
                                    + " FROM story_version WHERE id=?",
                                String.class,
                                draft));
        assertThat(wire(sections)).isEqualTo(wire(f.payload().get("sections")));
        var version = db.queryForMap("SELECT * FROM story_version WHERE id=?", draft);
        assertThat(version.get("title"))
                .isEqualTo(f.payload().at("/sections/basic/title").textValue());
        assertThat(version.get("method_answer")).isEqualTo("원래 답안 😀\n둘째 줄");
        assertThat(version.get("source_snapshot_id")).isEqualTo(f.snapshot());
        assertThat(version.get("created_by")).isEqualTo(f.owner().principal().accountId());
        assertThat(version.get("updated_by")).isEqualTo(f.owner().principal().accountId());
        assertThat(version.get("current_snapshot_id")).isNull();
        assertThat(((Number) version.get("edit_rev")).longValue()).isZero();
        assertThat(version.get("active_yn")).isEqualTo(true);
        Map<String, Object> storyAfter =
                db.queryForMap("SELECT * FROM story WHERE id=?", f.story());
        Map<String, Object> preserved = new LinkedHashMap<>(storyAfter);
        preserved.put("edit_rev", storyBefore.get("edit_rev"));
        preserved.put("updated_at", storyBefore.get("updated_at"));
        assertThat(preserved).isEqualTo(storyBefore);
        assertThat(storyAfter.get("edit_rev")).isEqualTo(8L);
        assertThat(result.sourcePolicyCode()).isEqualTo("RULE_20260924");
        assertThat(result.policyCode()).isEqualTo("RULE_20260924");
        assertThat(result.policyDifferences()).isEmpty();
        assertThat(result.warnings())
                .anySatisfy(
                        w -> {
                            assertThat(w.code()).isEqualTo("POLICY_TIME_RANGE");
                            assertThat(w.field()).isEqualTo("basic.estMin");
                        });
        assertThat(result.draftPath()).isEqualTo("/admin/stories/" + f.code() + "/versions/2");
        assertThat(result.current()).doesNotContainKey("blocked");
        assertThat(result.original().toString()).doesNotContain("답안", "MUTABLE", "payload");
        JsonNode receipt =
                parse(
                        db.queryForObject(
                                "SELECT result_data::text FROM story_action WHERE id=?",
                                String.class,
                                Long.parseLong(result.actionId())));
        assertThat(receipt.get("formatNo").intValue()).isEqualTo(1);
        assertThat(wire(receipt.get("original"))).isEqualTo(wire(result.original()));
        assertThat(receipt.toString())
                .doesNotContain("원래 답안", "SELECTED_REPORT", "checkedBy", "payload");
        for (JsonNode audit :
                db.query(
                        "SELECT to_jsonb(a)::text FROM story_audit a WHERE story_id=? AND"
                                + " action='DRAFT_CLONED'",
                        (rs, index) -> parse(rs.getString(1)),
                        f.story())) {
            assertThat(audit.at("/detail/actionId").textValue()).isEqualTo(result.actionId());
            assertThat(audit.get("actor_id").longValue())
                    .isEqualTo(f.owner().principal().accountId());
            if (audit.at("/detail/revisionScope").textValue().equals("STORY")) {
                assertThat(audit.get("before_rev").longValue()).isEqualTo(7);
                assertThat(audit.get("after_rev").longValue()).isEqualTo(8);
                assertThat(audit.get("version_id").isNull()).isTrue();
            } else {
                assertThat(audit.at("/detail/revisionScope").textValue()).isEqualTo("CONTENT");
                assertThat(audit.get("before_rev").isNull()).isTrue();
                assertThat(audit.get("after_rev").longValue()).isZero();
                assertThat(audit.get("version_id").longValue()).isEqualTo(draft);
            }
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM review_snapshot WHERE version_id=?",
                                Integer.class,
                                draft))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_audit WHERE story_id=? AND"
                                        + " action='DRAFT_CLONED'",
                                Integer.class,
                                f.story()))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM grade_runtime", Integer.class)).isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_batch WHERE snapshot_id=?",
                                Integer.class,
                                f.snapshot()))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_sample WHERE version_id=? AND"
                                        + " checked_by IS NOT NULL",
                                Integer.class,
                                draft))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_pair p JOIN story_role a ON"
                                        + " (a.version_id,a.code)=(p.version_id,p.role_a) JOIN"
                                        + " story_role b ON"
                                        + " (b.version_id,b.code)=(p.version_id,p.role_b) WHERE"
                                        + " p.version_id=?",
                                Integer.class,
                                draft))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM rubric_clue r JOIN story_rubric a ON"
                                        + " (a.version_id,a.code)=(r.version_id,r.rubric_code) JOIN"
                                        + " story_clue c ON"
                                        + " (c.version_id,c.code)=(r.version_id,r.clue_code) WHERE"
                                        + " r.version_id=?",
                                Integer.class,
                                draft))
                .isEqualTo(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM clue_role r JOIN story_role a ON"
                                        + " (a.version_id,a.code)=(r.version_id,r.role_code) JOIN"
                                        + " story_clue c ON"
                                        + " (c.version_id,c.code)=(r.version_id,r.clue_code) WHERE"
                                        + " r.version_id=?",
                                Integer.class,
                                draft))
                .isEqualTo(2);
    }

    /** 현재 owner/EDIT만 필요하며 CREATE·REVIEW·PUBLISH·최근 재인증을 복제 조건으로 추가하지 않는다. */
    @Test
    void ownerAndActiveEditNeedNoGlobalPrivilegesOrRecentReauthentication() {
        Fixture owner = fixture();
        clone(owner, owner.owner(), UUID.randomUUID());
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        ActionResult result = clone(f, editor, UUID.randomUUID());
        assertThat(result.replayed()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT created_by FROM story_version WHERE id=?",
                                Long.class,
                                draftId(f)))
                .isEqualTo(editor.principal().accountId());
        assertThat(
                        db.queryForObject(
                                "SELECT reauth_at<clock_timestamp()-interval '5 minutes' FROM"
                                        + " admin_session WHERE account_id=?",
                                Boolean.class,
                                editor.principal().accountId()))
                .isTrue();
    }

    /** 관계·전역 권한의 조합이 EDIT를 대신하지 않으며 외부 사건과 사본 ID를 숨긴다. */
    @Test
    void outsidersReviewPublishAndInactiveEditCannotCloneOrProbeForeignSnapshots() {
        Fixture f = fixture();
        Account outsider = account(false);
        failure(() -> clone(f, outsider, UUID.randomUUID()), 404, "NOT_FOUND");
        for (String permission : List.of("REVIEW", "PUBLISH")) {
            Account actor = account(false);
            db.update(
                    "UPDATE admin_account SET can_review=true,can_publish=true WHERE id=?",
                    actor.principal().accountId());
            grant(f, actor, permission);
            failure(() -> clone(f, actor, UUID.randomUUID()), 403, "FORBIDDEN");
        }
        Account former = account(false);
        grant(f, former, "EDIT");
        db.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                f.story(),
                former.principal().accountId());
        failure(() -> clone(f, former, UUID.randomUUID()), 404, "NOT_FOUND");
        Fixture foreign = fixture();
        Map<String, Object> before = business(f);
        failure(
                () ->
                        clones.cloneDraft(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                "7",
                                1,
                                Long.toString(foreign.snapshot()),
                                UUID.randomUUID(),
                                UUID.randomUUID()),
                409,
                "SNAPSHOT_CONFLICT");
        assertThat(business(f)).isEqualTo(before);
    }

    /** 현재 인증은 재생보다 먼저 검사하며 회수한 관계·세션·자격으로 과거 키를 읽을 수 없다. */
    @Test
    void replayRevalidatesCurrentSessionAccountCredentialAndGrantBeforeKeyLookup() {
        for (String revoked :
                List.of("grant", "session", "account", "credential", "mfa", "expired")) {
            Fixture f = fixture();
            Account editor = account(false);
            grant(f, editor, "EDIT");
            UUID key = UUID.randomUUID();
            clone(f, editor, key);
            switch (revoked) {
                case "grant" ->
                        db.update(
                                "UPDATE story_access SET active_yn=false WHERE story_id=? AND"
                                        + " admin_id=?",
                                f.story(),
                                editor.principal().accountId());
                case "session" ->
                        db.update(
                                "UPDATE admin_session SET"
                                        + " state='REVOKED',revoked_at=clock_timestamp() WHERE"
                                        + " account_id=?",
                                editor.principal().accountId());
                case "account" ->
                        db.update(
                                "UPDATE admin_account SET active_yn=false WHERE id=?",
                                editor.principal().accountId());
                case "credential" ->
                        db.update(
                                "UPDATE admin_credential SET auth_rev=auth_rev+1 WHERE"
                                        + " account_id=?",
                                editor.principal().accountId());
                case "mfa" ->
                        db.update(
                                "UPDATE admin_credential SET mfa_state='RECOVERY' WHERE"
                                        + " account_id=?",
                                editor.principal().accountId());
                case "expired" ->
                        db.update(
                                "UPDATE admin_session SET last_action_at=started_at-interval '1"
                                        + " hour',started_at=started_at-interval '1"
                                        + " hour',expires_at=expires_at-interval '1"
                                        + " hour',reauth_at=reauth_at-interval '1 hour' WHERE"
                                        + " account_id=?",
                                editor.principal().accountId());
            }
            failure(
                    () -> clone(f, editor, key),
                    revoked.equals("grant") ? 404 : 401,
                    revoked.equals("grant") ? "NOT_FOUND" : "AUTH_REQUIRED");
        }
    }

    /** 계정 루트 잠금 대기 중 회수된 EDIT는 성공 영수증의 존재로 우회할 수 없다. */
    @Test
    void accessLossWhileOriginalAccountRootIsLockedRejectsReplay() throws Exception {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        UUID key = UUID.randomUUID();
        clone(f, editor, key);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future =
                    new TransactionTemplate(manager)
                            .execute(
                                    status -> {
                                        db.queryForObject(
                                                "SELECT id FROM admin_account WHERE id=? FOR"
                                                        + " UPDATE",
                                                Long.class,
                                                editor.principal().accountId());
                                        var task =
                                                pool.submit(
                                                        () -> {
                                                            attempted.countDown();
                                                            try {
                                                                clone(f, editor, key);
                                                                return "UNEXPECTED_SUCCESS";
                                                            } catch (AuthException denied) {
                                                                return denied.code();
                                                            }
                                                        });
                                        try {
                                            assertThat(attempted.await(5, TimeUnit.SECONDS))
                                                    .isTrue();
                                        } catch (InterruptedException interrupted) {
                                            throw new AssertionError(interrupted);
                                        }
                                        db.queryForObject(
                                                "SELECT id FROM story WHERE id=? FOR UPDATE",
                                                Long.class,
                                                f.story());
                                        db.update(
                                                "UPDATE story_access SET active_yn=false WHERE"
                                                        + " story_id=? AND admin_id=?",
                                                f.story(),
                                                editor.principal().accountId());
                                        return task;
                                    });
            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("NOT_FOUND");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_action WHERE story_id=?",
                                Integer.class,
                                f.story()))
                .isEqualTo(1);
    }

    /** 이전 소유자라는 사실은 CLONE 재생 권한을 만들지 않고 현재 EDIT를 다시 요구한다. */
    @Test
    void formerOwnerHasNoSpecialCloneReceiptReplayPrivilege() {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        clone(f, f.owner(), key);
        Account newOwner = account(false);
        db.update(
                "UPDATE story SET owner_id=?,edit_rev=edit_rev+1 WHERE id=?",
                newOwner.principal().accountId(),
                f.story());
        failure(() -> clone(f, f.owner(), key), 404, "NOT_FOUND");
        grant(f, f.owner(), "EDIT");
        assertThat(clone(f, f.owner(), key).replayed()).isTrue();
    }

    /** 원래 수정번호·공개본·사본·작업 상태는 성공 키 재생의 선행조건이 아니며 추가 변경을 하지 않는다. */
    @Test
    void exactReplaySurvivesRevisionsPublishedSnapshotAndWorkStateChanges() {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        ActionResult first = clone(f, f.owner(), key);
        db.update(
                "UPDATE story SET edit_rev=44,play_rev=12,published_id=NULL,view_yn=false WHERE"
                        + " id=?",
                f.story());
        db.update(
                "UPDATE story_version SET"
                    + " edit_rev=10,current_snapshot_id=NULL,status='DRAFT',active_yn=false WHERE"
                    + " id=?",
                draftId(f));
        long replacement =
                db.queryForObject(
                        "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,99,?::jsonb,?,?) RETURNING id",
                        Long.class,
                        f.version(),
                        wire(f.payload()),
                        UUID.randomUUID(),
                        f.owner().principal().accountId());
        db.update(
                "UPDATE story_version SET current_snapshot_id=?,edit_rev=99 WHERE id=?",
                replacement,
                f.version());
        Map<String, Object> before = business(f);
        ActionResult replay = clone(f, f.owner(), key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.changed()).isFalse();
        assertThat(replay.actionId()).isEqualTo(first.actionId());
        assertThat(wire(replay.original())).isEqualTo(wire(first.original()));
        assertThat(replay.current().get("storyRev")).isEqualTo("44");
        assertThat(replay.current().get("playRev")).isEqualTo("12");
        assertThat(replay.current().get("publishedVersionNo")).isNull();
        assertThat(business(f)).isEqualTo(before);
    }

    /** 현재 비활성 원고는 EDIT 재생에도 버전 상태·경로를 노출하지 않으며 owner만 읽는다. */
    @Test
    void replayAndWorkNoticeConcealInactiveDraftFromNonOwner() throws Exception {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        UUID key = UUID.randomUUID();
        clone(f, editor, key);
        db.update("UPDATE story_version SET active_yn=false WHERE id=?", draftId(f));
        ActionResult replay = clone(f, editor, key);
        assertThat(replay.draftPath()).isNull();
        assertThat(replay.current())
                .doesNotContainKeys("versionNo", "status", "editRev", "activeYn");
        var concealed = http(f, editor, body(f, "8", UUID.randomUUID()));
        assertThat(concealed.get("code").textValue()).isEqualTo("WORK_VERSION_EXISTS");
        assertThat(concealed.has("notice")).isFalse();
        var owner = http(f, f.owner(), body(f, "8", UUID.randomUUID()));
        assertThat(owner.at("/notice/versionNo").intValue()).isEqualTo(2);
        assertThat(owner.at("/notice/draftPath").textValue())
                .isEqualTo("/admin/stories/" + f.code() + "/versions/2");
    }

    /**
     * 실제 MVC 직렬화가 현재 공개번호의 명시적 null과 현재 작업본 접근 제한을 함께 보존하는지 검사한다.
     *
     * @param hidden true면 비활성 작업본의 현재 필드·경로를 편집자에게 숨기고, false면 활성 작업본을 반환한다.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaySerializesNullPublicationPointerWithoutInventingDraftAccess(boolean hidden)
            throws Exception {
        Fixture f = fixture();
        Account editor = account(false);
        grant(f, editor, "EDIT");
        UUID key = UUID.randomUUID();
        String command = body(f, "7", key);
        JsonNode first = http(f, editor, command);
        assertThat(first.get("replayed")).isEqualTo(BooleanNode.FALSE);
        assertThat(first.get("changed")).isEqualTo(BooleanNode.TRUE);

        db.update("UPDATE story SET published_id=NULL,view_yn=false WHERE id=?", f.story());
        if (hidden) {
            db.update("UPDATE story_version SET active_yn=false WHERE id=?", draftId(f));
        }
        Map<String, Object> before = business(f);

        var response =
                mvc.perform(authorized(endpoint(f), csrf()).cookie(cookie(editor)).content(command))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        UUID.fromString(response.getHeader("X-Request-Id"));
        JsonNode replay = parse(response.getContentAsString());
        assertThat(replay.get("replayed")).isEqualTo(BooleanNode.TRUE);
        assertThat(replay.get("changed")).isEqualTo(BooleanNode.FALSE);
        assertThat(replay.get("actionId")).isEqualTo(first.get("actionId"));
        assertThat(wire(replay.get("original"))).isEqualTo(wire(first.get("original")));
        for (String field :
                List.of("sourcePolicyCode", "policyCode", "policyDifferences", "warnings")) {
            assertThat(replay.get(field)).isEqualTo(first.get(field));
        }

        JsonNode current = replay.get("current");
        assertThat(current.has("publishedVersionNo")).isTrue();
        assertThat(current.get("publishedVersionNo").isNull()).isTrue();
        assertThat(current.get("viewYn")).isEqualTo(BooleanNode.FALSE);
        List<String> currentFields = new ArrayList<>();
        current.fieldNames().forEachRemaining(currentFields::add);
        if (hidden) {
            assertThat(currentFields)
                    .containsExactlyInAnyOrder(
                            "storyRev", "playRev", "viewYn", "publishedVersionNo");
            assertThat(replay.has("draftPath")).isFalse();
        } else {
            assertThat(currentFields)
                    .containsExactlyInAnyOrder(
                            "storyRev",
                            "playRev",
                            "viewYn",
                            "publishedVersionNo",
                            "versionNo",
                            "status",
                            "editRev",
                            "activeYn");
            assertThat(current.get("activeYn")).isEqualTo(BooleanNode.TRUE);
            assertThat(replay.get("draftPath").textValue())
                    .isEqualTo("/admin/stories/" + f.code() + "/versions/2");
        }
        assertThat(business(f)).isEqualTo(before);
    }

    /** 같은 key를 다른输入·행위자·행동으로 재사용하면 상태 검사 전에 충돌한다. */
    @Test
    void requestKeyConflictsForDifferentActorActionAndEveryNormalizedInput() {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        clone(f, f.owner(), key);
        for (String field : List.of("revision", "version", "snapshot")) {
            failure(
                    () ->
                            clones.cloneDraft(
                                    f.owner().sid(),
                                    f.owner().principal(),
                                    f.code(),
                                    field.equals("revision") ? "8" : "7",
                                    field.equals("version") ? 2 : 1,
                                    field.equals("snapshot")
                                            ? "9223372036854775807"
                                            : Long.toString(f.snapshot()),
                                    key,
                                    UUID.randomUUID()),
                    409,
                    "REQUEST_KEY_CONFLICT");
        }
        Account editor = account(false);
        grant(f, editor, "EDIT");
        failure(() -> clone(f, editor, key), 409, "REQUEST_KEY_CONFLICT");
        UUID otherAction = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " story_action(story_id,request_key,actor_id,action,request_data,result_data)"
                    + " VALUES (?,?,?,'OWNER_CLOSE','{}','{}')",
                f.story(),
                otherAction,
                f.owner().principal().accountId());
        failure(() -> clone(f, f.owner(), otherAction), 409, "REQUEST_KEY_CONFLICT");
    }

    /** 신규 의도의 현재 사건·버전·사본 충돌은 영수증과 업무 변경을 남기지 않는다. */
    @Test
    void newIntentRejectsStaleRevisionNoncurrentVersionSnapshotAndInactiveStory() {
        Fixture f = fixture();
        Map<String, Object> before = business(f);
        failure(
                () ->
                        clones.cloneDraft(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                "6",
                                1,
                                Long.toString(f.snapshot()),
                                UUID.randomUUID(),
                                UUID.randomUUID()),
                409,
                "EDIT_CONFLICT");
        failure(
                () ->
                        clones.cloneDraft(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                "7",
                                2,
                                Long.toString(f.snapshot()),
                                UUID.randomUUID(),
                                UUID.randomUUID()),
                409,
                "STATE_CONFLICT");
        failure(
                () ->
                        clones.cloneDraft(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                "7",
                                1,
                                "9223372036854775807",
                                UUID.randomUUID(),
                                UUID.randomUUID()),
                409,
                "SNAPSHOT_CONFLICT");
        assertThat(business(f)).isEqualTo(before);
        db.update("UPDATE story SET published_id=NULL,view_yn=false WHERE id=?", f.story());
        failure(() -> clone(f, f.owner(), UUID.randomUUID()), 409, "STATE_CONFLICT");
        db.update(
                "UPDATE story SET published_id=?,active_yn=false WHERE id=?",
                f.version(),
                f.story());
        failure(() -> clone(f, f.owner(), UUID.randomUUID()), 409, "STATE_CONFLICT");
    }

    /**
     * active 여부와 무관하게 실제 세 작업 상태를 차단한다.
     *
     * @param state DRAFT·REVIEW·READY 중 실제 작업 상태
     */
    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW", "READY"})
    void everyInactiveWorkingStateBlocksNewIntentWithoutForcedDeletion(String state) {
        Fixture f = fixture();
        long work =
                db.queryForObject(
                        "INSERT INTO"
                            + " story_version(story_id,version_no,title,policy_code,created_by,updated_by,active_yn)"
                            + " VALUES (?,2,'합성 작업본','RULE_20260924',?,?,false) RETURNING id",
                        Long.class,
                        f.story(),
                        f.owner().principal().accountId(),
                        f.owner().principal().accountId());
        if (!state.equals("DRAFT")) {
            long snapshot =
                    db.queryForObject(
                            "INSERT INTO"
                                + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                                + " VALUES (?,0,'{}',?,?) RETURNING id",
                            Long.class,
                            work,
                            UUID.randomUUID(),
                            f.owner().principal().accountId());
            db.update(
                    "UPDATE story_version SET status=?,current_snapshot_id=? WHERE id=?",
                    state,
                    snapshot,
                    work);
        }
        Map<String, Object> before = business(f);
        assertThatThrownBy(() -> clone(f, f.owner(), UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        WorkVersionExists.class,
                        error -> assertThat(error.notice().versionNo()).isEqualTo(2));
        assertThat(business(f)).isEqualTo(before);
    }

    /** int·bigint 상한을 더하기 전에 거절하며 영수증을 남기지 않는다. */
    @Test
    void versionAndStoryRevisionOverflowAreAtomicConflicts() {
        Fixture f = fixture();
        db.update("UPDATE story_version SET version_no=2147483647 WHERE id=?", f.version());
        Map<String, Object> before = business(f);
        failure(
                () ->
                        clones.cloneDraft(
                                f.owner().sid(),
                                f.owner().principal(),
                                f.code(),
                                "7",
                                Integer.MAX_VALUE,
                                Long.toString(f.snapshot()),
                                UUID.randomUUID(),
                                UUID.randomUUID()),
                409,
                "VERSION_LIMIT");
        assertThat(business(f)).isEqualTo(before);
        Fixture max = fixture();
        db.update("UPDATE story SET edit_rev=9223372036854775807 WHERE id=?", max.story());
        failure(
                () ->
                        clones.cloneDraft(
                                max.owner().sid(),
                                max.owner().principal(),
                                max.code(),
                                "9223372036854775807",
                                1,
                                Long.toString(max.snapshot()),
                                UUID.randomUUID(),
                                UUID.randomUUID()),
                409,
                "EDIT_CONFLICT");
    }

    /** codec의 실제 형식·식별자·정책 결속을 검사하되 품질 통과나 checker 현재 자격은 요구하지 않는다. */
    @Test
    void malformedFrozenPayloadAndWrongScopeRollbackWithoutCoercion() {
        List<Consumer<ObjectNode>> mutations =
                List.of(
                        payload -> payload.put("storyCode", "FOREIGN"),
                        payload -> payload.put("versionNo", 2),
                        payload -> payload.put("sourceRev", "55"),
                        payload -> payload.put("formatNo", 2),
                        payload ->
                                ((ObjectNode) payload.at("/sections/basic")).put("difficulty", "3"),
                        payload -> ((ObjectNode) payload.at("/sections/basic")).remove("intro"),
                        payload -> ((ObjectNode) payload.at("/policy")).put("attemptLimit", 99),
                        payload ->
                                ((ObjectNode) payload.at("/resources/clues/0"))
                                        .put("personCode", "MISSING"));
        for (Consumer<ObjectNode> mutation : mutations) {
            Fixture f = fixture();
            ObjectNode altered = f.payload().deepCopy();
            mutation.accept(altered);
            db.update(
                    "UPDATE review_snapshot SET payload=?::jsonb WHERE id=?",
                    wire(altered),
                    f.snapshot());
            Map<String, Object> before = business(f);
            failure(() -> clone(f, f.owner(), UUID.randomUUID()), 503, "STORY_UNAVAILABLE");
            assertThat(business(f)).isEqualTo(before);
        }
        Fixture format = fixture();
        db.update("UPDATE review_snapshot SET format_no=2 WHERE id=?", format.snapshot());
        failure(() -> clone(format, format.owner(), UUID.randomUUID()), 503, "STORY_UNAVAILABLE");
    }

    /** 같은 사건 잠금이 새 의도 둘을 직렬화하고 같은 키 둘은 성공 한 건과 재생 한 건으로 정리한다. */
    @Test
    void concurrentNewIntentsAndConcurrentSameKeyReplay() throws Exception {
        Fixture f = fixture();
        List<String> different = race(f, UUID.randomUUID(), UUID.randomUUID());
        assertThat(different).containsExactlyInAnyOrder("CLONED", "EDIT_CONFLICT");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_version WHERE story_id=?",
                                Integer.class,
                                f.story()))
                .isEqualTo(2);
        Fixture replay = fixture();
        UUID key = UUID.randomUUID();
        assertThat(race(replay, key, key)).containsExactlyInAnyOrder("CLONED", "REPLAYED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM story_action WHERE story_id=?",
                                Integer.class,
                                replay.story()))
                .isEqualTo(1);
    }

    /** 필수 감사·영수증 실패 또는 저장 트리거의 콘텐츠/소속/원본 변조는 모든 행을 되돌린다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "auditFail",
                "receiptFail",
                "auditDrop",
                "receiptDrop",
                "receiptDrift",
                "auditDrift",
                "childContent",
                "childInactive",
                "childScope",
                "sampleChecked",
                "versionContent",
                "sourceMutation",
                "storyMutation"
            })
    void mandatoryWritesAndTriggerDriftRollbackCompleteGraphAndSameKeyCanRetry(String mutation) {
        Fixture f = fixture();
        Fixture foreign = fixture();
        UUID key = UUID.randomUUID();
        Map<String, Object> before = business(f);
        Map<String, Object> foreignBefore = business(foreign);
        String function = "clone_probe_" + UUID.randomUUID().toString().replace("-", "");
        String table;
        String event;
        String body;
        switch (mutation) {
            case "auditFail" -> {
                table = "story_audit";
                event = "BEFORE INSERT";
                body =
                        "IF NEW.action='DRAFT_CLONED' THEN RAISE EXCEPTION 'synthetic audit"
                                + " outage'; END IF; RETURN NEW;";
            }
            case "receiptFail" -> {
                table = "story_action";
                event = "BEFORE INSERT";
                body =
                        "IF NEW.action='CLONE' THEN RAISE EXCEPTION 'synthetic receipt outage'; END"
                                + " IF; RETURN NEW;";
            }
            case "auditDrop" -> {
                table = "story_audit";
                event = "BEFORE INSERT";
                body = "IF NEW.action='DRAFT_CLONED' THEN RETURN NULL; END IF; RETURN NEW;";
            }
            case "receiptDrop" -> {
                table = "story_action";
                event = "BEFORE INSERT";
                body = "IF NEW.action='CLONE' THEN RETURN NULL; END IF; RETURN NEW;";
            }
            case "receiptDrift" -> {
                table = "story_action";
                event = "BEFORE INSERT";
                body =
                        "IF NEW.action='CLONE' THEN"
                            + " NEW.result_data=jsonb_set(NEW.result_data,'{original,storyRev}','\"99\"');"
                            + " END IF; RETURN NEW;";
            }
            case "auditDrift" -> {
                table = "story_audit";
                event = "BEFORE INSERT";
                body =
                        "IF NEW.action='DRAFT_CLONED' THEN NEW.detail='{}'::jsonb; END IF; RETURN"
                                + " NEW;";
            }
            case "childContent" -> {
                table = "story_person";
                event = "BEFORE INSERT";
                body = "NEW.secret_text='TRIGGER_DRIFT'; RETURN NEW;";
            }
            case "childInactive" -> {
                table = "story_person";
                event = "BEFORE INSERT";
                body = "NEW.active_yn=false; RETURN NEW;";
            }
            case "childScope" -> {
                table = "story_hint";
                event = "BEFORE INSERT";
                body =
                        "NEW.version_id="
                                + foreign.version()
                                + "; NEW.code='DRIFT_'||NEW.code; RETURN NEW;";
            }
            case "sampleChecked" -> {
                table = "grade_sample";
                event = "BEFORE INSERT";
                body = "NEW.checked_by=" + f.checker().principal().accountId() + "; RETURN NEW;";
            }
            case "versionContent" -> {
                table = "story_version";
                event = "BEFORE INSERT";
                body = "NEW.method_answer='TRIGGER_DRIFT'; RETURN NEW;";
            }
            case "sourceMutation" -> {
                table = "story_person";
                event = "AFTER INSERT";
                body =
                        "UPDATE story_person SET secret_text='TRIGGER_SOURCE' WHERE version_id="
                                + f.version()
                                + " AND code='P1'; RETURN NEW;";
            }
            case "storyMutation" -> {
                table = "story_action";
                event = "AFTER INSERT";
                body =
                        "UPDATE story SET play_rev=play_rev+1,view_yn=false WHERE id="
                                + f.story()
                                + "; RETURN NEW;";
            }
            default -> throw new AssertionError(mutation);
        }
        db.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                        + body
                        + " END $$");
        db.execute(
                "CREATE TRIGGER "
                        + function
                        + " "
                        + event
                        + " ON "
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            failure(() -> clone(f, f.owner(), key), 503, "STORY_UNAVAILABLE");
            assertThat(business(f)).isEqualTo(before);
            assertThat(business(foreign)).isEqualTo(foreignBefore);
        } finally {
            db.execute("DROP TRIGGER " + function + " ON " + table);
            db.execute("DROP FUNCTION " + function + "()");
        }
        assertThat(clone(f, f.owner(), key).replayed()).isFalse();
    }

    /** 주변 거래에 참여하거나 독립 거래로 fallback하지 않는다. */
    @Test
    void ambientTransactionIsRejectedWithoutBusinessMutation() {
        Fixture f = fixture();
        Map<String, Object> before = business(f);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status ->
                                failure(
                                        () -> clone(f, f.owner(), UUID.randomUUID()),
                                        503,
                                        "STORY_UNAVAILABLE"));
        assertThat(business(f)).isEqualTo(before);
    }

    /** MockMvc의 secure 요청으로 세션·CSRF·Origin·닫힌 8KiB 입력과 no-store를 검사하며 실제 TLS 증거는 아니다. */
    @Test
    void httpAuthCsrfClosedBodyStrictTypesNoStoreAndReplay() throws Exception {
        Fixture f = fixture();
        Csrf csrf = csrf();
        String path = endpoint(f);
        String valid = body(f, "7", UUID.randomUUID());
        assertThat(
                        mvc.perform(
                                        post(path)
                                                .secure(true)
                                                .contentType("application/json")
                                                .content(valid))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        mvc.perform(authorized(path, csrf).content(valid))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(
                        mvc.perform(
                                        authorized(path, csrf)
                                                .cookie(cookie(f.owner()))
                                                .with(
                                                        request -> {
                                                            request.removeHeader("Origin");
                                                            request.addHeader(
                                                                    "Origin",
                                                                    "https://foreign.example");
                                                            return request;
                                                        })
                                                .content(valid))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        List<String> invalid =
                List.of(
                        valid.replace("\"expectedStoryRev\":\"7\"", "\"expectedStoryRev\":7"),
                        valid.replace("\"expectedStoryRev\":\"7\"", "\"expectedStoryRev\":\"07\""),
                        valid.replace(
                                "\"expectedStoryRev\":\"7\"",
                                "\"expectedStoryRev\":\"9223372036854775808\""),
                        valid.replace("\"sourceVersionNo\":1", "\"sourceVersionNo\":\"1\""),
                        valid.replace("\"sourceVersionNo\":1", "\"sourceVersionNo\":1.0"),
                        valid.replace("\"sourceVersionNo\":1", "\"sourceVersionNo\":0"),
                        valid.replace("\"sourceVersionNo\":1", "\"sourceVersionNo\":2147483648"),
                        valid.replace(
                                "\"sourceSnapshotId\":\"" + f.snapshot() + "\"",
                                "\"sourceSnapshotId\":" + f.snapshot()),
                        valid.replace(
                                "\"sourceSnapshotId\":\"" + f.snapshot() + "\"",
                                "\"sourceSnapshotId\":null"),
                        valid.replace(
                                "\"sourceSnapshotId\":\"" + f.snapshot() + "\"",
                                "\"sourceSnapshotId\":\"0\""),
                        valid.replace("\"requestKey\":", "\"extra\":true,\"requestKey\":"),
                        valid.replace(
                                "\"expectedStoryRev\":",
                                "\"expectedStoryRev\":\"7\",\"expectedStoryRev\":"),
                        valid.replaceAll(
                                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                                "11111111-1111-1111-8111-111111111111"),
                        valid + " {}",
                        "[]");
        for (String body : invalid) {
            var response =
                    mvc.perform(authorized(path, csrf).cookie(cookie(f.owner())).content(body))
                            .andReturn()
                            .getResponse();
            assertThat(response.getStatus()).as(body).isEqualTo(400);
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
        }
        assertThat(
                        mvc.perform(
                                        authorized(path, csrf)
                                                .cookie(cookie(f.owner()))
                                                .content(" ".repeat(8193)))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(413);
        var first =
                mvc.perform(authorized(path, csrf).cookie(cookie(f.owner())).content(valid))
                        .andReturn()
                        .getResponse();
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getHeader("Cache-Control")).contains("no-store");
        JsonNode result = parse(first.getContentAsString());
        List<String> resultFields = new ArrayList<>();
        result.fieldNames().forEachRemaining(resultFields::add);
        assertThat(resultFields)
                .containsExactlyInAnyOrder(
                        "actionId",
                        "action",
                        "replayed",
                        "changed",
                        "original",
                        "current",
                        "draftPath",
                        "sourcePolicyCode",
                        "policyCode",
                        "policyDifferences",
                        "warnings");
        assertThat(result.get("sourcePolicyCode").textValue()).isEqualTo("RULE_20260924");
        assertThat(result.get("policyCode").textValue()).isEqualTo("RULE_20260924");
        assertThat(result.get("action").textValue()).isEqualTo("CLONE");
        assertThat(result.get("changed").booleanValue()).isTrue();
        var replay =
                mvc.perform(authorized(path, csrf).cookie(cookie(f.owner())).content(valid))
                        .andReturn()
                        .getResponse();
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(parse(replay.getContentAsString()).get("replayed").booleanValue()).isTrue();
        assertThat(replay.getContentAsString())
                .doesNotContain("원래 답안", "payload", "checkedBy", "blocked");
        Account outsider = account(false);
        var hidden =
                mvc.perform(authorized(path, csrf).cookie(cookie(outsider)).content(valid))
                        .andReturn()
                        .getResponse();
        assertThat(hidden.getStatus()).isEqualTo(404);
        assertThat(hidden.getContentAsString())
                .doesNotContain(f.code(), "versionNo", "sourceSnapshotId");
    }

    /** 복제 접근 이력은 실제 요청 UUID·행위자·응답 상태와 고정 경로만 기록한다. */
    @Test
    void cloneAccessHistoryUsesFixedRouteAndActualRequestIdentity() throws Exception {
        Fixture f = fixture();
        var response =
                mvc.perform(
                                authorized(endpoint(f), csrf())
                                        .cookie(cookie(f.owner()))
                                        .content(body(f, "7", UUID.randomUUID())))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        UUID requestId = UUID.fromString(response.getHeader("X-Request-Id"));
        var row =
                db.queryForMap(
                        "SELECT * FROM access_history WHERE request_id=? AND kind='SERVER'",
                        requestId);
        assertThat(row.get("route")).isEqualTo("/admin/api/stories/{storyCode}/drafts");
        assertThat(row.get("actor_kind")).isEqualTo("ADMIN");
        assertThat(row.get("actor_key")).isEqualTo(f.owner().principal().accountKey());
        assertThat(row.get("worker_key")).isNull();
        assertThat(row.get("method")).isEqualTo("POST");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(((Number) row.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("ended_at")).isNotNull();
        assertThat(row.get("route").toString())
                .doesNotContain(f.code(), "sourceSnapshotId", "requestKey");
    }

    /** 합성 PUBLISHED와 현재 사본을直接 준비한다. 실제 공개 서비스·품질 통과·사람 승인을 실행하지 않는다. */
    private Fixture fixture() {
        Account owner = account(true);
        Account checker = account(false);
        var created =
                stories.createStory(
                        owner.sid(),
                        owner.principal(),
                        UUID.randomUUID(),
                        "합성 복제 사건",
                        UUID.randomUUID());
        long story =
                db.queryForObject(
                        "SELECT id FROM story WHERE code=?", Long.class, created.storyCode());
        long version =
                db.queryForObject(
                        "SELECT id FROM story_version WHERE story_id=?", Long.class, story);
        ObjectNode source = FrozenSnapshotContractTest.complete();
        source.put("storyCode", created.storyCode()).put("sourceRev", "1");
        ((ObjectNode) source.at("/sections/basic"))
                .put("title", "합성 사건 😀")
                .put("estMin", 1)
                .put("estMax", 2)
                .putNull("timelineOrigin");
        ((ObjectNode) source.at("/sections/answer"))
                .put("methodAnswer", "원래 답안 😀\n둘째 줄")
                .putNull("timeAnswer");
        for (JsonNode row : source.at("/resources/gradeSamples"))
            ((ObjectNode) row).put("checkedBy", checker.principal().accountKey().toString());
        ObjectNode inputError = (ObjectNode) source.at("/resources/gradeSamples/2/inputData");
        inputError.set(
                "report",
                parse(
                        "{\"method\":9007199254740993,\"exact\":1.0000000000000000000000000000000000000001,\"raw\":\""
                            + " 원문\\r"
                            + "\\n"
                            + "😀 \",\"order\":[2,1,null]}"));
        ObjectNode payload = (ObjectNode) FrozenSnapshotCodec.freeze(source).payload();
        for (Mapping mapping : MAPPINGS)
            save(version, mapping, payload.get("resources"), checker.principal().accountId());
        JsonNode basic = payload.at("/sections/basic");
        JsonNode answer = payload.at("/sections/answer");
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
                value(payload.at("/sections/reveal/revealText")),
                version);
        long snapshot =
                db.queryForObject(
                        "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,1,?::jsonb,?,?) RETURNING id",
                        Long.class,
                        version,
                        wire(payload),
                        UUID.randomUUID(),
                        owner.principal().accountId());
        db.update(
                "UPDATE story_version SET status='PUBLISHED',current_snapshot_id=?,edit_rev=2 WHERE"
                        + " id=?",
                snapshot,
                version);
        db.update(
                "UPDATE story SET published_id=?,view_yn=true,play_rev=5,edit_rev=7 WHERE id=?",
                version,
                story);
        db.update(
                "INSERT INTO"
                    + " review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence,self_review_yn)"
                    + " VALUES (?,'STRUCTURE',?,'{\"syntheticOnly\":true}','INCOMPLETE',?,'합성 이력:"
                    + " 실제 품질 승인 아님',false)",
                snapshot,
                UUID.randomUUID(),
                checker.principal().accountId());
        db.update(
                "UPDATE admin_account SET can_create=false,can_review=false,can_publish=false WHERE"
                        + " id=?",
                owner.principal().accountId());
        return new Fixture(owner, checker, created.storyCode(), story, version, snapshot, payload);
    }

    /** 실제 열을 가진 합성 자료만 저장하며 제품 복제 로직을 fixture로 호출하지 않는다. */
    private void save(long version, Mapping mapping, JsonNode resources, long checker) {
        String[] fields = mapping.fields().split(",");
        List<String> placeholders = new ArrayList<>();
        for (String field : fields) placeholders.add(jsonField(field) ? "?::jsonb" : "?");
        for (JsonNode row : resources.get(mapping.resource())) {
            List<Object> values = new ArrayList<>();
            values.add(version);
            for (String field : fields)
                values.add(
                        field.equals("checkedBy")
                                ? checker
                                : jsonField(field)
                                        ? jsonValue(row.get(field))
                                        : value(row.get(field)));
            db.update(
                    "INSERT INTO "
                            + mapping.table()
                            + "(version_id,"
                            + mapping.columns()
                            + ") VALUES (?,"
                            + String.join(",", placeholders)
                            + ")",
                    values.toArray());
        }
    }

    /** 소속과 모든 실제 콘텐츠 컬럼을 사본과 비교하고 새 시간·활성·checker 초기화까지 대조한다. */
    private void assertChildren(Fixture f, long draft) {
        for (Mapping mapping : MAPPINGS) {
            String[] columns = mapping.columns().split(",");
            String[] fields = mapping.fields().split(",");
            List<String> expected = new ArrayList<>();
            for (JsonNode row : f.payload().get("resources").get(mapping.resource())) {
                ObjectNode value = f.payload().objectNode();
                value.put("version_id", draft).put("active_yn", true);
                for (int index = 0; index < columns.length; index++) {
                    if (fields[index].equals("checkedBy")) value.putNull(columns[index]);
                    else value.set(columns[index], row.get(fields[index]));
                }
                expected.add(wire(value));
            }
            List<String> actual =
                    db.query(
                            "SELECT to_jsonb(t)::text FROM "
                                    + mapping.table()
                                    + " t WHERE version_id=?",
                            (rs, index) -> {
                                ObjectNode row = (ObjectNode) parse(rs.getString(1));
                                assertThat(row.get("created_at")).isEqualTo(row.get("updated_at"));
                                row.remove(List.of("created_at", "updated_at"));
                                return wire(row);
                            },
                            draft);
            assertThat(actual.stream().sorted().toList())
                    .as(mapping.resource())
                    .isEqualTo(expected.stream().sorted().toList());
        }
    }

    /** 실제 계정·자격·Spring Session·DB 세션을 만들고 재인증 시각은 10분 전으로 둔다. */
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
                        "SELECT clock_timestamp()", (rs, index) -> rs.getTimestamp(1).toInstant());
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
        Instant started = now.minusSeconds(600);
        db.update(
                "INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " VALUES (?,?,?,1,'ACTIVE',?,?,?::timestamptz+interval '8 hours',?,?)",
                principal.sessionKey(),
                id,
                crypto.sessionHash(prepared.id()),
                Timestamp.from(started),
                Timestamp.from(now),
                Timestamp.from(started),
                Timestamp.from(started),
                Timestamp.from(started));
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

    private ActionResult clone(Fixture f, Account actor, UUID key) {
        return clones.cloneDraft(
                actor.sid(),
                actor.principal(),
                f.code(),
                "7",
                1,
                Long.toString(f.snapshot()),
                key,
                UUID.randomUUID());
    }

    private long draftId(Fixture f) {
        return db.queryForObject(
                "SELECT id FROM story_version WHERE story_id=? AND version_no=2",
                Long.class,
                f.story());
    }

    /** 원본 전체 행·사본·자식·검수·감사를 원문 그대로 비교해 해시만으로 무변경을 주장하지 않는다. */
    private Map<String, Object> source(Fixture f) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "version", db.queryForMap("SELECT * FROM story_version WHERE id=?", f.version()));
        for (Mapping mapping : MAPPINGS)
            result.put(
                    mapping.table(),
                    rowStrings(
                            "SELECT to_jsonb(t)::text FROM "
                                    + mapping.table()
                                    + " t WHERE version_id=?",
                            f.version()));
        result.put(
                "snapshot",
                rowStrings(
                        "SELECT to_jsonb(t)::text FROM review_snapshot t WHERE version_id=?",
                        f.version()));
        result.put(
                "records",
                rowStrings(
                        "SELECT to_jsonb(t)::text FROM review_record t WHERE snapshot_id=?",
                        f.snapshot()));
        result.put(
                "audits",
                rowStrings(
                        "SELECT to_jsonb(t)::text FROM story_audit t WHERE version_id=?",
                        f.version()));
        return result;
    }

    /** 테스트 사건의 실제 전체 그래프를 비교하며 실패 시 새 행이 한 행도 남지 않아야 한다. */
    private Map<String, Object> business(Fixture f) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("story", db.queryForMap("SELECT * FROM story WHERE id=?", f.story()));
        result.put(
                "versions",
                rowStrings(
                        "SELECT to_jsonb(t)::text FROM story_version t WHERE story_id=?",
                        f.story()));
        for (Mapping mapping : MAPPINGS)
            result.put(
                    mapping.table(),
                    rowStrings(
                            "SELECT to_jsonb(t)::text FROM "
                                    + mapping.table()
                                    + " t WHERE version_id IN (SELECT id FROM story_version WHERE"
                                    + " story_id=?)",
                            f.story()));
        for (String table :
                List.of("story_audit", "story_action", "story_access", "story_transfer"))
            result.put(
                    table,
                    rowStrings(
                            "SELECT to_jsonb(t)::text FROM " + table + " t WHERE story_id=?",
                            f.story()));
        result.put(
                "snapshots",
                rowStrings(
                        "SELECT to_jsonb(t)::text FROM review_snapshot t WHERE version_id IN"
                                + " (SELECT id FROM story_version WHERE story_id=?)",
                        f.story()));
        for (String table : List.of("review_record", "grade_batch", "grade_job", "execution_issue"))
            result.put(
                    table,
                    rowStrings(
                            "SELECT to_jsonb(t)::text FROM "
                                    + table
                                    + " t WHERE snapshot_id IN (SELECT s.id FROM review_snapshot s"
                                    + " JOIN story_version v ON v.id=s.version_id WHERE"
                                    + " v.story_id=?)",
                            f.story()));
        return result;
    }

    private List<String> rowStrings(String sql, Object... args) {
        return db.query(sql, (rs, index) -> wire(parse(rs.getString(1))), args).stream()
                .sorted()
                .toList();
    }

    private static void failure(Callable<?> action, int status, String code) {
        assertThatThrownBy(action::call)
                .isInstanceOfSatisfying(
                        AuthException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(status);
                            assertThat(error.code()).isEqualTo(code);
                        });
    }

    private List<String> race(Fixture f, UUID first, UUID second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> raceCall(f, first, barrier));
            var b = pool.submit(() -> raceCall(f, second, barrier));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private String raceCall(Fixture f, UUID key, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return clone(f, f.owner(), key).replayed() ? "REPLAYED" : "CLONED";
        } catch (AuthException failure) {
            return failure.code();
        }
    }

    private Cookie cookie(Account actor) {
        var response = new MockHttpServletResponse();
        sessions.issueCookie(actor.sid(), new MockHttpServletRequest(), response);
        return response.getCookie("__Host-admin-session");
    }

    private Csrf csrf() throws Exception {
        var response =
                mvc.perform(get("/admin/api/auth/csrf").secure(true)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return new Csrf(
                response.getCookie("__Host-admin-csrf"),
                parse(response.getContentAsString()).get("token").textValue());
    }

    private static MockHttpServletRequestBuilder authorized(String path, Csrf csrf) {
        return post(path)
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerPort(443);
                            return request;
                        })
                .cookie(csrf.cookie())
                .header("X-CSRF-TOKEN", csrf.token())
                .header("Origin", "https://localhost")
                .contentType("application/json");
    }

    private JsonNode http(Fixture f, Account actor, String body) throws Exception {
        var response =
                mvc.perform(authorized(endpoint(f), csrf()).cookie(cookie(actor)).content(body))
                        .andReturn()
                        .getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return parse(response.getContentAsString());
    }

    private static String endpoint(Fixture f) {
        return "/admin/api/stories/" + f.code() + "/drafts";
    }

    private static String body(Fixture f, String rev, UUID key) {
        return "{\"expectedStoryRev\":\""
                + rev
                + "\",\"sourceVersionNo\":1,\"sourceSnapshotId\":\""
                + f.snapshot()
                + "\",\"requestKey\":\""
                + key
                + "\"}";
    }

    private static boolean jsonField(String field) {
        return List.of("ruleData", "inputData", "expectData").contains(field);
    }

    private static Object value(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.decimalValue().intValueExact();
        throw new AssertionError("합성 저장 자료형");
    }

    private static String jsonValue(JsonNode node) {
        return node.isNull() ? null : wire(node);
    }

    private static String wire(JsonNode node) {
        return new String(SnapshotJson.encode(node), StandardCharsets.UTF_8);
    }

    private static JsonNode parse(String raw) {
        return SnapshotJson.parse(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Mapping(String resource, String table, String columns, String fields) {}

    private record Account(String sid, AdminPrincipal principal) {}

    private record Fixture(
            Account owner,
            Account checker,
            String code,
            long story,
            long version,
            long snapshot,
            ObjectNode payload) {}

    private record Csrf(Cookie cookie, String token) {}
}
