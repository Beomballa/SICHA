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
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
                    "3d793c8cf4b11865af878bc3a31afe7a0e4e060346c255a25efc418cd4288d0d",
                    "e3d9c4475c95fc3b967b03113ea2300cc085b4a1f003afc11595f9d505874fcb",
                    "6f3ac02bee8feb2ae8b13388726dc84f7c4057d1a046ad1a46cb5b35032db33b",
                    "94b9046b71619e4658fa387a6b4615434a69c2aaebc49f2dadbd46f1808c87a6",
                    "15f4d8b64ff2379103839fb94cf7bc53885135690050544b87a200dfe5329971",
                    "42b7fc6fbeea20ed78b0ef30a38bda8f475781f533850eca1da31b024eda9274",
                    "883d489c71b18c4ff5eccaf859266ccda22ee95f035ceafd9541dcbd4b6695f2",
                    "f273e3746bbfc75084c5bdc416ae1102bfa210d37373de6b007cbc64ae5116cc",
                    "3412db1aaf098ad85048c932efcaaf03fa96a4d220882181ce26264e28b34a63",
                    "74ee1843f82319721a112dfefe7a7e0591e0fc3f1ee8c53f557c032c5f1f9ce5",
                    "f25744f03ef1f0ee9a244de0dda2c643339a72053dca606492bc3462ceff1f4f",
                    "fe6811a1f8821d44b90ee61ef831e4f6590394efb2332b7a3ac1390bc5c712ef",
                    "6418890ec0c742701a76e22631d3e7003d4488086fd77a67188e7130438b7bdc",
                    "7d91dbecf364945287508b603d4fa4a713f2915ed97745568925b263a8d5875e",
                    "baa0270d176c5bed3c13b76bcf705b430f4ca4a0aa5ee315f23261012a580d2b",
                    "63f6a249d96edc6ac8667911b717c9b6b7a8fdc6f38f2cd7c65e6d2ec008af76",
                    "dd0e83649de49ca6d1262ab3e4ba3d89bbf87546b3157cd5b8827d526f771ca2",
                    "a5773a08a2f56802175dc1730d79749e894ce6bdc238ca6de07e99458aee1a8d",
                    "20ec317a4f687bf024f893c8f7fb6ad5e16534639b6080298a69ce78ef9ce6ef",
                    "f4f7b84d62cb8c769cf0af9ae18999bfa705c1817de8e57f4b80d768abfa0eab",
                    "6c6411750cf9cdbbf5891bb091302bb3ad012a339db67026d8d0b04fd310d0dc",
                    "ff46ab46fa83c3f90f5f01171f21e6a68e60c470eaa864ebc5d41f2eb312fea0");

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
        assertThat(entries).hasSize(44);
        assertThat(entries.stream().map(OriginalSqlCatalog.Entry::name).distinct().count())
                .isEqualTo(44);
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
        assertThat(totalBytes).isEqualTo(384523);
        assertThat(provider.getResource("missing.sql")).isNull();
        assertThat(provider.getResource(entries.getFirst().name().toLowerCase())).isNull();
        assertThat(provider.getResource("db/migration/" + entries.getFirst().name())).isNull();
        assertThat(provider.getResource(entries.getFirst().name() + ".conf")).isNull();
        assertThat(provider.getResources("V", new String[] {".sql", "", ".sql"})).hasSize(44);
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
        assertThat(readiness.migrations()).isEqualTo(44);
        assertThat(bootFlyway.getConfiguration().getResourceProvider())
                .isInstanceOf(EmbeddedSqlResourceProvider.class);
        var before = history(jdbc);
        assertThat(before).hasSize(44);
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
            var legacy = legacy(legacyDatabase, "44");
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(44);
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
                .isEqualTo(180);
        exportGameSchemaMetadata();
    }

    /** 검증된 폐기 DB의 구조만 로컬 엑셀 대조 자료로 내보낸다. 업무 행이나 자격은 읽지 않는다. */
    private void exportGameSchemaMetadata() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var output =
                mapper.createObjectNode()
                        .put(
                                "migrationVersion",
                                jdbc.queryForObject(
                                        "SELECT max(version::integer) FROM flyway_schema_history"
                                                + " WHERE success",
                                        Integer.class));
        var tables = output.putObject("tables");
        for (String table :
                List.of(
                        "game_room",
                        "game_member",
                        "game_report",
                        "game_action",
                        "game_audit",
                        "story_release",
                        "release_solo_feedback",
                        "release_human_review",
                        "release_evidence",
                        "release_evidence_item",
                        "play_record",
                        "game_hint",
                        "game_clue_read",
                        "game_invite",
                        "blind_assessment",
                        "test_member",
                        "test_exposure",
                        "test_retention",
                        "play_test",
                        "privacy_target",
                        "privacy_erase")) {
            var target = tables.putObject(table);
            target.set(
                    "columns",
                    mapper.readTree(
                            jdbc.queryForObject(
                                    """
                                    SELECT jsonb_agg(jsonb_build_object('name',a.attname,'type',format_type(a.atttypid,a.atttypmod),
                                      'nullable',NOT a.attnotnull,'default',pg_get_expr(d.adbin,d.adrelid),
                                      'identity',a.attidentity,'description',col_description(a.attrelid,a.attnum)) ORDER BY a.attnum)::text
                                    FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                                    WHERE a.attrelid=?::regclass AND a.attnum>0 AND NOT a.attisdropped
                                    """,
                                    String.class,
                                    "public." + table)));
            target.set(
                    "constraints",
                    mapper.readTree(
                            jdbc.queryForObject(
                                    """
                                    SELECT COALESCE(jsonb_agg(jsonb_build_object('name',conname,'kind',contype,
                                      'definition',pg_get_constraintdef(oid,true)) ORDER BY conname),'[]'::jsonb)::text
                                    FROM pg_constraint WHERE conrelid=?::regclass
                                    """,
                                    String.class,
                                    "public." + table)));
            target.set(
                    "indexes",
                    mapper.readTree(
                            jdbc.queryForObject(
                                    """
                                    SELECT COALESCE(jsonb_agg(jsonb_build_object('name',c.relname,'definition',pg_get_indexdef(i.indexrelid),
                                      'unique',i.indisunique,'primary',i.indisprimary,'predicate',pg_get_expr(i.indpred,i.indrelid))
                                      ORDER BY c.relname),'[]'::jsonb)::text
                                    FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE i.indrelid=?::regclass
                                    """,
                                    String.class,
                                    "public." + table)));
            assertThat(target.path("columns").isArray()).isTrue();
            assertThat(target.path("columns").size()).isPositive();
        }
        Path destination = Path.of("build/local-grading/game-schema-current.json");
        Files.createDirectories(destination.getParent());
        Files.writeString(
                destination,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output),
                StandardCharsets.UTF_8);
    }

    /** 빈 폐기형 DB에 embedded SQL44를 실제 적용하고 재실행·검증·Boot 이력 정체성을 비교한다. */
    @Test
    void freshEmbeddedFortyFourThenZero() {
        try (var database = database()) {
            database.start();
            var embedded = embedded(database);
            assertThat(embedded.migrate().migrationsExecuted).isEqualTo(44);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            assertThat(embedded.migrate().migrationsExecuted).isZero();
            embedded.validate();
            assertSqlIdentity(embedded, bootFlyway);
            assertThat(history(jdbc)).isEqualTo(before);
        }
    }

    /** V42 감사·PRIMARY 권한·공통 가드는 보존하고 V43 한 건만 전진한다. */
    @Test
    void originalFortyTwoToAuditFortyThreePreservesHistoryAndPrimaryAuthority() throws Exception {
        try (var database = database()) {
            database.start();
            assertThat(embedded(database, "42").migrate().migrationsExecuted).isEqualTo(42);
            var jdbc = jdbc(database);
            long policy = syntheticMemberErasurePolicy(jdbc);
            long member = syntheticErasureMember(jdbc, policy);
            long root =
                    jdbc.queryForObject(
                            """
                            INSERT INTO public.privacy_erase(erase_key,member_id,policy_id,reason_code,locator_cipher,
                              receipt_hash,receipt_until,backup_until)
                            VALUES (gen_random_uuid(),?,?,'ACCOUNT_REQUEST',decode(repeat('01',32),'hex'),repeat('a',64),
                              clock_timestamp()+interval '89 days',clock_timestamp()+interval '34 days') RETURNING id
                            """,
                            Long.class,
                            member,
                            policy);
            long target =
                    jdbc.queryForObject(
                            """
                            INSERT INTO public.privacy_target(erase_id,target_key,kind,source_kind,member_id,locator_cipher)
                            VALUES (?,gen_random_uuid(),'PRIMARY','MEMBER',?,decode(repeat('02',32),'hex')) RETURNING id
                            """,
                            Long.class,
                            root,
                            member);
            jdbc.update(
                    "INSERT INTO public.privacy_target(erase_id,target_key,kind,source_kind) VALUES"
                        + " (?,gen_random_uuid(),'BACKUP','NONE'),(?,gen_random_uuid(),'RESTORE_GUARD','NONE')",
                    root,
                    root);
            jdbc.update(
                    """
                    WITH clock AS (SELECT clock_timestamp() AS instant)
                    INSERT INTO public.member_auth_audit(event_key,member_id,request_id,action,result_code,created_at,purge_at,auth_rev)
                    SELECT gen_random_uuid(),?,gen_random_uuid(),'LOGIN_LOCAL','DENIED',instant-interval '2 hours',
                      instant+deadline,0 FROM clock CROSS JOIN (VALUES (interval '-1 second'),(interval '1 hour')) AS deadlines(deadline)
                    """,
                    member);
            jdbc.update(
                    "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                            + " WHERE id=?",
                    member);
            jdbc.update(
                    "UPDATE public.privacy_erase SET"
                        + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=3,state='PROCESSING'"
                        + " WHERE id=?",
                    root);
            jdbc.update(
                    "UPDATE public.privacy_target SET"
                        + " state='RUNNING',lease_until=clock_timestamp()+interval '1 hour' WHERE"
                        + " id=?",
                    target);
            String role = "primary_v43_" + UUID.randomUUID().toString().replace("-", "");
            String password = "synthetic-primary-" + UUID.randomUUID();
            jdbc.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            jdbc.execute(
                    "GRANT EXECUTE ON FUNCTION public.consume_privacy_primary(bigint) TO " + role);
            var limited =
                    new JdbcTemplate(
                            new DriverManagerDataSource(database.getJdbcUrl(), role, password));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT NOT r.rolsuper AND NOT EXISTS (SELECT 1 FROM"
                                        + " pg_auth_members m WHERE m.member=r.oid) FROM pg_roles r"
                                        + " WHERE r.rolname=?",
                                    Boolean.class,
                                    role))
                    .isTrue();
            assertThat(limited.queryForObject("SELECT current_user", String.class)).isEqualTo(role);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE"
                                        + " oid='public.consume_privacy_primary(bigint)'::regprocedure",
                                    String.class))
                    .isNotEqualTo(role);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT"
                                        + " has_table_privilege(?,'public.member_auth_audit','DELETE')",
                                    Boolean.class,
                                    role))
                    .isFalse();
            assertThatThrownBy(() -> limited.execute("DELETE FROM public.member_auth_audit"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            var before = history(jdbc);
            var audits = jdbc.queryForList("SELECT * FROM public.member_auth_audit");
            assertThat(audits).hasSize(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.member_auth_audit WHERE"
                                            + " member_id=? AND purge_at<=clock_timestamp()",
                                    Integer.class,
                                    member))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.member_auth_audit WHERE"
                                            + " member_id=? AND purge_at>clock_timestamp()",
                                    Integer.class,
                                    member))
                    .isEqualTo(1);
            String authority =
                    """
                    SELECT proname,proowner,proacl::text,prosrc FROM pg_proc WHERE oid IN
                    ('public.consume_privacy_primary(bigint)'::regprocedure,
                     'public.guard_privacy_primary_delete()'::regprocedure,
                     'public.privacy_primary_delete_allowed(bigint,bigint)'::regprocedure) ORDER BY proname
                    """;
            var primary = jdbc.queryForList(authority);
            var current = embedded(database);
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 42)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM public.member_auth_audit"))
                    .isEqualTo(audits);
            assertThat(jdbc.queryForList(authority)).isEqualTo(primary);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT"
                                        + " has_function_privilege(?,'public.consume_privacy_primary(bigint)','EXECUTE')",
                                    Boolean.class,
                                    role))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT"
                                        + " has_function_privilege(?,'public.consume_member_auth_audit_expiry(integer)','EXECUTE')",
                                    Boolean.class,
                                    role))
                    .isFalse();
            assertThat(
                            limited.queryForObject(
                                    "SELECT public.consume_privacy_primary(?)",
                                    Boolean.class,
                                    target))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.member_auth_audit WHERE"
                                            + " member_id=?",
                                    Integer.class,
                                    member))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.member_account WHERE id=?",
                                    Integer.class,
                                    member))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.member_auth_audit_cleanup",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT state,result_code,member_id FROM public.privacy_target"
                                            + " WHERE id=?",
                                    target))
                    .containsEntry("state", "COMPLETE")
                    .containsEntry("result_code", "PHYSICAL_REMOVED")
                    .containsEntry("member_id", null);
            assertThat(jdbc.queryForList(authority)).isEqualTo(primary);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
        }
    }

    /** V39의 과거 노출·NULL 출처와 원본 이력을 바꾸지 않고 V40 보호 함수만 전진한다. */
    @Test
    void originalThirtyNineToExposureFortyPreservesHistory() {
        try (var database = database()) {
            database.start();
            var previous = embedded(database, "39");
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(39);
            var jdbc = jdbc(database);
            long owner =
                    jdbc.queryForObject(
                            "INSERT INTO admin_account(account_key) VALUES (gen_random_uuid())"
                                    + " RETURNING id",
                            Long.class);
            long story =
                    jdbc.queryForObject(
                            "INSERT INTO story(code,owner_id) VALUES ('V40_KEEP',?) RETURNING id",
                            Long.class,
                            owner);
            long member =
                    jdbc.queryForObject(
                            "INSERT INTO member_account(member_key,state) VALUES"
                                    + " (gen_random_uuid(),'ACTIVE') RETURNING id",
                            Long.class);
            jdbc.update(
                    "INSERT INTO test_exposure(story_id,member_id) VALUES (?,?)", story, member);
            var before = history(jdbc);
            var exposure = jdbc.queryForList("SELECT * FROM test_exposure");
            var current = embedded(database, "40");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 39)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM test_exposure")).isEqualTo(exposure);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT prosrc FROM pg_proc WHERE"
                                            + " oid='public.guard_test_exposure()'::regprocedure",
                                    String.class))
                    .contains("pg_catalog.pg_trigger_depth()>1");
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
        }
    }

    /** 합성 관계 fixture의 V40 종료 시계만 V41로 전진한다. 실제 평가 자격·READY의 증거가 아니다. */
    @Test
    void originalFortyToRetentionFortyOnePreservesHistoryAndOriginalDeadlines() throws Exception {
        try (var database = database()) {
            database.start();
            assertThat(embedded(database, "40").migrate().migrationsExecuted).isEqualTo(40);
            var jdbc = jdbc(database);
            var fixture = syntheticRetentionFixture(jdbc);
            var before = history(jdbc);
            var preserved =
                    jdbc.queryForList(
                            "SELECT * FROM test_retention WHERE test_id IN (?,?) ORDER BY test_id",
                            fixture.original(),
                            fixture.selected());

            var current = embedded(database, "41");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 40)).isEqualTo(before);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT * FROM test_retention WHERE test_id IN (?,?) ORDER BY"
                                            + " test_id",
                                    fixture.original(),
                                    fixture.selected()))
                    .isEqualTo(preserved);
            assertThat(
                            jdbc.queryForObject(
                                    """
                                    SELECT count(*) FROM test_retention r JOIN play_test t ON t.id=r.test_id
                                    WHERE t.id IN (?,?) AND r.raw_until=t.ended_at+interval '90 days'
                                      AND r.max_until=t.ended_at+interval '365 days' AND r.selected_at IS NULL
                                    """,
                                    Integer.class,
                                    fixture.recent(),
                                    fixture.expired()))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    """
                                    SELECT r.raw_until<now() AND r.max_until<now() FROM test_retention r
                                    WHERE r.test_id=?
                                    """,
                                    Boolean.class,
                                    fixture.expired()))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    """
                                    SELECT raw_until IS NULL AND max_until IS NULL AND selected_at IS NULL
                                    FROM test_retention WHERE test_id=?
                                    """,
                                    Boolean.class,
                                    fixture.waiting()))
                    .isTrue();
            var migrated = history(jdbc);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertThat(history(jdbc)).isEqualTo(migrated);
        }
    }

    /** V41의 실제 삭제 소비자가 남긴 NULL 출처 영수증을 소급 추측하지 않는다. 합성 관계이며 자격·READY 증거가 아니다. */
    @Test
    void originalFortyOneToPublicErasureFortyTwoPreservesPhysicalReceipts() throws Exception {
        try (var database = database()) {
            database.start();
            assertThat(embedded(database, "41").migrate().migrationsExecuted).isEqualTo(41);
            var jdbc = jdbc(database);
            long policy = syntheticMemberErasurePolicy(jdbc);
            long owner =
                    jdbc.queryForObject(
                            "SELECT owner_id FROM privacy_policy WHERE id=?", Long.class, policy);
            long story =
                    jdbc.queryForObject(
                            "INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id",
                            Long.class,
                            "ERASE_SYNTHETIC",
                            owner);
            long version =
                    jdbc.queryForObject(
                            """
                            INSERT INTO story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                            VALUES (?,1,'합성 삭제 관계','ERASE_SYNTHETIC',?,?) RETURNING id
                            """,
                            Long.class,
                            story,
                            owner,
                            owner);
            jdbc.update(
                    "INSERT INTO story_role(version_id,code,name) VALUES "
                            + "(?,'R1','합성 역할 1'),(?,'R2','합성 역할 2')",
                    version,
                    version);
            jdbc.update(
                    "INSERT INTO story_pair(version_id,role_a,role_b) VALUES (?,'R1','R2')",
                    version);
            long snapshot =
                    jdbc.queryForObject(
                            """
                            INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                            VALUES (?,0,'{}',?,?) RETURNING id
                            """,
                            Long.class,
                            version,
                            UUID.randomUUID(),
                            owner);
            long runtime =
                    jdbc.queryForObject(
                            """
                            INSERT INTO grade_runtime(code,config_hash,config_data,state)
                            VALUES (?,repeat('a',64),'{}','AVAILABLE') RETURNING id
                            """,
                            Long.class,
                            "ERASE_" + UUID.randomUUID().toString().replace("-", ""));

            long completedMember = syntheticErasureMember(jdbc, policy);
            long completedTest =
                    erasureWaitingTest(jdbc, completedMember, version, snapshot, runtime, owner);
            var completed = sealedErasure(jdbc, completedMember, completedTest, policy);
            jdbc.update(
                    "UPDATE member_account SET state='DELETED',deleted_at=clock_timestamp() WHERE"
                            + " id=?",
                    completedMember);
            jdbc.update(
                    "UPDATE privacy_erase SET"
                        + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=4,state='PROCESSING'"
                        + " WHERE id=?",
                    completed.root());
            jdbc.update(
                    "UPDATE privacy_target SET state='RUNNING',"
                            + "lease_until=clock_timestamp()+interval '1 hour' WHERE id IN (?,?)",
                    completed.testTarget(),
                    completed.memberTarget());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT consume_privacy_primary(?)",
                                    Boolean.class,
                                    completed.testTarget()))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT consume_privacy_primary(?)",
                                    Boolean.class,
                                    completed.memberTarget()))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM play_test WHERE id=?",
                                    Integer.class,
                                    completedTest))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM member_account WHERE id=?",
                                    Integer.class,
                                    completedMember))
                    .isZero();

            long liveMember = syntheticErasureMember(jdbc, policy);
            long liveTest = erasureWaitingTest(jdbc, liveMember, version, snapshot, runtime, owner);
            var pending = sealedErasure(jdbc, liveMember, liveTest, policy);
            jdbc.update(
                    "UPDATE member_account SET state='DELETED',deleted_at=clock_timestamp() WHERE"
                            + " id=?",
                    liveMember);
            jdbc.update(
                    "UPDATE privacy_erase SET"
                        + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=4,state='PROCESSING'"
                        + " WHERE id=?",
                    pending.root());
            var before = history(jdbc);
            var receipts =
                    jdbc.queryForList(
                            "SELECT * FROM privacy_target WHERE id IN (?,?) ORDER BY id",
                            completed.testTarget(),
                            completed.memberTarget());
            var rootReceipt =
                    jdbc.queryForMap("SELECT * FROM privacy_erase WHERE id=?", completed.root());
            assertThat(receipts)
                    .allSatisfy(
                            row -> {
                                assertThat(row.get("state")).isEqualTo("COMPLETE");
                                assertThat(row.get("result_code")).isEqualTo("PHYSICAL_REMOVED");
                                assertThat(row.get("confirmed_at")).isNotNull();
                                assertThat(row.get("member_id")).isNull();
                                assertThat(row.get("test_id")).isNull();
                            });

            var current = embedded(database, "42");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 41)).isEqualTo(before);
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT"
                                        + " id,erase_key,member_id,state,primary_done_at,receipt_hash,receipt_until"
                                        + " FROM privacy_erase WHERE id=?",
                                    completed.root()))
                    .containsEntry("member_id", null)
                    .containsEntry("state", rootReceipt.get("state"))
                    .containsEntry("primary_done_at", rootReceipt.get("primary_done_at"))
                    .containsEntry("receipt_hash", rootReceipt.get("receipt_hash"))
                    .containsEntry("receipt_until", rootReceipt.get("receipt_until"));
            for (var receipt : receipts) {
                var updated =
                        jdbc.queryForMap(
                                "SELECT * FROM privacy_target WHERE id=?", receipt.get("id"));
                assertThat(updated)
                        .containsAllEntriesOf(receipt)
                        .containsEntry("source_kind", "UNKNOWN");
            }
            assertThat(
                            jdbc.queryForList(
                                    "SELECT kind,source_kind FROM privacy_target "
                                            + "WHERE erase_id=? ORDER BY id",
                                    pending.root()))
                    .extracting(row -> row.get("source_kind"))
                    .containsExactly("MEMBER", "TEST", "NONE", "NONE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM privacy_target WHERE erase_id=? "
                                            + "AND ((source_kind='MEMBER' AND member_id=?) OR "
                                            + "(source_kind='TEST' AND test_id=?))",
                                    Integer.class,
                                    pending.root(),
                                    liveMember,
                                    liveTest))
                    .isEqualTo(2);
            var migrated = history(jdbc);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertThat(history(jdbc)).isEqualTo(migrated);
        }
    }

    /** 봉인 대상 접수 ID와 실재 회원·TEST PRIMARY 행 ID의 묶음이다. */
    private record ErasureTargets(long root, long memberTarget, long testTarget) {}

    /** V41 의존 TEST 영수증과 사전 부여된 실행 권한을 V42에서 끝까지 소비한다. 합성 관계는 READY 근거가 아니다. */
    @Test
    void originalFortyOneDependentTestAndExecutorGrantSurviveFortyTwo() throws Exception {
        try (var database = database()) {
            database.start();
            assertThat(embedded(database, "41").migrate().migrationsExecuted).isEqualTo(41);
            var jdbc = jdbc(database);
            long policy = syntheticMemberErasurePolicy(jdbc);
            long admin =
                    jdbc.queryForObject(
                            "SELECT owner_id FROM privacy_policy WHERE id=?", Long.class, policy);
            long story =
                    jdbc.queryForObject(
                            "INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id",
                            Long.class,
                            "ERASE_SYNTHETIC",
                            admin);
            long version =
                    jdbc.queryForObject(
                            """
                            INSERT INTO story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                            VALUES (?,1,'합성 의존 관계','ERASE_SYNTHETIC',?,?) RETURNING id
                            """,
                            Long.class,
                            story,
                            admin,
                            admin);
            jdbc.update(
                    "INSERT INTO story_role(version_id,code,name) VALUES "
                            + "(?,'R1','합성 역할 1'),(?,'R2','합성 역할 2')",
                    version,
                    version);
            jdbc.update(
                    "INSERT INTO story_pair(version_id,role_a,role_b) VALUES (?,'R1','R2')",
                    version);
            long snapshot =
                    jdbc.queryForObject(
                            """
                            INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                            VALUES (?,0,'{}',?,?) RETURNING id
                            """,
                            Long.class,
                            version,
                            UUID.randomUUID(),
                            admin);
            long runtime =
                    jdbc.queryForObject(
                            """
                            INSERT INTO grade_runtime(code,config_hash,config_data,state)
                            VALUES (?,repeat('a',64),'{}','AVAILABLE') RETURNING id
                            """,
                            Long.class,
                            "ERASE_" + UUID.randomUUID().toString().replace("-", ""));
            long first = syntheticErasureMember(jdbc, policy);
            long second = syntheticErasureMember(jdbc, policy);
            long test = erasureWaitingTest(jdbc, first, version, snapshot, runtime, admin);
            jdbc.update(
                    "INSERT INTO test_member(test_id,member_id,slot) VALUES (?,?,2)", test, second);
            var canonical = sealedErasure(jdbc, first, test, policy);
            var dependent = sealedErasure(jdbc, second, test, policy, canonical.testTarget());
            for (long member : List.of(first, second)) {
                jdbc.update(
                        "UPDATE member_account SET state='DELETED',deleted_at=clock_timestamp()"
                                + " WHERE id=?",
                        member);
            }
            for (var targets : List.of(canonical, dependent)) {
                jdbc.update(
                        "UPDATE privacy_erase SET"
                            + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=4,state='PROCESSING'"
                            + " WHERE id=?",
                        targets.root());
            }
            jdbc.update(
                    "UPDATE privacy_target SET state='RUNNING',"
                            + "lease_until=clock_timestamp()+interval '1 hour' WHERE id=?",
                    canonical.testTarget());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT consume_privacy_primary(?)",
                                    Boolean.class,
                                    canonical.testTarget()))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM play_test WHERE id=?",
                                    Integer.class,
                                    test))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT test_id FROM privacy_target WHERE id=?",
                                    Long.class,
                                    dependent.testTarget()))
                    .isNull();
            var originalReceipt =
                    jdbc.queryForMap(
                            "SELECT * FROM privacy_target WHERE id=?", canonical.testTarget());
            var history = history(jdbc);

            // V42 이전의 제한 역할 권한만 이관 대상으로 사용한다. 업그레이드 뒤 GRANT는 없다.
            String role = "erasure_executor_" + UUID.randomUUID().toString().replace("-", "");
            String password = "synthetic-executor-" + UUID.randomUUID();
            jdbc.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            jdbc.execute(
                    "GRANT EXECUTE ON FUNCTION public.consume_privacy_primary(bigint) TO "
                            + role
                            + " WITH GRANT OPTION");
            var current = embedded(database, "42");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 41)).isEqualTo(history);
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT * FROM privacy_target WHERE id=?",
                                    canonical.testTarget()))
                    .containsAllEntriesOf(originalReceipt)
                    .containsEntry("source_kind", "UNKNOWN");
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT source_kind,test_id,state,dependency_id "
                                            + "FROM privacy_target WHERE id=?",
                                    dependent.testTarget()))
                    .containsEntry("source_kind", "TEST")
                    .containsEntry("test_id", null)
                    .containsEntry("state", "PENDING")
                    .containsEntry("dependency_id", canonical.testTarget());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM privacy_target WHERE id IN (?,?) "
                                            + "AND source_kind='MEMBER' AND state='PENDING'",
                                    Integer.class,
                                    canonical.memberTarget(),
                                    dependent.memberTarget()))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM privacy_target WHERE erase_id IN (?,?)"
                                            + " AND kind IN ('BACKUP','RESTORE_GUARD') AND"
                                            + " source_kind='NONE'",
                                    Integer.class,
                                    canonical.root(),
                                    dependent.root()))
                    .isEqualTo(4);

            var limited =
                    new JdbcTemplate(
                            new DriverManagerDataSource(database.getJdbcUrl(), role, password));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT has_function_privilege(?,"
                                            + "'public.consume_privacy_primary(bigint)','EXECUTE')",
                                    Boolean.class,
                                    role))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT has_function_privilege(?,"
                                        + "'public.consume_privacy_primary_test_account(bigint)','EXECUTE')",
                                    Boolean.class,
                                    role))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    """
                                    SELECT bool_or(a.is_grantable) FROM pg_proc p CROSS JOIN LATERAL
                                      aclexplode(p.proacl) a WHERE p.oid='public.consume_privacy_primary(bigint)'::regprocedure
                                      AND a.grantee=(SELECT oid FROM pg_roles WHERE rolname=?)
                                    """,
                                    Boolean.class,
                                    role))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    """
                                    SELECT count(*) FROM pg_proc p CROSS JOIN LATERAL aclexplode(p.proacl) a
                                    WHERE p.oid='public.consume_privacy_primary_test_account(bigint)'::regprocedure
                                      AND a.grantee<>p.proowner
                                    """,
                                    Integer.class))
                    .isZero();
            assertThatThrownBy(
                            () ->
                                    limited.queryForObject(
                                            "SELECT public.consume_privacy_primary_test_account(?)",
                                            Boolean.class,
                                            dependent.testTarget()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .satisfies(
                            error -> {
                                Throwable cause = error;
                                while (cause.getCause() != null) cause = cause.getCause();
                                assertThat(cause).isInstanceOf(SQLException.class);
                                assertThat(((SQLException) cause).getSQLState()).isEqualTo("42501");
                            });
            jdbc.update(
                    "UPDATE privacy_target SET state='RUNNING',"
                            + "lease_until=clock_timestamp()+interval '1 hour' WHERE id=?",
                    dependent.testTarget());
            assertThat(
                            limited.queryForObject(
                                    "SELECT public.consume_privacy_primary(?)",
                                    Boolean.class,
                                    dependent.testTarget()))
                    .isTrue();
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT state,result_code,dependency_id,test_id,confirmed_at "
                                            + "FROM privacy_target WHERE id=?",
                                    dependent.testTarget()))
                    .containsEntry("state", "COMPLETE")
                    .containsEntry("result_code", "DEPENDENCY_CONFIRMED")
                    .containsEntry("dependency_id", null)
                    .containsEntry("test_id", null)
                    .containsKey("confirmed_at");
            for (long memberTarget : List.of(dependent.memberTarget(), canonical.memberTarget())) {
                jdbc.update(
                        "UPDATE privacy_target SET state='RUNNING',"
                                + "lease_until=clock_timestamp()+interval '1 hour' WHERE id=?",
                        memberTarget);
                assertThat(
                                limited.queryForObject(
                                        "SELECT public.consume_privacy_primary(?)",
                                        Boolean.class,
                                        memberTarget))
                        .isTrue();
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM member_account WHERE id IN (?,?)",
                                    Integer.class,
                                    first,
                                    second))
                    .isZero();
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
        }
    }

    /**
     * @param jdbc 폐기 DB 연결
     * @return 형식상 정상인 합성 MEMBER_AUTH 정책 ID; 운영 근거가 아니다
     */
    private static long syntheticMemberErasurePolicy(JdbcTemplate jdbc) throws Exception {
        long owner =
                jdbc.queryForObject(
                        "INSERT INTO admin_account(account_key) VALUES (?) RETURNING id",
                        Long.class,
                        UUID.randomUUID());
        String from =
                Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS).toString();
        String until =
                Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(1, ChronoUnit.DAYS).toString();
        var evidence = new HashMap<String, Object>();
        for (String kind :
                List.of(
                        "responsibility",
                        "access",
                        "keys",
                        "processors",
                        "copies",
                        "verification")) {
            evidence.put(
                    kind,
                    Map.of(
                            "ref",
                            "synthetic:" + kind,
                            "sha256",
                            "a".repeat(64),
                            "envCode",
                            "SYNTHETIC",
                            "scope",
                            "MEMBER_AUTH",
                            "verifiedAt",
                            from,
                            "validUntil",
                            until));
        }
        var document =
                Map.of(
                        "formatNo",
                        1,
                        "notice",
                        Map.of(
                                "version",
                                "SYNTHETIC_1",
                                "body",
                                "Synthetic notice",
                                "contact",
                                "synthetic@example.invalid"),
                        "validFrom",
                        from,
                        "validUntil",
                        until,
                        "authProviders",
                        List.of("LOCAL"),
                        "retention",
                        Map.ofEntries(
                                Map.entry("flowTtlSeconds", 600),
                                Map.entry("flowCleanupGraceSeconds", 3600),
                                Map.entry("accessTtlSeconds", 300),
                                Map.entry("accessCleanupGraceSeconds", 3600),
                                Map.entry("refreshIdleSeconds", 2592000),
                                Map.entry("sessionAbsoluteSeconds", 7776000),
                                Map.entry("familyCleanupGraceSeconds", 86400),
                                Map.entry("limitMaxSeconds", 86400),
                                Map.entry("accessHistoryMaxSeconds", 2592000),
                                Map.entry("securityAuditMaxSeconds", 7776000),
                                Map.entry("backupMaxSeconds", 3024000)),
                        "evidence",
                        evidence);
        return jdbc.queryForObject(
                """
                INSERT INTO privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)
                VALUES (?,'SYNTHETIC','MEMBER_AUTH','DRAFT',repeat('a',64),?::jsonb,?) RETURNING id
                """,
                Long.class,
                "ERASE_" + UUID.randomUUID().toString().replace("-", ""),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document),
                owner);
    }

    /**
     * @param jdbc 폐기 DB 연결
     * @param policy 저장된 MEMBER_AUTH 정책 ID
     * @return 해당 프로필이 연결된 합성 회원 ID
     */
    private static long syntheticErasureMember(JdbcTemplate jdbc, long policy) {
        long member =
                jdbc.queryForObject(
                        """
                        INSERT INTO member_account(member_key,state) VALUES (?,'ACTIVE') RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID());
        jdbc.update(
                "INSERT INTO member_profile(member_id,nickname_cipher,policy_id,accepted_at) "
                        + "VALUES (?,decode(repeat('03',32),'hex'),?,clock_timestamp())",
                member,
                policy);
        return member;
    }

    /**
     * @param jdbc 폐기 DB 연결
     * @param member TEST 참가 회원 ID
     * @param version 실제 사본이 소속한 버전 ID
     * @param snapshot TEST에 고정한 사본 ID
     * @param runtime TEST에 고정한 실행기 ID
     * @param owner 생성 관리자 ID
     * @return 해당 회원이 slot 1인 합성 WAITING TEST ID
     */
    private static long erasureWaitingTest(
            JdbcTemplate jdbc, long member, long version, long snapshot, long runtime, long owner) {
        long test =
                jdbc.queryForObject(
                        """
                        WITH clock AS (SELECT clock_timestamp() AS created)
                        INSERT INTO play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,
                          runtime_epoch,role_a,role_b,mode,invite_until,created_by,created_at,updated_at)
                        SELECT ?,?,?,?,repeat('a',64),0,'R1','R2','FUNCTIONAL',created+interval '7 days',
                          ?,created,created FROM clock RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        version,
                        snapshot,
                        runtime,
                        owner);
        jdbc.update("INSERT INTO test_member(test_id,member_id,slot) VALUES (?,?,1)", test, member);
        return test;
    }

    /**
     * @param jdbc 폐기 DB 연결
     * @param member 접수 회원 ID
     * @param test 실제 참가한 TEST ID
     * @param policy 회원 프로필의 MEMBER_AUTH 정책 ID
     * @return 회원·TEST PRIMARY 및 외부 두 대상을 포함한 아직 봉인하지 않은 접수
     */
    private static ErasureTargets sealedErasure(
            JdbcTemplate jdbc, long member, long test, long policy) {
        return sealedErasure(jdbc, member, test, policy, null);
    }

    /**
     * @param jdbc 폐기 DB 연결
     * @param member 접수 회원 ID
     * @param test 실제 참가한 TEST ID
     * @param policy 회원 프로필의 MEMBER_AUTH 정책 ID
     * @param dependency 다른 회원의 canonical TEST PRIMARY ID; null이면 canonical
     * @return 같은 TEST의 의존 관계가 결속된 아직 봉인하지 않은 접수
     */
    private static ErasureTargets sealedErasure(
            JdbcTemplate jdbc, long member, long test, long policy, Long dependency) {
        long root =
                jdbc.queryForObject(
                        """
                        INSERT INTO privacy_erase(erase_key,member_id,policy_id,reason_code,locator_cipher,
                          receipt_hash,receipt_until,backup_until)
                        VALUES (?,?,?,'ACCOUNT_REQUEST',decode(repeat('01',32),'hex'),repeat('a',64),
                          clock_timestamp()+interval '89 days',clock_timestamp()+interval '34 days') RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        member,
                        policy);
        long memberTarget =
                jdbc.queryForObject(
                        """
                        INSERT INTO privacy_target(erase_id,target_key,kind,member_id,locator_cipher)
                        VALUES (?,?,'PRIMARY',?,decode(repeat('02',32),'hex')) RETURNING id
                        """,
                        Long.class,
                        root,
                        UUID.randomUUID(),
                        member);
        long testTarget =
                jdbc.queryForObject(
                        """
                        INSERT INTO privacy_target(erase_id,target_key,kind,test_id,dependency_id,locator_cipher)
                        VALUES (?,?,'PRIMARY',?,?,decode(repeat('02',32),'hex')) RETURNING id
                        """,
                        Long.class,
                        root,
                        UUID.randomUUID(),
                        test,
                        dependency);
        jdbc.update(
                "INSERT INTO privacy_target(erase_id,target_key,kind) VALUES "
                        + "(?,gen_random_uuid(),'BACKUP'),(?,gen_random_uuid(),'RESTORE_GUARD')",
                root,
                root);
        return new ErasureTargets(root, memberTarget, testTarget);
    }

    /** 출처와 불일치한 기존 기한은 소급 보정하지 않고 전체 V41을 실패시킨다. */
    @Test
    void malformedFortyRetentionFailsWithoutChangingHistory() throws Exception {
        try (var database = database()) {
            database.start();
            assertThat(embedded(database, "40").migrate().migrationsExecuted).isEqualTo(40);
            var jdbc = jdbc(database);
            var fixture = syntheticRetentionFixture(jdbc);
            jdbc.update(
                    "UPDATE test_retention SET max_until=max_until+interval '1 day' "
                            + "WHERE test_id=?",
                    fixture.original());
            var before = history(jdbc);
            assertThatThrownBy(() -> embedded(database, "41").migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("malformed existing TEST retention");
            assertThat(history(jdbc)).isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT raw_until IS NULL FROM test_retention "
                                            + "WHERE test_id=?",
                                    Boolean.class,
                                    fixture.recent()))
                    .isTrue();
        }
    }

    private record RetentionFixture(
            long recent, long expired, long original, long selected, long waiting) {}

    /** V40의 실제 FK 부모와 형식상 유효한 합성 PLAYTEST 정책만 만든다. 자격 근거를 생성하지 않는다. */
    private static RetentionFixture syntheticRetentionFixture(JdbcTemplate jdbc) throws Exception {
        long owner =
                jdbc.queryForObject(
                        "INSERT INTO admin_account(account_key) VALUES (?) RETURNING id",
                        Long.class,
                        UUID.randomUUID());
        long story =
                jdbc.queryForObject(
                        "INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id",
                        Long.class,
                        "RET_SYNTHETIC",
                        owner);
        long version =
                jdbc.queryForObject(
                        """
                        INSERT INTO story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'합성 보관 관계','RET_SYNTHETIC',?,?) RETURNING id
                        """,
                        Long.class,
                        story,
                        owner,
                        owner);
        jdbc.update(
                "INSERT INTO story_role(version_id,code,name) VALUES (?,'R1','합성 역할 1'),"
                        + "(?,'R2','합성 역할 2')",
                version,
                version);
        jdbc.update(
                "INSERT INTO story_pair(version_id,role_a,role_b) VALUES (?,'R1','R2')", version);
        long snapshot =
                jdbc.queryForObject(
                        """
                        INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                        VALUES (?,0,'{}',?,?) RETURNING id
                        """,
                        Long.class,
                        version,
                        UUID.randomUUID(),
                        owner);
        String hash = "a".repeat(64);
        long runtime =
                jdbc.queryForObject(
                        """
                        INSERT INTO grade_runtime(code,config_hash,config_data,state)
                        VALUES (?,?,'{}','AVAILABLE') RETURNING id
                        """,
                        Long.class,
                        "RET_SYNTHETIC",
                        hash);
        String from =
                Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS).toString();
        String until =
                Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(1, ChronoUnit.DAYS).toString();
        var evidence = new HashMap<String, Object>();
        for (String kind :
                List.of(
                        "responsibility",
                        "access",
                        "keys",
                        "processors",
                        "copies",
                        "verification")) {
            evidence.put(
                    kind,
                    Map.of(
                            "ref",
                            "ret:" + kind,
                            "sha256",
                            hash,
                            "envCode",
                            "SYNTHETIC",
                            "scope",
                            "PLAYTEST",
                            "verifiedAt",
                            from,
                            "validUntil",
                            until));
        }
        var document =
                Map.of(
                        "formatNo",
                        1,
                        "notice",
                        Map.of(
                                "version",
                                "1",
                                "body",
                                "합성 보관 정책",
                                "contact",
                                "synthetic@example.invalid"),
                        "validFrom",
                        from,
                        "validUntil",
                        until,
                        "retention",
                        Map.of(
                                "inviteSeconds",
                                604800,
                                "lobbySeconds",
                                1800,
                                "resultSeconds",
                                86400,
                                "rawSeconds",
                                7776000,
                                "selectedSeconds",
                                31536000,
                                "backupMaxSeconds",
                                3024000),
                        "evidence",
                        evidence);
        long policy =
                jdbc.queryForObject(
                        """
                        INSERT INTO privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)
                        VALUES (?,'SYNTHETIC','PLAYTEST','DRAFT',?,?::jsonb,?) RETURNING id
                        """,
                        Long.class,
                        "RET_SYNTHETIC",
                        hash,
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(document),
                        owner);
        long recent = syntheticTest(jdbc, version, snapshot, runtime, owner, "ENDED", 2);
        long expired = syntheticTest(jdbc, version, snapshot, runtime, owner, "EXPIRED", 400);
        long original = syntheticTest(jdbc, version, snapshot, runtime, owner, "CANCELLED", 3);
        long selected = syntheticTest(jdbc, version, snapshot, runtime, owner, "ENDED", 4);
        long waiting = syntheticTest(jdbc, version, snapshot, runtime, owner, "WAITING", 0);
        for (long id : List.of(recent, expired, original, selected, waiting)) {
            jdbc.update("INSERT INTO test_retention(test_id,policy_id) VALUES (?,?)", id, policy);
        }
        jdbc.update(
                """
                UPDATE test_retention r SET raw_until=t.ended_at+interval '90 days',
                  max_until=t.ended_at+interval '365 days'
                FROM play_test t WHERE t.id=r.test_id AND r.test_id IN (?,?)
                """,
                original,
                selected);
        jdbc.update(
                """
                UPDATE test_retention r SET raw_until=t.ended_at+interval '365 days',
                  selected_at=t.ended_at+interval '1 day'
                FROM play_test t WHERE t.id=r.test_id AND r.test_id=?
                """,
                selected);
        return new RetentionFixture(recent, expired, original, selected, waiting);
    }

    /** 지정된 과거 종료 상태의 합성 부모를 만든다. daysAgo는 종료 이후 경과 일수다. */
    private static long syntheticTest(
            JdbcTemplate jdbc,
            long version,
            long snapshot,
            long runtime,
            long owner,
            String state,
            int daysAgo) {
        return jdbc.queryForObject(
                """
                WITH clock AS (SELECT now()-(?::integer+1)*interval '1 day' AS created,
                  now()-?::integer*interval '1 day' AS ended)
                INSERT INTO play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,
                  runtime_epoch,role_a,role_b,mode,state,outcome,invite_until,ended_at,
                  created_by,created_at,updated_at)
                SELECT ?,?,?,?,repeat('a',64),0,'R1','R2','BLIND',?,
                  CASE WHEN ?='ENDED' THEN 'FORFEIT' ELSE NULL END,
                  created+interval '7 days',CASE WHEN ?='WAITING' THEN NULL ELSE ended END,
                  ?,created,CASE WHEN ?='WAITING' THEN created ELSE ended END
                FROM clock RETURNING id
                """,
                Long.class,
                daysAgo,
                daysAgo,
                UUID.randomUUID(),
                version,
                snapshot,
                runtime,
                state,
                state,
                state,
                owner,
                state);
    }

    /** V38의 원문·기존 흐름을 보존하며 평가를 소급 발급하지 않고 V39 한 건만 전진한다. */
    @Test
    void originalThirtyEightToBlindThirtyNinePreservesHistory() {
        try (var database = database()) {
            database.start();
            var previous = embedded(database, "38");
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(38);
            var jdbc = jdbc(database);
            jdbc.update(
                    "INSERT INTO member_flow(flow_key,binder_hash,purpose,state,provider,"
                            + "lookup_hash,code_hash,proof_cipher,created_at,expires_at) VALUES"
                            + " (gen_random_uuid(),repeat('a',64),'SIGNUP','PENDING','LOCAL',"
                            + "repeat('b',64),repeat('c',64),decode(repeat('00',32),'hex'),"
                            + "now(),now()+interval '10 minutes')");
            var before = history(jdbc);
            var flows = jdbc.queryForList("SELECT * FROM member_flow");
            var current = embedded(database, "39");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 38)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM member_flow"))
                    .usingRecursiveComparison()
                    .isEqualTo(flows);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM blind_assessment", Integer.class))
                    .isZero();
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
        }
    }

    /** V23 이력과 기존 SIGNUP을 보존하고 V24만 적용하며 재실행은 0이다. */
    @Test
    void originalTwentyThreeToResetTwentyFourPreservesHistoricalRows() throws Exception {
        try (var database = database()) {
            database.start();
            var previous = legacy(database, "23");
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(23);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            jdbc.update(
                    "INSERT INTO"
                        + " member_flow(flow_key,binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,created_at,expires_at)"
                        + " VALUES"
                        + " (gen_random_uuid(),repeat('a',64),'SIGNUP','PENDING','LOCAL',repeat('b',64),repeat('c',64),decode(repeat('00',32),'hex'),now(),now()+interval"
                        + " '10 minutes')");
            var flows =
                    jdbc.queryForList(
                            "SELECT flow_key,binder_hash,purpose,state,created_at,expires_at FROM"
                                    + " member_flow");
            var current = embedded(database, "24");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 23)).isEqualTo(before);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT"
                                        + " flow_key,binder_hash,purpose,state,created_at,expires_at"
                                        + " FROM member_flow"))
                    .isEqualTo(flows);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT member_id IS NULL AND identity_id IS NULL AND auth_rev"
                                            + " IS NULL FROM member_flow",
                                    Boolean.class))
                    .isTrue();
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "24"));
        }
    }

    /** V24의 기존 흐름과 전체 이력을 보존한 채 V25의 세션 결속 제약만 전진 적용한다. */
    @Test
    void originalTwentyFourToRecentTwentyFivePreservesHistory() throws Exception {
        try (var database = database()) {
            database.start();
            var previous = legacy(database, "24");
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(24);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            jdbc.update(
                    "INSERT INTO"
                        + " member_flow(flow_key,binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,created_at,expires_at)"
                        + " VALUES"
                        + " (gen_random_uuid(),repeat('a',64),'RESET','PENDING','LOCAL',repeat('b',64),repeat('c',64),decode(repeat('00',32),'hex'),now(),now()+interval"
                        + " '10 minutes')");
            var flows =
                    jdbc.queryForList(
                            "SELECT flow_key,binder_hash,purpose,state,created_at,expires_at FROM"
                                    + " member_flow");
            var current = embedded(database, "25");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 24)).isEqualTo(before);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT"
                                        + " flow_key,binder_hash,purpose,state,created_at,expires_at"
                                        + " FROM member_flow"))
                    .isEqualTo(flows);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT session_id IS NULL AND reauth_purpose IS NULL FROM"
                                            + " member_flow",
                                    Boolean.class))
                    .isTrue();
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "25"));
        }
    }

    /** V25의 회원 흐름과 이력을 보존하고 비어 있는 파기 저장소만 전진 추가한다. */
    @Test
    void originalTwentyFiveToErasureTwentySixPreservesHistory() throws Exception {
        try (var database = database()) {
            database.start();
            var previous = legacy(database, "25");
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(25);
            var jdbc = jdbc(database);
            jdbc.update(
                    "INSERT INTO"
                        + " member_flow(flow_key,binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,created_at,expires_at)"
                        + " VALUES"
                        + " (gen_random_uuid(),repeat('a',64),'RESET','PENDING','LOCAL',repeat('b',64),repeat('c',64),decode(repeat('00',32),'hex'),now(),now()+interval"
                        + " '10 minutes')");
            var before = history(jdbc);
            var flows = jdbc.queryForList("SELECT * FROM member_flow");
            var current = embedded(database, "26");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(history(jdbc).subList(0, 25)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM member_flow"))
                    .usingRecursiveComparison()
                    .isEqualTo(flows);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM privacy_erase", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM privacy_target", Integer.class))
                    .isZero();
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            assertSqlIdentity(current, legacy(database, "26"));
        }
    }

    /** V26 이력을 보존하고 비어 있는 일반 실행 부모·감사 두 마이그레이션만 추가한다. */
    @Test
    void originalTwentySixToGeneralTwentyEightPreservesHistory() {
        try (var database = database()) {
            database.start();
            var previous = embedded(database, "26");
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(26);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            var current = embedded(database, "28");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(history(jdbc).subList(0, 26)).isEqualTo(before);
            for (String table :
                    List.of(
                            "game_room",
                            "game_member",
                            "game_report",
                            "game_action",
                            "game_audit")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                        .isZero();
            }
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
        }
    }

    /** V28의 기존 부모와 이력을 유지하면서 GAME·공개 근거·개인 기록 후속 일곱 건만 적용한다. */
    @Test
    void originalTwentyEightToPublicRecordsThirtyFivePreservesHistory() {
        try (var database = database()) {
            database.start();
            var previous = embedded(database, "28");
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(28);
            var jdbc = jdbc(database);
            var before = history(jdbc);
            long owner =
                    jdbc.queryForObject(
                            "INSERT INTO admin_account(account_key) VALUES (?) RETURNING id",
                            Long.class,
                            java.util.UUID.randomUUID());
            long story =
                    jdbc.queryForObject(
                            "INSERT INTO story(code,owner_id) VALUES ('V35_KEEP',?) RETURNING id",
                            Long.class,
                            owner);
            var current = embedded(database, "35");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(7);
            assertThat(history(jdbc).subList(0, 28)).isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT code FROM story WHERE id=? AND owner_id=?",
                                    String.class,
                                    story,
                                    owner))
                    .isEqualTo("V35_KEEP");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM play_record", Integer.class))
                    .isZero();
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
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
            var current = embedded(database, "23");
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
        return embedded(database, "44");
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
         * 정상 초기화 의존 bean 생성 시점에서 SQL25와 세션 테이블 준비를 관측한다.
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
            assertThat(migrations).isEqualTo(44);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.spring_session", Integer.class))
                    .isZero();
            return new Readiness(migrations);
        }
    }
}
