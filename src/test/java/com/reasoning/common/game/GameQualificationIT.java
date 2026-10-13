package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reasoning.common.game.service.GameQualificationService;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

/** 폐기형 PostgreSQL의 원본 마이그레이션과 합성 정책·실재 세션으로 검수 실행의 생성·수락 경계를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
public class GameQualificationIT extends PlaytestReportFixture {
    @Autowired GameQualificationService qualifications;

    @Test
    void reviewerAuthorityReceiptAndActualMemberAcceptance() throws Exception {
        assertThat(
                        db.queryForObject(
                                "SELECT max(version::integer) FROM flyway_schema_history WHERE"
                                        + " success",
                                Integer.class))
                .isEqualTo(42);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .validate();
        playtestPolicy();
        var selected = account("qualification-selected-" + UUID.randomUUID() + "@example.invalid");
        var other = account("qualification-other-" + UUID.randomUUID() + "@example.invalid");
        String token = selected.path("accessToken").asText();
        UUID selectedKey = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID otherKey =
                invitations
                        .getMemberIdentity(other.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID invitation = invitation(List.of(selectedKey, otherKey));
        long snapshot =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE test_key=?",
                        Long.class,
                        invitation);
        long story =
                db.queryForObject(
                        "SELECT id FROM story WHERE code=?", Long.class, invitationStoryCode);
        long selectedId =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?",
                        Long.class,
                        selectedKey);
        long otherId =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?", Long.class, otherKey);
        String policyCode =
                db.queryForObject(
                        "SELECT code FROM privacy_policy WHERE scope='PLAYTEST' AND state='ACTIVE'",
                        String.class);
        String noticeHash =
                db.queryForObject(
                        "SELECT notice_hash FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'",
                        String.class);
        UUID request = UUID.randomUUID();
        var created =
                qualifications.createQualification(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        2,
                        snapshot,
                        "R1",
                        "R2",
                        "H5_SYNTHETIC",
                        selectedKey,
                        "FUNCTIONAL",
                        policyCode,
                        noticeHash,
                        request,
                        UUID.randomUUID());
        assertThat(created.replayed()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_member WHERE room_id=(SELECT id FROM"
                                        + " game_room WHERE room_key=?)",
                                Integer.class,
                                created.roomKey()))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_audit WHERE"
                                    + " action='QUALIFICATION_CREATE' AND room_id=(SELECT id FROM"
                                    + " game_room WHERE room_key=?)",
                                Integer.class,
                                created.roomKey()))
                .isEqualTo(1);
        var replay =
                qualifications.createQualification(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        2,
                        snapshot,
                        "R1",
                        "R2",
                        "H5_SYNTHETIC",
                        selectedKey,
                        "FUNCTIONAL",
                        policyCode,
                        noticeHash,
                        request,
                        UUID.randomUUID());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.roomKey()).isEqualTo(created.roomKey());
        assertThatThrownBy(
                        () ->
                                qualifications.createQualification(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        2,
                                        snapshot,
                                        "R1",
                                        "R2",
                                        "H5_SYNTHETIC",
                                        otherKey,
                                        "FUNCTIONAL",
                                        policyCode,
                                        noticeHash,
                                        request,
                                        UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                qualifications.getAcceptanceNotice(
                                        other.path("accessToken").asText(),
                                        created.roomKey(),
                                        UUID.randomUUID()))
                .hasMessageContaining("ROOM_UNAVAILABLE");
        var notice =
                qualifications.getAcceptanceNotice(token, created.roomKey(), UUID.randomUUID());
        assertThat(notice.noticeHash()).isEqualTo(noticeHash);
        UUID acceptanceKey = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                qualifications.acceptQualification(
                                        token,
                                        created.roomKey(),
                                        false,
                                        policyCode,
                                        "0".repeat(64),
                                        acceptanceKey,
                                        UUID.randomUUID()))
                .hasMessageContaining("POLICY_CHANGED");
        var accepted =
                qualifications.acceptQualification(
                        token,
                        created.roomKey(),
                        false,
                        policyCode,
                        noticeHash,
                        acceptanceKey,
                        UUID.randomUUID());
        assertThat(accepted.replayed()).isFalse();
        String endpoint = "/api/qualifications/" + created.roomKey();
        mvc.perform(
                        get(endpoint + "/notice")
                                .secure(true)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomKey").value(created.roomKey().toString()));
        mvc.perform(
                        post(endpoint + "/accept")
                                .secure(true)
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                java.util.Map.of(
                                                        "blindDeclared",
                                                        false,
                                                        "policyCode",
                                                        policyCode,
                                                        "noticeHash",
                                                        noticeHash,
                                                        "requestKey",
                                                        acceptanceKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(
                        post(endpoint + "/accept")
                                .secure(true)
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                java.util.Map.of(
                                                        "policyCode",
                                                        policyCode,
                                                        "noticeHash",
                                                        noticeHash,
                                                        "requestKey",
                                                        UUID.randomUUID(),
                                                        "purpose",
                                                        "PUBLIC"))))
                .andExpect(status().isBadRequest());
        assertThat(
                        qualifications
                                .acceptQualification(
                                        token,
                                        created.roomKey(),
                                        false,
                                        policyCode,
                                        noticeHash,
                                        acceptanceKey,
                                        UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT member_id=? AND accepted_at IS NOT NULL FROM game_member"
                                    + " WHERE room_id=(SELECT id FROM game_room WHERE room_key=?)",
                                Boolean.class,
                                selectedId,
                                created.roomKey()))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_audit WHERE"
                                    + " action='QUALIFICATION_ACCEPT' AND room_id=(SELECT id FROM"
                                    + " game_room WHERE room_key=?)",
                                Integer.class,
                                created.roomKey()))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "DELETE FROM game_action WHERE request_key=?",
                                        acceptanceKey))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasStackTraceContaining("public game erasure unauthorized");
        UUID startKey = UUID.randomUUID();
        var started =
                qualifications.startQualification(
                        token, created.roomKey(), 0, startKey, UUID.randomUUID());
        assertThat(started.state()).isEqualTo("RUNNING");
        assertThat(started.rev()).isEqualTo("1");
        var startReplay =
                qualifications.startQualification(
                        token, created.roomKey(), 0, startKey, UUID.randomUUID());
        assertThat(startReplay.replayed()).isTrue();
        assertThat(startReplay.startedAt()).isEqualTo(started.startedAt());
        var materials = qualifications.getMaterials(token, created.roomKey(), UUID.randomUUID());
        assertThat(materials.roles()).hasSize(2);
        assertThat(materials.roles().toString()).doesNotContain("answer", "revealText");
        assertThat(
                        db.queryForObject(
                                "SELECT first_test_id IS NULL FROM test_exposure WHERE story_id=?"
                                        + " AND member_id=?",
                                Boolean.class,
                                story,
                                selectedId))
                .isTrue();
        var exposedAt =
                db.queryForMap(
                        "SELECT * FROM test_exposure WHERE story_id=? AND member_id=?",
                        story,
                        selectedId);
        qualifications.getMaterials(token, created.roomKey(), UUID.randomUUID());
        assertThat(
                        db.queryForMap(
                                "SELECT * FROM test_exposure WHERE story_id=? AND member_id=?",
                                story,
                                selectedId))
                .isEqualTo(exposedAt);
        mvc.perform(
                        get(endpoint + "/materials")
                                .secure(true)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.rev").value("1"));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_exposure WHERE story_id=? AND"
                                        + " member_id=?",
                                Integer.class,
                                story,
                                otherId))
                .isZero();
    }

    @Test
    void blindRejectsPriorMaterialExposureAndOwnerWithoutReviewGrant() throws Exception {
        playtestPolicy();
        var first = account("qualification-exposure-" + UUID.randomUUID() + "@example.invalid");
        var second = account("qualification-companion-" + UUID.randomUUID() + "@example.invalid");
        UUID selected =
                invitations
                        .getMemberIdentity(first.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID companion =
                invitations
                        .getMemberIdentity(second.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID invitation = invitation(List.of(selected, companion));
        long snapshot =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE test_key=?",
                        Long.class,
                        invitation);
        long story =
                db.queryForObject(
                        "SELECT id FROM story WHERE code=?", Long.class, invitationStoryCode);
        long member =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?", Long.class, selected);
        db.update("INSERT INTO test_exposure(story_id,member_id) VALUES (?,?)", story, member);
        String policyCode =
                db.queryForObject(
                        "SELECT code FROM privacy_policy WHERE scope='PLAYTEST' AND state='ACTIVE'",
                        String.class);
        String noticeHash =
                db.queryForObject(
                        "SELECT notice_hash FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'",
                        String.class);
        assertThatThrownBy(
                        () ->
                                qualifications.createQualification(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        2,
                                        snapshot,
                                        "R1",
                                        "R2",
                                        "H5_SYNTHETIC",
                                        selected,
                                        "BLIND",
                                        policyCode,
                                        noticeHash,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        db.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=? AND"
                        + " permission='REVIEW'",
                story,
                invitationActor.accountId());
        assertThatThrownBy(
                        () ->
                                qualifications.createQualification(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        2,
                                        snapshot,
                                        "R1",
                                        "R2",
                                        "H5_SYNTHETIC",
                                        selected,
                                        "FUNCTIONAL",
                                        policyCode,
                                        noticeHash,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FORBIDDEN");
    }
}
