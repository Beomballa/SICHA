package com.reasoning.common.grading.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.grading.model.FrozenModelProjection.RubricCoordinates;
import com.reasoning.common.grading.model.GradeModels.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 보고서와 의미 출력의 신뢰 경계만 검사한다. 의미 추론·점수 계산·외부 호출은 하지 않는다. */
public final class GradeResultValidator {
    private static final ObjectMapper MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(12)
                                                    .maxStringLength(10000)
                                                    .maxNumberLength(20)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * REPORT-1 JSON을 정규화한다. 추가 봉투나fault 필드는 허용하지 않는다.
     *
     * @param json null이 아닌 JSON이며 정확히 다섯 필드가 필요하다. 전송 바이트 상한은 호출 경계가 담당한다
     * @return 코드·Unicode·LF·서술 길이를 검증한 보고서
     * @throws IllegalArgumentException 잘못된 입력일 때 INVALID_REPORT
     */
    public Report parseReport(String json) {
        try {
            JsonNode root = parse(json);
            object(root, Set.of("culpritCode", "method", "time", "motive", "evidence"));
            return new Report(
                    text(root.get("culpritCode")),
                    text(root.get("method")),
                    text(root.get("time")),
                    text(root.get("motive")),
                    text(root.get("evidence")));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("INVALID_REPORT");
        }
    }

    /**
     * 엔진 원문을 엄격하게 파싱하여 고정 입력의 코드·구간과 대조한다. 점수나성공 필드는 거절한다.
     *
     * @param json null이 아닌 의미 결과 JSON이며 전송 바이트 상한은 실행 어댑터가 담당한다
     * @param report null이 아닌 정규화된 고정 REPORT-1
     * @param snapshot null이 아닌 완성 고정 채점표
     * @return COMPLETE의 전체 의미 결과 또는 items=null인 UNRESOLVED
     * @throws IllegalArgumentException 고정 인수가 null이면 INVALID_GRADE_INPUT, 출력 오류면
     *     INVALID_ENGINE_OUTPUT
     */
    public SemanticResult validate(String json, Report report, Snapshot snapshot) {
        inputs(report, snapshot);
        return validate(json, report, coordinates(snapshot));
    }

    /**
     * 답안 없는 등록 좌표로 출력 구조·전체 코드·보고서 구간을 검사한다. 점수 계산이나 누락 수선은 하지 않는다.
     *
     * @param json 전송 경계에서 크기를 제한한 의미 JSON
     * @param report null이 아닌 정확한 정규화 REPORT-1
     * @param coordinates 중복 없는 등록 순서의 전체 소항목 좌표
     * @return COMPLETE 전체 의미 출력 또는 items=null인 UNRESOLVED
     * @throws IllegalArgumentException 입력 좌표가 무효이면 INVALID_GRADE_INPUT, 출력 오류면
     *     INVALID_ENGINE_OUTPUT
     */
    public SemanticResult validate(
            String json, Report report, List<RubricCoordinates> coordinates) {
        coordinateInputs(report, coordinates);
        try {
            JsonNode root = parse(json);
            object(root, Set.of("formatNo", "status", "items"));
            integer(root.get("formatNo"), 1, 1);
            Status status = Status.valueOf(text(root.get("status")));
            SemanticResult result;
            if (status == Status.UNRESOLVED) {
                if (!root.get("items").isNull()) invalid();
                result = new SemanticResult(status, null);
            } else {
                List<SemanticItem> items = new ArrayList<>();
                for (JsonNode item : array(root.get("items"), 1, 50)) {
                    object(item, Set.of("rubricCode", "claims", "contradictions", "reason"));
                    items.add(
                            new SemanticItem(
                                    text(item.get("rubricCode")),
                                    propositions(item.get("claims"), 20),
                                    propositions(item.get("contradictions"), 10),
                                    text(item.get("reason"))));
                }
                result = new SemanticResult(status, items);
            }
            return validate(report, coordinates, result);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("INVALID_ENGINE_OUTPUT");
        }
    }

    /**
     * 직접 구성된 값도 동일하게 대조하므로 JSON 파싱을 우회해 누락·중복·범위 오류를 계산할 수 없다.
     *
     * @param report null이 아닌 정규화 보고서
     * @param snapshot null이 아닌 완성 채점표
     * @param result null이 아닌 의미 출력이며 범인 claims는 반드시 빈 목록이다
     * @return 대조를 통과한 불변 의미 출력
     * @throws IllegalArgumentException 고정 인수가 null이면 INVALID_GRADE_INPUT, 출력 오류면
     *     INVALID_ENGINE_OUTPUT
     */
    public SemanticResult validate(Report report, Snapshot snapshot, SemanticResult result) {
        inputs(report, snapshot);
        return validate(report, coordinates(snapshot), result);
    }

    /**
     * 실제 서버 사본에서 답안 없이 동일한 공개 출력 좌표를 추출한다. 서버 사본의 채점 책임은 이동하지 않는다.
     *
     * @param snapshot null이 아닌 실제 완성 서버 채점표
     * @return 수정 불가능한 등록 순서 좌표이며 범인 claims는 빈 목록이다
     * @throws IllegalArgumentException 사본이 null이면 INVALID_GRADE_INPUT
     */
    public static List<RubricCoordinates> coordinates(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("INVALID_GRADE_INPUT");
        return snapshot.rubrics().stream()
                .map(
                        rubric ->
                                new RubricCoordinates(
                                        rubric.code(),
                                        rubric.category() == Category.CULPRIT
                                                ? List.of()
                                                : rubric.claims().stream()
                                                        .map(ClaimRule::code)
                                                        .toList(),
                                        rubric.contradictions().stream()
                                                .map(ContradictionRule::code)
                                                .toList()))
                .toList();
    }

