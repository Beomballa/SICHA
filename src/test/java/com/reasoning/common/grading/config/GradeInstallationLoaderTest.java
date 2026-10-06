package com.reasoning.common.grading.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeDictionaryRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** 실제 보호 파일과 엄격 파싱을 검사하며 DB·제공자 호출은 하지 않는다. */
class GradeInstallationLoaderTest {
    @TempDir Path temporary;

    /** 비활성은 잘못된 경로·사전 저장소조차 접근하지 않는다. */
    @Test
    void disabledDoesNotReadOrQuery() {
        var jdbc = mock(JdbcTemplate.class);
        var loader = new GradeInstallationLoader();
        for (String path : new String[] {null, "", " \t\r\n"}) {
            assertThat(loader.load(path, null)).isEmpty();
            assertThat(new GradeInstallationConfig().gradeInstallations(path, jdbc)).isEmpty();
        }
        verifyNoInteractions(jdbc);
    }

    /** 정확한 정수 표기와 명시 옵션·템플릿 원문을 보존한다. */
    @Test
    void preservesTemplateAndExplicitOptions() {
        ObjectNode document = document();
        ObjectNode settings = settings(document);
        settings.put("modelTemplate", "  original\r\nline\n한글  ");
        settings.put("numCtx", new java.math.BigDecimal("8192.0"));
        settings.put("executionTimeoutMillis", new java.math.BigDecimal("1.2e5"));
        var input = GradeInstallationLoader.parse(SnapshotJson.encode(document)).getFirst();
        assertThat(input.settings().modelTemplate()).isEqualTo("  original\r\nline\n한글  ");
        assertThat(input.settings().numCtx()).isEqualTo(8192);
        assertThat(input.settings().executionTimeout().toMillis()).isEqualTo(120000);
        assertThat(input.settings().thinking()).isFalse();
        assertThat(input.toString()).doesNotContain("original", "PRIVATE", "a".repeat(64));
        assertThatThrownBy(
                        () -> GradeInstallationLoader.parse(SnapshotJson.encode(document)).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 유효 입력을 먼저 확인하고 UTF8·UTF16/32·Unicode·구조·수·보호 문서 상한을 거절한다.
     *
     * @throws Exception 시험 파일 생성·권한 설정·쓰기 실패인 경우
     */
    @Test
    void strictJsonAndReadLimits() throws Exception {
        byte[] valid = SnapshotJson.encode(document());
        String validJson = new String(valid, StandardCharsets.UTF_8);
        assertThat(GradeInstallationLoader.parse(valid)).hasSize(1);
        for (String invalid :
                List.of(
                        "",
                        "null",
                        "[]",
                        "{}",
                        "{\"formatNo\":1,\"runtimes\":[]}",
                        validJson.replace("\"formatNo\":1", "\"formatNo\":1,\"formatNo\":1"),
                        validJson + " {}",
                        "{",
                        validJson.replace("\"template\"", "\"\\ud800\""))) {
            rejected(invalid.getBytes(StandardCharsets.UTF_8));
        }
        byte[] invalidUtf8 = valid.clone();
        int templateOffset = validJson.indexOf("template");
        assertThat(templateOffset).isPositive();
        invalidUtf8[templateOffset] = (byte) 0xc3;
        invalidUtf8[templateOffset + 1] = 0x28;
        rejected(invalidUtf8);
        for (String encoding : List.of("UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            rejected(validJson.getBytes(java.nio.charset.Charset.forName(encoding)));
        }

        // 닫힌 설치 문법도 깊은 값·초과 수를 거절하므로 parser 자체의 거절을 별도로 확인한다.
        for (String invalid :
                List.of(
                        validJson.replace(
                                "\"template\"", "[".repeat(1100) + "0" + "]".repeat(1100)),
                        validJson.replace(
                                "\"numCtx\":8192",
                                "\"numCtx\":"
                                        + "1".repeat(SnapshotJson.numberLengthLimit() + 1)))) {
            byte[] bytes = invalid.getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> SnapshotJson.parse(bytes))
                    .hasMessage("INVALID_SNAPSHOT_JSON")
                    .hasNoCause();
            rejected(bytes);
        }
        rejected(new byte[GradeProtectedDocumentReader.MAX_BYTES + 1]);
        byte[] exact = new byte[GradeProtectedDocumentReader.MAX_BYTES];
        java.util.Arrays.fill(exact, (byte) ' ');
        System.arraycopy(valid, 0, exact, 0, valid.length);
        assertThat(GradeInstallationLoader.parse(exact)).hasSize(1);
        Path file = protectedFile("oversize");
        Files.write(file, exact);
        assertThat(GradeProtectedDocumentReader.read(file.toString())).isEqualTo(exact);
        assertThat(new GradeInstallationLoader().read(file.toString())).hasSize(1);

        byte[] oversized = java.util.Arrays.copyOf(exact, exact.length + 1);
        oversized[exact.length] = ' ';
        rejected(oversized);
        Files.write(file, oversized);
        assertThatThrownBy(() -> GradeProtectedDocumentReader.read(file.toString()))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                .hasNoCause();
        assertThatThrownBy(() -> new GradeInstallationLoader().read(file.toString()))
                .hasMessage("INVALID_GRADE_INSTALLATION")
                .hasNoCause();
    }

    /** 유효 입력 확인 뒤 닫힌 필수 구조와 실제 생성자의 범위·문법을 단독 변이로 검사한다. */
    @Test
    void rejectsMissingUnknownCoercedAndOutOfRangeValues() {
        ObjectNode valid = document();
        assertThat(GradeInstallationLoader.parse(SnapshotJson.encode(valid))).hasSize(1);

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
            ObjectNode missing = valid.deepCopy();
            settings(missing).remove(field);
            rejected(SnapshotJson.encode(missing));
            ObjectNode nil = valid.deepCopy();
            settings(nil).putNull(field);
            rejected(SnapshotJson.encode(nil));
        }
        for (String field :
                List.of(
                        "numCtx",
                        "numPredict",
                        "temperature",
                        "seed",
                        "thinking",
                        "executionTimeoutMillis")) {
            ObjectNode coerced = valid.deepCopy();
            settings(coerced).put(field, "1");
            rejected(SnapshotJson.encode(coerced));
        }
        for (String value : List.of("0", "120001", "1.1", "2147483648", "1e100")) {
            ObjectNode bad = valid.deepCopy();
            settings(bad).put("executionTimeoutMillis", new java.math.BigDecimal(value));
            rejected(SnapshotJson.encode(bad));
        }
        for (String value : List.of("-0.00001", "2.00000000000000000001")) {
            ObjectNode bad = valid.deepCopy();
            settings(bad).put("temperature", new java.math.BigDecimal(value));
            rejected(SnapshotJson.encode(bad));
        }
        for (ObjectNode object :
                List.of(
                        valid,
                        runtime(valid),
                        (ObjectNode) runtime(valid).get("profile"),
                        settings(valid))) {
            object.put("unexpected", "secret");
            rejected(SnapshotJson.encode(valid));
            object.remove("unexpected");
        }
        ObjectNode duplicate = valid.deepCopy();
        duplicate.withArray("runtimes").add(runtime(valid).deepCopy());
        rejected(SnapshotJson.encode(duplicate));
        for (String field : List.of("model", "endpoint", "modelDigest", "dictionaryHash")) {
            ObjectNode bad = valid.deepCopy();
            settings(bad).put(field, "SECRET_INVALID");
            rejected(SnapshotJson.encode(bad));
        }
        ObjectNode badProfile = valid.deepCopy();
        ((ObjectNode) runtime(badProfile).get("profile")).put("policyCode", "OTHER");
        rejected(SnapshotJson.encode(badProfile));
        ObjectNode longTemplate = valid.deepCopy();
        settings(longTemplate).put("modelTemplate", "x".repeat(65537));
        rejected(SnapshotJson.encode(longTemplate));
        for (ObjectNode object :
                List.of(valid, runtime(valid), (ObjectNode) runtime(valid).get("profile"))) {
            var keys = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                var previous = object.remove(key);
                rejected(SnapshotJson.encode(valid));
                object.putNull(key);
                rejected(SnapshotJson.encode(valid));
                object.set(key, previous);
            }
        }
    }

