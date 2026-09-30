package com.reasoning.common.grading.service;

import com.reasoning.common.grading.model.GradeModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 검증된 유한 명제로 기본 점수·필수 충족만 계산한다. 의미 추론·감점·DB·네트워크는 담당하지 않는다. */
public final class GradeCalculator {
    private final GradeResultValidator validator = new GradeResultValidator();

    /**
     * 모든 활성 소항목을 계산한다. 최고 충족 등록 점수를 선택하고 모순은 해당 항목만 0점으로 만든다. 성공은 모든 필수 조건 충족으로 정하며 별도의 총점 임계값이나
     * 힌트·오답 감점은 적용하지 않는다.
     *
     * @param report null이 아닌 정규화 REPORT-1이며 선택 범인은 고정 정답과 서버에서 직접 비교한다
     * @param snapshot null이 아닌 배점 합계100의 완성 고정 채점표
     * @param semanticResult null이 아닌 전체 의미 결과 또는 UNRESOLVED
     * @return 기본 항목 결과·합계·성공이며 UNRESOLVED일 때 셋 모두 null이다
     * @throws IllegalArgumentException 고정 인수 오류면 INVALID_GRADE_INPUT, 의미 출력 오류면
     *     INVALID_ENGINE_OUTPUT
     */
    public BaseResult calculate(Report report, Snapshot snapshot, SemanticResult semanticResult) {
        SemanticResult validated = validator.validate(report, snapshot, semanticResult);
        if (validated.status() == Status.UNRESOLVED) {
            return new BaseResult(Status.UNRESOLVED, null, null, null);
        }

        Map<String, SemanticItem> semanticItems =
                validated.items().stream()
                        .collect(Collectors.toMap(SemanticItem::rubricCode, Function.identity()));
        List<ItemResult> items = new ArrayList<>();
        int baseScore = 0;
        boolean success = true;
        for (Rubric rubric : snapshot.rubrics()) {
            SemanticItem semanticItem = semanticItems.get(rubric.code());
            List<ClaimResult> claims;
            if (rubric.category() == Category.CULPRIT) {
                claims =
                        List.of(
                                new ClaimResult(
                                        "SELECTED_CULPRIT",
                                        report.culpritCode().equals(snapshot.correctCulpritCode()),
                                        List.of()));
            } else {
                claims =
                        semanticItem.claims().stream()
                                .map(
                                        claim ->
                                                new ClaimResult(
                                                        claim.code(), claim.met(), claim.spans()))
                                .toList();
            }

            Map<String, Boolean> metByCode =
                    claims.stream().collect(Collectors.toMap(ClaimResult::code, ClaimResult::met));
            int score = 0;
            boolean contradicted =
                    semanticItem.contradictions().stream().anyMatch(Proposition::met);
            if (!contradicted) {
                for (Level level : rubric.levels()) {
                    boolean routeMet =
                            level.routes().stream()
                                    .anyMatch(
                                            route ->
                                                    route.stream()
                                                            .allMatch(
                                                                    code ->
                                                                            Boolean.TRUE.equals(
                                                                                    metByCode.get(
                                                                                            code))));
                    if (level.score() > 0 && routeMet) score = Math.max(score, level.score());
                }
            }

            Boolean requiredMet = rubric.required() ? score >= rubric.passScore() : null;
            if (Boolean.FALSE.equals(requiredMet)) success = false;
            items.add(
                    new ItemResult(
                            rubric.code(),
                            score,
                            requiredMet,
                            claims,
                            semanticItem.contradictions(),
                            semanticItem.reason()));
            baseScore += score;
        }
        return new BaseResult(Status.COMPLETE, items, baseScore, success);
    }
}
