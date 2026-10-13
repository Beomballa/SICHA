package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.game.service.GameQualificationService;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.repository.GradeSourceRepository;
import com.reasoning.common.grading.service.GradeLocalRunner;
import com.reasoning.common.grading.service.GradeResultValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.repository.BlindEligibilityReader;
import com.reasoning.common.story.repository.BlindEligibilityReader.Use;
import com.reasoning.common.story.service.BlindEligibilityService;
import com.reasoning.common.story.service.PlaytestReportFixture;
import com.reasoning.common.story.service.StoryReleaseEvidenceService;
import com.reasoning.common.story.service.StoryReleaseService;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 실재 검수 회원·현재 REVIEW 권한에서 원문과 GAME worker 입력을 생성한다. */
@SpringBootTest
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
class GameQualificationReportIT extends PlaytestReportFixture {
    @Autowired GameQualificationService qualifications;
    @Autowired CryptoService gameCrypto;
    @Autowired BlindEligibilityService eligibility;
    @Autowired StoryReleaseEvidenceService releaseEvidence;
    @Autowired StoryReleaseService releases;
    @Autowired GradeSourceRepository sources;
    @Autowired com.reasoning.common.member.auth.PlaytestPolicyGate gamePolicy;

    private record CohortMember(UUID member, String token) {}

    private CohortMember syntheticMember() throws Exception {
        String token =
                account("cohort-synthetic-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        return new CohortMember(
                invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey(), token);
    }

