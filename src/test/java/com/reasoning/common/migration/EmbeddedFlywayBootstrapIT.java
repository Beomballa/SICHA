package com.reasoning.common.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 공개 Java 원본만으로 정상 SQL 이력·Boot 초기화·JDBC 세션을 검사하는 폐기형 PG 시험이다. */
@SpringBootTest
@Testcontainers
@Import(EmbeddedFlywayBootstrapIT.ReadinessConfiguration.class)
class EmbeddedFlywayBootstrapIT extends DatabaseContextTest {
    private static final String IMAGE =
            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1";
    private static final List<String> HASHES =
            List.of(
                    "499026fe40f5559e37cf62b9e2a7b37d06d7e3baebd36a67af585a36a9028e34",
                    "e8cdc9dc3d63fbb74d6a31cf27df71f296ada10827cfcada0de6c5f2e6e63cbe",
                    "ccac2a3309612e2a881602b49a78ee9c0bf8c0ace51d082ede0bf1588df2edfc",
                    "c1bc78575020ca898483607a103d82368f49c9f72b7b07bdc91804337e1db150",
                    "b8ae790ea1692d78873cae524232aeaa8a37b3ecf630076c6d215c29e6f0dc1b",
                    "302e6d9bd54502cb567af0bd81ad318d76d3de32bb3adaa8a1110f11afd7e1fa",
                    "1ee149ec7647fe9269554c7dce414ac2e1460f8a8fcbd64f943a0d0cbc46144e",
                    "b7e69dc4ae299bff4453a2dbf20586a089ff692359ef6cd39674184d92f75093",
                    "9589856583be0521daed389b6a991321877ed0c5e5a84b13bf0ed0b159190d5e",
                    "0b13577cf98cdd89430db3aaf08d3ffa8dcdceaf517f97c0267198a82d12bc96",
                    "3568b3af27a0603ed0826af20cf0a24f90bce97ea8b6694e2c825565b9b2d67d",
                    "774dc683dd4163e822e376b3f80a703405bbb063f6ecbc47c6c359a59ba6e71e",
                    "7fcec0288a7f257b2d8cd7df4ba323a008d3ee00093f80119ff5cd913bec9b4e",
                    "929a5e2f864e61dac27bab3e9a13b2a2cf367d4b8fbdf76962720375d3f92c93",
                    "24c9050424431e6e70f0622c8cc03c9afd638b7f3a8b2c84884793699c25c816",
                    "3e51fefadc803df34939ca740257750b8350a368114914022da09da2bf5c8bcc",
                    "5e1c1f7649856360b3fa9396456cb50210507841fb0cd2f41df02943cfc8a02a",
                    "b030e65b4f3f6a229d95d17c89dd01fbfe29cea79a13b1637334c58216c195c2",
                    "5adb2c16378f7dc8fa01a237d431f05a6fc3e12d968b9a0a87a07be28ad34919",
                    "79232e8dfe5b5ac83369ba8ac9a1a7b009090cb31dad2def463982f76d60a8bc",
                    "911236943b2e10fac1189a501a02b1dd65b039227634291b8c4f0e77b1b11d9a",
                    "bb1852bf9d9cbe150355f678843a128eb293092224b4f50226c26cd9f07e600b",
                    "3d793c8cf4b11865af878bc3a31afe7a0e4e060346c255a25efc418cd4288d0d");

    @Container static final PostgreSQLContainer<?> postgres = database();

    @TempDir Path temporary;
    @Autowired Flyway bootFlyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired EntityManagerFactory entities;
    @Autowired Readiness readiness;

