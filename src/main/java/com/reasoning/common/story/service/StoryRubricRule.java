package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 사건별 유한 채점 규칙의 저장 형식과 활성 참조를 검사한다. 판정 실행은 담당하지 않는다. */
final class StoryRubricRule {
    private static final int BYTE_LIMIT = 131072;
    private static final Set<String> ROOT =
            Set.of("formatNo", "requiredNotice", "claims", "levels", "contradictions");
    private static final Set<String> CLAIM =
            Set.of("code", "meaning", "factCodes", "exampleClueRoutes");
    private static final Set<String> LEVEL = Set.of("code", "score", "routes");
    private static final Set<String> CONTRADICTION = Set.of("code", "meaning");
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    StoryRubricRule(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /** 서버만 구성할 수 있는 범인 규칙을 만들며 일반 명제의 사실 참조 예외는 여기에만 적용한다. */
    JsonNode culprit() {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatNo", 1);
        root.put("requiredNotice", "선택한 범인을 근거로 입증하세요.");
        ArrayNode claims = root.putArray("claims");
        ObjectNode selected = claims.addObject();
        selected.put("code", "SELECTED_CULPRIT");
        selected.put("meaning", "선택한 범인이 사건의 실제 범인과 일치한다.");
        selected.putArray("factCodes");
        selected.putArray("exampleClueRoutes");
        ArrayNode levels = root.putArray("levels");
        levels.addObject().put("code", "ZERO").put("score", 0).putArray("routes");
        levels.addObject()
                .put("code", "FULL")
                .put("score", 25)
                .putArray("routes")
                .addArray()
                .add("SELECTED_CULPRIT");
        ArrayNode contradictions = root.putArray("contradictions");
        contradictions
                .addObject()
                .put("code", "CONTRADICT_CULPRIT")
                .put("meaning", "선택한 범인과 본문에서 최종 단정한 범인이 서로 충돌한다.");
        contradictions
                .addObject()
                .put("code", "UNSUPPORTED_ACCOMPLICE")
                .put("meaning", "고정 사실과 타당한 증거 연결로 뒷받침되지 않거나 단독범행 사실과 양립하지 않는 공범을 최종 단정한다.");
        return root;
    }

    /** 비-null 규칙의 전체 스키마와 활성 사실·단서·연결을 같은 부모 잠금 아래 검사한다. */
    String validate(
            JsonNode rule,
            long versionId,
            String rubricCode,
            Integer max,
            boolean required,
            boolean culprit) {
        if (rule == null || rule.isNull()) return null;
        checkMembers(rule);
        if (max == null || max == 0) invalid();
        if (culprit) {
            if (!rule.equals(culprit())) invalid();
        } else {
            object(rule, ROOT);
            if (!integer(rule.get("formatNo"), 1, 1)) invalid();
            JsonNode notice = rule.get("requiredNotice");
            if (notice == null
                    || !(notice.isNull() && !required
                            || required && !notice.isNull() && validText(notice, 200))) invalid();
            if (required)
                ((ObjectNode) rule)
                        .put("requiredNotice", StoryService.text(notice.textValue(), 200, false));
            ArrayNode claims = array(rule.get("claims"), 1, 20);
            Set<String> codes = new HashSet<>();
            for (JsonNode claim : claims) {
                object(claim, CLAIM);
                String code = code(claim.get("code"));
                if (!codes.add(code) || !validText(claim.get("meaning"), 1000)) invalid();
                ((ObjectNode) claim)
                        .put(
                                "meaning",
                                StoryService.text(claim.get("meaning").textValue(), 1000, false));
                ArrayNode facts = array(claim.get("factCodes"), 1, 20);
                Set<String> uniqueFacts = new HashSet<>();
                for (JsonNode fact : facts) {
                    String ref = code(fact);
                    if (!uniqueFacts.add(ref)) invalid();
                }
                ArrayNode examples = array(claim.get("exampleClueRoutes"), 0, 5);
                for (JsonNode route : examples) {
                    Set<String> uniqueClues = new HashSet<>();
                    for (JsonNode clue : array(route, 1, 10)) {
                        if (!uniqueClues.add(code(clue))) invalid();
                    }
                }
            }
            Set<String> levelCodes = levels(rule.get("levels"), codes, max);
            contradictions(rule.get("contradictions"), codes, levelCodes);
        }
        String serialized;
        try {
            serialized = mapper.writeValueAsString(rule);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw AuthException.unprocessable("INVALID_INPUT");
        }
        if (serialized.getBytes(StandardCharsets.UTF_8).length > BYTE_LIMIT) invalid();
        Integer dbBytes =
                db.queryForObject("SELECT octet_length(?::jsonb::text)", Integer.class, serialized);
        if (dbBytes == null || dbBytes > BYTE_LIMIT) invalid();
        for (String code : factCodes(rule)) {
            if (db.queryForObject(
                            "SELECT count(*) FROM story_fact WHERE version_id=? AND code=? AND"
                                + " active_yn",
                            Integer.class,
                            versionId,
                            code)
                    == 0) invalid();
        }
        for (String code : clueCodes(rule)) {
            if (db.queryForObject(
                            "SELECT count(*) FROM story_clue c JOIN rubric_clue rc ON"
                                + " rc.version_id=c.version_id AND rc.clue_code=c.code WHERE"
                                + " c.version_id=? AND c.code=? AND c.active_yn AND"
                                + " rc.rubric_code=? AND rc.active_yn",
                            Integer.class,
                            versionId,
                            code,
                            rubricCode)
                    == 0) invalid();
        }
        return serialized;
    }

    /** 이미 검증한 저장 규칙에서 구조화 사실 코드만 추출한다. null 규칙은 빈 집합이다. */
    static Set<String> factCodes(JsonNode rule) {
        if (rule == null || rule.isNull()) return Set.of();
        Set<String> result = new HashSet<>();
        for (JsonNode claim : rule.path("claims"))
            for (JsonNode fact : claim.path("factCodes")) result.add(fact.textValue());
        return Set.copyOf(result);
    }

    /** 이미 검증한 저장 규칙에서 예시 경로의 단서 코드만 추출한다. null 규칙은 빈 집합이다. */
    static Set<String> clueCodes(JsonNode rule) {
        if (rule == null || rule.isNull()) return Set.of();
        Set<String> result = new HashSet<>();
        for (JsonNode claim : rule.path("claims"))
            for (JsonNode route : claim.path("exampleClueRoutes"))
                for (JsonNode clue : route) result.add(clue.textValue());
        return Set.copyOf(result);
    }

    /** 규칙의 단계는 점수·코드가 유일하고 0점과 만점을 포함해야 한다. */
    private static Set<String> levels(JsonNode node, Set<String> claims, int max) {
        Set<String> codes = new HashSet<>();
        Set<Integer> scores = new HashSet<>();
        for (JsonNode level : array(node, 2, 6)) {
            object(level, LEVEL);
            String levelCode = code(level.get("code"));
            if (!codes.add(levelCode)
                    || claims.contains(levelCode)
                    || !integer(level.get("score"), 0, max)) invalid();
            int score = level.get("score").intValue();
            if (!scores.add(score)) invalid();
            ArrayNode routes = array(level.get("routes"), score == 0 ? 0 : 1, score == 0 ? 0 : 5);
            for (JsonNode route : routes) {
                Set<String> seen = new HashSet<>();
                for (JsonNode claim : array(route, 1, 20)) {
                    String code = code(claim);
                    if (!claims.contains(code) || !seen.add(code)) invalid();
                }
            }
        }
        if (!scores.contains(0) || !scores.contains(max)) invalid();
        return codes;
    }

    /** 모순 코드의 중복과 명제·단계 간 코드 충돌을 막는다. */
    private static void contradictions(JsonNode node, Set<String> claims, Set<String> levels) {
        Set<String> codes = new HashSet<>();
        for (JsonNode item : array(node, 0, 10)) {
            object(item, CONTRADICTION);
            String contradictionCode = code(item.get("code"));
            if (!codes.add(contradictionCode)
                    || claims.contains(contradictionCode)
                    || levels.contains(contradictionCode)
                    || !validText(item.get("meaning"), 1000)) invalid();
            ((ObjectNode) item)
                    .put(
                            "meaning",
                            StoryService.text(item.get("meaning").textValue(), 1000, false));
        }
    }

    /** 고정 범인 규칙을 포함해 알려지지 않은 JSON 멤버는 값 오류와 구분하여 거절한다. */
    static void checkMembers(JsonNode node) {
        members(node, ROOT);
        if (node == null || !node.isObject()) return;
        for (JsonNode claim : node.path("claims")) members(claim, CLAIM);
        for (JsonNode level : node.path("levels")) members(level, LEVEL);
        for (JsonNode contradiction : node.path("contradictions"))
            members(contradiction, CONTRADICTION);
    }

    /** 객체일 때 허용 멤버만 검사하며 타입·필수 필드는 별도 형식 검사에 맡긴다. */
    private static void members(JsonNode node, Set<String> fields) {
        if (node != null && node.isObject())
            node.fieldNames()
                    .forEachRemaining(
                            name -> {
                                if (!fields.contains(name))
                                    throw AuthException.badRequest("INVALID_REQUEST");
                            });
    }

    private static void object(JsonNode node, Set<String> fields) {
        members(node, fields);
        if (node == null || !node.isObject() || node.size() != fields.size()) invalid();
    }

    private static ArrayNode array(JsonNode node, int min, int max) {
        if (!(node instanceof ArrayNode result) || result.size() < min || result.size() > max)
            throw AuthException.unprocessable("INVALID_INPUT");
        return result;
    }

    private static String code(JsonNode node) {
        if (node == null || !node.isTextual() || !node.textValue().matches("[A-Z0-9_]{1,32}"))
            invalid();
        return node.textValue();
    }

    private static boolean integer(JsonNode node, int min, int max) {
        return node != null
                && node.isIntegralNumber()
                && node.canConvertToInt()
                && node.intValue() >= min
                && node.intValue() <= max;
    }

    private static boolean validText(JsonNode node, int max) {
        if (node == null || !node.isTextual()) return false;
        StoryService.text(node.textValue(), max, false);
        return true;
    }

    private static void invalid() {
        throw AuthException.unprocessable("INVALID_INPUT");
    }
}
