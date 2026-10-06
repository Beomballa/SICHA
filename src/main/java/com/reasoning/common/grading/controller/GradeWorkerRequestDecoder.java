package com.reasoning.common.grading.controller;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 원본 전송 봉투의 문법만 검사하며 현재성·등록 좌표·구간 대조·점수 계산은 실제 서비스에 맡긴다. */
public final class GradeWorkerRequestDecoder {
    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(12)
                                                    .maxStringLength(262144)
                                                    .maxNumberLength(20)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ERRORS =
            Set.of(
                    "ENGINE_TIMEOUT",
                    "ENGINE_UNAVAILABLE",
                    "INVALID_OUTPUT",
                    "UNRESOLVED_REASONING",
                    "WORKER_LOST");

    /** 원인·원문·스택을 보관하지 않는 전송 입력 오류다. */
    public static final class Failure extends RuntimeException {
        private final boolean oversized;

        /**
         * @param oversized 전송 바이트 상한 초과 여부
         */
        private Failure(boolean oversized) {
            super(oversized ? "PAYLOAD_TOO_LARGE" : "INVALID_REMOTE_REQUEST", null, false, false);
            this.oversized = oversized;
        }

        /**
         * @return 전송 바이트 상한 초과 여부
         */
        public boolean oversized() {
            return oversized;
        }
    }

    /** 민감 관측·의미 원문을 문자열 표현에 포함하지 않는 검증된 완료 입력이다. */
    public record Completion(
            long leaseGen,
            int attemptNo,
            String observedProviderVersion,
            String providerResponseRef,
            String resultJson) {
        /**
         * @return 원문 없는 고정 표현
         */
        @Override
        public String toString() {
            return "Completion[redacted]";
        }
    }

    /**
     * 경로 UUID를 별칭 없이 검증한다.
     *
     * @param value 소문자 canonical UUID v4, null 불가
     * @return 검증된 비영 UUID
     * @throws Failure 문법·버전·variant가 다르면 고정 입력 오류
     */
    public UUID jobKey(String value) {
        if (value == null
                || !value.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value);
    }

    /**
     * 정확한 START 봉투를 읽는다.
     *
     * @param request 쿼리 없는 UTF-8 JSON 요청, null 불가, 전체 8KiB 이하
     * @return 양수 long 임대 세대
     * @throws Failure 전송·키·타입·범위 오류 또는 상한 초과
     */
    public long start(HttpServletRequest request) {
        JsonNode root = read(request, 8192);
        keys(root, "leaseGen");
        return integer(root.get("leaseGen"), 1, Long.MAX_VALUE);
    }

    /**
     * before-chat·renew의 원래 시도 tuple을 읽는다.
     *
     * @param request 쿼리 없는 UTF-8 JSON 요청, null 불가, 전체 8KiB 이하
     * @return 검증된 기존 프로토콜 입력
     * @throws Failure 전송·키·타입·범위 오류 또는 상한 초과
     */
    public AttemptRequest attempt(HttpServletRequest request) {
        JsonNode root = read(request, 8192);
        keys(root, "leaseGen", "attemptNo", "originalAttemptHash");
        String hash = text(root.get("originalAttemptHash"));
        if (!hash.matches("[0-9a-f]{64}")) throw invalid();
        return new AttemptRequest(
                integer(root.get("leaseGen"), 1, Long.MAX_VALUE),
                (int) integer(root.get("attemptNo"), 1, 3),
                hash);
    }

    /**
     * 전체 256KiB 완료 봉투의 닫힌 결과 문법을 검사한다. 배열 순서와 관측 문자열은 보존한다.
     *
     * @param request 쿼리 없는 UTF-8 JSON 요청, null 불가
     * @return 결과 subtree만 재직렬화한 민감 입력; 등록 집합·점수 검증은 하지 않음
     * @throws Failure 전송·중첩 키·타입·Unicode 오류 또는 전체 봉투 상한 초과
     */
    public Completion complete(HttpServletRequest request) {
        JsonNode root = read(request, 262144);
        keys(
                root,
                "leaseGen",
                "attemptNo",
                "observedProviderVersion",
                "providerResponseRef",
                "result");
        String observed = nullableText(root.get("observedProviderVersion"));
        String reference = nullableText(root.get("providerResponseRef"));
        if ((observed != null && observed.codePointCount(0, observed.length()) > 160)
                || (reference != null && !reference.matches("[A-Za-z0-9_./-]{8,120}")))
            throw invalid();
        JsonNode result = root.get("result");
        result(result);
        return new Completion(
                integer(root.get("leaseGen"), 1, Long.MAX_VALUE),
                (int) integer(root.get("attemptNo"), 1, 3),
                observed,
                reference,
                result.toString());
    }

