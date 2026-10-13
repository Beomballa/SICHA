package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.game.service.GamePlayService;
import com.reasoning.common.game.service.GameRecordService;
import com.reasoning.common.game.service.PublicCoopReportService;
import com.reasoning.common.game.service.PublicGameService;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.service.GradeLocalRunner;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 격리 PG에서 실제 합성 LOCAL 탈퇴와 PUBLIC 원천의 물리 파기를 대조한다. */
@SpringBootTest
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
class GameErasureIT extends PublicGameFixture {
    @Autowired GameRecordService records;
    @Autowired PublicGameService games;
    @Autowired GamePlayService play;
    @Autowired PublicCoopReportService coopReports;
    @Autowired org.springframework.context.ApplicationContext context;

    private static final String PASSWORD = "Synthetic-member-password-987!";
    private static final String SOURCE = "198.19.0.47";

    @Override
    protected int fixtureDifficulty() {
        return 2;
    }

    /** 실제 LOCAL 신원에 결속된 탈퇴 목적의 단회 재인증 증명을 발급한다. */
    private String grant(String access) {
        UUID identity =
                db.queryForObject(
                        "SELECT i.identity_key FROM member_identity i JOIN member_session s ON"
                            + " s.identity_id=i.id JOIN member_token t ON t.session_id=s.id WHERE"
                            + " t.token_hash=?",
                        UUID.class,
                        java.util.HexFormat.of()
                                .formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", access)));
        return service.reauthenticate(
                        access, "DELETE_ACCOUNT", identity, PASSWORD, SOURCE, UUID.randomUUID())
                .reauthGrant();
    }

    /** 재인증과 계정 회수·봉인 접수를 실제 서비스 경계에서 확정한다. */
    private com.reasoning.common.member.auth.MemberAuthService.ErasureAccepted accept(
            String access) {
        return service.deleteAccount(access, grant(access), UUID.randomUUID(), UUID.randomUUID());
    }

    /** 각 회수의 PRIMARY 대상만 순차 소비하고 외부/백업 완료를 주장하지 않는다. */
    private void drain(com.reasoning.common.member.auth.MemberAuthService.ErasureAccepted receipt) {
        for (int n = 0;
                n < 30 && service.processPrimary(receipt.eraseKey(), UUID.randomUUID());
                n++) {
            // 각 호출은 봉인된 출처 대상 하나만 소비한다.
        }
        assertThat(service.processPrimary(receipt.eraseKey(), UUID.randomUUID())).isFalse();
        assertThat(
                        service.status(
                                        receipt.eraseKey(),
                                        receipt.receiptToken(),
                                        UUID.randomUUID(),
                                        SOURCE)
                                .externalDone())
                .isFalse();
    }

    /** 해당 회원의 실재 LOCAL 탈퇴 접수 뒤 PRIMARY만 소비한다. */
    private com.reasoning.common.member.auth.MemberAuthService.ErasureAccepted erase(
            String access) {
        var receipt = accept(access);
        drain(receipt);
        return receipt;
    }

    /** 실제 TEST 사본과 합성 FRIENDLY 정책에 결속된 독립 PUBLIC 방을 생성한다. */
    private long room(String mode, String first, String second) throws Exception {
        UUID a = invitations.getMemberIdentity(first, UUID.randomUUID()).memberKey();
        String other =
                second == null
                        ? account("fixture-source-" + UUID.randomUUID() + "@example.invalid")
                                .path("accessToken")
                                .asText()
                        : second;
        UUID b = invitations.getMemberIdentity(other, UUID.randomUUID()).memberKey();
        UUID source = invitation(List.of(a, b));
        var policy = friendlyPolicy();
        var pinned =
                db.queryForMap(
                        "SELECT t.snapshot_id,t.runtime_id,t.runtime_epoch,t.config_hash,v.id AS"
                            + " version_id, v.story_id,v.difficulty,v.limit_sec FROM play_test t"
                            + " JOIN story_version v ON v.id=t.version_id WHERE t.test_key=?",
                        source);
        var frozen =
                com.reasoning.common.story.model.FrozenSnapshotCodec.decode(
                        db.queryForObject(
                                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                                        String.class,
                                        pinned.get("snapshot_id"))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long id =
                db.queryForObject(
                        "INSERT INTO"
                            + " game_room(room_key,purpose,mode,story_id,version_id,snapshot_id,runtime_id,"
                            + "runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,max_attempts,max_hints,created_at,lobby_until)"
                            + " SELECT"
                            + " gen_random_uuid(),'PUBLIC',?,?,?,?,?,?,?,?,?,0,'WAITING',?,?,?,?,c.t,c.t+interval"
                            + " '30 minutes' FROM (SELECT clock_timestamp() t) c RETURNING id",
                        Long.class,
                        mode,
                        pinned.get("story_id"),
                        pinned.get("version_id"),
                        pinned.get("snapshot_id"),
                        pinned.get("runtime_id"),
                        pinned.get("runtime_epoch"),
                        pinned.get("config_hash"),
                        frozen.rubricHash(),
                        policy.id(),
                        pinned.get("difficulty"),
                        pinned.get("limit_sec"),
                        frozen.payload().path("policy").path("attemptLimit").asInt(),
                        frozen.payload().path("policy").path("hintsPerPerson").asInt());
        join(id, first, 1, policy.id());
        if (second != null) join(id, second, 2, policy.id());
        return id;
    }

    /** 실제 신원 FK가 있는 회원 참가 행만 만든다. */
    private void join(long room, String token, int slot, long policy) {
        long member = service.authenticate(token, false).memberId();
        db.update(
                "INSERT INTO game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at)"
                        + " SELECT ?,i.member_id,i.id,?,?,clock_timestamp() FROM member_identity i"
                        + " WHERE i.member_id=? AND i.provider='LOCAL' AND i.active_yn",
                room,
                slot,
                policy,
                member);
    }

    /** 실제 시작 역할을 고정하고 임의 기한 경과를 만들지 않는다. */
    private void start(long room) {
        start(room, false);
    }

    /** 만료 분기는 합성 서버 시각보다 과거인 시작 사실로 실제 정산 소비자를 재현한다. */
    private void start(long room, boolean expired) {
        db.update(
                "UPDATE game_member SET role_code=CASE slot WHEN 1 THEN 'R1' ELSE 'R2'"
                        + " END,version_id=(SELECT version_id FROM game_room WHERE id=?) WHERE"
                        + " room_id=? AND active_yn",
                room,
                room);
        db.update(
                "WITH c AS (SELECT clock_timestamp()-CASE WHEN ? THEN interval '1 hour'"
                        + " ELSE interval '0 seconds' END t) UPDATE game_room SET state='RUNNING',"
                        + "started_at=c.t,deadline_at=c.t+limit_sec*interval '1 second',"
                        + "role_a='R1',role_b='R2',rev=1 FROM c WHERE id=?",
                expired,
                room);
    }

    /** 본인 SOLO는 요약 없이 물리 삭제하고 외부 완료로 오인하지 않는다. */
    @Test
    void soloOwnerPhysicallyRemoved() throws Exception {
        playtestPolicy();
        String owner =
                account("solo-erasure-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long member = service.authenticate(owner, false).memberId();
        long room = room("SOLO", owner, null);
        start(room);
        var accepted = erase(owner);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_room WHERE id=?", Integer.class, room))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE member_id=?",
                                Integer.class,
                                member))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT primary_done_at IS NOT NULL AND completed_at IS NULL"
                                        + " FROM privacy_erase WHERE erase_key=?",
                                Boolean.class,
                                accepted.eraseKey()))
                .isTrue();
    }

    /** 공급자 참조가 아직 NULL인 실제 GAME 작업·시도 FK도 암호화 대상에 담긴 채 물리 분리된다. */
    @Test
    void nullProviderAttemptHasSealedLocator() throws Exception {
        playtestPolicy();
        String owner =
                account("attempt-erasure-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long member = service.authenticate(owner, false).memberId();
        long room = room("SOLO", owner, null);
        start(room);
        long report =
                db.queryForObject(
                        "INSERT INTO"
                            + " game_report(report_key,room_id,snapshot_id,source_rev,proposer_id,"
                            + "accepted_by,state,payload_cipher,payload_hash,accepted_at,grade_deadline_at,ordinal)"
                            + " SELECT"
                            + " gen_random_uuid(),id,snapshot_id,0,?,?,'ACCEPTED',decode(repeat('03',32),'hex'),repeat('b',64),c.t,c.t+interval"
                            + " '120 seconds',1 FROM game_room,(SELECT clock_timestamp() t) c WHERE"
                            + " id=? RETURNING game_report.id",
                        Long.class,
                        member,
                        member,
                        room);
        long job =
                db.queryForObject(
                        "INSERT INTO"
                            + " grade_job(job_key,snapshot_id,runtime_id,game_report_id,report_hash,state,accepted_at,deadline_at,config_hash,rubric_hash)"
                            + " SELECT gen_random_uuid(),"
                            + "r.snapshot_id,g.runtime_id,r.id,r.payload_hash,'QUEUED',r.accepted_at,r.grade_deadline_at,g.config_hash,g.rubric_hash"
                            + " FROM game_report r JOIN game_room g ON g.id=r.room_id WHERE r.id=?"
                            + " RETURNING id",
                        Long.class,
                        report);
        String worker = "ERASURE_" + UUID.randomUUID();
        db.update(
                "UPDATE grade_job SET state='RUNNING',call_count=1,lease_gen=1,worker_key=?,"
                        + "lease_until=clock_timestamp()+interval '60 seconds' WHERE id=?",
                worker,
                job);
        db.update(
                "INSERT INTO"
                    + " grade_attempt(job_id,attempt_no,lease_gen,worker_key,state,provider_ref)"
                    + " VALUES (?,1,1,?,'RUNNING',NULL)",
                job,
                worker);
        var accepted = erase(owner);
        var sealed =
                db.queryForMap(
                        "SELECT id,locator_cipher,source_job_id,source_attempt_no FROM"
                            + " privacy_target WHERE erase_id=(SELECT id FROM privacy_erase WHERE"
                            + " erase_key=?) AND kind='PROCESSOR'",
                        accepted.eraseKey());
        long locatorId = ((Number) sealed.get("id")).longValue();
        String clear =
                crypto.decrypt(
                        new String((byte[]) sealed.get("locator_cipher"), StandardCharsets.UTF_8),
                        "privacy_target/" + locatorId + "/locator_cipher/v1");
        var value = JSON.readTree(clear);
        assertThat(value.path("jobId").asLong()).isEqualTo(job);
        assertThat(value.path("providerRef").isNull()).isTrue();
        assertThat(value.path("workerKey").asText()).isEqualTo(worker);
        assertThat(sealed.get("source_job_id")).isNull();
        assertThat(sealed.get("source_attempt_no")).isNull();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=?",
                                Integer.class,
                                job))
                .isZero();
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO grade_attempt(job_id,attempt_no,lease_gen,"
                                                + "worker_key,state) VALUES (?,2,1,?,'RUNNING')",
                                        job,
                                        worker))
                .isInstanceOf(RuntimeException.class);
    }

    /** 한 명의 고정 참가자가 탈퇴하면 살아 있는 회원에게 방 연결 없는 한 개의 새 요약만 남긴다. */
    @Test
    void coopSurvivorHasOnlyMinimalSummary() throws Exception {
        playtestPolicy();
        String first =
                account("coop-erasure-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("coop-erasure-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long survivor = service.authenticate(second, false).memberId();
        long room = room("COOP", first, second);
        start(room);
        new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()))
                .execute(
                        tx -> {
                            db.update(
                                    "WITH c AS (SELECT clock_timestamp() t) UPDATE game_room SET"
                                        + " state='ENDED',end_reason='FORFEIT',ended_at=c.t,time_basis_at=c.t"
                                        + " FROM c WHERE id=?",
                                    room);
                            GameRecordService.snapshotTerminal(db, room);
                            return null;
                        });
        UUID oldKey =
                db.queryForObject(
                        "SELECT record_key FROM play_record WHERE room_id=? AND member_id=?",
                        UUID.class,
                        room,
                        survivor);
        var firstErasure = erase(first);
        var stored = db.queryForMap("SELECT * FROM play_record WHERE member_id=?", survivor);
        assertThat(stored.get("state")).isEqualTo("PURGED");
        assertThat(stored.get("room_id")).isNull();
        assertThat(stored.get("created_at")).isNull();
        assertThat(stored.get("started_at")).isNull();
        assertThat(stored.get("mode")).isNull();
        assertThat(stored.get("record_key")).isNotEqualTo(oldKey);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_room WHERE id=?", Integer.class, room))
                .isZero();
        assertThatThrownBy(() -> records.getRecordDetail(second, oldKey, UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        var detail =
                records.getRecordDetail(second, (UUID) stored.get("record_key"), UUID.randomUUID());
        assertThat(detail.path("rawState").asText()).isEqualTo("PURGED");
        assertThat(detail.has("gameKey")).isFalse();
        assertThat(service.processPrimary(firstErasure.eraseKey(), UUID.randomUUID())).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT record_key FROM play_record WHERE member_id=?",
                                UUID.class,
                                survivor))
                .isEqualTo(stored.get("record_key"));
        erase(second);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE member_id=?",
                                Integer.class,
                                survivor))
                .isZero();
    }

    /** 두 계정 모두 먼저 삭제 상태가 되면 하나의 실제 방 영수증만 물리 파기하고 요약은 없다. */
    @Test
    void bothDeletedShareCanonicalRoomWithoutSummary() throws Exception {
        playtestPolicy();
        String first =
                account("both-erasure-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("both-erasure-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long firstId = service.authenticate(first, false).memberId();
        long secondId = service.authenticate(second, false).memberId();
        long room = room("COOP", first, second);
        start(room);
        new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()))
                .execute(
                        tx -> {
                            db.update(
                                    "WITH c AS (SELECT clock_timestamp() t) UPDATE game_room SET"
                                        + " state='ENDED',end_reason='FORFEIT',ended_at=c.t,time_basis_at=c.t"
                                        + " FROM c WHERE id=?",
                                    room);
                            GameRecordService.snapshotTerminal(db, room);
                            return null;
                        });
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=? AND state='RAW'",
                                Integer.class,
                                room))
                .isEqualTo(2);
        var firstReceipt = accept(first);
        var secondReceipt = accept(second);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM privacy_target WHERE source_kind='ROOM'"
                                        + " AND room_id=? AND dependency_id IS NULL",
                                Integer.class,
                                room))
                .isEqualTo(1);
        drain(firstReceipt);
        drain(secondReceipt);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_room WHERE id=?", Integer.class, room))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE member_id IN (?,?)",
                                Integer.class,
                                firstId,
                                secondId))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM privacy_target WHERE source_kind='ROOM' AND"
                                    + " erase_id IN (SELECT id FROM privacy_erase WHERE erase_key"
                                    + " IN (?,?)) AND state<>'COMPLETE'",
                                Integer.class,
                                firstReceipt.eraseKey(),
                                secondReceipt.eraseKey()))
                .isZero();
    }

    /** 마감 전 실제 탈퇴를 뒤늦게 소비해도 청소 시계로 TIME_LIMIT를 만들지 않는다. */
    @Test
    void delayedPhysicalConsumeKeepsDeletionOutcome() throws Exception {
        playtestPolicy();
        String first =
                account("deadline-before-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("deadline-before-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long survivor = service.authenticate(second, false).memberId();
        long room = room("COOP", first, second);
        db.update("UPDATE game_room SET limit_sec=3 WHERE id=? AND state='WAITING'", room);
        start(room);
        var accepted = accept(first);
        assertThat(
                        db.queryForObject(
                                "SELECT a.deleted_at<r.deadline_at FROM member_account a JOIN"
                                        + " game_member m ON m.member_id=a.id JOIN game_room r ON"
                                        + " r.id=m.room_id WHERE r.id=? AND a.state='DELETED'",
                                Boolean.class,
                                room))
                .isTrue();
        try (var wait = Executors.newSingleThreadScheduledExecutor()) {
            wait.schedule(() -> {}, 3500, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT deadline_at<clock_timestamp() FROM game_room WHERE id=?",
                                Boolean.class,
                                room))
                .isTrue();
        play.settleExpired(100);
        assertThat(db.queryForObject("SELECT state FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("RUNNING");
        drain(accepted);
        assertThat(
                        db.queryForObject(
                                "SELECT end_reason FROM play_record WHERE member_id=?",
                                String.class,
                                survivor))
                .isEqualTo("ACCOUNT_DELETED");
        assertThat(
                        db.queryForObject(
                                "SELECT final_score FROM play_record WHERE member_id=?",
                                Integer.class,
                                survivor))
                .isNull();
    }

    /** 원래 마감이 탈퇴보다 앞서고 정상 제출이 없으면 TIME_LIMIT와 0점을 남긴다. */
    @Test
    void deadlineBeforeDeletionProducesTimeLimit() throws Exception {
        playtestPolicy();
        String first =
                account("deadline-after-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("deadline-after-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long survivor = service.authenticate(second, false).memberId();
        long room = room("COOP", first, second);
        start(room, true);
        var accepted = accept(first);
        assertThat(
                        db.queryForObject(
                                "SELECT a.deleted_at>=r.deadline_at FROM member_account a JOIN"
                                        + " game_member m ON m.member_id=a.id JOIN game_room r ON"
                                        + " r.id=m.room_id WHERE r.id=? AND a.state='DELETED'",
                                Boolean.class,
                                room))
                .isTrue();
        play.settleExpired(100);
        assertThat(db.queryForObject("SELECT state FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("RUNNING");
        drain(accepted);
        assertThat(
                        db.queryForObject(
                                "SELECT end_reason FROM play_record WHERE member_id=?",
                                String.class,
                                survivor))
                .isEqualTo("TIME_LIMIT");
        assertThat(
                        db.queryForObject(
                                "SELECT final_score FROM play_record WHERE member_id=?",
                                Integer.class,
                                survivor))
                .isZero();
    }

    /** 이전 정상 GRADED 저장값은 시간초과 점수를 0으로 만들지 않고 고정 감점을 적용한다. */
    @Test
    void timeoutRetainsPreviousNormalScore() throws Exception {
        playtestPolicy();
        String first =
                account("graded-timeout-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("graded-timeout-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long firstId = service.authenticate(first, false).memberId();
        long survivor = service.authenticate(second, false).memberId();
        long room = room("COOP", first, second);
        start(room, true);
        int penalty =
                db.queryForObject(
                        "SELECT (s.payload->'policy'->>'wrongPenalty')::integer FROM"
                                + " review_snapshot s JOIN game_room r ON r.snapshot_id=s.id WHERE"
                                + " r.id=?",
                        Integer.class,
                        room);
        db.update(
                "INSERT INTO game_report(report_key,room_id,snapshot_id,source_rev,proposer_id,"
                    + "accepted_by,state,payload_cipher,payload_hash,accepted_at,grade_deadline_at,ordinal,base_score,success_yn,score_data,graded_at)"
                    + " SELECT gen_random_uuid(),id,snapshot_id,0,?,?,'GRADED',"
                    + "decode(repeat('03',32),'hex'),repeat('b',64),started_at,started_at+interval"
                    + " '120 seconds',1,80,false,'{}'::jsonb,started_at FROM game_room WHERE id=?",
                firstId,
                survivor,
                room);
        db.update(
                "UPDATE game_room SET graded_count=1,wrong_count=1,last_base_score=80 WHERE id=?",
                room);
        var accepted = accept(first);
        drain(accepted);
        assertThat(
                        db.queryForObject(
                                "SELECT end_reason FROM play_record WHERE member_id=?",
                                String.class,
                                survivor))
                .isEqualTo("TIME_LIMIT");
        assertThat(
                        db.queryForObject(
                                "SELECT final_score FROM play_record WHERE member_id=?",
                                Integer.class,
                                survivor))
                .isEqualTo(Math.max(0, 80 - penalty));
    }

    /** 실재 두 세션의 공동 동의와 판정 worker에서 나온 정상 오답은 탈퇴 시계 순서대로 요약한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workerWrongAnswerThenExpiryAndErasure(boolean deleteBeforeDeadline) throws Exception {
        playtestPolicy();
        String first =
                account("worker-erase-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("worker-erase-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long survivor = service.authenticate(second, false).memberId();
        long room = room("COOP", first, second);
        db.update("UPDATE game_room SET limit_sec=20 WHERE id=? AND state='WAITING'", room);
        start(room);
        UUID gameKey =
                db.queryForObject("SELECT room_key FROM game_room WHERE id=?", UUID.class, room);
        UUID testKey =
                db.queryForObject(
                        "SELECT t.test_key FROM play_test t JOIN game_room r ON"
                            + " r.snapshot_id=t.snapshot_id WHERE r.id=? ORDER BY t.id DESC LIMIT"
                            + " 1",
                        UUID.class,
                        room);
        String wrong =
                db.queryForObject(
                        "SELECT p.code FROM story_person p JOIN game_room r ON"
                            + " r.version_id=p.version_id JOIN story_version v ON v.id=r.version_id"
                            + " WHERE r.id=? AND p.code<>v.culprit_code ORDER BY p.code LIMIT 1",
                        String.class,
                        room);
        assertThat(wrong).isNotBlank();
        var answer = (com.fasterxml.jackson.databind.node.ObjectNode) supportedReport(testKey);
        answer.put("culpritCode", wrong);
        play.heartbeat(first, gameKey, UUID.randomUUID());
        play.heartbeat(second, gameKey, UUID.randomUUID());
        assertThat(
                        coopReports
                                .editReport(
                                        first,
                                        gameKey,
                                        1,
                                        0,
                                        answer,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isTrue();
        var proposed =
                coopReports.propose(first, gameKey, 2, 1, UUID.randomUUID(), UUID.randomUUID());
        UUID reportKey =
                UUID.fromString(
                        ((com.fasterxml.jackson.databind.JsonNode) proposed.original())
                                .path("reportKey")
                                .asText());
        assertThat(
                        coopReports
                                .respond(
                                        second,
                                        gameKey,
                                        reportKey,
                                        3,
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .changed())
                .isTrue();
        UUID jobKey =
                db.queryForObject(
                        "SELECT j.job_key FROM grade_job j JOIN game_report gr"
                                + " ON gr.id=j.game_report_id WHERE gr.room_id=?",
                        UUID.class,
                        room);
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var lease =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()))
                        .claimGame(worker.workerKey(), "H5_SYNTHETIC")
                        .orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(jobKey);
        Path root =
                Files.createTempDirectory(
                                "game-erase-worker-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        try {
            var workerAssembly =
                    com.reasoning.common.grading.controller.GradeWorkerController.Assembly.forGames(
                            credentials,
                            db,
                            new DataSourceTransactionManager(db.getDataSource()),
                            Map.of("H5_SYNTHETIC", syntheticRuntime),
                            context.getBean("playtestPolicyGate", PlaytestPolicyGate.class),
                            context.getBean("friendlyPolicyGate", PlaytestPolicyGate.class),
                            context.getBean(
                                    com.reasoning.common.member.auth.MemberPolicyGate.class),
                            crypto,
                            "H5_SYNTHETIC_COORDINATOR");
            var receipt =
                    new GradeLocalRunner(
                                    credentials,
                                    workerAssembly.start(),
                                    workerAssembly.completion(),
                                    profiles(root))
                            .runGameOnce(worker, jobKey, lease.leaseGen());
            assertThat(receipt.receipt().accepted()).isTrue();
        } finally {
            Files.deleteIfExists(root.resolve("profiles.json"));
            Files.deleteIfExists(root);
        }
        var graded =
                db.queryForMap(
                        "SELECT gr.state,gr.base_score,gr.success_yn,r.state AS room_state FROM"
                                + " game_report gr JOIN game_room r ON r.id=gr.room_id WHERE"
                                + " gr.room_id=?",
                        room);
        assertThat(graded.get("state")).isEqualTo("GRADED");
        assertThat(graded.get("success_yn")).isEqualTo(false);
        assertThat(((Number) graded.get("base_score")).intValue()).isPositive();
        assertThat(graded.get("room_state")).isEqualTo("RUNNING");
        int penalty =
                db.queryForObject(
                        "SELECT (s.payload->'policy'->>'wrongPenalty')::integer FROM"
                                + " review_snapshot s JOIN game_room r ON r.snapshot_id=s.id WHERE"
                                + " r.id=?",
                        Integer.class,
                        room);
        var accepted = deleteBeforeDeadline ? accept(first) : null;
        Timestamp deadline =
                db.queryForObject(
                        "SELECT deadline_at FROM game_room WHERE id=?", Timestamp.class, room);
        long delay =
                Math.max(1, Duration.between(Instant.now(), deadline.toInstant()).toMillis() + 200);
        try (var wait = Executors.newSingleThreadScheduledExecutor()) {
            wait.schedule(() -> {}, delay, TimeUnit.MILLISECONDS).get(30, TimeUnit.SECONDS);
        }
        if (!deleteBeforeDeadline) accepted = accept(first);
        var clocks =
                db.queryForMap(
                        "SELECT r.started_at,r.deadline_at,a.deleted_at FROM game_room r"
                                + " JOIN game_member m ON m.room_id=r.id"
                                + " JOIN member_account a ON a.id=m.member_id"
                                + " WHERE r.id=? AND m.role_code IS NOT NULL AND a.state='DELETED'",
                        room);
        long expectedSeconds =
                Duration.between(
                                ((Timestamp) clocks.get("started_at")).toInstant(),
                                ((Timestamp)
                                                clocks.get(
                                                        deleteBeforeDeadline
                                                                ? "deleted_at"
                                                                : "deadline_at"))
                                        .toInstant())
                        .getSeconds();
        play.settleExpired(100);
        assertThat(db.queryForObject("SELECT state FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("RUNNING");
        drain(accepted);
        assertThat(
                        db.queryForObject(
                                "SELECT duration_seconds FROM play_record WHERE member_id=?",
                                Long.class,
                                survivor))
                .isBetween(expectedSeconds, expectedSeconds + 1);
        assertThat(
                        db.queryForObject(
                                "SELECT end_reason FROM play_record WHERE member_id=?",
                                String.class,
                                survivor))
                .isEqualTo(deleteBeforeDeadline ? "ACCOUNT_DELETED" : "TIME_LIMIT");
        Integer score =
                db.queryForObject(
                        "SELECT final_score FROM play_record WHERE member_id=?",
                        Integer.class,
                        survivor);
        if (deleteBeforeDeadline) assertThat(score).isNull();
        else
            assertThat(score)
                    .isEqualTo(
                            Math.max(0, ((Number) graded.get("base_score")).intValue() - penalty));
    }

    /** 대기 guest만 지우면 host의 방은 보존되고 새 팀의 기록 권한을 막지 않는다. */
    @Test
    void waitingGuestReleasesOnlyOwnMembership() throws Exception {
        playtestPolicy();
        String host =
                account("waiting-host-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String guest =
                account("waiting-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long hostId = service.authenticate(host, false).memberId();
        long guestId = service.authenticate(guest, false).memberId();
        long room = room("COOP", host, guest);
        erase(guest);
        assertThat(db.queryForObject("SELECT state FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("WAITING");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_member WHERE room_id=? AND member_id=?",
                                Integer.class,
                                room,
                                guestId))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_member WHERE room_id=? AND member_id=?",
                                Integer.class,
                                room,
                                hostId))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?",
                                Integer.class,
                                room))
                .isZero();
    }

    /** 대기 guest 접수와 소비는 host의 계정 잠금을 먼저 획득해야 방/준비 행에 접근한다. */
    @Test
    void waitingGuestWaitsForHostAccountBeforeChangingRoom() throws Exception {
        playtestPolicy();
        String host =
                account("waiting-lock-host-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String guest =
                account("waiting-lock-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long hostId = service.authenticate(host, false).memberId();
        long room = room("COOP", host, guest);
        String proof = grant(guest);
        try (Connection lock = db.getDataSource().getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try (var statement =
                    lock.prepareStatement("SELECT id FROM member_account WHERE id=? FOR UPDATE")) {
                statement.setLong(1, hostId);
                statement.executeQuery().close();
            }
            var pending =
                    pool.submit(
                            () ->
                                    service.deleteAccount(
                                            guest, proof, UUID.randomUUID(), UUID.randomUUID()));
            assertThatThrownBy(() -> pending.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(
                            db.queryForObject(
                                    "SELECT state FROM game_room WHERE id=?", String.class, room))
                    .isEqualTo("WAITING");
            lock.commit();
            var accepted = pending.get(10, TimeUnit.SECONDS);
            try (var statement =
                    lock.prepareStatement("SELECT id FROM member_account WHERE id=? FOR UPDATE")) {
                statement.setLong(1, hostId);
                statement.executeQuery().close();
            }
            var consuming =
                    pool.submit(
                            () -> service.processPrimary(accepted.eraseKey(), UUID.randomUUID()));
            assertThatThrownBy(() -> consuming.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            lock.commit();
            assertThat(consuming.get(10, TimeUnit.SECONDS)).isTrue();
            drain(accepted);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM game_member WHERE room_id=?",
                                    Integer.class,
                                    room))
                    .isEqualTo(1);
        }
    }

    /** 이전 role-null guest 삭제는 후속 실제 고정 두 회원의 정상 원천/기록을 억제하지 않는다. */
    @Test
    void departedGuestDoesNotVetoLaterFixedTeam() throws Exception {
        playtestPolicy();
        String host =
                account("departed-host-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String previous =
                account("departed-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String next =
                account("departed-next-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long previousId = service.authenticate(previous, false).memberId();
        long hostId = service.authenticate(host, false).memberId();
        long nextId = service.authenticate(next, false).memberId();
        long room = room("COOP", host, previous);
        db.update(
                "UPDATE game_member SET active_yn=false,ready_yn=false WHERE room_id=? AND"
                        + " member_id=?",
                room,
                previousId);
        long policy =
                db.queryForObject("SELECT policy_id FROM game_room WHERE id=?", Long.class, room);
        join(room, next, 2, policy);
        start(room, true);
        var accepted = accept(previous);
        UUID key = db.queryForObject("SELECT room_key FROM game_room WHERE id=?", UUID.class, room);
        games.game(host, key, UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT end_reason FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("TIME_LIMIT");
        drain(accepted);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_room WHERE id=?", Integer.class, room))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?"
                                        + " AND member_id IN (?,?) AND state='RAW'",
                                Integer.class,
                                room,
                                hostId,
                                nextId))
                .isEqualTo(2);
    }

    /** 옛 guest의 접수 미커밋 동안 ROOM 소비는 그 계정 잠금에서 기다려 고아 target을 만들지 않는다. */
    @Test
    void canonicalRoomWaitsForDepartedGuestIntake() throws Exception {
        playtestPolicy();
        String host =
                account("race-host-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String former =
                account("race-former-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String next =
                account("race-next-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long formerId = service.authenticate(former, false).memberId();
        long room = room("COOP", host, former);
        db.update(
                "UPDATE game_member SET active_yn=false WHERE room_id=? AND member_id=?",
                room,
                formerId);
        join(
                room,
                next,
                2,
                db.queryForObject("SELECT policy_id FROM game_room WHERE id=?", Long.class, room));
        start(room);
        var hostReceipt = accept(host);
        while (db.queryForObject(
                        "SELECT count(*) FROM privacy_target WHERE erase_id=(SELECT id FROM"
                                + " privacy_erase WHERE erase_key=?) AND source_kind='TEST' AND"
                                + " state<>'COMPLETE'",
                        Integer.class,
                        hostReceipt.eraseKey())
                > 0)
            assertThat(service.processPrimary(hostReceipt.eraseKey(), UUID.randomUUID())).isTrue();
        String proof = grant(former);
        db.execute(
                "CREATE FUNCTION public.pause_guest_erasure_probe() RETURNS trigger LANGUAGE"
                        + " plpgsql AS $$ BEGIN IF NEW.member_id="
                        + formerId
                        + " THEN PERFORM pg_sleep(1); END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER pause_guest_erasure_probe BEFORE INSERT ON public.privacy_erase"
                        + " FOR EACH ROW EXECUTE FUNCTION public.pause_guest_erasure_probe()");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var guest =
                    pool.submit(
                            () ->
                                    service.deleteAccount(
                                            former, proof, UUID.randomUUID(), UUID.randomUUID()));
            try (var wait = Executors.newSingleThreadScheduledExecutor()) {
                wait.schedule(() -> {}, 250, TimeUnit.MILLISECONDS).get(1, TimeUnit.SECONDS);
            }
            var canonical =
                    pool.submit(
                            () ->
                                    service.processPrimary(
                                            hostReceipt.eraseKey(), UUID.randomUUID()));
            assertThatThrownBy(() -> canonical.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            var formerReceipt = guest.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> canonical.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
            drain(formerReceipt);
            drain(hostReceipt);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM privacy_target WHERE"
                                        + " source_kind='MEMBERSHIP' AND erase_id=(SELECT id FROM"
                                        + " privacy_erase WHERE erase_key=?) AND state<>'COMPLETE'",
                                    Integer.class,
                                    formerReceipt.eraseKey()))
                    .isZero();
        } finally {
            db.execute("DROP TRIGGER pause_guest_erasure_probe ON public.privacy_erase");
            db.execute("DROP FUNCTION public.pause_guest_erasure_probe()");
        }
    }

    /** 대기 host 탈퇴는 방을 취소하지만 살아 있는 guest 계정을 삭제하지 않는다. */
    @Test
    void waitingHostCancelsLobby() throws Exception {
        playtestPolicy();
        String host =
                account("cancel-host-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String guest =
                account("cancel-guest-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long guestId = service.authenticate(guest, false).memberId();
        long room = room("COOP", host, guest);
        erase(host);
        assertThat(db.queryForObject("SELECT state FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("CANCELLED");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM member_account WHERE id=?",
                                String.class,
                                guestId))
                .isEqualTo("ACTIVE");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?",
                                Integer.class,
                                room))
                .isZero();
    }

    /** 출처 중간 삭제 실패는 요약 회전과 영수증 COMPLETE를 함께 롤백하며 재시도는 단회다. */
    @Test
    void failedPhysicalDeleteRollsBackSummary() throws Exception {
        playtestPolicy();
        String first =
                account("rollback-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("rollback-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long survivor = service.authenticate(second, false).memberId();
        long room = room("COOP", first, second);
        start(room);
        var accepted = accept(first);
        db.execute(
                "CREATE FUNCTION public.reject_game_erase_probe() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN RAISE EXCEPTION 'GAME_ERASE_ROLLBACK_PROBE'; END $$");
        db.execute(
                "CREATE TRIGGER reject_game_erase_probe BEFORE DELETE ON public.game_room"
                        + " FOR EACH ROW EXECUTE FUNCTION public.reject_game_erase_probe()");
        try {
            assertThatThrownBy(
                            () -> {
                                for (int n = 0; n < 30; n++)
                                    service.processPrimary(accepted.eraseKey(), UUID.randomUUID());
                            })
                    .isInstanceOf(RuntimeException.class);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM game_room WHERE id=?",
                                    Integer.class,
                                    room))
                    .isEqualTo(1);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM play_record WHERE member_id=?",
                                    Integer.class,
                                    survivor))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM privacy_target WHERE source_kind='ROOM'"
                                            + " AND room_id=? AND state='COMPLETE'",
                                    Integer.class,
                                    room))
                    .isZero();
        } finally {
            db.execute("DROP TRIGGER reject_game_erase_probe ON public.game_room");
            db.execute("DROP FUNCTION public.reject_game_erase_probe()");
        }
        drain(accepted);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE member_id=? AND"
                                        + " state='PURGED'",
                                Integer.class,
                                survivor))
                .isEqualTo(1);
    }

    /** 비소유자 및 임대 없는 직접 삭제는 기존 전용 경계를 우회할 수 없다. */
    @Test
    void directDeletionRejected() throws Exception {
        playtestPolicy();
        String owner =
                account("direct-erasure-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long room = room("SOLO", owner, null);
        assertThatThrownBy(() -> db.update("DELETE FROM game_room WHERE id=?", room))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasStackTraceContaining("game physical deletion unauthorized");
        var accepted = accept(owner);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " privacy_target(erase_id,target_key,kind,source_kind,room_id)"
                                            + " VALUES ((SELECT id FROM privacy_erase WHERE"
                                            + " erase_key=?),gen_random_uuid(),'PRIMARY','ROOM',?)",
                                        accepted.eraseKey(),
                                        room))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                db.queryForObject(
                                        "SELECT consume_privacy_primary(id) FROM privacy_target"
                                            + " WHERE erase_id=(SELECT id FROM privacy_erase WHERE"
                                            + " erase_key=?) AND source_kind='ROOM'",
                                        Boolean.class,
                                        accepted.eraseKey()))
                .isInstanceOf(RuntimeException.class);
        drain(accepted);
    }
}
