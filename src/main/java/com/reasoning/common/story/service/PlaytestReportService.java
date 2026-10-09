package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.controller.GradeWorkerController.Assembly;
import com.reasoning.common.grading.model.GradeModels.Category;
import com.reasoning.common.grading.model.GradeModels.Report;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.GradeResultValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.util.CommonUtil;

import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 인증된 두 참가자의 공동 원문·제안·종료 피드백을 접근 경계의 단일 거래 안에서 처리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PlaytestReportService {
    private final PlaytestAccess access;
    private final JdbcTemplate db;
    private final CryptoService crypto;
    private final ObjectProvider<Assembly> assemblies;
    private final GradeResultValidator validator = new GradeResultValidator();

    /**
     * 명시 조립된 TEST 소비자만 접수 가능하게 연결한다.
     *
     * @param access 참가자·정책 거래 경계, null 불가
     * @param db 동일 저장소의 JDBC 도구, null 불가
     * @param crypto 보고서 암호화 경계, null 불가
     * @param assemblies 호출자가 별도로 등록한 worker 조립 제공자, 빈 제공자 허용
     */
    public PlaytestReportService(
            PlaytestAccess access,
            JdbcTemplate db,
            CryptoService crypto,
            ObjectProvider<Assembly> assemblies) {
        this.access = access;
        this.db = db;
        this.crypto = crypto;
        this.assemblies = assemblies;
    }

    public record Proposal(
            UUID reportKey,
            String sourceRev,
            int proposerSlot,
            String state,
            boolean canAccept,
            boolean canReject,
            boolean canWithdraw) {}

    public record ReportView(
            UUID testKey,
            String draftRev,
            JsonNode report,
            Proposal proposal,
            String submissionState,
            UUID requestId) {}

    public record ResultView(
            UUID testKey,
            String state,
            String outcome,
            Instant endedAt,
            Instant feedbackUntil,
            String resultPhase,
            boolean feedbackSubmitted,
            Integer finalScore,
            Object scoreSummary,
            Object reports,
            String revealText,
            UUID requestId) {}

    public record CategoryScore(String category, int score, int maxScore) {}

    public record ScoreSummary(
            int baseScore,
            int wrongCount,
            int penalty,
            int finalScore,
            List<CategoryScore> categories) {}

    public record GradedReport(
            UUID reportKey, int submitNo, JsonNode report, int baseScore, boolean success) {}

    private record Row(
            long revision,
            long draftRev,
            byte[] cipher,
            String hash,
            Instant deadline,
            String state,
            String outcome,
            Instant endedAt,
            Instant until) {}

    private record Pending(
            long id,
            UUID key,
            long sourceRev,
            long proposer,
            String state,
            String hash,
            byte[] cipher) {}

    private record Receipt(
            long actor, String action, String scope, String hash, JsonNode original) {}

    private record Outcome<T>(T value, AuthException denial) {
        T unwrap() {
            if (denial != null) throw denial;
            return value;
        }
    }

    /** 참가자 본인에게 현재 공동 초안과 활성 제안만 반환한다. */
    public ReportView report(String token, UUID key, UUID requestId) {
        return access.execute(
                        token,
                        key,
                        c -> {
                            AuthException denial = normalize(c, requestId);
                            if (denial != null) return new Outcome<ReportView>(null, denial);
                            Row row = row(c);
                            if (!"RUNNING".equals(row.state()) || row.deadline() == null)
                                throw conflict("STATE_CONFLICT");
                            JsonNode accepted = acceptedReport(c, row);
                            if (accepted != null) {
                                audit(c, "REPORT_READ", requestId, "READ");
                                return new Outcome<>(
                                        new ReportView(
                                                key,
                                                Long.toString(row.draftRev()),
                                                accepted,
                                                null,
                                                "PENDING",
                                                requestId),
                                        null);
                            }
                            if (!c.clock(db).isBefore(row.deadline()))
                                throw conflict("STATE_CONFLICT");
                            Pending pending = pending(c.id());
                            JsonNode draft =
                                    row.cipher() == null
                                            ? empty()
                                            : decrypt(
                                                    row.cipher(),
                                                    draftAad(c, row.draftRev()),
                                                    row.hash());
                            Proposal proposal =
                                    pending == null
                                            ? null
                                            : new Proposal(
                                                    pending.key(),
                                                    Long.toString(pending.sourceRev()),
                                                    c.self().id() == pending.proposer()
                                                            ? c.self().slot()
                                                            : c.partner().slot(),
                                                    pending.state(),
                                                    c.self().id() != pending.proposer()
                                                            && supportsTest(),
                                                    c.self().id() != pending.proposer(),
                                                    c.self().id() == pending.proposer());
                            audit(c, "REPORT_READ", requestId, "READ");
                            return new Outcome<>(
                                    new ReportView(
                                            key,
                                            Long.toString(row.draftRev()),
                                            draft,
                                            proposal,
                                            pending == null ? "NONE" : pending.state(),
                                            requestId),
                                    null);
                        })
                .unwrap();
    }

    /** 접수 중에는 변경 가능한 초안 대신 원래 접수된 암호화 제안만 검증해 반환한다. */
    private JsonNode acceptedReport(PlaytestAccess.Context c, Row row) {
        var accepted =
                db.queryForList(
                        """
                        SELECT r.id,r.source_draft_rev,r.payload_hash,r.payload_cipher,r.snapshot_id,
                               r.runtime_id,r.config_hash,r.runtime_epoch,r.accepted_at,
                               j.report_hash,j.rubric_hash,j.accepted_at AS job_accepted,
                               j.deadline_at AS grade_deadline,j.state AS job_state,
                               t.runtime_id AS test_runtime,t.config_hash AS test_config,
                               t.runtime_epoch AS test_epoch
                        FROM test_report r JOIN grade_job j ON j.report_id=r.id
                            JOIN play_test t ON t.id=r.test_id
                        WHERE r.test_id=? AND r.state='ACCEPTED' AND r.purged_at IS NULL
                            AND j.batch_id IS NULL AND j.sample_code IS NULL
                            AND j.repeat_no IS NULL AND j.input_hash IS NULL
                        """,
                        c.id());
        if (accepted.isEmpty()) {
            if (pendingAccepted(c.id())) throw unavailable();
            return null;
        }
        if (accepted.size() != 1) throw unavailable();
        var stored = accepted.getFirst();
        long reportId = ((Number) stored.get("id")).longValue();
        long sourceRev = ((Number) stored.get("source_draft_rev")).longValue();
        Timestamp acceptedAt = (Timestamp) stored.get("accepted_at");
        Timestamp jobAcceptedAt = (Timestamp) stored.get("job_accepted");
        Timestamp gradeDeadline = (Timestamp) stored.get("grade_deadline");
        if (sourceRev != row.draftRev()
                || stored.get("payload_cipher") == null
                || !java.util.Objects.equals(row.hash(), stored.get("payload_hash"))
                || ((Number) stored.get("snapshot_id")).longValue() != c.snapshotId()
                || ((Number) stored.get("runtime_id")).longValue()
                        != ((Number) stored.get("test_runtime")).longValue()
                || ((Number) stored.get("runtime_epoch")).longValue()
                        != ((Number) stored.get("test_epoch")).longValue()
                || !java.util.Objects.equals(stored.get("config_hash"), stored.get("test_config"))
                || acceptedAt == null
                || !acceptedAt.equals(jobAcceptedAt)
                || gradeDeadline == null
                || !gradeDeadline.toInstant().equals(acceptedAt.toInstant().plusSeconds(120))
                || !Set.of("QUEUED", "RUNNING").contains(stored.get("job_state")))
            throw unavailable();
        JsonNode fixed =
                decrypt(
                        (byte[]) stored.get("payload_cipher"),
                        reportAad(reportId, c.id(), c.snapshotId(), sourceRev),
                        (String) stored.get("payload_hash"));
        var frozen = FrozenSnapshotCodec.decode(SnapshotJson.encode(c.payload()));
        if (!frozen.rubricHash().equals(stored.get("rubric_hash"))
                || !frozen.reportHash(complete(fixed)).equals(stored.get("report_hash")))
            throw unavailable();
        return fixed;
    }

    /** 실변경에만 수정번호를 올리고 이전 제안을 무효화한다. */
    public PlaytestInvitationService.ActionResult edit(
            String token,
            UUID key,
            long rev,
            long draftRev,
            JsonNode report,
            UUID requestKey,
            UUID requestId) {
        keys(requestKey, requestId);
        if (rev < 0 || draftRev < 0 || rev == Long.MAX_VALUE || draftRev == Long.MAX_VALUE)
            throw invalid();
        if (report != null) {
            try {
                if (SnapshotJson.encode(report).length > 131072) throw tooLarge();
            } catch (IllegalArgumentException failure) {
                throw invalidReport();
            }
        }
        JsonNode normalized = draft(report);
        byte[] bytes = SnapshotJson.encode(normalized);
        if (bytes.length > 131072) throw tooLarge();
        String contentHash = SnapshotJson.hash(normalized);
        String intent =
                SnapshotJson.hash(
                        JsonNodeFactory.instance
                                .objectNode()
                                .put("testKey", key.toString())
                                .put("rev", rev)
                                .put("draftRev", draftRev)
                                .put("hash", contentHash));
        return command(
                token,
                key,
                "REPORT_EDIT",
                intent,
                requestKey,
                requestId,
                (c, row) -> {
                    if (row.revision() != rev || row.draftRev() != draftRev)
                        throw conflict("EDIT_CONFLICT");
                    if (pendingAccepted(c.id())) throw conflict("STATE_CONFLICT");
                    boolean changed = !contentHash.equals(row.hash());
                    if (changed) {
                        byte[] encrypted = encrypt(bytes, draftAad(c, draftRev + 1));
                        if (db.update(
                                        "UPDATE play_test SET"
                                            + " draft_cipher=?,draft_hash=?,draft_rev=draft_rev+1,rev=rev+1,updated_at=clock_timestamp()"
                                            + " WHERE id=? AND rev=? AND draft_rev=? AND"
                                            + " state='RUNNING'",
                                        encrypted,
                                        contentHash,
                                        c.id(),
                                        rev,
                                        draftRev)
                                != 1) throw conflict("EDIT_CONFLICT");
                        db.update(
                                "UPDATE test_report SET"
                                        + " state='INVALIDATED',updated_at=clock_timestamp() WHERE"
                                        + " test_id=? AND state='PROPOSED'",
                                c.id());
                    }
                    return changed;
                });
    }

    /** 완성 REPORT-1을 고정하되 아직 채점 제출은 하지 않는다. */
    public PlaytestInvitationService.ActionResult propose(
            String token, UUID key, long rev, long draftRev, UUID requestKey, UUID requestId) {
        keys(requestKey, requestId);
        if (rev < 0 || draftRev < 0) throw invalid();
        String intent = digest(key + ":" + rev + ":" + draftRev);
        return command(
                token,
                key,
                "REPORT_PROPOSE",
                intent,
                requestKey,
                requestId,
                (c, row) -> {
                    if (row.revision() != rev || row.draftRev() != draftRev)
                        throw conflict("EDIT_CONFLICT");
                    if (pending(c.id()) != null || pendingAccepted(c.id()))
                        throw conflict("STATE_CONFLICT");
                    if (row.cipher() == null) throw invalidReport();
                    JsonNode draft = decrypt(row.cipher(), draftAad(c, row.draftRev()), row.hash());
                    complete(draft);
                    boolean registered = false;
                    for (JsonNode person : c.payload().path("resources").path("persons")) {
                        if (draft.get("culpritCode")
                                .textValue()
                                .equals(person.path("code").asText())) registered = true;
                    }
                    if (!registered) throw invalidReport();
                    byte[] bytes = SnapshotJson.encode(draft);
                    if (bytes.length > 131072) throw tooLarge();
                    UUID reportKey = UUID.randomUUID();
                    // 저장된 payload_hash는 H(P)이며 채점 job의 report_hash는 사본·채점표에 결속한 별도 해시다.
                    String hash = SnapshotJson.hash(draft);
                    Long reportId =
                            db.queryForObject(
                                    "SELECT"
                                        + " nextval(pg_get_serial_sequence('public.test_report','id'))",
                                    Long.class);
                    if (reportId == null) throw unavailable();
                    if (db.update(
                                    "INSERT INTO"
                                        + " test_report(id,report_key,test_id,snapshot_id,runtime_id,config_hash,runtime_epoch,source_draft_rev,proposer_id,payload_cipher,payload_hash)"
                                        + " OVERRIDING SYSTEM VALUE SELECT"
                                        + " ?,?,id,snapshot_id,runtime_id,config_hash,runtime_epoch,draft_rev,?,?,?"
                                        + " FROM play_test WHERE id=? AND rev=? AND draft_rev=? AND"
                                        + " state='RUNNING' AND draft_hash=?",
                                    reportId,
                                    reportKey,
                                    c.self().id(),
                                    encrypt(
                                            bytes,
                                            reportAad(reportId, c.id(), c.snapshotId(), draftRev)),
                                    hash,
                                    c.id(),
                                    rev,
                                    draftRev,
                                    hash)
                            != 1) throw conflict("EDIT_CONFLICT");
                    if (db.update(
                                    "UPDATE play_test SET rev=rev+1,updated_at=clock_timestamp()"
                                            + " WHERE id=? AND rev=? AND state='RUNNING'",
                                    c.id(),
                                    rev)
                            != 1) throw conflict("EDIT_CONFLICT");
                    return true;
                });
    }

    /** 상대의 동의는 명시 TEST 소비자가 존재할 때만 접수하며 거절·철회는 유지한다. */
    public PlaytestInvitationService.ActionResult respond(
            String token,
            UUID key,
            UUID reportKey,
            long rev,
            String decision,
            UUID requestKey,
            UUID requestId) {
        keys(requestKey, requestId);
        if (rev < 0
                || reportKey == null
                || !Set.of("ACCEPT", "REJECT", "WITHDRAW").contains(decision)) throw invalid();
        String action = "REPORT_" + decision;
        String intent = digest(key + ":" + reportKey + ":" + rev + ":" + decision);
        return command(
                token,
                key,
                action,
                intent,
                requestKey,
                requestId,
                (c, row) -> {
                    if (row.revision() != rev) throw conflict("EDIT_CONFLICT");
                    Pending pending = pending(c.id());
                    if ("ACCEPT".equals(decision)) {
                        if (pending == null
                                || !reportKey.equals(pending.key())
                                || pending.proposer() == c.self().id())
                            throw conflict("STATE_CONFLICT");
                        if (!supportsTest()) throw unavailable();
                        return acceptProposal(c, row, pending, rev);
                    }
                    if (pending == null
                            || !reportKey.equals(pending.key())
                            || ("WITHDRAW".equals(decision)
                                    != (pending.proposer() == c.self().id())))
                        throw conflict("STATE_CONFLICT");
                    if (db.update(
                                    "UPDATE test_report SET state=?,updated_at=clock_timestamp()"
                                            + " WHERE id=? AND state='PROPOSED'",
                                    "REJECT".equals(decision) ? "REJECTED" : "WITHDRAWN",
                                    pending.id())
                            != 1) throw unavailable();
                    if (db.update(
                                    "UPDATE play_test SET rev=rev+1,updated_at=clock_timestamp()"
                                            + " WHERE id=? AND rev=? AND state='RUNNING'",
                                    c.id(),
                                    rev)
                            != 1) throw conflict("EDIT_CONFLICT");
                    return true;
                });
    }

    /**
     * @return 명시된 조립이 정확히 하나이며 TEST 소비·복구를 모두 소유할 때만 참
     */
    private boolean supportsTest() {
        try {
            var candidates = assemblies.stream().limit(2).toList();
            return candidates.size() == 1
                    && candidates.getFirst().supportsTest()
                    && candidates.getFirst().testRecovery() != null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** 실제 소비자가 연결된 뒤에만 호출할 접수 거래: 제안의 두 번째 동의와 QUEUED 작업을 원자적으로 고정한다. */
    private boolean acceptProposal(PlaytestAccess.Context c, Row row, Pending proposal, long rev) {
        if (proposal == null
                || proposal.proposer() == c.self().id()
                || row.revision() != rev
                || row.draftRev() != proposal.sourceRev()
                || row.cipher() == null
                || !java.util.Objects.equals(row.hash(), proposal.hash())
                || pendingAccepted(c.id())) throw conflict("STATE_CONFLICT");
        Instant accepted = c.clock(db);
        if (row.deadline() == null
                || !accepted.isBefore(row.deadline())
                || !recent(c.self().seen(), accepted)
                || !recent(c.partner().seen(), accepted)
                || !partnerSessionActive(c, accepted)) throw conflict("PARTNER_NOT_READY");
        JsonNode draft = decrypt(row.cipher(), draftAad(c, row.draftRev()), row.hash());
        JsonNode fixed =
                decrypt(
                        proposal.cipher(),
                        reportAad(proposal.id(), c.id(), c.snapshotId(), proposal.sourceRev()),
                        proposal.hash());
        if (!draft.equals(fixed)) throw conflict("STATE_CONFLICT");
        Report report = complete(fixed);
        var frozen = FrozenSnapshotCodec.decode(SnapshotJson.encode(c.payload()));
        String reportHash = frozen.reportHash(report);
        var test =
                db.queryForMap(
                        """
                        SELECT t.next_submit_no,t.attempt_count,t.snapshot_id,t.runtime_id,t.config_hash,
                               t.runtime_epoch,r.code,r.epoch,r.state
                        FROM play_test t JOIN grade_runtime r ON r.id=t.runtime_id WHERE t.id=?
                        """,
                        c.id());
        int ordinal = ((Number) test.get("next_submit_no")).intValue();
        int attempted = ((Number) test.get("attempt_count")).intValue();
        int limit = requiredInt(c.payload().path("policy").path("attemptLimit"));
        if (ordinal < 1
                || ordinal == Integer.MAX_VALUE
                || attempted < 0
                || limit < 1
                || attempted >= limit
                || ((Number) test.get("snapshot_id")).longValue() != c.snapshotId()
                || !"AVAILABLE".equals(test.get("state"))
                || ((Number) test.get("epoch")).longValue()
                        != ((Number) test.get("runtime_epoch")).longValue()
                || !java.util.Objects.equals(
                        test.get("config_hash"),
                        db.queryForObject(
                                "SELECT config_hash FROM grade_runtime WHERE id=?",
                                String.class,
                                test.get("runtime_id")))) throw conflict("RUNTIME_UNAVAILABLE");
        if (db.update(
                        """
                        UPDATE test_report SET state='ACCEPTED',accepted_by=?,accepted_at=?,submit_no=?,
                            updated_at=clock_timestamp()
                        WHERE id=? AND test_id=? AND state='PROPOSED' AND source_draft_rev=?
                            AND payload_hash=? AND accepted_at IS NULL AND purged_at IS NULL
                        """,
                        c.self().id(),
                        Timestamp.from(accepted),
                        ordinal,
                        proposal.id(),
                        c.id(),
                        row.draftRev(),
                        row.hash())
                != 1) throw conflict("STATE_CONFLICT");
        UUID jobKey = UUID.randomUUID();
        Long jobId =
                db.queryForObject(
                        """
                        INSERT INTO grade_job(job_key,snapshot_id,runtime_id,report_id,report_hash,
                            state,accepted_at,deadline_at,next_run_at,input_hash,config_hash,rubric_hash)
                        VALUES (?,?,?, ?,?,'QUEUED',?,?,?,NULL,?,?) RETURNING id
                        """,
                        Long.class,
                        jobKey,
                        c.snapshotId(),
                        test.get("runtime_id"),
                        proposal.id(),
                        reportHash,
                        Timestamp.from(accepted),
                        Timestamp.from(accepted.plusSeconds(120)),
                        Timestamp.from(accepted),
                        test.get("config_hash"),
                        frozen.rubricHash());
        if (jobId == null
                || db.update(
                                """
                                UPDATE play_test SET next_submit_no=next_submit_no+1,rev=rev+1,
                                    updated_at=clock_timestamp()
                                WHERE id=? AND rev=? AND draft_rev=? AND next_submit_no=? AND state='RUNNING'
                                    AND deadline_at>?
                                """,
                                c.id(),
                                rev,
                                row.draftRev(),
                                ordinal,
                                Timestamp.from(accepted))
                        != 1) throw unavailable();
        Boolean persisted =
                db.queryForObject(
                        """
                        SELECT j.state='QUEUED' AND j.batch_id IS NULL AND j.sample_code IS NULL
                            AND j.repeat_no IS NULL AND j.input_hash IS NULL AND j.report_id=?
                            AND j.report_hash=? AND j.rubric_hash=? AND j.accepted_at=?
                            AND j.deadline_at=? AND r.state='ACCEPTED' AND r.accepted_by=?
                            AND r.accepted_at=j.accepted_at AND r.submit_no=? AND r.payload_hash=?
                            AND t.next_submit_no=? AND t.rev=?
                        FROM grade_job j JOIN test_report r ON r.id=j.report_id
                            JOIN play_test t ON t.id=r.test_id WHERE j.id=? AND t.id=?
                        """,
                        Boolean.class,
                        proposal.id(),
                        reportHash,
                        frozen.rubricHash(),
                        Timestamp.from(accepted),
                        Timestamp.from(accepted.plusSeconds(120)),
                        c.self().id(),
                        ordinal,
                        row.hash(),
                        ordinal + 1,
                        rev + 1,
                        jobId,
                        c.id());
        if (!Boolean.TRUE.equals(persisted)) throw unavailable();
        return true;
    }

    private static boolean recent(Instant seen, Instant now) {
        return seen != null && !seen.isAfter(now) && seen.isAfter(now.minusSeconds(30));
    }

    private boolean partnerSessionActive(PlaytestAccess.Context c, Instant now) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM member_account a
                            JOIN member_session s ON s.member_id=a.id
                            JOIN member_identity i ON i.id=s.identity_id AND i.member_id=a.id
                            JOIN member_token token ON token.session_id=s.id
                            WHERE a.id=? AND a.state='ACTIVE' AND s.auth_rev=a.auth_rev
                              AND s.revoked_at IS NULL AND s.idle_until>? AND s.absolute_until>?
                              AND i.provider='LOCAL' AND i.realm='LOCAL' AND i.active_yn
                              AND i.proof_at IS NOT NULL AND token.kind='ACCESS'
                              AND token.state='ISSUED' AND token.expires_at>?)
                        """,
                        Boolean.class,
                        c.partner().id(),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        Timestamp.from(now)));
    }

    /** 제출 대기가 없고 마감 전인 플레이만 결과 없는 포기로 종료한다. */
    public PlaytestInvitationService.ActionResult forfeit(
            String token, UUID key, long rev, UUID requestKey, UUID requestId) {
        keys(requestKey, requestId);
        if (rev < 0) throw invalid();
        return command(
                token,
                key,
                "TEST_FORFEIT",
                digest(key + ":" + rev),
                requestKey,
                requestId,
                (c, row) -> {
                    if (row.revision() != rev) throw conflict("EDIT_CONFLICT");
                    if (pendingAccepted(c.id())) throw conflict("STATE_CONFLICT");
                    Instant endedAt = c.clock(db);
                    if (db.update(
                                    "UPDATE play_test SET"
                                        + " state='ENDED',outcome='FORFEIT',ended_at=?,result_until=?,rev=rev+1,updated_at=?"
                                        + " WHERE id=? AND rev=? AND state='RUNNING' AND"
                                        + " deadline_at>?",
                                    Timestamp.from(endedAt),
                                    Timestamp.from(endedAt.plusSeconds(86400)),
                                    Timestamp.from(endedAt),
                                    c.id(),
                                    rev,
                                    Timestamp.from(endedAt))
                            != 1) throw conflict("STATE_CONFLICT");
                    db.update(
                            "UPDATE test_report SET state='CANCELLED',updated_at=clock_timestamp()"
                                    + " WHERE test_id=? AND state='PROPOSED'",
                            c.id());
                    return true;
                });
    }

    /** 종료 시한과 본인 피드백을 확인하며 판정 근거가 없는 정보는 공개하지 않는다. */
    public ResultView result(String token, UUID key, UUID requestId) {
        return access.execute(
                        token,
                        key,
                        c -> {
                            Row row = row(c);
                            if ("RUNNING".equals(row.state())) {
                                AuthException expired = normalize(c, requestId);
                                if (expired != null) return new Outcome<ResultView>(null, expired);
                                throw conflict("RESULT_NOT_READY");
                            }
                            ended(row, c);
                            Instant now = c.clock(db);
                            if (!now.isBefore(row.until()))
                                throw new AuthException(
                                        410, "RESULT_ACCESS_EXPIRED", "RESULT_ACCESS_EXPIRED");
                            boolean submitted = feedbackAt(c) != null;
                            boolean normal =
                                    Set.of("SUCCESS", "ATTEMPTS_EXHAUSTED", "TIME_LIMIT")
                                            .contains(row.outcome());
                            List<GradedReport> reports =
                                    submitted && normal ? graded(c, row) : null;
                            ScoreSummary summary =
                                    submitted && normal ? score(c, row, reports) : null;
                            audit(c, "TEST_RESULT", requestId, "READ");
                            return new Outcome<>(
                                    new ResultView(
                                            key,
                                            row.state(),
                                            row.outcome(),
                                            row.endedAt(),
                                            row.until(),
                                            !submitted
                                                    ? "AWAITING_FEEDBACK"
                                                    : normal ? "AVAILABLE" : "UNAVAILABLE",
                                            submitted,
                                            summary == null ? null : rowFinalScore(c),
                                            summary,
                                            reports,
                                            summary == null
                                                    ? null
                                                    : c.payload()
                                                            .path("sections")
                                                            .path("reveal")
                                                            .path("revealText")
                                                            .textValue(),
                                            requestId),
                                    null);
                        })
                .unwrap();
    }

    /** 본인의 최초 피드백만 불변 암호문으로 저장하며 상대 본문은 읽지 않는다. */
    public PlaytestInvitationService.ActionResult feedback(
            String token, UUID key, JsonNode feedback, UUID requestKey, UUID requestId) {
        keys(requestKey, requestId);
        if (feedback != null) {
            try {
                if (SnapshotJson.encode(feedback).length > 32768) throw tooLarge();
            } catch (IllegalArgumentException failure) {
                throw invalid();
            }
        }
        JsonNode checked = feedback(feedback);
        byte[] bytes = SnapshotJson.encode(checked);
        if (bytes.length > 32768) throw tooLarge();
        String intent =
                SnapshotJson.hash(
                        JsonNodeFactory.instance
                                .objectNode()
                                .put("testKey", key.toString())
                                .set("feedback", checked));
        return command(
                token,
                key,
                "TEST_FEEDBACK",
                intent,
                requestKey,
                requestId,
                (c, row) -> {
                    ended(row, c);
                    if (!c.clock(db).isBefore(row.until()))
                        throw new AuthException(
                                410, "RESULT_ACCESS_EXPIRED", "RESULT_ACCESS_EXPIRED");
                    if (feedbackAt(c) != null) throw conflict("FEEDBACK_ALREADY_SUBMITTED");
                    if (db.update(
                                    "UPDATE test_member SET"
                                        + " feedback_cipher=?,feedback_at=clock_timestamp(),updated_at=clock_timestamp()"
                                        + " WHERE test_id=? AND member_id=? AND feedback_at IS"
                                        + " NULL",
                                    encrypt(bytes, feedbackAad(c)),
                                    c.id(),
                                    c.self().id())
                            != 1) throw conflict("FEEDBACK_ALREADY_SUBMITTED");
                    return true;
                });
    }

    @FunctionalInterface
    private interface Change {
        boolean apply(PlaytestAccess.Context c, Row row);
    }

    private PlaytestInvitationService.ActionResult command(
            String token,
            UUID key,
            String action,
            String intent,
            UUID requestKey,
            UUID requestId,
            Change change) {
        try {
            return attempt(token, key, action, intent, requestKey, requestId, change);
        } catch (DataAccessException failure) {
            if (!collision(failure)) throw unavailable();
            return access.execute(
                    token, key, c -> replay(c, action, intent, requestKey, requestId));
        }
    }

    private PlaytestInvitationService.ActionResult attempt(
            String token,
            UUID key,
            String action,
            String intent,
            UUID requestKey,
            UUID requestId,
            Change change) {
        return access.execute(
                        token,
                        key,
                        c -> {
                            Receipt previous = receipt(requestKey);
                            if (previous != null)
                                return new Outcome<>(
                                        replay(c, action, intent, requestKey, requestId), null);
                            Row row = row(c);
                            if (!"TEST_FEEDBACK".equals(action)) {
                                AuthException denial = active(c, row, requestId);
                                if (denial != null)
                                    return new Outcome<PlaytestInvitationService.ActionResult>(
                                            null, denial);
                                row = row(c);
                            }
                            boolean changed = change.apply(c, row);
                            ObjectNode original = (ObjectNode) state(c);
                            if ("REPORT_PROPOSE".equals(action)) {
                                Pending created = pending(c.id());
                                if (created == null || created.proposer() != c.self().id())
                                    throw unavailable();
                                original.put("reportKey", created.key().toString());
                            }
                            if ("REPORT_ACCEPT".equals(action)) {
                                var saved =
                                        db.queryForMap(
                                                """
                                                SELECT r.report_key,r.submit_no,r.accepted_at,j.job_key,j.deadline_at
                                                FROM test_report r JOIN grade_job j ON j.report_id=r.id
                                                WHERE r.test_id=? AND r.accepted_by=? AND r.state='ACCEPTED'
                                                """,
                                                c.id(),
                                                c.self().id());
                                original.put("reportKey", saved.get("report_key").toString());
                                original.put(
                                        "submitNo",
                                        Integer.toString(
                                                ((Number) saved.get("submit_no")).intValue()));
                                original.put(
                                        "acceptedAt",
                                        ((Timestamp) saved.get("accepted_at"))
                                                .toInstant()
                                                .toString());
                                original.put(
                                        "gradeDeadline",
                                        ((Timestamp) saved.get("deadline_at"))
                                                .toInstant()
                                                .toString());
                                original.put("jobKey", saved.get("job_key").toString());
                            }
                            if (db.update(
                                            "INSERT INTO"
                                                + " test_action(request_key,member_id,action,scope_key,request_hash,result_data)"
                                                + " VALUES (?,?,?,?,?,?::jsonb)",
                                            requestKey,
                                            c.self().id(),
                                            action,
                                            c.scope(),
                                            intent,
                                            original.toString())
                                    != 1) throw unavailable();
                            UUID event =
                                    audit(c, action, requestId, changed ? "CHANGED" : "UNCHANGED");
                            Receipt stored = receipt(requestKey);
                            if (stored == null || !original.equals(stored.original()))
                                throw unavailable();
                            check(stored, c, action, intent);
                            Boolean audited =
                                    db.queryForObject(
                                            """
                                            SELECT EXISTS(SELECT 1 FROM test_audit WHERE event_key=? AND actor_kind='MEMBER'
                                                AND actor_ref=? AND action=? AND scope_kind='PLAYTEST' AND scope_key=?
                                                AND request_id=? AND phase='RESULT' AND business_result=?)
                                            """,
                                            Boolean.class,
                                            event,
                                            c.self().key().toString(),
                                            action,
                                            c.scope(),
                                            requestId,
                                            changed ? "CHANGED" : "UNCHANGED");
                            if (!Boolean.TRUE.equals(audited)) throw unavailable();
                            Map<String, Object> originalMap = map(original);
                            return new Outcome<>(
                                    new PlaytestInvitationService.ActionResult(
                                            action,
                                            false,
                                            changed,
                                            originalMap,
                                            originalMap,
                                            requestId),
                                    null);
                        })
                .unwrap();
    }

    private PlaytestInvitationService.ActionResult replay(
            PlaytestAccess.Context c, String action, String intent, UUID key, UUID requestId) {
        Receipt stored = receipt(key);
        if (stored == null) throw unavailable();
        check(stored, c, action, intent);
        if ("TEST_FEEDBACK".equals(action)) {
            Row row = row(c);
            ended(row, c);
            if (!c.clock(db).isBefore(row.until()))
                throw new AuthException(410, "RESULT_ACCESS_EXPIRED", "RESULT_ACCESS_EXPIRED");
        }
        Map<String, Object> original = map(stored.original());
        return new PlaytestInvitationService.ActionResult(
                action, true, false, original, map(state(c)), requestId);
    }

    private void check(Receipt stored, PlaytestAccess.Context c, String action, String intent) {
        if (stored.actor() != c.self().id()
                || !action.equals(stored.action())
                || !c.scope().equals(stored.scope())
                || !intent.equals(stored.hash())) throw conflict("REQUEST_KEY_CONFLICT");
    }

    private Receipt receipt(UUID key) {
        return db.query(
                "SELECT member_id,action,scope_key,request_hash,result_data::text FROM test_action"
                        + " WHERE request_key=?",
                rs ->
                        rs.next()
                                ? new Receipt(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        SnapshotJson.parse(
                                                rs.getString(5).getBytes(StandardCharsets.UTF_8)))
                                : null,
                key);
    }

    private JsonNode state(PlaytestAccess.Context c) {
        return db.queryForObject(
                "SELECT rev,draft_rev,state,outcome FROM play_test WHERE id=?",
                (rs, n) ->
                        JsonNodeFactory.instance
                                .objectNode()
                                .put("testKey", c.key().toString())
                                .put("rev", Long.toString(rs.getLong(1)))
                                .put("draftRev", Long.toString(rs.getLong(2)))
                                .put("state", rs.getString(3))
                                .put("outcome", rs.getString(4)),
                c.id());
    }

    private static Map<String, Object> map(JsonNode value) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("testKey", value.path("testKey").asText());
        fields.put("rev", value.path("rev").asText());
        fields.put("draftRev", value.path("draftRev").asText());
        fields.put("state", value.path("state").asText());
        fields.put(
                "outcome", value.path("outcome").isNull() ? null : value.path("outcome").asText());
        if (value.hasNonNull("reportKey")) fields.put("reportKey", value.get("reportKey").asText());
        if (value.hasNonNull("submitNo")) fields.put("submitNo", value.get("submitNo").asText());
        if (value.hasNonNull("acceptedAt"))
            fields.put("acceptedAt", Instant.parse(value.get("acceptedAt").asText()));
        if (value.hasNonNull("gradeDeadline"))
            fields.put("gradeDeadline", Instant.parse(value.get("gradeDeadline").asText()));
        if (value.hasNonNull("jobKey")) fields.put("jobKey", value.get("jobKey").asText());
        return java.util.Collections.unmodifiableMap(fields);
    }

    private Row row(PlaytestAccess.Context c) {
        return db.queryForObject(
                "SELECT"
                    + " rev,draft_rev,draft_cipher,draft_hash,deadline_at,state,outcome,ended_at,result_until"
                    + " FROM play_test WHERE id=?",
                (rs, n) ->
                        new Row(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getBytes(3),
                                rs.getString(4),
                                time(rs.getTimestamp(5)),
                                rs.getString(6),
                                rs.getString(7),
                                time(rs.getTimestamp(8)),
                                time(rs.getTimestamp(9))),
                c.id());
    }

    private Pending pending(long id) {
        return db.query(
                "SELECT"
                    + " id,report_key,source_draft_rev,proposer_id,state,payload_hash,payload_cipher"
                    + " FROM test_report WHERE test_id=? AND state='PROPOSED'",
                rs ->
                        rs.next()
                                ? new Pending(
                                        rs.getLong(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getBytes(7))
                                : null,
                id);
    }

    private boolean pendingAccepted(long id) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM test_report WHERE test_id=? AND"
                                + " state='ACCEPTED')",
                        Boolean.class,
                        id));
    }

    private Instant feedbackAt(PlaytestAccess.Context c) {
        return db.queryForObject(
                "SELECT feedback_at,feedback_cipher FROM test_member WHERE test_id=? AND"
                        + " member_id=?",
                (rs, n) -> {
                    Instant at = time(rs.getTimestamp(1));
                    byte[] cipher = rs.getBytes(2);
                    if ((at == null) != (cipher == null)) throw unavailable();
                    if (at != null) {
                        String plain =
                                crypto.decrypt(
                                        new String(cipher, StandardCharsets.UTF_8), feedbackAad(c));
                        byte[] bytes = plain.getBytes(StandardCharsets.UTF_8);
                        if (bytes.length > 32768) throw unavailable();
                        JsonNode stored = parseStored(plain);
                        if (!stored.equals(feedback(stored))
                                || !java.util.Arrays.equals(bytes, SnapshotJson.encode(stored)))
                            throw unavailable();
                    }
                    return at;
                },
                c.id(),
                c.self().id());
    }

    /** 원문 암호와 고정 REPORT 해시 및 작업의 GCM 요약을 교차 검증한 정상 보고서만 공개한다. */
    private List<GradedReport> graded(PlaytestAccess.Context c, Row terminal) {
        var frozen = FrozenSnapshotCodec.decode(SnapshotJson.encode(c.payload()));
        var pinned =
                db.queryForMap(
                        "SELECT runtime_id,config_hash,runtime_epoch FROM play_test WHERE id=?",
                        c.id());
        var entries =
                db.queryForList(
                        """
                        SELECT r.id,r.report_key,r.source_draft_rev,r.submit_no,r.payload_cipher,r.payload_hash,
                               r.snapshot_id AS report_snapshot,r.runtime_id AS report_runtime,
                               r.config_hash AS report_config,r.runtime_epoch AS report_epoch,
                               j.id AS job_id,j.report_hash,j.input_hash,j.result_cipher,j.result_hash,
                               j.result_data::text AS summary,j.rubric_hash,j.config_hash,
                               j.snapshot_id,j.runtime_id,j.accepted_at AS job_accepted,r.accepted_at AS report_accepted
                        FROM test_report r JOIN grade_job j ON j.report_id=r.id
                        WHERE r.test_id=? AND r.state='GRADED' AND j.state='COMPLETED'
                              AND j.batch_id IS NULL AND j.sample_code IS NULL AND j.repeat_no IS NULL
                              AND r.purged_at IS NULL ORDER BY r.submit_no
                        """,
                        c.id());
        int attempts =
                db.queryForObject(
                        "SELECT attempt_count FROM play_test WHERE id=?", Integer.class, c.id());
        if (entries.size() != attempts) throw unavailable();
        List<GradedReport> result = new ArrayList<>();
        int previous = 0;
        for (var entry : entries) {
            int ordinal = ((Number) entry.get("submit_no")).intValue();
            long reportId = ((Number) entry.get("id")).longValue();
            long sourceRev = ((Number) entry.get("source_draft_rev")).longValue();
            long jobId = ((Number) entry.get("job_id")).longValue();
            if (ordinal <= previous
                    || entry.get("payload_cipher") == null
                    || entry.get("result_cipher") == null
                    || entry.get("result_hash") == null
                    || entry.get("input_hash") != null
                    || !java.util.Objects.equals(
                            entry.get("job_accepted"), entry.get("report_accepted"))
                    || ((Number) entry.get("snapshot_id")).longValue() != c.snapshotId()
                    || ((Number) entry.get("report_snapshot")).longValue() != c.snapshotId()
                    || ((Number) entry.get("runtime_id")).longValue()
                            != ((Number) pinned.get("runtime_id")).longValue()
                    || ((Number) entry.get("report_runtime")).longValue()
                            != ((Number) pinned.get("runtime_id")).longValue()
                    || ((Number) entry.get("report_epoch")).longValue()
                            != ((Number) pinned.get("runtime_epoch")).longValue()
                    || !java.util.Objects.equals(
                            entry.get("report_config"), pinned.get("config_hash"))
                    || !java.util.Objects.equals(
                            entry.get("config_hash"), pinned.get("config_hash"))
                    || !java.util.Objects.equals(entry.get("rubric_hash"), frozen.rubricHash()))
                throw unavailable();
            previous = ordinal;
            JsonNode report =
                    decrypt(
                            (byte[]) entry.get("payload_cipher"),
                            reportAad(reportId, c.id(), c.snapshotId(), sourceRev),
                            (String) entry.get("payload_hash"));
            Report validated = complete(report);
            if (!frozen.reportHash(validated).equals(entry.get("report_hash"))) throw unavailable();
            JsonNode base = new PlaytestResultAuthenticator(db, crypto).authenticate(c.id(), jobId);
            categories(
                    c, base, base.get("baseScore").intValue(), base.get("success").booleanValue());
            result.add(
                    new GradedReport(
                            (UUID) entry.get("report_key"),
                            ordinal,
                            report,
                            base.get("baseScore").intValue(),
                            base.get("success").booleanValue()));
        }
        return List.copyOf(result);
    }

    /** 등록 채점표의 최대값·단계·필수 조건과 암호화된 항목 점수의 전체 좌표를 대조한다. */
    private List<CategoryScore> categories(
            PlaytestAccess.Context c, JsonNode base, int expectedBase, boolean expectedSuccess) {
        JsonNode rubrics = c.payload().path("resources").path("rubrics");
        JsonNode items = base.path("items");
        if (!rubrics.isArray() || !items.isArray() || rubrics.size() != items.size())
            throw unavailable();
        var totals = new EnumMap<Category, int[]>(Category.class);
        var seen = new HashSet<String>();
        int sum = 0;
        boolean success = true;
        for (JsonNode rubric : rubrics) {
            String code = rubric.path("code").textValue();
            if (code == null || !seen.add(code)) throw unavailable();
            JsonNode selected = null;
            for (JsonNode item : items) {
                if (code.equals(item.path("rubricCode").textValue())) {
                    if (selected != null) throw unavailable();
                    selected = item;
                }
            }
            if (selected == null) throw unavailable();
            exact(
                    selected,
                    "rubricCode",
                    "score",
                    "requiredMet",
                    "claims",
                    "contradictions",
                    "reason");
            JsonNode scoreNode = selected.get("score");
            if (!scoreNode.isIntegralNumber() || !scoreNode.canConvertToInt()) throw unavailable();
            int score = scoreNode.intValue();
            int max = requiredInt(rubric.path("maxScore"));
            if (score < 0 || score > max || !rubric.path("ruleData").path("levels").isArray())
                throw unavailable();
            boolean known = false;
            for (JsonNode level : rubric.path("ruleData").path("levels"))
                if (level.path("score").isIntegralNumber()
                        && level.path("score").intValue() == score) known = true;
            if (!known) throw unavailable();
            boolean required = rubric.path("requiredYn").booleanValue();
            if (required) {
                boolean met = score >= requiredInt(rubric.path("passScore"));
                if (!selected.path("requiredMet").isBoolean()
                        || selected.path("requiredMet").booleanValue() != met) throw unavailable();
                success &= met;
            } else if (!selected.path("requiredMet").isNull()) throw unavailable();
            Category category;
            try {
                category = Category.valueOf(rubric.path("category").textValue());
            } catch (RuntimeException failure) {
                throw unavailable();
            }
            int[] pair = totals.computeIfAbsent(category, ignored -> new int[2]);
            pair[0] += score;
            pair[1] += max;
            sum += score;
        }
        if (sum != expectedBase || success != expectedSuccess || seen.size() != items.size())
            throw unavailable();
        return totals.entrySet().stream()
                .map(
                        entry ->
                                new CategoryScore(
                                        entry.getKey().name(),
                                        entry.getValue()[0],
                                        entry.getValue()[1]))
                .toList();
    }

    /** 마지막 정상 판정과 실제 누적 오답에 결속된 종료 점수를 구성한다. */
    private ScoreSummary score(PlaytestAccess.Context c, Row row, List<GradedReport> reports) {
        var counters =
                db.queryForMap(
                        "SELECT attempt_count,wrong_count,final_score FROM play_test WHERE id=?",
                        c.id());
        int attempts = ((Number) counters.get("attempt_count")).intValue();
        int wrong = ((Number) counters.get("wrong_count")).intValue();
        int finalScore = ((Number) counters.get("final_score")).intValue();
        if (attempts != reports.size() || wrong < 0 || wrong > attempts) throw unavailable();
        if (reports.isEmpty()) {
            if (!"TIME_LIMIT".equals(row.outcome())
                    || attempts != 0
                    || wrong != 0
                    || finalScore != 0) throw unavailable();
            return new ScoreSummary(0, 0, 0, 0, List.of());
        }
        GradedReport last = reports.getLast();
        if (last.success() != "SUCCESS".equals(row.outcome())
                || wrong != (int) reports.stream().filter(report -> !report.success()).count()
                || Math.max(0, last.baseScore() - wrong * 10) != finalScore) throw unavailable();
        // 범주 점수는 마지막 정상 작업의 검증된 GCM 봉투에서 다시 읽는다.
        var job =
                db.queryForMap(
                        "SELECT j.id FROM grade_job j JOIN"
                                + " test_report r ON r.id=j.report_id WHERE r.test_id=? AND"
                                + " r.report_key=?",
                        c.id(),
                        last.reportKey());
        JsonNode base =
                new PlaytestResultAuthenticator(db, crypto)
                        .authenticate(c.id(), ((Number) job.get("id")).longValue());
        return new ScoreSummary(
                last.baseScore(),
                wrong,
                wrong * 10,
                finalScore,
                categories(c, base, last.baseScore(), last.success()));
    }

    private Integer rowFinalScore(PlaytestAccess.Context c) {
        return db.queryForObject(
                "SELECT final_score FROM play_test WHERE id=?", Integer.class, c.id());
    }

    private static JsonNode parseStored(String text) {
        if (text == null) throw unavailable();
        try {
            return SnapshotJson.parse(text.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw unavailable();
        for (String field : fields) if (!node.has(field)) throw unavailable();
    }

    private AuthException active(PlaytestAccess.Context c, Row row, UUID requestId) {
        AuthException expired = normalize(c, requestId);
        if (expired != null) return expired;
        if (!"RUNNING".equals(row.state())
                || row.deadline() == null
                || !c.clock(db).isBefore(row.deadline())) throw conflict("STATE_CONFLICT");
        return null;
    }

    private AuthException normalize(PlaytestAccess.Context c, UUID requestId) {
        return PlaytestAccess.normalizeTime(
                db, crypto, c.id(), "MEMBER", c.self().key().toString(), requestId);
    }

    private static void ended(Row row, PlaytestAccess.Context c) {
        if (!"ENDED".equals(row.state()) || row.endedAt() == null || row.until() == null)
            throw conflict("RESULT_NOT_READY");
    }

    private JsonNode decrypt(byte[] cipher, String aad, String expectedHash) {
        String source = crypto.decrypt(new String(cipher, StandardCharsets.UTF_8), aad);
        JsonNode result;
        try {
            result = SnapshotJson.parse(source.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
        if (!SnapshotJson.hash(result).equals(expectedHash)) throw unavailable();
        return result;
    }

    private byte[] encrypt(byte[] bytes, String aad) {
        return crypto.encrypt(new String(bytes, StandardCharsets.UTF_8), aad)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String draftAad(PlaytestAccess.Context c, long draftRev) {
        return "play_test/" + c.id() + "/draft/" + draftRev + "/v1";
    }

    private static String reportAad(long reportId, long testId, long snapshotId, long draftRev) {
        return "test_report/"
                + reportId
                + "/test/"
                + testId
                + "/snapshot/"
                + snapshotId
                + "/draft/"
                + draftRev
                + "/payload/v1";
    }

    private static String feedbackAad(PlaytestAccess.Context c) {
        return "test_member/" + c.id() + "/" + c.self().id() + "/feedback/v1";
    }

    private static JsonNode empty() {
        return JsonNodeFactory.instance
                .objectNode()
                .putNull("culpritCode")
                .put("method", "")
                .put("time", "")
                .put("motive", "")
                .put("evidence", "");
    }

    private JsonNode draft(JsonNode node) {
        if (node == null
                || !node.isObject()
                || node.size() != 5
                || !node.has("culpritCode")
                || !node.has("method")
                || !node.has("time")
                || !node.has("motive")
                || !node.has("evidence")) throw invalidReport();
        JsonNode culprit = node.get("culpritCode");
        if (!culprit.isNull() && !culprit.isTextual()) throw invalidReport();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (culprit.isNull()) result.putNull("culpritCode");
        else result.put("culpritCode", culprit.textValue());
        int total = 0;
        for (String field : new String[] {"method", "time", "motive", "evidence"}) {
            JsonNode text = node.get(field);
            if (!text.isTextual()) throw invalidReport();
            String normalized;
            try {
                normalized = CommonUtil.normalizeText(text.textValue(), 5000, true);
            } catch (IllegalArgumentException failure) {
                throw invalidReport();
            }
            int length = normalized.codePointCount(0, normalized.length());
            if (length > 5000) throw invalidReport();
            total += length;
            result.put(field, normalized);
        }
        if (total > 20000) throw invalidReport();
        try {
            SnapshotJson.encode(result);
            if (!culprit.isNull()) {
                // 선택 인물 코드는 REPORT-1 규칙을 따르되 초안의 빈 서술은 허용한다.
                complete(result);
            }
        } catch (IllegalArgumentException failure) {
            throw invalidReport();
        }
        return result;
    }

    private Report complete(JsonNode node) {
        try {
            return validator.parseReport(
                    new String(SnapshotJson.encode(node), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw invalidReport();
        }
    }

    private static JsonNode feedback(JsonNode node) {
        if (node == null || !node.isObject() || node.size() < 3 || node.size() > 4) throw invalid();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String field :
                new String[] {"blockedAt", "roleContribution", "fairness", "gradingConcern"}) {
            JsonNode value = node.get(field);
            if (value == null && "gradingConcern".equals(field)) {
                result.put(field, "");
                continue;
            }
            if (value == null
                    || !value.isTextual()
                    || value.textValue().codePointCount(0, value.textValue().length()) > 2000)
                throw invalid();
            result.put(field, value.textValue());
        }
        try {
            SnapshotJson.encode(result);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
        return result;
    }

    private UUID audit(PlaytestAccess.Context c, String action, UUID requestId, String result) {
        UUID event = UUID.randomUUID();
        if (db.update(
                        "INSERT INTO"
                            + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                            + " VALUES (?,'MEMBER',?,?,'PLAYTEST',?,?,'RESULT',?,'{}'::jsonb)",
                        event,
                        c.self().key().toString(),
                        action,
                        c.scope(),
                        requestId,
                        result)
                != 1) throw unavailable();
        return event;
    }

    private static Instant time(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void keys(UUID key, UUID requestId) {
        if (key == null || requestId == null || key.version() != 4 || requestId.version() != 4)
            throw invalid();
    }

    private static String digest(String input) {
        return com.reasoning.common.util.CommonUtil.sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean collision(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sql && sql.getServerErrorMessage() != null)
                return "23505".equals(sql.getSQLState())
                        && "uk_test_action_request"
                                .equals(sql.getServerErrorMessage().getConstraint());
            if (cause instanceof SQLException sql && !"23505".equals(sql.getSQLState()))
                return false;
        }
        return false;
    }

    private static AuthException tooLarge() {
        return new AuthException(413, "PAYLOAD_TOO_LARGE", "PAYLOAD_TOO_LARGE");
    }

    private static int requiredInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw unavailable();
        return value.intValue();
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    private static AuthException invalidReport() {
        return AuthException.badRequest("INVALID_REPORT");
    }

    private static AuthException conflict(String code) {
        return AuthException.conflict(code);
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("PLAYTEST_UNAVAILABLE");
    }
}
