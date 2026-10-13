package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reasoning.common.member.LocalMemberAuthIT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 폐기형 PostgreSQL의 합성 검수 판단이다. 실제 사람 비참여 확인이나 운영 발급 근거가 아니다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(LocalMemberAuthIT.Installation.class)
public class BlindEligibilityIT extends LocalMemberAuthIT {
    @Autowired BlindEligibilityService eligibility;
    @Autowired com.reasoning.admin.auth.session.AdminSessionAdapter assessmentSessions;

    private record Fixture(UUID member, long snapshot, long story, long memberId) {}

    /** 실제 세션·CSRF를 통과한 엄격한 HTTP 입력만 평가 영수증을 만든다. */
    @Test
    void assessmentHttpRequiresExplicitConfirmationExactShapeAndCurrentReviewer() throws Exception {
        Fixture f = fixture();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var issued = new org.springframework.mock.web.MockHttpServletResponse();
        assessmentSessions.issueCookie(
                invitationSid, new org.springframework.mock.web.MockHttpServletRequest(), issued);
        var session = issued.getCookie("__Host-admin-session");
        var csrfResponse =
                mvc.perform(get("/admin/api/auth/csrf").secure(true))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var csrf = csrfResponse.getCookie("__Host-admin-csrf");
        String csrfToken = json.readTree(csrfResponse.getContentAsString()).path("token").asText();
        String path = "/admin/api/stories/" + invitationStoryCode + "/versions/1/blind-assessments";
        UUID requestKey = UUID.randomUUID();
        var body =
                json.createObjectNode()
                        .put("expectedRev", "0")
                        .put("snapshotId", Long.toString(f.snapshot()))
                        .put("memberKey", f.member().toString())
                        .put("decision", "ELIGIBLE")
                        .put("reasonCode", "NON_PARTICIPATION_CONFIRMED")
                        .put("verificationRef", "synthetic.http:01")
                        .put(
                                "checkedAt",
                                Instant.now()
                                        .minusSeconds(30)
                                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                        .toString())
                        .put("confirmed", true)
                        .putNull("supersedesKey")
                        .put("requestKey", requestKey.toString());
        java.util.function.Function<
                        com.fasterxml.jackson.databind.JsonNode,
                        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>
                request =
                        payload ->
                                post(path)
                                        .secure(true)
                                        .with(
                                                r -> {
                                                    r.setScheme("https");
                                                    r.setServerPort(443);
                                                    return r;
                                                })
                                        .header("Origin", "https://localhost")
                                        .header("X-CSRF-TOKEN", csrfToken)
                                        .cookie(session, csrf)
                                        .contentType("application/json")
                                        .content(payload.toString());
        mvc.perform(request.apply(body.deepCopy().put("confirmed", false)))
                .andExpect(status().isBadRequest());
        mvc.perform(request.apply(body.deepCopy().put("memberId", f.memberId())))
                .andExpect(status().isBadRequest());
        var response =
                mvc.perform(request.apply(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String key = json.readTree(response.getContentAsString()).path("assessmentKey").asText();
        assertThat(UUID.fromString(key).version()).isEqualTo(4);
        var replay =
                mvc.perform(request.apply(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        assertThat(json.readTree(replay.getContentAsString()).path("assessmentKey").asText())
                .isEqualTo(key);
        mvc.perform(get(path + "/" + key).secure(true).cookie(session)).andExpect(status().isOk());
        mvc.perform(get(path + "/" + key + "?extra=1").secure(true).cookie(session))
                .andExpect(status().isBadRequest());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_action WHERE request_key=?",
                                Integer.class,
                                requestKey))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM access_history WHERE actor_kind='ADMIN'"
                                        + " AND route=? AND http_status=201",
                                Integer.class,
                                "/admin/api/stories/{storyCode}/versions/{versionNo}/blind-assessments"))
                .isGreaterThanOrEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM access_history WHERE route LIKE ?",
                                Integer.class,
                                "%" + key + "%"))
                .isZero();
        db.update(
                "UPDATE admin_account SET can_review=false WHERE id=?",
                invitationActor.accountId());
        mvc.perform(get(path + "/" + key).secure(true).cookie(session))
                .andExpect(status().isForbidden());
    }

    private Fixture fixture() throws Exception {
        playtestPolicy();
        String a =
                account("blind-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String b =
                account("blind-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID member = invitations.getMemberIdentity(a, UUID.randomUUID()).memberKey();
        invitation(
                List.of(member, invitations.getMemberIdentity(b, UUID.randomUUID()).memberKey()));
        long snapshot =
                db.queryForObject(
                        "SELECT v.current_snapshot_id FROM story_version v JOIN story s ON"
                                + " s.id=v.story_id WHERE s.code=? AND v.version_no=1",
                        Long.class,
                        invitationStoryCode);
        long story =
                db.queryForObject(
                        "SELECT id FROM story WHERE code=?", Long.class, invitationStoryCode);
        long memberId =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?", Long.class, member);
        return new Fixture(member, snapshot, story, memberId);
    }

    private BlindEligibilityService.Assessment assess(
            Fixture f,
            String decision,
            String reason,
            Instant checked,
            UUID predecessor,
            UUID request) {
        return eligibility.create(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                0,
                f.snapshot(),
                f.member(),
                decision,
                reason,
                "synthetic.review:01",
                checked,
                true,
                predecessor,
                request,
                UUID.randomUUID());
    }

    private long inTransaction(java.util.function.Supplier<Long> action) {
        return new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()))
                .execute(status -> action.get());
    }

    /** 기존 FUNCTIONAL 초대에서 부모 사본/런타임 pin만 복사하는 합성 BLIND root; 서비스 입장 증거 아님. */
    private long syntheticBlindTest(Fixture f, long assessmentId) {
        long test =
                db.queryForObject(
                        """
                        INSERT INTO play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,runtime_epoch,
                          role_a,role_b,mode,invite_until,created_by)
                        SELECT ?,t.version_id,t.snapshot_id,t.runtime_id,t.config_hash,t.runtime_epoch,
                          t.role_a,t.role_b,'BLIND',now()+interval '7 days',t.created_by
                        FROM play_test t JOIN story_version v ON v.id=t.version_id
                        WHERE v.story_id=? LIMIT 1 RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        f.story());
        db.update(
                "INSERT INTO test_member(test_id,member_id,slot,assessment_id) VALUES (?,?,1,?)",
                test,
                f.memberId(),
                assessmentId);
        return test;
    }

    private void declaredSyntheticMember(long test, Fixture f) {
        db.update(
                """
                UPDATE test_member SET invite_state='ACCEPTED',accepted_at=now(),
                  accepted_policy_id=(SELECT r.policy_id FROM test_retention r JOIN play_test t
                    ON t.id=r.test_id JOIN story_version v ON v.id=t.version_id
                    WHERE v.story_id=? LIMIT 1),
                  accepted_notice_hash=(SELECT p.notice_hash FROM test_retention r
                    JOIN privacy_policy p ON p.id=r.policy_id JOIN play_test t ON t.id=r.test_id
                    JOIN story_version v ON v.id=t.version_id WHERE v.story_id=? LIMIT 1),
                  blind_declared=true WHERE test_id=? AND member_id=?
                """,
                f.story(),
                f.story(),
                test,
                f.memberId());
    }

    /** 회원을 미리 생성하지 않는 합성 Q 예약; 실제 검수 게임 생산/동의 증명 아님. */
    private long syntheticBlindRoom(Fixture f, long assessmentId) {
        return db.queryForObject(
                """
                INSERT INTO game_room(room_key,purpose,mode,qualification_kind,reviewer_id,
                  selected_member_id,story_id,version_id,snapshot_id,runtime_id,runtime_epoch,
                  config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,
                  max_attempts,max_hints,lobby_until,assessment_id)
                SELECT ?,'REVIEW_SOLO_QUALIFICATION','SOLO','BLIND',t.created_by,?,v.story_id,
                  t.version_id,t.snapshot_id,t.runtime_id,t.runtime_epoch,t.config_hash,
                  repeat('a',64),r.policy_id,0,'WAITING',1,60,3,0,now()+interval '30 minutes',?
                FROM play_test t JOIN story_version v ON v.id=t.version_id
                  JOIN test_retention r ON r.test_id=t.id WHERE v.story_id=? LIMIT 1 RETURNING id
                """,
                Long.class,
                UUID.randomUUID(),
                f.memberId(),
                assessmentId,
                f.story());
    }

    @Test
    void ownQualificationReservationDoesNotBlockPreAcceptButStillRequiresDeclarationForExposure()
            throws Exception {
        Fixture f = fixture();
        var assessment =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        Instant.now().minusSeconds(30),
                        null,
                        UUID.randomUUID());
        long id =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        assessment.assessmentKey());
        long room = syntheticBlindRoom(f, id);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_member WHERE room_id=?",
                                Integer.class,
                                room))
                .isZero();
        inTransaction(
                () -> {
                    eligibility.requirePinnedGameAdmission(
                            id, f.story(), f.snapshot(), f.memberId(), room);
                    return id;
                });
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () -> {
                                            eligibility.requirePinnedGameExposure(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    room);
                                            return id;
                                        }))
                .hasMessageContaining("BLIND_INELIGIBLE");
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () ->
                                                eligibility.requireCurrentForAdmission(
                                                        f.story(), f.snapshot(), f.memberId())))
                .hasMessageContaining("BLIND_INELIGIBLE");
    }

    @Test
    void typedTestAdmissionAndOwnFirstExposureUseOnlyStoredProvenance() throws Exception {
        Fixture f = fixture();
        var assessment =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        Instant.now().minusSeconds(30),
                        null,
                        UUID.randomUUID());
        long id =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        assessment.assessmentKey());
        long test = syntheticBlindTest(f, id);
        assertThat(
                        inTransaction(
                                () -> {
                                    eligibility.requirePinnedTestAdmission(
                                            id, f.story(), f.snapshot(), f.memberId(), test);
                                    return id;
                                }))
                .isEqualTo(id);
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () -> {
                                            eligibility.requirePinnedTestExposure(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    test);
                                            return id;
                                        }))
                .hasMessageContaining("BLIND_INELIGIBLE");
        declaredSyntheticMember(test, f);
        inTransaction(
                () -> {
                    eligibility.requirePinnedTestExposure(
                            id, f.story(), f.snapshot(), f.memberId(), test);
                    return id;
                });
        db.update(
                "INSERT INTO test_exposure(story_id,member_id,first_test_id) VALUES (?,?,?)",
                f.story(),
                f.memberId(),
                test);
        inTransaction(
                () -> {
                    eligibility.requirePinnedTestExposure(
                            id, f.story(), f.snapshot(), f.memberId(), test);
                    return id;
                });
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () -> {
                                            eligibility.requirePinnedTestAdmission(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    test);
                                            return id;
                                        }))
                .hasMessageContaining("BLIND_INELIGIBLE");
        var negative =
                assess(
                        f,
                        "INELIGIBLE",
                        "PARTICIPATION_FOUND",
                        Instant.now().minusSeconds(10),
                        assessment.assessmentKey(),
                        UUID.randomUUID());
        assertThat(negative.current()).isFalse();
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () -> {
                                            eligibility.requirePinnedTestExposure(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    test);
                                            return id;
                                        }))
                .hasMessageContaining("BLIND_INELIGIBLE");
    }

    @Test
    void nullOrOtherSourceIsNotOwnExposureAndReadOnlyTransactionIsRejected() throws Exception {
        Fixture f = fixture();
        var assessment =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        Instant.now().minusSeconds(30),
                        null,
                        UUID.randomUUID());
        long id =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        assessment.assessmentKey());
        long test = syntheticBlindTest(f, id);
        declaredSyntheticMember(test, f);
        var readOnly =
                new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        readOnly.setReadOnly(true);
        assertThatThrownBy(
                        () ->
                                readOnly.execute(
                                        status -> {
                                            eligibility.requirePinnedTestAdmission(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    test);
                                            return null;
                                        }))
                .hasMessageContaining("STORY_UNAVAILABLE");
        db.update(
                "INSERT INTO test_exposure(story_id,member_id) VALUES (?,?)",
                f.story(),
                f.memberId());
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () -> {
                                            eligibility.requirePinnedTestExposure(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    test);
                                            return id;
                                        }))
                .hasMessageContaining("BLIND_INELIGIBLE");
    }

    @Test
    void otherTestSourceCannotBeClaimedAsOwnFirstExposure() throws Exception {
        Fixture f = fixture();
        var assessment =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        Instant.now().minusSeconds(30),
                        null,
                        UUID.randomUUID());
        long id =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        assessment.assessmentKey());
        long first = syntheticBlindTest(f, id);
        long other = syntheticBlindTest(f, id);
        declaredSyntheticMember(first, f);
        db.update(
                "INSERT INTO test_exposure(story_id,member_id,first_test_id) VALUES (?,?,?)",
                f.story(),
                f.memberId(),
                other);
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () -> {
                                            eligibility.requirePinnedTestExposure(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    first);
                                            return id;
                                        }))
                .hasMessageContaining("BLIND_INELIGIBLE");
    }

    @Test
    void originalAssessmentRevocationInvalidatesOwnHistoricalFirstExposure() throws Exception {
        Fixture f = fixture();
        var assessment =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        Instant.now().minusSeconds(30),
                        null,
                        UUID.randomUUID());
        long id =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        assessment.assessmentKey());
        long test = syntheticBlindTest(f, id);
        declaredSyntheticMember(test, f);
        db.update(
                "INSERT INTO test_exposure(story_id,member_id,first_test_id) VALUES (?,?,?)",
                f.story(),
                f.memberId(),
                test);
        inTransaction(
                () -> {
                    eligibility.requirePinnedTestExposure(
                            id, f.story(), f.snapshot(), f.memberId(), test);
                    return id;
                });
        eligibility.revoke(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                assessment.assessmentKey(),
                0,
                "EVIDENCE_INVALID",
                "synthetic.revoke:03",
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () -> {
                                            eligibility.requirePinnedTestExposure(
                                                    id,
                                                    f.story(),
                                                    f.snapshot(),
                                                    f.memberId(),
                                                    test);
                                            return id;
                                        }))
                .hasMessageContaining("BLIND_INELIGIBLE");
    }

    @Test
    void reviewerAssessmentIsBoundToActualMemberSnapshotAndDurableReceipt() throws Exception {
        Fixture f = fixture();
        UUID request = UUID.randomUUID();
        Instant checked = Instant.now().minusSeconds(30);
        var created = assess(f, "ELIGIBLE", "NON_PARTICIPATION_CONFIRMED", checked, null, request);
        assertThat(created.current()).isTrue();
        assertThat(
                        assess(f, "ELIGIBLE", "NON_PARTICIPATION_CONFIRMED", checked, null, request)
                                .assessmentKey())
                .isEqualTo(created.assessmentKey());
        assertThat(
                        eligibility
                                .detail(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        created.assessmentKey(),
                                        UUID.randomUUID())
                                .current())
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM blind_assessment WHERE assessment_key=? AND"
                                        + " story_id=? AND snapshot_id=? AND member_id=?",
                                Integer.class,
                                created.assessmentKey(),
                                f.story(),
                                f.snapshot(),
                                f.memberId()))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_action WHERE request_key=? AND"
                                        + " action='BLIND_ASSESSMENT_CREATE'",
                                Integer.class,
                                request))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_audit WHERE"
                                        + " action='BLIND_ASSESSMENT_CREATE' AND scope_key=?",
                                Integer.class,
                                "assessment:" + created.assessmentKey()))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                assess(
                                        f,
                                        "INELIGIBLE",
                                        "PARTICIPATION_FOUND",
                                        checked,
                                        null,
                                        request))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE blind_assessment SET decision='INELIGIBLE' WHERE"
                                                + " assessment_key=?",
                                        created.assessmentKey()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "DELETE FROM blind_assessment WHERE assessment_key=?",
                                        created.assessmentKey()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.execute("TRUNCATE blind_assessment CASCADE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void unknownExposureAndCorrectionDoNotDefaultToEligible() throws Exception {
        Fixture f = fixture();
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(
                                                new DataSourceTransactionManager(
                                                        db.getDataSource()))
                                        .execute(
                                                status ->
                                                        eligibility.requireCurrentForAdmission(
                                                                f.story(),
                                                                f.snapshot(),
                                                                f.memberId())))
                .hasMessageContaining("BLIND_INELIGIBLE");
        Instant checked = Instant.now().minusSeconds(30);
        var negative =
                assess(f, "INELIGIBLE", "PARTICIPATION_FOUND", checked, null, UUID.randomUUID());
        assertThat(negative.current()).isFalse();
        assertThatThrownBy(
                        () ->
                                assess(
                                        f,
                                        "ELIGIBLE",
                                        "NON_PARTICIPATION_CONFIRMED",
                                        checked,
                                        null,
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        var corrected =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        checked,
                        negative.assessmentKey(),
                        UUID.randomUUID());
        assertThat(corrected.current()).isTrue();
        db.update(
                "INSERT INTO test_exposure(story_id,member_id) VALUES (?,?)",
                f.story(),
                f.memberId());
        assertThat(
                        eligibility
                                .detail(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        corrected.assessmentKey(),
                                        UUID.randomUUID())
                                .current())
                .isFalse();
        assertThatThrownBy(
                        () ->
                                assess(
                                        f,
                                        "ELIGIBLE",
                                        "NON_PARTICIPATION_CONFIRMED",
                                        checked,
                                        corrected.assessmentKey(),
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
    }

    @Test
    void revocationIsOneTimeAndReplaysShowRevokedState() throws Exception {
        Fixture f = fixture();
        var first =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        Instant.now().minusSeconds(30),
                        null,
                        UUID.randomUUID());
        UUID request = UUID.randomUUID();
        var revoked =
                eligibility.revoke(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        first.assessmentKey(),
                        0,
                        "EVIDENCE_INVALID",
                        "synthetic.revoke:01",
                        request,
                        UUID.randomUUID());
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.current()).isFalse();
        assertThat(
                        eligibility
                                .revoke(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        first.assessmentKey(),
                                        0,
                                        "EVIDENCE_INVALID",
                                        "synthetic.revoke:01",
                                        request,
                                        UUID.randomUUID())
                                .revokedAt())
                .isEqualTo(revoked.revokedAt());
        assertThatThrownBy(
                        () ->
                                eligibility.revoke(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        first.assessmentKey(),
                                        0,
                                        "ASSESSMENT_ERROR",
                                        "synthetic.revoke:01",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE blind_assessment SET revoked_at=NULL WHERE"
                                                + " assessment_key=?",
                                        first.assessmentKey()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void invalidIntentCannotIssueAnAssessment() throws Exception {
        Fixture f = fixture();
        Instant checked = Instant.now();
        assertThatThrownBy(
                        () ->
                                eligibility.create(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        0,
                                        f.snapshot(),
                                        f.member(),
                                        "ELIGIBLE",
                                        "NON_PARTICIPATION_CONFIRMED",
                                        "synthetic.review:01",
                                        checked,
                                        false,
                                        null,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("INVALID_REQUEST");
        assertThatThrownBy(
                        () ->
                                assess(
                                        f,
                                        "ELIGIBLE",
                                        "NON_PARTICIPATION_CONFIRMED",
                                        checked.minusSeconds(86401),
                                        null,
                                        UUID.randomUUID()))
                .hasMessageContaining("INVALID_REQUEST");
        assertThatThrownBy(
                        () ->
                                eligibility.create(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        0,
                                        f.snapshot() + 1,
                                        f.member(),
                                        "ELIGIBLE",
                                        "NON_PARTICIPATION_CONFIRMED",
                                        "synthetic.review:01",
                                        checked,
                                        true,
                                        null,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM blind_assessment WHERE story_id=?",
                                Integer.class,
                                f.story()))
                .isZero();
    }

    @Test
    void currentReviewerAuthorityAndParticipantBindingsCannotBeBorrowed() throws Exception {
        Fixture f = fixture();
        var created =
                assess(
                        f,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        Instant.now().minusSeconds(30),
                        null,
                        UUID.randomUUID());
        long assessmentId =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        created.assessmentKey());
        long test =
                db.queryForObject(
                        "SELECT t.id FROM play_test t JOIN story_version v ON v.id=t.version_id"
                                + " WHERE v.story_id=?",
                        Long.class,
                        f.story());
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE test_member SET assessment_id=? WHERE test_id=? AND"
                                                + " member_id=?",
                                        assessmentId,
                                        test,
                                        f.memberId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE blind_assessment SET member_id=? WHERE id=?",
                                        f.memberId() + 1,
                                        assessmentId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE blind_assessment SET snapshot_id=? WHERE id=?",
                                        f.snapshot() + 1,
                                        assessmentId))
                .isInstanceOf(DataAccessException.class);
        long admin = invitationActor.accountId();
        db.update("UPDATE admin_account SET can_review=false WHERE id=?", admin);
        assertThatThrownBy(
                        () ->
                                eligibility.detail(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        created.assessmentKey(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FORBIDDEN");
        assertThatThrownBy(
                        () ->
                                eligibility.revoke(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        created.assessmentKey(),
                                        0,
                                        "EVIDENCE_INVALID",
                                        "synthetic.revoke:02",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FORBIDDEN");
    }

    @Test
    void mandatoryAuditFailureRollsBackReceiptAndAssessment() throws Exception {
        Fixture f = fixture();
        UUID request = UUID.randomUUID();
        db.execute(
                """
                CREATE FUNCTION public.reject_blind_audit_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.action='BLIND_ASSESSMENT_CREATE' THEN
                  RAISE EXCEPTION 'synthetic audit failure' USING ERRCODE='23514'; END IF;
                  RETURN NEW; END $$
                """);
        db.execute(
                "CREATE TRIGGER tr_reject_blind_audit BEFORE INSERT ON test_audit FOR EACH ROW"
                        + " EXECUTE FUNCTION public.reject_blind_audit_test()");
        try {
            assertThatThrownBy(
                            () ->
                                    assess(
                                            f,
                                            "ELIGIBLE",
                                            "NON_PARTICIPATION_CONFIRMED",
                                            Instant.now().minusSeconds(30),
                                            null,
                                            request))
                    .hasMessageContaining("STORY_UNAVAILABLE");
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM test_action WHERE request_key=?",
                                    Integer.class,
                                    request))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM blind_assessment WHERE story_id=?",
                                    Integer.class,
                                    f.story()))
                    .isZero();
        } finally {
            db.execute("DROP TRIGGER tr_reject_blind_audit ON test_audit");
            db.execute("DROP FUNCTION public.reject_blind_audit_test()");
        }
    }
}
