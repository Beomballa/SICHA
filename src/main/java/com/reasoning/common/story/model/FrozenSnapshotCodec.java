package com.reasoning.common.story.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.GradeModels;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.util.CommonUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 형식1 전체 사본의 스키마·소유권·정렬·보수적 전체 결속 해시 경계다. 권한·의미·완성도 판정은 하지 않는다. */
public final class FrozenSnapshotCodec {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Map<String, List<String>> KEYS =
            Map.ofEntries(
                    Map.entry("persons", List.of("code")),
                    Map.entry("roles", List.of("code")),
                    Map.entry("pairs", List.of("roleA", "roleB")),
                    Map.entry("clues", List.of("code")),
                    Map.entry("clueRoles", List.of("clueCode", "roleCode")),
                    Map.entry("hints", List.of("code")),
                    Map.entry("events", List.of("code")),
                    Map.entry("facts", List.of("code")),
                    Map.entry("rubrics", List.of("code")),
                    Map.entry("rubricClues", List.of("rubricCode", "clueCode")),
                    Map.entry("gradeSamples", List.of("code")));

    private FrozenSnapshotCodec() {}

    /**
     * 서버가 권위 있게 읽은 전체 입력을 복사한 뒤 자원 11배열만 코드/의미 튜플 정렬한다.
     *
     * @param authoritativePayload 해시·DB 메타데이터 없는 필수 7필드 객체; nullable 원고는 명시 null이다
     * @return 호출자 노드와 분리된 불변 사본
     * @throws IllegalArgumentException 잘못된 스키마·정책·중복·크기이면 원인 없는 INVALID_FROZEN_SNAPSHOT
     */
    public static FrozenSnapshot freeze(JsonNode authoritativePayload) {
        try {
            byte[] original = SnapshotJson.encode(authoritativePayload);
            if (original.length > MAX_BYTES) throw invalid();
            JsonNode copy = SnapshotJson.parse(original);
            validate(copy, false);
            for (var entry : KEYS.entrySet()) {
                ArrayNode array = (ArrayNode) copy.get("resources").get(entry.getKey());
                List<JsonNode> rows = new ArrayList<>();
                array.forEach(rows::add);
                rows.sort(comparator(entry.getValue()));
                array.removeAll();
                rows.forEach(array::add);
            }
            validate(copy, true);
            return new FrozenSnapshot(copy);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw invalid();
        }
    }

    /**
     * 수신 바이트를 엄격 UTF-8/JSON으로 읽고 순서도 검사한다. 원고·배열·잘못된 REPORT를 보정하지 않는다.
     *
     * @param frozenBytes null이 아닌 16MiB 이하 JSON; 입력 배열을 보관하지 않는다
     * @return 동등한 표준 바이트를 소유하는 불변 사본
     * @throws IllegalArgumentException 스키마·정책·순서·중복·크기 오류이면 고정 오류
     */
    public static FrozenSnapshot decode(byte[] frozenBytes) {
        try {
            if (frozenBytes == null || frozenBytes.length > MAX_BYTES) throw invalid();
            JsonNode payload = SnapshotJson.parse(frozenBytes.clone());
            validate(payload, true);
            return new FrozenSnapshot(payload);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw invalid();
        }
    }

