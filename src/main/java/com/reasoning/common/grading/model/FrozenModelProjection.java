package com.reasoning.common.grading.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.service.FrozenDatasetValidator.SelectedSample;
import com.reasoning.common.grading.service.FrozenDatasetValidator.ValidatedDataset;

/** 검증된 전체 서버 자료에서 허용된 의미 자료와 단 하나의 정상 REPORT만 명시 투영한다. */
public final class FrozenModelProjection {
    private FrozenModelProjection() {}

    /**
     * 기대값·다른 보고서·장애·답안/공개 원고·정답 코드·서버 범인 명제를 모델 바이트에서 제외한다. CULPRIT 저장 규칙은 그대로 두고 모델 사본에서만 claims와
     * 단계 routes를 비운다.
     *
     * @param dataset 전체 참조와 fixture 검증을 통과한 같은 고정 집합
     * @param selected 해당 집합에서 선택한 정상 REPORT; INPUT_ERROR는 허용하지 않는다
     * @return 독점 바이트 소유권을 가진 투영이며 실행 채점표를 대신할 수 없다
     * @throws IllegalArgumentException null·다른 집합·INPUT_ERROR이면 원인 없는 INVALID_MODEL_PROJECTION
     */
    public static Projection project(ValidatedDataset dataset, SelectedSample selected) {
        if (dataset == null || selected == null || selected.report() == null) throw invalid();
        try {
            if (dataset.select(selected.code()) != selected) throw invalid();
            JsonNode resources = dataset.frozenSnapshot().payload().get("resources");
            ObjectNode context = object();
            context.set(
                    "facts", rows(resources.get("facts"), "code", "statement", "truth", "basis"));
            context.set(
                    "persons",
                    rows(resources.get("persons"), "code", "name", "publicText", "secretText"));
            context.set(
                    "clues",
                    rows(
                            resources.get("clues"),
                            "code",
                            "title",
                            "body",
                            "personCode",
                            "scope",
                            "sourceText"));
            ArrayNode rubrics = JsonNodeFactory.instance.arrayNode();
            for (JsonNode source : resources.get("rubrics")) {
                ObjectNode rubric =
                        fields(
                                source,
                                "code",
                                "category",
                                "maxScore",
                                "requiredYn",
                                "passScore",
                                "acceptedText",
                                "partialText",
                                "rejectText");
                JsonNode sourceRule = source.get("ruleData");
                ObjectNode rule = fields(sourceRule, "formatNo", "requiredNotice");
                if ("CULPRIT".equals(source.get("category").textValue())) {
                    rule.putArray("claims");
                    ArrayNode levels = rule.putArray("levels");
                    for (JsonNode sourceLevel : sourceRule.get("levels")) {
                        ObjectNode level = fields(sourceLevel, "code", "score");
                        level.putArray("routes");
                        levels.add(level);
                    }
                } else {
                    rule.set("claims", sourceRule.get("claims").deepCopy());
                    rule.set("levels", sourceRule.get("levels").deepCopy());
                }
                rule.set("contradictions", sourceRule.get("contradictions").deepCopy());
                rubric.set("ruleData", rule);
                rubrics.add(rubric);
            }
            context.set("rubrics", rubrics);
            context.set(
                    "rubricClues",
                    rows(resources.get("rubricClues"), "rubricCode", "clueCode", "linkText"));
            GradeModels.Report report = selected.report();
            ObjectNode payload = object().put("formatNo", 1);
            payload.set("gradingContext", context);
            payload.set(
                    "report",
                    object().put("culpritCode", report.culpritCode())
                            .put("method", report.method())
                            .put("time", report.time())
                            .put("motive", report.motive())
                            .put("evidence", report.evidence()));
            return new Projection(SnapshotJson.encode(payload));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 표준 바이트만 소유하며 노드/바이트를 공유하지 않는 모델 전용 값이다. */
    public static final class Projection {
        private final byte[] bytes;

        private Projection(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        public byte[] payloadBytes() {
            return bytes.clone();
        }

        public JsonNode payload() {
            return SnapshotJson.parse(bytes);
        }
    }

    /** 자원 종류별 허용 필드를 새 객체에 복사한다. 제외 목록 기반 삭제는 사용하지 않는다. */
    private static ArrayNode rows(JsonNode sources, String... allowed) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (JsonNode source : sources) array.add(fields(source, allowed));
        return array;
    }

    private static ObjectNode fields(JsonNode source, String... allowed) {
        ObjectNode result = object();
        for (String field : allowed) result.set(field, source.get(field).deepCopy());
        return result;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_MODEL_PROJECTION");
    }
}
