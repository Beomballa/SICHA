package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.game.service.GameQualificationService;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

/** 원본 마이그레이션에 실제 GAME 부모를 추가하여 TEST/BATCH와의 출처 분리를 검사한다. */
@SpringBootTest
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
class GameGradeSourceIT extends PlaytestReportFixture {
    @Autowired GameQualificationService qualifications;
    @Autowired com.reasoning.common.grading.repository.GradeSourceRepository sources;
    @Autowired com.reasoning.common.member.auth.PlaytestPolicyGate gamePolicy;
    @Autowired com.reasoning.common.auth.service.CryptoService gameCrypto;
    @Autowired com.reasoning.common.story.service.StoryReleaseEvidenceService releaseEvidence;

    @Test
    void actualAcceptedGameReportPinsJobAndRejectsOtherParents() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .resourceProvider(new com.reasoning.common.migration.EmbeddedSqlResourceProvider())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(
                        db.queryForObject(
                                "SELECT max(version::integer) FROM flyway_schema_history WHERE"
                                        + " success",
                                Integer.class))
                .isEqualTo(43);
        playtestPolicy();
        var selected = account("game-grade-" + UUID.randomUUID() + "@example.invalid");
        String token = selected.path("accessToken").asText();
        UUID memberKey = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        var other = account("game-grade-other-" + UUID.randomUUID() + "@example.invalid");
        UUID otherKey =
                invitations
                        .getMemberIdentity(other.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID invitation = invitation(List.of(memberKey, otherKey));
        long snapshot =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE test_key=?",
                        Long.class,
                        invitation);
        long memberId =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?", Long.class, memberKey);
        String code =
                db.queryForObject(
                        "SELECT code FROM privacy_policy WHERE scope='PLAYTEST' AND state='ACTIVE'",
                        String.class);
        String notice =
                db.queryForObject(
                        "SELECT notice_hash FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'",
                        String.class);
        UUID roomKey =
                qualifications
                        .createQualification(
                                invitationSid,
                                invitationActor,
                                invitationStoryCode,
                                1,
                                2,
                                snapshot,
                                "R1",
                                "R2",
                                "H5_SYNTHETIC",
                                memberKey,
                                "FUNCTIONAL",
                                code,
                                notice,
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .roomKey();
        qualifications.acceptQualification(
                token, roomKey, false, code, notice, UUID.randomUUID(), UUID.randomUUID());
        qualifications.startQualification(token, roomKey, 0, UUID.randomUUID(), UUID.randomUUID());
        long room =
                db.queryForObject("SELECT id FROM game_room WHERE room_key=?", Long.class, roomKey);
        var reportJson = supportedReport(invitation);
        byte[] reportBytes = com.reasoning.common.grading.model.SnapshotJson.encode(reportJson);
        String payloadHash = com.reasoning.common.grading.model.SnapshotJson.hash(reportJson);
        var frozen =
                com.reasoning.common.story.model.FrozenSnapshotCodec.decode(
                        db.queryForObject(
                                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                                        String.class,
                                        snapshot)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var parsed =
                new com.reasoning.common.grading.service.GradeResultValidator()
                        .parseReport(
                                new String(reportBytes, java.nio.charset.StandardCharsets.UTF_8));
        String hash = frozen.reportHash(parsed);
        long reservedReport =
                db.queryForObject(
                        "SELECT nextval(pg_get_serial_sequence('public.game_report','id'))",
                        Long.class);
        String aad =
                "game_report/"
                        + reservedReport
                        + "/room/"
                        + room
                        + "/snapshot/"
                        + snapshot
                        + "/draft/0/payload/v1";
        byte[] encrypted =
                gameCrypto
                        .encrypt(
                                new String(reportBytes, java.nio.charset.StandardCharsets.UTF_8),
                                aad)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long report =
                db.queryForObject(
                        """
                        INSERT INTO game_report(id,report_key,room_id,snapshot_id,source_rev,proposer_id,accepted_by,
                            state,payload_cipher,payload_hash,accepted_at,grade_deadline_at,ordinal)
                        OVERRIDING SYSTEM VALUE
                        SELECT ?,?,r.id,r.snapshot_id,r.draft_rev,?,?, 'ACCEPTED',?,?,
                            clock.instant,clock.instant+interval '120 seconds',1
                        FROM game_room r CROSS JOIN (SELECT clock_timestamp() AS instant) clock
                        WHERE r.id=? RETURNING id
                        """,
                        Long.class,
                        reservedReport,
                        UUID.randomUUID(),
                        memberId,
                        memberId,
                        encrypted,
                        payloadHash,
                        room);
        long job =
                db.queryForObject(
                        """
                        INSERT INTO grade_job(job_key,snapshot_id,runtime_id,game_report_id,report_hash,
                            state,accepted_at,deadline_at,config_hash,rubric_hash)
                        SELECT ?,r.snapshot_id,r.runtime_id,?,?,'QUEUED',g.accepted_at,g.grade_deadline_at,
                            r.config_hash,r.rubric_hash FROM game_room r JOIN game_report g ON g.room_id=r.id
                        WHERE r.id=? AND g.id=? RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        report,
                        hash,
                        room,
                        report);
        assertThat(job).isPositive();
        UUID jobKey =
                db.queryForObject("SELECT job_key FROM grade_job WHERE id=?", UUID.class, job);
        var prepared = sources.prepareQualificationGame(gamePolicy);
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        tx.executeWithoutResult(
                status -> {
                    var locked = sources.lockGameRoots(jobKey, gamePolicy, prepared);
                    assertThat(sources.requireGameExecutionEligibility(locked).jobKey())
                            .isEqualTo(jobKey);
                });
        assertThatThrownBy(
                        () -> db.update("UPDATE grade_job SET game_report_id=NULL WHERE id=?", job))
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () -> db.update("UPDATE grade_job SET report_id=? WHERE id=?", report, job))
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE grade_job SET runtime_id=runtime_id+1 WHERE id=?",
                                        job))
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        """
                                        INSERT INTO grade_job(job_key,snapshot_id,runtime_id,game_report_id,report_hash,
                                            state,accepted_at,deadline_at,config_hash,rubric_hash)
                                        SELECT ?,r.snapshot_id,r.runtime_id,?,?,'QUEUED',g.accepted_at,g.grade_deadline_at,
                                            repeat('b',64),r.rubric_hash FROM game_room r JOIN game_report g ON g.room_id=r.id
                                        WHERE r.id=? AND g.id=?
                                        """,
                                        UUID.randomUUID(),
                                        report,
                                        hash,
                                        room,
                                        report))
                .hasMessageContaining("pin mismatch");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        """
                                        INSERT INTO grade_job(job_key,snapshot_id,runtime_id,game_report_id,report_hash,
                                            state,accepted_at,deadline_at,config_hash,rubric_hash,batch_id)
                                        SELECT ?,r.snapshot_id,r.runtime_id,?,?,'QUEUED',g.accepted_at,g.grade_deadline_at,
                                            r.config_hash,r.rubric_hash,1 FROM game_room r JOIN game_report g ON g.room_id=r.id
                                        WHERE r.id=? AND g.id=?
                                        """,
                                        UUID.randomUUID(),
                                        report,
                                        hash,
                                        room,
                                        report))
                .hasMessageContaining("ck_gj_source");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        """
                                        INSERT INTO grade_job(job_key,snapshot_id,runtime_id,report_hash,
                                            state,accepted_at,deadline_at,config_hash,rubric_hash)
                                        SELECT ?,r.snapshot_id,r.runtime_id,?,'QUEUED',g.accepted_at,g.grade_deadline_at,
                                            r.config_hash,r.rubric_hash FROM game_room r JOIN game_report g ON g.room_id=r.id
                                        WHERE r.id=? AND g.id=?
                                        """,
                                        UUID.randomUUID(),
                                        hash,
                                        room,
                                        report))
                .hasMessageContaining("ck_gj_source");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        """
                                        INSERT INTO grade_job(job_key,snapshot_id,runtime_id,game_report_id,report_hash,
                                            state,accepted_at,deadline_at,config_hash,rubric_hash)
                                        SELECT ?,r.snapshot_id,r.runtime_id,?,?,'QUEUED',g.accepted_at,g.grade_deadline_at,
                                            r.config_hash,r.rubric_hash FROM game_room r JOIN game_report g ON g.room_id=r.id
                                        WHERE r.id=? AND g.id=?
                                        """,
                                        UUID.randomUUID(),
                                        report + 1000000,
                                        hash,
                                        room,
                                        report))
                .hasMessageContaining("grade_job GAME report pin mismatch");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE"
                                        + " conname='fk_gj_game_report' AND contype='f'",
                                Integer.class))
                .isEqualTo(1);
        var worker =
                credentials.authenticate(
                        "Bearer "
                                + java.util.Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(SECRET));
        var leases =
                new com.reasoning.common.grading.repository.GradeLeaseRepository(
                        db,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(db.getDataSource())));
        var lease = leases.claimGame(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(jobKey);
        var started = assembly.start().startApproved(worker, jobKey, lease.leaseGen());
        assertThat(started.disposition()).isEqualTo("NEW");
        assertThat(started.toJson().path("input").path("sourceKind").asText()).isEqualTo("GAME");
        assertThat(started.toJson().path("input").path("reportHash").asText()).isEqualTo(hash);
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE id=?", Integer.class, job))
                .isEqualTo(1);
        assertThat(
                        releaseEvidence
                                .evaluateRelease(
                                        db.queryForObject(
                                                "SELECT version_id FROM game_room WHERE id=?",
                                                Long.class,
                                                room),
                                        snapshot,
                                        db.queryForObject(
                                                "SELECT runtime_id FROM game_room WHERE id=?",
                                                Long.class,
                                                room),
                                        db.queryForObject(
                                                "SELECT runtime_epoch FROM game_room WHERE id=?",
                                                Long.class,
                                                room),
                                        db.queryForObject(
                                                "SELECT config_hash FROM game_room WHERE id=?",
                                                String.class,
                                                room),
                                        Long.MAX_VALUE)
                                .state())
                .isEqualTo(
                        com.reasoning.common.story.service.StoryReleaseEvidenceService.State
                                .MISSING);
    }
}
