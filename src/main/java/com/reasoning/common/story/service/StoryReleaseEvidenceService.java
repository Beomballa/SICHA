package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.story.repository.BlindEligibilityReader;
import com.reasoning.common.story.repository.BlindEligibilityReader.Use;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 검수 목적의 실제 두 출처만 모아 공개 근거를 검증한다. 공개·READY 상태는 변경하지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryReleaseEvidenceService {
    public enum State {
        VERIFIED,
        MISSING,
        UNAVAILABLE
    }

    public record Assessment(State state, String reason, Long receiptId) {}

    private record Source(
            String kind,
            long id,
            long reviewId,
            String a,
            String b,
            long first,
            long second,
            String fingerprint,
            Instant reviewedAt) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final BlindEligibilityReader eligibility;

    public StoryReleaseEvidenceService(JdbcTemplate db, StoryService stories, ObjectMapper json) {
        this.db = db;
        this.json = json;
        this.eligibility = new BlindEligibilityReader(db);
    }

    /** 소유 거래의 잠긴 REVIEW 부모에서만 호출하며 원래 출처와 검토자 결속을 재확인한다. */
    long recordReviewLocked(
            long versionId,
            Long snapshotId,
            String status,
            AdminActor actor,
            String sourceKind,
            long sourceId,
            String verdict,
            int criticalOpenCount,
            String detail,
            String verificationRef,
            Long supersedesId,
            String findingRef) {
        if (!Set.of("TEST", "SOLO").contains(sourceKind)
                || !Set.of("PASS", "FAIL", "INCOMPLETE").contains(verdict)
                || criticalOpenCount < 0
                || ("PASS".equals(verdict) && criticalOpenCount != 0)
                || detail == null
                || detail.isBlank()
                || detail.length() > 8000
                || verificationRef == null
                || !verificationRef.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_REVIEW");
        if (!"REVIEW".equals(status) || snapshotId == null)
            throw AuthException.conflict("STATE_CONFLICT");
        boolean source =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                sourceKind.equals("TEST")
                                        ? "SELECT EXISTS(SELECT 1 FROM play_test WHERE id=? AND"
                                                + " snapshot_id=? AND version_id=?)"
                                        : "SELECT EXISTS(SELECT 1 FROM game_room WHERE id=? AND"
                                                + " snapshot_id=? AND version_id=? AND"
                                                + " purpose='REVIEW_SOLO_QUALIFICATION')",
                                Boolean.class,
                                sourceId,
                                snapshotId,
                                versionId));
        if (!source) throw AuthException.conflict("EVIDENCE_SOURCE_MISSING");
        if (supersedesId != null
                && !Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM release_human_review WHERE id=? AND"
                                        + " snapshot_id=? AND "
                                        + (sourceKind.equals("TEST") ? "test_id" : "room_id")
                                        + "=? AND verdict IN ('FAIL','INCOMPLETE'))",
                                Boolean.class,
                                supersedesId,
                                snapshotId,
                                sourceId))) throw AuthException.conflict("REVIEW_CLOSURE_MISMATCH");
        if (supersedesId != null
                && (findingRef == null || !findingRef.matches("[A-Za-z0-9_-]{8,64}")))
            throw AuthException.badRequest("INVALID_REVIEW");
        return db.queryForObject(
                "INSERT INTO"
                    + " release_human_review(snapshot_id,test_id,room_id,reviewer_id,verdict,critical_open_count,detail,verification_ref,supersedes_id,finding_ref)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                snapshotId,
                sourceKind.equals("TEST") ? sourceId : null,
                sourceKind.equals("SOLO") ? sourceId : null,
                actor.accountId(),
                verdict,
                criticalOpenCount,
                detail,
                verificationRef,
                supersedesId,
                findingRef);
    }

    /** 요청키 영수증을 소유한 외부 거래에서 현재 사본과 실제 이중 출처만 봉인한다. */
    Assessment collectLocked(
            long versionId,
            Long currentSnapshot,
            String status,
            AdminActor actor,
            long snapshotId,
            long runtimeId,
            long epoch,
            String configHash) {
        if (!"REVIEW".equals(status) || currentSnapshot == null || currentSnapshot != snapshotId)
            throw AuthException.conflict("SNAPSHOT_CONFLICT");
        Assessment check = assess(versionId, snapshotId, runtimeId, epoch, configHash, null, false);
        if (check.state() != State.VERIFIED) return check;
        List<Source> sources = sources(versionId, snapshotId, runtimeId, epoch, configHash);
        for (Source source : sources) {
            if (source.kind().equals("TEST"))
                db.update(
                        "UPDATE test_retention SET raw_until=max_until,selected_at=now() WHERE"
                                + " test_id=? AND selected_at IS NULL AND now()<raw_until",
                        source.id());
            else
                db.update(
                        "UPDATE release_solo_feedback SET"
                                + " selected_at=now(),raw_until=ended_at+interval '365 days' WHERE"
                                + " room_id=? AND selected_at IS NULL AND now()<raw_until",
                        source.id());
        }
        String hash = hash(sources);
        Long receipt =
                db.queryForObject(
                        "INSERT INTO"
                            + " release_evidence(receipt_key,snapshot_id,runtime_id,runtime_epoch,config_hash,evidence_hash,created_by)"
                            + " VALUES(?,?,?,?,?,?,?) RETURNING id",
                        Long.class,
                        UUID.randomUUID(),
                        snapshotId,
                        runtimeId,
                        epoch,
                        configHash,
                        hash,
                        actor.accountId());
        for (Source s : sources)
            db.update(
                    "INSERT INTO"
                        + " release_evidence_item(receipt_id,source_kind,pair_a,pair_b,test_id,room_id,review_id,source_hash)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    receipt,
                    s.kind(),
                    s.a(),
                    s.b(),
                    s.kind().equals("TEST") ? s.id() : null,
                    s.kind().equals("SOLO") ? s.id() : null,
                    s.reviewId(),
                    s.fingerprint());
        return new Assessment(State.VERIFIED, "SOURCE_VERIFIED", receipt);
    }

    /** 게시 뒤에도 원본 파기·피드백 삭제·runtime epoch·검수 자격 변경을 매 진입 재검사한다. */
    public Assessment evaluateRelease(
            long versionId,
            long snapshotId,
            long runtimeId,
            long epoch,
            String configHash,
            long receiptId) {
        return assess(versionId, snapshotId, runtimeId, epoch, configHash, receiptId, true);
    }

    private Assessment assess(
            long version,
            long snapshot,
            long runtime,
            long epoch,
            String config,
            Long receipt,
            boolean persisted) {
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM review_snapshot WHERE id=? AND version_id=?)",
                        Boolean.class,
                        snapshot,
                        version)))
            return new Assessment(State.UNAVAILABLE, "SNAPSHOT_MISMATCH", receipt);
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM grade_runtime WHERE id=? AND epoch=? AND"
                                + " config_hash=? AND state='AVAILABLE')",
                        Boolean.class,
                        runtime,
                        epoch,
                        config)))
            return new Assessment(State.UNAVAILABLE, "RUNTIME_CHANGED", receipt);
        List<Source> current = sources(version, snapshot, runtime, epoch, config);
        List<Map<String, Object>> pairs =
                db.queryForList(
                        "SELECT role_a,role_b FROM story_pair WHERE version_id=? AND active_yn"
                                + " ORDER BY role_a,role_b",
                        version);
        if (pairs.isEmpty()) return new Assessment(State.MISSING, "NO_CANDIDATE_PAIRS", receipt);
        for (var pair : pairs) {
            String a = (String) pair.get("role_a"), b = (String) pair.get("role_b");
            for (String kind : List.of("TEST", "SOLO")) {
                Set<Long> participants = new HashSet<>();
                int count = 0;
                for (Source s : current)
                    if (s.kind().equals(kind)
                            && s.a().equals(a)
                            && s.b().equals(b)
                            && !participants.contains(s.first())
                            && !participants.contains(s.second())) {
                        participants.add(s.first());
                        participants.add(s.second());
                        count++;
                    }
                if (count < 3)
                    return new Assessment(
                            State.MISSING, "BLIND_" + kind + "_" + a + "_" + b, receipt);
            }
        }
        if (!persisted) return new Assessment(State.VERIFIED, "SOURCE_VERIFIED", null);
        var receipts =
                db.queryForList(
                        "SELECT evidence_hash FROM release_evidence WHERE id=? AND snapshot_id=?"
                                + " AND runtime_id=? AND runtime_epoch=? AND config_hash=?",
                        receipt,
                        snapshot,
                        runtime,
                        epoch,
                        config);
        if (receipts.size() != 1)
            return new Assessment(State.UNAVAILABLE, "RECEIPT_MISMATCH", receipt);
        var items =
                db.queryForList(
                        "SELECT source_kind,pair_a,pair_b,test_id,room_id,review_id,source_hash"
                                + " FROM release_evidence_item WHERE receipt_id=?",
                        receipt);
        if (items.size() != current.size()
                || !receipts.getFirst().get("evidence_hash").equals(hash(current)))
            return new Assessment(State.UNAVAILABLE, "SOURCE_CHANGED", receipt);
        for (Source source : current)
            if (items.stream()
                    .noneMatch(
                            row ->
                                    source.kind().equals(row.get("source_kind"))
                                            && source.a().equals(row.get("pair_a"))
                                            && source.b().equals(row.get("pair_b"))
                                            && row.get(
                                                            source.kind().equals("TEST")
                                                                    ? "test_id"
                                                                    : "room_id")
                                                    instanceof Number id
                                            && source.id() == id.longValue()
                                            && row.get("review_id") instanceof Number review
                                            && source.reviewId() == review.longValue()
                                            && source.fingerprint().equals(row.get("source_hash"))))
                return new Assessment(State.UNAVAILABLE, "SOURCE_ERASED", receipt);
        return new Assessment(State.VERIFIED, "SOURCE_VERIFIED", receipt);
    }

    private List<Source> sources(
            long version, long snapshot, long runtime, long epoch, String config) {
        List<Source> found = new ArrayList<>();
        String test =
                """
                SELECT DISTINCT ON (t.id) t.id,r.id review_id,t.role_a,t.role_b,ma.member_id first_id,mb.member_id second_id,
                  ea.story_id,ma.assessment_id first_assessment,mb.assessment_id second_assessment,
                  r.created_at reviewed_at,encode(sha256(convert_to(jsonb_build_object('payload',tr.payload_hash,'job',j.result_hash,
                    'attempt',attempt.output_hash,'review',r.id,'feedbackA',ma.feedback_at,'feedbackB',mb.feedback_at)::text,'UTF8')),'hex') source_hash
                FROM play_test t JOIN test_member ma ON ma.test_id=t.id AND ma.slot=1
                JOIN test_member mb ON mb.test_id=t.id AND mb.slot=2
                JOIN test_retention retention ON retention.test_id=t.id
                JOIN test_report tr ON tr.test_id=t.id AND tr.state='GRADED' AND tr.payload_cipher IS NOT NULL
                JOIN grade_job j ON j.report_id=tr.id AND j.state='COMPLETED' AND j.result_hash IS NOT NULL
                JOIN grade_attempt attempt ON attempt.job_id=j.id AND attempt.state='SUCCEEDED' AND attempt.completion_data IS NOT NULL
                JOIN release_human_review r ON r.test_id=t.id AND r.snapshot_id=t.snapshot_id AND r.verdict='PASS' AND r.critical_open_count=0
                JOIN test_exposure ea ON ea.story_id=(SELECT story_id FROM story_version WHERE id=t.version_id)
                  AND ea.member_id=ma.member_id AND ea.first_test_id=t.id AND ea.first_room_id IS NULL
                JOIN test_exposure eb ON eb.story_id=ea.story_id AND eb.member_id=mb.member_id
                  AND eb.first_test_id=t.id AND eb.first_room_id IS NULL
                WHERE t.version_id=? AND t.snapshot_id=? AND t.runtime_id=? AND t.runtime_epoch=? AND t.config_hash=?
                  AND t.mode='BLIND' AND t.state='ENDED' AND t.outcome IN ('SUCCESS','TIME_LIMIT','ATTEMPTS_EXHAUSTED')
                  AND ma.invite_state='ACCEPTED' AND mb.invite_state='ACCEPTED' AND ma.blind_declared AND mb.blind_declared
                  AND ma.feedback_cipher IS NOT NULL AND mb.feedback_cipher IS NOT NULL AND ma.revoked_at IS NULL AND mb.revoked_at IS NULL
                  AND retention.raw_until>now() AND tr.purged_at IS NULL AND j.runtime_id=t.runtime_id AND j.config_hash=t.config_hash
                  AND j.report_hash IS NOT NULL AND attempt.output_hash IS NOT NULL AND attempt.worker_key IS NOT NULL
                  AND j.snapshot_id=t.snapshot_id AND j.rubric_hash IS NOT NULL
                  AND attempt.ended_at<=r.created_at AND ma.feedback_at<=r.created_at AND mb.feedback_at<=r.created_at
                  AND EXISTS(SELECT 1 FROM grade_event event WHERE event.job_id=j.id AND event.attempt_no=attempt.attempt_no
                    AND event.actor_kind='WORKER' AND event.event_kind='COMPLETE_APPLIED')
                  AND ea.first_exposed_at>=t.started_at AND eb.first_exposed_at>=t.started_at
                  AND EXISTS(SELECT 1 FROM test_audit audit JOIN member_account member ON member.member_key::text=audit.actor_ref
                    WHERE audit.scope_kind='PLAYTEST' AND audit.scope_key='test:'||t.test_key::text
                      AND audit.action='TEST_MATERIALS' AND audit.business_result='EXPOSED'
                      AND member.id=ma.member_id AND audit.created_at>=ea.first_exposed_at)
                  AND EXISTS(SELECT 1 FROM test_audit audit JOIN member_account member ON member.member_key::text=audit.actor_ref
                    WHERE audit.scope_kind='PLAYTEST' AND audit.scope_key='test:'||t.test_key::text
                      AND audit.action='TEST_MATERIALS' AND audit.business_result='EXPOSED'
                      AND member.id=mb.member_id AND audit.created_at>=eb.first_exposed_at)
                  AND r.created_at>=t.ended_at AND r.created_at<=retention.raw_until
                  AND EXISTS(SELECT 1 FROM admin_account admin JOIN admin_credential credential ON credential.account_id=admin.id
                    JOIN story_access access ON access.admin_id=admin.id AND access.story_id=ea.story_id
                    WHERE admin.id=r.reviewer_id AND admin.active_yn AND admin.can_review AND credential.enrolled_at IS NOT NULL
                      AND credential.mfa_state='READY' AND access.active_yn AND access.permission='REVIEW')
                  AND NOT EXISTS(SELECT 1 FROM release_human_review prior WHERE prior.test_id=t.id AND prior.id<r.id
                    AND prior.verdict IN ('FAIL','INCOMPLETE') AND NOT EXISTS
                    (SELECT 1 FROM release_human_review closure WHERE closure.test_id=t.id AND closure.supersedes_id=prior.id
                     AND closure.id<=r.id AND closure.verdict='PASS' AND closure.finding_ref IS NOT NULL))
                  AND NOT EXISTS(SELECT 1 FROM release_human_review bad WHERE bad.test_id=t.id AND bad.id>r.id)
                ORDER BY t.id,j.id DESC
                """;
        db.query(
                test,
                rs -> {
                    if (eligibility.test(
                                    rs.getLong("story_id"),
                                    snapshot,
                                    rs.getLong("first_id"),
                                    rs.getLong("id"),
                                    (Long) rs.getObject("first_assessment"),
                                    Use.EVIDENCE)
                            && eligibility.test(
                                    rs.getLong("story_id"),
                                    snapshot,
                                    rs.getLong("second_id"),
                                    rs.getLong("id"),
                                    (Long) rs.getObject("second_assessment"),
                                    Use.EVIDENCE))
                        found.add(
                                new Source(
                                        "TEST",
                                        rs.getLong("id"),
                                        rs.getLong("review_id"),
                                        rs.getString("role_a"),
                                        rs.getString("role_b"),
                                        rs.getLong("first_id"),
                                        rs.getLong("second_id"),
                                        rs.getString("source_hash"),
                                        rs.getTimestamp("reviewed_at").toInstant()));
                },
                version,
                snapshot,
                runtime,
                epoch,
                config);
        String solo =
                """
                SELECT DISTINCT ON (g.id) g.id,r.id review_id,g.role_a,g.role_b,m.member_id first_id,
                  g.story_id,g.assessment_id,
                  r.created_at reviewed_at,encode(sha256(convert_to(jsonb_build_object('payload',gr.payload_hash,'job',j.result_hash,
                    'attempt',attempt.output_hash,'review',r.id,'feedback',f.submitted_at)::text,'UTF8')),'hex') source_hash
                FROM game_room g JOIN game_member m ON m.room_id=g.id AND m.member_id=g.selected_member_id AND m.active_yn
                JOIN release_solo_feedback f ON f.room_id=g.id AND f.member_id=m.member_id
                JOIN game_report gr ON gr.room_id=g.id AND gr.state='GRADED' AND gr.payload_cipher IS NOT NULL
                JOIN grade_job j ON j.game_report_id=gr.id AND j.state='COMPLETED' AND j.result_hash IS NOT NULL
                JOIN grade_attempt attempt ON attempt.job_id=j.id AND attempt.state='SUCCEEDED' AND attempt.completion_data IS NOT NULL
                JOIN release_human_review r ON r.room_id=g.id AND r.snapshot_id=g.snapshot_id AND r.verdict='PASS' AND r.critical_open_count=0
                JOIN test_exposure e ON e.story_id=g.story_id AND e.member_id=m.member_id
                  AND e.first_test_id IS NULL AND e.first_room_id=g.id
                WHERE g.version_id=? AND g.snapshot_id=? AND g.runtime_id=? AND g.runtime_epoch=? AND g.config_hash=?
                  AND g.purpose='REVIEW_SOLO_QUALIFICATION' AND g.mode='SOLO' AND g.qualification_kind='BLIND'
                  AND m.blind_declared IS TRUE
                  AND g.state='ENDED' AND g.end_reason IN ('SUCCESS','TIME_LIMIT','ATTEMPTS_EXHAUSTED')
                  AND e.first_exposed_at>=g.started_at AND EXISTS(SELECT 1 FROM game_audit exposure
                    WHERE exposure.room_id=g.id AND exposure.member_id=m.member_id AND exposure.action='CONTENT_READ'
                    AND exposure.created_at>=g.started_at)
                  AND j.runtime_id=g.runtime_id AND j.config_hash=g.config_hash
                  AND j.report_hash IS NOT NULL AND attempt.output_hash IS NOT NULL AND attempt.worker_key IS NOT NULL
                  AND j.snapshot_id=g.snapshot_id AND j.rubric_hash=g.rubric_hash AND attempt.ended_at<=r.created_at
                  AND EXISTS(SELECT 1 FROM grade_event event WHERE event.job_id=j.id AND event.attempt_no=attempt.attempt_no
                    AND event.actor_kind='WORKER' AND event.event_kind='COMPLETE_APPLIED')
                  AND r.created_at>=g.ended_at AND f.submitted_at<=r.created_at
                  AND f.raw_until>now()
                  AND EXISTS(SELECT 1 FROM admin_account admin JOIN admin_credential credential ON credential.account_id=admin.id
                    JOIN story_access access ON access.admin_id=admin.id AND access.story_id=g.story_id
                    WHERE admin.id=r.reviewer_id AND admin.active_yn AND admin.can_review AND credential.enrolled_at IS NOT NULL
                      AND credential.mfa_state='READY' AND access.active_yn AND access.permission='REVIEW')
                  AND NOT EXISTS(SELECT 1 FROM release_human_review prior WHERE prior.room_id=g.id AND prior.id<r.id
                    AND prior.verdict IN ('FAIL','INCOMPLETE') AND NOT EXISTS
                    (SELECT 1 FROM release_human_review closure WHERE closure.room_id=g.id AND closure.supersedes_id=prior.id
                     AND closure.id<=r.id AND closure.verdict='PASS' AND closure.finding_ref IS NOT NULL))
                  AND NOT EXISTS(SELECT 1 FROM release_human_review bad WHERE bad.room_id=g.id AND bad.id>r.id)
                ORDER BY g.id,j.id DESC
                """;
        db.query(
                solo,
                rs -> {
                    if (eligibility.game(
                            rs.getLong("story_id"),
                            snapshot,
                            rs.getLong("first_id"),
                            rs.getLong("id"),
                            (Long) rs.getObject("assessment_id"),
                            Use.EVIDENCE))
                        found.add(
                                new Source(
                                        "SOLO",
                                        rs.getLong("id"),
                                        rs.getLong("review_id"),
                                        rs.getString("role_a"),
                                        rs.getString("role_b"),
                                        rs.getLong("first_id"),
                                        -rs.getLong("id"),
                                        rs.getString("source_hash"),
                                        rs.getTimestamp("reviewed_at").toInstant()));
                },
                version,
                snapshot,
                runtime,
                epoch,
                config);
        return found;
    }

    private String hash(List<Source> sources) {
        return SnapshotJson.hash(
                json.valueToTree(
                        sources.stream()
                                .map(
                                        s ->
                                                Map.of(
                                                        "kind",
                                                        s.kind(),
                                                        "id",
                                                        s.id(),
                                                        "reviewId",
                                                        s.reviewId(),
                                                        "a",
                                                        s.a(),
                                                        "b",
                                                        s.b(),
                                                        "first",
                                                        s.first(),
                                                        "second",
                                                        s.second(),
                                                        "sourceHash",
                                                        s.fingerprint()))
                                .toList()));
    }
}
