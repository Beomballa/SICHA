package com.reasoning.common.story.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.util.CommonUtil;

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 수동 검수의 닫힌 근거 형식을 검사한다. 참조를 가져오거나 실제 실행·품질을 인증하지 않는다. */
public final class StoryReviewEvidence {
    private StoryReviewEvidence() {}

    /**
     * format 1/2의 명시 null·실제 확인 참조·별도 문맥을 검증하고 LF만 정규화한다.
     *
     * @param kind MODEL 또는 APPROVAL
     * @param result PASS, FAIL 또는 INCOMPLETE
     * @param modelId 명시 null 또는 실제 모델 이름 1~80자
     * @param effort 명시 null 또는 실제 추론 수준 1~16자
     * @param evidence 공백만이 아닌 1~20000 코드포인트 원문
     * @param data 모든 정의 필드가 있는 객체이며 format 2만 resolves를 가진다
     * @param now 현재 서버 UTC 시각; 확인 시각은 이를 넘을 수 없다
     * @return 정규화된 요청 값이며 객체는 별도 사본이다
     * @throws AuthException 형식 오류 400, Unicode·길이·근거·정책 불충족 422
     */
    public static Input normalize(
            String kind,
            String result,
            String modelId,
            String effort,
            String evidence,
            JsonNode data,
            Instant now) {
        if (Set.of("PLAYTEST", "GRADE").contains(kind == null ? "" : kind))
            throw AuthException.unprocessable("EVIDENCE_NOT_CONNECTED");
        if (!Set.of("MODEL", "APPROVAL").contains(kind == null ? "" : kind)
                || !Set.of("PASS", "FAIL", "INCOMPLETE").contains(result == null ? "" : result)
                || data == null
                || !data.isObject()) throw invalid();
        JsonNode format = data.get("formatNo");
        int number = integer(format);
        if (number != 1 && number != 2) throw invalid();
        Set<String> fields =
                new HashSet<>(
                        Set.of(
                                "formatNo",
                                "evidenceRef",
                                "checkedAt",
                                "criticalOpenCount",
                                "notes",
                                "runRef",
                                "separateContext"));
        if (number == 2) fields.add("resolves");
        exact(data, fields);
        ObjectNode normalized = data.deepCopy();
        String text = text(evidence, 20000, false);
        String notes = text(string(data.get("notes")), 4000, true);
        normalized.put("notes", notes);
        modelId = modelId == null ? null : text(modelId, 80, false);
        effort = effort == null ? null : text(effort, 16, false);
        boolean absent = data.get("evidenceRef").isNull();
        if (absent) {
            if (!"INCOMPLETE".equals(result)
                    || modelId != null
                    || effort != null
                    || !data.get("checkedAt").isNull()
                    || !data.get("criticalOpenCount").isNull()
                    || !data.get("runRef").isNull()
                    || !data.get("separateContext").isNull()) throw notReady();
            text(notes, 4000, false);
        } else {
            reference(data.get("evidenceRef"), "[A-Za-z0-9_./-]{8,160}");
            String time = string(data.get("checkedAt"));
            if (!time.matches(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|\\+00:00)"))
                throw invalid();
            try {
                Instant checked = java.time.OffsetDateTime.parse(time).toInstant();
                if (checked.isAfter(now)) throw notReady();
            } catch (DateTimeParseException invalidTime) {
                throw invalid();
            }
            BigInteger critical = count(data.get("criticalOpenCount"));
            if (critical.signum() < 0) throw notReady();
            if ("MODEL".equals(kind)) {
                if (modelId == null || effort == null) throw notReady();
                reference(data.get("runRef"), "[A-Za-z0-9_./-]{8,160}");
                if (!data.get("separateContext").isBoolean()
                        || !data.get("separateContext").booleanValue()) throw notReady();
            } else if (modelId != null
                    || effort != null
                    || !data.get("runRef").isNull()
                    || !data.get("separateContext").isNull()) throw notReady();
        }
        if (number == 2) {
            JsonNode resolves = data.get("resolves");
            if (!resolves.isArray() || resolves.size() > 100) throw invalid();
            if (!"PASS".equals(result) && !resolves.isEmpty()) throw notReady();
            Set<Long> ids = new HashSet<>();
            for (JsonNode row : resolves) {
                exact(row, Set.of("recordId", "reasonCode", "verificationRef"));
                long id = recordId(string(row.get("recordId")));
                if (!ids.add(id)
                        || !Set.of("RECORD_CORRECTION", "ISSUE_VERIFIED")
                                .contains(string(row.get("reasonCode")))) throw invalid();
                reference(row.get("verificationRef"), "[A-Za-z0-9_-]{8,64}");
            }
        }
        return new Input(kind, result, modelId, effort, text, normalized);
    }

    /** 신규 PASS에만 현재 필수 Medium 검수 모델·치명 지적 0을 검사한다. 재생 입력 비교를 앞서지 않는다. */
    public static void requireNew(Input input) {
        if (!"PASS".equals(input.result())) return;
        if (count(input.evidenceData().get("criticalOpenCount")).signum() != 0
                || "MODEL".equals(input.kind())
                        && !("gpt-6-astra".equals(input.modelId())
                                && "medium".equals(input.effort()))) throw notReady();
    }

    /** format 1은 원본을 바꾸지 않고 빈 해소 집합으로 해석한다. */
    public static List<Long> resolutions(JsonNode data) {
        if (!data.has("resolves")) return List.of();
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        data.get("resolves").forEach(row -> ids.add(recordId(row.get("recordId").textValue())));
        return List.copyOf(ids);
    }

    private static long recordId(String value) {
        if (!value.matches("[1-9][0-9]*")) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw invalid();
        }
    }

    private static int integer(JsonNode value) {
        if (value == null || !value.isNumber()) throw invalid();
        try {
            return value.decimalValue().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalidNumber) {
            throw invalid();
        }
    }

    /** 문서에 없는 int 상한을 만들지 않고 정확한 정수 값을 유지한다. */
    private static BigInteger count(JsonNode value) {
        if (value == null || !value.isNumber()) throw invalid();
        try {
            var number = value.decimalValue().stripTrailingZeros();
            // 지수 표현을 거대한 정수로 펼치기 전에 저장 후 같은 공유 parser로 재읽을 수 있는지만 검사한다.
            if (number.signum() != 0
                    && (long) number.precision() - number.scale()
                            > com.reasoning.common.grading.model.SnapshotJson.numberLengthLimit())
                throw AuthException.unprocessable("INVALID_INPUT");
            return number.toBigIntegerExact();
        } catch (ArithmeticException | NumberFormatException invalidNumber) {
            throw invalid();
        }
    }

    private static void exact(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()) throw invalid();
        for (String name : fields) if (!value.has(name)) throw invalid();
    }

    private static String string(JsonNode value) {
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static void reference(JsonNode value, String pattern) {
        if (!string(value).matches(pattern)) throw invalid();
    }

    private static String text(String value, int max, boolean blank) {
        try {
            return CommonUtil.normalizeText(value, max, blank);
        } catch (IllegalArgumentException invalidText) {
            throw AuthException.unprocessable("INVALID_INPUT");
        }
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    private static AuthException notReady() {
        return AuthException.unprocessable("REVIEW_NOT_READY");
    }

    /** 정규화한 수동 기록 입력이며 실제 실행의 증명은 아니다. */
    public record Input(
            String kind,
            String result,
            String modelId,
            String effort,
            String evidence,
            JsonNode evidenceData) {}
}
