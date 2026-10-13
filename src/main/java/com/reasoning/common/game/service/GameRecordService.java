package com.reasoning.common.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** 공개 종료의 개인 스냅샷과 현행 회원만의 기록 읽기를 소유한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class GameRecordService {
    private final JdbcTemplate db;
    private final MemberAuthService auth;
    private final MemberPolicyGate memberPolicy;
    private final PlaytestPolicyGate friendly;
    private final CryptoService crypto;
    private final TransactionTemplate tx;

    public GameRecordService(
            JdbcTemplate db,
            MemberAuthService auth,
            MemberPolicyGate memberPolicy,
            @Qualifier("friendlyPolicyGate") PlaytestPolicyGate friendly,
            CryptoService crypto) {
        this.db = db;
        this.auth = auth;
        this.memberPolicy = memberPolicy;
        this.friendly = friendly;
        this.crypto = crypto;
        this.tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** worker가 잠근 방의 종료 전이와 동일 쓰기 TX에서 한 번 호출한다. 중복 호출은 원자적으로 실패한다. */
    public static void snapshotTerminal(JdbcTemplate db, long roomId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("GAME_RECORD_REQUIRES_WRITE_TRANSACTION");
        var room =
                db.queryForMap(
                        "SELECT"
                            + " purpose,state,mode,version_id,difficulty,role_a,role_b,end_reason,started_at,ended_at,time_basis_at,graded_count,final_score"
                            + " FROM public.game_room WHERE id=?",
                        roomId);
        if (!"PUBLIC".equals(room.get("purpose"))) return;
        if (!"ENDED".equals(room.get("state"))) throw AuthException.unavailable("GAME_UNAVAILABLE");
        purgeUnsubmitted(db, roomId);
        if (Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM public.game_member m JOIN"
                            + " public.member_account a ON a.id=m.member_id WHERE m.room_id=? AND"
                            + " m.role_code IS NOT NULL AND a.state='DELETED')",
                        Boolean.class,
                        roomId))) return;
        var members =
                db.queryForList(
                        "SELECT member_id,role_code FROM public.game_member WHERE room_id=? AND"
                                + " active_yn AND role_code IS NOT NULL ORDER BY slot FOR UPDATE",
                        roomId);
        if (members.size() != ("SOLO".equals(room.get("mode")) ? 1 : 2))
            throw AuthException.unavailable("GAME_UNAVAILABLE");
        String reason = (String) room.get("end_reason");
        String result =
                "SUCCESS".equals(reason)
                        ? "SUCCESS"
                        : Set.of("TIME_LIMIT", "ATTEMPTS_EXHAUSTED").contains(reason)
                                ? "FAILURE"
                                : "INCOMPLETE";
        for (var member : members) {
            long memberId = ((Number) member.get("member_id")).longValue();
            Integer previous =
                    db.queryForObject(
                            "SELECT count(*) FROM public.play_record WHERE room_id=? AND"
                                    + " member_id=?",
                            Integer.class,
                            roomId,
                            memberId);
            if (previous != null && previous != 0)
                throw AuthException.unavailable("GAME_UNAVAILABLE");
            int changed =
                    db.update(
                            """
                            INSERT INTO public.play_record(record_key,member_id,room_id,state,mode,story_code,story_title,difficulty,
                              role_a,role_b,own_role,own_role_name,result_status,end_reason,started_at,ended_at,time_basis_at,
                              duration_seconds,graded_count,own_hint_count,final_score)
                            SELECT ?,m.member_id,r.id,'RAW',r.mode,s.code,snap.payload->'sections'->'basic'->>'title',
                              r.difficulty,r.role_a,r.role_b,m.role_code,
                              (SELECT role->>'name' FROM jsonb_array_elements(snap.payload->'resources'->'roles') role
                                WHERE role->>'code'=m.role_code),?,r.end_reason,
                              r.started_at,r.ended_at,r.time_basis_at,
                              GREATEST(0,EXTRACT(EPOCH FROM (r.time_basis_at-r.started_at))::integer),r.graded_count,
                              (SELECT count(*) FROM public.game_hint h WHERE h.room_id=r.id AND h.member_id=m.member_id),r.final_score
                            FROM public.game_room r JOIN public.game_member m ON m.room_id=r.id AND m.member_id=?
                            JOIN public.story s ON s.id=r.story_id JOIN public.review_snapshot snap ON snap.id=r.snapshot_id
                            WHERE r.id=? AND r.purpose='PUBLIC' AND r.state='ENDED' AND m.active_yn
                            """,
                            UUID.randomUUID(),
                            result,
                            memberId,
                            roomId);
            if (changed != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
        }
    }

    /** 종료된 PUBLIC 방의 미접수 원문만 제거한다. 접수된 보고서와 정산 근거는 파기하지 않는다. */
    private static void purgeUnsubmitted(JdbcTemplate db, long roomId) {
        db.update(
                "UPDATE public.game_room SET draft_cipher=NULL,draft_hash=NULL WHERE id=?", roomId);
        db.update(
                "UPDATE public.game_report SET state=CASE WHEN state='PROPOSED' THEN 'CANCELLED'"
                    + " ELSE state"
                    + " END,payload_cipher=NULL,payload_hash=NULL,purged_at=clock_timestamp() WHERE"
                    + " room_id=? AND state IN"
                    + " ('PROPOSED','REJECTED','WITHDRAWN','INVALIDATED','CANCELLED') AND purged_at"
                    + " IS NULL",
                roomId);
    }

    public record ReportDetail(
            UUID reportKey,
            int submitNo,
            String state,
            JsonNode report,
            Integer baseScore,
            Boolean success,
            JsonNode scoreBasis,
            Instant acceptedAt,
            Instant gradedAt,
            UUID requestId) {}

    public record Page(List<JsonNode> items, String nextCursor, UUID requestId) {}

    /** 암호화 커서는 회원·페이지 상한과 발급 시각에 묶고, 최신 행이 과거 페이지를 밀어내지 못한다. */
    public Page getRecordList(String token, String cursor, int size, UUID requestId) {
        if (size < 1 || size > 100) throw AuthException.badRequest("INVALID_REQUEST");
        var principal = auth.authenticate(token, false);
        Long seek = null;
        if (cursor != null) {
            try {
                String[] values =
                        crypto.decrypt(
                                        cursor,
                                        "play-records:v1:" + principal.memberKey() + ":" + size)
                                .split("\\|", -1);
                Instant now = Instant.now();
                if (values.length != 2
                        || !values[0].matches("[1-9][0-9]{0,18}")
                        || Instant.parse(values[1]).isAfter(now)
                        || !Instant.parse(values[1]).plusSeconds(600).isAfter(now))
                    throw new IllegalArgumentException();
                seek = Long.parseLong(values[0]);
            } catch (RuntimeException failure) {
                throw AuthException.badRequest("INVALID_CURSOR");
            }
        }
        Long finalSeek = seek;
        return withMember(
                token,
                principal,
                () -> pageRows(principal.memberId(), finalSeek, size),
                rows -> {
                    List<Map<String, Object>> current =
                            pageRows(principal.memberId(), finalSeek, size);
                    if (!sameRecords(rows, current)) throw missing();
                    List<JsonNode> items = new ArrayList<>();
                    for (int i = 0; i < Math.min(size, rows.size()); i++)
                        items.add(view(rows.get(i), false, requestId));
                    String next = null;
                    if (rows.size() > size) {
                        var last = rows.get(size - 1);
                        next =
                                crypto.encrypt(
                                        last.get("id") + "|" + Instant.now(),
                                        "play-records:v1:" + principal.memberKey() + ":" + size);
                    }
                    return new Page(List.copyOf(items), next, requestId);
                });
    }

    /** 현재 소유권을 다시 확인하여 NORMAL 상세 또는 제한된 PURGED 필드만 반환한다. */
    public JsonNode getRecordDetail(String token, UUID key, UUID requestId) {
        var principal = auth.authenticate(token, false);
        return withMember(
                token,
                principal,
                () -> List.of(owned(key, principal.memberId())),
                selected -> {
                    Map<String, Object> record = owned(key, principal.memberId());
                    if (!sameRecords(selected, List.of(record))) throw missing();
                    return detail(record, principal.memberId(), requestId);
                });
    }

    /**
     * 방 키로 현재 본인의 정식 기록 상세를 조회한다. 이전 방 키로 파기 요약을 역조회하지 않는다.
     *
     * @param token 현재 LOCAL 회원의 ACCESS 토큰, null 불가
     * @param gameKey 실제 PUBLIC 게임 키, null 불가
     * @param requestId 서버가 부여한 감사 요청 식별자, null 불가
     * @return 기존 기록 상세와 동일한 NORMAL 투영, 새 기록이나 원문 보고서는 생성하지 않음
     * @throws AuthException 현재 인증·정책 실패, 본인 미완료 게임 또는 없는 원천
     */
    public JsonNode getGameResult(String token, UUID gameKey, UUID requestId) {
        var principal = auth.authenticate(token, false);
        return withMember(
                token,
                principal,
                () -> ownedGameRecord(gameKey, principal.memberId()),
                selected -> {
                    List<Map<String, Object>> current =
                            ownedGameRecord(gameKey, principal.memberId());
                    if (!sameRecords(selected, current)) throw missing();
                    if (current.isEmpty()) {
                        if (Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM public.game_room r JOIN"
                                            + " public.game_member m ON m.room_id=r.id WHERE"
                                            + " r.room_key=? AND r.purpose='PUBLIC' AND r.state IN"
                                            + " ('WAITING','RUNNING') AND m.member_id=? AND"
                                            + " m.active_yn AND NOT EXISTS (SELECT 1 FROM"
                                            + " public.game_member peer JOIN public.member_account"
                                            + " a ON a.id=peer.member_id WHERE peer.room_id=r.id"
                                            + " AND peer.active_yn AND a.state='DELETED'))",
                                        Boolean.class,
                                        gameKey,
                                        principal.memberId())))
                            throw AuthException.conflict("RESULT_NOT_READY");
                        throw missing();
                    }
                    return detail(current.getFirst(), principal.memberId(), requestId);
                });
    }

    /** 잠금 전후의 실제 RAW 후보만 발견하며 이 조회 자체로 원문 접근 권한을 부여하지 않는다. */
    private List<Map<String, Object>> ownedGameRecord(UUID gameKey, long memberId) {
        return db.queryForList(
                "SELECT p.* FROM public.play_record p JOIN public.game_room r ON"
                        + " r.id=p.room_id WHERE r.room_key=? AND r.purpose='PUBLIC' AND"
                        + " r.state='ENDED' AND p.member_id=? AND p.state='RAW'",
                gameKey,
                memberId);
    }

    /** 현행 소유권을 잠근 쓰기 TX에서 동일 상세 투영과 필수 노출·감사를 함께 확정한다. */
    private JsonNode detail(Map<String, Object> record, long memberId, UUID requestId) {
        JsonNode value = view(record, true, requestId);
        if ("RAW".equals(record.get("state"))) {
            long roomId = ((Number) record.get("room_id")).longValue();
            if (!value.path("revealText").isNull())
                db.update(
                        "INSERT INTO public.test_exposure(story_id,member_id,first_test_id)"
                                + " SELECT story_id,?,NULL FROM public.game_room WHERE id=?"
                                + " ON CONFLICT(story_id,member_id) DO NOTHING",
                        memberId,
                        roomId);
            auditRead(roomId, memberId, requestId);
        }
        return value;
    }

    /** 본인 기록의 현재 참가자 관계 및 공동 원문 목적을 재확인한 뒤 감사와 같은 TX에서만 복호화한다. */
    public ReportDetail getReportDetail(String token, UUID key, UUID reportKey, UUID requestId) {
        var principal = auth.authenticate(token, false);
        return withMember(
                token,
                principal,
                () -> List.of(owned(key, principal.memberId())),
                selected -> {
                    Map<String, Object> record = owned(key, principal.memberId());
                    if (!sameRecords(selected, List.of(record))) throw missing();
                    if (!"RAW".equals(record.get("state"))) throw missing();
                    long roomId = ((Number) record.get("room_id")).longValue();
                    if (!sourceCurrent(roomId, principal.memberId())) throw missing();
                    List<Map<String, Object>> rows =
                            db.queryForList(
                                    """
                                    SELECT r.id,r.report_key,r.room_id,r.snapshot_id,r.source_rev,r.ordinal,r.state,
                                      r.payload_cipher,r.payload_hash,r.base_score,r.success_yn,r.score_data::text AS score_data,
                                      r.accepted_at,r.graded_at
                                    FROM public.game_report r WHERE r.room_id=? AND r.report_key=?
                                      AND r.state IN ('GRADED','UNGRADABLE') AND r.purged_at IS NULL
                                    """,
                                    roomId,
                                    reportKey);
                    if (rows.size() != 1) throw missing();
                    Map<String, Object> report = rows.getFirst();
                    auditRead(roomId, principal.memberId(), requestId);
                    JsonNode raw =
                            new GameReportService(db, crypto)
                                    .decrypt(
                                            (byte[]) report.get("payload_cipher"),
                                            GameReportService.reportAad(
                                                    ((Number) report.get("id")).longValue(),
                                                    roomId,
                                                    ((Number) report.get("snapshot_id"))
                                                            .longValue(),
                                                    ((Number) report.get("source_rev"))
                                                            .longValue()),
                                            (String) report.get("payload_hash"));
                    return new ReportDetail(
                            reportKey,
                            ((Number) report.get("ordinal")).intValue(),
                            (String) report.get("state"),
                            raw,
                            number(report.get("base_score")),
                            (Boolean) report.get("success_yn"),
                            scoreBasis(report, snapshot(roomId)),
                            instant(report.get("accepted_at")),
                            instant(report.get("graded_at")),
                            requestId);
                });
    }

    /** 제한 요약에는 공동 원문 연결과 정확 시각을 포함하는 키 자체를 만들지 않는다. */
    private JsonNode view(Map<String, Object> row, boolean detail, UUID requestId) {
        boolean raw = "RAW".equals(row.get("state"));
        Long roomId = raw ? ((Number) row.get("room_id")).longValue() : null;
        if (raw && !sourceCurrent(roomId, ((Number) row.get("member_id")).longValue()))
            throw missing();
        var value =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("recordKey", row.get("record_key").toString())
                        .put("rawState", raw ? "NORMAL" : "PURGED")
                        .put("difficulty", number(row.get("difficulty")))
                        .put("durationSec", number(row.get("duration_seconds")))
                        .put("outcome", (String) row.get("end_reason"))
                        .put("finalScore", number(row.get("final_score")));
        value.putObject("story")
                .put("code", (String) row.get("story_code"))
                .put("title", (String) row.get("story_title"));
        value.putObject("selfRole")
                .put("code", (String) row.get("own_role"))
                .put("name", (String) row.get("own_role_name"));
        if (!raw) return value.put("rawDeletedReason", "JOINT_REPORT_DELETED");
        value.put("mode", (String) row.get("mode"))
                .put("teamAttempts", number(row.get("graded_count")))
                .put("ownHintsUsed", number(row.get("own_hint_count")))
                .put("timeBasisAt", instant(row.get("time_basis_at")).toString())
                .put("startedAt", instant(row.get("started_at")).toString())
                .put("endedAt", instant(row.get("ended_at")).toString());
        if (!detail) return value;
        var room =
                db.queryForMap(
                        "SELECT room_key,wrong_count FROM public.game_room WHERE id=?", roomId);
        value.put("gameKey", room.get("room_key").toString())
                .putNull("rawDeletedReason")
                .put("requestId", requestId.toString())
                .putNull("lastReportKey")
                .putNull("revealText")
                .putNull("scoreSummary");
        var history = value.putArray("reports");
        Map<String, Object> lastGraded = null;
        for (var report :
                db.queryForList(
                        """
                        SELECT report_key,ordinal,state,base_score,success_yn,score_data::text AS score_data
                        FROM public.game_report WHERE room_id=? AND state IN ('GRADED','UNGRADABLE')
                          AND purged_at IS NULL ORDER BY ordinal
                        """,
                        roomId)) {
            history.addObject()
                    .put("reportKey", report.get("report_key").toString())
                    .put("submitNo", number(report.get("ordinal")))
                    .put("state", (String) report.get("state"))
                    .put("success", (Boolean) report.get("success_yn"))
                    .put("baseScore", number(report.get("base_score")));
            value.put("lastReportKey", report.get("report_key").toString());
            if ("GRADED".equals(report.get("state"))) lastGraded = report;
        }
        if (row.get("final_score") != null) {
            JsonNode frozen = snapshot(roomId);
            int wrong = number(room.get("wrong_count"));
            int base = lastGraded == null ? 0 : number(lastGraded.get("base_score"));
            int penalty = wrong * frozen.path("policy").path("wrongPenalty").intValue();
            int score = number(row.get("final_score"));
            if (Math.max(0, base - penalty) != score)
                throw AuthException.unavailable("GAME_UNAVAILABLE");
            var summary =
                    value.putObject("scoreSummary")
                            .put("baseScore", base)
                            .put("wrongCount", wrong)
                            .put("penalty", penalty)
                            .put("finalScore", score);
            summary.set(
                    "categories",
                    lastGraded == null
                            ? JsonNodeFactory.instance.arrayNode()
                            : scoreBasis(lastGraded, frozen).path("categories"));
            value.put(
                    "revealText",
                    frozen.path("sections").path("reveal").path("revealText").textValue());
        }
        return value;
    }

    private List<Map<String, Object>> pageRows(long memberId, Long afterId, int size) {
        return db.queryForList(
                "SELECT * FROM public.play_record WHERE member_id=?"
                        + " AND (?::bigint IS NULL OR id<?) ORDER BY id DESC LIMIT ?",
                memberId,
                afterId,
                afterId,
                size + 1);
    }

    private JsonNode snapshot(long roomId) {
        String value =
                db.queryForObject(
                        "SELECT s.payload::text FROM public.game_room r JOIN public.review_snapshot"
                                + " s ON s.id=r.snapshot_id WHERE r.id=?",
                        String.class,
                        roomId);
        if (value == null) throw missing();
        return com.reasoning.common.story.model.FrozenSnapshotCodec.decode(
                        value.getBytes(StandardCharsets.UTF_8))
                .payload();
    }

    private boolean sourceCurrent(long roomId, long memberId) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM public.game_room r JOIN public.game_member own
                            ON own.room_id=r.id AND own.member_id=? AND own.active_yn
                          WHERE r.id=? AND r.purpose='PUBLIC' AND r.state='ENDED'
                            AND (SELECT count(*) FROM public.game_member peer
                              JOIN public.member_account a ON a.id=peer.member_id
                              WHERE peer.room_id=r.id AND peer.active_yn AND peer.role_code IS NOT NULL
                                AND a.state<>'DELETED')=CASE WHEN r.mode='SOLO' THEN 1 ELSE 2 END)
                        """,
                        Boolean.class,
                        memberId,
                        roomId));
    }

    private Map<String, Object> owned(UUID key, long memberId) {
        var rows =
                db.queryForList(
                        "SELECT * FROM public.play_record WHERE record_key=? AND member_id=?",
                        key,
                        memberId);
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    private void auditRead(long roomId, long memberId, UUID requestId) {
        if (db.update(
                        "INSERT INTO"
                            + " public.game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                            + " VALUES (?,?,'MEMBER',?,'CONTENT_READ',?,'EXPOSED')",
                        UUID.randomUUID(),
                        roomId,
                        memberId,
                        requestId)
                != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
    }

    private static boolean sameRecords(
            List<Map<String, Object>> expected, List<Map<String, Object>> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            var a = expected.get(i);
            var b = actual.get(i);
            if (!java.util.Objects.equals(a.get("record_key"), b.get("record_key"))
                    || !java.util.Objects.equals(a.get("room_id"), b.get("room_id"))
                    || !java.util.Objects.equals(a.get("state"), b.get("state"))
                    || !java.util.Objects.equals(a.get("rev"), b.get("rev"))) return false;
        }
        return true;
    }

    /** 사전 조회는 잠금 순서를 바꾸지 않고 후보 참가자 ID만 발견한다. 잠금 뒤 동일 원본을 재확인한다. */
    private Map<Long, List<Long>> originalMembers(List<Map<String, Object>> records) {
        Map<Long, List<Long>> rooms = new java.util.HashMap<>();
        for (var record : records) {
            if (!"RAW".equals(record.get("state"))) continue;
            Long room = ((Number) record.get("room_id")).longValue();
            if (rooms.containsKey(room)) continue;
            var members =
                    db.queryForList(
                            "SELECT member_id FROM public.game_member WHERE room_id=? AND role_code"
                                    + " IS NOT NULL ORDER BY member_id",
                            Long.class,
                            room);
            if (members.size() != ("SOLO".equals(record.get("mode")) ? 1 : 2)) throw missing();
            rooms.put(room, List.copyOf(members));
        }
        return rooms;
    }

    private <T> T withMember(
            String token,
            MemberAuthService.MemberPrincipal principal,
            Supplier<List<Map<String, Object>>> select,
            Function<List<Map<String, Object>>, T> action) {
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    TreeSet<Long> owners =
                            new TreeSet<>(memberPolicy.ownerIds(principal.memberId()));
                    owners.add(evidence.owner());
                    for (long id : owners) {
                        db.queryForList(
                                "SELECT id FROM public.admin_account WHERE id=? FOR SHARE", id);
                        db.queryForList(
                                "SELECT account_id FROM public.admin_credential WHERE account_id=?"
                                        + " FOR SHARE",
                                id);
                    }
                    List<Map<String, Object>> candidates = select.get();
                    Map<Long, List<Long>> closure = originalMembers(candidates);
                    TreeSet<Long> members = new TreeSet<>();
                    closure.values().forEach(members::addAll);
                    members.add(principal.memberId());
                    for (long member : members) {
                        if (db.queryForList(
                                                "SELECT id FROM public.member_account WHERE id=?"
                                                        + " FOR UPDATE",
                                                Long.class,
                                                member)
                                        .size()
                                != 1) throw missing();
                    }
                    if (!closure.equals(originalMembers(candidates))) throw missing();
                    String hash =
                            HexFormat.of().formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", token));
                    db.queryForList(
                            "SELECT id FROM public.member_session WHERE id=? AND member_id=? FOR"
                                    + " UPDATE",
                            principal.sessionId(),
                            principal.memberId());
                    db.queryForList(
                            "SELECT id FROM public.member_identity WHERE id=(SELECT identity_id"
                                    + " FROM public.member_session WHERE id=?) FOR UPDATE",
                            principal.sessionId());
                    db.queryForList(
                            "SELECT id FROM public.member_token WHERE token_hash=? FOR UPDATE",
                            hash);
                    if (!Boolean.TRUE.equals(
                            db.queryForObject(
                                    """
                                    SELECT EXISTS(SELECT 1 FROM public.member_account a JOIN public.member_session s ON s.member_id=a.id
                                      JOIN public.member_identity i ON i.id=s.identity_id AND i.member_id=a.id
                                      JOIN public.member_token t ON t.session_id=s.id
                                      WHERE a.id=? AND a.member_key=? AND a.state='ACTIVE' AND a.auth_rev=?
                                        AND s.id=? AND s.session_key=? AND s.auth_rev=a.auth_rev AND s.revoked_at IS NULL
                                        AND s.idle_until>clock_timestamp() AND s.absolute_until>clock_timestamp()
                                        AND i.provider='LOCAL' AND i.realm='LOCAL' AND i.active_yn AND i.proof_at IS NOT NULL
                                        AND t.token_hash=? AND t.kind='ACCESS' AND t.state='ISSUED' AND t.expires_at>clock_timestamp())
                                    """,
                                    Boolean.class,
                                    principal.memberId(),
                                    principal.memberKey(),
                                    principal.authRev(),
                                    principal.sessionId(),
                                    principal.sessionKey(),
                                    hash)))
                        throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    friendly.lock(evidence, owners);
                    T value = action.apply(candidates);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    friendly.lock(evidence, owners);
                    return value;
                });
    }

    /** 보관 판정의 항목 점수만 고정 rubric과 대조하여 범주별 숫자로 축소한다. 설명·인용은 내보내지 않는다. */
    private static JsonNode scoreBasis(Map<String, Object> report, JsonNode frozen) {
        if (!"GRADED".equals(report.get("state"))) return null;
        Object encoded = report.get("score_data");
        if (!(encoded instanceof String text)) throw AuthException.unavailable("GAME_UNAVAILABLE");
        JsonNode source = SnapshotJson.parse(text.getBytes(StandardCharsets.UTF_8));
        var items = new java.util.HashMap<String, Integer>();
        if (!source.path("baseResult").path("items").isArray())
            throw AuthException.unavailable("GAME_UNAVAILABLE");
        for (JsonNode item : source.path("baseResult").path("items")) {
            JsonNode score = item.path("score");
            if (!item.path("rubricCode").isTextual()
                    || !score.isIntegralNumber()
                    || !score.canConvertToInt()
                    || items.put(item.path("rubricCode").textValue(), score.intValue()) != null)
                throw AuthException.unavailable("GAME_UNAVAILABLE");
        }
        var sums = new java.util.LinkedHashMap<String, int[]>();
        int total = 0;
        for (JsonNode rubric : frozen.path("resources").path("rubrics")) {
            Integer score = items.remove(rubric.path("code").textValue());
            int maximum = rubric.path("maxScore").intValue();
            if (score == null || score < 0 || score > maximum)
                throw AuthException.unavailable("GAME_UNAVAILABLE");
            int[] category =
                    sums.computeIfAbsent(
                            rubric.path("category").textValue(), ignored -> new int[2]);
            category[0] += score;
            category[1] += maximum;
            total += score;
        }
        if (!items.isEmpty() || total != number(report.get("base_score")))
            throw AuthException.unavailable("GAME_UNAVAILABLE");
        var basis =
                JsonNodeFactory.instance
                        .objectNode()
                        .put(
                                "wrongPenalty",
                                Boolean.TRUE.equals(report.get("success_yn"))
                                        ? 0
                                        : frozen.path("policy").path("wrongPenalty").intValue());
        var categories = basis.putArray("categories");
        sums.forEach(
                (code, values) ->
                        categories
                                .addObject()
                                .put("category", code)
                                .put("score", values[0])
                                .put("maxScore", values[1]));
        return basis;
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private static Integer number(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static AuthException missing() {
        return new AuthException(404, "RECORD_NOT_FOUND", "RECORD_NOT_FOUND");
    }
}
