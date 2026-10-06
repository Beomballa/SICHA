package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.migration.EmbeddedSqlResourceProvider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** LOCAL 전용 계약을 실제 폐기형 PG에서 검사하며 합성 자료를 운영 준비 증거로 해석하지 않는다. */
class LocalMemberSchemaIT {
    private static final String IMAGE =
            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1";
    private static final String CLOCK = "'2026-01-01T00:00:00Z'::timestamptz";
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static CryptoService crypto;
    private static String password;

    /** 기존 원본과 같은 독립 이미지에서 V19까지 적용하며 키와 암호는 합성 값만 생성한다. */
    @BeforeAll
    static void open() {
        postgres = database();
        postgres.start();
        jdbc = jdbc(postgres);
        var migration = flyway(postgres, null);
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(19);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        migration.validate();
        var properties = new AuthProperties();
        properties.setCryptoKeyFile(TestKeys.create((byte) 81));
        properties.setSearchKeyFile(TestKeys.create((byte) 82));
        properties.setLimitKeyFile(TestKeys.create((byte) 83));
        crypto = new CryptoService(properties);
        password =
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
                        .encode("Synthetic-only-Local-Auth-81!");
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** 실제 V18 부모와 이력을 보존하고 증가 적용·0 재실행·무시드 구조를 독립 DB에서 검사한다. */
    @Test
    void eighteenToNineteenPreservesHistoryAndSeedsNothing() {
        try (var database = database()) {
            database.start();
            assertThat(flyway(database, "18").migrate().migrationsExecuted).isEqualTo(18);
            var db = jdbc(database);
            db.execute("INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())");
            var admins = db.queryForList("SELECT * FROM public.admin_account");
            var history =
                    db.queryForList(
                            "SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
            var latest = flyway(database, null);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            latest.validate();
            assertThat(
                            db.queryForList(
                                            "SELECT * FROM public.flyway_schema_history ORDER BY"
                                                    + " installed_rank")
                                    .subList(0, 18))
                    .isEqualTo(history);
            assertThat(db.queryForList("SELECT * FROM public.admin_account")).isEqualTo(admins);
            for (String table : columns().keySet()) {
                assertThat(db.queryForObject("SELECT count(*) FROM public." + table, Long.class))
                        .as(table)
                        .isZero();
            }
            exactShape(db);
        }
    }

    /** 신규 적용에도 회원·정책을 자동 생성하지 않고 정확한 열 타입과 무결성 구조를 만든다. */
    @Test
    void cleanNineteenHasExactShapeAndNoSeeds() {
        try (var database = database()) {
            database.start();
            var latest = flyway(database, null);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(19);
            latest.validate();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            var db = jdbc(database);
            assertThat(db.queryForObject("SELECT count(*) FROM public.admin_account", Long.class))
                    .isZero();
            for (String table : columns().keySet()) {
                assertThat(db.queryForObject("SELECT count(*) FROM public." + table, Long.class))
                        .as(table)
                        .isZero();
            }
            exactShape(db);
        }
    }

    /** 실제 회원/정책/identity/session 부모를 사용하여 잘못된 참조와 LOCAL 밖 값을 거절한다. */
    @Test
    void immediateParentsLocalHashesClocksAndNulls() throws Exception {
        var fixture = fixture();
        long other = member();
        rejected(
                "UPDATE public.member_identity SET provider='APPLE' WHERE id=" + fixture.identity(),
                "ck_mi_provider");
        rejected(
                "UPDATE public.member_identity SET realm='CLIENT' WHERE id=" + fixture.identity(),
                "ck_mi_provider");
        rejected(
                "UPDATE public.member_identity SET lookup_ver=2 WHERE id=" + fixture.identity(),
                "ck_mi_ver");
        rejected(
                "UPDATE public.member_identity SET lookup_hash='A' WHERE id=" + fixture.identity(),
                "ck_member_identity_lookup_hash");
        rejected(
                "UPDATE public.member_identity SET password_hash='   ' WHERE id="
                        + fixture.identity(),
                "ck_mi_password");
        rejected(
                "UPDATE public.member_identity SET subject_cipher=decode('00','hex') WHERE id="
                        + fixture.identity(),
                "ck_member_identity_subject_cipher");
        rejected(
                "UPDATE public.member_identity SET member_id=9223372036854775807 WHERE id="
                        + fixture.identity(),
                "fk_ms_identity");
        rejected(
                "INSERT INTO"
                    + " public.member_identity(identity_key,member_id,provider,realm,lookup_hash,subject_cipher,password_hash,active_yn,proof_at,bound_at)"
                    + " SELECT"
                    + " gen_random_uuid(),9223372036854775807,provider,realm,lookup_hash,subject_cipher,password_hash,false,proof_at,bound_at"
                    + " FROM public.member_identity WHERE id="
                        + fixture.identity(),
                "fk_mi_member");
        rejected(sessionInsert(other, fixture.identity()), "fk_ms_identity");
        rejected(sessionInsert(fixture.member(), Long.MAX_VALUE), "fk_ms_identity");
        rejected(tokenInsert(Long.MAX_VALUE, "ACCESS", 0, "ISSUED", "5 minutes"), "fk_mt_session");
        rejected(
                "INSERT INTO public.member_profile(member_id,nickname_cipher,policy_id,accepted_at)"
                        + " VALUES ("
                        + other
                        + ",decode(repeat('01',32),'hex'),9223372036854775807,"
                        + CLOCK
                        + ")",
                "fk_mp_policy");
        rejected(
                "INSERT INTO public.member_profile(member_id,nickname_cipher,policy_id,accepted_at)"
                        + " VALUES (9223372036854775807,decode(repeat('01',32),'hex'),"
                        + fixture.policy()
                        + ","
                        + CLOCK
                        + ")",
                "fk_mp_member");
        rejected(
                tokenInsert(fixture.session(), "ACCESS", 0, "ISSUED", "5 minutes 1 second"),
                "ck_mt_time");
        rejected(
                tokenInsert(fixture.session(), "REFRESH", 0, "ISSUED", "720 hours 1 second"),
                "ck_mt_time");
        rejected(tokenInsert(fixture.session(), "ACCESS", 0, "USED", "5 minutes"), "ck_mt_used");
        rejected(
                sessionInsert(other, fixture.identity()).replace("'2160 hours'", "'2159 hours'"),
                "ck_ms_clock");
        rejected(
                "UPDATE public.member_account SET state='DELETED' WHERE id=" + fixture.member(),
                "ck_member_deleted");
        rejected(
                "UPDATE public.member_account SET auth_rev=-1 WHERE id=" + fixture.member(),
                "ck_member_rev");
        for (String column :
                List.of("member_key", "state", "auth_rev", "created_at", "updated_at")) {
            rejected(
                    "UPDATE public.member_account SET "
                            + column
                            + "=NULL WHERE id="
                            + fixture.member(),
                    "null value");
        }
        long flow = flow();
        rejected(
                "INSERT INTO"
                    + " public.member_flow(flow_key,binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,created_at,expires_at)"
                    + " SELECT"
                    + " gen_random_uuid(),binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,created_at,expires_at+interval"
                    + " '1 second' FROM public.member_flow WHERE id="
                        + flow,
                "ck_mf_clock");
        rejected(
                "UPDATE public.member_flow SET purpose='RESET' WHERE id=" + flow,
                "member_flow stable");
        rejected("UPDATE public.member_flow SET attempt_count=5 WHERE id=" + flow, "ck_mf_shape");
        rejected(
                "UPDATE public.member_flow SET state='VERIFIED',code_hash=NULL WHERE id=" + flow,
                "ck_mf_shape");
        rejected("UPDATE public.member_flow SET lookup_hash=NULL WHERE id=" + flow, "ck_mf_shape");
        rejected("UPDATE public.member_flow SET proof_cipher=NULL WHERE id=" + flow, "ck_mf_shape");
        rejected(
                "UPDATE public.member_flow SET"
                    + " state='FAILED',lookup_hash=NULL,code_hash=NULL,proof_cipher=NULL,consumed_at="
                        + CLOCK
                        + " WHERE id="
                        + flow,
                "ck_mf_");
        rejected(
                "INSERT INTO"
                    + " public.member_auth_audit(event_key,request_id,member_id,action,result_code,purge_at)"
                    + " VALUES (gen_random_uuid(),gen_random_uuid(),"
                        + fixture.member()
                        + ",'LOGIN_LOCAL','DENIED',now()+interval '1 hour')",
                "ck_maa_rev");
        rejected(
                "INSERT INTO"
                    + " public.member_auth_audit(event_key,request_id,member_id,auth_rev,action,result_code,purge_at)"
                    + " VALUES"
                    + " (gen_random_uuid(),gen_random_uuid(),9223372036854775807,0,'LOGIN_LOCAL','DENIED',now()+interval"
                    + " '1 hour')",
                "fk_maa_member");
        rejected(
                "INSERT INTO"
                    + " public.member_auth_audit(event_key,request_id,auth_rev,action,result_code,purge_at)"
                    + " VALUES"
                    + " (gen_random_uuid(),gen_random_uuid(),0,'LOGIN_LOCAL','DENIED',now()+interval"
                    + " '1 hour')",
                "ck_maa_rev");
        rejected(
                "INSERT INTO"
                    + " public.member_auth_audit(event_key,request_id,action,result_code,created_at,purge_at)"
                    + " VALUES (gen_random_uuid(),gen_random_uuid(),'LOGIN_LOCAL','DENIED',"
                        + CLOCK
                        + ","
                        + CLOCK
                        + "+interval '2160 hours 1 second')",
                "ck_maa_purge");
        rejected(
                "INSERT INTO public.member_auth_limit VALUES ('LOGIN_EMAIL_15M',repeat('A',64),"
                        + CLOCK
                        + ",0,NULL,"
                        + CLOCK
                        + "+interval '1 hour')",
                "ck_member_auth_limit_bucket_hash");
        rejected(
                "INSERT INTO public.member_auth_limit VALUES ('LOGIN_EMAIL_15M',repeat('a',64),"
                        + CLOCK
                        + ",0,NULL,"
                        + CLOCK
                        + "+interval '24 hours 1 second')",
                "ck_mal_clock");
        byte[] stored =
                jdbc.queryForObject(
                        "SELECT subject_cipher FROM public.member_identity WHERE id=?",
                        byte[].class,
                        fixture.identity());
        assertThat(
                        crypto.decrypt(
                                new String(stored, StandardCharsets.UTF_8),
                                "member_identity/" + fixture.identity() + "/subject_cipher/v1"))
                .endsWith("@example.invalid");
    }

    /** 생성 내용과 동의/가족/토큰/흐름의 단방향 경계를 UPDATE-to-self와 재활성화까지 검사한다. */
    @Test
    void immutableStateMachinesRetainFirstEvidence() throws Exception {
        var f = fixture();
        rejected(
                "UPDATE public.privacy_policy SET policy_data=policy_data || '{\"extra\":1}'::jsonb"
                        + " WHERE id="
                        + f.policy(),
                "privacy_policy immutable");
        rejected(
                "UPDATE public.privacy_policy SET code='REPLACED' WHERE id=" + f.policy(),
                "privacy_policy immutable");
        jdbc.update("UPDATE public.privacy_policy SET state='ACTIVE' WHERE id=?", f.policy());
        jdbc.update("UPDATE public.privacy_policy SET state='SUSPENDED' WHERE id=?", f.policy());
        rejected(
                "UPDATE public.privacy_policy SET state='ACTIVE' WHERE id=" + f.policy(),
                "privacy_policy immutable transition");
        jdbc.update("UPDATE public.privacy_policy SET state='RETIRED' WHERE id=?", f.policy());
        rejected(
                "UPDATE public.privacy_policy SET state='DRAFT' WHERE id=" + f.policy(),
                "privacy_policy immutable transition");
        rejected(
                "DELETE FROM public.privacy_policy WHERE id=" + f.policy(),
                "privacy_policy immutable");
        rejected("TRUNCATE public.privacy_policy CASCADE", "privacy_policy immutable");
        rejected(
                "UPDATE public.member_profile SET accepted_at=accepted_at+interval '1 second' WHERE"
                        + " member_id="
                        + f.member(),
                "member_profile consent immutable");
        rejected(
                "UPDATE public.member_profile SET policy_id="
                        + policy(f.actor())
                        + " WHERE member_id="
                        + f.member(),
                "member_profile consent immutable");
        rejected(
                "UPDATE public.member_session SET identity_id=9223372036854775807 WHERE id="
                        + f.session(),
                "member_session stable");
        rejected(
                "UPDATE public.member_session SET created_at=created_at-interval '1 second' WHERE"
                        + " id="
                        + f.session(),
                "member_session stable");
        jdbc.update(
                "UPDATE public.member_session SET last_refresh_at=last_refresh_at+interval '1"
                        + " hour',idle_until=idle_until+interval '1 hour' WHERE id=?",
                f.session());
        rejected(
                "UPDATE public.member_session SET last_refresh_at=last_refresh_at-interval '1"
                        + " hour',idle_until=idle_until-interval '1 hour' WHERE id="
                        + f.session(),
                "member_session stable");
        jdbc.update(
                "UPDATE public.member_session SET revoked_at="
                        + CLOCK
                        + "+interval '2 hours',revoke_code='LOGOUT' WHERE id=?",
                f.session());
        rejected(
                "UPDATE public.member_session SET revoked_at=NULL,revoke_code=NULL WHERE id="
                        + f.session(),
                "member_session stable");
        rejected(
                "UPDATE public.member_session SET revoke_code='REFRESH_REUSED' WHERE id="
                        + f.session(),
                "member_session stable");
        long token =
                id(tokenInsert(f.session(), "REFRESH", 0, "ISSUED", "720 hours") + " RETURNING id");
        rejected(
                "UPDATE public.member_token SET expires_at=expires_at-interval '1 second' WHERE id="
                        + token,
                "member_token immutable");
        jdbc.update(
                "UPDATE public.member_token SET state='USED',used_at="
                        + CLOCK
                        + "+interval '1 minute' WHERE id=?",
                token);
        rejected(
                "UPDATE public.member_token SET state='REVOKED',used_at=NULL WHERE id=" + token,
                "member_token immutable");
        rejected(
                "UPDATE public.member_token SET state='ISSUED',used_at=NULL WHERE id=" + token,
                "member_token immutable");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM public.member_token WHERE id=?",
                                String.class,
                                token))
                .isEqualTo("USED");
        long access =
                id(tokenInsert(f.session(), "ACCESS", 0, "ISSUED", "5 minutes") + " RETURNING id");
        jdbc.update("UPDATE public.member_token SET state='REVOKED' WHERE id=?", access);
        rejected(
                "UPDATE public.member_token SET state='ISSUED' WHERE id=" + access,
                "member_token immutable");
        long flow = flow();
        jdbc.update("UPDATE public.member_flow SET attempt_count=1 WHERE id=?", flow);
        rejected(
                "UPDATE public.member_flow SET attempt_count=0 WHERE id=" + flow,
                "member_flow stable");
        jdbc.update(
                "UPDATE public.member_flow SET state='VERIFIED',verified_at="
                        + CLOCK
                        + "+interval '1 minute',code_hash=NULL WHERE id=?",
                flow);
        rejected(
                "UPDATE public.member_flow SET state='PENDING' WHERE id=" + flow,
                "member_flow stable");
        rejected(
                "UPDATE public.member_flow SET verified_at=NULL WHERE id=" + flow,
                "member_flow stable");
        jdbc.update(
                "UPDATE public.member_flow SET state='CONSUMED',consumed_at="
                        + CLOCK
                        + "+interval '2 minutes',lookup_hash=NULL,proof_cipher=NULL WHERE id=?",
                flow);
        rejected(
                "UPDATE public.member_flow SET state='VERIFIED',consumed_at=NULL WHERE id=" + flow,
                "member_flow stable");
        long failed = flow();
        jdbc.update(
                "UPDATE public.member_flow SET"
                    + " state='FAILED',attempt_count=5,lookup_hash=NULL,code_hash=NULL,proof_cipher=NULL"
                    + " WHERE id=?",
                failed);
        rejected(
                "UPDATE public.member_flow SET attempt_count=4 WHERE id=" + failed,
                "member_flow stable");
        long audit =
                id(
                        "INSERT INTO"
                            + " public.member_auth_audit(event_key,request_id,action,result_code,created_at,purge_at)"
                            + " VALUES (gen_random_uuid(),gen_random_uuid(),'LOGIN_LOCAL','DENIED',"
                                + CLOCK
                                + ","
                                + CLOCK
                                + "+interval '1 hour') RETURNING id");
        rejected(
                "UPDATE public.member_auth_audit SET result_code=result_code WHERE id=" + audit,
                "member_auth_audit append only");
        rejected(
                "DELETE FROM public.member_auth_audit WHERE id=" + audit,
                "member_auth_audit append only");
        rejected("TRUNCATE public.member_auth_audit", "member_auth_audit append only");
    }

