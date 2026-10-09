package com.reasoning.common.grading.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.GradeModels.Report;
import com.reasoning.common.grading.service.FrozenDatasetValidator.SelectedSample;
import com.reasoning.common.grading.service.FrozenDatasetValidator.ValidatedDataset;
import com.reasoning.common.grading.service.GradeResultValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.util.CommonUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 서버 집합에서 답안 없는 의미 자료를 투영하고 정규 MODEL 자료를 해독한다. 해독은 집합 소속이나 실행 권한을 증명하지 않는다. */
public final class FrozenModelProjection {
    private FrozenModelProjection() {}

    /**
     * 실제 검증 집합의 정상 선택을 답안 없는 불변 의미 입력으로 만든다. 실행 권한이나 서버 채점표를 대신하지 않는다.
     *
     * @param dataset 전체 참조와 fixture 검증을 통과한 같은 고정 집합
     * @param selected 해당 집합에서 선택한 정상 REPORT; INPUT_ERROR는 허용하지 않는다
     * @return 정규 바이트·정확한 보고서·등록 순서 좌표를 독점 소유하는 모델 입력
     * @throws IllegalArgumentException null·다른 집합·INPUT_ERROR이면 원인 없는 INVALID_MODEL_PROJECTION
     */
    public static SemanticInput project(ValidatedDataset dataset, SelectedSample selected) {
        if (dataset == null || selected == null || selected.report() == null) throw invalid();
        try {
            if (dataset.select(selected.code()) != selected) throw invalid();
            return projectPayload(dataset.frozenSnapshot(), selected.report());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * 실제 고정 사본과 검증된 회원 REPORT를 BATCH와 동일한 답안 없는 의미 입력으로 투영한다. 사본의 실행 자격·현재성·완성도는 호출자가 먼저 검사하며
     * fixture는 읽지 않는다.
     *
     * @param frozen 스키마·정렬을 통과한 실제 전체 고정 사본; null 불가
     * @param report REPORT-1 경계에서 파싱·정규화된 실제 제출값; null 불가
     * @return BATCH와 동일한 정규 바이트·보고서·등록 순서 좌표를 소유하는 입력
     * @throws IllegalArgumentException 무효 사본·보고서·투영이면 INVALID_MODEL_PROJECTION
     */
    public static SemanticInput projectTest(FrozenSnapshot frozen, Report report) {
        return projectPayload(frozen, report);
    }

    /** 두 실행 경로가 동일한 제외 목록과 정규 바이트 검증을 사용한다. */
    private static SemanticInput projectPayload(FrozenSnapshot frozen, Report report) {
        if (frozen == null || report == null) throw invalid();
        try {
            byte[] projected =
                    SnapshotJson.encode(payload(frozen.payload().get("resources"), report));
            SemanticInput input = decodeCanonical(projected);
            if (!Arrays.equals(input.payloadBytes(), projected) || !input.report().equals(report))
                throw invalid();
            return input;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * 정규 MODEL 바이트의 닫힌 구조만 검증한다. 서버 소속·현재성·실행 소유권은 증명하지 않는다.
     *
     * @param canonicalBytes null 불가인 SnapshotJson 정규 바이트; 별도 raw 크기 상한은 추가하지 않는다
     * @return 원본 바이트·정확한 REPORT·순서 좌표만 방어 소유하는 불변 자료
     * @throws IllegalArgumentException UTF-8·정규 표기·구조·참조·범위 오류이면 원인 없는 INVALID_MODEL_PROJECTION
     */
    public static SemanticInput decodeCanonical(byte[] canonicalBytes) {
        try {
            if (canonicalBytes == null) throw invalid();
            byte[] owned = canonicalBytes.clone();
            JsonNode root = SnapshotJson.parse(owned);
            if (!Arrays.equals(owned, SnapshotJson.encode(root))) throw invalid();

            validateModel(root);
            return new SemanticInput(owned, root);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw invalid();
        }
    }

    /**
     * 기대값·다른 보고서·장애·답안/공개 원고·정답 코드·서버 범인 명제를 제외한다. CULPRIT 저장 규칙은 그대로 두고 모델 사본에서만 claims와 단계
     * routes를 비운다. 전체 값 검증은 BATCH의 FrozenDatasetValidator 또는 TEST의 실제 소스 검증 경계가 담당한다.
     *
     * @param resources 고정 사본의 자원 배열 노드
     * @param report 정규화된 정상 REPORT 선택값 또는 실제 제출값
     * @return 정확한 기존 허용 필드와 배열 순서를 보존한 새 투영 노드
     * @throws IllegalArgumentException 불완전한 규칙이면 원인 없는 고정 오류
     */
    private static JsonNode payload(JsonNode resources, Report report) {
        try {
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
                if (sourceRule == null || sourceRule.isNull()) throw invalid();
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
            ObjectNode payload = object().put("formatNo", 1);
            payload.set("gradingContext", context);
            payload.set(
                    "report",
                    object().put("culpritCode", report.culpritCode())
                            .put("method", report.method())
                            .put("time", report.time())
                            .put("motive", report.motive())
                            .put("evidence", report.evidence()));
            return payload;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 표준 바이트·정확한 보고서·등록 순서 좌표만 소유한다. 실행 인가나 서버 채점표가 아니다. */
    public static final class SemanticInput {
        private final byte[] bytes;
        private final Report report;
        private final List<RubricCoordinates> orderedRubricCoordinates;

        /**
         * 엄격 decoder가 소유한 바이트에서만 REPORT와 좌표를 만든다. 원본 집합·선택·호출자 REPORT는 받지 않는다.
         *
         * @param canonical null 불가인 decoder 독점 정규 바이트
         * @param owned null 불가인 해당 바이트에서 파싱·구조 검증한 노드
         * @throws IllegalArgumentException REPORT 정규화로 원문이 바뀌면 원인 없는 고정 오류
         */
        private SemanticInput(byte[] canonical, JsonNode owned) {
            try {
                report =
                        new GradeResultValidator()
                                .parseReport(
                                        new String(
                                                SnapshotJson.encode(owned.get("report")),
                                                StandardCharsets.UTF_8));
                JsonNode raw = owned.get("report");
                if (!report.culpritCode().equals(raw.get("culpritCode").textValue())
                        || !report.method().equals(raw.get("method").textValue())
                        || !report.time().equals(raw.get("time").textValue())
                        || !report.motive().equals(raw.get("motive").textValue())
                        || !report.evidence().equals(raw.get("evidence").textValue()))
                    throw invalid();
                orderedRubricCoordinates = coordinates(owned.get("gradingContext"));
                bytes = canonical;
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
        }

        public byte[] payloadBytes() {
            return bytes.clone();
        }

        public JsonNode payload() {
            return SnapshotJson.parse(bytes);
        }

        public Report report() {
            return report;
        }

        public List<RubricCoordinates> orderedRubricCoordinates() {
            return orderedRubricCoordinates;
        }

        @Override
        public String toString() {
            return "SemanticInput[structuralData=true]";
        }
    }

    /**
     * 의미 출력의 등록 순서 자리다. 범인 선택 명제나 서버 점수 규칙을 담지 않으며 목록을 방어 복사한다.
     *
     * @param rubricCode 대문자 영숫자·밑줄 1~32자의 등록 소항목 코드
     * @param claimCodes 중복 없는 0~20개 등록 명제 코드; 범인 항목은 빈 목록
     * @param contradictionCodes 명제 코드와 겹치지 않는 0~10개 등록 모순 코드
     * @throws IllegalArgumentException null·코드 형식·중복·개수 오류이면 원인 없는 고정 오류
     */
    public record RubricCoordinates(
            String rubricCode, List<String> claimCodes, List<String> contradictionCodes) {
        public RubricCoordinates {
            rubricCode = code(rubricCode);
            claimCodes = coordinateCodes(claimCodes, 20);
            contradictionCodes = coordinateCodes(contradictionCodes, 10);
            Set<String> all = new HashSet<>(claimCodes);
            for (String contradiction : contradictionCodes) {
                if (!all.add(contradiction)) throw invalid();
            }
        }
    }

    /**
     * 구조 검증된 답안 없는 자료에서 공개 코드 좌표만 추출한다. 원본 소속을 주장하거나 배열을 정렬하지 않는다.
     *
     * @param context null 불가인 구조 검증된 다섯 자원 배열 문맥
     * @return 수정 불가능한 등록 순서 좌표; 범인 claims는 빈 목록이다
     * @throws IllegalArgumentException 코드가 중복되거나 허용 코드 경계를 벗어난 경우
     */
    private static List<RubricCoordinates> coordinates(JsonNode context) {
        List<RubricCoordinates> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode rubric : context.get("rubrics")) {
            String rubricCode = code(rubric.get("code").textValue());
            if (!seen.add(rubricCode)) throw invalid();
            JsonNode rule = rubric.get("ruleData");
            result.add(
                    new RubricCoordinates(
                            rubricCode,
                            propositionCodes(rule.get("claims")),
                            propositionCodes(rule.get("contradictions"))));
        }
        return List.copyOf(result);
    }

    /**
     * 구조 검증된 명제 배열에서 코드만 등록 순서대로 추출한다.
     *
     * @param values null이 아닌 검증된 claims 또는 contradictions 배열
     * @return 방어 복사한 수정 불가능한 코드 목록
     * @throws IllegalArgumentException 코드가 허용 형식이 아닌 경우
     */
    private static List<String> propositionCodes(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) result.add(code(value.get("code").textValue()));
        return List.copyOf(result);
    }

    /**
     * 외부에서 구성하는 좌표 목록의 형식·개수·중복을 확인하고 순서를 보존한다.
     *
     * @param codes null 불가인 코드 목록이며 빈 목록은 허용한다
     * @param max 명제는 20, 모순은 10인 포함 상한
     * @return 수정 불가능한 방어 복사 목록
     * @throws IllegalArgumentException null·상한 초과·무효 코드·중복인 경우
     */
    private static List<String> coordinateCodes(List<String> codes, int max) {
        if (codes == null || codes.size() > max) throw invalid();
        Set<String> seen = new HashSet<>();
        for (String value : codes) if (!seen.add(code(value))) throw invalid();
        return List.copyOf(codes);
    }

    /**
     * 기존 의미 판정 코드의 대문자 영숫자·밑줄 경계를 확인한다.
     *
     * @param value null 불가인 1~32자 코드
     * @return 변환하지 않은 원래 코드
     * @throws IllegalArgumentException null이거나 허용 형식이 아닌 경우
     */
    private static String code(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) throw invalid();
        return value;
    }

    /**
     * 다섯 자원의 저장 scalar 경계·정렬·실재 참조만 검사한다. 답안·분류 배점 합계는 복원하지 않는다.
     *
     * @param root null 불가인 정규 MODEL 객체
     * @throws IllegalArgumentException 닫힌 필드·유형·순서·참조가 틀리면 원인 없는 고정 오류
     */
    private static void validateModel(JsonNode root) {
        exact(root, "formatNo", "gradingContext", "report");
        integer(root.get("formatNo"), 1, 1);
        JsonNode context = root.get("gradingContext");
        exact(context, "facts", "persons", "clues", "rubrics", "rubricClues");
        Set<String> facts = orderedCodes(context.get("facts"), "code");
        Set<String> persons = orderedCodes(context.get("persons"), "code");
        Set<String> clues = orderedCodes(context.get("clues"), "code");
        Set<String> rubrics = orderedCodes(context.get("rubrics"), "code");
        array(context.get("rubrics"), 5, 50);
        orderedCodes(context.get("rubricClues"), "rubricCode", "clueCode");

        for (JsonNode row : context.get("facts")) {
            exact(row, "code", "statement", "truth", "basis");
            text(row.get("statement"), 4000, true, true);
            enumeration(row.get("truth"), true, "TRUE", "FALSE", "MISREAD");
            text(row.get("basis"), 8000, true, true);
        }
        for (JsonNode row : context.get("persons")) {
            exact(row, "code", "name", "publicText", "secretText");
            text(row.get("name"), 80, false, false);
            text(row.get("publicText"), 8000, true, true);
            text(row.get("secretText"), 8000, true, true);
        }
        for (JsonNode row : context.get("clues")) {
            exact(row, "code", "title", "body", "personCode", "scope", "sourceText");
            text(row.get("title"), 160, false, false);
            text(row.get("body"), 12000, true, true);
            if (!row.get("personCode").isNull()) reference(row.get("personCode"), persons);
            enumeration(row.get("scope"), false, "COMMON", "ROLE");
            text(row.get("sourceText"), 400, true, true);
        }
        Map<String, Set<String>> linked = new HashMap<>();
        for (JsonNode row : context.get("rubricClues")) {
            exact(row, "rubricCode", "clueCode", "linkText");
            reference(row.get("rubricCode"), rubrics);
            reference(row.get("clueCode"), clues);
            text(row.get("linkText"), 4000, true, true);
            linked.computeIfAbsent(row.get("rubricCode").textValue(), unused -> new HashSet<>())
                    .add(row.get("clueCode").textValue());
        }
        for (JsonNode row : context.get("rubrics")) {
            exact(
                    row,
                    "code",
                    "category",
                    "maxScore",
                    "requiredYn",
                    "passScore",
                    "acceptedText",
                    "partialText",
                    "rejectText",
                    "ruleData");
            enumeration(
                    row.get("category"), false, "CULPRIT", "METHOD", "TIME", "MOTIVE", "EVIDENCE");
            int max = integer(row.get("maxScore"), 1, 100);
            if (!row.get("requiredYn").isBoolean()) throw invalid();
            boolean required = row.get("requiredYn").booleanValue();
            Integer pass =
                    row.get("passScore").isNull() ? null : integer(row.get("passScore"), 1, max);
            if (required != (pass != null)) throw invalid();
            text(row.get("acceptedText"), 12000, true, true);
            text(row.get("partialText"), 12000, true, true);
            text(row.get("rejectText"), 8000, true, true);
            validateRule(
                    row.get("ruleData"),
                    "CULPRIT".equals(row.get("category").textValue()),
                    required,
                    max,
                    pass,
                    facts,
                    linked.getOrDefault(row.get("code").textValue(), Set.of()));
        }
        exact(root.get("report"), "culpritCode", "method", "time", "motive", "evidence");
        reference(root.get("report").get("culpritCode"), persons);
    }

    /**
     * 저장 규칙의 유한 배열 경계와 실제 참조를 확인하되 CULPRIT의 제거된 명제·경로는 복원하지 않는다.
     *
     * @param rule null 불가인 닫힌 규칙 객체; claims 0~20, levels 2~6, contradictions 0~10
     * @param culprit 범인 투영이면 claims 및 모든 routes는 빈 배열
     * @param required 저장 필수 여부
     * @param max 저장 최대 배점 1~100; 계산에는 사용하지 않는다
     * @param pass 필수이면 1~max의 저장 단계 점수, 선택이면 null
     * @param facts null 불가인 실제 사실 코드 집합
     * @param linked null 불가인 해당 항목의 실제 연결 단서 집합
     * @throws IllegalArgumentException 구조·좌표·범위·참조 오류이면 원인 없는 고정 오류
     */
    private static void validateRule(
            JsonNode rule,
            boolean culprit,
            boolean required,
            int max,
            Integer pass,
            Set<String> facts,
            Set<String> linked) {
        exact(rule, "formatNo", "requiredNotice", "claims", "levels", "contradictions");
        integer(rule.get("formatNo"), 1, 1);
        text(rule.get("requiredNotice"), 200, true, false);
        if (!required && !rule.get("requiredNotice").isNull()) throw invalid();
        array(rule.get("claims"), culprit ? 0 : 1, culprit ? 0 : 20);
        Set<String> claims = new HashSet<>();
        Set<String> all = new HashSet<>();
        for (JsonNode claim : rule.get("claims")) {
            exact(claim, "code", "meaning", "factCodes", "exampleClueRoutes");
            String name = code(string(claim.get("code")));
            if (!claims.add(name) || !all.add(name)) throw invalid();
            text(claim.get("meaning"), 1000, false, false);
            codeReferences(claim.get("factCodes"), 1, 20, facts);
            routes(claim.get("exampleClueRoutes"), 0, 5, 10, linked);
        }
        array(rule.get("levels"), 2, 6);
        Set<Integer> scores = new HashSet<>();
        for (JsonNode level : rule.get("levels")) {
            exact(level, "code", "score", "routes");
            if (!all.add(code(string(level.get("code"))))) throw invalid();
            int score = integer(level.get("score"), 0, max);
            if (!scores.add(score)) throw invalid();
            routes(
                    level.get("routes"),
                    culprit || score == 0 ? 0 : 1,
                    culprit || score == 0 ? 0 : 5,
                    20,
                    claims);
        }
        if (!scores.contains(0) || !scores.contains(max) || required && !scores.contains(pass))
            throw invalid();
        array(rule.get("contradictions"), 0, 10);
        for (JsonNode contradiction : rule.get("contradictions")) {
            exact(contradiction, "code", "meaning");
            if (!all.add(code(string(contradiction.get("code"))))) throw invalid();
            text(contradiction.get("meaning"), 1000, false, false);
        }
        // source ruleData의 compact 및 JSONB 128KiB 경계를 그대로 검사한다.
        if (SnapshotJson.encode(rule).length > 131072 || jsonbSize(rule) > 131072) throw invalid();
    }

    /**
     * 다섯 최상위 자원 배열만 코드/튜플 오름차순을 요구한다. 중첩 등록 배열은 정렬하지 않는다.
     *
     * @param rows null 불가인 0~Integer.MAX_VALUE 자원 배열
     * @param keys null 불가인 code 또는 rubricCode·clueCode 튜플 필드
     * @return 중복 없는 첫 번째 키의 코드 집합
     * @throws IllegalArgumentException 배열·코드·순서·튜플 중복 오류이면 고정 오류
     */
    private static Set<String> orderedCodes(JsonNode rows, String... keys) {
        array(rows, 0, Integer.MAX_VALUE);
        List<String> previous = null;
        Set<String> result = new HashSet<>();
        for (JsonNode row : rows) {
            List<String> current = new ArrayList<>();
            for (String key : keys) current.add(code(string(row.get(key))));
            if (previous != null) {
                int comparison = 0;
                for (int index = 0; index < keys.length && comparison == 0; index++)
                    comparison = previous.get(index).compareTo(current.get(index));
                if (comparison >= 0) throw invalid();
            }
            result.add(current.getFirst());
            previous = current;
        }
        return Set.copyOf(result);
    }

    /**
     * 유한 경로는 코드 배열만 허용하고 실제 참조를 검사한다. 경로 등록 순서를 보존한다.
     *
     * @param node null 불가인 min~max개 경로 배열
     * @param min 허용 최소 경로 수
     * @param max 허용 최대 경로 수
     * @param routeMax 각 경로의 최대 코드 수; 최소는 1
     * @param allowed null 불가인 실제 참조 집합
     * @throws IllegalArgumentException 배열·중복 코드·참조 오류이면 고정 오류
     */
    private static void routes(JsonNode node, int min, int max, int routeMax, Set<String> allowed) {
        array(node, min, max);
        for (JsonNode route : node) codeReferences(route, 1, routeMax, allowed);
    }

    /**
     * 코드 목록의 저장 개수·중복·실재 참조 경계를 확인한다.
     *
     * @param node null 불가인 min~max개 코드 배열
     * @param min 허용 최소 개수
     * @param max 허용 최대 개수
     * @param allowed null 불가인 해당 자원 종류의 실제 코드 집합
     * @throws IllegalArgumentException 유형·코드·중복·참조 오류이면 고정 오류
     */
    private static void codeReferences(JsonNode node, int min, int max, Set<String> allowed) {
        array(node, min, max);
        Set<String> seen = new HashSet<>();
        for (JsonNode value : node) {
            reference(value, allowed);
            if (!seen.add(value.textValue())) throw invalid();
        }
    }

    /**
     * 저장 코드 참조를 확인하며 값을 변환하지 않는다.
     *
     * @param node null 불가인 대문자 영숫자·밑줄 1~32자 코드 scalar
     * @param allowed null 불가인 같은 자료의 실제 코드 집합
     * @throws IllegalArgumentException null·무효 코드·미등록 값이면 원인 없는 고정 오류
     */
    private static void reference(JsonNode node, Set<String> allowed) {
        if (!allowed.contains(code(string(node)))) throw invalid();
    }

    /**
     * 필수 객체의 정확한 키 집합을 확인한다.
     *
     * @param node null 불가인 객체
     * @param fields null 불가인 허용 필드 전체 목록
     * @throws IllegalArgumentException null·추가·누락 키·다른 유형이면 원인 없는 고정 오류
     */
    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }

    /**
     * 배열의 포함 개수 경계를 확인한다.
     *
     * @param node null 불가인 배열
     * @param min 포함 개수 하한
     * @param max 포함 개수 상한
     * @throws IllegalArgumentException null·유형·개수 오류이면 원인 없는 고정 오류
     */
    private static void array(JsonNode node, int min, int max) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max)
            throw invalid();
    }

    /**
     * 문자열을 변환 없이 반환한다. 내용 경계는 호출자의 저장 필드 정책으로 검사한다.
     *
     * @param node null 불가인 문자열 scalar
     * @return 변환하지 않은 문자열
     * @throws IllegalArgumentException null·다른 유형이면 원인 없는 고정 오류
     */
    private static String string(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    /**
     * 저장 문자열의 Unicode·LF·코드포인트 경계를 보정 없이 확인한다.
     *
     * @param node nullable 정책에 따라서만 null을 허용하는 scalar
     * @param max 기존 저장 필드의 코드포인트 포함 상한
     * @param nullable 명시 null 허용 여부
     * @param blank 빈 문자열·공백 전용 허용 여부
     * @throws IllegalArgumentException 유형·정규화·길이 오류이면 고정 오류
     */
    private static void text(JsonNode node, int max, boolean nullable, boolean blank) {
        if (nullable && node != null && node.isNull()) return;
        String value = string(node);
        if (!CommonUtil.normalizeText(value, max, blank).equals(value)) throw invalid();
    }

    /**
     * 기존 정확한 수학적 정수 경계를 검사한다. 정규 바이트 비교가 다른 숫자 표기를 별도로 거절한다.
     *
     * @param node null 불가인 숫자
     * @param min 포함 하한
     * @param max 포함 상한
     * @return 변환 손실 없는 정수
     * @throws IllegalArgumentException 유형·소수·범위 오류이면 고정 오류
     */
    private static int integer(JsonNode node, int min, int max) {
        if (node == null || !node.isNumber()) throw invalid();
        int value = node.decimalValue().intValueExact();
        if (value < min || value > max) throw invalid();
        return value;
    }

    /**
     * 명시 null 정책과 기존 열거 문자열을 확인한다. 변환·기본값은 없다.
     *
     * @param node nullable 정책에 따라서만 명시 null을 허용하는 scalar
     * @param nullable 명시 null 허용 여부
     * @param values null 불가인 기존 열거 문자열 전체 목록
     * @throws IllegalArgumentException 유형·미등록 값이면 원인 없는 고정 오류
     */
    private static void enumeration(JsonNode node, boolean nullable, String... values) {
        if (nullable && node != null && node.isNull()) return;
        if (!Set.of(values).contains(string(node))) throw invalid();
    }

    /**
     * 검사 완료된 규칙의 JSONB 쉼표/콜론 공백 비용을 계산한다. 새 primitive 상한은 없다.
     *
     * @param node null 불가인 숫자가 0~100 정수인 검증 완료 규칙 노드
     * @return 기존 source ruleData 크기 대조용 바이트 수
     */
    private static long jsonbSize(JsonNode node) {
        if (!node.isContainerNode()) return SnapshotJson.encode(node).length;
        long size = 2 + Math.max(0, node.size() - 1) * 2L;
        if (node.isObject()) {
            for (var field : node.properties())
                size +=
                        SnapshotJson.encode(JsonNodeFactory.instance.textNode(field.getKey()))
                                        .length
                                + 2
                                + jsonbSize(field.getValue());
        } else {
            for (JsonNode value : node) size += jsonbSize(value);
        }
        return size;
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