    /**
     * 길이 헤더와 무관하게 cap+1 바이트까지만 읽고 원본 UTF-8·중복 키·후행 JSON을 검사한다.
     *
     * @param request JSON 요청, null 불가
     * @param cap 전체 봉투의 양수 바이트 상한
     * @return Unicode 검증을 마친 JSON 트리
     * @throws Failure 입력 오류·읽기 실패 또는 상한 초과
     */
    private static JsonNode read(HttpServletRequest request, int cap) {
        try {
            if (request.getQueryString() != null || request.getHeader("Content-Encoding") != null)
                throw invalid();
            var types = Collections.list(request.getHeaders("Content-Type"));
            if (types.size() != 1) throw invalid();
            MediaType type = MediaType.parseMediaType(types.getFirst());
            if (!"application".equalsIgnoreCase(type.getType())
                    || !"json".equalsIgnoreCase(type.getSubtype())
                    || type.getParameters().keySet().stream()
                            .anyMatch(key -> !"charset".equalsIgnoreCase(key))
                    || (type.getCharset() != null
                            && !StandardCharsets.UTF_8.equals(type.getCharset()))) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(cap + 1);
            if (bytes.length > cap) throw new Failure(true);
            String source =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            JsonNode root = JSON.readTree(source);
            if (root == null) throw invalid();
            unicode(root);
            return root;
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /**
     * 디코딩된 모든 문자열·키에서 NUL과 고립 surrogate를 거절한다.
     *
     * @param node null 아닌 파싱 트리
     * @throws Failure 유효 Unicode가 아니면 고정 입력 오류
     */
    private static void unicode(JsonNode node) {
        if (node.isTextual()) unicode(node.textValue());
        if (node.isObject()) node.fieldNames().forEachRemaining(GradeWorkerRequestDecoder::unicode);
        for (JsonNode child : node) unicode(child);
    }

    /**
     * 문자열을 수선하지 않고 Unicode scalar 여부만 검사한다.
     *
     * @param value null 아닌 디코딩 문자열
     * @throws Failure NUL 또는 고립 surrogate가 있으면 고정 입력 오류
     */
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }

    /**
     * 결과의 모든 객체를 닫힌 전송 문법으로 검사한다. 서버 의미 대조를 복제하지 않는다.
     *
     * @param node null 불가인 결과 subtree
     * @throws Failure 키·타입·고정 분기 값이 다르면 고정 입력 오류
     */
    private static void result(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        String kind = text(node.get("kind"));
        if (!"COMPLETE".equals(kind)) {
            keys(node, "kind", "errorCode");
            if (!Set.of("ERROR", "UNRESOLVED").contains(kind)
                    || !ERRORS.contains(text(node.get("errorCode")))) throw invalid();
            return;
        }
        keys(node, "kind", "semantic");
        JsonNode semantic = node.get("semantic");
        keys(semantic, "formatNo", "status", "items");
        integer(semantic.get("formatNo"), 1, 1);
        if (!"COMPLETE".equals(text(semantic.get("status")))) throw invalid();
        for (JsonNode item : array(semantic.get("items"))) {
            keys(item, "rubricCode", "claims", "contradictions", "reason");
            text(item.get("rubricCode"));
            text(item.get("reason"));
            propositions(item.get("claims"));
            propositions(item.get("contradictions"));
        }
    }

    /**
     * 소항목·인용 구간의 키와 토큰 타입만 검사한다.
     *
     * @param node null 불가인 소항목 배열
     * @throws Failure 닫힌 문법이 아니면 고정 입력 오류
     */
    private static void propositions(JsonNode node) {
        for (JsonNode proposition : array(node)) {
            keys(proposition, "code", "met", "spans");
            text(proposition.get("code"));
            if (!proposition.get("met").isBoolean()) throw invalid();
            for (JsonNode span : array(proposition.get("spans"))) {
                keys(span, "field", "start", "end");
                if (!Set.of("method", "time", "motive", "evidence")
                        .contains(text(span.get("field")))) throw invalid();
                integer(span.get("start"), 0, Integer.MAX_VALUE);
                integer(span.get("end"), 1, Integer.MAX_VALUE);
            }
        }
    }

    /**
     * 배열 토큰을 강제한다.
     *
     * @param node 배열 토큰, null 불가
     * @return 원래 배열
     * @throws Failure 배열이 아니면 입력 오류
     */
    private static JsonNode array(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid();
        return node;
    }

    /**
     * 필수 키 집합을 정확히 대조한다.
     *
     * @param node 객체 토큰, null 불가
     * @param names 정확한 필수 키
     * @throws Failure 키 집합 불일치
     */
    private static void keys(JsonNode node, String... names) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(names))) throw invalid();
    }

    /**
     * 문자열 토큰을 변환 없이 읽는다.
     *
     * @param node 문자열 토큰, null 불가
     * @return 수선 없는 문자열
     * @throws Failure 문자열이 아니면 입력 오류
     */
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    /**
     * 관측값의 명시 null과 문자열을 구분한다.
     *
     * @param node 명시 null 또는 문자열, 누락 불가
     * @return 원래 문자열 또는 null
     * @throws Failure 타입 오류
     */
    private static String nullableText(JsonNode node) {
        if (node == null) throw invalid();
        return node.isNull() ? null : text(node);
    }

    /**
     * 분수·지수·문자열·overflow를 허용하지 않는 JSON 정수 토큰을 검사한다.
     *
     * @param node 정수 토큰, null 불가
     * @param min 포함 하한
     * @param max 포함 상한
     * @return 검증된 long
     * @throws Failure 타입·범위 오류
     */
    private static long integer(JsonNode node, long min, long max) {
        if (node == null
                || !node.isIntegralNumber()
                || !node.canConvertToLong()
                || node.longValue() < min
                || node.longValue() > max) throw invalid();
        return node.longValue();
    }

    /**
     * @return 원문·원인 없는 고정 입력 오류
     */
    private static Failure invalid() {
        return new Failure(false);
    }
}
