package com.reasoning.common.grading.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 보호된 worker 등록 문서만 조립하며 HTTP 인증·실행·DB·제공자를 활성화하지 않는다. */
final class GradeWorkerRegistryLoader {
    /**
     * 모든 등록을 검증한 뒤 한 번만 불변 자격증명 레지스트리를 만든다.
     *
     * @param configuredPath null·빈 문자열·공백이면 읽기 없이 비활성, 그 외에는 보호된 절대 경로
     * @return 비활성에서는 모든 인증을 거절하며 활성에서는 전체 등록만 담는 새 레지스트리
     * @throws IllegalStateException 설정된 파일·경계·파싱 오류이면 원인 없는 INVALID_GRADE_WORKERS
     */
    GradeWorkerCredentials load(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return new GradeWorkerCredentials(List.of());
        }

        try {
            return parse(GradeProtectedDocumentReader.read(configuredPath));
        } catch (IOException | RuntimeException | LinkageError exception) {
            throw failure();
        }
    }

    /**
     * 닫힌 UTF-8 문법과 실제 등록 생성자를 사용하며 토큰·기본 권한·부분 결과를 만들지 않는다.
     *
     * @param bytes 16MiB 이하 원문이며 null·빈 문서·빈 등록 배열은 거절한다
     * @return 전체 등록이 유효할 때만 반환하는 불변 자격증명 레지스트리
     * @throws IllegalStateException 크기·문법·등록·중복 오류이면 원인 없는 INVALID_GRADE_WORKERS
     */
    static GradeWorkerCredentials parse(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > GradeProtectedDocumentReader.MAX_BYTES) {
                throw failure();
            }

            JsonNode root = SnapshotJson.parse(bytes);
            closed(root, Set.of("formatNo", "workers"));
            JsonNode format = root.get("formatNo");
            if (!format.isNumber() || format.decimalValue().intValueExact() != 1) {
                throw failure();
            }

            JsonNode workers = root.get("workers");
            nonemptyArray(workers);
            var registrations = new ArrayList<Registration>();
            for (JsonNode worker : workers) {
                closed(worker, Set.of("workerKey", "credentialSha256", "runtimeCodes", "actions"));
                JsonNode runtimes = worker.get("runtimeCodes");
                nonemptyArray(runtimes);
                var runtimeCodes = new HashSet<String>();
                for (JsonNode runtime : runtimes) {
                    if (!runtimeCodes.add(text(runtime))) throw failure();
                }

                JsonNode permissions = worker.get("actions");
                nonemptyArray(permissions);
                var actions = new HashSet<Action>();
                for (JsonNode permission : permissions) {
                    if (!actions.add(Action.valueOf(text(permission)))) throw failure();
                }

                registrations.add(
                        new Registration(
                                text(worker.get("workerKey")),
                                text(worker.get("credentialSha256")),
                                runtimeCodes,
                                actions));
            }

            return new GradeWorkerCredentials(registrations);
        } catch (RuntimeException | LinkageError exception) {
            throw failure();
        }
    }

    /**
     * 필수 비null 키만 허용하며 미등록 키·누락·다른 구조를 거절한다.
     *
     * @param node 검증할 원문 노드이며 null 또는 객체가 아니면 거절한다
     * @param keys 허용되는 필수 키 전체 집합이며 내부 호출에서 null을 전달하지 않는다
     * @throws IllegalStateException 구조·키·필수 값이 맞지 않으면 INVALID_GRADE_WORKERS
     */
    private static void closed(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || node.size() != keys.size()) throw failure();
        for (String key : keys) {
            if (!node.hasNonNull(key)) throw failure();
        }
    }

    /**
     * 비어 있지 않은 실제 배열만 허용하며 암묵 변환을 거절한다.
     *
     * @param node 필수 배열 노드이며 null·다른 타입·빈 배열은 거절한다
     * @throws IllegalStateException 배열 계약 위반이면 INVALID_GRADE_WORKERS
     */
    private static void nonemptyArray(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) throw failure();
    }

    /**
     * 문자열 원문만 반환하며 공백 제거·대소문자 변환·암묵 변환을 하지 않는다.
     *
     * @param node 필수 문자열 노드이며 null 또는 문자열이 아니면 거절한다
     * @return 변환하지 않은 문자열이며 허용 문법·범위는 등록 생성자에서 검증한다
     * @throws IllegalStateException 문자열 계약 위반이면 INVALID_GRADE_WORKERS
     */
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw failure();
        return node.textValue();
    }

    /** 경로·문서·해시·토큰·원인 예외를 포함하지 않는 고정 오류를 만든다. */
    private static IllegalStateException failure() {
        return new IllegalStateException("INVALID_GRADE_WORKERS");
    }
}
