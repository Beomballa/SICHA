package com.reasoning.common.member.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 계정 접수의 실제 대상 캡처와 명시 호출된 단일 PRIMARY 물리 소비를 분리한다. */
public final class MemberErasureService {
    public record Accepted(
            long eraseId, UUID eraseKey, String receiptToken, Instant receiptExpiresAt) {}

    public record Status(
            String state,
            boolean accessRevoked,
            boolean primaryDone,
            boolean externalDone,
            boolean backupDone,
            Instant completedAt,
            UUID requestId) {}

    private final JdbcTemplate db;
    private final CryptoService crypto;
    private final TransactionTemplate transaction;
    private final TransactionTemplate limiter;

    /** 기존 회원 JDBC 연결과 동일한 트랜잭션 관리자로 명시 작업 경계를 구성한다. */
    public MemberErasureService(
            JdbcTemplate db, PlatformTransactionManager manager, CryptoService crypto) {
        this.db = db;
        this.crypto = crypto;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(15);
        this.limiter = new TransactionTemplate(manager);
        this.limiter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.limiter.setTimeout(15);
    }

    /** TEST·시작 고정 참가자·현재 대기석 회원의 계정을 ID 순서로 먼저 잠그고 관계를 재확인한다. */
    void lockParticipants(long memberId) {
        requireTransaction();
        List<Long> closure = participantIds(memberId);
        List<String> memberships = membershipClosure(memberId);
        for (Long id : closure) {
            if (db.query(
                                    "SELECT id FROM public.member_account WHERE id=? FOR UPDATE",
                                    (rs, index) -> rs.getLong(1),
                                    id)
                            .size()
                    != 1) throw AuthException.conflict("STATE_CONFLICT");
        }
        if (!closure.equals(participantIds(memberId))
                || !memberships.equals(membershipClosure(memberId)))
            throw AuthException.conflict("STATE_CONFLICT");
    }

    private List<Long> participantIds(long memberId) {
        return db.query(
                "SELECT id FROM public.member_account WHERE id=? OR id IN (SELECT tm2.member_id"
                        + " FROM public.test_member tm1 JOIN public.test_member tm2 ON"
                        + " tm2.test_id=tm1.test_id WHERE tm1.member_id=?) OR id IN (SELECT"
                        + " gm2.member_id FROM public.game_member gm1 JOIN public.game_room r ON"
                        + " r.id=gm1.room_id JOIN public.game_member gm2 ON gm2.room_id=r.id WHERE"
                        + " gm1.member_id=? AND r.purpose='PUBLIC' AND (gm1.role_code IS NOT NULL"
                        + " OR (r.state='WAITING' AND gm1.role_code IS NULL"
                        + " AND gm1.active_yn AND gm2.active_yn))) ORDER BY id",
                (rs, index) -> rs.getLong(1),
                memberId,
                memberId,
                memberId);
    }

    private List<String> membershipClosure(long memberId) {
        return db.query(
                "SELECT source FROM (SELECT 'TEST:'||tm1.test_id::text||':'||tm2.member_id::text AS"
                    + " source FROM public.test_member tm1 JOIN public.test_member tm2 ON"
                    + " tm2.test_id=tm1.test_id WHERE tm1.member_id=? UNION ALL SELECT"
                    + " 'ROOM:'||gm1.room_id::text||':'||gm2.id::text||':'||gm2.member_id::text"
                    + " FROM public.game_member gm1 JOIN public.game_room r ON r.id=gm1.room_id"
                    + " JOIN public.game_member gm2 ON gm2.room_id=gm1.room_id WHERE"
                    + " gm1.member_id=? AND r.purpose='PUBLIC' AND gm1.role_code IS NOT NULL UNION"
                    + " ALL SELECT"
                    + " 'WAITING:'||gm1.room_id::text||':'||gm2.id::text||':'||gm2.member_id::text"
                    + " FROM public.game_member gm1 JOIN public.game_room r ON r.id=gm1.room_id"
                    + " JOIN public.game_member gm2 ON gm2.room_id=gm1.room_id WHERE"
                    + " gm1.member_id=? AND r.purpose='PUBLIC' AND r.state='WAITING' AND"
                    + " gm1.role_code IS NULL AND gm1.active_yn AND gm2.active_yn UNION ALL SELECT"
                    + " 'MEMBERSHIP:'||gm.id::text||':'||gm.room_id::text FROM public.game_member"
                    + " gm JOIN public.game_room r ON r.id=gm.room_id WHERE gm.member_id=? AND"
                    + " r.purpose='PUBLIC' AND gm.role_code IS NULL) closure ORDER BY source",
                (rs, index) -> rs.getString(1),
                memberId,
                memberId,
                memberId,
                memberId);
    }