    /**
     * 승인된 RULE_20260924 적용값 전체를 구성한다. H1 표시 정책을 변경하지 않는다. normalWrongDelta=1은 정상 오답에만 적용하며 정상 성공에는
     * 오답을 증가시키지 않는다. 회복 소진은 세 번째 실패 또는 고정 기한의 선도달이며 세 번째 호출 예약 자체가 아니다.
     *
     * @param policyCode 지원하는 고정 정책 코드
     * @param difficulty 1~5 난이도
     * @param limitSec 해당 원고의 양의 초 단위 제한
     * @return 호출자가 독점 소유하는 완전한 정책 객체
     * @throws IllegalArgumentException 미지원 정책·범위이면 고정 오류
     */
    public static ObjectNode createPolicy(String policyCode, int difficulty, int limitSec) {
        if (!"RULE_20260924".equals(policyCode)
                || difficulty < 1
                || difficulty > 5
                || limitSec <= 0) throw invalid();
        ObjectNode policy =
                object().put("policyCode", policyCode)
                        .put("limitSec", limitSec)
                        .put("attemptLimit", difficulty <= 2 ? 5 : difficulty <= 4 ? 3 : 2)
                        .put("hintsPerPerson", difficulty <= 2 ? 3 : difficulty <= 4 ? 2 : 1)
                        .put("wrongPenalty", 10)
                        .put("hintPenalty", 0)
                        .put("timePenalty", 0);
        policy.set(
                "categoryScores",
                object().put("CULPRIT", 25)
                        .put("METHOD", 20)
                        .put("TIME", 15)
                        .put("MOTIVE", 10)
                        .put("EVIDENCE", 30));
        policy.set(
                "scoring",
                object().put("levelSelection", "HIGHEST_SATISFIED_ROUTE_OR_ZERO")
                        .put("contradictionScore", 0)
                        .put("requiredMode", "ALL_REQUIRED_AT_PASS_SCORE")
                        .put("penaltiesAffectSuccess", false)
                        .put("baseScoreMode", "SUM_ITEM_SCORES")
                        .put(
                                "finalScoreMode",
                                "LAST_NORMAL_BASE_MINUS_WRONG_COUNT_TIMES_WRONG_PENALTY")
                        .put("minimumFinalScore", 0)
                        .put("timeLimitWithoutNormalReportScore", 0)
                        .putNull("incompleteFinalScore"));
        ObjectNode submissions =
                object().put("agreementMode", "BOTH_SAME_REPORT")
                        .put("attemptConsumption", "NORMAL_JUDGMENT_ONLY")
                        .put("normalAttemptDelta", 1)
                        .put("normalWrongDelta", 1)
                        .put("errorAttemptDelta", 0)
                        .put("retryNormalWrong", false);
        submissions
                .putArray("pendingBlocks")
                .add("REPORT_EDIT")
                .add("NEW_SUBMISSION")
                .add("NEW_HINT")
                .add("FORFEIT");
        policy.set("submissions", submissions);
        ObjectNode hints =
                object().put("requirePreviousLevel", false).put("repeatOpenConsumes", false);
        hints.putArray("levels").add(1).add(2).add(3);
        policy.set("hints", hints);
        ObjectNode time =
                object().put("clockContinuesOffline", true)
                        .put("acceptanceBoundary", "BEFORE_PLAY_DEADLINE")
                        .put("pendingAtPlayDeadline", "WAIT_FOR_FIXED_RECOVERY");
        time.putArray("normalOutcomeOrder")
                .add("SUCCESS")
                .add("TIME_LIMIT")
                .add("ATTEMPTS_EXHAUSTED")
                .add("CONTINUE");
        policy.set("time", time);
        policy.set(
                "recovery",
                object().put("timeoutSec", 120)
                        .put("maxCalls", 3)
                        .put("includesQueueAndRetryDelay", true)
                        .put("resetBudgetOnRestart", false)
                        .put("retryInput", "SAME_FROZEN_REPORT_AND_CRITERIA")
                        .put("resultBoundary", "BEFORE_RECOVERY_DEADLINE")
                        .put("exhaustionRule", "FIRST_OF_THIRD_FAILED_CALL_OR_DEADLINE")
                        .put("exhaustedOutcome", "SYSTEM_ERROR"));
        return policy;
    }

    /** 원본 노드 대신 표준 바이트만 소유한다. 모든 노드/배열 반환은 새 호출자 소유 값이다. */
    public static final class FrozenSnapshot {
        private final byte[] bytes;
        private final String payloadHash;
        private final String rubricHash;
        private final String datasetHash;

        private FrozenSnapshot(JsonNode payload) {
            bytes = SnapshotJson.encode(payload);
            if (bytes.length > MAX_BYTES) throw invalid();
            payloadHash = CommonUtil.sha256(bytes);
            JsonNode resources = payload.get("resources");
            ObjectNode rubric = binding().put("payloadHash", payloadHash);
            rubric.set("rubrics", resources.get("rubrics"));
            rubric.set("rubricClues", resources.get("rubricClues"));
            rubricHash = SnapshotJson.hash(rubric);
            ObjectNode dataset =
                    binding().put("payloadHash", payloadHash).put("rubricHash", rubricHash);
            dataset.set("gradeSamples", resources.get("gradeSamples"));
            datasetHash = SnapshotJson.hash(dataset);
        }

        public byte[] payloadBytes() {
            return bytes.clone();
        }

        public JsonNode payload() {
            return SnapshotJson.parse(bytes);
        }

