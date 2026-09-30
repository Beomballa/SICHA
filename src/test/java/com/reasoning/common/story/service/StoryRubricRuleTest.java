package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

class StoryRubricRuleTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final StoryRubricRule rules = new StoryRubricRule(db, mapper);

    @BeforeEach
    void referencesExist() {
        when(db.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
        when(db.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        when(db.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
        when(db.queryForObject(
                        eq("SELECT octet_length(?::jsonb::text)"), eq(Integer.class), anyString()))
                .thenReturn(100);
    }

    @Test
    void structuredReferencesDoNotParseNaturalLanguage() throws Exception {
        JsonNode rule = sample();
        assertThat(StoryRubricRule.factCodes(rule)).containsExactly("F");
        assertThat(StoryRubricRule.clueCodes(rule)).containsExactly("C");
        ((ObjectNode) rule.path("claims").get(0))
                .put("meaning", "C F other references are only prose");
        assertThat(StoryRubricRule.factCodes(rule)).isEqualTo(Set.of("F"));
        assertThat(StoryRubricRule.clueCodes(rule)).isEqualTo(Set.of("C"));
        assertThat(StoryRubricRule.factCodes(null)).isEmpty();
        assertThat(StoryRubricRule.clueCodes(null)).isEmpty();
    }

    @Test
    void unknownNestedMembersAreRequestErrorsNotValues() throws Exception {
        for (String pointer : new String[] {"/claims/0", "/levels/0", "/contradictions/0"}) {
            JsonNode rule = sample();
            ((ObjectNode) rule.at(pointer)).put("unapproved", 1);
            rejected("INVALID_REQUEST", rule);
        }
        JsonNode root = sample();
        ((ObjectNode) root).put("unapproved", 1);
        rejected("INVALID_REQUEST", root);
    }

    @Test
    void malformedShapesScoresRoutesAndCodesAreValueErrors() throws Exception {
        for (String json :
                new String[] {
                    "[]",
                    "{}",
                    "{\"formatNo\":1}",
                    "{\"formatNo\":1,\"requiredNotice\":null,\"claims\":[],\"levels\":[],\"contradictions\":[]}"
                }) rejected("INVALID_INPUT", mapper.readTree(json));
        for (String pointer : new String[] {"/formatNo", "/claims", "/levels", "/contradictions"}) {
            JsonNode rule = sample();
            ((ObjectNode) rule).remove(pointer.substring(1));
            rejected("INVALID_INPUT", rule);
        }
        JsonNode invalid = sample();
        ((ObjectNode) invalid.path("levels").get(1)).put("score", 9);
        rejected("INVALID_INPUT", invalid);
        invalid = sample();
        ((ObjectNode) invalid.path("claims").get(0)).put("code", "lower");
        rejected("INVALID_INPUT", invalid);
        invalid = sample();
        ((ObjectNode) invalid.path("levels").get(1)).putArray("routes").addArray().add("MISSING");
        rejected("INVALID_INPUT", invalid);
        invalid = sample();
        ((ObjectNode) invalid.path("claims").get(0)).putArray("factCodes").add("F").add("F");
        rejected("INVALID_INPUT", invalid);
        invalid = sample();
        ((ObjectNode) invalid.path("claims").get(0)).put("meaning", "𐐀".repeat(1001));
        rejected("INVALID_INPUT", invalid);
    }

    @Test
    void fixedCulpritRuleCannotBeChanged() {
        JsonNode canonical = rules.culprit();
        assertThat(StoryRubricRule.factCodes(canonical)).isEmpty();
        assertThat(StoryRubricRule.clueCodes(canonical)).isEmpty();
        assertThat(rules.validate(canonical.deepCopy(), 1, "CULPRIT", 25, true, true))
                .contains("SELECTED_CULPRIT");
        JsonNode altered = canonical.deepCopy();
        ((ObjectNode) altered.path("contradictions").get(0)).put("meaning", "different");
        rejected("INVALID_INPUT", altered, true);
        altered = canonical.deepCopy();
        ((ObjectNode) altered.path("claims").get(0)).put("unknown", "x");
        rejected("INVALID_REQUEST", altered, true);
    }

    @Test
    void linkedExamplesMustExistInActiveRelationNotJustClue() throws Exception {
        JsonNode valid = sample();
        assertThat(rules.validate(valid.deepCopy(), 7, "R", 10, true, false)).contains("\"C\"");
        when(db.queryForObject(anyString(), eq(Integer.class), eq(7L), eq("C"), eq("R")))
                .thenReturn(0);
        rejected("INVALID_INPUT", valid);
    }

    private JsonNode sample() throws Exception {
        return mapper.readTree(
                """
                {"formatNo":1,"requiredNotice":"Find the fact","claims":[{"code":"CLAIM",
                "meaning":"Evidence supports this","factCodes":["F"],"exampleClueRoutes":[["C"]]}],
                "levels":[{"code":"ZERO","score":0,"routes":[]},
                {"code":"FULL","score":10,"routes":[["CLAIM"]]}],
                "contradictions":[{"code":"CONTRA","meaning":"Contrary evidence"}]}
                """);
    }

    private void rejected(String code, JsonNode rule) {
        rejected(code, rule, false);
    }

    private void rejected(String code, JsonNode rule, boolean culprit) {
        assertThatThrownBy(() -> rules.validate(rule, 7, "R", culprit ? 25 : 10, true, culprit))
                .isInstanceOf(AuthException.class)
                .satisfies(error -> assertThat(((AuthException) error).code()).isEqualTo(code));
    }
}
