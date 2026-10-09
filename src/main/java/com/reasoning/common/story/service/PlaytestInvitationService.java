package com.reasoning.common.story.service;

import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;

import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** PLAYTEST invitation and consent boundary; gameplay and data collection remain separate. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PlaytestInvitationService {
    private final StoryService stories;
    private final GradeRuntimeRepository runtimes;
    private final JdbcTemplate db;
    private final PlaytestPolicyGate policy;
    private final MemberPolicyGate memberPolicy;
    private final MemberAuthService auth;
    private final CryptoService crypto;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final TransactionTemplate tx;

    @Autowired
    public PlaytestInvitationService(
            StoryService stories,
            GradeRuntimeRepository runtimes,
            JdbcTemplate db,
            PlaytestPolicyGate policy,
            MemberPolicyGate memberPolicy,
            MemberAuthService auth,
            CryptoService crypto,
            @Qualifier("gradeInstallations")
                    Map<String, InstalledRuntimeManifestVerifier> installations) {
        this.stories = stories;
        this.runtimes = runtimes;
        this.db = db;
        this.policy = policy;
        this.memberPolicy = memberPolicy;
        this.auth = auth;
        this.crypto = crypto;
        this.installations = Map.copyOf(installations);
        this.tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // Existing format-only tests do not enter the transaction or require a database.
    PlaytestInvitationService(
            StoryService stories, GradeRuntimeRepository runtimes, JdbcTemplate db) {
        this.stories = stories;
        this.runtimes = runtimes;
        this.db = db;
        this.policy = null;
        this.memberPolicy = null;
        this.auth = null;
        this.crypto = null;
        this.tx = null;
        this.installations = Map.of();
    }

    public record Invitation(
            UUID testKey,
            String state,
            long revision,
            Instant inviteUntil,
            long snapshotId,
            long runtimeId,
            String configHash,
            long runtimeEpoch,
            List<UUID> members,
            UUID requestId) {}

    public record AdminSummary(
            UUID testKey,
            String snapshotId,
            String runtimeConfigId,
            long runtimeEpoch,
            List<String> pair,
            String mode,
            String state,
            String outcome,
            String rev,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt,
            int completedFeedbackCount,
            String reviewState) {}

    public record AdminMember(
            int slot,
            UUID memberKey,
            int inviteGen,
            String inviteStatus,
            Instant acceptedAt,
            boolean ready,
            String roleCode,
            Instant revokedAt,
            boolean blindStatus) {}

    public record AdminDetail(
            UUID testKey,
            String snapshotId,
            String runtimeConfigId,
            long runtimeEpoch,
            List<String> pair,
            String mode,
            String state,
            String outcome,
            String rev,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt,
            int completedFeedbackCount,
            String reviewState,
            List<AdminMember> members,
            Instant inviteExpiresAt,
            Instant lobbyExpiresAt,
            Instant playDeadline,
            Instant feedbackUntil,
            int attemptCount,
            int wrongCount,
            Integer finalScore,
            UUID requestId) {}

    public record AdminPage(List<AdminSummary> items, String nextCursor, UUID requestId) {}

    public record ActionResult(
            String action,
            boolean replayed,
            boolean changed,
            Map<String, Object> original,
            Map<String, Object> current,
            UUID requestId) {}

    public record MemberInvitation(
            UUID testKey,
            int inviteGen,
            String title,
            String mode,
            int limitSec,
            Instant inviteExpiresAt,
            String inviteStatus,
            String policyCode,
            String noticeHash,
            String noticeSummary) {}

    public record InvitationPage(List<MemberInvitation> items, String nextCursor, UUID requestId) {}

    public record SelfState(
            int slot,
            int inviteGen,
            boolean accepted,
            boolean ready,
            String roleCode,
            boolean blindStatus) {}

    public record PartnerState(boolean accepted, boolean ready, boolean online) {}

    public record TestState(
            UUID testKey,
            String title,
            String mode,
            String state,
            String outcome,
            String rev,
            String draftRev,
            String snapshotRef,
            String runtimeConfigId,
            SelfState self,
            PartnerState partner,
            Instant inviteExpiresAt,
            Instant lobbyExpiresAt,
            Instant startedAt,
            Instant playDeadline,
            Instant serverTime,
            String submissionState,
            Integer attemptsRemaining,
            Integer hintsRemaining,
            Instant feedbackUntil,
            boolean feedbackSubmitted,
            String resultPhase,
            UUID requestId) {}

    public record ConsentNotice(
            UUID testKey,
            int generation,
            String revision,
            String policyCode,
            String noticeHash,
            String version,
            String body,
            String contact,
            UUID requestId) {}

    public record MemberIdentity(UUID memberKey, UUID requestId) {}

    private record Result<T>(T value, AuthException denial) {
        static <T> Result<T> allow(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> deny(AuthException denial) {
            return new Result<>(null, denial);
        }

        T unwrap() {
            if (denial != null) throw denial;
            return value;
        }
    }

    public MemberIdentity getMemberIdentity(String accessToken, UUID requestId) {
        uuid(requestId);
        MemberAuthService.MemberPrincipal principal = auth.authenticate(accessToken, false);
        return tx.execute(
                status -> {
                    memberBound(accessToken, principal);
                    return new MemberIdentity(principal.memberKey(), requestId);
                });
    }

    /** 인증한 LOCAL 회원에게 현재 REVIEW 사본에 연결된 자신의 유효한 초대만 반환한다. */
    public InvitationPage getMemberInvitationList(
            String accessToken, Long beforeId, int size, UUID requestId) {
        uuid(requestId);
        if (size < 1 || size > 100 || beforeId != null && beforeId <= 0)
            throw AuthException.badRequest("INVALID_REQUEST");
        MemberAuthService.MemberPrincipal principal = auth.authenticate(accessToken, false);
        return tx.execute(
                status -> {
                    memberBound(accessToken, principal);
                    List<MemberItem> rows =
                            db.query(
                                    """
                                    SELECT t.id,t.test_key,m.invite_gen,snap.payload->'sections'->'basic'->>'title',
                                      t.mode,(snap.payload->'sections'->'basic'->>'limitSec')::int,t.invite_until,
                                      m.invite_state,p.code,p.notice_hash,p.policy_data->'notice'->>'body'
                                    FROM test_member m JOIN play_test t ON t.id=m.test_id
                                    JOIN story_version v ON v.id=t.version_id JOIN story s ON s.id=v.story_id
                                    JOIN review_snapshot snap ON snap.id=t.snapshot_id
                                    JOIN test_retention retention ON retention.test_id=t.id
                                    JOIN privacy_policy p ON p.id=retention.policy_id
                                    WHERE m.member_id=? AND (?::bigint IS NULL OR t.id<?) AND m.invite_state IN ('SENT','ACCEPTED')
                                      AND t.state='WAITING' AND t.invite_until>clock_timestamp()
                                      AND (t.ready_until IS NULL OR t.ready_until>clock_timestamp())
                                      AND s.active_yn AND v.active_yn AND v.status='REVIEW'
                                      AND v.current_snapshot_id=t.snapshot_id
                                    ORDER BY t.id DESC LIMIT ?
                                    """,
                                    (rs, n) ->
                                            new MemberItem(
                                                    rs.getLong(1),
                                                    new MemberInvitation(
                                                            rs.getObject(2, UUID.class),
                                                            rs.getInt(3),
                                                            rs.getString(4),
                                                            rs.getString(5),
                                                            rs.getInt(6),
                                                            rs.getTimestamp(7).toInstant(),
                                                            rs.getString(8),
                                                            rs.getString(9),
                                                            rs.getString(10),
                                                            noticeSummary(rs.getString(11)))),
                                    principal.memberId(),
                                    beforeId,
                                    beforeId,
                                    size + 1);
                    // The cursor is the physical parent ID, never a member or partner identifier.
                    return new InvitationPage(
                            rows.stream().limit(size).map(MemberItem::invitation).toList(),
                            rows.size() > size ? Long.toString(rows.get(size - 1).id()) : null,
                            requestId);
                });
    }

    /** 현재 참여자의 대기·진행·종료 메타데이터만 반환하며 종료 뒤에도 상대 식별자를 공개하지 않는다. */
    public TestState getMemberInvitationDetail(String accessToken, UUID testKey, UUID requestId) {
        uuid(testKey);
        uuid(requestId);
        MemberAuthService.MemberPrincipal principal = auth.authenticate(accessToken, false);
        return tx.execute(
                        status -> {
                            memberBound(accessToken, principal);
                            TestTarget target = target(testKey, principal.memberId());
                            requireReview(target);
                            AuthException expired =
                                    PlaytestAccess.normalizeTime(
                                            db,
                                            crypto,
                                            target.id(),
                                            "MEMBER",
                                            principal.memberKey().toString(),
                                            requestId);
                            if (expired != null && "INVITATION_EXPIRED".equals(expired.code()))
                                return Result.<TestState>deny(expired);
                            String state =
                                    db.queryForObject(
                                            "SELECT state FROM play_test WHERE id=?",
                                            String.class,
                                            target.id());
                            if ("WAITING".equals(state)) requireWaiting(target.id());
                            else if (!Set.of("RUNNING", "ENDED").contains(state)
                                    || !"ACCEPTED"
                                            .equals(
                                                    memberInviteStatus(
                                                            testKey, principal.memberId())))
                                throw AuthException.conflict("STATE_CONFLICT");
                            return Result.allow(
                                    memberState(target.id(), principal.memberId(), requestId));
                        })
                .unwrap();
    }

    public ConsentNotice getConsentNotice(String accessToken, UUID testKey, UUID requestId) {
        uuid(testKey);
        uuid(requestId);
        MemberAuthService.MemberPrincipal principal = auth.authenticate(accessToken, false);
        PlaytestPolicyGate.Snapshot evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                        status -> {
                            Set<Long> owners =
                                    lockPolicyOwners(principal.memberId(), evidence.owner());
                            memberBound(accessToken, principal);
                            MemberPolicyGate.Permit permit =
                                    memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                            PlaytestPolicyGate.Notice notice = policy.lock(evidence, owners);
                            TestTarget current = target(testKey, principal.memberId());
                            requireReview(current);
                            AuthException expired =
                                    PlaytestAccess.normalizeTime(
                                            db,
                                            crypto,
                                            current.id(),
                                            "MEMBER",
                                            principal.memberKey().toString(),
                                            requestId);
                            if (expired != null) return Result.<ConsentNotice>deny(expired);
                            requireWaiting(current.id());
                            TestState invitation =
                                    memberState(current.id(), principal.memberId(), requestId);
                            if (!"SENT".equals(memberInviteStatus(testKey, principal.memberId()))
                                    || !invitation.inviteExpiresAt().isAfter(now()))
                                throw AuthException.conflict("INVITATION_INVALIDATED");
                            Long pinned =
                                    db.query(
                                            "SELECT r.policy_id FROM test_retention r JOIN"
                                                    + " play_test t ON t.id=r.test_id WHERE"
                                                    + " t.test_key=?",
                                            rs -> rs.next() ? rs.getLong(1) : null,
                                            testKey);
                            if (pinned == null || pinned != notice.policyId())
                                throw AuthException.conflict("POLICY_CHANGED");
                            checkConsentPolicies(
                                    permit,
                                    principal.memberId(),
                                    evidence,
                                    owners,
                                    notice.policyId());
                            return Result.allow(
                                    new ConsentNotice(
                                            testKey,
                                            invitation.self().inviteGen(),
                                            invitation.rev(),
                                            notice.code(),
                                            notice.hash(),
                                            notice.version(),
                                            notice.body(),
                                            notice.contact(),
                                            requestId));
                        })
                .unwrap();
    }

    /**
     * 회원 동의·영수증·감사를 한 거래로 확정하며 같은 의도의 재전송은 원래 결과를 반환한다.
     *
     * @param accessToken 현재 LOCAL 회원의 접근 토큰
     * @param testKey 대상 초대의 UUID
     * @param generation 양의 초대 세대
     * @param expectedRevision 음수가 아닌 현재 수정번호
     * @param policyCode 고지의 정책 코드
     * @param noticeHash 고지 내용의 해시
     * @param requestKey 전역 요청 UUID
     * @param requestId 서버 요청 UUID
     * @return 최초 수락 또는 동일 영수증 재전송의 안전한 결과
     * @throws AuthException 인증·부모 REVIEW·초대 상태·영수증 의도가 맞지 않을 때
     */
    public ActionResult acceptInvitation(
            String accessToken,
            UUID testKey,
            int generation,
            long expectedRevision,
            String policyCode,
            String noticeHash,
            UUID requestKey,
            UUID requestId) {
        uuid(testKey);
        uuid(requestKey);
        uuid(requestId);
        if (generation < 1
                || expectedRevision < 0
                || expectedRevision == Long.MAX_VALUE
                || policyCode == null
                || noticeHash == null) throw AuthException.badRequest("INVALID_REQUEST");
        MemberAuthService.MemberPrincipal principal = auth.authenticate(accessToken, false);
        PlaytestPolicyGate.Snapshot evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        try {
            return tx.execute(
                            status -> {
                                Set<Long> owners =
                                        lockPolicyOwners(principal.memberId(), evidence.owner());
                                memberBound(accessToken, principal);
                                MemberPolicyGate.Permit permit =
                                        memberPolicy.lock(
                                                memberEvidence, principal.memberId(), owners);
                                PlaytestPolicyGate.Notice notice = policy.lock(evidence, owners);
                                TestTarget target = target(testKey, principal.memberId());
                                String scope = "test:" + testKey;
                                String hash =
                                        digest(
                                                testKey
                                                        + ":"
                                                        + generation
                                                        + ":"
                                                        + expectedRevision
                                                        + ":"
                                                        + policyCode
                                                        + ":"
                                                        + noticeHash);
                                Receipt prior = receipt(requestKey);
                                requireReview(target);
                                if (prior != null) {
                                    checkMemberReceipt(prior, principal.memberId(), scope, hash);
                                    requireAcceptedReplay(
                                            target.id(),
                                            principal.memberId(),
                                            generation,
                                            notice,
                                            policyCode,
                                            noticeHash);
                                    AuthException expired =
                                            PlaytestAccess.normalizeTime(
                                                    db,
                                                    crypto,
                                                    target.id(),
                                                    "MEMBER",
                                                    principal.memberKey().toString(),
                                                    requestId);
                                    if (expired != null
                                            && "INVITATION_EXPIRED".equals(expired.code()))
                                        return Result.<ActionResult>deny(expired);
                                    ActionResult replay =
                                            new ActionResult(
                                                    "INVITATION_ACCEPT",
                                                    true,
                                                    false,
                                                    actionState(
                                                            testKey,
                                                            prior.rev(),
                                                            prior.state(),
                                                            prior.inviteUntil()),
                                                    currentState(testKey),
                                                    requestId);
                                    checkConsentPolicies(
                                            permit,
                                            principal.memberId(),
                                            evidence,
                                            owners,
                                            notice.policyId());
                                    return Result.allow(replay);
                                }
                                AuthException expired =
                                        PlaytestAccess.normalizeTime(
                                                db,
                                                crypto,
                                                target.id(),
                                                "MEMBER",
                                                principal.memberKey().toString(),
                                                requestId);
                                if (expired != null) return Result.<ActionResult>deny(expired);
                                if (!notice.code().equals(policyCode)
                                        || !notice.hash().equals(noticeHash))
                                    throw AuthException.conflict("POLICY_CHANGED");
                                requireWaiting(target.id());
                                long actualRev =
                                        db.queryForObject(
                                                "SELECT rev FROM play_test WHERE id=?",
                                                Long.class,
                                                target.id());
                                if (actualRev != expectedRevision)
                                    throw AuthException.conflict("EDIT_CONFLICT");
                                Long valid =
                                        db.query(
                                                "SELECT id FROM play_test WHERE id=? AND"
                                                        + " state='WAITING' AND rev=? AND"
                                                        + " invite_until>clock_timestamp()",
                                                rs -> rs.next() ? rs.getLong(1) : null,
                                                target.id(),
                                                expectedRevision);
                                if (valid == null) throw AuthException.conflict("STATE_CONFLICT");
                                var rows =
                                        db.query(
                                                "SELECT invite_gen,invite_state FROM test_member"
                                                    + " WHERE test_id=? AND member_id=? FOR UPDATE",
                                                (rs, n) ->
                                                        new Object[] {
                                                            rs.getInt(1), rs.getString(2)
                                                        },
                                                target.id(),
                                                principal.memberId());
                                if (rows.size() != 1) throw missing();
                                var row = rows.getFirst();
                                if ((int) row[0] != generation || !"SENT".equals(row[1]))
                                    throw AuthException.conflict("STATE_CONFLICT");
                                Long pinned =
                                        db.query(
                                                "SELECT policy_id FROM test_retention WHERE"
                                                        + " test_id=?",
                                                rs -> rs.next() ? rs.getLong(1) : null,
                                                target.id());
                                if (pinned == null || pinned != notice.policyId())
                                    throw AuthException.conflict("POLICY_CHANGED");
                                if (db.update(
                                                """
                                                UPDATE test_member SET invite_state='ACCEPTED',accepted_at=clock_timestamp(),
                                                accepted_policy_id=?,accepted_notice_hash=?,updated_at=clock_timestamp()
                                                WHERE test_id=? AND member_id=? AND invite_gen=? AND invite_state='SENT'
                                                """,
                                                notice.policyId(),
                                                notice.hash(),
                                                target.id(),
                                                principal.memberId(),
                                                generation)
                                        != 1) throw unavailable();
                                if (db.update(
                                                """
                                                UPDATE play_test SET rev=rev+1,updated_at=clock_timestamp(),
                                                  ready_until=CASE WHEN ready_until IS NULL AND
                                                    (SELECT count(*) FROM test_member WHERE test_id=? AND invite_state='ACCEPTED')=2
                                                    THEN LEAST(invite_until,clock_timestamp()+interval '30 minutes')
                                                    ELSE ready_until END
                                                WHERE id=? AND state='WAITING' AND rev=?
                                                """,
                                                target.id(),
                                                target.id(),
                                                expectedRevision)
                                        != 1) throw unavailable();
                                if (db.update(
                                                """
                                                INSERT INTO test_action(request_key,member_id,action,scope_key,request_hash,result_data)
                                                VALUES (?,?,'INVITATION_ACCEPT',?,?,jsonb_build_object('testKey',?::text,
                                                  'rev',?::text,'state','WAITING',
                                                  'inviteExpiresAt',?::timestamptz))
                                                """,
                                                requestKey,
                                                principal.memberId(),
                                                scope,
                                                hash,
                                                testKey.toString(),
                                                expectedRevision + 1,
                                                java.sql.Timestamp.from(target.inviteUntil()))
                                        != 1) throw unavailable();
                                if (db.update(
                                                """
                                                INSERT INTO test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,
                                                request_id,phase,business_result,detail)
                                                VALUES (?,'MEMBER',?,'INVITATION_ACCEPT','PLAYTEST',?,?,'RESULT','ACCEPTED','{}'::jsonb)
                                                """,
                                                UUID.randomUUID(),
                                                principal.memberKey().toString(),
                                                scope,
                                                requestId)
                                        != 1) throw unavailable();
                                Map<String, Object> original =
                                        actionState(
                                                testKey,
                                                Long.toString(expectedRevision + 1),
                                                "WAITING",
                                                target.inviteUntil());
                                checkConsentPolicies(
                                        permit,
                                        principal.memberId(),
                                        evidence,
                                        owners,
                                        notice.policyId());
                                return Result.allow(
                                        new ActionResult(
                                                "INVITATION_ACCEPT",
                                                false,
                                                true,
                                                original,
                                                original,
                                                requestId));
                            })
                    .unwrap();
        } catch (DataAccessException failure) {
            if (!requestKeyCollision(failure)) throw unavailable();
            return tx.execute(
                            status -> {
                                Set<Long> owners =
                                        lockPolicyOwners(principal.memberId(), evidence.owner());
                                memberBound(accessToken, principal);
                                MemberPolicyGate.Permit permit =
                                        memberPolicy.lock(
                                                memberEvidence, principal.memberId(), owners);
                                PlaytestPolicyGate.Notice notice = policy.lock(evidence, owners);
                                TestTarget target = target(testKey, principal.memberId());
                                requireReview(target);
                                String hash =
                                        digest(
                                                testKey
                                                        + ":"
                                                        + generation
                                                        + ":"
                                                        + expectedRevision
                                                        + ":"
                                                        + policyCode
                                                        + ":"
                                                        + noticeHash);
                                Receipt prior = receipt(requestKey);
                                if (prior == null) throw unavailable();
                                checkMemberReceipt(
                                        prior, principal.memberId(), "test:" + testKey, hash);
                                requireAcceptedReplay(
                                        target.id(),
                                        principal.memberId(),
                                        generation,
                                        notice,
                                        policyCode,
                                        noticeHash);
                                AuthException expired =
                                        PlaytestAccess.normalizeTime(
                                                db,
                                                crypto,
                                                target.id(),
                                                "MEMBER",
                                                principal.memberKey().toString(),
                                                requestId);
                                if (expired != null && "INVITATION_EXPIRED".equals(expired.code()))
                                    return Result.<ActionResult>deny(expired);
                                checkConsentPolicies(
                                        permit,
                                        principal.memberId(),
                                        evidence,
                                        owners,
                                        notice.policyId());
                                return Result.allow(
                                        new ActionResult(
                                                "INVITATION_ACCEPT",
                                                true,
                                                false,
                                                actionState(
                                                        testKey,
                                                        prior.rev(),
                                                        prior.state(),
                                                        prior.inviteUntil()),
                                                currentState(testKey),
                                                requestId));
                            })
                    .unwrap();
        }
    }

    private void requireAcceptedReplay(
            long id,
            long memberId,
            int generation,
            PlaytestPolicyGate.Notice notice,
            String policyCode,
            String noticeHash) {
        if (!notice.code().equals(policyCode) || !notice.hash().equals(noticeHash))
            throw AuthException.conflict("POLICY_CHANGED");
        Boolean valid =
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM play_test t JOIN test_member m ON"
                            + " m.test_id=t.id JOIN test_retention r ON r.test_id=t.id WHERE t.id=?"
                            + " AND m.member_id=? AND t.state IN"
                            + " ('WAITING','RUNNING','ENDED','EXPIRED') AND"
                            + " m.invite_state='ACCEPTED' AND m.invite_gen=? AND"
                            + " m.accepted_policy_id=? AND r.policy_id=? AND"
                            + " m.accepted_notice_hash=?)",
                        Boolean.class,
                        id,
                        memberId,
                        generation,
                        notice.policyId(),
                        notice.policyId(),
                        notice.hash());
        if (!Boolean.TRUE.equals(valid)) throw AuthException.conflict("INVITATION_INVALIDATED");
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

    private String memberInviteStatus(UUID key, long memberId) {
        return db.queryForObject(
                "SELECT m.invite_state FROM test_member m JOIN play_test t ON t.id=m.test_id"
                        + " WHERE t.test_key=? AND m.member_id=?",
                String.class,
                key,
                memberId);
    }

    /** 고정 사본과 현재 참가 행에서만 대기 상태를 구성하며 타인 식별자와 비밀은 제외한다. */
    private TestState memberState(long id, long memberId, UUID requestId) {
        return db.queryForObject(
                """
                SELECT t.test_key,snap.payload->'sections'->'basic'->>'title',t.mode,t.state,t.outcome,
                  t.rev,t.draft_rev,t.snapshot_id,r.code,m.slot,m.invite_gen,
                  m.invite_state='ACCEPTED',m.ready_yn,m.role_code,m.blind_declared,
                  other.invite_state='ACCEPTED',other.ready_yn,
                  other.last_seen_at IS NOT NULL AND other.last_seen_at>clock_timestamp()-interval '30 seconds',
                  t.invite_until,t.ready_until,t.started_at,t.deadline_at,clock_timestamp(),
                  t.result_until,t.attempt_count,
                  (snap.payload->'policy'->>'attemptLimit')::int,
                  (snap.payload->'policy'->>'hintsPerPerson')::int,
                  (SELECT count(*) FROM test_hint h WHERE h.test_id=t.id AND h.member_id=m.member_id)
                FROM play_test t JOIN review_snapshot snap ON snap.id=t.snapshot_id
                JOIN grade_runtime r ON r.id=t.runtime_id
                JOIN test_member m ON m.test_id=t.id AND m.member_id=?
                JOIN test_member other ON other.test_id=t.id AND other.slot<>m.slot
                WHERE t.id=?
                """,
                (rs, n) ->
                        new TestState(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                Long.toString(rs.getLong(6)),
                                Long.toString(rs.getLong(7)),
                                Long.toString(rs.getLong(8)),
                                rs.getString(9),
                                new SelfState(
                                        rs.getInt(10),
                                        rs.getInt(11),
                                        rs.getBoolean(12),
                                        rs.getBoolean(13),
                                        rs.getString(14),
                                        rs.getBoolean(15)),
                                new PartnerState(
                                        rs.getBoolean(16), rs.getBoolean(17), rs.getBoolean(18)),
                                rs.getTimestamp(19).toInstant(),
                                rs.getTimestamp(20) == null
                                        ? null
                                        : rs.getTimestamp(20).toInstant(),
                                rs.getTimestamp(21) == null
                                        ? null
                                        : rs.getTimestamp(21).toInstant(),
                                rs.getTimestamp(22) == null
                                        ? null
                                        : rs.getTimestamp(22).toInstant(),
                                rs.getTimestamp(23).toInstant(),
                                "NONE",
                                "RUNNING".equals(rs.getString(4))
                                        ? Math.max(0, rs.getInt(26) - rs.getInt(25))
                                        : null,
                                "RUNNING".equals(rs.getString(4))
                                        ? Math.max(0, rs.getInt(27) - rs.getInt(28))
                                        : null,
                                rs.getTimestamp(24) == null
                                        ? null
                                        : rs.getTimestamp(24).toInstant(),
                                false,
                                "NONE",
                                requestId),
                memberId,
                id);
    }

    private void requireWaiting(long id) {
        String state =
                db.queryForObject("SELECT state FROM play_test WHERE id=?", String.class, id);
        if ("EXPIRED".equals(state))
            throw new AuthException(410, "INVITATION_EXPIRED", "INVITATION_EXPIRED");
        if (!"WAITING".equals(state)) throw AuthException.conflict("STATE_CONFLICT");
    }

    /** 현재 회원 대상의 부모 잠금을 선행해 REVIEW 철회와 수락을 직렬화한다. */
    private TestTarget target(UUID key, long memberId) {
        var ids =
                db.query(
                        """
                        SELECT t.id,t.runtime_id,v.story_id,t.version_id
                        FROM play_test t JOIN story_version v ON v.id=t.version_id
                        JOIN test_member m ON m.test_id=t.id
                        WHERE t.test_key=? AND m.member_id=?
                        """,
                        (rs, n) ->
                                new long[] {
                                    rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)
                                },
                        key,
                        memberId);
        if (ids.size() != 1) throw missing();
        long[] idsRow = ids.getFirst();
        db.query(
                "SELECT id FROM grade_runtime WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                idsRow[1]);
        var story =
                db.query(
                        "SELECT active_yn FROM story WHERE id=? FOR UPDATE",
                        (rs, n) -> rs.getBoolean(1),
                        idsRow[2]);
        var version =
                db.query(
                        """
                        SELECT active_yn,status,current_snapshot_id FROM story_version
                        WHERE id=? AND story_id=? FOR UPDATE
                        """,
                        (rs, n) ->
                                new Object[] {rs.getBoolean(1), rs.getString(2), rs.getObject(3)},
                        idsRow[3],
                        idsRow[2]);
        var test =
                db.query(
                        "SELECT snapshot_id,invite_until FROM play_test WHERE id=? FOR UPDATE",
                        (rs, n) -> new Object[] {rs.getLong(1), rs.getTimestamp(2).toInstant()},
                        idsRow[0]);
        if (story.size() != 1 || version.size() != 1 || test.size() != 1) throw missing();
        var v = version.getFirst();
        var t = test.getFirst();
        return new TestTarget(
                idsRow[0],
                story.getFirst() && (boolean) v[0],
                (String) v[1],
                (Long) v[2],
                (long) t[0],
                (Instant) t[1]);
    }

    /** 활성 사건과 REVIEW의 현재 사본이 초대에 고정된 사본과 일치해야 한다. */
    static void requireReview(TestTarget target) {
        if (!target.active()
                || !"REVIEW".equals(target.status())
                || target.currentSnapshot() == null
                || target.currentSnapshot() != target.snapshotId())
            throw AuthException.conflict("INVITATION_INVALIDATED");
    }

    private Instant now() {
        return db.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
    }

    /**
     * 정책 소유자를 발견한 뒤 회원 행보다 먼저 관리자 계정 전체를 ID순으로 잠근다.
     *
     * @param memberId 인증된 회원의 내부 ID
     * @param playtestOwner PLAYTEST 파일에 고정된 관리자 ID
     * @return 실제로 잠근 관리자 ID 집합
     * @throws AuthException 수집 비활성화 또는 소유자 발견·잠금 실패 시
     */
    private Set<Long> lockPolicyOwners(long memberId, long playtestOwner) {
        TreeSet<Long> owners = new TreeSet<>(memberPolicy.ownerIds(memberId));
        owners.add(playtestOwner);
        for (long owner : owners) {
            var locked =
                    db.query(
                            "SELECT id FROM admin_account WHERE id=? FOR SHARE",
                            (rs, n) -> rs.getLong(1),
                            owner);
            if (locked.size() != 1) throw unavailable();
        }
        return owners;
    }

    /**
     * 같은 거래의 최종 정책 효력과 회원의 고정 정책을 다시 검사한다.
     *
     * @param permit 잠긴 MEMBER_AUTH 정책 허가
     * @param memberId 인증된 회원의 내부 ID
     * @param evidence 잠금 전 준비한 PLAYTEST 근거
     * @param owners 이미 잠긴 관리자 ID 집합
     * @param policyId 초대에 고정한 PLAYTEST 정책 ID
     * @throws AuthException 정책·소유자·수집 상태가 바뀌었을 때
     */
    private void checkConsentPolicies(
            MemberPolicyGate.Permit permit,
            long memberId,
            PlaytestPolicyGate.Snapshot evidence,
            Set<Long> owners,
            long policyId) {
        memberPolicy.checkPinned(permit, memberId);
        memberPolicy.check(permit);
        if (policy.lock(evidence, owners).policyId() != policyId)
            throw AuthException.conflict("POLICY_CHANGED");
    }

    private void memberBound(String token, MemberAuthService.MemberPrincipal principal) {
        String hash = HexFormat.of().formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", token));
        db.query(
                "SELECT id FROM member_account WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                principal.memberId());
        var profile =
                db.query(
                        "SELECT member_id FROM member_profile WHERE member_id=? FOR SHARE",
                        (rs, n) -> rs.getLong(1),
                        principal.memberId());
        if (profile.size() != 1) throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
        db.query(
                "SELECT id FROM member_session WHERE id=? AND member_id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                principal.sessionId(),
                principal.memberId());
        db.query(
                "SELECT i.id FROM member_identity i JOIN member_session s ON s.identity_id=i.id"
                        + " WHERE s.id=? FOR UPDATE OF i",
                (rs, n) -> rs.getLong(1),
                principal.sessionId());
        db.query(
                "SELECT id FROM member_token WHERE token_hash=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                hash);
        Boolean valid =
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM member_account a
                          JOIN member_session s ON s.member_id=a.id
                          JOIN member_identity i ON i.id=s.identity_id AND i.member_id=a.id
                          JOIN member_token t ON t.session_id=s.id
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
                        hash);
        if (!Boolean.TRUE.equals(valid)) throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
    }

    private List<Member> members(List<UUID> keys) {
        return db.query(
                "SELECT id,member_key FROM member_account WHERE member_key IN (?,?) AND"
                        + " state='ACTIVE' ORDER BY id",
                (rs, n) -> new Member(rs.getLong(1), rs.getObject(2, UUID.class)),
                keys.get(0),
                keys.get(1));
    }

    private void lockMembers(List<UUID> keys) {
        if (keys.size() != 2 || keys.contains(null) || keys.get(0).equals(keys.get(1)))
            throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
        List<Member> selected = members(keys);
        if (selected.size() != 2) throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
        for (Member member : selected) {
            var locked =
                    db.query(
                            "SELECT id FROM member_account WHERE id=? AND state='ACTIVE' FOR"
                                    + " UPDATE",
                            (rs, n) -> rs.getLong(1),
                            member.id());
            if (locked.size() != 1) throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
        }
        for (Member member : selected) {
            var identities =
                    db.query(
                            "SELECT id FROM member_identity WHERE member_id=? AND provider='LOCAL'"
                                + " AND realm='LOCAL' AND active_yn AND proof_at IS NOT NULL FOR"
                                + " UPDATE",
                            (rs, n) -> rs.getLong(1),
                            member.id());
            if (identities.size() != 1)
                throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
        }
    }

    private void lockMembersForRevoke(List<UUID> keys) {
        if (keys.size() != 2) throw unavailable();
        List<Member> selected =
                db.query(
                        "SELECT id,member_key FROM member_account WHERE member_key IN (?,?) ORDER"
                                + " BY id",
                        (rs, n) -> new Member(rs.getLong(1), rs.getObject(2, UUID.class)),
                        keys.get(0),
                        keys.get(1));
        if (selected.size() != 2) throw unavailable();
        for (Member member : selected)
            db.query(
                    "SELECT id FROM member_account WHERE id=? FOR UPDATE",
                    (rs, n) -> rs.getLong(1),
                    member.id());
    }

    /** 현재 REVIEW 사본의 초대를 생성하고 부모 범위 영수증으로 재전송을 구분한다. */
    public ActionResult createInvitation(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            long expectedRev,
            long snapshotId,
            String runtimeConfigId,
            String mode,
            String roleA,
            String roleB,
            List<UUID> memberKeys,
            UUID requestKey,
            UUID requestId) {
        uuid(requestKey);
        uuid(requestId);
        if (expectedRev < 0
                || snapshotId <= 0
                || runtimeConfigId == null
                || !runtimeConfigId.matches("[A-Z0-9_]{1,80}")
                || !Set.of("BLIND", "FUNCTIONAL").contains(mode == null ? "" : mode)
                || memberKeys == null
                || memberKeys.size() != 2
                || memberKeys.get(0) == null
                || memberKeys.get(1) == null
                || memberKeys.get(0).equals(memberKeys.get(1))
                || roleA == null
                || roleB == null
                || !roleA.matches("[A-Z0-9_]{1,32}")
                || !roleB.matches("[A-Z0-9_]{1,32}")
                || roleA.compareTo(roleB) >= 0) throw AuthException.badRequest("INVALID_REQUEST");
        // 기존 노출 및 제작 참여를 완전하게 입증할 수 없으면 BLIND 자격을 주장하지 않는다.
        if ("BLIND".equals(mode)) throw AuthException.conflict("BLIND_INELIGIBLE");
        Long runtimeId =
                db.query(
                        "SELECT id FROM grade_runtime WHERE code=?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        runtimeConfigId);
        if (runtimeId == null) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        List<UUID> sorted = memberKeys.stream().sorted().toList();
        String hash =
                digest(
                        expectedRev
                                + ":"
                                + snapshotId
                                + ":"
                                + runtimeConfigId
                                + ":"
                                + mode
                                + ":"
                                + roleA
                                + ":"
                                + roleB
                                + ":"
                                + sorted.get(0)
                                + ":"
                                + sorted.get(1));
        PlaytestPolicyGate.Snapshot evidence = policy.prepare();
        try {
            return stories.withBatchAction(
                    sid,
                    actor,
                    storyCode,
                    versionNo,
                    List.of(evidence.owner()),
                    () -> {
                        PlaytestPolicyGate.Notice notice =
                                policy.lock(evidence, Set.of(evidence.owner()));
                        // 재전송도 현재 참가자 자격을 확인한 후에만 결과 식별자를 반환한다.
                        lockMembers(sorted);
                        return new CreationLock(notice, runtime(runtimeId));
                    },
                    (scope, locks) -> {
                        RuntimeRow runtime = locks.runtime();
                        String scopeKey = "version:" + scope.versionId();
                        Receipt prior = receipt(requestKey);
                        if (prior != null)
                            return replay(
                                    prior, actor, "INVITATION_CREATE", scopeKey, hash, requestId);
                        if (!"REVIEW".equals(scope.status())
                                || scope.snapshotId() == null
                                || scope.snapshotId() != snapshotId)
                            throw AuthException.conflict("INVITATION_INVALIDATED");
                        if (scope.rev() != expectedRev)
                            throw AuthException.conflict("EDIT_CONFLICT");
                        if (!"AVAILABLE".equals(runtime.state()))
                            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                        var verifier = installations.get(runtime.code());
                        if (verifier == null) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                        var verified = verifier.verify(runtime);
                        if (!verified.profile().policyCode().equals(scope.policyCode()))
                            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                        Integer pair =
                                db.queryForObject(
                                        "SELECT count(*) FROM story_pair WHERE version_id=? AND"
                                                + " role_a=? AND role_b=? AND active_yn",
                                        Integer.class,
                                        scope.versionId(),
                                        roleA,
                                        roleB);
                        if (pair == null || pair != 1)
                            throw AuthException.conflict("PAIR_UNAVAILABLE");
                        List<Member> members = members(sorted);
                        if (members.size() != 2)
                            throw AuthException.unprocessable("PARTICIPANT_UNAVAILABLE");
                        // The database pins the actual runtime hash/epoch again in its INSERT
                        // trigger.
                        UUID testKey = UUID.randomUUID();
                        Long id =
                                db.queryForObject(
                                        """
                                        INSERT INTO play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,
                                          runtime_epoch,role_a,role_b,mode,invite_until,created_by)
                                        VALUES (?,?,?,?,?,?,?,?,'FUNCTIONAL',now()+interval '7 days',?)
                                        RETURNING id
                                        """,
                                        Long.class,
                                        testKey,
                                        scope.versionId(),
                                        snapshotId,
                                        runtime.id(),
                                        runtime.configHash(),
                                        runtime.epoch(),
                                        roleA,
                                        roleB,
                                        actor.accountId());
                        if (id == null) throw unavailable();
                        if (db.update(
                                        "INSERT INTO test_retention(test_id,policy_id) VALUES"
                                                + " (?,?)",
                                        id,
                                        locks.notice().policyId())
                                != 1) throw unavailable();
                        for (int slot = 0; slot < members.size(); slot++)
                            if (db.update(
                                            "INSERT INTO test_member(test_id,member_id,slot) VALUES"
                                                    + " (?,?,?)",
                                            id,
                                            members.get(slot).id(),
                                            slot + 1)
                                    != 1) throw unavailable();
                        if (db.update(
                                        """
                                        INSERT INTO test_action(request_key,admin_id,action,scope_key,request_hash,result_data)
                                        VALUES (?,?,'INVITATION_CREATE',?,?,jsonb_build_object('testKey',?::text,
                                          'rev','0','state','WAITING','inviteExpiresAt',
                                          (SELECT invite_until FROM play_test WHERE id=?)))
                                        """,
                                        requestKey,
                                        actor.accountId(),
                                        scopeKey,
                                        hash,
                                        testKey.toString(),
                                        id)
                                != 1) throw unavailable();
                        audit(actor, "INVITATION_CREATE", "test:" + testKey, requestId, "CREATED");
                        policy.lock(evidence, Set.of(evidence.owner()));
                        Map<String, Object> original =
                                actionState(
                                        testKey, "0", "WAITING", view(id, requestId).inviteUntil());
                        return new ActionResult(
                                "INVITATION_CREATE", false, true, original, original, requestId);
                    });
        } catch (DataAccessException failure) {
            if (!requestKeyCollision(failure)) throw unavailable();
            return stories.withBatchAction(
                    sid,
                    actor,
                    storyCode,
                    versionNo,
                    List.of(evidence.owner()),
                    () -> {
                        policy.lock(evidence, Set.of(evidence.owner()));
                        lockMembers(sorted);
                        return runtime(runtimeId);
                    },
                    (scope, locked) -> {
                        Receipt prior = receipt(requestKey);
                        if (prior == null) throw unavailable();
                        return replay(
                                prior,
                                actor,
                                "INVITATION_CREATE",
                                "version:" + scope.versionId(),
                                hash,
                                requestId);
                    });
        }
    }

    /** 현재 REVIEW 권한과 사건 소속을 확인해 회원 메타데이터만 읽는다. */
    public AdminDetail getInvitationDetail(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            UUID testKey,
            UUID requestId) {
        uuid(testKey);
        uuid(requestId);
        return stories.withExecutionIssueRead(
                sid,
                actor,
                storyCode,
                versionNo,
                scope -> {
                    Long id =
                            db.query(
                                    "SELECT id FROM play_test WHERE test_key=? AND version_id=?",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    testKey,
                                    scope.versionId());
                    if (id == null) throw missing();
                    PlaytestAccess.normalizeTime(
                            db, crypto, id, "ADMIN", actor.accountKey().toString(), requestId);
                    audit(actor, "INVITATION_READ", "test:" + id, requestId, "READ");
                    return adminDetail(id, requestId);
                });
    }

    /** 배타 내부 ID 커서로 최대 100개의 비밀 없는 관리자 요약을 조회한다. */
    public AdminPage getInvitationList(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            Long cursor,
            int size,
            UUID requestId) {
        uuid(requestId);
        if (size < 1 || size > 100 || cursor != null && cursor <= 0)
            throw AuthException.badRequest("INVALID_REQUEST");
        return stories.withExecutionIssueRead(
                sid,
                actor,
                storyCode,
                versionNo,
                scope -> {
                    List<Long> ids =
                            db.queryForList(
                                    """
                                    SELECT id FROM play_test WHERE version_id=? AND (?::bigint IS NULL OR id<?)
                                    ORDER BY id DESC LIMIT ?
                                    """,
                                    Long.class,
                                    scope.versionId(),
                                    cursor,
                                    cursor,
                                    size + 1);
                    List<AdminSummary> items =
                            ids.stream()
                                    .limit(size)
                                    .map(
                                            id -> {
                                                PlaytestAccess.normalizeTime(
                                                        db,
                                                        crypto,
                                                        id,
                                                        "ADMIN",
                                                        actor.accountKey().toString(),
                                                        requestId);
                                                return adminSummary(id);
                                            })
                                    .toList();
                    audit(
                            actor,
                            "INVITATION_LIST",
                            "version:" + scope.versionId(),
                            requestId,
                            "READ");
                    return new AdminPage(
                            items,
                            ids.size() > size ? Long.toString(ids.get(size - 1)) : null,
                            requestId);
                });
    }

    /** 현재 권한을 재확인한 대기·진행·종료 초대의 접근을 회수하며 종료 결과는 보존한다. */
    public ActionResult revokeInvitation(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            UUID testKey,
            long expectedRevision,
            String reasonCode,
            String verificationRef,
            UUID requestKey,
            UUID requestId) {
        uuid(testKey);
        uuid(requestKey);
        uuid(requestId);
        if (expectedRevision < 0
                || expectedRevision == Long.MAX_VALUE
                || !Set.of("TESTER_REQUEST", "ACCESS_REVOKED", "CONTENT_REVIEW", "OPERATIONAL")
                        .contains(reasonCode == null ? "" : reasonCode)
                || verificationRef == null
                || !verificationRef.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        Long runtimeId =
                db.query(
                        "SELECT runtime_id FROM play_test WHERE test_key=? AND version_id IN"
                            + " (SELECT id FROM story_version WHERE version_no=? AND story_id IN"
                            + " (SELECT id FROM story WHERE code=?))",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        testKey,
                        versionNo,
                        storyCode);
        List<UUID> memberKeys =
                db.query(
                        "SELECT a.member_key FROM test_member m JOIN play_test t ON t.id=m.test_id"
                            + " JOIN member_account a ON a.id=m.member_id WHERE t.test_key=? ORDER"
                            + " BY a.id",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        testKey);
        if (runtimeId == null) {
            stories.withExecutionIssueRead(sid, actor, storyCode, versionNo, scope -> null);
            throw missing();
        }
        String hash =
                digest(testKey + ":" + expectedRevision + ":" + reasonCode + ":" + verificationRef);
        try {
            return stories.withBatchAction(
                            sid,
                            actor,
                            storyCode,
                            versionNo,
                            () -> {
                                lockMembersForRevoke(memberKeys);
                                return runtime(runtimeId);
                            },
                            (scope, runtime) -> {
                                Long id =
                                        db.query(
                                                "SELECT id FROM play_test WHERE test_key=? AND"
                                                        + " version_id=? FOR UPDATE",
                                                rs -> rs.next() ? rs.getLong(1) : null,
                                                testKey,
                                                scope.versionId());
                                if (id == null) throw missing();
                                String scopeKey = "test:" + testKey;
                                Receipt prior = receipt(requestKey);
                                if (prior != null) {
                                    return Result.allow(
                                            replay(
                                                    prior,
                                                    actor,
                                                    "INVITATION_REVOKE",
                                                    scopeKey,
                                                    hash,
                                                    requestId));
                                }
                                AuthException expired =
                                        PlaytestAccess.normalizeTime(
                                                db,
                                                crypto,
                                                id,
                                                "ADMIN",
                                                actor.accountKey().toString(),
                                                requestId);
                                if (expired != null) return Result.<ActionResult>deny(expired);
                                Invitation before = view(id, requestId);
                                if (before.revision() != expectedRevision)
                                    throw AuthException.conflict("EDIT_CONFLICT");
                                if ("CANCELLED".equals(before.state())) {
                                    String previousReason =
                                            db.queryForObject(
                                                    "SELECT cancel_reason FROM play_test WHERE"
                                                            + " id=?",
                                                    String.class,
                                                    id);
                                    String previousRef =
                                            db.query(
                                                    """
                                                    SELECT detail->>'verificationRef' FROM test_audit
                                                    WHERE action='INVITATION_REVOKE' AND scope_key=?
                                                      AND business_result='CANCELLED' ORDER BY id DESC LIMIT 1
                                                    """,
                                                    rs -> rs.next() ? rs.getString(1) : null,
                                                    scopeKey);
                                    if (!reasonCode.equals(previousReason)
                                            || !verificationRef.equals(previousRef))
                                        throw AuthException.conflict("STATE_CONFLICT");
                                    Map<String, Object> unchanged = currentState(testKey);
                                    db.update(
                                            """
                                            INSERT INTO test_action(request_key,admin_id,action,scope_key,request_hash,result_data)
                                            VALUES (?,?,'INVITATION_REVOKE',?,?,jsonb_build_object('testKey',?::text,
                                              'rev',?::text,'state','CANCELLED','inviteExpiresAt',?::timestamptz))
                                            """,
                                            requestKey,
                                            actor.accountId(),
                                            scopeKey,
                                            hash,
                                            testKey.toString(),
                                            unchanged.get("rev"),
                                            java.sql.Timestamp.from(before.inviteUntil()));
                                    audit(
                                            actor,
                                            "INVITATION_REVOKE",
                                            scopeKey,
                                            requestId,
                                            "UNCHANGED");
                                    return Result.allow(
                                            new ActionResult(
                                                    "INVITATION_REVOKE",
                                                    false,
                                                    false,
                                                    unchanged,
                                                    unchanged,
                                                    requestId));
                                }
                                if (!Set.of("WAITING", "RUNNING", "ENDED").contains(before.state()))
                                    throw AuthException.conflict("STATE_CONFLICT");
                                if ("ENDED".equals(before.state())) {
                                    Boolean active =
                                            db.queryForObject(
                                                    "SELECT EXISTS(SELECT 1 FROM test_member WHERE"
                                                        + " test_id=? AND invite_state='ACCEPTED')",
                                                    Boolean.class,
                                                    id);
                                    if (!Boolean.TRUE.equals(active))
                                        return Result.allow(
                                                unchangedEndedWithdrawal(
                                                        id,
                                                        testKey,
                                                        before,
                                                        actor,
                                                        reasonCode,
                                                        verificationRef,
                                                        requestKey,
                                                        requestId,
                                                        hash));
                                }
                                if (!"ENDED".equals(before.state())
                                        && db.update(
                                                        """
                                                        UPDATE play_test SET state='CANCELLED',cancel_reason=?,
                                                          ended_at=clock_timestamp(),updated_at=clock_timestamp(),rev=rev+1
                                                        WHERE id=? AND state IN ('WAITING','RUNNING') AND rev=?
                                                        """,
                                                        reasonCode,
                                                        id,
                                                        expectedRevision)
                                                != 1)
                                    throw AuthException.conflict("INVITATION_INVALIDATED");
                                db.update(
                                        """
                                        UPDATE test_member SET invite_state='REVOKED',ready_yn=false,
                                          revoked_at=clock_timestamp(),
                                          updated_at=clock_timestamp() WHERE test_id=? AND invite_state IN ('SENT','ACCEPTED')
                                        """,
                                        id);
                                if (db.update(
                                                """
                                                INSERT INTO test_action(request_key,admin_id,action,scope_key,request_hash,result_data)
                                                VALUES (?,?,'INVITATION_REVOKE',?,?,jsonb_build_object('testKey',?::text,
                                                  'rev',?::text,'state',?::text,
                                                  'inviteExpiresAt',?::timestamptz))
                                                """,
                                                requestKey,
                                                actor.accountId(),
                                                scopeKey,
                                                hash,
                                                testKey.toString(),
                                                "ENDED".equals(before.state())
                                                        ? expectedRevision
                                                        : expectedRevision + 1,
                                                "ENDED".equals(before.state())
                                                        ? "ENDED"
                                                        : "CANCELLED",
                                                java.sql.Timestamp.from(before.inviteUntil()))
                                        != 1) throw unavailable();
                                if (db.update(
                                                """
                                                INSERT INTO test_audit(event_key,actor_kind,actor_ref,action,scope_kind,
                                                  scope_key,request_id,phase,business_result,detail)
                                                VALUES (?,'ADMIN',?,'INVITATION_REVOKE','PLAYTEST',?,?,'RESULT',
                                                  ?::text,jsonb_build_object('reasonCode',?::text,'verificationRef',?::text))
                                                """,
                                                UUID.randomUUID(),
                                                actor.accountKey().toString(),
                                                scopeKey,
                                                requestId,
                                                "ENDED".equals(before.state())
                                                        ? "REVOKED"
                                                        : "CANCELLED",
                                                reasonCode,
                                                verificationRef)
                                        != 1) throw unavailable();
                                Map<String, Object> original =
                                        actionState(
                                                testKey,
                                                Long.toString(
                                                        "ENDED".equals(before.state())
                                                                ? expectedRevision
                                                                : expectedRevision + 1),
                                                "ENDED".equals(before.state())
                                                        ? "ENDED"
                                                        : "CANCELLED",
                                                before.inviteUntil());
                                return Result.allow(
                                        new ActionResult(
                                                "INVITATION_REVOKE",
                                                false,
                                                true,
                                                original,
                                                original,
                                                requestId));
                            })
                    .unwrap();
        } catch (DataAccessException failure) {
            if (!requestKeyCollision(failure)) throw unavailable();
            return stories.withBatchAction(
                    sid,
                    actor,
                    storyCode,
                    versionNo,
                    () -> {
                        lockMembersForRevoke(memberKeys);
                        return runtime(runtimeId);
                    },
                    (scope, locked) -> {
                        Long id =
                                db.query(
                                        "SELECT id FROM play_test WHERE test_key=? AND version_id=?"
                                                + " FOR UPDATE",
                                        rs -> rs.next() ? rs.getLong(1) : null,
                                        testKey,
                                        scope.versionId());
                        if (id == null) throw missing();
                        Receipt prior = receipt(requestKey);
                        if (prior == null) throw unavailable();
                        return replay(
                                prior,
                                actor,
                                "INVITATION_REVOKE",
                                "test:" + testKey,
                                hash,
                                requestId);
                    });
        }
    }

    private ActionResult unchangedEndedWithdrawal(
            long id,
            UUID testKey,
            Invitation before,
            AdminActor actor,
            String reason,
            String reference,
            UUID requestKey,
            UUID requestId,
            String hash) {
        String scope = "test:" + testKey;
        Boolean matching =
                db.queryForObject(
                        "SELECT (SELECT count(*) FROM test_member WHERE test_id=?)=2 AND (SELECT"
                                + " count(*) FROM test_member WHERE test_id=? AND"
                                + " invite_state='REVOKED' AND revoked_at IS NOT NULL AND NOT"
                                + " ready_yn)=2 AND EXISTS(SELECT 1 FROM test_audit WHERE"
                                + " action='INVITATION_REVOKE' AND actor_kind='ADMIN' AND"
                                + " scope_kind='PLAYTEST' AND scope_key=? AND phase='RESULT' AND"
                                + " business_result='REVOKED' AND detail->>'reasonCode'=? AND"
                                + " detail->>'verificationRef'=?)",
                        Boolean.class,
                        id,
                        id,
                        scope,
                        reason,
                        reference);
        if (!Boolean.TRUE.equals(matching)) throw AuthException.conflict("STATE_CONFLICT");
        var history = withdrawalHistory(id);
        Map<String, Object> unchanged = currentState(testKey);
        if (db.update(
                        "INSERT INTO"
                            + " test_action(request_key,admin_id,action,scope_key,request_hash,result_data)"
                            + " VALUES"
                            + " (?,?,'INVITATION_REVOKE',?,?,jsonb_build_object('testKey',?::text,"
                            + "'rev',?::text,'state','ENDED','inviteExpiresAt',?::timestamptz))",
                        requestKey,
                        actor.accountId(),
                        scope,
                        hash,
                        testKey.toString(),
                        unchanged.get("rev"),
                        java.sql.Timestamp.from(before.inviteUntil()))
                != 1) throw unavailable();
        UUID event = UUID.randomUUID();
        if (db.update(
                        "INSERT INTO"
                            + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                            + " VALUES (?,'ADMIN',?,'INVITATION_REVOKE','PLAYTEST',?,?,'RESULT',"
                            + "'UNCHANGED',jsonb_build_object('reasonCode',?::text,'verificationRef',?::text))",
                        event,
                        actor.accountKey().toString(),
                        scope,
                        requestId,
                        reason,
                        reference)
                != 1) throw unavailable();
        Receipt stored = receipt(requestKey);
        if (stored == null || !history.equals(withdrawalHistory(id))) throw unavailable();
        replay(stored, actor, "INVITATION_REVOKE", scope, hash, requestId);
        if (!unchanged.equals(
                actionState(testKey, stored.rev(), stored.state(), stored.inviteUntil())))
            throw unavailable();
        Boolean audited =
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM test_audit WHERE event_key=? AND"
                            + " actor_kind='ADMIN' AND actor_ref=? AND action='INVITATION_REVOKE'"
                            + " AND scope_key=? AND request_id=? AND phase='RESULT' AND"
                            + " business_result='UNCHANGED' AND detail->>'reasonCode'=? AND"
                            + " detail->>'verificationRef'=?)",
                        Boolean.class,
                        event,
                        actor.accountKey().toString(),
                        scope,
                        requestId,
                        reason,
                        reference);
        if (!Boolean.TRUE.equals(audited)) throw unavailable();
        return new ActionResult("INVITATION_REVOKE", false, false, unchanged, unchanged, requestId);
    }

    private List<Map<String, Object>> withdrawalHistory(long id) {
        return db.queryForList(
                "SELECT"
                    + " t.state,t.outcome,t.final_score,t.ended_at,t.rev,t.cancel_reason,t.started_at,t.deadline_at,t.result_until,t.attempt_count,t.wrong_count,t.updated_at"
                    + " AS test_updated_at,m.member_id,m.invite_state,m.revoked_at,m.ready_yn,m.role_code,m.updated_at"
                    + " FROM play_test t JOIN test_member m ON m.test_id=t.id WHERE t.id=? ORDER BY"
                    + " m.member_id",
                id);
    }

    private RuntimeRow runtime(long id) {
        return runtimes.lockRuntime(id)
                .orElseThrow(() -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
    }

    record Receipt(
            Long adminId,
            Long memberId,
            String action,
            String scope,
            String hash,
            String testKey,
            String rev,
            String state,
            Instant inviteUntil) {}

    private Receipt receipt(UUID key) {
        return db.query(
                """
                SELECT admin_id,member_id,action,scope_key,request_hash,result_data->>'testKey',
                  result_data->>'rev',result_data->>'state',
                  (result_data->>'inviteExpiresAt')::timestamptz
                FROM test_action WHERE request_key=?
                """,
                rs ->
                        rs.next()
                                ? new Receipt(
                                        (Long) rs.getObject(1),
                                        (Long) rs.getObject(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getTimestamp(9) == null
                                                ? null
                                                : rs.getTimestamp(9).toInstant())
                                : null,
                key);
    }

    /** 전역 영수증을 회원·행동·대상·전체 의도에 결속하며 불일치를 충돌로 돌려준다. */
    static void checkMemberReceipt(Receipt receipt, long memberId, String scope, String hash) {
        if (receipt.adminId() != null
                || receipt.memberId() == null
                || receipt.memberId() != memberId
                || !"INVITATION_ACCEPT".equals(receipt.action())
                || !scope.equals(receipt.scope())
                || !scope.equals("test:" + receipt.testKey())
                || !hash.equals(receipt.hash()))
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
    }

    private ActionResult replay(
            Receipt receipt,
            AdminActor actor,
            String action,
            String scope,
            String hash,
            UUID requestId) {
        if (receipt.adminId() == null
                || receipt.adminId() != actor.accountId()
                || !action.equals(receipt.action())
                || !scope.equals(receipt.scope())
                || !hash.equals(receipt.hash()))
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        UUID key = UUID.fromString(receipt.testKey());
        Instant until = receipt.inviteUntil();
        Map<String, Object> original = actionState(key, receipt.rev(), receipt.state(), until);
        Long id =
                db.queryForObject(
                        "SELECT id FROM play_test WHERE test_key=? FOR UPDATE", Long.class, key);
        PlaytestAccess.normalizeTime(
                db, crypto, id, "ADMIN", actor.accountKey().toString(), requestId);
        Map<String, Object> current = currentState(key);
        return new ActionResult(action, true, false, original, current, requestId);
    }

    private Map<String, Object> currentState(UUID key) {
        Long id =
                db.query(
                        "SELECT id FROM play_test WHERE test_key=? FOR UPDATE",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        key);
        if (id == null) throw unavailable();
        Map<String, Object> current =
                db.query(
                        "SELECT rev,state,invite_until FROM play_test WHERE test_key=? FOR UPDATE",
                        rs ->
                                rs.next()
                                        ? actionState(
                                                key,
                                                Long.toString(rs.getLong(1)),
                                                rs.getString(2),
                                                rs.getTimestamp(3).toInstant())
                                        : null,
                        key);
        if (current == null) throw unavailable();
        return current;
    }

    private static Map<String, Object> actionState(
            UUID key, String rev, String state, Instant until) {
        return Map.of("testKey", key, "rev", rev, "state", state, "inviteExpiresAt", until);
    }

    /** 검증된 PLAYTEST 정책의 고정 보관기간을 원문 고지와 함께 명시한다. */
    private static String noticeSummary(String body) {
        return body
                + "\n테스트 원문은 종료 후 90일, 선별 보존 자료는 최대 365일 보관됩니다. "
                + "원문 파기 뒤에는 보고서·피드백 원문 조회와 재판정이 불가능합니다.";
    }

    private AdminSummary adminSummary(long id) {
        return db.queryForObject(
                """
                SELECT t.test_key,t.snapshot_id,r.code,t.runtime_epoch,t.role_a,t.role_b,
                  t.mode,t.state,t.outcome,t.rev,t.created_at,t.started_at,t.ended_at,
                  t.attempt_count,t.wrong_count
                FROM play_test t JOIN grade_runtime r ON r.id=t.runtime_id WHERE t.id=?
                """,
                (rs, n) -> {
                    if (!Set.of("WAITING", "RUNNING", "ENDED", "CANCELLED", "EXPIRED")
                            .contains(rs.getString(8))) throw unavailable();
                    return new AdminSummary(
                            rs.getObject(1, UUID.class),
                            Long.toString(rs.getLong(2)),
                            rs.getString(3),
                            rs.getLong(4),
                            List.of(rs.getString(5), rs.getString(6)),
                            rs.getString(7),
                            rs.getString(8),
                            rs.getString(9),
                            Long.toString(rs.getLong(10)),
                            rs.getTimestamp(11).toInstant(),
                            rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant(),
                            rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(),
                            0,
                            "NONE");
                },
                id);
    }

    private AdminDetail adminDetail(long id, UUID requestId) {
        AdminSummary summary = adminSummary(id);
        List<AdminMember> members =
                db.query(
                        """
                        SELECT m.slot,a.member_key,m.invite_gen,m.invite_state,m.accepted_at,
                          m.ready_yn,m.role_code,m.revoked_at,m.blind_declared
                        FROM test_member m JOIN member_account a ON a.id=m.member_id
                        WHERE m.test_id=? ORDER BY m.slot
                        """,
                        (rs, n) ->
                                new AdminMember(
                                        rs.getInt(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getInt(3),
                                        rs.getString(4),
                                        rs.getTimestamp(5) == null
                                                ? null
                                                : rs.getTimestamp(5).toInstant(),
                                        rs.getBoolean(6),
                                        rs.getString(7),
                                        rs.getTimestamp(8) == null
                                                ? null
                                                : rs.getTimestamp(8).toInstant(),
                                        rs.getBoolean(9)),
                        id);
        return db.queryForObject(
                """
                SELECT invite_until,ready_until,deadline_at,result_until,attempt_count,
                  wrong_count,final_score FROM play_test WHERE id=?
                """,
                (rs, n) ->
                        new AdminDetail(
                                summary.testKey(),
                                summary.snapshotId(),
                                summary.runtimeConfigId(),
                                summary.runtimeEpoch(),
                                summary.pair(),
                                summary.mode(),
                                summary.state(),
                                summary.outcome(),
                                summary.rev(),
                                summary.createdAt(),
                                summary.startedAt(),
                                summary.endedAt(),
                                summary.completedFeedbackCount(),
                                summary.reviewState(),
                                List.copyOf(members),
                                rs.getTimestamp(1).toInstant(),
                                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(),
                                rs.getInt(5),
                                rs.getInt(6),
                                (Integer) rs.getObject(7),
                                requestId),
                id);
    }

    private Invitation view(long id, UUID requestId) {
        return db.queryForObject(
                """
                SELECT test_key,state,rev,invite_until,snapshot_id,runtime_id,config_hash,runtime_epoch
                FROM play_test WHERE id=?
                """,
                (rs, n) ->
                        new Invitation(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getLong(3),
                                rs.getTimestamp(4).toInstant(),
                                rs.getLong(5),
                                rs.getLong(6),
                                rs.getString(7),
                                rs.getLong(8),
                                db.queryForList(
                                        """
                                        SELECT a.member_key FROM test_member m JOIN member_account a ON a.id=m.member_id
                                        WHERE m.test_id=? ORDER BY m.slot
                                        """,
                                        UUID.class,
                                        id),
                                requestId),
                id);
    }

    private void audit(
            AdminActor actor, String action, String scope, UUID requestId, String result) {
        if (db.update(
                        """
                        INSERT INTO test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,
                          request_id,phase,business_result,detail)
                        VALUES (?,'ADMIN',?,?,'PLAYTEST',?,?,'RESULT',?,'{}'::jsonb)
                        """,
                        UUID.randomUUID(),
                        actor.accountKey().toString(),
                        action,
                        scope,
                        requestId,
                        result)
                != 1) throw unavailable();
    }

    private static void uuid(UUID value) {
        if (value == null || value.version() != 4 || value.variant() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw unavailable();
        }
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("PLAYTEST_UNAVAILABLE");
    }

    private static AuthException missing() {
        return new AuthException(404, "NOT_FOUND", "NOT_FOUND");
    }

    private record Member(long id, UUID key) {}

    private record MemberItem(long id, MemberInvitation invitation) {}

    record TestTarget(
            long id,
            boolean active,
            String status,
            Long currentSnapshot,
            long snapshotId,
            Instant inviteUntil) {}

    private record CreationLock(PlaytestPolicyGate.Notice notice, RuntimeRow runtime) {}
}
