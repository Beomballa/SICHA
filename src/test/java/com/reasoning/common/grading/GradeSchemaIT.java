package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.migration.EmbeddedSqlResourceProvider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 회귀 전용 네 테이블의 물리 계약을 실제 폐기형 PostgreSQL에서 검사한다. 모델 품질 시험은 아니다. */
public class GradeSchemaIT {
    static final String IMAGE =
            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1";
    static final String HASH = "a".repeat(64);
    static final Set<String> TABLES =
            Set.of("grade_runtime", "grade_batch", "grade_job", "grade_attempt");
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private long owner;
    private long snapshot;
    private long otherSnapshot;
    private long runtime;
    private long batch;
    private long job;

    @BeforeAll
    static void open() {
        postgres = startDatabase();
        jdbc = jdbc(postgres);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** V12→V13의 네 테이블 추가를 먼저 검사하고 후속 마이그레이션까지 적용한 격리 DB를 만든다. */
    public static PostgreSQLContainer<?> startDatabase() {
        var database =
                new PostgreSQLContainer<>(
                        DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
        try {
            database.start();
            var prior =
                    Flyway.configure()
                            .resourceProvider(new EmbeddedSqlResourceProvider())
                            .dataSource(
                                    database.getJdbcUrl(),
                                    database.getUsername(),
                                    database.getPassword())
                            .locations("classpath:db/migration")
                            .target("12")
                            .load();
            assertThat(prior.migrate().migrationsExecuted).isEqualTo(12);
            JdbcTemplate connection = jdbc(database);
            Set<String> before = tableNames(connection);
            var current =
                    Flyway.configure()
                            .resourceProvider(new EmbeddedSqlResourceProvider())
                            .dataSource(
                                    database.getJdbcUrl(),
                                    database.getUsername(),
                                    database.getPassword())
                            .locations("classpath:db/migration")
                            .target("13")
                            .load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            Set<String> added = tableNames(connection);
            added.removeAll(before);
            assertThat(added).containsExactlyInAnyOrderElementsOf(TABLES);
            var latest =
                    Flyway.configure()
                            .resourceProvider(new EmbeddedSqlResourceProvider())
                            .dataSource(
                                    database.getJdbcUrl(),
                                    database.getUsername(),
                                    database.getPassword())
                            .locations("classpath:db/migration")
                            .load();
            latest.migrate();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            latest.validate();
            return database;
        } catch (RuntimeException | AssertionError failure) {
            database.close();
            throw failure;
        }
    }

    /** 테스트 DB에만 연결하는 JDBC 도구를 만든다. */
    public static JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        var connection =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                database.getJdbcUrl(),
                                database.getUsername(),
                                database.getPassword()));
        connection.setQueryTimeout(10);
        return connection;
    }

    /** public의 물리 테이블 집합을 읽는다. */
    private static Set<String> tableNames(JdbcTemplate connection) {
        return new HashSet<>(
                connection.queryForList(
                        "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname='public'",
                        String.class));
    }

    @BeforeEach
    void fixtures() {
        owner =
                id(
                        "INSERT INTO public.admin_account(account_key) VALUES (?::uuid) RETURNING"
                                + " id",
                        UUID.randomUUID().toString());
        long story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        "G" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                        owner);
        long version =
                id(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'격리 회귀','H2',?,?) RETURNING id",
                        story,
                        owner,
                        owner);
        snapshot = snapshot(version);
        otherSnapshot = snapshot(version);
        runtime =
                id(
                        "INSERT INTO public.grade_runtime(code,config_hash,config_data,state)"
                                + " VALUES (?,?,'{}','AVAILABLE') RETURNING id",
                        UUID.randomUUID().toString(),
                        HASH);
        batch =
                id(
                        "INSERT INTO"
                            + " public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)"
                            + " VALUES (?::uuid,?,?,'REVIEW',?,?,?,?,0,'STAGED',3,?) RETURNING id",
                        UUID.randomUUID().toString(),
                        snapshot,
                        runtime,
                        HASH,
                        HASH,
                        HASH,
                        HASH,
                        owner);
        job = newJob("ONE", 1);
    }

    @Test
    void exactColumnsTypesNullabilityAndDefaults() {
        Map<String, String> specifications =
                Map.of(
                        "grade_runtime",
                                "id:bigint:N,code:character"
                                    + " varying(80):N,config_hash:character(64):N,config_data:jsonb:N,state:character"
                                    + " varying(24):N,epoch:bigint:N,created_at:timestamp with time"
                                    + " zone:N,updated_at:timestamp with time zone:N",
                        "grade_batch",
                                "id:bigint:N,batch_key:uuid:N,snapshot_id:bigint:N,runtime_id:bigint:N,purpose:character"
                                    + " varying(24):N,dataset_hash:character(64):N,rubric_hash:character(64):N,payload_hash:character(64):N,config_hash:character(64):N,runtime_epoch:bigint:N,state:character"
                                    + " varying(24):N,repeat_count:smallint:N,expected_count:integer:N,passed_yn:boolean:Y,valid_until:timestamp"
                                    + " with time zone:Y,created_by:bigint:N,created_at:timestamp"
                                    + " with time zone:N,ended_at:timestamp with time zone:Y",
                        "grade_job",
                                "id:bigint:N,job_key:uuid:N,snapshot_id:bigint:N,runtime_id:bigint:N,batch_id:bigint:Y,sample_code:character"
                                    + " varying(32):Y,repeat_no:smallint:Y,state:character"
                                    + " varying(24):N,accepted_at:timestamp with time"
                                    + " zone:Y,deadline_at:timestamp with time"
                                    + " zone:Y,call_count:smallint:N,lease_gen:bigint:N,lease_until:timestamp"
                                    + " with time zone:Y,worker_key:character"
                                    + " varying(80):Y,next_run_at:timestamp with time"
                                    + " zone:N,input_hash:character(64):Y,config_hash:character(64):N,rubric_hash:character(64):N,result_cipher:bytea:Y,result_data:jsonb:Y,result_hash:character(64):Y,error_code:character"
                                    + " varying(40):Y,created_at:timestamp with time"
                                    + " zone:N,updated_at:timestamp with time"
                                    + " zone:N,report_id:bigint:Y,report_hash:character(64):Y",
                        "grade_attempt",
                                "job_id:bigint:N,attempt_no:smallint:N,lease_gen:bigint:N,worker_key:character"
                                    + " varying(80):N,started_at:timestamp with time"
                                    + " zone:N,ended_at:timestamp with time zone:Y,state:character"
                                    + " varying(24):N,provider_ref:character"
                                    + " varying(120):Y,error_code:character"
                                    + " varying(40):Y,output_hash:character(64):Y,output_cipher:bytea:Y,observed_version:character"
                                    + " varying(160):Y,completion_data:jsonb:Y");
        Map<String, String> defaults =
                Map.of(
                        "epoch",
                        "0",
                        "repeat_count",
                        "3",
                        "call_count",
                        "0",
                        "lease_gen",
                        "0",
                        "created_at",
                        "now()",
                        "updated_at",
                        "now()",
                        "next_run_at",
                        "now()",
                        "started_at",
                        "now()");
        for (var spec : specifications.entrySet()) {
            var columns =
                    jdbc.queryForList(
                            """
                            SELECT a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod) AS type,
                                a.attnotnull, a.attidentity, pg_catalog.pg_get_expr(d.adbin,d.adrelid) AS def,
                                pg_catalog.col_description(a.attrelid,a.attnum) AS comment
                            FROM pg_catalog.pg_attribute a LEFT JOIN pg_catalog.pg_attrdef d
                                ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                            WHERE a.attrelid=?::regclass AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum
                            """,
                            "public." + spec.getKey());
            assertThat(
                            columns.stream()
                                    .map(
                                            c ->
                                                    c.get("attname")
                                                            + ":"
                                                            + c.get("type")
                                                            + ":"
                                                            + (Boolean.TRUE.equals(
                                                                            c.get("attnotnull"))
                                                                    ? "N"
                                                                    : "Y"))
                                    .toList())
                    .containsExactly(spec.getValue().split(","));
            for (var column : columns) {
                String name = (String) column.get("attname");
                if (spec.getKey().equals("grade_job") && name.equals("report_id")) {
                    assertThat(column.get("comment")).isNull();
                } else {
                    assertThat((String) column.get("comment")).containsPattern("[가-힣]");
                }
                assertThat(column.get("attidentity")).isEqualTo(name.equals("id") ? "a" : "");
                assertThat(column.get("def"))
                        .isEqualTo(
                                spec.getKey().equals("grade_attempt") && name.equals("lease_gen")
                                        ? null
                                        : defaults.get(name));
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT pg_catalog.obj_description(?::regclass,'pg_class')",
                                    String.class,
                                    "public." + spec.getKey()))
                    .containsPattern("[가-힣]");
        }
    }

    @Test
    void exactKeysIndexesChecksAndKoreanComments() throws Exception {
        Map<String, Set<String>> indexes =
                Map.of(
                        "grade_runtime", Set.of("pk_grade_runtime", "uk_grade_runtime_code"),
                        "grade_batch",
                                Set.of(
                                        "pk_grade_batch",
                                        "uk_grade_batch_key",
                                        "uk_grade_batch_snapshot",
                                        "uk_grade_batch_source"),
                        "grade_job",
                                Set.of(
                                        "pk_grade_job",
                                        "uk_grade_job_key",
                                        "uk_grade_job_sample",
                                        "uk_gj_worker",
                                        "uk_gj_report"),
                        "grade_attempt", Set.of("pk_grade_attempt", "uk_ga_lease"));
        Map<String, Set<String>> checks =
                Map.of(
                        "grade_runtime",
                                Set.of(
                                        "ck_runtime_state",
                                        "ck_runtime_epoch",
                                        "ck_grade_runtime_config_hash",
                                        "ck_grade_runtime_config_data"),
                        "grade_batch",
                                Set.of(
                                        "ck_gb_purpose",
                                        "ck_gb_state",
                                        "ck_gb_size",
                                        "ck_gb_result",
                                        "ck_grade_batch_dataset_hash",
                                        "ck_grade_batch_rubric_hash",
                                        "ck_grade_batch_payload_hash",
                                        "ck_grade_batch_config_hash"),
                        "grade_job",
                                Set.of(
                                        "ck_gj_state",
                                        "ck_gj_source",
                                        "ck_gj_report_hash",
                                        "ck_gj_running",
                                        "ck_gj_budget",
                                        "ck_gj_deadline",
                                        "ck_gj_lease",
                                        "ck_grade_job_input_hash",
                                        "ck_grade_job_config_hash",
                                        "ck_grade_job_rubric_hash",
                                        "ck_grade_job_result_cipher",
                                        "ck_grade_job_result_data",
                                        "ck_grade_job_result_hash"),
                        "grade_attempt",
                                Set.of(
                                        "ck_ga_state",
                                        "ck_ga_no",
                                        "ck_ga_end",
                                        "ck_grade_attempt_output_hash",
                                        "ck_grade_attempt_output_cipher",
                                        "ck_grade_attempt_completion_data"));
        Map<String, List<String>> keys =
                Map.of(
                        "grade_runtime",
                        List.of("id"),
                        "grade_batch",
                        List.of("id"),
                        "grade_job",
                        List.of("id"),
                        "grade_attempt",
                        List.of("job_id", "attempt_no"));
        Map<String, String> uniqueDefinitions =
                Map.of(
                        "uk_grade_runtime_code",
                        "UNIQUE (code)",
                        "uk_grade_batch_key",
                        "UNIQUE (batch_key)",
                        "uk_grade_batch_snapshot",
                        "UNIQUE (snapshot_id, id)",
                        "uk_grade_batch_source",
                        "UNIQUE (snapshot_id, runtime_id, id)",
                        "uk_grade_job_key",
                        "UNIQUE (job_key)",
                        "uk_grade_job_sample",
                        "UNIQUE (batch_id, sample_code, repeat_no)",
                        "uk_gj_report",
                        "UNIQUE (report_id)",
                        "uk_ga_lease",
                        "UNIQUE (job_id, lease_gen)");
        Map<String, List<String>> foreignKeys = new HashMap<>();
        try (var connection = postgres.createConnection("")) {
            var metadata = connection.getMetaData();
            for (String table : TABLES) {
                Set<String> actualIndexes = new HashSet<>();
                try (var rows = metadata.getIndexInfo(null, "public", table, false, false)) {
                    while (rows.next()) actualIndexes.add(rows.getString("INDEX_NAME"));
                }
                assertThat(actualIndexes).containsExactlyInAnyOrderElementsOf(indexes.get(table));
                Map<Short, String> primary = new TreeMap<>();
                try (var rows = metadata.getPrimaryKeys(null, "public", table)) {
                    while (rows.next()) {
                        assertThat(rows.getString("PK_NAME")).isEqualTo("pk_" + table);
                        primary.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
                    }
                }
                assertThat(primary.values()).containsExactlyElementsOf(keys.get(table));
                Map<String, Map<Short, String>> ordered = new HashMap<>();
                try (var rows = metadata.getImportedKeys(null, "public", table)) {
                    while (rows.next()) {
                        assertThat(rows.getString("PKTABLE_SCHEM")).isEqualTo("public");
                        assertThat(rows.getInt("DELETE_RULE"))
                                .isEqualTo(DatabaseMetaData.importedKeyNoAction);
                        assertThat(rows.getInt("UPDATE_RULE"))
                                .isEqualTo(DatabaseMetaData.importedKeyNoAction);
                        ordered.computeIfAbsent(
                                        rows.getString("FK_NAME"), ignored -> new TreeMap<>())
                                .put(
                                        rows.getShort("KEY_SEQ"),
                                        rows.getString("FKCOLUMN_NAME")
                                                + "->"
                                                + rows.getString("PKTABLE_NAME")
                                                + "."
                                                + rows.getString("PKCOLUMN_NAME"));
                    }
                }
                ordered.forEach(
                        (name, fields) -> foreignKeys.put(name, List.copyOf(fields.values())));
                var constraints =
                        jdbc.queryForList(
                                "SELECT conname,contype,pg_catalog.pg_get_constraintdef(oid) AS"
                                        + " def,pg_catalog.obj_description(oid,'pg_constraint') AS"
                                        + " comment FROM pg_catalog.pg_constraint WHERE"
                                        + " conrelid=?::regclass",
                                "public." + table);
                assertThat(
                                constraints.stream()
                                        .filter(c -> "c".equals(c.get("contype")))
                                        .map(c -> (String) c.get("conname"))
                                        .toList())
                        .containsExactlyInAnyOrderElementsOf(checks.get(table));
                for (var constraint : constraints) {
                    if (Set.of("ck_gj_source", "ck_gj_report_hash", "uk_gj_report", "fk_gj_report")
                            .contains(constraint.get("conname"))) {
                        assertThat(constraint.get("comment")).isNull();
                    } else {
                        assertThat((String) constraint.get("comment")).containsPattern("[가-힣]");
                    }
                    if ("u".equals(constraint.get("contype"))) {
                        assertThat(constraint.get("def"))
                                .isEqualTo(uniqueDefinitions.get(constraint.get("conname")));
                    }
                    if ("c".equals(constraint.get("contype"))) {
                        assertThat((String) constraint.get("def"))
                                .doesNotContain("now()", "clock_timestamp", "CURRENT_TIMESTAMP");
                        verifyCheckDefinition(
                                (String) constraint.get("conname"), (String) constraint.get("def"));
                    }
                }
                for (String index : actualIndexes) {
                    String comment =
                            jdbc.queryForObject(
                                    "SELECT"
                                            + " pg_catalog.obj_description(?::regclass,'pg_class')",
                                    String.class,
                                    "public." + index);
                    if (index.equals("uk_gj_report")) {
                        assertThat(comment).isNull();
                    } else {
                        assertThat(comment).contains("미측정");
                    }
                }
            }
        }
        assertThat(foreignKeys)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "fk_gb_snapshot",
                                List.of("snapshot_id->review_snapshot.id"),
                                "fk_gb_runtime",
                                List.of("runtime_id->grade_runtime.id"),
                                "fk_gb_admin",
                                List.of("created_by->admin_account.id"),
                                "fk_gj_snapshot",
                                List.of("snapshot_id->review_snapshot.id"),
                                "fk_gj_runtime",
                                List.of("runtime_id->grade_runtime.id"),
                                "fk_gj_batch",
                                List.of(
                                        "snapshot_id->grade_batch.snapshot_id",
                                        "batch_id->grade_batch.id"),
                                "fk_gj_report",
                                List.of(
                                        "snapshot_id->test_report.snapshot_id",
                                        "runtime_id->test_report.runtime_id",
                                        "report_id->test_report.id"),
                                "fk_ga_job",
                                List.of("job_id->grade_job.id")));
        String workerIndex =
                jdbc.queryForObject(
                        "SELECT indexdef FROM pg_catalog.pg_indexes WHERE schemaname='public' AND"
                                + " indexname='uk_gj_worker'",
                        String.class);
        assertThat(workerIndex)
                .contains("UNIQUE", "(worker_key)", "RUNNING", "worker_key IS NOT NULL", "WHERE");
    }

    /** PostgreSQL의 출력 정규화와 무관하게 모든 CHECK의 승인된 경계와 참여 열을 대조한다. */
    private static void verifyCheckDefinition(String name, String definition) {
        if (name.equals("ck_gj_source")) {
            String expected =
                    "CHECK ((batch_id IS NOT NULL AND sample_code IS NOT NULL AND repeat_no IS NOT"
                        + " NULL AND repeat_no >= 1 AND repeat_no <= 3 AND input_hash IS NOT NULL"
                        + " AND report_id IS NULL AND report_hash IS NULL) OR (batch_id IS NULL AND"
                        + " sample_code IS NULL AND repeat_no IS NULL AND input_hash IS NULL AND"
                        + " report_id IS NOT NULL AND report_hash IS NOT NULL))";
            assertThat(definition.replaceAll("[()\\s]", ""))
                    .isEqualTo(expected.replaceAll("[()\\s]", ""));
        }
        if (name.endsWith("_hash")) {
            String column =
                    name.equals("ck_gj_report_hash")
                            ? "report_hash"
                            : name.replaceFirst("^ck_grade_(runtime|batch|job|attempt)_", "");
            assertThat(definition).contains(column, "^[0-9a-f]{64}$");
            return;
        }
        List<String> tokens =
                switch (name) {
                    case "ck_runtime_state" ->
                            List.of("state", "AVAILABLE", "SUSPENDED", "RETIRED");
                    case "ck_runtime_epoch" -> List.of("epoch >= 0");
                    case "ck_gb_purpose" -> List.of("purpose", "REVIEW", "AVAILABILITY");
                    case "ck_gb_state" ->
                            List.of(
                                    "state",
                                    "STAGED",
                                    "RUNNING",
                                    "COMPLETED",
                                    "FAILED",
                                    "CANCELLED");
                    case "ck_gb_size" ->
                            List.of(
                                    "repeat_count = 3",
                                    "expected_count > 0",
                                    "expected_count % 3",
                                    "runtime_epoch >= 0");
                    case "ck_gb_result" ->
                            List.of(
                                    "COMPLETED",
                                    "FAILED",
                                    "CANCELLED",
                                    "ended_at IS NOT NULL",
                                    "passed_yn IS NULL");
                    case "ck_gj_state" ->
                            List.of(
                                    "state",
                                    "STAGED",
                                    "QUEUED",
                                    "RUNNING",
                                    "COMPLETED",
                                    "FAILED",
                                    "CANCELLED");
                    case "ck_gj_source" ->
                            List.of(
                                    "batch_id IS NOT NULL",
                                    "sample_code IS NOT NULL",
                                    "repeat_no IS NOT NULL",
                                    "repeat_no >= 1",
                                    "repeat_no <= 3",
                                    "input_hash IS NOT NULL",
                                    "report_id IS NULL",
                                    "report_hash IS NULL",
                                    "batch_id IS NULL",
                                    "sample_code IS NULL",
                                    "repeat_no IS NULL",
                                    "input_hash IS NULL",
                                    "report_id IS NOT NULL",
                                    "report_hash IS NOT NULL");
                    case "ck_gj_running" -> List.of("RUNNING", "worker_key IS NOT NULL");
                    case "ck_gj_budget" ->
                            List.of("call_count >= 0", "call_count <= 3", "lease_gen >= 0");
                    case "ck_gj_deadline" ->
                            List.of(
                                    "accepted_at IS NULL",
                                    "deadline_at IS NULL",
                                    "STAGED",
                                    "CANCELLED",
                                    "FAILED",
                                    "accepted_at IS NOT NULL",
                                    "deadline_at IS NOT NULL",
                                    "00:02:00");
                    case "ck_gj_lease" -> List.of("worker_key IS NULL", "lease_until IS NULL");
                    case "ck_ga_state" ->
                            List.of("state", "RUNNING", "SUCCEEDED", "FAILED", "EXPIRED");
                    case "ck_ga_no" ->
                            List.of("attempt_no >= 1", "attempt_no <= 3", "lease_gen > 0");
                    case "ck_ga_end" -> List.of("RUNNING", "ended_at IS NOT NULL");
                    case "ck_grade_runtime_config_data" ->
                            List.of(
                                    "jsonb_typeof(config_data)",
                                    "object",
                                    "octet_length",
                                    "131072");
                    case "ck_grade_job_result_data" ->
                            List.of(
                                    "jsonb_typeof(result_data)",
                                    "object",
                                    "octet_length",
                                    "131072");
                    case "ck_grade_attempt_completion_data" ->
                            List.of(
                                    "jsonb_typeof(completion_data)",
                                    "object",
                                    "octet_length",
                                    "131072");
                    case "ck_grade_job_result_cipher" ->
                            List.of("octet_length(result_cipher)", "32", "524288");
                    case "ck_grade_attempt_output_cipher" ->
                            List.of("octet_length(output_cipher)", "32", "524288");
                    default -> throw new AssertionError("미승인 CHECK: " + name);
                };
        assertThat(definition).contains(tokens.toArray(String[]::new));
    }

    @Test
    void mandatoryBatchSourceAndSameSnapshot() {
        for (String field : List.of("batch_id", "sample_code", "repeat_no", "input_hash")) {
            failsConstraint(
                    "23514",
                    "ck_gj_source",
                    "UPDATE public.grade_job SET " + field + "=NULL WHERE id=" + job);
        }
        for (String field : List.of("snapshot_id", "runtime_id")) {
            fails("23502", "UPDATE public.grade_job SET " + field + "=NULL WHERE id=" + job);
        }
        for (int repeat : List.of(0, 4))
            fails("23514", "UPDATE public.grade_job SET repeat_no=" + repeat + " WHERE id=" + job);
        fails(
                "23503",
                "UPDATE public.grade_job SET snapshot_id=" + otherSnapshot + " WHERE id=" + job);
        fails("23503", "UPDATE public.grade_job SET batch_id=9223372036854775807 WHERE id=" + job);
        fails(
                "23503",
                "UPDATE public.grade_job SET runtime_id=9223372036854775807 WHERE id=" + job);
        fails(
                "23503",
                "UPDATE public.grade_batch SET snapshot_id=9223372036854775807 WHERE id=" + batch);
        fails(
                "23503",
                "UPDATE public.grade_batch SET runtime_id=9223372036854775807 WHERE id=" + batch);
        fails(
                "23503",
                "UPDATE public.grade_batch SET created_by=9223372036854775807 WHERE id=" + batch);
        fails(
                "23505",
                "INSERT INTO"
                    + " public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)"
                    + " SELECT '"
                        + UUID.randomUUID()
                        + "',snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash"
                        + " FROM public.grade_job WHERE id="
                        + job);
        fails("23503", "DELETE FROM public.grade_batch WHERE id=" + batch);
        fails("23503", "DELETE FROM public.grade_runtime WHERE id=" + runtime);
    }

    @Test
    void admissionBudgetWorkerLeaseAndThirdRunningCall() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT accepted_at IS NULL AND deadline_at IS NULL AND"
                                    + " call_count=0 AND lease_gen=0 FROM public.grade_job WHERE"
                                    + " id=?",
                                Boolean.class,
                                job))
                .isTrue();
        for (String state : List.of("FAILED", "CANCELLED", "STAGED"))
            updateJob("state='" + state + "'");
        for (String state : List.of("QUEUED", "RUNNING", "COMPLETED"))
            fails("23514", "UPDATE public.grade_job SET state='" + state + "' WHERE id=" + job);
        fails("23514", "UPDATE public.grade_job SET accepted_at=now() WHERE id=" + job);
        fails("23514", "UPDATE public.grade_job SET deadline_at=now() WHERE id=" + job);
        updateJob(
                "state='QUEUED',accepted_at='2026-01-01T00:00:00Z',deadline_at='2026-01-01T00:02:00Z'");
        fails(
                "23514",
                "UPDATE public.grade_job SET deadline_at=accepted_at+interval '119 seconds' WHERE"
                        + " id="
                        + job);
        fails(
                "23514",
                "UPDATE public.grade_job SET deadline_at=accepted_at+interval '121 seconds' WHERE"
                        + " id="
                        + job);
        for (int calls : List.of(0, 1, 2, 3)) updateJob("call_count=" + calls);
        for (int calls : List.of(-1, 4))
            fails("23514", "UPDATE public.grade_job SET call_count=" + calls + " WHERE id=" + job);
        fails("23514", "UPDATE public.grade_job SET lease_gen=-1 WHERE id=" + job);
        fails("23514", "UPDATE public.grade_job SET worker_key='W' WHERE id=" + job);
        fails("23514", "UPDATE public.grade_job SET lease_until=deadline_at WHERE id=" + job);
        updateJob(
                "state='RUNNING',worker_key='W"
                        + job
                        + "',lease_until=accepted_at+interval '30"
                        + " seconds',lease_gen=3,call_count=3");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state='RUNNING' AND call_count=3 FROM public.grade_job"
                                        + " WHERE id=?",
                                Boolean.class,
                                job))
                .isTrue();
        fails("23514", "UPDATE public.grade_job SET state='COMPLETED' WHERE id=" + job);
        fails("23514", "UPDATE public.grade_job SET lease_until=NULL WHERE id=" + job);
        long second = newJob("TWO", 1);
        fails(
                "23505",
                "UPDATE public.grade_job SET"
                    + " state='RUNNING',accepted_at='2026-01-01T00:00:00Z',deadline_at='2026-01-01T00:02:00Z',worker_key='W"
                        + job
                        + "',lease_until='2026-01-01T00:00:30Z' WHERE id="
                        + second);
        updateJob("state='COMPLETED',worker_key=NULL,lease_until=NULL");
        // 완료 결과의 필수 내용은 서비스 계약이다. 원본에 없는 DB terminal-result 제약을 발명하지 않는다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_data IS NULL FROM public.grade_job WHERE id=?",
                                Boolean.class,
                                job))
                .isTrue();
        jdbc.update(
                "UPDATE public.grade_job SET"
                    + " state='RUNNING',accepted_at='2026-01-01T00:00:00Z',deadline_at='2026-01-01T00:02:00Z',worker_key=?,lease_until='2026-01-01T00:00:30Z'"
                    + " WHERE id=?",
                "W" + job,
                second);
    }

    /** V23는 TEST 출처만 고정하고 활성 BATCH의 기존 출처·시간 fixture 갱신을 허용한다. */
    @Test
    void testSourcePinsImmutableWhileLegacyBatchAdjustmentsRemainAllowed() {
        long version =
                jdbc.queryForObject(
                        "SELECT version_id FROM public.review_snapshot WHERE id=?",
                        Long.class,
                        snapshot);
        jdbc.update(
                "INSERT INTO public.story_role(version_id,code,name) VALUES (?,'A','역할"
                        + " A'),(?,'B','역할 B')",
                version,
                version);
        jdbc.update(
                "INSERT INTO public.story_pair(version_id,role_a,role_b) VALUES (?,'A','B')",
                version);
        long test =
                id(
                        "INSERT INTO"
                            + " public.play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,runtime_epoch,role_a,role_b,mode,invite_until,created_by)"
                            + " VALUES (?::uuid,?,?,?,?,0,'A','B','FUNCTIONAL',now()+interval '7"
                            + " days',?) RETURNING id",
                        UUID.randomUUID().toString(),
                        version,
                        snapshot,
                        runtime,
                        HASH,
                        owner);
        long proposer =
                id(
                        "INSERT INTO public.member_account(member_key,state) VALUES"
                                + " (?::uuid,'ACTIVE') RETURNING id",
                        UUID.randomUUID().toString());
        long acceptor =
                id(
                        "INSERT INTO public.member_account(member_key,state) VALUES"
                                + " (?::uuid,'ACTIVE') RETURNING id",
                        UUID.randomUUID().toString());
        jdbc.update(
                "INSERT INTO public.test_member(test_id,member_id,slot) VALUES (?,?,1),(?,?,2)",
                test,
                proposer,
                test,
                acceptor);
        long report =
                id(
                        "INSERT INTO"
                            + " public.test_report(report_key,test_id,snapshot_id,runtime_id,config_hash,runtime_epoch,source_draft_rev,proposer_id,payload_cipher,payload_hash)"
                            + " VALUES (?::uuid,?,?,?,?,0,0,?,decode(repeat('00',32),'hex'),?)"
                            + " RETURNING id",
                        UUID.randomUUID().toString(),
                        test,
                        snapshot,
                        runtime,
                        HASH,
                        proposer,
                        HASH);
        jdbc.update(
                "UPDATE public.test_report SET"
                    + " state='ACCEPTED',accepted_by=?,accepted_at=created_at+interval '1"
                    + " second',submit_no=1,updated_at=created_at+interval '1 second' WHERE id=?",
                acceptor,
                report);
        for (String pins :
                List.of(
                        "config_hash,accepted_at+interval '1 second',accepted_at+interval '121"
                                + " seconds','QUEUED'",
                        "config_hash,accepted_at,accepted_at+interval '119 seconds','QUEUED'",
                        "'"
                                + "b".repeat(64)
                                + "',accepted_at,accepted_at+interval '120 seconds','QUEUED'",
                        "config_hash,accepted_at,accepted_at+interval '120 seconds','STAGED'")) {
            failsConstraint(
                    "23514",
                    "grade_job TEST report pin mismatch",
                    "INSERT INTO"
                        + " public.grade_job(job_key,snapshot_id,runtime_id,report_id,report_hash,rubric_hash,config_hash,accepted_at,deadline_at,state)"
                        + " SELECT '"
                            + UUID.randomUUID()
                            + "'::uuid,snapshot_id,runtime_id,id,'"
                            + HASH
                            + "','"
                            + HASH
                            + "',"
                            + pins
                            + " FROM public.test_report WHERE id="
                            + report);
        }
        long testJob =
                id(
                        "INSERT INTO"
                            + " public.grade_job(job_key,snapshot_id,runtime_id,report_id,report_hash,config_hash,rubric_hash,state,accepted_at,deadline_at)"
                            + " SELECT"
                            + " ?::uuid,snapshot_id,runtime_id,id,?,config_hash,?,'QUEUED',accepted_at,accepted_at+interval"
                            + " '120 seconds' FROM public.test_report WHERE id=? RETURNING id",
                        UUID.randomUUID().toString(),
                        HASH,
                        HASH,
                        report);
        var original =
                jdbc.queryForMap(
                        "SELECT"
                            + " snapshot_id,runtime_id,report_id,report_hash,config_hash,rubric_hash,accepted_at,deadline_at"
                            + " FROM public.grade_job WHERE id=?",
                        testJob);
        for (String fields :
                List.of(
                        "snapshot_id=" + otherSnapshot,
                        "runtime_id=9223372036854775807",
                        "report_id=9223372036854775807",
                        "report_hash='" + "b".repeat(64) + "'",
                        "config_hash='" + "b".repeat(64) + "'",
                        "rubric_hash='" + "b".repeat(64) + "'",
                        "accepted_at=accepted_at+interval '1 second'",
                        "accepted_at=accepted_at+interval '1"
                                + " second',deadline_at=deadline_at+interval '1 second'",
                        "deadline_at=deadline_at+interval '1 second'",
                        "report_id=NULL,report_hash=NULL,batch_id="
                                + batch
                                + ",sample_code='FROM_TEST',repeat_no=1,input_hash='"
                                + HASH
                                + "'")) {
            failsConstraint(
                    "23514",
                    "accepted grade_job source immutable",
                    "UPDATE public.grade_job SET " + fields + " WHERE id=" + testJob);
        }
        assertThat(
                        jdbc.queryForMap(
                                "SELECT"
                                    + " snapshot_id,runtime_id,report_id,report_hash,config_hash,rubric_hash,accepted_at,deadline_at"
                                    + " FROM public.grade_job WHERE id=?",
                                testJob))
                .isEqualTo(original);
        assertThat(
                        jdbc.update(
                                "UPDATE public.grade_job SET report_hash=report_hash,state='FAILED'"
                                        + " WHERE id=?",
                                testJob))
                .isEqualTo(1);

        String conversion =
                "batch_id=NULL,sample_code=NULL,repeat_no=NULL,input_hash=NULL,report_id="
                        + report
                        + ",report_hash='"
                        + HASH
                        + "',state='QUEUED',accepted_at=(SELECT accepted_at FROM public.test_report"
                        + " WHERE id="
                        + report
                        + "),deadline_at=(SELECT accepted_at+interval '120 seconds' FROM"
                        + " public.test_report WHERE id="
                        + report
                        + ")";
        failsConstraint(
                "23514",
                "accepted grade_job source immutable",
                "UPDATE public.grade_job SET " + conversion + " WHERE id=" + job);
        updateJob(
                "state='QUEUED',accepted_at='2026-01-01T00:00:00Z',deadline_at='2026-01-01T00:02:00Z'");
        updateJob(
                "sample_code='ADJUSTED',repeat_no=2,input_hash='"
                        + "b".repeat(64)
                        + "',config_hash='"
                        + "b".repeat(64)
                        + "',rubric_hash='"
                        + "b".repeat(64)
                        + "',accepted_at='2026-01-02T00:00:00Z',deadline_at='2026-01-02T00:02:00Z'");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT report_id IS NULL AND report_hash IS NULL AND"
                                    + " sample_code='ADJUSTED' AND repeat_no=2 AND input_hash=? AND"
                                    + " accepted_at='2026-01-02T00:00:00Z' AND"
                                    + " deadline_at=accepted_at+interval '120 seconds' FROM"
                                    + " public.grade_job WHERE id=?",
                                Boolean.class,
                                "b".repeat(64),
                                job))
                .isTrue();
        failsConstraint(
                "23514",
                "accepted grade_job source immutable",
                "UPDATE public.grade_job SET " + conversion + " WHERE id=" + job);
    }

    @Test
    void batchTerminalAndAttemptFencing() {
        for (String fields :
                List.of(
                        "repeat_count=2",
                        "expected_count=0",
                        "expected_count=4",
                        "runtime_epoch=-1",
                        "state='UNKNOWN'",
                        "purpose='GAME'",
                        "passed_yn=true",
                        "ended_at=now()",
                        "state='COMPLETED'")) {
            fails("23514", "UPDATE public.grade_batch SET " + fields + " WHERE id=" + batch);
        }
        jdbc.update(
                "UPDATE public.grade_batch SET state='COMPLETED',ended_at=now(),passed_yn=false"
                        + " WHERE id=?",
                batch);
        fails("23514", "UPDATE public.grade_batch SET state='FAILED' WHERE id=" + batch);
        jdbc.update(
                "UPDATE public.grade_batch SET state='FAILED',passed_yn=NULL WHERE id=?", batch);
        jdbc.update(
                "INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                        + " VALUES (?,1,1,'W','RUNNING')",
                job);
        fails(
                "23505",
                "INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                        + " VALUES ("
                        + job
                        + ",2,1,'W','RUNNING')");
        fails(
                "23505",
                "INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                        + " VALUES ("
                        + job
                        + ",1,2,'W','RUNNING')");
        for (String fields :
                List.of(
                        "attempt_no=0",
                        "attempt_no=4",
                        "lease_gen=0",
                        "lease_gen=-1",
                        "state='UNKNOWN'",
                        "state='SUCCEEDED'",
                        "ended_at=now()")) {
            fails("23514", "UPDATE public.grade_attempt SET " + fields + " WHERE job_id=" + job);
        }
        fails("23502", "UPDATE public.grade_attempt SET worker_key=NULL WHERE job_id=" + job);
        fails(
                "23503",
                "UPDATE public.grade_attempt SET job_id=9223372036854775807 WHERE job_id=" + job);
        for (String state : List.of("SUCCEEDED", "FAILED", "EXPIRED"))
            jdbc.update(
                    "UPDATE public.grade_attempt SET state=?,ended_at=now() WHERE job_id=?",
                    state,
                    job);
        jdbc.update(
                "INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                        + " VALUES (?,3,3,'W','RUNNING')",
                job);
        fails("23503", "DELETE FROM public.grade_job WHERE id=" + job);
    }

    @Test
    void hashJsonAndCipherBounds() {
        jdbc.update(
                "INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                        + " VALUES (?,1,1,'W','RUNNING')",
                job);
        for (String table : TABLES) {
            String where =
                    table.equals("grade_attempt")
                            ? "job_id=" + job
                            : "id="
                                    + (table.equals("grade_runtime")
                                            ? runtime
                                            : table.equals("grade_batch") ? batch : job);
            List<String> hashes =
                    switch (table) {
                        case "grade_runtime" -> List.of("config_hash");
                        case "grade_batch" ->
                                List.of(
                                        "dataset_hash",
                                        "rubric_hash",
                                        "payload_hash",
                                        "config_hash");
                        case "grade_job" ->
                                List.of("input_hash", "config_hash", "rubric_hash", "result_hash");
                        default -> List.of("output_hash");
                    };
            for (String field : hashes) {
                for (String bad : List.of("A".repeat(64), "g".repeat(64), "a".repeat(63)))
                    fails(
                            "23514",
                            "UPDATE public."
                                    + table
                                    + " SET "
                                    + field
                                    + "='"
                                    + bad
                                    + "' WHERE "
                                    + where);
                jdbc.update("UPDATE public." + table + " SET " + field + "=? WHERE " + where, HASH);
            }
        }
        for (String table : List.of("grade_runtime", "grade_job", "grade_attempt")) {
            String field =
                    table.equals("grade_runtime")
                            ? "config_data"
                            : table.equals("grade_job") ? "result_data" : "completion_data";
            String where =
                    table.equals("grade_runtime")
                            ? "id=" + runtime
                            : table.equals("grade_job") ? "id=" + job : "job_id=" + job;
            for (String invalid : List.of("'[]'", "'null'", "'1'", "'\"text\"'"))
                fails(
                        "23514",
                        "UPDATE public."
                                + table
                                + " SET "
                                + field
                                + "="
                                + invalid
                                + "::jsonb WHERE "
                                + where);
            int overhead =
                    jdbc.queryForObject(
                            "SELECT octet_length(jsonb_build_object('x','')::text)", Integer.class);
            int chars = (131072 - overhead) / 4;
            int remainder = (131072 - overhead) % 4;
            String exact =
                    "jsonb_build_object('x',repeat('𐐀',"
                            + chars
                            + ")||repeat('a',"
                            + remainder
                            + "))";
            jdbc.update(
                    "UPDATE public." + table + " SET " + field + "=" + exact + " WHERE " + where);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT octet_length("
                                            + field
                                            + "::text) FROM public."
                                            + table
                                            + " WHERE "
                                            + where,
                                    Integer.class))
                    .isEqualTo(131072);
            fails(
                    "23514",
                    "UPDATE public."
                            + table
                            + " SET "
                            + field
                            + "=jsonb_build_object('x',repeat('𐐀',"
                            + chars
                            + ")||repeat('a',"
                            + (remainder + 1)
                            + ")) WHERE "
                            + where);
            if (!table.equals("grade_runtime"))
                jdbc.update("UPDATE public." + table + " SET " + field + "=NULL WHERE " + where);
        }
        for (String table : List.of("grade_job", "grade_attempt")) {
            String field = table.equals("grade_job") ? "result_cipher" : "output_cipher";
            String where = table.equals("grade_job") ? "id=" + job : "job_id=" + job;
            for (int length : List.of(32, 524288))
                jdbc.update(
                        "UPDATE public."
                                + table
                                + " SET "
                                + field
                                + "=decode(repeat('00',"
                                + length
                                + "),'hex') WHERE "
                                + where);
            for (int length : List.of(0, 31, 524289))
                fails(
                        "23514",
                        "UPDATE public."
                                + table
                                + " SET "
                                + field
                                + "=decode(repeat('00',"
                                + length
                                + "),'hex') WHERE "
                                + where);
            jdbc.update("UPDATE public." + table + " SET " + field + "=NULL WHERE " + where);
        }
        fails("23514", "UPDATE public.grade_runtime SET state='UNKNOWN' WHERE id=" + runtime);
        fails("23514", "UPDATE public.grade_runtime SET epoch=-1 WHERE id=" + runtime);
    }

    /** 새 검수 사본은 테스트 내부에서만 만든다. */
    private long snapshot(long version) {
        return id(
                "INSERT INTO"
                    + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                    + " VALUES (?,0,'{}',?::uuid,?) RETURNING id",
                version,
                UUID.randomUUID().toString(),
                owner);
    }

    /** 출처가 완비된 미활성 회귀 작업을 만든다. */
    private long newJob(String sample, int repeat) {
        return id(
                "INSERT INTO"
                    + " public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)"
                    + " VALUES (?::uuid,?,?,?, ?,?,'STAGED',?,?,?) RETURNING id",
                UUID.randomUUID().toString(),
                snapshot,
                runtime,
                batch,
                sample,
                repeat,
                HASH,
                HASH,
                HASH);
    }

    /** 식별자를 반환하는 테스트 INSERT를 실행한다. */
    private long id(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    /** 현재 시험 작업을 갱신한다. */
    private void updateJob(String fields) {
        assertThat(jdbc.update("UPDATE public.grade_job SET " + fields + " WHERE id=?", job))
                .isEqualTo(1);
    }

    /** SQLSTATE와 실제 CHECK 또는 trigger 메시지를 함께 확인한다. */
    private static void failsConstraint(String state, String constraint, String sql) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraint)
                .satisfies(
                        error ->
                                assertThat(((java.sql.SQLException) error.getCause()).getSQLState())
                                        .isEqualTo(state));
    }

    /** 자동 커밋의 한 실패 문장을 검사하므로 다음 시험에 실패 트랜잭션을 남기지 않는다. */
    private static void fails(String state, String sql) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .isInstanceOf(DataAccessException.class)
                .satisfies(
                        error ->
                                assertThat(((java.sql.SQLException) error.getCause()).getSQLState())
                                        .isEqualTo(state));
    }
}
