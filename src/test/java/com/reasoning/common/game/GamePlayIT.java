package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.game.service.GamePlayService;
import com.reasoning.common.game.service.GameRecordService;
import com.reasoning.common.game.service.PublicGameService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** 독립 합성 회원·고정 사본의 실제 PostgreSQL 점유·힌트·종료 경계를 검증한다. */
@SpringBootTest
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
class GamePlayIT extends PublicGameFixture {
    @Autowired PublicGameService games;
    @Autowired GamePlayService play;
    @Autowired GameRecordService records;

    @Override
    protected int fixtureDifficulty() {
        return 3;
    }

    private record Room(UUID key, long id, long member, long snapshot, int maxHints) {}

    @Test
    void concurrentRoomsCannotOccupyOneMemberTwice() throws Exception {
        playtestPolicy();
        String token =
                account("concurrent-room-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long member = service.authenticate(token, false).memberId();
        UUID first = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID peer =
                invitations
                        .getMemberIdentity(
                                account("concurrent-peer-" + UUID.randomUUID() + "@example.invalid")
                                        .path("accessToken")
                                        .asText(),
                                UUID.randomUUID())
                        .memberKey();
        UUID source = invitation(List.of(first, peer));
        long policy = friendlyPolicy().id();
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++)
                attempts.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    try {
                                        room(source, member, policy, false);
                                        return true;
                                    } catch (
                                            org.springframework.dao.DataIntegrityViolationException
                                                    rejected) {
                                        return false;
                                    }
                                }));
            go.countDown();
            assertThat(attempts.get(0).get() != attempts.get(1).get()).isTrue();
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_member WHERE member_id=? AND"
                                        + " occupying_yn",
                                Integer.class,
                                member))
                .isEqualTo(1);
    }

    @Test
    void terminalReleasesOnlyOccupancyAndKeepsHistoricalMembershipAndRecords() throws Exception {
        playtestPolicy();
        var account = account("lifecycle-" + UUID.randomUUID() + "@example.invalid");
        String token = account.path("accessToken").asText();
        var principal = service.authenticate(token, false);
        UUID first = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID peer =
                invitations
                        .getMemberIdentity(
                                account("lifecycle-peer-" + UUID.randomUUID() + "@example.invalid")
                                        .path("accessToken")
                                        .asText(),
                                UUID.randomUUID())
                        .memberKey();
        UUID source = invitation(List.of(first, peer));
        long policy = friendlyPolicy().id();
        Room original = room(source, principal.memberId(), policy, false);
        assertThat(play.currentRoom(token, UUID.randomUUID()).game().gameKey())
                .isEqualTo(original.key());
        assertThatThrownBy(() -> room(source, principal.memberId(), policy, false))
                .hasMessageContaining("uk_gm_occupied_member");
        start(original);
        var ended = play.forfeit(token, original.key(), 1, UUID.randomUUID(), UUID.randomUUID());
        assertThat(ended.changed()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT active_yn FROM game_member WHERE room_id=?",
                                Boolean.class,
                                original.id()))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT occupying_yn FROM game_member WHERE room_id=?",
                                Boolean.class,
                                original.id()))
                .isFalse();
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE game_member SET occupying_yn=true WHERE room_id=?",
                                        original.id()))
                .hasMessageContaining("occupancy is derived");
        Room next = room(source, principal.memberId(), policy, false);
        assertThat(play.currentRoom(token, UUID.randomUUID()).game().gameKey())
                .isEqualTo(next.key());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=? AND member_id=?",
                                Integer.class,
                                original.id(),
                                principal.memberId()))
                .isEqualTo(1);
        var recordPage = records.getRecordList(token, null, 20, UUID.randomUUID());
        assertThat(recordPage.items())
                .anyMatch(
                        row ->
                                row.path("gameKey").isMissingNode()
                                        && row.path("outcome").asText().equals("FORFEIT"));
        var old =
                records.getRecordDetail(
                        token,
                        UUID.fromString(recordPage.items().getFirst().path("recordKey").asText()),
                        UUID.randomUUID());
        assertThat(old.path("outcome").asText()).isEqualTo("FORFEIT");
    }

    @Test
    void hintsAndCluesChargeOnlyFirstOpenAndPreserveReceiptAndCurrentAuthority() throws Exception {
        playtestPolicy();
        var account = account("hints-" + UUID.randomUUID() + "@example.invalid");
        String token = account.path("accessToken").asText();
        long member = service.authenticate(token, false).memberId();
        UUID first = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID second =
                invitations
                        .getMemberIdentity(
                                account("hints-peer-" + UUID.randomUUID() + "@example.invalid")
                                        .path("accessToken")
                                        .asText(),
                                UUID.randomUUID())
                        .memberKey();
        Room room = room(invitation(List.of(first, second)), member, friendlyPolicy().id(), false);
        start(room);
        var beforeHeartbeat =
                db.queryForMap("SELECT rev,deadline_at FROM game_room WHERE id=?", room.id());
        play.heartbeat(token, room.key(), UUID.randomUUID());
        assertThat(db.queryForMap("SELECT rev,deadline_at FROM game_room WHERE id=?", room.id()))
                .isEqualTo(beforeHeartbeat);
        assertThat(
                        db.queryForObject(
                                "SELECT last_seen_at IS NOT NULL AND presence_session_id IS NOT"
                                        + " NULL AND presence_auth_rev IS NOT NULL FROM game_member"
                                        + " WHERE room_id=?",
                                Boolean.class,
                                room.id()))
                .isTrue();
        var foreign =
                account("hints-foreign-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        assertThatThrownBy(
                        () ->
                                play.openHint(
                                        foreign,
                                        room.key(),
                                        1,
                                        1,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("NOT_FOUND");
        var firstOpen =
                play.openHint(token, room.key(), 1, 1, UUID.randomUUID(), UUID.randomUUID());
        assertThat(firstOpen.changed()).isTrue();
        var repeat = play.openHint(token, room.key(), 2, 1, UUID.randomUUID(), UUID.randomUUID());
        assertThat(repeat.changed()).isFalse();
        UUID request = UUID.randomUUID();
        var secondOpen = play.openHint(token, room.key(), 2, 2, request, UUID.randomUUID());
        assertThat(play.openHint(token, room.key(), 2, 2, request, UUID.randomUUID()).original())
                .isEqualTo(secondOpen.original());
        assertThatThrownBy(() -> play.openHint(token, room.key(), 2, 3, request, UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_hint WHERE room_id=? AND member_id=?",
                                Integer.class,
                                room.id(),
                                member))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                play.openHint(
                                        token,
                                        room.key(),
                                        3,
                                        3,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("HINT_LIMIT_REACHED");
        var materials = games.materials(token, room.key(), UUID.randomUUID());
        assertThat(materials.openedHints().size()).isEqualTo(2);
        assertThat(materials.hintsRemaining()).isEqualTo(room.maxHints() - 2);
        for (var opened : materials.openedHints()) {
            assertThat(opened.path("code").isTextual()).isTrue();
            assertThat(opened.path("code").asText()).isNotBlank();
            assertThat(opened.path("body").isTextual()).isTrue();
        }
        assertThat(materials.toString()).doesNotContain("resources").doesNotContain("secretText");
        String clue = materials.clues().get(0).path("code").asText();
        assertThat(
                        play.clueRead(
                                        token,
                                        room.key(),
                                        3,
                                        clue,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isTrue();
        assertThat(
                        play.clueRead(
                                        token,
                                        room.key(),
                                        4,
                                        clue,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_clue_read WHERE room_id=? AND"
                                        + " member_id=?",
                                Integer.class,
                                room.id(),
                                member))
                .isEqualTo(1);
        assertThat(
                        games.materials(token, room.key(), UUID.randomUUID())
                                .clues()
                                .get(0)
                                .path("read")
                                .asBoolean())
                .isTrue();
        assertThat(clue).isEqualTo("C1");
        assertThat(materials.clues()).anyMatch(row -> row.path("code").asText().equals("C2"));
        assertThat(
                        play.clueRead(
                                        token,
                                        room.key(),
                                        4,
                                        "C2",
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_clue_read WHERE room_id=? AND"
                                        + " member_id=?",
                                Integer.class,
                                room.id(),
                                member))
                .isEqualTo(2);
        UUID forfeitKey = UUID.randomUUID();
        assertThat(play.forfeit(token, room.key(), 5, forfeitKey, UUID.randomUUID()).changed())
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT own_hint_count FROM play_record WHERE room_id=?",
                                Integer.class,
                                room.id()))
                .isEqualTo(2);
        service.logout(token, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> play.forfeit(token, room.key(), 5, forfeitKey, UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
    }

    @Test
    void expiredLobbyAndDeadlineSettleWithoutInventingAttemptsOrRecords() throws Exception {
        playtestPolicy();
        var account = account("expiry-" + UUID.randomUUID() + "@example.invalid");
        String token = account.path("accessToken").asText();
        long member = service.authenticate(token, false).memberId();
        UUID first = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID peer =
                invitations
                        .getMemberIdentity(
                                account("expiry-peer-" + UUID.randomUUID() + "@example.invalid")
                                        .path("accessToken")
                                        .asText(),
                                UUID.randomUUID())
                        .memberKey();
        UUID source = invitation(List.of(first, peer));
        long policy = friendlyPolicy().id();
        Room lobby = room(source, member, policy, true);
        assertThat(play.settleExpired(10)).isPositive();
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM game_room WHERE id=?", String.class, lobby.id()))
                .isEqualTo("CANCELLED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?",
                                Integer.class,
                                lobby.id()))
                .isZero();
        Room running = room(source, member, policy, false);
        startExpired(running);
        assertThat(play.settleExpired(10)).isPositive();
        var terminal =
                db.queryForMap(
                        "SELECT"
                            + " state,end_reason,final_score,graded_count,wrong_count,ended_at,deadline_at,time_basis_at"
                            + " FROM game_room WHERE id=?",
                        running.id());
        assertThat(terminal.get("state")).isEqualTo("ENDED");
        assertThat(terminal.get("end_reason")).isEqualTo("TIME_LIMIT");
        assertThat(((Number) terminal.get("final_score")).intValue()).isZero();
        assertThat(terminal.get("time_basis_at")).isEqualTo(terminal.get("deadline_at"));
        assertThat(
                        db.queryForObject(
                                "SELECT own_hint_count FROM play_record WHERE room_id=?",
                                Integer.class,
                                running.id()))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT occupying_yn FROM game_member WHERE room_id=?",
                                Boolean.class,
                                running.id()))
                .isFalse();
    }

    @Test
    void acceptedGradeJobRemainsRunningPastPlayDeadline() throws Exception {
        playtestPolicy();
        String token =
                account("pending-expiry-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long member = service.authenticate(token, false).memberId();
        UUID first = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID peer =
                invitations
                        .getMemberIdentity(
                                account("pending-peer-" + UUID.randomUUID() + "@example.invalid")
                                        .path("accessToken")
                                        .asText(),
                                UUID.randomUUID())
                        .memberKey();
        UUID source = invitation(List.of(first, peer));
        Room pending = room(source, member, friendlyPolicy().id(), false, 12);
        start(pending);
        var edited =
                games.editReport(
                        token,
                        pending.key(),
                        1,
                        0,
                        supportedReport(source),
                        UUID.randomUUID(),
                        UUID.randomUUID());
        assertThat(edited.changed()).isTrue();
        var submitted =
                games.submitReport(
                        token, pending.key(), 2, 1, UUID.randomUUID(), UUID.randomUUID());
        assertThat(submitted.changed()).isTrue();
        // 고정 수락 이후에도 실제 서버 기한을 지나야 한다. 120초 worker 복구 창보다 짧게 기다린다.
        Thread.sleep(12500);
        assertThat(play.settleExpired(10)).isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM game_room WHERE id=?",
                                String.class,
                                pending.id()))
                .isEqualTo("RUNNING");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?",
                                Integer.class,
                                pending.id()))
                .isZero();
        assertThatThrownBy(
                        () ->
                                play.openHint(
                                        token,
                                        pending.key(),
                                        3,
                                        1,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("PLAY_DEADLINE_EXPIRED");
    }

    /** 고정 사본·LOCAL 신원·FRIENDLY 동의를 실제 루트에 묶고 공개 발급 자격은 가장하지 않는다. */
    private Room room(UUID sourceKey, long member, long policy, boolean expired) {
        return room(sourceKey, member, policy, expired, null);
    }

    private Room room(UUID sourceKey, long member, long policy, boolean expired, Integer limitSec) {
        var source =
                db.queryForMap(
                        "SELECT"
                            + " t.version_id,v.story_id,t.snapshot_id,t.runtime_id,t.runtime_epoch,t.config_hash,v.difficulty,v.limit_sec"
                            + " FROM play_test t JOIN story_version v ON v.id=t.version_id WHERE"
                            + " t.test_key=?",
                        sourceKey);
        long snapshot = ((Number) source.get("snapshot_id")).longValue();
        var frozen =
                FrozenSnapshotCodec.decode(
                        db.queryForObject(
                                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                                        String.class,
                                        snapshot)
                                .getBytes(StandardCharsets.UTF_8));
        int maxHints = frozen.payload().path("policy").path("hintsPerPerson").asInt();
        UUID key = UUID.randomUUID();
        Long id =
                db.queryForObject(
                        """
                        INSERT INTO game_room(room_key,purpose,mode,story_id,version_id,snapshot_id,runtime_id,
                          runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,
                          max_attempts,max_hints,created_at,lobby_until)
                        SELECT ?,'PUBLIC','SOLO',?,?,?,?,?,?,?,?,0,'WAITING',?,?,?,?,t.c,t.c+interval '30 minutes'
                        FROM (SELECT clock_timestamp()-?*interval '1 minute' AS c) t RETURNING id
                        """,
                        Long.class,
                        key,
                        source.get("story_id"),
                        source.get("version_id"),
                        snapshot,
                        source.get("runtime_id"),
                        source.get("runtime_epoch"),
                        source.get("config_hash"),
                        frozen.rubricHash(),
                        policy,
                        source.get("difficulty"),
                        limitSec == null ? source.get("limit_sec") : limitSec,
                        frozen.payload().path("policy").path("attemptLimit").asInt(),
                        maxHints,
                        expired ? 31 : 0);
        long identity =
                db.queryForObject(
                        "SELECT id FROM member_identity WHERE member_id=? AND provider='LOCAL' AND"
                                + " active_yn",
                        Long.class,
                        member);
        db.update(
                "INSERT INTO game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at) "
                        + "VALUES (?,?,?,1,?,clock_timestamp())",
                id,
                member,
                identity,
                policy);
        return new Room(key, id, member, snapshot, maxHints);
    }

    private void start(Room room) {
        long version =
                db.queryForObject(
                        "SELECT version_id FROM game_room WHERE id=?", Long.class, room.id());
        db.update(
                "UPDATE game_member SET version_id=?,role_code='R1' WHERE room_id=?",
                version,
                room.id());
        db.update(
                "WITH t AS (SELECT clock_timestamp() AS n) UPDATE game_room SET"
                        + " state='RUNNING',started_at=t.n,deadline_at=t.n+limit_sec*interval '1"
                        + " second',role_a='R1',role_b='R2',rev=1 FROM t WHERE id=?",
                room.id());
    }

    private void startExpired(Room room) {
        long version =
                db.queryForObject(
                        "SELECT version_id FROM game_room WHERE id=?", Long.class, room.id());
        db.update(
                "UPDATE game_member SET version_id=?,role_code='R1' WHERE room_id=?",
                version,
                room.id());
        db.update(
                "WITH t AS (SELECT clock_timestamp()-interval '2 seconds' AS n) UPDATE game_room"
                    + " SET state='RUNNING',started_at=t.n-limit_sec*interval '1"
                    + " second',deadline_at=t.n,role_a='R1',role_b='R2',rev=1 FROM t WHERE id=?",
                room.id());
    }
}