        public String payloadHash() {
            return payloadHash;
        }

        public String rubricHash() {
            return rubricHash;
        }

        public String datasetHash() {
            return datasetHash;
        }

        /**
         * 전체 집합에 결속된 한 원본 inputData 식별자를 계산한다. INPUT_ERROR의 malformed JSON도 그대로 포함한다.
         *
         * @param sampleCode 같은 사본 안의 유일한 코드
         * @return 서버 전용 해시이며 HTTP 필드를 추가하지 않는다
         * @throws IllegalArgumentException 미등록 코드이면 고정 오류
         */
        public String inputHash(String sampleCode) {
            for (JsonNode sample : payload().get("resources").get("gradeSamples")) {
                if (sample.get("code").textValue().equals(sampleCode)) {
                    ObjectNode input =
                            binding()
                                    .put("payloadHash", payloadHash)
                                    .put("rubricHash", rubricHash)
                                    .put("datasetHash", datasetHash)
                                    .put("sampleCode", sampleCode);
                    input.set("inputData", sample.get("inputData"));
                    return SnapshotJson.hash(input);
                }
            }
            throw invalid();
        }

        /**
         * 이미 검증·LF 정규화된 REPORT-1을 전체 원고/채점표에 결속한다. 오류 fixture의 보고서를 합성하지 않는다.
         *
         * @param report null이 아닌 GradeModels.Report
         * @return 보수적 전체 결속 해시
         * @throws IllegalArgumentException null이면 고정 오류
         */
        public String reportHash(GradeModels.Report report) {
            if (report == null) throw invalid();
            ObjectNode preimage =
                    binding().put("payloadHash", payloadHash).put("rubricHash", rubricHash);
            preimage.set(
                    "report",
                    object().put("culpritCode", report.culpritCode())
                            .put("method", report.method())
                            .put("time", report.time())
                            .put("motive", report.motive())
                            .put("evidence", report.evidence()));
            return SnapshotJson.hash(preimage);
        }
    }