    private long gradedBlindTest(long snapshot, String a, String b) throws Exception {
        CohortMember first = syntheticMember(), second = syntheticMember();
        UUID key =
                additionalBlindInvitation(snapshot, a, b, List.of(first.member(), second.member()));
        for (CohortMember participant : List.of(first, second)) {
            var notice = invitations.getConsentNotice(participant.token(), key, UUID.randomUUID());
            invitations.acceptInvitation(
                    participant.token(),
                    key,
                    1,
                    testRev(key),
                    true,
                    notice.policyCode(),
                    notice.noticeHash(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
        investigation.ready(
                first.token(), key, testRev(key), true, UUID.randomUUID(), UUID.randomUUID());
        investigation.ready(
                second.token(), key, testRev(key), true, UUID.randomUUID(), UUID.randomUUID());
        investigation.heartbeat(first.token(), key, UUID.randomUUID());
        investigation.heartbeat(second.token(), key, UUID.randomUUID());
        investigation.start(first.token(), key, testRev(key), UUID.randomUUID(), UUID.randomUUID());
        investigation.materials(first.token(), key, UUID.randomUUID());
        investigation.materials(second.token(), key, UUID.randomUUID());
        reports.edit(
                first.token(),
                key,
                testRev(key),
                testDraftRev(key),
                supportedReport(key),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                first.token(),
                key,
                testRev(key),
                testDraftRev(key),
                UUID.randomUUID(),
                UUID.randomUUID());
        UUID proposal =
                reports.report(second.token(), key, UUID.randomUUID()).proposal().reportKey();
        investigation.heartbeat(first.token(), key, UUID.randomUUID());
        investigation.heartbeat(second.token(), key, UUID.randomUUID());
        UUID job =
                UUID.fromString(
                        (String)
                                reports.respond(
                                                second.token(),
                                                key,
                                                proposal,
                                                testRev(key),
                                                "ACCEPT",
                                                UUID.randomUUID(),
                                                UUID.randomUUID())
                                        .original()
                                        .get("jobKey"));
        gradeBlindTest(job);
        for (CohortMember participant : List.of(first, second))
            reports.feedback(
                    participant.token(),
                    key,
                    JSON.createObjectNode()
                            .put("blockedAt", "합성 장애 없음")
                            .put("roleContribution", "합성 역할")
                            .put("fairness", "합성 공정성"),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        long id = db.queryForObject("SELECT id FROM play_test WHERE test_key=?", Long.class, key);
        releases.review(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                "TEST",
                id,
                "PASS",
                0,
                "합성 TEST 채점 및 피드백 검토",
                "SyntheticReviewer_01",
                null,
                null,
                UUID.randomUUID());
        return id;
    }

    private long testRev(UUID key) {
        return db.queryForObject("SELECT rev FROM play_test WHERE test_key=?", Long.class, key);
    }

    private long testDraftRev(UUID key) {
        return db.queryForObject(
                "SELECT draft_rev FROM play_test WHERE test_key=?", Long.class, key);
    }

    private long gradedBlindSolo(long snapshot, String a, String b) throws Exception {
        CohortMember selected = syntheticMember();
        blindAssessment(selected.member(), snapshot);
        var notice =
                db.queryForMap(
                        "SELECT code,notice_hash FROM privacy_policy"
                                + " WHERE scope='PLAYTEST' AND state='ACTIVE'");
        UUID room =
                qualifications
                        .createQualification(
                                invitationSid,
                                invitationActor,
                                invitationStoryCode,
                                1,
                                2,
                                snapshot,
                                a,
                                b,
                                "H5_SYNTHETIC",
                                selected.member(),
                                "BLIND",
                                (String) notice.get("code"),
                                (String) notice.get("notice_hash"),
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .roomKey();
        qualifications.acceptQualification(
                selected.token(),
                room,
                true,
                (String) notice.get("code"),
                (String) notice.get("notice_hash"),
                UUID.randomUUID(),
                UUID.randomUUID());
        qualifications.startQualification(
                selected.token(), room, 0, UUID.randomUUID(), UUID.randomUUID());
        qualifications.getMaterials(selected.token(), room, UUID.randomUUID());
        UUID test =
                db.queryForObject(
                        "SELECT test_key FROM play_test WHERE snapshot_id=?"
                                + " ORDER BY id LIMIT 1",
                        UUID.class,
                        snapshot);
        qualifications.editReport(
                selected.token(),
                room,
                1,
                0,
                supportedReport(test),
                UUID.randomUUID(),
                UUID.randomUUID());
        var submitted =
                qualifications.submitReport(
                        selected.token(), room, 2, 1, UUID.randomUUID(), UUID.randomUUID());
        UUID job = UUID.fromString(submitted.original().path("jobKey").asText());
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var lease =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()))
                        .claimGame(worker.workerKey(), "H5_SYNTHETIC")
                        .orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(job);
        Path root =
                Files.createTempDirectory(
                                "cohort-game-worker-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        try {
            var result =
                    new GradeLocalRunner(
                                    credentials,
                                    assembly.start(),
                                    assembly.completion(),
                                    profiles(root))
                            .runGameOnce(worker, job, lease.leaseGen());
            assertThat(result.state()).isEqualTo(GradeLocalRunner.State.RECEIPT);
            assertThat(result.receipt().accepted()).isTrue();
            assertThat(result.receipt().state()).isEqualTo("COMPLETED");
        } finally {
            Files.deleteIfExists(root.resolve("profiles.json"));
            Files.deleteIfExists(root);
        }
        qualifications.submitFeedback(
                selected.token(), room, "합성 검수 피드백", UUID.randomUUID(), UUID.randomUUID());
        long id = db.queryForObject("SELECT id FROM game_room WHERE room_key=?", Long.class, room);
        releases.review(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                "SOLO",
                id,
                "PASS",
                0,
                "합성 SOLO 채점 및 피드백 검토",
                "SyntheticReviewer_01",
                null,
                null,
                UUID.randomUUID());
        return id;
    }

    /** 같은 고정 원고의 두 역할 쌍에 독립 회원 18명의 실제 채점·피드백·검토를 모아 영수증을 재검사한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collectedSyntheticDualSourceReceiptInvalidatesOnlyOnOriginalOrLaterNegativeAssessment(
            boolean laterIneligible) throws Exception {
        playtestPolicy();
        CohortMember seedA = syntheticMember(), seedB = syntheticMember();
        UUID seed = invitation(List.of(seedA.member(), seedB.member()));
        long snapshot =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE test_key=?", Long.class, seed);
        long firstTest = 0;
        for (String[] pair : List.of(new String[] {"R1", "R2"}, new String[] {"R1", "R3"})) {
            for (int n = 0; n < 3; n++) {
                long test = gradedBlindTest(snapshot, pair[0], pair[1]);
                if (firstTest == 0) firstTest = test;
            }
            for (int n = 0; n < 3; n++) gradedBlindSolo(snapshot, pair[0], pair[1]);
        }
        var runtime =
                db.queryForMap(
                        "SELECT version_id,runtime_id,runtime_epoch,config_hash"
                                + " FROM play_test WHERE id=?",
                        firstTest);
        long version = ((Number) runtime.get("version_id")).longValue();
        long runtimeId = ((Number) runtime.get("runtime_id")).longValue();
        long epoch = ((Number) runtime.get("runtime_epoch")).longValue();
        String hash = (String) runtime.get("config_hash");
        var sourceChecks =
                db.queryForList(
                        """
                        SELECT t.role_a,t.role_b,
                          t.state='ENDED' AND t.outcome IN ('SUCCESS','TIME_LIMIT','ATTEMPTS_EXHAUSTED') ended_ok,
                          EXISTS(SELECT 1 FROM test_retention x WHERE x.test_id=t.id AND x.raw_until>now()) retention_ok,
                          EXISTS(SELECT 1 FROM test_report p JOIN grade_job j ON j.report_id=p.id
                            JOIN grade_attempt a ON a.job_id=j.id WHERE p.test_id=t.id AND p.state='GRADED'
                            AND p.payload_cipher IS NOT NULL AND p.purged_at IS NULL AND j.state='COMPLETED'
                            AND j.result_hash IS NOT NULL AND a.state='SUCCEEDED' AND a.completion_data IS NOT NULL
                            AND a.output_hash IS NOT NULL AND EXISTS(SELECT 1 FROM grade_event e WHERE e.job_id=j.id
                              AND e.attempt_no=a.attempt_no AND e.actor_kind='WORKER' AND e.event_kind='COMPLETE_APPLIED')) worker_ok,
                          (SELECT count(*)=2 FROM test_member m WHERE m.test_id=t.id AND m.invite_state='ACCEPTED'
                            AND m.blind_declared AND m.feedback_cipher IS NOT NULL AND m.revoked_at IS NULL) consent_feedback_ok,
                          (SELECT count(*)=2 FROM test_member m JOIN test_exposure e ON e.member_id=m.member_id
                            AND e.story_id=(SELECT story_id FROM story_version WHERE id=t.version_id)
                            WHERE m.test_id=t.id AND e.first_test_id=t.id AND e.first_room_id IS NULL
                            AND e.first_exposed_at>=t.started_at
                            AND EXISTS(SELECT 1 FROM test_audit a JOIN member_account member ON member.member_key::text=a.actor_ref
                              WHERE a.scope_kind='PLAYTEST' AND a.scope_key='test:'||t.test_key::text
                                AND a.action='TEST_MATERIALS' AND a.business_result='EXPOSED'
                                AND member.id=m.member_id AND a.created_at>=e.first_exposed_at)) exposure_audit_ok,
                          EXISTS(SELECT 1 FROM release_human_review r JOIN admin_account a ON a.id=r.reviewer_id
                            JOIN admin_credential c ON c.account_id=a.id JOIN story_access x ON x.admin_id=a.id
                            WHERE r.test_id=t.id AND r.verdict='PASS' AND r.critical_open_count=0
                              AND a.active_yn AND a.can_review AND c.enrolled_at IS NOT NULL AND c.mfa_state='READY'
                              AND x.active_yn AND x.permission='REVIEW' AND x.story_id=(SELECT story_id FROM story_version WHERE id=t.version_id)
                              AND r.created_at>=t.ended_at) reviewer_ok
                        FROM play_test t WHERE t.snapshot_id=? AND t.mode='BLIND'
                        """,
                        snapshot);
        assertThat(sourceChecks).hasSize(6);
        for (var check : sourceChecks) {
            assertThat(check.values()).as("합성 TEST 근거 조건: %s", check).doesNotContain(false, null);
        }
        var beforeReceipt =
                releaseEvidence.evaluateRelease(
                        version, snapshot, runtimeId, epoch, hash, Long.MAX_VALUE);
        assertThat(beforeReceipt.reason())
                .as("실제 출처 충족 뒤 아직 없는 영수증만 거절해야 한다: %s", beforeReceipt)
                .isEqualTo("RECEIPT_MISMATCH");
        var collected =
                releases.collect(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        Long.toString(snapshot),
                        "H5_SYNTHETIC",
                        UUID.randomUUID());
        long receipt = Long.parseLong(collected.original().path("receiptId").asText());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM release_evidence_item"
                                        + " WHERE receipt_id=?",
                                Integer.class,
                                receipt))
                .isEqualTo(12);
        assertThat(
                        releaseEvidence
                                .evaluateRelease(version, snapshot, runtimeId, epoch, hash, receipt)
                                .state())
                .isEqualTo(StoryReleaseEvidenceService.State.VERIFIED);
        var source =
                db.queryForMap(
                        "SELECT a.assessment_key,account.member_key,m.member_id FROM test_member m"
                            + " JOIN blind_assessment a ON a.id=m.assessment_id JOIN member_account"
                            + " account ON account.id=m.member_id WHERE m.test_id=? AND m.slot=1",
                        firstTest);
        if (laterIneligible) {
            // 불변 평가 시계를 조작하지 않고 실제 후속 부적격 평가로 근거를 무효화한다.
            eligibility.create(
                    invitationSid,
                    invitationActor,
                    invitationStoryCode,
                    1,
                    2,
                    snapshot,
                    (UUID) source.get("member_key"),
                    "INELIGIBLE",
                    "PARTICIPATION_FOUND",
                    "synthetic.cohort:later-negative",
                    Instant.now().minusSeconds(5),
                    true,
                    (UUID) source.get("assessment_key"),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        } else {
            eligibility.revoke(
                    invitationSid,
                    invitationActor,
                    invitationStoryCode,
                    1,
                    (UUID) source.get("assessment_key"),
                    2,
                    "PARTICIPATION_FOUND",
                    "synthetic.cohort:revoked",
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
        assertThat(
                        releaseEvidence
                                .evaluateRelease(version, snapshot, runtimeId, epoch, hash, receipt)
                                .state())
                .isNotEqualTo(StoryReleaseEvidenceService.State.VERIFIED);
    }

    private void assertCurrentGame(UUID jobKey) {
        var prepared = sources.prepareQualificationGame(gamePolicy);
        new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()))
                .executeWithoutResult(
                        status -> {
                            var locked = sources.lockGameRoots(jobKey, gamePolicy, prepared);
                            assertThat(sources.requireGameExecutionEligibility(locked).jobKey())
                                    .isEqualTo(jobKey);
                        });
    }

    private UUID assess(UUID member, long snapshot) {
        return eligibility
                .create(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        2,
                        snapshot,
                        member,
                        "ELIGIBLE",
                        "NON_PARTICIPATION_CONFIRMED",
                        "synthetic.qualification:01",
                        Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS),
                        true,
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID())
                .assessmentKey();
    }

    @Test
    void admissionDeclarationAndRevocationUsePinnedAssessment() throws Exception {
        playtestPolicy();
        var selected = account("qualification-assess-" + UUID.randomUUID() + "@example.invalid");
        var companion =
                account("qualification-functional-" + UUID.randomUUID() + "@example.invalid");
        String token = selected.path("accessToken").asText();
        String functionalToken = companion.path("accessToken").asText();
        UUID selectedKey = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID functionalKey =
                invitations.getMemberIdentity(functionalToken, UUID.randomUUID()).memberKey();
        UUID invitation = invitation(List.of(selectedKey, functionalKey));
        long snapshot =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE test_key=?",
                        Long.class,
                        invitation);
        var notice =
                db.queryForMap(
                        "SELECT code,notice_hash FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'");
        String code = (String) notice.get("code");
        String hash = (String) notice.get("notice_hash");
        assertThatThrownBy(
                        () ->
                                qualifications.createQualification(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        2,
                                        snapshot,
                                        "R1",
                                        "R2",
                                        "H5_SYNTHETIC",
                                        selectedKey,
                                        "BLIND",
                                        code,
                                        hash,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        UUID assessment = assess(selectedKey, snapshot);
        long memberId =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?",
                        Long.class,
                        selectedKey);
        long storyId =
                db.queryForObject(
                        "SELECT story_id FROM story_version WHERE id=(SELECT version_id FROM"
                                + " play_test WHERE test_key=?)",
                        Long.class,
                        invitation);
        var reader = new BlindEligibilityReader(db);
        assertThat(reader.currentForAdmission(storyId, snapshot, memberId)).isPositive();
        UUID room =
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
                                selectedKey,
                                "BLIND",
                                code,
                                hash,
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .roomKey();
        var consent = qualifications.getAcceptanceNotice(token, room, UUID.randomUUID());
        assertThat(consent.qualificationKind()).isEqualTo("BLIND");
        assertThatThrownBy(
                        () ->
                                qualifications.acceptQualification(
                                        token,
                                        room,
                                        false,
                                        code,
                                        hash,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        UUID acceptKey = UUID.randomUUID();
        qualifications.acceptQualification(
                token, room, true, code, hash, acceptKey, UUID.randomUUID());
        long roomId =
                db.queryForObject("SELECT id FROM game_room WHERE room_key=?", Long.class, room);
        long pinnedId =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        assessment);
        assertThat(reader.game(storyId, snapshot, memberId, roomId, pinnedId, Use.EXECUTION))
                .isTrue();
        assertThat(reader.game(storyId, snapshot, memberId, roomId, null, Use.EXECUTION)).isFalse();
        assertThat(reader.game(storyId, snapshot, memberId, roomId, pinnedId, Use.EVIDENCE))
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_exposure WHERE story_id=? AND"
                                        + " member_id=?",
                                Integer.class,
                                storyId,
                                memberId))
                .isZero();
        assertThat(
                        qualifications
                                .acceptQualification(
                                        token, room, true, code, hash, acceptKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                qualifications.acceptQualification(
                                        token,
                                        room,
                                        false,
                                        code,
                                        hash,
                                        acceptKey,
                                        UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        qualifications.startQualification(token, room, 0, UUID.randomUUID(), UUID.randomUUID());
        qualifications.getMaterials(token, room, UUID.randomUUID());
        assertThat(reader.game(storyId, snapshot, memberId, roomId, pinnedId, Use.EVIDENCE))
                .isTrue();
        var firstAt =
                db.queryForObject(
                        "SELECT e.first_exposed_at FROM test_exposure e JOIN game_room r ON"
                                + " r.id=e.first_room_id WHERE r.room_key=?",
                        java.sql.Timestamp.class,
                        room);
        qualifications.getMaterials(token, room, UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT e.first_exposed_at FROM test_exposure e JOIN game_room r ON"
                                        + " r.id=e.first_room_id WHERE r.room_key=?",
                                java.sql.Timestamp.class,
                                room))
                .isEqualTo(firstAt);
        assertThat(
                        qualifications
                                .acceptQualification(
                                        token, room, true, code, hash, acceptKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        eligibility.revoke(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                assessment,
                2,
                "PARTICIPATION_FOUND",
                "synthetic.qualification:revoked",
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(reader.game(storyId, snapshot, memberId, roomId, pinnedId, Use.EVIDENCE))
                .isFalse();
        assertThatThrownBy(() -> qualifications.getMaterials(token, room, UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        assertThatThrownBy(() -> qualifications.getReport(token, room, UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        UUID functional =
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
                                functionalKey,
                                "FUNCTIONAL",
                                code,
                                hash,
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .roomKey();
        assertThat(
                        qualifications
                                .getAcceptanceNotice(functionalToken, functional, UUID.randomUUID())
                                .qualificationKind())
                .isEqualTo("FUNCTIONAL");
        qualifications.acceptQualification(
                functionalToken,
                functional,
                false,
                code,
                hash,
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT m.blind_declared FROM game_member m JOIN game_room r ON"
                                        + " r.id=m.room_id WHERE r.room_key=?",
                                Boolean.class,
                                functional))
                .isNull();
        qualifications.startQualification(
                functionalToken, functional, 0, UUID.randomUUID(), UUID.randomUUID());
        assertThat(
                        qualifications
                                .getMaterials(functionalToken, functional, UUID.randomUUID())
                                .roles())
                .hasSize(2);
    }

    @Override
    protected int fixtureDifficulty() {
        return 2;
    }

    /** 실제 수락·보고서로 생성된 대기 작업은 원평가 회수 뒤 새 채점 권한을 얻지 못한다. */
    @Test
    void queuedBlindQualificationLosesExecutionWhenOriginalAssessmentIsRevoked() throws Exception {
        playtestPolicy();
        var selected = account("qualification-revoked-" + UUID.randomUUID() + "@example.invalid");
        var other =
                account("qualification-revoked-other-" + UUID.randomUUID() + "@example.invalid");
        String token = selected.path("accessToken").asText();
        UUID selectedKey = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID otherKey =
                invitations
                        .getMemberIdentity(other.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID invitation = invitation(List.of(selectedKey, otherKey));
        long snapshot =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE test_key=?",
                        Long.class,
                        invitation);
        UUID assessment = assess(selectedKey, snapshot);
        var notice =
                db.queryForMap(
                        "SELECT code,notice_hash FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'");
        UUID room =
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
                                selectedKey,
                                "BLIND",
                                (String) notice.get("code"),
                                (String) notice.get("notice_hash"),
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .roomKey();
        qualifications.acceptQualification(
                token,
                room,
                true,
                (String) notice.get("code"),
                (String) notice.get("notice_hash"),
                UUID.randomUUID(),
                UUID.randomUUID());
        qualifications.startQualification(token, room, 0, UUID.randomUUID(), UUID.randomUUID());
        qualifications.getMaterials(token, room, UUID.randomUUID());
        qualifications.editReport(
                token,
                room,
                1,
                0,
                supportedReport(invitation),
                UUID.randomUUID(),
                UUID.randomUUID());
        var submitted =
                qualifications.submitReport(
                        token, room, 2, 1, UUID.randomUUID(), UUID.randomUUID());
        UUID job = UUID.fromString(submitted.original().path("jobKey").asText());
        assertCurrentGame(job);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM grade_job WHERE job_key=?", String.class, job))
                .isEqualTo("QUEUED");
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        var lease = leases.claimGame(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(job);
        int chatsBefore = PROVIDER.chats;
        eligibility.revoke(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                assessment,
                2,
                "PARTICIPATION_FOUND",
                "synthetic.qualification:revoked",
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThatThrownBy(() -> assertCurrentGame(job)).hasMessageContaining("SOURCE_NOT_CURRENT");
        db.update("UPDATE grade_job SET lease_until=clock_timestamp() WHERE job_key=?", job);
        assembly.testRecovery().recover(job);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM grade_job WHERE job_key=?", String.class, job))
                .isEqualTo("FAILED");
        assertThat(
                        db.queryForObject(
                                "SELECT error_code FROM grade_job WHERE job_key=?",
                                String.class,
                                job))
                .isEqualTo("SOURCE_REVOKED");
        assertThat(PROVIDER.chats).isEqualTo(chatsBefore);
    }

    /** 합성 공급자와 실제 GAME 예약·암호화·채점·결과를 끝까지 통과한다. 사람/실모델 품질 증거가 아니다. */
    @Test
    void blindQualificationCompletesThroughInstalledGameWorkerAndPublishesResult()
            throws Exception {
        playtestPolicy();
        var selected = account("qualification-positive-" + UUID.randomUUID() + "@example.invalid");
        var companion =
                account("qualification-companion-" + UUID.randomUUID() + "@example.invalid");
        String token = selected.path("accessToken").asText();
        String foreignToken = companion.path("accessToken").asText();
        UUID selectedKey = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID companionKey =
                invitations.getMemberIdentity(foreignToken, UUID.randomUUID()).memberKey();
        assertThat(selectedKey).isNotEqualTo(companionKey);
        UUID invitation = invitation(List.of(selectedKey, companionKey));
        long snapshot =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE test_key=?",
                        Long.class,
                        invitation);
        UUID assessmentKey = assess(selectedKey, snapshot);
        var notice =
                db.queryForMap(
                        "SELECT code,notice_hash FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'");
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
                                selectedKey,
                                "BLIND",
                                (String) notice.get("code"),
                                (String) notice.get("notice_hash"),
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .roomKey();
        assertThat(
                        db.queryForObject(
                                "SELECT max_attempts FROM game_room WHERE room_key=? AND"
                                        + " assessment_id=(SELECT id FROM blind_assessment WHERE"
                                        + " assessment_key=?)",
                                Integer.class,
                                roomKey,
                                assessmentKey))
                .isEqualTo(5);
        qualifications.acceptQualification(
                token,
                roomKey,
                true,
                (String) notice.get("code"),
                (String) notice.get("notice_hash"),
                UUID.randomUUID(),
                UUID.randomUUID());
        qualifications.startQualification(token, roomKey, 0, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> qualifications.getResult(token, roomKey, UUID.randomUUID()))
                .hasMessageContaining("RESULT_NOT_READY");
        assertThatThrownBy(() -> qualifications.getReport(foreignToken, roomKey, UUID.randomUUID()))
                .hasMessageContaining("ROOM_UNAVAILABLE");
        var materials = qualifications.getMaterials(token, roomKey, UUID.randomUUID());
        assertThat(materials.roles()).hasSize(2);
        assertThat(qualifications.getMaterials(token, roomKey, UUID.randomUUID()).roles())
                .isEqualTo(materials.roles());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_exposure e JOIN game_room r ON"
                                    + " r.id=e.first_room_id WHERE r.room_key=? AND e.first_test_id"
                                    + " IS NULL",
                                Integer.class,
                                roomKey))
                .isEqualTo(1);
        var original = supportedReport(invitation);
        var edited =
                qualifications.editReport(
                        token, roomKey, 1, 0, original, UUID.randomUUID(), UUID.randomUUID());
        assertThat(edited.changed()).isTrue();
        var submitted =
                qualifications.submitReport(
                        token, roomKey, 2, 1, UUID.randomUUID(), UUID.randomUUID());
        UUID jobKey = UUID.fromString(submitted.original().path("jobKey").asText());
        var pinned =
                db.queryForMap(
                        "SELECT j.id AS job_id,j.state,j.report_hash,j.rubric_hash,"
                            + "j.game_report_id,j.batch_id,j.report_id,g.room_id,g.snapshot_id,g.source_rev,g.payload_cipher,g.payload_hash"
                            + " FROM grade_job j JOIN game_report g ON g.id=j.game_report_id WHERE"
                            + " j.job_key=?",
                        jobKey);
        assertThat(pinned)
                .containsEntry("state", "QUEUED")
                .containsEntry("batch_id", null)
                .containsEntry("report_id", null)
                .containsEntry("source_rev", 1L);
        long jobId = ((Number) pinned.get("job_id")).longValue();
        assertCurrentGame(jobKey);
        long reportId = ((Number) pinned.get("game_report_id")).longValue();
        String aad =
                "game_report/"
                        + reportId
                        + "/room/"
                        + pinned.get("room_id")
                        + "/snapshot/"
                        + pinned.get("snapshot_id")
                        + "/draft/"
                        + pinned.get("source_rev")
                        + "/payload/v1";
        assertThat(new String((byte[]) pinned.get("payload_cipher"), StandardCharsets.UTF_8))
                .doesNotContain("합성 근거");
        assertThat(
                        SnapshotJson.parse(
                                gameCrypto
                                        .decrypt(
                                                new String(
                                                        (byte[]) pinned.get("payload_cipher"),
                                                        StandardCharsets.UTF_8),
                                                aad)
                                        .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(original);
        assertThat(pinned.get("payload_hash")).isEqualTo(SnapshotJson.hash(original));
        var frozen =
                FrozenSnapshotCodec.decode(
                        db.queryForObject(
                                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                                        String.class,
                                        snapshot)
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(pinned.get("rubric_hash")).isEqualTo(frozen.rubricHash());
        assertThat(pinned.get("report_hash"))
                .isEqualTo(
                        frozen.reportHash(
                                new GradeResultValidator()
                                        .parseReport(
                                                new String(
                                                        SnapshotJson.encode(original),
                                                        StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> qualifications.getResult(token, roomKey, UUID.randomUUID()))
                .hasMessageContaining("RESULT_NOT_READY");

        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        var lease = leases.claimGame(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(jobKey);
        Path root =
                Files.createTempDirectory(
                                "qualification-game-worker-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        int chatsBefore = PROVIDER.chats;
        try {
            var completionOwner = org.mockito.Mockito.spy(assembly.completion());
            var completionFailure =
                    new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
            org.mockito.Mockito.doAnswer(
                            call -> {
                                try {
                                    return call.callRealMethod();
                                } catch (RuntimeException failure) {
                                    completionFailure.set(failure);
                                    throw failure;
                                }
                            })
                    .when(completionOwner)
                    .complete(
                            org.mockito.ArgumentMatchers.eq(worker),
                            org.mockito.ArgumentMatchers.eq(jobKey),
                            org.mockito.ArgumentMatchers.eq(lease.leaseGen()),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.nullable(String.class),
                            org.mockito.ArgumentMatchers.nullable(String.class),
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(UUID.class));
            var runner =
                    new GradeLocalRunner(
                            credentials, assembly.start(), completionOwner, profiles(root));
            var completion = runner.runGameOnce(worker, jobKey, lease.leaseGen());
            Throwable rootFailure = completionFailure.get();
            while (rootFailure != null && rootFailure.getCause() != null)
                rootFailure = rootFailure.getCause();
            assertThat(
                            rootFailure == null
                                    ? null
                                    : rootFailure.getClass().getSimpleName()
                                            + ": "
                                            + rootFailure.getMessage().split("\\n")[0]
                                            + " "
                                            + java.util.Arrays.toString(
                                                    java.util.Arrays.copyOf(
                                                            rootFailure.getStackTrace(), 4)))
                    .isNull();
            assertThat(completion.state())
                    .as("실제 모델 호출 수: %s", PROVIDER.chats - chatsBefore)
                    .isEqualTo(GradeLocalRunner.State.RECEIPT);
            assertThat(completion.receipt().accepted()).isTrue();
            assertThat(completion.receipt().state()).isEqualTo("COMPLETED");
            assertThat(completion.receipt().outcome()).isEqualTo("COMPLETE");
        } finally {
            Files.deleteIfExists(root.resolve("profiles.json"));
            Files.deleteIfExists(root);
        }
        assertThat(PROVIDER.chats).isEqualTo(chatsBefore + 1);
        assertThat(leases.claimGame(worker.workerKey(), "H5_SYNTHETIC")).isEmpty();
        assertThat(PROVIDER.semantic.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(
                        db.queryForMap(
                                "SELECT state,call_count,report_hash,rubric_hash FROM grade_job"
                                        + " WHERE id=?",
                                jobId))
                .containsEntry("state", "COMPLETED")
                .containsEntry("call_count", 1)
                .containsEntry("report_hash", pinned.get("report_hash"))
                .containsEntry("rubric_hash", frozen.rubricHash());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=? AND"
                                        + " state='SUCCEEDED' AND output_cipher IS NOT NULL AND"
                                        + " completion_data IS NOT NULL",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_event WHERE job_id=? "
                                        + "AND event_kind='COMPLETE_APPLIED'",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
        var score =
                SnapshotJson.parse(
                                gameCrypto
                                        .decrypt(
                                                new String(
                                                        db.queryForObject(
                                                                "SELECT result_cipher FROM"
                                                                        + " grade_job WHERE id=?",
                                                                byte[].class,
                                                                jobId),
                                                        StandardCharsets.UTF_8),
                                                "grade_job/" + jobId + "/result/v1")
                                        .getBytes(StandardCharsets.UTF_8))
                        .path("baseResult");
        assertThat(score.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(score.path("success").asBoolean()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM game_report WHERE id=?", String.class, reportId))
                .isEqualTo("GRADED");
        var result = qualifications.getResult(token, roomKey, UUID.randomUUID());
        assertThat(result.state()).isEqualTo("ENDED");
        assertThat(result.finalScore()).isEqualTo(score.path("baseScore").asInt());
        assertThat(result.gradedCount()).isEqualTo(1);
        assertThat(result.wrongCount()).isZero();
        assertThat(result.reports()).hasSize(1);
        assertThat(result.scoringRules().path("attemptLimit").asInt()).isEqualTo(5);
        assertThatThrownBy(() -> qualifications.getResult(foreignToken, roomKey, UUID.randomUUID()))
                .hasMessageContaining("ROOM_UNAVAILABLE");
        UUID feedbackKey = UUID.randomUUID();
        assertThat(
                        qualifications
                                .submitFeedback(
                                        token, roomKey, "합성 검수 피드백", feedbackKey, UUID.randomUUID())
                                .replayed())
                .isFalse();
        assertThat(
                        qualifications
                                .submitFeedback(
                                        token, roomKey, "합성 검수 피드백", feedbackKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM release_solo_feedback WHERE room_id=?",
                                Integer.class,
                                pinned.get("room_id")))
                .isEqualTo(1);
        long story =
                db.queryForObject(
                        "SELECT story_id FROM game_room WHERE room_key=?", Long.class, roomKey);
        long member =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?",
                        Long.class,
                        selectedKey);
        long room = ((Number) pinned.get("room_id")).longValue();
        long assessment =
                db.queryForObject(
                        "SELECT id FROM blind_assessment WHERE assessment_key=?",
                        Long.class,
                        assessmentKey);
        var reader = new BlindEligibilityReader(db);
        assertThat(reader.game(story, snapshot, member, room, assessment, Use.EVIDENCE)).isTrue();
        // A genuine graded, exposed SOLO source and a real reviewer PASS are insufficient for
        // collection: this one-source fixture has no three independent TEST/SOLO sources per pair.
        long reviewId =
                Long.parseLong(
                        releases.review(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        "SOLO",
                                        room,
                                        "PASS",
                                        0,
                                        "합성 채점 및 피드백 검토",
                                        "SyntheticReviewer_01",
                                        null,
                                        null,
                                        UUID.randomUUID())
                                .original()
                                .path("recordId")
                                .asText());
        assertThat(
                        db.queryForObject(
                                "SELECT reviewer_id FROM release_human_review WHERE id=?",
                                Long.class,
                                reviewId))
                .isEqualTo(invitationActor.accountId());
        var runtime =
                db.queryForMap(
                        "SELECT runtime_id,runtime_epoch,config_hash,version_id"
                                + " FROM game_room WHERE id=?",
                        room);
        var coverage =
                releaseEvidence.evaluateRelease(
                        ((Number) runtime.get("version_id")).longValue(),
                        snapshot,
                        ((Number) runtime.get("runtime_id")).longValue(),
                        ((Number) runtime.get("runtime_epoch")).longValue(),
                        (String) runtime.get("config_hash"),
                        Long.MAX_VALUE);
        assertThat(coverage.state()).isEqualTo(StoryReleaseEvidenceService.State.MISSING);
        assertThat(coverage.reason()).startsWith("BLIND_TEST_");
        assertThatThrownBy(
                        () ->
                                releases.collect(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        Long.toString(snapshot),
                                        "H5_SYNTHETIC",
                                        UUID.randomUUID()))
                .hasMessageContaining("EVIDENCE_NOT_CONNECTED");
        eligibility.create(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                2,
                snapshot,
                selectedKey,
                "INELIGIBLE",
                "PARTICIPATION_FOUND",
                "synthetic.qualification:later",
                Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS),
                true,
                assessmentKey,
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(reader.game(story, snapshot, member, room, assessment, Use.EVIDENCE)).isFalse();
    }

    @Test
    void selectedMemberProducesEncryptedReportAndOnlyOneWorkerJob() throws Exception {
        playtestPolicy();
        var selected = account("qualification-report-" + UUID.randomUUID() + "@example.invalid");
        var other = account("qualification-wrong-" + UUID.randomUUID() + "@example.invalid");
        String token = selected.path("accessToken").asText();
        UUID memberKey = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
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
        assess(memberKey, snapshot);
        var notice =
                db.queryForMap(
                        "SELECT code,notice_hash FROM privacy_policy WHERE scope='PLAYTEST' AND"
                                + " state='ACTIVE'");
        UUID key =
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
                                "BLIND",
                                (String) notice.get("code"),
                                (String) notice.get("notice_hash"),
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .roomKey();
        assertThat(
                        db.queryForObject(
                                "SELECT max_attempts FROM game_room WHERE room_key=?",
                                Integer.class,
                                key))
                .isEqualTo(5);
        qualifications.acceptQualification(
                token,
                key,
                true,
                (String) notice.get("code"),
                (String) notice.get("notice_hash"),
                UUID.randomUUID(),
                UUID.randomUUID());
        qualifications.startQualification(token, key, 0, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                qualifications.getReport(
                                        other.path("accessToken").asText(), key, UUID.randomUUID()))
                .hasMessageContaining("ROOM_UNAVAILABLE");
        assertThat(qualifications.getReport(token, key, UUID.randomUUID()).draftRev())
                .isEqualTo("0");
        var empty =
                JsonNodeFactory.instance
                        .objectNode()
                        .putNull("culpritCode")
                        .put("method", "")
                        .put("time", "")
                        .put("motive", "")
                        .put("evidence", "");
        var noop =
                qualifications.editReport(
                        token, key, 1, 0, empty, UUID.randomUUID(), UUID.randomUUID());
        assertThat(noop.changed()).isFalse();
        assertThat(noop.current().rev()).isEqualTo("1");
        String culprit =
                db.queryForObject(
                        "SELECT payload->'resources'->'persons'->0->>'code' FROM review_snapshot"
                                + " WHERE id=?",
                        String.class,
                        snapshot);
        var report =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("culpritCode", culprit)
                        .put("method", "합성 방법")
                        .put("time", "합성 시각")
                        .put("motive", "합성 동기")
                        .put("evidence", "합성 증거");
        UUID editKey = UUID.randomUUID();
        var edit = qualifications.editReport(token, key, 1, 0, report, editKey, UUID.randomUUID());
        assertThat(edit.changed()).isTrue();
        assertThat(edit.current().draftRev()).isEqualTo("1");
        assertThat(
                        qualifications
                                .editReport(token, key, 1, 0, report, editKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                qualifications.editReport(
                                        token, key, 1, 0, empty, editKey, UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                qualifications.submitReport(
                                        token, key, 1, 0, UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("EDIT_CONFLICT");
        UUID submitKey = UUID.randomUUID();
        var submitted = qualifications.submitReport(token, key, 2, 1, submitKey, UUID.randomUUID());
        assertThat(submitted.original().path("reportKey").isTextual()).isTrue();
        assertThat(submitted.current().submissionState()).isEqualTo("PENDING");
        UUID unexposedJob = UUID.fromString(submitted.original().path("jobKey").asText());
        assertCurrentGame(unexposedJob);
        long unexposedMember =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?", Long.class, memberKey);
        long unexposedStory =
                db.queryForObject(
                        "SELECT story_id FROM game_room WHERE room_key=?", Long.class, key);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_exposure WHERE story_id=? AND"
                                        + " member_id=?",
                                Integer.class,
                                unexposedStory,
                                unexposedMember))
                .isZero();
        long unexposedRoom =
                db.queryForObject("SELECT id FROM game_room WHERE room_key=?", Long.class, key);
        long unexposedAssessment =
                db.queryForObject(
                        "SELECT assessment_id FROM game_room WHERE id=?",
                        Long.class,
                        unexposedRoom);
        assertThat(
                        new BlindEligibilityReader(db)
                                .game(
                                        unexposedStory,
                                        snapshot,
                                        unexposedMember,
                                        unexposedRoom,
                                        unexposedAssessment,
                                        Use.EVIDENCE))
                .isFalse();
        assertThat(
                        qualifications
                                .submitReport(token, key, 2, 1, submitKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        var pin =
                db.queryForMap(
                        "SELECT"
                            + " r.id,r.room_id,r.snapshot_id,r.source_rev,r.payload_cipher,r.payload_hash,j.report_hash,j.state,j.deadline_at-r.accepted_at"
                            + " AS delay FROM game_report r JOIN grade_job j ON"
                            + " j.game_report_id=r.id WHERE r.report_key=?",
                        UUID.fromString(submitted.original().path("reportKey").asText()));
        assertThat(pin.get("state")).isEqualTo("QUEUED");
        assertThat(pin.get("payload_hash")).isEqualTo(SnapshotJson.hash(report));
        assertThat(new String((byte[]) pin.get("payload_cipher"), StandardCharsets.UTF_8))
                .doesNotContain("합성 방법");
        assertThat(pin.get("report_hash")).isNotEqualTo(pin.get("payload_hash"));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_report WHERE room_id=?",
                                Integer.class,
                                pin.get("room_id")))
                .isEqualTo(1);
        db.update(
                "UPDATE game_room SET"
                    + " state='ENDED',end_reason='FORFEIT',ended_at=clock_timestamp(),time_basis_at=clock_timestamp()"
                    + " WHERE room_key=?",
                key);
        var ended =
                db.queryForObject(
                        "SELECT ended_at FROM game_room WHERE room_key=?",
                        java.sql.Timestamp.class,
                        key);
        db.update(
                "UPDATE game_room SET ended_at=?::timestamptz-interval '25 hours' WHERE room_key=?",
                ended,
                key);
        assertThatThrownBy(() -> qualifications.getResult(token, key, UUID.randomUUID()))
                .hasMessageContaining("RESULT_EXPIRED");
        assertThatThrownBy(
                        () ->
                                qualifications.submitFeedback(
                                        token,
                                        key,
                                        "만료된 피드백",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FEEDBACK_EXPIRED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM release_solo_feedback WHERE room_id=?",
                                Integer.class,
                                pin.get("room_id")))
                .isZero();
        db.update("UPDATE game_room SET ended_at=? WHERE room_key=?", ended, key);
        assertThat(qualifications.getResult(token, key, UUID.randomUUID()).finalScore()).isNull();
        UUID feedbackKey = UUID.randomUUID();
        var feedback =
                qualifications.submitFeedback(
                        token, key, "명확한 피드백", feedbackKey, UUID.randomUUID());
        assertThat(feedback.replayed()).isFalse();
        assertThat(
                        qualifications
                                .submitFeedback(
                                        token, key, "명확한 피드백", feedbackKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                qualifications.submitFeedback(
                                        token, key, "다른 피드백", feedbackKey, UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_audit WHERE room_id=? AND"
                                        + " action='QUALIFICATION_FEEDBACK'",
                                Integer.class,
                                pin.get("room_id")))
                .isEqualTo(1);
        assertThat(
                        new String(
                                db.queryForObject(
                                        "SELECT feedback_cipher FROM release_solo_feedback WHERE"
                                                + " room_id=?",
                                        byte[].class,
                                        pin.get("room_id")),
                                StandardCharsets.UTF_8))
                .doesNotContain("명확한 피드백");
        assertThatThrownBy(
                        () ->
                                qualifications.submitFeedback(
                                        other.path("accessToken").asText(),
                                        key,
                                        "부당한 피드백",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("ROOM_UNAVAILABLE");
        db.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=(SELECT story_id FROM"
                        + " game_room WHERE room_key=?) AND admin_id=? AND permission='REVIEW'",
                key,
                invitationActor.accountId());
        assertThatThrownBy(() -> qualifications.getReport(token, key, UUID.randomUUID()))
                .hasMessageContaining("ROOM_UNAVAILABLE");
    }
}
