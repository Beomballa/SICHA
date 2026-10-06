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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** BATCH V17 저장 구조의 폐기형 PostgreSQL 시험 정의다. 서비스·모델 품질·운영 권한 검증이 아니다. */
class GradeBatchStorageSchemaIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static final String HASH = "a".repeat(64);
    private static final String TABLE_SQL = "('test_action','test_audit','execution_issue')";
    private static final Map<String, String> KEYS =
            Map.of(
                    "pk_test_action", "PRIMARY KEY (id)",
                    "uk_test_action_request", "UNIQUE (request_key)",
                    "pk_test_audit", "PRIMARY KEY (id)",
                    "uk_test_audit_event", "UNIQUE (event_key)",
                    "pk_execution_issue", "PRIMARY KEY (id)",
                    "uk_execution_issue_key", "UNIQUE (issue_key)",
                    "uk_eissue_batch_kind", "UNIQUE (batch_id, kind)",
                    "uk_grade_batch_source", "UNIQUE (snapshot_id, runtime_id, id)");
    private static final Map<String, String> FOREIGN =
            Map.of(
                    "fk_ta_admin", "FOREIGN KEY (admin_id) REFERENCES admin_account(id)",
                    "fk_eissue_snapshot",
                            "FOREIGN KEY (snapshot_id) REFERENCES review_snapshot(id)",
                    "fk_eissue_runtime", "FOREIGN KEY (runtime_id) REFERENCES grade_runtime(id)",
                    "fk_eissue_batch",
                            "FOREIGN KEY (snapshot_id, runtime_id, batch_id) REFERENCES"
                                    + " grade_batch(snapshot_id, runtime_id, id)",
                    "fk_eissue_res_batch",
                            "FOREIGN KEY (snapshot_id, resolved_batch_id) REFERENCES"
                                    + " grade_batch(snapshot_id, id)",
                    "fk_eissue_admin", "FOREIGN KEY (resolved_by) REFERENCES admin_account(id)");
    private static final Map<String, List<String>> CHECKS =
            Map.ofEntries(
                    Map.entry("ck_ta_size", List.of("octet_length((result_data)::text)", "32768")),
                    Map.entry("ck_test_action_request_hash", List.of("^[0-9a-f]{64}$")),
                    Map.entry(
                            "ck_test_action_result_data",
                            List.of("jsonb_typeof(result_data)", "object")),
                    Map.entry("ck_audit_actor_kind", List.of("ADMIN", "WORKER", "SYSTEM")),
                    Map.entry("ck_audit_phase", List.of("ATTEMPT", "RESULT")),
                    Map.entry("ck_audit_size", List.of("octet_length((detail)::text)", "8192")),
                    Map.entry("ck_test_audit_detail", List.of("jsonb_typeof(detail)", "object")),
                    Map.entry("ck_ei_kind", List.of("CONTENT", "GRADING", "INFRA", "OBSERVATION")),
                    Map.entry("ck_ei_severity", List.of("CRITICAL", "MINOR")),
                    Map.entry("ck_ei_state", List.of("OPEN", "RESOLVED")),
                    Map.entry(
                            "ck_eissue_resolution",
                            List.of(
                                    "OPEN",
                                    "RESOLVED",
                                    "CONTENT",
                                    "resolved_batch_id IS NULL",
                                    "resolved_by IS NULL",
                                    "resolved_at IS NULL",
                                    "resolution_data IS NULL",
                                    "resolved_batch_id IS NOT NULL",
                                    "resolved_batch_id <> batch_id",
                                    "resolved_by IS NOT NULL",
                                    "resolved_at IS NOT NULL",
                                    "resolved_at >= created_at",
                                    "resolution_data IS NOT NULL")),
                    Map.entry(
                            "ck_execution_issue_resolution_data",
                            List.of(
                                    "jsonb_typeof(resolution_data)",
                                    "object",
                                    "octet_length((resolution_data)::text)",
                                    "131072")));
    private long admin;
    private long otherAdmin;
    private long snapshot;
    private long otherSnapshot;
    private long runtime;
    private long otherRuntime;
    private long source;
    private long target;
    private long wrongTarget;

    /** 두 독립 폐기 DB로 실제 V16 FK 기준 및 V1→V17 전체 적용·재실행·검증을 대조한다. */
    @BeforeAll
    static void open() {
        postgres = container();
        try (var baseline = container()) {
            baseline.start();
            var old = flyway(baseline, "16");
            assertThat(old.migrate().migrationsExecuted).isEqualTo(16);
            old.validate();
            var before = GradeSchemaIT.jdbc(baseline);
            int oldForeignCount = foreignCount(before);
            var history =
                    before.queryForList(
                            "SELECT version,script,checksum FROM flyway_schema_history WHERE"
                                    + " success ORDER BY installed_rank");
            var oldColumns =
                    before.queryForList(
                            "SELECT column_name,data_type,is_nullable,column_default FROM"
                                    + " information_schema.columns WHERE table_schema='public' AND"
                                    + " table_name='grade_batch' ORDER BY ordinal_position");
            var oldTables =
                    new java.util.HashSet<>(
                            before.queryForList(
                                    "SELECT tablename FROM pg_tables WHERE schemaname='public'",
                                    String.class));
            var baselineCounts = catalogCounts(before);
            postgres.start();
            var current = flyway(postgres, "17");
            assertThat(current.migrate().migrationsExecuted).isEqualTo(17);
            assertThat(current.migrate().migrationsExecuted).isZero();
            current.validate();
            jdbc = GradeSchemaIT.jdbc(postgres);
            assertThat(jdbc.queryForObject("SHOW server_version", String.class))
                    .startsWith("16.10");
            assertThat(jdbc.queryForObject("SHOW server_encoding", String.class)).isEqualTo("UTF8");
            int newForeignCount = foreignCount(jdbc);
            assertThat(newForeignCount).isEqualTo(oldForeignCount + 6);
            var addedTables =
                    new java.util.HashSet<>(
                            jdbc.queryForList(
                                    "SELECT tablename FROM pg_tables WHERE schemaname='public'",
                                    String.class));
            addedTables.removeAll(oldTables);
            assertThat(addedTables)
                    .containsExactlyInAnyOrder("test_action", "test_audit", "execution_issue");
            var currentCounts = catalogCounts(jdbc);
            for (var delta :
                    Map.of(
                                    "columns",
                                    34,
                                    "constraints",
                                    26,
                                    "indexes",
                                    8,
                                    "functions",
                                    2,
                                    "triggers",
                                    4,
                                    "sequences",
                                    3)
                            .entrySet())
                assertThat(
                                ((Number) currentCounts.get(delta.getKey())).intValue()
                                        - ((Number) baselineCounts.get(delta.getKey())).intValue())
                        .isEqualTo(delta.getValue());
            assertThat(
                            jdbc.queryForList(
                                    "SELECT version,script,checksum FROM flyway_schema_history"
                                            + " WHERE success AND version::int<=16 ORDER BY"
                                            + " installed_rank"))
                    .isEqualTo(history);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT column_name,data_type,is_nullable,column_default FROM"
                                        + " information_schema.columns WHERE table_schema='public'"
                                        + " AND table_name='grade_batch' ORDER BY"
                                        + " ordinal_position"))
                    .isEqualTo(oldColumns);
            var upgrade = flyway(baseline, "17");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            upgrade.validate();
            assertThat(foreignCount(before)).isEqualTo(newForeignCount);
            System.out.printf(
                    "PASS V17 actual whole-public FK: V16=%d, V17=%d; exact added FK=6%n",
                    oldForeignCount, newForeignCount);
        } catch (RuntimeException | AssertionError failure) {
            postgres.close();
            throw failure;
        }
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** UUID로 독립 등록한 실제 부모다. 합성 사본·STAGED 집합은 구조 fixture이며 실행 품질 근거가 아니다. */
    @BeforeEach
    void parents() {
        admin =
                id(
                        "INSERT INTO admin_account(account_key) VALUES (?) RETURNING id",
                        UUID.randomUUID());
        otherAdmin =
                id(
                        "INSERT INTO admin_account(account_key) VALUES (?) RETURNING id",
                        UUID.randomUUID());
        long story =
                id("INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id", token(), admin);
        long version =
                id(
                        "INSERT INTO"
                            + " story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'저장 관계 fixture','H2',?,?) RETURNING id",
                        story,
                        admin,
                        admin);
        snapshot = snapshot(version);
        otherSnapshot = snapshot(version);
        runtime = runtime();
        otherRuntime = runtime();
        source = batch(snapshot, runtime);
        target = batch(snapshot, otherRuntime);
        wrongTarget = batch(otherSnapshot, otherRuntime);
    }

    /** 실제 열 순서·타입·NULL·identity·기본값과 주석 및 보류 부모 부재를 대조한다. */
    @Test
    void exactOrderedColumnsNullabilityIdentityDefaultsAndKoreanComments() {
        columns(
                "test_action",
                List.of(
                        "id:bigint:true",
                        "request_key:uuid:true",
                        "admin_id:bigint:true",
                        "action:character varying(40):true",
                        "scope_key:character varying(160):true",
                        "request_hash:character(64):true",
                        "result_data:jsonb:true",
                        "created_at:timestamp with time zone:true"));
        columns(
                "test_audit",
                List.of(
                        "id:bigint:true",
                        "event_key:uuid:true",
                        "actor_kind:character varying(24):true",
                        "actor_ref:character varying(80):true",
                        "action:character varying(40):true",
                        "scope_kind:character varying(24):true",
                        "scope_key:character varying(160):true",
                        "request_id:uuid:true",
                        "phase:character varying(24):true",
                        "business_result:character varying(24):true",
                        "detail:jsonb:true",
                        "created_at:timestamp with time zone:true"));
        columns(
                "execution_issue",
                List.of(
                        "id:bigint:true",
                        "issue_key:uuid:true",
                        "snapshot_id:bigint:true",
                        "runtime_id:bigint:true",
                        "batch_id:bigint:true",
                        "kind:character varying(24):true",
                        "severity:character varying(24):true",
                        "state:character varying(24):true",
                        "reason_code:character varying(40):true",
                        "resolved_batch_id:bigint:false",
                        "resolved_by:bigint:false",
                        "resolved_at:timestamp with time zone:false",
                        "resolution_data:jsonb:false",
                        "created_at:timestamp with time zone:true"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE"
                                        + " table_schema='public' AND table_name IN "
                                        + TABLE_SQL,
                                Integer.class))
                .isEqualTo(34);
        assertThat(
                        jdbc.queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE"
                                        + " table_schema='public' AND table_name IN"
                                        + " ('member_account','test_review','evidence_set')",
                                String.class))
                .isEmpty();
    }

    /** 실제 제약 DDL·참조 정책·제약 인덱스·invoker/guard·PUBLIC 권한을 대조한다. */
    @Test
    void exactConstraintsDdlActionsIndexesInvokerGuardsAndPublicAcl() {
        var rows =
                jdbc.queryForList(
                        """
                        SELECT conname,contype,condeferrable,condeferred,convalidated,confupdtype,confdeltype,confmatchtype,
                            pg_get_constraintdef(c.oid) AS ddl,obj_description(c.oid,'pg_constraint') AS comment
                        FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                        WHERE n.nspname='public' AND (t.relname IN ('test_action','test_audit','execution_issue') OR conname='uk_grade_batch_source')
                        """);
        Set<String> expected = new java.util.HashSet<>(KEYS.keySet());
        expected.addAll(FOREIGN.keySet());
        expected.addAll(CHECKS.keySet());
        assertThat(rows).hasSize(26);
        assertThat(rows.stream().map(r -> r.get("conname")).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        for (var row : rows) {
            String name = (String) row.get("conname");
            String ddl = (String) row.get("ddl");
            assertThat(row.get("condeferrable")).isEqualTo(false);
            assertThat(row.get("condeferred")).isEqualTo(false);
            assertThat(row.get("convalidated")).isEqualTo(true);
            assertThat((String) row.get("comment")).containsPattern("[가-힣]");
            if (KEYS.containsKey(name)) assertThat(ddl).isEqualTo(KEYS.get(name));
            if (FOREIGN.containsKey(name)) {
                assertThat(ddl).isEqualTo(FOREIGN.get(name));
                assertThat(row.get("confupdtype")).isEqualTo("a");
                assertThat(row.get("confdeltype")).isEqualTo("a");
                assertThat(row.get("confmatchtype")).isEqualTo("s");
            }
            if (CHECKS.containsKey(name)) {
                assertThat(ddl).startsWith("CHECK (");
                for (String part : CHECKS.get(name)) assertThat(ddl).contains(part);
                if (name.equals("ck_audit_actor_kind")) assertThat(ddl).doesNotContain("MEMBER");
            }
        }
        assertThat(rows.stream().filter(r -> r.get("contype").equals("p"))).hasSize(3);
        assertThat(rows.stream().filter(r -> r.get("contype").equals("u"))).hasSize(5);
        assertThat(rows.stream().filter(r -> r.get("contype").equals("f"))).hasSize(6);
        assertThat(rows.stream().filter(r -> r.get("contype").equals("c"))).hasSize(12);
        var indexes =
                jdbc.queryForList(
                        """
                        SELECT i.relname,am.amname,x.indisunique,x.indisvalid,x.indisready,x.indnkeyatts=x.indnatts AS no_include,
                            x.indpred IS NULL AS no_predicate,x.indexprs IS NULL AS no_expression,c.conname,
                            pg_get_indexdef(i.oid) AS ddl,obj_description(i.oid,'pg_class') AS comment
                        FROM pg_index x JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_class t ON t.oid=x.indrelid
                        JOIN pg_am am ON am.oid=i.relam LEFT JOIN pg_constraint c ON c.conindid=i.oid AND c.contype IN ('p','u')
                        WHERE t.oid IN ('public.test_action'::regclass,'public.test_audit'::regclass,'public.execution_issue'::regclass)
                            OR i.oid='public.uk_grade_batch_source'::regclass
                        """);
        assertThat(indexes).hasSize(8);
        assertThat(indexes.stream().map(r -> r.get("relname")).toList())
                .containsExactlyInAnyOrderElementsOf(KEYS.keySet());
        for (var index : indexes) {
            assertThat(index)
                    .containsEntry("amname", "btree")
                    .containsEntry("indisunique", true)
                    .containsEntry("indisvalid", true)
                    .containsEntry("indisready", true)
                    .containsEntry("no_include", true)
                    .containsEntry("no_predicate", true)
                    .containsEntry("no_expression", true);
            assertThat(index.get("conname")).isEqualTo(index.get("relname"));
            assertThat((String) index.get("ddl")).contains("USING btree");
            String key = (String) index.get("relname");
            String columns = KEYS.get(key).substring(KEYS.get(key).indexOf('('));
            assertThat((String) index.get("ddl")).endsWith(columns);
            assertThat((String) index.get("comment")).contains("IDX-", "미측정");
        }
        var functions =
                jdbc.queryForList(
                        """
                        SELECT proname,prosecdef,prorettype::regtype::text AS result,pg_get_functiondef(p.oid) AS ddl,
                            obj_description(p.oid,'pg_proc') AS comment
                        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                        WHERE n.nspname='public' AND proname IN ('reject_batch_history_mutation','guard_batch_issue_history')
                        """);
        assertThat(functions).hasSize(2);
        for (var function : functions) {
            assertThat(function)
                    .containsEntry("prosecdef", false)
                    .containsEntry("result", "trigger");
            assertThat((String) function.get("comment")).contains("소유자", "superuser");
            assertThat((String) function.get("ddl")).doesNotContain("SECURITY DEFINER");
            String ddl = (String) function.get("ddl");
            if (function.get("proname").equals("reject_batch_history_mutation"))
                assertThat(ddl).contains("42501", "BATCH_HISTORY_IMMUTABLE");
            else
                assertThat(ddl)
                        .contains(
                                "BATCH_ISSUE_MUST_OPEN",
                                "BATCH_ISSUE_HISTORY_CONFLICT",
                                "BATCH_ISSUE_IMMUTABLE",
                                "IS DISTINCT FROM",
                                "NEW.created_at",
                                "OLD.created_at");
        }
        var triggers =
                jdbc.queryForList(
                        """
                        SELECT tgname,tgtype::int,tgenabled,pg_get_triggerdef(t.oid) AS ddl,obj_description(t.oid,'pg_trigger') AS comment
                        FROM pg_trigger t WHERE NOT tgisinternal AND tgrelid IN
                            ('public.test_action'::regclass,'public.test_audit'::regclass,'public.execution_issue'::regclass)
                        """);
        assertThat(triggers).hasSize(4);
        Map<String, Integer> types =
                Map.of(
                        "tr_test_action_immutable",
                        58,
                        "tr_test_audit_immutable",
                        58,
                        "tr_execution_issue_history",
                        31,
                        "tr_execution_issue_no_truncate",
                        34);
        assertThat(triggers.stream().map(r -> r.get("tgname")).toList())
                .containsExactlyInAnyOrderElementsOf(types.keySet());
        for (var trigger : triggers) {
            assertThat(trigger.get("tgtype")).isEqualTo(types.get(trigger.get("tgname")));
            assertThat(trigger.get("tgenabled")).isEqualTo("O");
            assertThat((String) trigger.get("comment")).containsPattern("[가-힣]");
            assertThat((String) trigger.get("ddl")).contains("BEFORE", "EXECUTE FUNCTION");
            assertThat((String) trigger.get("ddl"))
                    .contains(
                            trigger.get("tgname").equals("tr_execution_issue_history")
                                    ? "FOR EACH ROW EXECUTE FUNCTION guard_batch_issue_history()"
                                    : "FOR EACH STATEMENT EXECUTE FUNCTION"
                                            + " reject_batch_history_mutation()");
        }
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*) FROM pg_class c CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                                WHERE c.oid IN ('public.test_action'::regclass,'public.test_audit'::regclass,'public.execution_issue'::regclass) AND a.grantee=0
                                """,
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*) FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                                WHERE p.pronamespace='public'::regnamespace AND p.proname IN ('reject_batch_history_mutation','guard_batch_issue_history') AND a.grantee=0
                                """,
                                Integer.class))
                .isZero();
    }

    /** 주체·범위와 무관한 전역 키, UUID/해시와 출처별 분류 유일성을 검사한다. */
    @Test
    void globalKeysUuidHashesAndIndependentClassifications() {
        UUID key = UUID.randomUUID();
        action(key, admin, "BATCH_CREATE", "version:1", HASH, "{}");
        fails("23505", () -> action(key, otherAdmin, "ISSUE_RESOLVE", "version:2", HASH, "{}"));
        fails("23505", () -> action(key, otherAdmin, "BATCH_CREATE", "version:1", HASH, "{}"));
        fails("23505", () -> action(key, admin, "BATCH_CREATE", "version:2", HASH, "{}"));
        fails("23505", () -> action(key, admin, "ISSUE_RESOLVE", "version:1", HASH, "{}"));
        UUID event = UUID.randomUUID();
        audit(event, "ADMIN", "RESULT", "{}");
        fails("23505", () -> audit(event, "SYSTEM", "ATTEMPT", "{}"));
        UUID issueKey = UUID.randomUUID();
        issue(issueKey, snapshot, runtime, source, "GRADING", "CRITICAL");
        fails("23505", () -> issue(issueKey, snapshot, otherRuntime, target, "INFRA", "MINOR"));
        fails(
                "23505",
                () -> issue(UUID.randomUUID(), snapshot, runtime, source, "GRADING", "MINOR"));
        issue(UUID.randomUUID(), snapshot, runtime, source, "INFRA", "MINOR");
        for (String actor : List.of("ADMIN", "WORKER", "SYSTEM"))
            for (String phase : List.of("ATTEMPT", "RESULT"))
                audit(UUID.randomUUID(), actor, phase, "{}");
        for (String kind : List.of("CONTENT", "GRADING", "INFRA", "OBSERVATION"))
            for (String severity : List.of("CRITICAL", "MINOR"))
                issue(
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        batch(snapshot, runtime),
                        kind,
                        severity);
        for (String hash : List.of("A".repeat(64), "g".repeat(64), "a".repeat(63)))
            fails(
                    "23514",
                    () ->
                            action(
                                    UUID.randomUUID(),
                                    admin,
                                    "BATCH_CREATE",
                                    "version:1",
                                    hash,
                                    "{}"));
        fails(
                "22001",
                () ->
                        action(
                                UUID.randomUUID(),
                                admin,
                                "BATCH_CREATE",
                                "version:1",
                                "a".repeat(65),
                                "{}"));
        fails(
                "22P02",
                () ->
                        jdbc.update(
                                "INSERT INTO"
                                    + " test_action(request_key,admin_id,action,scope_key,request_hash,result_data)"
                                    + " VALUES ('not-uuid',?,'BATCH_CREATE','version:1',?,'{}')",
                                admin,
                                HASH));
        fails(
                "22P02",
                () ->
                        jdbc.update(
                                auditInsert().replaceFirst("\\?::uuid", "'not-uuid'::uuid"),
                                UUID.randomUUID()));
        fails(
                "22P02",
                () ->
                        jdbc.update(
                                auditInsert()
                                        .replace("?::uuid,'RESULT'", "'not-uuid'::uuid,'RESULT'"),
                                UUID.randomUUID()));
        fails(
                "22P02",
                () ->
                        jdbc.update(
                                issueInsert().replace("?::uuid", "'not-uuid'::uuid"),
                                snapshot,
                                runtime,
                                source,
                                "INFRA",
                                "MINOR"));
        for (String actor : List.of("MEMBER", "UNKNOWN"))
            fails("23514", () -> audit(UUID.randomUUID(), actor, "RESULT", "{}"));
        fails("23514", () -> audit(UUID.randomUUID(), "ADMIN", "UNKNOWN", "{}"));
        fails(
                "23514",
                () ->
                        issue(
                                UUID.randomUUID(),
                                snapshot,
                                runtime,
                                batch(snapshot, runtime),
                                "UNKNOWN",
                                "MINOR"));
        fails(
                "23514",
                () ->
                        issue(
                                UUID.randomUUID(),
                                snapshot,
                                runtime,
                                batch(snapshot, runtime),
                                "INFRA",
                                "UNKNOWN"));
    }

    /** 실제 부모·출처 삼중 관계·같은 사본 해소 및 참조 키 변경 거절을 검사한다. */
    @Test
    void realSourceAndTargetForeignKeysBlockWrongParentsAndReferencedKeyChanges() {
        fails(
                "23503",
                () ->
                        action(
                                UUID.randomUUID(),
                                Long.MAX_VALUE,
                                "BATCH_CREATE",
                                "version:1",
                                HASH,
                                "{}"));
        for (long[] input :
                List.of(
                        new long[] {Long.MAX_VALUE, runtime, source},
                        new long[] {snapshot, Long.MAX_VALUE, source},
                        new long[] {snapshot, runtime, Long.MAX_VALUE},
                        new long[] {otherSnapshot, runtime, source},
                        new long[] {snapshot, otherRuntime, source}))
            fails(
                    "23503",
                    () -> issue(UUID.randomUUID(), input[0], input[1], input[2], "INFRA", "MINOR"));
        long issue = issue(UUID.randomUUID(), snapshot, runtime, source, "GRADING", "CRITICAL");
        fails("23503", () -> resolve(issue, wrongTarget, admin, "{}"));
        fails("23503", () -> resolve(issue, Long.MAX_VALUE, admin, "{}"));
        fails("23503", () -> resolve(issue, target, Long.MAX_VALUE, "{}"));
        fails("23503", () -> jdbc.update("UPDATE grade_batch SET id=DEFAULT WHERE id=?", source));
        fails(
                "23503",
                () ->
                        jdbc.update(
                                "UPDATE grade_batch SET runtime_id=? WHERE id=?",
                                otherRuntime,
                                source));
        fails(
                "23503",
                () ->
                        jdbc.update(
                                "UPDATE grade_batch SET snapshot_id=? WHERE id=?",
                                otherSnapshot,
                                source));
        resolve(issue, target, admin, "{}");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT runtime_id FROM grade_batch WHERE id=?",
                                Long.class,
                                target))
                .isEqualTo(otherRuntime);
        fails("23503", () -> jdbc.update("UPDATE grade_batch SET id=DEFAULT WHERE id=?", target));
        fails("23503", () -> jdbc.update("UPDATE admin_account SET id=DEFAULT WHERE id=?", admin));
        for (String sql :
                List.of(
                        "DELETE FROM grade_batch WHERE id=" + source,
                        "DELETE FROM grade_batch WHERE id=" + target,
                        "DELETE FROM grade_runtime WHERE id=" + runtime,
                        "DELETE FROM review_snapshot WHERE id=" + snapshot,
                        "DELETE FROM admin_account WHERE id=" + admin))
            fails("23503", () -> jdbc.update(sql));
    }

    /** 부분 해소·불변 출처·직접 해소 생성·재개/재해소를 실제 CHECK와 guard로 거절한다. */
    @Test
    void openHasNoPartialResolutionAndResolvedRequiresEveryFieldExactlyOnce() {
        String[] fields = {
            "resolved_batch_id=" + target,
            "resolved_by=" + admin,
            "resolved_at=clock_timestamp()",
            "resolution_data='{}'"
        };
        for (int mask = 1; mask < 16; mask++) {
            var selected = new java.util.ArrayList<String>();
            for (int bit = 0; bit < 4; bit++)
                if ((mask & (1 << bit)) != 0) selected.add(fields[bit]);
            long fresh = batch(snapshot, runtime);
            // OPEN 부분 필드는 실제 CHECK로 검사한다. 출처별 UNIQUE는 독립 집합으로 피한다.
            String names =
                    String.join(
                            ",",
                            selected.stream().map(s -> s.substring(0, s.indexOf('='))).toList());
            String values =
                    String.join(
                            ",",
                            selected.stream().map(s -> s.substring(s.indexOf('=') + 1)).toList());
            fails(
                    "23514",
                    () ->
                            jdbc.update(
                                    "INSERT INTO"
                                        + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code,"
                                            + names
                                            + ") VALUES (?,?,?,?, 'INFRA','MINOR','OPEN','FIXTURE',"
                                            + values
                                            + ")",
                                    UUID.randomUUID(),
                                    snapshot,
                                    runtime,
                                    fresh));
        }
        long issue = issue(UUID.randomUUID(), snapshot, runtime, source, "GRADING", "CRITICAL");
        String full =
                "state='RESOLVED',resolved_batch_id="
                        + target
                        + ",resolved_by="
                        + admin
                        + ",resolved_at=clock_timestamp(),resolution_data='{}'";
        for (String missing :
                List.of("resolved_batch_id", "resolved_by", "resolved_at", "resolution_data")) {
            String invalid = full.replaceAll(missing + "=[^,]+", missing + "=NULL");
            fails(
                    "23514",
                    () ->
                            jdbc.update(
                                    "UPDATE execution_issue SET " + invalid + " WHERE id=?",
                                    issue));
        }
        fails("23514", () -> resolve(issue, source, admin, "{}"));
        fails(
                "23514",
                () ->
                        jdbc.update(
                                "UPDATE execution_issue SET "
                                        + full.replace(
                                                "resolved_at=clock_timestamp()",
                                                "resolved_at=created_at-interval '1 microsecond'")
                                        + " WHERE id=?",
                                issue));
        long content = issue(UUID.randomUUID(), snapshot, runtime, source, "CONTENT", "MINOR");
        fails("23514", () -> resolve(content, target, admin, "{}"));
        fails(
                "23514",
                () ->
                        jdbc.update(
                                "INSERT INTO"
                                    + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code,resolved_batch_id,resolved_by,resolved_at,resolution_data)"
                                    + " VALUES"
                                    + " (?,?,?,?,'INFRA','MINOR','RESOLVED','FIXTURE',?,?,clock_timestamp(),'{}')",
                                UUID.randomUUID(),
                                snapshot,
                                runtime,
                                target,
                                source,
                                admin));
        for (String column :
                List.of(
                        "id",
                        "issue_key",
                        "snapshot_id",
                        "runtime_id",
                        "batch_id",
                        "kind",
                        "severity",
                        "reason_code",
                        "created_at")) {
            String mutation =
                    switch (column) {
                        case "id" -> "DEFAULT";
                        case "issue_key" -> "'" + UUID.randomUUID() + "'";
                        case "snapshot_id" -> Long.toString(otherSnapshot);
                        case "runtime_id" -> Long.toString(otherRuntime);
                        case "batch_id" -> Long.toString(target);
                        case "kind" -> "'INFRA'";
                        case "severity" -> "'MINOR'";
                        case "reason_code" -> "'CHANGED'";
                        default -> "created_at+interval '1 second'";
                    };
            fails(
                    "23514",
                    () ->
                            jdbc.update(
                                    "UPDATE execution_issue SET "
                                            + full
                                            + ","
                                            + column
                                            + "="
                                            + mutation
                                            + " WHERE id=?",
                                    issue));
        }
        fails(
                "23514",
                () -> jdbc.update("UPDATE execution_issue SET state='UNKNOWN' WHERE id=?", issue));
        resolve(issue, target, admin, "{}");
        fails("23514", () -> resolve(issue, target, otherAdmin, "{}"));
        fails(
                "23514",
                () ->
                        jdbc.update(
                                "UPDATE execution_issue SET"
                                    + " state='OPEN',resolved_batch_id=NULL,resolved_by=NULL,resolved_at=NULL,resolution_data=NULL"
                                    + " WHERE id=?",
                                issue));
    }

    /** SQL JSONB 텍스트의 ASCII/한글/비BMP 정확 바이트 경계와 객체·NULL을 구분한다. */
    @Test
    void sqlJsonbUtf8ExactByteBoundsObjectShapesAndSqlNull() {
        for (String character : List.of("a", "한", "𐐀")) {
            String actionExact = exactJson(32768, character);
            action(UUID.randomUUID(), admin, "BATCH_CREATE", "version:1", HASH, actionExact);
            fails(
                    "23514",
                    () ->
                            action(
                                    UUID.randomUUID(),
                                    admin,
                                    "BATCH_CREATE",
                                    "version:1",
                                    HASH,
                                    overflow(actionExact)));
            String auditExact = exactJson(8192, character);
            audit(UUID.randomUUID(), "SYSTEM", "RESULT", auditExact);
            fails(
                    "23514",
                    () -> audit(UUID.randomUUID(), "SYSTEM", "RESULT", overflow(auditExact)));
            String resolutionExact = exactJson(131072, character);
            long issue =
                    issue(
                            UUID.randomUUID(),
                            snapshot,
                            runtime,
                            batch(snapshot, runtime),
                            "INFRA",
                            "MINOR");
            fails("23514", () -> resolve(issue, target, admin, overflow(resolutionExact)));
            resolve(issue, target, admin, resolutionExact);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT octet_length(resolution_data::text) FROM"
                                            + " execution_issue WHERE id=?",
                                    Integer.class,
                                    issue))
                    .isEqualTo(131072);
        }
        for (String invalid : List.of("[]", "1", "true", "\"text\"", "null")) {
            fails(
                    "23514",
                    () ->
                            action(
                                    UUID.randomUUID(),
                                    admin,
                                    "BATCH_CREATE",
                                    "version:1",
                                    HASH,
                                    invalid));
            fails("23514", () -> audit(UUID.randomUUID(), "ADMIN", "RESULT", invalid));
            long issue =
                    issue(
                            UUID.randomUUID(),
                            snapshot,
                            runtime,
                            batch(snapshot, runtime),
                            "INFRA",
                            "MINOR");
            fails("23514", () -> resolve(issue, target, admin, invalid));
        }
        fails(
                "23502",
                () -> action(UUID.randomUUID(), admin, "BATCH_CREATE", "version:1", HASH, null));
        fails("23502", () -> audit(UUID.randomUUID(), "ADMIN", "RESULT", null));
        long issue =
                issue(
                        UUID.randomUUID(),
                        snapshot,
                        runtime,
                        batch(snapshot, runtime),
                        "INFRA",
                        "MINOR");
        assertThat(
                        jdbc.queryForMap(
                                        "SELECT"
                                            + " resolved_batch_id,resolved_by,resolved_at,resolution_data"
                                            + " FROM execution_issue WHERE id=?",
                                        issue)
                                .values())
                .containsOnlyNulls();
        fails("23514", () -> resolve(issue, target, admin, null));
    }

    /** 필수 열 각각의 NULL 거절과 ALWAYS identity 수동 식별자 거절을 관측한다. */
    @Test
    void everyRequiredColumnRejectsNullAndAlwaysIdentityRejectsExplicitId() {
        Map<String, String> inserts =
                Map.of(
                        "test_action",
                        actionInsert(),
                        "test_audit",
                        auditInsert(),
                        "execution_issue",
                        issueInsert());
        for (String table : inserts.keySet()) {
            var required =
                    jdbc.queryForList(
                            "SELECT attname FROM pg_attribute WHERE attrelid=?::regclass AND"
                                + " attnum>0 AND NOT attisdropped AND attnotnull AND attname<>'id'",
                            String.class,
                            "public." + table);
            for (String column : required) {
                String sql =
                        switch (table) {
                            case "test_action" ->
                                    "INSERT INTO"
                                        + " test_action(request_key,admin_id,action,scope_key,request_hash,result_data,created_at)"
                                        + " SELECT"
                                        + " request_key,admin_id,action,scope_key,request_hash,result_data,created_at"
                                        + " FROM test_action WHERE id=?";
                            case "test_audit" ->
                                    "INSERT INTO"
                                        + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail,created_at)"
                                        + " SELECT"
                                        + " event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail,created_at"
                                        + " FROM test_audit WHERE id=?";
                            default ->
                                    "INSERT INTO"
                                        + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code,created_at)"
                                        + " SELECT"
                                        + " issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code,created_at"
                                        + " FROM execution_issue WHERE id=?";
                        };
                long row =
                        switch (table) {
                            case "test_action" ->
                                    action(
                                            UUID.randomUUID(),
                                            admin,
                                            "BATCH_CREATE",
                                            "version:1",
                                            HASH,
                                            "{}");
                            case "test_audit" -> audit(UUID.randomUUID(), "ADMIN", "RESULT", "{}");
                            default ->
                                    issue(
                                            UUID.randomUUID(),
                                            snapshot,
                                            runtime,
                                            batch(snapshot, runtime),
                                            "INFRA",
                                            "MINOR");
                        };
                int select = sql.indexOf(" SELECT ");
                String invalid =
                        sql.substring(0, select)
                                + sql.substring(select)
                                        .replaceFirst("\\b" + column + "\\b", "NULL");
                // state NULL은 BEFORE INSERT의 OPEN 강제에서 먼저 거절된다.
                fails(
                        table.equals("execution_issue") && column.equals("state")
                                ? "23514"
                                : "23502",
                        () -> jdbc.update(invalid, row));
            }
            fails("428C9", () -> jdbc.update("INSERT INTO " + table + "(id) VALUES (999999)"));
        }
    }

    /** PostgreSQL의 비BMP 문자 수·UTF-8 바이트 수와 실제 VARCHAR 저장 경계를 대조한다. */
    @Test
    void varcharBoundsCountPostgresCharactersNotUtf16OrUtf8Bytes() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT length(?)=40 AND octet_length(?)=160",
                                Boolean.class,
                                "𐐀".repeat(40),
                                "𐐀".repeat(40)))
                .isTrue();
        action(UUID.randomUUID(), admin, "𐐀".repeat(40), "𐐀".repeat(160), HASH, "{}");
        fails(
                "22001",
                () -> action(UUID.randomUUID(), admin, "𐐀".repeat(41), "version:1", HASH, "{}"));
        fails(
                "22001",
                () ->
                        action(
                                UUID.randomUUID(),
                                admin,
                                "BATCH_CREATE",
                                "한".repeat(161),
                                HASH,
                                "{}"));
        long reasonBatch = batch(snapshot, runtime);
        jdbc.update(
                "INSERT INTO"
                    + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                    + " VALUES (?,?,?,?,'INFRA','MINOR','OPEN',?)",
                UUID.randomUUID(),
                snapshot,
                runtime,
                reasonBatch,
                "𐐀".repeat(40));
        fails(
                "22001",
                () ->
                        jdbc.update(
                                "INSERT INTO"
                                    + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                                    + " VALUES (?,?,?,?,'GRADING','MINOR','OPEN',?)",
                                UUID.randomUUID(),
                                snapshot,
                                runtime,
                                reasonBatch,
                                "𐐀".repeat(41)));
        fails("22001", () -> audit(UUID.randomUUID(), "𐐀".repeat(25), "RESULT", "{}"));
        fails("22001", () -> audit(UUID.randomUUID(), "ADMIN", "𐐀".repeat(25), "{}"));
        long event = audit(UUID.randomUUID(), "SYSTEM", "RESULT", "{}");
        for (var bound :
                Map.of(
                                "actor_ref",
                                80,
                                "action",
                                40,
                                "scope_kind",
                                24,
                                "scope_key",
                                160,
                                "business_result",
                                24)
                        .entrySet()) {
            String sql =
                    "INSERT INTO"
                        + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                        + " SELECT"
                        + " ?,'SYSTEM',actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail"
                        + " FROM test_audit WHERE id=?";
            int select = sql.indexOf(" SELECT ");
            String changed =
                    sql.substring(0, select)
                            + sql.substring(select)
                                    .replaceFirst("\\b" + bound.getKey() + "\\b", "?");
            jdbc.update(changed, UUID.randomUUID(), "𐐀".repeat(bound.getValue()), event);
            fails(
                    "22001",
                    () ->
                            jdbc.update(
                                    changed,
                                    UUID.randomUUID(),
                                    "𐐀".repeat(bound.getValue() + 1),
                                    event));
        }
        fails(
                "22021",
                () ->
                        jdbc.queryForObject(
                                "SELECT convert_from(decode('eda080','hex'),'UTF8')",
                                String.class));
        fails(
                "22P02",
                () ->
                        jdbc.queryForObject(
                                "SELECT ('\"' || chr(92) || 'uD800\"')::jsonb", String.class));
    }

    /** 폐기형 상속/열 권한과 과잉 허용 일반 역할에서 실제 이력 guard를 대조한다. */
    @Test
    void disposableInheritedRuntimeCanInsertReadAndOnlyResolveWhileOvergrantsCannotRewriteHistory()
            throws Exception {
        String grant = token().toLowerCase();
        String role = token().toLowerCase();
        jdbc.execute(
                "CREATE ROLE "
                        + grant
                        + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        jdbc.execute(
                "CREATE ROLE "
                        + role
                        + " NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        jdbc.execute("GRANT " + grant + " TO " + role);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + grant);
        jdbc.execute("GRANT SELECT,INSERT ON test_action,test_audit,execution_issue TO " + grant);
        jdbc.execute(
                "GRANT UPDATE(state,resolved_batch_id,resolved_by,resolved_at,resolution_data) ON"
                        + " execution_issue TO "
                        + grant);
        for (String table : List.of("test_action", "test_audit", "execution_issue")) {
            String sequence =
                    jdbc.queryForObject(
                            "SELECT pg_get_serial_sequence(?, 'id')",
                            String.class,
                            "public." + table);
            jdbc.execute("GRANT USAGE ON SEQUENCE " + sequence + " TO " + grant);
        }
        for (String field :
                List.of(
                        "state",
                        "resolved_batch_id",
                        "resolved_by",
                        "resolved_at",
                        "resolution_data"))
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT has_column_privilege(?, 'public.execution_issue', ?,"
                                            + " 'UPDATE')",
                                    Boolean.class,
                                    role,
                                    field))
                    .isTrue();
        for (String field :
                List.of(
                        "id",
                        "issue_key",
                        "snapshot_id",
                        "runtime_id",
                        "batch_id",
                        "kind",
                        "severity",
                        "reason_code",
                        "created_at"))
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT has_column_privilege(?, 'public.execution_issue', ?,"
                                            + " 'UPDATE')",
                                    Boolean.class,
                                    role,
                                    field))
                    .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT has_schema_privilege(?, 'public', 'CREATE')",
                                Boolean.class,
                                role))
                .isFalse();
        for (String function :
                List.of("reject_batch_history_mutation()", "guard_batch_issue_history()"))
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT has_function_privilege(?, ?, 'EXECUTE')",
                                    Boolean.class,
                                    role,
                                    "public." + function))
                    .isFalse();
        try (var connection = postgres.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            try (var rows =
                    statement.executeQuery(
                            "SELECT current_user,session_user,pg_has_role(current_user,'"
                                    + grant
                                    + "','USAGE'),(SELECT NOT rolsuper FROM pg_roles WHERE"
                                    + " rolname=current_user)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(role);
                assertThat(rows.getString(2)).isNotEqualTo(role);
                assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.getBoolean(4)).isTrue();
            }
            statement.execute(
                    "INSERT INTO"
                        + " test_action(request_key,admin_id,action,scope_key,request_hash,result_data)"
                        + " VALUES ('"
                            + UUID.randomUUID()
                            + "',"
                            + admin
                            + ",'BATCH_CREATE','version:1','"
                            + HASH
                            + "','{}')");
            statement.execute(
                    "INSERT INTO"
                        + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                        + " VALUES ('"
                            + UUID.randomUUID()
                            + "','ADMIN','fixture','BATCH_CREATED','VERSION','version:1','"
                            + UUID.randomUUID()
                            + "','RESULT','SUCCESS','{}')");
            long issue;
            try (var rows =
                    statement.executeQuery(
                            "INSERT INTO"
                                + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                                + " VALUES ('"
                                    + UUID.randomUUID()
                                    + "',"
                                    + snapshot
                                    + ","
                                    + runtime
                                    + ","
                                    + source
                                    + ",'INFRA','MINOR','OPEN','FIXTURE') RETURNING id")) {
                assertThat(rows.next()).isTrue();
                issue = rows.getLong(1);
            }
            for (String table : List.of("test_action", "test_audit", "execution_issue")) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isPositive();
                }
                sqlFails(statement, "42501", "DELETE FROM " + table);
                sqlFails(statement, "42501", "TRUNCATE " + table);
            }
            sqlFails(
                    statement,
                    "42501",
                    "UPDATE execution_issue SET reason_code='CHANGED' WHERE id=" + issue);
            sqlFails(statement, "42501", "UPDATE test_action SET action='CHANGED'");
            sqlFails(statement, "42501", "UPDATE test_audit SET action='CHANGED'");
            assertThat(
                            statement.executeUpdate(
                                    "UPDATE execution_issue SET state='RESOLVED',resolved_batch_id="
                                            + target
                                            + ",resolved_by="
                                            + admin
                                            + ",resolved_at=clock_timestamp(),resolution_data='{}'"
                                            + " WHERE id="
                                            + issue))
                    .isEqualTo(1);
            try (var rows =
                    statement.executeQuery(
                            "SELECT state,resolved_batch_id,resolved_by FROM execution_issue WHERE"
                                    + " id="
                                    + issue)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("RESOLVED");
                assertThat(rows.getLong(2)).isEqualTo(target);
                assertThat(rows.getLong(3)).isEqualTo(admin);
            }
            statement.execute("RESET ROLE");
            jdbc.execute("GRANT ALL ON test_action,test_audit,execution_issue TO " + grant);
            long openIssue =
                    issue(UUID.randomUUID(), snapshot, runtime, source, "GRADING", "CRITICAL");
            statement.execute("SET ROLE " + role);
            for (String table : List.of("test_action", "test_audit")) {
                sqlFails(statement, "42501", "UPDATE " + table + " SET action='CHANGED'");
                sqlFails(statement, "42501", "DELETE FROM " + table);
                sqlFails(statement, "42501", "TRUNCATE " + table);
            }
            sqlFails(statement, "42501", "DELETE FROM execution_issue WHERE id=" + issue);
            sqlFails(statement, "42501", "DELETE FROM execution_issue WHERE id=" + openIssue);
            sqlFails(statement, "42501", "TRUNCATE execution_issue");
            sqlFails(
                    statement,
                    "23514",
                    "UPDATE execution_issue SET state='RESOLVED',resolved_batch_id="
                            + target
                            + ",resolved_by="
                            + admin
                            + ",resolved_at=clock_timestamp(),resolution_data='{}',reason_code='CHANGED'"
                            + " WHERE id="
                            + openIssue);
            sqlFails(
                    statement,
                    "23514",
                    "UPDATE execution_issue SET reason_code='CHANGED' WHERE id=" + issue);
            sqlFails(
                    statement,
                    "23514",
                    "UPDATE execution_issue SET"
                        + " state='OPEN',resolved_batch_id=NULL,resolved_by=NULL,resolved_at=NULL,resolution_data=NULL"
                        + " WHERE id="
                            + issue);
            sqlFails(
                    statement,
                    "23514",
                    "UPDATE execution_issue SET resolved_by=" + otherAdmin + " WHERE id=" + issue);
            sqlFails(
                    statement,
                    "23514",
                    "INSERT INTO"
                        + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                        + " VALUES ('"
                            + UUID.randomUUID()
                            + "',"
                            + snapshot
                            + ","
                            + runtime
                            + ","
                            + source
                            + ",'OBSERVATION','MINOR','RESOLVED','FIXTURE')");
            statement.execute("RESET ROLE");
        }
        // 소유자·superuser의 트리거 제거/비활성화 방어는 주장하지 않는다. 폐기 DB 종료가 역할 정리 경계다.
    }

    /** 정확한 합성 감사 실패 시 SQL 트랜잭션 전체 롤백을 검사하며 서비스 증거로 확대하지 않는다. */
    @Test
    void syntheticAuditFailureRollsBackCreationAggregationIssueAndResolutionSchemaTransactions() {
        UUID historicalKey = UUID.randomUUID();
        long receipt = action(historicalKey, admin, "BATCH_CREATE", "version:history", HASH, "{}");
        long audit = audit(UUID.randomUUID(), "ADMIN", "RESULT", "{}");
        long existing = issue(UUID.randomUUID(), snapshot, runtime, source, "INFRA", "MINOR");
        Map<String, Object> history = fingerprints(receipt, audit, existing);
        jdbc.execute(
                "CREATE FUNCTION public.batch_storage_audit_fault() RETURNS trigger LANGUAGE"
                        + " plpgsql AS $$ BEGIN RAISE EXCEPTION USING"
                        + " ERRCODE='23514',MESSAGE='SYNTHETIC_AUDIT_FAILURE'; END; $$");
        jdbc.execute(
                "CREATE TRIGGER batch_storage_audit_fault BEFORE INSERT ON test_audit FOR EACH ROW"
                        + " EXECUTE FUNCTION public.batch_storage_audit_fault()");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        try {
            UUID batchKey = UUID.randomUUID();
            UUID requestKey = UUID.randomUUID();
            UUID jobKey = UUID.randomUUID();
            UUID newIssueKey = UUID.randomUUID();
            auditFails(
                    () ->
                            tx.executeWithoutResult(
                                    status -> {
                                        long staged = batch(batchKey, snapshot, runtime);
                                        jdbc.update(
                                                "INSERT INTO"
                                                    + " grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,input_hash,config_hash,rubric_hash)"
                                                    + " VALUES"
                                                    + " (?,?,?,?,'FIXTURE',1,'STAGED',?,?,?)",
                                                jobKey,
                                                snapshot,
                                                runtime,
                                                staged,
                                                HASH,
                                                HASH,
                                                HASH);
                                        action(
                                                requestKey,
                                                admin,
                                                "BATCH_CREATE",
                                                "version:1",
                                                HASH,
                                                "{}");
                                        audit(UUID.randomUUID(), "ADMIN", "RESULT", "{}");
                                    }));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM grade_batch WHERE batch_key=?",
                                    Integer.class,
                                    batchKey))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM grade_job WHERE job_key=?",
                                    Integer.class,
                                    jobKey))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM test_action WHERE request_key=?",
                                    Integer.class,
                                    requestKey))
                    .isZero();
            auditFails(
                    () ->
                            tx.executeWithoutResult(
                                    status -> {
                                        jdbc.update(
                                                "UPDATE grade_batch SET"
                                                    + " state='FAILED',ended_at=clock_timestamp()"
                                                    + " WHERE id=?",
                                                target);
                                        issue(
                                                newIssueKey,
                                                snapshot,
                                                otherRuntime,
                                                target,
                                                "GRADING",
                                                "CRITICAL");
                                        audit(UUID.randomUUID(), "SYSTEM", "RESULT", "{}");
                                    }));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM grade_batch WHERE id=?",
                                    String.class,
                                    target))
                    .isEqualTo("STAGED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM execution_issue WHERE issue_key=?",
                                    Integer.class,
                                    newIssueKey))
                    .isZero();
            UUID resolutionReceipt = UUID.randomUUID();
            auditFails(
                    () ->
                            tx.executeWithoutResult(
                                    status -> {
                                        resolve(existing, target, admin, "{}");
                                        action(
                                                resolutionReceipt,
                                                admin,
                                                "ISSUE_RESOLVE",
                                                "issue:" + existing,
                                                HASH,
                                                "{}");
                                        audit(UUID.randomUUID(), "ADMIN", "RESULT", "{}");
                                    }));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM test_action WHERE request_key=?",
                                    Integer.class,
                                    resolutionReceipt))
                    .isZero();
            assertThat(fingerprints(receipt, audit, existing)).isEqualTo(history);
        } finally {
            jdbc.execute("DROP TRIGGER batch_storage_audit_fault ON test_audit");
            jdbc.execute("DROP FUNCTION public.batch_storage_audit_fault()");
        }
    }

    /** 고정 이미지 외 DB 주소·환경 자격을 받지 않는 폐기 컨테이너를 만든다. */
    private static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse(GradeSchemaIT.IMAGE).asCompatibleSubstituteFor("postgres"));
    }

    /**
     * 주어진 폐기 DB에만 원본 Java SQL을 지정 버전까지 적용하는 Flyway 설정을 만든다.
     *
     * @param database 시작된 null이 아닌 폐기형 PostgreSQL
     * @param target null이 아닌 목표 버전 16 또는 17
     * @return 기본 SQL 처리와 기존 이력 검증을 유지하는 Flyway
     */
    private static Flyway flyway(PostgreSQLContainer<?> database, String target) {
        return Flyway.configure()
                .resourceProvider(new EmbeddedSqlResourceProvider())
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    /** 실제 public 외래 키 수를 읽어 문서 추정과 분리한다. */
    private static int foreignCount(JdbcTemplate db) {
        return db.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE contype='f' AND"
                        + " connamespace='public'::regnamespace",
                Integer.class);
    }

    /** 예상 문자열 개수가 아닌 실제 public 객체 수로 V17 전역 추가 경계를 대조한다. */
    private static Map<String, Object> catalogCounts(JdbcTemplate db) {
        return db.queryForMap(
                """
                SELECT
                    (SELECT count(*) FROM information_schema.columns WHERE table_schema='public') AS columns,
                    (SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace) AS constraints,
                    (SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='i') AS indexes,
                    (SELECT count(*) FROM pg_proc WHERE pronamespace='public'::regnamespace) AS functions,
                    (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE NOT t.tgisinternal AND c.relnamespace='public'::regnamespace) AS triggers,
                    (SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='S') AS sequences
                """);
    }

    /** 순서·타입·NULL·identity·기본값·한글 주석을 실제 카탈로그에서 검사한다. */
    private void columns(String table, List<String> expected) {
        var rows =
                jdbc.queryForList(
                        """
                        SELECT attname,format_type(a.atttypid,a.atttypmod) AS type,attnotnull,attidentity,
                            pg_get_expr(d.adbin,d.adrelid) AS def,col_description(a.attrelid,a.attnum) AS comment
                        FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                        WHERE a.attrelid=?::regclass AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum
                        """,
                        "public." + table);
        assertThat(
                        rows.stream()
                                .map(
                                        r ->
                                                r.get("attname")
                                                        + ":"
                                                        + r.get("type")
                                                        + ":"
                                                        + r.get("attnotnull"))
                                .toList())
                .containsExactlyElementsOf(expected);
        for (var row : rows) {
            assertThat(row.get("attidentity"))
                    .isEqualTo(row.get("attname").equals("id") ? "a" : "");
            assertThat(row.get("def"))
                    .isEqualTo(row.get("attname").equals("created_at") ? "now()" : null);
            assertThat((String) row.get("comment")).containsPattern("[가-힣]");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT obj_description(?::regclass,'pg_class')",
                                String.class,
                                "public." + table))
                .containsPattern("[가-힣]");
    }

    /** 실제 사본 관계 fixture를 생성한다. */
    private long snapshot(long version) {
        return id(
                "INSERT INTO review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                        + " VALUES (?,0,'{}',?,?) RETURNING id",
                version,
                UUID.randomUUID(),
                admin);
    }

    /** 독립 UUID 코드로 실제 runtime 부모를 등록한다. */
    private long runtime() {
        return id(
                "INSERT INTO grade_runtime(code,config_hash,config_data,state) VALUES"
                        + " (?,?,'{}','AVAILABLE') RETURNING id",
                token(),
                HASH);
    }

    /** 실제 부모를 갖는 독립 STAGED 집합을 만든다. */
    private long batch(long snap, long run) {
        return batch(UUID.randomUUID(), snap, run);
    }

    /** 롤백 시험에서 외부 확인 가능한 UUID를 가진 집합을 만든다. */
    private long batch(UUID key, long snap, long run) {
        return id(
                "INSERT INTO"
                    + " grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,payload_hash,config_hash,runtime_epoch,state,expected_count,created_by)"
                    + " VALUES (?,?,?,'REVIEW',?,?,?,?,0,'STAGED',3,?) RETURNING id",
                key,
                snap,
                run,
                HASH,
                HASH,
                HASH,
                HASH,
                admin);
    }

    /** 시험 입력을 정규화 없이 전달하는 실제 영수증 INSERT다. */
    private long action(
            UUID key, long actor, String action, String scope, String hash, String json) {
        return id(actionInsert() + " RETURNING id", key, actor, action, scope, hash, json);
    }

    /** 필수 컬럼의 영수증 INSERT 형식을 한 곳에서 고정한다. */
    private static String actionInsert() {
        return "INSERT INTO"
                + " test_action(request_key,admin_id,action,scope_key,request_hash,result_data)"
                + " VALUES (?::uuid,?,?,?,?,?::jsonb)";
    }

    /** 비밀 없는 합성 감사 입력을 실제 제약으로 검사한다. */
    private long audit(UUID key, String actor, String phase, String json) {
        return id(
                "INSERT INTO"
                    + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                    + " VALUES (?,?,"
                    + " 'fixture','BATCH_CREATED','VERSION','version:1',?,?,'SUCCESS',?::jsonb)"
                    + " RETURNING id",
                key,
                actor,
                UUID.randomUUID(),
                phase,
                json);
    }

    /** 합성 관리자 감사의 UUID 음성 시험·NULL 시험에 쓰는 고정 INSERT다. */
    private static String auditInsert() {
        return "INSERT INTO"
                   + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)"
                   + " VALUES"
                   + " (?::uuid,'ADMIN','fixture','BATCH_CREATED','VERSION','version:1',?::uuid,'RESULT','SUCCESS','{}')";
    }

    /** 실제 출처와 OPEN 상태만 가진 지적을 만든다. */
    private long issue(UUID key, long snap, long run, long batch, String kind, String severity) {
        return id(issueInsert() + " RETURNING id", key, snap, run, batch, kind, severity);
    }

    /** 해소 필드 없는 OPEN 지적의 고정 INSERT다. */
    private static String issueInsert() {
        return "INSERT INTO"
                   + " execution_issue(issue_key,snapshot_id,runtime_id,batch_id,kind,severity,state,reason_code)"
                   + " VALUES (?::uuid,?,?,?,?,?,'OPEN','FIXTURE')";
    }

    /** 구조적 해소만 실행한다. 서비스 권한·후속 실행 품질 통과를 주장하지 않는다. */
    private void resolve(long issue, long batch, long actor, String json) {
        assertThat(
                        jdbc.update(
                                "UPDATE execution_issue SET"
                                    + " state='RESOLVED',resolved_batch_id=?,resolved_by=?,resolved_at=clock_timestamp(),resolution_data=?::jsonb"
                                    + " WHERE id=?",
                                batch,
                                actor,
                                json,
                                issue))
                .isEqualTo(1);
    }

    /** SQL JSONB 텍스트의 실제 UTF-8 바이트 수를 먼저 관측한 정확 경계 객체를 만든다. */
    private String exactJson(int limit, String character) {
        int overhead =
                jdbc.queryForObject(
                        "SELECT octet_length(jsonb_build_object('x','')::text)", Integer.class);
        int width = jdbc.queryForObject("SELECT octet_length(?)", Integer.class, character);
        String json =
                "{\"x\":\""
                        + character.repeat((limit - overhead) / width)
                        + "a".repeat((limit - overhead) % width)
                        + "\"}";
        assertThat(jdbc.queryForObject("SELECT octet_length(?::jsonb::text)", Integer.class, json))
                .isEqualTo(limit);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT octet_length(?::jsonb::text)",
                                Integer.class,
                                overflow(json)))
                .isEqualTo(limit + 1);
        return json;
    }

    /** 고정 객체 값에 ASCII 한 바이트를 추가한다. */
    private static String overflow(String json) {
        return json.substring(0, json.length() - 2) + "a\"}";
    }

    /** 기존 세 이력 행 전체의 SQL JSON 텍스트를 롤백 전후 대조한다. */
    private Map<String, Object> fingerprints(long receipt, long audit, long issue) {
        Map<String, Object> result = new HashMap<>();
        result.put(
                "receipt",
                jdbc.queryForObject(
                        "SELECT to_jsonb(t)::text FROM test_action t WHERE id=?",
                        String.class,
                        receipt));
        result.put(
                "audit",
                jdbc.queryForObject(
                        "SELECT to_jsonb(t)::text FROM test_audit t WHERE id=?",
                        String.class,
                        audit));
        result.put(
                "issue",
                jdbc.queryForObject(
                        "SELECT to_jsonb(t)::text FROM execution_issue t WHERE id=?",
                        String.class,
                        issue));
        return result;
    }

    /** JDBC 실패는 실제 근본 SQLException SQLSTATE로만 판정한다. */
    private static void fails(String state, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(
                        error -> {
                            Throwable cause = error;
                            while (cause.getCause() != null && !(cause instanceof SQLException))
                                cause = cause.getCause();
                            assertThat(cause).isInstanceOf(SQLException.class);
                            assertThat(((SQLException) cause).getSQLState()).isEqualTo(state);
                        });
    }

    /** SET ROLE 아래의 실제 SQLSTATE를 검사한다. 자동 커밋 실패 후 다음 명령을 분리한다. */
    private static void sqlFails(Statement statement, String state, String sql) {
        assertThatThrownBy(() -> statement.execute(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(state));
    }

    /** 무관한 setup CHECK 실패가 감사 주입 실패로 오인되지 않도록 실제 오류 메시지까지 대조한다. */
    private static void auditFails(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(
                        error -> {
                            Throwable cause = error;
                            while (cause.getCause() != null && !(cause instanceof SQLException))
                                cause = cause.getCause();
                            assertThat(cause).isInstanceOf(SQLException.class);
                            assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
                            assertThat(cause.getMessage()).contains("SYNTHETIC_AUDIT_FAILURE");
                        });
    }

    /** 실제 RETURNING 식별자만 읽는다. */
    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 합성 관계·역할 등록의 충돌을 피하는 비밀 아닌 UUID 코드다. */
    private static String token() {
        return "B_"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
}
