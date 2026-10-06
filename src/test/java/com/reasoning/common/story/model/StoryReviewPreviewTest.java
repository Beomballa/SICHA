package com.reasoning.common.story.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.SnapshotJson;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** 공개 필드의 서버 투영만 검사하며 인증·사본 저장·사람 검수나 실제 실행을 증명하지 않는다. */
class StoryReviewPreviewTest {
    private static final String SECRET = "SYNTHETIC_PRIVATE_CANARY";

    @Test
    void roleHasExactAllowlistAndNeverCopiesPrivateOrOtherRoleFields() {
        ObjectNode source = source();
        JsonNode data =
                StoryReviewPreview.role(source.get("sections"), source.get("resources"), "R1");
        assertThat(keys(data)).containsExactlyInAnyOrder("basic", "role", "persons", "clues");
        assertThat(keys(data.get("basic")))
                .containsExactlyInAnyOrder(
                        "title", "intro", "setting", "difficulty", "estMin", "estMax", "limitSec");
        assertThat(keys(data.get("role"))).containsExactlyInAnyOrder("code", "name", "brief");
        assertThat(data.get("role").get("code").textValue()).isEqualTo("R1");
        for (JsonNode person : data.get("persons"))
            assertThat(keys(person)).containsExactlyInAnyOrder("code", "name", "publicText");
        for (JsonNode clue : data.get("clues"))
            assertThat(keys(clue)).containsExactlyInAnyOrder("code", "title", "body", "personCode");
        assertThat(new String(SnapshotJson.encode(data), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(
                        SECRET,
                        "secretText",
                        "sourceText",
                        "ruleData",
                        "checkedBy",
                        "revealText",
                        "timelineOrigin");
    }

    @Test
    void accessibleCommonAndSelectedRoleUnionIsSortedAndDeduplicated() {
        ObjectNode source = source();
        JsonNode r1 =
                StoryReviewPreview.role(source.get("sections"), source.get("resources"), "R1");
        assertThat(codes(r1.get("persons"))).containsExactly("P1", "P2");
        assertThat(codes(r1.get("clues"))).containsExactly("C1", "C2");
        JsonNode r2 =
                StoryReviewPreview.role(source.get("sections"), source.get("resources"), "R2");
        assertThat(codes(r2.get("clues"))).containsExactly("C2", "C3");
        assertThat(codes(r1.get("clues"))).doesNotContain("C3", "C4");
    }

    @Test
    void partialNullsRemainExplicitAndReturnedTreeIsDetachedFromSource() {
        ObjectNode source = source();
        byte[] before = SnapshotJson.encode(source);
        JsonNode data =
                StoryReviewPreview.role(source.get("sections"), source.get("resources"), "R1");
        for (String field : List.of("difficulty", "estMin", "estMax", "limitSec")) {
            assertThat(data.get("basic").has(field)).isTrue();
            assertThat(data.get("basic").get(field).isNull()).isTrue();
        }
        assertThat(data.get("role").get("brief").isNull()).isTrue();
        assertThat(data.get("persons").get(0).get("publicText").isNull()).isTrue();
        assertThat(data.get("clues").get(0).get("body").isNull()).isTrue();
        ((ObjectNode) data.get("basic")).put("title", "반환된 노드 수정");
        ((ObjectNode) data.get("persons").get(0)).put("name", "반환된 인물 수정");
        assertThat(SnapshotJson.encode(source)).isEqualTo(before);
        ((ObjectNode) source.get("sections").get("basic")).put("setting", "원본 변경");
        assertThat(data.get("basic").get("setting").textValue()).isEqualTo("저장된 배경");
    }

    @Test
    void revealHasOnlySavedTitleAndExplanationWithNullPreserved() {
        ObjectNode source = source();
        JsonNode data = StoryReviewPreview.reveal(source.get("sections"));
        assertThat(keys(data)).containsExactlyInAnyOrder("title", "revealText");
        assertThat(data.get("title").textValue()).isEqualTo("저장된 제목");
        assertThat(data.get("revealText").isNull()).isTrue();
        ((ObjectNode) source.get("sections").get("reveal")).put("revealText", "저장한 해설");
        assertThat(StoryReviewPreview.reveal(source.get("sections")).get("revealText").textValue())
                .isEqualTo("저장한 해설");
        assertThat(data.get("revealText").isNull()).isTrue();
    }

    @Test
    void absentSelectedRoleCannotFallBackToFirstOrExposePrivateSourceInError() {
        ObjectNode source = source();
        assertThatThrownBy(
                        () ->
                                StoryReviewPreview.role(
                                        source.get("sections"), source.get("resources"), "ABSENT"))
                .hasMessage("ROLE_NOT_FOUND")
                .hasNoCause();
    }

    /** 실제 loader가 제공하는 nullable 키와 전체 활성 배열 형태를 가진 합성 원본을 만든다. */
    private static ObjectNode source() {
        ObjectNode root =
                (ObjectNode)
                        SnapshotJson.parse(
                                ("""
                                {"sections":{"basic":{"title":"저장된 제목","intro":"저장된 소개","setting":"저장된 배경","difficulty":null,"estMin":null,"estMax":null,"limitSec":null,"timelineOrigin":"PRIVATE"},
                                 "answer":{"culpritCode":"PRIVATE","methodAnswer":"PRIVATE","timeAnswer":"PRIVATE","motiveAnswer":"PRIVATE"},"reveal":{"revealText":null}},
                                 "resources":{"roles":[{"code":"R2","name":"PRIVATE","brief":"PRIVATE"},{"code":"R1","name":"역할 하나","brief":null}],
                                 "persons":[{"code":"P2","name":"공개 둘","publicText":"소개","secretText":"PRIVATE"},{"code":"P1","name":"공개 하나","publicText":null,"secretText":"PRIVATE"}],
                                 "clues":[{"code":"C4","title":"PRIVATE","body":"PRIVATE","personCode":null,"scope":"ROLE","sourceText":"PRIVATE"},
                                 {"code":"C3","title":"PRIVATE","body":"PRIVATE","personCode":null,"scope":"ROLE","sourceText":"PRIVATE"},
                                 {"code":"C2","title":"공통 단서","body":"본문","personCode":"P2","scope":"COMMON","sourceText":"PRIVATE"},
                                 {"code":"C1","title":"역할 단서","body":null,"personCode":null,"scope":"ROLE","sourceText":"PRIVATE"}],
                                 "clueRoles":[{"clueCode":"C3","roleCode":"R2"},{"clueCode":"C2","roleCode":"R1"},{"clueCode":"C1","roleCode":"R1"},{"clueCode":"C1","roleCode":"R1"}],
                                 "pairs":[{"roleA":"R1","roleB":"R2"}],"hints":[{"body":"PRIVATE"}],"events":[{"actualText":"PRIVATE"}],"facts":[{"statement":"PRIVATE"}],
                                 "rubrics":[{"ruleData":"PRIVATE"}],"rubricClues":[{"linkText":"PRIVATE"}],"gradeSamples":[{"inputData":"PRIVATE","checkedBy":"PRIVATE"}]}}
                                """)
                                        .replace("PRIVATE", SECRET)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return root;
    }

    private static List<String> keys(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static List<String> codes(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(row -> result.add(row.get("code").textValue()));
        return result;
    }
}
