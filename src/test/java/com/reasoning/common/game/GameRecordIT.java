package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.game.service.GameRecordService;
import com.reasoning.common.game.service.PublicGameService;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.service.GradeLocalRunner;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 격리된 합성 FRIENDLY 근거로 실제 PUBLIC worker 종료와 개인 기록 소유 읽기를 검증한다. */
@SpringBootTest(
        properties = {
            "app.member-web.enabled=true",
            "app.member-web.origin=https://player.example.invalid"
        })
@Testcontainers
@Import({
    com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
    PlaytestReportFixture.TestAssembly.class
})
class GameRecordIT extends PublicGameFixture {
    @Autowired PublicGameService games;
    @Autowired GameRecordService records;
    @Autowired org.springframework.context.ApplicationContext context;

    @Override
    protected int fixtureDifficulty() {
        return 2;
    }

    /** 검수 초대를 PUBLIC으로 변환하지 않고 별도의 실제 PUBLIC 루트를 생성한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realPublicWorkerCreatesRecordsOnlyWhileSourceIsNotDeleted(boolean erasedBeforeExecution)
            throws Exception {
        playtestPolicy();
        var first = account("record-owner-" + UUID.randomUUID() + "@example.invalid");
        var other = account("record-foreign-" + UUID.randomUUID() + "@example.invalid");
        String token = first.path("accessToken").asText();
        String foreign = other.path("accessToken").asText();
        var firstPrincipal = service.authenticate(token, false);
        UUID firstKey = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        UUID otherKey = invitations.getMemberIdentity(foreign, UUID.randomUUID()).memberKey();
        UUID invitation = invitation(List.of(firstKey, otherKey));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE member_id=?",
                                Integer.class,
                                firstPrincipal.memberId()))
                .isZero();
        var notice = friendlyPolicy("합성 공개 기록 고지");
        long version =
                db.queryForObject(
                        "SELECT version_id FROM play_test WHERE test_key=?",
                        Long.class,
                        invitation);
        var source =
                db.queryForMap(
                        "SELECT"
                            + " v.story_id,t.snapshot_id,t.runtime_id,t.runtime_epoch,t.config_hash,v.difficulty,v.limit_sec"
                            + " FROM play_test t JOIN story_version v ON v.id=t.version_id WHERE"
                            + " t.test_key=?",
                        invitation);
        long snapshot = ((Number) source.get("snapshot_id")).longValue();
        var frozen =
                com.reasoning.common.story.model.FrozenSnapshotCodec.decode(
                        db.queryForObject(
                                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                                        String.class,
                                        snapshot)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int maxAttempts = frozen.payload().path("policy").path("attemptLimit").asInt();
        int maxHints = frozen.payload().path("policy").path("hintsPerPerson").asInt();
        var identity =
                db.queryForObject(
                        "SELECT id FROM member_identity WHERE member_id=? AND provider='LOCAL' AND"
                                + " active_yn",
                        Long.class,
                        firstPrincipal.memberId());
        UUID roomKey = UUID.randomUUID();
        long room =
                db.queryForObject(
                        """
                        INSERT INTO game_room(room_key,purpose,mode,story_id,version_id,snapshot_id,runtime_id,
                          runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,
                          max_attempts,max_hints,created_at,lobby_until)
                        SELECT ?,'PUBLIC','SOLO',?,?,?,?,?,?,?,?,0,'WAITING',?,?,?,?,clock.t,clock.t+interval '30 minutes'
                        FROM (SELECT clock_timestamp() t) clock
                        RETURNING id
                        """,
                        Long.class,
                        roomKey,
                        source.get("story_id"),
                        version,
                        snapshot,
                        source.get("runtime_id"),
                        source.get("runtime_epoch"),
                        source.get("config_hash"),
                        frozen.rubricHash(),
                        notice.id(),
                        source.get("difficulty"),
                        source.get("limit_sec"),
                        maxAttempts,
                        maxHints);
        db.update(
                "INSERT INTO game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at)"
                        + " VALUES (?,?,?,1,?,clock_timestamp())",
                room,
                firstPrincipal.memberId(),
                identity,
                notice.id());
        assertThatThrownBy(() -> games.result(token, roomKey, UUID.randomUUID()))
                .hasMessageContaining("RESULT_NOT_READY");
        assertThatThrownBy(() -> games.result(foreign, roomKey, UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        assertThatThrownBy(() -> games.result(token, invitation, UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        assertThat(
                        db.queryForObject(
                                "SELECT purpose FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("PUBLIC");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?",
                                Integer.class,
                                room))
                .isZero();
        // 고정 검수 사본은 PUBLIC 자격의 근거로 가장하지 않는다. 이 루트는 합성 worker/reader 검증 전용이다.
        db.update(
                "UPDATE game_member SET role_code='R1',version_id=? WHERE room_id=? AND"
                        + " member_id=?",
                version,
                room,
                firstPrincipal.memberId());
        db.update(
                "WITH t AS (SELECT clock_timestamp() n) UPDATE game_room SET"
                        + " state='RUNNING',started_at=t.n,deadline_at=t.n+limit_sec*interval '1"
                        + " second',role_a='R1',role_b='R2',rev=1 FROM t WHERE id=?",
                room);
        var original = supportedReport(invitation);
        var edit =
                games.editReport(
                        token, roomKey, 1, 0, original, UUID.randomUUID(), UUID.randomUUID());
        assertThat(edit.changed()).isTrue();
        var submit = games.submitReport(token, roomKey, 2, 1, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> games.result(token, roomKey, UUID.randomUUID()))
                .hasMessageContaining("RESULT_NOT_READY");
        UUID jobKey = UUID.fromString(((JsonNode) submit.original()).path("jobKey").asText());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=?",
                                Integer.class,
                                room))
                .isZero();
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        var lease = leases.claimGame(worker.workerKey(), "H5_SYNTHETIC").orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(jobKey);
        Path root =
                Files.createTempDirectory(
                                "public-record-worker-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        int beforeChats = PROVIDER.chats;
        try {
            var publicAssembly =
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
            if (erasedBeforeExecution) {
                db.update(
                        "UPDATE member_account SET"
                            + " state='DELETED',auth_rev=auth_rev+1,deleted_at=clock_timestamp(),updated_at=clock_timestamp()"
                            + " WHERE id=?",
                        firstPrincipal.memberId());
                db.update(
                        "UPDATE grade_job SET lease_until=clock_timestamp() WHERE job_key=?",
                        jobKey);
                publicAssembly.testRecovery().recover(jobKey);
                assertThat(
                                db.queryForObject(
                                        "SELECT state FROM grade_job WHERE job_key=?",
                                        String.class,
                                        jobKey))
                        .isEqualTo("FAILED");
                assertThat(
                                db.queryForObject(
                                        "SELECT state FROM game_room WHERE id=?",
                                        String.class,
                                        room))
                        .isEqualTo("ENDED");
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM play_record WHERE room_id=?",
                                        Integer.class,
                                        room))
                        .isZero();
                assertThat(
                                db.queryForObject(
                                        "SELECT draft_cipher IS NULL AND draft_hash IS NULL FROM"
                                                + " game_room WHERE id=?",
                                        Boolean.class,
                                        room))
                        .isTrue();
                assertThat(PROVIDER.chats).isEqualTo(beforeChats);
                assertThatThrownBy(() -> games.result(token, roomKey, UUID.randomUUID()))
                        .hasMessageContaining("MEMBER_AUTH_REQUIRED");
                return;
            }
            var runner =
                    new GradeLocalRunner(
                            credentials,
                            publicAssembly.start(),
                            publicAssembly.completion(),
                            profiles(root));
            var completion = runner.runGameOnce(worker, jobKey, lease.leaseGen());
            assertThat(completion.state()).isEqualTo(GradeLocalRunner.State.RECEIPT);
            assertThat(completion.receipt().accepted()).isTrue();
            assertThat(completion.receipt().state()).isEqualTo("COMPLETED");
            assertThat(completion.receipt().outcome()).isEqualTo("COMPLETE");
        } finally {
            Files.deleteIfExists(root.resolve("profiles.json"));
            Files.deleteIfExists(root);
        }
        assertThat(PROVIDER.chats).isEqualTo(beforeChats + 1);
        assertThat(db.queryForObject("SELECT state FROM game_room WHERE id=?", String.class, room))
                .isEqualTo("ENDED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record WHERE room_id=? AND member_id=?"
                                        + " AND state='RAW'",
                                Integer.class,
                                room,
                                firstPrincipal.memberId()))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_record p JOIN game_room r ON"
                                        + " r.id=p.room_id WHERE r.purpose<>'PUBLIC'",
                                Integer.class))
                .isZero();
        int readsBefore =
                db.queryForObject(
                        "SELECT count(*) FROM game_audit WHERE room_id=? AND action='CONTENT_READ'",
                        Integer.class,
                        room);
        var result = games.result(token, roomKey, UUID.randomUUID());
        var page = records.getRecordList(token, null, 1, UUID.randomUUID());
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items().getFirst().path("mode").asText()).isEqualTo("SOLO");
        assertThat(records.getRecordList(foreign, null, 1, UUID.randomUUID()).items()).isEmpty();
        assertThatThrownBy(() -> records.getRecordList(token, null, 101, UUID.randomUUID()))
                .hasMessageContaining("INVALID_REQUEST");
        assertThatThrownBy(() -> records.getRecordList(token, "forged", 1, UUID.randomUUID()))
                .hasMessageContaining("INVALID_CURSOR");
        var row = page.items().getFirst();
        assertThat(row.path("finalScore")).isEqualTo(result.path("finalScore"));
        assertThat(row.path("teamAttempts")).isEqualTo(result.path("teamAttempts"));
        assertThat(row.path("ownHintsUsed").intValue())
                .isEqualTo(
                        db.queryForObject(
                                "SELECT count(*) FROM game_hint WHERE room_id=? AND member_id=?",
                                Integer.class,
                                room,
                                firstPrincipal.memberId()));
        assertThat(row.path("rawState").asText()).isEqualTo("NORMAL");
        assertThat(row.has("reports")).isFalse();
        UUID recordKey = UUID.fromString(row.path("recordKey").asText());
        var detail = records.getRecordDetail(token, recordKey, UUID.randomUUID());
        var resultWithoutRequest = result.deepCopy();
        var detailWithoutRequest = detail.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) resultWithoutRequest).remove("requestId");
        ((com.fasterxml.jackson.databind.node.ObjectNode) detailWithoutRequest).remove("requestId");
        assertThat(resultWithoutRequest).isEqualTo(detailWithoutRequest);
        assertThat(result.path("gameKey").asText()).isEqualTo(roomKey.toString());
        assertThat(result.path("rawState").asText()).isEqualTo("NORMAL");
        assertThat(result.path("requestId").isTextual()).isTrue();
        assertThat(result.path("scoreSummary").isObject()).isTrue();
        assertThat(result.path("revealText").isTextual()).isTrue();
        assertThat(result.path("rawDeletedReason").isNull()).isTrue();
        var fields = new java.util.HashSet<String>();
        result.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .isEqualTo(
                        Set.of(
                                "recordKey",
                                "rawState",
                                "mode",
                                "story",
                                "selfRole",
                                "difficulty",
                                "durationSec",
                                "outcome",
                                "finalScore",
                                "teamAttempts",
                                "ownHintsUsed",
                                "timeBasisAt",
                                "startedAt",
                                "endedAt",
                                "gameKey",
                                "scoreSummary",
                                "reports",
                                "lastReportKey",
                                "revealText",
                                "rawDeletedReason",
                                "requestId"));
        assertThat(detail.path("reports")).hasSize(1);
        assertThat(detail.path("reports").get(0).path("submitNo").asInt()).isEqualTo(1);
        var reportFields = new java.util.HashSet<String>();
        detail.path("reports").get(0).fieldNames().forEachRemaining(reportFields::add);
        assertThat(reportFields)
                .isEqualTo(Set.of("reportKey", "submitNo", "state", "success", "baseScore"));
        UUID reportKey = UUID.fromString(detail.path("reports").get(0).path("reportKey").asText());
        var report = records.getReportDetail(token, recordKey, reportKey, UUID.randomUUID());
        assertThat(report.report()).isEqualTo(original);
        assertThat(report.submitNo()).isEqualTo(1);
        assertThat(report.scoreBasis().path("categories").isArray()).isTrue();
        assertThat(report.scoreBasis().path("wrongPenalty").asInt()).isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM game_audit WHERE room_id=? AND"
                                        + " action='CONTENT_READ'",
                                Integer.class,
                                room))
                .isEqualTo(readsBefore + 3);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_exposure WHERE story_id=? AND"
                                        + " member_id=?",
                                Integer.class,
                                source.get("story_id"),
                                firstPrincipal.memberId()))
                .isEqualTo(1);
        assertThatThrownBy(() -> games.result(foreign, roomKey, UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        assertThatThrownBy(() -> records.getRecordDetail(foreign, recordKey, UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                records.getReportDetail(
                                        foreign, recordKey, report.reportKey(), UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        verifyWebRecordModels(token, foreign, recordKey, reportKey);
        assertThatThrownBy(() -> db.update("DELETE FROM game_member WHERE room_id=?", room))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasStackTraceContaining("member physical deletion unauthorized");
        db.update("UPDATE member_identity SET active_yn=false WHERE id=?", identity);
        assertThatThrownBy(() -> records.getRecordDetail(token, recordKey, UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
        assertThatThrownBy(() -> games.result(token, roomKey, UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
        db.update("UPDATE member_identity SET active_yn=true WHERE id=?", identity);
        db.update(
                "UPDATE game_member SET active_yn=false WHERE room_id=? AND member_id=?",
                room,
                firstPrincipal.memberId());
        assertThatThrownBy(() -> games.result(token, roomKey, UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        assertThatThrownBy(() -> records.getRecordDetail(token, recordKey, UUID.randomUUID()))
                .hasMessageContaining("RECORD_NOT_FOUND");
        db.update("UPDATE privacy_policy SET state='RETIRED' WHERE id=?", notice.id());
        assertThatThrownBy(() -> records.getRecordList(foreign, null, 1, UUID.randomUUID()))
                .hasMessageContaining("FRIENDLY_COLLECTION_NOT_READY");
    }

    /** 실제 worker의 저장·HTTP 투영을 제품 JavaScript 파서와 대조하고 타인 원문은404로 유지한다. */
    private void verifyWebRecordModels(String token, String foreign, UUID recordKey, UUID reportKey)
            throws Exception {
        var page =
                mvc.perform(recordWeb("/api/member/records?size=20", token))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isOk())
                        .andReturn();
        var detail =
                mvc.perform(recordWeb("/api/member/records/" + recordKey, token))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isOk())
                        .andReturn();
        var report =
                mvc.perform(
                                recordWeb(
                                        "/api/member/records/"
                                                + recordKey
                                                + "/reports/"
                                                + reportKey,
                                        token))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isOk())
                        .andReturn();
        mvc.perform(recordWeb("/api/member/records/" + recordKey, foreign))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        mvc.perform(
                        recordWeb(
                                "/api/member/records/" + recordKey + "/reports/" + reportKey,
                                foreign))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        Path receipt = Files.createTempFile("record-model-check-", ".json");
        Path error = Files.createTempFile("record-model-check-", ".txt");
        Process process = null;
        try {
            process =
                    new ProcessBuilder("node", "src/test/browser/record-model-check.mjs")
                            .redirectOutput(receipt.toFile())
                            .redirectError(error.toFile())
                            .start();
            try (var input = process.getOutputStream()) {
                JSON.writeValue(
                        input,
                        Map.of(
                                "recordKey",
                                recordKey.toString(),
                                "page",
                                JSON.readTree(page.getResponse().getContentAsByteArray()),
                                "detail",
                                JSON.readTree(detail.getResponse().getContentAsByteArray()),
                                "report",
                                JSON.readTree(report.getResponse().getContentAsByteArray())));
            }
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Record model checker deadline exceeded");
            }
            assertThat(process.exitValue())
                    .withFailMessage("Record model: %s", Files.readString(error))
                    .isZero();
            assertThat(JSON.readTree(receipt.toFile()).path("ok").asBoolean()).isTrue();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(receipt);
            Files.deleteIfExists(error);
        }
    }

    /** 무쿠키·동일 원점·현재 Bearer의 실제 브라우저 요청 경계만 구성한다. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder recordWeb(
            String path, String token) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .secure(true)
                .with(
                        request -> {
                            request.setScheme("https");
                            request.setServerName("player.example.invalid");
                            request.setServerPort(443);
                            return request;
                        })
                .header("X-Sicha-Player-Web", "1")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Authorization", "Bearer " + token);
    }
}
