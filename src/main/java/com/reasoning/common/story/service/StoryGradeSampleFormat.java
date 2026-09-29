package com.reasoning.common.story.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AuthException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 초안 검증 답안의 JSON 형식·크기만 검사하며 사람 확인이나 판정을 수행하지 않는다. */
final class StoryGradeSampleFormat {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    StoryGradeSampleFormat(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /** null 초안을 허용하며, 입력된 객체의 알려진 필드·형식·크기를 검사한다. */
    String input(JsonNode value, JsonNode expectation, long versionId) {
        if (value == null || value.isNull()) return null;
        members(value, "formatNo", "report", "fault");
        format(value);
        String kind = expectation == null ? null : text(expectation.get("kind"));
        JsonNode report = value.get("report");
        if (!"INPUT_ERROR".equals(kind) && report != null && !report.isNull()) {
            members(report, "culpritCode", "method", "time", "motive", "evidence");
            int total = 0;
            for (String field : new String[] {"method", "time", "motive", "evidence"}) {
                JsonNode part = report.get(field);
                if (part == null || !part.isTextual()) invalid();
                String normalized = StoryService.text(part.textValue(), 5000, true);
                total += normalized.codePointCount(0, normalized.length());
            }
            if (total > 20000) invalid();
            JsonNode culprit = report.get("culpritCode");
            if (culprit != null && culprit.isTextual()) {
                String code = culprit.textValue();
                if (!code.matches("[A-Z0-9_]{1,32}") || db.queryForObject(
                        "SELECT count(*) FROM story_person WHERE version_id=? AND code=? AND active_yn",
                        Integer.class, versionId, code) == 0) invalid();
            } else invalid();
        }
        JsonNode fault = value.get("fault");
        if (fault != null && !fault.isNull()) {
            members(fault, "type", "failRuns");
            if (!"ENGINE_ERROR".equals(kind) || !Set.of("TIMEOUT", "UNAVAILABLE").contains(text(fault.get("type")))
                    || !integer(fault.get("failRuns"), 3, 3)) invalid();
        }
        return json(value, 131072);
    }

    /** 알려진 기대 종류·항목·오류 코드만 허용하고 최종 일대일 대조는 SR-09에 남긴다. */
    String expectation(JsonNode value, Integer score, Boolean success, long versionId) {
        if (value == null || value.isNull()) return null;
        members(value, "formatNo", "kind", "items", "error");
        format(value);
        String kind = text(value.get("kind"));
        if (kind != null && !Set.of("GRADED", "INPUT_ERROR", "ENGINE_ERROR").contains(kind)) invalid();
        JsonNode items = value.get("items");
        if (items != null && !items.isNull()) {
            if (kind != null && !"GRADED".equals(kind) || !items.isArray() || items.size() > 50) invalid();
            Set<String> codes = new HashSet<>();
            for (JsonNode item : items) {
                members(item, "rubricCode", "score", "requiredMet", "reason");
                String code = text(item.get("rubricCode"));
                if (code == null || !code.matches("[A-Z0-9_]{1,32}") || !codes.add(code)) invalid();
                Integer count = db.queryForObject("SELECT count(*) FROM story_rubric WHERE version_id=? AND code=? AND active_yn",
                        Integer.class, versionId, code);
                if (count == null || count == 0) invalid();
                if (item.hasNonNull("score") && !integer(item.get("score"), 0, 100)) invalid();
                JsonNode required = item.get("requiredMet");
                if (required != null && !required.isNull() && !required.isBoolean()) invalid();
                JsonNode reason = item.get("reason");
                if (reason != null && !reason.isNull() && (!reason.isTextual()
                        || StoryService.text(reason.textValue(), 1000, false).isBlank())) invalid();
            }
        }
        JsonNode error = value.get("error");
        if (error != null && !error.isNull()) {
            members(error, "code", "state", "score", "attemptDelta");
            if (kind != null && !Set.of("INPUT_ERROR", "ENGINE_ERROR").contains(kind)) invalid();
            String code = text(error.get("code"));
            String state = text(error.get("state"));
            if (code != null && kind != null && !("INPUT_ERROR".equals(kind) ? "INVALID_REPORT" : "GRADING_UNAVAILABLE").equals(code)) invalid();
            if (state != null && kind != null && !("INPUT_ERROR".equals(kind) ? "REJECTED" : "SYSTEM_ERROR").equals(state)) invalid();
            if (error.hasNonNull("score") || error.hasNonNull("attemptDelta") && !integer(error.get("attemptDelta"), 0, 0)) invalid();
        }
        if (kind != null && !"GRADED".equals(kind) && (score != null || success != null)) invalid();
        return json(value, 65536);
    }

    private String json(JsonNode value, int limit) {
        try {
            String serialized = mapper.writeValueAsString(value);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > limit) invalid();
            Integer bytes = db.queryForObject("SELECT octet_length(?::jsonb::text)", Integer.class, serialized);
            if (bytes == null || bytes > limit) invalid();
            return serialized;
        } catch (JsonProcessingException e) {
            throw AuthException.unprocessable("INVALID_INPUT");
        }
    }

    private static void members(JsonNode value, String... fields) {
        if (value == null || !value.isObject()) invalid();
        Set<String> allowed = Set.of(fields);
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw AuthException.badRequest("INVALID_REQUEST");
        });
    }

    private static void format(JsonNode value) {
        if (value.has("formatNo") && !integer(value.get("formatNo"), 1, 1)) invalid();
    }

    private static boolean integer(JsonNode value, int min, int max) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= min && value.intValue() <= max;
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) invalid();
        return value.textValue();
    }

    private static void invalid() {
        throw AuthException.unprocessable("INVALID_INPUT");
    }
}
