package com.reasoning.common.grading.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.QualifiedExecution;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.RuntimeConfiguration;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.engine.LocalSemanticEngine.JobDeadline;
import com.reasoning.common.grading.engine.LocalSemanticEngine.Settings;
import com.reasoning.common.grading.model.FrozenModelProjection.SemanticInput;
import com.reasoning.common.grading.model.GradeDictionary;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 보호된 전체 설정·사전으로 실제 설치를 조립한다. SQL·제공자 호출·인증·등록·활성화는 수행하지 않는다. */
public final class WorkerGradeProfileLoader {
    private static final ObjectMapper MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(16)
                                                    .maxStringLength(131072)
                                                    .maxNumberLength(128)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    /** 전체 성공한 실제 설치만 소유하며 원문·설정 또는 실행 권위를 노출하지 않는다. */
    public static final class Profiles {
        private final Map<String, InstalledRuntimeManifestVerifier> profiles;

        private Profiles(Map<String, InstalledRuntimeManifestVerifier> profiles) {
            this.profiles = Map.copyOf(profiles);
        }

        /**
         * 실제 설정 대조 후 원래 START와 private profile 시간으로 decode 전 P를 고정한다.
         *
         * @param runtime epoch 없는 정확한 아홉 설정 문자열, null 불가
         * @param requestStartedNano 원래 START dispatch 직전 같은 프로세스 monotonic 값
         * @param remainingBudgetMillis 원래 응답의 1~120000ms
         * @param remainingLeaseMillis 원래 응답의 1~30000ms
         * @return 해당 실제 engine에만 결속된 live J/P/L
         * @throws IllegalStateException 누락·불일치의 WORKER_PROFILE_MISMATCH
         */
        public JobDeadline openDeadline(
                RuntimeConfiguration runtime,
                long requestStartedNano,
                long remainingBudgetMillis,
                long remainingLeaseMillis) {
            return find(runtime)
                    .openDeadline(
                            runtime,
                            requestStartedNano,
                            remainingBudgetMillis,
                            remainingLeaseMillis);
        }

        /**
         * 실제 불변 의미 입력을 같은 설치에 결속하며 시간이나 권위를 새로 만들지 않는다.
         *
         * @param runtime 정확한 아홉 설정 문자열, null 불가
         * @param input 실제 canonical decoder의 입력, null 불가
         * @return private 생성한 실제 engine·사전·입력 소유 실행값
         * @throws IllegalStateException 누락·불일치의 WORKER_PROFILE_MISMATCH
         */
        public QualifiedExecution bind(RuntimeConfiguration runtime, SemanticInput input) {
            return find(runtime).bindExecution(runtime, input);
        }

        private InstalledRuntimeManifestVerifier find(RuntimeConfiguration runtime) {
            if (runtime == null || !profiles.containsKey(runtime.code())) throw mismatch();
            return profiles.get(runtime.code());
        }

        @Override
        public String toString() {
            return "Profiles[redacted]";
        }
    }

    /**
     * 실제 보호 byte-only reader를 통해 전체 설치를 원자적으로 조립한다. 호스트 불변성·검사/열기 TOCTOU 한계는 reader와 같다.
     *
     * @param configuredPath trim하지 않는 절대 보호 경로, null·blank는 I/O 전에 실패
     * @return 모든 행·hash·실제 origin 검사가 성공한 불변 소유 map
     * @throws IllegalStateException 원인·suppressed 없는 INVALID_GRADE_WORKER_PROFILES
     */
    public Profiles load(String configuredPath) {
        try {
            if (configuredPath == null || configuredPath.isBlank()) throw failure();
            return parse(GradeProtectedDocumentReader.read(configuredPath));
        } catch (java.io.IOException | RuntimeException | LinkageError exception) {
            throw failure();
        }
    }

