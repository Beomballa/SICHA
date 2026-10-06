package com.reasoning.common.grading.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.RuntimeConfiguration;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeDictionary.Term;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

/** 실제 보호 문서·현재 설치 full19·전체 사전을 검사하며 SQL·제공자·빈 활성화는 없다. */
class WorkerGradeProfileLoaderTest {
    @TempDir Path temporary;

    private static final String TEMPLATE = "  𐐀{{ .System }}\r\n{{ .Prompt }}\n{{ .Response }}  ";
    private static final InstalledProfile PROFILE =
            new InstalledProfile("OWNED_TEST", "LOCAL", "1", "RULE_20260924");

    /** 실제 설치가 계산한 full19와 보호 문서의 아홉 좌표가 일치해야만 live 예산을 연다. */
    @Test
    void protectedFileQualifiesActualFullManifestAndRedacts() throws Exception {
        var dictionary = dictionary();
        var actual = installation(dictionary, TEMPLATE);
        var document = document(dictionary, TEMPLATE);
        Path file = protectedFile(document);
        var loaded = new WorkerGradeProfileLoader().load(file.toString());
        assertThat(actual.registrationManifest().size()).isEqualTo(19);
        assertThat(actual.registrationManifest().path("engineCodeHash").asText())
                .isEqualTo(actual.engineCodeHash());
        assertThat(actual.registrationManifest().path("dictionaryHash").asText())
                .isEqualTo(dictionary.sha256());
        assertThat(actual.registrationManifest().path("configId").asText())
                .isEqualTo(PROFILE.configId());
        assertThat(actual.configHash()).isEqualTo(SnapshotJson.hash(actual.registrationManifest()));
        var deadline = loaded.openDeadline(runtime(actual), System.nanoTime(), 120000, 30000);
        try {
            deadline.requireLive();
            assertThat(deadline.liveWait()).isLessThanOrEqualTo(Duration.ofSeconds(30));
            assertThat(loaded.toString()).isEqualTo("Profiles[redacted]");
            assertThat(runtime(actual).toString()).doesNotContain(TEMPLATE, actual.configHash());
        } finally {
            deadline.stopAlarm();
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> new WorkerGradeProfileLoader().load(file.toString()))
                .hasMessage("INVALID_GRADE_WORKER_PROFILES")
                .hasNoCause();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Path alias = file.getParent().resolve("alias.json");
        Files.createSymbolicLink(alias, file);
        assertThatThrownBy(() -> new WorkerGradeProfileLoader().load(alias.toString()))
                .hasMessage("INVALID_GRADE_WORKER_PROFILES")
                .hasNoCause();
    }

    /** 각 좌표의 단독 변이는 실제 computed manifest와 비교하여 전부 거절한다. */
    @Test
    void eachOfNineCoordinatesMustMatchActualInstallation() {
        var actual = installation(dictionary(), TEMPLATE);
        var profiles =
                WorkerGradeProfileLoader.parse(
                        SnapshotJson.encode(document(dictionary(), TEMPLATE)));
        var good = runtime(actual);
        String[] values = {
            good.code(),
            good.configHash(),
            good.engineVersion(),
            good.modelId(),
            good.modelVersion(),
            good.pinMode(),
            good.promptHash(),
            good.optionsHash(),
            good.reportContractVersion()
        };
        for (int index = 0; index < values.length; index++) {
            String[] changed = values.clone();
            changed[index] =
                    index == 1 || index == 4 || index == 6 || index == 7 ? "0".repeat(64) : "OTHER";
            var bad =
                    new RuntimeConfiguration(
                            changed[0],
                            changed[1],
                            changed[2],
                            changed[3],
                            changed[4],
                            changed[5],
                            changed[6],
                            changed[7],
                            changed[8]);
            assertThatThrownBy(() -> profiles.openDeadline(bad, System.nanoTime(), 120000, 30000))
                    .hasMessage("WORKER_PROFILE_MISMATCH")
                    .hasNoCause();
        }
    }

