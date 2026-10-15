package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.game.service.GamePlayService;
import com.reasoning.common.game.service.GamePresenceCleanupService;
import com.reasoning.common.game.service.GameRecordService;
import com.reasoning.common.game.service.PublicGameService;
import com.reasoning.common.member.auth.MemberAuthCleanupService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Autowired GamePresenceCleanupService presence;

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
        Room later = privateRoom().room();
        startExpired(later);
        assertThat(expiry(List.of(pending, later), null, null).settleExpired(1)).isEqualTo(1);
        assertThat(state(pending)).isEqualTo("RUNNING");
        assertThat(state(later)).isEqualTo("ENDED");
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

    @Test
    void deletedFixedParticipantDoesNotConsumeDiscoveryPrefix() throws Exception {
        var deleted = privateRoom();
        startExpired(deleted.room());
        acceptPresenceErasure(newFamily(deleted));
        Room later = privateRoom().room();
        startExpired(later);
        var before = db.queryForMap("SELECT * FROM game_room WHERE id=?", deleted.room().id());
        assertThat(expiry(List.of(deleted.room(), later), null, null).settleExpired(1))
                .isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM game_room WHERE id=?", deleted.room().id()))
                .isEqualTo(before);
        assertThat(state(later)).isEqualTo("ENDED");
    }

    @Test
    void qualificationAndTestSourcesAreOutsidePublicExpiry() throws Exception {
        Room publicRoom = privateRoom("SOLO", true).room();
        long policy =
                db.queryForObject(
                        "SELECT id FROM privacy_policy WHERE scope='PLAYTEST' AND state='ACTIVE'",
                        Long.class);
        long qualification =
                db.queryForObject(
                        "WITH clock AS MATERIALIZED (SELECT clock_timestamp()-interval '31 minutes'"
                            + " AS n) INSERT INTO game_room"
                            + " (room_key,purpose,mode,qualification_kind,reviewer_id,selected_member_id,story_id,version_id,snapshot_id,runtime_id,runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,max_attempts,max_hints,created_at,lobby_until)"
                            + " SELECT"
                            + " gen_random_uuid(),'REVIEW_SOLO_QUALIFICATION','SOLO','FUNCTIONAL',?,?,r.story_id,r.version_id,r.snapshot_id,r.runtime_id,r.runtime_epoch,r.config_hash,r.rubric_hash,?,0,'WAITING',r.difficulty,r.limit_sec,r.max_attempts,r.max_hints,n,n+interval"
                            + " '30 minutes' FROM game_room r,clock WHERE r.id=? RETURNING id",
                        Long.class,
                        invitationActor.accountId(),
                        publicRoom.member(),
                        policy,
                        publicRoom.id());
        var qBefore = db.queryForMap("SELECT * FROM game_room WHERE id=?", qualification);
        var testsBefore = db.queryForList("SELECT * FROM play_test ORDER BY id");
        Room q =
                new Room(
                        UUID.randomUUID(),
                        qualification,
                        publicRoom.member(),
                        publicRoom.snapshot(),
                        publicRoom.maxHints());
        assertThat(expiry(List.of(publicRoom, q), null, null).settleExpired(100)).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM game_room WHERE id=?", qualification))
                .isEqualTo(qBefore);
        assertThat(db.queryForList("SELECT * FROM play_test ORDER BY id")).isEqualTo(testsBefore);
    }

    @Test
    void expiryBothModesKeepLiveRoomsAndBoundDiscovery() throws Exception {
        for (String mode : List.of("SOLO", "COOP")) {
            Room lobby = privateRoom(mode, true).room();
            Room running = privateRoom(mode).room();
            Room liveLobby = privateRoom(mode).room();
            Room liveRunning = privateRoom(mode).room();
            if ("COOP".equals(mode)) {
                addExpiryGuest(running);
                addExpiryGuest(liveRunning);
            }
            startExpired(running);
            start(liveRunning);
            var scoped = expiry(List.of(lobby, running, liveLobby, liveRunning), null, null);
            assertThatThrownBy(() -> scoped.settleExpired(0))
                    .hasMessageContaining("INVALID_REQUEST");
            assertThatThrownBy(() -> scoped.settleExpired(101))
                    .hasMessageContaining("INVALID_REQUEST");
            assertThat(scoped.settleExpired(1)).isEqualTo(1);
            assertThat(state(lobby)).isEqualTo("CANCELLED");
            assertThat(state(running)).isEqualTo("RUNNING");
            assertThat(scoped.settleExpired(100)).isEqualTo(1);
            assertThat(state(running)).isEqualTo("ENDED");
            assertThat(state(liveLobby)).isEqualTo("WAITING");
            assertThat(state(liveRunning)).isEqualTo("RUNNING");
            assertThat(scoped.settleExpired(100)).isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM play_record WHERE room_id=?",
                                    Integer.class,
                                    lobby.id()))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM play_record WHERE room_id=?",
                                    Integer.class,
                                    running.id()))
                    .isEqualTo("SOLO".equals(mode) ? 1 : 2);
        }
    }

    @Test
    void simulatedMissingLobbyReadAfterRealDiscoverySkipsWithoutLateLock() throws Exception {
        Room missing = privateRoom("SOLO", true).room();
        Room later = privateRoom("SOLO", true).room();
        // 실제 발견 뒤 최초 부모 읽기의 누락만 모사한다. 물리 삭제·PRIMARY·삭제 가드는 실행하지 않는다.
        assertThat(expiry(List.of(missing, later), null, null, missing.id()).settleExpired(100))
                .isEqualTo(1);
        assertThat(state(missing)).isEqualTo("WAITING");
        assertThat(state(later)).isEqualTo("CANCELLED");
        // 일시 누락을 해제한 다음 호출은 같은 방을 정상 취소하며 다른 시험에 만료 대기를 남기지 않는다.
        assertThat(expiry(List.of(missing), null, null).settleExpired(100)).isEqualTo(1);
        assertThat(state(missing)).isEqualTo("CANCELLED");
    }

    @Test
    void expirySkipsLockedMemberAndRoomButSettlesLaterIndependentCandidate() throws Exception {
        for (String table : List.of("member_account", "game_room")) {
            Room blocked = privateRoom().room();
            Room later = privateRoom().room();
            startExpired(blocked);
            startExpired(later);
            try (Connection lock = db.getDataSource().getConnection()) {
                lock.setAutoCommit(false);
                try (var statement =
                        lock.prepareStatement(
                                "SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
                    statement.setLong(
                            1, table.equals("member_account") ? blocked.member() : blocked.id());
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(expiry(List.of(blocked, later), null, null).settleExpired(100))
                                .isEqualTo(1);
                        assertThat(state(blocked)).isEqualTo("RUNNING");
                        assertThat(state(later)).isEqualTo("ENDED");
                    }
                } finally {
                    lock.rollback();
                }
            }
            assertThat(expiry(List.of(blocked), null, null).settleExpired(100)).isEqualTo(1);
        }
    }

    @Test
    void expiryRevalidatesMemberButIgnoresUnrelatedSourceEditAfterDiscovery() throws Exception {
        for (boolean memberChange : new boolean[] {false, true}) {
            Room room = privateRoom().room();
            startExpired(room);
            var discovered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable boundary =
                    () -> {
                        if (once.compareAndSet(false, true)) {
                            discovered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("합성 만료 장벽 해제 실패");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                        }
                    };
            try (var pool = Executors.newSingleThreadExecutor()) {
                var sweep =
                        pool.submit(() -> expiry(List.of(room), boundary, null).settleExpired(100));
                try {
                    assertThat(discovered.await(5, TimeUnit.SECONDS)).isTrue();
                    if (memberChange) {
                        db.update(
                                "UPDATE game_member SET active_yn=false WHERE room_id=?",
                                room.id());
                    } else {
                        db.update(
                                "UPDATE story SET updated_at=clock_timestamp() WHERE id=(SELECT"
                                        + " story_id FROM game_room WHERE id=?)",
                                room.id());
                    }
                } finally {
                    release.countDown();
                }
                assertThat(sweep.get(5, TimeUnit.SECONDS)).isEqualTo(memberChange ? 0 : 1);
            }
            assertThat(state(room)).isEqualTo(memberChange ? "RUNNING" : "ENDED");
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM play_record WHERE room_id=?",
                                    Integer.class,
                                    room.id()))
                    .isEqualTo(memberChange ? 0 : 1);
            if (memberChange) {
                // 경합 관찰용 합성 참가자를 복원하여 다른 시험의 만료 발견에 불완전한 방을 남기지 않는다.
                db.update("UPDATE game_member SET active_yn=true WHERE room_id=?", room.id());
                assertThat(expiry(List.of(room), null, null).settleExpired(100)).isEqualTo(1);
            }
        }
    }

    @Test
    void concurrentExpiryCommitsOneRevisionAuditAndRecord() throws Exception {
        Room room = privateRoom().room();
        startExpired(room);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                go.await();
                                return expiry(List.of(room), null, null).settleExpired(100);
                            });
            var second =
                    pool.submit(
                            () -> {
                                go.await();
                                return expiry(List.of(room), null, null).settleExpired(100);
                            });
            go.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT rev FROM game_room WHERE id=?", Long.class, room.id()))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_audit WHERE room_id=? AND"
                                        + " action='PUBLIC_TIME_LIMIT'",
                                Integer.class,
                                room.id()))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?",
                                Integer.class,
                                room.id()))
                .isEqualTo(1);
    }

    @Test
    void expirySqlFailureRollsBackOnlyFailingRoomAndKeepsEarlierCommit() throws Exception {
        for (String failureTable : List.of("game_audit", "play_record")) {
            Room first = privateRoom().room();
            Room failed = privateRoom().room();
            startExpired(first);
            startExpired(failed);
            assertThatThrownBy(
                            () ->
                                    expiry(List.of(first, failed), null, failureTable)
                                            .settleExpired(100))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(state(first)).isEqualTo("ENDED");
            assertThat(state(failed)).isEqualTo("RUNNING");
            assertThat(
                            db.queryForObject(
                                    "SELECT rev FROM game_room WHERE id=?",
                                    Long.class,
                                    failed.id()))
                    .isEqualTo(1);
            assertThat(
                            db.queryForObject(
                                    "SELECT occupying_yn FROM game_member WHERE room_id=?",
                                    Boolean.class,
                                    failed.id()))
                    .isTrue();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM play_record WHERE room_id=?",
                                    Integer.class,
                                    failed.id()))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM game_audit WHERE room_id=? AND"
                                            + " actor_kind='SYSTEM'",
                                    Integer.class,
                                    failed.id()))
                    .isZero();
            assertThat(expiry(List.of(failed), null, null).settleExpired(100)).isEqualTo(1);
        }
    }

    /** 합성 ID로 발견만 한정하고 후보 조건·저장·잠금 SQL은 실제 PG에서 실행한다. */
    private GamePlayService expiry(List<Room> rooms, Runnable beforeLock, String failureTable) {
        return expiry(rooms, beforeLock, failureTable, null);
    }

    /** 누락 후보 ID가 있으면 실제 발견 뒤 최초 부모 읽기만 빈 결과로 모사한다. */
    private GamePlayService expiry(
            List<Room> rooms, Runnable beforeLock, String failureTable, Long missingParent) {
        String ids =
                rooms.stream()
                        .map(room -> Long.toString(room.id()))
                        .collect(java.util.stream.Collectors.joining(","));
        JdbcTemplate template =
                new JdbcTemplate(db.getDataSource()) {
                    private int inserts;
                    private boolean missingRead;

                    @Override
                    public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                        boolean discovery =
                                sql.startsWith("SELECT id FROM game_room WHERE purpose='PUBLIC'");
                        if (discovery) {
                            sql =
                                    sql.replace(
                                            " ORDER BY id LIMIT ?",
                                            " AND id IN (" + ids + ") ORDER BY id LIMIT ?");
                        }
                        return super.queryForList(sql, type, args);
                    }

                    @Override
                    public List<Map<String, Object>> queryForList(String sql, Object... args) {
                        if (!missingRead
                                && missingParent != null
                                && sql.startsWith("SELECT r.purpose,r.mode,")
                                && missingParent.equals(args[0])) {
                            missingRead = true;
                            return List.of();
                        }
                        if (beforeLock != null
                                && sql.startsWith("SELECT id FROM admin_account WHERE"))
                            beforeLock.run();
                        return super.queryForList(sql, args);
                    }

                    @Override
                    public int update(String sql, Object... args) {
                        if (failureTable != null
                                && sql.replaceAll("\\s+", " ")
                                        .replace("public.", "")
                                        .contains("INSERT INTO " + failureTable)
                                && ++inserts == 2) {
                            // 실제 SQL 오류로 소유 거래를 중단하며 스키마·트리거는 변경하지 않는다.
                            execute("SELECT 1/0");
                        }
                        return super.update(sql, args);
                    }
                };
        return new GamePlayService(template, games, service);
    }

    /** 합성 방의 현재 상태를 독립 연결로 읽는다. */
    private String state(Room room) {
        return db.queryForObject("SELECT state FROM game_room WHERE id=?", String.class, room.id());
    }

    private record PresenceFixture(Room room, String email, String token, long session) {}

    /** 기존 합성 방과 실제 회원 발급·heartbeat·logout으로 종료 접속 근거를 만든다. */
    private PresenceFixture privateRoom() throws Exception {
        return privateRoom("SOLO");
    }

    private PresenceFixture privateRoom(String mode) throws Exception {
        return privateRoom(mode, false);
    }

    /** 생성 시각과 대기 마감의 30분 결속을 유지한 합성 방을 발급한다. */
    private PresenceFixture privateRoom(String mode, boolean expired) throws Exception {
        if (db.queryForObject(
                        "SELECT count(*) FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'",
                        Integer.class)
                == 0) playtestPolicy();
        long policy =
                db.queryForObject(
                                        "SELECT count(*) FROM privacy_policy WHERE scope='FRIENDLY'"
                                                + " AND state='ACTIVE'",
                                        Integer.class)
                                == 0
                        ? friendlyPolicy().id()
                        : db.queryForObject(
                                "SELECT id FROM privacy_policy WHERE scope='FRIENDLY' AND"
                                        + " state='ACTIVE'",
                                Long.class);
        String email = "presence-" + UUID.randomUUID() + "@example.invalid";
        String token = account(email).path("accessToken").asText();
        var actor = service.authenticate(token, false);
        UUID first = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID peer =
                invitations
                        .getMemberIdentity(
                                account("presence-peer-" + UUID.randomUUID() + "@example.invalid")
                                        .path("accessToken")
                                        .asText(),
                                UUID.randomUUID())
                        .memberKey();
        Room room =
                room(
                        invitation(List.of(first, peer)),
                        actor.memberId(),
                        policy,
                        expired,
                        null,
                        mode);
        if (!expired) play.heartbeat(token, room.key(), UUID.randomUUID());
        service.logout(token, UUID.randomUUID(), UUID.randomUUID());
        return new PresenceFixture(room, email, token, actor.sessionId());
    }

    /** 합성 회원의 실제 LOCAL 비밀번호 경로로 별도 살아 있는 family를 발급한다. */
    private String newFamily(PresenceFixture fixture) throws Exception {
        return service.login(
                        fixture.email(),
                        "Synthetic-member-password-987!",
                        "198.19.75.1",
                        UUID.randomUUID())
                .tokens()
                .accessToken();
    }

    private MemberAuthCleanupService authCleanup() {
        return new MemberAuthCleanupService(
                db, new DataSourceTransactionManager(db.getDataSource()));
    }

    /** 테스트가 서로 남긴 종료 접속 근거만 기존 소유 서비스로 한정 정리한다. */
    private void drainPresence() {
        for (int n = 0; n < 20; n++) {
            int cleared = presence.cleanup(100);
            var removed = authCleanup().cleanup(100);
            if (cleared == 0
                    && removed.flows() == 0
                    && removed.accessTokens() == 0
                    && removed.refreshTokens() == 0
                    && removed.sessions() == 0
                    && removed.limits() == 0) return;
        }
        throw new AssertionError("합성 종료 접속 근거 적체가 한정 호출 안에 해소되지 않았습니다.");
    }

    /** 접속 근거와 갱신 시각 이외의 참가자 필드를 모두 비교한다. */
    private Map<String, Object> membershipContent(long room) {
        var row =
                new java.util.LinkedHashMap<>(
                        db.queryForMap("SELECT * FROM game_member WHERE room_id=?", room));
        for (String field :
                List.of("last_seen_at", "presence_session_id", "presence_auth_rev", "updated_at"))
            row.remove(field);
        return row;
    }

    @Test
    void terminalPresenceDetachThenActualFiveCountsAuthDrainPreservesBusinessContent()
            throws Exception {
        drainPresence();
        var fixture = privateRoom();
        var room = db.queryForMap("SELECT * FROM game_room WHERE id=?", fixture.room().id());
        var membership = membershipContent(fixture.room().id());
        var snapshot =
                db.queryForMap(
                        "SELECT * FROM review_snapshot WHERE id=?", fixture.room().snapshot());
        var audit =
                db.queryForList(
                        "SELECT * FROM game_audit WHERE room_id=? ORDER BY id",
                        fixture.room().id());
        assertThat(authCleanup().cleanup(100).sessions()).isZero();
        assertThat(presence.cleanup(1)).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT last_seen_at IS NULL AND presence_session_id IS NULL AND"
                                    + " presence_auth_rev IS NULL FROM game_member WHERE room_id=?",
                                Boolean.class,
                                fixture.room().id()))
                .isTrue();
        var counts = authCleanup().cleanup(100);
        assertThat(counts.sessions()).isEqualTo(1);
        assertThat(counts.flows()).isZero();
        assertThat(counts.accessTokens()).isBetween(0, 100);
        assertThat(counts.refreshTokens()).isBetween(0, 100);
        assertThat(counts.limits()).isBetween(0, 100);
        assertThat(db.queryForList("SELECT id FROM member_session WHERE id=?", fixture.session()))
                .isEmpty();
        assertThat(membershipContent(fixture.room().id()))
                .usingRecursiveComparison()
                .isEqualTo(membership);
        assertThat(db.queryForMap("SELECT * FROM game_room WHERE id=?", fixture.room().id()))
                .usingRecursiveComparison()
                .isEqualTo(room);
        assertThat(
                        db.queryForMap(
                                "SELECT * FROM review_snapshot WHERE id=?",
                                fixture.room().snapshot()))
                .usingRecursiveComparison()
                .isEqualTo(snapshot);
        assertThat(
                        db.queryForList(
                                "SELECT * FROM game_audit WHERE room_id=? ORDER BY id",
                                fixture.room().id()))
                .usingRecursiveComparison()
                .isEqualTo(audit);
    }

    @Test
    void actualNewFamilyHeartbeatAfterDiscoverySurvivesCapturedTripleAndCleanupFirstAlsoWorks()
            throws Exception {
        drainPresence();
        var fixture = privateRoom();
        String live = newFamily(fixture);
        var discovered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var sweep =
                    pool.submit(() -> interceptedPresence(discovered, resume, false).cleanup(100));
            try {
                assertThat(discovered.await(3, TimeUnit.SECONDS)).isTrue();
                play.heartbeat(live, fixture.room().key(), UUID.randomUUID());
                var latest =
                        db.queryForMap(
                                "SELECT last_seen_at,presence_session_id,presence_auth_rev FROM"
                                        + " game_member WHERE room_id=?",
                                fixture.room().id());
                resume.countDown();
                assertThat(sweep.get(8, TimeUnit.SECONDS)).isZero();
                assertThat(
                                db.queryForMap(
                                        "SELECT last_seen_at,presence_session_id,presence_auth_rev"
                                                + " FROM game_member WHERE room_id=?",
                                        fixture.room().id()))
                        .isEqualTo(latest);
            } finally {
                resume.countDown();
            }
        }
        var second = privateRoom();
        String secondLive = newFamily(second);
        assertThat(presence.cleanup(100)).isEqualTo(1);
        play.heartbeat(secondLive, second.room().key(), UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT presence_session_id FROM game_member WHERE room_id=?",
                                Long.class,
                                second.room().id()))
                .isEqualTo(service.authenticate(secondLive, false).sessionId());
    }

    @Test
    void sameFamilyTimestampChangedAfterDiscoveryPreservesWholeCapturedFence() throws Exception {
        drainPresence();
        var fixture = privateRoom();
        var discovered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var sweep =
                    pool.submit(() -> interceptedPresence(discovered, resume, false).cleanup(100));
            try {
                assertThat(discovered.await(3, TimeUnit.SECONDS)).isTrue();
                db.update(
                        "UPDATE game_member SET"
                            + " last_seen_at=clock_timestamp(),updated_at=clock_timestamp() WHERE"
                            + " room_id=?",
                        fixture.room().id());
                var latest =
                        db.queryForMap(
                                "SELECT last_seen_at,presence_session_id,presence_auth_rev FROM"
                                        + " game_member WHERE room_id=?",
                                fixture.room().id());
                resume.countDown();
                assertThat(sweep.get(8, TimeUnit.SECONDS)).isZero();
                assertThat(
                                db.queryForMap(
                                        "SELECT last_seen_at,presence_session_id,presence_auth_rev"
                                                + " FROM game_member WHERE room_id=?",
                                        fixture.room().id()))
                        .isEqualTo(latest);
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void qualificationAndHistoricalInactiveMembershipsOfIdleAndAbsoluteFamiliesDetach()
            throws Exception {
        drainPresence();
        var fixture = privateRoom();
        String live = newFamily(fixture);
        start(fixture.room());
        play.forfeit(live, fixture.room().key(), 1, UUID.randomUUID(), UUID.randomUUID());
        db.update("UPDATE game_member SET active_yn=false WHERE room_id=?", fixture.room().id());
        long policy =
                db.queryForObject(
                        "SELECT id FROM privacy_policy WHERE scope='PLAYTEST' AND state='ACTIVE'",
                        Long.class);
        long qualification =
                db.queryForObject(
                        "WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS n) INSERT INTO"
                            + " game_room"
                            + " (room_key,purpose,mode,qualification_kind,reviewer_id,selected_member_id,story_id,version_id,snapshot_id,runtime_id,runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,max_attempts,max_hints,created_at,lobby_until)"
                            + " SELECT"
                            + " gen_random_uuid(),'REVIEW_SOLO_QUALIFICATION','SOLO','FUNCTIONAL',?,?,r.story_id,r.version_id,r.snapshot_id,r.runtime_id,r.runtime_epoch,r.config_hash,r.rubric_hash,?,0,'WAITING',r.difficulty,r.limit_sec,r.max_attempts,r.max_hints,n,n+interval"
                            + " '30 minutes' FROM game_room r,clock WHERE r.id=? RETURNING id",
                        Long.class,
                        invitationActor.accountId(),
                        fixture.room().member(),
                        policy,
                        fixture.room().id());
        long idle = terminalFamily(fixture.room().member(), "-31 days");
        long absolute = terminalFamily(fixture.room().member(), "-91 days");
        db.update(
                "UPDATE game_member SET"
                    + " last_seen_at=clock_timestamp(),presence_session_id=?,presence_auth_rev=(SELECT"
                    + " auth_rev FROM member_session WHERE id=?) WHERE room_id=?",
                idle,
                idle,
                fixture.room().id());
        db.update(
                "INSERT INTO"
                    + " game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at,active_yn,last_seen_at,presence_session_id,presence_auth_rev)"
                    + " SELECT"
                    + " ?,s.member_id,s.identity_id,1,?,clock_timestamp(),false,clock_timestamp(),s.id,s.auth_rev"
                    + " FROM member_session s WHERE s.id=?",
                qualification,
                policy,
                absolute);
        var roots =
                db.queryForList(
                        "SELECT * FROM game_room WHERE id IN (?,?) ORDER BY id",
                        fixture.room().id(),
                        qualification);
        var members =
                db.queryForList(
                        "SELECT"
                            + " id,room_id,member_id,identity_id,slot,policy_id,accepted_at,active_yn,occupying_yn,role_code,version_id"
                            + " FROM game_member WHERE room_id IN (?,?) ORDER BY id",
                        fixture.room().id(),
                        qualification);
        assertThat(presence.cleanup(100)).isEqualTo(2);
        assertThat(authCleanup().cleanup(100).sessions()).isEqualTo(3);
        assertThat(
                        db.queryForList(
                                "SELECT * FROM game_room WHERE id IN (?,?) ORDER BY id",
                                fixture.room().id(),
                                qualification))
                .usingRecursiveComparison()
                .isEqualTo(roots);
        assertThat(
                        db.queryForList(
                                "SELECT"
                                    + " id,room_id,member_id,identity_id,slot,policy_id,accepted_at,active_yn,occupying_yn,role_code,version_id"
                                    + " FROM game_member WHERE room_id IN (?,?) ORDER BY id",
                                fixture.room().id(),
                                qualification))
                .usingRecursiveComparison()
                .isEqualTo(members);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_member WHERE room_id IN (?,?) AND"
                                        + " (last_seen_at IS NOT NULL OR presence_session_id IS NOT"
                                        + " NULL OR presence_auth_rev IS NOT NULL)",
                                Integer.class,
                                fixture.room().id(),
                                qualification))
                .isZero();
    }

    /** 생성 때 불변 30일/90일 산식을 그대로 적용한 합성 종료 family다. */
    private long terminalFamily(long member, String offset) {
        return db.queryForObject(
                "WITH clock AS MATERIALIZED (SELECT clock_timestamp()+?::interval AS c) INSERT INTO"
                    + " member_session(session_key,member_id,identity_id,auth_rev,created_at,last_refresh_at,idle_until,absolute_until)"
                    + " SELECT gen_random_uuid(),a.id,i.id,a.auth_rev,c,c,c+interval '720"
                    + " hours',c+interval '2160 hours' FROM clock,member_account a JOIN"
                    + " member_identity i ON i.member_id=a.id AND i.provider='LOCAL' AND"
                    + " i.active_yn WHERE a.id=? RETURNING id",
                Long.class,
                offset,
                member);
    }

    @Test
    void actualErasureRootsAndDirectRoomMembershipTargetsPreservePresence() throws Exception {
        drainPresence();
        var fixture = privateRoom();
        String live = newFamily(fixture);
        start(fixture.room());
        play.forfeit(live, fixture.room().key(), 1, UUID.randomUUID(), UUID.randomUUID());
        db.update(
                "UPDATE game_member SET"
                    + " last_seen_at=clock_timestamp(),presence_session_id=?,presence_auth_rev=(SELECT"
                    + " auth_rev FROM member_session WHERE id=?) WHERE room_id=?",
                fixture.session(),
                fixture.session(),
                fixture.room().id());
        acceptPresenceErasure(live);
        var waiting = privateRoom();
        acceptPresenceErasure(newFamily(waiting));
        var before =
                db.queryForList(
                        "SELECT * FROM game_member WHERE room_id IN (?,?) ORDER BY id",
                        fixture.room().id(),
                        waiting.room().id());
        var targets = db.queryForList("SELECT * FROM privacy_target ORDER BY id");
        assertThat(targets)
                .anyMatch(
                        row ->
                                row.get("room_id") instanceof Number id
                                        && fixture.room().id() == id.longValue());
        long waitingMembership =
                db.queryForObject(
                        "SELECT id FROM game_member WHERE room_id=?",
                        Long.class,
                        waiting.room().id());
        assertThat(targets)
                .anyMatch(
                        row ->
                                row.get("game_member_id") instanceof Number id
                                        && id.longValue() == waitingMembership);
        assertThat(presence.cleanup(100)).isZero();
        assertThat(authCleanup().cleanup(100).sessions()).isZero();
        assertThat(
                        db.queryForList(
                                "SELECT * FROM game_member WHERE room_id IN (?,?) ORDER BY id",
                                fixture.room().id(),
                                waiting.room().id()))
                .usingRecursiveComparison()
                .isEqualTo(before);
        assertThat(db.queryForList("SELECT * FROM privacy_target ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(targets);
    }

    /** 봉인된 실제 탈퇴 접수만 만들며 PRIMARY 대상은 소비하지 않는다. */
    private void acceptPresenceErasure(String live) {
        var actor = service.authenticate(live, false);
        UUID identity =
                db.queryForObject(
                        "SELECT i.identity_key FROM member_identity i JOIN member_session s ON"
                                + " s.identity_id=i.id WHERE s.id=?",
                        UUID.class,
                        actor.sessionId());
        String grant =
                service.reauthenticate(
                                live,
                                "DELETE_ACCOUNT",
                                identity,
                                "Synthetic-member-password-987!",
                                "198.19.0.48",
                                UUID.randomUUID())
                        .reauthGrant();
        service.deleteAccount(live, grant, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void boundedPresenceRollbackAndLockedRoomSkipUseActualJdbcTransactions() throws Exception {
        drainPresence();
        var first = privateRoom();
        var second = privateRoom();
        var before =
                db.queryForList(
                        "SELECT * FROM game_member WHERE room_id IN (?,?) ORDER BY id",
                        first.room().id(),
                        second.room().id());
        assertThatThrownBy(() -> interceptedPresence(null, null, true).cleanup(100))
                .hasMessageContaining("synthetic-presence-write-failure");
        assertThat(
                        db.queryForList(
                                "SELECT * FROM game_member WHERE room_id IN (?,?) ORDER BY id",
                                first.room().id(),
                                second.room().id()))
                .usingRecursiveComparison()
                .isEqualTo(before);
        assertThat(presence.cleanup(1)).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_member WHERE room_id IN (?,?) AND"
                                        + " presence_session_id IS NOT NULL",
                                Integer.class,
                                first.room().id(),
                                second.room().id()))
                .isEqualTo(1);
        db.update(
                "UPDATE game_member SET"
                    + " last_seen_at=clock_timestamp(),presence_session_id=?,presence_auth_rev=(SELECT"
                    + " auth_rev FROM member_session WHERE id=?) WHERE room_id=?",
                first.session(),
                first.session(),
                first.room().id());
        try (Connection held = db.getDataSource().getConnection();
                var statement =
                        held.prepareStatement("SELECT id FROM game_room WHERE id=? FOR UPDATE")) {
            held.setAutoCommit(false);
            statement.setLong(1, first.room().id());
            statement.executeQuery().close();
            assertThat(presence.cleanup(100)).isEqualTo(1);
            assertThat(
                            db.queryForObject(
                                    "SELECT presence_session_id FROM game_member WHERE room_id=?",
                                    Long.class,
                                    first.room().id()))
                    .isEqualTo(first.session());
            held.rollback();
        }
        assertThat(presence.cleanup(1)).isEqualTo(1);
        assertThat(presence.cleanup(1)).isZero();
        assertThatThrownBy(() -> presence.cleanup(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> presence.cleanup(101))
                .isInstanceOf(IllegalArgumentException.class);
        var ambient = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        ambient.executeWithoutResult(
                status ->
                        assertThatThrownBy(() -> presence.cleanup(1))
                                .hasMessage("AMBIENT_GAME_TRANSACTION"));
    }

    @Test
    void coopHeartbeatAndLobbyRootsDoNotDeadlockPresenceCleanup() throws Exception {
        drainPresence();
        var fixture = privateRoom("COOP");
        String live = newFamily(fixture);
        long liveSession = service.authenticate(live, false).sessionId();
        db.execute(
                "CREATE FUNCTION public.pause_presence_heartbeat() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN IF NEW.room_id="
                        + fixture.room().id()
                        + " AND NEW.presence_session_id="
                        + liveSession
                        + " THEN PERFORM pg_advisory_xact_lock(8642098); END IF; RETURN NEW; END"
                        + " $$");
        db.execute(
                "CREATE TRIGGER tr_pause_presence_heartbeat BEFORE UPDATE ON game_member FOR EACH"
                        + " ROW EXECUTE FUNCTION public.pause_presence_heartbeat()");
        var pool = Executors.newFixedThreadPool(3);
        try (Connection held = db.getDataSource().getConnection();
                var statement = held.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(8642098)");
            try {
                var beating =
                        pool.submit(
                                () ->
                                        play.heartbeat(
                                                live, fixture.room().key(), UUID.randomUUID()));
                awaitPresenceLock("UPDATE game_member SET%", "advisory");
                var sweeping = pool.submit(() -> presence.cleanup(100));
                assertThat(sweeping.get(8, TimeUnit.SECONDS)).isZero();
                var lobby =
                        pool.submit(
                                () -> games.game(live, fixture.room().key(), UUID.randomUUID()));
                awaitPresenceLock("SELECT %FROM member_account%FOR UPDATE%", null);
                statement.execute("SELECT pg_advisory_unlock(8642098)");
                beating.get(8, TimeUnit.SECONDS);
                assertThat(lobby.get(8, TimeUnit.SECONDS).gameKey())
                        .isEqualTo(fixture.room().key());
                assertThat(
                                db.queryForObject(
                                        "SELECT presence_session_id FROM game_member WHERE"
                                                + " room_id=?",
                                        Long.class,
                                        fixture.room().id()))
                        .isEqualTo(liveSession);
            } finally {
                statement.execute("SELECT pg_advisory_unlock(8642098)");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
            db.execute("DROP TRIGGER tr_pause_presence_heartbeat ON game_member");
            db.execute("DROP FUNCTION public.pause_presence_heartbeat()");
        }
    }

    /** 실제 PG 대기 관측으로 heartbeat/lobby의 루트 소유를 확인한다. */
    private void awaitPresenceLock(String query, String event) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            if (Boolean.TRUE.equals(
                    db.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE"
                                + " datname=current_database() AND pid<>pg_backend_pid() AND"
                                + " wait_event_type='Lock' AND query LIKE ? AND (?::text IS NULL OR"
                                + " wait_event=?))",
                            Boolean.class,
                            query,
                            event,
                            event))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("실제 접속 근거 잠금 장벽이 관측되지 않았습니다.");
    }

    /** 실제 JDBC discovery 이후만 정지하며 저장 가드와 SQL은 그대로 실행한다. */
    private GamePresenceCleanupService interceptedPresence(
            CountDownLatch discovered, CountDownLatch resume, boolean failSecondWrite) {
        JdbcTemplate template =
                new JdbcTemplate(db.getDataSource()) {
                    private int writes;

                    @Override
                    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                        var result = super.query(sql, mapper, args);
                        if (sql.startsWith("SELECT gm.id,") && discovered != null) {
                            discovered.countDown();
                            try {
                                if (!resume.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("접속 근거 발견 장벽 해제 실패");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                        }
                        return result;
                    }

                    @Override
                    public int update(String sql, Object... args) {
                        if (sql.startsWith("UPDATE game_member gm SET")
                                && failSecondWrite
                                && ++writes == 2) {
                            throw new IllegalStateException("synthetic-presence-write-failure");
                        }
                        return super.update(sql, args);
                    }
                };
        return new GamePresenceCleanupService(template);
    }

    /** 고정 사본·LOCAL 신원·FRIENDLY 동의를 실제 루트에 묶고 공개 발급 자격은 가장하지 않는다. */
    private Room room(UUID sourceKey, long member, long policy, boolean expired) {
        return room(sourceKey, member, policy, expired, null);
    }

    private Room room(UUID sourceKey, long member, long policy, boolean expired, Integer limitSec) {
        return room(sourceKey, member, policy, expired, limitSec, "SOLO");
    }

    private Room room(
            UUID sourceKey,
            long member,
            long policy,
            boolean expired,
            Integer limitSec,
            String mode) {
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
                        SELECT ?,'PUBLIC',?,?,?,?,?,?,?,?,?,0,'WAITING',?,?,?,?,t.c,t.c+interval '30 minutes'
                        FROM (SELECT clock_timestamp()-?*interval '1 minute' AS c) t RETURNING id
                        """,
                        Long.class,
                        key,
                        mode,
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

    /**
     * COOP 종료 회귀에 서로 다른 실제 LOCAL 회원의 두 번째 자리를 추가한다.
     *
     * @param room 아직 시작하지 않은 합성 COOP 방
     */
    private void addExpiryGuest(Room room) throws Exception {
        String token =
                account("expiry-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long guest = service.authenticate(token, false).memberId();
        db.update(
                "INSERT INTO game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at)"
                    + " SELECT r.id,?,i.id,2,r.policy_id,clock_timestamp() FROM game_room r JOIN"
                    + " member_identity i ON i.member_id=? AND i.active_yn AND i.provider='LOCAL'"
                    + " WHERE r.id=?",
                guest,
                guest,
                room.id());
    }

    /**
     * @param room 슬롯별 고정 역할을 배정하여 현재 DB 시각에 시작할 합성 방
     */
    private void start(Room room) {
        long version =
                db.queryForObject(
                        "SELECT version_id FROM game_room WHERE id=?", Long.class, room.id());
        db.update(
                "UPDATE game_member SET version_id=?,role_code=CASE WHEN slot=1 THEN 'R1' ELSE 'R2'"
                        + " END WHERE room_id=?",
                version,
                room.id());
        db.update(
                "WITH t AS (SELECT clock_timestamp() AS n) UPDATE game_room SET"
                        + " state='RUNNING',started_at=t.n,deadline_at=t.n+limit_sec*interval '1"
                        + " second',role_a='R1',role_b='R2',rev=1 FROM t WHERE id=?",
                room.id());
    }

    /**
     * @param room 슬롯별 고정 역할과 실제로 지난 마감을 지정할 합성 방
     */
    private void startExpired(Room room) {
        long version =
                db.queryForObject(
                        "SELECT version_id FROM game_room WHERE id=?", Long.class, room.id());
        db.update(
                "UPDATE game_member SET version_id=?,role_code=CASE WHEN slot=1 THEN 'R1' ELSE 'R2'"
                        + " END WHERE room_id=?",
                version,
                room.id());
        db.update(
                "WITH t AS (SELECT clock_timestamp()-interval '2 seconds' AS n) UPDATE game_room"
                    + " SET state='RUNNING',started_at=t.n-limit_sec*interval '1"
                    + " second',deadline_at=t.n,role_a='R1',role_b='R2',rev=1 FROM t WHERE id=?",
                room.id());
    }
}