    /**
     * 닫힌 원문을 명시 한도와 실제 생성자로 검사하여 부분 설치를 게시하지 않는다.
     *
     * @param bytes strict UTF-8 원문, null 불가, 최대16MiB
     * @return 실제 설정·전체 사전·engine·verifier의 불변 소유자
     * @throws IllegalStateException 모든 실패의 원인 없는 고정 오류
     */
    static Profiles parse(byte[] bytes) {
        try {
            if (bytes == null
                    || bytes.length == 0
                    || bytes.length > GradeProtectedDocumentReader.MAX_BYTES) throw failure();
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (json.charAt(0) == '\uFEFF') throw failure();
            try (var parser = MAPPER.createParser(json)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING)
                        unicode(parser.getText());
                    if (token.isNumeric() && parser.getTextLength() > 128) throw failure();
                }
            }
            JsonNode root = MAPPER.readTree(json);
            keys(root, "formatNo", "profiles");
            if (integer(root.get("formatNo")) != 1) throw failure();
            JsonNode rows = root.get("profiles");
            if (!rows.isArray() || rows.isEmpty()) throw failure();
            Map<String, InstalledRuntimeManifestVerifier> assembled = new LinkedHashMap<>();
            for (JsonNode row : rows) {
                keys(row, "profile", "settings", "dictionaryCode", "dictionaryTerms");
                JsonNode p = row.get("profile");
                keys(p, "configId", "engineId", "engineVersion", "policyCode");
                var profile =
                        new InstalledProfile(
                                text(p, "configId"),
                                text(p, "engineId"),
                                text(p, "engineVersion"),
                                text(p, "policyCode"));
                if (assembled.containsKey(profile.configId())) throw failure();
                JsonNode s = row.get("settings");
                keys(
                        s,
                        "endpoint",
                        "model",
                        "modelDigest",
                        "dictionaryHash",
                        "modelTemplate",
                        "numCtx",
                        "numPredict",
                        "temperature",
                        "seed",
                        "thinking",
                        "executionTimeoutMillis");
                JsonNode temperature = s.get("temperature");
                if (!temperature.isNumber()
                        || temperature.decimalValue().signum() < 0
                        || temperature.decimalValue().compareTo(java.math.BigDecimal.valueOf(2)) > 0
                        || !s.get("thinking").isBoolean()) throw failure();
                int timeout = integer(s.get("executionTimeoutMillis"));
                if (timeout < 1 || timeout > 120000) throw failure();
                var settings =
                        new Settings(
                                URI.create(text(s, "endpoint")),
                                text(s, "model"),
                                text(s, "modelDigest"),
                                text(s, "dictionaryHash"),
                                text(s, "modelTemplate"),
                                integer(s.get("numCtx")),
                                integer(s.get("numPredict")),
                                temperature.doubleValue(),
                                integer(s.get("seed")),
                                s.get("thinking").booleanValue(),
                                Duration.ofMillis(timeout));
                JsonNode terms = row.get("dictionaryTerms");
                if (!terms.isArray()) throw failure();
                var allTerms = new ArrayList<GradeDictionary.Term>();
                for (JsonNode term : terms) {
                    keys(term, "conceptCode", "canonical", "alias");
                    allTerms.add(
                            new GradeDictionary.Term(
                                    text(term, "conceptCode"),
                                    text(term, "canonical"),
                                    text(term, "alias")));
                }
                var dictionary = new GradeDictionary(text(row, "dictionaryCode"), allTerms);
                if (!dictionary.sha256().equals(settings.dictionaryHash())) throw failure();
                assembled.put(
                        profile.configId(),
                        new InstalledRuntimeManifestVerifier(
                                new LocalSemanticEngine(settings), dictionary, profile));
            }
            return new Profiles(assembled);
        } catch (java.io.IOException | RuntimeException | LinkageError exception) {
            throw failure();
        }
    }

    /** decoded 문자열의 UTF-16 한도·NUL·surrogate를 변환 없이 검사한다. */
    private static void unicode(String value) {
        if (value.length() > 131072) throw failure();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw failure();
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw failure();
            }
        }
    }

    /** 필수 non-null 키의 정확한 집합을 검사한다. */
    private static void keys(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) throw failure();
        for (String name : names) if (!node.hasNonNull(name)) throw failure();
    }

    /** 문자열 타입만 허용하며 trim·기본값은 없다. */
    private static String text(JsonNode node, String key) {
        if (!node.get(key).isTextual()) throw failure();
        return node.get(key).textValue();
    }

    /** 정확한 수학 정수만 int로 변환하며 1.0/1e0은 허용한다. */
    private static int integer(JsonNode value) {
        if (!value.isNumber()) throw failure();
        return value.decimalValue().intValueExact();
    }

    private static IllegalStateException mismatch() {
        return new IllegalStateException("WORKER_PROFILE_MISMATCH");
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("INVALID_GRADE_WORKER_PROFILES");
    }
}