    /** 스키마 검사만 수행하며 nullable 저장 원고와 완성 실행 데이터의 차이를 유지한다. */
    private static void validate(JsonNode root, boolean ordered) {
        exact(
                root,
                "formatNo",
                "storyCode",
                "versionNo",
                "sourceRev",
                "policy",
                "sections",
                "resources");
        integer(root.get("formatNo"), 1, 1);
        String story = string(root.get("storyCode"));
        if (!story.matches("[A-Z0-9_]{1,40}")) throw invalid();
        integer(root.get("versionNo"), 1, Integer.MAX_VALUE);
        String revision = string(root.get("sourceRev"));
        if (!revision.matches("0|[1-9][0-9]*")) throw invalid();
        try {
            Long.parseLong(revision);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        JsonNode sections = root.get("sections");
        exact(sections, "basic", "answer", "reveal");
        JsonNode basic = sections.get("basic");
        exact(
                basic,
                "title",
                "intro",
                "setting",
                "difficulty",
                "estMin",
                "estMax",
                "limitSec",
                "timelineOrigin");
        text(basic.get("title"), 160, false, false);
        text(basic.get("intro"), 12000, true, true);
        text(basic.get("setting"), 4000, true, true);
        text(basic.get("timelineOrigin"), 120, true, true);
        int difficulty = integer(basic.get("difficulty"), 1, 5);
        int limit = integer(basic.get("limitSec"), 1, Integer.MAX_VALUE);
        nullableInteger(basic.get("estMin"), 1, Short.MAX_VALUE);
        nullableInteger(basic.get("estMax"), 1, Short.MAX_VALUE);
        if (!basic.get("estMin").isNull()
                && !basic.get("estMax").isNull()
                && basic.get("estMin").intValue() > basic.get("estMax").intValue()) throw invalid();
        JsonNode expectedPolicy =
                createPolicy(string(root.get("policy").path("policyCode")), difficulty, limit);
        if (!java.util.Arrays.equals(
                SnapshotJson.encode(expectedPolicy), SnapshotJson.encode(root.get("policy"))))
            throw invalid();
        // 정책 정수는 런타임 노드 종류가 아니라 정확한 수학적 정수 값으로 검사한다.
        policyTypes(root.get("policy"), expectedPolicy);
        JsonNode answer = sections.get("answer");
        exact(answer, "culpritCode", "methodAnswer", "timeAnswer", "motiveAnswer");
        nullableCode(answer.get("culpritCode"));
        text(answer.get("methodAnswer"), 12000, true, true);
        text(answer.get("timeAnswer"), 8000, true, true);
        text(answer.get("motiveAnswer"), 8000, true, true);
        exact(sections.get("reveal"), "revealText");
        text(sections.get("reveal").get("revealText"), 20000, true, true);
        JsonNode resources = root.get("resources");
        exact(resources, KEYS.keySet().toArray(String[]::new));
        for (var entry : KEYS.entrySet()) {
            JsonNode array = resources.get(entry.getKey());
            array(array, 0, Integer.MAX_VALUE);
            Set<List<String>> seen = new java.util.HashSet<>();
            JsonNode previous = null;
            for (JsonNode row : array) {
                row(entry.getKey(), row);
                List<String> key =
                        entry.getValue().stream().map(field -> code(row.get(field))).toList();
                if (!seen.add(key)
                        || ordered
                                && previous != null
                                && comparator(entry.getValue()).compare(previous, row) >= 0)
                    throw invalid();
                previous = row;
            }
        }
        if (SnapshotJson.encode(root).length > MAX_BYTES) throw invalid();
    }

    private static void row(String kind, JsonNode row) {
        switch (kind) {
            case "persons" -> {
                exact(row, "code", "name", "publicText", "secretText");
                text(row.get("name"), 80, false, false);
                text(row.get("publicText"), 8000, true, true);
                text(row.get("secretText"), 8000, true, true);
            }
            case "roles" -> {
                exact(row, "code", "name", "brief");
                text(row.get("name"), 80, false, false);
                text(row.get("brief"), 4000, true, true);
            }
            case "pairs" -> {
                exact(row, "roleA", "roleB");
                if (code(row.get("roleA")).compareTo(code(row.get("roleB"))) >= 0) throw invalid();
            }
            case "clues" -> {
                exact(row, "code", "title", "body", "personCode", "scope", "sourceText");
                text(row.get("title"), 160, false, false);
                text(row.get("body"), 12000, true, true);
                nullableCode(row.get("personCode"));
                enumeration(row.get("scope"), false, "COMMON", "ROLE");
                text(row.get("sourceText"), 400, true, true);
            }
            case "clueRoles" -> exact(row, "clueCode", "roleCode");
            case "hints" -> {
                exact(row, "code", "level", "body");
                integer(row.get("level"), 1, 3);
                text(row.get("body"), 4000, true, true);
            }
            case "events" -> {
                exact(row, "code", "startMin", "endMin", "actualText", "apparentText");
                nullableInteger(row.get("startMin"), 0, Integer.MAX_VALUE);
                nullableInteger(row.get("endMin"), 0, Integer.MAX_VALUE);
                if (!row.get("endMin").isNull()
                        && (row.get("startMin").isNull()
                                || row.get("endMin").intValue() < row.get("startMin").intValue()))
                    throw invalid();
                text(row.get("actualText"), 8000, true, true);
                text(row.get("apparentText"), 8000, true, true);
            }
            case "facts" -> {
                exact(row, "code", "statement", "truth", "basis");
                text(row.get("statement"), 4000, true, true);
                enumeration(row.get("truth"), true, "TRUE", "FALSE", "MISREAD");
                text(row.get("basis"), 8000, true, true);
            }
            case "rubrics" -> {
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
                        row.get("category"),
                        false,
                        "CULPRIT",
                        "METHOD",
                        "TIME",
                        "MOTIVE",
                        "EVIDENCE");
                nullableInteger(row.get("maxScore"), 0, 100);
                bool(row.get("requiredYn"), false);
                nullableInteger(row.get("passScore"), 0, 100);
                if (!row.get("passScore").isNull()
                        && (!row.get("requiredYn").booleanValue()
                                || row.get("maxScore").isNull()
                                || row.get("passScore").intValue()
                                        > row.get("maxScore").intValue())) throw invalid();
                text(row.get("acceptedText"), 12000, true, true);
                text(row.get("partialText"), 12000, true, true);
                text(row.get("rejectText"), 8000, true, true);
                if (!row.get("ruleData").isNull()) rule(row.get("ruleData"));
            }
            case "rubricClues" -> {
                exact(row, "rubricCode", "clueCode", "linkText");
                text(row.get("linkText"), 4000, true, true);
            }
            case "gradeSamples" -> {
                exact(
                        row,
                        "code",
                        "inputData",
                        "expectData",
                        "expectedScore",
                        "expectedSuccess",
                        "reason",
                        "checkedBy");
                nullableInteger(row.get("expectedScore"), 0, 100);
                bool(row.get("expectedSuccess"), true);
                text(row.get("reason"), 8000, true, true);
                if (!row.get("checkedBy").isNull()) {
                    String uuid = string(row.get("checkedBy"));
                    if (!UUID.fromString(uuid).toString().equals(uuid)) throw invalid();
                }
                JsonNode expect = row.get("expectData");
                if (!expect.isNull()) expectation(expect);
                JsonNode input = row.get("inputData");
                if (!input.isNull()) {
                    exact(input, "formatNo", "report", "fault");
                    integer(input.get("formatNo"), 1, 1);
                    boundedJson(input, 131072);
                    if (!input.get("fault").isNull()) {
                        exact(input.get("fault"), "type", "failRuns");
                        enumeration(
                                input.get("fault").get("type"), false, "TIMEOUT", "UNAVAILABLE");
                        integer(input.get("fault").get("failRuns"), 3, 3);
                    }
                    if (!"INPUT_ERROR".equals(expect.path("kind").asText()))
                        report(input.get("report"));
                }
            }
            default -> throw invalid();
        }
    }

