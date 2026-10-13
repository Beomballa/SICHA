package com.reasoning.common.story.service;

import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.repository.BlindEligibilityReader;
import com.reasoning.common.story.repository.BlindEligibilityReader.Use;

import org.postgresql.util.PSQLException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 실제 검수자의 확인 기록이다. 문자열 근거 검사만으로 실제 비참여를 증명하지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class BlindEligibilityService {
    private final JdbcTemplate db;
    private final StoryService stories;
    private final PlaytestPolicyGate policy;
    private final BlindEligibilityReader reader;

    public BlindEligibilityService(
            JdbcTemplate db, StoryService stories, PlaytestPolicyGate policy) {
        this.db = db;
        this.stories = stories;
        this.policy = policy;
        this.reader = new BlindEligibilityReader(db);
    }

    public record Assessment(
            UUID assessmentKey,
            String decision,
            String reasonCode,
            long snapshotId,
            UUID memberKey,
            Instant checkedAt,
            Instant recordedAt,
            Instant validUntil,
            Instant revokedAt,
            boolean current,
            UUID requestId) {}

    private record Member(long id, UUID key) {}

    private record Row(
            long id,
            UUID key,
            long storyId,
            long versionId,
            long snapshotId,
            long memberId,
            String decision,
            String reason,
            Instant checked,
            Instant recorded,
            Instant until,
            Instant revoked) {}

    private record Receipt(long actor, String action, String scope, String hash, UUID key) {}

    /** 입력 제한: 참조는 비개인 ASCII 1~160자; 이유는 서버 고정 코드만 허용한다. */
    public Assessment create(
            String sid,
            AdminActor actor,
            String code,
            int number,
            long expectedRev,
            long snapshotId,
            UUID memberKey,
            String decision,
            String reasonCode,
            String verificationRef,
            Instant checkedAt,
            boolean confirmed,
            UUID supersedesKey,
            UUID requestKey,
            UUID requestId) {
        try {
            return createOnce(
                    sid,
                    actor,
                    code,
                    number,
                    expectedRev,
                    snapshotId,
                    memberKey,
                    decision,
                    reasonCode,
                    verificationRef,
                    checkedAt,
                    confirmed,
                    supersedesKey,
                    requestKey,
                    requestId);
        } catch (DataAccessException failure) {
            if (!requestKeyCollision(failure)) throw unavailable();
            return createOnce(
                    sid,
                    actor,
                    code,
                    number,
                    expectedRev,
                    snapshotId,
                    memberKey,
                    decision,
                    reasonCode,
                    verificationRef,
                    checkedAt,
                    confirmed,
                    supersedesKey,
                    requestKey,
                    requestId);
        }
    }

    private Assessment createOnce(
            String sid,
            AdminActor actor,
            String code,
            int number,
            long expectedRev,
            long snapshotId,
            UUID memberKey,
            String decision,
            String reasonCode,
            String verificationRef,
            Instant checkedAt,
            boolean confirmed,
            UUID supersedesKey,
            UUID requestKey,
            UUID requestId) {
        if (!confirmed
                || expectedRev < 0
                || snapshotId <= 0
                || memberKey == null
                || decision == null
                || reasonCode == null
                || !Set.of("ELIGIBLE", "INELIGIBLE").contains(decision)
                || !("ELIGIBLE".equals(decision)
                        ? "NON_PARTICIPATION_CONFIRMED".equals(reasonCode)
                        : Set.of("PARTICIPATION_FOUND", "EVIDENCE_UNVERIFIED").contains(reasonCode))
                || !reference(verificationRef)
                || checkedAt == null
                || requestKey == null
                || requestId == null) throw AuthException.badRequest("INVALID_REQUEST");
        String hash =
                digest(
                        expectedRev
                                + ":"
                                + snapshotId
                                + ":"
                                + memberKey
                                + ":"
                                + decision
                                + ":"
                                + reasonCode
                                + ":"
                                + verificationRef
                                + ":"
                                + checkedAt
                                + ":"
                                + supersedesKey
                                + ":true");
        PlaytestPolicyGate.Snapshot evidence = policy.prepare();
        return stories.withBatchAction(
                sid,
                actor,
                code,
                number,
                List.of(evidence.owner()),
                () -> {
                    Member member = lockMember(memberKey);
                    policy.lock(evidence, Set.of(evidence.owner()));
                    return member;
                },
                (scope, member) -> {
                    String scopeKey = "version:" + scope.versionId();
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        checkReceipt(prior, actor, "BLIND_ASSESSMENT_CREATE", scopeKey, hash);
                        if (prior.key() == null)
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        return view(row(prior.key(), scope), member.key(), scope, requestId);
                    }
                    if (!"REVIEW".equals(scope.status())
                            || scope.snapshotId() == null
                            || scope.snapshotId() != snapshotId)
                        throw AuthException.conflict("BLIND_INELIGIBLE");
                    if (scope.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    Instant now = now();
                    if (checkedAt.isAfter(now)
                            || checkedAt.isBefore(now.minus(Duration.ofHours(24))))
                        throw AuthException.badRequest("INVALID_REQUEST");
                    Row preceding = latest(scope.storyId(), member.id());
                    if (preceding != null
                            && (supersedesKey == null || !supersedesKey.equals(preceding.key())))
                        throw AuthException.conflict("BLIND_INELIGIBLE");
                    if (preceding == null && supersedesKey != null)
                        throw AuthException.conflict("BLIND_INELIGIBLE");
                    if (preceding != null && preceding.revoked() != null)
                        throw AuthException.conflict("BLIND_INELIGIBLE");
                    if ("ELIGIBLE".equals(decision)
                            && !reader.unoccupied(scope.storyId(), member.id()))
                        throw AuthException.conflict("BLIND_INELIGIBLE");
                    UUID key = UUID.randomUUID();
                    String target = "assessment:" + key;
                    long action =
                            insertAction(
                                    requestKey,
                                    actor.accountId(),
                                    "BLIND_ASSESSMENT_CREATE",
                                    scopeKey,
                                    hash,
                                    key);
                    long audit =
                            insertAudit(
                                    actor.accountId(),
                                    "BLIND_ASSESSMENT_CREATE",
                                    target,
                                    requestId,
                                    "CREATED",
                                    reasonCode,
                                    verificationRef);
                    Long id =
                            db.queryForObject(
                                    """
                                    INSERT INTO blind_assessment(assessment_key,story_id,version_id,snapshot_id,member_id,
                                      assessed_by,decision,reason_code,verification_ref,checked_at,valid_until,
                                      supersedes_id,action_id,audit_id)
                                    VALUES (?,?,?,?,?,?,?,?,?,?,?::timestamptz+interval '7 days',?,?,?) RETURNING id
                                    """,
                                    Long.class,
                                    key,
                                    scope.storyId(),
                                    scope.versionId(),
                                    snapshotId,
                                    member.id(),
                                    actor.accountId(),
                                    decision,
                                    reasonCode,
                                    verificationRef,
                                    Timestamp.from(checkedAt),
                                    Timestamp.from(checkedAt),
                                    preceding == null ? null : preceding.id(),
                                    action,
                                    audit);
                    if (id == null) throw unavailable();
                    return view(row(key, scope), member.key(), scope, requestId);
                });
    }

    /** 현재 REVIEW 권위와 회원 잠금 뒤 제한된 사실 및 현재 효력을 읽는다. */
    public Assessment detail(
            String sid,
            AdminActor actor,
            String code,
            int number,
            UUID assessmentKey,
            UUID requestId) {
        if (assessmentKey == null || requestId == null)
            throw AuthException.badRequest("INVALID_REQUEST");
        Long memberId =
                db.query(
                        "SELECT member_id FROM blind_assessment WHERE assessment_key=?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        assessmentKey);
        return stories.withBatchAction(
                sid,
                actor,
                code,
                number,
                List.of(),
                () -> lockMemberId(memberId),
                (scope, member) -> view(row(assessmentKey, scope), member.key(), scope, requestId));
    }

    /** 회수는 활성 PUBLISHED에서도 가능하며 기록은 단회만 바뀐다. */
    public Assessment revoke(
            String sid,
            AdminActor actor,
            String code,
            int number,
            UUID assessmentKey,
            long expectedRev,
            String reasonCode,
            String verificationRef,
            UUID requestKey,
            UUID requestId) {
        try {
            return revokeOnce(
                    sid,
                    actor,
                    code,
                    number,
                    assessmentKey,
                    expectedRev,
                    reasonCode,
                    verificationRef,
                    requestKey,
                    requestId);
        } catch (DataAccessException failure) {
            if (!requestKeyCollision(failure)) throw unavailable();
            return revokeOnce(
                    sid,
                    actor,
                    code,
                    number,
                    assessmentKey,
                    expectedRev,
                    reasonCode,
                    verificationRef,
                    requestKey,
                    requestId);
        }
    }

    private Assessment revokeOnce(
            String sid,
            AdminActor actor,
            String code,
            int number,
            UUID assessmentKey,
            long expectedRev,
            String reasonCode,
            String verificationRef,
            UUID requestKey,
            UUID requestId) {
        if (assessmentKey == null
                || expectedRev < 0
                || requestKey == null
                || requestId == null
                || reasonCode == null
                || !Set.of("EVIDENCE_INVALID", "PARTICIPATION_FOUND", "ASSESSMENT_ERROR")
                        .contains(reasonCode)
                || !reference(verificationRef)) throw AuthException.badRequest("INVALID_REQUEST");
        String hash =
                digest(
                        assessmentKey
                                + ":"
                                + expectedRev
                                + ":"
                                + reasonCode
                                + ":"
                                + verificationRef);
        Long memberId =
                db.query(
                        "SELECT member_id FROM blind_assessment WHERE assessment_key=?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        assessmentKey);
        return stories.withBatchAction(
                sid,
                actor,
                code,
                number,
                List.of(),
                () -> lockMemberId(memberId),
                (scope, member) -> {
                    Row assessment = row(assessmentKey, scope);
                    String target = "assessment:" + assessmentKey;
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        checkReceipt(prior, actor, "BLIND_ASSESSMENT_REVOKE", target, hash);
                        if (!assessmentKey.equals(prior.key()))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        return view(assessment, member.key(), scope, requestId);
                    }
                    if (scope.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    if (assessment.revoked() != null)
                        throw AuthException.conflict("BLIND_INELIGIBLE");
                    long action =
                            insertAction(
                                    requestKey,
                                    actor.accountId(),
                                    "BLIND_ASSESSMENT_REVOKE",
                                    target,
                                    hash,
                                    assessmentKey);
                    long audit =
                            insertAudit(
                                    actor.accountId(),
                                    "BLIND_ASSESSMENT_REVOKE",
                                    target,
                                    requestId,
                                    "REVOKED",
                                    reasonCode,
                                    verificationRef);
                    if (db.update(
                                    """
                                    UPDATE blind_assessment SET revoked_at=now(),revoked_by=?,revoke_reason_code=?,
                                      revoke_verification_ref=?,revoke_action_id=?,revoke_audit_id=?
                                    WHERE id=? AND revoked_at IS NULL
                                    """,
                                    actor.accountId(),
                                    reasonCode,
                                    verificationRef,
                                    action,
                                    audit,
                                    assessment.id())
                            != 1) throw unavailable();
                    return view(row(assessmentKey, scope), member.key(), scope, requestId);
                });
    }

    /** 호출자는 정렬 관리자→회원→story/version 부모 잠금과 쓰기 거래를 보유해야 한다. 신규 입장 전 정확한 현행 평가를 선택한다. */
    public long requireCurrentForAdmission(long storyId, long snapshotId, long memberId) {
        requireTransaction();
        Long current = reader.currentForAdmission(storyId, snapshotId, memberId);
        if (current == null) throw AuthException.conflict("BLIND_INELIGIBLE");
        return current;
    }

    /** 이미 잠긴 TEST의 평가 pin을 수락 전 재검사한다. 선언은 수락 거래에서 별도로 받아야 한다. */
    public void requirePinnedTestAdmission(
            long assessmentId, long storyId, long snapshotId, long memberId, long testId) {
        requireTransaction();
        if (!reader.test(storyId, snapshotId, memberId, testId, assessmentId, Use.ADMISSION))
            throw AuthException.conflict("BLIND_INELIGIBLE");
    }

    /** 이미 잠긴 SOLO 방의 평가 pin을 수락 전 재검사한다. 자기 WAITING 예약은 장애가 아니다. */
    public void requirePinnedGameAdmission(
            long assessmentId, long storyId, long snapshotId, long memberId, long roomId) {
        requireTransaction();
        if (!reader.game(storyId, snapshotId, memberId, roomId, assessmentId, Use.ADMISSION))
            throw AuthException.conflict("BLIND_INELIGIBLE");
    }

    /** TEST 원문 제공 전 또는 자기 출처의 반복 조회에서 DB 최초노출 시간·출처를 검사한다. */
    public void requirePinnedTestExposure(
            long assessmentId, long storyId, long snapshotId, long memberId, long testId) {
        requireTransaction();
        if (!reader.test(storyId, snapshotId, memberId, testId, assessmentId, Use.EXECUTION))
            throw AuthException.conflict("BLIND_INELIGIBLE");
    }

    /** SOLO 원문 제공 전 또는 자기 출처의 반복 조회에서 DB 최초노출 시간·출처를 검사한다. */
    public void requirePinnedGameExposure(
            long assessmentId, long storyId, long snapshotId, long memberId, long roomId) {
        requireTransaction();
        if (!reader.game(storyId, snapshotId, memberId, roomId, assessmentId, Use.EXECUTION))
            throw AuthException.conflict("BLIND_INELIGIBLE");
    }

    private Row latest(long story, long member) {
        return db.query(
                """
                SELECT id,assessment_key,story_id,version_id,snapshot_id,member_id,decision,reason_code,
                  checked_at,recorded_at,valid_until,revoked_at
                FROM blind_assessment WHERE story_id=? AND member_id=? ORDER BY id DESC LIMIT 1
                """,
                rs -> rs.next() ? map(rs) : null,
                story,
                member);
    }

    private Row row(UUID key, StoryService.ReviewScope scope) {
        Row result =
                db.query(
                        """
                        SELECT id,assessment_key,story_id,version_id,snapshot_id,member_id,decision,reason_code,
                          checked_at,recorded_at,valid_until,revoked_at
                        FROM blind_assessment WHERE assessment_key=? AND story_id=? AND version_id=? FOR UPDATE
                        """,
                        rs -> rs.next() ? map(rs) : null,
                        key,
                        scope.storyId(),
                        scope.versionId());
        if (result == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return result;
    }

    private static Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(
                rs.getLong(1),
                rs.getObject(2, UUID.class),
                rs.getLong(3),
                rs.getLong(4),
                rs.getLong(5),
                rs.getLong(6),
                rs.getString(7),
                rs.getString(8),
                rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11).toInstant(),
                rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant());
    }

    private Assessment view(
            Row row, UUID memberKey, StoryService.ReviewScope scope, UUID requestId) {
        boolean current =
                scope.snapshotId() != null
                        && scope.snapshotId() == row.snapshotId()
                        && Long.valueOf(row.id())
                                .equals(
                                        reader.currentForAdmission(
                                                row.storyId(), row.snapshotId(), row.memberId()));
        return new Assessment(
                row.key(),
                row.decision(),
                row.reason(),
                row.snapshotId(),
                memberKey,
                row.checked(),
                row.recorded(),
                row.until(),
                row.revoked(),
                current,
                requestId);
    }

    private Member lockMember(UUID key) {
        List<Member> members =
                db.query(
                        "SELECT id,member_key FROM member_account WHERE member_key=? AND"
                                + " state='ACTIVE'",
                        (rs, n) -> new Member(rs.getLong(1), rs.getObject(2, UUID.class)),
                        key);
        if (members.size() != 1) throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
        return lockMemberId(members.getFirst().id());
    }

    private Member lockMemberId(Long id) {
        if (id == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        List<Member> members =
                db.query(
                        "SELECT id,member_key FROM member_account WHERE id=? AND state='ACTIVE' FOR"
                                + " UPDATE",
                        (rs, n) -> new Member(rs.getLong(1), rs.getObject(2, UUID.class)),
                        id);
        if (members.size() != 1) throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
        var local =
                db.query(
                        """
                        SELECT id FROM member_identity WHERE member_id=? AND provider='LOCAL' AND realm='LOCAL'
                          AND active_yn AND proof_at IS NOT NULL FOR UPDATE
                        """,
                        (rs, n) -> rs.getLong(1),
                        id);
        if (local.size() != 1) throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
        return members.getFirst();
    }

    private Receipt receipt(UUID key) {
        return db.query(
                "SELECT admin_id,action,scope_key,request_hash,result_data->>'assessmentKey' FROM"
                        + " test_action WHERE request_key=?",
                rs ->
                        rs.next()
                                ? new Receipt(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5) == null
                                                        || !rs.getString(5)
                                                                .matches(
                                                                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                                                ? null
                                                : UUID.fromString(rs.getString(5)))
                                : null,
                key);
    }

    private static void checkReceipt(
            Receipt r, AdminActor actor, String action, String scope, String hash) {
        if (r.actor() != actor.accountId()
                || !r.action().equals(action)
                || !r.scope().equals(scope)
                || !r.hash().equals(hash)) throw AuthException.conflict("REQUEST_KEY_CONFLICT");
    }

    private long insertAction(
            UUID requestKey, long admin, String action, String scope, String hash, UUID key) {
        Long id =
                db.queryForObject(
                        """
                        INSERT INTO test_action(request_key,admin_id,action,scope_key,request_hash,result_data)
                          VALUES (?,?,?,?,?,jsonb_build_object('assessmentKey',?::text)) RETURNING id
                        """,
                        Long.class,
                        requestKey,
                        admin,
                        action,
                        scope,
                        hash,
                        key.toString());
        if (id == null) throw unavailable();
        return id;
    }

    private long insertAudit(
            long admin,
            String action,
            String scope,
            UUID requestId,
            String result,
            String reason,
            String ref) {
        Long id =
                db.queryForObject(
                        """
                        INSERT INTO test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,
                          request_id,phase,business_result,detail)
                        VALUES (?,'ADMIN',?,?,'ASSESSMENT',?,?,'RESULT',?,
                          jsonb_build_object('reasonCode',?::text,'verificationRef',?::text)) RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        Long.toString(admin),
                        action,
                        scope,
                        requestId,
                        result,
                        reason,
                        ref);
        if (id == null) throw unavailable();
        return id;
    }

    private static String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean reference(String ref) {
        return ref != null && ref.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");
    }

    private static boolean requestKeyCollision(DataAccessException failure) {
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

    private Instant now() {
        return db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw unavailable();
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("STORY_UNAVAILABLE");
    }
}
