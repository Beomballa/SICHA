package com.reasoning.common.grading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.Failure;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 서버에서 실제 사용하는 순수 encoding·private digest·예산 계산을 검사한다. decoder나 worker 증거가 아니다. */
class GradeRemoteExecutionProtocolTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID KEY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String HASH = "a".repeat(64);

    /** 초기 0세대의 표현은 허용하고 음수 세대는 실제 factory에서 거절하는지 검사한다. */
    @Test
    void runtimeProjectionAcceptsInitialZeroAndRejectsNegativeEpoch() {
        var manifest = runtime().toJson();
        manifest.set("configId", manifest.remove("code"));
        assertThat(
                        GradeRemoteExecutionProtocol.runtime(manifest, HASH, 0)
                                .toJson()
                                .get("epoch")
                                .longValue())
                .isZero();
        assertThatThrownBy(() -> GradeRemoteExecutionProtocol.runtime(manifest, HASH, -1))
                .isInstanceOf(Failure.class)
                .hasMessage("INVALID_REMOTE_REQUEST")
                .hasNoCause();
    }

    /** 실제 투영의 바이트·Base64·SHA·사본 불변성과 비공개 원문 배제를 순수 메모리에서 검사한다. */
    @Test
    void canonicalModelCopiesExactProductionProjectionAndNeverReencodes() {
        var dataset =
                new FrozenDatasetValidator()
                        .validate(
                                FrozenSnapshotCodec.freeze(FrozenSnapshotContractTest.complete()));
        byte[] original =
                FrozenModelProjection.project(dataset, dataset.select("FULL")).payloadBytes();
        byte[] expected = original.clone();
        var model = GradeRemoteExecutionProtocol.model(original);
        original[0] = 0;
        model.payloadBytes()[0] = 0;
        model.toJson().removeAll();
        assertThat(model.payloadBytes()).isEqualTo(expected);
        assertThat(model.sha256()).isEqualTo(CommonUtil.sha256(expected));
        assertThat(model.toJson().get("bytesBase64").textValue())
                .isEqualTo(Base64.getEncoder().encodeToString(expected));
        assertThat(model.toJson().get("encoding").textValue())
                .isEqualTo("SNAPSHOT_JSON_CANONICAL_UTF8-v1");
        keys(model.toJson(), "encoding", "bytesBase64", "sha256");
        assertThat(model.toString())
                .doesNotContain("SELECTED_REPORT", "bytesBase64", model.sha256());
        assertThat(new String(expected, StandardCharsets.UTF_8))
                .contains("SELECTED_REPORT")
                .doesNotContain(
                        "ENGINE_REPORT", "expectData", "expectedScore", "checkedBy", "revealText");
    }

    /** runtime10·두 variant·NEW/REPLAY의 exact 키·명시 null·형식 거절을 SQL 없이 검사한다. */
    @Test
    void exactRuntimeVariantsAndReplayHaveRequiredExplicitNullKeys() {
        var runtime = runtime();
        keys(
                runtime.toJson(),
                "code",
                "configHash",
                "epoch",
                "engineVersion",
                "modelId",
                "modelVersion",
                "pinMode",
                "promptHash",
                "optionsHash",
                "reportContractVersion");
        assertThat(runtime.toJson().get("epoch").isIntegralNumber()).isTrue();
        assertThat(runtime.toJson().toString())
                .doesNotContain("PRIVATE_SETTINGS", "endpoint", "dictionary");
        var model = GradeRemoteExecutionProtocol.model("{}".getBytes(StandardCharsets.UTF_8));
        var input =
                GradeRemoteExecutionProtocol.input(
                        KEY,
                        1,
                        3,
                        NOW.plusSeconds(120),
                        7,
                        HASH,
                        HASH,
                        HASH,
                        runtime,
                        model,
                        null,
                        HASH);
        keys(
                input.toJson(),
                "formatNo",
                "sourceKind",
                "variant",
                "jobKey",
                "leaseGen",
                "attemptNo",
                "deadlineAt",
                "snapshotId",
                "payloadHash",
                "rubricHash",
                "datasetHash",
                "runtime",
                "modelInput",
                "fault",
                "originalAttemptHash");
        assertThat(input.toJson().get("fault").isNull()).isTrue();
        assertThat(input.toJson().get("snapshotId").textValue()).isEqualTo("7");
        var limits =
                GradeRemoteExecutionProtocol.limits(
                        NOW, NOW.plusSeconds(120), NOW.plusSeconds(30), NOW.plusSeconds(500), 0);
        var fresh = GradeRemoteExecutionProtocol.start(KEY, 1, 3, input, limits);
        keys(
                fresh.toJson(),
                "formatNo",
                "disposition",
                "jobKey",
                "leaseGen",
                "attemptNo",
                "input",
                "limits");
        assertThat(fresh.disposition()).isEqualTo("NEW");
        var replay = GradeRemoteExecutionProtocol.start(KEY, 1, 3, null, null);
        assertThat(replay.disposition()).isEqualTo("REPLAY");
        assertThat(replay.toJson().get("input").isNull()).isTrue();
        assertThat(replay.toJson().get("limits").isNull()).isTrue();
        var request = new AttemptRequest(1, 3, HASH);
        keys(
                GradeRemoteExecutionProtocol.fence(KEY, request, limits).toJson(),
                "formatNo",
                "jobKey",
                "leaseGen",
                "attemptNo",
                "originalAttemptHash",
                "limits");
        keys(
                GradeRemoteExecutionProtocol.renew(KEY, request, limits).toJson(),
                "formatNo",
                "jobKey",
                "leaseGen",
                "attemptNo",
                "originalAttemptHash",
                "limits");
        for (String type : List.of("TIMEOUT", "UNAVAILABLE")) {
            var fault = GradeRemoteExecutionProtocol.fault(type, 3);
            keys(fault.toJson(), "type", "failRuns");
            var synthetic =
                    GradeRemoteExecutionProtocol.input(
                            KEY, 1, 1, NOW, 7, HASH, HASH, HASH, runtime, null, fault, HASH);
            assertThat(synthetic.toJson().get("modelInput").isNull()).isTrue();
            assertThat(synthetic.toJson().get("variant").textValue()).isEqualTo("ENGINE_ERROR");
            assertThat(synthetic.toString()).isEqualTo("ApprovedExecutionInput[redacted]");
        }
        assertThatThrownBy(
                        () ->
                                GradeRemoteExecutionProtocol.input(
                                        KEY, 1, 1, NOW, 7, HASH, HASH, HASH, runtime, null, null,
                                        HASH))
                .isInstanceOf(Failure.class);
        assertThatThrownBy(
                        () ->
                                GradeRemoteExecutionProtocol.input(
                                        KEY,
                                        1,
                                        1,
                                        NOW,
                                        7,
                                        HASH,
                                        HASH,
                                        HASH,
                                        runtime,
                                        model,
                                        GradeRemoteExecutionProtocol.fault("TIMEOUT", 3),
                                        HASH))
                .isInstanceOf(Failure.class);
        assertThatThrownBy(() -> GradeRemoteExecutionProtocol.start(KEY, 1, 1, input, limits))
                .isInstanceOf(Failure.class);
        assertThatThrownBy(() -> GradeRemoteExecutionProtocol.start(KEY, 1, 3, input, null))
                .isInstanceOf(Failure.class);
        assertThatThrownBy(() -> GradeRemoteExecutionProtocol.fault("INPUT_ERROR", 3))
                .isInstanceOf(Failure.class);
        assertThatThrownBy(() -> GradeRemoteExecutionProtocol.fault("TIMEOUT", 2))
                .isInstanceOf(Failure.class);
    }

    /** 독립 literal preimage와 24개 결속 변경·소유 tuple·입력 변경의 digest 민감도를 검사한다. */
    @Test
    void originalPrivatePreimageMatchesIndependentLiteralAndEveryBindingMatters() {
        var binding = binding();
        var model = GradeRemoteExecutionProtocol.model("{}".getBytes(StandardCharsets.UTF_8));
        String actual =
                GradeRemoteExecutionProtocol.originalHash(
                        binding, "W", 9, 2, NOW, runtime(), model, null);
        String literal =
                """
                {"formatNo":1,"domain":"GRADE_REMOTE_ORIGINAL_ATTEMPT-v1",
                "sourceBinding":["1","11111111-1111-4111-8111-111111111111","2","11111111-1111-4111-8111-111111111111","3","4","5","6","7","R","8","a","b","c","d","e","FULL",1,"REVIEW","0","0",1,"RULE","2026-01-01T00:00:00Z"],
                "workerKey":"W","leaseGen":"9","attemptNo":2,"deadlineAt":"2026-01-01T00:00:00Z","variant":"MODEL",
                "runtime":{"code":"R","configHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","epoch":8,"engineVersion":"1","modelId":"M","modelVersion":"V","pinMode":"ALIAS_MONITORED","promptHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","optionsHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","reportContractVersion":"REPORT-1"},
                "modelInputHash":"44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a","fault":null}
                """;
        assertThat(actual)
                .isEqualTo(
                        SnapshotJson.hash(
                                SnapshotJson.parse(literal.getBytes(StandardCharsets.UTF_8))));
        for (int i = 0; i < 24; i++) {
            var changed = new ArrayList<>(binding);
            Object before = changed.get(i);
            changed.set(
                    i,
                    before instanceof Number n
                            ? n.longValue() + 1
                            : before instanceof UUID
                                    ? UUID.randomUUID()
                                    : before instanceof OffsetDateTime time
                                            ? time.plusNanos(1)
                                            : before + "X");
            assertThat(
                            GradeRemoteExecutionProtocol.originalHash(
                                    changed, "W", 9, 2, NOW, runtime(), model, null))
                    .isNotEqualTo(actual);
        }
        assertThat(
                        GradeRemoteExecutionProtocol.originalHash(
                                binding, "W", 9, 2, NOW.plusNanos(1), runtime(), model, null))
                .isNotEqualTo(actual);
        assertThat(
                        GradeRemoteExecutionProtocol.originalHash(
                                binding, "OTHER", 9, 2, NOW, runtime(), model, null))
                .isNotEqualTo(actual);
        assertThat(
                        GradeRemoteExecutionProtocol.originalHash(
                                binding, "W", 10, 2, NOW, runtime(), model, null))
                .isNotEqualTo(actual);
        assertThat(
                        GradeRemoteExecutionProtocol.originalHash(
                                binding, "W", 9, 3, NOW, runtime(), model, null))
                .isNotEqualTo(actual);
        assertThat(
                        GradeRemoteExecutionProtocol.originalHash(
                                binding,
                                "W",
                                9,
                                2,
                                NOW,
                                runtime(),
                                GradeRemoteExecutionProtocol.model(
                                        "{\"report\":\"MODEL_CANARY\"}"
                                                .getBytes(StandardCharsets.UTF_8)),
                                null))
                .isNotEqualTo(actual);
        assertThat(
                        GradeRemoteExecutionProtocol.originalHash(
                                binding,
                                "W",
                                9,
                                2,
                                NOW,
                                runtime(),
                                null,
                                GradeRemoteExecutionProtocol.fault("TIMEOUT", 3)))
                .isNotEqualTo(actual);
        GradeRemoteExecutionProtocol.limits(
                NOW, NOW.plusSeconds(120), NOW.plusSeconds(10), NOW.plusSeconds(500), 0);
        GradeRemoteExecutionProtocol.limits(
                NOW, NOW.plusSeconds(120), NOW.plusSeconds(30), NOW.plusSeconds(500), 10_000_000);
        assertThat(
                        GradeRemoteExecutionProtocol.originalHash(
                                binding, "W", 9, 2, NOW, runtime(), model, null))
                .isEqualTo(actual);
    }

    /** 실제 순수 예산 helper의 1ms 경계·내림·상한·null·음수·overflow 거절을 검사한다. */
    @Test
    void productionLimitsRejectZeroSubmillisecondOversizeNullNegativeAndOverflowWithoutClamping() {
        for (long nanos : List.of(0L, 999_999L))
            expired(
                    () ->
                            GradeRemoteExecutionProtocol.limits(
                                    NOW,
                                    NOW.plusNanos(nanos),
                                    NOW.plusNanos(nanos),
                                    NOW.plusSeconds(500),
                                    0));
        assertThat(
                        GradeRemoteExecutionProtocol.limits(
                                        NOW,
                                        NOW.plusNanos(1_000_000),
                                        NOW.plusNanos(1_000_000),
                                        NOW.plusSeconds(500),
                                        0)
                                .remainingBudgetMillis())
                .isEqualTo(1);
        assertThat(
                        GradeRemoteExecutionProtocol.limits(
                                        NOW,
                                        NOW.plusNanos(1_999_999),
                                        NOW.plusNanos(1_999_999),
                                        NOW.plusSeconds(500),
                                        0)
                                .remainingLeaseMillis())
                .isEqualTo(1);
        var delayed =
                GradeRemoteExecutionProtocol.limits(
                        NOW,
                        NOW.plusSeconds(120),
                        NOW.plusSeconds(30),
                        NOW.plusSeconds(500),
                        1_234_567_890);
        assertThat(delayed.remainingBudgetMillis()).isEqualTo(118765);
        assertThat(delayed.remainingLeaseMillis()).isEqualTo(28765);
        assertThat(
                        GradeRemoteExecutionProtocol.limits(
                                        NOW,
                                        NOW.plusSeconds(120),
                                        NOW.plusSeconds(30),
                                        NOW.plusSeconds(10),
                                        0)
                                .remainingBudgetMillis())
                .isEqualTo(10000);
        var shortJob =
                GradeRemoteExecutionProtocol.limits(
                        NOW, NOW.plusSeconds(1), NOW.plusSeconds(30), NOW.plusSeconds(500), 0);
        assertThat(shortJob.remainingBudgetMillis()).isEqualTo(1000);
        assertThat(shortJob.remainingLeaseMillis()).isEqualTo(1000);
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW,
                                NOW.plusNanos(1_000_000),
                                NOW.plusNanos(1_000_000),
                                NOW.plusSeconds(500),
                                1));
        expired(() -> GradeRemoteExecutionProtocol.limits(null, NOW, NOW, NOW, 0));
        expired(() -> GradeRemoteExecutionProtocol.limits(NOW, null, NOW, NOW, 0));
        expired(() -> GradeRemoteExecutionProtocol.limits(NOW, NOW.plusSeconds(120), null, NOW, 0));
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW, NOW.plusSeconds(120), NOW.plusSeconds(30), null, 0));
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW,
                                NOW.plusSeconds(120).plusNanos(1),
                                NOW.plusSeconds(30),
                                NOW.plusSeconds(500),
                                0));
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW,
                                NOW.plusSeconds(120),
                                NOW.plusSeconds(30).plusNanos(1),
                                NOW.plusSeconds(500),
                                0));
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW, NOW.plusSeconds(120), NOW.plusSeconds(30), NOW, 0));
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW,
                                NOW.plusSeconds(120),
                                NOW.plusSeconds(30),
                                NOW.plusSeconds(500),
                                -1));
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW, Instant.MAX, NOW.plusSeconds(30), NOW.plusSeconds(500), 0));
        expired(
                () ->
                        GradeRemoteExecutionProtocol.limits(
                                NOW,
                                NOW.plusSeconds(120),
                                NOW.plusSeconds(30),
                                NOW.plusSeconds(500),
                                Long.MAX_VALUE));
    }

    /** 잘못된 tuple 거절과 모든 고정 오류의 원문·원인 미보관을 메모리에서 검사한다. */
    @Test
    void malformedTuplesAndFailuresNeverRetainPayloadOrCause() {
        for (String hash :
                List.of("REPORT_CANARY", "A".repeat(64), "a".repeat(63), "a".repeat(65))) {
            assertThatThrownBy(() -> new AttemptRequest(1, 1, hash))
                    .isInstanceOf(Failure.class)
                    .hasMessage("INVALID_REMOTE_REQUEST")
                    .hasNoCause();
        }
        for (int attempt : List.of(0, 4))
            assertThatThrownBy(() -> new AttemptRequest(1, attempt, HASH))
                    .isInstanceOf(Failure.class);
        assertThatThrownBy(() -> new AttemptRequest(0, 1, HASH)).isInstanceOf(Failure.class);
        assertThatThrownBy(() -> new AttemptRequest(1, 1, null)).isInstanceOf(Failure.class);
        assertThat(new AttemptRequest(Long.MAX_VALUE, 3, HASH).toString())
                .isEqualTo("AttemptRequest[redacted]");
        for (var code : GradeRemoteExecutionProtocol.FailureCode.values()) {
            var failure = GradeRemoteExecutionProtocol.failure(code);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).isEqualTo(code.name());
            assertThat(failure.toString()).doesNotContain("REPORT_CANARY", HASH);
        }
    }

    /**
     * 출력 allowlist 밖의 비밀 canary를 포함한 독립 manifest를 메모리에서 만든다.
     *
     * @return null 아닌 epoch=8 runtime10 표본
     */
    private GradeRemoteExecutionProtocol.Runtime10 runtime() {
        var manifest =
                SnapshotJson.parse(
                        ("{\"configId\":\"R\",\"engineVersion\":\"1\",\"modelId\":\"M\",\"modelVersion\":\"V\",\"pinMode\":\"ALIAS_MONITORED\",\"promptHash\":\""
                                        + HASH
                                        + "\",\"optionsHash\":\""
                                        + HASH
                                        + "\",\"reportContractVersion\":\"REPORT-1\",\"settings\":\"PRIVATE_SETTINGS\"}")
                                .getBytes(StandardCharsets.UTF_8));
        return GradeRemoteExecutionProtocol.runtime(manifest, HASH, 8);
    }

    /**
     * 24개 binding 순서의 독립 표본을 만든다. repeat/format은 JSON int로 인코딩되어야 한다.
     *
     * @return null·null 원소 없는 불변 서버 사실 표본
     */
    private List<Object> binding() {
        return List.of(
                1L,
                KEY,
                2L,
                KEY,
                3L,
                4L,
                5L,
                6L,
                7L,
                "R",
                8L,
                "a",
                "b",
                "c",
                "d",
                "e",
                "FULL",
                1L,
                "REVIEW",
                0L,
                0L,
                1L,
                "RULE",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * 순서에 무관하게 exact 객체 키만 비교하며 트리를 변경하지 않는다.
     *
     * @param value null 아닌 JSON 객체
     * @param expected null·null 원소 없는 예상 필드명 배열
     * @throws AssertionError 누락·추가 키가 있으면 검증 실패
     */
    private void keys(JsonNode value, String... expected) {
        var keys = new ArrayList<String>();
        value.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(expected);
    }

    /**
     * 순수 계산이 원인 없는 고정 만료 실패인지 검사한다.
     *
     * @param operation null 아닌 메모리 계산; SQL·외부 I/O 없음
     * @throws AssertionError REMOTE_EXECUTION_EXPIRED 이외 결과이면 검증 실패
     */
    private void expired(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(Failure.class)
                .hasMessage("REMOTE_EXECUTION_EXPIRED")
                .hasNoCause();
    }
}
