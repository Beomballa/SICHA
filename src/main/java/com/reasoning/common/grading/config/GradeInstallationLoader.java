package com.reasoning.common.grading.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.engine.LocalSemanticEngine.Settings;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeDictionaryRepository;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 보호된 배포 문서만 읽어 실제 설치를 조립하며 실행 인가·등록·제공자 호출을 하지 않는다. */
public final class GradeInstallationLoader {
    private static final String INVALID = "INVALID_GRADE_INSTALLATION";

    /** 프로필·사전 코드·실행 설정을 보존하는 내부 불변 입력이며 원문 출력은 금지한다. */
    record Installation(InstalledProfile profile, String dictionaryCode, Settings settings) {
        @Override
        public String toString() {
            return "Installation[redacted]";
        }
    }

    /**
     * 전체 입력과 고정 사전을 검증한 뒤 실제 설치 전체를 한 번에 반환한다.
     *
     * @param configuredPath null·공백이면 비활성, 그 외에는 보호된 절대 경로
     * @param dictionaries 활성 사전을 고정 해시로 읽는 저장소; 비활성에서는 사용하지 않는다
     * @return configId별 불변 실제 verifier이며 부분 결과는 게시하지 않는다
     * @throws IllegalStateException 파일·파싱·DB·설치 오류 또는 활성 SQL 트랜잭션인 경우
     */
    public Map<String, InstalledRuntimeManifestVerifier> load(
            String configuredPath, GradeDictionaryRepository dictionaries) {
        if (configuredPath == null || configuredPath.isBlank()) return Map.of();
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure();
            List<Installation> inputs = read(configuredPath);
            Map<String, InstalledRuntimeManifestVerifier> assembled = new LinkedHashMap<>();
            for (Installation input : inputs) {
                var dictionary =
                        dictionaries.load(
                                input.dictionaryCode(), input.settings().dictionaryHash());
                var engine = new LocalSemanticEngine(input.settings());
                assembled.put(
                        input.profile().configId(),
                        new InstalledRuntimeManifestVerifier(engine, dictionary, input.profile()));
            }
            return Map.copyOf(assembled);
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    /**
     * 공통 보호 리더의 원문 바이트를 실제 설치 입력으로 파싱한다.
     *
     * @param configuredPath 보호 파일의 절대 경로이며 비활성 분기는 load에서 처리한다
     * @return 전체 검증을 통과한 불변 설치 입력 목록
     * @throws IllegalStateException 경계·파일·파싱 실패를 원인 없는 고정 설치 오류로 변환한다
     */
    List<Installation> read(String configuredPath) {
        try {
            return parse(GradeProtectedDocumentReader.read(configuredPath));
        } catch (IOException | RuntimeException | LinkageError exception) {
            throw failure();
        }
    }

    /**
     * 닫힌 JSON을 정확한 수와 실제 생성자로 검사하며 템플릿의 CR/LF를 보존한다.
     *
     * @param bytes 공통 보호 문서 상한 이내의 UTF-8 원문이며 null은 거절한다
     * @return 전체 입력이 유효할 때만 반환하는 불변 설치 목록
     * @throws IllegalStateException 크기·JSON·실제 설치 값이 잘못된 경우 원인 없는 고정 오류
     */
    static List<Installation> parse(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > GradeProtectedDocumentReader.MAX_BYTES)
                throw failure();
            JsonNode root = SnapshotJson.parse(bytes);
            closed(root, Set.of("formatNo", "runtimes"));
            if (integer(root.get("formatNo")) != 1) throw failure();
            JsonNode runtimes = root.get("runtimes");
            if (!runtimes.isArray() || runtimes.isEmpty()) throw failure();
            var inputs = new ArrayList<Installation>();
            var ids = new HashSet<String>();
            for (JsonNode runtime : runtimes) {
                closed(runtime, Set.of("profile", "dictionaryCode", "settings"));
                JsonNode profile = runtime.get("profile");
                closed(profile, Set.of("configId", "engineId", "engineVersion", "policyCode"));
                var installed =
                        new InstalledProfile(
                                text(profile, "configId"),
                                text(profile, "engineId"),
                                text(profile, "engineVersion"),
                                text(profile, "policyCode"));
                if (!ids.add(installed.configId())) throw failure();
                String dictionaryCode = text(runtime, "dictionaryCode");
                if (!dictionaryCode.matches("[A-Z0-9_]{1,80}")) throw failure();
                JsonNode settings = runtime.get("settings");
                closed(
                        settings,
                        Set.of(
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
                                "executionTimeoutMillis"));
                JsonNode temperature = settings.get("temperature");
                if (!temperature.isNumber()
                        || temperature.decimalValue().signum() < 0
                        || temperature.decimalValue().compareTo(java.math.BigDecimal.valueOf(2)) > 0
                        || !settings.get("thinking").isBoolean()) throw failure();
                int timeout = integer(settings.get("executionTimeoutMillis"));
                if (timeout < 1 || timeout > 120000) throw failure();
                var actual =
                        new Settings(
                                URI.create(text(settings, "endpoint")),
                                text(settings, "model"),
                                text(settings, "modelDigest"),
                                text(settings, "dictionaryHash"),
                                text(settings, "modelTemplate"),
                                integer(settings.get("numCtx")),
                                integer(settings.get("numPredict")),
                                temperature.doubleValue(),
                                integer(settings.get("seed")),
                                settings.get("thinking").booleanValue(),
                                Duration.ofMillis(timeout));
                inputs.add(new Installation(installed, dictionaryCode, actual));
            }
            return List.copyOf(inputs);
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    /** 필수 비null 키만 허용하고 미등록 키·누락을 거절한다. */
    private static void closed(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || node.size() != keys.size()) throw failure();
        for (String key : keys) if (!node.hasNonNull(key)) throw failure();
    }

    /** 문자열 값만 반환하고 암묵 변환을 거절한다. */
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (!value.isTextual()) throw failure();
        return value.textValue();
    }

    /** 정확한 정수값만 int 범위로 변환하며 소수 표기 정수는 허용한다. */
    private static int integer(JsonNode value) {
        if (!value.isNumber()) throw failure();
        return value.decimalValue().intValueExact();
    }

    /** 경로·구성·원인 예외를 포함하지 않는 동일한 안전 오류를 발행한다. */
    private static IllegalStateException failure() {
        return new IllegalStateException(INVALID);
    }
}
