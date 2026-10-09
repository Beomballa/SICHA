package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.model.FrozenSnapshotCodec;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** 두 참가자와 정책·원본을 같은 거래에서 재검증하고 진행 중에는 설치 실행환경도 확인하는 플레이 접근 경계다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PlaytestAccess {
    private final JdbcTemplate db;
    private final MemberAuthService auth;
    private final CryptoService crypto;
    private final MemberPolicyGate memberPolicy;
    private final PlaytestPolicyGate policy;
    private final GradeRuntimeRepository runtimes;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final TransactionTemplate tx;

    public PlaytestAccess(
            JdbcTemplate db,
            MemberAuthService auth,
            CryptoService crypto,
            MemberPolicyGate memberPolicy,
            PlaytestPolicyGate policy,
            GradeRuntimeRepository runtimes,
            @Qualifier("gradeInstallations")
                    Map<String, InstalledRuntimeManifestVerifier> installations) {
        this.db = db;
        this.auth = auth;
        this.crypto = crypto;
        this.memberPolicy = memberPolicy;
        this.policy = policy;
        this.runtimes = runtimes;
        this.installations = Map.copyOf(installations);
        tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Participant(
            long id,
            UUID key,
            int slot,
            int generation,
            String inviteState,
            boolean ready,
            String role,
            Instant seen,
            long policyId,
            String noticeHash) {}

    public record Context(
            long id,
            UUID key,
            long storyId,
            long versionId,
            long snapshotId,
            long revision,
            long draftRev,
            String state,
            String outcome,
            String mode,
            String roleA,
            String roleB,
            Instant inviteUntil,
            Instant readyUntil,
            Instant startedAt,
            Instant deadline,
            Instant endedAt,
            Instant resultUntil,
            long policyId,
            JsonNode payload,
            Participant self,
            Participant partner) {
        public String scope() {
            return "test:" + key;
        }

        public Instant clock(JdbcTemplate db) {
            return db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        }
    }

    @FunctionalInterface
    public interface Operation<T> {
        T apply(Context context);
    }

    /**
     * 소유자·회원 쌍·인증과 모든 상위 행을 정렬 잠그고 현재 정책·원본 및 진행 중 설치를 확인한다.
     *
     * @param token 현재 LOCAL 접근 토큰
     * @param testKey 대상 테스트 UUID v4
     * @param operation 잠금 안에서 실행할 작업; 원문 반환 전 감사와 노출을 확정해야 한다
     * @return 거래에서 확정된 작업 결과
     * @throws AuthException 인증·정책·원본·설치 또는 테스트 결속이 깨졌을 때
     */
    public <T> T execute(String token, UUID testKey, Operation<T> operation) {
        if (testKey == null || testKey.version() != 4 || testKey.variant() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
        var principal = auth.authenticate(token, false);
        var evidence = policy.prepare();
        var memberEvidence = memberPolicy.prepare();
        return tx.execute(
                status -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    var ids =
                            db.query(
                                    "SELECT"
                                        + " t.id,t.runtime_id,t.version_id,v.story_id,t.snapshot_id,m.member_id"
                                        + " FROM play_test t JOIN story_version v ON"
                                        + " v.id=t.version_id JOIN test_member m ON m.test_id=t.id"
                                        + " WHERE t.test_key=? ORDER BY m.member_id",
                                    (rs, n) ->
                                            new long[] {
                                                rs.getLong(1),
                                                rs.getLong(2),
                                                rs.getLong(3),
                                                rs.getLong(4),
                                                rs.getLong(5),
                                                rs.getLong(6)
                                            },
                                    testKey);
                    if (ids.size() != 2
                            || ids.get(0)[5] == ids.get(1)[5]
                            || ids.stream().noneMatch(row -> row[5] == principal.memberId()))
                        throw missing();
                    TreeSet<Long> owners = new TreeSet<>();
                    owners.add(evidence.owner());
                    for (long[] row : ids) owners.addAll(memberPolicy.ownerIds(row[5]));
                    for (long owner : owners) {
                        var locked =
                                db.query(
                                        "SELECT id FROM admin_account WHERE id=? FOR SHARE",
                                        (rs, n) -> rs.getLong(1),
                                        owner);
                        if (locked.size() != 1) throw unavailable();
                    }
                    long[] first = ids.getFirst();
                    for (long[] row : ids) {
                        var locked =
                                db.query(
                                        "SELECT id FROM member_account WHERE id=? AND"
                                                + " state='ACTIVE' FOR UPDATE",
                                        (rs, n) -> rs.getLong(1),
                                        row[5]);
                        if (locked.size() != 1) throw unavailable();
                    }
                    for (long[] row : ids) {
                        var identities =
                                db.query(
                                        "SELECT id FROM member_identity WHERE member_id=? AND"
                                            + " provider='LOCAL' AND realm='LOCAL' AND active_yn"
                                            + " AND proof_at IS NOT NULL FOR UPDATE",
                                        (rs, n) -> rs.getLong(1),
                                        row[5]);
                        if (identities.size() != 1)
                            throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                    }
                    for (long[] row : ids) {
                        var profile =
                                db.query(
                                        "SELECT member_id FROM member_profile WHERE member_id=? FOR"
                                                + " SHARE",
                                        (rs, n) -> rs.getLong(1),
                                        row[5]);
                        if (profile.size() != 1)
                            throw AuthException.conflict("PARTICIPANT_UNAVAILABLE");
                        var permit = memberPolicy.lock(memberEvidence, row[5], owners);
                        memberPolicy.checkPinned(permit, row[5]);
                        memberPolicy.check(permit);
                    }
                    String hash =
                            HexFormat.of().formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", token));
                    db.query(
                            "SELECT id FROM member_session WHERE id=? AND member_id=? FOR UPDATE",
                            (rs, n) -> rs.getLong(1),
                            principal.sessionId(),
                            principal.memberId());
                    db.query(
                            "SELECT i.id FROM member_identity i JOIN member_session s ON"
                                    + " s.identity_id=i.id WHERE s.id=? FOR UPDATE OF i",
                            (rs, n) -> rs.getLong(1),
                            principal.sessionId());
                    db.query(
                            "SELECT id FROM member_token WHERE token_hash=? FOR UPDATE",
                            (rs, n) -> rs.getLong(1),
                            hash);
                    Boolean valid =
                            db.queryForObject(
                                    """
                                    SELECT EXISTS(SELECT 1 FROM member_account a JOIN member_session s ON s.member_id=a.id
                                    JOIN member_identity i ON i.id=s.identity_id AND i.member_id=a.id JOIN member_token t ON t.session_id=s.id
                                    WHERE a.id=? AND a.member_key=? AND a.state='ACTIVE' AND a.auth_rev=? AND s.id=?
                                    AND s.session_key=? AND s.auth_rev=a.auth_rev AND s.revoked_at IS NULL
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
                    if (!Boolean.TRUE.equals(valid))
                        throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
                    var notice = policy.lock(evidence, owners);
                    var runtime =
                            runtimes.lockRuntime(first[1])
                                    .orElseThrow(
                                            () -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
                    var story =
                            db.query(
                                    "SELECT active_yn FROM story WHERE id=? FOR UPDATE",
                                    (rs, n) -> rs.getBoolean(1),
                                    first[3]);
                    var version =
                            db.query(
                                    "SELECT active_yn,status,current_snapshot_id,policy_code FROM"
                                            + " story_version WHERE id=? AND story_id=? FOR UPDATE",
                                    (rs, n) ->
                                            new Object[] {
                                                rs.getBoolean(1),
                                                rs.getString(2),
                                                rs.getObject(3),
                                                rs.getString(4)
                                            },
                                    first[2],
                                    first[3]);
                    var tests =
                            db.query(
                                    """
                                    SELECT id,snapshot_id,runtime_id,config_hash,runtime_epoch,rev,state,mode,role_a,role_b,
                                    invite_until,ready_until,started_at,deadline_at,draft_rev,outcome,ended_at,result_until
                                    FROM play_test WHERE id=? FOR UPDATE
                                    """,
                                    (rs, n) ->
                                            new Object[] {
                                                rs.getLong(1),
                                                rs.getLong(2),
                                                rs.getLong(3),
                                                rs.getString(4),
                                                rs.getLong(5),
                                                rs.getLong(6),
                                                rs.getString(7),
                                                rs.getString(8),
                                                rs.getString(9),
                                                rs.getString(10),
                                                rs.getTimestamp(11).toInstant(),
                                                time(rs.getTimestamp(12)),
                                                time(rs.getTimestamp(13)),
                                                time(rs.getTimestamp(14)),
                                                rs.getLong(15),
                                                rs.getString(16),
                                                time(rs.getTimestamp(17)),
                                                time(rs.getTimestamp(18))
                                            },
                                    first[0]);
                    if (story.size() != 1 || version.size() != 1 || tests.size() != 1)
                        throw missing();
                    Object[] v = version.getFirst(), t = tests.getFirst();
                    boolean ended = "ENDED".equals(t[6]);
                    if (!story.getFirst()
                            || !(boolean) v[0]
                            || !("REVIEW".equals(v[1])
                                    || ended && ("READY".equals(v[1]) || "PUBLISHED".equals(v[1])))
                            || !java.util.Objects.equals(v[2], first[4])
                            || (long) t[1] != first[4]
                            || (long) t[2] != runtime.id()
                            || !ended
                                    && (!"AVAILABLE".equals(runtime.state())
                                            || !t[3].equals(runtime.configHash())
                                            || (long) t[4] != runtime.epoch()))
                        throw AuthException.conflict("INVITATION_INVALIDATED");
                    if (!ended) {
                        var installation = installations.get(runtime.code());
                        if (installation == null)
                            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                        try {
                            if (!installation.verify(runtime).profile().policyCode().equals(v[3]))
                                throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                        } catch (IllegalStateException failure) {
                            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                        }
                    }
                    var retention =
                            db.query(
                                    "SELECT policy_id,raw_until FROM test_retention WHERE test_id=?"
                                            + " FOR SHARE",
                                    rs ->
                                            rs.next()
                                                    ? new Object[] {
                                                        rs.getLong(1), time(rs.getTimestamp(2))
                                                    }
                                                    : null,
                                    first[0]);
                    if (retention == null || (long) retention[0] != notice.policyId())
                        throw AuthException.conflict("POLICY_CHANGED");
                    long pinned = (long) retention[0];
                    if (ended) {
                        Instant now =
                                db.queryForObject("SELECT clock_timestamp()", Timestamp.class)
                                        .toInstant();
                        if (t[16] == null || t[17] == null || !now.isBefore((Instant) t[17]))
                            throw new AuthException(
                                    410, "RESULT_ACCESS_EXPIRED", "RESULT_ACCESS_EXPIRED");
                        if (retention[1] != null && !now.isBefore((Instant) retention[1]))
                            throw AuthException.conflict("INVITATION_INVALIDATED");
                    }
                    var members =
                            db.query(
                                    """
                                    SELECT m.member_id,a.member_key,m.slot,m.invite_gen,m.invite_state,m.ready_yn,m.role_code,
                                    m.last_seen_at,m.accepted_policy_id,m.accepted_notice_hash
                                    FROM test_member m JOIN member_account a ON a.id=m.member_id WHERE m.test_id=? ORDER BY m.member_id FOR UPDATE OF m
                                    """,
                                    (rs, n) ->
                                            new Participant(
                                                    rs.getLong(1),
                                                    rs.getObject(2, UUID.class),
                                                    rs.getInt(3),
                                                    rs.getInt(4),
                                                    rs.getString(5),
                                                    rs.getBoolean(6),
                                                    rs.getString(7),
                                                    time(rs.getTimestamp(8)),
                                                    rs.getLong(9),
                                                    rs.getString(10)),
                                    first[0]);
                    if (members.size() != 2
                            || members.get(0).id() != ids.get(0)[5]
                            || members.get(1).id() != ids.get(1)[5]
                            || members.get(0).slot() == members.get(1).slot()) throw unavailable();
                    Participant self =
                            members.get(0).id() == principal.memberId()
                                    ? members.get(0)
                                    : members.get(1);
                    Participant partner =
                            members.get(0).id() == principal.memberId()
                                    ? members.get(1)
                                    : members.get(0);
                    if (!"ACCEPTED".equals(self.inviteState())
                            || !"ACCEPTED".equals(partner.inviteState())
                            || self.policyId() != pinned
                            || partner.policyId() != pinned
                            || !notice.hash().equals(self.noticeHash())
                            || !notice.hash().equals(partner.noticeHash()))
                        throw AuthException.conflict("INVITATION_INVALIDATED");
                    String source =
                            db.queryForObject(
                                    "SELECT payload::text FROM review_snapshot WHERE id=? AND"
                                            + " version_id=? AND edit_rev=(SELECT edit_rev FROM"
                                            + " review_snapshot WHERE id=?)",
                                    String.class,
                                    first[4],
                                    first[2],
                                    first[4]);
                    JsonNode payload;
                    try {
                        payload =
                                FrozenSnapshotCodec.decode(source.getBytes(StandardCharsets.UTF_8))
                                        .payload();
                    } catch (RuntimeException failure) {
                        throw AuthException.conflict("INVITATION_INVALIDATED");
                    }
                    if (!payload.path("policy").path("policyCode").asText().equals(v[3])
                            || !payload.path("sourceRev")
                                    .asText()
                                    .equals(
                                            db.queryForObject(
                                                            "SELECT edit_rev FROM review_snapshot"
                                                                    + " WHERE id=?",
                                                            Long.class,
                                                            first[4])
                                                    .toString()))
                        throw AuthException.conflict("INVITATION_INVALIDATED");
                    for (JsonNode rubric : payload.path("resources").path("rubrics")) {
                        if (!rubric.path("requiredYn").asBoolean(false)) continue;
                        JsonNode requiredNotice = rubric.path("ruleData").path("requiredNotice");
                        if (!requiredNotice.isTextual() || requiredNotice.textValue().isBlank())
                            throw AuthException.conflict("INVITATION_INVALIDATED");
                    }
                    Context context =
                            new Context(
                                    first[0],
                                    testKey,
                                    first[3],
                                    first[2],
                                    first[4],
                                    (long) t[5],
                                    (long) t[14],
                                    (String) t[6],
                                    (String) t[15],
                                    (String) t[7],
                                    (String) t[8],
                                    (String) t[9],
                                    (Instant) t[10],
                                    (Instant) t[11],
                                    (Instant) t[12],
                                    (Instant) t[13],
                                    (Instant) t[16],
                                    (Instant) t[17],
                                    pinned,
                                    payload,
                                    self,
                                    partner);
                    T result = operation.apply(context);
                    memberPolicy.check(memberPolicy.lock(memberEvidence, self.id(), owners));
                    memberPolicy.check(memberPolicy.lock(memberEvidence, partner.id(), owners));
                    if (policy.lock(evidence, owners).policyId() != pinned)
                        throw AuthException.conflict("POLICY_CHANGED");
                    return result;
                });
    }

    private static Instant time(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 테스트 잠금 아래 미확정 판정을 우선하고 인증된 마감 점수·시간 전이·감사를 함께 확정한다. */
    static AuthException normalizeTime(
            JdbcTemplate db,
            CryptoService crypto,
            long id,
            String actorKind,
            String actorRef,
            UUID requestId) {
        var row =
                db.queryForMap(
                        "SELECT"
                            + " test_key,state,rev,invite_until,ready_until,deadline_at,attempt_count,wrong_count,clock_timestamp()"
                            + " AS now FROM play_test WHERE id=? FOR UPDATE",
                        id);
        String state = (String) row.get("state");
        Instant now = ((Timestamp) row.get("now")).toInstant();
        boolean waiting =
                "WAITING".equals(state)
                        && (!now.isBefore(((Timestamp) row.get("invite_until")).toInstant())
                                || row.get("ready_until") != null
                                        && !now.isBefore(
                                                ((Timestamp) row.get("ready_until")).toInstant()));
        boolean running =
                "RUNNING".equals(state)
                        && row.get("deadline_at") != null
                        && !now.isBefore(((Timestamp) row.get("deadline_at")).toInstant());
        if (running) {
            Boolean pending =
                    db.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM test_report WHERE test_id=? AND"
                                    + " state='ACCEPTED')",
                            Boolean.class,
                            id);
            if (Boolean.TRUE.equals(pending)) running = false;
        }
        int score = running ? timeoutScore(db, crypto, id, row) : 0;
        if (waiting || running) {
            String next = waiting ? "EXPIRED" : "ENDED";
            UUID eventKey = UUID.randomUUID();
            if (db.update(
                            "UPDATE play_test SET"
                                + " state=?,ended_at=?,rev=rev+1,updated_at=clock_timestamp(),"
                                + " outcome=CASE WHEN ? THEN 'TIME_LIMIT' ELSE outcome END,"
                                + " final_score=CASE WHEN ? THEN ? ELSE final_score END,"
                                + " result_until=CASE WHEN ? THEN ?::timestamptz ELSE result_until"
                                + " END WHERE id=? AND state=? AND rev=?",
                            next,
                            Timestamp.from(now),
                            running,
                            running,
                            score,
                            running,
                            Timestamp.from(now.plusSeconds(86400)),
                            id,
                            state,
                            row.get("rev"))
                    != 1) throw unavailable();
            if (db.update(
                            "INSERT INTO"
                                + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                                + " VALUES (?,?,?,?,'PLAYTEST',?,?,'RESULT',?,'{}'::jsonb)",
                            eventKey,
                            actorKind,
                            actorRef,
                            waiting ? "INVITATION_EXPIRE" : "TEST_DEADLINE",
                            "test:" + row.get("test_key"),
                            requestId,
                            waiting ? "EXPIRED" : "TIME_LIMIT")
                    != 1) throw unavailable();
            Boolean valid =
                    db.queryForObject(
                            "SELECT t.state=? AND t.rev=? AND t.ended_at=? AND (?=false OR"
                                + " (t.outcome='TIME_LIMIT' AND t.final_score=? AND"
                                + " t.result_until=?)) AND EXISTS(SELECT 1 FROM test_audit WHERE"
                                + " event_key=? AND scope_key=? AND request_id=? AND"
                                + " business_result=? AND actor_kind=? AND actor_ref=? AND action=?"
                                + " AND scope_kind='PLAYTEST' AND phase='RESULT') FROM play_test t"
                                + " WHERE t.id=?",
                            Boolean.class,
                            next,
                            ((Number) row.get("rev")).longValue() + 1,
                            Timestamp.from(now),
                            running,
                            score,
                            Timestamp.from(now.plusSeconds(86400)),
                            eventKey,
                            "test:" + row.get("test_key"),
                            requestId,
                            waiting ? "EXPIRED" : "TIME_LIMIT",
                            actorKind,
                            actorRef,
                            waiting ? "INVITATION_EXPIRE" : "TEST_DEADLINE",
                            id);
            if (!Boolean.TRUE.equals(valid)) throw unavailable();
        }
        if (waiting || "EXPIRED".equals(state))
            return new AuthException(410, "INVITATION_EXPIRED", "INVITATION_EXPIRED");
        if (running) return new AuthException(410, "TEST_EXPIRED", "TEST_EXPIRED");
        return null;
    }

    /** 마지막 정상 TEST의 성공 시도와 독립 전체 결과를 인증한 뒤 마감 점수를 계산한다. */
    private static int timeoutScore(
            JdbcTemplate db, CryptoService crypto, long id, Map<String, Object> test) {
        var reports =
                db.queryForList(
                        """
                        SELECT r.submit_no,j.id AS job_id
                        FROM test_report r JOIN grade_job j ON j.report_id=r.id
                        JOIN play_test t ON t.id=r.test_id
                        WHERE r.test_id=? AND r.state='GRADED' AND j.state='COMPLETED'
                          AND j.batch_id IS NULL AND j.sample_code IS NULL AND j.repeat_no IS NULL
                          AND j.input_hash IS NULL AND j.accepted_at=r.accepted_at
                          AND j.snapshot_id=t.snapshot_id
                          AND j.runtime_id=t.runtime_id AND j.config_hash=t.config_hash
                          AND r.snapshot_id=t.snapshot_id AND r.runtime_id=t.runtime_id
                          AND r.config_hash=t.config_hash AND r.runtime_epoch=t.runtime_epoch
                        ORDER BY r.submit_no DESC
                        """,
                        id);
        int attempts = ((Number) test.get("attempt_count")).intValue();
        int wrong = ((Number) test.get("wrong_count")).intValue();
        if (attempts < 0 || attempts > 5 || wrong != attempts || reports.size() != attempts)
            throw unavailable();
        if (reports.isEmpty()) return 0;
        Map<String, Object> latest = reports.getFirst();
        if (((Number) latest.get("submit_no")).intValue() != attempts) throw unavailable();
        JsonNode result =
                new PlaytestResultAuthenticator(db, crypto)
                        .authenticate(id, ((Number) latest.get("job_id")).longValue());
        if (result.get("success").booleanValue()) throw unavailable();
        return Math.max(0, result.get("baseScore").intValue() - wrong * 10);
    }

    private static AuthException missing() {
        return new AuthException(404, "NOT_FOUND", "NOT_FOUND");
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("PLAYTEST_UNAVAILABLE");
    }
}
