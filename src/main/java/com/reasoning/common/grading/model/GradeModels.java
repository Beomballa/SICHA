package com.reasoning.common.grading.model;

import com.reasoning.common.util.CommonUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 형식1의 고정 입력·유한 규칙·판정 값이다. 모든 컬렉션은 방어 복사하며 저장·실행 권한을 갖지 않는다. 생성자는 null·범위·구조 오류에
 * IllegalArgumentException을 던진다. 원문 JSON 스키마는 호출 경계에서 검사한다.
 */
public final class GradeModels {
    private GradeModels() {}

    public enum Category {
        CULPRIT,
        METHOD,
        TIME,
        MOTIVE,
        EVIDENCE
    }

    public enum Status {
        COMPLETE,
        UNRESOLVED
    }

    public enum Field {
        method,
        time,
        motive,
        evidence
    }

    /**
     * REPORT-1의 서술은 공백을 보존하고 LF로 정규화한다. 각 0~5000 코드포인트, 합계 20000 이하다.
     *
     * @param culpritCode null이 아닌 기존 원고 형식의 선택 인물 코드
     * @param method null이 아닌 방법 서술
     * @param time null이 아닌 시간선 서술
     * @param motive null이 아닌 동기 서술
     * @param evidence null이 아닌 근거와 추론 서술
     */
    public record Report(
            String culpritCode, String method, String time, String motive, String evidence) {
        public Report {
            culpritCode = code(culpritCode);
            method = CommonUtil.normalizeText(method, 5000, true);
            time = CommonUtil.normalizeText(time, 5000, true);
            motive = CommonUtil.normalizeText(motive, 5000, true);
            evidence = CommonUtil.normalizeText(evidence, 5000, true);
            if (length(method) + length(time) + length(motive) + length(evidence) > 20000)
                invalid();
        }

        /**
         * 정규화된 서술을 선택한다.
         *
         * @param field null이 아닌 네 서술 필드
         * @return 공백 보존·LF 정규화된 문자열
         * @throws IllegalArgumentException field가 null인 경우
         */
        public String text(Field field) {
            if (field == null) invalid();
            return switch (field) {
                case method -> method;
                case time -> time;
                case motive -> motive;
                case evidence -> evidence;
            };
        }
    }

    /** 명제의 의미와 사실·예시 단서 참조다. 범인 서버 명제만 두 참조 목록이 비어 있다. */
    public record ClaimRule(
            String code,
            String meaning,
            List<String> factCodes,
            List<List<String>> exampleClueRoutes) {
        public ClaimRule {
            code = GradeModels.code(code);
            meaning = CommonUtil.normalizeText(meaning, 1000, false);
            factCodes = codes(factCodes, 0, 20);
            exampleClueRoutes = routes(exampleClueRoutes, 0, 5, 10);
        }
    }

    /** 한 경로의 모든 명제가 충족되면 해당 양수 단계가 후보가 된다. 0점은 경로가 없다. */
    public record Level(String code, int score, List<List<String>> routes) {
        public Level {
            code = GradeModels.code(code);
            if (score < 0 || score > 100) invalid();
            routes = GradeModels.routes(routes, score == 0 ? 0 : 1, score == 0 ? 0 : 5, 20);
        }
    }

    /** 모순은 해당 소항목에만 영향을 주며 의미는 1~1000 코드포인트다. */
    public record ContradictionRule(String code, String meaning) {
        public ContradictionRule {
            code = GradeModels.code(code);
            meaning = CommonUtil.normalizeText(meaning, 1000, false);
        }
    }