    private static void report(JsonNode report) {
        exact(report, "culpritCode", "method", "time", "motive", "evidence");
        code(report.get("culpritCode"));
        int total = 0;
        for (String field : List.of("method", "time", "motive", "evidence")) {
            text(report.get(field), 5000, false, true);
            total +=
                    report.get(field)
                            .textValue()
                            .codePointCount(0, report.get(field).textValue().length());
        }
        if (total > 20000) throw invalid();
    }

    private static void expectation(JsonNode expect) {
        exact(expect, "formatNo", "kind", "items", "error");
        integer(expect.get("formatNo"), 1, 1);
        enumeration(expect.get("kind"), false, "GRADED", "INPUT_ERROR", "ENGINE_ERROR");
        if (!expect.get("items").isNull()) {
            array(expect.get("items"), 0, 50);
            for (JsonNode item : expect.get("items")) {
                exact(item, "rubricCode", "score", "requiredMet", "reason");
                code(item.get("rubricCode"));
                integer(item.get("score"), 0, 100);
                bool(item.get("requiredMet"), true);
                text(item.get("reason"), 1000, false, false);
            }
        }
        if (!expect.get("error").isNull()) {
            JsonNode error = expect.get("error");
            exact(error, "code", "state", "score", "attemptDelta");
            enumeration(error.get("code"), false, "INVALID_REPORT", "GRADING_UNAVAILABLE");
            enumeration(error.get("state"), false, "REJECTED", "SYSTEM_ERROR");
            if (!error.get("score").isNull()) throw invalid();
            integer(error.get("attemptDelta"), 0, 0);
        }
        boundedJson(expect, 65536);
    }

    private static void rule(JsonNode rule) {
        exact(rule, "formatNo", "requiredNotice", "claims", "levels", "contradictions");
        integer(rule.get("formatNo"), 1, 1);
        text(rule.get("requiredNotice"), 200, true, false);
        array(rule.get("claims"), 1, 20);
        for (JsonNode claim : rule.get("claims")) {
            exact(claim, "code", "meaning", "factCodes", "exampleClueRoutes");
            code(claim.get("code"));
            text(claim.get("meaning"), 1000, false, false);
            codes(claim.get("factCodes"), 0, 20);
            routes(claim.get("exampleClueRoutes"), 0, 5, 10);
        }
        array(rule.get("levels"), 2, 6);
        for (JsonNode level : rule.get("levels")) {
            exact(level, "code", "score", "routes");
            code(level.get("code"));
            int score = integer(level.get("score"), 0, 100);
            routes(level.get("routes"), score == 0 ? 0 : 1, score == 0 ? 0 : 5, 20);
        }
        array(rule.get("contradictions"), 0, 10);
        for (JsonNode contradiction : rule.get("contradictions")) {
            exact(contradiction, "code", "meaning");
            code(contradiction.get("code"));
            text(contradiction.get("meaning"), 1000, false, false);
        }
        boundedJson(rule, 131072);
    }