    /** 회원 프로필 정책과 모든 실재 TEST/제공자 시도를 단일 접수 트랜잭션에서 암호화해 캡처한다. */
    Accepted enqueue(long memberId, UUID memberKey) {
        requireTransaction();
        Long policy =
                db.queryForObject(
                        "SELECT policy_id FROM public.member_profile WHERE member_id=?",
                        Long.class,
                        memberId);
        if (policy == null) throw unavailable();
        if (Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM public.game_room r WHERE"
                            + " r.purpose='REVIEW_SOLO_QUALIFICATION' AND r.selected_member_id=?)"
                            + " OR EXISTS (SELECT 1 FROM public.blind_assessment WHERE member_id=?)"
                            + " OR EXISTS (SELECT 1 FROM public.release_solo_feedback WHERE"
                            + " member_id=?)",
                        Boolean.class,
                        memberId,
                        memberId,
                        memberId))) throw unavailable();
        UUID eraseKey = crypto.randomUuid();
        String receipt = crypto.randomToken(32);
        String receiptHash = hex(crypto.tokenHash("MEMBER_ERASURE_RECEIPT_V1", receipt));
        long id =
                db.queryForObject(
                        "SELECT nextval(pg_get_serial_sequence('public.privacy_erase','id'))",
                        Long.class);
        Instant now = db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        Instant until = now.plusSeconds(90L * 86400);
        List<TestSource> tests =
                db.query(
                        "SELECT p.id,p.test_key FROM public.test_member tm JOIN public.play_test p"
                            + " ON p.id=tm.test_id WHERE tm.member_id=? ORDER BY p.id FOR UPDATE OF"
                            + " p",
                        (rs, index) -> new TestSource(rs.getLong(1), (UUID) rs.getObject(2)),
                        memberId);
        var rooms =
                db.query(
                        "SELECT r.id,r.room_key FROM public.game_room r WHERE r.purpose='PUBLIC'"
                            + " AND EXISTS (SELECT 1 FROM public.game_member m WHERE m.room_id=r.id"
                            + " AND m.member_id=? AND m.role_code IS NOT NULL) ORDER BY r.id FOR"
                            + " UPDATE OF r",
                        (rs, index) -> new RoomSource(rs.getLong(1), (UUID) rs.getObject(2)),
                        memberId);
        var memberships =
                db.query(
                        "SELECT m.id,m.room_id,r.room_key FROM public.game_member m JOIN"
                            + " public.game_room r ON r.id=m.room_id WHERE m.member_id=? AND"
                            + " r.purpose='PUBLIC' AND m.role_code IS NULL ORDER BY m.id FOR UPDATE"
                            + " OF m",
                        (rs, index) ->
                                new MembershipSource(
                                        rs.getLong(1), rs.getLong(2), (UUID) rs.getObject(3)),
                        memberId);
        var identities =
                db.query(
                        "SELECT id,identity_key FROM public.member_identity WHERE member_id=? ORDER"
                                + " BY id FOR SHARE",
                        (rs, index) -> new SourceKey(rs.getLong(1), (UUID) rs.getObject(2)),
                        memberId);
        var reports =
                db.query(
                        "SELECT r.id,r.report_key,r.test_id FROM public.test_report r JOIN"
                            + " public.test_member tm ON tm.test_id=r.test_id WHERE tm.member_id=?"
                            + " ORDER BY r.id FOR SHARE OF r",
                        (rs, index) ->
                                new ReportSource(
                                        rs.getLong(1), (UUID) rs.getObject(2), rs.getLong(3)),
                        memberId);
        var jobs =
                db.query(
                        "SELECT j.id,j.job_key,r.test_id FROM public.grade_job j JOIN"
                            + " public.test_report r ON r.id=j.report_id JOIN public.test_member tm"
                            + " ON tm.test_id=r.test_id WHERE tm.member_id=? AND j.batch_id IS NULL"
                            + " ORDER BY j.id FOR SHARE OF j",
                        (rs, index) ->
                                new JobSource(rs.getLong(1), (UUID) rs.getObject(2), rs.getLong(3)),
                        memberId);
        var gameReports =
                db.query(
                        "SELECT gr.id,gr.report_key,gr.room_id FROM public.game_report gr JOIN"
                            + " public.game_member m ON m.room_id=gr.room_id WHERE m.member_id=?"
                            + " AND m.role_code IS NOT NULL ORDER BY gr.id FOR SHARE OF gr",
                        (rs, index) ->
                                new ReportSource(
                                        rs.getLong(1), (UUID) rs.getObject(2), rs.getLong(3)),
                        memberId);
        var gameJobs =
                db.query(
                        "SELECT j.id,j.job_key,gr.room_id FROM public.grade_job j JOIN"
                            + " public.game_report gr ON gr.id=j.game_report_id JOIN"
                            + " public.game_member m ON m.room_id=gr.room_id WHERE m.member_id=?"
                            + " AND m.role_code IS NOT NULL ORDER BY j.id FOR SHARE OF j",
                        (rs, index) ->
                                new JobSource(rs.getLong(1), (UUID) rs.getObject(2), rs.getLong(3)),
                        memberId);
        var attempts =
                db.query(
                        "SELECT"
                            + " a.job_id,a.attempt_no,a.provider_ref,j.job_key,j.runtime_id,rt.code,a.worker_key,a.lease_gen"
                            + " FROM public.grade_attempt a JOIN public.grade_job j ON"
                            + " j.id=a.job_id JOIN public.grade_runtime rt ON rt.id=j.runtime_id"
                            + " WHERE (EXISTS (SELECT 1 FROM public.test_report r JOIN"
                            + " public.test_member tm ON tm.test_id=r.test_id WHERE"
                            + " r.id=j.report_id AND tm.member_id=?) OR EXISTS (SELECT 1 FROM"
                            + " public.game_report gr JOIN public.game_member gm ON"
                            + " gm.room_id=gr.room_id WHERE gr.id=j.game_report_id AND"
                            + " gm.member_id=? AND gm.role_code IS NOT NULL)) AND j.batch_id IS"
                            + " NULL ORDER BY a.job_id,a.attempt_no FOR SHARE OF a",
                        (rs, index) ->
                                new Attempt(
                                        rs.getLong(1),
                                        rs.getShort(2),
                                        rs.getString(3),
                                        (UUID) rs.getObject(4),
                                        rs.getLong(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getLong(8)),
                        memberId,
                        memberId);
        var records =
                db.query(
                        "SELECT p.id,p.record_key,p.room_id FROM public.play_record p WHERE"
                                + " p.member_id=? OR p.room_id IN (SELECT m.room_id FROM"
                                + " public.game_member m JOIN public.game_room r ON r.id=m.room_id"
                                + " WHERE m.member_id=? AND m.role_code IS NOT NULL AND"
                                + " r.purpose='PUBLIC') ORDER BY p.id FOR SHARE OF p",
                        (rs, index) ->
                                new RecordSource(
                                        rs.getLong(1),
                                        (UUID) rs.getObject(2),
                                        rs.getObject(3, Long.class)),
                        memberId,
                        memberId);
        String manifest =
                manifest(
                        memberId,
                        memberKey,
                        identities,
                        tests,
                        reports,
                        jobs,
                        rooms,
                        memberships,
                        gameReports,
                        gameJobs,
                        attempts,
                        records);
        byte[] locator = cipher(manifest, "privacy_erase", id, "locator_cipher");
        db.update(
                "INSERT INTO"
                    + " public.privacy_erase(id,erase_key,member_id,policy_id,reason_code,locator_cipher,receipt_hash,receipt_until,created_at,backup_until)"
                    + " OVERRIDING SYSTEM VALUE VALUES"
                    + " (?,?,?,?,'ACCOUNT_REQUEST',?,?,?,?,?::timestamptz)",
                id,
                eraseKey,
                memberId,
                policy,
                locator,
                receiptHash,
                Timestamp.from(until),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(35L * 86400)));
        target(id, "PRIMARY", memberId, null, null, null, null, manifest);
        target(id, "BACKUP", null, null, null, null, null, manifest);
        target(id, "RESTORE_GUARD", null, null, null, null, null, manifest);
        for (TestSource test : tests) {
            List<Long> canonical =
                    db.query(
                            "SELECT id FROM public.privacy_target WHERE kind='PRIMARY' AND"
                                    + " test_id=? AND dependency_id IS NULL ORDER BY id",
                            (rs, index) -> rs.getLong(1),
                            test.id());
            target(
                    id,
                    "PRIMARY",
                    null,
                    test.id(),
                    canonical.isEmpty() ? null : canonical.getFirst(),
                    null,
                    null,
                    "test:" + test.key());
        }
        for (RoomSource room : rooms) {
            List<Long> canonical =
                    db.query(
                            "SELECT id FROM public.privacy_target WHERE kind='PRIMARY' AND"
                                    + " source_kind='ROOM' AND room_id=? AND dependency_id IS NULL"
                                    + " ORDER BY id",
                            (rs, index) -> rs.getLong(1),
                            room.id());
            targetGame(
                    id,
                    "ROOM",
                    room.id(),
                    null,
                    canonical.isEmpty() ? null : canonical.getFirst(),
                    "room:" + room.key());
        }
        for (MembershipSource membership : memberships)
            targetGame(
                    id,
                    "MEMBERSHIP",
                    null,
                    membership.id(),
                    null,
                    "membership:" + membership.id() + ":" + membership.roomKey());
        for (Attempt attempt : attempts)
            target(
                    id,
                    "PROCESSOR",
                    null,
                    null,
                    null,
                    attempt.jobId(),
                    attempt.number(),
                    processorLocator(attempt));
        return new Accepted(id, eraseKey, receipt, until);
    }

    private record SourceKey(long id, UUID key) {}

    private record TestSource(long id, UUID key) {}

    private record RoomSource(long id, UUID key) {}

    private record MembershipSource(long id, long roomId, UUID roomKey) {}

    private record RecordSource(long id, UUID key, Long roomId) {}

    private record ReportSource(long id, UUID key, long testId) {}

    private record JobSource(long id, UUID key, long testId) {}

    private record Attempt(
            long jobId,
            short number,
            String providerRef,
            UUID jobKey,
            long runtimeId,
            String runtimeCode,
            String workerKey,
            long leaseGen) {}

    /** 복구 측에서 회원·TEST·PUBLIC 방·참가·보고서·작업의 실제 키를 재식별한다. */
    private String manifest(
            long memberId,
            UUID memberKey,
            List<SourceKey> identities,
            List<TestSource> tests,
            List<ReportSource> reports,
            List<JobSource> jobs,
            List<RoomSource> rooms,
            List<MembershipSource> memberships,
            List<ReportSource> gameReports,
            List<JobSource> gameJobs,
            List<Attempt> attempts,
            List<RecordSource> records) {
        ObjectNode document = new ObjectMapper().createObjectNode();
        document.put("formatNo", 1);
        document.putObject("member").put("id", memberId).put("key", memberKey.toString());
        var identityNodes = document.putArray("identities");
        for (SourceKey item : identities)
            identityNodes.addObject().put("id", item.id()).put("key", item.key().toString());
        var testNodes = document.putArray("tests");
        for (TestSource item : tests)
            testNodes.addObject().put("id", item.id()).put("key", item.key().toString());
        var reportNodes = document.putArray("reports");
        for (ReportSource item : reports)
            reportNodes
                    .addObject()
                    .put("id", item.id())
                    .put("key", item.key().toString())
                    .put("testId", item.testId());
        var jobNodes = document.putArray("jobs");
        for (JobSource item : jobs)
            jobNodes.addObject()
                    .put("id", item.id())
                    .put("key", item.key().toString())
                    .put("testId", item.testId());
        var roomNodes = document.putArray("rooms");
        for (RoomSource item : rooms)
            roomNodes.addObject().put("id", item.id()).put("key", item.key().toString());
        var memberNodes = document.putArray("memberships");
        for (MembershipSource item : memberships)
            memberNodes
                    .addObject()
                    .put("id", item.id())
                    .put("roomId", item.roomId())
                    .put("roomKey", item.roomKey().toString());
        var gameReportNodes = document.putArray("gameReports");
        for (ReportSource item : gameReports)
            gameReportNodes
                    .addObject()
                    .put("id", item.id())
                    .put("key", item.key().toString())
                    .put("roomId", item.testId());
        var gameJobNodes = document.putArray("gameJobs");
        for (JobSource item : gameJobs)
            gameJobNodes
                    .addObject()
                    .put("id", item.id())
                    .put("key", item.key().toString())
                    .put("roomId", item.testId());
        var attemptNodes = document.putArray("attempts");
        for (Attempt attempt : attempts) {
            try {
                attemptNodes.add(new ObjectMapper().readTree(processorLocator(attempt)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw unavailable();
            }
        }
        var recordNodes = document.putArray("records");
        for (RecordSource item : records)
            recordNodes
                    .addObject()
                    .put("id", item.id())
                    .put("key", item.key().toString())
                    .put("roomId", item.roomId());
        return MemberPolicyEvidenceRegistry.canonical(document);
    }

    /** 외부 삭제 담당자에게 필요한 실행 설정과 공급자 참조를 대상별 AAD로 봉인한다. */
    private String processorLocator(Attempt attempt) {
        ObjectNode document = new ObjectMapper().createObjectNode();
        document.put("formatNo", 1);
        document.put("runtimeId", attempt.runtimeId());
        document.put("runtimeCode", attempt.runtimeCode());
        document.put("jobId", attempt.jobId());
        document.put("jobKey", attempt.jobKey().toString());
        document.put("attemptNo", attempt.number());
        document.put("providerRef", attempt.providerRef());
        // 공급자 참조가 아직 없는 실행도 원격 journal과 대조할 실제 소유자를 보존한다.
        document.put("workerKey", attempt.workerKey());
        document.put("leaseGen", attempt.leaseGen());
        return MemberPolicyEvidenceRegistry.canonical(document);
    }

    /** 대상 ID의 AAD로 원문 참조를 봉인하며 PROCESSOR 검색값은 작업·시도 식별자 HMAC만 저장한다. */
    private void target(
            long rootId,
            String kind,
            Long memberId,
            Long testId,
            Long dependencyId,
            Long jobId,
            Short attemptNo,
            String value) {
        long id =
                db.queryForObject(
                        "SELECT nextval(pg_get_serial_sequence('public.privacy_target','id'))",
                        Long.class);
        String lookup =
                "PROCESSOR".equals(kind)
                        ? hex(
                                crypto.limitHash(
                                        "MEMBER_ERASURE_PROCESSOR_V1", jobId + ":" + attemptNo))
                        : null;
        db.update(
                "INSERT INTO"
                    + " public.privacy_target(id,erase_id,target_key,kind,source_kind,member_id,test_id,dependency_id,source_job_id,source_attempt_no,locator_cipher,lookup_hash)"
                    + " OVERRIDING SYSTEM VALUE VALUES (?,?,gen_random_uuid(),?,?,?,?,?,?,?,?,?)",
                id,
                rootId,
                kind,
                memberId != null ? "MEMBER" : testId != null ? "TEST" : "NONE",
                memberId,
                testId,
                dependencyId,
                jobId,
                attemptNo,
                cipher(value, "privacy_target", id, "locator_cipher"),
                lookup);
    }

    /** 고정 PUBLIC 방과 과거 회원관계에 대해 각각 실재 FK와 암호화된 복원 위치를 봉인한다. */
    private void targetGame(
            long rootId,
            String sourceKind,
            Long roomId,
            Long gameMemberId,
            Long dependencyId,
            String value) {
        long id =
                db.queryForObject(
                        "SELECT nextval(pg_get_serial_sequence('public.privacy_target','id'))",
                        Long.class);
        db.update(
                "INSERT INTO"
                    + " public.privacy_target(id,erase_id,target_key,kind,source_kind,room_id,game_member_id,dependency_id,locator_cipher)"
                    + " OVERRIDING SYSTEM VALUE VALUES (?,?,gen_random_uuid(),'PRIMARY',?,?,?,?,?)",
                id,
                rootId,
                sourceKind,
                roomId,
                gameMemberId,
                dependencyId,
                cipher(value, "privacy_target", id, "locator_cipher"));
    }

    /** 실제 DELETED 부모 이후에만 누락 대상이 없는지 DB 봉인 검사를 확정한다. */
    void seal(long eraseId, long memberId) {
        requireTransaction();
        Integer count =
                db.queryForObject(
                        "SELECT count(*) FROM public.privacy_target WHERE erase_id=?",
                        Integer.class,
                        eraseId);
        if (count == null || count < 3) throw unavailable();
        if (db.update(
                        "UPDATE public.privacy_erase SET"
                            + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=?,state='PROCESSING'"
                            + " WHERE id=? AND member_id=?",
                        count,
                        eraseId,
                        memberId)
                != 1) throw unavailable();
    }

    /** 한 번에 하나의 실재 대상만 임대하고 고정 SQL 소비를 같은 트랜잭션에서 실행한다. */
    public boolean processPrimary(UUID eraseKey) {
        if (eraseKey == null) throw unavailable();
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        Boolean consumed =
                transaction.execute(
                        status -> {
                            db.execute("SET LOCAL lock_timeout='5s'");
                            // 접수와 같은 순서로 회원을 먼저 잠가 canonical FK 대기 순환을 막는다.
                            List<Long> owners =
                                    db.query(
                                            "SELECT member_id FROM public.privacy_erase WHERE"
                                                    + " erase_key=?",
                                            (rs, index) -> rs.getObject(1, Long.class),
                                            eraseKey);
                            if (owners.size() != 1 || owners.getFirst() == null) return false;
                            long owner = owners.getFirst();
                            lockParticipants(owner);
                            List<Long> roots =
                                    db.query(
                                            "SELECT id FROM public.privacy_erase WHERE erase_key=?"
                                                    + " AND member_id=?"
                                                    + " FOR UPDATE",
                                            (rs, index) -> rs.getLong(1),
                                            eraseKey,
                                            owner);
                            if (roots.size() != 1) return false;
                            List<Long> targets =
                                    db.query(
                                            "SELECT t.id FROM public.privacy_target t WHERE"
                                                + " t.erase_id=? AND t.kind='PRIMARY' AND ("
                                                + " ((t.test_id IS NOT NULL OR t.room_id IS NOT"
                                                + " NULL OR t.game_member_id IS NOT NULL) AND"
                                                + " t.dependency_id IS NULL) OR (t.dependency_id IS"
                                                + " NOT NULL AND EXISTS (SELECT 1 FROM"
                                                + " public.privacy_target canonical WHERE"
                                                + " canonical.id=t.dependency_id AND"
                                                + " canonical.state='COMPLETE' AND"
                                                + " canonical.confirmed_at IS NOT NULL AND"
                                                + " canonical.test_id IS NULL AND canonical.room_id"
                                                + " IS NULL AND"
                                                + " canonical.result_code='PHYSICAL_REMOVED'))) AND"
                                                + " t.state IN ('PENDING','RETRY') AND"
                                                + " t.next_run_at<=clock_timestamp() ORDER BY CASE"
                                                + " WHEN t.source_kind='MEMBERSHIP' THEN 0 ELSE 1"
                                                + " END,t.id LIMIT 1 FOR UPDATE OF t",
                                            (rs, index) -> rs.getLong(1),
                                            roots.getFirst());
                            if (targets.isEmpty())
                                targets =
                                        db.query(
                                                "SELECT id FROM public.privacy_target WHERE"
                                                    + " erase_id=? AND kind='PRIMARY' AND member_id"
                                                    + " IS NOT NULL AND state IN"
                                                    + " ('PENDING','RETRY') AND"
                                                    + " next_run_at<=clock_timestamp() AND NOT"
                                                    + " EXISTS (SELECT 1 FROM public.privacy_target"
                                                    + " p WHERE p.erase_id=? AND p.kind='PRIMARY'"
                                                    + " AND (p.test_id IS NOT NULL OR p.room_id IS"
                                                    + " NOT NULL OR p.game_member_id IS NOT NULL OR"
                                                    + " p.dependency_id IS NOT NULL) AND"
                                                    + " p.state<>'COMPLETE') ORDER BY id LIMIT 1"
                                                    + " FOR UPDATE",
                                                (rs, index) -> rs.getLong(1),
                                                roots.getFirst(),
                                                roots.getFirst());
                            if (targets.isEmpty()) return false;
                            Long id = targets.getFirst();
                            db.update(
                                    "UPDATE public.privacy_target SET"
                                        + " state='RUNNING',attempt_count=attempt_count+1,lease_gen=lease_gen+1,lease_until=clock_timestamp()+interval"
                                        + " '60 seconds' WHERE id=?",
                                    id);
                            Long memberId =
                                    db.queryForObject(
                                            "SELECT member_id FROM public.privacy_erase WHERE id=?",
                                            Long.class,
                                            roots.getFirst());
                            if (memberId == null || memberId != owner) throw unavailable();
                            Long testId =
                                    db.queryForObject(
                                            "SELECT test_id FROM public.privacy_target WHERE id=?",
                                            Long.class,
                                            id);
                            Long dependencyId =
                                    db.queryForObject(
                                            "SELECT dependency_id FROM public.privacy_target WHERE"
                                                    + " id=?",
                                            Long.class,
                                            id);
                            Long roomId =
                                    db.queryForObject(
                                            "SELECT room_id FROM public.privacy_target WHERE id=?",
                                            Long.class,
                                            id);
                            Long gameMemberId =
                                    db.queryForObject(
                                            "SELECT game_member_id FROM public.privacy_target WHERE"
                                                    + " id=?",
                                            Long.class,
                                            id);
                            if (testId == null
                                    && roomId == null
                                    && gameMemberId == null
                                    && dependencyId == null
                                    && memberId != null) cleanCorrelations(memberId);
                            if (!Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT public.consume_privacy_primary(?)",
                                            Boolean.class,
                                            id))) throw unavailable();
                            return true;
                        });
        return Boolean.TRUE.equals(consumed);
    }

    /** 실재 identity로부터 도출한 오래된 무결속 flow/회원 제한 키만 파기한다. */
    private void cleanCorrelations(long memberId) {
        List<Boolean> states =
                db.query(
                        "SELECT state='DELETED' FROM public.member_account"
                                + " WHERE id=? FOR UPDATE",
                        (rs, index) -> rs.getBoolean(1),
                        memberId);
        if (states.size() != 1 || !states.getFirst()) throw unavailable();
        String memberBucket = hex(crypto.limitHash("REAUTH_MEMBER_15M", Long.toString(memberId)));
        db.update(
                "DELETE FROM public.member_auth_limit WHERE scope='REAUTH_MEMBER_15M' AND"
                        + " bucket_hash=?",
                memberBucket);
        var identities =
                db.query(
                        "SELECT id,subject_cipher,lookup_hash FROM public.member_identity WHERE"
                                + " member_id=? ORDER BY id FOR UPDATE",
                        (rs, index) -> new Identity(rs.getLong(1), rs.getBytes(2), rs.getString(3)),
                        memberId);
        for (Identity identity : identities) {
            String email =
                    crypto.decrypt(
                            new String(identity.cipher(), StandardCharsets.UTF_8),
                            "member_identity/" + identity.id() + "/subject_cipher/v1");
            for (String scope :
                    List.of(
                            "SIGNUP_EMAIL_15M",
                            "SIGNUP_EMAIL_24H",
                            "RESET_EMAIL_15M",
                            "RESET_EMAIL_24H",
                            "LOGIN_EMAIL_15M"))
                db.update(
                        "DELETE FROM public.member_auth_limit WHERE scope=? AND bucket_hash=?",
                        scope,
                        hex(crypto.limitHash(scope, email)));
            db.update(
                    "DELETE FROM public.member_flow WHERE member_id IS NULL AND lookup_hash=?",
                    identity.lookup());
        }
    }

    private record Identity(long id, byte[] cipher, String lookup) {}

    /** 영수증의 정확한 해시와 DB 절대 기한을 검사하고 외부/백업 실제 대상만 상태로 투영한다. */
    public Status status(UUID eraseKey, String receiptToken, UUID requestId, String source) {
        if (eraseKey == null
                || source == null
                || source.length() > 45
                || !source.matches("[0-9A-Fa-f:.]+"))
            throw AuthException.badRequest("INVALID_REQUEST");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        Boolean allowed =
                limiter.execute(
                        tx -> {
                            db.execute("SET LOCAL lock_timeout='5s'");
                            String bucket =
                                    hex(crypto.limitHash("ERASE_STATUS_SOURCE_15M", source));
                            db.update(
                                    "INSERT INTO"
                                        + " public.member_auth_limit(scope,bucket_hash,window_at,hit_count,purge_at)"
                                        + " VALUES"
                                        + " ('ERASE_STATUS_SOURCE_15M',?,clock_timestamp(),0,clock_timestamp()+interval"
                                        + " '15 minutes') ON CONFLICT DO NOTHING",
                                    bucket);
                            var limit =
                                    db.queryForMap(
                                            "SELECT hit_count,purge_at FROM"
                                                    + " public.member_auth_limit WHERE"
                                                    + " scope='ERASE_STATUS_SOURCE_15M' AND"
                                                    + " bucket_hash=? FOR UPDATE",
                                            bucket);
                            Instant now =
                                    db.queryForObject("SELECT clock_timestamp()", Timestamp.class)
                                            .toInstant();
                            Instant purge = ((Timestamp) limit.get("purge_at")).toInstant();
                            int hits =
                                    now.isBefore(purge)
                                            ? ((Number) limit.get("hit_count")).intValue()
                                            : 0;
                            db.update(
                                    "UPDATE public.member_auth_limit SET window_at=CASE WHEN"
                                        + " purge_at<=? THEN ? ELSE window_at"
                                        + " END,hit_count=?,purge_at=CASE WHEN purge_at<=? THEN ?"
                                        + " ELSE purge_at END WHERE scope='ERASE_STATUS_SOURCE_15M'"
                                        + " AND bucket_hash=?",
                                    Timestamp.from(now),
                                    Timestamp.from(now),
                                    hits + 1,
                                    Timestamp.from(now),
                                    Timestamp.from(now.plusSeconds(900)),
                                    bucket);
                            return hits < 30;
                        });
        if (!Boolean.TRUE.equals(allowed))
            throw new AuthException(429, "RATE_LIMITED", "RATE_LIMITED");
        return transaction.execute(
                tx -> {
                    Instant now =
                            db.queryForObject("SELECT clock_timestamp()", Timestamp.class)
                                    .toInstant();
                    if (receiptToken == null || !receiptToken.matches("[A-Za-z0-9_-]{43}"))
                        throw AuthException.unauthorized("RECEIPT_UNAVAILABLE");
                    String hash = hex(crypto.tokenHash("MEMBER_ERASURE_RECEIPT_V1", receiptToken));
                    var roots =
                            db.query(
                                    "SELECT"
                                        + " id,state,receipt_hash,receipt_until,primary_done_at,external_done_at,completed_at"
                                        + " FROM public.privacy_erase WHERE erase_key=? AND"
                                        + " sealed_at IS NOT NULL",
                                    (rs, index) ->
                                            new Receipt(
                                                    rs.getLong(1),
                                                    rs.getString(2),
                                                    rs.getString(3),
                                                    rs.getTimestamp(4) == null
                                                            ? null
                                                            : rs.getTimestamp(4).toInstant(),
                                                    rs.getTimestamp(5),
                                                    rs.getTimestamp(6),
                                                    rs.getTimestamp(7)),
                                    eraseKey);
                    if (roots.size() != 1
                            || roots.getFirst().hash() == null
                            || roots.getFirst().until() == null
                            || !crypto.constantEquals(
                                    hash.getBytes(StandardCharsets.US_ASCII),
                                    roots.getFirst().hash().getBytes(StandardCharsets.US_ASCII))
                            || !now.isBefore(roots.getFirst().until()))
                        throw AuthException.unauthorized("RECEIPT_UNAVAILABLE");
                    Receipt root = roots.getFirst();
                    Boolean backup =
                            db.queryForObject(
                                    "SELECT count(*)>0 AND bool_and(state='COMPLETE') FROM"
                                            + " public.privacy_target WHERE erase_id=? AND kind IN"
                                            + " ('BACKUP','RESTORE_GUARD')",
                                    Boolean.class,
                                    root.id());
                    return new Status(
                            root.state(),
                            true,
                            root.primary() != null,
                            root.external() != null,
                            Boolean.TRUE.equals(backup),
                            root.completed() == null ? null : root.completed().toInstant(),
                            requestId);
                });
    }

    private record Receipt(
            long id,
            String state,
            String hash,
            Instant until,
            Timestamp primary,
            Timestamp external,
            Timestamp completed) {}

    private byte[] cipher(String raw, String table, long id, String field) {
        return crypto.encrypt(raw, table + "/" + id + "/" + field + "/v1")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("AUTH_UNAVAILABLE");
    }
}
