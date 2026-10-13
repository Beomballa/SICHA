package com.reasoning.common.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.story.model.StoryReviewPreview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 공개 협동 방의 실재 두 회원·일회 초대·대기 동의와 시작을 원자적으로 관리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PublicCoopService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final PublicGameService games;
    private final MemberAuthService auth;

    public PublicCoopService(JdbcTemplate db, PublicGameService games, MemberAuthService auth) {
        this.db = db;
        this.games = games;
        this.auth = auth;
    }

    public record InvitationIssued(
            String action,
            boolean replayed,
            boolean changed,
            Object original,
            Object current,
            UUID requestId,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    UUID inviteKey,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String inviteSecret,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Instant expiresAt,
            boolean requiresReissue,
            String blockedReason) {

        /** 공통 액션을 평탄한 응답으로 옮기며 재전송에는 비밀 필드를 포함하지 않는다. */
        private InvitationIssued(
                PublicGameService.Action value,
                UUID inviteKey,
                String inviteSecret,
                Instant expiresAt,
                boolean requiresReissue,
                String blockedReason) {
            this(
                    value.action(),
                    value.replayed(),
                    value.changed(),
                    value.original(),
                    value.current(),
                    value.requestId(),
                    inviteKey,
                    inviteSecret,
                    expiresAt,
                    requiresReissue,
                    blockedReason);
        }
    }

    public record InvitePreview(
            UUID inviteKey,
            String title,
            int difficulty,
            int limitSec,
            int playerCount,
            Instant expiresAt,
            Instant lobbyExpiresAt,
            String policyCode,
            String noticeHash,
            String noticeSummary,
            UUID requestId) {}

    /** 현재 방장만 새 비밀을 발급하며 동일 요청 재전송에는 비밀을 재노출하지 않는다. */
    public InvitationIssued issueInvitation(
            String token, UUID gameKey, long expectedRev, UUID requestKey, UUID requestId) {
        check(expectedRev, requestKey);
        String intent = digest("PUBLIC_INVITE", gameKey.toString(), Long.toString(expectedRev));
        return games.withLobby(
                token,
                gameKey,
                false,
                (room, actor) -> {
                    requireHost(room, actor);
                    lockKey(requestKey);
                    var prior = receipt(room, actor, "PUBLIC_INVITE", intent, requestKey);
                    if (prior != null) {
                        boolean possible = waiting(room) && emptyGuest(room.id());
                        String blocked = possible ? null : "STATE_CONFLICT";
                        if (possible) {
                            try {
                                requireCurrentSelection(room);
                            } catch (AuthException failure) {
                                possible = false;
                                blocked = failure.code();
                            }
                        }
                        return new InvitationIssued(
                                response(
                                        room,
                                        actor,
                                        "PUBLIC_INVITE",
                                        true,
                                        false,
                                        prior,
                                        requestId),
                                null,
                                null,
                                null,
                                possible,
                                blocked);
                    }
                    requireWaiting(room, expectedRev);
                    requireCurrentSelection(room);
                    if (!emptyGuest(room.id())) throw AuthException.conflict("ROOM_FULL");
                    db.update(
                            "UPDATE game_invite SET revoked_at=clock_timestamp() WHERE room_id=?"
                                + " AND revoked_at IS NULL AND consumed_at IS NULL",
                            room.id());
                    byte[] secret = new byte[32];
                    RANDOM.nextBytes(secret);
                    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
                    UUID key = UUID.randomUUID();
                    int generation =
                            db.queryForObject(
                                    "SELECT COALESCE(max(generation),0)+1 FROM game_invite WHERE"
                                        + " room_id=?",
                                    Integer.class,
                                    room.id());
                    Instant expiry =
                            db.queryForObject(
                                            "SELECT LEAST(clock_timestamp()+interval '30"
                                                + " minutes',lobby_until) FROM game_room WHERE"
                                                + " id=?",
                                            Timestamp.class,
                                            room.id())
                                    .toInstant();
                    db.update(
                            "INSERT INTO"
                                + " game_invite(invite_key,room_id,generation,token_hash,expires_at)"
                                + " VALUES (?,?,?,?,?)",
                            key,
                            room.id(),
                            generation,
                            secretHash(key, raw),
                            Timestamp.from(expiry));
                    db.update(
                            "UPDATE game_member SET ready_yn=false,updated_at=clock_timestamp()"
                                + " WHERE room_id=? AND ready_yn",
                            room.id());
                    advance(room);
                    var original =
                            JsonNodeFactory.instance
                                    .objectNode()
                                    .put("gameKey", room.key().toString())
                                    .put("rev", Long.toString(room.rev() + 1))
                                    .put("inviteKey", key.toString());
                    save(
                            room,
                            actor,
                            "PUBLIC_INVITE",
                            intent,
                            requestKey,
                            requestId,
                            original,
                            true);
                    return new InvitationIssued(
                            response(
                                    room, actor, "PUBLIC_INVITE", false, true, original, requestId),
                            key,
                            raw,
                            expiry,
                            false,
                            null);
                });
    }

    /** 비밀 검증만 수행하는 조회로 가입·동의·준비·자료 접근을 변경하지 않는다. */
    public InvitePreview inspectInvitation(
            String token, UUID inviteKey, String inviteSecret, UUID requestId) {
        validateSecret(inviteKey, inviteSecret);
        auth.authenticate(token, false);
        UUID roomKey = inviteRoom(inviteKey);
        return games.withLobby(
                token,
                roomKey,
                true,
                (room, actor) -> {
                    var invite = invite(inviteKey, inviteSecret, room.id());
                    requireOpen(invite, room);
                    var story =
                            db.queryForMap(
                                    "SELECT"
                                        + " v.title,r.difficulty,r.limit_sec,p.code,p.notice_hash,p.policy_data->'notice'->>'body'"
                                        + " AS summary FROM game_room r JOIN story_version v ON"
                                        + " v.id=r.version_id JOIN privacy_policy p ON"
                                        + " p.id=r.policy_id WHERE r.id=?",
                                    room.id());
                    return new InvitePreview(
                            inviteKey,
                            (String) story.get("title"),
                            ((Number) story.get("difficulty")).intValue(),
                            ((Number) story.get("limit_sec")).intValue(),
                            2,
                            ((Timestamp) invite.get("expires_at")).toInstant(),
                            room.lobbyUntil(),
                            (String) story.get("code"),
                            (String) story.get("notice_hash"),
                            (String) story.get("summary"),
                            requestId);
                });
    }

    /** 현행 회원이 별도 고지를 확인할 때만 비밀을 소비하고 실제 다른 신원을 두 번째 자리에 결속한다. */
    public PublicGameService.Action joinInvitation(
            String token,
            UUID inviteKey,
            String inviteSecret,
            String policyCode,
            String noticeHash,
            UUID requestKey,
            UUID requestId) {
        validateSecret(inviteKey, inviteSecret);
        uuid(requestKey);
        if (policyCode == null || noticeHash == null) throw invalid();
        auth.authenticate(token, false);
        UUID roomKey = inviteRoom(inviteKey);
        String intent =
                digest(
                        "PUBLIC_JOIN",
                        inviteKey.toString(),
                        secretHash(inviteKey, inviteSecret),
                        policyCode,
                        noticeHash);
        return games.withLobby(
                token,
                roomKey,
                true,
                (room, actor) -> {
                    var invite = invite(inviteKey, inviteSecret, room.id());
                    lockKey(requestKey);
                    var prior = receipt(room, actor, "PUBLIC_JOIN", intent, requestKey);
                    if (prior != null) {
                        if (!active(room, actor)) throw AuthException.conflict("STATE_CONFLICT");
                        return response(room, actor, "PUBLIC_JOIN", true, false, prior, requestId);
                    }
                    requireOpen(invite, room);
                    requireCurrentSelection(room);
                    var notice =
                            db.queryForMap(
                                    "SELECT code,notice_hash FROM privacy_policy WHERE id=?",
                                    actor.policyId());
                    if (!policyCode.equals(notice.get("code"))
                            || !noticeHash.equals(notice.get("notice_hash")))
                        throw AuthException.conflict("POLICY_CHANGED");
                    var hosts =
                            db.queryForList(
                                    "SELECT m.member_id,m.identity_id FROM game_member m JOIN"
                                        + " member_account a ON a.id=m.member_id JOIN"
                                        + " member_identity i ON i.id=m.identity_id AND"
                                        + " i.member_id=a.id JOIN member_profile profile ON"
                                        + " profile.member_id=a.id JOIN privacy_policy p ON"
                                        + " p.id=profile.policy_id WHERE m.room_id=? AND m.slot=1"
                                        + " AND m.active_yn AND a.state='ACTIVE' AND i.active_yn"
                                        + " AND i.provider='LOCAL' AND i.realm='LOCAL' AND"
                                        + " i.proof_at IS NOT NULL AND p.state IN"
                                        + " ('ACTIVE','RETIRED')",
                                    room.id());
                    if (hosts.size() != 1) throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    var host = hosts.getFirst();
                    if (((Number) host.get("member_id")).longValue() == actor.principal().memberId()
                            || ((Number) host.get("identity_id")).longValue() == actor.identityId())
                        throw AuthException.conflict("STATE_CONFLICT");
                    if (!emptyGuest(room.id())) throw AuthException.conflict("ROOM_FULL");
                    if (Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM game_member WHERE member_id=? AND"
                                        + " occupying_yn)",
                                    Boolean.class,
                                    actor.principal().memberId())))
                        throw AuthException.conflict("ACTIVE_GAME_EXISTS");
                    int changed =
                            db.update(
                                    "UPDATE game_member SET"
                                        + " active_yn=true,ready_yn=false,updated_at=clock_timestamp()"
                                        + " WHERE room_id=? AND member_id=? AND identity_id=? AND"
                                        + " slot=2 AND NOT active_yn",
                                    room.id(),
                                    actor.principal().memberId(),
                                    actor.identityId());
                    if (changed == 0)
                        db.update(
                                "INSERT INTO"
                                    + " game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at)"
                                    + " VALUES (?,?,?,2,?,clock_timestamp())",
                                room.id(),
                                actor.principal().memberId(),
                                actor.identityId(),
                                actor.policyId());
                    db.update(
                            "UPDATE game_invite SET consumed_at=clock_timestamp() WHERE id=? AND"
                                + " consumed_at IS NULL AND revoked_at IS NULL",
                            invite.get("id"));
                    db.update(
                            "UPDATE game_member SET ready_yn=false,updated_at=clock_timestamp()"
                                + " WHERE room_id=?",
                            room.id());
                    advance(room);
                    var original = result(room, room.rev() + 1, true);
                    save(room, actor, "PUBLIC_JOIN", intent, requestKey, requestId, original, true);
                    return response(room, actor, "PUBLIC_JOIN", false, true, original, requestId);
                });
    }

    /** 방장은 취소하고 손님은 기존 관계만 비활성화하며 어느 쪽도 생성 시각을 연장하지 않는다. */
    public PublicGameService.Action leave(
            String token, UUID gameKey, long expectedRev, UUID requestKey, UUID requestId) {
        check(expectedRev, requestKey);
        String intent = digest("PUBLIC_LEAVE", gameKey.toString(), Long.toString(expectedRev));
        return games.withLobby(
                token,
                gameKey,
                true,
                (room, actor) -> {
                    lockKey(requestKey);
                    var prior = receipt(room, actor, "PUBLIC_LEAVE", intent, requestKey);
                    if (prior != null)
                        return new PublicGameService.Action(
                                "PUBLIC_LEAVE", true, false, prior, null, requestId);
                    if (!active(room, actor))
                        throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
                    requireWaiting(room, expectedRev);
                    db.update(
                            "UPDATE game_invite SET revoked_at=clock_timestamp() WHERE room_id=?"
                                + " AND revoked_at IS NULL AND consumed_at IS NULL",
                            room.id());
                    if (host(room, actor)) {
                        db.update(
                                "UPDATE game_room SET"
                                    + " state='CANCELLED',end_reason='HOST_LEFT',ended_at=clock_timestamp(),rev=rev+1,updated_at=clock_timestamp()"
                                    + " WHERE id=?",
                                room.id());
                    } else {
                        db.update(
                                "UPDATE game_member SET"
                                    + " active_yn=false,ready_yn=false,last_seen_at=NULL,presence_session_id=NULL,presence_auth_rev=NULL,updated_at=clock_timestamp()"
                                    + " WHERE room_id=? AND member_id=? AND active_yn",
                                room.id(),
                                actor.principal().memberId());
                        db.update(
                                "UPDATE game_member SET ready_yn=false,updated_at=clock_timestamp()"
                                    + " WHERE room_id=? AND ready_yn",
                                room.id());
                        advance(room);
                    }
                    var original = result(room, room.rev() + 1, true);
                    save(
                            room,
                            actor,
                            "PUBLIC_LEAVE",
                            intent,
                            requestKey,
                            requestId,
                            original,
                            true);
                    return new PublicGameService.Action(
                            "PUBLIC_LEAVE", false, true, original, null, requestId);
                });
    }

    /** 본인 준비만 갱신하며 true는 두 실재 회원과 정확한 현재 공개본에 결속한다. */
    public PublicGameService.Action ready(
            String token,
            UUID gameKey,
            long expectedRev,
            int publishedVersionNo,
            long playRev,
            boolean ready,
            UUID requestKey,
            UUID requestId) {
        check(expectedRev, requestKey);
        if (publishedVersionNo < 1 || playRev < 0) throw invalid();
        String intent =
                digest(
                        "PUBLIC_READY",
                        gameKey.toString(),
                        Long.toString(expectedRev),
                        Integer.toString(publishedVersionNo),
                        Long.toString(playRev),
                        Boolean.toString(ready));
        return games.withLobby(
                token,
                gameKey,
                false,
                (room, actor) -> {
                    lockKey(requestKey);
                    var prior = receipt(room, actor, "PUBLIC_READY", intent, requestKey);
                    if (prior != null)
                        return response(room, actor, "PUBLIC_READY", true, false, prior, requestId);
                    requireWaiting(room, expectedRev);
                    if (ready) {
                        var selected = games.lobbySelection(room);
                        if (selected.versionId() != room.versionId()
                                || selected.snapshotId() != room.snapshotId()
                                || selected.versionNo() != publishedVersionNo
                                || selected.playRev() != playRev
                                || room.playRev() != playRev)
                            throw AuthException.conflict("PREPARATION_CHANGED");
                        int eligible =
                                db.queryForObject(
                                        "SELECT count(DISTINCT m.identity_id) FROM game_member m"
                                            + " JOIN member_account a ON a.id=m.member_id JOIN"
                                            + " member_identity i ON i.id=m.identity_id AND"
                                            + " i.member_id=a.id JOIN member_profile profile ON"
                                            + " profile.member_id=a.id JOIN privacy_policy p ON"
                                            + " p.id=profile.policy_id WHERE m.room_id=? AND"
                                            + " m.active_yn AND m.policy_id=? AND a.state='ACTIVE'"
                                            + " AND i.active_yn AND i.provider='LOCAL' AND"
                                            + " i.realm='LOCAL' AND i.proof_at IS NOT NULL AND"
                                            + " p.state IN ('ACTIVE','RETIRED')",
                                        Integer.class,
                                        room.id(),
                                        actor.policyId());
                        if (eligible != 2) throw AuthException.conflict("STATE_CONFLICT");
                    }
                    boolean old =
                            Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT ready_yn FROM game_member WHERE room_id=? AND"
                                                + " member_id=?",
                                            Boolean.class,
                                            room.id(),
                                            actor.principal().memberId()));
                    if (old != ready) {
                        db.update(
                                "UPDATE game_member SET ready_yn=?,updated_at=clock_timestamp()"
                                    + " WHERE room_id=? AND member_id=? AND active_yn",
                                ready,
                                room.id(),
                                actor.principal().memberId());
                        advance(room);
                    }
                    var original = result(room, room.rev() + (old == ready ? 0 : 1), old != ready);
                    save(
                            room,
                            actor,
                            "PUBLIC_READY",
                            intent,
                            requestKey,
                            requestId,
                            original,
                            old != ready);
                    return response(
                            room, actor, "PUBLIC_READY", false, old != ready, original, requestId);
                });
    }

    /** 같은 사건의 최신 적격 후보만 명시 갱신하고 양측 준비를 해제한다. */
    public PublicGameService.Action refreshSelection(
            String token, UUID gameKey, long expectedRev, UUID requestKey, UUID requestId) {
        check(expectedRev, requestKey);
        String intent =
                digest("PUBLIC_SELECTION_REFRESH", gameKey.toString(), Long.toString(expectedRev));
        return games.withLobby(
                token,
                gameKey,
                false,
                (room, actor) -> {
                    lockKey(requestKey);
                    var prior =
                            receipt(room, actor, "PUBLIC_SELECTION_REFRESH", intent, requestKey);
                    if (prior != null)
                        return response(
                                room,
                                actor,
                                "PUBLIC_SELECTION_REFRESH",
                                true,
                                false,
                                prior,
                                requestId);
                    requireWaiting(room, expectedRev);
                    var selected = games.publishedLobbySelection(room);
                    var frozen = games.frozen(selected.snapshotId(), selected.versionId());
                    int attempts = frozen.payload().path("policy").path("attemptLimit").asInt(0);
                    int hints = frozen.payload().path("policy").path("hintsPerPerson").asInt(-1);
                    if (attempts < 1
                            || hints < 0
                            || frozen.payload()
                                            .path("sections")
                                            .path("basic")
                                            .path("difficulty")
                                            .asInt(0)
                                    != selected.difficulty())
                        throw AuthException.conflict("RELEASE_NOT_READY");
                    var pins =
                            db.queryForMap(
                                    "SELECT config_hash,rubric_hash,difficulty FROM game_room WHERE"
                                        + " id=?",
                                    room.id());
                    boolean changed =
                            selected.versionId() != room.versionId()
                                    || selected.snapshotId() != room.snapshotId()
                                    || selected.playRev() != room.playRev()
                                    || selected.runtimeId() != room.runtimeId()
                                    || selected.runtimeEpoch() != room.runtimeEpoch()
                                    || !selected.configHash().equals(pins.get("config_hash"))
                                    || !frozen.rubricHash().equals(pins.get("rubric_hash"))
                                    || selected.difficulty()
                                            != ((Number) pins.get("difficulty")).intValue()
                                    || selected.limitSec() != room.limitSec()
                                    || attempts != room.maxAttempts()
                                    || hints != room.maxHints();
                    if (changed) {
                        if (db.update(
                                        "UPDATE game_room SET"
                                            + " version_id=?,snapshot_id=?,play_rev=?,runtime_id=?,runtime_epoch=?,config_hash=?,rubric_hash=?,difficulty=?,limit_sec=?,max_attempts=?,max_hints=?,rev=rev+1,updated_at=clock_timestamp()"
                                            + " WHERE id=? AND state='WAITING' AND rev=?",
                                        selected.versionId(),
                                        selected.snapshotId(),
                                        selected.playRev(),
                                        selected.runtimeId(),
                                        selected.runtimeEpoch(),
                                        selected.configHash(),
                                        frozen.rubricHash(),
                                        selected.difficulty(),
                                        selected.limitSec(),
                                        attempts,
                                        hints,
                                        room.id(),
                                        expectedRev)
                                != 1) throw AuthException.conflict("EDIT_CONFLICT");
                        db.update(
                                "UPDATE game_member SET ready_yn=false,updated_at=clock_timestamp()"
                                    + " WHERE room_id=?",
                                room.id());
                    }
                    var original = result(room, room.rev() + (changed ? 1 : 0), changed);
                    save(
                            room,
                            actor,
                            "PUBLIC_SELECTION_REFRESH",
                            intent,
                            requestKey,
                            requestId,
                            original,
                            changed);
                    return response(
                            room,
                            actor,
                            "PUBLIC_SELECTION_REFRESH",
                            false,
                            changed,
                            original,
                            requestId);
                });
    }

    /** 방장과 손님의 현행 세션·준비 및 고정 후보 역할쌍을 확인한 후 단회 시작한다. */
    public PublicGameService.Action startGame(
            String token,
            UUID gameKey,
            long expectedRev,
            int publishedVersionNo,
            long playRev,
            UUID requestKey,
            UUID requestId) {
        check(expectedRev, requestKey);
        if (publishedVersionNo < 1 || playRev < 0) throw invalid();
        String intent =
                digest(
                        "PUBLIC_START",
                        gameKey.toString(),
                        Long.toString(expectedRev),
                        Integer.toString(publishedVersionNo),
                        Long.toString(playRev));
        return games.withLobby(
                token,
                gameKey,
                false,
                (room, actor) -> {
                    requireHost(room, actor);
                    lockKey(requestKey);
                    var prior = receipt(room, actor, "PUBLIC_START", intent, requestKey);
                    if (prior != null)
                        return response(room, actor, "PUBLIC_START", true, false, prior, requestId);
                    requireWaiting(room, expectedRev);
                    var selected = games.lobbySelection(room);
                    if (selected.versionId() != room.versionId()
                            || selected.snapshotId() != room.snapshotId()
                            || selected.versionNo() != publishedVersionNo
                            || selected.playRev() != playRev
                            || room.playRev() != playRev)
                        throw AuthException.conflict("PREPARATION_CHANGED");
                    var members =
                            db.queryForList(
                                    "SELECT"
                                        + " m.member_id,m.identity_id,m.slot,m.ready_yn,m.policy_id,m.last_seen_at,m.presence_auth_rev,a.auth_rev,a.state,s.revoked_at,s.idle_until,s.absolute_until,i.active_yn,i.provider,i.realm,i.proof_at,s.identity_id"
                                        + " AS session_identity,s.auth_rev AS"
                                        + " session_auth_rev,p.state AS member_policy_state FROM"
                                        + " game_member m JOIN member_account a ON a.id=m.member_id"
                                        + " JOIN member_identity i ON i.id=m.identity_id JOIN"
                                        + " member_profile profile ON profile.member_id=a.id JOIN"
                                        + " privacy_policy p ON p.id=profile.policy_id LEFT JOIN"
                                        + " member_session s ON s.id=m.presence_session_id WHERE"
                                        + " m.room_id=? AND m.active_yn ORDER BY m.slot",
                                    room.id());
                    if (members.size() != 2
                            || ((Number) members.get(0).get("member_id")).longValue()
                                    == ((Number) members.get(1).get("member_id")).longValue()
                            || ((Number) members.get(0).get("identity_id")).longValue()
                                    == ((Number) members.get(1).get("identity_id")).longValue())
                        throw AuthException.conflict("STATE_CONFLICT");
                    Instant now =
                            db.queryForObject("SELECT clock_timestamp()", Timestamp.class)
                                    .toInstant();
                    for (var person : members) {
                        if (!Boolean.TRUE.equals(person.get("ready_yn"))
                                || !"ACTIVE".equals(person.get("state"))
                                || !Boolean.TRUE.equals(person.get("active_yn"))
                                || !"LOCAL".equals(person.get("provider"))
                                || !"LOCAL".equals(person.get("realm"))
                                || person.get("proof_at") == null
                                || !List.of("ACTIVE", "RETIRED")
                                        .contains(person.get("member_policy_state"))
                                || person.get("session_identity") == null
                                || ((Number) person.get("session_identity")).longValue()
                                        != ((Number) person.get("identity_id")).longValue()
                                || person.get("session_auth_rev") == null
                                || ((Number) person.get("session_auth_rev")).longValue()
                                        != ((Number) person.get("auth_rev")).longValue()
                                || ((Number) person.get("policy_id")).longValue()
                                        != actor.policyId())
                            throw AuthException.conflict("STATE_CONFLICT");
                        if (!(person.get("last_seen_at") instanceof Timestamp seen)
                                || !seen.toInstant().plusSeconds(30).isAfter(now)
                                || person.get("presence_auth_rev") == null
                                || ((Number) person.get("presence_auth_rev")).longValue()
                                        != ((Number) person.get("auth_rev")).longValue()
                                || person.get("revoked_at") != null
                                || !(person.get("idle_until") instanceof Timestamp idle)
                                || !idle.toInstant().isAfter(now)
                                || !(person.get("absolute_until") instanceof Timestamp absolute)
                                || !absolute.toInstant().isAfter(now))
                            throw AuthException.conflict("PARTNER_OFFLINE");
                    }
                    var pairs =
                            db.query(
                                    "SELECT role_a,role_b FROM story_pair WHERE version_id=? AND"
                                        + " active_yn AND role_a<>role_b ORDER BY role_a,role_b",
                                    (rs, n) -> List.of(rs.getString(1), rs.getString(2)),
                                    room.versionId());
                    JsonNode frozen = games.frozen(room.snapshotId(), room.versionId()).payload();
                    pairs.removeIf(
                            pair -> {
                                for (JsonNode row : frozen.path("resources").path("pairs")) {
                                    if (!pair.get(0).equals(row.path("roleA").asText())
                                            || !pair.get(1).equals(row.path("roleB").asText()))
                                        continue;
                                    try {
                                        StoryReviewPreview.role(
                                                frozen.path("sections"),
                                                frozen.path("resources"),
                                                pair.get(0));
                                        StoryReviewPreview.role(
                                                frozen.path("sections"),
                                                frozen.path("resources"),
                                                pair.get(1));
                                        return false;
                                    } catch (IllegalArgumentException ignored) {
                                        return true;
                                    }
                                }
                                return true;
                            });
                    if (pairs.isEmpty()) throw AuthException.unprocessable("RELEASE_NOT_READY");
                    var pair = pairs.get(RANDOM.nextInt(pairs.size()));
                    boolean reverse = RANDOM.nextBoolean();
                    if (db.update(
                                    "UPDATE game_member SET version_id=?,role_code=CASE slot WHEN 1"
                                        + " THEN ? ELSE ? END,updated_at=clock_timestamp() WHERE"
                                        + " room_id=? AND active_yn AND role_code IS NULL",
                                    room.versionId(),
                                    reverse ? pair.get(1) : pair.get(0),
                                    reverse ? pair.get(0) : pair.get(1),
                                    room.id())
                            != 2) throw AuthException.conflict("STATE_CONFLICT");
                    if (db.update(
                                    "WITH c AS (SELECT clock_timestamp() AS t) UPDATE game_room SET"
                                        + " state='RUNNING',role_a=?,role_b=?,started_at=c.t,deadline_at=c.t+limit_sec*interval"
                                        + " '1 second',rev=rev+1,updated_at=c.t FROM c WHERE id=?"
                                        + " AND rev=? AND state='WAITING' AND lobby_until>c.t",
                                    pair.get(0),
                                    pair.get(1),
                                    room.id(),
                                    expectedRev)
                            != 1) throw AuthException.conflict("STATE_CONFLICT");
                    var original = result(room, room.rev() + 1, true);
                    save(
                            room,
                            actor,
                            "PUBLIC_START",
                            intent,
                            requestKey,
                            requestId,
                            original,
                            true);
                    return response(room, actor, "PUBLIC_START", false, true, original, requestId);
                });
    }

    private UUID inviteRoom(UUID key) {
        UUID room =
                db.query(
                        "SELECT r.room_key FROM game_invite i JOIN game_room r ON r.id=i.room_id"
                            + " WHERE i.invite_key=? AND r.purpose='PUBLIC' AND r.mode='COOP'",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                        key);
        if (room == null) throw unavailable();
        return room;
    }

    private Map<String, Object> invite(UUID key, String secret, long room) {
        var rows =
                db.queryForList(
                        "SELECT id,token_hash,expires_at,revoked_at,consumed_at FROM game_invite"
                            + " WHERE invite_key=? AND room_id=? FOR UPDATE",
                        key,
                        room);
        if (rows.isEmpty()
                || !MessageDigest.isEqual(
                        ((String) rows.getFirst().get("token_hash"))
                                .getBytes(StandardCharsets.US_ASCII),
                        secretHash(key, secret).getBytes(StandardCharsets.US_ASCII)))
            throw unavailable();
        return rows.getFirst();
    }

    private void requireOpen(Map<String, Object> invite, PublicGameService.Room room) {
        if (!((Timestamp) invite.get("expires_at")).toInstant().isAfter(Instant.now()))
            throw new AuthException(410, "INVITATION_EXPIRED", "INVITATION_EXPIRED");
        if (!waiting(room) || invite.get("revoked_at") != null || invite.get("consumed_at") != null)
            throw AuthException.conflict("STATE_CONFLICT");
        if (!emptyGuest(room.id())) throw AuthException.conflict("ROOM_FULL");
    }

    private boolean waiting(PublicGameService.Room room) {
        return "WAITING".equals(room.state()) && room.lobbyUntil().isAfter(Instant.now());
    }

    private void requireCurrentSelection(PublicGameService.Room room) {
        var selected = games.lobbySelection(room);
        if (selected.versionId() != room.versionId()
                || selected.snapshotId() != room.snapshotId()
                || selected.playRev() != room.playRev())
            throw AuthException.conflict("PREPARATION_CHANGED");
    }

    private void requireWaiting(PublicGameService.Room room, long expectedRev) {
        if (!waiting(room)) throw AuthException.conflict("STATE_CONFLICT");
        if (room.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
    }

    private boolean emptyGuest(long room) {
        return !Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM game_member WHERE room_id=? AND slot=2 AND"
                            + " active_yn)",
                        Boolean.class,
                        room));
    }

    private boolean active(PublicGameService.Room room, PublicGameService.LobbyActor actor) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM game_member WHERE room_id=? AND member_id=?"
                            + " AND identity_id=? AND active_yn)",
                        Boolean.class,
                        room.id(),
                        actor.principal().memberId(),
                        actor.identityId()));
    }

    private boolean host(PublicGameService.Room room, PublicGameService.LobbyActor actor) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM game_member WHERE room_id=? AND member_id=?"
                            + " AND identity_id=? AND slot=1 AND active_yn)",
                        Boolean.class,
                        room.id(),
                        actor.principal().memberId(),
                        actor.identityId()));
    }

    private void requireHost(PublicGameService.Room room, PublicGameService.LobbyActor actor) {
        if (!host(room, actor)) throw AuthException.forbidden("FORBIDDEN");
    }

    private void advance(PublicGameService.Room room) {
        if (db.update(
                        "UPDATE game_room SET rev=rev+1,updated_at=clock_timestamp() WHERE id=? AND"
                            + " rev=?",
                        room.id(),
                        room.rev())
                != 1) throw AuthException.conflict("EDIT_CONFLICT");
    }

    private JsonNode result(PublicGameService.Room room, long revision, boolean changed) {
        return JsonNodeFactory.instance
                .objectNode()
                .put("gameKey", room.key().toString())
                .put("rev", Long.toString(revision))
                .put("changed", changed);
    }

    private JsonNode receipt(
            PublicGameService.Room room,
            PublicGameService.LobbyActor actor,
            String action,
            String intent,
            UUID key) {
        var rows =
                db.queryForList(
                        "SELECT member_id,room_id,action,request_hash,result_data::text AS data"
                            + " FROM game_action WHERE request_key=?",
                        key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (((Number) row.get("member_id")).longValue() != actor.principal().memberId()
                || ((Number) row.get("room_id")).longValue() != room.id()
                || !action.equals(row.get("action"))
                || !intent.equals(row.get("request_hash")))
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        return SnapshotJson.parse(((String) row.get("data")).getBytes(StandardCharsets.UTF_8));
    }

    private PublicGameService.Action response(
            PublicGameService.Room room,
            PublicGameService.LobbyActor actor,
            String action,
            boolean replayed,
            boolean changed,
            JsonNode original,
            UUID requestId) {
        PublicGameService.Room current = games.roomById(room.id());
        boolean member = active(current, actor);
        return new PublicGameService.Action(
                action,
                replayed,
                changed,
                original,
                member ? games.state(current, actor.principal().memberId(), requestId) : null,
                requestId);
    }

    private void save(
            PublicGameService.Room room,
            PublicGameService.LobbyActor actor,
            String action,
            String intent,
            UUID key,
            UUID requestId,
            JsonNode original,
            boolean changed) {
        if (db.update(
                                "INSERT INTO"
                                    + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                    + " VALUES (?,?,?,?,?,?::jsonb)",
                                key,
                                actor.principal().memberId(),
                                room.id(),
                                action,
                                intent,
                                original.toString())
                        != 1
                || db.update(
                                "INSERT INTO"
                                    + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                    + " VALUES (?,?,'MEMBER',?,?,?,?)",
                                UUID.randomUUID(),
                                room.id(),
                                actor.principal().memberId(),
                                action,
                                requestId,
                                "PUBLIC_START".equals(action)
                                        ? "STARTED"
                                        : changed ? "CHANGED" : "UNCHANGED")
                        != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
    }

    private void lockKey(UUID key) {
        db.query(
                "SELECT pg_advisory_xact_lock(?)",
                rs -> null,
                key.getMostSignificantBits() ^ key.getLeastSignificantBits());
    }

    private static void check(long rev, UUID key) {
        uuid(key);
        if (rev < 0 || rev == Long.MAX_VALUE) throw invalid();
    }

    private static void uuid(UUID key) {
        if (key == null || key.version() != 4 || key.variant() != 2) throw invalid();
    }

    private static void validateSecret(UUID key, String secret) {
        uuid(key);
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        try {
            if (Base64.getUrlDecoder().decode(secret).length != 32) throw unavailable();
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static String secretHash(UUID key, String secret) {
        return digest("PUBLIC_COOP_INVITE_V1", key.toString(), secret);
    }

    private static String digest(String... fields) {
        try {
            StringBuilder input = new StringBuilder();
            for (String field : fields) input.append(field.length()).append(':').append(field);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static AuthException unavailable() {
        return new AuthException(404, "INVITATION_UNAVAILABLE", "INVITATION_UNAVAILABLE");
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }
}