    /**
     * 공통 리더의 좁은 경계 시험 지점과 실제 설치 로더의 안전 오류 계약을 함께 검사한다.
     *
     * @throws Exception 시험 파일·링크 생성 또는 POSIX 권한 설정 실패인 경우
     */
    @Test
    void rejectsUnsafePathsAndAcceptsOnlyOwnerModes() throws Exception {
        Path base = temporary.toRealPath();
        Path outside = Files.createDirectory(base.resolve("installation"));
        Path file = protectedFile("valid");
        for (String mode : List.of("r--------", "rw-------")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
            GradeProtectedDocumentReader.secure(file, outside);
            assertThat(GradeProtectedDocumentReader.read(file.toString()))
                    .isEqualTo(SnapshotJson.encode(document()));
            assertThat(new GradeInstallationLoader().read(file.toString())).hasSize(1);
        }
        for (String mode :
                List.of("---------", "r--r--r--", "rw-r-----", "rw-r--r--", "rwx------")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
            assertThatThrownBy(() -> GradeProtectedDocumentReader.secure(file, outside))
                    .isInstanceOf(IOException.class)
                    .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                    .hasNoCause();
            assertThatThrownBy(() -> new GradeInstallationLoader().read(file.toString()))
                    .hasMessage("INVALID_GRADE_INSTALLATION")
                    .hasNoCause();
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> GradeProtectedDocumentReader.secure(file, base))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                .hasNoCause();
        assertThatThrownBy(() -> GradeProtectedDocumentReader.secure(Path.of("relative"), outside))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                .hasNoCause();
        assertThatThrownBy(() -> GradeProtectedDocumentReader.secure(base, outside))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                .hasNoCause();
        Path link = Files.createSymbolicLink(base.resolve("link"), file);
        assertThatThrownBy(() -> GradeProtectedDocumentReader.secure(link, outside))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                .hasNoCause();
        Path directoryLink = Files.createSymbolicLink(base.resolve("directory-link"), base);
        assertThatThrownBy(
                        () ->
                                GradeProtectedDocumentReader.secure(
                                        directoryLink.resolve(file.getFileName()), outside))
                .isInstanceOf(IOException.class)
                .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                .hasNoCause();

        for (Path invalid :
                List.of(
                        Path.of("relative"),
                        base,
                        link,
                        directoryLink.resolve(file.getFileName()))) {
            assertThatThrownBy(() -> new GradeInstallationLoader().read(invalid.toString()))
                    .hasMessage("INVALID_GRADE_INSTALLATION")
                    .hasNoCause();
        }
        assertThatThrownBy(
                        () ->
                                new GradeInstallationLoader()
                                        .load(base.resolve("missing-secret").toString(), null))
                .hasMessage("INVALID_GRADE_INSTALLATION")
                .hasNoCause();
    }

