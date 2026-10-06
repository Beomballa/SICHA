package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.migration.EmbeddedSqlResourceProvider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 실제 부모를 가진 폐기형 물리 시험이다. 합성 행은 실제 모델 실행·품질·운영 승인 증거가 아니다. */
class GradeEvidenceSchemaIT {
    private static final String IMAGE =
            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1";
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private long actor;
    private long snapshot;
    private long otherSnapshot;
    private long runtime;
    private long otherRuntime;
    private long batch;
    private long nextBatch;
    private long set;

    /** 공유 시험의 max 버전 가정에 의존하지 않고 embedded 19를 적용한다. */
    @BeforeAll
    static void open() {
        postgres =
                new PostgreSQLContainer<>(
                        DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
        postgres.start();
        jdbc = jdbc(postgres);
        var flyway = flyway(postgres, null);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(19);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 실행 콜백을 흉내내지 않고 FK 검사에 필요한 실제 저장 부모만 생성한다. */
    @BeforeEach
    void fixtures() {
        actor =
                id(
                        "INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())"
                                + " RETURNING id");
        long story =
                id(
                        "INSERT INTO public.story(code,owner_id) VALUES ('G"
                                + UUID.randomUUID().toString().replace("-", "").toUpperCase()
                                + "',"
                                + actor
                                + ") RETURNING id");
        long version =
                id(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES ("
                                + story
                                + ",1,'물리 시험','H3',"
                                + actor
                                + ","
                                + actor
                                + ") RETURNING id");
        snapshot = snapshot(version);
        otherSnapshot = snapshot(version);
        runtime = runtime();
        otherRuntime = runtime();
        batch = batch(snapshot, runtime);
        nextBatch = batch(snapshot, runtime);
        set = id(setInsert() + " RETURNING id");
    }

    /** V17 역사적 행과 이력은 V18 증가 적용 후에도 그대로 남는다. */
    @Test
    void seventeenUpgradePreservesManualRecordAndHistory() throws Exception {
        try (var database =
                new PostgreSQLContainer<>(
                        DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))) {
            database.start();
            var legacy = flyway(database, "17");
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(17);
            var db = jdbc(database);
            db.execute("INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())");
            db.execute(
                    "INSERT INTO public.story(code,owner_id) SELECT 'GRADE_UPGRADE',id FROM"
                            + " public.admin_account");
            db.execute(
                    "INSERT INTO"
                        + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                        + " SELECT s.id,1,'물리 시험','H3',s.owner_id,s.owner_id FROM public.story s");
            db.execute(
                    "INSERT INTO"
                        + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                        + " SELECT id,0,'{}',gen_random_uuid(),created_by FROM"
                        + " public.story_version");
            db.execute(
                    "INSERT INTO"
                        + " public.review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence,self_review_yn)"
                        + " SELECT id,'MODEL',gen_random_uuid(),'{}','INCOMPLETE',created_by,'물리"
                        + " 시험',false FROM public.review_snapshot");
            var records = db.queryForList("SELECT * FROM public.review_record");
            var history =
                    db.queryForList(
                            "SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
            var latest = flyway(database, "18");
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            latest.validate();
            assertThat(
                            db.queryForList(
                                            "SELECT * FROM public.flyway_schema_history ORDER BY"
                                                    + " installed_rank")
                                    .subList(0, 17))
                    .isEqualTo(history);
            var upgraded = db.queryForList("SELECT * FROM public.review_record");
            upgraded.forEach(row -> assertThat(row.remove("evidence_set_id")).isNull());
            assertThat(upgraded).isEqualTo(records);
        }
    }

    @Test
    void exactPhysicalShapeAndInvokerBoundary() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE"
                                    + " table_schema='public' AND table_name='evidence_set' ORDER"
                                    + " BY ordinal_position",
                                String.class))
                .containsExactly(
                        "id",
                        "set_key",
                        "snapshot_id",
                        "runtime_id",
                        "runtime_epoch",
                        "kind",
                        "evidence_hash",
                        "summary_data",
                        "available_yn",
                        "invalidated_at",
                        "invalidated_issue_id",
                        "created_by",
                        "created_at");
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE"
                                    + " table_schema='public' AND table_name='evidence_item' ORDER"
                                    + " BY ordinal_position",
                                String.class))
                .containsExactly(
                        "set_id", "snapshot_id", "runtime_id", "batch_id", "evidence_hash");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid IN"
                                    + " ('public.evidence_set'::regclass,'public.evidence_item'::regclass)"
                                    + " AND contype='f' AND NOT condeferrable AND confupdtype='a'"
                                    + " AND confdeltype='a'",
                                Integer.class))
                .isEqualTo(6);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                                        + " conname='pk_evidence_item'",
                                String.class))
                .isEqualTo("PRIMARY KEY (set_id, batch_id)");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_proc WHERE proname IN"
                                    + " ('reject_grade_evidence_mutation','guard_grade_evidence_set','guard_grade_evidence_membership')"
                                    + " AND NOT prosecdef AND proconfig @>"
                                    + " ARRAY['search_path=pg_catalog, public']",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_class c CROSS JOIN LATERAL"
                                    + " aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a"
                                    + " WHERE c.oid IN"
                                    + " ('public.evidence_set'::regclass,'public.evidence_item'::regclass)"
                                    + " AND a.grantee=0",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_proc p CROSS JOIN LATERAL"
                                    + " aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a"
                                    + " WHERE p.proname IN"
                                    + " ('reject_grade_evidence_mutation','guard_grade_evidence_set','guard_grade_evidence_membership')"
                                    + " AND a.grantee=0",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT column_name FROM information_schema.columns WHERE"
                                        + " table_schema='public' AND table_name='evidence_set' AND"
                                        + " is_nullable='YES' ORDER BY ordinal_position",
                                String.class))
                .containsExactly("invalidated_at", "invalidated_issue_id");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE"
                                    + " table_schema='public' AND table_name='evidence_item' AND"
                                    + " is_nullable='NO'",
                                Integer.class))
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE"
                                        + " conrelid='public.evidence_set'::regclass",
                                Integer.class))
                .isEqualTo(13);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE"
                                        + " conrelid='public.evidence_item'::regclass",
                                Integer.class))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_index WHERE indrelid IN"
                                    + " ('public.evidence_set'::regclass,'public.evidence_item'::regclass)",
                                Integer.class))
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT runtime_epoch FROM public.evidence_set WHERE id=" + set,
                                Long.class))
                .isZero();
    }

    @Test
    void actualParentsNullsHashesJsonAndEpoch() throws Exception {
        try (var connection = transaction()) {
            fails(connection, "23503", item(set, otherSnapshot, runtime, batch));
            fails(connection, "23503", item(set, snapshot, otherRuntime, batch));
            fails(connection, "23503", item(set, snapshot, runtime, batch(otherSnapshot, runtime)));
            fails(connection, "23503", item(set, snapshot, runtime, batch(snapshot, otherRuntime)));
            fails(connection, "23503", item(Long.MAX_VALUE, snapshot, runtime, batch));
            for (String field :
                    List.of(
                            "set_key",
                            "snapshot_id",
                            "runtime_id",
                            "runtime_epoch",
                            "kind",
                            "evidence_hash",
                            "summary_data",
                            "created_by",
                            "created_at")) {
                fails(
                        connection,
                        "23502",
                        "INSERT INTO"
                            + " public.evidence_set(set_key,snapshot_id,runtime_id,runtime_epoch,kind,evidence_hash,summary_data,created_by,created_at)"
                            + " SELECT "
                                + setValues(field));
            }
            fails(connection, "23514", setInsert().replace(",0,'GRADE'", ",-1,'GRADE'"));
            fails(connection, "23514", setInsert().replace("'GRADE'", "'PLAYTEST'"));
            fails(connection, "23514", setInsert().replace("repeat('a',64)", "repeat('A',64)"));
            fails(
                    connection,
                    "23514",
                    "INSERT INTO"
                        + " public.evidence_set(set_key,snapshot_id,runtime_id,runtime_epoch,kind,evidence_hash,summary_data,created_by,available_yn)"
                        + " SELECT gen_random_uuid(),"
                            + snapshot
                            + ","
                            + runtime
                            + ",0,'GRADE',repeat('a',64),'{}',"
                            + actor
                            + ",NULL");
            fails(
                    connection,
                    "23503",
                    setInsert().replace("()," + snapshot + ",", "(),9223372036854775807,"));
            fails(
                    connection,
                    "23503",
                    setInsert().replace("," + runtime + ",0,", ",9223372036854775807,0,"));
            fails(
                    connection,
                    "23514",
                    "INSERT INTO"
                        + " public.evidence_set(set_key,snapshot_id,runtime_id,runtime_epoch,kind,evidence_hash,summary_data,created_by,available_yn,invalidated_at,invalidated_issue_id)"
                        + " SELECT gen_random_uuid(),"
                            + snapshot
                            + ","
                            + runtime
                            + ",0,'GRADE',repeat('a',64),'{}',"
                            + actor
                            + ",false,now(),1");
            for (String json :
                    List.of(
                            "'null'",
                            "'[]'",
                            "'1'",
                            "'\"scalar\"'",
                            "jsonb_build_object('x',repeat('a',131073))")) {
                fails(connection, "23514", setInsert().replace("'{}'", json));
            }
            execute(
                    connection,
                    setInsert().replace("'{}'", "jsonb_build_object('x',repeat('a',131063))"));
            fails(
                    connection,
                    "23514",
                    setInsert().replace("'{}'", "jsonb_build_object('x',repeat('a',131064))"));
            execute(connection, item(set, snapshot, runtime, batch));
            fails(connection, "23505", item(set, snapshot, runtime, batch));
            for (String field :
                    List.of("set_id", "snapshot_id", "runtime_id", "batch_id", "evidence_hash")) {
                String values =
                        field.equals("set_id")
                                ? "NULL,"
                                        + snapshot
                                        + ","
                                        + runtime
                                        + ","
                                        + nextBatch
                                        + ",repeat('a',64)"
                                : field.equals("snapshot_id")
                                        ? set
                                                + ",NULL,"
                                                + runtime
                                                + ","
                                                + nextBatch
                                                + ",repeat('a',64)"
                                        : field.equals("runtime_id")
                                                ? set
                                                        + ","
                                                        + snapshot
                                                        + ",NULL,"
                                                        + nextBatch
                                                        + ",repeat('a',64)"
                                                : field.equals("batch_id")
                                                        ? set
                                                                + ","
                                                                + snapshot
                                                                + ","
                                                                + runtime
                                                                + ",NULL,repeat('a',64)"
                                                        : set + "," + snapshot + "," + runtime + ","
                                                                + nextBatch + ",NULL";
                fails(
                        connection,
                        field.equals("set_id") ? "23503" : "23502",
                        "INSERT INTO public.evidence_item VALUES (" + values + ")");
            }
            connection.rollback();
        }
    }

    @Test
    void gradeRequiresRealNonemptyParentAndManualCannotLink() throws Exception {
        try (var connection = transaction()) {
            fails(connection, "23503", record(snapshot, "GRADE", "PASS", "NULL"));
            fails(
                    connection,
                    "23503",
                    record(snapshot, "GRADE", "PASS", Long.toString(Long.MAX_VALUE)));
            fails(connection, "23514", record(snapshot, "GRADE", "PASS", Long.toString(set)));
            execute(connection, item(set, snapshot, runtime, batch));
            fails(connection, "23503", record(otherSnapshot, "GRADE", "PASS", Long.toString(set)));
            for (String kind : List.of("STRUCTURE", "MODEL", "APPROVAL")) {
                for (String result : List.of("PASS", "FAIL", "INCOMPLETE"))
                    execute(connection, record(snapshot, kind, result, "NULL"));
                fails(connection, "23514", record(snapshot, kind, "PASS", Long.toString(set)));
            }
            for (String result : List.of("FAIL", "INCOMPLETE"))
                fails(connection, "23514", record(snapshot, "GRADE", result, Long.toString(set)));
            execute(connection, record(snapshot, "GRADE", "PASS", Long.toString(set)));
            fails(connection, "23514", item(set, snapshot, runtime, nextBatch));
            fails(connection, "23514", record(snapshot, "GRADE", "PASS", Long.toString(set)));
            for (String table : List.of("evidence_set", "evidence_item", "review_record")) {
                fails(connection, "42501", "DELETE FROM public." + table);
                fails(connection, "42501", "TRUNCATE public." + table + " CASCADE");
            }
            fails(
                    connection,
                    "42501",
                    "UPDATE public.evidence_item SET evidence_hash=repeat('b',64)");
            fails(connection, "42501", "UPDATE public.review_record SET evidence='덮어쓰기'");
            connection.rollback();
        }
    }

    @Test
    void firstSameSnapshotIssueOnlyAndNeverRevives() throws Exception {
        long issue = issue(snapshot, otherRuntime, batch(snapshot, otherRuntime));
        long foreign = issue(otherSnapshot, runtime, batch(otherSnapshot, runtime));
        long resolved = issue(snapshot, runtime, batch);
        try (var connection = transaction()) {
            execute(
                    connection,
                    "UPDATE public.execution_issue SET state='RESOLVED',resolved_batch_id="
                            + nextBatch
                            + ",resolved_by="
                            + actor
                            + ",resolved_at=clock_timestamp(),resolution_data='{}' WHERE id="
                            + resolved);
            fails(connection, "23514", invalidate(resolved, "clock_timestamp()"));
            fails(connection, "23514", invalidate(foreign, "clock_timestamp()"));
            fails(connection, "23514", invalidate(Long.MAX_VALUE, "clock_timestamp()"));
            fails(connection, "23514", invalidate(issue, "NULL"));
            fails(connection, "23514", invalidate(issue, "created_at-interval '1 second'"));
            fails(
                    connection,
                    "23514",
                    "UPDATE public.evidence_set SET evidence_hash=repeat('b',64) WHERE id=" + set);
            execute(connection, invalidate(issue, "clock_timestamp()"));
            execute(connection, item(set, snapshot, runtime, batch));
            fails(connection, "23514", record(snapshot, "GRADE", "PASS", Long.toString(set)));
            fails(connection, "23514", invalidate(issue, "clock_timestamp()"));
            fails(
                    connection,
                    "23514",
                    "UPDATE public.evidence_set SET"
                        + " available_yn=true,invalidated_at=NULL,invalidated_issue_id=NULL WHERE"
                        + " id="
                            + set);
            fails(
                    connection,
                    "23514",
                    "UPDATE public.evidence_set SET invalidated_issue_id="
                            + foreign
                            + " WHERE id="
                            + set);
            connection.rollback();
        }
    }

    /** 봉인 TX가 집합 행을 잠그면 다른 연결의 늦은 구성원은 대기 후 거절된다. */
    @Test
    void lateItemCannotRaceRecordSeal() throws Exception {
        jdbc.execute(item(set, snapshot, runtime, batch));
        try (var sealing = transaction();
                var late = transaction();
                var executor = Executors.newSingleThreadExecutor()) {
            execute(sealing, record(snapshot, "GRADE", "PASS", Long.toString(set)));
            int pid;
            try (var statement = late.createStatement();
                    var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                pid = rows.getInt(1);
            }
            var pending =
                    executor.submit(
                            () -> {
                                try {
                                    execute(late, item(set, snapshot, runtime, nextBatch));
                                    return "INSERTED";
                                } catch (SQLException error) {
                                    return error.getSQLState();
                                }
                            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                blocked =
                        Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT cardinality(pg_blocking_pids(?))>0",
                                        Boolean.class,
                                        pid));
                if (blocked) break;
                Thread.sleep(10);
            }
            assertThat(blocked).isTrue();
            assertThat(pending.isDone()).isFalse();
            sealing.commit();
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo("23514");
            late.rollback();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.evidence_item WHERE set_id=" + set,
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void staleIsolationCannotBypassSealButManualStillWorks() throws Exception {
        for (int isolation :
                List.of(
                        Connection.TRANSACTION_REPEATABLE_READ,
                        Connection.TRANSACTION_SERIALIZABLE)) {
            try (var connection = transaction()) {
                connection.setTransactionIsolation(isolation);
                fails(connection, "23514", item(set, snapshot, runtime, batch));
                fails(connection, "23514", record(snapshot, "GRADE", "PASS", Long.toString(set)));
                execute(connection, record(snapshot, "MODEL", "PASS", "NULL"));
                connection.rollback();
            }
        }
    }

    /** 공통 필수 열의 NULL 시험 INSERT 값만 생성한다. 생성 시각 NULL도 별도 열 목록으로 검사한다. */
    private String setValues(String field) {
        return (field.equals("set_key") ? "NULL" : "gen_random_uuid()")
                + ","
                + (field.equals("snapshot_id") ? "NULL" : snapshot)
                + ","
                + (field.equals("runtime_id") ? "NULL" : runtime)
                + ","
                + (field.equals("runtime_epoch") ? "NULL" : "0")
                + ","
                + (field.equals("kind") ? "NULL" : "'GRADE'")
                + ","
                + (field.equals("evidence_hash") ? "NULL" : "repeat('a',64)")
                + ","
                + (field.equals("summary_data") ? "NULL" : "'{}'::jsonb")
                + ","
                + (field.equals("created_by") ? "NULL" : actor)
                + (field.equals("created_at") ? ",NULL" : ",now()");
    }

    /** 봉인 전 초기 물리 집합 INSERT; 실제 실행 통과를 주장하지 않는다. */
    private String setInsert() {
        return "INSERT INTO"
                   + " public.evidence_set(set_key,snapshot_id,runtime_id,runtime_epoch,kind,evidence_hash,summary_data,created_by)"
                   + " VALUES (gen_random_uuid(),"
                + snapshot
                + ","
                + runtime
                + ",0,'GRADE',repeat('a',64),'{}',"
                + actor
                + ")";
    }

    /** 실제 부모 식별자를 가진 구성원 INSERT를 반환한다. */
    private static String item(long set, long snapshot, long runtime, long batch) {
        return "INSERT INTO public.evidence_item VALUES ("
                + set
                + ","
                + snapshot
                + ","
                + runtime
                + ","
                + batch
                + ",repeat('a',64))";
    }

    /** 형식 검사용 합성 검수 INSERT이며 서버 실행 인증의 대체물이 아니다. */
    private String record(long target, String kind, String result, String parent) {
        return "INSERT INTO"
                   + " public.review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence,self_review_yn,evidence_set_id)"
                   + " VALUES ("
                + target
                + ",'"
                + kind
                + "',gen_random_uuid(),'{\"syntheticOnly\":true}','"
                + result
                + "',"
                + actor
                + ",'물리 시험',false,"
                + parent
                + ")";
    }

    /** 서비스가 사용할 단회 무효화 대입 형태를 반환한다. */
    private String invalidate(long issue, String time) {
        return "UPDATE public.evidence_set SET available_yn=false,invalidated_issue_id="
                + issue
                + ",invalidated_at="
                + time
                + " WHERE id="
                + set;
    }

    /** 실행 지적의 실제 FK 부모를 생성한다. */
    private long issue(long target, long config, long source) {
        return id(
                "INSERT INTO"
                    + " public.execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                    + " VALUES (gen_random_uuid(),"
                        + target
                        + ","
                        + config
                        + ","
                        + source
                        + ",'GRADING','CRITICAL','OPEN','PHYSICAL_TEST') RETURNING id");
    }

    /** 실제 BATCH 저장 행만 생성하며 완료 콜백·실제 모델 품질을 주장하지 않는다. */
    private long batch(long target, long config) {
        return id(
                "INSERT INTO"
                    + " public.grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)"
                    + " VALUES (gen_random_uuid(),"
                        + target
                        + ","
                        + config
                        + ",'REVIEW',repeat('a',64),repeat('a',64),repeat('a',64),repeat('a',64),0,'STAGED',3,"
                        + actor
                        + ") RETURNING id");
    }

    /** 실제 실행 설정 부모를 생성한다. 설치·품질 증명이 아니다. */
    private long runtime() {
        return id(
                "INSERT INTO public.grade_runtime(code,config_hash,config_data,state) VALUES"
                        + " ('PHYSICAL_"
                        + UUID.randomUUID()
                        + "',repeat('a',64),'{}','AVAILABLE') RETURNING id");
    }

    /** 실제 버전의 물리 시험 사본을 생성한다. */
    private long snapshot(long version) {
        return id(
                "INSERT INTO"
                    + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                    + " VALUES ("
                        + version
                        + ",0,'{}',gen_random_uuid(),"
                        + actor
                        + ") RETURNING id");
    }

    /** 상수 INSERT가 반환하는 실제 내부 식별자를 읽는다. */
    private static long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    /** 폐기형 DB만 대상으로 원본 embedded SQL을 구성한다. null target은 최신 버전이다. */
    private static Flyway flyway(PostgreSQLContainer<?> database, String target) {
        var config =
                Flyway.configure()
                        .dataSource(
                                database.getJdbcUrl(),
                                database.getUsername(),
                                database.getPassword())
                        .locations("classpath:no-physical-sql")
                        .resourceProvider(new EmbeddedSqlResourceProvider());
        if (target != null) config.target(target);
        return config.load();
    }

    /** 폐기형 DB의 JDBC 조회 도구를 반환한다. */
    private static JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    /** 호출자가 종료와 롤백을 소유하는 시험 TX를 연다. */
    private static Connection transaction() throws SQLException {
        var connection = postgres.createConnection("");
        connection.setAutoCommit(false);
        return connection;
    }

    /** 상수 SQL을 10초 제한으로 실행한다. */
    private static int execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            return statement.executeUpdate(sql);
        }
    }

    /** 실패 SQLState를 확인하고 savepoint로 다음 물리 검사의 TX를 복구한다. */
    private static void fails(Connection connection, String state, String sql) throws SQLException {
        var savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> execute(connection, sql))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo(state);
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
