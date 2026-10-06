package com.reasoning.common.grading.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Servlet 원본 stream만으로 닫힌 문법을 검사한다. mock의 secure 값은 TLS 증거로 사용하지 않는다. */
class GradeWorkerRequestDecoderTest {
    private final GradeWorkerRequestDecoder decoder = new GradeWorkerRequestDecoder();
    private static final String HASH = "a".repeat(64);
    private static final String ERROR = "{\"kind\":\"ERROR\",\"errorCode\":\"ENGINE_TIMEOUT\"}";
    private static final String SEMANTIC =
            "{\"kind\":\"COMPLETE\",\"semantic\":{\"formatNo\":1,\"status\":\"COMPLETE\",\"items\":["
                + "{\"rubricCode\":\"ONE\",\"claims\":[{\"code\":\"C\",\"met\":false,\"spans\":["
                + "{\"field\":\"method\",\"start\":0,\"end\":1}]}],\"contradictions\":[],\"reason\":\"CANARY\"}]}}";

    /** 양 끝 정수·세 번째 시도·배열 순서·문자열 보존은 parser 책임이다. */
    @Test
    void acceptsPositiveEdgesWithoutCoercion() {
        assertThat(decoder.start(request("{\"leaseGen\":1}"))).isEqualTo(1);
        assertThat(decoder.start(request("{\"leaseGen\":9223372036854775807}")))
                .isEqualTo(Long.MAX_VALUE);
        var attempt = decoder.attempt(request(attempt("3", HASH)));
        assertThat(attempt.attemptNo()).isEqualTo(3);
        assertThat(attempt.originalAttemptHash()).isEqualTo(HASH);
        var complete =
                decoder.complete(request(envelope(SEMANTIC, "\"  관측😀  \"", "\"abcd/123\"")));
        assertThat(complete.observedProviderVersion()).isEqualTo("  관측😀  ");
        assertThat(complete.providerResponseRef()).isEqualTo("abcd/123");
        assertThat(complete.resultJson()).isEqualTo(SEMANTIC);
        assertThat(complete.toString()).isEqualTo("Completion[redacted]").doesNotContain("CANARY");
        assertThat(decoder.complete(request(envelope(ERROR))).observedProviderVersion()).isNull();
        assertThat(
                        decoder.complete(request(envelope(ERROR.replace("ERROR", "UNRESOLVED"))))
                                .attemptNo())
                .isEqualTo(1);
    }

    /** 헤더 길이를 모르는 실제 stream에서 전체 봉투 상한은 inclusive이고 +1은 거절된다. */
    @Test
    void countsWholeEnvelopeBytesWithoutContentLength() {
        String start = "{\"leaseGen\":1}";
        String complete = envelope(ERROR);
        for (int cap : new int[] {8192, 262144}) {
            String seed = cap == 8192 ? start : complete;
            MockHttpServletRequest exact = unknownLength(seed + " ".repeat(cap - seed.length()));
            assertThat(exact.getContentLengthLong()).isEqualTo(-1);
            if (cap == 8192) assertThat(decoder.start(exact)).isEqualTo(1);
            else assertThat(decoder.complete(exact).resultJson()).isEqualTo(ERROR);
            var excess = unknownLength(seed + " ".repeat(cap + 1 - seed.length()));
            assertThatThrownBy(
                            () -> {
                                if (cap == 8192) decoder.start(excess);
                                else decoder.complete(excess);
                            })
                    .isInstanceOfSatisfying(
                            GradeWorkerRequestDecoder.Failure.class,
                            error -> {
                                assertThat(error.oversized()).isTrue();
                                assertThat(error.getMessage()).isEqualTo("PAYLOAD_TOO_LARGE");
                                assertThat(error.getCause()).isNull();
                            });
        }
    }

    /** 정수 토큰의 비정규형·범위·타입을 모두 거절한다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "-1",
                "-0",
                "1.0",
                "1e0",
                "1E+0",
                "\"1\"",
                "null",
                "true",
                "[]",
                "{}",
                "9223372036854775808",
                "18446744073709551615",
                "+1",
                "01",
                "NaN"
            })
    void rejectsLeaseLexicalForms(String value) {
        invalid(() -> decoder.start(request("{\"leaseGen\":" + value + "}")));
    }

    /** escaped 키도 decoded 키와 충돌하며 모든 depth의 중복을 거절한다. */
    @Test
    void rejectsDecodedDuplicatesTrailingDocumentsAndUnknownKeys() {
        for (String body :
                new String[] {
                    "{\"leaseGen\":1,\"leaseGen\":2}",
                    "{\"leaseGen\":1,\"lease\\u0047en\":2}",
                    "{\"leaseGen\":1} {}",
                    "{\"leaseGen\":1} null",
                    "{\"leaseGen\":1,\"workerKey\":\"CANARY\"}",
                    "{}",
                    "[]",
                    "null",
                    ""
                }) invalid(() -> decoder.start(request(body)));
        for (String result :
                new String[] {
                    SEMANTIC.replace(
                            "\"kind\":\"COMPLETE\"", "\"kind\":\"COMPLETE\",\"kind\":\"COMPLETE\""),
                    SEMANTIC.replace(
                            "\"status\":\"COMPLETE\"",
                            "\"status\":\"COMPLETE\",\"sta\\u0074us\":\"COMPLETE\""),
                    SEMANTIC.replace(
                            "\"reason\":\"CANARY\"", "\"reason\":\"CANARY\",\"reason\":\"CANARY\""),
                    SEMANTIC.replace("\"met\":false", "\"met\":false,\"met\":true"),
                    SEMANTIC.replace("\"start\":0", "\"start\":0,\"start\":1")
                }) invalid(() -> decoder.complete(request(envelope(result))));
    }