    /** 빈 사전도 실제 역사 형식 hash로 pin하며 exact 수학 정수 표기만 허용한다. */
    @Test
    void emptyDictionaryAndExactDecimalIntegersQualify() {
        var empty = new GradeDictionary("EMPTY", List.of());
        var actual = installation(empty, TEMPLATE);
        String raw =
                new String(SnapshotJson.encode(document(empty, TEMPLATE)), StandardCharsets.UTF_8);
        raw =
                raw.replace("\"formatNo\":1", "\"formatNo\":1e0")
                        .replace("\"seed\":1", "\"seed\":1.0")
                        .replace("\"numCtx\":65536", "\"numCtx\":65536.0")
                        .replace(
                                "\"executionTimeoutMillis\":30000",
                                "\"executionTimeoutMillis\":3e4");
        var profiles = WorkerGradeProfileLoader.parse(raw.getBytes(StandardCharsets.UTF_8));
        var deadline = profiles.openDeadline(runtime(actual), System.nanoTime(), 120000, 30000);
        deadline.stopAlarm();
        ObjectNode bad = document(empty, TEMPLATE);
        settings(bad).put("dictionaryHash", "0".repeat(64));
        rejected(SnapshotJson.encode(bad));
        settings(bad)
                .put("dictionaryHash", empty.sha256())
                .put("seed", new java.math.BigDecimal("1.0001"));
        rejected(SnapshotJson.encode(bad));
    }

    /** LF 표현 정규화·모호한 별칭은 실제 사전 의미를 보존하되 정규화 후 중복은 실패한다. */
    @Test
    void fullTermsNormalizeLfKeepAmbiguousAliasesAndHistoricalHash() {
        var dictionary = dictionary();
        assertThat(dictionary.candidates("공유\r\n표현")).hasSize(2);
        assertThat(dictionary.canonicalJson()).contains("공유\\n표현").doesNotContain("\\r");
        assertThat(dictionary.sha256())
                .isEqualTo(
                        CommonUtil.sha256(
                                dictionary.canonicalJson().getBytes(StandardCharsets.UTF_8)));
        ObjectNode valid = document(dictionary, TEMPLATE);
        var terms =
                (com.fasterxml.jackson.databind.node.ArrayNode) row(valid).get("dictionaryTerms");
        ((ObjectNode) terms.get(0)).put("alias", "공유\r\n표현");
        var loaded = WorkerGradeProfileLoader.parse(SnapshotJson.encode(valid));
        var deadline =
                loaded.openDeadline(
                        runtime(installation(dictionary, TEMPLATE)),
                        System.nanoTime(),
                        120000,
                        30000);
        deadline.stopAlarm();
        terms.addObject().put("conceptCode", "ONE").put("canonical", "첫 개념").put("alias", "공유\n표현");
        rejected(SnapshotJson.encode(valid));
    }

    /** 템플릿의 보충 문자·raw CR/LF는 pin에 포함되며 LF 치환은 다른 실제 설치다. */
    @Test
    void rawTemplateAndSupplementaryCodePointsArePinned() {
        var actual = installation(dictionary(), TEMPLATE);
        var loaded =
                WorkerGradeProfileLoader.parse(
                        SnapshotJson.encode(document(dictionary(), TEMPLATE)));
        var normalized = installation(dictionary(), TEMPLATE.replace("\r\n", "\n"));
        assertThat(actual.configHash()).isNotEqualTo(normalized.configHash());
        assertThatThrownBy(
                        () ->
                                loaded.openDeadline(
                                        runtime(normalized), System.nanoTime(), 120000, 30000))
                .hasMessage("WORKER_PROFILE_MISMATCH");
        ObjectNode maximum = document(dictionary(), "𐐀".repeat(65536));
        var maxProfiles = WorkerGradeProfileLoader.parse(SnapshotJson.encode(maximum));
        var maxDeadline =
                maxProfiles.openDeadline(
                        runtime(installation(dictionary(), "𐐀".repeat(65536))),
                        System.nanoTime(),
                        120000,
                        30000);
        maxDeadline.stopAlarm();
        settings(maximum).put("modelTemplate", "𐐀".repeat(65536) + "x");
        rejected(SnapshotJson.encode(maximum));
    }