    /**
     * 완성된 활성 소항목이다. null 규칙·빈 명제·미등록 참조·범용 실행식은 표현하거나 평가할 수 없다.
     *
     * @param code null이 아닌 소항목 코드
     * @param category null이 아닌 공통 평가 분류
     * @param maxScore 1~100의 양수 최대 배점
     * @param required 필수 해결 조건 여부
     * @param passScore 필수 항목이면 등록된 양수 단계이며 선택 항목이면 null
     * @param requiredNotice 필수 항목이면 null 또는 1~200 코드포인트 안내이며 선택 항목이면 null; 범인 항목은 고정 안내
     * @param claims null이 아닌 1~20개 명제
     * @param levels null이 아닌 2~6개 유한 단계이며 0점·만점을 포함한다
     * @param contradictions null이 아닌 0~10개 모순
     * @param linkedClueCodes null이 아닌 해당 소항목의 활성 단서 연결 코드 집합
     */
    public record Rubric(
            String code,
            Category category,
            int maxScore,
            boolean required,
            Integer passScore,
            String requiredNotice,
            List<ClaimRule> claims,
            List<Level> levels,
            List<ContradictionRule> contradictions,
            Set<String> linkedClueCodes) {
        public Rubric {
            code = GradeModels.code(code);
            if (category == null || maxScore <= 0 || maxScore > 100) invalid();
            if (required) {
                if (requiredNotice != null)
                    requiredNotice = CommonUtil.normalizeText(requiredNotice, 200, false);
                if (passScore == null || passScore <= 0 || passScore > maxScore) invalid();
            } else if (passScore != null || requiredNotice != null) {
                invalid();
            }

            claims = copy(claims, 1, 20);
            levels = copy(levels, 2, 6);
            contradictions = copy(contradictions, 0, 10);
            linkedClueCodes = codeSet(linkedClueCodes);
            Set<String> claimCodes = new HashSet<>();
            Set<String> allCodes = new HashSet<>();
            for (ClaimRule claim : claims) {
                if (!claimCodes.add(claim.code()) || !allCodes.add(claim.code())) invalid();
                if (category != Category.CULPRIT && claim.factCodes().isEmpty()) invalid();
                for (List<String> route : claim.exampleClueRoutes()) {
                    if (!linkedClueCodes.containsAll(route)) invalid();
                }
            }

            Set<Integer> scores = new HashSet<>();
            for (Level level : levels) {
                if (!allCodes.add(level.code())
                        || level.score() > maxScore
                        || !scores.add(level.score())) invalid();
                for (List<String> route : level.routes()) {
                    if (!claimCodes.containsAll(route)) invalid();
                }
            }
            if (!scores.contains(0)
                    || !scores.contains(maxScore)
                    || required && !scores.contains(passScore)) invalid();
            for (ContradictionRule contradiction : contradictions) {
                if (!allCodes.add(contradiction.code())) invalid();
            }

            if (category == Category.CULPRIT) {
                ClaimRule selected =
                        new ClaimRule(
                                "SELECTED_CULPRIT",
                                "선택한 범인이 사건의 실제 범인과 일치한다.",
                                List.of(),
                                List.of());
                Set<Level> fixedLevels =
                        Set.of(
                                new Level("ZERO", 0, List.of()),
                                new Level("FULL", 25, List.of(List.of("SELECTED_CULPRIT"))));
                Set<ContradictionRule> fixedContradictions =
                        Set.of(
                                new ContradictionRule(
                                        "CONTRADICT_CULPRIT", "선택한 범인과 본문에서 최종 단정한 범인이 서로 충돌한다."),
                                new ContradictionRule(
                                        "UNSUPPORTED_ACCOMPLICE",
                                        "고정 사실과 타당한 증거 연결로 뒷받침되지 않거나 단독범행 사실과 양립하지 않는 공범을 최종"
                                                + " 단정한다."));
                if (maxScore != 25
                        || !required
                        || !Integer.valueOf(25).equals(passScore)
                        || !"선택한 범인을 근거로 입증하세요.".equals(requiredNotice)
                        || !claims.equals(List.of(selected))
                        || !Set.copyOf(levels).equals(fixedLevels)
                        || !Set.copyOf(contradictions).equals(fixedContradictions)) invalid();
            }
        }
    }

    /**
     * 활성 사실·단서·연결이 고정된 완성 사본이다. 구조 검사는 의미 정확성·사람 검수·실행 통과를 증명하지 않는다. 분류별 배점은
     * 범인25/방법20/시간15/동기10/근거30이며 방법·근거에 필수 항목이 있어야 한다. 저장 ruleData의 formatNo·미등록 필드·128KiB 상한 검사는
     * 원문을 읽는 저장 어댑터의 책임이다.
     *
     * @param correctCulpritCode null이 아닌 같은 사본의 고정 정답 코드
     * @param rubrics null이 아닌 전체 활성 항목 5~50개이며 범인 항목은 정확히 하나다
     * @param factCodes null이 아닌 같은 사본의 활성 사실 코드 집합
     * @param clueCodes null이 아닌 같은 사본의 활성 단서 코드 집합
     */
    public record Snapshot(
            String correctCulpritCode,
            List<Rubric> rubrics,
            Set<String> factCodes,
            Set<String> clueCodes) {
        public Snapshot {
            correctCulpritCode = code(correctCulpritCode);
            rubrics = copy(rubrics, 5, 50);
            factCodes = codeSet(factCodes);
            clueCodes = codeSet(clueCodes);
            Set<String> rubricCodes = new HashSet<>();
            Map<Category, Integer> totals = new java.util.EnumMap<>(Category.class);
            int culprits = 0;
            boolean requiredMethod = false;
            boolean requiredEvidence = false;
            for (Rubric rubric : rubrics) {
                if (!rubricCodes.add(rubric.code())
                        || !clueCodes.containsAll(rubric.linkedClueCodes())) invalid();
                totals.merge(rubric.category(), rubric.maxScore(), Integer::sum);
                if (rubric.category() == Category.CULPRIT) culprits++;
                if (rubric.category() == Category.METHOD && rubric.required())
                    requiredMethod = true;
                if (rubric.category() == Category.EVIDENCE && rubric.required())
                    requiredEvidence = true;
                for (ClaimRule claim : rubric.claims()) {
                    if (!factCodes.containsAll(claim.factCodes())) invalid();
                }
            }
            if (culprits != 1
                    || !requiredMethod
                    || !requiredEvidence
                    || !totals.equals(
                            Map.of(
                                    Category.CULPRIT,
                                    25,
                                    Category.METHOD,
                                    20,
                                    Category.TIME,
                                    15,
                                    Category.MOTIVE,
                                    10,
                                    Category.EVIDENCE,
                                    30))) invalid();
        }
    }