    /**
     * 공통 리더와 설치 로더가 user.name 대신 현재 JVM의 실제 POSIX UID를 사용하는지 검사한다.
     *
     * @throws Exception 보호 시험 파일 생성 또는 실제 UID 속성 읽기 실패인 경우
     */
    @Test
    void checksActualProcessUidRatherThanUserNameProperty() throws Exception {
        Path file = protectedFile("uid");
        assertThat(((Number) Files.getAttribute(file, "unix:uid")).longValue())
                .isEqualTo(new com.sun.security.auth.module.UnixSystem().getUid());
        String previous = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "not-the-current-process-user");
            assertThat(GradeProtectedDocumentReader.read(file.toString()))
                    .isEqualTo(SnapshotJson.encode(document()));
            assertThat(new GradeInstallationLoader().read(file.toString())).hasSize(1);
        } finally {
            if (previous == null) System.clearProperty("user.name");
            else System.setProperty("user.name", previous);
        }
    }

    /**
     * 실제 CodeSource 설치 안의 보호 파일을 공통 리더와 설치 로더가 cwd와 무관하게 거절한다.
     *
     * @throws Exception 실제 설치 경로 확인 또는 보호 시험 파일 생성·정리 실패인 경우
     */
    @Test
    void rejectsFileInsideActualApplicationInstallation() throws Exception {
        Path source =
                Path.of(
                                com.reasoning.ReasoningApplication.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toRealPath();
        Path directory = Files.isDirectory(source) ? source : source.getParent();
        Path inside = Files.createTempFile(directory, "installation-rejection-", ".json");
        try {
            Files.setPosixFilePermissions(inside, PosixFilePermissions.fromString("rw-------"));
            Files.write(inside, SnapshotJson.encode(document()));
            assertThatThrownBy(() -> GradeProtectedDocumentReader.read(inside.toString()))
                    .isInstanceOf(IOException.class)
                    .hasMessage("INVALID_GRADE_PROTECTED_DOCUMENT")
                    .hasNoCause();
            assertThatThrownBy(() -> new GradeInstallationLoader().read(inside.toString()))
                    .hasMessage("INVALID_GRADE_INSTALLATION")
                    .hasNoCause();
        } finally {
            Files.delete(inside);
        }
    }

    /**
     * 유효 입력 확인 뒤 후속 입력 실패가 첫 사전 SELECT 이전에 전체 시작을 거절하는지 검사한다.
     *
     * @throws Exception 보호 시험 파일 생성·쓰기 실패인 경우
     */
    @Test
    void parsesAllInputsBeforeAnyDictionaryQuery() throws Exception {
        ObjectNode root = document();
        assertThat(GradeInstallationLoader.parse(SnapshotJson.encode(root))).hasSize(1);

        root.withArray("runtimes").add(runtime(root).deepCopy().put("dictionaryCode", "invalid"));
        Path file = protectedFile("all-or-nothing");
        Files.write(file, SnapshotJson.encode(root));
        var jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(
                        () ->
                                new GradeInstallationLoader()
                                        .load(file.toString(), new GradeDictionaryRepository(jdbc)))
                .hasMessage("INVALID_GRADE_INSTALLATION")
                .hasNoCause();
        verifyNoInteractions(jdbc);
    }

    /** 정해진 보호 권한의 외부 임시 문서를 만든다. */
    private Path protectedFile(String name) throws Exception {
        Path file = Files.createFile(temporary.toRealPath().resolve(name));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.write(file, SnapshotJson.encode(document()));
        return file;
    }

    /** 안전 오류가 원인과 입력 원문을 노출하지 않는지 검사한다. */
    private static void rejected(byte[] bytes) {
        assertThatThrownBy(() -> GradeInstallationLoader.parse(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVALID_GRADE_INSTALLATION")
                .hasNoCause();
    }

    /** 기본값 없이 모든 필드를 명시한 시험 문서를 만든다. */
    private static ObjectNode document() {
        return (ObjectNode)
                SnapshotJson.parse(
                        ("""
                        {"formatNo":1,"runtimes":[{"profile":{"configId":"PRIVATE","engineId":"LOCAL","engineVersion":"2","policyCode":"RULE_20260924"},
                        "dictionaryCode":"TEST","settings":{"endpoint":"http://127.0.0.1:11434/","model":"fixture:8b","modelDigest":"%s",
                        "dictionaryHash":"%s","modelTemplate":"template","numCtx":8192,"numPredict":256,"temperature":0,"seed":0,"thinking":false,"executionTimeoutMillis":120000}}]}
                        """)
                                .formatted("a".repeat(64), "b".repeat(64))
                                .getBytes(StandardCharsets.UTF_8));
    }

    /** 첫 runtime의 시험 객체를 반환한다. */
    private static ObjectNode runtime(ObjectNode root) {
        return (ObjectNode) root.get("runtimes").get(0);
    }

    /** 명시 설정의 시험 객체를 반환한다. */
    private static ObjectNode settings(ObjectNode root) {
        return (ObjectNode) runtime(root).get("settings");
    }
}
