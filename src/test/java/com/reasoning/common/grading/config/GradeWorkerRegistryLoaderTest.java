package com.reasoning.common.grading.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.ReasoningApplication;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.util.CommonUtil;
import com.sun.security.auth.module.UnixSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 실제 보호 리더·자격증명·Spring 설정만 검사하며 SQL·네트워크·실행을 활성화하지 않는다. */
class GradeWorkerRegistryLoaderTest {
    @TempDir Path temporary;
    private static final byte[] TOKEN_BYTES = new byte[32];
    private static final String TOKEN =
            Base64.getUrlEncoder().withoutPadding().encodeToString(TOKEN_BYTES);
    private static final String HEADER = "Bearer " + TOKEN;
    private static final String DIGEST = CommonUtil.sha256(TOKEN_BYTES);

    /** 비활성은 리더 호출 전 종료하며 실제 정규 토큰도 거절한다. */
    @Test
    void blankDisablesBeforeAnyProtectedRead() {
        try (var reader = mockStatic(GradeProtectedDocumentReader.class)) {
            for (String path : new String[] {null, "", " \t\r\n", "\u2003"}) {
                var credentials = new GradeWorkerRegistryLoader().load(path);
                assertThatThrownBy(() -> credentials.authenticate(HEADER))
                        .isInstanceOf(SecurityException.class)
                        .hasMessage("WORKER_AUTH_REQUIRED")
                        .hasNoCause();
                assertThat(new GradeInstallationConfig().gradeWorkerCredentials(path)).isNotNull();
            }
            reader.verifyNoInteractions();
        }
    }

    /** 원본 32바이트 해시·명시 권한·레지스트리 소유자 증명·파일 변경 후 불변성을 검사한다. */
    @Test
    void authenticatesActualPrimitiveAndKeepsOwnerAndPermissionsImmutable() throws Exception {
        ObjectNode root = document();
        byte[] secondBytes = TOKEN_BYTES.clone();
        secondBytes[0] = 1;
        String secondHeader =
                "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secondBytes);
        ObjectNode second = worker(root).deepCopy();
        second.put("workerKey", "second");
        second.put("credentialSha256", CommonUtil.sha256(secondBytes));
        second.putArray("runtimeCodes").add("OTHER");
        second.putArray("actions").add("COMPLETE");
        root.withArray("workers").add(second);
        Path file = protectedFile(SnapshotJson.encode(root));
        var credentials = new GradeWorkerRegistryLoader().load(file.toString());
        var proof = credentials.authenticate(HEADER);
        assertThat(proof.workerKey()).isEqualTo("worker_1");
        credentials.requirePermission(proof, Action.CLAIM, "RUNTIME_1");
        var secondProof = credentials.authenticate(secondHeader);
        assertThat(secondProof.workerKey()).isEqualTo("second");
        credentials.requirePermission(secondProof, Action.COMPLETE, "OTHER");
        for (Action action : List.of(Action.START, Action.RENEW, Action.COMPLETE)) {
            denied(() -> credentials.requirePermission(proof, action, "RUNTIME_1"));
        }
        for (String runtime : new String[] {null, "OTHER", "runtime_1", " RUNTIME_1"}) {
            denied(() -> credentials.requirePermission(proof, Action.CLAIM, runtime));
        }
        denied(() -> credentials.requirePermission(null, Action.CLAIM, "RUNTIME_1"));
        denied(() -> credentials.requirePermission(proof, null, "RUNTIME_1"));
        var otherRegistry = new GradeWorkerRegistryLoader().load(file.toString());
        denied(() -> otherRegistry.requirePermission(proof, Action.CLAIM, "RUNTIME_1"));
        denied(
                () ->
                        credentials.requirePermission(
                                otherRegistry.authenticate(HEADER), Action.CLAIM, "RUNTIME_1"));

        Files.writeString(file, "invalid changed document");
        credentials.requirePermission(proof, Action.CLAIM, "RUNTIME_1");
        assertThat(credentials.authenticate(HEADER).workerKey()).isEqualTo("worker_1");
        assertThatThrownBy(() -> new GradeWorkerRegistryLoader().load(file.toString()))
                .hasMessage("INVALID_GRADE_WORKERS")
                .hasNoCause();

        ObjectNode encodedDigest = document();
        worker(encodedDigest)
                .put("credentialSha256", CommonUtil.sha256(TOKEN.getBytes(StandardCharsets.UTF_8)));
        var wrong = GradeWorkerRegistryLoader.parse(SnapshotJson.encode(encodedDigest));
        assertThatThrownBy(() -> wrong.authenticate(HEADER))
                .hasMessage("WORKER_AUTH_REQUIRED")
                .hasNoCause();
        for (Action action : Action.values()) {
            ObjectNode one = document();
            worker(one).putArray("actions").add(action.name());
            var actual = GradeWorkerRegistryLoader.parse(SnapshotJson.encode(one));
            actual.requirePermission(actual.authenticate(HEADER), action, "RUNTIME_1");
        }
    }

