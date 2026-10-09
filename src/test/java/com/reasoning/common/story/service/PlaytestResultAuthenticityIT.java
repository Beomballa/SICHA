package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.service.GradeResultValidator;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

/** Actual accepted TEST callbacks, not fabricated result rows or BATCH fixtures. */
class PlaytestResultAuthenticityIT extends PlaytestReportIT {
    private record Completed(Pair pair, long testId, long jobId) {}

    private TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    }

    private Completed completed(boolean success) throws Exception {
        Pair pair = running();
        JsonNode payload = supportedReport(pair.key());
        reports.edit(
                pair.first(),
                pair.key(),
                rev(pair.key()),
                draftRev(pair.key()),
                payload,
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
        var accepted =
                reports.respond(
                        pair.second(),
                        pair.key(),
                        proposal,
                        rev(pair.key()),
                        "ACCEPT",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        UUID jobKey = UUID.fromString((String) accepted.original().get("jobKey"));
        long jobId =
                db.queryForObject("SELECT id FROM grade_job WHERE job_key=?", Long.class, jobKey);
        long testId =
                db.queryForObject(
                        "SELECT id FROM play_test WHERE test_key=?", Long.class, pair.key());
        long snapshotId =
                db.queryForObject(
                        "SELECT snapshot_id FROM play_test WHERE id=?", Long.class, testId);
        var saved =
                transaction()
                        .execute(
                                status ->
                                        new GradeFrozenInputRepository(db)
                                                .loadSavedFacts(snapshotId));
        ObjectNode semantic = JSON.createObjectNode().put("formatNo", 1).put("status", "COMPLETE");
        var items = semantic.putArray("items");
        for (var coordinate : GradeResultValidator.coordinates(saved.dataset().gradingSnapshot())) {
            var item =
                    items.addObject()
                            .put("rubricCode", coordinate.rubricCode())
                            .put("reason", "고정 의미 판정");
            var claims = item.putArray("claims");
            for (String code : coordinate.claimCodes()) {
                var claim = claims.addObject().put("code", code).put("met", success);
                var spans = claim.putArray("spans");
                if (success) spans.addObject().put("field", "method").put("start", 0).put("end", 1);
            }
            var contradictions = item.putArray("contradictions");
            for (String code : coordinate.contradictionCodes())
                contradictions.addObject().put("code", code).put("met", false).putArray("spans");
        }
        var validator = new GradeResultValidator();
        var pinnedReport = validator.parseReport(JSON.writeValueAsString(payload));
        assertThat(
                        validator
                                .validate(
                                        JSON.writeValueAsString(semantic),
                                        pinnedReport,
                                        saved.dataset().gradingSnapshot())
                                .status())
                .isEqualTo(com.reasoning.common.grading.model.GradeModels.Status.COMPLETE);
        var worker =
                credentials.authenticate(
                        "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        var lease =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()))
                        .claimTest(worker.workerKey(), "H5_SYNTHETIC")
                        .orElseThrow();
        assertThat(lease.jobKey()).isEqualTo(jobKey);
        var started = assembly.start().startApproved(worker, jobKey, lease.leaseGen());
        assertThat(started.attemptNo()).isEqualTo(1);
        var command = JSON.createObjectNode().put("kind", "COMPLETE");
        command.set("semantic", semantic);
        assertThat(
                        assembly.completion()
                                .complete(
                                        worker,
                                        jobKey,
                                        lease.leaseGen(),
                                        1,
                                        null,
                                        null,
                                        JSON.writeValueAsString(command),
                                        UUID.randomUUID())
                                .state())
                .isEqualTo("COMPLETED");
        JsonNode base = authenticate(new Completed(pair, testId, jobId));
        assertThat(base.path("success").booleanValue()).isEqualTo(success);
        return new Completed(pair, testId, jobId);
    }

    private JsonNode authenticate(Completed completed) {
        return transaction()
                .execute(
                        status ->
                                new PlaytestResultAuthenticator(db, crypto)
                                        .authenticate(completed.testId(), completed.jobId()));
    }

    private ObjectNode privateResult(long jobId) throws Exception {
        byte[] cipher =
                db.queryForObject(
                        "SELECT result_cipher FROM grade_job WHERE id=?", byte[].class, jobId);
        return (ObjectNode)
                JSON.readTree(
                        crypto.decrypt(
                                new String(cipher, StandardCharsets.UTF_8),
                                "grade_job/" + jobId + "/result/v1"));
    }

    private void replace(long jobId, ObjectNode value) throws Exception {
        byte[] cipher =
                crypto.encrypt(JSON.writeValueAsString(value), "grade_job/" + jobId + "/result/v1")
                        .getBytes(StandardCharsets.UTF_8);
        db.update(
                "UPDATE grade_job SET result_cipher=?,result_hash=? WHERE id=?",
                cipher,
                SnapshotJson.hash(value),
                jobId);
    }

    @Test
    void validNegativeSemanticsAndTruthGateRemainSeparate() throws Exception {
        Completed negative = completed(false);
        assertThat(authenticate(negative).path("success").booleanValue()).isFalse();
        Completed positive = completed(true);
        var hidden =
                reports.result(positive.pair().first(), positive.pair().key(), UUID.randomUUID());
        assertThat(hidden.resultPhase()).isEqualTo("AWAITING_FEEDBACK");
        assertThat(hidden.reports()).isNull();
        assertThat(hidden.revealText()).isNull();
        reports.feedback(
                positive.pair().first(),
                positive.pair().key(),
                feedback("본인"),
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(
                        reports.result(
                                        positive.pair().first(),
                                        positive.pair().key(),
                                        UUID.randomUUID())
                                .resultPhase())
                .isEqualTo("AVAILABLE");
        assertThat(
                        reports.result(
                                        positive.pair().second(),
                                        positive.pair().key(),
                                        UUID.randomUUID())
                                .revealText())
                .isNull();
    }

    @Test
    void coherentlyEncryptedLegalScoreReasonAndClaimReplacementsAreRejected() throws Exception {
        Completed completed = completed(true);
        reports.feedback(
                completed.pair().first(),
                completed.pair().key(),
                feedback("본인"),
                UUID.randomUUID(),
                UUID.randomUUID());
        ObjectNode original = privateResult(completed.jobId());
        for (Consumer<ObjectNode> mutation :
                java.util.List.<Consumer<ObjectNode>>of(
                        value ->
                                ((ObjectNode) value.path("baseResult").path("items").get(0))
                                        .put("reason", "대체 사유"),
                        value ->
                                ((ObjectNode)
                                                value.path("baseResult")
                                                        .path("items")
                                                        .get(0)
                                                        .path("claims")
                                                        .get(0))
                                        .put("met", false),
                        value ->
                                ((ObjectNode) value.path("baseResult").path("items").get(0))
                                        .put("score", 0))) {
            ObjectNode changed = original.deepCopy();
            mutation.accept(changed);
            replace(completed.jobId(), changed);
            assertThatThrownBy(() -> authenticate(completed)).isInstanceOf(AuthException.class);
            assertThatThrownBy(
                            () ->
                                    reports.result(
                                            completed.pair().first(),
                                            completed.pair().key(),
                                            UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
        }
        replace(completed.jobId(), original);
        assertThat(authenticate(completed).path("success").booleanValue()).isTrue();
        var attempt =
                db.queryForMap(
                        "SELECT output_cipher,output_hash FROM grade_attempt WHERE job_id=? AND"
                                + " attempt_no=1",
                        completed.jobId());
        String aad = "grade_attempt/" + completed.jobId() + "/1/output/v1";
        ObjectNode semantic =
                (ObjectNode)
                        JSON.readTree(
                                crypto.decrypt(
                                        new String(
                                                (byte[]) attempt.get("output_cipher"),
                                                StandardCharsets.UTF_8),
                                        aad));
        ((ObjectNode) semantic.path("items").get(0)).put("reason", "대체 의미 원문");
        db.update(
                "UPDATE grade_attempt SET output_cipher=?,output_hash=? WHERE job_id=? AND"
                        + " attempt_no=1",
                crypto.encrypt(JSON.writeValueAsString(semantic), aad)
                        .getBytes(StandardCharsets.UTF_8),
                SnapshotJson.hash(semantic),
                completed.jobId());
        assertThatThrownBy(() -> authenticate(completed)).isInstanceOf(AuthException.class);
        db.update(
                "UPDATE grade_attempt SET output_cipher=?,output_hash=? WHERE job_id=? AND"
                        + " attempt_no=1",
                attempt.get("output_cipher"),
                attempt.get("output_hash"),
                completed.jobId());
        assertThat(authenticate(completed).path("success").booleanValue()).isTrue();
    }

    @Test
    void floatingAndCoercedResultNodesAreRejectedEvenWhenHashesAgree() throws Exception {
        Completed completed = completed(true);
        ObjectNode original = privateResult(completed.jobId());
        for (Consumer<ObjectNode> mutation :
                java.util.List.<Consumer<ObjectNode>>of(
                        value -> value.put("formatNo", 1.0),
                        value ->
                                ((ObjectNode) value.path("baseResult"))
                                        .put(
                                                "baseScore",
                                                original.path("baseResult")
                                                        .path("baseScore")
                                                        .doubleValue()),
                        value ->
                                ((ObjectNode) value.path("baseResult").path("items").get(0))
                                        .put("score", 25.0),
                        value -> ((ObjectNode) value.path("baseResult")).put("success", "true"),
                        value ->
                                ((ObjectNode) value.path("baseResult").path("items").get(0))
                                        .put("requiredMet", 1))) {
            ObjectNode changed = original.deepCopy();
            mutation.accept(changed);
            replace(completed.jobId(), changed);
            assertThatThrownBy(() -> authenticate(completed)).isInstanceOf(AuthException.class);
        }
        replace(completed.jobId(), original);
    }

    @Test
    void substitutedTimeoutResultRollsBackSettlementWithoutChangingBudgets() throws Exception {
        Completed completed = completed(false);
        db.update(
                "UPDATE play_test SET started_at=clock_timestamp()-interval '2 hours',"
                        + " deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",
                completed.testId());
        var before =
                db.queryForMap(
                        "SELECT"
                            + " state,rev,attempt_count,wrong_count,final_score,outcome,result_until"
                            + " FROM play_test WHERE id=?",
                        completed.testId());
        var budget =
                db.queryForMap(
                        "SELECT call_count,lease_gen,state FROM grade_job WHERE id=?",
                        completed.jobId());
        int audits =
                db.queryForObject(
                        "SELECT count(*) FROM test_audit WHERE scope_key=? AND"
                                + " action='TEST_DEADLINE'",
                        Integer.class,
                        completed.pair().key().toString());
        ObjectNode original = privateResult(completed.jobId());
        ObjectNode changed = original.deepCopy();
        ((ObjectNode) changed.path("baseResult").path("items").get(0)).put("reason", "대체 마감 사유");
        replace(completed.jobId(), changed);
        assertThatThrownBy(
                        () ->
                                transaction()
                                        .execute(
                                                status ->
                                                        PlaytestAccess.normalizeTime(
                                                                db,
                                                                crypto,
                                                                completed.testId(),
                                                                "SYSTEM",
                                                                "AUTHENTICITY_TEST",
                                                                UUID.randomUUID())))
                .isInstanceOf(AuthException.class);
        assertThat(
                        db.queryForMap(
                                "SELECT"
                                    + " state,rev,attempt_count,wrong_count,final_score,outcome,result_until"
                                    + " FROM play_test WHERE id=?",
                                completed.testId()))
                .isEqualTo(before);
        assertThat(
                        db.queryForMap(
                                "SELECT call_count,lease_gen,state FROM grade_job WHERE id=?",
                                completed.jobId()))
                .isEqualTo(budget);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_audit WHERE scope_key=? AND"
                                        + " action='TEST_DEADLINE'",
                                Integer.class,
                                completed.pair().key().toString()))
                .isEqualTo(audits);
        replace(completed.jobId(), original);
        transaction()
                .execute(
                        status ->
                                PlaytestAccess.normalizeTime(
                                        db,
                                        crypto,
                                        completed.testId(),
                                        "SYSTEM",
                                        "AUTHENTICITY_TEST",
                                        UUID.randomUUID()));
        assertThat(
                        db.queryForObject(
                                "SELECT outcome FROM play_test WHERE id=?",
                                String.class,
                                completed.testId()))
                .isEqualTo("TIME_LIMIT");
        assertThat(
                        db.queryForMap(
                                "SELECT call_count,lease_gen,state FROM grade_job WHERE id=?",
                                completed.jobId()))
                .isEqualTo(budget);
    }
}
