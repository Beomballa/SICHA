package com.reasoning.common.story.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;

/** 잠금 아래 읽은 서버 원본으로만 일곱 루트 필드의 완전 사본을 구성한다. */
public final class StoryFrozenSnapshotProducer {
    private StoryFrozenSnapshotProducer() {}

    /**
     * 실제 저장 정책·난이도·제한 시간을 사용하며 미작성 값이나 정책 기본값을 보충하지 않는다.
     *
     * @param storyCode null이 아닌 저장된 사건 코드
     * @param versionNo 1 이상인 저장 버전 번호
     * @param sourceRev 전환 전 0 이상인 수정번호
     * @param storedPolicyCode null이 아닌 RULE_20260924 저장값
     * @param sections 모든 nullable 키도 명시한 basic/answer/reveal 서버 원본
     * @param resources 활성 자료 전체의 열한 배열이며 null이 아니다
     * @return 입력과 분리되어 정렬·해시가 확정된 불변 사본
     * @throws IllegalArgumentException 잘못된 원본은 INVALID_FROZEN_SNAPSHOT, 16MiB 초과는
     *     SNAPSHOT_TOO_LARGE
     */
    public static FrozenSnapshot freeze(
            String storyCode,
            int versionNo,
            long sourceRev,
            String storedPolicyCode,
            JsonNode sections,
            JsonNode resources) {
        if (sourceRev < 0 || sections == null || resources == null)
            throw new IllegalArgumentException("INVALID_FROZEN_SNAPSHOT");
        JsonNode basic = sections.get("basic");
        if (basic == null) throw new IllegalArgumentException("INVALID_FROZEN_SNAPSHOT");
        ObjectNode root =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("formatNo", 1)
                        .put("storyCode", storyCode)
                        .put("versionNo", versionNo)
                        .put("sourceRev", Long.toString(sourceRev));
        root.set(
                "policy",
                FrozenSnapshotCodec.createPolicy(
                        storedPolicyCode,
                        integer(basic.get("difficulty")),
                        integer(basic.get("limitSec"))));
        root.set("sections", sections);
        root.set("resources", resources);
        int bytes;
        try {
            bytes = SnapshotJson.encode(root).length;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("INVALID_FROZEN_SNAPSHOT");
        }
        if (bytes > 16 * 1024 * 1024) throw new IllegalArgumentException("SNAPSHOT_TOO_LARGE");
        return FrozenSnapshotCodec.freeze(root);
    }

    /** 노드 하위 타입 대신 정확한 정수 값과 int 범위를 검사하며 문자열 수를 허용하지 않는다. */
    private static int integer(JsonNode value) {
        if (value == null || !value.isNumber())
            throw new IllegalArgumentException("INVALID_FROZEN_SNAPSHOT");
        try {
            return value.decimalValue().intValueExact();
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("INVALID_FROZEN_SNAPSHOT");
        }
    }
}