    /** 유효 대조에서 UTF-8·다른 인코딩·중복 키·후행 JSON·고립 서로게이트만 변경한다. */
    @Test
    void rejectsStrictEncodingAndJsonMutations() {
        String valid = new String(SnapshotJson.encode(document()), StandardCharsets.UTF_8);
        for (String invalid :
                List.of(
                        "",
                        "null",
                        "[]",
                        "{}",
                        "{",
                        valid + " {}",
                        valid.replace("\"formatNo\":1", "\"formatNo\":1,\"formatNo\":1"),
                        valid.replace(
                                "\"workerKey\":\"worker_1\"",
                                "\"workerKey\":\"worker_1\",\"workerKey\":\"worker_1\""),
                        valid.replace("worker_1", "\\ud800"),
                        valid.replace("worker_1", "\\udc00"))) {
            control();
            rejected(invalid.getBytes(StandardCharsets.UTF_8));
        }
        for (String encoding : List.of("UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            control();
            rejected(valid.getBytes(Charset.forName(encoding)));
        }
        byte[] malformed = valid.getBytes(StandardCharsets.UTF_8);
        int offset = valid.indexOf("worker_1");
        malformed[offset] = (byte) 0xc3;
        malformed[offset + 1] = 0x28;
        control();
        rejected(malformed);
        rejected(null);
    }

    /** 닫힌 필드·타입·정확한 버전·비어 있지 않은 배열을 유효 대조와 독립 변이로 검사한다. */
    @Test
    void rejectsClosedSchemaAndTypeMutations() {
        for (String field : List.of("formatNo", "workers")) {
            mutation(root -> root.remove(field));
            mutation(root -> root.putNull(field));
        }
        for (String field : List.of("workerKey", "credentialSha256", "runtimeCodes", "actions")) {
            mutation(root -> worker(root).remove(field));
            mutation(root -> worker(root).putNull(field));
        }
        mutation(root -> root.put("token", TOKEN));
        mutation(root -> worker(root).put("endpoint", "forbidden"));
        mutation(root -> root.putArray("workers"));
        mutation(root -> root.put("workers", "worker"));
        mutation(root -> root.putObject("workers"));
        mutation(root -> root.putArray("workers").addNull());
        mutation(root -> root.putArray("workers").add("worker"));
        for (String field : List.of("runtimeCodes", "actions")) {
            mutation(root -> worker(root).putArray(field));
            mutation(root -> worker(root).put(field, "CLAIM"));
            mutation(root -> worker(root).putObject(field));
            mutation(root -> worker(root).putArray(field).addNull());
            mutation(root -> worker(root).putArray(field).add(1));
            mutation(root -> worker(root).putArray(field).addObject());
        }
        for (String field : List.of("workerKey", "credentialSha256")) {
            mutation(root -> worker(root).put(field, 1));
            mutation(root -> worker(root).put(field, true));
            mutation(root -> worker(root).putArray(field));
        }
        mutation(root -> root.put("formatNo", "1"));
        mutation(root -> root.put("formatNo", true));
        mutation(root -> root.putArray("formatNo"));
        for (String value : List.of("0", "2", "1.1", "2147483648", "1e100")) {
            mutation(root -> root.put("formatNo", new java.math.BigDecimal(value)));
        }
        control();
        String valid = new String(SnapshotJson.encode(document()), StandardCharsets.UTF_8);
        assertThat(
                        GradeWorkerRegistryLoader.parse(
                                        valid.replace("\"formatNo\":1", "\"formatNo\":1.0")
                                                .getBytes(StandardCharsets.UTF_8))
                                .authenticate(HEADER)
                                .workerKey())
                .isEqualTo("worker_1");
    }

    /** 실제 등록의 경계·문법 및 중복 권한·키·해시·마지막 잘못된 등록의 전체 실패를 검사한다. */
    @Test
    void delegatesRegistrationBoundsAndRejectsDuplicatesWithoutPartialAssembly() {
        for (String value : List.of("", "x".repeat(81), "한글", "a.b", " worker", "worker ")) {
            mutation(root -> worker(root).put("workerKey", value));
        }
        for (String value : List.of("", "R".repeat(81), "lower", "R-X", " R", "R ")) {
            mutation(root -> worker(root).putArray("runtimeCodes").add(value));
        }
        for (String value :
                List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            mutation(root -> worker(root).put("credentialSha256", value));
        }
        for (String value : List.of("claim", " CLAIM", "CLAIM ", "*", "UNKNOWN", "")) {
            mutation(root -> worker(root).putArray("actions").add(value));
        }
        mutation(root -> worker(root).withArray("runtimeCodes").add("RUNTIME_1"));
        mutation(root -> worker(root).withArray("actions").add("CLAIM"));
        mutation(
                root -> {
                    ObjectNode duplicate = worker(root).deepCopy();
                    duplicate.put("credentialSha256", "f".repeat(64));
                    root.withArray("workers").add(duplicate);
                });
        mutation(
                root -> {
                    ObjectNode duplicate = worker(root).deepCopy();
                    duplicate.put("workerKey", "different");
                    root.withArray("workers").add(duplicate);
                });
        mutation(
                root -> {
                    ObjectNode invalid = worker(root).deepCopy();
                    invalid.put("workerKey", "last");
                    invalid.put("credentialSha256", "invalid");
                    root.withArray("workers").add(invalid);
                });
        for (int length : List.of(1, 80)) {
            ObjectNode root = document();
            worker(root).put("workerKey", "x".repeat(length));
            worker(root).putArray("runtimeCodes").add("R".repeat(length));
            var credentials = GradeWorkerRegistryLoader.parse(SnapshotJson.encode(root));
            var proof = credentials.authenticate(HEADER);
            assertThat(proof.workerKey()).hasSize(length);
            credentials.requirePermission(proof, Action.CLAIM, "R".repeat(length));
        }
    }

    /** 동일한 유효 문서와 공백 패딩으로 직접 파서·실제 스트림의 정확한 16MiB 상한을 검사한다. */
    @Test
    void acceptsExactByteLimitAndRejectsOneExtraByte() throws Exception {
        byte[] valid = SnapshotJson.encode(document());
        control();
        byte[] exact = new byte[GradeProtectedDocumentReader.MAX_BYTES];
        Arrays.fill(exact, (byte) ' ');
        System.arraycopy(valid, 0, exact, 0, valid.length);
        assertThat(GradeWorkerRegistryLoader.parse(exact).authenticate(HEADER).workerKey())
                .isEqualTo("worker_1");
        Path file = protectedFile(exact);
        assertThat(
                        new GradeWorkerRegistryLoader()
                                .load(file.toString())
                                .authenticate(HEADER)
                                .workerKey())
                .isEqualTo("worker_1");
        byte[] oversized = Arrays.copyOf(exact, exact.length + 1);
        oversized[exact.length] = ' ';
        rejected(oversized);
        Files.write(file, oversized);
        loadRejected(file.toString());
    }

    /** 실제 UID·CodeSource 외부·0400/0600·모든 경로 구성요소를 사용하며 고정 안전 진단을 검사한다. */
    @Test
    void enforcesActualProtectedFilePolicyAndMasksFailures() throws Exception {
        Path file = protectedFile(SnapshotJson.encode(document()));
        assertThat(((Number) Files.getAttribute(file, "unix:uid")).longValue())
                .isEqualTo(new UnixSystem().getUid());
        String oldName = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "not-the-actual-owner");
            for (String mode : List.of("r--------", "rw-------")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
                assertThat(
                                new GradeWorkerRegistryLoader()
                                        .load(file.toString())
                                        .authenticate(HEADER)
                                        .workerKey())
                        .isEqualTo("worker_1");
            }
        } finally {
            if (oldName == null) System.clearProperty("user.name");
            else System.setProperty("user.name", oldName);
        }
        for (String mode :
                List.of(
                        "---------",
                        "-w-------",
                        "r--r--r--",
                        "rw-r-----",
                        "rwx------",
                        "rw------x",
                        "rw-rw----",
                        "rw----r--")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
            loadRejected(file.toString());
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Path finalLink = temporary.toRealPath().resolve("final-link");
        Files.createSymbolicLink(finalLink, file);
        loadRejected(finalLink.toString());
        Path ancestor = temporary.toRealPath().resolve("ancestor-link");
        Files.createSymbolicLink(ancestor, temporary.toRealPath());
        loadRejected(ancestor.resolve(file.getFileName()).toString());
        Path nested = Files.createDirectories(temporary.toRealPath().resolve("nested/deeper"));
        Path middleLink = nested.resolve("middle-link");
        Files.createSymbolicLink(middleLink, temporary.toRealPath());
        loadRejected(middleLink.resolve(file.getFileName()).toString());
        loadRejected(
                ancestor.resolve("..")
                        .resolve(temporary.getFileName())
                        .resolve(file.getFileName())
                        .toString());
        loadRejected("relative.json");
        loadRejected(temporary.toRealPath().toString());
        loadRejected(temporary.toRealPath().resolve("missing").toString());
        loadRejected(file + "\0");
        Path codeSource =
                Path.of(
                                ReasoningApplication.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toRealPath();
        Path inside = Files.createTempFile(codeSource, "worker-boundary-", ".json");
        try {
            Files.setPosixFilePermissions(inside, PosixFilePermissions.fromString("rw-------"));
            Files.write(inside, SnapshotJson.encode(document()));
            loadRejected(inside.toString());
        } finally {
            Files.deleteIfExists(inside);
        }
        Files.write(file, new byte[0]);
        loadRejected(file.toString());
        for (Throwable failure :
                List.of(
                        new java.io.IOException(file.toString()),
                        new UnsupportedOperationException(DIGEST),
                        new UnsatisfiedLinkError(TOKEN))) {
            try (var reader = mockStatic(GradeProtectedDocumentReader.class)) {
                reader.when(() -> GradeProtectedDocumentReader.read(file.toString()))
                        .thenThrow(failure);
                loadRejected(file.toString());
            }
        }
    }

    /** 실제 리소스의 환경 별칭과 상위 canonical 우선순위를 검사하며 JDBC는 초기화 외 호출하지 않는다. */
    @Test
    void springUsesActualResourceEnvironmentMappingAndCanonicalPrecedence() throws Exception {
        Path valid = protectedFile(SnapshotJson.encode(document()));
        Path missing = temporary.toRealPath().resolve("missing");
        var jdbc = mock(JdbcTemplate.class);
        runner(Map.of("GRADING_WORKERS_FILE", valid.toString()), Map.of(), jdbc, false)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(GradeWorkerCredentials.class)
                                                    .authenticate(HEADER)
                                                    .workerKey())
                                    .isEqualTo("worker_1");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("app.grading.workers-file"))
                                    .isEqualTo(valid.toString());
                        });
        runner(
                        Map.of("GRADING_WORKERS_FILE", missing.toString()),
                        Map.of("app.grading.workers-file", valid.toString()),
                        jdbc,
                        false)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(GradeWorkerCredentials.class)
                                                    .authenticate(HEADER)
                                                    .workerKey())
                                    .isEqualTo("worker_1");
                        });
        for (Map<String, Object> alias :
                List.<Map<String, Object>>of(
                        Map.of(),
                        Map.of("GRADING_WORKERS_FILE", ""),
                        Map.of("GRADING_WORKERS_FILE", " \t "))) {
            runner(alias, Map.of(), jdbc, false)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThatThrownBy(
                                                () ->
                                                        context.getBean(
                                                                        GradeWorkerCredentials
                                                                                .class)
                                                                .authenticate(HEADER))
                                        .hasMessage("WORKER_AUTH_REQUIRED");
                            });
        }
        try (var reader = mockStatic(GradeProtectedDocumentReader.class)) {
            for (String blank : List.of("", " \t ")) {
                runner(
                                Map.of("GRADING_WORKERS_FILE", missing.toString()),
                                Map.of("app.grading.workers-file", blank),
                                jdbc,
                                false)
                        .run(
                                context -> {
                                    assertThat(context).hasNotFailed();
                                    assertThatThrownBy(
                                                    () ->
                                                            context.getBean(
                                                                            GradeWorkerCredentials
                                                                                    .class)
                                                                    .authenticate(HEADER))
                                            .hasMessage("WORKER_AUTH_REQUIRED");
                                });
            }
            reader.verifyNoInteractions();
        }
        assertOnlyJdbcInitialization(jdbc);
        var directJdbc = mock(JdbcTemplate.class);
        assertThat(new GradeInstallationConfig().gradeInstallations("", directJdbc)).isEmpty();
        verifyNoInteractions(directJdbc);
    }

    /** 사용자가 조회하지 않아도 실제 lazy 처리기 아래 단일 빈을 게시하고 설정 오류는 시작을 거절한다. */
    @Test
    void springIsEagerSingletonEvenWithGlobalLazyAndAddsNoActivationBeans() throws Exception {
        Path file = protectedFile(SnapshotJson.encode(document()));
        var jdbc = mock(JdbcTemplate.class);
        for (boolean lazy : List.of(false, true)) {
            runner(Map.of("GRADING_WORKERS_FILE", file.toString()), Map.of(), jdbc, lazy)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(
                                                context.getBeanFactory()
                                                        .containsSingleton(
                                                                "gradeWorkerCredentials"))
                                        .isTrue();
                                assertThat(
                                                context.getBeanFactory()
                                                        .getBeanDefinition("gradeWorkerCredentials")
                                                        .isLazyInit())
                                        .isFalse();
                                assertThat(context.getBean(GradeWorkerCredentials.class))
                                        .isSameAs(context.getBean("gradeWorkerCredentials"));
                                assertThat(
                                                context.getBeanFactory()
                                                        .getBeanDefinition("gradeWorkerCredentials")
                                                        .getDependsOn())
                                        .isNullOrEmpty();
                                assertThat(context.getBean("gradeInstallations"))
                                        .isInstanceOfSatisfying(
                                                Map.class,
                                                installations ->
                                                        assertThat(installations.isEmpty())
                                                                .isTrue());
                                assertThat(
                                                context.getBeansOfType(
                                                        org.springframework.security.authentication
                                                                .AuthenticationProvider.class))
                                        .isEmpty();
                                assertThat(
                                                context.getBeansOfType(
                                                        org.springframework.security.web
                                                                .SecurityFilterChain.class))
                                        .isEmpty();
                                assertThat(context.getBeansOfType(jakarta.servlet.Filter.class))
                                        .isEmpty();
                                assertThat(
                                                context.getBeansOfType(
                                                        org.springframework.web.servlet.mvc.method
                                                                .annotation
                                                                .RequestMappingHandlerMapping
                                                                .class))
                                        .isEmpty();
                                assertThat(
                                                context.getBeansOfType(
                                                        com.reasoning.common.grading.engine
                                                                .LocalSemanticEngine.class))
                                        .isEmpty();
                                assertThat(
                                                context.getBeansOfType(
                                                        com.reasoning.common.grading.engine
                                                                .InstalledRuntimeManifestVerifier
                                                                .class))
                                        .isEmpty();
                            });

            control();
            ObjectNode invalidLast = document();
            invalidLast.withArray("workers").addObject().put("workerKey", "last");
            for (String content :
                    List.of(
                            "not-json",
                            "{\"formatNo\":1,\"workers\":[]}",
                            new String(SnapshotJson.encode(invalidLast), StandardCharsets.UTF_8))) {
                Files.writeString(file, content);
                runner(Map.of("GRADING_WORKERS_FILE", file.toString()), Map.of(), jdbc, lazy)
                        .run(
                                context -> {
                                    assertThat(context).hasFailed();
                                    assertThat(rootCause(context.getStartupFailure()))
                                            .isInstanceOf(IllegalStateException.class)
                                            .hasMessage("INVALID_GRADE_WORKERS")
                                            .hasNoCause();
                                });
            }
            runner(
                            Map.of(
                                    "GRADING_WORKERS_FILE",
                                    temporary.toRealPath().resolve("missing").toString()),
                            Map.of(),
                            jdbc,
                            lazy)
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(rootCause(context.getStartupFailure()))
                                        .hasMessage("INVALID_GRADE_WORKERS")
                                        .hasNoCause();
                            });
            Files.write(file, SnapshotJson.encode(document()));
        }
        assertOnlyJdbcInitialization(jdbc);
    }

    /** 실제 application.properties와 격리된 환경형 입력만 사용하는 작은 Spring 시작을 만든다. */
    private static ApplicationContextRunner runner(
            Map<String, Object> environment,
            Map<String, Object> canonical,
            JdbcTemplate jdbc,
            boolean lazy) {
        return new ApplicationContextRunner()
                .withUserConfiguration(GradeInstallationConfig.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withInitializer(
                        context -> {
                            var sources = context.getEnvironment().getPropertySources();
                            sources.remove("systemEnvironment");
                            sources.remove("systemProperties");
                            try {
                                var resource =
                                        new ResourcePropertySource(
                                                "actualApplication",
                                                new ClassPathResource("application.properties"));
                                assertThat(resource.getProperty("app.grading.workers-file"))
                                        .isEqualTo("${GRADING_WORKERS_FILE:}");
                                sources.addLast(resource);
                            } catch (java.io.IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                            sources.addFirst(
                                    new SystemEnvironmentPropertySource(
                                            "syntheticEnvironment", environment));
                            sources.addFirst(
                                    new MapPropertySource(
                                            "blankInstallations",
                                            Map.of("app.grading.installations-file", "")));
                            sources.addFirst(new MapPropertySource("canonicalOverride", canonical));
                            if (lazy) {
                                sources.addFirst(
                                        new MapPropertySource(
                                                "globalLazy",
                                                Map.of("spring.main.lazy-initialization", "true")));
                                context.addBeanFactoryPostProcessor(
                                        new LazyInitializationBeanFactoryPostProcessor());
                            }
                        });
    }

    /** Spring의 인수 없는 JDBC 초기화만 허용하며 SQL 등 다른 호출은 전부 실패시킨다. */
    private static void assertOnlyJdbcInitialization(JdbcTemplate jdbc) {
        for (var call : org.mockito.Mockito.mockingDetails(jdbc).getInvocations()) {
            assertThat(call.getMethod().getName()).isEqualTo("afterPropertiesSet");
            assertThat(call.getArguments()).isEmpty();
        }
    }

    /** 원인 없는 worker 진단만 외부에 남는지 실제 보호 읽기 경계에서 검사한다. */
    private static void loadRejected(String path) {
        assertThatThrownBy(() -> new GradeWorkerRegistryLoader().load(path))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVALID_GRADE_WORKERS")
                .hasNoCause();
    }

    /** 정상 대조를 먼저 통과시킨 뒤 단 하나의 스키마 변이를 적용한다. */
    private static void mutation(Consumer<ObjectNode> change) {
        control();
        ObjectNode root = document();
        change.accept(root);
        rejected(SnapshotJson.encode(root));
    }

    /** 테스트에서 사용하는 원래 문서가 실제 인증까지 성공함을 확인한다. */
    private static void control() {
        assertThat(
                        GradeWorkerRegistryLoader.parse(SnapshotJson.encode(document()))
                                .authenticate(HEADER)
                                .workerKey())
                .isEqualTo("worker_1");
    }

    /** 직접 파서의 모든 실패가 민감 원인 없이 같은 진단인지 확인한다. */
    private static void rejected(byte[] bytes) {
        assertThatThrownBy(() -> GradeWorkerRegistryLoader.parse(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVALID_GRADE_WORKERS")
                .hasNoCause();
    }

    /** 실제 권한 거절의 원인 없는 진단을 확인한다. */
    private static void denied(Runnable request) {
        assertThatThrownBy(request::run)
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKER_NOT_AUTHORIZED")
                .hasNoCause();
    }

    /** 테스트 전용 정규 토큰의 원본 바이트 해시만 담는 최소 등록 문서를 만든다. */
    private static ObjectNode document() {
        return (ObjectNode)
                SnapshotJson.parse(
                        ("""
                        {"formatNo":1,"workers":[{"workerKey":"worker_1","credentialSha256":"%s",
                        "runtimeCodes":["RUNTIME_1"],"actions":["CLAIM"]}]}
                        """)
                                .formatted(DIGEST)
                                .getBytes(StandardCharsets.UTF_8));
    }

    /** 테스트에서 첫 등록만 독립적으로 변경할 수 있게 실제 노드를 반환한다. */
    private static ObjectNode worker(JsonNode root) {
        return (ObjectNode) root.get("workers").get(0);
    }

    /** 실제 경계를 벗어난 real 경로에 현재 UID 소유의 0600 문서를 만든다. */
    private Path protectedFile(byte[] bytes) throws Exception {
        Path file = Files.createTempFile(temporary.toRealPath(), "workers-", ".json");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.write(file, bytes);
        return file;
    }

    /** Spring 포장 밖 마지막 진단을 반환한다. */
    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }
}