    /** strict 문서 depth16·문자131072·수128·16MiB와 UTF-8·중복·후행·Unicode 경계를 검사한다. */
    @Test
    void explicitParserAndDocumentBoundsAreClosed() throws Exception {
        byte[] valid = SnapshotJson.encode(document(dictionary(), TEMPLATE));
        String raw = new String(valid, StandardCharsets.UTF_8);
        for (String bad :
                List.of(
                        "",
                        "null",
                        "[]",
                        "{}",
                        raw + " {}",
                        "\uFEFF" + raw,
                        raw.replace("\"formatNo\":1", "\"formatNo\":1,\"formatNo\":1"),
                        raw.replace("\"seed\":1", "\"seed\":" + "1".repeat(129)),
                        raw.replace(
                                "\"seed\":1", "\"seed\":" + "[".repeat(17) + "0" + "]".repeat(17)),
                        raw.replace("\"seed\":1", "\"seed\":\"" + "x".repeat(131073) + "\""),
                        raw.replace("\"LOCAL\"", "\"\\ud800\""),
                        raw.replace("\"LOCAL\"", "\"\\udc00\""),
                        raw.replace("\"LOCAL\"", "\"\\u0000\"")))
            rejected(bad.getBytes(StandardCharsets.UTF_8));
        byte[] malformed = raw.replace("LOCAL", "éOCAL").getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < malformed.length; index++) {
            if (malformed[index] == (byte) 0xc3) {
                malformed[index + 1] = 0x28;
                break;
            }
        }
        rejected(malformed);
        for (String encoding : List.of("UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"))
            rejected(raw.getBytes(java.nio.charset.Charset.forName(encoding)));
        byte[] exact = new byte[16 * 1024 * 1024];
        java.util.Arrays.fill(exact, (byte) ' ');
        System.arraycopy(valid, 0, exact, 0, valid.length);
        var parsed = WorkerGradeProfileLoader.parse(exact);
        var deadline =
                parsed.openDeadline(
                        runtime(installation(dictionary(), TEMPLATE)),
                        System.nanoTime(),
                        120000,
                        30000);
        deadline.stopAlarm();
        Path path = protectedFile(document(dictionary(), TEMPLATE));
        Files.write(path, exact);
        var loaded = new WorkerGradeProfileLoader().load(path.toString());
        var loadedDeadline =
                loaded.openDeadline(
                        runtime(installation(dictionary(), TEMPLATE)),
                        System.nanoTime(),
                        120000,
                        30000);
        loadedDeadline.stopAlarm();
        byte[] oversized = java.util.Arrays.copyOf(exact, exact.length + 1);
        oversized[exact.length] = ' ';
        rejected(oversized);
        Files.write(path, oversized);
        assertThatThrownBy(() -> new WorkerGradeProfileLoader().load(path.toString()))
                .hasMessage("INVALID_GRADE_WORKER_PROFILES")
                .hasNoCause();
    }

    /** 모든 필수 키·타입·최종 행 실패와 decimal 온도 범위를 all-or-nothing으로 검사한다. */
    @Test
    void closedGrammarFinalRowAndDecimalTemperatureRejectAtomically() {
        ObjectNode valid = document(dictionary(), TEMPLATE);
        for (String field :
                List.of(
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
                        "executionTimeoutMillis")) {
            ObjectNode bad = valid.deepCopy();
            settings(bad).remove(field);
            rejected(SnapshotJson.encode(bad));
            bad = valid.deepCopy();
            settings(bad).putNull(field);
            rejected(SnapshotJson.encode(bad));
            bad = valid.deepCopy();
            if (List.of("endpoint", "model", "modelDigest", "dictionaryHash", "modelTemplate")
                    .contains(field)) {
                settings(bad).put(field, 1);
            } else {
                settings(bad).put(field, "1");
            }
            rejected(SnapshotJson.encode(bad));
        }
        for (String value :
                List.of(
                        "-0.00000000000000000000000000000001",
                        "2.00000000000000000000000000000001")) {
            ObjectNode bad = valid.deepCopy();
            settings(bad).put("temperature", new java.math.BigDecimal(value));
            rejected(SnapshotJson.encode(bad));
        }
        for (String endpoint :
                List.of(
                        "http://example.com:11434",
                        "https://127.0.0.1:1",
                        "http://127.0.0.1:1/private")) {
            ObjectNode bad = valid.deepCopy();
            settings(bad).put("endpoint", endpoint);
            rejected(SnapshotJson.encode(bad));
        }
        ObjectNode finalRow = valid.deepCopy();
        var rows = (com.fasterxml.jackson.databind.node.ArrayNode) finalRow.get("profiles");
        ObjectNode last = row(valid).deepCopy();
        ((ObjectNode) last.get("profile")).put("configId", "FINAL");
        ((ObjectNode) last.get("settings")).put("dictionaryHash", "0".repeat(64));
        rows.add(last);
        rejected(SnapshotJson.encode(finalRow));
        rows.remove(1);
        rows.add(row(valid).deepCopy());
        rejected(SnapshotJson.encode(finalRow));
        for (String field : List.of("profile", "settings", "dictionaryCode", "dictionaryTerms")) {
            ObjectNode bad = valid.deepCopy();
            row(bad).remove(field);
            rejected(SnapshotJson.encode(bad));
        }
        ObjectNode unknown = valid.deepCopy();
        row(unknown)
                .put(
                        "expectedManifest",
                        installation(dictionary(), TEMPLATE).registrationManifest().toString());
        rejected(SnapshotJson.encode(unknown));
        for (String path : new String[] {null, "", " \t\r\n"})
            assertThatThrownBy(() -> new WorkerGradeProfileLoader().load(path))
                    .hasMessage("INVALID_GRADE_WORKER_PROFILES")
                    .hasNoCause();
    }

    /** numeric128의 정확 경계는 수학적 정수로 허용하고129는 문법 검사 전에 거절한다. */
    @Test
    void exactNumericTokenBoundaryIsNotDoubleRounded() {
        String raw =
                new String(
                        SnapshotJson.encode(document(dictionary(), TEMPLATE)),
                        StandardCharsets.UTF_8);
        String number128 = "1." + "0".repeat(126);
        var profiles =
                WorkerGradeProfileLoader.parse(
                        raw.replace("\"seed\":1", "\"seed\":" + number128)
                                .getBytes(StandardCharsets.UTF_8));
        var deadline =
                profiles.openDeadline(
                        runtime(installation(dictionary(), TEMPLATE)),
                        System.nanoTime(),
                        120000,
                        30000);
        deadline.stopAlarm();
        rejected(
                raw.replace("\"seed\":1", "\"seed\":" + number128 + "0")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** 전체 terms와 profile의 닫힌 키·타입·마지막 행도 부분 성공으로 수선하지 않는다. */
    @Test
    void profileAndTermRowsCannotHideUnknownOrInvalidFinalValues() {
        ObjectNode valid = document(dictionary(), TEMPLATE);
        for (String field : List.of("configId", "engineId", "engineVersion", "policyCode")) {
            ObjectNode bad = valid.deepCopy();
            ((ObjectNode) row(bad).get("profile")).remove(field);
            rejected(SnapshotJson.encode(bad));
            bad = valid.deepCopy();
            ((ObjectNode) row(bad).get("profile")).put(field, 1);
            rejected(SnapshotJson.encode(bad));
        }
        for (String field : List.of("conceptCode", "canonical", "alias")) {
            ObjectNode bad = valid.deepCopy();
            ((ObjectNode) row(bad).path("dictionaryTerms").get(1)).remove(field);
            rejected(SnapshotJson.encode(bad));
            bad = valid.deepCopy();
            ((ObjectNode) row(bad).path("dictionaryTerms").get(1)).put(field, " ");
            rejected(SnapshotJson.encode(bad));
        }
        ObjectNode unknown = valid.deepCopy();
        ((ObjectNode) row(unknown).path("dictionaryTerms").get(1)).put("enabled", true);
        rejected(SnapshotJson.encode(unknown));
        ObjectNode wrongCode = valid.deepCopy();
        row(wrongCode).put("dictionaryCode", "OTHER");
        rejected(SnapshotJson.encode(wrongCode));
        ObjectNode missing = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) row(missing).get("dictionaryTerms"))
                .remove(1);
        rejected(SnapshotJson.encode(missing));
    }

    /** 전체 행은 모호한 별칭도 유지하며 서버와 동일한 실제 역사 hash를 계산한다. */
    private static GradeDictionary dictionary() {
        return new GradeDictionary(
                "OWNED_DICT",
                List.of(new Term("ONE", "첫 개념", "공유\r\n표현"), new Term("TWO", "둘째 개념", "공유\n표현")));
    }

    /**
     * SQL·네트워크 없이 실제 현재 CodeSource와 Settings를 결속한다.
     *
     * @param dictionary 전체 사전
     * @param template 원문 템플릿
     * @return 실제 computed full19 설치
     */
    private static InstalledRuntimeManifestVerifier installation(
            GradeDictionary dictionary, String template) {
        return new InstalledRuntimeManifestVerifier(
                new LocalSemanticEngine(
                        new LocalSemanticEngine.Settings(
                                URI.create("http://127.0.0.1:1"),
                                "qwen3:8b",
                                "a".repeat(64),
                                dictionary.sha256(),
                                template,
                                65536,
                                4096,
                                0,
                                1,
                                false,
                                Duration.ofSeconds(30))),
                dictionary,
                PROFILE);
    }

    /**
     * 실제 computed manifest에서만 epoch 없는 아홉 좌표를 읽는다.
     *
     * @param actual 실제 설치
     * @return 인가가 아닌 typed 대조값
     */
    private static RuntimeConfiguration runtime(InstalledRuntimeManifestVerifier actual) {
        var node = actual.registrationManifest();
        return new RuntimeConfiguration(
                node.path("configId").asText(),
                actual.configHash(),
                node.path("engineVersion").asText(),
                node.path("modelId").asText(),
                node.path("modelVersion").asText(),
                node.path("pinMode").asText(),
                node.path("promptHash").asText(),
                node.path("optionsHash").asText(),
                node.path("reportContractVersion").asText());
    }

    /**
     * 운영 Loader의 정확한 닫힌 grammar를 생성한다.
     *
     * @param dictionary 전체 사전
     * @param template 원문 템플릿
     * @return 원문 설정과 전체 terms뿐인 문서
     */
    private static ObjectNode document(GradeDictionary dictionary, String template) {
        var root =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("formatNo", 1);
        var row = root.putArray("profiles").addObject();
        row.putObject("profile")
                .put("configId", PROFILE.configId())
                .put("engineId", PROFILE.engineId())
                .put("engineVersion", PROFILE.engineVersion())
                .put("policyCode", PROFILE.policyCode());
        row.putObject("settings")
                .put("endpoint", "http://127.0.0.1:1/")
                .put("model", "qwen3:8b")
                .put("modelDigest", "a".repeat(64))
                .put("dictionaryHash", dictionary.sha256())
                .put("modelTemplate", template)
                .put("numCtx", 65536)
                .put("numPredict", 4096)
                .put("temperature", 0)
                .put("seed", 1)
                .put("thinking", false)
                .put("executionTimeoutMillis", 30000);
        row.put("dictionaryCode", dictionary.dictionaryCode());
        var terms = row.putArray("dictionaryTerms");
        for (var term : dictionary.terms())
            terms.addObject()
                    .put("conceptCode", term.conceptCode())
                    .put("canonical", term.canonical())
                    .put("alias", term.alias());
        return root;
    }

    private static ObjectNode row(ObjectNode root) {
        return (ObjectNode) root.path("profiles").get(0);
    }

    private static ObjectNode settings(ObjectNode root) {
        return (ObjectNode) row(root).get("settings");
    }

    /**
     * 외부 canonical 0700/0600 경로만 실제 reader에 전달한다.
     *
     * @param document 완전한 profile 문서
     * @return 실제 보호 파일
     * @throws Exception 파일 생성·권한·쓰기 실패
     */
    private Path protectedFile(ObjectNode document) throws Exception {
        Path root =
                Files.createTempDirectory(
                                temporary,
                                "owned-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        Path file =
                Files.createFile(
                        root.resolve("profiles.json"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Files.write(file, SnapshotJson.encode(document));
        return file;
    }

    /**
     * 오류는 경로·원문·원인·부분 설치를 노출하지 않는다.
     *
     * @param bytes 공격 원문 또는 경계 값
     */
    private static void rejected(byte[] bytes) {
        assertThatThrownBy(() -> WorkerGradeProfileLoader.parse(bytes))
                .hasMessage("INVALID_GRADE_WORKER_PROFILES")
                .hasNoCause()
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
    }
}