    /** JSONB 텍스트의 쉼표/콜론 공백과 일반 숫자 표기 비용도 검사하되 primitive 자체 상한은 추가하지 않는다. */
    private static void boundedJson(JsonNode node, int limit) {
        if (SnapshotJson.encode(node).length > limit || jsonbSize(node, limit) > limit)
            throw invalid();
    }

    private static long jsonbSize(JsonNode node, int limit) {
        if (node.isNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            if (number.signum() == 0) return 1;
            long scale = number.scale();
            long precision = number.precision();
            // JSONB numeric 범위와 일반 표기의 공유 parser 재읽기를 계산만으로 대조한다.
            // Jackson은 소수의 정수부 선행 0을 숫자 길이에 포함하지 않는다.
            long integerDigits = Math.max(0L, precision - scale);
            long fractionDigits = Math.max(0L, scale);
            if (precision - scale > 131072L
                    || scale > 16383L
                    || integerDigits + fractionDigits > SnapshotJson.numberLengthLimit()) {
                throw invalid();
            }
            long length = scale <= 0 ? precision - scale : Math.max(precision, scale + 1) + 1;
            return length + (number.signum() < 0 ? 1 : 0);
        }
        if (!node.isContainerNode()) return SnapshotJson.encode(node).length;
        long size = 2;
        if (node.isObject()) {
            for (var field : node.properties()) {
                size +=
                        SnapshotJson.encode(JsonNodeFactory.instance.textNode(field.getKey()))
                                        .length
                                + 2;
                size += jsonbSize(field.getValue(), limit);
                if (size > limit) return size;
            }
        } else {
            for (JsonNode element : node) {
                size += jsonbSize(element, limit);
                if (size > limit) return size;
            }
        }
        return size + Math.max(0, node.size() - 1) * 2L;
    }

    private static void policyTypes(JsonNode value, JsonNode expected) {
        if (expected.isIntegralNumber()) {
            integer(value, expected.intValue(), expected.intValue());
        } else if (expected.isContainerNode()) {
            if (expected.isObject()) {
                expected.fieldNames()
                        .forEachRemaining(
                                field -> policyTypes(value.get(field), expected.get(field)));
            } else {
                for (int index = 0; index < expected.size(); index++)
                    policyTypes(value.get(index), expected.get(index));
            }
        }
    }

    private static Comparator<JsonNode> comparator(List<String> keys) {
        return (left, right) -> {
            for (String key : keys) {
                int order = left.get(key).textValue().compareTo(right.get(key).textValue());
                if (order != 0) return order;
            }
            return 0;
        };
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode binding() {
        return object().put("formatNo", 1);
    }

    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }

    private static void array(JsonNode node, int min, int max) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max)
            throw invalid();
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static String code(JsonNode node) {
        String code = string(node);
        if (!code.matches("[A-Z0-9_]{1,32}")) throw invalid();
        return code;
    }

    private static void nullableCode(JsonNode node) {
        if (!node.isNull()) code(node);
    }

    /** 저장 문자열은 정규화를 확인만 한다. CR/LF를 조용히 바꾸어 원본 해시를 주장하지 않는다. */
    private static void text(JsonNode node, int max, boolean nullable, boolean blank) {
        if (nullable && node.isNull()) return;
        String value = string(node);
        if (!CommonUtil.normalizeText(value, max, blank).equals(value)) throw invalid();
    }

    private static int integer(JsonNode node, int min, int max) {
        if (node == null || !node.isNumber()) throw invalid();
        try {
            int value = node.decimalValue().intValueExact();
            if (value < min || value > max) throw invalid();
            return value;
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    private static void nullableInteger(JsonNode node, int min, int max) {
        if (!node.isNull()) integer(node, min, max);
    }

    private static void bool(JsonNode node, boolean nullable) {
        if (!(nullable && node.isNull()) && !node.isBoolean()) throw invalid();
    }

    private static void enumeration(JsonNode node, boolean nullable, String... values) {
        if (nullable && node.isNull()) return;
        if (!Set.of(values).contains(string(node))) throw invalid();
    }

    private static void codes(JsonNode node, int min, int max) {
        array(node, min, max);
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode element : node) if (!seen.add(code(element))) throw invalid();
    }

    private static void routes(JsonNode node, int min, int max, int routeMax) {
        array(node, min, max);
        for (JsonNode route : node) codes(route, 1, routeMax);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_FROZEN_SNAPSHOT");
    }
}
