package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal.Scope;
import com.reasoning.common.grading.controller.GradeWorkerController.Assembly;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.service.GradeCompletionService;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;
import com.reasoning.common.grading.service.GradeStartService;
import com.reasoning.common.member.LocalMemberAuthIT;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 실제 PostgreSQL·LOCAL 세션·암호화·고정 REVIEW 원본을 통과하는 공동 보고서 회귀다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LocalMemberAuthIT.Installation.class, PlaytestReportFixture.TestAssembly.class})
public class PlaytestReportIT extends PlaytestReportFixture {
    private boolean policyReady;

    @Autowired
    com.reasoning.common.member.auth.MemberAuthConfiguration.Properties memberConfiguration;

    @Autowired MemberPolicyGate memberPolicyGate;

    protected record Pair(UUID key, String first, String second, String outsider) {}

    /** 부모의 테스트별 정책 초기화에 맞춰 단일 정책 fixture를 다시 준비한다. */
    @BeforeEach
    void resetPolicyFixture() {
        policyReady = false;
    }

    /** 만료 가능한 lease만 만료시킨 뒤 실제 복구로 worker fence를 해제한다. */
    @AfterEach
    void recoverOutstandingWorkerLeases() {
        var keys =
                db.queryForList(
                        "SELECT job_key FROM grade_job WHERE state='RUNNING' AND worker_key=? AND"
                                + " batch_id IS NULL",
                        UUID.class,
                        WORKER);
        for (UUID key : keys) {
            var pins =
                    db.queryForMap(
                            "SELECT accepted_at,deadline_at,call_count,lease_gen FROM grade_job"
                                    + " WHERE job_key=?",
                            key);
            var counters =
                    db.queryForMap(
                            "SELECT t.attempt_count,t.wrong_count FROM play_test t JOIN test_report"
                                + " r ON r.test_id=t.id JOIN grade_job j ON j.report_id=r.id WHERE"
                                + " j.job_key=?",
                            key);
            var authority = workerParticipantAuthority(key);
            db.update(
                    "UPDATE grade_job SET lease_until=clock_timestamp() WHERE job_key=? AND"
                            + " state='RUNNING' AND worker_key=?",
                    key,
                    WORKER);
            var recovered = assembly.testRecovery().recover(key);
            assertThat(recovered.changed()).isTrue();
            assertThat(
                            db.queryForMap(
                                    "SELECT accepted_at,deadline_at,call_count,lease_gen FROM"
                                            + " grade_job WHERE job_key=?",
                                    key))
                    .isEqualTo(pins);
            assertThat(
                            db.queryForMap(
                                    "SELECT t.attempt_count,t.wrong_count FROM play_test t JOIN"
                                            + " test_report r ON r.test_id=t.id JOIN grade_job j ON"
                                            + " j.report_id=r.id WHERE j.job_key=?",
                                    key))
                    .isEqualTo(counters);
            assertThat(workerParticipantAuthority(key)).isEqualTo(authority);
            assertThat(
                            db.queryForObject(
                                    "SELECT worker_key IS NULL AND lease_until IS NULL AND"
                                            + " state<>'RUNNING' FROM grade_job WHERE job_key=?",
                                    Boolean.class,
                                    key))
                    .isTrue();
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_job WHERE state='RUNNING' AND"
                                        + " worker_key=?",
                                Integer.class,
                                WORKER))
                .isZero();
        // 복구가 정상 재시도를 QUEUED로 돌린 경우 다음 fixture의 FIFO 앞에 남기지 않는다.
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        var queued =
                db.queryForList(
                        "SELECT j.job_key FROM grade_job j JOIN grade_runtime r ON"
                            + " r.id=j.runtime_id WHERE j.state='QUEUED' AND j.batch_id IS NULL AND"
                            + " r.code='H5_SYNTHETIC' ORDER BY j.id",
                        UUID.class);
        for (UUID key : queued) {
            var clocks =
                    db.queryForMap(
                            "SELECT accepted_at,deadline_at FROM grade_job WHERE job_key=?", key);
            var counters =
                    db.queryForMap(
                            "SELECT t.attempt_count,t.wrong_count FROM play_test t JOIN test_report"
                                + " r ON r.test_id=t.id JOIN grade_job j ON j.report_id=r.id WHERE"
                                + " j.job_key=?",
                            key);
            assembly.testRecovery().recover(key);
            while ("QUEUED"
                    .equals(
                            db.queryForObject(
                                    "SELECT state FROM grade_job WHERE job_key=?",
                                    String.class,
                                    key))) {
                var lease = leases.claimTest(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
                assertThat(lease.jobKey()).isEqualTo(key);
                boolean epochCurrent =
                        Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT rt.epoch=r.runtime_epoch FROM grade_job j JOIN"
                                                + " test_report r ON r.id=j.report_id JOIN"
                                                + " grade_runtime rt ON rt.id=j.runtime_id WHERE"
                                                + " j.job_key=?",
                                        Boolean.class,
                                        key));
                if (!epochCurrent) {
                    db.update(
                            "UPDATE grade_job SET lease_until=clock_timestamp() WHERE job_key=?",
                            key);
                    assertThat(assembly.testRecovery().recover(key).changed()).isTrue();
                    continue;
                }
                var started = assembly.start().startApproved(worker, key, lease.leaseGen());
                var receipt =
                        assembly.completion()
                                .complete(
                                        worker,
                                        key,
                                        lease.leaseGen(),
                                        started.attemptNo(),
                                        null,
                                        null,
                                        "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                                        UUID.randomUUID());
                assertThat(receipt.accepted()).isTrue();
            }
            assertThat(
                            db.queryForMap(
                                    "SELECT accepted_at,deadline_at FROM grade_job WHERE job_key=?",
                                    key))
                    .isEqualTo(clocks);
            assertThat(
                            db.queryForMap(
                                    "SELECT t.attempt_count,t.wrong_count FROM play_test t JOIN"
                                            + " test_report r ON r.test_id=t.id JOIN grade_job j ON"
                                            + " j.report_id=r.id WHERE j.job_key=?",
                                    key))
                    .isEqualTo(counters);
            assertThat(
                            db.queryForObject(
                                    "SELECT worker_key IS NULL AND lease_until IS NULL AND"
                                        + " result_cipher IS NULL AND call_count<=3 FROM grade_job"
                                        + " WHERE job_key=?",
                                    Boolean.class,
                                    key))
                    .isTrue();
        }
    }

    private List<Map<String, Object>> workerParticipantAuthority(UUID key) {
        return db.queryForList(
                "SELECT"
                    + " m.member_id,a.auth_rev,a.state,p.policy_id,p.accepted_at,i.active_yn,i.proof_at"
                    + " FROM grade_job j JOIN test_report r ON r.id=j.report_id JOIN test_member m"
                    + " ON m.test_id=r.test_id JOIN member_account a ON a.id=m.member_id JOIN"
                    + " member_profile p ON p.member_id=m.member_id JOIN member_identity i ON"
                    + " i.member_id=m.member_id WHERE j.job_key=? ORDER BY m.member_id,i.id",
                key);
    }

    /** 별도 회원의 동의·준비·활동 확인을 마친 실제 RUNNING 행을 만든다. */
    protected Pair running() throws Exception {
        if (!policyReady) {
            playtestPolicy();
            policyReady = true;
        }
        String first =
                account("report-first-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String second =
                account("report-second-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String outsider =
                account("report-other-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID key =
                invitation(
                        List.of(
                                invitations.getMemberIdentity(first, UUID.randomUUID()).memberKey(),
                                invitations
                                        .getMemberIdentity(second, UUID.randomUUID())
                                        .memberKey()));
        for (String token : List.of(first, second)) {
            var notice = invitations.getConsentNotice(token, key, UUID.randomUUID());
            invitations.acceptInvitation(
                    token,
                    key,
                    1,
                    rev(key),
                    notice.policyCode(),
                    notice.noticeHash(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
        investigation.ready(first, key, rev(key), true, UUID.randomUUID(), UUID.randomUUID());
        investigation.ready(second, key, rev(key), true, UUID.randomUUID(), UUID.randomUUID());
        investigation.heartbeat(first, key, UUID.randomUUID());
        investigation.heartbeat(second, key, UUID.randomUUID());
        investigation.start(first, key, rev(key), UUID.randomUUID(), UUID.randomUUID());
        return new Pair(key, first, second, outsider);
    }

    /**
     * @param key 실제 초대 키
     * @return 서버 저장 수정번호
     */
    protected long rev(UUID key) {
        return db.queryForObject("SELECT rev FROM play_test WHERE test_key=?", Long.class, key);
    }

    /**
     * @param key 실제 초대 키
     * @return 서버 저장 공동 초안 번호
     */
    protected long draftRev(UUID key) {
        return db.queryForObject(
                "SELECT draft_rev FROM play_test WHERE test_key=?", Long.class, key);
    }

    /**
     * @param marker 피드백 본문 식별값
     * @return 본인에게만 저장할 합성 자유 입력
     */
    protected JsonNode feedback(String marker) {
        return JSON.createObjectNode()
                .put("blockedAt", "합성 장애 " + marker)
                .put("roleContribution", "합성 역할")
                .put("fairness", "합성 공정성");
    }

    /** 한 명의 실제 수정은 양쪽 읽기에 반영되고 경합한 이전 CAS·키 재사용은 쓰지 못한다. */
    @Test
    void sharedDraftCasReplayAndCiphertextAreReal() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        long originalRev = rev(key);
        JsonNode first = report(key, "첫째");
        UUID intent = UUID.randomUUID();
        var written =
                reports.edit(
                        pair.first(),
                        key,
                        rev(key),
                        draftRev(key),
                        first,
                        intent,
                        UUID.randomUUID());
        assertThat(written.changed()).isTrue();
        assertThat(reports.report(pair.second(), key, UUID.randomUUID()).report()).isEqualTo(first);
        assertThat(draftRev(key)).isEqualTo(1);
        byte[] cipher =
                db.queryForObject(
                        "SELECT draft_cipher FROM play_test WHERE test_key=?", byte[].class, key);
        assertThat(cipher).isNotNull();
        assertThat(new String(cipher, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("합성 방법", "culpritCode");
        assertThat(
                        reports.edit(
                                        pair.first(),
                                        key,
                                        originalRev,
                                        0,
                                        first,
                                        intent,
                                        UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                reports.edit(
                                        pair.second(),
                                        key,
                                        originalRev,
                                        0,
                                        report(key, "경합"),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("EDIT_CONFLICT");
        assertThatThrownBy(
                        () ->
                                reports.edit(
                                        pair.first(),
                                        key,
                                        originalRev,
                                        0,
                                        report(key, "키 변경"),
                                        intent,
                                        UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThat(draftRev(key)).isEqualTo(1);
        assertThatThrownBy(() -> reports.report(pair.outsider(), key, UUID.randomUUID()))
                .hasMessageContaining("NOT_FOUND");
    }

    /** 상대가 보는 제안은 원문 수정 시 무효화되고 옛 제안에 대한 수락은 불가능하다. */
    @Test
    void proposedSnapshotInvalidatedByEitherMembersEdit() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        reports.edit(
                pair.first(),
                key,
                rev(key),
                draftRev(key),
                report(key, "초안"),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                pair.first(), key, rev(key), draftRev(key), UUID.randomUUID(), UUID.randomUUID());
        var pending = reports.report(pair.second(), key, UUID.randomUUID()).proposal();
        assertThat(pending).isNotNull();
        for (String token : List.of(pair.first(), pair.second())) {
            assertThat(
                            invitations
                                    .getMemberInvitationDetail(token, key, UUID.randomUUID())
                                    .submissionState())
                    .isEqualTo("PROPOSED");
        }
        assertThat(pending.canAccept()).isTrue();
        assertThat(pending.canReject()).isTrue();
        UUID proposed = pending.reportKey();
        assertThat(reports.report(pair.first(), key, UUID.randomUUID()).proposal().canAccept())
                .isFalse();
        reports.edit(
                pair.second(),
                key,
                rev(key),
                draftRev(key),
                report(key, "상대 수정"),
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM test_report WHERE report_key=?",
                                String.class,
                                proposed))
                .isEqualTo("INVALIDATED");
        assertThat(reports.report(pair.first(), key, UUID.randomUUID()).proposal()).isNull();
        for (String token : List.of(pair.first(), pair.second())) {
            assertThat(
                            invitations
                                    .getMemberInvitationDetail(token, key, UUID.randomUUID())
                                    .submissionState())
                    .isEqualTo("NONE");
        }
        assertThatThrownBy(
                        () ->
                                reports.respond(
                                        pair.second(),
                                        key,
                                        proposed,
                                        rev(key),
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("STATE_CONFLICT");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_job WHERE report_id IN (SELECT id FROM"
                                    + " test_report WHERE test_id=(SELECT id FROM play_test WHERE"
                                    + " test_key=?))",
                                Integer.class,
                                key))
                .isZero();
    }

    /** 소비자가 없는 동일 서비스 경계는 503으로 차단하고 제안·작업을 그대로 둔다. */
    @Test
    void acceptanceWithoutGenuineConsumerNeverCreatesGradeJob() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        reports.edit(
                pair.first(),
                key,
                rev(key),
                draftRev(key),
                report(key, "제안"),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                pair.first(), key, rev(key), draftRev(key), UUID.randomUUID(), UUID.randomUUID());
        UUID proposed =
                reports.report(pair.second(), key, UUID.randomUUID()).proposal().reportKey();
        var unavailable =
                new PlaytestReportService(
                        access,
                        db,
                        crypto,
                        new StaticListableBeanFactory().getBeanProvider(Assembly.class));
        assertThat(unavailable.report(pair.second(), key, UUID.randomUUID()).proposal().canAccept())
                .isFalse();
        assertThatThrownBy(
                        () ->
                                unavailable.respond(
                                        pair.second(),
                                        key,
                                        proposed,
                                        rev(key),
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        AuthException.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(503);
                            assertThat(failure.code()).isEqualTo("PLAYTEST_UNAVAILABLE");
                        });

        var batchManager = new DataSourceTransactionManager(db.getDataSource());
        var batch =
                new Assembly(
                        credentials,
                        new GradeStartService(db, batchManager, credentials, installations),
                        new GradeCompletionService(
                                db,
                                batchManager,
                                credentials,
                                installations,
                                crypto,
                                "H5_SYNTHETIC_COORDINATOR"));
        assertThat(batch.supportsTest()).isFalse();
        var batchBeans = new StaticListableBeanFactory();
        batchBeans.addBean("batch", batch);
        var batchOnly =
                new PlaytestReportService(
                        access, db, crypto, batchBeans.getBeanProvider(Assembly.class));
        assertThat(batchOnly.report(pair.second(), key, UUID.randomUUID()).proposal().canAccept())
                .isFalse();
        assertThatThrownBy(
                        () ->
                                batchOnly.respond(
                                        pair.second(),
                                        key,
                                        proposed,
                                        rev(key),
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        AuthException.class,
                        failure -> assertThat(failure.status()).isEqualTo(503));

        var multipleBeans = new StaticListableBeanFactory();
        multipleBeans.addBean("first", assembly);
        multipleBeans.addBean("second", batch);
        var ambiguous =
                new PlaytestReportService(
                        access, db, crypto, multipleBeans.getBeanProvider(Assembly.class));
        assertThat(ambiguous.report(pair.second(), key, UUID.randomUUID()).proposal().canAccept())
                .isFalse();
        assertThatThrownBy(
                        () ->
                                ambiguous.respond(
                                        pair.second(),
                                        key,
                                        proposed,
                                        rev(key),
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        AuthException.class,
                        failure -> assertThat(failure.status()).isEqualTo(503));
        assertThat(
                        db.queryForMap(
                                "SELECT state,accepted_by,accepted_at,submit_no FROM test_report"
                                        + " WHERE report_key=?",
                                proposed))
                .containsEntry("state", "PROPOSED")
                .containsEntry("accepted_by", null)
                .containsEntry("accepted_at", null)
                .containsEntry("submit_no", null);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_job WHERE report_id="
                                        + "(SELECT id FROM test_report WHERE report_key=?)",
                                Integer.class,
                                proposed))
                .isZero();
    }

    /** 회원 REST 수락은 실제 TEST 조립이 있을 때만 제안·작업·영수증을 원자적으로 고정한다. */
    @Test
    void publicAcceptCreatesQueuedTestJobAndReplaysReceipt() throws Exception {
        publicAcceptCreatesQueuedTestJobAndReplaysReceipt(false);
    }

    /** Wire-clock skew simulation only, not native transport or synchronized-clock evidence. */
    @Test
    void workerAheadOfExportedTestDeadlineUsesAuthoritativeStartBudget() throws Exception {
        publicAcceptCreatesQueuedTestJobAndReplaysReceipt(true);
    }

    private void publicAcceptCreatesQueuedTestJobAndReplaysReceipt(boolean wireClockSkew)
            throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        reports.edit(
                pair.first(),
                key,
                rev(key),
                draftRev(key),
                supportedReport(key),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                pair.first(), key, rev(key), draftRev(key), UUID.randomUUID(), UUID.randomUUID());
        UUID proposed =
                reports.report(pair.second(), key, UUID.randomUUID()).proposal().reportKey();
        assertThat(reports.report(pair.first(), key, UUID.randomUUID()).proposal().canAccept())
                .isFalse();
        investigation.heartbeat(pair.first(), key, UUID.randomUUID());
        investigation.heartbeat(pair.second(), key, UUID.randomUUID());
        long revision = rev(key);
        UUID requestKey = UUID.randomUUID();
        String path = "/api/playtests/" + key + "/report/proposals/" + proposed + "/respond";
        byte[] body =
                JSON.writeValueAsBytes(
                        Map.of(
                                "expectedRev",
                                Long.toString(revision),
                                "decision",
                                "ACCEPT",
                                "requestKey",
                                requestKey.toString()));
        var accepted =
                mvc.perform(
                                post(path)
                                        .secure(true)
                                        .header("Authorization", "Bearer " + pair.second())
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode receipt = JSON.readTree(accepted.getResponse().getContentAsByteArray());
        assertThat(receipt.path("replayed").asBoolean()).isFalse();
        assertThat(receipt.path("changed").asBoolean()).isTrue();
        for (String token : List.of(pair.first(), pair.second())) {
            assertThat(
                            invitations
                                    .getMemberInvitationDetail(token, key, UUID.randomUUID())
                                    .submissionState())
                    .isEqualTo("PENDING");
        }
        var job =
                db.queryForMap(
                        "SELECT"
                            + " j.state,j.report_id,j.batch_id,j.sample_code,j.repeat_no,j.input_hash,j.job_key,r.state"
                            + " AS report_state,r.submit_no FROM grade_job j JOIN test_report r ON"
                            + " r.id=j.report_id WHERE r.report_key=?",
                        proposed);
        assertThat(job)
                .containsEntry("state", "QUEUED")
                .containsEntry("report_state", "ACCEPTED")
                .containsEntry("batch_id", null)
                .containsEntry("sample_code", null)
                .containsEntry("repeat_no", null)
                .containsEntry("input_hash", null)
                .containsEntry("submit_no", 1);
        assertThat(receipt.path("original").path("jobKey").asText())
                .isEqualTo(job.get("job_key").toString());
        var replayed =
                mvc.perform(
                                post(path)
                                        .secure(true)
                                        .header("Authorization", "Bearer " + pair.second())
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode replay = JSON.readTree(replayed.getResponse().getContentAsByteArray());
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("original")).isEqualTo(receipt.path("original"));
        var withoutConsumer =
                new PlaytestReportService(
                        access,
                        db,
                        crypto,
                        new StaticListableBeanFactory().getBeanProvider(Assembly.class));
        var durableReplay =
                withoutConsumer.respond(
                        pair.second(),
                        key,
                        proposed,
                        revision,
                        "ACCEPT",
                        requestKey,
                        UUID.randomUUID());
        assertThat(durableReplay.replayed()).isTrue();
        assertThat(durableReplay.original().get("jobKey")).isEqualTo(job.get("job_key").toString());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_job WHERE report_id=?",
                                Integer.class,
                                job.get("report_id")))
                .isEqualTo(1);

        UUID jobKey = UUID.fromString(job.get("job_key").toString());
        long jobId =
                ((Number)
                                db.queryForObject(
                                        "SELECT id FROM grade_job WHERE job_key=?",
                                        Long.class,
                                        jobKey))
                        .longValue();
        var originalClock =
                db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", jobId);
        assertThat(
                        db.queryForObject(
                                "SELECT deadline_at=accepted_at+interval '120 seconds'"
                                        + " FROM grade_job WHERE id=?",
                                Boolean.class,
                                jobId))
                .isTrue();
        assertThat(assembly.supportsTest()).isTrue();
        assertThat(installations.get("H5_SYNTHETIC").configHash())
                .isEqualTo(PROVIDER.installation.configHash());
        Path root =
                Files.createTempDirectory(
                                "h5-worker-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        Scope scope = new Scope(SERVER_SCOPE, WORKER, CommonUtil.sha256(SECRET));
        int beforeChats = PROVIDER.chats;
        var transport = new AssemblyTransport(scope, wireClockSkew);
        GradeCompletionService.CompletionReceipt completion;
        try {
            var profiles = profiles(root);
            Path descriptor = journal(root, scope);
            try (var owner =
                    new GradeRemoteBatchWorker(
                            GradeRemoteOnceJournal.open(descriptor.toString()),
                            transport,
                            new GradeRemoteBatchWorker.TransportBounds(
                                    8 * 1024 * 1024, Duration.ofSeconds(10)),
                            profiles)) {
                var outcome = owner.pollAndRunOnce("H5_SYNTHETIC");
                assertThat(outcome).isNotNull();
                assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
                completion = outcome.receipt();
                assertThat(completion.jobKey()).isEqualTo(jobKey);
                assertThat(completion.attemptNo()).isEqualTo(1);
            }
        } finally {
            Files.deleteIfExists(root.resolve("profiles.json"));
            Files.deleteIfExists(root.resolve("descriptor.json"));
            Files.deleteIfExists(root.resolve("journal.bin"));
            Files.deleteIfExists(root.resolve("owner.lock"));
            Files.deleteIfExists(root);
        }
        assertThat(transport.polls).isEqualTo(1);
        assertThat(transport.starts).isEqualTo(1);
        assertThat(transport.fences).isEqualTo(1);
        assertThat(transport.completions).isEqualTo(1);
        if (wireClockSkew) assertThat(transport.wireDeadline).isBefore(Instant.now());
        assertThat(PROVIDER.chats).isEqualTo(beforeChats + 1);
        JsonNode semantic = PROVIDER.semantic;
        assertThat(semantic).isNotNull();
        assertThat(completion.accepted()).isTrue();
        assertThat(completion.state()).isEqualTo("COMPLETED");
        assertThat(completion.outcome()).isEqualTo("COMPLETE");
        for (String token : List.of(pair.first(), pair.second())) {
            assertThat(
                            invitations
                                    .getMemberInvitationDetail(token, key, UUID.randomUUID())
                                    .submissionState())
                    .isEqualTo("NONE");
        }
        assertThat(
                        db.queryForMap(
                                "SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", jobId))
                .isEqualTo(originalClock);
        assertThat(
                        db.queryForMap(
                                "SELECT state,call_count,result_hash FROM grade_job WHERE id=?",
                                jobId))
                .containsEntry("state", "COMPLETED")
                .containsEntry("call_count", 1);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM test_report WHERE report_key=?",
                                String.class,
                                proposed))
                .isEqualTo("GRADED");
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
                                "SELECT count(*) FROM grade_event WHERE job_id=? AND"
                                        + " event_kind='COMPLETE_APPLIED' AND actor_kind='WORKER'",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_audit WHERE scope_key=?"
                                        + " AND action='GRADE_COMPLETE' AND actor_kind='WORKER'"
                                        + " AND business_result='GRADED'",
                                Integer.class,
                                jobKey.toString()))
                .isEqualTo(1);
        byte[] output =
                db.queryForObject(
                        "SELECT output_cipher FROM grade_attempt WHERE job_id=?"
                                + " AND attempt_no=1",
                        byte[].class,
                        jobId);
        byte[] result =
                db.queryForObject(
                        "SELECT result_cipher FROM grade_job WHERE id=?", byte[].class, jobId);
        assertThat(new String(output, StandardCharsets.UTF_8))
                .doesNotContain("rubricCode", "claims");
        assertThat(new String(result, StandardCharsets.UTF_8))
                .doesNotContain("baseResult", "items");
        assertThat(
                        SnapshotJson.parse(
                                crypto.decrypt(
                                                new String(output, StandardCharsets.UTF_8),
                                                "grade_attempt/" + jobId + "/1/output/v1")
                                        .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(SnapshotJson.parse(JSON.writeValueAsBytes(semantic)));
        JsonNode base =
                SnapshotJson.parse(
                                crypto.decrypt(
                                                new String(result, StandardCharsets.UTF_8),
                                                "grade_job/" + jobId + "/result/v1")
                                        .getBytes(StandardCharsets.UTF_8))
                        .path("baseResult");
        assertThat(base.path("success").asBoolean()).isTrue();
        assertThat(
                        db.queryForMap(
                                "SELECT attempt_count,wrong_count FROM play_test WHERE test_key=?",
                                key))
                .containsEntry("attempt_count", 1)
                .containsEntry("wrong_count", 0);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM play_test WHERE test_key=?", String.class, key))
                .isEqualTo("ENDED");
        assertThat(reports.result(pair.first(), key, UUID.randomUUID()).resultPhase())
                .isEqualTo("AWAITING_FEEDBACK");
        assertThat(reports.result(pair.first(), key, UUID.randomUUID()).reports()).isNull();
        reports.feedback(pair.first(), key, feedback("본인"), UUID.randomUUID(), UUID.randomUUID());
        var self = reports.result(pair.first(), key, UUID.randomUUID());
        assertThat(self.resultPhase()).isEqualTo("AVAILABLE");
        assertThat((List<?>) self.reports()).hasSize(1);
        assertThat(self.finalScore()).isEqualTo(base.path("baseScore").intValue());
        assertThat(reports.result(pair.second(), key, UUID.randomUUID()).resultPhase())
                .isEqualTo("AWAITING_FEEDBACK");
        assertThat(reports.result(pair.second(), key, UUID.randomUUID()).reports()).isNull();
        reports.feedback(pair.second(), key, feedback("상대"), UUID.randomUUID(), UUID.randomUUID());
        assertThat(reports.result(pair.second(), key, UUID.randomUUID()).resultPhase())
                .isEqualTo("AVAILABLE");
        assertThat(self.toString()).doesNotContain("합성 장애 본인");
        assertThat(reports.result(pair.first(), key, UUID.randomUUID()).toString())
                .doesNotContain("합성 장애 상대");
        assertThat(reports.result(pair.second(), key, UUID.randomUUID()).toString())
                .doesNotContain("합성 장애 본인");
        assertThat(
                        db.queryForObject(
                                "SELECT call_count FROM grade_job WHERE id=?",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
    }

    /** 실제 두 회원이 접수한 미예약 작업만 원래 마감 이후 시스템 복구로 기술 종료한다. */
    @Test
    void acceptedQueueExpiresWithoutInventingAnAttempt() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        reports.edit(
                pair.first(),
                key,
                rev(key),
                draftRev(key),
                report(key, "만료"),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                pair.first(), key, rev(key), draftRev(key), UUID.randomUUID(), UUID.randomUUID());
        UUID proposed =
                reports.report(pair.second(), key, UUID.randomUUID()).proposal().reportKey();
        investigation.heartbeat(pair.first(), key, UUID.randomUUID());
        investigation.heartbeat(pair.second(), key, UUID.randomUUID());
        var accepted =
                reports.respond(
                        pair.second(),
                        key,
                        proposed,
                        rev(key),
                        "ACCEPT",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        UUID jobKey = UUID.fromString((String) accepted.original().get("jobKey"));
        long jobId =
                db.queryForObject("SELECT id FROM grade_job WHERE job_key=?", Long.class, jobKey);
        assertThat(
                        db.queryForMap(
                                "SELECT state,call_count,lease_gen FROM grade_job WHERE id=?",
                                jobId))
                .containsEntry("state", "QUEUED")
                .containsEntry("call_count", 0)
                .containsEntry("lease_gen", 0L);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=?",
                                Integer.class,
                                jobId))
                .isZero();
        // V22는 접수와 작업 마감을 불변으로 고정하므로 실제 DB 시계가 만료할 때까지 유한 대기한다.
        OffsetDateTime deadline =
                db.queryForObject(
                        "SELECT deadline_at FROM grade_job WHERE id=?",
                        OffsetDateTime.class,
                        jobId);
        long bound = System.nanoTime() + Duration.ofSeconds(125).toNanos();
        while (Boolean.FALSE.equals(
                        db.queryForObject("SELECT clock_timestamp()>=?", Boolean.class, deadline))
                && System.nanoTime() < bound) Thread.sleep(200);
        assertThat(
                        db.queryForObject(
                                "SELECT clock_timestamp()>=deadline_at FROM grade_job"
                                        + " WHERE id=?",
                                Boolean.class,
                                jobId))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT j.deadline_at=r.accepted_at+interval '120 seconds' AND"
                                        + " j.accepted_at=r.accepted_at FROM grade_job j JOIN"
                                        + " test_report r ON r.id=j.report_id WHERE j.id=?",
                                Boolean.class,
                                jobId))
                .isTrue();
        var originalClock =
                db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", jobId);
        var expiredWorker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        assertThat(
                        new GradeLeaseRepository(
                                        db, new DataSourceTransactionManager(db.getDataSource()))
                                .claimTest(expiredWorker.workerKey(), "H5_SYNTHETIC"))
                .isEmpty();
        var recovered = assembly.testRecovery().recoverExpiredTestQueue(20);
        assertThat(recovered)
                .anySatisfy(
                        result -> {
                            assertThat(result.jobKey()).isEqualTo(jobKey);
                            assertThat(result.changed()).isTrue();
                            assertThat(result.state()).isEqualTo("FAILED");
                            assertThat(result.attemptNo()).isNull();
                            assertThat(result.reason()).isEqualTo("DEADLINE_EXCEEDED");
                        });
        assertThat(assembly.testRecovery().recover(jobKey).changed()).isFalse();
        // Actual DB expiry remains authoritative; the real consumer cannot reserve or dispatch it.
        int beforeChats = PROVIDER.chats;
        Path root =
                Files.createTempDirectory(
                                "h5-expired-worker-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        Scope scope = new Scope(SERVER_SCOPE, WORKER, CommonUtil.sha256(SECRET));
        var transport = new AssemblyTransport(scope);
        try {
            var profiles = profiles(root);
            Path descriptor = journal(root, scope);
            try (var owner =
                    new GradeRemoteBatchWorker(
                            GradeRemoteOnceJournal.open(descriptor.toString()),
                            transport,
                            new GradeRemoteBatchWorker.TransportBounds(
                                    8 * 1024 * 1024, Duration.ofSeconds(10)),
                            profiles)) {
                assertThat(owner.pollAndRunOnce("H5_SYNTHETIC")).isNull();
            }
        } finally {
            Files.deleteIfExists(root.resolve("profiles.json"));
            Files.deleteIfExists(root.resolve("descriptor.json"));
            Files.deleteIfExists(root.resolve("journal.bin"));
            Files.deleteIfExists(root.resolve("owner.lock"));
            Files.deleteIfExists(root);
        }
        assertThat(transport.polls).isEqualTo(1);
        assertThat(transport.starts).isZero();
        assertThat(transport.fences).isZero();
        assertThat(transport.renewals).isZero();
        assertThat(transport.completions).isZero();
        assertThat(PROVIDER.chats).isEqualTo(beforeChats);
        assertThat(
                        db.queryForMap(
                                "SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", jobId))
                .isEqualTo(originalClock);
        assertThat(
                        db.queryForMap(
                                "SELECT attempt_count,wrong_count FROM play_test WHERE test_key=?",
                                key))
                .containsEntry("attempt_count", 0)
                .containsEntry("wrong_count", 0);
        assertThat(
                        db.queryForMap(
                                "SELECT state,call_count,error_code FROM grade_job WHERE id=?",
                                jobId))
                .containsEntry("state", "FAILED")
                .containsEntry("call_count", 0)
                .containsEntry("error_code", "DEADLINE_EXCEEDED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=?",
                                Integer.class,
                                jobId))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM test_report WHERE report_key=?",
                                String.class,
                                proposed))
                .isEqualTo("UNGRADABLE");
        assertThat(
                        db.queryForObject(
                                "SELECT state='ENDED' AND outcome='SYSTEM_ERROR' AND"
                                    + " result_until=ended_at+interval '24 hours' FROM play_test"
                                    + " WHERE test_key=?",
                                Boolean.class,
                                key))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_event WHERE job_id=? AND"
                                    + " event_kind='JOB_DEADLINE_EXPIRED' AND attempt_no IS NULL",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_audit WHERE scope_key=? AND"
                                    + " action='TEST_GRADE_RECOVERY' AND actor_kind='SYSTEM' AND"
                                    + " business_result='UNGRADABLE'",
                                Integer.class,
                                jobKey.toString()))
                .isEqualTo(1);
        assertThat(reports.result(pair.first(), key, UUID.randomUUID()).resultPhase())
                .isEqualTo("AWAITING_FEEDBACK");
        reports.feedback(pair.first(), key, feedback("만료"), UUID.randomUUID(), UUID.randomUUID());
        assertThat(reports.result(pair.first(), key, UUID.randomUUID()).resultPhase())
                .isEqualTo("UNAVAILABLE");
    }

    /** 실제 접수 후 세 번의 오류 완료만 호출 예산을 소모하며 접수 당시 120초를 재설정하지 않는다. */
    @Test
    void acceptedReportUsesThreeActualAttemptsWithinOriginalBudget() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        reports.edit(
                pair.first(),
                key,
                rev(key),
                draftRev(key),
                report(key, "세 시도"),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                pair.first(), key, rev(key), draftRev(key), UUID.randomUUID(), UUID.randomUUID());
        UUID proposed =
                reports.report(pair.second(), key, UUID.randomUUID()).proposal().reportKey();
        investigation.heartbeat(pair.first(), key, UUID.randomUUID());
        investigation.heartbeat(pair.second(), key, UUID.randomUUID());
        var accepted =
                reports.respond(
                        pair.second(),
                        key,
                        proposed,
                        rev(key),
                        "ACCEPT",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        UUID jobKey = UUID.fromString((String) accepted.original().get("jobKey"));
        long jobId =
                db.queryForObject("SELECT id FROM grade_job WHERE job_key=?", Long.class, jobKey);
        var originalClock =
                db.queryForMap("SELECT accepted_at,deadline_at FROM grade_job WHERE id=?", jobId);
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        for (int number = 1; number <= 3; number++) {
            var lease = leases.claimTest(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
            assertThat(lease.jobKey()).isEqualTo(jobKey);
            var started = assembly.start().startApproved(worker, jobKey, lease.leaseGen());
            assertThat(started.disposition()).isEqualTo("NEW");
            assertThat(started.attemptNo()).isEqualTo(number);
            var tuple =
                    new AttemptRequest(
                            lease.leaseGen(),
                            number,
                            started.toJson().path("input").path("originalAttemptHash").asText());
            assertThat(
                            assembly.start()
                                    .revalidate(worker, jobKey, tuple)
                                    .toJson()
                                    .path("originalAttemptHash")
                                    .asText())
                    .isEqualTo(tuple.originalAttemptHash());
            var done =
                    assembly.completion()
                            .complete(
                                    worker,
                                    jobKey,
                                    lease.leaseGen(),
                                    number,
                                    null,
                                    null,
                                    "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                                    UUID.randomUUID());
            assertThat(done.accepted()).isTrue();
            assertThat(done.retryScheduled()).isEqualTo(number < 3);
            assertThat(done.state()).isEqualTo(number < 3 ? "QUEUED" : "FAILED");
            assertThat(
                            db.queryForMap(
                                    "SELECT accepted_at,deadline_at FROM grade_job WHERE id=?",
                                    jobId))
                    .isEqualTo(originalClock);
            assertThat(
                            db.queryForObject(
                                    "SELECT call_count FROM grade_job WHERE id=?",
                                    Integer.class,
                                    jobId))
                    .isEqualTo(number);
        }
        assertThat(leases.claimTest(worker.workerKey(), "H5_SYNTHETIC")).isEmpty();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=?"
                                        + " AND state='FAILED' AND error_code='ENGINE_TIMEOUT'"
                                        + " AND completion_data IS NOT NULL",
                                Integer.class,
                                jobId))
                .isEqualTo(3);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM test_report WHERE report_key=?",
                                String.class,
                                proposed))
                .isEqualTo("UNGRADABLE");
        assertThat(
                        db.queryForObject(
                                "SELECT state='ENDED' AND outcome='SYSTEM_ERROR' AND"
                                    + " result_until=ended_at+interval '24 hours' FROM play_test"
                                    + " WHERE test_key=?",
                                Boolean.class,
                                key))
                .isTrue();
    }

    private UUID accepted(Pair pair) throws Exception {
        reports.edit(
                pair.first(),
                pair.key(),
                rev(pair.key()),
                draftRev(pair.key()),
                supportedReport(pair.key()),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                pair.first(),
                pair.key(),
                rev(pair.key()),
                draftRev(pair.key()),
                UUID.randomUUID(),
                UUID.randomUUID());
        UUID proposal =
                reports.report(pair.second(), pair.key(), UUID.randomUUID()).proposal().reportKey();
        investigation.heartbeat(pair.first(), pair.key(), UUID.randomUUID());
        investigation.heartbeat(pair.second(), pair.key(), UUID.randomUUID());
        return UUID.fromString(
                (String)
                        reports.respond(
                                        pair.second(),
                                        pair.key(),
                                        proposal,
                                        rev(pair.key()),
                                        "ACCEPT",
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .original()
                                .get("jobKey"));
    }

    /** ACCEPT 뒤 양쪽 참가자의 LOCAL 증명·현재 정책·소유권 회수는 dispatch와 완료 모두 차단한다. */
    @ParameterizedTest
    @CsvSource({
        "FIRST,START,IDENTITY", "SECOND,START,IDENTITY",
        "FIRST,COMPLETE,IDENTITY", "SECOND,COMPLETE,IDENTITY",
        "FIRST,START,ACCOUNT", "SECOND,START,ACCOUNT",
        "FIRST,COMPLETE,ACCOUNT", "SECOND,COMPLETE,ACCOUNT",
        "FIRST,START,POLICY", "SECOND,START,POLICY",
        "FIRST,COMPLETE,POLICY", "SECOND,COMPLETE,POLICY",
        "FIRST,START,PINNED", "SECOND,START,PINNED",
        "FIRST,COMPLETE,PINNED", "SECOND,COMPLETE,PINNED",
        "FIRST,START,OWNER", "SECOND,START,OWNER",
        "FIRST,COMPLETE,OWNER", "SECOND,COMPLETE,OWNER"
    })
    void participantAuthorityRevokedAfterAcceptance(
            String participant, String boundary, String defect) throws Exception {
        Pair pair = running();
        long member =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?",
                        Long.class,
                        invitations
                                .getMemberIdentity(
                                        participant.equals("FIRST") ? pair.first() : pair.second(),
                                        UUID.randomUUID())
                                .memberKey());
        UUID key = accepted(pair);
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        var lease = leases.claimTest(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
        if (boundary.equals("COMPLETE"))
            assembly.start().startApproved(worker, key, lease.leaseGen());
        switch (defect) {
            case "IDENTITY" ->
                    db.update(
                            "UPDATE member_identity SET active_yn=false WHERE member_id=? AND"
                                    + " provider='LOCAL' AND realm='LOCAL'",
                            member);
            case "ACCOUNT" ->
                    db.update("UPDATE member_account SET state='BLOCKED' WHERE id=?", member);
            case "POLICY" ->
                    db.update(
                            "UPDATE privacy_policy SET state='SUSPENDED' WHERE id=(SELECT policy_id"
                                    + " FROM member_profile WHERE member_id=?)",
                            member);
            case "PINNED" -> replaceCurrentPolicyPreservingRevokedOriginalConsent(member);
            case "OWNER" ->
                    db.update(
                            "UPDATE admin_account SET can_manage=false WHERE id=(SELECT p.owner_id"
                                + " FROM privacy_policy p JOIN member_profile m ON m.policy_id=p.id"
                                + " WHERE m.member_id=?)",
                            member);
            default -> throw new IllegalArgumentException(defect);
        }
        if (boundary.equals("START")) {
            assertThatThrownBy(() -> assembly.start().startApproved(worker, key, lease.leaseGen()))
                    .hasMessage("REMOTE_EXECUTION_NOT_CURRENT");
            assertThat(
                            db.queryForObject(
                                    "SELECT call_count FROM grade_job WHERE job_key=?",
                                    Integer.class,
                                    key))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM grade_attempt WHERE job_id=(SELECT id"
                                            + " FROM grade_job WHERE job_key=?)",
                                    Integer.class,
                                    key))
                    .isZero();
        } else {
            var receipt =
                    assembly.completion()
                            .complete(
                                    worker,
                                    key,
                                    lease.leaseGen(),
                                    1,
                                    null,
                                    null,
                                    "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}",
                                    UUID.randomUUID());
            assertThat(receipt.accepted()).isFalse();
            assertThat(receipt.reason()).isEqualTo("SOURCE_REVOKED");
            assertThat(
                            db.queryForObject(
                                    "SELECT completion_data IS NULL AND state='RUNNING' FROM"
                                        + " grade_attempt WHERE job_id=(SELECT id FROM grade_job"
                                        + " WHERE job_key=?)",
                                    Boolean.class,
                                    key))
                    .isTrue();
        }
        assertThat(
                        db.queryForObject(
                                "SELECT result_cipher IS NULL FROM grade_job WHERE job_key=?",
                                Boolean.class,
                                key))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT attempt_count FROM play_test WHERE test_key=?",
                                Integer.class,
                                pair.key()))
                .isZero();
    }

    /** 실제 signup 동의를 그대로 두고 새 ACTIVE 정책의 독립 파일 증거만 설치한다. */
    private void replaceCurrentPolicyPreservingRevokedOriginalConsent(long member)
            throws Exception {
        long original =
                db.queryForObject(
                        "SELECT policy_id FROM member_profile WHERE member_id=?",
                        Long.class,
                        member);
        var profile =
                db.queryForMap(
                        "SELECT policy_id,accepted_at,created_at FROM member_profile WHERE"
                                + " member_id=?",
                        member);
        ObjectNode document =
                (ObjectNode)
                        JSON.readTree(
                                db.queryForObject(
                                        "SELECT policy_data::text FROM privacy_policy WHERE id=?",
                                        String.class,
                                        original));
        String code = "H5_CURRENT_" + UUID.randomUUID().toString().replace("-", "");
        String hash = MemberPolicyGate.noticeHash(code, document.path("notice"));
        ObjectNode manifest =
                (ObjectNode)
                        JSON.readTree(
                                Files.readAllBytes(
                                        Path.of(memberConfiguration.getEvidenceRegistryFile())));
        manifest.put("noticeHash", hash);
        for (JsonNode row : manifest.path("records")) {
            ObjectNode record = (ObjectNode) row;
            ObjectNode artifact =
                    (ObjectNode)
                            JSON.readTree(
                                    Files.readAllBytes(Path.of(record.path("path").textValue())));
            artifact.put("noticeHash", hash);
            byte[] bytes = JSON.writeValueAsBytes(artifact);
            Path path =
                    Files.createTempFile(
                            "h5-current-policy-",
                            ".json",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            path.toFile().deleteOnExit();
            Files.write(path, bytes);
            path = path.toRealPath();
            String digest =
                    com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry.sha256(bytes);
            record.put("path", path.toString()).put("sha256", digest);
            ((ObjectNode) document.path("evidence").path(record.path("kind").textValue()))
                    .put("sha256", digest);
        }
        long replacement =
                db.queryForObject(
                        "INSERT INTO"
                            + " privacy_policy(code,env_code,scope,state,notice_hash,owner_id,policy_data)"
                            + " SELECT ?,env_code,scope,'DRAFT',?,owner_id,?::jsonb FROM"
                            + " privacy_policy WHERE id=? RETURNING id",
                        Long.class,
                        code,
                        hash,
                        document.toString(),
                        original);
        db.update("UPDATE privacy_policy SET state='SUSPENDED' WHERE id=?", original);
        db.update("UPDATE privacy_policy SET state='ACTIVE' WHERE id=?", replacement);
        Path registry =
                Files.createTempFile(
                        "h5-current-manifest-",
                        ".json",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        registry.toFile().deleteOnExit();
        Files.write(registry, JSON.writeValueAsBytes(manifest));
        memberConfiguration.setEvidenceRegistryFile(registry.toRealPath().toString());
        var evidence = memberPolicyGate.prepare();
        var permit =
                new org.springframework.transaction.support.TransactionTemplate(
                                new DataSourceTransactionManager(db.getDataSource()))
                        .execute(status -> memberPolicyGate.lock(evidence, null));
        assertThat(permit.current().id()).isEqualTo(replacement);
        memberPolicyGate.check(permit);
        assertThat(
                        db.queryForMap(
                                "SELECT policy_id,accepted_at,created_at FROM member_profile WHERE"
                                        + " member_id=?",
                                member))
                .isEqualTo(profile);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM privacy_policy WHERE id=?",
                                String.class,
                                original))
                .isEqualTo("SUSPENDED");
    }

    /** 회수된 실제 LOCAL 부모도 역사 정산으로 fence를 풀며 채점·시도·정상 예산을 만들지 않는다. */
    @ParameterizedTest
    @CsvSource({"FIRST", "SECOND"})
    void revokedParticipantLeaseRecoverySettlesWithoutInventingAttempt(String participant)
            throws Exception {
        Pair pair = running();
        long member =
                db.queryForObject(
                        "SELECT id FROM member_account WHERE member_key=?",
                        Long.class,
                        invitations
                                .getMemberIdentity(
                                        participant.equals("FIRST") ? pair.first() : pair.second(),
                                        UUID.randomUUID())
                                .memberKey());
        UUID key = accepted(pair);
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        var lease = leases.claimTest(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(key);
        var pins =
                db.queryForMap(
                        "SELECT accepted_at,deadline_at,call_count,lease_gen,report_id FROM"
                                + " grade_job WHERE job_key=?",
                        key);
        db.update(
                "UPDATE member_identity SET active_yn=false WHERE member_id=? AND provider='LOCAL'"
                        + " AND realm='LOCAL'",
                member);
        assertThatThrownBy(() -> assembly.start().startApproved(worker, key, lease.leaseGen()))
                .hasMessage("REMOTE_EXECUTION_NOT_CURRENT");
        db.update("UPDATE grade_job SET lease_until=clock_timestamp() WHERE job_key=?", key);
        var recovered = assembly.testRecovery().recover(key);
        assertThat(recovered.changed()).isTrue();
        assertThat(recovered.reason()).isEqualTo("SOURCE_REVOKED");
        assertThat(recovered.attemptNo()).isNull();
        assertThat(recovered.state()).isEqualTo("FAILED");
        assertThat(
                        db.queryForMap(
                                "SELECT accepted_at,deadline_at,call_count,lease_gen,report_id FROM"
                                        + " grade_job WHERE job_key=?",
                                key))
                .isEqualTo(pins);
        assertThat(
                        db.queryForObject(
                                "SELECT worker_key IS NULL AND lease_until IS NULL AND"
                                        + " result_cipher IS NULL AND result_hash IS NULL FROM"
                                        + " grade_job WHERE job_key=?",
                                Boolean.class,
                                key))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=(SELECT id FROM"
                                        + " grade_job WHERE job_key=?)",
                                Integer.class,
                                key))
                .isZero();
        assertThat(
                        db.queryForMap(
                                "SELECT attempt_count,wrong_count,outcome FROM play_test WHERE"
                                        + " test_key=?",
                                pair.key()))
                .containsEntry("attempt_count", 0)
                .containsEntry("wrong_count", 0)
                .containsEntry("outcome", "SYSTEM_ERROR");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM test_report WHERE id=(SELECT report_id FROM"
                                        + " grade_job WHERE job_key=?)",
                                String.class,
                                key))
                .isEqualTo("UNGRADABLE");
        assertThat(assembly.testRecovery().recover(key).changed()).isFalse();
        Pair next = running();
        UUID nextKey = accepted(next);
        assertThat(leases.claimTest(worker.workerKey(), "H5_SYNTHETIC").orElseThrow().jobKey())
                .isEqualTo(nextKey);
    }

    /** 명시 runtime epoch 회수는 출처 회수와 구분하며 stale fencing과 이미 저장한 명령은 우선한다. */
    @Test
    void testEpochChangeHasExplicitReasonAndPreservesStaleAndAcceptedReplay() throws Exception {
        Pair pair = running();
        UUID key = accepted(pair);
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var lease =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()))
                        .claimTest(worker.workerKey(), "H5_SYNTHETIC")
                        .orElseThrow();
        assembly.start().startApproved(worker, key, lease.leaseGen());
        db.update(
                "UPDATE grade_runtime SET epoch=epoch+1 WHERE id=(SELECT runtime_id FROM grade_job"
                        + " WHERE job_key=?)",
                key);
        String command = "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}";
        var stale =
                assembly.completion()
                        .complete(
                                worker,
                                key,
                                lease.leaseGen() + 1,
                                1,
                                null,
                                null,
                                command,
                                UUID.randomUUID());
        assertThat(stale.reason()).isEqualTo("STALE_LEASE");
        var changed =
                assembly.completion()
                        .complete(
                                worker,
                                key,
                                lease.leaseGen(),
                                1,
                                null,
                                null,
                                command,
                                UUID.randomUUID());
        assertThat(changed.accepted()).isFalse();
        assertThat(changed.reason()).isEqualTo("RUNTIME_EPOCH_CHANGED");
        db.update(
                "UPDATE grade_runtime SET epoch=epoch-1 WHERE id=(SELECT runtime_id FROM grade_job"
                        + " WHERE job_key=?)",
                key);
        var original =
                assembly.completion()
                        .complete(
                                worker,
                                key,
                                lease.leaseGen(),
                                1,
                                null,
                                null,
                                command,
                                UUID.randomUUID());
        assertThat(original.accepted()).isTrue();
        db.update(
                "UPDATE grade_runtime SET epoch=epoch+1 WHERE id=(SELECT runtime_id FROM grade_job"
                        + " WHERE job_key=?)",
                key);
        assertThat(
                        assembly.completion()
                                .complete(
                                        worker,
                                        key,
                                        lease.leaseGen(),
                                        1,
                                        null,
                                        null,
                                        command,
                                        UUID.randomUUID()))
                .isEqualTo(original);
    }

    /** 실제 HTTP 경계는 비참가자와 숫자 수정번호를 거절하고 비저장 공동 초안만 투영한다. */
    @Test
    void reportHttpRequiresParticipantAndCanonicalRevision() throws Exception {
        Pair pair = running();
        String path = "/api/playtests/" + pair.key() + "/report";
        JsonNode draft = report(pair.key(), "HTTP");
        mvc.perform(get(path).secure(true).header("Authorization", "Bearer " + pair.outsider()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        patch(path)
                                .secure(true)
                                .header("Authorization", "Bearer " + pair.first())
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                Map.of(
                                                        "expectedRev",
                                                        rev(pair.key()),
                                                        "expectedDraftRev",
                                                        "0",
                                                        "report",
                                                        draft,
                                                        "requestKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isBadRequest());
        assertThat(draftRev(pair.key())).isZero();
        var saved =
                mvc.perform(
                                patch(path)
                                        .secure(true)
                                        .header("Authorization", "Bearer " + pair.first())
                                        .contentType("application/json")
                                        .content(
                                                JSON.writeValueAsBytes(
                                                        Map.of(
                                                                "expectedRev",
                                                                Long.toString(rev(pair.key())),
                                                                "expectedDraftRev",
                                                                "0",
                                                                "report",
                                                                draft,
                                                                "requestKey",
                                                                UUID.randomUUID().toString()))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        var viewed =
                mvc.perform(
                                get(path)
                                        .secure(true)
                                        .header("Authorization", "Bearer " + pair.second()))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(viewed.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(JSON.readTree(viewed.getResponse().getContentAsByteArray()).path("report"))
                .isEqualTo(draft);
        assertThat(JSON.readTree(viewed.getResponse().getContentAsByteArray()).toString())
                .doesNotContain("feedbackCipher", "payloadCipher", "gradeSamples");
    }

    /** 허용되지 않은 원문·서버 저장 암호문 훼손은 원문이나 접수 근거를 공개하지 않는다. */
    @Test
    void invalidReportAndCipherTamperFailClosed() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        JsonNode invalid = report(key, "원본").deepCopy();
        ((ObjectNode) invalid).put("culpritCode", "UNREGISTERED_PERSON");
        reports.edit(
                pair.first(),
                key,
                rev(key),
                draftRev(key),
                invalid,
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                reports.propose(
                                        pair.first(),
                                        key,
                                        rev(key),
                                        draftRev(key),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("INVALID_REPORT");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_report WHERE test_id="
                                        + "(SELECT id FROM play_test WHERE test_key=?)",
                                Integer.class,
                                key))
                .isZero();
        reports.edit(
                pair.first(),
                key,
                rev(key),
                draftRev(key),
                report(key, "정상"),
                UUID.randomUUID(),
                UUID.randomUUID());
        db.update(
                "UPDATE play_test SET draft_cipher=? WHERE test_key=?",
                "corrupt-ciphertext-with-valid-column-length-0000"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                key);
        assertThatThrownBy(() -> reports.report(pair.second(), key, UUID.randomUUID()))
                .hasMessageContaining("AUTH_UNAVAILABLE");
        assertThatThrownBy(
                        () ->
                                reports.propose(
                                        pair.first(),
                                        key,
                                        rev(key),
                                        draftRev(key),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("AUTH_UNAVAILABLE");
    }

    /** 유효한 다른 행의 GCM 봉투라도 행 결속 AAD가 달라 공동 초안에 이식할 수 없다. */
    @Test
    void authenticCipherFromAnotherPlayCannotBeDecrypted() throws Exception {
        Pair source = running();
        Pair target = running();
        reports.edit(
                source.first(),
                source.key(),
                rev(source.key()),
                draftRev(source.key()),
                report(source.key(), "출처"),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.edit(
                target.first(),
                target.key(),
                rev(target.key()),
                draftRev(target.key()),
                report(target.key(), "대상"),
                UUID.randomUUID(),
                UUID.randomUUID());
        byte[] authentic =
                db.queryForObject(
                        "SELECT draft_cipher FROM play_test WHERE test_key=?",
                        byte[].class,
                        source.key());
        db.update("UPDATE play_test SET draft_cipher=? WHERE test_key=?", authentic, target.key());
        assertThatThrownBy(() -> reports.report(target.second(), target.key(), UUID.randomUUID()))
                .hasMessageContaining("AUTH_UNAVAILABLE");
        assertThat(reports.report(source.second(), source.key(), UUID.randomUUID()).report())
                .isEqualTo(report(source.key(), "출처"));
    }

    /** 현재 REVIEW 루트의 철회와 실행 세대 변경은 이미 발급된 수정 키보다 우선한다. */
    @Test
    void currentRootAndRuntimeEpochBlockReportReplay() throws Exception {
        Pair root = running();
        UUID intent = UUID.randomUUID();
        JsonNode draft = report(root.key(), "원본");
        long original = rev(root.key());
        reports.edit(root.first(), root.key(), original, 0, draft, intent, UUID.randomUUID());
        db.update(
                "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL WHERE id="
                        + "(SELECT version_id FROM play_test WHERE test_key=?)",
                root.key());
        assertThatThrownBy(
                        () ->
                                reports.edit(
                                        root.first(),
                                        root.key(),
                                        original,
                                        0,
                                        draft,
                                        intent,
                                        UUID.randomUUID()))
                .hasMessageContaining("INVITATION_INVALIDATED");
        assertThatThrownBy(() -> reports.report(root.second(), root.key(), UUID.randomUUID()))
                .hasMessageContaining("INVITATION_INVALIDATED");

        Pair epoch = running();
        db.update(
                "UPDATE grade_runtime SET epoch=epoch+1 WHERE id="
                        + "(SELECT runtime_id FROM play_test WHERE test_key=?)",
                epoch.key());
        assertThatThrownBy(
                        () ->
                                reports.edit(
                                        epoch.first(),
                                        epoch.key(),
                                        rev(epoch.key()),
                                        0,
                                        report(epoch.key(), "세대 변경"),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("INVITATION_INVALIDATED");
        assertThat(
                        db.queryForObject(
                                "SELECT draft_cipher IS NULL FROM play_test WHERE test_key=?",
                                Boolean.class,
                                epoch.key()))
                .isTrue();
    }

    /** 회원 세션을 회수하면 접수 이전의 초안과 요청 영수증을 모두 읽을 수 없다. */
    @Test
    void revokedMemberSessionCannotReadOrReplayDraft() throws Exception {
        Pair pair = running();
        UUID intent = UUID.randomUUID();
        JsonNode draft = report(pair.key(), "회수");
        long original = rev(pair.key());
        reports.edit(pair.first(), pair.key(), original, 0, draft, intent, UUID.randomUUID());
        service.logout(pair.first(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> reports.report(pair.first(), pair.key(), UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
        assertThatThrownBy(
                        () ->
                                reports.edit(
                                        pair.first(),
                                        pair.key(),
                                        original,
                                        0,
                                        draft,
                                        intent,
                                        UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
        assertThat(reports.report(pair.second(), pair.key(), UUID.randomUUID()).report())
                .isEqualTo(draft);
    }

    /** 종료 피드백은 각자 한 번만 저장되고 상대 내용 및 결과 조회에서는 노출되지 않는다. */
    @Test
    void forfeitFeedbackOwnOnlyAndExactTwentyFourHourWindow() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        reports.forfeit(pair.first(), key, rev(key), UUID.randomUUID(), UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT result_until=ended_at+interval '24 hours'"
                                        + " FROM play_test WHERE test_key=?",
                                Boolean.class,
                                key))
                .isTrue();
        assertThat(reports.result(pair.first(), key, UUID.randomUUID()).feedbackSubmitted())
                .isFalse();
        UUID intent = UUID.randomUUID();
        assertThat(
                        reports.feedback(
                                        pair.first(),
                                        key,
                                        feedback("비공개"),
                                        intent,
                                        UUID.randomUUID())
                                .changed())
                .isTrue();
        assertThat(
                        reports.feedback(
                                        pair.first(),
                                        key,
                                        feedback("비공개"),
                                        intent,
                                        UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                reports.feedback(
                                        pair.first(),
                                        key,
                                        feedback("두 번째"),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("FEEDBACK_ALREADY_SUBMITTED");
        var technical = reports.result(pair.first(), key, UUID.randomUUID());
        assertThat(technical.feedbackSubmitted()).isTrue();
        assertThat(technical.resultPhase()).isEqualTo("UNAVAILABLE");
        assertThat(technical.revealText()).isNull();
        assertThat(technical.finalScore()).isNull();
        assertThat(reports.result(pair.second(), key, UUID.randomUUID()).feedbackSubmitted())
                .isFalse();
        assertThat(reports.result(pair.second(), key, UUID.randomUUID()).toString())
                .doesNotContain("합성 장애 비공개");
        byte[] cipher =
                db.queryForObject(
                        "SELECT m.feedback_cipher FROM test_member m JOIN play_test t ON"
                                + " t.id=m.test_id JOIN member_account a ON a.id=m.member_id WHERE"
                                + " t.test_key=? AND a.member_key=?",
                        byte[].class,
                        key,
                        invitations.getMemberIdentity(pair.first(), UUID.randomUUID()).memberKey());
        assertThat(new String(cipher, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("합성 장애 비공개");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_member m JOIN play_test t ON"
                                    + " t.id=m.test_id WHERE t.test_key=? AND m.feedback_at IS NOT"
                                    + " NULL",
                                Integer.class,
                                key))
                .isEqualTo(1);
    }

    /** 접수 이전 제안은 포기로 취소되며 채점 근거가 되지 않는다. */
    @Test
    void forfeitCancelsUnacceptedProposalWithoutGradeJob() throws Exception {
        Pair pair = running();
        reports.edit(
                pair.first(),
                pair.key(),
                rev(pair.key()),
                draftRev(pair.key()),
                report(pair.key(), "고정"),
                UUID.randomUUID(),
                UUID.randomUUID());
        reports.propose(
                pair.first(),
                pair.key(),
                rev(pair.key()),
                draftRev(pair.key()),
                UUID.randomUUID(),
                UUID.randomUUID());
        UUID proposed =
                reports.report(pair.second(), pair.key(), UUID.randomUUID()).proposal().reportKey();
        reports.forfeit(
                pair.first(), pair.key(), rev(pair.key()), UUID.randomUUID(), UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM test_report WHERE report_key=?",
                                String.class,
                                proposed))
                .isEqualTo("CANCELLED");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM play_test WHERE test_key=?",
                                String.class,
                                pair.key()))
                .isEqualTo("ENDED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_job WHERE report_id IN"
                                        + " (SELECT id FROM test_report WHERE report_key=?)",
                                Integer.class,
                                proposed))
                .isZero();
    }
}
