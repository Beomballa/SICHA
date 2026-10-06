package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.repository.GradeRuntimeRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 신뢰된 등록 경계의 실제 DB 충돌·동등 JSON·잠금을 시험하며 실제 모델 승인을 주장하지 않는다. */
class GradeRuntimeRepositoryIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static GradeRuntimeRepository repository;

    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        repository = new GradeRuntimeRepository(jdbc);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    @Test
    void equivalentJsonReplaysWithoutMutatingAnyExistingValue() {
        String code = code();
        var registered =
                repository.registerRuntime(
                        code,
                        GradeSchemaIT.HASH,
                        "{\"model\":\"isolated\",\"nested\":{\"a\":1,\"b\":true}}");
        var replay =
                repository.registerRuntime(
                        code,
                        GradeSchemaIT.HASH,
                        " { \"nested\": {\"b\":true,\"a\":1.0}, \"model\":\"isolated\" } ");
        assertThat(replay).isEqualTo(registered);
        assertThat(repository.getRuntimeDetail(registered.id())).contains(registered);
        assertThat(registered.toString())
                .doesNotContain(
                        "isolated", "nested", registered.configJson(), GradeSchemaIT.HASH, code);
        jdbc.update(
                "UPDATE public.grade_runtime SET"
                    + " state='SUSPENDED',epoch=9,updated_at=created_at+interval '1 second' WHERE"
                    + " id=?",
                registered.id());
        var suspended = repository.getRuntimeDetail(registered.id()).orElseThrow();
        assertThat(repository.registerRuntime(code, GradeSchemaIT.HASH, registered.configJson()))
                .isEqualTo(suspended);
        assertThatThrownBy(
                        () ->
                                repository.registerRuntime(
                                        code, "b".repeat(64), registered.configJson()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUNTIME_CONFIGURATION_CONFLICT");
        assertThatThrownBy(
                        () ->
                                repository.registerRuntime(
                                        code, GradeSchemaIT.HASH, "{\"model\":\"different\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUNTIME_CONFIGURATION_CONFLICT");
        assertThat(repository.getRuntimeDetail(registered.id())).contains(suspended);
        assertThat(
                        repository
                                .registerRuntime(
                                        code(), "b".repeat(64), "{\"model\":\"different\"}")
                                .id())
                .isNotEqualTo(registered.id());
    }

    @Test
    void strictJsonFormatsAndUtf8AndStoredBudgets() {
        String code = code();
        for (String bad :
                List.of(
                        "",
                        "[]",
                        "null",
                        "1",
                        "\"object\"",
                        "{} {}",
                        "{\"a\":1,\"a\":2}",
                        "{\"x\":{\"a\":1,\"a\":2}}",
                        "{",
                        "{\"secret\":\"sensitive\",}")) {
            assertThatThrownBy(() -> repository.registerRuntime(code, GradeSchemaIT.HASH, bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_RUNTIME_CONFIGURATION")
                    .hasNoCause();
        }
        assertThatThrownBy(() -> repository.registerRuntime(code, GradeSchemaIT.HASH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_RUNTIME_CONFIGURATION");
        String loneSurrogate = "{\"x\":\"" + (char) 0xD800 + "\"}";
        assertThatThrownBy(
                        () -> repository.registerRuntime(code, GradeSchemaIT.HASH, loneSurrogate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_RUNTIME_CONFIGURATION");
        for (String badCode : List.of("", "lower", "WITH-DASH", "A".repeat(81))) {
            assertThatThrownBy(() -> repository.registerRuntime(badCode, GradeSchemaIT.HASH, "{}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_RUNTIME_REGISTRATION");
        }
        for (String badHash : List.of("a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            assertThatThrownBy(() -> repository.registerRuntime(code, badHash, "{}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_RUNTIME_REGISTRATION");
        }
        assertThatThrownBy(() -> repository.registerRuntime(null, GradeSchemaIT.HASH, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.registerRuntime(code, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        // JSONB은 콜론 뒤 한 바이트를 추가한다. 원문 경계뿐 아니라 실제 저장 표현도 검사한다.
        String exact = "{\"x\":\"" + "a".repeat(131063) + "\"}";
        var row = repository.registerRuntime(code(), GradeSchemaIT.HASH, exact);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT octet_length(config_data::text) FROM public.grade_runtime"
                                    + " WHERE id=?",
                                Integer.class,
                                row.id()))
                .isEqualTo(131072);
        for (String over :
                List.of(
                        "{\"x\":\"" + "a".repeat(131064) + "\"}",
                        "{\"x\":\"" + "a".repeat(131073) + "\"}",
                        "{\"x\":\"" + "𐐀".repeat(32768) + "\"}")) {
            assertThatThrownBy(() -> repository.registerRuntime(code, GradeSchemaIT.HASH, over))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_RUNTIME_CONFIGURATION");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_runtime WHERE code=?",
                                Integer.class,
                                code))
                .isZero();
    }

    @Test
    void concurrentEquivalentRegistrationReturnsOneRow() throws Exception {
        String code = code();
        int workers = 8;
        var ready = new CountDownLatch(workers);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures =
                    new ArrayList<java.util.concurrent.Future<GradeRuntimeRepository.RuntimeRow>>();
            for (int i = 0; i < workers; i++) {
                final String json = i % 2 == 0 ? "{\"a\":1,\"b\":2}" : "{\"b\":2,\"a\":1.0}";
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!release.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("동시 등록 시작 대기 실패");
                                    return repository.registerRuntime(
                                            code, GradeSchemaIT.HASH, json);
                                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            var first = futures.getFirst().get(20, TimeUnit.SECONDS);
            for (var future : futures)
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_runtime WHERE code=?",
                                Integer.class,
                                code))
                .isEqualTo(1);
    }

    @Test
    void concurrentDifferentConfigurationHasOneWinnerAndOneImmutableConflict() throws Exception {
        String code = code();
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (String value : List.of("one", "two")) {
                futures.add(
                        executor.submit(
                                () -> {
                                    if (!release.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("충돌 등록 시작 대기 실패");
                                    try {
                                        repository.registerRuntime(
                                                code,
                                                GradeSchemaIT.HASH,
                                                "{\"v\":\"" + value + "\"}");
                                        return "REGISTERED";
                                    } catch (IllegalStateException failure) {
                                        assertThat(failure)
                                                .hasMessage("RUNTIME_CONFIGURATION_CONFLICT")
                                                .hasNoCause();
                                        return "CONFLICT";
                                    }
                                }));
            }
            release.countDown();
            List<String> outcomes =
                    List.of(
                            futures.get(0).get(20, TimeUnit.SECONDS),
                            futures.get(1).get(20, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("REGISTERED", "CONFLICT");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_runtime WHERE code=?",
                                Integer.class,
                                code))
                .isEqualTo(1);
    }

    @Test
    void unknownPositiveIdAndTransactionScopedLock() throws Exception {
        assertThat(repository.getRuntimeDetail(Long.MAX_VALUE)).isEmpty();
        for (long id : List.of(0L, -1L)) {
            assertThatThrownBy(() -> repository.getRuntimeDetail(id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_RUNTIME_ID");
            assertThatThrownBy(() -> repository.lockRuntime(id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_RUNTIME_ID");
        }
        var row = repository.registerRuntime(code(), GradeSchemaIT.HASH, "{}");
        assertThatThrownBy(() -> repository.lockRuntime(row.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUNTIME_LOCK_REQUIRES_TRANSACTION");
        var transaction =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(
                status -> {
                    assertThat(repository.lockRuntime(Long.MAX_VALUE)).isEmpty();
                    assertThat(repository.lockRuntime(row.id())).contains(row);
                    try (var independent = postgres.createConnection("")) {
                        try (var settings = independent.createStatement()) {
                            settings.execute("SET lock_timeout='100ms'");
                        }
                        try (var update =
                                independent.prepareStatement(
                                        "UPDATE public.grade_runtime SET epoch=epoch+1 WHERE"
                                            + " id=?")) {
                            update.setLong(1, row.id());
                            assertThatThrownBy(update::executeUpdate)
                                    .isInstanceOf(java.sql.SQLException.class)
                                    .satisfies(
                                            error ->
                                                    assertThat(
                                                                    ((java.sql.SQLException) error)
                                                                            .getSQLState())
                                                            .isEqualTo("55P03"));
                        }
                    } catch (java.sql.SQLException failure) {
                        throw new AssertionError("독립 잠금 연결 실패", failure);
                    }
                });
        assertThat(
                        jdbc.update(
                                "UPDATE public.grade_runtime SET epoch=epoch+1 WHERE id=?",
                                row.id()))
                .isEqualTo(1);
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(
                status ->
                        assertThatThrownBy(() -> repository.lockRuntime(row.id()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("RUNTIME_LOCK_REQUIRES_TRANSACTION"));
    }

    @Test
    void databaseJsonErrorsDoNotEchoConfiguration() {
        // PostgreSQL JSONB은 NUL을 지원하지 않는다. 저장 오류의 상세·원인을 외부로 전달하지 않는다.
        String nul = "{\"private\":\"" + "\\" + "u0000\"}";
        assertThatThrownBy(() -> repository.registerRuntime(code(), GradeSchemaIT.HASH, nul))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessage("RUNTIME_STORAGE_FAILURE")
                .hasNoCause();
    }

    /** 공개 가능한 형식의 시험 등록 코드를 생성한다. */
    private static String code() {
        return "R_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
