package com.reasoning.common.grading.repository;

import com.reasoning.common.grading.model.GradeDictionary;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** 활성 사전의 한 SELECT 사본만 읽는다. 관리 CRUD나 현재 사전으로의 대체는 제공하지 않는다. */
public final class GradeDictionaryRepository {
    private final JdbcTemplate jdbc;

    /**
     * @param jdbc null이 아닌 호출자가 구성한 DB 연결 도구
     * @throws NullPointerException jdbc가 null인 경우
     */
    public GradeDictionaryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * 한 문장의 일관된 활성 행으로 사전과 해시를 함께 만들고 등록 해시를 검사한다.
     *
     * @param dictionaryCode null이 아닌 대문자 영숫자·밑줄 1~80자
     * @param expectedSha256 null이 아닌 등록된 소문자 SHA-256 64자리
     * @return 검사에 사용한 바로 그 불변 사본이며 검사 뒤 재조회하지 않는다
     * @throws IllegalArgumentException 코드·해시·행 무결성이 잘못되거나 등록 해시와 다른 경우
     * @throws org.springframework.dao.DataAccessException DB 조회 실패인 경우
     */
    public GradeDictionary load(String dictionaryCode, String expectedSha256) {
        if (dictionaryCode == null
                || !dictionaryCode.matches("[A-Z0-9_]{1,80}")
                || expectedSha256 == null
                || !expectedSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("INVALID_DICTIONARY_REGISTRATION");
        }
        var rows =
                jdbc.query(
                        """
                        SELECT concept_code, canonical_text, alias_text
                        FROM public.grade_term
                        WHERE dictionary_code = ? AND active_yn = true
                        ORDER BY dictionary_code COLLATE "C", concept_code COLLATE "C", alias_text COLLATE "C"
                        """,
                        (row, number) ->
                                new GradeDictionary.Term(
                                        row.getString("concept_code"),
                                        row.getString("canonical_text"),
                                        row.getString("alias_text")),
                        dictionaryCode);
        GradeDictionary dictionary = new GradeDictionary(dictionaryCode, rows);
        if (!dictionary.sha256().equals(expectedSha256)) {
            throw new IllegalArgumentException("DICTIONARY_HASH_MISMATCH");
        }
        return dictionary;
    }
}