    /** 병렬 연결의 실제 UNIQUE 대기를 관측하고 한 현재 refresh만 커밋하며 USED 근거를 남긴다. */
    @Test
    void concurrentCurrentRefreshAndRotation() throws Exception {
        var f = fixture();
        try (Connection first = postgres.createConnection("");
                var executor = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try (var statement = first.createStatement()) {
                statement.execute(tokenInsert(f.session(), "REFRESH", 0, "ISSUED", "720 hours"));
            }
            var started = new java.util.concurrent.CountDownLatch(1);
            var backend = new java.util.concurrent.atomic.AtomicInteger();
            var second =
                    executor.submit(
                            () -> {
                                try (Connection connection = postgres.createConnection("");
                                        var statement = connection.createStatement()) {
                                    try (var rs =
                                            statement.executeQuery("SELECT pg_backend_pid()")) {
                                        rs.next();
                                        backend.set(rs.getInt(1));
                                    }
                                    statement.execute("SET statement_timeout='10s'");
                                    started.countDown();
                                    try {
                                        statement.execute(
                                                tokenInsert(
                                                        f.session(),
                                                        "REFRESH",
                                                        1,
                                                        "ISSUED",
                                                        "720 hours"));
                                        return "unexpected success";
                                    } catch (SQLException failure) {
                                        return failure.getSQLState() + ":" + failure.getMessage();
                                    }
                                }
                            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !waiting) {
                waiting =
                        Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=?"
                                                + " AND wait_event_type='Lock')",
                                        Boolean.class,
                                        backend.get()));
                if (!waiting) Thread.sleep(10);
            }
            assertThat(waiting).isTrue();
            assertThat(second.isDone()).isFalse();
            first.commit();
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .startsWith("23505:")
                    .contains("uk_mt_current_refresh");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.member_token WHERE session_id=? AND"
                                        + " kind='REFRESH' AND state='ISSUED'",
                                Long.class,
                                f.session()))
                .isEqualTo(1L);
        try (Connection connection = postgres.createConnection("");
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(
                    "UPDATE public.member_token SET state='USED',used_at="
                            + CLOCK
                            + "+interval '1 minute' WHERE session_id="
                            + f.session());
            statement.execute(tokenInsert(f.session(), "REFRESH", 1, "ISSUED", "720 hours"));
            connection.commit();
        }
        assertThat(
                        jdbc.queryForList(
                                "SELECT state FROM public.member_token WHERE session_id=? ORDER BY"
                                        + " generation",
                                String.class,
                                f.session()))
                .containsExactly("USED", "ISSUED");
    }

    /** 정책 잠금용 불변 열 권한만 추가하며 등록·상태 변경과 custom GUC 우회를 거절한다. */
    @Test
    void applicationRoleCannotMutatePolicyOrAudit() throws Exception {
        var f = fixture();
        var originalPolicy =
                jdbc.queryForMap("SELECT * FROM public.privacy_policy WHERE id=?", f.policy());
        String role = "local_auth_probe_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE ROLE " + role + " NOLOGIN");
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
            jdbc.execute(
                    "GRANT SELECT ON public.privacy_policy,public.member_auth_audit TO " + role);
            jdbc.execute("GRANT UPDATE (code) ON public.privacy_policy TO " + role);
            try (Connection connection = postgres.createConnection("");
                    var statement = connection.createStatement()) {
                statement.execute("SET ROLE " + role);
                statement.execute("SET app.policy_mutation='true'");
                connection.setAutoCommit(false);
                try (var rs =
                        statement.executeQuery(
                                "SELECT id FROM public.privacy_policy WHERE id="
                                        + f.policy()
                                        + " FOR SHARE")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(f.policy());
                    assertThat(rs.next()).isFalse();
                }
                try (Connection writer = postgres.createConnection("");
                        var mutation = writer.createStatement()) {
                    mutation.execute("SET lock_timeout='250ms'");
                    assertThatThrownBy(
                                    () ->
                                            mutation.execute(
                                                    "UPDATE public.privacy_policy SET"
                                                            + " state='ACTIVE' WHERE id="
                                                            + f.policy()))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("55P03");
                }
                connection.commit();
                connection.setAutoCommit(true);
                for (String sql :
                        List.of(
                                "INSERT INTO"
                                    + " public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                                    + " SELECT"
                                    + " code,env_code,scope,state,notice_hash,policy_data,owner_id"
                                    + " FROM public.privacy_policy WHERE id="
                                        + f.policy(),
                                "UPDATE public.privacy_policy SET state='ACTIVE' WHERE id="
                                        + f.policy(),
                                "DELETE FROM public.privacy_policy WHERE id=" + f.policy(),
                                "TRUNCATE public.privacy_policy",
                                "UPDATE public.member_auth_audit SET action=action",
                                "DELETE FROM public.member_auth_audit",
                                "TRUNCATE public.member_auth_audit")) {
                    assertThatThrownBy(() -> statement.execute(sql))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("42501");
                }
                assertThatThrownBy(
                                () ->
                                        statement.execute(
                                                "UPDATE public.privacy_policy SET code='REPLACED'"
                                                        + " WHERE id="
                                                        + f.policy()))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("privacy_policy immutable transition")
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23514");
                assertThat(
                                statement.executeUpdate(
                                        "UPDATE public.privacy_policy SET code=code WHERE id="
                                                + f.policy()))
                        .isEqualTo(1);
                assertThat(
                                jdbc.queryForMap(
                                        "SELECT * FROM public.privacy_policy WHERE id=?",
                                        f.policy()))
                        .isEqualTo(originalPolicy);
                try (var rs =
                        statement.executeQuery(
                                "SELECT code FROM public.privacy_policy WHERE id=" + f.policy())) {
                    assertThat(rs.next()).isTrue();
                }
            }
        } finally {
            jdbc.execute("DROP OWNED BY " + role);
            jdbc.execute("DROP ROLE " + role);
        }
    }

    /** SQL NULL·JSON null·추가 키·LOCAL 외 값·잘못된 기간을 닫힌 등록 경계에서 거절한다. */
    @Test
    void closedPolicyDocumentAndDraftOnlyInsert() throws Exception {
        long actor =
                id(
                        "INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())"
                                + " RETURNING id");
        String document = policyDocument();
        for (String invalid :
                List.of(
                        "{}",
                        "null",
                        "[]",
                        document.replace("\"formatNo\":1", "\"formatNo\":null"),
                        document.replace("\"formatNo\":1", "\"formatNo\":\"1\""),
                        document.replace("[\"LOCAL\"]", "[\"APPLE\"]"),
                        document.replace("\"body\":\"Synthetic notice\"", "\"body\":null"),
                        document.replace("\"envCode\":\"SYNTHETIC\"", "\"envCode\":\"OTHER\""),
                        document.replace("2026-12-31T00:00:00Z", "2025-01-01T00:00:00Z"),
                        document.substring(0, document.length() - 1) + ",\"extra\":true}")) {
            assertThatThrownBy(() -> insertPolicy(actor, "DRAFT", invalid))
                    .hasMessageContaining("ck_pp_local_document");
        }
        assertThatThrownBy(() -> insertPolicy(actor, "ACTIVE", document))
                .hasMessageContaining("DRAFT insert only");
        assertThatThrownBy(() -> insertPolicy(Long.MAX_VALUE, "DRAFT", document))
                .hasMessageContaining("fk_pp_owner");
        long policy = insertPolicy(actor, "DRAFT", document);
        long other = insertPolicy(actor, "DRAFT", document);
        rejected(
                "INSERT INTO"
                    + " public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                    + " SELECT '   ',env_code,scope,state,notice_hash,policy_data,owner_id FROM"
                    + " public.privacy_policy WHERE id="
                        + policy,
                "ck_pp_code");
        rejected(
                "INSERT INTO"
                    + " public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                    + " SELECT"
                    + " 'PLAYTEST_PROBE',env_code,'PLAYTEST',state,notice_hash,policy_data,owner_id"
                    + " FROM public.privacy_policy WHERE id="
                        + policy,
                "ck_pp_scope");
        rejected(
                "INSERT INTO"
                    + " public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                    + " SELECT"
                    + " 'HASH_PROBE',env_code,scope,state,repeat('A',64),policy_data,owner_id FROM"
                    + " public.privacy_policy WHERE id="
                        + policy,
                "ck_privacy_policy_notice_hash");
        jdbc.update("UPDATE public.admin_account SET active_yn=false WHERE id=?", actor);
        rejected(
                "UPDATE public.privacy_policy SET state='ACTIVE' WHERE id=" + policy,
                "active owner required");
        jdbc.update("UPDATE public.admin_account SET active_yn=true WHERE id=?", actor);
        jdbc.update("UPDATE public.privacy_policy SET state='ACTIVE' WHERE id=?", policy);
        rejected(
                "UPDATE public.privacy_policy SET state='ACTIVE' WHERE id=" + other,
                "uk_pp_active");
        jdbc.update("UPDATE public.privacy_policy SET state='RETIRED' WHERE id=?", policy);
        jdbc.update("UPDATE public.privacy_policy SET state='ACTIVE' WHERE id=?", other);
        jdbc.update("UPDATE public.privacy_policy SET state='RETIRED' WHERE id=?", other);
    }

    /** 전체 열과 열 순서의 독립 기대값이다. */
    private static Map<String, List<String>> columns() {
        return Map.ofEntries(
                Map.entry(
                        "member_account",
                        List.of(
                                "id",
                                "member_key",
                                "state",
                                "auth_rev",
                                "created_at",
                                "updated_at",
                                "deleted_at")),
                Map.entry(
                        "privacy_policy",
                        List.of(
                                "id",
                                "code",
                                "env_code",
                                "scope",
                                "state",
                                "notice_hash",
                                "policy_data",
                                "owner_id",
                                "created_at")),
                Map.entry(
                        "member_profile",
                        List.of(
                                "member_id",
                                "nickname_cipher",
                                "policy_id",
                                "accepted_at",
                                "created_at",
                                "updated_at")),
                Map.entry(
                        "member_identity",
                        List.of(
                                "id",
                                "identity_key",
                                "member_id",
                                "provider",
                                "realm",
                                "lookup_hash",
                                "lookup_ver",
                                "subject_cipher",
                                "password_hash",
                                "active_yn",
                                "proof_at",
                                "bound_at",
                                "created_at",
                                "updated_at")),
                Map.entry(
                        "member_session",
                        List.of(
                                "id",
                                "session_key",
                                "member_id",
                                "identity_id",
                                "auth_rev",
                                "created_at",
                                "last_refresh_at",
                                "idle_until",
                                "absolute_until",
                                "revoked_at",
                                "revoke_code")),
                Map.entry(
                        "member_token",
                        List.of(
                                "id",
                                "session_id",
                                "kind",
                                "generation",
                                "token_hash",
                                "state",
                                "issued_at",
                                "expires_at",
                                "used_at")),
                Map.entry(
                        "member_flow",
                        List.of(
                                "id",
                                "flow_key",
                                "binder_hash",
                                "purpose",
                                "state",
                                "provider",
                                "lookup_hash",
                                "code_hash",
                                "proof_cipher",
                                "attempt_count",
                                "created_at",
                                "expires_at",
                                "verified_at",
                                "consumed_at")),
                Map.entry(
                        "member_auth_audit",
                        List.of(
                                "id",
                                "event_key",
                                "member_id",
                                "request_id",
                                "action",
                                "result_code",
                                "http_status",
                                "created_at",
                                "purge_at",
                                "auth_rev")),
                Map.entry(
                        "member_auth_limit",
                        List.of(
                                "scope",
                                "bucket_hash",
                                "window_at",
                                "hit_count",
                                "blocked_until",
                                "purge_at")));
    }

    /** FK 정의·즉시성·NO ACTION과 정확한 인덱스를 독립 기대값으로 비교한다. */
    private static void exactShape(JdbcTemplate db) {
        int count = 0;
        var nullable =
                Set.of(
                        "member_account.deleted_at",
                        "member_session.revoked_at",
                        "member_session.revoke_code",
                        "member_token.used_at",
                        "member_flow.lookup_hash",
                        "member_flow.code_hash",
                        "member_flow.proof_cipher",
                        "member_flow.verified_at",
                        "member_flow.consumed_at",
                        "member_auth_audit.member_id",
                        "member_auth_audit.http_status",
                        "member_auth_audit.auth_rev",
                        "member_auth_limit.blocked_until");
        for (var table : columns().entrySet()) {
            var rows =
                    db.queryForList(
                            "SELECT"
                                + " column_name,data_type,is_nullable,character_maximum_length,column_default,is_identity,identity_generation"
                                + " FROM information_schema.columns WHERE table_schema='public' AND"
                                + " table_name=? ORDER BY ordinal_position",
                            table.getKey());
            assertThat(rows.stream().map(row -> row.get("column_name")).toList())
                    .containsExactlyElementsOf(table.getValue());
            count += rows.size();
            for (var row : rows) {
                String column = (String) row.get("column_name");
                assertThat(row.get("is_nullable"))
                        .as(table.getKey() + "." + column)
                        .isEqualTo(nullable.contains(table.getKey() + "." + column) ? "YES" : "NO");
                if (column.endsWith("_hash") && !column.equals("password_hash")) {
                    assertThat(row.get("data_type")).isEqualTo("character");
                    assertThat(((Number) row.get("character_maximum_length")).intValue())
                            .isEqualTo(64);
                } else if (column.endsWith("_cipher")) {
                    assertThat(row.get("data_type")).isEqualTo("bytea");
                } else if (column.equals("id")) {
                    assertThat(row.get("is_identity")).isEqualTo("YES");
                    assertThat(row.get("identity_generation")).isEqualTo("ALWAYS");
                }
                String expectedType;
                if (column.endsWith("_hash") && !column.equals("password_hash"))
                    expectedType = "character";
                else if (column.endsWith("_cipher")) expectedType = "bytea";
                else if (column.equals("policy_data")) expectedType = "jsonb";
                else if (column.endsWith("_key") || column.equals("request_id"))
                    expectedType = "uuid";
                else if (column.endsWith("_at") || column.endsWith("_until"))
                    expectedType = "timestamp with time zone";
                else if (column.equals("active_yn")) expectedType = "boolean";
                else if (List.of("lookup_ver", "attempt_count", "http_status").contains(column))
                    expectedType = "smallint";
                else if (column.equals("hit_count")) expectedType = "integer";
                else if (column.equals("id")
                        || column.endsWith("_id")
                        || List.of("auth_rev", "generation").contains(column))
                    expectedType = "bigint";
                else expectedType = "character varying";
                assertThat(row.get("data_type"))
                        .as(table.getKey() + "." + column)
                        .isEqualTo(expectedType);
                if (expectedType.equals("character varying")) {
                    int width =
                            switch (column) {
                                case "code" -> 60;
                                case "env_code", "revoke_code", "action", "result_code" -> 40;
                                case "scope" ->
                                        table.getKey().equals("member_auth_limit") ? 40 : 24;
                                case "realm" -> 160;
                                case "password_hash" -> 512;
                                default -> 24;
                            };
                    assertThat(((Number) row.get("character_maximum_length")).intValue())
                            .isEqualTo(width);
                }
                String expectedDefault = null;
                if (List.of("created_at", "updated_at").contains(column)) expectedDefault = "now()";
                if (column.equals("active_yn")) expectedDefault = "true";
                if (column.equals("lookup_ver")) expectedDefault = "1";
                if (column.equals("attempt_count")
                        || table.getKey().equals("member_account") && column.equals("auth_rev"))
                    expectedDefault = "0";
                assertThat(row.get("column_default"))
                        .as(table.getKey() + "." + column + " default")
                        .isEqualTo(expectedDefault);
            }
        }
        assertThat(count).isEqualTo(86);
        String tables =
                "'member_account','privacy_policy','member_profile','member_identity','member_session','member_token','member_flow','member_auth_audit','member_auth_limit'";
        var foreign =
                db.queryForList(
                        "SELECT conname,pg_get_constraintdef(oid) AS"
                            + " definition,condeferrable,condeferred,confdeltype,confupdtype FROM"
                            + " pg_constraint WHERE conrelid IN (SELECT oid FROM pg_class WHERE"
                            + " relnamespace='public'::regnamespace AND relname IN ("
                                + tables
                                + ")) AND contype='f'");
        var expected =
                Map.of(
                        "fk_pp_owner",
                        "FOREIGN KEY (owner_id) REFERENCES admin_account(id)",
                        "fk_mp_member",
                        "FOREIGN KEY (member_id) REFERENCES member_account(id)",
                        "fk_mp_policy",
                        "FOREIGN KEY (policy_id) REFERENCES privacy_policy(id)",
                        "fk_mi_member",
                        "FOREIGN KEY (member_id) REFERENCES member_account(id)",
                        "fk_ms_member",
                        "FOREIGN KEY (member_id) REFERENCES member_account(id)",
                        "fk_ms_identity",
                        "FOREIGN KEY (member_id, identity_id) REFERENCES member_identity(member_id,"
                                + " id)",
                        "fk_mt_session",
                        "FOREIGN KEY (session_id) REFERENCES member_session(id)",
                        "fk_maa_member",
                        "FOREIGN KEY (member_id) REFERENCES member_account(id)");
        assertThat(foreign).hasSize(8);
        assertThat(foreign.stream().map(row -> (String) row.get("conname")).toList())
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (var row : foreign) {
            assertThat(row.get("definition")).isEqualTo(expected.get(row.get("conname")));
            assertThat(row.get("condeferrable")).isEqualTo(false);
            assertThat(row.get("condeferred")).isEqualTo(false);
            assertThat(row.get("confdeltype").toString()).isEqualTo("a");
            assertThat(row.get("confupdtype").toString()).isEqualTo("a");
        }
        var actualIndexes =
                db.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND tablename"
                                + " IN ("
                                + tables
                                + ")",
                        String.class);
        assertThat(actualIndexes)
                .containsExactlyInAnyOrder(
                        "pk_member_account",
                        "uk_member_key",
                        "pk_privacy_policy",
                        "uk_privacy_policy_code",
                        "uk_pp_active",
                        "pk_member_profile",
                        "pk_member_identity",
                        "uk_mi_key",
                        "uk_mi_member_id",
                        "uk_mi_subject",
                        "uk_mi_member_provider",
                        "pk_member_session",
                        "uk_ms_key",
                        "uk_ms_member_id",
                        "pk_member_token",
                        "uk_mt_hash",
                        "uk_mt_generation",
                        "uk_mt_current_refresh",
                        "pk_member_flow",
                        "uk_mf_key",
                        "ix_mf_expiry",
                        "pk_member_auth_audit",
                        "uk_maa_event",
                        "pk_member_auth_limit");
        Map<String, List<String>> indexColumns =
                Map.ofEntries(
                        Map.entry("pk_member_account", List.of("id")),
                        Map.entry("uk_member_key", List.of("member_key")),
                        Map.entry("pk_privacy_policy", List.of("id")),
                        Map.entry("uk_privacy_policy_code", List.of("env_code", "code")),
                        Map.entry("uk_pp_active", List.of("env_code", "scope")),
                        Map.entry("pk_member_profile", List.of("member_id")),
                        Map.entry("pk_member_identity", List.of("id")),
                        Map.entry("uk_mi_key", List.of("identity_key")),
                        Map.entry("uk_mi_member_id", List.of("member_id", "id")),
                        Map.entry("uk_mi_subject", List.of("provider", "realm", "lookup_hash")),
                        Map.entry("uk_mi_member_provider", List.of("member_id", "provider")),
                        Map.entry("pk_member_session", List.of("id")),
                        Map.entry("uk_ms_key", List.of("session_key")),
                        Map.entry("uk_ms_member_id", List.of("member_id", "id")),
                        Map.entry("pk_member_token", List.of("id")),
                        Map.entry("uk_mt_hash", List.of("token_hash")),
                        Map.entry("uk_mt_generation", List.of("session_id", "kind", "generation")),
                        Map.entry("uk_mt_current_refresh", List.of("session_id")),
                        Map.entry("pk_member_flow", List.of("id")),
                        Map.entry("uk_mf_key", List.of("flow_key")),
                        Map.entry("ix_mf_expiry", List.of("expires_at", "id")),
                        Map.entry("pk_member_auth_audit", List.of("id")),
                        Map.entry("uk_maa_event", List.of("event_key")),
                        Map.entry("pk_member_auth_limit", List.of("scope", "bucket_hash")));
        for (var index : indexColumns.entrySet()) {
            var shape =
                    db.queryForMap(
                            "SELECT"
                                + " i.indisunique,i.indnkeyatts,i.indnatts,pg_get_expr(i.indpred,i.indrelid)"
                                + " AS predicate FROM pg_index i JOIN pg_class c ON"
                                + " c.oid=i.indexrelid WHERE c.relnamespace='public'::regnamespace"
                                + " AND c.relname=?",
                            index.getKey());
            assertThat(shape.get("indisunique")).isEqualTo(!index.getKey().equals("ix_mf_expiry"));
            assertThat(((Number) shape.get("indnkeyatts")).intValue())
                    .isEqualTo(index.getValue().size());
            assertThat(((Number) shape.get("indnatts")).intValue())
                    .isEqualTo(index.getValue().size());
            assertThat(
                            db.queryForList(
                                    "SELECT pg_get_indexdef(c.oid,n,true) FROM pg_class c CROSS"
                                        + " JOIN generate_series(1,?) n WHERE"
                                        + " c.relnamespace='public'::regnamespace AND c.relname=?"
                                        + " ORDER BY n",
                                    String.class,
                                    index.getValue().size(),
                                    index.getKey()))
                    .containsExactlyElementsOf(index.getValue());
            switch (index.getKey()) {
                case "uk_pp_active" ->
                        assertThat(shape.get("predicate"))
                                .isEqualTo("((state)::text = 'ACTIVE'::text)");
                case "uk_mi_subject", "uk_mi_member_provider" ->
                        assertThat(shape.get("predicate")).isEqualTo("active_yn");
                case "uk_mt_current_refresh" ->
                        assertThat(shape.get("predicate"))
                                .isEqualTo(
                                        "(((kind)::text = 'REFRESH'::text) AND ((state)::text ="
                                                + " 'ISSUED'::text))");
                default -> assertThat(shape.get("predicate")).isNull();
            }
        }
        assertThat(
                        db.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND"
                                        + " indexname='uk_mt_current_refresh'",
                                String.class))
                .contains("UNIQUE", "(session_id)", "REFRESH", "ISSUED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM pg_proc WHERE"
                                        + " pronamespace='public'::regnamespace AND proname LIKE"
                                        + " 'guard_local_%' AND prosecdef",
                                Long.class))
                .isZero();
    }

    /** 실제 소유자와 암호화한 행만 만들고 미구현 부모 테이블을 만들지 않는다. */
    private static Fixture fixture() throws Exception {
        long actor =
                id(
                        "INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())"
                                + " RETURNING id");
        long policy = policy(actor);
        long member = member();
        byte[] nickname = cipher("합성회원", "member_profile/" + member + "/nickname_cipher/v1");
        jdbc.update(
                "INSERT INTO"
                    + " public.member_profile(member_id,nickname_cipher,policy_id,accepted_at,created_at,updated_at)"
                    + " VALUES (?,?,?,"
                        + CLOCK
                        + ","
                        + CLOCK
                        + ","
                        + CLOCK
                        + ")",
                member,
                nickname,
                policy);
        long identity = reserve("member_identity");
        String email = "synthetic-" + UUID.randomUUID() + "@example.invalid";
        jdbc.update(
                "INSERT INTO"
                    + " public.member_identity(id,identity_key,member_id,provider,realm,lookup_hash,subject_cipher,password_hash,proof_at,bound_at,created_at,updated_at)"
                    + " OVERRIDING SYSTEM VALUE VALUES (?,? ,?,'LOCAL','LOCAL',?,?,?,"
                        + CLOCK
                        + ","
                        + CLOCK
                        + ","
                        + CLOCK
                        + ","
                        + CLOCK
                        + ")",
                identity,
                UUID.randomUUID(),
                member,
                HexFormat.of().formatHex(crypto.memberEmailHash(email)),
                cipher(email, "member_identity/" + identity + "/subject_cipher/v1"),
                password);
        long session = id(sessionInsert(member, identity) + " RETURNING id");
        return new Fixture(actor, policy, member, identity, session);
    }

    private record Fixture(long actor, long policy, long member, long identity, long session) {}

    /** 합성 근거 문서 형식을 만들며 외부 증거의 실재성을 주장하지 않는다. */
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
        var retention = new TreeMap<String, Object>();
        retention.putAll(
                Map.of(
                        "flowTtlSeconds",
                        600,
                        "flowCleanupGraceSeconds",
                        3600,
                        "accessTtlSeconds",
                        300,
                        "accessCleanupGraceSeconds",
                        3600,
                        "refreshIdleSeconds",
                        2592000,
                        "sessionAbsoluteSeconds",
                        7776000,
                        "familyCleanupGraceSeconds",
                        86400,
                        "limitMaxSeconds",
                        86400,
                        "accessHistoryMaxSeconds",
                        2592000,
                        "securityAuditMaxSeconds",
                        7776000));
        retention.put("backupMaxSeconds", 3024000);
        return new ObjectMapper()
                .writeValueAsString(
                        new TreeMap<>(
                                Map.of(
                                        "formatNo",
                                        1,
                                        "notice",
                                        new TreeMap<>(
                                                Map.of(
                                                        "version",
                                                        "SYNTHETIC_1",
                                                        "body",
                                                        "Synthetic notice",
                                                        "contact",
                                                        "synthetic@example.invalid")),
                                        "validFrom",
                                        "2026-01-01T00:00:00Z",
                                        "validUntil",
                                        "2026-12-31T00:00:00Z",
                                        "authProviders",
                                        List.of("LOCAL"),
                                        "retention",
                                        retention,
                                        "evidence",
                                        evidence)));
    }

    private static long policy(long actor) throws Exception {
        return insertPolicy(actor, "DRAFT", policyDocument());
    }

    /** 실제 admin 부모와 정확한 notice commitment를 쓰는 격리 등록 fixture다. */
    private static long insertPolicy(long actor, String state, String document) throws Exception {
        String code =
                "SYNTHETIC_"
                        + UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .toUpperCase(java.util.Locale.ROOT);
        var notice =
                new ObjectMapper()
                        .readTree(
                                document.equals("null") || document.equals("[]") ? "{}" : document);
        var commitment = new TreeMap<String, Object>();
        commitment.put("formatNo", 1);
        commitment.put("scope", "MEMBER_AUTH");
        commitment.put("policyCode", code);
        commitment.put("notice", notice.get("notice"));
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(new ObjectMapper().writeValueAsBytes(commitment)));
        return jdbc.queryForObject(
                "INSERT INTO"
                    + " public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                    + " VALUES (?,'SYNTHETIC','MEMBER_AUTH',?,?,?::jsonb,?) RETURNING id",
                Long.class,
                code,
                state,
                hash,
                document,
                actor);
    }

    private static long member() {
        return id(
                "INSERT INTO public.member_account(member_key,state) VALUES"
                        + " (gen_random_uuid(),'ACTIVE') RETURNING id");
    }

    /** UTC 경과 시간 상한으로 발행 시각을 정하고 실제 부모에 세션과 토큰을 결속한다. */
    private static String sessionInsert(long member, long identity) {
        return "INSERT INTO"
                   + " public.member_session(session_key,member_id,identity_id,auth_rev,created_at,last_refresh_at,idle_until,absolute_until)"
                   + " VALUES (gen_random_uuid(),"
                + member
                + ","
                + identity
                + ",0,"
                + CLOCK
                + ","
                + CLOCK
                + ","
                + CLOCK
                + "+interval '720 hours',"
                + CLOCK
                + "+interval '2160 hours')";
    }

    private static String tokenInsert(
            long session, String kind, long generation, String state, String duration) {
        String hash =
                HexFormat.of()
                        .formatHex(crypto.tokenHash("member-" + kind, crypto.randomToken(32)));
        return "INSERT INTO"
                   + " public.member_token(session_id,kind,generation,token_hash,state,issued_at,expires_at)"
                   + " VALUES ("
                + session
                + ",'"
                + kind
                + "',"
                + generation
                + ",'"
                + hash
                + "','"
                + state
                + "',"
                + CLOCK
                + ","
                + CLOCK
                + "+interval '"
                + duration
                + "')";
    }

    /** 암호화 전에 identity를 예약하고 SIGNUP proof를 실제 AES-GCM으로 결속한다. */
    private static long flow() {
        long id = reserve("member_flow");
        UUID flowKey = UUID.randomUUID();
        String lookup =
                HexFormat.of().formatHex(crypto.memberEmailHash("synthetic@example.invalid"));
        jdbc.update(
                "INSERT INTO"
                    + " public.member_flow(id,flow_key,binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,created_at,expires_at)"
                    + " OVERRIDING SYSTEM VALUE VALUES (?,? ,?,'SIGNUP','PENDING','LOCAL',?,?,?,"
                        + CLOCK
                        + ","
                        + CLOCK
                        + "+interval '10 minutes')",
                id,
                flowKey,
                HexFormat.of().formatHex(crypto.tokenHash("binder", crypto.randomToken(32))),
                lookup,
                HexFormat.of()
                        .formatHex(
                                crypto.limitHash(
                                        "MEMBER_SIGNUP_CODE_V1", flowKey.toString() + ":01234567")),
                cipher(
                        "{\"formatNo\":1,\"email\":\"synthetic@example.invalid\",\"realm\":\"LOCAL\",\"lookupVer\":1,\"signupAllowed\":true}",
                        "member_flow/" + id + "/proof_cipher/v1"));
        return id;
    }

    private static byte[] cipher(String plaintext, String aad) {
        return crypto.encrypt(plaintext, aad).getBytes(StandardCharsets.UTF_8);
    }

    private static long reserve(String table) {
        return jdbc.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('public." + table + "','id'))", Long.class);
    }

    private static long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static void rejected(String sql, String reason) {
        assertThatThrownBy(() -> jdbc.execute(sql)).hasMessageContaining(reason);
    }

    private static PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    private static Flyway flyway(PostgreSQLContainer<?> database, String target) {
        var config =
                Flyway.configure()
                        .resourceProvider(new EmbeddedSqlResourceProvider())
                        .locations("classpath:local-member-no-sql-files")
                        .dataSource(
                                database.getJdbcUrl(),
                                database.getUsername(),
                                database.getPassword());
        if (target != null) config.target(target);
        return config.load();
    }
}