    /**
     * 직접 구성한 결과도 같은 좌표·코드포인트 경계로 검사한다. 결과 순서를 변경하거나 점수를 생성하지 않는다.
     *
     * @param report null이 아닌 정확한 REPORT-1
     * @param coordinates 중복 없는 전체 등록 좌표
     * @param result null이 아닌 불변 의미 결과
     * @return 대조를 통과한 원래 의미 결과
     * @throws IllegalArgumentException 입력 또는 의미 출력이 무효인 경우 원문 없는 고정 오류
     */
    public SemanticResult validate(
            Report report, List<RubricCoordinates> coordinates, SemanticResult result) {
        coordinateInputs(report, coordinates);
        if (result == null) invalid();
        if (result.status() == Status.UNRESOLVED) return result;

        Map<String, RubricCoordinates> rubrics =
                coordinates.stream()
                        .collect(
                                Collectors.toMap(
                                        RubricCoordinates::rubricCode, Function.identity()));
        Set<String> seen = new HashSet<>();
        for (SemanticItem item : result.items()) {
            RubricCoordinates rubric = rubrics.get(item.rubricCode());
            if (rubric == null || !seen.add(item.rubricCode())) invalid();
            check(item.claims(), Set.copyOf(rubric.claimCodes()), report);
            check(item.contradictions(), Set.copyOf(rubric.contradictionCodes()), report);
        }
        if (!seen.equals(rubrics.keySet())) invalid();
        return result;
    }

    /** 코드별로 정확히 한 번 판정되었는지와 정규화 보고서의 코드포인트 범위를 확인한다. */
    private static void check(List<Proposition> propositions, Set<String> expected, Report report) {
        Set<String> seen = new HashSet<>();
        for (Proposition proposition : propositions) {
            if (!expected.contains(proposition.code()) || !seen.add(proposition.code())) invalid();
            for (Span span : proposition.spans()) {
                String field = report.text(span.field());
                if (span.end() > field.codePointCount(0, field.length())) invalid();
            }
        }
        if (!seen.equals(expected)) invalid();
    }

    private static List<Proposition> propositions(JsonNode node, int max) {
        List<Proposition> results = new ArrayList<>();
        for (JsonNode proposition : array(node, 0, max)) {
            object(proposition, Set.of("code", "met", "spans"));
            if (!proposition.get("met").isBoolean()) invalid();
            List<Span> spans = new ArrayList<>();
            for (JsonNode span : array(proposition.get("spans"), 0, 10)) {
                object(span, Set.of("field", "start", "end"));
                spans.add(
                        new Span(
                                Field.valueOf(text(span.get("field"))),
                                integer(span.get("start"), 0, Integer.MAX_VALUE),
                                integer(span.get("end"), 1, Integer.MAX_VALUE)));
            }
            results.add(
                    new Proposition(
                            text(proposition.get("code")),
                            proposition.get("met").booleanValue(),
                            spans));
        }
        return List.copyOf(results);
    }

    /** 유한 JSON만 읽으며 중복 키·후행 토큰·깊은 실행식 형태는 파서 단계에서 차단한다. */
    private static JsonNode parse(String json) {
        if (json == null) invalid();
        try {
            JsonNode result = MAPPER.readTree(json);
            if (result == null) invalid();
            return result;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("INVALID_JSON");
        }
    }

    private static void object(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) invalid();
    }

    private static JsonNode array(JsonNode node, int min, int max) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) invalid();
        return node;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) invalid();
        return node.textValue();
    }

    private static int integer(JsonNode node, int min, int max) {
        if (node == null
                || !node.isIntegralNumber()
                || !node.canConvertToInt()
                || node.intValue() < min
                || node.intValue() > max) invalid();
        return node.intValue();
    }

    private static void inputs(Report report, Snapshot snapshot) {
        if (report == null || snapshot == null)
            throw new IllegalArgumentException("INVALID_GRADE_INPUT");
    }

    /**
     * 전체 출력 좌표의 입력 경계를 확인하며 중복이나 누락을 조용히 수선하지 않는다.
     *
     * @param report null 불가인 정규화 REPORT-1
     * @param coordinates null 불가인 5~50개 전체 소항목의 중복 없는 좌표
     * @throws IllegalArgumentException null·개수·중복 오류이면 INVALID_GRADE_INPUT
     */
    private static void coordinateInputs(Report report, List<RubricCoordinates> coordinates) {
        if (report == null
                || coordinates == null
                || coordinates.size() < 5
                || coordinates.size() > 50)
            throw new IllegalArgumentException("INVALID_GRADE_INPUT");
        Set<String> seen = new HashSet<>();
        for (RubricCoordinates coordinate : coordinates) {
            if (coordinate == null || !seen.add(coordinate.rubricCode()))
                throw new IllegalArgumentException("INVALID_GRADE_INPUT");
        }
    }

    private static void invalid() {
        throw new IllegalArgumentException("INVALID_ENGINE_OUTPUT");
    }
}
