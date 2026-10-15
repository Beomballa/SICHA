package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.migration.EmbeddedSqlResourceProvider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 폐기형 합성 PG에서 회원 파기와 최초 노출 출처의 불변성을 검증한다. */
class MemberErasureSchemaIT {
    private static final String IMAGE =
            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1";
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate db;
    private static long policy;

    /** 전체 원본을 적용하고 운영 역할과 무관한 폐기형 테스트 역할만 만든다. */
    @BeforeAll
    static void open() throws Exception {
        postgres =
                new PostgreSQLContainer<>(
                        DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
        postgres.start();
        var source =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db = new JdbcTemplate(source);
        var flyway =
                Flyway.configure()
                        .dataSource(source)
                        .resourceProvider(new EmbeddedSqlResourceProvider())
                        .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(44);
        flyway.validate();
        long admin =
                db.queryForObject(
                        "INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())"
                                + " RETURNING id",
                        Long.class);
        policy =
                db.queryForObject(
                        "INSERT INTO"
                            + " public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                            + " VALUES"
                            + " (gen_random_uuid()::text,'SYNTHETIC','MEMBER_AUTH','DRAFT',repeat('a',64),?::jsonb,?)"
                            + " RETURNING id",
                        Long.class,
                        policyDocument(),
                        admin);
        db.execute("CREATE ROLE erasure_probe NOLOGIN");
        db.execute("GRANT USAGE ON SCHEMA public TO erasure_probe");
        db.execute(
                "GRANT SELECT, DELETE ON public.member_account,public.member_auth_audit TO"
                        + " erasure_probe");
        db.execute("GRANT SELECT, UPDATE ON public.test_exposure TO erasure_probe");
        db.execute("GRANT SELECT ON public.play_test,public.game_room TO erasure_probe");
        db.execute("GRANT SELECT ON public.privacy_erase TO erasure_probe");
        db.execute("GRANT SELECT, UPDATE ON public.privacy_target TO erasure_probe");
        db.execute(
                "GRANT EXECUTE ON FUNCTION public.privacy_primary_delete_allowed(bigint,bigint) TO"
                        + " erasure_probe");
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 합성 정책 문서는 기존 MEMBER_AUTH 문서 검사만 통과하며 실재 근거를 주장하지 않는다. */
    private static String policyDocument() throws Exception {
        var evidence = new TreeMap<String, Object>();
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
                            "2025-01-01T00:00:00Z",
                            "validUntil",
                            "2027-01-01T00:00:00Z"));
        }
        return new ObjectMapper()
                .writeValueAsString(
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
                                "2026-01-01T00:00:00Z",
                                "validUntil",
                                "2026-12-31T00:00:00Z",
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
                                evidence));
    }

    /** 검증에서 사용하는 실재 계정, 세 종류 대상과 봉인된 불변 명세를 생성한다. */
    private static long[] manifest() {
        long member =
                db.queryForObject(
                        "INSERT INTO public.member_account(member_key,state) VALUES"
                                + " (gen_random_uuid(),'ACTIVE') RETURNING id",
                        Long.class);
        db.update(
                "INSERT INTO public.member_profile(member_id,nickname_cipher,policy_id,accepted_at)"
                        + " VALUES (?,decode(repeat('03',32),'hex'),?,clock_timestamp())",
                member,
                policy);
        long root =
                db.queryForObject(
                        "INSERT INTO"
                            + " public.privacy_erase(erase_key,member_id,policy_id,reason_code,locator_cipher,receipt_hash,receipt_until,backup_until)"
                            + " VALUES"
                            + " (gen_random_uuid(),?,?,'ACCOUNT_REQUEST',decode(repeat('01',32),'hex'),repeat('a',64),clock_timestamp()+interval"
                            + " '89 days',clock_timestamp()+interval '34 days') RETURNING id",
                        Long.class,
                        member,
                        policy);
        long primary =
                db.queryForObject(
                        "INSERT INTO"
                            + " public.privacy_target(erase_id,target_key,kind,source_kind,member_id,locator_cipher)"
                            + " VALUES"
                            + " (?,gen_random_uuid(),'PRIMARY','MEMBER',?,decode(repeat('02',32),'hex'))"
                            + " RETURNING id",
                        Long.class,
                        root,
                        member);
        db.update(
                "INSERT INTO public.privacy_target(erase_id,target_key,kind,source_kind) VALUES"
                    + " (?,gen_random_uuid(),'BACKUP','NONE'),(?,gen_random_uuid(),'RESTORE_GUARD','NONE')",
                root,
                root);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE public.privacy_erase SET"
                                            + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=3,state='PROCESSING'"
                                            + " WHERE id=?",
                                        root))
                .hasMessageContaining("erasure manifest incomplete");
        db.update(
                "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                        + " WHERE id=?",
                member);
        db.update(
                "UPDATE public.privacy_erase SET"
                    + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=3,state='PROCESSING'"
                    + " WHERE id=?",
                root);
        db.update(
                "UPDATE public.privacy_target SET"
                    + " state='RUNNING',lease_until=clock_timestamp()+interval '1 hour' WHERE id=?",
                primary);
        return new long[] {member, root, primary};
    }

    /** 실제 대기 중인 FUNCTIONAL 테스트와 참가자의 최초 노출 행을 생성한다. */
    private static long[] exposure() {
        long admin =
                db.queryForObject(
                        "INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())"
                                + " RETURNING id",
                        Long.class);
        long story =
                db.queryForObject(
                        "INSERT INTO public.story(code,owner_id) VALUES"
                            + " ('EX'||upper(replace(gen_random_uuid()::text,'-','')),?) RETURNING"
                            + " id",
                        Long.class,
                        admin);
        long version =
                db.queryForObject(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 노출','H2',?,?) RETURNING id",
                        Long.class,
                        story,
                        admin,
                        admin);
        long snapshot =
                db.queryForObject(
                        "INSERT INTO"
                            + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,'{}',gen_random_uuid(),?) RETURNING id",
                        Long.class,
                        version,
                        admin);
        long runtime =
                db.queryForObject(
                        "INSERT INTO public.grade_runtime(code,config_hash,config_data,state)"
                            + " VALUES (gen_random_uuid()::text,repeat('a',64),'{}','AVAILABLE')"
                            + " RETURNING id",
                        Long.class);
        db.update(
                "INSERT INTO public.story_role(version_id,code,name) VALUES"
                        + " (?,'R1','R1'),(?,'R2','R2')",
                version,
                version);
        db.update(
                "INSERT INTO public.story_pair(version_id,role_a,role_b) VALUES (?,'R1','R2')",
                version);
        long test =
                db.queryForObject(
                        "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                            + " public.play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,"
                            + " runtime_epoch,role_a,role_b,mode,invite_until,created_by,created_at,updated_at)"
                            + " SELECT"
                            + " gen_random_uuid(),?,?,?,repeat('a',64),0,'R1','R2','FUNCTIONAL',"
                            + " t.n+interval '7 days',?,t.n,t.n FROM t RETURNING id",
                        Long.class,
                        version,
                        snapshot,
                        runtime,
                        admin);
        long member =
                db.queryForObject(
                        "INSERT INTO public.member_account(member_key,state) VALUES"
                                + " (gen_random_uuid(),'ACTIVE') RETURNING id",
                        Long.class);
        db.update(
                "INSERT INTO public.test_member(test_id,member_id,slot) VALUES (?,?,1)",
                test,
                member);
        db.update(
                "INSERT INTO public.test_exposure(story_id,member_id,first_test_id)"
                        + " VALUES (?,?,?)",
                story,
                member,
                test);
        return new long[] {story, member, test};
    }

    /** 실제 출처가 존재하는 동안 직접 분리와 재결속, 노출 사실 변경을 모두 거부한다. */
    @Test
    void exposureSourceAndIdentityCannotBeOverwritten() throws SQLException {
        long[] ids = exposure();
        String row = " WHERE story_id=" + ids[0] + " AND member_id=" + ids[1];
        for (String update :
                List.of(
                        "first_test_id=NULL",
                        "first_test_id=9223372036854775807",
                        "first_test_id=NULL,first_room_id=9223372036854775807",
                        "first_room_id=NULL",
                        "first_exposed_at=first_exposed_at+interval '1 second'",
                        "story_id=story_id+922337203685477580",
                        "member_id=member_id+922337203685477580")) {
            assertThatThrownBy(() -> db.update("UPDATE public.test_exposure SET " + update + row))
                    .hasStackTraceContaining("test_exposure is append only");
        }
        assertThatThrownBy(
                        () -> asProbe("UPDATE public.test_exposure SET first_test_id=NULL" + row))
                .hasMessageContaining("test_exposure is append only");
        assertThatThrownBy(() -> db.execute("TRUNCATE public.test_exposure"))
                .hasStackTraceContaining("test_exposure is append only");
        assertThat(
                        db.queryForObject(
                                "SELECT first_test_id FROM public.test_exposure" + row, Long.class))
                .isEqualTo(ids[2]);
        long unknownMember =
                db.queryForObject(
                        "INSERT INTO public.member_account(member_key,state) VALUES"
                                + " (gen_random_uuid(),'ACTIVE') RETURNING id",
                        Long.class);
        db.update(
                "INSERT INTO public.test_exposure(story_id,member_id) VALUES (?,?)",
                ids[0],
                unknownMember);
        String unknownRow = " WHERE story_id=" + ids[0] + " AND member_id=" + unknownMember;
        for (String update :
                List.of(
                        "first_test_id=" + ids[2],
                        "first_room_id=9223372036854775807",
                        "first_room_id=NULL")) {
            assertThatThrownBy(
                            () ->
                                    db.update(
                                            "UPDATE public.test_exposure SET "
                                                    + update
                                                    + unknownRow))
                    .hasStackTraceContaining("test_exposure is append only");
        }
    }

    /** 만료 임대는 원자적으로 실패하고 새 임대에서 물리 제거와 영수증이 함께 확정된다. */
    @Test
    void rollbackRetryAndNoGamePhysicalRemoval() {
        long[] ids = manifest();
        db.update(
                "UPDATE public.privacy_target SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE id=?",
                ids[2]);
        assertThatThrownBy(
                        () ->
                                db.queryForObject(
                                        "SELECT public.consume_privacy_primary(?)",
                                        Boolean.class,
                                        ids[2]))
                .hasMessageContaining("primary manifest or lease invalid");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM public.privacy_target WHERE id=?",
                                String.class,
                                ids[2]))
                .isEqualTo("RUNNING");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM public.member_account WHERE id=?",
                                Integer.class,
                                ids[0]))
                .isEqualTo(1);
        db.update(
                "UPDATE public.privacy_target SET lease_until=clock_timestamp()+interval '1 hour'"
                        + " WHERE id=?",
                ids[2]);
        assertThat(
                        db.queryForObject(
                                "SELECT public.consume_privacy_primary(?)", Boolean.class, ids[2]))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT public.consume_privacy_primary(?)", Boolean.class, ids[2]))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM public.member_account WHERE id=?",
                                Integer.class,
                                ids[0]))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT member_id FROM public.privacy_erase WHERE id=?",
                                Long.class,
                                ids[1]))
                .isNull();
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM public.privacy_erase WHERE id=?",
                                String.class,
                                ids[1]))
                .isEqualTo("WAITING");
        assertThat(
                        db.queryForObject(
                                "SELECT receipt_hash FROM public.privacy_erase WHERE id=?",
                                String.class,
                                ids[1]))
                .isEqualTo("a".repeat(64));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM public.privacy_target WHERE erase_id=? AND"
                                        + " kind IN ('BACKUP','RESTORE_GUARD') AND state='PENDING'",
                                Integer.class,
                                ids[1]))
                .isEqualTo(2);
    }

    /** 실재 FK, 불완전한 대상과 봉인 뒤 추가·소유자 변경을 거부한다. */
    @Test
    void ownershipAndImmutableManifest() {
        long[] ids = manifest();
        assertThat(
                        db.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND"
                                        + " tablename='privacy_target' AND"
                                        + " indexname='uk_pt_canonical_test'",
                                String.class))
                .contains("UNIQUE", "test_id", "dependency_id IS NULL");
        assertThat(
                        db.queryForObject(
                                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                                        + " conname='fk_pt_attempt' AND"
                                        + " conrelid='public.privacy_target'::regclass",
                                String.class))
                .contains(
                        "FOREIGN KEY (source_job_id, source_attempt_no)",
                        "grade_attempt(job_id, attempt_no)",
                        "ON DELETE SET NULL");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE public.privacy_target SET"
                                            + " state='COMPLETE',member_id=NULL,lease_until=NULL,locator_cipher=NULL,confirmed_at=clock_timestamp()"
                                            + " WHERE id=?",
                                        ids[2]))
                .hasStackTraceContaining("erasure target binding immutable");
        long other =
                db.queryForObject(
                        "INSERT INTO public.member_account(member_key,state) VALUES"
                                + " (gen_random_uuid(),'ACTIVE') RETURNING id",
                        Long.class);
        db.update(
                "INSERT INTO public.member_profile(member_id,nickname_cipher,policy_id,accepted_at)"
                        + " VALUES (?,decode(repeat('03',32),'hex'),?,clock_timestamp())",
                other,
                policy);
        long open =
                db.queryForObject(
                        "INSERT INTO"
                            + " public.privacy_erase(erase_key,member_id,policy_id,reason_code,locator_cipher)"
                            + " VALUES"
                            + " (gen_random_uuid(),?,?,'ACCOUNT_REQUEST',decode(repeat('01',32),'hex'))"
                            + " RETURNING id",
                        Long.class,
                        other,
                        policy);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_target(erase_id,target_key,kind,source_kind)"
                                            + " VALUES (?,gen_random_uuid(),'PRIMARY','MEMBER')",
                                        open))
                .hasMessageContaining("erasure target ownership invalid");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_target(erase_id,target_key,kind,source_kind,member_id)"
                                            + " VALUES (?,gen_random_uuid(),'PRIMARY','MEMBER',?)",
                                        open,
                                        ids[0]))
                .hasMessageContaining("erasure target ownership invalid");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_target(erase_id,target_key,kind,source_kind,source_job_id,source_attempt_no,locator_cipher)"
                                            + " VALUES"
                                            + " (?,gen_random_uuid(),'PROCESSOR','NONE',9223372036854775807,1,decode(repeat('02',32),'hex'))",
                                        open))
                .hasMessageContaining("processor attempt ownership invalid");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_target(erase_id,target_key,kind,source_kind,locator_cipher)"
                                            + " VALUES"
                                            + " (?,gen_random_uuid(),'PROCESSOR','NONE',decode(repeat('02',32),'hex'))",
                                        open))
                .hasMessageContaining("processor attempt ownership invalid");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_target(erase_id,target_key,kind,source_kind,dependency_id)"
                                            + " VALUES"
                                            + " (?,gen_random_uuid(),'BACKUP','NONE',9223372036854775807)",
                                        open))
                .hasMessageContaining("canonical source dependency invalid");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE public.privacy_target SET state='RETRY' WHERE id=?",
                                        ids[2]))
                .hasMessageContaining("ck_pt_lease");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE public.privacy_target SET lease_until=NULL WHERE"
                                                + " id=?",
                                        ids[2]))
                .hasMessageContaining("ck_pt_lease");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE public.privacy_target SET member_id=? WHERE id=?",
                                        other,
                                        ids[2]))
                .hasStackTraceContaining("erasure target binding immutable");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_target(erase_id,target_key,kind,source_kind,member_id)"
                                            + " VALUES (?,gen_random_uuid(),'PRIMARY','MEMBER',?)",
                                        ids[1],
                                        ids[0]))
                .hasMessageContaining("erasure target ownership invalid");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_target(erase_id,target_key,kind,source_kind,member_id)"
                                            + " VALUES"
                                            + " (9223372036854775807,gen_random_uuid(),'PRIMARY','MEMBER',?)",
                                        ids[0]))
                .hasMessageContaining("erasure target ownership invalid");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE public.privacy_erase SET member_id=? WHERE id=?",
                                        other,
                                        ids[1]))
                .hasStackTraceContaining("erasure binding immutable");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " public.privacy_erase(erase_key,member_id,policy_id,reason_code,locator_cipher)"
                                            + " VALUES"
                                            + " (gen_random_uuid(),9223372036854775807,?,'ACCOUNT_REQUEST',decode(repeat('01',32),'hex'))",
                                        policy))
                .hasMessageContaining("invalid erasure owner or policy");
    }

    /** 명시적으로 부여한 비소유자 DELETE조차 실행 소유자가 아니면 거부한다. */
    @Test
    void nonOwnerCannotDeleteOrInvokeConsumer() throws SQLException {
        long[] ids = manifest();
        assertThatThrownBy(
                        () ->
                                asProbe(
                                        "UPDATE public.privacy_target SET state='COMPLETE',"
                                            + "locator_cipher=NULL,lease_until=NULL,confirmed_at=clock_timestamp(),result_code='PHYSICAL_REMOVED'"
                                            + " WHERE id="
                                                + ids[2]))
                .hasMessageContaining("primary completion unauthorized");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM public.privacy_target WHERE id=?",
                                String.class,
                                ids[2]))
                .isEqualTo("RUNNING");
        assertThatThrownBy(() -> asProbe("DELETE FROM public.member_account WHERE id=" + ids[0]))
                .hasMessageContaining("primary deletion unauthorized");
        assertThatThrownBy(() -> asProbe("SELECT public.consume_privacy_primary(" + ids[2] + ")"))
                .hasMessageContaining("permission denied");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM public.member_account WHERE id=?",
                                Integer.class,
                                ids[0]))
                .isEqualTo(1);
        db.execute(
                "GRANT EXECUTE ON FUNCTION public.consume_privacy_primary(bigint) TO"
                        + " erasure_probe");
        assertThat(asProbeResult("SELECT public.consume_privacy_primary(" + ids[2] + ")")).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM public.member_account WHERE id=?",
                                Integer.class,
                                ids[0]))
                .isZero();
    }

    /** 역할 교체를 한 JDBC 연결 안에 한정하고 오류 후 역할을 원복한다. */
    private static void asProbe(String sql) throws SQLException {
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("SET ROLE erasure_probe");
            statement.execute(sql);
        }
    }

    /** 비소유자에게 함수 실행만 허가했을 때 실제 고정 대상이 처리되는지 확인한다. */
    private static boolean asProbeResult(String sql) throws SQLException {
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("SET ROLE erasure_probe");
            try (var result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }
}
