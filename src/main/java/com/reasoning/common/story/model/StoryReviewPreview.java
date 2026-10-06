package com.reasoning.common.story.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.util.CommonUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/** 서버가 읽은 활성 원본만 허용 목록으로 투영한다. 인증·저장·정책 생성·실행은 하지 않는다. */
public final class StoryReviewPreview {
    private StoryReviewPreview() {}

    /**
     * 선택 역할의 공개 자료만 새 JSON으로 복사하며 부분 초안의 명시 null은 보존한다.
     *
     * @param sections 서버 권위의 basic/answer/reveal 객체이며 null이 아니다
     * @param resources 전체 활성 열한 배열이며 null이 아니다
     * @param roleCode 선택한 활성 역할 코드이며 null이 아니다
     * @return basic/role/persons/clues만 가진 독립적인 공개 자료
     * @throws IllegalArgumentException 선택 역할이 없으면 원문 없는 ROLE_NOT_FOUND
     */
    public static JsonNode role(JsonNode sections, JsonNode resources, String roleCode) {
        JsonNode selected = null;
        for (JsonNode row : resources.get("roles")) {
            if (roleCode.equals(row.get("code").textValue())) selected = row;
        }
        if (selected == null) throw new IllegalArgumentException("ROLE_NOT_FOUND");

        ObjectNode data = object();
        data.set(
                "basic",
                fields(
                        sections.get("basic"),
                        "title",
                        "intro",
                        "setting",
                        "difficulty",
                        "estMin",
                        "estMax",
                        "limitSec"));
        data.set("role", fields(selected, "code", "name", "brief"));
        var persons = new TreeMap<String, JsonNode>(CommonUtil::compareCodePoints);
        for (JsonNode row : resources.get("persons")) {
            persons.put(row.get("code").textValue(), fields(row, "code", "name", "publicText"));
        }
        var publicPersons = data.putArray("persons");
        persons.values().forEach(publicPersons::add);

        Set<String> assigned = new HashSet<>();
        for (JsonNode row : resources.get("clueRoles")) {
            if (roleCode.equals(row.get("roleCode").textValue()))
                assigned.add(row.get("clueCode").textValue());
        }
        var clues = new TreeMap<String, JsonNode>(CommonUtil::compareCodePoints);
        for (JsonNode row : resources.get("clues")) {
            String code = row.get("code").textValue();
            String scope = row.get("scope").textValue();
            if ("COMMON".equals(scope) || ("ROLE".equals(scope) && assigned.contains(code)))
                clues.put(code, fields(row, "code", "title", "body", "personCode"));
        }
        var publicClues = data.putArray("clues");
        clues.values().forEach(publicClues::add);
        return data;
    }

    /**
     * 해설 검토에는 제목과 저장 해설만 복사한다.
     *
     * @param sections null이 아닌 서버 권위의 저장 영역이며 부분 원고 null을 허용한다
     * @return title/revealText만 가진 새 객체
     */
    public static JsonNode reveal(JsonNode sections) {
        ObjectNode data = fields(sections.get("basic"), "title");
        data.set("revealText", sections.get("reveal").get("revealText").deepCopy());
        return data;
    }

    private static ObjectNode fields(JsonNode row, String... names) {
        ObjectNode result = object();
        for (String name : names) result.set(name, row.get(name).deepCopy());
        return result;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }
}