    /** 폐기형 DB와 합성 키만 주입하며 존재하지 않는 SQL 위치로 독립 SQL 의존을 차단한다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add(
                "spring.flyway.locations", () -> "classpath:embedded-bootstrap-no-sql-files");
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 61));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 62));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 63));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    /** 전체 바이트, 독립 reader, 정확한 조회와 접미사 합집합 및 실패 폐쇄를 검사한다. */
    @Test
    void completeCatalogReadersAndIntegrityFailures() throws Exception {
        var entries = OriginalSqlCatalog.entries();
        var provider = new EmbeddedSqlResourceProvider();
        assertThat(entries).hasSize(23);
        assertThat(entries.stream().map(OriginalSqlCatalog.Entry::name).distinct().count())
                .isEqualTo(23);
        int totalBytes = 0;
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            byte[] bytes = entry.sql().getBytes(StandardCharsets.UTF_8);
            totalBytes += bytes.length;
            assertThat(hash(bytes)).isEqualTo(HASHES.get(index));
            assertThat(entry.sql()).doesNotContain("\r", "\uFEFF").endsWith("\n");
            var resource = provider.getResource(entry.name());
            assertThat(resource.getFilename()).isEqualTo(entry.name());
            assertThat(resource.getRelativePath()).isEqualTo(entry.name());
            assertThat(resource.getAbsolutePath()).isEqualTo("db/migration/" + entry.name());
            assertThat(resource.getAbsolutePathOnDisk()).isEmpty();
            try (Reader first = resource.read();
                    Reader second = resource.read()) {
                assertThat(first).isNotSameAs(second);
                assertThat(first.read()).isEqualTo(entry.sql().charAt(0));
                assertThat(text(second)).isEqualTo(entry.sql());
            }
            try (Reader reopened = resource.read()) {
                assertThat(text(reopened)).isEqualTo(entry.sql());
            }
        }
        assertThat(totalBytes).isEqualTo(166621);
        assertThat(provider.getResource("missing.sql")).isNull();
        assertThat(provider.getResource(entries.getFirst().name().toLowerCase())).isNull();
        assertThat(provider.getResource("db/migration/" + entries.getFirst().name())).isNull();
        assertThat(provider.getResource(entries.getFirst().name() + ".conf")).isNull();
        assertThat(provider.getResources("V", new String[] {".sql", "", ".sql"})).hasSize(23);
        assertThat(provider.getResources("V1__", new String[] {".txt", ".sql"})).hasSize(1);
        assertThat(provider.getResources("R", new String[] {""})).isEmpty();
        assertThat(provider.getResources("", new String[] {})).isEmpty();
        assertThat(provider.getResources("", new String[] {".conf"})).isEmpty();
        assertThatThrownBy(() -> provider.getResources("V", new String[] {""}).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> entries.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> OriginalSqlCatalog.validate(entries.subList(0, 16)))
                .isInstanceOf(IllegalStateException.class);
        var duplicate = new ArrayList<>(entries);
        duplicate.set(16, entries.getFirst());
        assertThatThrownBy(() -> OriginalSqlCatalog.validate(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이름");
        var renamed = new ArrayList<>(entries);
        renamed.set(0, new OriginalSqlCatalog.Entry("V1__renamed.sql", entries.getFirst().sql()));
        assertThatThrownBy(() -> OriginalSqlCatalog.validate(renamed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이름");
        var altered = new ArrayList<>(entries);
        altered.set(
                0,
                new OriginalSqlCatalog.Entry(
                        entries.getFirst().name(), entries.getFirst().sql() + "\n"));
        assertThatThrownBy(() -> OriginalSqlCatalog.validate(altered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("해시");
    }

    /** 정상 Boot 시작 뒤 실제 Hibernate 조회와 JDBC 세션 왕복 및 원본 SQL 전체 구조를 비교한다. */
    @Test
    void freshBootSqlHistorySchemaAndSessionReadiness() throws Exception {
        assertThat(readiness.migrations()).isEqualTo(23);
        assertThat(bootFlyway.getConfiguration().getResourceProvider())
                .isInstanceOf(EmbeddedSqlResourceProvider.class);
        var before = history(jdbc);
        assertThat(before).hasSize(23);
        assertThat(bootFlyway.migrate().migrationsExecuted).isZero();
        bootFlyway.validate();
        assertThat(history(jdbc)).isEqualTo(before);
        try (var manager = entities.createEntityManager()) {
            assertThat(
                            ((Number)
                                            manager.createNativeQuery(
                                                            "SELECT count(*) FROM"
                                                                    + " public.grade_term")
                                                    .getSingleResult())
                                    .longValue())
                    .isZero();
        }
        var jdbcSession = sessions.createSession();
        Session session = jdbcSession;
        session.setAttribute("bootstrapProbe", "합성 준비 검사");
        sessions.save(jdbcSession);
        try {
            Session restored = sessions.findById(session.getId());
            assertThat(restored.<String>getAttribute("bootstrapProbe")).isEqualTo("합성 준비 검사");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.spring_session WHERE session_id=?",
                                    Integer.class,
                                    session.getId()))
                    .isEqualTo(1);
        } finally {
            sessions.deleteById(session.getId());
        }
        try (var legacyDatabase = database()) {
            legacyDatabase.start();
            var legacy = legacy(legacyDatabase, "23");
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(23);
            legacy.validate();
            assertSqlIdentity(bootFlyway, legacy);
            assertThat(schema(jdbc)).isEqualTo(schema(jdbc(legacyDatabase)));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON"
                                        + " n.oid=c.connamespace WHERE n.nspname='public' AND"
                                        + " c.contype='f'",
                                Integer.class))
                .isEqualTo(101);
    }

    /** 빈 폐기형 DB에 embedded SQL23을 실제 적용하고 재실행·검증·Boot 이력 정체성을 비교한다. */
    @Test
    void freshEmbeddedTwentyThreeThenZero() {
        try (var database = database()) {
            database.start();
            var embedded = embedded(database);
            assertThat(embedded.migrate().migrationsExecuted).isEqualTo(23);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            assertThat(embedded.migrate().migrationsExecuted).isZero();
            embedded.validate();
            assertSqlIdentity(embedded, bootFlyway);
            assertThat(history(jdbc)).isEqualTo(before);
        }
    }

    /** V22 자체의 원본 이력과 보호 함수를 보존하고 V23 한 건만 적용한다. */
    @Test
    void originalSqlTwentyTwoToEmbeddedTwentyThree() throws Exception {
        try (var database = database()) {
            database.start();
            var historical = legacy(database, "22");
            assertThat(historical.migrate().migrationsExecuted).isEqualTo(22);
            historical.validate();
            assertSqlIdentity(embedded(database, "22"), historical);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT prosrc FROM pg_proc WHERE"
                                            + " oid='public.guard_test_job_source()'::regprocedure",
                                    String.class))
                    .contains("OLD.report_id IS NOT NULL OR OLD.accepted_at IS NOT NULL");
            var current = embedded(database);
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 22)).isEqualTo(before);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "23"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT prosrc FROM pg_proc WHERE"
                                            + " oid='public.guard_test_job_source()'::regprocedure",
                                    String.class))
                    .contains("OLD.report_id IS NOT NULL OR NEW.report_id IS NOT NULL");
        }
    }

    /** V22 초기화·재실행·SQL 정체성은 최신 목표와 분리해 유지한다. */
    @Test
    void freshEmbeddedTwentyTwoThenZero() throws Exception {
        try (var database = database()) {
            database.start();
            var historical = embedded(database, "22");
            assertThat(historical.migrate().migrationsExecuted).isEqualTo(22);
            var before = history(jdbc(database));
            assertThat(historical.migrate().migrationsExecuted).isZero();
            historical.validate();
            assertSqlIdentity(historical, legacy(database, "22"));
            assertThat(history(jdbc(database))).isEqualTo(before);
        }
    }

    /** V19 자체의 초기화·재실행·SQL 정체성을 최신 목표와 분리해 유지한다. */
    @Test
    void freshEmbeddedNineteenThenZero() throws Exception {
        try (var database = database()) {
            database.start();
            var historical = embedded(database, "19");
            assertThat(historical.migrate().migrationsExecuted).isEqualTo(19);
            var before = history(jdbc(database));
            assertThat(historical.migrate().migrationsExecuted).isZero();
            historical.validate();
            assertSqlIdentity(historical, legacy(database, "19"));
            assertThat(history(jdbc(database))).isEqualTo(before);
        }
    }

    /** 실제 V19 이력을 보존하고 V20 한 건만 적용하며 파일·embedded 구조를 대조한다. */
    @Test
    void originalSqlNineteenToEmbeddedTwenty() throws Exception {
        try (var database = database()) {
            database.start();
            var original = legacy(database, "19");
            assertThat(original.migrate().migrationsExecuted).isEqualTo(19);
            var before = history(jdbc(database));
            var current = embedded(database, "20");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc(database)).subList(0, 19)).isEqualTo(before);
            var after = history(jdbc(database));
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "20"));
            assertThat(history(jdbc(database))).isEqualTo(after);
        }
    }

    /** 정상 독립 SQL V20 이력을 보존하고 V21 한 건만 embedded로 적용한다. */
    @Test
    void originalSqlTwentyToEmbeddedTwentyOne() throws Exception {
        try (var database = database()) {
            database.start();
            var original = legacy(database, "20");
            assertThat(original.migrate().migrationsExecuted).isEqualTo(20);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            var current = embedded(database, "21");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 20)).isEqualTo(before);
            var after = history(jdbc);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "21"));
            assertThat(history(jdbc)).isEqualTo(after);
        }
    }

    /** SQL V16에서 embedded V19까지의 기존 경로를 명시적 목표로 보존한 뒤 V20을 적용한다. */
    @Test
    void originalSqlSixteenToEmbeddedTwenty() throws Exception {
        try (var database = database()) {
            database.start();
            var legacy = legacy(database, "16");
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(16);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            var embedded = embedded(database, "19");
            assertThat(embedded.migrate().migrationsExecuted).isEqualTo(3);
            assertThat(history(jdbc).subList(0, 16)).isEqualTo(before);
            assertThat(embedded.migrate().migrationsExecuted).isZero();
            embedded.validate();
            assertSqlIdentity(embedded, legacy(database, "19"));
            var current = embedded(database, "20");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "20"));
        }
    }

    /** 이미 정상 SQL V18인 DB는 V19·V20으로 전환되며 변조된 역사적 텍스트는 검증에서 실패한다. */
    @Test
    void originalSqlEighteenSwitchAndChecksumFailure() throws Exception {
        try (var database = database()) {
            database.start();
            var legacy = legacy(database, "18");
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(18);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            var embedded = embedded(database, "19");
            assertThat(embedded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 18)).isEqualTo(before);
            before = history(jdbc);
            assertThat(embedded.migrate().migrationsExecuted).isZero();
            embedded.validate();
            assertSqlIdentity(embedded, legacy(database, "19"));
            var current = embedded(database, "20");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "20"));
            before = history(jdbc);
            assertThat(history(jdbc)).isEqualTo(before);
            Path altered = materialize();
            var first = OriginalSqlCatalog.entries().getFirst();
            Files.writeString(
                    altered.resolve(first.name()),
                    first.sql() + "-- altered historical text\n",
                    StandardCharsets.UTF_8);
            var tampered = configuration(database).locations("filesystem:" + altered).load();
            assertThatThrownBy(tampered::validate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum mismatch");
            assertThatThrownBy(tampered::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum mismatch");
            assertThat(history(jdbc)).isEqualTo(before);

            var changedReader =
                    configuration(database).resourceProvider(alteredEmbeddedReader()).load();
            assertThatThrownBy(changedReader::validate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum mismatch");
            assertThatThrownBy(changedReader::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum mismatch");
            assertThat(history(jdbc)).isEqualTo(before);
        }
    }

    /** 빈 물리 위치에서도 기본 SQL 실행 오류가 논리 파일명과 정확한 문장 줄을 보존하는지 검사한다. */
    @Test
    void genuineSqlFailureHasLogicalFilenameAndLine() throws Exception {
        try (var database = database()) {
            database.start();
            var jdbc = jdbc(database);
            Path emptySql = Files.createTempDirectory(temporary, "empty-diagnostic-sql-");
            assertThat(
                            configuration(database)
                                    .locations("filesystem:" + emptySql)
                                    .load()
                                    .migrate()
                                    .migrationsExecuted)
                    .isZero();
            jdbc.execute("CREATE TABLE public.admin_account (id bigint)");
            var flyway = embedded(database);
            assertThatThrownBy(flyway::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("V1__h0_admin_auth.sql")
                    .hasMessageContaining("Line       : 1")
                    .hasMessageContaining("db/migration/V1__h0_admin_auth.sql ()");
        }
    }

    /** 폐기형 PG16.10의 새 수명을 만든다. 호출자가 시작과 종료를 소유한다. */
    private static PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    }

    /**
     * 폐기형 DB의 기본 Flyway 구성을 만든다.
     *
     * @param database null이 아닌 폐기형 DB
     * @return 기본 resolver를 보존한 구성
     */
    private static FluentConfiguration configuration(PostgreSQLContainer<?> database) {
        return Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    /**
     * 전체 embedded SQL 공급원만 연결한다.
     *
     * @param database 시작된 폐기형 DB
     * @return 독립 파일 위치가 없는 전체 embedded SQL Flyway
     */
    private static Flyway embedded(PostgreSQLContainer<?> database) {
        return embedded(database, "23");
    }

    /** 역사적 목표도 동일한 embedded 공급원으로 실행한다. */
    private static Flyway embedded(PostgreSQLContainer<?> database, String target) {
        return configuration(database)
                .locations("classpath:embedded-bootstrap-no-sql-files")
                .resourceProvider(new EmbeddedSqlResourceProvider())
                .target(target)
                .load();
    }

    /**
     * 파일 정체성은 그대로 두고 첫 embedded reader에 줄끝 이외 주석만 추가하는 음성 fixture다. 생산 제공자나 원본 목록에는 대체 입력을 허용하지
     * 않는다.
     *
     * @return 기본 SQL checksum 검증에 변조된 문자 원문을 전달하는 시험 전용 제공자
     */
    private static ResourceProvider alteredEmbeddedReader() {
        var original = new EmbeddedSqlResourceProvider();
        var first = OriginalSqlCatalog.entries().getFirst();
        LoadableResource changed =
                new EmbeddedSqlResourceProvider.EmbeddedSqlResource(
                        first.name(), first.sql() + "-- altered embedded reader\n");

        return new ResourceProvider() {
            @Override
            public LoadableResource getResource(String name) {
                return first.name().equals(name) ? changed : original.getResource(name);
            }

            @Override
            public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
                return original.getResources(prefix, suffixes).stream()
                        .map(
                                resource ->
                                        first.name().equals(resource.getFilename())
                                                ? changed
                                                : resource)
                        .toList();
            }
        };
    }

    /**
     * 원본 Java 바이트를 owner 전용 임시 SQL로 물질화한 뒤 정상 SQL discovery를 사용한다.
     *
     * @param database 시작된 폐기형 DB
     * @param target 정상 SQL 목표 버전 16, 18, 19, 20, 21, 22 또는 23
     * @return 기본 파일 SQL Flyway
     * @throws Exception 임시 파일 쓰기 실패
     */
    private Flyway legacy(PostgreSQLContainer<?> database, String target) throws Exception {
        return configuration(database)
                .locations("filesystem:" + materialize())
                .target(target)
                .load();
    }

    /**
     * 정상 SQL 참조용 원본 바이트를 임시 파일로 복사한다.
     *
     * @return 독립 원본 해시를 대조한 owner 전용 임시 SQL 디렉터리
     * @throws Exception 쓰기·해시 실패
     */
    private Path materialize() throws Exception {
        Path directory = Files.createTempDirectory(temporary, "original-sql-");
        Files.setPosixFilePermissions(
                directory,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        var entries = OriginalSqlCatalog.entries();
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            byte[] bytes = entry.sql().getBytes(StandardCharsets.UTF_8);
            assertThat(hash(bytes)).isEqualTo(HASHES.get(index));
            Path file = Files.createFile(directory.resolve(entry.name()));
            Files.setPosixFilePermissions(
                    file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.write(file, bytes);
        }
        return directory;
    }

    /**
     * 폐기형 DB 전용 연결 도구를 만든다.
     *
     * @param database 시작된 폐기형 DB
     * @return 해당 DB 전용 JDBC 도구
     */
    private static JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    /**
     * 이력 재작성 검사용 실제 행 전체를 읽는다.
     *
     * @param jdbc 폐기형 DB 도구
     * @return 시간·실행자까지 포함한 실제 전체 이력
     */
    private static List<Map<String, Object>> history(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
    }

    /**
     * 기본 SQL resolver가 계산한 타입·script·description·checksum을 양쪽에서 비교한다.
     *
     * @param embedded 정상 embedded Flyway
     * @param legacy 정상 원본 파일 SQL Flyway
     */
    private static void assertSqlIdentity(Flyway embedded, Flyway legacy) {
        var actual = embedded.info().all();
        var expected = legacy.info().all();
        assertThat(actual).hasSameSizeAs(expected);
        for (int index = 0; index < actual.length; index++) {
            assertThat(actual[index].getType()).isEqualTo(CoreMigrationType.SQL);
            assertThat(actual[index].getScript())
                    .isEqualTo(expected[index].getScript())
                    .isEqualTo(OriginalSqlCatalog.entries().get(index).name());
            assertThat(actual[index].getDescription()).isEqualTo(expected[index].getDescription());
            assertThat(actual[index].getChecksum())
                    .isNotNull()
                    .isEqualTo(expected[index].getChecksum());
            assertThat(actual[index].getVersion()).isEqualTo(expected[index].getVersion());
        }
    }

    /**
     * OID·실행 시각을 제외한 public 구조 전체를 비교한다. PL/pgSQL 본문과 주석도 포함한다.
     *
     * @param jdbc 폐기형 DB 도구
     * @return 순서가 고정된 테이블·열·제약·인덱스·함수·트리거 구조
     */
    private static List<List<Map<String, Object>>> schema(JdbcTemplate jdbc) {
        return List.of(
                jdbc.queryForList(
                        "SELECT c.relname,c.relkind,obj_description(c.oid,'pg_class') AS comment"
                            + " FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE"
                            + " n.nspname='public' ORDER BY c.relname"),
                jdbc.queryForList(
                        "SELECT c.relname,a.attname,format_type(a.atttypid,a.atttypmod) AS"
                            + " type,a.attnotnull,a.attidentity,pg_get_expr(d.adbin,d.adrelid) AS"
                            + " default_value,col_description(c.oid,a.attnum) AS comment FROM"
                            + " pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN"
                            + " pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attrdef d ON"
                            + " d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE n.nspname='public'"
                            + " AND a.attnum>0 AND NOT a.attisdropped ORDER BY c.relname,a.attnum"),
                jdbc.queryForList(
                        "SELECT c.conname,c.contype,pg_get_constraintdef(c.oid) AS"
                            + " definition,obj_description(c.oid,'pg_constraint') AS comment FROM"
                            + " pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE"
                            + " n.nspname='public' ORDER BY c.conname"),
                jdbc.queryForList(
                        "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE"
                                + " schemaname='public' ORDER BY indexname"),
                jdbc.queryForList(
                        "SELECT p.proname,l.lanname,p.prosecdef,pg_get_functiondef(p.oid) AS"
                            + " definition,obj_description(p.oid,'pg_proc') AS comment FROM pg_proc"
                            + " p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON"
                            + " l.oid=p.prolang WHERE n.nspname='public' ORDER BY p.proname"),
                jdbc.queryForList(
                        "SELECT c.relname,t.tgname,pg_get_triggerdef(t.oid) AS"
                            + " definition,obj_description(t.oid,'pg_trigger') AS comment FROM"
                            + " pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n"
                            + " ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT"
                            + " t.tgisinternal ORDER BY c.relname,t.tgname"));
    }

    /**
     * 독립 기대값 비교용 원본 바이트 해시를 계산한다.
     *
     * @param bytes 원본 UTF-8 바이트
     * @return 독립 SHA-256 소문자 hex
     * @throws Exception 알고리즘 부재
     */
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * reader의 남은 문자를 읽는다. 종료는 호출자가 담당한다.
     *
     * @param reader 열린 자원 reader
     * @return 남은 전체 문자
     * @throws Exception 읽기 실패
     */
    private static String text(Reader reader) throws Exception {
        var output = new StringWriter();
        reader.transferTo(output);
        return output.toString();
    }

    record Readiness(int migrations) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadinessConfiguration {
        /**
         * 정상 초기화 의존 bean 생성 시점에서 SQL23과 세션 테이블 준비를 관측한다.
         *
         * @param jdbc 정상 Boot가 주입한 폐기형 DB 도구
         * @return 생성 시점의 실제 성공 마이그레이션 수
         */
        @Bean
        @DependsOnDatabaseInitialization
        Readiness readiness(JdbcTemplate jdbc) {
            int migrations =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM public.flyway_schema_history WHERE success",
                            Integer.class);
            assertThat(migrations).isEqualTo(23);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.spring_session", Integer.class))
                    .isZero();
            return new Readiness(migrations);
        }
    }
}
