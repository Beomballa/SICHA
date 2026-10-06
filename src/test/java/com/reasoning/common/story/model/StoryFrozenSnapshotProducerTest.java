package com.reasoning.common.story.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.FrozenDatasetValidator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/** 순수 서버 원고 조립 경계만 검사하며 저장·인가·의미 품질의 증거로 사용하지 않는다. */
class StoryFrozenSnapshotProducerTest {
    @Test
    void completeSourceMatchesCodecBytesAndEveryRegisteredDifficultyUsesActualLimit() {
        for (int difficulty = 1; difficulty <= 5; difficulty++) {
            ObjectNode source = FrozenSnapshotContractTest.complete();
            basic(source).put("difficulty", difficulty).put("limitSec", 731);
            source.set(
                    "policy", FrozenSnapshotCodec.createPolicy("RULE_20260924", difficulty, 731));
            var frozen = produce(source);
            assertThat(frozen.payloadBytes())
                    .isEqualTo(FrozenSnapshotCodec.freeze(source).payloadBytes());
            assertThat(frozen.payload().size()).isEqualTo(7);
            assertThat(frozen.payload().get("resources").size()).isEqualTo(11);
            assertThat(frozen.payload().get("policy").get("limitSec").intValue()).isEqualTo(731);
        }
    }

    @Test
    void producerCopiesSourceAndReturnedNodesPreservingNullEmptyAndNestedArrayOrder() {
        ObjectNode source = FrozenSnapshotContractTest.complete();
        ObjectNode person = (ObjectNode) source.get("resources").get("persons").get(0);
        person.putNull("publicText").put("secretText", "");
        ArrayNode persons = (ArrayNode) source.get("resources").get("persons");
        JsonNode first = persons.remove(0);
        persons.add(first);
        byte[] before = SnapshotJson.encode(source);
        var frozen = produce(source);
        assertThat(SnapshotJson.encode(source)).isEqualTo(before);
        JsonNode payload = frozen.payload();
        assertThat(payload.get("resources").get("persons").get(0).get("code").textValue())
                .isEqualTo("P1");
        assertThat(payload.get("resources").get("persons").get(0).get("publicText").isNull())
                .isTrue();
        assertThat(payload.get("resources").get("persons").get(0).get("secretText").textValue())
                .isEmpty();
        for (int i = 0; i < source.get("resources").get("rubrics").size(); i++) {
            JsonNode original = source.get("resources").get("rubrics").get(i);
            for (JsonNode row : payload.get("resources").get("rubrics")) {
                if (row.get("code").equals(original.get("code")))
                    assertThat(SnapshotJson.encode(row.get("ruleData")))
                            .isEqualTo(SnapshotJson.encode(original.get("ruleData")));
            }
        }
        byte[] bytes = frozen.payloadBytes();
        String hash = frozen.payloadHash();
        basic(source).put("title", "변경된 원고");
        ((ObjectNode) payload.get("sections").get("basic")).put("title", "반환 노드 변경");
        byte[] exposed = frozen.payloadBytes();
        exposed[0] = 0;
        assertThat(frozen.payloadBytes()).isEqualTo(bytes);
        assertThat(frozen.payloadHash()).isEqualTo(hash);
    }

    @Test
    void exactIntegralDecimalIsAcceptedButMissingFractionalAndOutOfRangeInputsAreNotFilled() {
        ObjectNode source = FrozenSnapshotContractTest.complete();
        basic(source)
                .put("difficulty", new BigDecimal("2.0"))
                .put("limitSec", new BigDecimal("900.000"));
        assertThat(
                        produce(source)
                                .payload()
                                .get("sections")
                                .get("basic")
                                .get("difficulty")
                                .decimalValue())
                .isEqualByComparingTo("2");
        for (String field : List.of("difficulty", "limitSec")) {
            ObjectNode missing = FrozenSnapshotContractTest.complete();
            basic(missing).remove(field);
            assertThatThrownBy(() -> produce(missing))
                    .hasMessage("INVALID_FROZEN_SNAPSHOT")
                    .hasNoCause();
            ObjectNode fractional = FrozenSnapshotContractTest.complete();
            basic(fractional).put(field, new BigDecimal("1.5"));
            assertThatThrownBy(() -> produce(fractional))
                    .hasMessage("INVALID_FROZEN_SNAPSHOT")
                    .hasNoCause();
            ObjectNode huge = FrozenSnapshotContractTest.complete();
            basic(huge).put(field, new BigDecimal("2147483648"));
            assertThatThrownBy(() -> produce(huge))
                    .hasMessage("INVALID_FROZEN_SNAPSHOT")
                    .hasNoCause();
        }
        assertThatThrownBy(
                        () ->
                                StoryFrozenSnapshotProducer.freeze(
                                        "CASE",
                                        1,
                                        -1,
                                        "RULE_20260924",
                                        source.get("sections"),
                                        source.get("resources")))
                .hasMessage("INVALID_FROZEN_SNAPSHOT")
                .hasNoCause();
        assertThatThrownBy(
                        () ->
                                StoryFrozenSnapshotProducer.freeze(
                                        "CASE",
                                        1,
                                        0,
                                        "H2",
                                        source.get("sections"),
                                        source.get("resources")))
                .hasMessage("INVALID_FROZEN_SNAPSHOT")
                .hasNoCause();
    }

