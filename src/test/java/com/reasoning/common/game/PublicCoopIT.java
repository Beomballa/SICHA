package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.game.service.PublicCoopService;
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

/** 합성 PUBLIC 대기 루트에서 서로 다른 실재 회원의 점유·초대 경계만 검증한다. */
@SpringBootTest
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
class PublicCoopIT extends PlaytestReportFixture {
    @Autowired PublicCoopService coop;
    @Autowired PublicGameService games;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper wireMapper;

    /** 실제 Jackson 응답은 공통 액션을 중첩하지 않고 재전송 비밀 필드를 완전히 제외한다. */
    @Test
    void invitationWireEnvelopeIsFlatAndReplayOmitsSecretFields() {
        UUID request = UUID.randomUUID();
        var issued =
                wireMapper.valueToTree(
                        new PublicCoopService.InvitationIssued(
                                "PUBLIC_INVITE",
                                false,
                                true,
                                java.util.Map.of("rev", "1"),
                                java.util.Map.of("state", "WAITING"),
                                request,
                                UUID.randomUUID(),
                                "A".repeat(43),
                                java.time.Instant.parse("2030-01-01T00:30:00Z"),
                                false,
                                null));
        assertThat(issued.path("action").isTextual()).isTrue();
        assertThat(issued.path("action").asText()).isEqualTo("PUBLIC_INVITE");
        assertThat(issued.path("requestId").asText()).isEqualTo(request.toString());
        assertThat(issued.has("inviteSecret")).isTrue();
        var replay =
                wireMapper.valueToTree(
                        new PublicCoopService.InvitationIssued(
                                "PUBLIC_INVITE",
                                true,
                                false,
                                java.util.Map.of("rev", "1"),
                                java.util.Map.of("state", "WAITING"),
                                request,
                                null,
                                null,
                                null,
                                true,
                                null));
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("requiresReissue").asBoolean()).isTrue();
        assertThat(replay.has("inviteKey")).isFalse();
        assertThat(replay.has("inviteSecret")).isFalse();
        assertThat(replay.has("expiresAt")).isFalse();
    }

    @Test
    void inviteLocatorIsOneOpenGenerationPerRoomAndNeverStoresPlaintext() {
        assertThat(db.queryForObject("SELECT to_regclass('public.game_invite')", String.class))
                .isEqualTo("game_invite");
        var columns =
                db.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE"
                            + " table_schema='public' AND table_name='game_invite'",
                        String.class);
        assertThat(columns)
                .contains(
                        "invite_key",
                        "token_hash",
                        "generation",
                        "expires_at",
                        "consumed_at",
                        "revoked_at")
                .doesNotContain("invite_secret", "token_cipher", "secret_cipher");
        assertThat(
                        db.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND"
                                    + " indexname='uk_game_invite_open'",
                                String.class))
                .contains("revoked_at IS NULL", "consumed_at IS NULL");
    }

    @Test
    void liveInvitationCannotBePhysicallyDeletedButTerminalCleanupRemovesHash() throws Exception {
        playtestPolicy();
        String host =
                account("coop-guard-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String guest =
                account("coop-guard-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID source =
                invitation(
                        List.of(
                                service.authenticate(host, false).memberKey(),
                                service.authenticate(guest, false).memberKey()));
        long policy = syntheticFriendlyPolicy();
        var principal = service.authenticate(host, false);
        UUID key = seed(source, principal.memberId(), policy);
        long room = db.queryForObject("SELECT id FROM game_room WHERE room_key=?", Long.class, key);
        long identity =
                db.queryForObject(
                        "SELECT identity_id FROM member_session WHERE id=? AND member_id=?",
                        Long.class,
                        principal.sessionId(),
                        principal.memberId());
        db.update(
                "INSERT INTO game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at)"
                    + " VALUES (?,?,?,1,?,clock_timestamp())",
                room,
                principal.memberId(),
                identity,
                policy);
        // 실제 현행 세션·세대에 결속된 heartbeat 저장형을 사용하고 공개 수집 gate는 우회하지 않는다.
        db.update(
                "UPDATE game_member SET"
                    + " last_seen_at=clock_timestamp(),presence_session_id=?,presence_auth_rev=?"
                    + " WHERE room_id=? AND member_id=?",
                principal.sessionId(),
                principal.authRev(),
                room,
                principal.memberId());
        assertThat(
                        db.queryForObject(
                                "SELECT last_seen_at IS NOT NULL AND presence_session_id=? AND"
                                    + " presence_auth_rev=? FROM game_member WHERE room_id=? AND"
                                    + " member_id=?",
                                Boolean.class,
                                principal.sessionId(),
                                principal.authRev(),
                                room,
                                principal.memberId()))
                .isTrue();
        UUID invite = UUID.randomUUID();
        db.update(
                "INSERT INTO game_invite(invite_key,room_id,generation,token_hash,expires_at)"
                    + " SELECT ?,id,1,repeat('a',64),lobby_until FROM game_room WHERE id=?",
                invite,
                room);
        assertThatThrownBy(() -> db.update("DELETE FROM game_invite WHERE invite_key=?", invite))
                .hasMessageContaining("live invitation physical deletion forbidden");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_invite WHERE invite_key=?",
                                Integer.class,
                                invite))
                .isEqualTo(1);
        db.update(
                "UPDATE game_room SET"
                    + " state='CANCELLED',end_reason='HOST_LEFT',ended_at=clock_timestamp() WHERE"
                    + " id=?",
                room);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_invite WHERE invite_key=?",
                                Integer.class,
                                invite))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT active_yn AND NOT occupying_yn AND last_seen_at IS NULL AND"
                                    + " presence_session_id IS NULL AND presence_auth_rev IS NULL"
                                    + " FROM game_member WHERE room_id=? AND member_id=?",
                                Boolean.class,
                                room,
                                principal.memberId()))
                .isTrue();
    }

    @Test
    void distinctRealMembersAndHistoricalGuestCannotBecomeDuplicateOccupants() throws Exception {
        playtestPolicy();
        String host =
                account("coop-host-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String guest =
                account("coop-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String outsider =
                account("coop-outsider-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        var first = service.authenticate(host, false);
        var second = service.authenticate(guest, false);
        var third = service.authenticate(outsider, false);
        assertThat(first.memberId()).isNotEqualTo(second.memberId()).isNotEqualTo(third.memberId());
        UUID source = invitation(List.of(first.memberKey(), second.memberKey()));
        long policy =
                db.queryForObject(
                        "SELECT id FROM privacy_policy WHERE scope='PLAYTEST' AND state='ACTIVE'",
                        Long.class);
        // 합성 PLAYTEST 고지를 PUBLIC에 사용하지 않는다. DB가 목적별 동의를 강제한다.
        assertThatThrownBy(() -> seed(source, first.memberId(), policy))
                .hasMessageContaining("game purpose policy mismatch");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_room WHERE purpose='PUBLIC' AND"
                                    + " policy_id=?",
                                Integer.class,
                                policy))
                .isZero();
        assertThatThrownBy(
                        () ->
                                coop.inspectInvitation(
                                        host, UUID.randomUUID(), "x".repeat(43), UUID.randomUUID()))
                .hasMessageContaining("INVITATION_UNAVAILABLE");
    }

    @Test
    void unsupportedSyntheticReleaseCannotCreateCoopOrSelfJoin() throws Exception {
        playtestPolicy();
        String first =
                account("coop-no-release-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("coop-peer-no-release-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID source =
                invitation(
                        List.of(
                                service.authenticate(first, false).memberKey(),
                                service.authenticate(second, false).memberKey()));
        var story =
                db.queryForMap(
                        "SELECT v.story_id,v.version_no,s.code,s.play_rev FROM play_test t JOIN"
                            + " story_version v ON v.id=t.version_id JOIN story s ON"
                            + " s.id=v.story_id WHERE t.test_key=?",
                        source);
        assertThatThrownBy(
                        () ->
                                games.createGame(
                                        first,
                                        "COOP",
                                        (String) story.get("code"),
                                        ((Number) story.get("version_no")).intValue(),
                                        ((Number) story.get("play_rev")).longValue(),
                                        "NOT_CONSENT",
                                        "0".repeat(64),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FRIENDLY_COLLECTION_NOT_READY");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_room WHERE purpose='PUBLIC' AND"
                                    + " story_id=?",
                                Integer.class,
                                story.get("story_id")))
                .isZero();
    }

    /** 검수 사본을 공개로 승격하지 않고 정책 목적 제약만 시험하는 합성 루트 삽입이다. */
    private UUID seed(UUID sourceKey, long member, long policy) {
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
                                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                                        String.class,
                                        source.get("snapshot_id"))
                                .getBytes(StandardCharsets.UTF_8));
        UUID key = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " game_room(room_key,purpose,mode,story_id,version_id,snapshot_id,runtime_id,runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,max_attempts,max_hints,created_at,lobby_until)"
                    + " SELECT"
                    + " ?,'PUBLIC','COOP',?,?,?,?,?,?,?,?,0,'WAITING',?,?,?,?,t.c,t.c+interval '30"
                    + " minutes' FROM (SELECT clock_timestamp() AS c) t",
                key,
                source.get("story_id"),
                source.get("version_id"),
                source.get("snapshot_id"),
                source.get("runtime_id"),
                source.get("runtime_epoch"),
                source.get("config_hash"),
                frozen.rubricHash(),
                policy,
                source.get("difficulty"),
                source.get("limit_sec"),
                frozen.payload().path("policy").path("attemptLimit").asInt(),
                frozen.payload().path("policy").path("hintsPerPerson").asInt());
        return key;
    }

    /** 공개 동의로 사용하지 않는 DB 제약용 합성 FRIENDLY 문서다. */
    private long syntheticFriendlyPolicy() throws Exception {
        var parent =
                db.queryForMap(
                        "SELECT owner_id,policy_data::text AS document FROM privacy_policy WHERE"
                            + " scope='PLAYTEST' AND state='ACTIVE' ORDER BY id DESC LIMIT 1");
        var data =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        JSON.readTree((String) parent.get("document"));
        data.set(
                "retention",
                JSON.valueToTree(
                        java.util.Map.of(
                                "inviteSeconds",
                                1800,
                                "lobbySeconds",
                                1800,
                                "raw",
                                "ACCOUNT_ACTIVE",
                                "backupMaxSeconds",
                                3024000)));
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) data.get("evidence");
        evidence.elements()
                .forEachRemaining(
                        item ->
                                ((com.fasterxml.jackson.databind.node.ObjectNode) item)
                                        .put("scope", "FRIENDLY"));
        return db.queryForObject(
                "INSERT INTO"
                    + " privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                    + " VALUES (?,'SYNTHETIC_HTTP','FRIENDLY','DRAFT',repeat('a',64),?::jsonb,?)"
                    + " RETURNING id",
                Long.class,
                "COOP_SQL_" + UUID.randomUUID().toString().replace("-", ""),
                JSON.writeValueAsString(data),
                parent.get("owner_id"));
    }
}