    /** UTF-8 오류를 replacement로 수선하지 않고 NUL·고립 surrogate를 거절한다. */
    @Test
    void rejectsMalformedBytesAndUnicodeAtAllStringPositions() {
        for (byte[] bytes :
                new byte[][] {
                    {(byte) 0xc0, (byte) 0xaf}, {(byte) 0xe2, (byte) 0x82},
                    {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xff}
                }) invalid(() -> decoder.start(request(bytes)));
        for (String bad : new String[] {"\\u0000", "\\ud800", "\\udc00", "\\ud800x"}) {
            invalid(() -> decoder.complete(request(envelope(SEMANTIC.replace("CANARY", bad)))));
            invalid(() -> decoder.complete(request(envelope(ERROR, "\"" + bad + "\"", "null"))));
            invalid(() -> decoder.start(request("{\"" + bad + "\":1}")));
        }
        assertThat(
                        decoder.complete(
                                        request(
                                                envelope(
                                                        ERROR,
                                                        "\"" + "😀".repeat(160) + "\"",
                                                        "null")))
                                .observedProviderVersion()
                                .codePointCount(0, 320))
                .isEqualTo(160);
        invalid(
                () ->
                        decoder.complete(
                                request(envelope(ERROR, "\"" + "😀".repeat(161) + "\"", "null"))));
    }