    @Test
    void nullableRequiredNoticesRemainNullInWholeDatasetAndSelectedProjection() {
        ObjectNode source = FrozenSnapshotContractTest.complete();
        for (String code : List.of("METHOD", "EVIDENCE"))
            rule(source, code).putNull("requiredNotice");
        var frozen = produce(source);
        var dataset = new FrozenDatasetValidator().validate(frozen);
        assertThat(
                        dataset.gradingSnapshot().rubrics().stream()
                                .filter(r -> List.of("METHOD", "EVIDENCE").contains(r.code())))
                .allSatisfy(
                        r -> {
                            assertThat(r.required()).isTrue();
                            assertThat(r.requiredNotice()).isNull();
                            assertThat(r.passScore()).isPositive();
                        });
        var projection = FrozenModelProjection.project(dataset, dataset.select("FULL"));
        for (JsonNode row : projection.payload().get("gradingContext").get("rubrics"))
            if (List.of("METHOD", "EVIDENCE").contains(row.get("code").textValue()))
                assertThat(row.get("ruleData").get("requiredNotice").isNull()).isTrue();
    }

    @Test
    void nonNullNoticeStillRequiresMeaningfulTwoHundredCodePointsAndCulpritStaysFixed() {
        ObjectNode valid = FrozenSnapshotContractTest.complete();
        rule(valid, "METHOD").put("requiredNotice", "😀".repeat(200));
        var method =
                new FrozenDatasetValidator()
                        .validate(produce(valid)).gradingSnapshot().rubrics().stream()
                                .filter(r -> "METHOD".equals(r.code()))
                                .findFirst()
                                .orElseThrow();
        assertThat(method.requiredNotice()).isEqualTo("😀".repeat(200));
        for (String value : List.of("", " \t\n", "😀".repeat(201))) {
            ObjectNode invalid = FrozenSnapshotContractTest.complete();
            rule(invalid, "METHOD").put("requiredNotice", value);
            assertThatThrownBy(() -> new FrozenDatasetValidator().validate(produce(invalid)))
                    .hasMessage("INVALID_FROZEN_SNAPSHOT")
                    .hasNoCause();
        }
        ObjectNode culprit = FrozenSnapshotContractTest.complete();
        rule(culprit, "CULPRIT").putNull("requiredNotice");
        assertThatThrownBy(() -> new FrozenDatasetValidator().validate(produce(culprit)))
                .hasMessage("INVALID_FROZEN_DATASET")
                .hasNoCause();
        ObjectNode optional = FrozenSnapshotContractTest.complete();
        rule(optional, "TIME").put("requiredNotice", "선택 항목에 잘못 넣은 안내");
        assertThatThrownBy(() -> new FrozenDatasetValidator().validate(produce(optional)))
                .hasMessage("INVALID_FROZEN_DATASET")
                .hasNoCause();
    }

    /**
     * 완전 문법용 합성 원고를 순수 producer로 전달하며 검수 준비 여부는 별도 서비스가 판단한다.
     *
     * @param source null이 아닌 전체 합성 원고
     * @return 입력과 분리된 고정 사본
     */
    private static FrozenSnapshotCodec.FrozenSnapshot produce(ObjectNode source) {
        return StoryFrozenSnapshotProducer.freeze(
                source.get("storyCode").textValue(),
                source.get("versionNo").intValue(),
                Long.parseLong(source.get("sourceRev").textValue()),
                "RULE_20260924",
                source.get("sections"),
                source.get("resources"));
    }

    private static ObjectNode basic(ObjectNode source) {
        return (ObjectNode) source.get("sections").get("basic");
    }

    private static ObjectNode rule(ObjectNode source, String code) {
        for (JsonNode row : source.get("resources").get("rubrics"))
            if (code.equals(row.get("code").textValue())) return (ObjectNode) row.get("ruleData");
        throw new AssertionError("합성 소항목 누락");
    }
}
