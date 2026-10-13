package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.game.service.PublicCoopReportService;
import com.reasoning.common.game.service.PublicGameService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 실제 두 회원·현행 세션과 격리된 합성 시작 PUBLIC 출처의 공동 동의 검증. 공개 자격 검증은 아니다. */
@SpringBootTest
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
class PublicCoopReportIT extends PublicGameFixture {
    @Autowired PublicCoopReportService coopReports;
    @Autowired PublicGameService games;

    private record Room(
            UUID key, long id, UUID source, String host, String guest, long hostId, long guestId) {}

    @Test
    void savedARequiresDistinctExplicitConsentAndAcceptReusesItsExactEncryptedSource()
            throws Exception {
        Room room = room();
        JsonNode a = supportedReport(room.source());
        assertThat(coopReports.report(room.host(), room.key(), UUID.randomUUID()).report())
                .isNotNull();
        UUID editKey = UUID.randomUUID();
        var edited =
                coopReports.editReport(
                        room.host(), room.key(), 1, 0, a, editKey, UUID.randomUUID());
        assertThat(edited.changed()).isTrue();
        assertThat(
                        coopReports
                                .editReport(
                                        room.host(),
                                        room.key(),
                                        1,
                                        0,
                                        a,
                                        editKey,
                                        UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                coopReports.editReport(
                                        room.host(),
                                        room.key(),
                                        1,
                                        0,
                                        changed(a),
                                        editKey,
                                        UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        UUID proposeKey = UUID.randomUUID();
        var proposed =
                coopReports.propose(room.host(), room.key(), 2, 1, proposeKey, UUID.randomUUID());
        UUID reportKey =
                UUID.fromString(((JsonNode) proposed.original()).path("reportKey").asText());
        assertThat(
                        coopReports
                                .propose(
                                        room.host(),
                                        room.key(),
                                        2,
                                        1,
                                        proposeKey,
                                        UUID.randomUUID())
                                .replayed())
                .isTrue();
        var first = coopReports.report(room.host(), room.key(), UUID.randomUUID());
        var second = coopReports.report(room.guest(), room.key(), UUID.randomUUID());
        assertThat(first.proposal().report()).isEqualTo(a);
        assertThat(second.proposal().report()).isEqualTo(a);
        assertThat(second.proposal().canAccept()).isTrue();
        assertThat(first.proposal().canAccept()).isFalse();
        assertThat(first.submissionState()).isEqualTo("PROPOSED");
        assertThat(
                        coopReports
                                .editReport(
                                        room.guest(),
                                        room.key(),
                                        3,
                                        1,
                                        a,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isFalse();
        assertThat(
                        coopReports
                                .report(room.guest(), room.key(), UUID.randomUUID())
                                .proposal()
                                .reportKey())
                .isEqualTo(reportKey);
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.host(),
                                        room.key(),
                                        reportKey,
                                        3,
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FORBIDDEN");
        var before =
                db.queryForMap(
                        "SELECT id,payload_cipher,payload_hash,source_rev FROM game_report"
                                + " WHERE report_key=?",
                        reportKey);
        UUID acceptKey = UUID.randomUUID();
        var accepted =
                coopReports.respond(
                        room.guest(),
                        room.key(),
                        reportKey,
                        3,
                        "ACCEPT",
                        acceptKey,
                        UUID.randomUUID());
        assertThat(accepted.changed()).isTrue();
        assertThat(
                        coopReports
                                .respond(
                                        room.guest(),
                                        room.key(),
                                        reportKey,
                                        3,
                                        "ACCEPT",
                                        acceptKey,
                                        UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.guest(),
                                        room.key(),
                                        reportKey,
                                        3,
                                        "REJECT",
                                        acceptKey,
                                        UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        var after =
                db.queryForMap(
                        "SELECT"
                            + " id,payload_cipher,payload_hash,source_rev,accepted_by,ordinal,accepted_at,grade_deadline_at"
                            + " FROM game_report WHERE report_key=?",
                        reportKey);
        assertThat(after.get("id")).isEqualTo(before.get("id"));
        assertThat((byte[]) after.get("payload_cipher"))
                .containsExactly((byte[]) before.get("payload_cipher"));
        assertThat(after.get("payload_hash")).isEqualTo(before.get("payload_hash"));
        assertThat(after.get("source_rev")).isEqualTo(before.get("source_rev"));
        assertThat(after.get("accepted_by")).isEqualTo(room.guestId());
        assertThat(((Number) after.get("ordinal")).intValue()).isEqualTo(1);
        assertThat(
                        db.queryForMap(
                                "SELECT state,game_report_id FROM grade_job WHERE game_report_id=?",
                                after.get("id")))
                .containsEntry("state", "QUEUED")
                .containsEntry("game_report_id", after.get("id"));
        assertThat(
                        coopReports
                                .report(room.host(), room.key(), UUID.randomUUID())
                                .pending()
                                .report())
                .isEqualTo(a);
        assertThat(
                        coopReports
                                .report(room.guest(), room.key(), UUID.randomUUID())
                                .submissionState())
                .isEqualTo("PENDING");
        assertThatThrownBy(
                        () ->
                                coopReports.editReport(
                                        room.host(),
                                        room.key(),
                                        4,
                                        1,
                                        changed(a),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("REPORT_PENDING");
        assertThatThrownBy(
                        () ->
                                coopReports.propose(
                                        room.guest(),
                                        room.key(),
                                        4,
                                        1,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("REPORT_PENDING");
        db.update(
                "UPDATE member_identity SET active_yn=false WHERE id=(SELECT identity_id FROM"
                    + " member_session WHERE id=(SELECT presence_session_id FROM game_member WHERE"
                    + " room_id=? AND member_id=?))",
                room.id(),
                room.guestId());
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.guest(),
                                        room.key(),
                                        reportKey,
                                        3,
                                        "ACCEPT",
                                        acceptKey,
                                        UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM grade_job WHERE game_report_id=?",
                                String.class,
                                after.get("id")))
                .isEqualTo("QUEUED");
    }

    @Test
    void changedPatchInvalidatesAWhileNoopAndGetDoNotConsent() throws Exception {
        Room room = room();
        JsonNode a = supportedReport(room.source());
        coopReports.editReport(
                room.host(), room.key(), 1, 0, a, UUID.randomUUID(), UUID.randomUUID());
        UUID oldKey =
                UUID.fromString(
                        ((JsonNode)
                                        coopReports
                                                .propose(
                                                        room.host(),
                                                        room.key(),
                                                        2,
                                                        1,
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID())
                                                .original())
                                .path("reportKey")
                                .asText());
        coopReports.report(room.guest(), room.key(), UUID.randomUUID());
        assertThat(
                        coopReports
                                .editReport(
                                        room.guest(),
                                        room.key(),
                                        3,
                                        1,
                                        a,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isFalse();
        JsonNode b = changed(a);
        coopReports.editReport(
                room.guest(), room.key(), 3, 1, b, UUID.randomUUID(), UUID.randomUUID());
        assertThat(coopReports.report(room.host(), room.key(), UUID.randomUUID()).proposal())
                .isNull();
        assertThat(
                        db.queryForMap(
                                "SELECT state,payload_cipher,payload_hash FROM game_report"
                                        + " WHERE report_key=?",
                                oldKey))
                .containsEntry("state", "INVALIDATED")
                .containsEntry("payload_cipher", null)
                .containsEntry("payload_hash", null);
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.guest(),
                                        room.key(),
                                        oldKey,
                                        4,
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("STATE_CONFLICT");
        UUID newKey =
                UUID.fromString(
                        ((JsonNode)
                                        coopReports
                                                .propose(
                                                        room.guest(),
                                                        room.key(),
                                                        4,
                                                        2,
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID())
                                                .original())
                                .path("reportKey")
                                .asText());
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(
                        coopReports
                                .respond(
                                        room.guest(),
                                        room.key(),
                                        newKey,
                                        5,
                                        "WITHDRAW",
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isTrue();
        assertThat(
                        db.queryForMap(
                                "SELECT state,payload_cipher,payload_hash FROM game_report"
                                        + " WHERE report_key=?",
                                newKey))
                .containsEntry("state", "WITHDRAWN")
                .containsEntry("payload_cipher", null)
                .containsEntry("payload_hash", null);
        UUID rejectKey =
                UUID.fromString(
                        ((JsonNode)
                                        coopReports
                                                .propose(
                                                        room.host(),
                                                        room.key(),
                                                        6,
                                                        2,
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID())
                                                .original())
                                .path("reportKey")
                                .asText());
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.guest(),
                                        room.key(),
                                        rejectKey,
                                        7,
                                        "WITHDRAW",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FORBIDDEN");
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.host(),
                                        room.key(),
                                        rejectKey,
                                        7,
                                        "REJECT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FORBIDDEN");
        coopReports.respond(
                room.guest(),
                room.key(),
                rejectKey,
                7,
                "REJECT",
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(
                        db.queryForMap(
                                "SELECT state,payload_cipher,payload_hash FROM game_report"
                                        + " WHERE report_key=?",
                                rejectKey))
                .containsEntry("state", "REJECTED")
                .containsEntry("payload_cipher", null)
                .containsEntry("payload_hash", null);
    }

    @Test
    void stalePresenceAndAuthRevisionBlockConsentWithoutConsumingProposal() throws Exception {
        Room room = room();
        JsonNode a = supportedReport(room.source());
        coopReports.editReport(
                room.host(), room.key(), 1, 0, a, UUID.randomUUID(), UUID.randomUUID());
        UUID key =
                UUID.fromString(
                        ((JsonNode)
                                        coopReports
                                                .propose(
                                                        room.host(),
                                                        room.key(),
                                                        2,
                                                        1,
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID())
                                                .original())
                                .path("reportKey")
                                .asText());
        db.update(
                "UPDATE game_member SET last_seen_at=clock_timestamp()-interval '30 seconds'"
                        + " WHERE room_id=? AND member_id=?",
                room.id(),
                room.hostId());
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.guest(),
                                        room.key(),
                                        key,
                                        3,
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("PARTNER_OFFLINE");
        db.update(
                "UPDATE game_member SET last_seen_at=clock_timestamp() WHERE room_id=?", room.id());
        db.update(
                "UPDATE game_member SET presence_auth_rev=presence_auth_rev+1 WHERE room_id=?"
                        + " AND member_id=?",
                room.id(),
                room.hostId());
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.guest(),
                                        room.key(),
                                        key,
                                        3,
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("PARTNER_OFFLINE");
        db.update(
                "UPDATE game_member SET presence_auth_rev=presence_auth_rev-1 WHERE room_id=?"
                        + " AND member_id=?",
                room.id(),
                room.hostId());
        db.update(
                "UPDATE member_session SET revoked_at=clock_timestamp(),revoke_code='TEST_REVOKE'"
                        + " WHERE id=(SELECT"
                        + " presence_session_id FROM game_member WHERE room_id=? AND member_id=?)",
                room.id(),
                room.hostId());
        assertThatThrownBy(
                        () ->
                                coopReports.respond(
                                        room.guest(),
                                        room.key(),
                                        key,
                                        3,
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("PARTNER_OFFLINE");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM game_report WHERE report_key=?",
                                String.class,
                                key))
                .isEqualTo("PROPOSED");
    }

    /** 검수 사본을 공개했다고 주장하지 않고 시작된 합성 PUBLIC 고정 루트만 구성한다. */
    private Room room() throws Exception {
        playtestPolicy();
        String hostToken =
                account("coop-fixed-host-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String guestToken =
                account("coop-fixed-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        var host = service.authenticate(hostToken, false);
        var guest = service.authenticate(guestToken, false);
        UUID sourceKey = invitation(List.of(host.memberKey(), guest.memberKey()));
        Friendly policy = friendlyPolicy();
        var source =
                db.queryForMap(
                        "SELECT"
                            + " t.version_id,v.story_id,t.snapshot_id,t.runtime_id,t.runtime_epoch,t.config_hash,v.difficulty,v.limit_sec"
                            + " FROM play_test t JOIN story_version v ON v.id=t.version_id WHERE"
                            + " t.test_key=?",
                        sourceKey);
        var frozen =
                FrozenSnapshotCodec.decode(
                        db.queryForObject(
                                        "SELECT payload::text FROM" + " review_snapshot WHERE id=?",
                                        String.class,
                                        source.get("snapshot_id"))
                                .getBytes(StandardCharsets.UTF_8));
        UUID key = UUID.randomUUID();
        long roomId =
                db.queryForObject(
                        "INSERT INTO game_room(room_key,purpose,mode,story_id,version_id,"
                            + "snapshot_id,runtime_id,runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,"
                            + "state,difficulty,limit_sec,max_attempts,max_hints,created_at,lobby_until)"
                            + " SELECT"
                            + " ?,'PUBLIC','COOP',?,?,?,?,?,?,?,?,0,'WAITING',?,?,?,?,c.t,c.t+interval"
                            + " '30 minutes' FROM (SELECT clock_timestamp() t) c RETURNING id",
                        Long.class,
                        key,
                        source.get("story_id"),
                        source.get("version_id"),
                        source.get("snapshot_id"),
                        source.get("runtime_id"),
                        source.get("runtime_epoch"),
                        source.get("config_hash"),
                        frozen.rubricHash(),
                        policy.id(),
                        source.get("difficulty"),
                        source.get("limit_sec"),
                        frozen.payload().path("policy").path("attemptLimit").asInt(),
                        frozen.payload().path("policy").path("hintsPerPerson").asInt());
        var pair =
                db.queryForMap(
                        "SELECT role_a,role_b FROM story_pair WHERE version_id=? AND active_yn"
                                + " ORDER BY role_a,role_b LIMIT 1",
                        source.get("version_id"));
        for (var actor : List.of(host, guest)) {
            long identity =
                    db.queryForObject(
                            "SELECT identity_id FROM member_session WHERE id=?",
                            Long.class,
                            actor.sessionId());
            int slot = actor.memberId() == host.memberId() ? 1 : 2;
            db.update(
                    "INSERT INTO"
                        + " game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at)"
                        + " VALUES (?,?,?,?,?,clock_timestamp())",
                    roomId,
                    actor.memberId(),
                    identity,
                    slot,
                    policy.id());
            db.update(
                    "UPDATE game_member SET"
                        + " version_id=?,role_code=?,presence_session_id=?,presence_auth_rev=?,last_seen_at=clock_timestamp()"
                        + " WHERE room_id=? AND member_id=?",
                    source.get("version_id"),
                    pair.get(slot == 1 ? "role_a" : "role_b"),
                    actor.sessionId(),
                    actor.authRev(),
                    roomId,
                    actor.memberId());
        }
        db.update(
                "WITH c AS (SELECT clock_timestamp() t) UPDATE game_room SET"
                    + " state='RUNNING',role_a=?,role_b=?,started_at=c.t,deadline_at=c.t+limit_sec*interval"
                    + " '1 second',rev=1 FROM c WHERE id=?",
                pair.get("role_a"),
                pair.get("role_b"),
                roomId);
        return new Room(
                key, roomId, sourceKey, hostToken, guestToken, host.memberId(), guest.memberId());
    }

    private JsonNode changed(JsonNode initial) {
        ObjectNode next = initial.deepCopy();
        next.put("method", initial.path("method").asText() + " 새 증거");
        return next;
    }
}
