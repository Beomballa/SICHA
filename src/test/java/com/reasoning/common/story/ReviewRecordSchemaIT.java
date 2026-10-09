package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.GradeSchemaIT;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 최신 V22 검수 물리와 추가 전용 보호를 검사한다. 폐기형 행·역할은 검수 품질 증거가 아니다. */
class ReviewRecordSchemaIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private long reviewer;
    private long version;
    private long snapshot;
    private long otherSnapshot;
    private long record;

    /** 실제 최신 앱 스키마의 버전·적용 횟수와 공유 역사적 증가 검사를 유지한다. */
    @BeforeAll
    static void open() {
        // 공유 부트스트랩의 V12→V13 증가·최신 재실행 0·validate 검사를 그대로 사용한다.
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(version::integer) FROM public.flyway_schema_history"
                                        + " WHERE success",
                                Integer.class))
                .isEqualTo(23);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.flyway_schema_history WHERE success",
                                Integer.class))
                .isEqualTo(23);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void fixtures() {
        reviewer =
                jdbc.queryForObject(
                        "INSERT INTO public.admin_account(account_key) VALUES (?::uuid) RETURNING"
                                + " id",
                        Long.class,
                        UUID.randomUUID().toString());
        long story =
                jdbc.queryForObject(
                        "INSERT INTO public.story(code,owner_id) VALUES (?,?) RETURNING id",
                        Long.class,
                        "R" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                        reviewer);
        version =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 저장 시험','H3',?,?) RETURNING id",
                        Long.class,
                        story,
                        reviewer,
                        reviewer);
        snapshot = newSnapshot();
        otherSnapshot = newSnapshot();
        record =
                jdbc.queryForObject(
                        insert(snapshot, "STRUCTURE", "PASS", UUID.randomUUID()) + " RETURNING id",
                        Long.class);
    }

    @Test
    void exactColumnsDefaultsIdentityAndComments() {
        var columns =
                jdbc.queryForList(
                        """
                        SELECT a.attname,format_type(a.atttypid,a.atttypmod) AS type,a.attnotnull,a.attidentity,
                            pg_get_expr(d.adbin,d.adrelid) AS def,col_description(a.attrelid,a.attnum) AS comment
                        FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                        WHERE a.attrelid='public.review_record'::regclass AND a.attnum>0 AND NOT a.attisdropped
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
                        "snapshot_id:bigint:true",
                        "kind:character varying(16):true",
                        "request_key:uuid:true",
                        "evidence_data:jsonb:true",
                        "result:character varying(12):true",
                        "reviewer_id:bigint:true",
                        "model_id:character varying(80):false",
                        "effort:character varying(16):false",
                        "evidence:text:true",
                        "self_review_yn:boolean:true",
                        "created_at:timestamp with time zone:true",
                        "evidence_set_id:bigint:false");
        for (var column : columns) {
            String name = (String) column.get("attname");
            assertThat(column.get("attidentity")).isEqualTo(name.equals("id") ? "a" : "");
            assertThat(column.get("def")).isEqualTo(name.equals("created_at") ? "now()" : null);
            assertThat((String) column.get("comment")).containsPattern("[가-힣]");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT"
                                    + " obj_description('public.review_record'::regclass,'pg_class')",
                                String.class))
                .contains("STRUCTURE/MODEL/APPROVAL", "GRADE", "소유자");
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.evidence_set')", String.class))
                .isEqualTo("evidence_set");
    }

    @Test
    void exactConstraintsIndexesAndRealParentActions() throws Exception {
        var constraints =
                jdbc.queryForList(
                        """
                        SELECT conname,contype,pg_get_constraintdef(oid) AS def,
                            obj_description(oid,'pg_constraint') AS comment
                        FROM pg_constraint WHERE conrelid='public.review_record'::regclass
                        """);
        assertThat(
                        constraints.stream()
                                .map(c -> c.get("conname") + ":" + c.get("contype"))
                                .toList())
                .containsExactlyInAnyOrder(
                        "pk_review_record:p",
                        "uq_review_record_request:u",
                        "ck_review_record_kind:c",
                        "ck_review_record_result:c",
                        "ck_review_record_evidence:c",
                        "ck_review_record_data:c",
                        "fk_review_record_snapshot:f",
                        "fk_review_record_reviewer:f",
                        "ck_review_record_set:c",
                        "uq_review_record_set:u",
                        "fk_review_record_set:f");
        Map<String, String> definitions = new HashMap<>();
        for (var constraint : constraints) {
            definitions.put((String) constraint.get("conname"), (String) constraint.get("def"));
            assertThat((String) constraint.get("comment")).containsPattern("[가-힣]");
        }
        assertThat(definitions.get("pk_review_record")).isEqualTo("PRIMARY KEY (id)");
        assertThat(definitions.get("uq_review_record_request"))
                .isEqualTo("UNIQUE (snapshot_id, request_key)");
        assertThat(definitions.get("ck_review_record_kind"))
                .contains("STRUCTURE", "MODEL", "APPROVAL", "GRADE")
                .doesNotContain("PLAYTEST");
        assertThat(definitions.get("fk_review_record_set"))
                .contains(
                        "FOREIGN KEY (snapshot_id, evidence_set_id)",
                        "evidence_set(snapshot_id, id)");
        assertThat(definitions.get("ck_review_record_result"))
                .contains("PASS", "FAIL", "INCOMPLETE");
        assertThat(definitions.get("ck_review_record_evidence"))
                .contains("char_length(btrim(evidence))", "1", "20000");
        assertThat(definitions.get("ck_review_record_data"))
                .contains("jsonb_typeof(evidence_data)", "object", "octet_length", "131072");
        var indexes =
                jdbc.queryForList(
                        """
                        SELECT c.relname,am.amname,i.indisunique,i.indnkeyatts,i.indnatts,
                            pg_get_expr(i.indpred,i.indrelid) AS predicate,
                            pg_get_indexdef(i.indexrelid) AS def,obj_description(c.oid,'pg_class') AS comment
                        FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am am ON am.oid=c.relam
                        WHERE i.indrelid='public.review_record'::regclass
                        """);
        assertThat(indexes.stream().map(i -> i.get("relname")).toList())
                .containsExactlyInAnyOrder(
                        "pk_review_record", "uq_review_record_request", "uq_review_record_set");
        for (var index : indexes) {
            boolean primary = "pk_review_record".equals(index.get("relname"));
            boolean set = "uq_review_record_set".equals(index.get("relname"));
            assertThat(index.get("amname")).isEqualTo("btree");
            assertThat(index.get("indisunique")).isEqualTo(true);
            assertThat(((Number) index.get("indnkeyatts")).intValue())
                    .isEqualTo(primary || set ? 1 : 2);
            assertThat(index.get("indnatts")).isEqualTo(index.get("indnkeyatts"));
            assertThat(index.get("predicate")).isNull();
            assertThat((String) index.get("def"))
                    .contains(
                            primary
                                    ? "(id)"
                                    : set ? "(evidence_set_id)" : "(snapshot_id, request_key)")
                    .doesNotContain("DESC", "INCLUDE");
            assertThat((String) index.get("comment")).contains("미측정");
        }
        try (var connection = postgres.createConnection("")) {
            Map<String, String> parents = new HashMap<>();
            try (var keys =
                    connection.getMetaData().getImportedKeys(null, "public", "review_record")) {
                while (keys.next()) {
                    assertThat(keys.getInt("KEY_SEQ")).isBetween(1, 2);
                    assertThat(keys.getString("PKTABLE_SCHEM")).isEqualTo("public");
                    assertThat(keys.getInt("DELETE_RULE"))
                            .isEqualTo(DatabaseMetaData.importedKeyNoAction);
                    assertThat(keys.getInt("UPDATE_RULE"))
                            .isEqualTo(DatabaseMetaData.importedKeyNoAction);
                    parents.put(
                            keys.getString("FK_NAME") + ":" + keys.getInt("KEY_SEQ"),
                            keys.getString("FKCOLUMN_NAME")
                                    + "->"
                                    + keys.getString("PKTABLE_NAME")
                                    + "."
                                    + keys.getString("PKCOLUMN_NAME"));
                }
            }
            assertThat(parents)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of(
                                    "fk_review_record_snapshot:1",
                                            "snapshot_id->review_snapshot.id",
                                    "fk_review_record_reviewer:1", "reviewer_id->admin_account.id",
                                    "fk_review_record_set:1",
                                            "snapshot_id->evidence_set.snapshot_id",
                                    "fk_review_record_set:2", "evidence_set_id->evidence_set.id"));
        }
    }

    @Test
    void validKindsResultsRequiredFieldsDuplicatesAndForeignKeys() throws Exception {
        try (var connection = transaction()) {
            for (String kind : List.of("STRUCTURE", "MODEL", "APPROVAL")) {
                for (String result : List.of("PASS", "FAIL", "INCOMPLETE")) {
                    assertThat(
                                    execute(
                                            connection,
                                            insert(snapshot, kind, result, UUID.randomUUID())))
                            .isEqualTo(1);
                }
            }
            for (String field :
                    List.of(
                            "id",
                            "snapshot_id",
                            "kind",
                            "request_key",
                            "evidence_data",
                            "result",
                            "reviewer_id",
                            "evidence",
                            "self_review_yn",
                            "created_at")) {
                // GENERATED ALWAYS 식별자는 OVERRIDING을 명시해 NOT NULL 자체를 검사한다.
                if (field.equals("id")) {
                    fails(
                            connection,
                            "23502",
                            "INSERT INTO public.review_record OVERRIDING SYSTEM VALUE SELECT"
                                + " NULL,snapshot_id,kind,gen_random_uuid(),evidence_data,result,reviewer_id,model_id,effort,evidence,self_review_yn,created_at,evidence_set_id"
                                + " FROM public.review_record WHERE id="
                                    + record);
                } else {
                    fails(connection, "23502", update(field + "=NULL"));
                }
            }
            for (String kind : List.of("UNKNOWN", "PLAYTEST", "structure", ""))
                fails(connection, "23514", update("kind='" + kind + "'"));
            fails(connection, "23503", update("kind='GRADE'"));
            fails(connection, "23514", update("result='UNKNOWN'"));
            fails(
                    connection,
                    "23502",
                    "INSERT INTO"
                        + " public.review_record(snapshot_id,kind,evidence_data,result,reviewer_id,evidence,self_review_yn)"
                        + " VALUES ("
                            + snapshot
                            + ",'STRUCTURE','{}','PASS',"
                            + reviewer
                            + ",'합성',false)");
            fails(
                    connection,
                    "23502",
                    "INSERT INTO"
                        + " public.review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence)"
                        + " VALUES ("
                            + snapshot
                            + ",'STRUCTURE',gen_random_uuid(),'{}','PASS',"
                            + reviewer
                            + ",'합성')");
            UUID key = UUID.randomUUID();
            execute(connection, insert(snapshot, "MODEL", "PASS", key));
            fails(connection, "23505", insert(snapshot, "APPROVAL", "FAIL", key));
            assertThat(execute(connection, insert(otherSnapshot, "MODEL", "PASS", key)))
                    .isEqualTo(1);
            for (String field : List.of("snapshot_id", "reviewer_id"))
                fails(connection, "23503", update(field + "=9223372036854775807"));
            fails(connection, "23503", "DELETE FROM public.review_snapshot WHERE id=" + snapshot);
            fails(connection, "23503", "DELETE FROM public.admin_account WHERE id=" + reviewer);
            // 실제 부모의 ALWAYS 식별자 보호를 시험 TX에서만 풀어 FK의 UPDATE NO ACTION을 별도로 검사한다.
            // 전체 TX rollback으로 원래 identity 정의를 복원하며 V1/V2 마이그레이션은 변경하지 않는다.
            execute(
                    connection,
                    "ALTER TABLE public.review_snapshot ALTER COLUMN id SET GENERATED BY DEFAULT");
            fails(
                    connection,
                    "23503",
                    "UPDATE public.review_snapshot SET id=9223372036854775807 WHERE id="
                            + snapshot);
            execute(
                    connection,
                    "ALTER TABLE public.admin_account ALTER COLUMN id SET GENERATED BY DEFAULT");
            fails(
                    connection,
                    "23503",
                    "UPDATE public.admin_account SET id=9223372036854775807 WHERE id=" + reviewer);
            execute(connection, update("model_id=NULL,effort=NULL,self_review_yn=false"));
            // 서비스별 모델·effort 조합이나 UUID 버전 제약을 SQL에 추가하지 않는다.
            execute(
                    connection,
                    update(
                            "model_id='',effort='',request_key='00000000-0000-0000-0000-000000000000',self_review_yn=true"));
            connection.rollback();
        }
    }

    @Test
    void unicodeTextVarcharAndJsonbStoredEnvelopeBoundaries() throws Exception {
        try (var connection = transaction()) {
            for (String value : List.of("''", "'   '", "repeat('a',20001)", "repeat('𐐀',20001)"))
                fails(connection, "23514", update("evidence=" + value));
            for (String value : List.of("'a'", "repeat('a',20000)", "repeat('𐐀',20000)"))
                execute(connection, update("evidence=" + value));
            // btrim은 ASCII 공백만 제거한다. 탭·개행의 의미상 공백 검사는 서비스 책임이다.
            execute(connection, update("evidence=chr(9)||chr(10)"));
            for (var field :
                    Map.of("model_id", 80, "effort", 16, "kind", 16, "result", 12).entrySet()) {
                fails(
                        connection,
                        "22001",
                        update(field.getKey() + "=repeat('a'," + (field.getValue() + 1) + ")"));
                fails(
                        connection,
                        "22001",
                        update(field.getKey() + "=repeat('𐐀'," + (field.getValue() + 1) + ")"));
                if (field.getKey().equals("model_id") || field.getKey().equals("effort"))
                    execute(
                            connection,
                            update(field.getKey() + "=repeat('𐐀'," + field.getValue() + ")"));
            }
            for (String json : List.of("'null'", "'[]'", "'1'", "'\"scalar\"'"))
                fails(connection, "23514", update("evidence_data=" + json + "::jsonb"));
            // PostgreSQL이 직렬화한 메타데이터 및 escape 크기를 직접 측정한다. 원시 UTF-8 길이 추정 금지.
            for (String unit : List.of("'a'", "'𐐀'", "chr(10)", "chr(34)", "chr(92)")) {
                String empty =
                        "jsonb_build_object('formatNo',1,'request',jsonb_build_object('expectedRev','0'),"
                            + "'details',jsonb_build_object('syntheticOnly',true,'x',''))";
                int overhead = scalar(connection, "SELECT octet_length((" + empty + ")::text)");
                int width =
                        scalar(
                                connection,
                                "SELECT octet_length(jsonb_build_object('x',"
                                        + unit
                                        + ")::text)-octet_length(jsonb_build_object('x','')::text)");
                int count = (131072 - overhead) / width;
                int remainder = (131072 - overhead) % width;
                String exact =
                        empty.replace(
                                "'x',''",
                                "'x',repeat("
                                        + unit
                                        + ","
                                        + count
                                        + ")||repeat('a',"
                                        + remainder
                                        + ")");
                assertThat(scalar(connection, "SELECT octet_length((" + exact + ")::text)"))
                        .isEqualTo(131072);
                execute(connection, update("evidence_data=" + exact));
                String over =
                        empty.replace(
                                "'x',''",
                                "'x',repeat("
                                        + unit
                                        + ","
                                        + count
                                        + ")||repeat('a',"
                                        + (remainder + 1)
                                        + ")");
                assertThat(scalar(connection, "SELECT octet_length((" + over + ")::text)"))
                        .isEqualTo(131073);
                fails(connection, "23514", update("evidence_data=" + over));
            }
            connection.rollback();
        }
    }

    @Test
    void nonOwnerInheritedInsertSelectWithoutEffectiveMutationPrivileges() throws Exception {
        // 역할·권한은 시험 TX에서만 생성하고 rollback한다. 일반 배포 역할 정책이나 소유자 보호 주장이 아니다.
        try (var connection = transaction()) {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String writer = "h3_writer_" + suffix;
            String actor = "h3_actor_" + suffix;
            execute(
                    connection,
                    "CREATE ROLE "
                            + writer
                            + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
            execute(
                    connection,
                    "CREATE ROLE "
                            + actor
                            + " NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
            execute(connection, "GRANT " + writer + " TO " + actor);
            execute(connection, "GRANT USAGE ON SCHEMA public TO " + writer);
            execute(
                    connection,
                    "GRANT SELECT,INSERT ON public.review_record,public.review_snapshot TO "
                            + writer);
            for (String table : List.of("review_record", "review_snapshot")) {
                String sequence =
                        jdbc.queryForObject(
                                "SELECT pg_get_serial_sequence(?, 'id')",
                                String.class,
                                "public." + table);
                execute(connection, "GRANT USAGE ON SEQUENCE " + sequence + " TO " + writer);
                assertThat(
                                jdbc.queryForObject(
                                        """
                                        SELECT count(*) FROM pg_class c CROSS JOIN LATERAL
                                            aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) acl
                                        WHERE c.oid=?::regclass AND acl.grantee=0
                                            AND acl.privilege_type IN ('UPDATE','DELETE','TRUNCATE')
                                        """,
                                        Integer.class,
                                        "public." + table))
                        .isZero();
            }
            execute(connection, "SET LOCAL ROLE " + actor);
            assertThat(
                            scalar(
                                    connection,
                                    "SELECT count(*) FROM pg_roles WHERE rolname=current_user AND"
                                            + " NOT rolsuper AND NOT rolbypassrls"))
                    .isEqualTo(1);
            for (String table : List.of("review_record", "review_snapshot")) {
                assertThat(
                                scalar(
                                        connection,
                                        "SELECT count(*) FROM pg_class WHERE oid='public."
                                                + table
                                                + "'::regclass AND"
                                                + " pg_get_userbyid(relowner)<>current_user"))
                        .isEqualTo(1);
                for (String permission : List.of("SELECT", "INSERT"))
                    assertThat(
                                    scalar(
                                            connection,
                                            "SELECT has_table_privilege(current_user,'public."
                                                    + table
                                                    + "','"
                                                    + permission
                                                    + "')::integer"))
                            .isEqualTo(1);
                for (String permission : List.of("UPDATE", "DELETE", "TRUNCATE"))
                    assertThat(
                                    scalar(
                                            connection,
                                            "SELECT has_table_privilege(current_user,'public."
                                                    + table
                                                    + "','"
                                                    + permission
                                                    + "')::integer"))
                            .isZero();
                assertThat(scalar(connection, "SELECT count(*) FROM public." + table)).isPositive();
                fails(connection, "42501", "UPDATE public." + table + " SET created_at=now()");
                fails(connection, "42501", "DELETE FROM public." + table);
                fails(connection, "42501", "TRUNCATE public." + table);
            }
            assertThat(execute(connection, snapshotInsert())).isEqualTo(1);
            assertThat(
                            execute(
                                    connection,
                                    insert(snapshot, "MODEL", "INCOMPLETE", UUID.randomUUID())))
                    .isEqualTo(1);
            connection.rollback();
        }
    }

    /** 실제 부모를 가진 합성 시험 사본을 생성한다. */
    private long newSnapshot() {
        return jdbc.queryForObject(snapshotInsert() + " RETURNING id", Long.class);
    }

    /** 시험 버전과 관리자에 연결된 사본 INSERT를 반환한다. 원고 품질 증거는 포함하지 않는다. */
    private String snapshotInsert() {
        return "INSERT INTO"
                + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                + " VALUES ("
                + version
                + ",0,'{\"syntheticOnly\":true}','"
                + UUID.randomUUID()
                + "',"
                + reviewer
                + ")";
    }

    /**
     * 실제 모델 실행을 주장하지 않는 시험 INSERT를 만든다.
     *
     * @param target 실제 시험 사본의 양수 식별자
     * @param kind null이 아닌 SQL 상수 검수 종류
     * @param result null이 아닌 SQL 상수 결과
     * @param key null이 아닌 시험 요청 UUID; 버전은 물리 제약 대상이 아님
     * @return 합성 메타데이터 INSERT SQL
     */
    private String insert(long target, String kind, String result, UUID key) {
        return "INSERT INTO"
                   + " public.review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,evidence,self_review_yn)"
                   + " VALUES ("
                + target
                + ",'"
                + kind
                + "','"
                + key
                + "','{\"syntheticOnly\":true}', '"
                + result
                + "',"
                + reviewer
                + ",'합성 저장 메타데이터; 검수 품질 증거 아님',false)";
    }

    /** 기존 행을 변경하지 않고 새 INSERT 후보로 물리 제약을 검사한다. 상수 대입식만 허용한다. */
    private String update(String fields) {
        Map<String, String> replacements = new HashMap<>();
        for (String assignment :
                fields.startsWith("model_id=NULL,") || fields.startsWith("model_id='',")
                        ? List.of(fields.split(","))
                        : List.of(fields)) {
            int equals = assignment.indexOf('=');
            replacements.put(assignment.substring(0, equals), assignment.substring(equals + 1));
        }
        List<String> columns =
                List.of(
                        "snapshot_id",
                        "kind",
                        "request_key",
                        "evidence_data",
                        "result",
                        "reviewer_id",
                        "model_id",
                        "effort",
                        "evidence",
                        "self_review_yn",
                        "created_at",
                        "evidence_set_id");
        return "INSERT INTO public.review_record("
                + String.join(",", columns)
                + ") SELECT "
                + columns.stream()
                        .map(
                                column ->
                                        replacements.getOrDefault(
                                                column,
                                                column.equals("request_key")
                                                        ? "gen_random_uuid()"
                                                        : column))
                        .collect(java.util.stream.Collectors.joining(","))
                + " FROM public.review_record WHERE id="
                + record;
    }

    /** 소유자도 일반 UPDATE·DELETE로 기존 검수 행을 고칠 수 없다. */
    @Test
    void ownerCannotUpdateDeleteOrTruncateAppendOnlyRecords() throws Exception {
        try (var connection = transaction()) {
            fails(
                    connection,
                    "42501",
                    "UPDATE public.review_record SET evidence='changed' WHERE id=" + record);
            fails(connection, "42501", "DELETE FROM public.review_record WHERE id=" + record);
            fails(connection, "42501", "TRUNCATE public.review_record");
            assertThat(
                            scalar(
                                    connection,
                                    "SELECT count(*) FROM public.review_record WHERE id=" + record))
                    .isEqualTo(1);
            connection.rollback();
        }
    }

    /** 각 시험이 rollback할 수 있는 격리 연결을 만든다. */
    private static Connection transaction() throws SQLException {
        var connection = postgres.createConnection("");
        connection.setAutoCommit(false);
        return connection;
    }

    /**
     * 시험 상수 SQL 한 문장을 실행한다. 실패의 원문이나 메타데이터는 기록하지 않는다.
     *
     * @param connection null이 아닌 시험 TX 연결
     * @param sql null이 아닌 시험 SQL
     * @return 변경된 행 수 또는 DDL의 0
     * @throws SQLException 실행 또는 10초 제한을 초과할 때
     */
    private static int execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            return statement.executeUpdate(sql);
        }
    }

    /**
     * 실패 문장마다 savepoint를 복구하여 다음 검사의 TX를 오염시키지 않는다.
     *
     * @param connection null이 아닌 자동 커밋 해제 연결
     * @param state null이 아닌 예상 SQLState 5자리
     * @param sql null이 아닌 실패 시험 SQL; 오류 원문은 단언하지 않음
     * @throws SQLException savepoint 생성·복구에 실패할 때
     */
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

    /**
     * PostgreSQL이 계산한 카탈로그 수·문자열 바이트 경계를 읽는다.
     *
     * @param connection null이 아닌 시험 연결
     * @param sql null이 아닌 단일 정수 조회 SQL
     * @return 첫 행의 정수; null 값은 허용하지 않음
     * @throws SQLException 조회 또는 10초 제한을 초과할 때
     */
    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery(sql)) {
                assertThat(rows.next()).isTrue();
                int value = rows.getInt(1);
                assertThat(rows.wasNull()).isFalse();
                assertThat(rows.next()).isFalse();
                return value;
            }
        }
    }
}
