package com.reasoning.common.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.StoryReviewPreview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 현재 회원 권위와 방 잠금을 재사용해 공개 게임의 열람·단회 행동·서버 만료를 확정한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class GamePlayService {
    private final JdbcTemplate db;
    private final PublicGameService games;
    private final MemberAuthService auth;
    private final TransactionTemplate tx;

    /**
     * 기존 공개 게임 권위를 보관하고 만료 방마다 독립 확정하는 JDBC 거래를 구성한다.
     *
     * @param db 게임 저장소와 같은 DataSource를 가진 템플릿이며 null 불가
     * @param games 공개 게임의 잠금·상태 권위를 소유하는 서비스이며 null 불가
     * @param auth 현재 회원 인증 서비스이며 null 불가
     */
    public GamePlayService(JdbcTemplate db, PublicGameService games, MemberAuthService auth) {
        this.db = db;
        this.games = games;
        this.auth = auth;
        tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    public record CurrentRoom(PublicGameService.GameState game, UUID requestId) {}

    /** 점유 중인 공개 방만 현재 회원으로 다시 잠금 검증해 반환한다. 과거 종료 기록은 현재 방이 아니다. */
    public CurrentRoom currentRoom(String token, UUID requestId) {
        var principal = auth.authenticate(token, false);
        List<UUID> keys =
                db.queryForList(
                        "SELECT r.room_key FROM game_room r JOIN game_member m ON m.room_id=r.id"
                            + " WHERE m.member_id=? AND m.occupying_yn AND r.purpose='PUBLIC' ORDER"
                            + " BY r.id",
                        UUID.class,
                        principal.memberId());
        if (keys.size() > 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
        if (keys.isEmpty()) {
            games.notice(token, requestId);
            return new CurrentRoom(null, requestId);
        }
        var state = games.game(token, keys.getFirst(), requestId);
        return new CurrentRoom(
                "WAITING".equals(state.state()) || "RUNNING".equals(state.state()) ? state : null,
                requestId);
    }

    /** 본인 현행 세션·인증 버전만 접속 근거로 갱신하며 방 revision과 시간 제한은 변경하지 않는다. */
    public void heartbeat(String token, UUID gameKey, UUID requestId) {
        var principal = auth.authenticate(token, false);
        games.withMemberRoom(
                token,
                gameKey,
                false,
                (room, member) -> {
                    if (!"RUNNING".equals(room.state()) && !"WAITING".equals(room.state()))
                        throw AuthException.conflict("STATE_CONFLICT");
                    if (db.update(
                                    "UPDATE game_member SET"
                                        + " last_seen_at=clock_timestamp(),presence_session_id=?,presence_auth_rev=?,updated_at=clock_timestamp()"
                                        + " WHERE room_id=? AND member_id=? AND active_yn AND"
                                        + " identity_id=(SELECT identity_id FROM member_session"
                                        + " WHERE id=?)",
                                    principal.sessionId(),
                                    principal.authRev(),
                                    room.id(),
                                    member,
                                    principal.sessionId())
                            != 1) throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    return null;
                });
    }

    /** 1~3 고정 단계의 첫 열람만 인당 한도와 방 수정번호를 소비한다. 동일 단계 재열람은 무변경이다. */
    public PublicGameService.Action openHint(
            String token,
            UUID gameKey,
            long expectedRev,
            int level,
            UUID requestKey,
            UUID requestId) {
        check(gameKey, expectedRev, requestKey);
        if (level < 1 || level > 3) throw AuthException.badRequest("INVALID_REQUEST");
        String intent =
                digest(
                        "PUBLIC_HINT_OPEN",
                        gameKey.toString(),
                        Long.toString(expectedRev),
                        Integer.toString(level));
        return games.withReportRoom(
                token,
                gameKey,
                (room, member) -> {
                    lockRequest(requestKey);
                    var replay =
                            replay(room, member, "PUBLIC_HINT_OPEN", intent, requestKey, requestId);
                    if (replay != null) return replay;
                    requireRunning(room, true);
                    if (room.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    JsonNode hint =
                            hint(
                                    games.frozen(room.snapshotId(), room.versionId()).payload(),
                                    level);
                    if (hint == null
                            || !hint.path("code").isTextual()
                            || !hint.path("body").isTextual()
                            || hint.path("body").textValue().isBlank())
                        throw AuthException.conflict("HINT_UNAVAILABLE");
                    boolean opened =
                            Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM game_hint WHERE room_id=?"
                                                    + " AND member_id=? AND level=?)",
                                            Boolean.class,
                                            room.id(),
                                            member,
                                            level));
                    if (!opened) {
                        int count =
                                db.queryForObject(
                                        "SELECT count(*) FROM game_hint WHERE room_id=? AND"
                                                + " member_id=?",
                                        Integer.class,
                                        room.id(),
                                        member);
                        if (count >= room.maxHints())
                            throw AuthException.conflict("HINT_LIMIT_REACHED");
                        db.update(
                                "INSERT INTO"
                                    + " game_hint(room_id,member_id,snapshot_id,level,hint_code)"
                                    + " VALUES (?,?,?,?,?)",
                                room.id(),
                                member,
                                room.snapshotId(),
                                level,
                                hint.path("code").textValue());
                        increment(room, expectedRev);
                    }
                    return save(
                            room,
                            member,
                            "PUBLIC_HINT_OPEN",
                            intent,
                            requestKey,
                            requestId,
                            !opened);
                });
    }

    /** 고정 역할의 공통·본인 단서만 단회 읽음 표시하며 판정 대기 중에도 허용한다. */
    public PublicGameService.Action clueRead(
            String token,
            UUID gameKey,
            long expectedRev,
            String clueCode,
            UUID requestKey,
            UUID requestId) {
        check(gameKey, expectedRev, requestKey);
        if (clueCode == null || !clueCode.matches("[A-Z0-9_]{1,32}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        String intent =
                digest(
                        "PUBLIC_CLUE_READ",
                        gameKey.toString(),
                        Long.toString(expectedRev),
                        clueCode);
        return games.withReportRoom(
                token,
                gameKey,
                (room, member) -> {
                    lockRequest(requestKey);
                    var replay =
                            replay(room, member, "PUBLIC_CLUE_READ", intent, requestKey, requestId);
                    if (replay != null) return replay;
                    requireRunning(room, false);
                    if (room.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    JsonNode payload = games.frozen(room.snapshotId(), room.versionId()).payload();
                    boolean allowed = false;
                    boolean solo = "SOLO".equals(room.mode());
                    List<String> roles =
                            solo
                                    ? List.of(room.roleA(), room.roleB())
                                    : List.of(
                                            db.queryForObject(
                                                    "SELECT role_code FROM game_member WHERE"
                                                            + " room_id=? AND member_id=?",
                                                    String.class,
                                                    room.id(),
                                                    member));
                    for (String role : roles) {
                        JsonNode own =
                                StoryReviewPreview.role(
                                        payload.path("sections"), payload.path("resources"), role);
                        for (JsonNode clue : own.path("clues")) {
                            if (clueCode.equals(clue.path("code").textValue())) allowed = true;
                        }
                    }
                    if (!allowed) throw AuthException.conflict("CLUE_UNAVAILABLE");
                    boolean read =
                            Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM game_clue_read WHERE"
                                                    + " room_id=? AND member_id=? AND clue_code=?)",
                                            Boolean.class,
                                            room.id(),
                                            member,
                                            clueCode));
                    if (!read) {
                        db.update(
                                "INSERT INTO"
                                        + " game_clue_read(room_id,member_id,snapshot_id,clue_code)"
                                        + " VALUES (?,?,?,?)",
                                room.id(),
                                member,
                                room.snapshotId(),
                                clueCode);
                        increment(room, expectedRev);
                    }
                    return save(
                            room, member, "PUBLIC_CLUE_READ", intent, requestKey, requestId, !read);
                });
    }

    /** 미접수·기한 내 진행 중인 방을 점수/해설 없이 종료하고 동일 거래에서 개인 기록을 확정한다. */
    public PublicGameService.Action forfeit(
            String token, UUID gameKey, long expectedRev, UUID requestKey, UUID requestId) {
        check(gameKey, expectedRev, requestKey);
        String intent = digest("PUBLIC_FORFEIT", gameKey.toString(), Long.toString(expectedRev));
        return games.withReportRoom(
                token,
                gameKey,
                (room, member) -> {
                    lockRequest(requestKey);
                    var replay =
                            replay(room, member, "PUBLIC_FORFEIT", intent, requestKey, requestId);
                    if (replay != null) return replay;
                    requireRunning(room, true);
                    if (room.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    if (db.update(
                                    "WITH clock AS (SELECT clock_timestamp() AS t) UPDATE game_room"
                                        + " SET state='ENDED',end_reason='FORFEIT',ended_at=clock.t,time_basis_at=clock.t,rev=rev+1,updated_at=clock.t"
                                        + " FROM clock WHERE id=? AND state='RUNNING' AND rev=? AND"
                                        + " deadline_at>clock.t",
                                    room.id(),
                                    expectedRev)
                            != 1) throw AuthException.conflict("PLAY_DEADLINE_EXPIRED");
                    GameRecordService.snapshotTerminal(db, room.id());
                    return save(
                            room, member, "PUBLIC_FORFEIT", intent, requestKey, requestId, true);
                });
    }

    /**
     * 공개 만료 후보를 부모 우선 비차단 잠금과 결속 재검증 후 방별 독립 거래로 종료한다. 발견 조회는 거래 밖이며 15초 거래·10초 SQL·1초 잠금 제한은 방별
     * 처리에만 적용된다. 호출 전체의 제한 시간이 아니다.
     *
     * @param limit 발견할 후보 수 1~100
     * @return 이번 호출에서 종료한 방 수. 경합·결속 변경은 제외한다.
     * @throws AuthException limit 범위가 잘못되었거나 종결 무결성 검사가 실패한 경우
     * @throws RuntimeException 실제 저장 실패 시 해당 방만 롤백하고 이전 방의 확정은 유지한다.
     */
    public int settleExpired(int limit) {
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        List<Long> candidates =
                db.queryForList(
                        "SELECT id FROM game_room WHERE purpose='PUBLIC' AND ((state='WAITING' AND"
                            + " lobby_until<=clock_timestamp()) OR (state='RUNNING' AND"
                            + " deadline_at<=clock_timestamp() AND NOT EXISTS (SELECT 1 FROM"
                            + " game_report g WHERE g.room_id=game_room.id AND g.state='ACCEPTED')"
                            + " AND NOT EXISTS (SELECT 1 FROM game_member m JOIN member_account a"
                            + " ON a.id=m.member_id WHERE m.room_id=game_room.id AND m.role_code IS"
                            + " NOT NULL AND a.state='DELETED'))) ORDER BY id LIMIT ?",
                        Long.class,
                        limit);
        int settled = 0;
        for (long id : candidates) {
            Boolean changed =
                    tx.execute(
                            status -> {
                                db.execute("SET LOCAL statement_timeout = '10s'");
                                db.execute("SET LOCAL lock_timeout = '1s'");
                                var parents = expiryParents(id);
                                if (parents.isEmpty()) return false;
                                var members = expiryMembers(id);
                                List<Long> owners = expiryOwners(id);
                                for (long owner : owners) {
                                    if (!expiryLock("admin_account", "id", owner, false)
                                            || !expiryLock(
                                                    "admin_credential", "account_id", owner, false))
                                        return false;
                                }
                                for (var member : members)
                                    if (!expiryLock(
                                            "member_account", "id", member.get("member_id"), true))
                                        return false;
                                var parent = parents.getFirst();
                                if (!expiryLock(
                                                "grade_runtime",
                                                "id",
                                                parent.get("runtime_id"),
                                                true)
                                        || !expiryLock("story", "id", parent.get("story_id"), false)
                                        || !expiryLock(
                                                "story_version",
                                                "id",
                                                parent.get("version_id"),
                                                false)
                                        || !expiryLock("game_room", "id", id, true)) return false;
                                if (!parents.equals(expiryParents(id))
                                        || !members.equals(expiryMembers(id))
                                        || !owners.equals(expiryOwners(id))) return false;
                                return reconcile(db, id);
                            });
            if (Boolean.TRUE.equals(changed)) settled++;
        }
        return settled;
    }

    /**
     * 내부 고정 식별자의 필수 행을 잠그며 누락·경합은 취득 실패로 반환한다.
     *
     * @param table 이 클래스가 지정한 고정 테이블명이며 외부 입력 불가
     * @param key 해당 테이블의 고정 기본키 열명
     * @param id 잠글 행의 null 불가 식별자
     * @param exclusive true이면 수정 잠금, false이면 공유 잠금
     * @return 정확히 한 행의 잠금을 취득했는지 여부
     */
    private boolean expiryLock(String table, String key, Object id, boolean exclusive) {
        return db.queryForList(
                                "SELECT "
                                        + key
                                        + " FROM "
                                        + table
                                        + " WHERE "
                                        + key
                                        + "=? FOR "
                                        + (exclusive ? "UPDATE" : "SHARE")
                                        + " SKIP LOCKED",
                                id)
                        .size()
                == 1;
    }

    /**
     * 종료가 참조하는 고정 부모와 정책 결속만 발견한다. 현행 공개·활성 상태는 종료 조건이 아니다.
     *
     * @param id 발견한 방의 내부 식별자
     * @return 고정 결속 한 행 또는 방·필수 부모 누락 시 빈 목록
     */
    private List<java.util.Map<String, Object>> expiryParents(long id) {
        return db.queryForList(
                "SELECT"
                    + " r.purpose,r.mode,r.runtime_id,r.runtime_epoch,r.config_hash,r.story_id,r.version_id,r.snapshot_id,r.rubric_hash,r.policy_id,r.play_rev"
                    + " FROM game_room r JOIN grade_runtime g ON g.id=r.runtime_id JOIN story s ON"
                    + " s.id=r.story_id JOIN story_version v ON v.id=r.version_id WHERE r.id=?",
                id);
    }

    /**
     * 회원 잠금 전후의 참가자·탈퇴·정책 결속을 같은 순서로 비교한다.
     *
     * @param id 발견한 방의 내부 식별자
     * @return 회원 식별자 오름차순의 현재 참가자 결속
     */
    private List<java.util.Map<String, Object>> expiryMembers(long id) {
        return db.queryForList(
                "SELECT"
                    + " m.member_id,m.identity_id,m.policy_id,m.role_code,m.active_yn,a.state,p.policy_id"
                    + " AS profile_policy_id FROM game_member m JOIN member_account a ON"
                    + " a.id=m.member_id LEFT JOIN member_profile p ON p.member_id=m.member_id"
                    + " WHERE m.room_id=? ORDER BY m.member_id",
                id);
    }

    /**
     * 기존 배경 종료가 필요로 하는 정책 소유자 집합을 정렬한다. 전경 입장 조건은 추가하지 않는다.
     *
     * @param id 발견한 방의 내부 식별자
     * @return 중복 없는 소유자 식별자 오름차순 목록
     */
    private List<Long> expiryOwners(long id) {
        return db.queryForList(
                "SELECT DISTINCT p.owner_id FROM privacy_policy p WHERE p.id=(SELECT policy_id FROM"
                    + " game_room WHERE id=?) OR p.id IN (SELECT profile.policy_id FROM"
                    + " member_profile profile JOIN game_member m ON m.member_id=profile.member_id"
                    + " WHERE m.room_id=?) OR (p.scope='MEMBER_AUTH' AND p.state='ACTIVE') ORDER BY"
                    + " p.owner_id",
                Long.class,
                id,
                id);
    }

    /**
     * 이미 잠긴 방을 서버 시계로 종결한다. 대기는 현재 참가자, 시작 후에는 고정 역할 참가자의 탈퇴만 반영한다. 접수된 채점은 고정 복구 기한까지 이 경로에서 건드리지
     * 않는다.
     */
    static boolean reconcile(JdbcTemplate db, long id) {
        var row =
                db.queryForMap(
                        "SELECT purpose,state,lobby_until,deadline_at,snapshot_id,version_id,"
                                + "last_base_score,wrong_count FROM game_room WHERE id=?",
                        id);
        if (!"PUBLIC".equals(row.get("purpose"))) return false;
        String state = (String) row.get("state");
        Instant now = db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        boolean deleted =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM game_member m JOIN member_account a ON"
                                    + " a.id=m.member_id WHERE m.room_id=? AND a.state='DELETED'"
                                    + " AND (m.role_code IS NOT NULL OR (?='WAITING' AND"
                                    + " m.active_yn)))",
                                Boolean.class,
                                id,
                                state));
        if ("WAITING".equals(state)
                && !((Timestamp) row.get("lobby_until")).toInstant().isAfter(now)) {
            if (db.update(
                            "UPDATE game_room SET"
                                + " state='CANCELLED',end_reason=?,ended_at=clock_timestamp(),rev=rev+1,updated_at=clock_timestamp()"
                                + " WHERE id=? AND state='WAITING'",
                            deleted ? "ACCOUNT_DELETED" : "LOBBY_EXPIRED",
                            id)
                    != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
            auditSystem(db, id, deleted ? "PUBLIC_ACCOUNT_DELETED" : "PUBLIC_LOBBY_EXPIRED");
            return true;
        }
        if (!"RUNNING".equals(state)
                || ((Timestamp) row.get("deadline_at")).toInstant().isAfter(now)
                || Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM game_report WHERE room_id=? AND"
                                        + " state='ACCEPTED')",
                                Boolean.class,
                                id))) return false;
        // 삭제된 고정 참가자의 최초 탈퇴시각을 모르는 만료 정비는 결과를 확정하지 않는다.
        // 봉인된 PRIMARY 소비자가 같은 잠금 아래 종료 우선순위와 보존 요약을 결정한다.
        if (deleted) return false;
        int base =
                row.get("last_base_score") == null
                        ? 0
                        : ((Number) row.get("last_base_score")).intValue();
        Integer score;
        {
            var normal =
                    db.queryForList(
                            "SELECT base_score FROM game_report WHERE room_id=? AND state='GRADED'"
                                    + " ORDER BY ordinal DESC",
                            id);
            int graded =
                    db.queryForObject(
                            "SELECT graded_count FROM game_room WHERE id=?", Integer.class, id);
            if (normal.size() != graded
                    || (normal.isEmpty()
                            ? row.get("last_base_score") != null
                            : row.get("last_base_score") == null
                                    || base
                                            != ((Number) normal.getFirst().get("base_score"))
                                                    .intValue()))
                throw AuthException.unavailable("GAME_UNAVAILABLE");
            String source =
                    db.queryForObject(
                            "SELECT payload::text FROM review_snapshot WHERE id=? AND version_id=?",
                            String.class,
                            row.get("snapshot_id"),
                            row.get("version_id"));
            if (source == null) throw AuthException.unavailable("GAME_UNAVAILABLE");
            int penalty =
                    FrozenSnapshotCodec.decode(source.getBytes(StandardCharsets.UTF_8))
                            .payload()
                            .path("policy")
                            .path("wrongPenalty")
                            .asInt(-1);
            if (penalty < 0 || penalty > 100) throw AuthException.unavailable("GAME_UNAVAILABLE");
            score = Math.max(0, base - ((Number) row.get("wrong_count")).intValue() * penalty);
        }
        if (db.update(
                        "UPDATE game_room SET"
                            + " state='ENDED',end_reason=?,final_score=?,ended_at=clock_timestamp(),time_basis_at=deadline_at,rev=rev+1,updated_at=clock_timestamp()"
                            + " WHERE id=? AND state='RUNNING'",
                        "TIME_LIMIT",
                        score,
                        id)
                != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
        GameRecordService.snapshotTerminal(db, id);
        auditSystem(db, id, "PUBLIC_TIME_LIMIT");
        return true;
    }

    private static void auditSystem(JdbcTemplate db, long room, String action) {
        if (db.update(
                        "INSERT INTO"
                            + " game_audit(event_key,room_id,actor_kind,action,request_id,result)"
                            + " VALUES (?,?,'SYSTEM',?,?,'CHANGED')",
                        UUID.randomUUID(),
                        room,
                        action,
                        UUID.randomUUID())
                != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
    }

    static JsonNode hint(JsonNode payload, int level) {
        JsonNode found = null;
        for (JsonNode row : payload.path("resources").path("hints")) {
            if (row.path("level").asInt(-1) != level) continue;
            if (found != null) throw AuthException.conflict("HINT_UNAVAILABLE");
            found = row;
        }
        return found;
    }

    private void requireRunning(PublicGameService.Room room, boolean blockPending) {
        if (!"RUNNING".equals(room.state())) throw AuthException.conflict("STATE_CONFLICT");
        if (room.deadlineAt() == null
                || !room.deadlineAt()
                        .isAfter(
                                db.queryForObject("SELECT clock_timestamp()", Timestamp.class)
                                        .toInstant()))
            throw AuthException.conflict("PLAY_DEADLINE_EXPIRED");
        if (blockPending
                && Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM game_report WHERE room_id=? AND"
                                        + " state='ACCEPTED')",
                                Boolean.class,
                                room.id()))) throw AuthException.conflict("SUBMISSION_PENDING");
    }

    private void increment(PublicGameService.Room room, long revision) {
        if (db.update(
                        "UPDATE game_room SET rev=rev+1,updated_at=clock_timestamp() WHERE id=? AND"
                                + " rev=? AND state='RUNNING' AND deadline_at>clock_timestamp()",
                        room.id(),
                        revision)
                != 1) throw AuthException.conflict("EDIT_CONFLICT");
    }

    private PublicGameService.Action save(
            PublicGameService.Room room,
            long member,
            String name,
            String intent,
            UUID requestKey,
            UUID requestId,
            boolean changed) {
        long rev = db.queryForObject("SELECT rev FROM game_room WHERE id=?", Long.class, room.id());
        var original =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("gameKey", room.key().toString())
                        .put("rev", Long.toString(rev))
                        .put(
                                "state",
                                changed && "PUBLIC_FORFEIT".equals(name) ? "ENDED" : room.state())
                        .put("changed", changed);
        if (db.update(
                                "INSERT INTO"
                                    + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                    + " VALUES (?,?,?,?,?,?::jsonb)",
                                requestKey,
                                member,
                                room.id(),
                                name,
                                intent,
                                original.toString())
                        != 1
                || db.update(
                                "INSERT INTO"
                                    + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                    + " VALUES (?,?,'MEMBER',?,?,?,?)",
                                UUID.randomUUID(),
                                room.id(),
                                member,
                                name,
                                requestId,
                                changed ? "CHANGED" : "UNCHANGED")
                        != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
        return new PublicGameService.Action(
                name,
                false,
                changed,
                original,
                games.state(current(room), member, requestId),
                requestId);
    }

    private PublicGameService.Action replay(
            PublicGameService.Room room,
            long member,
            String name,
            String intent,
            UUID key,
            UUID requestId) {
        var rows =
                db.queryForList(
                        "SELECT room_id,member_id,action,request_hash,result_data::text AS data "
                                + "FROM game_action WHERE request_key=?",
                        key);
        if (rows.isEmpty()) return null;
        var receipt = rows.getFirst();
        if (((Number) receipt.get("room_id")).longValue() != room.id()
                || !memberEquals(receipt.get("member_id"), member)
                || !name.equals(receipt.get("action"))
                || !intent.equals(receipt.get("request_hash")))
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        return new PublicGameService.Action(
                name,
                true,
                false,
                SnapshotJson.parse(((String) receipt.get("data")).getBytes(StandardCharsets.UTF_8)),
                games.state(room, member, requestId),
                requestId);
    }

    private static boolean memberEquals(Object id, long member) {
        return id instanceof Number number && number.longValue() == member;
    }

    private PublicGameService.Room current(PublicGameService.Room room) {
        // 잠긴 root가 변경된 후 기존 서비스의 권위 투영은 새 revision을 사용한다.
        return games.roomById(room.id());
    }

    private void lockRequest(UUID key) {
        db.query(
                "SELECT pg_advisory_xact_lock(?)",
                rs -> null,
                key.getMostSignificantBits() ^ key.getLeastSignificantBits());
    }

    private static void check(UUID gameKey, long revision, UUID requestKey) {
        if (gameKey == null
                || gameKey.version() != 4
                || gameKey.variant() != 2
                || requestKey == null
                || requestKey.version() != 4
                || requestKey.variant() != 2
                || revision < 0
                || revision == Long.MAX_VALUE) throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static String digest(String... values) {
        try {
            StringBuilder normalized = new StringBuilder();
            for (String value : values) normalized.append(value.length()).append(':').append(value);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            normalized
                                                    .toString()
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
