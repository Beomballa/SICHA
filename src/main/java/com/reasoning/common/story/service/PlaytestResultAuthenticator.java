package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.service.GradeCalculator;
import com.reasoning.common.grading.service.GradeResultValidator;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Historical result proof only; does not grant current execution or disclosure authority. */
final class PlaytestResultAuthenticator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JdbcTemplate db;
    private final CryptoService crypto;

    PlaytestResultAuthenticator(JdbcTemplate db, CryptoService crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    JsonNode authenticate(long testId, long jobId) {
        try {
            var row =
                    db.queryForMap(
                            """
                            SELECT j.job_key,j.call_count,j.lease_gen,j.report_hash,j.rubric_hash,
                                   j.result_cipher,j.result_hash,j.result_data::text AS summary,
                                   r.id AS report_id,r.source_draft_rev,r.payload_cipher,r.payload_hash,
                                   j.snapshot_id,a.attempt_no,a.worker_key,a.observed_version,a.provider_ref,
                                   a.output_cipher,a.output_hash,a.completion_data::text AS completion
                            FROM grade_job j JOIN test_report r ON r.id=j.report_id
                            JOIN play_test t ON t.id=r.test_id
                            JOIN grade_attempt a ON a.job_id=j.id AND a.attempt_no=j.call_count
                            WHERE j.id=? AND t.id=? AND j.state='COMPLETED' AND r.state='GRADED'
                              AND j.batch_id IS NULL AND j.sample_code IS NULL AND j.repeat_no IS NULL
                              AND j.input_hash IS NULL AND r.purged_at IS NULL
                              AND j.accepted_at=r.accepted_at AND j.snapshot_id=t.snapshot_id
                              AND r.snapshot_id=t.snapshot_id AND j.runtime_id=t.runtime_id
                              AND r.runtime_id=t.runtime_id AND j.config_hash=t.config_hash
                              AND r.config_hash=t.config_hash AND r.runtime_epoch=t.runtime_epoch
                              AND a.state='SUCCEEDED' AND a.lease_gen=j.lease_gen
                              AND j.error_code IS NULL AND a.error_code IS NULL
                              AND a.ended_at IS NOT NULL AND a.ended_at>=a.started_at
                              AND a.ended_at<j.deadline_at
                            """,
                            jobId,
                            testId);
            long snapshotId = number(row, "snapshot_id");
            var saved = new GradeFrozenInputRepository(db).loadSavedFacts(snapshotId);
            if (!saved.frozen().rubricHash().equals(row.get("rubric_hash"))) fail();
            JsonNode reportJson =
                    decrypt(
                            row,
                            "payload_cipher",
                            "payload_hash",
                            "test_report/"
                                    + row.get("report_id")
                                    + "/test/"
                                    + testId
                                    + "/snapshot/"
                                    + snapshotId
                                    + "/draft/"
                                    + row.get("source_draft_rev")
                                    + "/payload/v1");
            var validator = new GradeResultValidator();
            var report = validator.parseReport(text(reportJson));
            if (!MAPPER.valueToTree(report).equals(reportJson)
                    || !saved.frozen().reportHash(report).equals(row.get("report_hash"))) fail();
            boolean known = false;
            for (JsonNode person : saved.frozen().payload().path("resources").path("persons"))
                known |= report.culpritCode().equals(person.path("code").textValue());
            if (!known) fail();
            int attempt = Math.toIntExact(number(row, "attempt_no"));
            if (attempt < 1 || attempt > 3) fail();
            JsonNode semantic =
                    decrypt(
                            row,
                            "output_cipher",
                            "output_hash",
                            "grade_attempt/" + jobId + "/" + attempt + "/output/v1");
            JsonNode envelope = SnapshotJson.parse(bytes((String) row.get("completion")));
            exact(envelope, "receipt", "auditEventId", "commandHash");
            JsonNode receipt = envelope.get("receipt");
            exact(
                    receipt,
                    "jobKey",
                    "attemptNo",
                    "accepted",
                    "state",
                    "retryScheduled",
                    "outcome",
                    "reason",
                    "requestId");
            if (!row.get("job_key").toString().equals(receipt.get("jobKey").textValue())
                    || !receipt.get("attemptNo").isIntegralNumber()
                    || !receipt.get("attemptNo").canConvertToInt()
                    || receipt.get("attemptNo").intValue() != attempt
                    || !receipt.get("accepted").isBoolean()
                    || !receipt.get("accepted").booleanValue()
                    || !receipt.get("retryScheduled").isBoolean()
                    || receipt.get("retryScheduled").booleanValue()
                    || !"COMPLETED".equals(receipt.get("state").textValue())
                    || !"COMPLETE".equals(receipt.get("outcome").textValue())
                    || !"NONE".equals(receipt.get("reason").textValue())
                    || !envelope.get("auditEventId").isIntegralNumber()
                    || !envelope.get("auditEventId").canConvertToLong()) fail();
            var command =
                    JsonNodeFactory.instance
                            .objectNode()
                            .put("jobKey", row.get("job_key").toString())
                            .put("leaseGen", number(row, "lease_gen"))
                            .put("attemptNo", attempt)
                            .put("observedProviderVersion", (String) row.get("observed_version"))
                            .put("providerResponseRef", (String) row.get("provider_ref"));
            var result = JsonNodeFactory.instance.objectNode().put("kind", "COMPLETE");
            result.set("semantic", semantic);
            command.set("result", result);
            String commandHash = SnapshotJson.hash(command);
            if (!commandHash.equals(envelope.get("commandHash").textValue())) fail();
            var event =
                    db.queryForMap(
                            """
                            SELECT e.command_hash,e.request_id,e.detail::text AS detail
                            FROM grade_event e WHERE e.id=? AND e.job_id=? AND e.attempt_no=?
                              AND e.event_kind='COMPLETE_APPLIED' AND e.actor_kind='WORKER' AND e.actor_key=?
                            """,
                            envelope.get("auditEventId").longValue(),
                            jobId,
                            attempt,
                            row.get("worker_key"));
            if (!commandHash.equals(event.get("command_hash"))
                    || !Objects.equals(
                            event.get("request_id").toString(),
                            receipt.get("requestId").textValue())) fail();
            JsonNode detail = SnapshotJson.parse(bytes((String) event.get("detail")));
            var expectedDetail =
                    JsonNodeFactory.instance
                            .objectNode()
                            .put("leaseGen", number(row, "lease_gen"))
                            .put("beforeJob", "RUNNING")
                            .put("afterJob", "COMPLETED")
                            .put("beforeAttempt", "RUNNING")
                            .put("afterAttempt", "SUCCEEDED")
                            .put("reason", "NONE")
                            .put("outputHash", (String) row.get("output_hash"))
                            .put("resultHash", (String) row.get("result_hash"));
            same(detail, expectedDetail);
            var calculated =
                    new GradeCalculator()
                            .calculate(
                                    report,
                                    saved.dataset().gradingSnapshot(),
                                    validator.validate(
                                            json(semantic),
                                            report,
                                            saved.dataset().gradingSnapshot()));
            JsonNode base = MAPPER.valueToTree(calculated);
            var expected = JsonNodeFactory.instance.objectNode().put("formatNo", 1);
            expected.set("baseResult", base);
            same(
                    decrypt(
                            row,
                            "result_cipher",
                            "result_hash",
                            "grade_job/" + jobId + "/result/v1"),
                    expected);
            same(
                    SnapshotJson.parse(bytes((String) row.get("summary"))),
                    JsonNodeFactory.instance
                            .objectNode()
                            .put("formatNo", 1)
                            .put("baseScore", calculated.baseScore())
                            .put("success", calculated.success()));
            if (!"COMPLETE".equals(base.path("status").textValue())) fail();
            return base;
        } catch (RuntimeException failure) {
            throw AuthException.unavailable("PLAYTEST_UNAVAILABLE");
        }
    }

    private JsonNode decrypt(
            Map<String, Object> row, String cipherKey, String hashKey, String aad) {
        byte[] cipher = (byte[]) row.get(cipherKey);
        if (cipher == null || cipher.length > 524288) fail();
        byte[] plain = bytes(crypto.decrypt(new String(cipher, StandardCharsets.UTF_8), aad));
        if (plain.length > 524288) fail();
        JsonNode value = SnapshotJson.parse(plain);
        if (!SnapshotJson.hash(value).equals(row.get(hashKey))) fail();
        return value;
    }

    /** Canonical equality keeps every semantic field, with no numeric/boolean coercion. */
    private static void same(JsonNode actual, JsonNode expected) {
        types(actual, expected);
        if (!Arrays.equals(SnapshotJson.encode(actual), SnapshotJson.encode(expected))) fail();
    }

    private static void types(JsonNode actual, JsonNode expected) {
        if (actual == null) fail();
        if (expected.isIntegralNumber()) {
            if (!actual.isIntegralNumber()
                    || !actual.bigIntegerValue().equals(expected.bigIntegerValue())) fail();
        } else if (actual.getNodeType() != expected.getNodeType()) fail();
        if (expected.isObject()) {
            if (actual.size() != expected.size()) fail();
            expected.fieldNames()
                    .forEachRemaining(key -> types(actual.get(key), expected.get(key)));
        } else if (expected.isArray()) {
            if (actual.size() != expected.size()) fail();
            for (int index = 0; index < expected.size(); index++)
                types(actual.get(index), expected.get(index));
        }
    }

    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) fail();
        for (String field : fields) if (!node.has(field)) fail();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(JsonNode value) {
        return new String(SnapshotJson.encode(value), StandardCharsets.UTF_8);
    }

    private static String json(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (java.io.IOException failure) {
            throw AuthException.unavailable("PLAYTEST_UNAVAILABLE");
        }
    }

    private static void fail() {
        throw AuthException.unavailable("PLAYTEST_UNAVAILABLE");
    }
}
