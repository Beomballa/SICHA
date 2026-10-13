package com.reasoning.common.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.model.GradeModels.Report;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.GradeResultValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.util.CommonUtil;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 소유 서비스가 권위·방 잠금을 검증한 거래에서만 호출하는 보고서 원문 및 작업 저장소. */
final class GameReportService {
    private final JdbcTemplate db;
    private final CryptoService crypto;

    GameReportService(JdbcTemplate db, CryptoService crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    record Accepted(
            UUID reportKey, UUID jobKey, int ordinal, Instant acceptedAt, Instant deadlineAt) {}

    static JsonNode empty() {
        return JsonNodeFactory.instance
                .objectNode()
                .putNull("culpritCode")
                .put("method", "")
                .put("time", "")
                .put("motive", "")
                .put("evidence", "");
    }

    static byte[] checkedDraft(JsonNode report) {
        if (report == null
                || !report.isObject()
                || report.size() != 5
                || !report.has("culpritCode")) throw AuthException.badRequest("INVALID_REPORT");
        JsonNode culprit = report.get("culpritCode");
        if (!culprit.isNull()
                && (!culprit.isTextual() || !culprit.textValue().matches("[A-Z0-9_]{1,32}")))
            throw AuthException.badRequest("INVALID_REPORT");
        int total = 0;
        for (String field : List.of("method", "time", "motive", "evidence")) {
            JsonNode node = report.get(field);
            if (node == null || !node.isTextual()) throw AuthException.badRequest("INVALID_REPORT");
            String text = node.textValue();
            try {
                if (!text.equals(CommonUtil.normalizeText(text, 5000, true)))
                    throw AuthException.badRequest("INVALID_REPORT");
            } catch (IllegalArgumentException failure) {
                throw AuthException.badRequest("INVALID_REPORT");
            }
            total += text.codePointCount(0, text.length());
        }
        if (total > 20000) throw AuthException.badRequest("INVALID_REPORT");
        try {
            byte[] bytes = SnapshotJson.encode(report);
            if (bytes.length > 131072)
                throw new AuthException(413, "PAYLOAD_TOO_LARGE", "PAYLOAD_TOO_LARGE");
            return bytes;
        } catch (IllegalArgumentException failure) {
            throw AuthException.badRequest("INVALID_REPORT");
        }
    }

    static Report complete(JsonNode report) {
        checkedDraft(report);
        try {
            return new GradeResultValidator()
                    .parseReport(new String(SnapshotJson.encode(report), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw AuthException.badRequest("INVALID_REPORT");
        }
    }

    JsonNode decrypt(byte[] cipher, String aad, String hash) {
        try {
            JsonNode report =
                    SnapshotJson.parse(
                            crypto.decrypt(new String(cipher, StandardCharsets.UTF_8), aad)
                                    .getBytes(StandardCharsets.UTF_8));
            if (!SnapshotJson.hash(report).equals(hash))
                throw AuthException.unavailable("GAME_UNAVAILABLE");
            checkedDraft(report);
            return report;
        } catch (RuntimeException failure) {
            throw AuthException.unavailable("GAME_UNAVAILABLE");
        }
    }

    byte[] encrypt(JsonNode report, String aad) {
        return crypto.encrypt(new String(SnapshotJson.encode(report), StandardCharsets.UTF_8), aad)
                .getBytes(StandardCharsets.UTF_8);
    }

    static String draftAad(long roomId, long revision) {
        return "game_room/" + roomId + "/draft/" + revision + "/v1";
    }

    static String reportAad(long reportId, long roomId, long snapshotId, long revision) {
        return "game_report/"
                + reportId
                + "/room/"
                + roomId
                + "/snapshot/"
                + snapshotId
                + "/draft/"
                + revision
                + "/payload/v1";
    }

    /** 원문 해시와 동결 판정 해시를 분리하여 동일 GAME worker 작업을 생성한다. */
    Accepted accept(
            long roomId,
            long snapshotId,
            long runtimeId,
            long memberId,
            long expectedRev,
            long draftRev,
            int ordinal,
            JsonNode fixed,
            Report parsed,
            FrozenSnapshotCodec.FrozenSnapshot frozen,
            String configHash) {
        Long reportId =
                db.queryForObject(
                        "SELECT nextval(pg_get_serial_sequence('public.game_report','id'))",
                        Long.class);
        if (reportId == null) throw AuthException.unavailable("GAME_UNAVAILABLE");
        UUID reportKey = UUID.randomUUID();
        byte[] cipher = encrypt(fixed, reportAad(reportId, roomId, snapshotId, draftRev));
        String hash = SnapshotJson.hash(fixed);
        if (db.update(
                        "INSERT INTO"
                            + " game_report(id,report_key,room_id,snapshot_id,source_rev,proposer_id,accepted_by,state,payload_cipher,payload_hash,accepted_at,grade_deadline_at,ordinal)"
                            + " OVERRIDING SYSTEM VALUE SELECT"
                            + " ?,?,id,snapshot_id,draft_rev,?,?,'ACCEPTED',?,?,c.t,c.t+interval"
                            + " '120 seconds',? FROM game_room CROSS JOIN (SELECT clock_timestamp()"
                            + " AS t) c WHERE id=? AND rev=? AND draft_rev=? AND state='RUNNING'"
                            + " AND deadline_at>c.t",
                        reportId,
                        reportKey,
                        memberId,
                        memberId,
                        cipher,
                        hash,
                        ordinal,
                        roomId,
                        expectedRev,
                        draftRev)
                != 1) throw AuthException.conflict("STATE_CONFLICT");
        var pin =
                db.queryForMap(
                        "SELECT accepted_at,grade_deadline_at FROM game_report WHERE id=?",
                        reportId);
        UUID jobKey = UUID.randomUUID();
        Long jobId =
                db.queryForObject(
                        "INSERT INTO"
                            + " grade_job(job_key,snapshot_id,runtime_id,game_report_id,report_hash,state,accepted_at,deadline_at,next_run_at,input_hash,config_hash,rubric_hash)"
                            + " VALUES (?,?,?, ?,?,'QUEUED',?,?,?,NULL,?,?) RETURNING id",
                        Long.class,
                        jobKey,
                        snapshotId,
                        runtimeId,
                        reportId,
                        frozen.reportHash(parsed),
                        pin.get("accepted_at"),
                        pin.get("grade_deadline_at"),
                        pin.get("accepted_at"),
                        configHash,
                        frozen.rubricHash());
        if (jobId == null
                || db.update(
                                "UPDATE game_room SET rev=rev+1,updated_at=clock_timestamp() WHERE"
                                        + " id=? AND rev=? AND draft_rev=? AND state='RUNNING'",
                                roomId,
                                expectedRev,
                                draftRev)
                        != 1) throw AuthException.conflict("EDIT_CONFLICT");
        return new Accepted(
                reportKey,
                jobKey,
                ordinal,
                ((Timestamp) pin.get("accepted_at")).toInstant(),
                ((Timestamp) pin.get("grade_deadline_at")).toInstant());
    }

    /**
     * 소유자가 검증한 동결 제안을 재암호화하지 않고 상대의 명시 동의와 GAME 작업을 결속한다.
     *
     * @param reportId 기존 PROPOSED 행 식별자
     * @param reportKey 기존 공개 제안키
     * @param roomId 잠긴 공개 방 식별자
     * @param snapshotId 방의 고정 사본 식별자
     * @param runtimeId 방의 고정 실행 설정 식별자
     * @param accepterId 제안자와 다른 실제 참가자 식별자
     * @param expectedRev 방의 동의 전 수정번호
     * @param draftRev 제안과 일치하는 저장 초안 수정번호
     * @param ordinal 미사용 제출 순번
     * @param parsed 검증된 고정 보고서
     * @param frozen 검증된 고정 판정 사본
     * @param configHash 방의 고정 실행 설정 해시
     * @return DB 접수 시각과 실제 작업키
     * @throws AuthException 고정 제안·방 수정번호 또는 기한이 바뀌었을 때
     */
    Accepted acceptProposal(
            long reportId,
            UUID reportKey,
            long roomId,
            long snapshotId,
            long runtimeId,
            long accepterId,
            long expectedRev,
            long draftRev,
            int ordinal,
            Report parsed,
            FrozenSnapshotCodec.FrozenSnapshot frozen,
            String configHash) {
        if (db.update(
                        "UPDATE game_report SET state='ACCEPTED',accepted_by=?,ordinal=?,"
                            + " accepted_at=c.t,grade_deadline_at=c.t+interval '120 seconds' FROM"
                            + " game_room r CROSS JOIN (SELECT clock_timestamp() AS t) c WHERE"
                            + " game_report.id=? AND game_report.room_id=r.id AND r.id=? AND"
                            + " r.rev=? AND r.draft_rev=? AND r.state='RUNNING' AND"
                            + " r.deadline_at>c.t AND game_report.state='PROPOSED' AND"
                            + " game_report.source_rev=? AND game_report.payload_cipher IS NOT NULL"
                            + " AND game_report.payload_hash IS NOT NULL AND"
                            + " game_report.proposer_id<>?",
                        accepterId,
                        ordinal,
                        reportId,
                        roomId,
                        expectedRev,
                        draftRev,
                        draftRev,
                        accepterId)
                != 1) throw AuthException.conflict("STATE_CONFLICT");
        var pin =
                db.queryForMap(
                        "SELECT accepted_at,grade_deadline_at FROM game_report WHERE id=?",
                        reportId);
        UUID jobKey = UUID.randomUUID();
        Long jobId =
                db.queryForObject(
                        "INSERT INTO"
                            + " grade_job(job_key,snapshot_id,runtime_id,game_report_id,report_hash,"
                            + "state,accepted_at,deadline_at,next_run_at,input_hash,config_hash,rubric_hash)"
                            + " VALUES (?,?,?,?,?,'QUEUED',?,?,?,NULL,?,?) RETURNING id",
                        Long.class,
                        jobKey,
                        snapshotId,
                        runtimeId,
                        reportId,
                        frozen.reportHash(parsed),
                        pin.get("accepted_at"),
                        pin.get("grade_deadline_at"),
                        pin.get("accepted_at"),
                        configHash,
                        frozen.rubricHash());
        if (jobId == null
                || db.update(
                                "UPDATE game_room SET rev=rev+1,updated_at=clock_timestamp() WHERE"
                                    + " id=? AND rev=? AND draft_rev=? AND state='RUNNING'",
                                roomId,
                                expectedRev,
                                draftRev)
                        != 1) throw AuthException.conflict("EDIT_CONFLICT");
        return new Accepted(
                reportKey,
                jobKey,
                ordinal,
                ((Timestamp) pin.get("accepted_at")).toInstant(),
                ((Timestamp) pin.get("grade_deadline_at")).toInstant());
    }
}
