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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** 승인된 최소 감사 물리 구조만 검사한다. 완료·복구 서비스나 운영 배포의 검증이 아니다. */
class GradeEventSchemaIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private long job;
    private long noAttemptJob;

    /** 고정 digest의 폐기 DB에 현재 V1~V18을 적용하며 일반 DB나 환경 자격은 사용하지 않는다. */
    @BeforeAll
    static void open() {
        postgres =
                new PostgreSQLContainer<>(
                        DockerImageName.parse(GradeSchemaIT.IMAGE)
                                .asCompatibleSubstituteFor("postgres"));
        try {
            postgres.start();
            var flyway =
                    Flyway.configure()
                            .resourceProvider(new EmbeddedSqlResourceProvider())
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations("classpath:db/migration")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(19);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
            jdbc = GradeSchemaIT.jdbc(postgres);
        } catch (RuntimeException | AssertionError failure) {
            postgres.close();
            throw failure;
        }
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 실제 FK 부모를 합성 행으로 생성한다. {} 사본은 관계 fixture이지 의미·품질 근거가 아니다. */
    @BeforeEach
    void fixtures() {
        long owner =
                id(
                        "INSERT INTO public.admin_account(account_key) VALUES (?) RETURNING id",
                        UUID.randomUUID());
        long story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        token(),
                        owner);
        long version =
                id(
                        """
                        INSERT INTO public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)
                        VALUES (?,1,'감사 관계 fixture','H2',?,?) RETURNING id
                        """,
                        story,
                        owner,
                        owner);
        long snapshot =
                id(
                        """
                        INSERT INTO public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)
                        VALUES (?,0,'{}',?,?) RETURNING id
                        """,
                        version,
                        UUID.randomUUID(),
                        owner);
        long runtime =
                id(
                        "INSERT INTO public.grade_runtime(code,config_hash,config_data,state)"
                                + " VALUES (?,?,'{}','AVAILABLE') RETURNING id",
                        token(),
                        GradeSchemaIT.HASH);
        long batch =
                id(
                        """
                        INSERT INTO public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,
                            rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)
                        VALUES (?,?,?,'REVIEW',?,?,?,?,0,'STAGED',3,?) RETURNING id
                        """,
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH,
                        GradeSchemaIT.HASH,
                        owner);
        job = newJob(snapshot, runtime, batch, "ONE");
        noAttemptJob = newJob(snapshot, runtime, batch, "TWO");
        jdbc.update(
                "INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                        + " VALUES (?,1,1,'fixture-worker','RUNNING')",
                job);
        jdbc.update(
                "INSERT INTO public.grade_attempt(job_id,attempt_no,lease_gen,worker_key,state)"
                        + " VALUES (?,3,3,'fixture-worker','RUNNING')",
                job);
    }

    @Test
    void exactElevenColumnsIdentityClockNullabilityAndComments() {
        var columns =
                jdbc.queryForList(
                        """
                        SELECT a.attname,format_type(a.atttypid,a.atttypmod) AS type,a.attnotnull,a.attidentity,
                            pg_get_expr(d.adbin,d.adrelid) AS def,col_description(a.attrelid,a.attnum) AS comment
                        FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                        WHERE a.attrelid='public.grade_event'::regclass AND a.attnum>0 AND NOT a.attisdropped
                        ORDER BY a.attnum
                        """);
        assertThat(
                        columns.stream()
                                .map(
                                        c ->
                                                c.get("attname")
                                                        + ":"
                                                        + c.get("type")
                                                        + ":"
                                                        + c.get("attnotnull"))
                                .toList())
                .containsExactly(
                        "id:bigint:true",
                        "job_id:bigint:true",
                        "attempt_no:smallint:false",
                        "actor_kind:character varying(8):true",
                        "actor_key:character varying(80):true",
                        "event_kind:character varying(24):true",
                        "command_key:uuid:true",
                        "request_id:uuid:false",
                        "command_hash:character(64):true",
                        "detail:jsonb:true",
                        "created_at:timestamp with time zone:true");
        for (var column : columns) {
            String name = (String) column.get("attname");
            assertThat(column.get("attidentity")).isEqualTo(name.equals("id") ? "a" : "");
            assertThat(column.get("def"))
                    .isEqualTo(name.equals("created_at") ? "clock_timestamp()" : null);
            assertThat((String) column.get("comment")).containsPattern("[가-힣]");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT obj_description('public.grade_event'::regclass,'pg_class')",
                                String.class))
                .containsPattern("[가-힣]");
        var access =
                jdbc.queryForMap(
                        """
                        SELECT data_type,character_maximum_length,is_nullable,column_default
                        FROM information_schema.columns WHERE table_schema='public' AND table_name='access_history' AND column_name='worker_key'
                        """);
        assertThat(access)
                .containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 80)
                .containsEntry("is_nullable", "YES")
                .containsEntry("column_default", null);
    }

    @Test
    void exactNineConstraintsTwoIndexesAndOrderedNoActionForeignKeys() throws Exception {
        var constraints =
                jdbc.queryForList(
                        """
                        SELECT conname,contype,confmatchtype,pg_get_constraintdef(oid) AS def,
                            obj_description(oid,'pg_constraint') AS comment FROM pg_constraint
                        WHERE conrelid='public.grade_event'::regclass
                        """);
        assertThat(constraints.stream().map(c -> c.get("conname")).toList())
                .containsExactlyInAnyOrder(
                        "pk_grade_event",
                        "uk_grade_event_command",
                        "fk_grade_event_job",
                        "fk_grade_event_attempt",
                        "ck_grade_event_attempt",
                        "ck_grade_event_actor_key",
                        "ck_grade_event_command_hash",
                        "ck_grade_event_detail",
                        "ck_grade_event_shape");
        Map<String, String> definitions = new HashMap<>();
        for (var constraint : constraints) {
            assertThat((String) constraint.get("comment")).containsPattern("[가-힣]");
            definitions.put((String) constraint.get("conname"), (String) constraint.get("def"));
            if ("f".equals(constraint.get("contype")))
                assertThat(constraint.get("confmatchtype")).isEqualTo("s");
        }
        assertThat(definitions.get("pk_grade_event")).isEqualTo("PRIMARY KEY (id)");
        assertThat(definitions.get("uk_grade_event_command"))
                .isEqualTo("UNIQUE (actor_kind, actor_key, command_key)");
        assertThat(definitions.get("ck_grade_event_actor_key"))
                .contains("^[A-Za-z0-9_-]{1,80}$", "COLLATE \"C\"");
        assertThat(definitions.get("ck_grade_event_command_hash")).contains("^[0-9a-f]{64}$");
        assertThat(definitions.get("ck_grade_event_attempt"))
                .contains("attempt_no IS NULL", "attempt_no >= 1", "attempt_no <= 3");
        assertThat(definitions.get("ck_grade_event_detail"))
                .contains("jsonb_typeof(detail)", "object", "octet_length", "4096");
        assertThat(definitions.get("ck_grade_event_shape"))
                .contains(
                        "WORKER",
                        "SYSTEM",
                        "COMPLETE_APPLIED",
                        "COMPLETE_REJECTED",
                        "RECOVERY_EXPIRED",
                        "JOB_ACTIVATED",
                        "JOB_INPUT_REJECTED",
                        "JOB_SOURCE_CANCELLED",
                        "JOB_LEASE_RECLAIMED",
                        "attempt_no IS NULL",
                        "attempt_no IS NOT NULL",
                        "request_id IS NULL",
                        "request_id IS NOT NULL");
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND"
                                + " tablename='grade_event'",
                        String.class);
        assertThat(indexes).containsExactlyInAnyOrder("pk_grade_event", "uk_grade_event_command");
        for (String index : indexes)
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT obj_description(?::regclass,'pg_class')",
                                    String.class,
                                    "public." + index))
                    .contains("미측정");
        Map<String, Map<Short, String>> foreign = new HashMap<>();
        try (var connection = postgres.createConnection("");
                var rows =
                        connection.getMetaData().getImportedKeys(null, "public", "grade_event")) {
            while (rows.next()) {
                assertThat(rows.getString("PKTABLE_SCHEM")).isEqualTo("public");
                assertThat(rows.getInt("DELETE_RULE"))
                        .isEqualTo(DatabaseMetaData.importedKeyNoAction);
                assertThat(rows.getInt("UPDATE_RULE"))
                        .isEqualTo(DatabaseMetaData.importedKeyNoAction);
                foreign.computeIfAbsent(rows.getString("FK_NAME"), ignored -> new TreeMap<>())
                        .put(
                                rows.getShort("KEY_SEQ"),
                                rows.getString("FKCOLUMN_NAME")
                                        + "->"
                                        + rows.getString("PKTABLE_NAME")
                                        + "."
                                        + rows.getString("PKCOLUMN_NAME"));
            }
        }
        assertThat(foreign)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "fk_grade_event_job", Map.of((short) 1, "job_id->grade_job.id"),
                                "fk_grade_event_attempt",
                                        Map.of(
                                                (short) 1,
                                                "job_id->grade_attempt.job_id",
                                                (short) 2,
                                                "attempt_no->grade_attempt.attempt_no")));
    }

    @Test
    void closedEventShapesRealParentsAndPerActorCommandUniqueness() {
        for (String kind : List.of("COMPLETE_APPLIED", "COMPLETE_REJECTED")) {
            assertThat(
                            event(
                                    job,
                                    1,
                                    "WORKER",
                                    "fixture-worker",
                                    kind,
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    GradeSchemaIT.HASH,
                                    "{}"))
                    .isPositive();
        }
        event(
                job,
                1,
                "SYSTEM",
                "deployment_coordinator",
                "RECOVERY_EXPIRED",
                UUID.randomUUID(),
                null,
                GradeSchemaIT.HASH,
                "{}");
        event(
                job,
                3,
                "WORKER",
                "fixture-worker",
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        for (String kind :
                List.of(
                        "JOB_ACTIVATED",
                        "JOB_INPUT_REJECTED",
                        "JOB_SOURCE_CANCELLED",
                        "JOB_LEASE_RECLAIMED")) {
            event(
                    noAttemptJob,
                    null,
                    "SYSTEM",
                    "deployment_coordinator",
                    kind,
                    UUID.randomUUID(),
                    null,
                    GradeSchemaIT.HASH,
                    "{}");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=?",
                                Long.class,
                                noAttemptJob))
                .isZero();
        UUID command = UUID.randomUUID();
        event(
                job,
                1,
                "WORKER",
                "worker-A",
                "COMPLETE_APPLIED",
                command,
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        reject(
                "23505",
                job,
                1,
                "WORKER",
                "worker-A",
                "COMPLETE_REJECTED",
                command,
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        event(
                job,
                1,
                "WORKER",
                "worker-B",
                "COMPLETE_REJECTED",
                command,
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        event(
                noAttemptJob,
                null,
                "SYSTEM",
                "worker-A",
                "JOB_ACTIVATED",
                command,
                null,
                GradeSchemaIT.HASH,
                "{}");
        reject(
                "23503",
                Long.MAX_VALUE,
                null,
                "SYSTEM",
                "coordinator",
                "JOB_ACTIVATED",
                UUID.randomUUID(),
                null,
                GradeSchemaIT.HASH,
                "{}");
        reject(
                "23503",
                noAttemptJob,
                1,
                "WORKER",
                "worker",
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        reject(
                "23503",
                job,
                2,
                "WORKER",
                "worker",
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        for (String kind :
                List.of(
                        "COMPLETE_APPLIED",
                        "COMPLETE_REJECTED",
                        "RECOVERY_EXPIRED",
                        "JOB_ACTIVATED",
                        "JOB_INPUT_REJECTED",
                        "JOB_SOURCE_CANCELLED",
                        "JOB_LEASE_RECLAIMED",
                        "UNKNOWN")) {
            for (String actor : List.of("WORKER", "SYSTEM", "ADMIN", "MEMBER")) {
                for (Integer attempt : new Integer[] {null, 1}) {
                    for (UUID request : new UUID[] {null, UUID.randomUUID()}) {
                        boolean valid =
                                actor.equals("WORKER")
                                                && kind.startsWith("COMPLETE_")
                                                && attempt != null
                                                && request != null
                                        || actor.equals("SYSTEM")
                                                && kind.equals("RECOVERY_EXPIRED")
                                                && attempt != null
                                                && request == null
                                        || actor.equals("SYSTEM")
                                                && kind.startsWith("JOB_")
                                                && attempt == null
                                                && request == null;
                        if (!valid)
                            reject(
                                    "23514",
                                    job,
                                    attempt,
                                    actor,
                                    "actor",
                                    kind,
                                    UUID.randomUUID(),
                                    request,
                                    GradeSchemaIT.HASH,
                                    "{}");
                    }
                }
            }
        }
    }

    /** 실제 예약 없는 회수 사건도 실제 작업 FK를 요구하며 가짜 시도나 요청을 허용하지 않는다. */
    @Test
    void leaseReclaimRequiresRealJobWithoutAttemptOrRequest() {
        long reclaimed =
                event(
                        noAttemptJob,
                        null,
                        "SYSTEM",
                        "deployment_coordinator",
                        "JOB_LEASE_RECLAIMED",
                        UUID.randomUUID(),
                        null,
                        GradeSchemaIT.HASH,
                        "{}");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT"
                                    + " job_id,attempt_no,actor_kind,actor_key,event_kind,request_id"
                                    + " FROM public.grade_event WHERE id=?",
                                reclaimed))
                .containsEntry("job_id", noAttemptJob)
                .containsEntry("attempt_no", null)
                .containsEntry("actor_kind", "SYSTEM")
                .containsEntry("actor_key", "deployment_coordinator")
                .containsEntry("event_kind", "JOB_LEASE_RECLAIMED")
                .containsEntry("request_id", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_attempt WHERE job_id=?",
                                Long.class,
                                noAttemptJob))
                .isZero();
        reject(
                "23503",
                Long.MAX_VALUE,
                null,
                "SYSTEM",
                "deployment_coordinator",
                "JOB_LEASE_RECLAIMED",
                UUID.randomUUID(),
                null,
                GradeSchemaIT.HASH,
                "{}");
    }

    @Test
    void nullsAsciiHashAndUtf8ObjectBounds() {
        reject(
                "22001",
                job,
                1,
                "ANONYMOUS",
                "actor",
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        for (String field :
                List.of(
                        "job_id",
                        "actor_kind",
                        "actor_key",
                        "event_kind",
                        "command_key",
                        "command_hash",
                        "detail",
                        "created_at")) {
            long id =
                    event(
                            noAttemptJob,
                            null,
                            "SYSTEM",
                            "coordinator",
                            "JOB_ACTIVATED",
                            UUID.randomUUID(),
                            null,
                            GradeSchemaIT.HASH,
                            "{}");
            fails("23502", "UPDATE grade_event SET " + field + "=NULL WHERE id=" + id);
        }
        for (int attempt : List.of(0, 4))
            reject(
                    "23514",
                    job,
                    attempt,
                    "WORKER",
                    "worker",
                    "COMPLETE_APPLIED",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    GradeSchemaIT.HASH,
                    "{}");
        for (String actor : List.of("", "한글", "a b", "a.b", "a\n", "é"))
            reject(
                    "23514",
                    job,
                    1,
                    "WORKER",
                    actor,
                    "COMPLETE_APPLIED",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    GradeSchemaIT.HASH,
                    "{}");
        reject(
                "22001",
                job,
                1,
                "WORKER",
                "a".repeat(81),
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        event(
                job,
                1,
                "WORKER",
                "a".repeat(78) + "_-",
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        for (String hash : List.of("A".repeat(64), "g".repeat(64), "a".repeat(63)))
            reject(
                    "23514",
                    job,
                    1,
                    "WORKER",
                    "worker",
                    "COMPLETE_APPLIED",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    hash,
                    "{}");
        for (String detail : List.of("[]", "null", "1", "\"text\""))
            reject(
                    "23514",
                    job,
                    1,
                    "WORKER",
                    "worker",
                    "COMPLETE_APPLIED",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    GradeSchemaIT.HASH,
                    detail);
        int overhead =
                jdbc.queryForObject(
                        "SELECT octet_length(jsonb_build_object('x','')::text)", Integer.class);
        int chars = (4096 - overhead) / 4;
        int remainder = (4096 - overhead) % 4;
        String exact = "{\"x\":\"" + "𐐀".repeat(chars) + "a".repeat(remainder) + "\"}";
        long id =
                event(
                        job,
                        1,
                        "WORKER",
                        "worker",
                        "COMPLETE_APPLIED",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        GradeSchemaIT.HASH,
                        exact);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT octet_length(detail::text) FROM grade_event WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(4096);
        reject(
                "23514",
                job,
                1,
                "WORKER",
                "worker",
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                exact.replace("\"}", "a\"}"));
        fails(
                "428C9",
                "INSERT INTO"
                    + " grade_event(id,job_id,actor_kind,actor_key,event_kind,command_key,command_hash,detail)"
                    + " VALUES (999,"
                        + job
                        + ",'SYSTEM','coordinator','JOB_ACTIVATED','"
                        + UUID.randomUUID()
                        + "','"
                        + GradeSchemaIT.HASH
                        + "','{}')");
    }

    @Test
    void operationClockIsObservedAtInsertNotTransactionStart() throws Exception {
        try (var connection = postgres.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_sleep(0.02)");
                try (var insert =
                        connection.prepareStatement(
                                """
                                INSERT INTO grade_event(job_id,actor_kind,actor_key,event_kind,command_key,command_hash,detail)
                                VALUES (?,'SYSTEM','coordinator','JOB_ACTIVATED',?,?,'{}') RETURNING created_at>now(),created_at<=clock_timestamp()
                                """)) {
                    insert.setLong(1, noAttemptJob);
                    insert.setObject(2, UUID.randomUUID());
                    insert.setString(3, GradeSchemaIT.HASH);
                    try (var rows = insert.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBoolean(1)).isTrue();
                        assertThat(rows.getBoolean(2)).isTrue();
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void accessWorkerIsStringActorWithoutUuidAndCannotNavigate() {
        for (String actor : List.of("ADMIN", "MEMBER", "ANONYMOUS", "WORKER")) {
            UUID key = List.of("ADMIN", "MEMBER").contains(actor) ? UUID.randomUUID() : null;
            String worker = actor.equals("WORKER") ? "fixture-worker_1" : null;
            assertThat(access("SERVER", actor, key, worker)).isEqualTo(1);
            if (List.of("ADMIN", "MEMBER").contains(actor))
                assertThat(access("NAV", actor, key, worker)).isEqualTo(1);
            else accessFails("23514", "NAV", actor, key, worker);
            if (!actor.equals("WORKER")) accessFails("23514", "SERVER", actor, key, "worker");
        }
        accessFails("23514", "SERVER", "ANONYMOUS", UUID.randomUUID(), null);
        for (String actor : List.of("ADMIN", "MEMBER"))
            accessFails("23514", "SERVER", actor, null, null);
        for (UUID key : new UUID[] {UUID.randomUUID(), new UUID(0, 0)})
            accessFails("23514", "SERVER", "WORKER", key, "worker");
        for (String actor : List.of("ADMIN", "MEMBER", "ANONYMOUS", "WORKER")) {
            for (UUID key : new UUID[] {null, UUID.randomUUID()}) {
                for (String worker : new String[] {null, "worker"}) {
                    boolean valid =
                            actor.equals("ANONYMOUS") && key == null && worker == null
                                    || List.of("ADMIN", "MEMBER").contains(actor)
                                            && key != null
                                            && worker == null
                                    || actor.equals("WORKER") && key == null && worker != null;
                    if (!valid) accessFails("23514", "SERVER", actor, key, worker);
                }
            }
        }
        accessFails("23514", "SERVER", "WORKER", UUID.randomUUID(), null);
        for (String worker : new String[] {null, "", "한글", "worker.name", "a b", "a\n"})
            accessFails("23514", "SERVER", "WORKER", null, worker);
        accessFails("22001", "SERVER", "WORKER", null, "a".repeat(81));
        assertThat(access("SERVER", "WORKER", null, "a".repeat(80))).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT bool_and(actor_key IS NULL) FROM access_history WHERE"
                                        + " actor_kind='WORKER'",
                                Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND"
                                        + " tablename='access_history'",
                                String.class))
                .containsExactlyInAnyOrder(
                        "pk_access_history",
                        "uq_access_history_request",
                        "uq_access_history_event",
                        "ix_access_history_actor");
        assertThat(
                        jdbc.queryForList(
                                "SELECT conname FROM pg_constraint WHERE"
                                        + " conrelid='public.access_history'::regclass",
                                String.class))
                .containsExactlyInAnyOrder(
                        "pk_access_history",
                        "ck_access_history_kind",
                        "ck_access_history_actor",
                        "ck_access_history_status",
                        "ck_access_history_shape",
                        "ck_access_history_target");
    }

    @Test
    void accessOriginalServerShapeStatusTargetAndUniquenessRemainEnforced() {
        access("SERVER", "WORKER", null, "worker");
        long id = jdbc.queryForObject("SELECT max(id) FROM access_history", Long.class);
        for (String change :
                List.of(
                        "route=NULL",
                        "method=NULL",
                        "started_at=NULL",
                        "event_key='" + UUID.randomUUID() + "'",
                        "screen_code='FIXTURE'",
                        "from_screen_code='FIXTURE'",
                        "client_at=clock_timestamp()",
                        "http_status=99",
                        "http_status=600",
                        "duration_ms=-1",
                        "target_kind='GRADE_JOB'",
                        "target_key='" + UUID.randomUUID() + "'")) {
            fails("23514", "UPDATE access_history SET " + change + " WHERE id=" + id);
        }
        for (int status : List.of(100, 599))
            jdbc.update(
                    "UPDATE access_history SET http_status=?,duration_ms=0 WHERE id=?", status, id);
        jdbc.update(
                "UPDATE access_history SET target_kind='GRADE_JOB',target_key=? WHERE id=?",
                UUID.randomUUID(),
                id);
        fails(
                "23505",
                "INSERT INTO"
                    + " access_history(kind,request_id,actor_kind,worker_key,route,method,started_at)"
                    + " SELECT kind,request_id,actor_kind,worker_key,route,method,started_at FROM"
                    + " access_history WHERE id="
                        + id);
        access("NAV", "ADMIN", UUID.randomUUID(), null);
        long nav = jdbc.queryForObject("SELECT max(id) FROM access_history", Long.class);
        for (String change :
                List.of(
                        "event_key=NULL",
                        "screen_code=NULL",
                        "http_status=200",
                        "duration_ms=0",
                        "route='/fixture'",
                        "method='GET'",
                        "started_at=clock_timestamp()",
                        "ended_at=clock_timestamp()",
                        "error_code='FIXTURE'",
                        "target_kind='GRADE_JOB'",
                        "target_key='" + UUID.randomUUID() + "'")) {
            fails("23514", "UPDATE access_history SET " + change + " WHERE id=" + nav);
        }
        jdbc.update(
                "UPDATE access_history SET from_screen_code='FIXTURE',client_at=clock_timestamp()"
                        + " WHERE id=?",
                nav);
        fails(
                "23505",
                "INSERT INTO"
                    + " access_history(kind,request_id,event_key,actor_kind,actor_key,screen_code)"
                    + " SELECT kind,'"
                        + UUID.randomUUID()
                        + "',event_key,actor_kind,actor_key,screen_code FROM access_history WHERE"
                        + " id="
                        + nav);
    }

    @Test
    void auditReferencesBlockParentDeletionAndKeyUpdates() {
        event(
                job,
                1,
                "WORKER",
                "worker",
                "COMPLETE_APPLIED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                GradeSchemaIT.HASH,
                "{}");
        fails("23503", "DELETE FROM grade_attempt WHERE job_id=" + job + " AND attempt_no=1");
        fails(
                "23503",
                "UPDATE grade_attempt SET attempt_no=2 WHERE job_id=" + job + " AND attempt_no=1");
        event(
                noAttemptJob,
                null,
                "SYSTEM",
                "coordinator",
                "JOB_ACTIVATED",
                UUID.randomUUID(),
                null,
                GradeSchemaIT.HASH,
                "{}");
        fails("23503", "DELETE FROM grade_job WHERE id=" + noAttemptJob);
    }

    @Test
    void disposableNonOwnerRoleCanOnlyInsertAndSelectOwnerCanStillRead() throws Exception {
        String role = "audit_app_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                "CREATE ROLE "
                        + role
                        + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION"
                        + " NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
        jdbc.execute("GRANT INSERT,SELECT ON public.grade_event TO " + role);
        String sequence =
                jdbc.queryForObject(
                        "SELECT pg_get_serial_sequence('public.grade_event','id')", String.class);
        jdbc.execute("GRANT USAGE ON SEQUENCE " + sequence + " TO " + role);
        long inserted;
        try (var connection = postgres.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            try (var rows =
                    statement.executeQuery(
                            "SELECT current_user,session_user,(SELECT rolcanlogin AND NOT rolsuper"
                                    + " FROM pg_roles WHERE rolname=current_user)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(role);
                assertThat(rows.getString(2)).isNotEqualTo(role);
                assertThat(rows.getBoolean(3)).isTrue();
            }
            try (var insert =
                    connection.prepareStatement(
                            """
                            INSERT INTO public.grade_event(job_id,actor_kind,actor_key,event_kind,command_key,command_hash,detail)
                            VALUES (?,'SYSTEM','coordinator','JOB_ACTIVATED',?,?,'{}') RETURNING id
                            """)) {
                insert.setLong(1, noAttemptJob);
                insert.setObject(2, UUID.randomUUID());
                insert.setString(3, GradeSchemaIT.HASH);
                try (var rows = insert.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    inserted = rows.getLong(1);
                }
            }
            try (var rows =
                    statement.executeQuery(
                            "SELECT id FROM public.grade_event WHERE id=" + inserted)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(inserted);
            }
            for (String sql :
                    List.of(
                            "UPDATE public.grade_event SET detail='{}' WHERE id=" + inserted,
                            "DELETE FROM public.grade_event WHERE id=" + inserted,
                            "TRUNCATE public.grade_event")) {
                assertThatThrownBy(() -> statement.execute(sql))
                        .isInstanceOf(SQLException.class)
                        .satisfies(
                                error ->
                                        assertThat(((SQLException) error).getSQLState())
                                                .isEqualTo("42501"));
            }
            statement.execute("RESET ROLE");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.grade_event WHERE id=?",
                                Long.class,
                                inserted))
                .isEqualTo(1);
    }

    /**
     * 실제 출처를 가진 미활성 작업을 만든다.
     *
     * @param snapshot null이 아닌 실제 사본 식별자
     * @param runtime null이 아닌 실제 실행 설정 식별자
     * @param batch 같은 사본의 실제 회귀 집합 식별자
     * @param sample null이 아닌 합성 fixture 코드
     * @return DB 생성 작업 식별자
     */
    private long newJob(long snapshot, long runtime, long batch, String sample) {
        return id(
                """
                INSERT INTO public.grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)
                VALUES (?,?,?,?,?,1,'STAGED',?,?,?) RETURNING id
                """,
                UUID.randomUUID(),
                snapshot,
                runtime,
                batch,
                sample,
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH,
                GradeSchemaIT.HASH);
    }

    /**
     * 최소 감사 INSERT를 실행한다. 잘못된 입력도 그대로 전달하여 실제 DB 제약을 검사한다.
     *
     * @param target 실제 작업 식별자 또는 음성 시험의 존재하지 않는 값
     * @param attempt 실제 시도 1~3 또는 시도 없는 사건의 null
     * @param actor 주체 종류; 음성 시험은 미허용 값
     * @param key 합성 주체 키; 음성 시험은 잘못된 값
     * @param kind 사건 종류
     * @param command CSPRNG 합성 UUID
     * @param request 완료 요청 UUID 또는 SYSTEM의 null
     * @param hash 소문자 64자 해시 또는 음성 시험 값
     * @param detail JSON 객체 문자열 또는 음성 시험 값
     * @return DB 생성 감사 식별자
     * @throws DataAccessException 실제 DB 제약 위반
     */
    private long event(
            long target,
            Integer attempt,
            String actor,
            String key,
            String kind,
            UUID command,
            UUID request,
            String hash,
            String detail) {
        return id(
                """
                INSERT INTO public.grade_event(job_id,attempt_no,actor_kind,actor_key,event_kind,command_key,request_id,command_hash,detail)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb) RETURNING id
                """,
                target,
                attempt,
                actor,
                key,
                kind,
                command,
                request,
                hash,
                detail);
    }

    /**
     * 실패한 감사 INSERT의 SQLSTATE를 검사한다.
     *
     * @param state 기대 SQLSTATE
     * @param target 실제 또는 음성 시험의 미존재 작업 식별자
     * @param attempt 실제 또는 음성 시도 번호; null 허용
     * @param actor 음성 시험 주체 종류
     * @param key 음성 시험 합성 주체 키
     * @param kind 음성 시험 사건 종류
     * @param command CSPRNG 합성 명령 UUID
     * @param request 합성 요청 UUID; null 허용
     * @param hash 시험 명령 해시
     * @param detail 시험 JSON 문자열
     */
    private void reject(
            String state,
            long target,
            Integer attempt,
            String actor,
            String key,
            String kind,
            UUID command,
            UUID request,
            String hash,
            String detail) {
        assertThatThrownBy(
                        () ->
                                event(
                                        target, attempt, actor, key, kind, command, request, hash,
                                        detail))
                .isInstanceOf(DataAccessException.class)
                .satisfies(
                        error ->
                                assertThat(((SQLException) error.getCause()).getSQLState())
                                        .isEqualTo(state));
    }

    /**
     * 원문 없는 정규 접근 fixture를 만든다.
     *
     * @param kind SERVER 또는 NAV
     * @param actor 합성 주체 종류
     * @param key 관리자·회원 UUID; 실행기·익명은 null
     * @param worker 실행기 문자열; 그 외는 null
     * @return 삽입 행 수
     * @throws DataAccessException 실제 조합 제약 위반
     */
    private int access(String kind, String actor, UUID key, String worker) {
        return jdbc.update(
                """
                INSERT INTO public.access_history(kind,request_id,actor_kind,actor_key,worker_key,
                    route,method,started_at,event_key,screen_code)
                VALUES (?,?,?,?,?,CASE WHEN ?='SERVER' THEN '/internal/grade/complete' END,
                    CASE WHEN ?='SERVER' THEN 'POST' END,CASE WHEN ?='SERVER' THEN clock_timestamp() END,
                    CASE WHEN ?='NAV' THEN ?::uuid END,CASE WHEN ?='NAV' THEN 'FIXTURE' END)
                """,
                kind,
                UUID.randomUUID(),
                actor,
                key,
                worker,
                kind,
                kind,
                kind,
                kind,
                UUID.randomUUID(),
                kind);
    }

    /**
     * 접근 조합 실패를 검사한다.
     *
     * @param state 기대 SQLSTATE
     * @param kind SERVER 또는 NAV
     * @param actor 음성 시험 주체 종류
     * @param key 시험 UUID 주체 키; null 허용
     * @param worker 시험 문자열 실행기 키; null 허용
     */
    private void accessFails(String state, String kind, String actor, UUID key, String worker) {
        assertThatThrownBy(() -> access(kind, actor, key, worker))
                .isInstanceOf(DataAccessException.class)
                .satisfies(
                        error ->
                                assertThat(((SQLException) error.getCause()).getSQLState())
                                        .isEqualTo(state));
    }

    /**
     * 자동 커밋 실패 후 다음 시험을 오염시키지 않는다.
     *
     * @param state 기대 SQLSTATE
     * @param sql null이 아닌 내부 고정 시험 SQL; 사용자 입력 아님
     */
    private static void fails(String state, String sql) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .isInstanceOf(DataAccessException.class)
                .satisfies(
                        error ->
                                assertThat(((SQLException) error.getCause()).getSQLState())
                                        .isEqualTo(state));
    }

    /**
     * 실제 DB 생성 식별자를 읽는다.
     *
     * @param sql RETURNING id를 가진 내부 INSERT
     * @param args SQL 위치 인자; nullable 여부는 해당 시험 계약에 따름
     * @return 실제 행 식별자
     */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** CSPRNG UUID 기반 비밀 아닌 합성 키다. 실제 인증 토큰은 사용하지 않는다. */
    private String token() {
        return "S_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
