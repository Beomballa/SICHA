package com.reasoning.common.grading.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.util.CommonUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 단어 발견을 충족 판정으로 바꾸지 않는 형식1의 불변 의미 주석 사전이다. */
public final class GradeDictionary {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Comparator<String> CODE_POINT_ORDER = CommonUtil::compareCodePoints;
    private final String dictionaryCode;
    private final List<Term> terms;
    private final List<Concept> concepts;
    private final String canonicalJson;
    private final String sha256;

    /**
     * 동일 행 사본에서 정규화·개념 일관성 검사·결정적 JSON·해시를 만든다.
     *
     * @param dictionaryCode null이 아닌 대문자 영숫자·밑줄 1~80자
     * @param terms null이 아닌 전체 활성 행이며 빈 사전도 허용한다
     * @throws IllegalArgumentException null 행, 중복 키 또는 같은 개념의 표준어 불일치인 경우
     */
    public GradeDictionary(String dictionaryCode, List<Term> terms) {
        this.dictionaryCode = code(dictionaryCode, 80);
        if (terms == null || terms.stream().anyMatch(Objects::isNull)) invalid();
        List<Term> sorted = new ArrayList<>(terms);
        sorted.sort(
                Comparator.comparing(Term::conceptCode, CODE_POINT_ORDER)
                        .thenComparing(Term::alias, CODE_POINT_ORDER)
                        .thenComparing(Term::canonical, CODE_POINT_ORDER));
        Map<String, String> canonicalByCode = new HashMap<>();
        Map<String, List<String>> aliasesByCode = new HashMap<>();
        for (Term term : sorted) {
            String previous = canonicalByCode.putIfAbsent(term.conceptCode(), term.canonical());
            if (previous != null && !previous.equals(term.canonical())) invalid();
            List<String> aliases =
                    aliasesByCode.computeIfAbsent(term.conceptCode(), ignored -> new ArrayList<>());
            if (aliases.contains(term.alias())) invalid();
            aliases.add(term.alias());
        }
        this.terms = List.copyOf(sorted);
        this.concepts =
                canonicalByCode.keySet().stream()
                        .sorted(CODE_POINT_ORDER)
                        .map(
                                value ->
                                        new Concept(
                                                value,
                                                canonicalByCode.get(value),
                                                aliasesByCode.get(value)))
                        .toList();
        try {
            var root = MAPPER.createObjectNode();
            root.put("formatNo", 1);
            root.put("dictionaryCode", this.dictionaryCode);
            var entries = root.putArray("terms");
            for (Term term : this.terms) {
                var entry = entries.addObject();
                entry.put("conceptCode", term.conceptCode());
                entry.put("canonical", term.canonical());
                entry.put("alias", term.alias());
            }
            this.canonicalJson = MAPPER.writeValueAsString(root);
            this.sha256 = CommonUtil.sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("DICTIONARY_SERIALIZATION_FAILED");
        }
    }

    /**
     * 각 표현은 LF 정규화·공백 보존한다.
     *
     * @param conceptCode null이 아닌 대문자 영숫자·밑줄 1~32자
     * @param canonical null이 아닌 공백 전용이 아닌 1~200 Unicode 코드포인트 표준 표현
     * @param alias null이 아닌 공백 전용이 아닌 1~200 Unicode 코드포인트 별칭
     * @throws IllegalArgumentException 코드·길이 오류, null·NUL·잘못된 서로게이트인 경우
     */
    public record Term(String conceptCode, String canonical, String alias) {
        public Term {
            conceptCode = code(conceptCode, 32);
            canonical = text(canonical, 200);
            alias = text(alias, 200);
        }
    }

    /**
     * 동일 별칭이 여러 개념에 속해도 모두 보존하는 개념별 읽기 전용 주석이다.
     *
     * @param code null이 아닌 대문자 영숫자·밑줄 1~32자
     * @param canonical null이 아닌 공백 전용이 아닌 1~200 코드포인트 표준 표현
     * @param aliases null이 아닌 하나 이상의 별칭, 각 값은 null 아닌 1~200 코드포인트
     * @throws IllegalArgumentException 표현·코드가 잘못되거나 별칭 목록이 비어 있는 경우
     */
    public record Concept(String code, String canonical, List<String> aliases) {
        public Concept {
            code = GradeDictionary.code(code, 32);
            canonical = text(canonical, 200);
            if (aliases == null || aliases.isEmpty()) invalid();
            aliases =
                    aliases.stream()
                            .map(value -> text(value, 200))
                            .sorted(CODE_POINT_ORDER)
                            .toList();
        }
    }

    public String dictionaryCode() {
        return dictionaryCode;
    }

    public List<Term> terms() {
        return terms;
    }

    public List<Concept> concepts() {
        return concepts;
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    public String sha256() {
        return sha256;
    }

    /**
     * 별칭에 해당하는 모든 후보를 반환하며 보고서를 검색하거나 충족 여부를 판정하지 않는다.
     *
     * @param alias null이 아닌 1~200 코드포인트 별칭
     * @return 같은 별칭을 갖는 모든 개념의 행이며 미등록이면 빈 목록
     * @throws IllegalArgumentException null·공백 전용·잘못된 Unicode·길이 초과인 경우
     */
    public List<Term> candidates(String alias) {
        String normalized = text(alias, 200);
        return terms.stream().filter(term -> term.alias().equals(normalized)).toList();
    }

    private static String code(String value, int max) {
        if (value == null || !value.matches("[A-Z0-9_]{1," + max + "}")) invalid();
        return value;
    }

    private static String text(String value, int max) {
        return CommonUtil.normalizeText(value, max, false);
    }

    private static void invalid() {
        throw new IllegalArgumentException("INVALID_GRADE_DICTIONARY");
    }
}
