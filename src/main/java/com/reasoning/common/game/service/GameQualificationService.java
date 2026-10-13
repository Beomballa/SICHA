package com.reasoning.common.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.StoryReviewPreview;
import com.reasoning.common.story.service.BlindEligibilityService;
import com.reasoning.common.story.service.StoryService;
import com.reasoning.common.util.CommonUtil;

import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** 현재 REVIEW 권위로 검수 단독 실행을 예약하고 별도의 실재 LOCAL 회원 동의만 참가자로 고정한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class GameQualificationService {
    private final JdbcTemplate db;
    private final StoryService stories;
    private final GradeRuntimeRepository runtimes;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final PlaytestPolicyGate policy;
    private final MemberPolicyGate memberPolicy;
    private final MemberAuthService auth;
    private final BlindEligibilityService eligibility;
    private final CryptoService crypto;
    private final GameReportService reports;
    private final TransactionTemplate tx;

    public GameQualificationService(
            JdbcTemplate db,
            StoryService stories,
            GradeRuntimeRepository runtimes,
            @Qualifier("gradeInstallations")
                    Map<String, InstalledRuntimeManifestVerifier> installations,
            PlaytestPolicyGate policy,
            MemberPolicyGate memberPolicy,
            MemberAuthService auth,
            CryptoService crypto,
            BlindEligibilityService eligibility) {
        this.db = db;
        this.stories = stories;
        this.runtimes = runtimes;
        this.installations = Map.copyOf(installations);
        this.policy = policy;
        this.memberPolicy = memberPolicy;
        this.auth = auth;
        this.eligibility = eligibility;
        this.crypto = crypto;
        this.reports = new GameReportService(db, crypto);
        tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Result(UUID roomKey, String state, boolean replayed, UUID requestId) {}

    public record Notice(
            UUID roomKey,
            String qualificationKind,
            String title,
            String policyCode,
            String noticeHash,
            String version,
            String body,
            String contact,
            UUID requestId) {}

    /** 단독 참가자의 시작 결과. rev는 다음 쓰기에서 사용할 현재 방 수정번호다. */
    public record StartResult(
            UUID roomKey,
            String state,
            String rev,
            Instant startedAt,
            Instant deadlineAt,
            boolean replayed,
            UUID requestId) {}

    /** 고정 사본의 두 역할 자료를 단독 참가자에게 합쳐 공개한다. 역할 둘 모두가 본인에게 열린다. */
    public record Materials(
            UUID roomKey,
            String state,
            String rev,
            Instant deadlineAt,
            JsonNode basic,
            JsonNode roles,
            JsonNode persons,
            JsonNode clues,
            UUID requestId) {}

    public record ReportView(
            UUID roomKey,
            String rev,
            String draftRev,
            JsonNode report,
            String submissionState,
            UUID requestId) {}

    public record ReportAction(
            String action,
            boolean replayed,
            boolean changed,
            JsonNode original,
            ReportView current,
            UUID requestId) {}

    public record ResultView(
            UUID roomKey,
            String state,
            String endReason,
            Instant endedAt,
            Integer finalScore,
            int gradedCount,
            int wrongCount,
            List<JsonNode> reports,
            JsonNode scoringRules,
            String revealText,
            UUID requestId) {}

    public record FeedbackResult(
            UUID roomKey, boolean replayed, Instant submittedAt, UUID requestId) {}

    private record Member(long id, long identityId) {}

    private record Receipt(
            long roomId,
            Long adminId,
            Long memberId,
            String action,
            String hash,
            UUID roomKey,
            String state,
            Long rev,
            Long startedMicros,
            Long deadlineMicros) {}

    private record Creation(PlaytestPolicyGate.Notice notice, RuntimeRow runtime) {}

    /** 관리자 세션·명시 REVIEW 권한과 설치 실행환경을 재검증해 동의 전 방만 만든다. 고지 또는 자료는 전달하지 않는다. */
    public Result createQualification(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            long expectedRev,
            long snapshotId,
            String roleA,
            String roleB,
            String runtimeConfigId,
            UUID memberKey,
            String qualificationKind,
            String policyCode,
            String noticeHash,
            UUID requestKey,
            UUID requestId) {
        uuid(requestKey);
        uuid(requestId);
        uuid(memberKey);
        if (actor == null
                || expectedRev < 0
                || snapshotId <= 0
                || runtimeConfigId == null
                || !runtimeConfigId.matches("[A-Z0-9_]{1,80}")
                || roleA == null
                || !roleA.matches("[A-Z0-9_]{1,32}")
                || roleB == null
                || !roleB.matches("[A-Z0-9_]{1,32}")
                || !Set.of("BLIND", "FUNCTIONAL")
                        .contains(qualificationKind == null ? "" : qualificationKind)
                || policyCode == null
                || noticeHash == null) throw AuthException.badRequest("INVALID_REQUEST");
        Long runtimeId =
                db.query(
                        "SELECT id FROM grade_runtime WHERE code=?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        runtimeConfigId);
        if (runtimeId == null) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        String hash =
                digestFields(
                        Long.toString(expectedRev),
                        Long.toString(snapshotId),
                        roleA,
                        roleB,
                        runtimeConfigId,
                        memberKey.toString(),
                        qualificationKind,
                        policyCode,
                        noticeHash);
        var evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        Long selectedId =
                db.query(
                        "SELECT id FROM member_account WHERE member_key=?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        memberKey);
        if (selectedId == null) throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
        Set<Long> owners = owners(evidence.owner(), selectedId);
        return stories.withBatchAction(
                sid,
                actor,
                storyCode,
                versionNo,
                java.util.List.copyOf(owners),
                () -> {
                    Member selected = member(memberKey);
                    if (selected.id() != selectedId)
                        throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    return new Creation(
                            policy.lock(evidence, Set.of(evidence.owner())),
                            runtimes.lockRuntime(runtimeId)
                                    .orElseThrow(
                                            () -> AuthException.conflict("RUNTIME_UNAVAILABLE")));
                },
                (scope, locked) -> {
                    Member member = member(memberKey);
                    if (member.id() != selectedId)
                        throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    var permit = memberPolicy.lock(memberEvidence, member.id(), owners);
                    memberPolicy.checkPinned(permit, member.id());
                    memberPolicy.check(permit);
                    if (!locked.notice().code().equals(policyCode)
                            || !locked.notice().hash().equals(noticeHash))
                        throw AuthException.conflict("POLICY_CHANGED");
                    lockRequest(requestKey);
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        if (prior.adminId() == null
                                || prior.adminId() != actor.accountId()
                                || !"QUALIFICATION_CREATE".equals(prior.action())
                                || !hash.equals(prior.hash())
                                || !Boolean.TRUE.equals(
                                        db.queryForObject(
                                                "SELECT version_id=? AND reviewer_id=? AND"
                                                    + " result_data->>'memberKey'=? FROM game_room"
                                                    + " JOIN game_action ON"
                                                    + " game_action.room_id=game_room.id WHERE"
                                                    + " game_room.id=? AND"
                                                    + " game_action.request_key=?",
                                                Boolean.class,
                                                scope.versionId(),
                                                actor.accountId(),
                                                memberKey.toString(),
                                                prior.roomId(),
                                                requestKey)))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        checkCurrent(scope.status(), scope.snapshotId(), snapshotId);
                        if ("BLIND".equals(qualificationKind)) {
                            Long pinned =
                                    db.queryForObject(
                                            "SELECT assessment_id FROM game_room WHERE id=?",
                                            Long.class,
                                            prior.roomId());
                            if (pinned == null) throw AuthException.conflict("BLIND_INELIGIBLE");
                            Boolean ownSource =
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM test_exposure WHERE"
                                                + " story_id=? AND member_id=? AND first_room_id=?"
                                                + " AND first_test_id IS NULL)",
                                            Boolean.class,
                                            scope.storyId(),
                                            member.id(),
                                            prior.roomId());
                            if (Boolean.TRUE.equals(ownSource))
                                eligibility.requirePinnedGameExposure(
                                        pinned,
                                        scope.storyId(),
                                        snapshotId,
                                        member.id(),
                                        prior.roomId());
                            else
                                eligibility.requirePinnedGameAdmission(
                                        pinned,
                                        scope.storyId(),
                                        snapshotId,
                                        member.id(),
                                        prior.roomId());
                        }
                        policy.lock(evidence, Set.of(evidence.owner()));
                        return new Result(prior.roomKey(), prior.state(), true, requestId);
                    }
                    checkCurrent(scope.status(), scope.snapshotId(), snapshotId);
                    if (scope.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    var runtime = locked.runtime();
                    if (!"AVAILABLE".equals(runtime.state())
                            || !runtime.code().equals(runtimeConfigId))
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    var installed = installations.get(runtime.code());
                    if (installed == null
                            || !installed
                                    .verify(runtime)
                                    .profile()
                                    .policyCode()
                                    .equals(scope.policyCode()))
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    if (!Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM story_pair WHERE version_id=? AND"
                                            + " role_a=? AND role_b=? AND active_yn)",
                                    Boolean.class,
                                    scope.versionId(),
                                    roleA,
                                    roleB))) throw AuthException.conflict("PAIR_UNAVAILABLE");
                    Long assessmentId =
                            "BLIND".equals(qualificationKind)
                                    ? eligibility.requireCurrentForAdmission(
                                            scope.storyId(), snapshotId, member.id())
                                    : null;
                    if (Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM game_member WHERE member_id=? AND"
                                            + " occupying_yn)",
                                    Boolean.class,
                                    member.id())))
                        throw AuthException.conflict("ACTIVE_GAME_EXISTS");
                    var source =
                            db.query(
                                    "SELECT payload::text FROM review_snapshot WHERE id=? AND"
                                            + " version_id=?",
                                    rs -> rs.next() ? rs.getString(1) : null,
                                    snapshotId,
                                    scope.versionId());
                    if (source == null) throw AuthException.conflict("SNAPSHOT_UNAVAILABLE");
                    var frozen =
                            FrozenSnapshotCodec.decode(source.getBytes(StandardCharsets.UTF_8));
                    var basic = frozen.payload().path("sections").path("basic");
                    int difficulty = basic.path("difficulty").asInt(0);
                    int limitSec = basic.path("limitSec").asInt(0);
                    int attempts = frozen.payload().path("policy").path("attemptLimit").asInt(0);
                    int hints = frozen.payload().path("policy").path("hintsPerPerson").asInt(-1);
                    if (difficulty < 1
                            || difficulty > 5
                            || limitSec <= 0
                            || attempts <= 0
                            || hints < 0) throw AuthException.conflict("SNAPSHOT_UNAVAILABLE");
                    UUID roomKey = UUID.randomUUID();
                    Long roomId =
                            db.queryForObject(
                                    """
                                    INSERT INTO game_room(room_key,purpose,mode,qualification_kind,reviewer_id,selected_member_id,story_id,
                                      version_id,snapshot_id,runtime_id,runtime_epoch,config_hash,rubric_hash,policy_id,
                                      play_rev,state,difficulty,limit_sec,max_attempts,max_hints,lobby_until,assessment_id)
                                    VALUES (?,'REVIEW_SOLO_QUALIFICATION','SOLO',?,?,?,?,?,?,?,?,?,?,?,?,'WAITING',?,?,?,?,
                                      now()+interval '30 minutes',?) RETURNING id
                                    """,
                                    Long.class,
                                    roomKey,
                                    qualificationKind,
                                    actor.accountId(),
                                    member.id(),
                                    scope.storyId(),
                                    scope.versionId(),
                                    snapshotId,
                                    runtime.id(),
                                    runtime.epoch(),
                                    runtime.configHash(),
                                    frozen.rubricHash(),
                                    locked.notice().policyId(),
                                    db.queryForObject(
                                            "SELECT play_rev FROM story WHERE id=?",
                                            Long.class,
                                            scope.storyId()),
                                    difficulty,
                                    limitSec,
                                    attempts,
                                    hints,
                                    assessmentId);
                    if (roomId == null) throw AuthException.unavailable("GAME_UNAVAILABLE");
                    db.update(
                            """
                            INSERT INTO game_action(request_key,admin_id,room_id,action,request_hash,result_data)
                            VALUES (?,?,?,'QUALIFICATION_CREATE',?,jsonb_build_object('roomKey',?::text,
                              'memberKey',?::text,'roleA',?::text,'roleB',?::text,'state','WAITING'))
                            """,
                            requestKey,
                            actor.accountId(),
                            roomId,
                            hash,
                            roomKey.toString(),
                            memberKey.toString(),
                            roleA,
                            roleB);
                    db.update(
                            """
                            INSERT INTO game_audit(event_key,room_id,actor_kind,admin_id,action,request_id,result)
                            VALUES (?,?,'ADMIN',?,'QUALIFICATION_CREATE',?,'CREATED')
                            """,
                            UUID.randomUUID(),
                            roomId,
                            actor.accountId(),
                            requestId);
                    policy.lock(evidence, Set.of(evidence.owner()));
                    memberPolicy.checkPinned(permit, member.id());
                    memberPolicy.check(permit);
                    return new Result(roomKey, "WAITING", false, requestId);
                });
    }

    /** 실제 회원 세션으로 현재 고지를 읽는다. 방 생성만으로 동의나 자료 노출을 추정하지 않는다. */
    public Notice getAcceptanceNotice(String accessToken, UUID roomKey, UUID requestId) {
        uuid(roomKey);
        uuid(requestId);
        var principal = auth.authenticate(accessToken, false);
        var evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    Set<Long> owners = roomOwners(roomKey, evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(accessToken, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = policy.lock(evidence, owners);
                    lockRoomRuntime(roomKey);
                    var room = room(roomKey);
                    validateRoom(room, principal, notice, true);
                    JsonNode title =
                            frozen(room).payload().path("sections").path("basic").path("title");
                    if (!title.isTextual() || title.asText().isBlank())
                        throw AuthException.conflict("SNAPSHOT_UNAVAILABLE");
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    return new Notice(
                            roomKey,
                            room.kind(),
                            title.asText(),
                            notice.code(),
                            notice.hash(),
                            notice.version(),
                            notice.body(),
                            notice.contact(),
                            requestId);
                });
    }

    /** 고지 코드·해시를 실제 LOCAL 회원이 명시 수락한 거래에서만 참가·영수증·감사를 생성한다. */
    public Result acceptQualification(
            String accessToken,
            UUID roomKey,
            boolean blindDeclared,
            String policyCode,
            String noticeHash,
            UUID requestKey,
            UUID requestId) {
        uuid(roomKey);
        uuid(requestKey);
        uuid(requestId);
        if (policyCode == null || noticeHash == null)
            throw AuthException.badRequest("INVALID_REQUEST");
        var principal = auth.authenticate(accessToken, false);
        var evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        String hash =
                digestFields(
                        roomKey.toString(),
                        Boolean.toString(blindDeclared),
                        policyCode,
                        noticeHash);
        return tx.execute(
                status -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    Set<Long> owners = roomOwners(roomKey, evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(accessToken, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = policy.lock(evidence, owners);
                    lockRoomRuntime(roomKey);
                    var room = room(roomKey);
                    validateRoom(room, principal, notice, false);
                    if (!notice.code().equals(policyCode) || !notice.hash().equals(noticeHash))
                        throw AuthException.conflict("POLICY_CHANGED");
                    if ("BLIND".equals(room.kind())) {
                        blindCurrent(room, principal.memberId());
                    }
                    lockRequest(requestKey);
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        if (prior.memberId() == null
                                || prior.memberId() != principal.memberId()
                                || !"QUALIFICATION_ACCEPT".equals(prior.action())
                                || !hash.equals(prior.hash())
                                || prior.roomId() != room.id()
                                || !prior.roomKey().equals(roomKey))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        if (!Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM game_member WHERE room_id=?"
                                            + " AND member_id=? AND active_yn AND policy_id=? AND"
                                            + " blind_declared IS NOT DISTINCT FROM ?)",
                                        Boolean.class,
                                        room.id(),
                                        principal.memberId(),
                                        notice.policyId(),
                                        blindDeclared ? true : null)))
                            throw AuthException.conflict("STATE_CONFLICT");
                        return new Result(roomKey, prior.state(), true, requestId);
                    }
                    if ("BLIND".equals(room.kind()) && !blindDeclared)
                        throw AuthException.conflict("BLIND_INELIGIBLE");
                    if ("FUNCTIONAL".equals(room.kind()) && blindDeclared)
                        throw AuthException.badRequest("INVALID_REQUEST");
                    if (!"WAITING".equals(room.state()) || !room.lobbyOpen())
                        throw AuthException.conflict("STATE_CONFLICT");
                    if (Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM game_member WHERE member_id=? AND"
                                            + " occupying_yn)",
                                    Boolean.class,
                                    principal.memberId())))
                        throw AuthException.conflict("ACTIVE_GAME_EXISTS");
                    var identities =
                            db.query(
                                    "SELECT id FROM member_identity WHERE member_id=? AND"
                                        + " provider='LOCAL' AND realm='LOCAL' AND active_yn AND"
                                        + " proof_at IS NOT NULL FOR UPDATE",
                                    (rs, n) -> rs.getLong(1),
                                    principal.memberId());
                    if (identities.size() != 1)
                        throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    db.update(
                            """
                            INSERT INTO game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at,blind_declared)
                            VALUES (?,?,?,1,?,clock_timestamp(),?)
                            """,
                            room.id(),
                            principal.memberId(),
                            identities.getFirst(),
                            notice.policyId(),
                            blindDeclared ? true : null);
                    db.update(
                            """
                            INSERT INTO game_action(request_key,member_id,room_id,action,request_hash,result_data)
                            VALUES (?,?,?,'QUALIFICATION_ACCEPT',?,jsonb_build_object('roomKey',?::text,'state','WAITING'))
                            """,
                            requestKey,
                            principal.memberId(),
                            room.id(),
                            hash,
                            roomKey.toString());
                    db.update(
                            """
                            INSERT INTO game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)
                            VALUES (?,?,'MEMBER',?,'QUALIFICATION_ACCEPT',?,'ACCEPTED')
                            """,
                            UUID.randomUUID(),
                            room.id(),
                            principal.memberId(),
                            requestId);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    policy.lock(evidence, owners);
                    return new Result(roomKey, "WAITING", false, requestId);
                });
    }

    /**
     * 현재 LOCAL 회원·검수 권위와 정확한 생성 역할쌍을 재검증한 뒤 단독 대기를 한 번만 시작한다.
     *
     * @param expectedRev 현재 방 수정번호(0 이상, 최대 long 제외)
     * @param requestKey 전역 UUID v4 불변 의도 키; 같은 회원·방·수정번호 재전송만 허용
     * @throws AuthException 권위 상실·만료·동의 불일치·수정번호 충돌 시 자료 없이 거절
     */
    public StartResult startQualification(
            String accessToken, UUID roomKey, long expectedRev, UUID requestKey, UUID requestId) {
        uuid(roomKey);
        uuid(requestKey);
        uuid(requestId);
        if (expectedRev < 0 || expectedRev == Long.MAX_VALUE)
            throw AuthException.badRequest("INVALID_REQUEST");
        var principal = auth.authenticate(accessToken, false);
        var evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        String hash = digestFields(roomKey.toString(), Long.toString(expectedRev));
        return tx.execute(
                status -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    Set<Long> owners = roomOwners(roomKey, evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(accessToken, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = policy.lock(evidence, owners);
                    lockRoomRuntime(roomKey);
                    var room = room(roomKey);
                    validateRoom(room, principal, notice, false);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    if ("BLIND".equals(room.kind())) blindCurrent(room, principal.memberId());
                    lockRequest(requestKey);
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        if (prior.memberId() == null
                                || prior.memberId() != principal.memberId()
                                || !"QUALIFICATION_START".equals(prior.action())
                                || !hash.equals(prior.hash())
                                || prior.roomId() != room.id()
                                || !roomKey.equals(prior.roomKey()))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        if (!"RUNNING".equals(room.state())
                                || !startedMember(room, principal.memberId()))
                            throw AuthException.conflict("STATE_CONFLICT");
                        if (prior.rev() == null
                                || prior.startedMicros() == null
                                || prior.deadlineMicros() == null)
                            throw AuthException.conflict("STATE_CONFLICT");
                        return new StartResult(
                                roomKey,
                                prior.state(),
                                Long.toString(prior.rev()),
                                Instant.ofEpochSecond(0, prior.startedMicros() * 1000),
                                Instant.ofEpochSecond(0, prior.deadlineMicros() * 1000),
                                true,
                                requestId);
                    }
                    if (!"WAITING".equals(room.state()) || !room.lobbyOpen())
                        throw AuthException.conflict("STATE_CONFLICT");
                    if (room.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    if (room.roleA() == null
                            || room.roleB() == null
                            || room.roleA().equals(room.roleB())
                            || !Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM story_pair WHERE"
                                                    + " version_id=? AND role_a=? AND role_b=? AND"
                                                    + " active_yn)",
                                            Boolean.class,
                                            room.versionId(),
                                            room.roleA(),
                                            room.roleB())))
                        throw AuthException.conflict("PAIR_UNAVAILABLE");
                    JsonNode frozen = frozen(room).payload();
                    boolean selectedPair = false;
                    for (JsonNode pair : frozen.get("resources").get("pairs")) {
                        if (room.roleA().equals(pair.get("roleA").textValue())
                                && room.roleB().equals(pair.get("roleB").textValue()))
                            selectedPair = true;
                    }
                    if (!selectedPair) throw AuthException.conflict("PAIR_UNAVAILABLE");
                    var members =
                            db.queryForList(
                                    "SELECT member_id FROM game_member WHERE room_id=? AND"
                                            + " active_yn FOR UPDATE",
                                    Long.class,
                                    room.id());
                    if (members.size() != 1
                            || members.getFirst() != principal.memberId()
                            || !Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM game_member WHERE"
                                                + " room_id=? AND member_id=? AND slot=1 AND"
                                                + " policy_id=? AND version_id IS NULL AND"
                                                + " role_code IS NULL AND identity_id=(SELECT"
                                                + " identity_id FROM member_session WHERE id=?))",
                                            Boolean.class,
                                            room.id(),
                                            principal.memberId(),
                                            notice.policyId(),
                                            principal.sessionId())))
                        throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    if (db.update(
                                    "UPDATE game_member SET"
                                        + " version_id=?,role_code=?,updated_at=clock_timestamp()"
                                        + " WHERE room_id=? AND member_id=? AND active_yn AND"
                                        + " role_code IS NULL",
                                    room.versionId(),
                                    room.roleA(),
                                    room.id(),
                                    principal.memberId())
                            != 1) throw AuthException.conflict("STATE_CONFLICT");
                    if (db.update(
                                    "WITH clock AS (SELECT clock_timestamp() AS instant) UPDATE"
                                        + " game_room SET"
                                        + " state='RUNNING',role_a=?,role_b=?,started_at=clock.instant,deadline_at=clock.instant+limit_sec*interval"
                                        + " '1 second',rev=rev+1,updated_at=clock_timestamp() FROM"
                                        + " clock WHERE id=? AND state='WAITING' AND rev=? AND"
                                        + " lobby_until>clock.instant",
                                    room.roleA(),
                                    room.roleB(),
                                    room.id(),
                                    expectedRev)
                            != 1) throw AuthException.conflict("STATE_CONFLICT");
                    db.update(
                            "INSERT INTO"
                                + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                + " SELECT"
                                + " ?,?,r.id,'QUALIFICATION_START',?,jsonb_build_object('roomKey',?::text,'state','RUNNING','rev',r.rev,'startedMicros',(extract(epoch"
                                + " from"
                                + " r.started_at)*1000000)::bigint,'deadlineMicros',(extract(epoch"
                                + " from r.deadline_at)*1000000)::bigint) FROM game_room r WHERE"
                                + " r.id=?",
                            requestKey,
                            principal.memberId(),
                            hash,
                            roomKey.toString(),
                            room.id());
                    db.update(
                            "INSERT INTO"
                                + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                + " VALUES (?,?,'MEMBER',?,'QUALIFICATION_START',?,'STARTED')",
                            UUID.randomUUID(),
                            room.id(),
                            principal.memberId(),
                            requestId);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    policy.lock(evidence, owners);
                    var clock =
                            db.queryForMap(
                                    "SELECT rev,started_at,deadline_at FROM game_room WHERE id=?",
                                    room.id());
                    return new StartResult(
                            roomKey,
                            "RUNNING",
                            clock.get("rev").toString(),
                            ((Timestamp) clock.get("started_at")).toInstant(),
                            ((Timestamp) clock.get("deadline_at")).toInstant(),
                            false,
                            requestId);
                });
    }

    /**
     * 현재 참가자에게만 고정 사본의 두 역할 공개 자료를 합쳐 반환한다. 첫 노출과 필수 감사가 실패하면 반환하지 않는다.
     *
     * @param accessToken 현재 LOCAL 접근 토큰
     * @param roomKey 검수 단독 방 UUID v4
     * @param requestId 서버 감사 UUID v4
     * @return 기본 정보·두 역할·중복 제거된 공개 인물과 단서; 정답·채점표는 포함하지 않음
     */
    public Materials getMaterials(String accessToken, UUID roomKey, UUID requestId) {
        uuid(roomKey);
        uuid(requestId);
        var principal = auth.authenticate(accessToken, false);
        var evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    Set<Long> owners = roomOwners(roomKey, evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(accessToken, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = policy.lock(evidence, owners);
                    lockRoomRuntime(roomKey);
                    var room = room(roomKey);
                    validateRoom(room, principal, notice, false);
                    if (!"RUNNING".equals(room.state())
                            || !startedMember(room, principal.memberId()))
                        throw AuthException.conflict("STATE_CONFLICT");
                    if ("BLIND".equals(room.kind())) blindExposure(room, principal.memberId());
                    JsonNode payload = frozen(room).payload();
                    JsonNode first;
                    JsonNode second;
                    try {
                        first =
                                StoryReviewPreview.role(
                                        payload.get("sections"),
                                        payload.get("resources"),
                                        room.roleA());
                        second =
                                StoryReviewPreview.role(
                                        payload.get("sections"),
                                        payload.get("resources"),
                                        room.roleB());
                    } catch (IllegalArgumentException failure) {
                        throw AuthException.conflict("SNAPSHOT_UNAVAILABLE");
                    }
                    var roles =
                            JsonNodeFactory.instance
                                    .arrayNode()
                                    .add(first.get("role"))
                                    .add(second.get("role"));
                    var persons = JsonNodeFactory.instance.arrayNode();
                    var clues = JsonNodeFactory.instance.arrayNode();
                    var uniquePersons =
                            new TreeMap<String, JsonNode>(CommonUtil::compareCodePoints);
                    var uniqueClues = new TreeMap<String, JsonNode>(CommonUtil::compareCodePoints);
                    for (JsonNode projection : java.util.List.of(first, second)) {
                        for (JsonNode person : projection.get("persons"))
                            uniquePersons.put(person.get("code").textValue(), person);
                        for (JsonNode clue : projection.get("clues"))
                            uniqueClues.put(clue.get("code").textValue(), clue);
                    }
                    uniquePersons.values().forEach(persons::add);
                    uniqueClues.values().forEach(clues::add);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    policy.lock(evidence, owners);
                    db.update(
                            "INSERT INTO"
                                + " test_exposure(story_id,member_id,first_test_id,first_room_id)"
                                + " VALUES (?,?,NULL,?) ON CONFLICT DO NOTHING",
                            room.storyId(),
                            principal.memberId(),
                            room.id());
                    if (!Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM test_exposure WHERE story_id=? AND"
                                            + " member_id=? AND first_test_id IS NULL AND"
                                            + " first_room_id=?)",
                                    Boolean.class,
                                    room.storyId(),
                                    principal.memberId(),
                                    room.id()))) throw AuthException.conflict("BLIND_INELIGIBLE");
                    if ("BLIND".equals(room.kind())) blindExposure(room, principal.memberId());
                    db.update(
                            "INSERT INTO"
                                + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                + " VALUES (?,?,'MEMBER',?,'CONTENT_READ',?,'EXPOSED')",
                            UUID.randomUUID(),
                            room.id(),
                            principal.memberId(),
                            requestId);
                    return new Materials(
                            roomKey,
                            "RUNNING",
                            Long.toString(room.rev()),
                            room.deadlineAt(),
                            first.get("basic"),
                            roles,
                            persons,
                            clues,
                            requestId);
                });
    }

    /** 소유자와 현행 REVIEW·MFA·정책·런타임·LOCAL 세션을 한 거래에서 다시 검증한다. */
    private <T> T withReportRoom(
            String token, UUID key, java.util.function.BiFunction<Room, Long, T> command) {
        uuid(key);
        var principal = auth.authenticate(token, false);
        var evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    Set<Long> owners = roomOwners(key, evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = policy.lock(evidence, owners);
                    lockRoomRuntime(key);
                    Room room = room(key);
                    validateRoom(room, principal, notice, false);
                    if (!startedMember(room, principal.memberId())
                            || !Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM game_member WHERE"
                                                    + " room_id=? AND member_id=? AND"
                                                    + " identity_id=(SELECT identity_id FROM"
                                                    + " member_session WHERE id=?) AND active_yn)",
                                            Boolean.class,
                                            room.id(),
                                            principal.memberId(),
                                            principal.sessionId())))
                        throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    if ("BLIND".equals(room.kind())) blindExposure(room, principal.memberId());
                    T value = command.apply(room, principal.memberId());
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    policy.lock(evidence, owners);
                    return value;
                });
    }

    public ReportView getReport(String token, UUID key, UUID requestId) {
        uuid(requestId);
        return withReportRoom(token, key, (room, member) -> reportView(room, key, requestId));
    }

    private ReportView reportView(Room room, UUID key, UUID requestId) {
        if (!"RUNNING".equals(room.state())) throw AuthException.conflict("STATE_CONFLICT");
        var draft =
                db.queryForMap(
                        "SELECT draft_rev,draft_cipher,draft_hash FROM game_room WHERE id=?",
                        room.id());
        long revision = ((Number) draft.get("draft_rev")).longValue();
        var accepted =
                db.queryForList(
                        "SELECT r.id,r.source_rev,r.payload_cipher,r.payload_hash,j.report_hash"
                            + " FROM game_report r JOIN grade_job j ON j.game_report_id=r.id WHERE"
                            + " r.room_id=? AND r.state='ACCEPTED' AND r.purged_at IS NULL",
                        room.id());
        if (accepted.size() > 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
        if (accepted.isEmpty() && !room.deadlineAt().isAfter(dbNow()))
            throw AuthException.conflict("PLAY_DEADLINE_EXPIRED");
        JsonNode payload;
        if (accepted.isEmpty()) {
            payload =
                    draft.get("draft_cipher") == null
                            ? GameReportService.empty()
                            : reports.decrypt(
                                    (byte[]) draft.get("draft_cipher"),
                                    GameReportService.draftAad(room.id(), revision),
                                    (String) draft.get("draft_hash"));
        } else {
            var pin = accepted.getFirst();
            long sourceRev = ((Number) pin.get("source_rev")).longValue();
            payload =
                    reports.decrypt(
                            (byte[]) pin.get("payload_cipher"),
                            GameReportService.reportAad(
                                    ((Number) pin.get("id")).longValue(),
                                    room.id(),
                                    room.snapshotId(),
                                    sourceRev),
                            (String) pin.get("payload_hash"));
            if (revision != sourceRev
                    || !frozen(room)
                            .reportHash(GameReportService.complete(payload))
                            .equals(pin.get("report_hash")))
                throw AuthException.unavailable("GAME_UNAVAILABLE");
        }
        return new ReportView(
                key,
                Long.toString(room.rev()),
                Long.toString(revision),
                payload,
                accepted.isEmpty() ? "NONE" : "PENDING",
                requestId);
    }

    public ReportAction editReport(
            String token,
            UUID key,
            long expectedRev,
            long expectedDraftRev,
            JsonNode report,
            UUID requestKey,
            UUID requestId) {
        uuid(key);
        uuid(requestKey);
        uuid(requestId);
        if (expectedRev < 0
                || expectedRev == Long.MAX_VALUE
                || expectedDraftRev < 0
                || expectedDraftRev == Long.MAX_VALUE)
            throw AuthException.badRequest("INVALID_REQUEST");
        byte[] bytes = GameReportService.checkedDraft(report);
        String hash = SnapshotJson.hash(report);
        String intent =
                digestFields(
                        "QUALIFICATION_REPORT_EDIT",
                        key.toString(),
                        Long.toString(expectedRev),
                        Long.toString(expectedDraftRev),
                        hash);
        return withReportRoom(
                token,
                key,
                (room, member) -> {
                    lockRequest(requestKey);
                    var prior = receipt(requestKey);
                    if (prior != null)
                        return reportReplay(
                                prior,
                                room,
                                key,
                                member,
                                "QUALIFICATION_REPORT_EDIT",
                                intent,
                                requestKey,
                                requestId);
                    requireEditable(room);
                    var draft =
                            db.queryForMap(
                                    "SELECT draft_rev,draft_hash FROM game_room WHERE id=?",
                                    room.id());
                    long revision = ((Number) draft.get("draft_rev")).longValue();
                    if (room.rev() != expectedRev || revision != expectedDraftRev)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    boolean changed =
                            !hash.equals(draft.get("draft_hash"))
                                    && !(draft.get("draft_hash") == null
                                            && report.equals(GameReportService.empty()));
                    if (changed
                            && db.update(
                                            "UPDATE game_room SET"
                                                + " draft_cipher=?,draft_hash=?,draft_rev=draft_rev+1,rev=rev+1,updated_at=clock_timestamp()"
                                                + " WHERE id=? AND rev=? AND draft_rev=? AND"
                                                + " state='RUNNING' AND"
                                                + " deadline_at>clock_timestamp()",
                                            crypto.encrypt(
                                                            new String(
                                                                    bytes, StandardCharsets.UTF_8),
                                                            GameReportService.draftAad(
                                                                    room.id(), revision + 1))
                                                    .getBytes(StandardCharsets.UTF_8),
                                            hash,
                                            room.id(),
                                            expectedRev,
                                            revision)
                                    != 1) throw AuthException.conflict("EDIT_CONFLICT");
                    return reportAction(
                            room,
                            key,
                            member,
                            "QUALIFICATION_REPORT_EDIT",
                            intent,
                            changed,
                            null,
                            requestKey,
                            requestId);
                });
    }

    public ReportAction submitReport(
            String token,
            UUID key,
            long expectedRev,
            long expectedDraftRev,
            UUID requestKey,
            UUID requestId) {
        uuid(key);
        uuid(requestKey);
        uuid(requestId);
        if (expectedRev < 0
                || expectedRev == Long.MAX_VALUE
                || expectedDraftRev < 0
                || expectedDraftRev == Long.MAX_VALUE)
            throw AuthException.badRequest("INVALID_REQUEST");
        String intent =
                digestFields(
                        "QUALIFICATION_REPORT_SUBMIT",
                        key.toString(),
                        Long.toString(expectedRev),
                        Long.toString(expectedDraftRev));
        return withReportRoom(
                token,
                key,
                (room, member) -> {
                    lockRequest(requestKey);
                    var prior = receipt(requestKey);
                    if (prior != null)
                        return reportReplay(
                                prior,
                                room,
                                key,
                                member,
                                "QUALIFICATION_REPORT_SUBMIT",
                                intent,
                                requestKey,
                                requestId);
                    requireEditable(room);
                    var draft =
                            db.queryForMap(
                                    "SELECT"
                                        + " draft_rev,draft_cipher,draft_hash,graded_count,max_attempts,runtime_epoch,config_hash,rubric_hash"
                                        + " FROM game_room WHERE id=?",
                                    room.id());
                    long revision = ((Number) draft.get("draft_rev")).longValue();
                    if (room.rev() != expectedRev || revision != expectedDraftRev)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    if (draft.get("draft_cipher") == null)
                        throw AuthException.badRequest("INVALID_REPORT");
                    JsonNode fixed =
                            reports.decrypt(
                                    (byte[]) draft.get("draft_cipher"),
                                    GameReportService.draftAad(room.id(), revision),
                                    (String) draft.get("draft_hash"));
                    var parsed = GameReportService.complete(fixed);
                    var snapshot = frozen(room);
                    boolean known = false;
                    for (JsonNode person : snapshot.payload().path("resources").path("persons"))
                        if (parsed.culpritCode().equals(person.path("code").textValue()))
                            known = true;
                    if (!known) throw AuthException.badRequest("INVALID_REPORT");
                    int count = ((Number) draft.get("graded_count")).intValue();
                    int limit = ((Number) draft.get("max_attempts")).intValue();
                    if (count < 0 || count >= limit || count >= 5)
                        throw AuthException.conflict("ATTEMPTS_EXHAUSTED");
                    var runtime =
                            runtimes.lockRuntime(room.runtimeId())
                                    .orElseThrow(
                                            () -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
                    if (!"AVAILABLE".equals(runtime.state())
                            || runtime.epoch() != ((Number) draft.get("runtime_epoch")).longValue()
                            || !Objects.equals(runtime.configHash(), draft.get("config_hash"))
                            || !snapshot.rubricHash().equals(draft.get("rubric_hash"))
                            || installations.get(runtime.code()) == null)
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    try {
                        if (!installations
                                .get(runtime.code())
                                .verify(runtime)
                                .profile()
                                .policyCode()
                                .equals(
                                        snapshot.payload()
                                                .path("policy")
                                                .path("policyCode")
                                                .textValue()))
                            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    } catch (RuntimeException failure) {
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    }
                    var accepted =
                            reports.accept(
                                    room.id(),
                                    room.snapshotId(),
                                    room.runtimeId(),
                                    member,
                                    expectedRev,
                                    revision,
                                    count + 1,
                                    fixed,
                                    parsed,
                                    snapshot,
                                    (String) draft.get("config_hash"));
                    return reportAction(
                            room,
                            key,
                            member,
                            "QUALIFICATION_REPORT_SUBMIT",
                            intent,
                            true,
                            accepted,
                            requestKey,
                            requestId);
                });
    }

    private void requireEditable(Room room) {
        if (!"RUNNING".equals(room.state())) throw AuthException.conflict("STATE_CONFLICT");
        if (room.deadlineAt() == null || !room.deadlineAt().isAfter(dbNow()))
            throw AuthException.conflict("PLAY_DEADLINE_EXPIRED");
        if (Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM game_report WHERE room_id=? AND state IN"
                                + " ('PROPOSED','ACCEPTED'))",
                        Boolean.class,
                        room.id()))) throw AuthException.conflict("SUBMISSION_PENDING");
    }

    private ReportAction reportReplay(
            Receipt prior,
            Room room,
            UUID key,
            long member,
            String action,
            String intent,
            UUID requestKey,
            UUID requestId) {
        if (prior.roomId() != room.id()
                || prior.memberId() == null
                || prior.memberId() != member
                || !action.equals(prior.action())
                || !intent.equals(prior.hash()))
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        String original =
                db.queryForObject(
                        "SELECT result_data::text FROM game_action WHERE request_key=?",
                        String.class,
                        requestKey);
        return new ReportAction(
                action,
                true,
                false,
                SnapshotJson.parse(original.getBytes(StandardCharsets.UTF_8)),
                reportView(room, key, requestId),
                requestId);
    }

    private ReportAction reportAction(
            Room room,
            UUID key,
            long member,
            String action,
            String intent,
            boolean changed,
            GameReportService.Accepted accepted,
            UUID requestKey,
            UUID requestId) {
        long rev = db.queryForObject("SELECT rev FROM game_room WHERE id=?", Long.class, room.id());
        long draftRev =
                db.queryForObject(
                        "SELECT draft_rev FROM game_room WHERE id=?", Long.class, room.id());
        var original =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("roomKey", key.toString())
                        .put("rev", Long.toString(rev))
                        .put("draftRev", Long.toString(draftRev))
                        .put("state", room.state())
                        .put("changed", changed);
        if (accepted != null)
            original.put("reportKey", accepted.reportKey().toString())
                    .put("jobKey", accepted.jobKey().toString())
                    .put("ordinal", accepted.ordinal())
                    .put("acceptedAt", accepted.acceptedAt().toString())
                    .put("gradeDeadline", accepted.deadlineAt().toString());
        if (db.update(
                                "INSERT INTO"
                                    + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                    + " VALUES (?,?,?,?,?,?::jsonb)",
                                requestKey,
                                member,
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
                                member,
                                action,
                                requestId,
                                changed ? "CHANGED" : "UNCHANGED")
                        != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
        Room current = room(key);
        return new ReportAction(
                action, false, changed, original, reportView(current, key, requestId), requestId);
    }

    private Instant dbNow() {
        return db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /** 현재 검수 참가자에게만 완료 후 24시간 이내의 실제 채점 결과를 공개한다. */
    public ResultView getResult(String token, UUID key, UUID requestId) {
        uuid(requestId);
        return withReportRoom(
                token,
                key,
                (room, member) -> {
                    if (!"ENDED".equals(room.state()))
                        throw AuthException.conflict("RESULT_NOT_READY");
                    var terminal =
                            db.queryForMap(
                                    "SELECT"
                                        + " end_reason,ended_at,final_score,graded_count,wrong_count"
                                        + " FROM game_room WHERE id=?",
                                    room.id());
                    Instant ended = ((Timestamp) terminal.get("ended_at")).toInstant();
                    if (!ended.plusSeconds(86400).isAfter(dbNow()))
                        throw AuthException.conflict("RESULT_EXPIRED");
                    String reason = (String) terminal.get("end_reason");
                    boolean scored =
                            Set.of("SUCCESS", "TIME_LIMIT", "ATTEMPTS_EXHAUSTED").contains(reason);
                    var grades = new java.util.ArrayList<JsonNode>();
                    String reveal = null;
                    JsonNode rules = null;
                    if (scored) {
                        var rows =
                                db.queryForList(
                                        "SELECT"
                                            + " r.ordinal,r.base_score,r.success_yn,r.score_data::text"
                                            + " AS score_data,j.state AS job_state FROM game_report"
                                            + " r JOIN grade_job j ON j.game_report_id=r.id WHERE"
                                            + " r.room_id=? AND r.state='GRADED' ORDER BY"
                                            + " r.ordinal",
                                        room.id());
                        if (rows.size() != ((Number) terminal.get("graded_count")).intValue()
                                || terminal.get("final_score") == null)
                            throw AuthException.unavailable("GAME_UNAVAILABLE");
                        for (var row : rows) {
                            if (!"COMPLETED".equals(row.get("job_state")))
                                throw AuthException.unavailable("GAME_UNAVAILABLE");
                            JsonNode score =
                                    SnapshotJson.parse(
                                            ((String) row.get("score_data"))
                                                    .getBytes(StandardCharsets.UTF_8));
                            if (score.path("baseScore").asInt(-1)
                                            != ((Number) row.get("base_score")).intValue()
                                    || !score.path("success").isBoolean()
                                    || score.path("success").booleanValue()
                                            != (Boolean) row.get("success_yn"))
                                throw AuthException.unavailable("GAME_UNAVAILABLE");
                            grades.add(
                                    JsonNodeFactory.instance
                                            .objectNode()
                                            .put(
                                                    "ordinal",
                                                    ((Number) row.get("ordinal")).intValue())
                                            .put(
                                                    "baseScore",
                                                    ((Number) row.get("base_score")).intValue())
                                            .put("success", (Boolean) row.get("success_yn"))
                                            .put("score", score.path("score").intValue()));
                        }
                        var snapshot = frozen(room).payload();
                        int penalty = snapshot.path("policy").path("wrongPenalty").asInt(-1);
                        int attempts =
                                db.queryForObject(
                                        "SELECT max_attempts FROM game_room WHERE id=?",
                                        Integer.class,
                                        room.id());
                        if (penalty < 0 || penalty > 100 || attempts < 1 || attempts > 5)
                            throw AuthException.unavailable("GAME_UNAVAILABLE");
                        rules =
                                JsonNodeFactory.instance
                                        .objectNode()
                                        .put("wrongPenalty", penalty)
                                        .put("attemptLimit", attempts)
                                        .put(
                                                "formula",
                                                "max(0, baseScore - wrongCount * wrongPenalty)");
                        reveal =
                                snapshot.path("sections")
                                        .path("reveal")
                                        .path("revealText")
                                        .textValue();
                    }
                    return new ResultView(
                            key,
                            room.state(),
                            reason,
                            ended,
                            scored ? ((Number) terminal.get("final_score")).intValue() : null,
                            ((Number) terminal.get("graded_count")).intValue(),
                            ((Number) terminal.get("wrong_count")).intValue(),
                            List.copyOf(grades),
                            rules,
                            reveal,
                            requestId);
                });
    }

    /** BLIND 검수 참가자의 명시적 피드백만 감사·영수증과 함께 암호화하여 접수한다. */
    public FeedbackResult submitFeedback(
            String token, UUID key, String feedback, UUID requestKey, UUID requestId) {
        uuid(key);
        uuid(requestKey);
        uuid(requestId);
        String normalized;
        try {
            normalized = CommonUtil.normalizeText(feedback, 8000, true);
        } catch (IllegalArgumentException failure) {
            throw AuthException.badRequest("INVALID_FEEDBACK");
        }
        if (normalized == null || normalized.isBlank() || !normalized.equals(feedback))
            throw AuthException.badRequest("INVALID_FEEDBACK");
        String intent = digestFields("QUALIFICATION_FEEDBACK", key.toString(), normalized);
        return withReportRoom(
                token,
                key,
                (room, member) -> {
                    lockRequest(requestKey);
                    if (!"BLIND".equals(room.kind()) || !"ENDED".equals(room.state()))
                        throw AuthException.conflict("STATE_CONFLICT");
                    Instant ended =
                            db.queryForObject(
                                            "SELECT ended_at FROM game_room WHERE id=?",
                                            Timestamp.class,
                                            room.id())
                                    .toInstant();
                    if (!ended.plusSeconds(86400).isAfter(dbNow()))
                        throw AuthException.conflict("FEEDBACK_EXPIRED");
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        if (prior.roomId() != room.id()
                                || prior.memberId() == null
                                || prior.memberId() != member
                                || !"QUALIFICATION_FEEDBACK".equals(prior.action())
                                || !intent.equals(prior.hash()))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        Timestamp submitted =
                                db.queryForObject(
                                        "SELECT submitted_at FROM release_solo_feedback WHERE"
                                                + " room_id=? AND member_id=?",
                                        Timestamp.class,
                                        room.id(),
                                        member);
                        if (submitted == null) throw AuthException.conflict("STATE_CONFLICT");
                        return new FeedbackResult(key, true, submitted.toInstant(), requestId);
                    }
                    byte[] cipher =
                            crypto.encrypt(
                                            normalized,
                                            "release_solo_feedback/"
                                                    + room.id()
                                                    + "/member/"
                                                    + member
                                                    + "/v1")
                                    .getBytes(StandardCharsets.UTF_8);
                    if (db.update(
                                    "INSERT INTO"
                                        + " release_solo_feedback(room_id,member_id,feedback_cipher,ended_at,raw_until)"
                                        + " SELECT id,?, ?,ended_at,ended_at+interval '90 days'"
                                        + " FROM game_room WHERE id=? AND"
                                        + " purpose='REVIEW_SOLO_QUALIFICATION' AND"
                                        + " qualification_kind='BLIND' AND state='ENDED' AND"
                                        + " ended_at+interval '24 hours'>clock_timestamp() ON"
                                        + " CONFLICT DO NOTHING",
                                    member,
                                    cipher,
                                    room.id())
                            != 1) throw AuthException.conflict("FEEDBACK_ALREADY_SUBMITTED");
                    var submitted =
                            db.queryForObject(
                                    "SELECT submitted_at FROM release_solo_feedback WHERE"
                                            + " room_id=?",
                                    Timestamp.class,
                                    room.id());
                    var original =
                            JsonNodeFactory.instance
                                    .objectNode()
                                    .put("roomKey", key.toString())
                                    .put("submittedAt", submitted.toInstant().toString());
                    if (db.update(
                                            "INSERT INTO"
                                                + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                                + " VALUES"
                                                + " (?,?,?,'QUALIFICATION_FEEDBACK',?,?::jsonb)",
                                            requestKey,
                                            member,
                                            room.id(),
                                            intent,
                                            original.toString())
                                    != 1
                            || db.update(
                                            "INSERT INTO"
                                                + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                                + " VALUES"
                                                + " (?,?,'MEMBER',?,'QUALIFICATION_FEEDBACK',?,'ACCEPTED')",
                                            UUID.randomUUID(),
                                            room.id(),
                                            member,
                                            requestId)
                                    != 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
                    return new FeedbackResult(key, false, submitted.toInstant(), requestId);
                });
    }

    private boolean startedMember(Room room, long memberId) {
        return room.roleA() != null
                && room.roleB() != null
                && Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT (SELECT count(*) FROM game_member WHERE room_id=? AND"
                                        + " active_yn)=1 AND EXISTS(SELECT 1 FROM game_member WHERE"
                                        + " room_id=? AND member_id=? AND active_yn AND slot=1 AND"
                                        + " policy_id=? AND version_id=? AND role_code=?)",
                                Boolean.class,
                                room.id(),
                                room.id(),
                                memberId,
                                room.policyId(),
                                room.versionId(),
                                room.roleA()));
    }

    private FrozenSnapshotCodec.FrozenSnapshot frozen(Room room) {
        String source =
                db.query(
                        "SELECT payload::text FROM review_snapshot WHERE id=? AND version_id=?",
                        rs -> rs.next() ? rs.getString(1) : null,
                        room.snapshotId(),
                        room.versionId());
        if (source == null) throw AuthException.conflict("SNAPSHOT_UNAVAILABLE");
        try {
            var snapshot = FrozenSnapshotCodec.decode(source.getBytes(StandardCharsets.UTF_8));
            String rubricHash =
                    db.queryForObject(
                            "SELECT rubric_hash FROM game_room WHERE id=?",
                            String.class,
                            room.id());
            if (!snapshot.rubricHash().equals(rubricHash))
                throw AuthException.conflict("SNAPSHOT_UNAVAILABLE");
            return snapshot;
        } catch (IllegalArgumentException failure) {
            throw AuthException.conflict("SNAPSHOT_UNAVAILABLE");
        }
    }

    private record Room(
            long id,
            long storyId,
            long versionId,
            long snapshotId,
            Long assessmentId,
            long reviewerId,
            long policyId,
            long runtimeId,
            String kind,
            String state,
            boolean lobbyOpen,
            UUID selectedMember,
            String roleA,
            String roleB,
            long rev,
            Instant startedAt,
            Instant deadlineAt) {}

    private void lockRoomRuntime(UUID key) {
        Long runtimeId =
                db.query(
                        "SELECT runtime_id FROM game_room WHERE room_key=?"
                                + " AND purpose='REVIEW_SOLO_QUALIFICATION'",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        key);
        if (runtimeId == null) throw AuthException.conflict("ROOM_UNAVAILABLE");
        var parents =
                db.queryForMap(
                        "SELECT story_id,version_id,reviewer_id FROM game_room WHERE room_key=?",
                        key);
        db.queryForList("SELECT id FROM story WHERE id=? FOR SHARE", parents.get("story_id"));
        db.queryForList(
                "SELECT id FROM story_version WHERE id=? FOR SHARE", parents.get("version_id"));
        db.queryForList(
                "SELECT admin_id FROM story_access WHERE story_id=? AND admin_id=?"
                        + " AND permission='REVIEW' FOR SHARE",
                parents.get("story_id"),
                parents.get("reviewer_id"));
        if (runtimes.lockRuntime(runtimeId).isEmpty())
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
    }

    private Room room(UUID key) {
        var rooms =
                db.query(
                        """
                        SELECT r.id,r.story_id,r.version_id,r.snapshot_id,r.assessment_id,r.reviewer_id,r.policy_id,r.runtime_id,
                          r.qualification_kind,r.state,r.lobby_until>clock_timestamp(),
                          (SELECT m.member_key::text FROM member_account m WHERE m.id=r.selected_member_id),
                          (SELECT a.result_data->>'roleA' FROM game_action a WHERE a.room_id=r.id
                            AND a.action='QUALIFICATION_CREATE'),
                          (SELECT a.result_data->>'roleB' FROM game_action a WHERE a.room_id=r.id
                            AND a.action='QUALIFICATION_CREATE'),r.rev,r.started_at,r.deadline_at
                        FROM game_room r WHERE r.room_key=? AND r.purpose='REVIEW_SOLO_QUALIFICATION' FOR UPDATE OF r
                        """,
                        (rs, n) ->
                                new Room(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        (Long) rs.getObject(5),
                                        rs.getLong(6),
                                        rs.getLong(7),
                                        rs.getLong(8),
                                        rs.getString(9),
                                        rs.getString(10),
                                        rs.getBoolean(11),
                                        rs.getString(12) == null
                                                ? null
                                                : UUID.fromString(rs.getString(12)),
                                        rs.getString(13),
                                        rs.getString(14),
                                        rs.getLong(15),
                                        rs.getTimestamp(16) == null
                                                ? null
                                                : rs.getTimestamp(16).toInstant(),
                                        rs.getTimestamp(17) == null
                                                ? null
                                                : rs.getTimestamp(17).toInstant()),
                        key);
        if (rooms.size() != 1) throw AuthException.conflict("ROOM_UNAVAILABLE");
        return rooms.getFirst();
    }

    private void validateRoom(
            Room room,
            MemberAuthService.MemberPrincipal principal,
            PlaytestPolicyGate.Notice notice,
            boolean requireOpen) {
        if (room.policyId() != notice.policyId()) throw AuthException.conflict("POLICY_CHANGED");
        if (!principal.memberKey().equals(room.selectedMember()))
            throw AuthException.conflict("ROOM_UNAVAILABLE");
        if ("RUNNING".equals(room.state())
                && (room.roleA() == null
                        || room.roleB() == null
                        || !Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT role_a=? AND role_b=? FROM game_room WHERE id=?",
                                        Boolean.class,
                                        room.roleA(),
                                        room.roleB(),
                                        room.id()))
                        || !Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM story_pair WHERE version_id=?"
                                                + " AND role_a=? AND role_b=? AND active_yn)",
                                        Boolean.class,
                                        room.versionId(),
                                        room.roleA(),
                                        room.roleB()))))
            throw AuthException.conflict("PAIR_UNAVAILABLE");
        RuntimeRow runtime =
                runtimes.lockRuntime(room.runtimeId())
                        .orElseThrow(() -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
        var installed = installations.get(runtime.code());
        if (!"AVAILABLE".equals(runtime.state())
                || installed == null
                || !installed
                        .verify(runtime)
                        .profile()
                        .policyCode()
                        .equals(
                                db.queryForObject(
                                        "SELECT policy_code FROM story_version WHERE id=?",
                                        String.class,
                                        room.versionId())))
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM story s JOIN story_version v ON v.story_id=s.id
                          JOIN admin_account a ON a.id=? JOIN admin_credential c ON c.account_id=a.id
                          JOIN story_access x ON x.story_id=s.id AND x.admin_id=a.id
                          JOIN grade_runtime r ON r.id=(SELECT runtime_id FROM game_room WHERE id=?)
                          WHERE s.id=? AND s.active_yn AND v.id=? AND v.active_yn AND v.status='REVIEW'
                            AND v.current_snapshot_id=? AND a.active_yn AND a.can_review
                            AND c.enrolled_at IS NOT NULL AND c.mfa_state='READY'
                            AND x.permission='REVIEW' AND x.active_yn AND r.state='AVAILABLE'
                            AND r.epoch=(SELECT runtime_epoch FROM game_room WHERE id=?)
                            AND r.config_hash=(SELECT config_hash FROM game_room WHERE id=?))
                        """,
                        Boolean.class,
                        room.reviewerId(),
                        room.id(),
                        room.storyId(),
                        room.versionId(),
                        room.snapshotId(),
                        room.id(),
                        room.id()))) throw AuthException.conflict("ROOM_UNAVAILABLE");
        if (requireOpen && (!"WAITING".equals(room.state()) || !room.lobbyOpen()))
            throw AuthException.conflict("STATE_CONFLICT");
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM member_account WHERE id=? AND state='ACTIVE')",
                        Boolean.class,
                        principal.memberId())))
            throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
    }

    private Member member(UUID key) {
        var rows =
                db.query(
                        "SELECT id FROM member_account WHERE member_key=? AND state='ACTIVE' FOR"
                                + " UPDATE",
                        (rs, n) -> rs.getLong(1),
                        key);
        if (rows.size() != 1) throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
        var identities =
                db.query(
                        "SELECT id FROM member_identity WHERE member_id=? AND provider='LOCAL' AND"
                            + " realm='LOCAL' AND active_yn AND proof_at IS NOT NULL FOR UPDATE",
                        (rs, n) -> rs.getLong(1),
                        rows.getFirst());
        if (identities.size() != 1) throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
        return new Member(rows.getFirst(), identities.getFirst());
    }

    private void blindAdmission(Room room, long memberId) {
        if (room.assessmentId() == null) throw AuthException.conflict("BLIND_INELIGIBLE");
        eligibility.requirePinnedGameAdmission(
                room.assessmentId(), room.storyId(), room.snapshotId(), memberId, room.id());
    }

    private void blindExposure(Room room, long memberId) {
        if (room.assessmentId() == null) throw AuthException.conflict("BLIND_INELIGIBLE");
        eligibility.requirePinnedGameExposure(
                room.assessmentId(), room.storyId(), room.snapshotId(), memberId, room.id());
    }

    /** 자기 최초 원문 제공 이후에는 동일 출처의 원문 재조회 규칙을 사용한다. */
    private void blindCurrent(Room room, long memberId) {
        Boolean ownSource =
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM test_exposure WHERE story_id=? AND member_id=?"
                                + " AND first_room_id=? AND first_test_id IS NULL)",
                        Boolean.class,
                        room.storyId(),
                        memberId,
                        room.id());
        if (Boolean.TRUE.equals(ownSource)) blindExposure(room, memberId);
        else blindAdmission(room, memberId);
    }

    private Set<Long> owners(long policyOwner, long memberId) {
        TreeSet<Long> owners = new TreeSet<>(memberPolicy.ownerIds(memberId));
        owners.add(policyOwner);
        return owners;
    }

    /** 실제 검수자도 정렬된 관리자 잠금 집합에 포함하여 검수 자격 회수와 직렬화한다. */
    private Set<Long> roomOwners(UUID roomKey, long policyOwner, long memberId) {
        var result = new TreeSet<>(owners(policyOwner, memberId));
        var reviewers =
                db.queryForList(
                        "SELECT reviewer_id FROM game_room WHERE room_key=?"
                                + " AND purpose='REVIEW_SOLO_QUALIFICATION'",
                        Long.class,
                        roomKey);
        if (reviewers.size() != 1) throw AuthException.conflict("ROOM_UNAVAILABLE");
        result.add(reviewers.getFirst());
        return result;
    }

    private void lockOwners(Set<Long> owners) {
        for (long id : owners) {
            if (db.query(
                                    "SELECT id FROM admin_account WHERE id=? FOR SHARE",
                                    (rs, n) -> rs.getLong(1),
                                    id)
                            .size()
                    != 1) throw AuthException.conflict("POLICY_CHANGED");
            db.queryForList(
                    "SELECT account_id FROM admin_credential WHERE account_id=? FOR SHARE", id);
        }
    }

    private void bound(String token, MemberAuthService.MemberPrincipal principal) {
        String hash = HexFormat.of().formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", token));
        db.query(
                "SELECT id FROM member_account WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                principal.memberId());
        db.query(
                "SELECT id FROM member_session WHERE id=? AND member_id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                principal.sessionId(),
                principal.memberId());
        db.query(
                "SELECT id FROM member_identity WHERE id=(SELECT identity_id FROM member_session"
                        + " WHERE id=?) FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                principal.sessionId());
        db.query(
                "SELECT id FROM member_token WHERE token_hash=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                hash);
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM member_account a JOIN member_session s ON s.member_id=a.id
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
                        hash))) throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
    }

    private Receipt receipt(UUID key) {
        var rows =
                db.query(
                        """
                        SELECT a.room_id,a.admin_id,a.member_id,a.action,a.request_hash,r.room_key,
                          a.result_data->>'state',(a.result_data->>'rev')::bigint,
                          (a.result_data->>'startedMicros')::bigint,
                          (a.result_data->>'deadlineMicros')::bigint
                        FROM game_action a JOIN game_room r ON r.id=a.room_id
                        WHERE a.request_key=?
                        """,
                        (rs, n) ->
                                new Receipt(
                                        rs.getLong(1),
                                        (Long) rs.getObject(2),
                                        (Long) rs.getObject(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getObject(6, UUID.class),
                                        rs.getString(7),
                                        (Long) rs.getObject(8),
                                        (Long) rs.getObject(9),
                                        (Long) rs.getObject(10)),
                        key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void lockRequest(UUID key) {
        db.query(
                "SELECT pg_advisory_xact_lock(?)",
                rs -> null,
                key.getMostSignificantBits() ^ key.getLeastSignificantBits());
    }

    private static void checkCurrent(String status, Long actual, long selected) {
        if (!"REVIEW".equals(status) || actual == null || actual != selected)
            throw AuthException.conflict("QUALIFICATION_INVALIDATED");
    }

    private static void uuid(UUID key) {
        if (key == null || key.version() != 4 || key.variant() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static String digestFields(String... fields) {
        try {
            StringBuilder normalized = new StringBuilder();
            for (String field : fields) normalized.append(field.length()).append(':').append(field);
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