    /** MIME·query·encoding은 body 읽기 전 원본 메타데이터로 검사한다. */
    @Test
    void rejectsMediaQueryAndEncodingVariants() {
        for (String type :
                new String[] {
                    "text/json",
                    "application/json;charset=ISO-8859-1",
                    "application/json;foo=bar",
                    "application/problem+json",
                    "not a media type"
                }) {
            var request = request("{\"leaseGen\":1}");
            request.removeHeader("Content-Type");
            request.addHeader("Content-Type", type);
            invalid(() -> decoder.start(request));
        }
        var missing = request("{\"leaseGen\":1}");
        missing.removeHeader("Content-Type");
        invalid(() -> decoder.start(missing));
        // MockHttpServletRequest는 Content-Type 추가를 덮어쓰므로 실제 두 occurrence를 별도로 제공한다.
        var duplicate =
                new MockHttpServletRequest() {
                    @Override
                    public java.util.Enumeration<String> getHeaders(String name) {
                        if ("Content-Type".equalsIgnoreCase(name))
                            return java.util.Collections.enumeration(
                                    java.util.List.of("application/json", "application/json"));
                        return super.getHeaders(name);
                    }
                };
        duplicate.setContent("{\"leaseGen\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(java.util.Collections.list(duplicate.getHeaders("Content-Type"))).hasSize(2);
        invalid(() -> decoder.start(duplicate));
        var query = request("{\"leaseGen\":1}");
        query.setQueryString("");
        invalid(() -> decoder.start(query));
        for (String encoding : new String[] {"identity", "gzip", ""}) {
            var request = request("{\"leaseGen\":1}");
            request.addHeader("Content-Encoding", encoding);
            invalid(() -> decoder.start(request));
        }
        var utf8 = request("{\"leaseGen\":1}");
        utf8.removeHeader("Content-Type");
        utf8.addHeader("Content-Type", "Application/JSON; charset=UTF-8");
        assertThat(decoder.start(utf8)).isEqualTo(1);
    }

    /** UUID v4 variant·소문자 canonical 경로만 허용한다. */
    @Test
    void pathHasNoAliases() {
        String canonical = "12345678-abcd-4321-8123-123456789abc";
        assertThat(decoder.jobKey(canonical)).isEqualTo(UUID.fromString(canonical));
        for (String value :
                new String[] {
                    null,
                    canonical.toUpperCase(),
                    " " + canonical,
                    canonical + " ",
                    canonical.replace("-4321-", "-1321-"),
                    canonical.replace("-8123-", "-7123-"),
                    "00000000-0000-0000-0000-000000000000",
                    "1-1-4-8-1",
                    canonical.replace("-", "")
                }) invalid(() -> decoder.jobKey(value));
    }

    /** 중첩 문법은 점수·success·잘못된 타입을 받지 않으며 의미 판정을 복제하지 않는다. */
    @Test
    void completionAndAttemptAreClosedAtEveryLevel() {
        for (String number : new String[] {"0", "4", "1.0", "1e0", "\"1\"", "2147483648"})
            invalid(() -> decoder.attempt(request(attempt(number, HASH))));
        for (String hash : new String[] {HASH.toUpperCase(), "a".repeat(63), "g".repeat(64)})
            invalid(() -> decoder.attempt(request(attempt("1", hash))));
        for (String result :
                new String[] {
                    "{}",
                    "null",
                    "[]",
                    ERROR.replace("ENGINE_TIMEOUT", "CANARY"),
                    ERROR.replace("ERROR", "SUCCESS"),
                    ERROR.replace("}", ",\"semantic\":null}"),
                    SEMANTIC.replace(
                            "\"kind\":\"COMPLETE\"", "\"kind\":\"COMPLETE\",\"score\":100"),
                    SEMANTIC.replace("\"formatNo\":1", "\"formatNo\":1.0"),
                    SEMANTIC.replace(
                            "\"status\":\"COMPLETE\"", "\"status\":\"COMPLETE\",\"success\":true"),
                    SEMANTIC.replace(
                            "\"rubricCode\":\"ONE\"", "\"rubricCode\":\"ONE\",\"score\":1"),
                    SEMANTIC.replace("\"met\":false", "\"met\":\"false\""),
                    SEMANTIC.replace("\"code\":\"C\"", "\"code\":\"C\",\"score\":1"),
                    SEMANTIC.replace("\"field\":\"method\"", "\"field\":\"culprit\""),
                    SEMANTIC.replace("\"start\":0", "\"start\":2147483648"),
                    SEMANTIC.replace("\"end\":1", "\"end\":0"),
                    SEMANTIC.replace("\"end\":1", "\"end\":1e0"),
                    SEMANTIC.replace("\"end\":1", "\"end\":1,\"quote\":\"CANARY\"")
                }) invalid(() -> decoder.complete(request(envelope(result))));
        for (String body :
                new String[] {
                    envelope(ERROR).replace("\"observedProviderVersion\":null,", ""),
                    envelope(ERROR)
                            .replace("\"providerResponseRef\":null", "\"providerResponseRef\":1"),
                    envelope(ERROR, "null", "\"short\""),
                    envelope(ERROR, "null", "\"bad ref!\""),
                    envelope(ERROR)
                            .replace("\"leaseGen\":1", "\"leaseGen\":1,\"requestId\":\"CANARY\"")
                }) invalid(() -> decoder.complete(request(body)));
        for (String code :
                new String[] {
                    "ENGINE_TIMEOUT",
                    "ENGINE_UNAVAILABLE",
                    "INVALID_OUTPUT",
                    "UNRESOLVED_REASONING",
                    "WORKER_LOST"
                })
            assertThat(
                            decoder.complete(
                                            request(
                                                    envelope(
                                                            ERROR.replace("ENGINE_TIMEOUT", code))))
                                    .resultJson())
                    .contains(code);
    }

    /** 원인·stack·입력을 공개하지 않는 Failure를 모든 거절에서 공통 검사한다. */
    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        GradeWorkerRequestDecoder.Failure.class,
                        error -> {
                            assertThat(error.oversized()).isFalse();
                            assertThat(error.getMessage()).isEqualTo("INVALID_REMOTE_REQUEST");
                            assertThat(error.getCause()).isNull();
                            assertThat(error.getStackTrace()).isEmpty();
                            assertThat(error.toString()).doesNotContain("CANARY");
                        });
    }

    private static String attempt(String number, String hash) {
        return "{\"leaseGen\":1,\"attemptNo\":"
                + number
                + ",\"originalAttemptHash\":\""
                + hash
                + "\"}";
    }

    private static String envelope(String result) {
        return envelope(result, "null", "null");
    }

    private static String envelope(String result, String observed, String reference) {
        return "{\"leaseGen\":1,\"attemptNo\":1,\"observedProviderVersion\":"
                + observed
                + ",\"providerResponseRef\":"
                + reference
                + ",\"result\":"
                + result
                + "}";
    }

    private static MockHttpServletRequest request(String body) {
        return request(body.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest request(byte[] bytes) {
        var request = new MockHttpServletRequest();
        request.addHeader("Content-Type", "application/json");
        request.setContent(bytes);
        return request;
    }

    /** Content-Length를 제공하지 않는 Servlet 요청 원본 stream fixture다. */
    private static MockHttpServletRequest unknownLength(String body) {
        var request =
                new MockHttpServletRequest() {
                    @Override
                    public int getContentLength() {
                        return -1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        request.addHeader("Content-Type", "application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
