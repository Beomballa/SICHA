package com.reasoning.common.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.model.GradeModels.Report;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.StoryReviewPreview;
import com.reasoning.common.story.service.StoryReleaseEvidenceService;
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
import java.security.SecureRandom;
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

/** 실제 공개 근거가 결속된 사건만 PUBLIC 방에 입장시키고 역할별 고정 사본을 투영한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PublicGameService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate db;
    private final MemberAuthService auth;
    private final MemberPolicyGate memberPolicy;
    private final PlaytestPolicyGate friendly;
    private final GradeRuntimeRepository runtimes;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final CryptoService crypto;
    private final GameReportService reports;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final StoryReleaseEvidenceService releaseEvidence;
    private final GameRecordService records;

    public PublicGameService(
            JdbcTemplate db,
            MemberAuthService auth,
            MemberPolicyGate memberPolicy,
            @Qualifier("friendlyPolicyGate") PlaytestPolicyGate friendly,
            GradeRuntimeRepository runtimes,
            @Qualifier("gradeInstallations")
                    Map<String, InstalledRuntimeManifestVerifier> installations,
            CryptoService crypto,
            ObjectMapper json,
            StoryReleaseEvidenceService releaseEvidence,
            GameRecordService records) {
        this.db = db;
        this.auth = auth;
        this.memberPolicy = memberPolicy;
        this.friendly = friendly;
        this.runtimes = runtimes;
        this.installations = Map.copyOf(installations);
        this.crypto = crypto;
        this.reports = new GameReportService(db, crypto);
        this.json = json;
        this.releaseEvidence = releaseEvidence;
        this.records = records;
        tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record StorySummary(
            String storyCode,
            String title,
            int difficulty,
            int estMin,
            int estMax,
            int limitSec,
            int playerCount,
            int publishedVersionNo,
            String playRev) {}

    public record StoryPublic(
            String storyCode,
            String title,
            int difficulty,
            int estMin,
            int estMax,
            int limitSec,
            int playerCount,
            int publishedVersionNo,
            String playRev,
            String intro,
            String setting,
            String policyCode,
            String noticeHash,
            String noticeSummary) {}

    public record Page(List<StorySummary> items, String nextCursor, UUID requestId) {}

    public record Notice(
            String policyCode,
            String noticeHash,
            String version,
            String body,
            String contact,
            UUID requestId) {}

    public record Action(
            String action,
            boolean replayed,
            boolean changed,
            Object original,
            Object current,
            UUID requestId) {}

    public enum GameMode {
        SOLO,
        COOP
    }

    public record GameState(
            UUID gameKey,
            GameMode mode,
            String title,
            String state,
            String outcome,
            String endReason,
            String rev,
            String draftRev,
            boolean host,
            Self self,
            Partner partner,
            int publishedVersionNo,
            String playRev,
            boolean needsRefresh,
            Instant lobbyExpiresAt,
            Instant startedAt,
            Instant playDeadline,
            Instant serverTime,
            String submissionState,
            Integer attemptsRemaining,
            Integer hintsRemaining,
            boolean canReady,
            boolean canStart,
            String blockedReason,
            UUID requestId) {}

    public record Self(int slot, boolean ready, boolean effectiveReady, String roleCode) {}

    public record Partner(boolean joined, boolean ready, boolean effectiveReady, boolean online) {}

    public record Materials(
            UUID gameKey,
            String state,
            String rev,
            Instant playDeadline,
            JsonNode basic,
            JsonNode roles,
            JsonNode persons,
            JsonNode clues,
            JsonNode openedHints,
            JsonNode requiredNotices,
            int hintsRemaining,
            UUID requestId) {}

    public record ReportView(
            UUID gameKey,
            String rev,
            String draftRev,
            JsonNode report,
            String submissionState,
            UUID requestId) {}

    record Selection(
            long storyId,
            long versionId,
            long snapshotId,
            long releaseId,
            long runtimeId,
            long runtimeEpoch,
            long sourceRev,
            long playRev,
            int versionNo,
            String code,
            String title,
            String intro,
            String setting,
            String policyCode,
            int difficulty,
            int estMin,
            int estMax,
            int limitSec,
            String configHash,
            String evidenceHash,
            String bundle,
            boolean blocked) {}

    record Room(
            long id,
            UUID key,
            String mode,
            long storyId,
            long versionId,
            long snapshotId,
            long runtimeId,
            long runtimeEpoch,
            long policyId,
            long playRev,
            long rev,
            String state,
            String title,
            int versionNo,
            int limitSec,
            int maxAttempts,
            int maxHints,
            int gradedCount,
            String roleA,
            String roleB,
            Instant lobbyUntil,
            Instant startedAt,
            Instant deadlineAt) {}

    record Receipt(long roomId, long memberId, String action, String hash) {}

    record LobbyActor(
            MemberAuthService.MemberPrincipal principal, long identityId, long policyId) {}

    /** 방 관련 회원 전체를 ID 순서대로 잠그고 현재 토큰과 FRIENDLY 고지를 재확인한다. */
    <T> T withLobby(
            String token,
            UUID key,
            boolean guestAllowed,
            java.util.function.BiFunction<Room, LobbyActor, T> operation) {
        uuid(key);
        var principal = auth.authenticate(token, false);
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    lockTimeout();
                    Long id =
                            db.query(
                                    "SELECT id FROM game_room WHERE room_key=? AND purpose='PUBLIC'"
                                            + " AND mode='COOP'",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    key);
                    if (id == null) throw missing();
                    List<Long> discovered =
                            db.queryForList(
                                    "SELECT member_id FROM game_member WHERE room_id=? ORDER BY"
                                            + " member_id",
                                    Long.class,
                                    id);
                    Set<Long> owners = new TreeSet<>();
                    owners.add(evidence.owner());
                    for (long member : discovered) owners.addAll(memberPolicy.ownerIds(member));
                    owners.addAll(memberPolicy.ownerIds(principal.memberId()));
                    lockOwners(owners);
                    Set<Long> members = new TreeSet<>(discovered);
                    members.add(principal.memberId());
                    for (long member : members)
                        db.queryForList(
                                "SELECT id FROM member_account WHERE id=? FOR UPDATE", member);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var memberPermits =
                            new java.util.ArrayList<Map.Entry<Long, MemberPolicyGate.Permit>>();
                    for (long member :
                            db.queryForList(
                                    "SELECT member_id FROM game_member WHERE room_id=? AND"
                                            + " active_yn ORDER BY member_id",
                                    Long.class,
                                    id))
                        if (member != principal.memberId())
                            memberPermits.add(
                                    Map.entry(
                                            member,
                                            memberPolicy.lock(memberEvidence, member, owners)));
                    var notice = friendly.lock(evidence, owners);
                    var candidate = roomById(id, false);
                    if (candidate == null) throw missing();
                    List<Map<String, Object>> publishedParents =
                            "WAITING".equals(candidate.state())
                                    ? db.queryForList(
                                            "SELECT v.id AS version_id,x.runtime_id FROM story s"
                                                + " JOIN story_version v ON v.id=s.published_id AND"
                                                + " v.story_id=s.id JOIN story_release x ON"
                                                + " x.version_id=v.id AND"
                                                + " x.snapshot_id=v.current_snapshot_id WHERE"
                                                + " s.id=?",
                                            candidate.storyId())
                                    : List.of();
                    Long publishedRuntime =
                            publishedParents.isEmpty()
                                    ? null
                                    : ((Number) publishedParents.getFirst().get("runtime_id"))
                                            .longValue();
                    Long publishedVersion =
                            publishedParents.isEmpty()
                                    ? null
                                    : ((Number) publishedParents.getFirst().get("version_id"))
                                            .longValue();
                    Set<Long> runtimeIds = new TreeSet<>();
                    runtimeIds.add(candidate.runtimeId());
                    if (publishedRuntime != null) runtimeIds.add(publishedRuntime);
                    for (long runtimeId : runtimeIds)
                        runtimes.lockRuntime(runtimeId)
                                .orElseThrow(() -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
                    db.queryForList(
                            "SELECT id FROM story WHERE id=? FOR SHARE", candidate.storyId());
                    Set<Long> versionIds = new TreeSet<>();
                    versionIds.add(candidate.versionId());
                    if (publishedVersion != null) versionIds.add(publishedVersion);
                    for (long versionId : versionIds)
                        db.queryForList(
                                "SELECT id FROM story_version WHERE id=? FOR SHARE", versionId);
                    Selection published =
                            "WAITING".equals(candidate.state())
                                    ? selectionById(candidate.storyId(), true)
                                    : null;
                    if ("WAITING".equals(candidate.state())
                            && (!Objects.equals(
                                            publishedRuntime,
                                            published == null ? null : published.runtimeId())
                                    || !Objects.equals(
                                            publishedVersion,
                                            published == null ? null : published.versionId())))
                        throw AuthException.conflict("PREPARATION_CHANGED");
                    Room room = roomById(id);
                    List<Long> actual =
                            db.queryForList(
                                    "SELECT member_id FROM game_member WHERE room_id=? ORDER BY"
                                            + " member_id",
                                    Long.class,
                                    id);
                    if (!actual.equals(discovered)) throw AuthException.conflict("STATE_CONFLICT");
                    if (room.policyId() != notice.policyId())
                        throw AuthException.conflict("POLICY_CHANGED");
                    if (!guestAllowed
                            && !Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM game_member WHERE"
                                                    + " room_id=? AND member_id=? AND active_yn AND"
                                                    + " identity_id=(SELECT identity_id FROM"
                                                    + " member_session WHERE id=?))",
                                            Boolean.class,
                                            id,
                                            principal.memberId(),
                                            principal.sessionId()))) throw missing();
                    T answer =
                            operation.apply(
                                    room,
                                    new LobbyActor(
                                            principal,
                                            localIdentity(principal),
                                            notice.policyId()));
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    for (var other : memberPermits) {
                        memberPolicy.checkPinned(other.getValue(), other.getKey());
                        memberPolicy.check(other.getValue());
                    }
                    friendly.lock(evidence, owners);
                    return answer;
                });
    }

    /** 잠긴 사건의 현재 공개 후보와 근거·실행 설정을 확인한다. */
    Selection publishedLobbySelection(Room room) {
        Selection selected = selectionById(room.storyId(), true);
        if (selected == null) throw AuthException.conflict("RELEASE_BLOCKED");
        requireRelease(selected);
        if (!runtimeValid(selected)) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        return selected;
    }

    /** 준비·시작에서는 방의 기존 선택이 현행 후보와 정확히 일치해야 한다. */
    Selection lobbySelection(Room room) {
        Selection selected = publishedLobbySelection(room);
        if (selected.runtimeId() != room.runtimeId()
                || selected.runtimeEpoch() != room.runtimeEpoch()
                || !selected.configHash()
                        .equals(
                                db.queryForObject(
                                        "SELECT config_hash FROM game_room WHERE id=?",
                                        String.class,
                                        room.id())))
            throw AuthException.conflict("PREPARATION_CHANGED");
        return selected;
    }

    /** 현재 Bearer 회원의 고지 원본만 읽으며 수집 비활성 상태에서는 고지를 제공하지 않는다. */
    public Notice notice(String token, UUID requestId) {
        var principal = auth.authenticate(token, false);
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var current = friendly.lock(evidence, owners);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    return new Notice(
                            current.code(),
                            current.hash(),
                            current.version(),
                            current.body(),
                            current.contact(),
                            requestId);
                });
    }

    /** 공개 근거·현행 효력이 검증된 첫 페이지 및 인증 암호화 커서를 반환한다. */
    public Page stories(String token, String cursor, int size, UUID requestId) {
        var principal = auth.authenticate(token, false);
        if (size < 1 || size > 100) throw invalid();
        String after = "";
        if (cursor != null) {
            try {
                String[] parts =
                        crypto.decrypt(cursor, "public-stories:v1:" + principal.memberKey())
                                .split("\\|", -1);
                if (parts.length != 2
                        || !parts[0].matches("[A-Z0-9_]{1,40}")
                        || Instant.parse(parts[1]).plusSeconds(600).isBefore(Instant.now()))
                    throw invalidCursor();
                after = parts[0];
            } catch (RuntimeException failure) {
                throw invalidCursor();
            }
        }
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        String initialCursor = after;
        return tx.execute(
                status -> {
                    String lower = initialCursor;
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = friendly.lock(evidence, owners);
                    List<StorySummary> items = new java.util.ArrayList<>();
                    String last = null;
                    // 후보가 무효이면 누락 없이 다음 배치를 읽도록 DB 커서를 계속 전진시킨다.
                    while (items.size() <= size) {
                        var candidates =
                                db.query(
                                        "SELECT code FROM story WHERE code>? AND active_yn AND"
                                                + " view_yn ORDER BY code COLLATE \"C\" LIMIT 101",
                                        (rs, n) -> rs.getString(1),
                                        lower);
                        if (candidates.isEmpty()) break;
                        for (String code : candidates) {
                            lower = code;
                            Selection selected = selection(code, false);
                            if (selected != null
                                    && releaseStatus(selected) == ReleaseStatus.VERIFIED
                                    && runtimeValid(selected)) {
                                if (items.size() == size) {
                                    memberPolicy.checkPinned(permit, principal.memberId());
                                    memberPolicy.check(permit);
                                    friendly.lock(evidence, owners);
                                    return new Page(
                                            List.copyOf(items),
                                            crypto.encrypt(
                                                    last + "|" + Instant.now(),
                                                    "public-stories:v1:" + principal.memberKey()),
                                            requestId);
                                }
                                items.add(summary(selected));
                                last = code;
                            }
                        }
                        if (candidates.size() < 101) break;
                    }
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    friendly.lock(evidence, owners);
                    return new Page(List.copyOf(items), null, requestId);
                });
    }

    /** 공개 버전의 안전한 소개만 반환하며 정답·역할 카드는 읽지 않는다. */
    public StoryPublic story(String token, String code, UUID requestId) {
        var principal = auth.authenticate(token, false);
        if (code == null || !code.matches("[A-Z0-9_]{1,40}")) throw invalid();
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = friendly.lock(evidence, owners);
                    Selection selected = selection(code, false);
                    if (selected == null
                            || releaseStatus(selected) != ReleaseStatus.VERIFIED
                            || !runtimeValid(selected)) throw missing();
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    return new StoryPublic(
                            code,
                            selected.title(),
                            selected.difficulty(),
                            selected.estMin(),
                            selected.estMax(),
                            selected.limitSec(),
                            2,
                            selected.versionNo(),
                            Long.toString(selected.playRev()),
                            selected.intro(),
                            selected.setting(),
                            notice.code(),
                            notice.hash(),
                            notice.body());
                });
    }

    /** 명시적 모드·현재 고지·공개 근거를 동일 거래에서 확인하고 실제 회원 자리를 만든다. */
    public Action createGame(
            String token,
            String mode,
            String code,
            int versionNo,
            long playRev,
            String policyCode,
            String noticeHash,
            UUID requestKey,
            UUID requestId) {
        if (!("SOLO".equals(mode) || "COOP".equals(mode))
                || code == null
                || !code.matches("[A-Z0-9_]{1,40}")
                || versionNo < 1
                || playRev < 0
                || playRev == Long.MAX_VALUE
                || policyCode == null
                || noticeHash == null) throw invalid();
        uuid(requestKey);
        var principal = auth.authenticate(token, false);
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        String hash =
                digest(
                        "PUBLIC_CREATE",
                        mode,
                        code,
                        Integer.toString(versionNo),
                        Long.toString(playRev),
                        policyCode,
                        noticeHash);
        return tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = friendly.lock(evidence, owners);
                    if (!notice.code().equals(policyCode) || !notice.hash().equals(noticeHash))
                        throw AuthException.conflict("POLICY_CHANGED");
                    lockRequest(requestKey);
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        if (prior.memberId() != principal.memberId()
                                || !"PUBLIC_CREATE".equals(prior.action())
                                || !hash.equals(prior.hash())
                                || !Boolean.TRUE.equals(
                                        db.queryForObject(
                                                "SELECT EXISTS(SELECT 1 FROM game_member WHERE"
                                                    + " room_id=? AND member_id=? AND active_yn)",
                                                Boolean.class,
                                                prior.roomId(),
                                                principal.memberId())))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        Room room = roomById(prior.roomId());
                        if (room == null || !"WAITING".equals(room.state()))
                            throw AuthException.conflict("STATE_CONFLICT");
                        String saved =
                                db.queryForObject(
                                        "SELECT result_data::text FROM game_action WHERE"
                                                + " request_key=?",
                                        String.class,
                                        requestKey);
                        return new Action(
                                "PUBLIC_CREATE",
                                true,
                                false,
                                SnapshotJson.parse(saved.getBytes(StandardCharsets.UTF_8)),
                                state(room, principal.memberId(), requestId),
                                requestId);
                    }
                    if (Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM game_member WHERE member_id=? AND"
                                            + " occupying_yn)",
                                    Boolean.class,
                                    principal.memberId())))
                        throw AuthException.conflict("ACTIVE_GAME_EXISTS");
                    Long runtimeId =
                            db.query(
                                    "SELECT x.runtime_id FROM story s JOIN story_version v ON"
                                            + " v.id=s.published_id JOIN story_release x ON"
                                            + " x.version_id=v.id AND"
                                            + " x.snapshot_id=v.current_snapshot_id WHERE s.code=?",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    code);
                    if (runtimeId == null) throw AuthException.unprocessable("RELEASE_NOT_READY");
                    RuntimeRow runtime =
                            runtimes.lockRuntime(runtimeId)
                                    .orElseThrow(
                                            () -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
                    Selection selected = selection(code, true);
                    if (selected == null
                            || selected.versionNo() != versionNo
                            || selected.playRev() != playRev)
                        throw AuthException.conflict("PREPARATION_CHANGED");
                    requireRelease(selected);
                    if (!runtimeValid(selected, runtime))
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    long identityId = localIdentity(principal);
                    var frozen = frozen(selected.snapshotId(), selected.versionId());
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
                    UUID key = UUID.randomUUID();
                    Long roomId =
                            db.queryForObject(
                                    """
                                    INSERT INTO game_room(room_key,purpose,mode,story_id,version_id,snapshot_id,runtime_id,
                                      runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,
                                      max_attempts,max_hints,created_at,lobby_until)
                                    SELECT ?,'PUBLIC',?,?,?,?,?,?,?,?,?,?,'WAITING',?,?,?,?,c.t,c.t+interval '30 minutes'
                                    FROM (SELECT clock_timestamp() AS t) c RETURNING id
                                    """,
                                    Long.class,
                                    key,
                                    mode,
                                    selected.storyId(),
                                    selected.versionId(),
                                    selected.snapshotId(),
                                    runtime.id(),
                                    runtime.epoch(),
                                    runtime.configHash(),
                                    frozen.rubricHash(),
                                    notice.policyId(),
                                    selected.playRev(),
                                    selected.difficulty(),
                                    selected.limitSec(),
                                    attempts,
                                    hints);
                    db.update(
                            "INSERT INTO"
                                + " game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at)"
                                + " VALUES (?,?,?,1,?,clock_timestamp())",
                            roomId,
                            principal.memberId(),
                            identityId,
                            notice.policyId());
                    db.update(
                            "INSERT INTO"
                                + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                + " VALUES"
                                + " (?,?,?,'PUBLIC_CREATE',?,jsonb_build_object('gameKey',?::text,'state','WAITING','rev','0'))",
                            requestKey,
                            principal.memberId(),
                            roomId,
                            hash,
                            key.toString());
                    db.update(
                            "INSERT INTO"
                                + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                + " VALUES (?,?,'MEMBER',?,'PUBLIC_CREATE',?,'CREATED')",
                            UUID.randomUUID(),
                            roomId,
                            principal.memberId(),
                            requestId);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    friendly.lock(evidence, owners);
                    return action(
                            "PUBLIC_CREATE",
                            false,
                            roomById(roomId),
                            principal.memberId(),
                            requestId);
                });
    }

    /** 현재 참가자의 방만 읽는다. 시작 뒤에는 사건 노출 상태를 다시 요구하지 않는다. */
    public GameState game(String token, UUID key, UUID requestId) {
        uuid(key);
        String mode =
                db.query(
                        "SELECT mode FROM game_room WHERE room_key=? AND purpose='PUBLIC'",
                        rs -> rs.next() ? rs.getString(1) : null,
                        key);
        if ("COOP".equals(mode))
            return withLobby(
                    token,
                    key,
                    false,
                    (room, actor) -> {
                        GamePlayService.reconcile(db, room.id());
                        return state(roomById(room.id()), actor.principal().memberId(), requestId);
                    });
        var principal = auth.authenticate(token, false);
        reconcileBeforeRead(token, key, principal);
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = friendly.lock(evidence, owners);
                    Room room = owned(key, principal.memberId());
                    if (room == null) throw missing();
                    GamePlayService.reconcile(db, room.id());
                    room = roomById(room.id());
                    if (room.policyId() != notice.policyId())
                        throw AuthException.conflict("POLICY_CHANGED");
                    GameState state = state(room, principal.memberId(), requestId);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    return state;
                });
    }

    /** 방장 한 명과 유효 공개본을 직렬화한 뒤 검증된 역할쌍만 선택하여 시작 고정값을 저장한다. */
    public Action startGame(
            String token,
            UUID key,
            long expectedRev,
            int versionNo,
            long playRev,
            UUID requestKey,
            UUID requestId) {
        uuid(key);
        uuid(requestKey);
        if (expectedRev < 0 || expectedRev == Long.MAX_VALUE || versionNo < 1 || playRev < 0)
            throw invalid();
        var principal = auth.authenticate(token, false);
        reconcileBeforeRead(token, key, principal);
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        String hash =
                digest(
                        "PUBLIC_START",
                        key.toString(),
                        Long.toString(expectedRev),
                        Integer.toString(versionNo),
                        Long.toString(playRev));
        return tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = friendly.lock(evidence, owners);
                    Room initial = owned(key, principal.memberId(), false);
                    if (initial == null) throw missing();
                    RuntimeRow runtime =
                            runtimes.lockRuntime(initial.runtimeId())
                                    .orElseThrow(
                                            () -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
                    Selection selected = selectionById(initial.storyId(), true);
                    Room room = owned(key, principal.memberId());
                    if (room == null) throw missing();
                    if (room.policyId() != notice.policyId())
                        throw AuthException.conflict("POLICY_CHANGED");
                    lockRequest(requestKey);
                    Receipt prior = receipt(requestKey);
                    if (prior != null) {
                        if (prior.memberId() != principal.memberId()
                                || prior.roomId() != room.id()
                                || !"PUBLIC_START".equals(prior.action())
                                || !hash.equals(prior.hash())
                                || !"RUNNING".equals(room.state()))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        return action("PUBLIC_START", true, room, principal.memberId(), requestId);
                    }
                    if (!"WAITING".equals(room.state()) || !room.lobbyUntil().isAfter(dbNow()))
                        throw AuthException.conflict("STATE_CONFLICT");
                    if (!"SOLO".equals(room.mode())) throw AuthException.conflict("STATE_CONFLICT");
                    if (room.rev() != expectedRev) throw AuthException.conflict("EDIT_CONFLICT");
                    if (selected == null
                            || selected.versionId() != room.versionId()
                            || selected.snapshotId() != room.snapshotId()
                            || selected.versionNo() != versionNo
                            || selected.playRev() != playRev
                            || room.playRev() != playRev)
                        throw AuthException.conflict("PREPARATION_CHANGED");
                    requireRelease(selected);
                    if (!runtimeValid(selected, runtime)
                            || runtime.id() != room.runtimeId()
                            || runtime.epoch() != room.runtimeEpoch())
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    if (!Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM game_member WHERE room_id=? AND"
                                        + " member_id=? AND slot=1 AND active_yn AND policy_id=?"
                                        + " AND identity_id=(SELECT identity_id FROM member_session"
                                        + " WHERE id=?))",
                                    Boolean.class,
                                    room.id(),
                                    principal.memberId(),
                                    notice.policyId(),
                                    principal.sessionId())))
                        throw AuthException.conflict("STATE_CONFLICT");
                    var pairs =
                            db.query(
                                    "SELECT role_a,role_b FROM story_pair WHERE version_id=? AND"
                                            + " active_yn ORDER BY role_a,role_b",
                                    (rs, n) -> List.of(rs.getString(1), rs.getString(2)),
                                    room.versionId());
                    JsonNode payload = frozen(room.snapshotId(), room.versionId()).payload();
                    pairs.removeIf(pair -> !pairPresent(payload, pair));
                    if (pairs.isEmpty()) throw AuthException.conflict("RELEASE_NOT_READY");
                    List<String> pair = pairs.get(RANDOM.nextInt(pairs.size()));
                    db.update(
                            "UPDATE game_member SET"
                                    + " version_id=?,role_code=?,updated_at=clock_timestamp() WHERE"
                                    + " room_id=? AND member_id=? AND slot=1 AND active_yn AND"
                                    + " role_code IS NULL",
                            room.versionId(),
                            pair.get(0),
                            room.id(),
                            principal.memberId());
                    if (db.update(
                                    "WITH c AS (SELECT clock_timestamp() AS t) UPDATE game_room SET"
                                        + " state='RUNNING',role_a=?,role_b=?,started_at=c.t,deadline_at=c.t+limit_sec*interval"
                                        + " '1 second',rev=rev+1,updated_at=c.t FROM c WHERE id=?"
                                        + " AND state='WAITING' AND rev=? AND lobby_until>c.t",
                                    pair.get(0),
                                    pair.get(1),
                                    room.id(),
                                    expectedRev)
                            != 1) throw AuthException.conflict("STATE_CONFLICT");
                    db.update(
                            "INSERT INTO"
                                + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                + " SELECT"
                                + " ?,?,id,'PUBLIC_START',?,jsonb_build_object('gameKey',?::text,'state','RUNNING','rev',rev::text)"
                                + " FROM game_room WHERE id=?",
                            requestKey,
                            principal.memberId(),
                            hash,
                            key.toString(),
                            room.id());
                    db.update(
                            "INSERT INTO"
                                + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                + " VALUES (?,?,'MEMBER',?,'PUBLIC_START',?,'STARTED')",
                            UUID.randomUUID(),
                            room.id(),
                            principal.memberId(),
                            requestId);
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    friendly.lock(evidence, owners);
                    return action(
                            "PUBLIC_START",
                            false,
                            roomById(room.id()),
                            principal.memberId(),
                            requestId);
                });
    }

    /** 고정 두 역할과 COMMON 자료만 반환하며 첫 보안 노출·감사가 실패하면 본문을 돌려주지 않는다. */
    public Materials materials(String token, UUID key, UUID requestId) {
        uuid(key);
        var principal = auth.authenticate(token, false);
        reconcileBeforeRead(token, key, principal);
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = friendly.lock(evidence, owners);
                    Room initial = owned(key, principal.memberId(), false);
                    if (initial == null) throw missing();
                    runtimes.lockRuntime(initial.runtimeId())
                            .orElseThrow(() -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
                    Room room = owned(key, principal.memberId());
                    if (room == null) throw missing();
                    if (room.policyId() != notice.policyId())
                        throw AuthException.conflict("POLICY_CHANGED");
                    String ownRole =
                            db.query(
                                    "SELECT role_code FROM game_member WHERE room_id=? AND"
                                            + " member_id=? AND active_yn AND version_id=?",
                                    rs -> rs.next() ? rs.getString(1) : null,
                                    room.id(),
                                    principal.memberId(),
                                    room.versionId());
                    if (!"RUNNING".equals(room.state())
                            || room.roleA() == null
                            || room.roleB() == null
                            || ownRole == null) throw AuthException.conflict("STATE_CONFLICT");
                    JsonNode payload = frozen(room.snapshotId(), room.versionId()).payload();
                    JsonNode first;
                    JsonNode second;
                    try {
                        first =
                                StoryReviewPreview.role(
                                        payload.get("sections"), payload.get("resources"), ownRole);
                        second =
                                "SOLO".equals(room.mode())
                                        ? StoryReviewPreview.role(
                                                payload.get("sections"),
                                                payload.get("resources"),
                                                room.roleB())
                                        : null;
                    } catch (IllegalArgumentException failure) {
                        throw AuthException.conflict("RELEASE_NOT_READY");
                    }
                    var roles = JsonNodeFactory.instance.arrayNode().add(first.get("role"));
                    if (second != null) roles.add(second.get("role"));
                    var persons = new TreeMap<String, JsonNode>(CommonUtil::compareCodePoints);
                    var clues = new TreeMap<String, JsonNode>(CommonUtil::compareCodePoints);
                    for (JsonNode projection :
                            second == null ? List.of(first) : List.of(first, second)) {
                        for (JsonNode person : projection.get("persons"))
                            persons.put(person.get("code").asText(), person);
                        for (JsonNode clue : projection.get("clues"))
                            clues.put(clue.get("code").asText(), clue);
                    }
                    var publicPersons = JsonNodeFactory.instance.arrayNode();
                    var publicClues = JsonNodeFactory.instance.arrayNode();
                    persons.values().forEach(publicPersons::add);
                    for (JsonNode clue : clues.values()) {
                        var visible = clue.deepCopy();
                        var read =
                                db.queryForList(
                                        "SELECT read_at FROM game_clue_read WHERE room_id=? AND"
                                                + " member_id=? AND clue_code=?",
                                        Timestamp.class,
                                        room.id(),
                                        principal.memberId(),
                                        clue.path("code").asText());
                        if (visible
                                instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                            object.put("read", !read.isEmpty());
                            if (read.isEmpty()) object.putNull("readAt");
                            else object.put("readAt", read.getFirst().toInstant().toString());
                        }
                        publicClues.add(visible);
                    }
                    var opened = JsonNodeFactory.instance.arrayNode();
                    for (var hint :
                            db.queryForList(
                                    "SELECT level,hint_code FROM game_hint WHERE room_id=? AND"
                                            + " member_id=? ORDER BY level",
                                    room.id(),
                                    principal.memberId())) {
                        JsonNode fixed =
                                GamePlayService.hint(
                                        payload, ((Number) hint.get("level")).intValue());
                        if (fixed == null
                                || !hint.get("hint_code").equals(fixed.path("code").textValue()))
                            throw AuthException.unavailable("GAME_UNAVAILABLE");
                        opened.addObject()
                                .put("level", ((Number) hint.get("level")).intValue())
                                .put("code", fixed.path("code").textValue())
                                .put("body", fixed.path("body").textValue());
                    }
                    var notices = JsonNodeFactory.instance.arrayNode();
                    for (JsonNode rubric : payload.path("resources").path("rubrics")) {
                        if (!rubric.path("requiredYn").asBoolean(false)) continue;
                        JsonNode text = rubric.path("ruleData").path("requiredNotice");
                        if (!text.isTextual() || text.textValue().isBlank())
                            throw AuthException.unavailable("GAME_UNAVAILABLE");
                        notices.addObject()
                                .put("rubricCode", rubric.path("code").textValue())
                                .put("text", text.textValue());
                    }
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    friendly.lock(evidence, owners);
                    db.update(
                            "INSERT INTO test_exposure(story_id,member_id,first_test_id) VALUES"
                                    + " (?,?,NULL) ON CONFLICT DO NOTHING",
                            room.storyId(),
                            principal.memberId());
                    db.update(
                            "INSERT INTO"
                                + " game_audit(event_key,room_id,actor_kind,member_id,action,request_id,result)"
                                + " VALUES (?,?,'MEMBER',?,'CONTENT_READ',?,'EXPOSED')",
                            UUID.randomUUID(),
                            room.id(),
                            principal.memberId(),
                            requestId);
                    return new Materials(
                            key,
                            room.state(),
                            Long.toString(room.rev()),
                            room.deadlineAt(),
                            first.get("basic"),
                            roles,
                            publicPersons,
                            publicClues,
                            opened,
                            notices,
                            Math.max(0, room.maxHints() - opened.size()),
                            requestId);
                });
    }

    /** 현재 회원·정책과 소유 방을 거래 안에서 잠근 후 보고서 명령을 수행한다. */
    <T> T withReportRoom(
            String token, UUID key, java.util.function.BiFunction<Room, Long, T> command) {
        return withMemberRoom(token, key, true, command);
    }

    /** 고정 사건의 범인·rubric·런타임을 확인한다. 현재 공개상태는 진행 중 게임의 자격이 아니다. */
    FrozenSnapshotCodec.FrozenSnapshot checkedReportSource(Room room, Report parsed) {
        var frozen = frozen(room.snapshotId(), room.versionId());
        var pin =
                db.queryForMap(
                        "SELECT rubric_hash,config_hash FROM game_room WHERE id=?", room.id());
        if (!frozen.rubricHash().equals(pin.get("rubric_hash")))
            throw AuthException.conflict("PREPARATION_CHANGED");
        boolean known = false;
        for (JsonNode person : frozen.payload().path("resources").path("persons"))
            if (parsed.culpritCode().equals(person.path("code").textValue())) known = true;
        if (!known) throw AuthException.badRequest("INVALID_REPORT");
        RuntimeRow runtime =
                runtimes.lockRuntime(room.runtimeId())
                        .orElseThrow(() -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
        try {
            if (!"AVAILABLE".equals(runtime.state())
                    || runtime.epoch() != room.runtimeEpoch()
                    || !Objects.equals(runtime.configHash(), pin.get("config_hash"))
                    || installations.get(runtime.code()) == null
                    || !installations
                            .get(runtime.code())
                            .verify(runtime)
                            .profile()
                            .policyCode()
                            .equals(frozen.payload().path("policy").path("policyCode").textValue()))
                throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        } catch (RuntimeException failure) {
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        }
        return frozen;
    }

    /** 현재 토큰·동의·참가자·방 잠금의 순서를 공통으로 유지하고 시작 역할은 해당 명령에서만 요구한다. */
    <T> T withMemberRoom(
            String token,
            UUID key,
            boolean started,
            java.util.function.BiFunction<Room, Long, T> command) {
        uuid(key);
        String mode =
                db.query(
                        "SELECT mode FROM game_room WHERE room_key=? AND purpose='PUBLIC'",
                        rs -> rs.next() ? rs.getString(1) : null,
                        key);
        if ("COOP".equals(mode)) {
            reconcileBeforeRead(token, key, auth.authenticate(token, false));
            return withLobby(
                    token,
                    key,
                    false,
                    (room, actor) -> {
                        GamePlayService.reconcile(db, room.id());
                        Room current = roomById(room.id());
                        if (started
                                && !Boolean.TRUE.equals(
                                        db.queryForObject(
                                                "SELECT EXISTS(SELECT 1 FROM game_member WHERE"
                                                    + " room_id=? AND member_id=? AND active_yn AND"
                                                    + " version_id=? AND role_code IS NOT NULL)",
                                                Boolean.class,
                                                room.id(),
                                                actor.principal().memberId(),
                                                current.versionId()))) throw missing();
                        return command.apply(current, actor.principal().memberId());
                    });
        }
        var principal = auth.authenticate(token, false);
        reconcileBeforeRead(token, key, principal);
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    var notice = friendly.lock(evidence, owners);
                    Room room = owned(key, principal.memberId());
                    if (room == null) throw missing();
                    GamePlayService.reconcile(db, room.id());
                    room = roomById(room.id());
                    if (room.policyId() != notice.policyId())
                        throw AuthException.conflict("POLICY_CHANGED");
                    if (!Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM game_member WHERE room_id=? AND"
                                        + " member_id=? AND slot=1 AND active_yn AND policy_id=?"
                                        + " AND identity_id=(SELECT identity_id FROM member_session"
                                        + " WHERE id=?) AND (?=false OR (version_id=? AND"
                                        + " role_code=?)))",
                                    Boolean.class,
                                    room.id(),
                                    principal.memberId(),
                                    notice.policyId(),
                                    principal.sessionId(),
                                    started,
                                    room.versionId(),
                                    room.roleA()))) throw missing();
                    T value = command.apply(room, principal.memberId());
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    friendly.lock(evidence, owners);
                    return value;
                });
    }

    /** 실행 중 원문의 현재 revision과 접수 상태를 반환하며 암호문은 방 AAD로만 연다. */
    public ReportView report(String token, UUID key, UUID requestId) {
        return withReportRoom(
                token,
                key,
                (room, member) -> {
                    if (!"SOLO".equals(room.mode())) throw AuthException.conflict("STATE_CONFLICT");
                    if (!"RUNNING".equals(room.state()))
                        throw AuthException.conflict("STATE_CONFLICT");
                    var draft =
                            db.queryForMap(
                                    "SELECT draft_rev,draft_cipher,draft_hash FROM game_room WHERE"
                                            + " id=?",
                                    room.id());
                    long revision = ((Number) draft.get("draft_rev")).longValue();
                    var accepted =
                            db.queryForList(
                                    "SELECT"
                                        + " r.id,r.source_rev,r.payload_cipher,r.payload_hash,j.report_hash"
                                        + " FROM game_report r JOIN grade_job j ON"
                                        + " j.game_report_id=r.id WHERE r.room_id=? AND"
                                        + " r.state='ACCEPTED' AND r.purged_at IS NULL",
                                    room.id());
                    if (accepted.size() > 1) throw AuthException.unavailable("GAME_UNAVAILABLE");
                    if (accepted.isEmpty() && !room.deadlineAt().isAfter(dbNow()))
                        throw AuthException.conflict("PLAY_DEADLINE_EXPIRED");
                    JsonNode payload;
                    if (!accepted.isEmpty()) {
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
                                || !frozen(room.snapshotId(), room.versionId())
                                        .reportHash(GameReportService.complete(payload))
                                        .equals(pin.get("report_hash")))
                            throw AuthException.unavailable("GAME_UNAVAILABLE");
                    } else {
                        payload =
                                draft.get("draft_cipher") == null
                                        ? GameReportService.empty()
                                        : reports.decrypt(
                                                (byte[]) draft.get("draft_cipher"),
                                                GameReportService.draftAad(room.id(), revision),
                                                (String) draft.get("draft_hash"));
                    }
                    return new ReportView(
                            key,
                            Long.toString(room.rev()),
                            Long.toString(revision),
                            payload,
                            accepted.isEmpty() ? "NONE" : "PENDING",
                            requestId);
                });
    }

    /** 정확히 전달된 원문이 달라진 경우에만 두 revision을 올린다. */
    public Action editReport(
            String token,
            UUID key,
            long expectedRev,
            long expectedDraftRev,
            JsonNode report,
            UUID requestKey,
            UUID requestId) {
        uuid(key);
        uuid(requestKey);
        if (expectedRev < 0
                || expectedDraftRev < 0
                || expectedRev == Long.MAX_VALUE
                || expectedDraftRev == Long.MAX_VALUE) throw invalid();
        byte[] bytes = GameReportService.checkedDraft(report);
        String payloadHash = SnapshotJson.hash(report);
        String intent =
                digest(
                        "PUBLIC_REPORT_EDIT",
                        key.toString(),
                        Long.toString(expectedRev),
                        Long.toString(expectedDraftRev),
                        payloadHash);
        return withReportRoom(
                token,
                key,
                (room, member) -> {
                    if (!"SOLO".equals(room.mode())) throw AuthException.conflict("STATE_CONFLICT");
                    lockRequest(requestKey);
                    Receipt prior = receipt(requestKey);
                    if (prior != null)
                        return reportReplay(
                                prior,
                                room,
                                member,
                                "PUBLIC_REPORT_EDIT",
                                intent,
                                requestKey,
                                requestId);
                    requireEditable(room);
                    var draft =
                            db.queryForMap(
                                    "SELECT draft_rev,draft_hash FROM game_room WHERE id=?",
                                    room.id());
                    long currentDraftRev = ((Number) draft.get("draft_rev")).longValue();
                    if (room.rev() != expectedRev || currentDraftRev != expectedDraftRev)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    boolean changed =
                            !payloadHash.equals(draft.get("draft_hash"))
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
                                                                    room.id(), currentDraftRev + 1))
                                                    .getBytes(StandardCharsets.UTF_8),
                                            payloadHash,
                                            room.id(),
                                            expectedRev,
                                            expectedDraftRev)
                                    != 1) throw AuthException.conflict("EDIT_CONFLICT");
                    return reportAction(
                            room,
                            member,
                            requestKey,
                            requestId,
                            "PUBLIC_REPORT_EDIT",
                            intent,
                            changed);
                });
    }

    /** 본인의 명시적 동의로 완성 REPORT-1과 QUEUED GAME 작업을 한 거래에 고정한다. */
    public Action submitReport(
            String token,
            UUID key,
            long expectedRev,
            long expectedDraftRev,
            UUID requestKey,
            UUID requestId) {
        uuid(key);
        uuid(requestKey);
        if (expectedRev < 0 || expectedDraftRev < 0 || expectedRev == Long.MAX_VALUE)
            throw invalid();
        String intent =
                digest(
                        "PUBLIC_REPORT_SUBMIT",
                        key.toString(),
                        Long.toString(expectedRev),
                        Long.toString(expectedDraftRev));
        return withReportRoom(
                token,
                key,
                (room, member) -> {
                    if (!"SOLO".equals(room.mode())) throw AuthException.conflict("STATE_CONFLICT");
                    lockRequest(requestKey);
                    Receipt prior = receipt(requestKey);
                    if (prior != null)
                        return reportReplay(
                                prior,
                                room,
                                member,
                                "PUBLIC_REPORT_SUBMIT",
                                intent,
                                requestKey,
                                requestId);
                    requireEditable(room);
                    var draft =
                            db.queryForMap(
                                    "SELECT"
                                        + " draft_rev,draft_cipher,draft_hash,graded_count,max_attempts,runtime_id,runtime_epoch,config_hash,rubric_hash"
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
                    Report parsed = GameReportService.complete(fixed);
                    var frozen = frozen(room.snapshotId(), room.versionId());
                    if (!frozen.rubricHash().equals(draft.get("rubric_hash")))
                        throw AuthException.conflict("PREPARATION_CHANGED");
                    boolean known = false;
                    for (JsonNode person : frozen.payload().path("resources").path("persons"))
                        if (parsed.culpritCode().equals(person.path("code").textValue()))
                            known = true;
                    if (!known) throw AuthException.badRequest("INVALID_REPORT");
                    int count = ((Number) draft.get("graded_count")).intValue();
                    int limit = ((Number) draft.get("max_attempts")).intValue();
                    if (count >= limit || count >= 5 || count < 0)
                        throw AuthException.conflict("ATTEMPTS_EXHAUSTED");
                    if (Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM game_report WHERE room_id=? AND"
                                            + " state IN ('PROPOSED','ACCEPTED'))",
                                    Boolean.class,
                                    room.id()))) throw AuthException.conflict("SUBMISSION_PENDING");
                    RuntimeRow runtime =
                            runtimes.lockRuntime(room.runtimeId())
                                    .orElseThrow(
                                            () -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
                    if (!"AVAILABLE".equals(runtime.state())
                            || runtime.epoch() != room.runtimeEpoch()
                            || !Objects.equals(runtime.configHash(), draft.get("config_hash"))
                            || installations.get(runtime.code()) == null)
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    try {
                        if (!installations
                                .get(runtime.code())
                                .verify(runtime)
                                .profile()
                                .policyCode()
                                .equals(
                                        frozen.payload()
                                                .path("policy")
                                                .path("policyCode")
                                                .textValue()))
                            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    } catch (RuntimeException failure) {
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    }
                    int ordinal = count + 1;
                    var accepted =
                            reports.accept(
                                    room.id(),
                                    room.snapshotId(),
                                    room.runtimeId(),
                                    member,
                                    expectedRev,
                                    revision,
                                    ordinal,
                                    fixed,
                                    parsed,
                                    frozen,
                                    (String) draft.get("config_hash"));
                    return reportAction(
                            room,
                            member,
                            requestKey,
                            requestId,
                            "PUBLIC_REPORT_SUBMIT",
                            intent,
                            true,
                            accepted.reportKey(),
                            accepted.jobKey(),
                            accepted.ordinal(),
                            accepted.acceptedAt(),
                            accepted.deadlineAt());
                });
    }

    /** 현재 기록 소유권과 원문 자격을 통해 동일한 개인 기록 상세를 반환한다. */
    public JsonNode result(String token, UUID key, UUID requestId) {
        return records.getGameResult(token, key, requestId);
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

    /** 만료 종료를 별도 거래로 확정해 뒤따르는 409 응답에서도 서버 시계 전이를 롤백하지 않는다. */
    private void reconcileBeforeRead(
            String token, UUID key, MemberAuthService.MemberPrincipal principal) {
        String mode =
                db.query(
                        "SELECT mode FROM game_room WHERE room_key=? AND purpose='PUBLIC'",
                        rs -> rs.next() ? rs.getString(1) : null,
                        key);
        if ("COOP".equals(mode)) {
            Boolean expired =
                    db.queryForObject(
                            "SELECT state='WAITING' AND lobby_until<=clock_timestamp() OR"
                                    + " state='RUNNING' AND deadline_at<=clock_timestamp() FROM"
                                    + " game_room WHERE room_key=?",
                            Boolean.class,
                            key);
            if (Boolean.TRUE.equals(expired))
                withLobby(
                        token,
                        key,
                        false,
                        (room, actor) -> {
                            GamePlayService.reconcile(db, room.id());
                            return null;
                        });
            return;
        }
        var ids =
                db.queryForList(
                        "SELECT r.id FROM game_room r JOIN game_member m ON m.room_id=r.id WHERE"
                                + " r.room_key=? AND r.purpose='PUBLIC' AND"
                                + " m.member_id=? AND m.active_yn AND ((r.state='WAITING' AND"
                                + " r.lobby_until<=clock_timestamp()) OR (r.state='RUNNING' AND"
                                + " r.deadline_at<=clock_timestamp()))",
                        Long.class,
                        key,
                        principal.memberId());
        if (ids.isEmpty()) return;
        var evidence = friendly.prepare();
        var memberEvidence = memberPolicy.prepare();
        tx.execute(
                status -> {
                    lockTimeout();
                    Set<Long> owners = owners(evidence.owner(), principal.memberId());
                    lockOwners(owners);
                    bound(token, principal);
                    var permit = memberPolicy.lock(memberEvidence, principal.memberId(), owners);
                    friendly.lock(evidence, owners);
                    Room room = roomById(ids.getFirst());
                    if (room != null) GamePlayService.reconcile(db, room.id());
                    memberPolicy.checkPinned(permit, principal.memberId());
                    memberPolicy.check(permit);
                    return null;
                });
    }

    Action reportReplay(
            Receipt prior,
            Room room,
            long member,
            String name,
            String intent,
            UUID requestKey,
            UUID requestId) {
        if (prior.roomId() != room.id()
                || prior.memberId() != member
                || !name.equals(prior.action())
                || !intent.equals(prior.hash()))
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        var original =
                db.queryForMap(
                        "SELECT result_data::text AS data FROM game_action WHERE request_key=?",
                        requestKey);
        return new Action(
                name,
                true,
                false,
                SnapshotJson.parse(
                        ((String) original.get("data")).getBytes(StandardCharsets.UTF_8)),
                state(room, member, requestId),
                requestId);
    }

    Action reportAction(
            Room room,
            long member,
            UUID requestKey,
            UUID requestId,
            String name,
            String intent,
            boolean changed) {
        return reportAction(
                room,
                member,
                requestKey,
                requestId,
                name,
                intent,
                changed,
                null,
                null,
                0,
                null,
                null);
    }

    Action reportAction(
            Room room,
            long member,
            UUID requestKey,
            UUID requestId,
            String name,
            String intent,
            boolean changed,
            UUID reportKey,
            UUID jobKey,
            int ordinal,
            Instant accepted,
            Instant deadline) {
        Room current = roomById(room.id());
        long draftRev =
                db.queryForObject(
                        "SELECT draft_rev FROM game_room WHERE id=?", Long.class, room.id());
        var original =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("gameKey", room.key().toString())
                        .put("rev", Long.toString(current.rev()))
                        .put("draftRev", Long.toString(draftRev))
                        .put("state", current.state())
                        .put("changed", changed);
        if (reportKey != null) original.put("reportKey", reportKey.toString());
        if (jobKey != null)
            original.put("jobKey", jobKey.toString())
                    .put("ordinal", ordinal)
                    .put("acceptedAt", accepted.toString())
                    .put("gradeDeadline", deadline.toString());
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
        return new Action(
                name, false, changed, original, state(current, member, requestId), requestId);
    }

    private enum ReleaseStatus {
        VERIFIED,
        MISSING,
        UNAVAILABLE,
        INVALID
    }

    /** 임의 JSON의 PASS를 신뢰하지 않고 실제 불변 기록과 미연결 PLAYTEST 수집을 분리한다. */
    private ReleaseStatus releaseStatus(Selection selected) {
        if (selected.blocked()) return ReleaseStatus.INVALID;
        try {
            JsonNode bundle = json.readTree(selected.bundle());
            JsonNode ids = bundle.path("recordIds");
            if (bundle.path("formatNo").asInt(-1) != 1
                    || !"STORY-RELEASE-01".equals(bundle.path("gateVersion").asText())
                    || bundle.path("snapshotId").asLong(-1) != selected.snapshotId()
                    || bundle.path("sourceRev").asLong(-1) != selected.sourceRev()
                    || !selected.evidenceHash().equals(bundle.path("evidenceHash").asText())
                    || !ids.isObject()
                    || !bundle.path("resolutionRecordIds").isArray()
                    || !bundle.path("checks").isArray()
                    || !bundle.path("runtime").isObject()) return ReleaseStatus.INVALID;
            JsonNode rt = bundle.path("runtime");
            if (!rt.path("configId").isTextual()
                    || !rt.path("policyCode").asText().equals(selected.policyCode())
                    || !rt.path("policyHash").asText().matches("[0-9a-f]{64}")
                    || !rt.path("engineId").isTextual()
                    || !rt.path("engineVersion").isTextual()
                    || !rt.path("optionsHash").asText().matches("[0-9a-f]{64}")
                    || !rt.path("reportContractVersion").isTextual()) return ReleaseStatus.INVALID;
            for (String kind : List.of("STRUCTURE", "MODEL", "GRADE", "APPROVAL")) {
                JsonNode id = ids.path(kind);
                if (!id.isIntegralNumber()
                        || id.asLong() < 1
                        || !Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM review_record r WHERE r.id=?"
                                                + " AND r.snapshot_id=? AND r.kind=? AND"
                                                + " r.result='PASS')",
                                        Boolean.class,
                                        id.asLong(),
                                        selected.snapshotId(),
                                        kind))) return ReleaseStatus.INVALID;
            }
            long grade = ids.path("GRADE").asLong();
            if (!Boolean.TRUE.equals(
                    db.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM review_record r JOIN evidence_set e ON"
                                + " e.id=r.evidence_set_id JOIN evidence_item i ON i.set_id=e.id"
                                + " WHERE r.id=? AND e.snapshot_id=? AND e.runtime_id=? AND"
                                + " e.runtime_epoch=? AND e.available_yn AND e.invalidated_at IS"
                                + " NULL AND e.kind='GRADE')",
                            Boolean.class,
                            grade,
                            selected.snapshotId(),
                            selected.runtimeId(),
                            selected.runtimeEpoch()))) return ReleaseStatus.INVALID;
            long approval = ids.path("APPROVAL").asLong();
            if (approval <= grade
                    || approval <= ids.path("MODEL").asLong()
                    || approval <= ids.path("STRUCTURE").asLong()
                    || !Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT NOT EXISTS(SELECT 1 FROM review_record r WHERE"
                                        + " r.snapshot_id=? AND r.result IN ('FAIL','INCOMPLETE')"
                                        + " AND NOT EXISTS (SELECT 1 FROM review_record fix WHERE"
                                        + " fix.snapshot_id=r.snapshot_id AND fix.kind=r.kind AND"
                                        + " fix.id>r.id AND fix.id<=? AND fix.result='PASS' AND"
                                        + " fix.evidence_data->'details'->'resolves' @>"
                                        + " jsonb_build_array(jsonb_build_object('recordId',r.id::text)))"
                                        + " )",
                                    Boolean.class,
                                    selected.snapshotId(),
                                    approval))
                    || !Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT NOT EXISTS(SELECT 1 FROM execution_issue WHERE"
                                            + " snapshot_id=? AND state='OPEN' AND"
                                            + " severity='CRITICAL')",
                                    Boolean.class,
                                    selected.snapshotId()))) return ReleaseStatus.INVALID;
            JsonNode playtest = ids.path("PLAYTEST");
            if (!playtest.isIntegralNumber() || playtest.asLong() < 1) return ReleaseStatus.MISSING;
            var evidence =
                    releaseEvidence.evaluateRelease(
                            selected.versionId(),
                            selected.snapshotId(),
                            selected.runtimeId(),
                            selected.runtimeEpoch(),
                            selected.configHash(),
                            playtest.asLong());
            return switch (evidence.state()) {
                case VERIFIED -> ReleaseStatus.VERIFIED;
                case MISSING -> ReleaseStatus.MISSING;
                case UNAVAILABLE -> ReleaseStatus.UNAVAILABLE;
            };
        } catch (org.springframework.dao.DataAccessException failure) {
            return ReleaseStatus.UNAVAILABLE;
        } catch (Exception failure) {
            return ReleaseStatus.INVALID;
        }
    }

    private void requireRelease(Selection selected) {
        if (selected.blocked()) throw AuthException.conflict("RELEASE_BLOCKED");
        ReleaseStatus result = releaseStatus(selected);
        if (result != ReleaseStatus.VERIFIED) {
            String code =
                    switch (result) {
                        case MISSING -> "RELEASE_EVIDENCE_MISSING";
                        case UNAVAILABLE -> "RELEASE_EVIDENCE_UNAVAILABLE";
                        default -> "RELEASE_NOT_READY";
                    };
            throw new AuthException(422, code, code);
        }
    }

    private boolean runtimeValid(Selection selected) {
        var runtime = runtimes.getRuntimeDetail(selected.runtimeId());
        return runtime.isPresent() && runtimeValid(selected, runtime.get());
    }

    private boolean runtimeValid(Selection selected, RuntimeRow runtime) {
        var installed = installations.get(runtime.code());
        try {
            return "AVAILABLE".equals(runtime.state())
                    && runtime.id() == selected.runtimeId()
                    && runtime.epoch() == selected.runtimeEpoch()
                    && runtime.configHash().equals(selected.configHash())
                    && installed != null
                    && installed
                            .verify(runtime)
                            .profile()
                            .policyCode()
                            .equals(selected.policyCode());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private Selection selection(String code, boolean lock) {
        return select("s.code=?", code, lock);
    }

    private Selection selectionById(long id, boolean lock) {
        return select("s.id=?", id, lock);
    }

    private Selection select(String predicate, Object value, boolean lock) {
        var rows =
                db.query(
                        """
                        SELECT s.id,v.id,x.snapshot_id,x.id,x.runtime_id,x.runtime_epoch,x.source_rev,s.play_rev,
                          v.version_no,s.code,v.title,v.intro,v.setting,v.policy_code,v.difficulty,v.est_min,v.est_max,
                          v.limit_sec,x.config_hash,x.evidence_hash,x.bundle_data::text,x.blocked_yn
                        FROM story s JOIN story_version v ON v.id=s.published_id AND v.story_id=s.id
                        JOIN story_release x ON x.version_id=v.id AND x.snapshot_id=v.current_snapshot_id
                        JOIN review_snapshot snap ON snap.id=x.snapshot_id AND snap.version_id=v.id
                        WHERE
                        """
                                + predicate
                                + " AND s.active_yn AND s.view_yn AND v.active_yn AND"
                                + " v.status='PUBLISHED' AND x.source_rev=snap.edit_rev AND v.title"
                                + " IS NOT NULL AND v.intro IS NOT NULL AND v.setting IS NOT NULL"
                                + " AND v.difficulty BETWEEN 1 AND 5 AND v.est_min>0 AND"
                                + " v.est_max>=v.est_min AND v.limit_sec>0"
                                + (lock ? " FOR SHARE OF s,v,x,snap" : ""),
                        (rs, n) ->
                                new Selection(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getLong(5),
                                        rs.getLong(6),
                                        rs.getLong(7),
                                        rs.getLong(8),
                                        rs.getInt(9),
                                        rs.getString(10),
                                        rs.getString(11),
                                        rs.getString(12),
                                        rs.getString(13),
                                        rs.getString(14),
                                        rs.getInt(15),
                                        rs.getInt(16),
                                        rs.getInt(17),
                                        rs.getInt(18),
                                        rs.getString(19),
                                        rs.getString(20),
                                        rs.getString(21),
                                        rs.getBoolean(22)),
                        value);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StorySummary summary(Selection s) {
        return new StorySummary(
                s.code(),
                s.title(),
                s.difficulty(),
                s.estMin(),
                s.estMax(),
                s.limitSec(),
                2,
                s.versionNo(),
                Long.toString(s.playRev()));
    }

    FrozenSnapshotCodec.FrozenSnapshot frozen(long snapshotId, long versionId) {
        String source =
                db.query(
                        "SELECT payload::text FROM review_snapshot WHERE id=? AND version_id=?",
                        rs -> rs.next() ? rs.getString(1) : null,
                        snapshotId,
                        versionId);
        if (source == null) throw AuthException.conflict("RELEASE_NOT_READY");
        try {
            return FrozenSnapshotCodec.decode(source.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw AuthException.conflict("RELEASE_NOT_READY");
        }
    }

    private static boolean pairPresent(JsonNode payload, List<String> pair) {
        for (JsonNode p : payload.path("resources").path("pairs"))
            if (pair.get(0).equals(p.path("roleA").asText())
                    && pair.get(1).equals(p.path("roleB").asText())) return true;
        return false;
    }

    private Room owned(UUID key, long memberId) {
        return owned(key, memberId, true);
    }

    private Room owned(UUID key, long memberId, boolean lock) {
        var rows =
                db.query(
                        "SELECT r.id FROM game_room r JOIN game_member m ON m.room_id=r.id WHERE"
                                + " r.room_key=? AND r.purpose='PUBLIC' AND"
                                + " m.member_id=? AND m.active_yn",
                        (rs, n) -> rs.getLong(1),
                        key,
                        memberId);
        return rows.isEmpty() ? null : roomById(rows.getFirst(), lock);
    }

    Room roomById(long id) {
        return roomById(id, true);
    }

    private Room roomById(long id, boolean lock) {
        var rows =
                db.query(
                        """
                        SELECT r.id,r.room_key,r.mode,r.story_id,r.version_id,r.snapshot_id,r.runtime_id,r.runtime_epoch,
                          r.policy_id,r.play_rev,r.rev,r.state,v.title,v.version_no,r.limit_sec,r.max_attempts,
                          r.max_hints,r.graded_count,r.role_a,r.role_b,r.lobby_until,r.started_at,r.deadline_at
                        FROM game_room r JOIN story_version v ON v.id=r.version_id
                        WHERE r.id=? AND r.purpose='PUBLIC'
                        """
                                + (lock ? " FOR UPDATE OF r" : ""),
                        (rs, n) ->
                                new Room(
                                        rs.getLong(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getLong(5),
                                        rs.getLong(6),
                                        rs.getLong(7),
                                        rs.getLong(8),
                                        rs.getLong(9),
                                        rs.getLong(10),
                                        rs.getLong(11),
                                        rs.getString(12),
                                        rs.getString(13),
                                        rs.getInt(14),
                                        rs.getInt(15),
                                        rs.getInt(16),
                                        rs.getInt(17),
                                        rs.getInt(18),
                                        rs.getString(19),
                                        rs.getString(20),
                                        rs.getTimestamp(21).toInstant(),
                                        rs.getTimestamp(22) == null
                                                ? null
                                                : rs.getTimestamp(22).toInstant(),
                                        rs.getTimestamp(23) == null
                                                ? null
                                                : rs.getTimestamp(23).toInstant()),
                        id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    GameState state(Room room, long memberId, UUID requestId) {
        boolean waiting = "WAITING".equals(room.state());
        Selection current = waiting ? selectionById(room.storyId(), false) : null;
        boolean refresh =
                waiting
                        && (current == null
                                || current.versionId() != room.versionId()
                                || current.snapshotId() != room.snapshotId()
                                || current.playRev() != room.playRev()
                                || current.runtimeId() != room.runtimeId()
                                || current.runtimeEpoch() != room.runtimeEpoch()
                                || !current.configHash()
                                        .equals(
                                                db.queryForObject(
                                                        "SELECT config_hash FROM game_room WHERE"
                                                                + " id=?",
                                                        String.class,
                                                        room.id()))
                                || releaseStatus(current) != ReleaseStatus.VERIFIED
                                || !runtimeValid(current));
        String reason = refresh ? "PREPARATION_CHANGED" : null;
        Instant now = dbNow();
        var people =
                db.queryForList(
                        "SELECT"
                            + " m.member_id,m.identity_id,m.slot,m.ready_yn,m.role_code,m.active_yn,m.last_seen_at,m.presence_auth_rev,a.auth_rev,a.state,s.revoked_at,s.idle_until,s.absolute_until,s.identity_id"
                            + " AS session_identity,s.auth_rev AS session_auth_rev,i.active_yn AS"
                            + " identity_active,i.provider,i.realm,i.proof_at FROM game_member m"
                            + " JOIN member_account a ON a.id=m.member_id JOIN member_identity i ON"
                            + " i.id=m.identity_id AND i.member_id=a.id LEFT JOIN member_session s"
                            + " ON s.id=m.presence_session_id WHERE m.room_id=? ORDER BY"
                            + " m.slot,m.id",
                        room.id());
        var self =
                people.stream()
                        .filter(
                                p ->
                                        ((Number) p.get("member_id")).longValue() == memberId
                                                && Boolean.TRUE.equals(p.get("active_yn")))
                        .findFirst()
                        .orElseThrow(PublicGameService::missing);
        var peer =
                people.stream()
                        .filter(
                                p ->
                                        ((Number) p.get("member_id")).longValue() != memberId
                                                && Boolean.TRUE.equals(p.get("active_yn")))
                        .findFirst()
                        .orElse(null);
        boolean ownReady = Boolean.TRUE.equals(self.get("ready_yn"));
        boolean peerReady = peer != null && Boolean.TRUE.equals(peer.get("ready_yn"));
        boolean online = online(peer, now);
        boolean eligible = waiting && !refresh && room.lobbyUntil().isAfter(now);
        boolean host = ((Number) self.get("slot")).intValue() == 1;
        return new GameState(
                room.key(),
                GameMode.valueOf(room.mode()),
                room.title(),
                room.state(),
                "ENDED".equals(room.state())
                        ? db.queryForObject(
                                "SELECT end_reason FROM game_room WHERE id=?",
                                String.class,
                                room.id())
                        : null,
                "ENDED".equals(room.state()) || "CANCELLED".equals(room.state())
                        ? db.queryForObject(
                                "SELECT end_reason FROM game_room WHERE id=?",
                                String.class,
                                room.id())
                        : null,
                Long.toString(room.rev()),
                Long.toString(
                        db.queryForObject(
                                "SELECT draft_rev FROM game_room WHERE id=?",
                                Long.class,
                                room.id())),
                host,
                new Self(
                        ((Number) self.get("slot")).intValue(),
                        ownReady,
                        ownReady && eligible,
                        waiting ? null : (String) self.get("role_code")),
                new Partner(peer != null, peerReady, peerReady && eligible, online),
                room.versionNo(),
                Long.toString(room.playRev()),
                refresh,
                room.lobbyUntil(),
                room.startedAt(),
                room.deadlineAt(),
                now,
                db.queryForObject(
                        "SELECT CASE WHEN EXISTS(SELECT 1 FROM game_report WHERE room_id=?"
                                + " AND state='ACCEPTED') THEN 'PENDING' WHEN EXISTS(SELECT 1"
                                + " FROM game_report WHERE room_id=? AND state='PROPOSED')"
                                + " THEN 'PROPOSED' ELSE 'NONE' END",
                        String.class,
                        room.id(),
                        room.id()),
                waiting ? null : Math.max(0, room.maxAttempts() - room.gradedCount()),
                waiting
                        ? null
                        : Math.max(
                                0,
                                room.maxHints()
                                        - db.queryForObject(
                                                "SELECT count(*) FROM game_hint WHERE room_id=? AND"
                                                        + " member_id=?",
                                                Integer.class,
                                                room.id(),
                                                memberId)),
                eligible && "COOP".equals(room.mode()) && peer != null,
                host
                        && eligible
                        && ("SOLO".equals(room.mode())
                                || peerReady && ownReady && online && online(self, now)),
                reason,
                requestId);
    }

    private static boolean online(Map<String, Object> person, Instant now) {
        return person != null
                && person.get("last_seen_at") instanceof Timestamp seen
                && seen.toInstant().plusSeconds(30).isAfter(now)
                && "ACTIVE".equals(person.get("state"))
                && person.get("session_identity") != null
                && Boolean.TRUE.equals(person.get("identity_active"))
                && "LOCAL".equals(person.get("provider"))
                && "LOCAL".equals(person.get("realm"))
                && person.get("proof_at") != null
                && ((Number) person.get("session_identity")).longValue()
                        == ((Number) person.get("identity_id")).longValue()
                && person.get("presence_auth_rev") != null
                && ((Number) person.get("presence_auth_rev")).longValue()
                        == ((Number) person.get("auth_rev")).longValue()
                && person.get("session_auth_rev") != null
                && ((Number) person.get("session_auth_rev")).longValue()
                        == ((Number) person.get("auth_rev")).longValue()
                && person.get("revoked_at") == null
                && person.get("idle_until") instanceof Timestamp idle
                && idle.toInstant().isAfter(now)
                && person.get("absolute_until") instanceof Timestamp absolute
                && absolute.toInstant().isAfter(now);
    }

    private Action action(String name, boolean replayed, Room room, long memberId, UUID requestId) {
        return new Action(
                name,
                replayed,
                true,
                Map.of(
                        "gameKey",
                        room.key(),
                        "state",
                        room.state(),
                        "rev",
                        Long.toString(room.rev()),
                        "lobbyExpiresAt",
                        room.lobbyUntil()),
                state(room, memberId, requestId),
                requestId);
    }

    private long localIdentity(MemberAuthService.MemberPrincipal principal) {
        var ids =
                db.query(
                        "SELECT id FROM member_identity WHERE id=(SELECT identity_id FROM"
                            + " member_session WHERE id=?) AND member_id=? AND provider='LOCAL' AND"
                            + " realm='LOCAL' AND active_yn AND proof_at IS NOT NULL FOR UPDATE",
                        (rs, n) -> rs.getLong(1),
                        principal.sessionId(),
                        principal.memberId());
        if (ids.size() != 1) throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
        return ids.getFirst();
    }

    private void bound(String token, MemberAuthService.MemberPrincipal p) {
        String hash = HexFormat.of().formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", token));
        db.queryForList("SELECT id FROM member_account WHERE id=? FOR UPDATE", p.memberId());
        db.queryForList(
                "SELECT id FROM member_session WHERE id=? AND member_id=? FOR UPDATE",
                p.sessionId(),
                p.memberId());
        db.queryForList(
                "SELECT id FROM member_identity WHERE id=(SELECT identity_id FROM member_session"
                        + " WHERE id=?) FOR UPDATE",
                p.sessionId());
        db.queryForList("SELECT id FROM member_token WHERE token_hash=? FOR UPDATE", hash);
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
                        p.memberId(),
                        p.memberKey(),
                        p.authRev(),
                        p.sessionId(),
                        p.sessionKey(),
                        hash))) throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
    }

    private Set<Long> owners(long policyOwner, long memberId) {
        var owners = new TreeSet<>(memberPolicy.ownerIds(memberId));
        owners.add(policyOwner);
        return owners;
    }

    private void lockOwners(Set<Long> owners) {
        for (long id : owners) {
            db.queryForList("SELECT id FROM admin_account WHERE id=? FOR SHARE", id);
            db.queryForList(
                    "SELECT account_id FROM admin_credential WHERE account_id=? FOR SHARE", id);
        }
    }

    Receipt receipt(UUID key) {
        var rows =
                db.query(
                        "SELECT room_id,member_id,action,request_hash FROM game_action WHERE"
                                + " request_key=?",
                        (rs, n) ->
                                new Receipt(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getString(3),
                                        rs.getString(4)),
                        key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void lockRequest(UUID key) {
        db.query(
                "SELECT pg_advisory_xact_lock(?)",
                rs -> null,
                key.getMostSignificantBits() ^ key.getLeastSignificantBits());
    }

    private void lockTimeout() {
        db.execute("SET LOCAL lock_timeout = '5s'");
    }

    Instant dbNow() {
        return db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    static String digest(String... parts) {
        try {
            StringBuilder text = new StringBuilder();
            for (String part : parts) text.append(part.length()).append(':').append(part);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void uuid(UUID key) {
        if (key == null || key.version() != 4 || key.variant() != 2) throw invalid();
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    private static AuthException missing() {
        return new AuthException(404, "NOT_FOUND", "NOT_FOUND");
    }

    private static AuthException invalidCursor() {
        return AuthException.badRequest("INVALID_CURSOR");
    }
}
