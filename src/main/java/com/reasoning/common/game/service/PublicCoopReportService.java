package com.reasoning.common.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.GradeModels.Report;
import com.reasoning.common.grading.model.SnapshotJson;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 공개 협동 방의 저장 초안과 불변 제안, 상대의 명시적 동의만 다룬다. 권위와 잠금은 방 소유 서비스가 제공한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PublicCoopReportService {
    private final PublicGameService owner;
    private final JdbcTemplate db;
    private final GameReportService reports;

    public PublicCoopReportService(
            PublicGameService owner,
            JdbcTemplate db,
            com.reasoning.common.auth.service.CryptoService crypto) {
        this.owner = owner;
        this.db = db;
        this.reports = new GameReportService(db, crypto);
    }

    public record Proposal(
            UUID reportKey,
            String sourceRev,
            JsonNode report,
            int proposerSlot,
            String state,
            boolean canAccept,
            boolean canReject,
            boolean canWithdraw) {}

    public record Pending(
            UUID reportKey,
            int submitNo,
            JsonNode report,
            Instant acceptedAt,
            Instant gradeDeadline) {}

    public record ReportView(
            UUID gameKey,
            String draftRev,
            JsonNode report,
            Proposal proposal,
            Pending pending,
            String submissionState,
            UUID requestId) {}

    /**
     * 현재 인증된 참가자의 고정 원문만 반환하며 조회는 동의하지 않는다.
     *
     * @param token 현재 회원 접근 토큰
     * @param gameKey 공개 협동 방 UUID
     * @param requestId 조회 추적 UUID
     * @return 저장 초안과 활성 제안 또는 접수본
     * @throws AuthException 현재 권위나 원문 결속이 무효일 때
     */
    public ReportView report(String token, UUID gameKey, UUID requestId) {
        return owner.withReportRoom(
                token,
                gameKey,
                (room, member) -> {
                    running(room);
                    Map<String, Object> draft = draft(room);
                    long revision = number(draft, "draft_rev");
                    JsonNode saved =
                            draft.get("draft_cipher") == null
                                    ? GameReportService.empty()
                                    : reports.decrypt(
                                            (byte[]) draft.get("draft_cipher"),
                                            GameReportService.draftAad(room.id(), revision),
                                            (String) draft.get("draft_hash"));
                    List<Map<String, Object>> active = active(room);
                    if (active.size() > 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
                    Proposal proposal = null;
                    Pending pending = null;
                    if (!active.isEmpty()) {
                        var row = active.getFirst();
                        long id = number(row, "id");
                        long sourceRev = number(row, "source_rev");
                        JsonNode fixed =
                                reports.decrypt(
                                        (byte[]) row.get("payload_cipher"),
                                        GameReportService.reportAad(
                                                id, room.id(), room.snapshotId(), sourceRev),
                                        (String) row.get("payload_hash"));
                        if ("PROPOSED".equals(row.get("state"))) {
                            int slot = ((Number) row.get("slot")).intValue();
                            boolean proposer = number(row, "proposer_id") == member;
                            proposal =
                                    new Proposal(
                                            (UUID) row.get("report_key"),
                                            Long.toString(sourceRev),
                                            fixed,
                                            slot,
                                            "PROPOSED",
                                            !proposer,
                                            !proposer,
                                            proposer);
                        } else {
                            String jobHash =
                                    db.queryForObject(
                                            "SELECT report_hash FROM grade_job WHERE"
                                                + " game_report_id=?",
                                            String.class,
                                            id);
                            if (jobHash == null
                                    || !jobHash.equals(
                                            owner.frozen(room.snapshotId(), room.versionId())
                                                    .reportHash(GameReportService.complete(fixed))))
                                throw AuthException.unavailable("GAME_UNAVAILABLE");
                            pending =
                                    new Pending(
                                            (UUID) row.get("report_key"),
                                            ((Number) row.get("ordinal")).intValue(),
                                            fixed,
                                            ((Timestamp) row.get("accepted_at")).toInstant(),
                                            ((Timestamp) row.get("grade_deadline_at")).toInstant());
                        }
                    }
                    return new ReportView(
                            gameKey,
                            Long.toString(revision),
                            saved,
                            proposal,
                            pending,
                            pending != null ? "PENDING" : proposal != null ? "PROPOSED" : "NONE",
                            requestId);
                });
    }

    /**
     * 저장된 전체 REPORT-1을 비교하고 실제 변경에서만 활성 제안을 파기한다.
     *
     * @param token 현재 회원 접근 토큰
     * @param gameKey 공개 협동 방 UUID
     * @param expectedRev 현재 방 수정번호
     * @param expectedDraftRev 현재 초안 수정번호
     * @param report 정규화된 REPORT-1 전체 객체
     * @param requestKey 단회 요청 UUID
     * @param requestId 요청 추적 UUID
     * @return 확정 변경 또는 무변경 영수증
     * @throws AuthException 권위·기한·수정번호가 맞지 않을 때
     */
    public PublicGameService.Action editReport(
            String token,
            UUID gameKey,
            long expectedRev,
            long expectedDraftRev,
            JsonNode report,
            UUID requestKey,
            UUID requestId) {
        keys(gameKey, requestKey, expectedRev, expectedDraftRev);
        GameReportService.checkedDraft(report);
        String hash = SnapshotJson.hash(report);
        String intent =
                PublicGameService.digest(
                        "PUBLIC_REPORT_EDIT",
                        gameKey.toString(),
                        Long.toString(expectedRev),
                        Long.toString(expectedDraftRev),
                        hash);
        return owner.withReportRoom(
                token,
                gameKey,
                (room, member) -> {
                    coop(room);
                    owner.lockRequest(requestKey);
                    var prior = owner.receipt(requestKey);
                    if (prior != null)
                        return owner.reportReplay(
                                prior,
                                room,
                                member,
                                "PUBLIC_REPORT_EDIT",
                                intent,
                                requestKey,
                                requestId);
                    editable(room);
                    var saved = draft(room);
                    long revision = number(saved, "draft_rev");
                    if (room.rev() != expectedRev || revision != expectedDraftRev)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    boolean changed =
                            !hash.equals(saved.get("draft_hash"))
                                    && !(saved.get("draft_hash") == null
                                            && report.equals(GameReportService.empty()));
                    if (changed) {
                        byte[] cipher =
                                reports.encrypt(
                                        report,
                                        GameReportService.draftAad(room.id(), revision + 1));
                        if (db.update(
                                        "UPDATE game_room SET"
                                            + " draft_cipher=?,draft_hash=?,draft_rev=draft_rev+1,rev=rev+1,updated_at=clock_timestamp()"
                                            + " WHERE id=? AND rev=? AND draft_rev=? AND"
                                            + " state='RUNNING' AND deadline_at>clock_timestamp()",
                                        cipher,
                                        hash,
                                        room.id(),
                                        expectedRev,
                                        revision)
                                != 1) throw AuthException.conflict("EDIT_CONFLICT");
                        db.update(
                                "UPDATE game_report SET"
                                    + " state='INVALIDATED',payload_cipher=NULL,payload_hash=NULL,purged_at=clock_timestamp()"
                                    + " WHERE room_id=? AND state='PROPOSED'",
                                room.id());
                    }
                    return owner.reportAction(
                            room,
                            member,
                            requestKey,
                            requestId,
                            "PUBLIC_REPORT_EDIT",
                            intent,
                            changed);
                });
    }

    /**
     * 저장된 유효 REPORT-1을 단회 동결하되 횟수와 채점 작업은 소비하지 않는다.
     *
     * @param token 현재 회원 접근 토큰
     * @param gameKey 공개 협동 방 UUID
     * @param expectedRev 현재 방 수정번호
     * @param expectedDraftRev 현재 초안 수정번호
     * @param requestKey 단회 요청 UUID
     * @param requestId 요청 추적 UUID
     * @return 새 제안키를 포함하는 영수증
     * @throws AuthException 초안·원본·기한·수정번호가 무효일 때
     */
    public PublicGameService.Action propose(
            String token,
            UUID gameKey,
            long expectedRev,
            long expectedDraftRev,
            UUID requestKey,
            UUID requestId) {
        keys(gameKey, requestKey, expectedRev, expectedDraftRev);
        String intent =
                PublicGameService.digest(
                        "PUBLIC_REPORT_PROPOSE",
                        gameKey.toString(),
                        Long.toString(expectedRev),
                        Long.toString(expectedDraftRev));
        return owner.withReportRoom(
                token,
                gameKey,
                (room, member) -> {
                    coop(room);
                    owner.lockRequest(requestKey);
                    var prior = owner.receipt(requestKey);
                    if (prior != null)
                        return owner.reportReplay(
                                prior,
                                room,
                                member,
                                "PUBLIC_REPORT_PROPOSE",
                                intent,
                                requestKey,
                                requestId);
                    editable(room);
                    var saved = draft(room);
                    long revision = number(saved, "draft_rev");
                    if (room.rev() != expectedRev || revision != expectedDraftRev)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    if (saved.get("draft_cipher") == null)
                        throw AuthException.badRequest("INVALID_REPORT");
                    JsonNode fixed =
                            reports.decrypt(
                                    (byte[]) saved.get("draft_cipher"),
                                    GameReportService.draftAad(room.id(), revision),
                                    (String) saved.get("draft_hash"));
                    Report parsed = GameReportService.complete(fixed);
                    owner.checkedReportSource(room, parsed);
                    attempts(room);
                    if (!active(room).isEmpty()) throw AuthException.conflict("SUBMISSION_PENDING");
                    Long id =
                            db.queryForObject(
                                    "SELECT"
                                        + " nextval(pg_get_serial_sequence('public.game_report','id'))",
                                    Long.class);
                    if (id == null) throw AuthException.unavailable("GAME_UNAVAILABLE");
                    UUID reportKey = UUID.randomUUID();
                    if (db.update(
                                            "INSERT INTO"
                                                + " game_report(id,report_key,room_id,snapshot_id,source_rev,proposer_id,state,payload_cipher,payload_hash)"
                                                + " OVERRIDING SYSTEM VALUE VALUES"
                                                + " (?,?,?,?,?,?,'PROPOSED',?,?)",
                                            id,
                                            reportKey,
                                            room.id(),
                                            room.snapshotId(),
                                            revision,
                                            member,
                                            reports.encrypt(
                                                    fixed,
                                                    GameReportService.reportAad(
                                                            id,
                                                            room.id(),
                                                            room.snapshotId(),
                                                            revision)),
                                            SnapshotJson.hash(fixed))
                                    != 1
                            || db.update(
                                            "UPDATE game_room SET"
                                                + " rev=rev+1,updated_at=clock_timestamp() WHERE"
                                                + " id=? AND rev=?",
                                            room.id(),
                                            expectedRev)
                                    != 1) throw AuthException.conflict("EDIT_CONFLICT");
                    return owner.reportAction(
                            room,
                            member,
                            requestKey,
                            requestId,
                            "PUBLIC_REPORT_PROPOSE",
                            intent,
                            true,
                            reportKey,
                            null,
                            0,
                            null,
                            null);
                });
    }

    /**
     * 역할에 맞는 명시 응답만 처리하며 수락은 같은 원문을 실제 GAME 작업에 결속한다.
     *
     * @param token 현재 회원 접근 토큰
     * @param gameKey 공개 협동 방 UUID
     * @param reportKey 활성 고정 제안 UUID
     * @param expectedRev 현재 방 수정번호
     * @param decision ACCEPT, REJECT 또는 WITHDRAW
     * @param requestKey 단회 요청 UUID
     * @param requestId 요청 추적 UUID
     * @return 수락 접수 시각 또는 취소 결과가 고정된 영수증
     * @throws AuthException 제안 소유·접속·권위·원문 결속이 무효일 때
     */
    public PublicGameService.Action respond(
            String token,
            UUID gameKey,
            UUID reportKey,
            long expectedRev,
            String decision,
            UUID requestKey,
            UUID requestId) {
        keys(gameKey, requestKey, expectedRev, 0);
        PublicGameService.uuid(reportKey);
        if (!List.of("ACCEPT", "REJECT", "WITHDRAW").contains(decision))
            throw AuthException.badRequest("INVALID_REQUEST");
        String action = "PUBLIC_REPORT_" + decision;
        String intent =
                PublicGameService.digest(
                        action,
                        gameKey.toString(),
                        reportKey.toString(),
                        Long.toString(expectedRev),
                        decision);
        return owner.withReportRoom(
                token,
                gameKey,
                (room, member) -> {
                    coop(room);
                    owner.lockRequest(requestKey);
                    var prior = owner.receipt(requestKey);
                    if (prior != null)
                        return owner.reportReplay(
                                prior, room, member, action, intent, requestKey, requestId);
                    editable(room);
                    if (room.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    var rows =
                            db.queryForList(
                                    "SELECT"
                                        + " r.id,r.report_key,r.proposer_id,r.source_rev,r.payload_cipher,r.payload_hash,r.state"
                                        + " FROM game_report r WHERE r.room_id=? AND r.report_key=?"
                                        + " FOR UPDATE",
                                    room.id(),
                                    reportKey);
                    if (rows.size() != 1 || !"PROPOSED".equals(rows.getFirst().get("state")))
                        throw AuthException.conflict("STATE_CONFLICT");
                    var proposal = rows.getFirst();
                    boolean proposer = number(proposal, "proposer_id") == member;
                    if (proposer != "WITHDRAW".equals(decision))
                        throw AuthException.forbidden("FORBIDDEN");
                    long revision = number(draft(room), "draft_rev");
                    if (number(proposal, "source_rev") != revision)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    if (!"ACCEPT".equals(decision)) {
                        if (db.update(
                                                "UPDATE game_report SET"
                                                    + " state=?,payload_cipher=NULL,payload_hash=NULL,purged_at=clock_timestamp()"
                                                    + " WHERE id=? AND state='PROPOSED'",
                                                decision.equals("REJECT")
                                                        ? "REJECTED"
                                                        : "WITHDRAWN",
                                                number(proposal, "id"))
                                        != 1
                                || db.update(
                                                "UPDATE game_room SET"
                                                    + " rev=rev+1,updated_at=clock_timestamp()"
                                                    + " WHERE id=? AND rev=?",
                                                room.id(),
                                                expectedRev)
                                        != 1) throw AuthException.conflict("EDIT_CONFLICT");
                        return owner.reportAction(
                                room, member, requestKey, requestId, action, intent, true);
                    }
                    present(room);
                    attempts(room);
                    var saved = draft(room);
                    JsonNode fixed =
                            reports.decrypt(
                                    (byte[]) proposal.get("payload_cipher"),
                                    GameReportService.reportAad(
                                            number(proposal, "id"),
                                            room.id(),
                                            room.snapshotId(),
                                            revision),
                                    (String) proposal.get("payload_hash"));
                    if (!SnapshotJson.hash(fixed).equals(saved.get("draft_hash")))
                        throw AuthException.conflict("EDIT_CONFLICT");
                    JsonNode current =
                            reports.decrypt(
                                    (byte[]) saved.get("draft_cipher"),
                                    GameReportService.draftAad(room.id(), revision),
                                    (String) saved.get("draft_hash"));
                    if (!fixed.equals(current)) throw AuthException.conflict("EDIT_CONFLICT");
                    Report parsed = GameReportService.complete(fixed);
                    var frozen = owner.checkedReportSource(room, parsed);
                    int ordinal = room.gradedCount() + 1;
                    var accepted =
                            reports.acceptProposal(
                                    number(proposal, "id"),
                                    reportKey,
                                    room.id(),
                                    room.snapshotId(),
                                    room.runtimeId(),
                                    member,
                                    expectedRev,
                                    revision,
                                    ordinal,
                                    parsed,
                                    frozen,
                                    (String) saved.get("config_hash"));
                    return owner.reportAction(
                            room,
                            member,
                            requestKey,
                            requestId,
                            action,
                            intent,
                            true,
                            reportKey,
                            accepted.jobKey(),
                            ordinal,
                            accepted.acceptedAt(),
                            accepted.deadlineAt());
                });
    }

    private void present(PublicGameService.Room room) {
        Instant now = owner.dbNow();
        var people =
                db.queryForList(
                        "SELECT m.member_id,m.active_yn,m.version_id,m.role_code,m.policy_id,"
                            + "m.identity_id,m.last_seen_at,m.presence_auth_rev,a.auth_rev,a.state,s.identity_id"
                            + " AS session_identity,s.auth_rev AS"
                            + " session_auth_rev,s.revoked_at,s.idle_until,s.absolute_until,i.provider,i.realm,i.proof_at,i.active_yn"
                            + " AS identity_active FROM game_member m JOIN member_account a ON"
                            + " a.id=m.member_id JOIN member_identity i ON i.id=m.identity_id LEFT"
                            + " JOIN member_session s ON s.id=m.presence_session_id WHERE"
                            + " m.room_id=? AND m.role_code IS NOT NULL",
                        room.id());
        if (people.size() != 2
                || number(people.get(0), "member_id") == number(people.get(1), "member_id"))
            throw AuthException.conflict("PARTNER_OFFLINE");
        for (var p : people) {
            if (!Boolean.TRUE.equals(p.get("active_yn"))
                    || !"ACTIVE".equals(p.get("state"))
                    || number(p, "version_id") != room.versionId()
                    || number(p, "policy_id") != room.policyId()
                    || !Boolean.TRUE.equals(p.get("identity_active"))
                    || !"LOCAL".equals(p.get("provider"))
                    || !"LOCAL".equals(p.get("realm"))
                    || p.get("proof_at") == null
                    || p.get("session_identity") == null
                    || number(p, "identity_id") != number(p, "session_identity")
                    || p.get("presence_auth_rev") == null
                    || p.get("session_auth_rev") == null
                    || number(p, "presence_auth_rev") != number(p, "auth_rev")
                    || number(p, "session_auth_rev") != number(p, "auth_rev")
                    || p.get("revoked_at") != null
                    || !recent(p.get("last_seen_at"), now)
                    || !fresh(p.get("idle_until"), now, 0)
                    || !fresh(p.get("absolute_until"), now, 0))
                throw AuthException.conflict("PARTNER_OFFLINE");
        }
    }

    private static boolean fresh(Object value, Instant now, int seconds) {
        return value instanceof Timestamp t && t.toInstant().plusSeconds(seconds).isAfter(now);
    }

    private static boolean recent(Object value, Instant now) {
        return value instanceof Timestamp t
                && !t.toInstant().isAfter(now)
                && t.toInstant().plusSeconds(30).isAfter(now);
    }

    private void attempts(PublicGameService.Room room) {
        if (room.gradedCount() < 0
                || room.gradedCount() >= room.maxAttempts()
                || room.gradedCount() >= 5) throw AuthException.conflict("ATTEMPTS_EXHAUSTED");
    }

    private void editable(PublicGameService.Room room) {
        running(room);
        if (!room.deadlineAt().isAfter(owner.dbNow()))
            throw AuthException.conflict("PLAY_DEADLINE_EXPIRED");
        if (db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM game_report WHERE room_id=? AND state='ACCEPTED')",
                Boolean.class,
                room.id())) throw AuthException.conflict("REPORT_PENDING");
    }

    private static void running(PublicGameService.Room room) {
        coop(room);
        if (!"RUNNING".equals(room.state())) throw AuthException.conflict("STATE_CONFLICT");
    }

    private static void coop(PublicGameService.Room room) {
        if (!"COOP".equals(room.mode())) throw AuthException.conflict("STATE_CONFLICT");
    }

    private Map<String, Object> draft(PublicGameService.Room room) {
        return db.queryForMap(
                "SELECT draft_rev,draft_cipher,draft_hash,config_hash FROM game_room WHERE id=?",
                room.id());
    }

    private List<Map<String, Object>> active(PublicGameService.Room room) {
        return db.queryForList(
                "SELECT r.id,r.report_key,r.proposer_id,r.source_rev,r.state,r.payload_cipher,"
                    + "r.payload_hash,r.accepted_at,r.grade_deadline_at,r.ordinal,m.slot FROM"
                    + " game_report r JOIN game_member m ON m.room_id=r.room_id AND"
                    + " m.member_id=r.proposer_id WHERE r.room_id=? AND r.state IN"
                    + " ('PROPOSED','ACCEPTED') AND r.purged_at IS NULL",
                room.id());
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static void keys(
            UUID gameKey, UUID requestKey, long expectedRev, long expectedDraftRev) {
        PublicGameService.uuid(gameKey);
        PublicGameService.uuid(requestKey);
        if (expectedRev < 0
                || expectedDraftRev < 0
                || expectedRev == Long.MAX_VALUE
                || expectedDraftRev == Long.MAX_VALUE)
            throw AuthException.badRequest("INVALID_REQUEST");
    }
}