    /** Unicode 코드포인트의 반열린 구간이다. 보고서의 상한은 결과 검증기에서 대조한다. */
    public record Span(Field field, int start, int end) {
        public Span {
            if (field == null || start < 0 || end <= start) invalid();
        }
    }

    /** true의 의미 판정에는 최소 1구간이 필요하다. 서버 생성 범인 선택값은 별도 값형을 쓴다. */
    public record Proposition(String code, boolean met, List<Span> spans) {
        public Proposition {
            code = GradeModels.code(code);
            spans = copy(spans, met ? 1 : 0, 10);
        }
    }

    /** 모델에서 받는 원본 값이며 점수·성공·범인 선택 일치는 포함하지 않는다. */
    public record SemanticItem(
            String rubricCode,
            List<Proposition> claims,
            List<Proposition> contradictions,
            String reason) {
        public SemanticItem {
            rubricCode = code(rubricCode);
            claims = copy(claims, 0, 20);
            contradictions = copy(contradictions, 0, 10);
            reason = CommonUtil.normalizeText(reason, 1000, false);
        }
    }

    /** UNRESOLVED는 items=null만 허용하며 부분 정상 결과나 점수로 승격하지 않는다. */
    public record SemanticResult(Status status, List<SemanticItem> items) {
        public SemanticResult {
            if (status == null) invalid();
            if (status == Status.UNRESOLVED) {
                if (items != null) invalid();
            } else {
                items = copy(items, 1, 50);
            }
        }
    }

    /** 서버 생성 SELECTED_CULPRIT만 근거 구간이 없을 수 있으며 나머지는 검증된 의미 판정이다. */
    public record ClaimResult(String code, boolean met, List<Span> spans) {
        public ClaimResult {
            code = GradeModels.code(code);
            spans = copy(spans, 0, 10);
            if (met && spans.isEmpty() && !code.equals("SELECTED_CULPRIT")) invalid();
        }
    }

    /** 기본 점수의 내역이다. 선택 항목의 requiredMet은 null이며 최종 감점을 포함하지 않는다. */
    public record ItemResult(
            String rubricCode,
            int score,
            Boolean requiredMet,
            List<ClaimResult> claims,
            List<Proposition> contradictions,
            String reason) {
        public ItemResult {
            rubricCode = code(rubricCode);
            if (score < 0 || score > 100) invalid();
            claims = copy(claims, 1, 20);
            contradictions = copy(contradictions, 0, 10);
            reason = CommonUtil.normalizeText(reason, 1000, false);
        }
    }

    /** 기본 평가만 나타낸다. UNRESOLVED의 items/baseScore/success는 모두 null이다. */
    public record BaseResult(
            Status status, List<ItemResult> items, Integer baseScore, Boolean success) {
        public BaseResult {
            if (status == null) invalid();
            if (status == Status.UNRESOLVED) {
                if (items != null || baseScore != null || success != null) invalid();
            } else {
                items = copy(items, 1, 50);
                if (baseScore == null
                        || baseScore < 0
                        || baseScore > 100
                        || success == null
                        || items.stream().mapToInt(ItemResult::score).sum() != baseScore) invalid();
            }
        }
    }

    /** 고정 코드는 기존 원고와 같은 대문자 영숫자·밑줄 1~32자로 제한한다. */
    private static String code(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) invalid();
        return value;
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static <T> List<T> copy(List<T> values, int min, int max) {
        if (values == null
                || values.size() < min
                || values.size() > max
                || values.stream().anyMatch(java.util.Objects::isNull)) invalid();
        return List.copyOf(values);
    }

    private static List<String> codes(List<String> values, int min, int max) {
        List<String> result = copy(values, min, max);
        Set<String> seen = new HashSet<>();
        for (String value : result) {
            if (!seen.add(code(value))) invalid();
        }
        return result;
    }

    private static Set<String> codeSet(Set<String> values) {
        if (values == null) invalid();
        for (String value : values) code(value);
        return Set.copyOf(values);
    }

    private static List<List<String>> routes(
            List<List<String>> values, int min, int max, int routeMax) {
        return copy(values, min, max).stream().map(route -> codes(route, 1, routeMax)).toList();
    }

    private static void invalid() {
        throw new IllegalArgumentException("INVALID_GRADE_INPUT");
    }
}
