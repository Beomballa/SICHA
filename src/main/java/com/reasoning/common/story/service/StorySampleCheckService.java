package com.reasoning.common.story.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryService.VersionScope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 전체 저장 예시의 구조만 검사하고 명시적인 사람 확인을 기록하며 의미 판정은 수행하지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StorySampleCheckService {
    private static final Map<String, Integer> WEIGHTS =
            Map.of("CULPRIT", 25, "METHOD", 20, "TIME", 15, "MOTIVE", 10, "EVIDENCE", 30);
    private final StoryService stories;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final StoryGradeSampleFormat format;
    private final StoryRubricRule rules;

    /** 기존 부모 잠금·필수 감사와 저장 형식 검사기를 재사용한다. */
    public StorySampleCheckService(StoryService stories, JdbcTemplate db, ObjectMapper mapper) {
        this.stories = stories;
        this.db = db;
        this.mapper = mapper;
        this.format = new StoryGradeSampleFormat(db, mapper);
        this.rules = new StoryRubricRule(db, mapper);
    }

    /**
     * 원본을 검토한 사용자의 명시적인 호출에서만 현재 전체 활성 자료를 확인한다.
     *
     * @param sid 현재 등록 세션 ID이며 null이면 인증을 거절한다
     * @param actor 서버 검증 행위자이며 현재 소유자 또는 활성 EDIT여야 한다
     * @param storyCode 대상 사건의 불변 코드
     * @param versionNo 활성 DRAFT의 양의 버전 번호
     * @param expectedRev null이 아닌 음수 없는 현재 십진 수정번호; 무변경에도 검사한다
     * @param requestId null이 아닌 서버 접근 이력 UUID
     * @return 원고 없는 확정 수정번호·전체 확인 수·변경 여부·요청 ID
     * @throws AuthException 인증·권한·상태·수정번호 오류, SAMPLE_NOT_READY 또는 감사 실패 시
     */
    public CheckResult checkGradeSamples(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            UUID requestId) {
        if (expectedRev == null || requestId == null)
            throw AuthException.badRequest("INVALID_REQUEST");
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    List<Sample> samples =
                            db.query(
                                    "SELECT"
                                        + " input_data::text,expect_data::text,expected_score,expected_success,reason,checked_by"
                                        + " FROM grade_sample WHERE version_id=? AND active_yn"
                                        + " ORDER BY code COLLATE \"C\" FOR UPDATE",
                                    (rs, row) ->
                                            new Sample(
                                                    parse(rs.getString(1)),
                                                    parse(rs.getString(2)),
                                                    (Integer) rs.getObject(3),
                                                    (Boolean) rs.getObject(4),
                                                    rs.getString(5),
                                                    (Long) rs.getObject(6)),
                                    scope.versionId());
                    try {
                        validate(scope, samples);
                    } catch (AuthException invalid) {
                        throw AuthException.unprocessable("SAMPLE_NOT_READY");
                    }
                    boolean changed =
                            samples.stream()
                                    .anyMatch(
                                            sample ->
                                                    !Objects.equals(
                                                            sample.checkedBy(), actor.accountId()));
                    long revision =
                            changed
                                    ? stories.recordSampleCheck(
                                            scope, actor, samples.size(), requestId)
                                    : scope.rev();
                    return new CheckResult(
                            Long.toString(revision), samples.size(), changed, requestId);
                });
    }

    /** 같은 부모 수정번호 안에서 규칙·전체 예시·등록 단계의 집합 완성도를 검사한다. */
    private void validate(VersionScope scope, List<Sample> samples) {
        if (samples.isEmpty()) notReady();
        Map<String, Rubric> rubrics = rubrics(scope);
        Map<String, Set<Integer>> covered = new HashMap<>();
        rubrics.keySet().forEach(code -> covered.put(code, new HashSet<>()));
        Set<String> kinds = new HashSet<>();
        for (Sample sample : samples) {
            validateSample(scope, sample, rubrics, covered);
            kinds.add(sample.expectation().get("kind").textValue());
        }
        if (!kinds.containsAll(Set.of("GRADED", "INPUT_ERROR", "ENGINE_ERROR"))) notReady();
        for (Rubric rubric : rubrics.values())
            if (!covered.get(rubric.code()).containsAll(rubric.scores())) notReady();
    }

    /** 모든 활성 규칙의 완성도·활성 참조·고정 범인·분류 배점을 같은 거래에서 검사한다. */
    private Map<String, Rubric> rubrics(VersionScope scope) {
        List<RubricRow> rows =
                db.query(
                        "SELECT code,category,max_score,required_yn,pass_score,rule_data::text FROM"
                            + " story_rubric WHERE version_id=? AND active_yn ORDER BY code COLLATE"
                            + " \"C\"",
                        (rs, row) ->
                                new RubricRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        (Integer) rs.getObject(3),
                                        rs.getBoolean(4),
                                        (Integer) rs.getObject(5),
                                        parse(rs.getString(6))),
                        scope.versionId());
        if (rows.isEmpty() || rows.size() > 50) notReady();
        Map<String, Integer> totals = new HashMap<>();
        Map<String, Rubric> result = new HashMap<>();
        Set<String> requiredCategories = new HashSet<>();
        int culpritCount = 0;
        for (RubricRow row : rows) {
            if (!WEIGHTS.containsKey(row.category())
                    || row.max() == null
                    || row.max() <= 0
                    || row.rule() == null
                    || row.rule().isNull()) notReady();
            boolean culprit = "CULPRIT".equals(row.category());
            if (culprit) {
                culpritCount++;
                if (row.max() != 25 || !row.required() || !Integer.valueOf(25).equals(row.pass()))
                    notReady();
            }
            rules.validate(
                    row.rule().deepCopy(),
                    scope.versionId(),
                    row.code(),
                    row.max(),
                    row.required(),
                    culprit);
            Set<Integer> scores = new HashSet<>();
            for (JsonNode level : row.rule().get("levels"))
                scores.add(level.get("score").intValue());
            if (row.required()) {
                if (row.pass() == null || row.pass() <= 0 || !scores.contains(row.pass()))
                    notReady();
                requiredCategories.add(row.category());
            } else if (row.pass() != null) notReady();
            totals.merge(row.category(), row.max(), Integer::sum);
            result.put(
                    row.code(),
                    new Rubric(row.code(), row.required(), row.pass(), Set.copyOf(scores)));
        }
        if (culpritCount != 1
                || !totals.equals(WEIGHTS)
                || !requiredCategories.containsAll(Set.of("METHOD", "EVIDENCE"))) notReady();
        return result;
    }

    /** 기존 크기·Unicode/LF 검사에 필수 필드와 종류별 완성도를 추가한다. */
    private void validateSample(
            VersionScope scope,
            Sample sample,
            Map<String, Rubric> rubrics,
            Map<String, Set<Integer>> covered) {
        text(sample.reason(), 8000);
        JsonNode input = sample.input();
        JsonNode expect = sample.expectation();
        exact(expect, "formatNo", "kind", "items", "error");
        if (!integer(expect.get("formatNo"), 1, 1)) notReady();
        format.expectation(expect, sample.score(), sample.success(), scope.versionId());
        format.input(input, expect, scope.versionId());
        String kind = expect.path("kind").asText();
        if (input == null
                || !input.isObject()
                || !integer(input.get("formatNo"), 1, 1)
                || !input.has("fault")) notReady();
        if (!"INPUT_ERROR".equals(kind)) {
            exact(input, "formatNo", "report", "fault");
            exact(input.get("report"), "culpritCode", "method", "time", "motive", "evidence");
        }
        if ("INPUT_ERROR".equals(kind) && validReport(input, scope.versionId())) notReady();
        if ("ENGINE_ERROR".equals(kind)) {
            JsonNode fault = input.get("fault");
            exact(fault, "type", "failRuns");
            if (!fault.path("type").isTextual()
                    || !Set.of("TIMEOUT", "UNAVAILABLE").contains(fault.path("type").textValue()))
                notReady();
            if (!integer(fault.get("failRuns"), 3, 3)) notReady();
        } else if (!input.get("fault").isNull()) notReady();

        if ("GRADED".equals(kind)) {
            graded(sample, rubrics, covered);
        } else {
            if (!Set.of("INPUT_ERROR", "ENGINE_ERROR").contains(kind)
                    || !expect.get("items").isNull()
                    || sample.score() != null
                    || sample.success() != null) notReady();
            JsonNode error = expect.get("error");
            exact(error, "code", "state", "score", "attemptDelta");
            String code = "INPUT_ERROR".equals(kind) ? "INVALID_REPORT" : "GRADING_UNAVAILABLE";
            String state = "INPUT_ERROR".equals(kind) ? "REJECTED" : "SYSTEM_ERROR";
            if (!code.equals(error.path("code").asText())
                    || !state.equals(error.path("state").asText())
                    || !error.get("score").isNull()
                    || !integer(error.get("attemptDelta"), 0, 0)) notReady();
        }
    }

    /**
     * 입력 오류 사례가 실제로 정상 제출 가능한 보고서를 포함하는지 검사한다.
     *
     * @param input 현재 예시의 입력 객체이며 보고서가 없거나 불완전할 수 있다
     * @param versionId 활성 인물 코드를 확인할 같은 거래의 내부 버전 식별자
     * @return 모든 필드·Unicode·길이·활성 인물 검사를 통과하면 true
     * @throws AuthException 입력 오류가 아닌 업무 오류는 그대로 전달한다
     */
    private boolean validReport(JsonNode input, long versionId) {
        try {
            exact(input, "formatNo", "report", "fault");
            exact(input.get("report"), "culpritCode", "method", "time", "motive", "evidence");
            format.input(input, mapper.createObjectNode().put("kind", "GRADED"), versionId);
            return true;
        } catch (AuthException invalid) {
            if (invalid.status() == 400 || invalid.status() == 422) return false;
            throw invalid;
        }
    }

    /** 전체 항목의 등록 단계·필수 조건·합계를 대조하며 자연어의 진위를 추측하지 않는다. */
    private void graded(
            Sample sample, Map<String, Rubric> rubrics, Map<String, Set<Integer>> covered) {
        JsonNode expect = sample.expectation();
        JsonNode items = expect.get("items");
        if (!expect.get("error").isNull()
                || !items.isArray()
                || items.size() != rubrics.size()
                || sample.score() == null
                || sample.score() < 0
                || sample.score() > 100
                || sample.success() == null) notReady();
        Set<String> seen = new HashSet<>();
        int sum = 0;
        boolean success = true;
        for (JsonNode item : items) {
            exact(item, "rubricCode", "score", "requiredMet", "reason");
            Rubric rubric = rubrics.get(item.path("rubricCode").asText());
            if (rubric == null || !seen.add(rubric.code()) || !integer(item.get("score"), 0, 100))
                notReady();
            int score = item.get("score").intValue();
            if (!rubric.scores().contains(score)) notReady();
            JsonNode met = item.get("requiredMet");
            if (rubric.required()) {
                boolean passed = score >= rubric.pass();
                if (!met.isBoolean() || met.booleanValue() != passed) notReady();
                success &= passed;
            } else if (!met.isNull()) notReady();
            JsonNode reason = item.get("reason");
            if (!reason.isTextual()) notReady();
            text(reason.textValue(), 1000);
            covered.get(rubric.code()).add(score);
            sum += score;
        }
        if (!seen.equals(rubrics.keySet()) || sum != sample.score() || success != sample.success())
            notReady();
    }

    /** 저장 JSON을 읽고 원문을 예외에 붙이지 않은 채 형식 오류만 보고한다. */
    private JsonNode parse(String raw) {
        if (raw == null) return null;
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException invalid) {
            throw AuthException.unprocessable("SAMPLE_NOT_READY");
        }
    }

    /** 필수 객체의 완전한 필드 집합을 검사한다. */
    private static void exact(JsonNode value, String... fields) {
        if (value == null || !value.isObject() || value.size() != fields.length) notReady();
        for (String field : fields) if (!value.has(field)) notReady();
    }

    /** 공통 Unicode/LF 규칙으로 공백뿐인 근거와 코드포인트 상한을 검사한다. */
    private static void text(String value, int max) {
        if (value == null || StoryService.text(value, max, false).isBlank()) notReady();
    }

    private static boolean integer(JsonNode node, int min, int max) {
        return node != null
                && node.isIntegralNumber()
                && node.canConvertToInt()
                && node.intValue() >= min
                && node.intValue() <= max;
    }

    private static void notReady() {
        throw AuthException.unprocessable("SAMPLE_NOT_READY");
    }

    private record RubricRow(
            String code,
            String category,
            Integer max,
            boolean required,
            Integer pass,
            JsonNode rule) {}

    private record Rubric(String code, boolean required, Integer pass, Set<Integer> scores) {}

    private record Sample(
            JsonNode input,
            JsonNode expectation,
            Integer score,
            Boolean success,
            String reason,
            Long checkedBy) {}

    public record CheckResult(String editRev, int checkedCount, boolean changed, UUID requestId) {}
}
