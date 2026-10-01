package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.grading.model.GradeModels.*;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.util.CommonUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 같은 불변 사본 전체의 참조·유한 채점 규칙·fixture 완성도를 검사한다. 의미 추론·현재 권한·사람 승인은 없다. */
public final class FrozenDatasetValidator {
    private final GradeResultValidator reports = new GradeResultValidator();

    /**
     * 선택 이전에 오류 사례까지 포함한 전체 집합을 검사한다. GradeModels가 유한 규칙 구조를 검증한다.
     *
     * @param frozen null이 아닌 형식1 전체 사본
     * @return 기대값은 서버에만 유지하는 불변 검증 집합
     * @throws IllegalArgumentException 참조·규칙·fixture·확인·커버 오류이면 원인 없는 INVALID_FROZEN_DATASET
     */
    public ValidatedDataset validate(FrozenSnapshot frozen) {
        try {
            if (frozen == null) throw invalid();
            JsonNode payload = frozen.payload();
            JsonNode resources = payload.get("resources");
            Set<String> persons = codes(resources.get("persons"));
            references(payload, resources, persons);
            Snapshot grading = gradingSnapshot(payload, resources);
            Map<String, Rubric> rubrics =
                    grading.rubrics().stream()
                            .collect(Collectors.toMap(Rubric::code, Function.identity()));
            Map<String, Set<Integer>> covered = new HashMap<>();
            rubrics.keySet().forEach(code -> covered.put(code, new HashSet<>()));
            List<SampleValue> samples = new ArrayList<>();
            Set<String> kinds = new HashSet<>();
            for (JsonNode sample : resources.get("gradeSamples")) {
                samples.add(sample(sample, persons, rubrics, covered));
                kinds.add(sample.get("expectData").get("kind").textValue());
            }
            if (!kinds.containsAll(Set.of("GRADED", "INPUT_ERROR", "ENGINE_ERROR")))
                throw invalid();
            for (Rubric rubric : grading.rubrics()) {
                Set<Integer> scores =
                        rubric.levels().stream().map(Level::score).collect(Collectors.toSet());
                if (!covered.get(rubric.code()).containsAll(scores)) throw invalid();
            }
            return new ValidatedDataset(frozen, grading, samples);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 저장 ruleData에서 명시 매핑한다. 설명문을 파싱하거나 기대 점수로 규칙을 만들지 않는다. */
    private static Snapshot gradingSnapshot(JsonNode payload, JsonNode resources) {
        Map<String, Set<String>> linked = new HashMap<>();
        for (JsonNode link : resources.get("rubricClues")) {
            linked.computeIfAbsent(link.get("rubricCode").textValue(), unused -> new HashSet<>())
                    .add(link.get("clueCode").textValue());
        }
        List<Rubric> rubrics = new ArrayList<>();
        for (JsonNode row : resources.get("rubrics")) {
            JsonNode rule = row.get("ruleData");
            if (rule.isNull() || row.get("maxScore").isNull()) throw invalid();
            List<ClaimRule> claims = new ArrayList<>();
            for (JsonNode claim : rule.get("claims")) {
                claims.add(
                        new ClaimRule(
                                claim.get("code").textValue(),
                                claim.get("meaning").textValue(),
                                strings(claim.get("factCodes")),
                                routes(claim.get("exampleClueRoutes"))));
            }
            List<Level> levels = new ArrayList<>();
            for (JsonNode level : rule.get("levels")) {
                levels.add(
                        new Level(
                                level.get("code").textValue(),
                                level.get("score").intValue(),
                                routes(level.get("routes"))));
            }
            List<ContradictionRule> contradictions = new ArrayList<>();
            for (JsonNode contradiction : rule.get("contradictions")) {
                contradictions.add(
                        new ContradictionRule(
                                contradiction.get("code").textValue(),
                                contradiction.get("meaning").textValue()));
            }
            String code = row.get("code").textValue();
            rubrics.add(
                    new Rubric(
                            code,
                            Category.valueOf(row.get("category").textValue()),
                            row.get("maxScore").intValue(),
                            row.get("requiredYn").booleanValue(),
                            nullableInt(row.get("passScore")),
                            nullableText(rule.get("requiredNotice")),
                            claims,
                            levels,
                            contradictions,
                            linked.getOrDefault(code, Set.of())));
        }
        String culprit = payload.get("sections").get("answer").get("culpritCode").textValue();
        return new Snapshot(
                culprit, rubrics, codes(resources.get("facts")), codes(resources.get("clues")));
    }

    /** 모든 참조는 현재 테이블이 아니라 동일한 고정 자원 집합에 대해서만 검사한다. */
    private static void references(JsonNode payload, JsonNode resources, Set<String> persons) {
        JsonNode culprit = payload.get("sections").get("answer").get("culpritCode");
        if (culprit.isNull() || !persons.contains(culprit.textValue())) throw invalid();
        Set<String> roles = codes(resources.get("roles"));
        Set<String> clues = codes(resources.get("clues"));
        Set<String> rubrics = codes(resources.get("rubrics"));
        Map<String, String> scopes = new HashMap<>();
        for (JsonNode clue : resources.get("clues")) {
            if (!clue.get("personCode").isNull()
                    && !persons.contains(clue.get("personCode").textValue())) throw invalid();
            scopes.put(clue.get("code").textValue(), clue.get("scope").textValue());
        }
        for (JsonNode pair : resources.get("pairs")) {
            if (!roles.contains(pair.get("roleA").textValue())
                    || !roles.contains(pair.get("roleB").textValue())) throw invalid();
        }
        Set<String> assigned = new HashSet<>();
        for (JsonNode relation : resources.get("clueRoles")) {
            String clue = relation.get("clueCode").textValue();
            if (!clues.contains(clue)
                    || !roles.contains(relation.get("roleCode").textValue())
                    || !"ROLE".equals(scopes.get(clue))) throw invalid();
            assigned.add(clue);
        }
        for (var scope : scopes.entrySet()) {
            if ("ROLE".equals(scope.getValue()) && !assigned.contains(scope.getKey()))
                throw invalid();
        }
        for (JsonNode relation : resources.get("rubricClues")) {
            if (!rubrics.contains(relation.get("rubricCode").textValue())
                    || !clues.contains(relation.get("clueCode").textValue())) throw invalid();
        }
        Set<Integer> hintLevels = new HashSet<>();
        for (JsonNode hint : resources.get("hints")) {
            if (!hintLevels.add(hint.get("level").intValue())) throw invalid();
        }
    }

    /** 실제 REPORT 경계에 입력 오류를 증명하고 정상 입력은 원본 정규화 상태도 대조한다. */
    private SampleValue sample(
            JsonNode sample,
            Set<String> persons,
            Map<String, Rubric> rubrics,
            Map<String, Set<Integer>> covered) {
        if (sample.get("checkedBy").isNull()) throw invalid();
        requiredText(sample.get("reason"), 8000);
        JsonNode input = sample.get("inputData");
        JsonNode expectation = sample.get("expectData");
        if (input.isNull() || expectation.isNull()) throw invalid();
        String kind = expectation.get("kind").textValue();
        Report report = null;
        boolean valid = false;
        try {
            report =
                    reports.parseReport(
                            new String(
                                    SnapshotJson.encode(input.get("report")),
                                    StandardCharsets.UTF_8));
            valid = persons.contains(report.culpritCode());
        } catch (IllegalArgumentException rejected) {
            // INVALID_REPORT는 원문 없이 이 fixture의 입력 오류 분기를 증명한다.
        }
        Fault fault = null;
        if ("INPUT_ERROR".equals(kind)) {
            if (valid || !input.get("fault").isNull()) throw invalid();
            report = null;
        } else {
            if (!valid) throw invalid();
            for (Field field : Field.values()) {
                if (!report.text(field).equals(input.get("report").get(field.name()).textValue()))
                    throw invalid();
            }
            if ("ENGINE_ERROR".equals(kind)) {
                JsonNode control = input.get("fault");
                if (control.isNull()) throw invalid();
                fault =
                        new Fault(
                                control.get("type").textValue(),
                                control.get("failRuns").intValue());
            } else if (!input.get("fault").isNull()) throw invalid();
        }
        if ("GRADED".equals(kind)) {
            graded(sample, expectation, rubrics, covered);
        } else {
            if (!expectation.get("items").isNull()
                    || !sample.get("expectedScore").isNull()
                    || !sample.get("expectedSuccess").isNull()) throw invalid();
            JsonNode error = expectation.get("error");
            if (error.isNull()
                    || !("INPUT_ERROR".equals(kind) ? "INVALID_REPORT" : "GRADING_UNAVAILABLE")
                            .equals(error.get("code").textValue())
                    || !("INPUT_ERROR".equals(kind) ? "REJECTED" : "SYSTEM_ERROR")
                            .equals(error.get("state").textValue())
                    || !error.get("score").isNull()
                    || error.get("attemptDelta").intValue() != 0) throw invalid();
        }
        return new SampleValue(
                sample.get("code").textValue(),
                kind,
                report,
                fault,
                SnapshotJson.encode(expectation),
                nullableInt(sample.get("expectedScore")),
                sample.get("expectedSuccess").isNull()
                        ? null
                        : sample.get("expectedSuccess").booleanValue());
    }

    /** 의미 판정 없이 등록 단계·필수 조건·정확한 전체 소항목 집합·합계만 대조한다. */
    private static void graded(
            JsonNode sample,
            JsonNode expectation,
            Map<String, Rubric> rubrics,
            Map<String, Set<Integer>> covered) {
        JsonNode items = expectation.get("items");
        if (!expectation.get("error").isNull()
                || !items.isArray()
                || items.size() != rubrics.size()
                || sample.get("expectedScore").isNull()
                || sample.get("expectedSuccess").isNull()) throw invalid();
        Set<String> seen = new HashSet<>();
        int sum = 0;
        boolean success = true;
        for (JsonNode item : items) {
            Rubric rubric = rubrics.get(item.get("rubricCode").textValue());
            if (rubric == null || !seen.add(rubric.code())) throw invalid();
            int score = item.get("score").intValue();
            if (rubric.levels().stream().noneMatch(level -> level.score() == score))
                throw invalid();
            JsonNode met = item.get("requiredMet");
            if (rubric.required()) {
                boolean passed = score >= rubric.passScore();
                if (!met.isBoolean() || met.booleanValue() != passed) throw invalid();
                success &= passed;
            } else if (!met.isNull()) throw invalid();
            requiredText(item.get("reason"), 1000);
            covered.get(rubric.code()).add(score);
            sum += score;
        }
        if (!seen.equals(rubrics.keySet())
                || sum != sample.get("expectedScore").intValue()
                || success != sample.get("expectedSuccess").booleanValue()) throw invalid();
    }

    /** 합성 장애는 모델 데이터와 분리한 불변 하네스 값이다. 예약 횟수만으로 회복 소진을 판정하지 않는다. */
    public record Fault(String type, int failRuns) {
        public Fault {
            if (type == null || !Set.of("TIMEOUT", "UNAVAILABLE").contains(type) || failRuns != 3)
                throw invalid();
        }
    }

    /** 원본 사본과 유한 채점 값은 불변이며 기대 JSON은 바이트로만 보관한다. */
    public static final class ValidatedDataset {
        private final FrozenSnapshot frozen;
        private final Snapshot grading;
        private final Map<String, SelectedSample> samples;

        private ValidatedDataset(
                FrozenSnapshot frozen, Snapshot grading, List<SampleValue> values) {
            this.frozen = frozen;
            this.grading = grading;
            Map<String, SelectedSample> selected = new HashMap<>();
            for (SampleValue value : values) selected.put(value.code(), new SelectedSample(value));
            samples = Map.copyOf(selected);
        }

        public FrozenSnapshot frozenSnapshot() {
            return frozen;
        }

        public Snapshot gradingSnapshot() {
            return grading;
        }

        /**
         * 전체 검증이 끝난 집합에서 코드 하나만 선택한다.
         *
         * @param sampleCode 같은 사본의 정확한 코드이며 null/미등록은 거절한다
         * @return 유일한 불변 선택값
         * @throws IllegalArgumentException 미등록이면 고정 오류
         */
        public SelectedSample select(String sampleCode) {
            if (sampleCode == null || !samples.containsKey(sampleCode)) throw invalid();
            return samples.get(sampleCode);
        }
    }

    /** INPUT_ERROR만 report=null이다. 기대값·장애 제어를 모델 보고서와 혼합하지 않는다. */
    public static final class SelectedSample {
        private final String code;
        private final String kind;
        private final Report report;
        private final Fault fault;
        private final byte[] expectation;
        private final Integer expectedScore;
        private final Boolean expectedSuccess;

        private SelectedSample(SampleValue value) {
            code = value.code();
            kind = value.kind();
            report = value.report();
            fault = value.fault();
            expectation = value.expectation().clone();
            expectedScore = value.expectedScore();
            expectedSuccess = value.expectedSuccess();
        }

        public String code() {
            return code;
        }

        public String kind() {
            return kind;
        }

        public Report report() {
            return report;
        }

        public Fault fault() {
            return fault;
        }

        /** 서버 비교 전용이며 매 호출마다 새 노드를 소유한다. */
        public JsonNode expectation() {
            return SnapshotJson.parse(expectation);
        }

        public Integer expectedScore() {
            return expectedScore;
        }

        public Boolean expectedSuccess() {
            return expectedSuccess;
        }
    }

    private record SampleValue(
            String code,
            String kind,
            Report report,
            Fault fault,
            byte[] expectation,
            Integer expectedScore,
            Boolean expectedSuccess) {}

    private static Set<String> codes(JsonNode rows) {
        Set<String> result = new HashSet<>();
        for (JsonNode row : rows) result.add(row.get("code").textValue());
        return Set.copyOf(result);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.textValue()));
        return List.copyOf(values);
    }

    private static List<List<String>> routes(JsonNode array) {
        List<List<String>> values = new ArrayList<>();
        array.forEach(value -> values.add(strings(value)));
        return List.copyOf(values);
    }

    private static Integer nullableInt(JsonNode node) {
        return node.isNull() ? null : node.intValue();
    }

    private static String nullableText(JsonNode node) {
        return node.isNull() ? null : node.textValue();
    }

    private static void requiredText(JsonNode node, int max) {
        if (!node.isTextual()
                || !CommonUtil.normalizeText(node.textValue(), max, false).equals(node.textValue()))
            throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_FROZEN_DATASET");
    }
}
