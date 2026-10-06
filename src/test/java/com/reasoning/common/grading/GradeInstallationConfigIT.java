package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.config.GradeInstallationConfig;
import com.reasoning.common.grading.config.GradeInstallationLoader;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.engine.LocalSemanticEngine.Settings;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeDictionaryRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.migration.EmbeddedFlywayConfiguration;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** 폐기 PostgreSQL·실제 Spring 시작·설치 원본을 검사하며 제공자 실행·품질 승인을 하지 않는다. */
class GradeInstallationConfigIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    @TempDir Path temporary;

    /** 기존 고정 이미지와 전체 마이그레이션을 사용하는 격리 DB를 연다. */
    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
    }

    /** 테스트 전용 컨테이너만 닫는다. */
    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 미지정·빈·공백 설정은 실제 Spring 빈을 만들되 사전 SELECT를 하지 않는다. */
    @Test
    void disabledStartupDoesNotAccessDictionary() {
        JdbcTemplate observed = observedJdbc(jdbc);
        clearInvocations(observed);
        for (String property : new String[] {null, "", " \t "}) {
            ApplicationContextRunner runner = runner(postgres, observed);
            if (property != null)
                runner = runner.withPropertyValues("app.grading.installations-file=" + property);
            runner.run(
                    context -> {
                        assertThat(context).hasNotFailed();
                        Map<?, ?> installed = context.getBean("gradeInstallations", Map.class);
                        assertThat(installed).isEmpty();
                    });
        }
        assertDictionaryOnlySelects(observed, 0);
    }

    /** 아직 빈 DB인 시작에서 Flyway가 끝난 뒤 빈 사전을 고정해 조립한다. */
    @Test
    void startupWaitsForDatabaseInitialization() throws Exception {
        try (var fresh =
                new PostgreSQLContainer<>(
                        DockerImageName.parse(GradeSchemaIT.IMAGE)
                                .asCompatibleSubstituteFor("postgres"))) {
            fresh.start();
            JdbcTemplate observed = observedJdbc(GradeSchemaIT.jdbc(fresh));
            clearInvocations(observed);
            var empty = new GradeDictionary("EMPTY_INSTALL", List.of());
            Path file = document(empty, "EMPTY_RUNTIME", 11434);
            runner(fresh, observed)
                    .withPropertyValues("app.grading.installations-file=" + file)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                Map<?, ?> installed =
                                        context.getBean("gradeInstallations", Map.class);
                                assertThat(installed.get("EMPTY_RUNTIME"))
                                        .isInstanceOf(InstalledRuntimeManifestVerifier.class);
                                assertDictionaryOnlySelects(observed, 1);
                                assertThat(
                                                GradeSchemaIT.jdbc(fresh)
                                                        .queryForObject(
                                                                "SELECT count(*) FROM"
                                                                        + " public.grade_runtime",
                                                                Long.class))
                                        .isZero();
                            });
        }
    }

    /** 실제 한 SELECT 사본·명시 설정·프로필과 동일한 설치를 만들고 map을 불변으로 게시한다. */
    @Test
    void immutableAssemblyUsesPinnedActualIdentityWithoutNetworkOrWrites() throws Exception {
        String code = code();
        var term = new GradeDictionary.Term("ONE", "표준", "별칭");
        jdbc.update(
                "INSERT INTO"
                    + " public.grade_term(dictionary_code,concept_code,canonical_text,alias_text)"
                    + " VALUES (?,?,?,?)",
                code,
                term.conceptCode(),
                term.canonical(),
                term.alias());
        GradeDictionary dictionary = new GradeDictionary(code, List.of(term));
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path file = document(dictionary, "ACTUAL_RUNTIME", port);
            long before =
                    jdbc.queryForObject("SELECT count(*) FROM public.grade_runtime", Long.class);
            JdbcTemplate observed = observedJdbc(jdbc);
            clearInvocations(observed);
            runner(postgres, observed)
                    .withPropertyValues("app.grading.installations-file=" + file)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                Map<?, ?> map = context.getBean("gradeInstallations", Map.class);
                                var verifier =
                                        InstalledRuntimeManifestVerifier.class.cast(
                                                map.get("ACTUAL_RUNTIME"));
                                var actualDictionary =
                                        (GradeDictionary)
                                                ReflectionTestUtils.getField(
                                                        verifier, "dictionary");
                                var actualEngine =
                                        (LocalSemanticEngine)
                                                ReflectionTestUtils.getField(verifier, "engine");
                                assertThat(actualDictionary.canonicalJson())
                                        .isEqualTo(dictionary.canonicalJson());
                                assertThat(actualDictionary.sha256())
                                        .isEqualTo(dictionary.sha256());
                                var profile =
                                        new InstalledProfile(
                                                "ACTUAL_RUNTIME", "LOCAL", "2", "RULE_20260924");
                                var expected =
                                        new InstalledRuntimeManifestVerifier(
                                                new LocalSemanticEngine(settings(dictionary, port)),
                                                dictionary,
                                                profile);
                                assertThat(verifier.registrationManifest())
                                        .isEqualTo(expected.registrationManifest());
                                assertThat(actualEngine.configurationDescriptor().settingsHash())
                                        .isEqualTo(
                                                expected.registrationManifest()
                                                        .get("settingsHash")
                                                        .textValue());
                                var verified =
                                        verifier.verify(
                                                new RuntimeRow(
                                                        1,
                                                        "ACTUAL_RUNTIME",
                                                        verifier.configHash(),
                                                        new String(
                                                                SnapshotJson.encode(
                                                                        verifier
                                                                                .registrationManifest()),
                                                                StandardCharsets.UTF_8),
                                                        "AVAILABLE",
                                                        1,
                                                        null,
                                                        null));
                                assertThat(verified.profile()).isEqualTo(profile);
                                assertThat(ReflectionTestUtils.getField(verified, "this$0"))
                                        .isSameAs(verifier);
                                assertThatThrownBy(map::clear)
                                        .isInstanceOf(UnsupportedOperationException.class);
                                assertDictionaryOnlySelects(observed, 1);
                                assertThat(requests.get()).isZero();
                            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.grade_runtime", Long.class))
                    .isEqualTo(before);
        } finally {
            server.stop(0);
        }
    }

    /** 두 번째 runtime 해시 오류는 부분 레지스트리 없이 시작을 실패시키며 원인 원문을 차단한다. */
    @Test
    void dictionaryMismatchFailsWholeStartupSafely() throws Exception {
        GradeDictionary empty = new GradeDictionary(code(), List.of());
        Path file = document(empty, "FIRST_RUNTIME", 11434);
        ObjectNode root = (ObjectNode) SnapshotJson.parse(Files.readAllBytes(file));
        ObjectNode second = ((ObjectNode) root.get("runtimes").get(0)).deepCopy();
        ((ObjectNode) second.get("profile")).put("configId", "SECOND_RUNTIME");
        ((ObjectNode) second.get("settings")).put("dictionaryHash", "f".repeat(64));
        root.withArray("runtimes").add(second);
        Files.write(file, SnapshotJson.encode(root));
        JdbcTemplate observed = observedJdbc(jdbc);
        clearInvocations(observed);
        runner(postgres, observed)
                .withPropertyValues("app.grading.installations-file=" + file)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            Throwable safe = rootCause(context.getStartupFailure());
                            assertThat(safe).hasMessage("INVALID_GRADE_INSTALLATION").hasNoCause();
                            assertDictionaryOnlySelects(observed, 2);
                        });
    }

    /** 실제 SQL 오류와 활성 TX는 민감한 원인 없이 거절하며 TX 안에서는 SELECT도 하지 않는다. */
    @Test
    void databaseErrorAndTransactionBoundaryAreSafe() throws Exception {
        GradeDictionary empty = new GradeDictionary(code(), List.of());
        Path file = document(empty, "ERROR_RUNTIME", 11434);
        DriverManagerDataSource unavailable =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl() + "?currentSchema=missing",
                        postgres.getUsername(),
                        postgres.getPassword());
        // 테이블을 삭제하지 않고 현재 사용자에게 접근 불가능한 폐기 연결에서 실제 DB 오류를 만든다.
        unavailable.setUsername("missing_installation_user");
        assertThatThrownBy(
                        () ->
                                new GradeInstallationLoader()
                                        .load(
                                                file.toString(),
                                                new GradeDictionaryRepository(
                                                        new JdbcTemplate(unavailable))))
                .hasMessage("INVALID_GRADE_INSTALLATION")
                .hasNoCause();
        JdbcTemplate observed = observedJdbc(jdbc);
        clearInvocations(observed);
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(
                        status -> {
                            assertThatThrownBy(
                                            () ->
                                                    new GradeInstallationLoader()
                                                            .load(
                                                                    file.toString(),
                                                                    new GradeDictionaryRepository(
                                                                            observed)))
                                    .hasMessage("INVALID_GRADE_INSTALLATION")
                                    .hasNoCause();
                        });
        verifyNoInteractions(observed);
    }

    /** 누락 파일·빈 목록·잘못된 문서는 비활성으로 낮추지 않고 시작을 거절한다. */
    @Test
    void configuredInvalidDocumentsFailStartupBeforeSelect() throws Exception {
        Path file = document(new GradeDictionary(code(), List.of()), "INVALID_RUNTIME", 11434);
        JdbcTemplate observed = observedJdbc(jdbc);
        clearInvocations(observed);
        for (String content : List.of("{\"formatNo\":1,\"runtimes\":[]}", "{}", "not-json")) {
            Files.writeString(file, content);
            runner(postgres, observed)
                    .withPropertyValues("app.grading.installations-file=" + file)
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(rootCause(context.getStartupFailure()))
                                        .hasMessage("INVALID_GRADE_INSTALLATION")
                                        .hasNoCause();
                            });
        }
        runner(postgres, observed)
                .withPropertyValues(
                        "app.grading.installations-file="
                                + temporary.toRealPath().resolve("missing"))
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(rootCause(context.getStartupFailure()))
                                    .hasMessage("INVALID_GRADE_INSTALLATION")
                                    .hasNoCause();
                        });
        assertDictionaryOnlySelects(observed, 0);
    }

    /** 실행 제공자·worker 없이 실제 SQL 공급원과 DB 초기화 자동 구성을 포함하는 Spring 시작을 만든다. */
    private static ApplicationContextRunner runner(
            PostgreSQLContainer<?> database, JdbcTemplate observed) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withUserConfiguration(
                        EmbeddedFlywayConfiguration.class, GradeInstallationConfig.class)
                .withBean(
                        DataSource.class,
                        () ->
                                new DriverManagerDataSource(
                                        database.getJdbcUrl(),
                                        database.getUsername(),
                                        database.getPassword()))
                .withBean(JdbcTemplate.class, () -> observed);
    }

    /** 내부 overload 호출을 중복 세지 않고 실제 JDBC 도구로 위임하는 관측 경계를 만든다. */
    private static JdbcTemplate observedJdbc(JdbcTemplate target) {
        return mock(
                JdbcTemplate.class,
                withSettings()
                        .defaultAnswer(
                                invocation -> {
                                    try {
                                        return invocation
                                                .getMethod()
                                                .invoke(target, invocation.getRawArguments());
                                    } catch (
                                            java.lang.reflect.InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                }));
    }

    /**
     * Spring의 인수 없는 빈 초기화와 실제 SQL 호출을 구분하며 모든 나머지 호출을 사전 SELECT로 제한한다.
     *
     * @param observed 실제 JDBC 도구로 위임하는 관측 객체
     * @param expected 허용된 사전 SELECT의 정확한 횟수이며 비활성은 0
     */
    private static void assertDictionaryOnlySelects(JdbcTemplate observed, int expected) {
        var calls = org.mockito.Mockito.mockingDetails(observed).getInvocations();
        int selected = 0;
        for (var call : calls) {
            if (call.getMethod().getName().equals("afterPropertiesSet")) {
                assertThat(call.getArguments()).isEmpty();
                continue;
            }

            assertThat(call.getMethod().getName()).isEqualTo("query");
            assertThat((String) call.getArgument(0))
                    .startsWith("SELECT concept_code, canonical_text, alias_text")
                    .contains("FROM public.grade_term");
            selected++;
        }
        assertThat(selected).isEqualTo(expected);
    }

    /** Spring 포장 밖의 마지막 오류를 찾아 민감 원인 차단을 검사한다. */
    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }

    /** 외부 임시 보호 파일에 모든 입력을 명시하며 CR/LF 원문을 포함한다. */
    private Path document(GradeDictionary dictionary, String configId, int port) throws Exception {
        ObjectNode root =
                (ObjectNode)
                        SnapshotJson.parse(
                                ("""
                                {"formatNo":1,"runtimes":[{"profile":{"configId":"%s","engineId":"LOCAL","engineVersion":"2","policyCode":"RULE_20260924"},
                                "dictionaryCode":"%s","settings":{"endpoint":"http://127.0.0.1:%d/","model":"fixture:8b","modelDigest":"%s",
                                "dictionaryHash":"%s","modelTemplate":"original\\r\\nline\\n","numCtx":8192,"numPredict":256,"temperature":0,"seed":0,"thinking":false,"executionTimeoutMillis":120000}}]}
                                """)
                                        .formatted(
                                                configId,
                                                dictionary.dictionaryCode(),
                                                port,
                                                "a".repeat(64),
                                                dictionary.sha256())
                                        .getBytes(StandardCharsets.UTF_8));
        Path file = Files.createTempFile(temporary.toRealPath(), "install-", ".json");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.write(file, SnapshotJson.encode(root));
        return file;
    }

    /** 문서와 동일한 명시 실제 설정을 독립적으로 만들어 설치 identity를 대조한다. */
    private static Settings settings(GradeDictionary dictionary, int port) {
        return new Settings(
                URI.create("http://127.0.0.1:" + port + "/"),
                "fixture:8b",
                "a".repeat(64),
                dictionary.sha256(),
                "original\r\nline\n",
                8192,
                256,
                0,
                0,
                false,
                Duration.ofMillis(120000));
    }

    /** 테스트 간 겹치지 않는 등록 문법의 사전 코드를 만든다. */
    private static String code() {
        return "I"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
}
