package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.migration.EmbeddedSqlResourceProvider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 기존 합성 설치 fixture를 사용하며 각 만료 시험에는 독립 폐기 DB와 제한 실행자를 만든다. */
class MemberAuthAuditCleanupIT extends LocalMemberAuthIT {
    private JdbcTemplate sql;
    private JdbcTemplate executor;
    private DriverManagerDataSource source;
    private String role;
    private long policy;
    private long admin;

    /** 부모 fixture와 업무 행을 공유하지 않는 최신 V43 DB를 만든다. 운영 역할은 수정하지 않는다. */
    @BeforeEach
    void auditDatabase() throws Exception {
        String database = "audit_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE DATABASE " + database);
        String url =
                postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        source = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        sql = new JdbcTemplate(source);
        var flyway =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:audit-no-independent-sql")
                        .resourceProvider(new EmbeddedSqlResourceProvider())
                        .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(43);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        role = "audit_invoker_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        sql.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
        executor = new JdbcTemplate(new DriverManagerDataSource(url, role, password));
        sql.execute(
                "GRANT EXECUTE ON FUNCTION public.consume_member_auth_audit_expiry(integer) TO "
                        + role);
        admin =
                sql.queryForObject(
                        "INSERT INTO public.admin_account(account_key) VALUES (gen_random_uuid())"
                                + " RETURNING id",
                        Long.class);
        String document =
                db.queryForObject(
                        "SELECT policy_data::text FROM public.privacy_policy WHERE"
                                + " scope='MEMBER_AUTH' ORDER BY id DESC LIMIT 1",
                        String.class);
        policy =
                sql.queryForObject(
                        """
                        INSERT INTO public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)
                        VALUES ('AUDIT_SYNTHETIC','SYNTHETIC_HTTP','MEMBER_AUTH','DRAFT',repeat('a',64),?::jsonb,?) RETURNING id
                        """,
                        Long.class,
                        document,
                        admin);
        exportSchemaEvidence();
    }

    /** 실제 제한 로그인 연결과 Java 소유 거래에서 확정·권한 실패·영수증 rollback을 관찰한다. */
    @Test
    void actualOwnedWrapperCommitsOnlyWithReceiptAndDeniedEntryRollsBack() {
        var wrapper =
                new com.reasoning.common.member.auth.MemberAuthAuditCleanupService(
                        executor,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                executor.getDataSource()));
        long due = audit(null, true);
        long live = audit(null, false);
        var counts = wrapper.cleanup(1);
        assertThat(counts.audits()).isEqualTo(1);
        assertThat(counts.receipts()).isZero();
        assertThat(sql.queryForList("SELECT id FROM public.member_auth_audit WHERE id=?", due))
                .isEmpty();
        assertThat(sql.queryForList("SELECT id FROM public.member_auth_audit WHERE id=?", live))
                .hasSize(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT deleted_count FROM public.member_auth_audit_cleanup WHERE"
                                        + " operation_key=?",
                                Integer.class,
                                counts.operationKey()))
                .isEqualTo(1);
        long retained = audit(null, true);
        sql.execute(
                "REVOKE EXECUTE ON FUNCTION public.consume_member_auth_audit_expiry(integer) FROM "
                        + role);
        assertThatThrownBy(() -> wrapper.cleanup(1))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(org.postgresql.util.PSQLException.class)
                .extracting(failure -> ((org.postgresql.util.PSQLException) failure).getSQLState())
                .isEqualTo("42501");
        assertThat(sql.queryForList("SELECT id FROM public.member_auth_audit WHERE id=?", retained))
                .hasSize(1);
        assertThat(count("member_auth_audit_cleanup")).isEqualTo(1);
        sql.execute(
                "GRANT EXECUTE ON FUNCTION public.consume_member_auth_audit_expiry(integer) TO "
                        + role);
        sql.execute(
                "CREATE FUNCTION public.fail_wrapped_receipt() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$ BEGIN RAISE EXCEPTION 'synthetic receipt insert failure'; END $$");
        sql.execute(
                "CREATE TRIGGER tr_fail_wrapped_receipt BEFORE INSERT ON"
                        + " public.member_auth_audit_cleanup FOR EACH ROW EXECUTE FUNCTION"
                        + " public.fail_wrapped_receipt()");
        try {
            assertThatThrownBy(() -> wrapper.cleanup(1)).isInstanceOf(DataAccessException.class);
            assertThat(
                            sql.queryForList(
                                    "SELECT id FROM public.member_auth_audit WHERE id=?", retained))
                    .hasSize(1);
            assertThat(count("member_auth_audit_cleanup")).isEqualTo(1);
        } finally {
            sql.execute("DROP TRIGGER tr_fail_wrapped_receipt ON public.member_auth_audit_cleanup");
            sql.execute("DROP FUNCTION public.fail_wrapped_receipt()");
        }
        assertThat(wrapper.cleanup(1).audits()).isEqualTo(1);
        assertThat(count("member_auth_audit_cleanup")).isEqualTo(2);
        assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .isActualTransactionActive())
                .isFalse();
        assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .hasResource(executor.getDataSource()))
                .isFalse();
    }

    /** 저장 기한은 짧은 것까지 존중하며 NULL·ACTIVE·BLOCKED가 하나의 상한을 공유한다. */
    @Test
    void dueLiveShorterNullableGlobalCapAndMinimalReceipt() {
        long active = member("ACTIVE");
        long blocked = member("BLOCKED");
        long deleted = member("DELETED");
        long live = audit(active, false);
        long retained = audit(deleted, true);
        audit(active, true);
        audit(blocked, true);
        audit(null, true);
        var first = consume(1);
        assertThat(first).containsEntry("audits", 1).containsEntry("receipts", 0);
        UUID operation = (UUID) first.get("operation_key");
        assertThat(operation).isNotNull();
        var receipt =
                sql.queryForMap(
                        "SELECT * FROM public.member_auth_audit_cleanup WHERE operation_key=?",
                        operation);
        assertThat(receipt.keySet())
                .containsExactlyInAnyOrder(
                        "operation_key",
                        "policy_ref",
                        "deleted_count",
                        "completed_at",
                        "success_code",
                        "receipt_expires_at");
        assertThat(receipt)
                .containsEntry("deleted_count", 1)
                .containsEntry("policy_ref", "DATA-LIFECYCLE-01")
                .containsEntry("success_code", "EXPIRED_REMOVED");
        assertThat(
                        sql.queryForObject(
                                "SELECT receipt_expires_at=completed_at+interval '2160 hours' FROM"
                                        + " public.member_auth_audit_cleanup WHERE operation_key=?",
                                Boolean.class,
                                operation))
                .isTrue();
        assertThat(consume(100)).containsEntry("audits", 2);
        assertThat(consume(100)).containsEntry("audits", 0).containsEntry("operation_key", null);
        assertThat(
                        sql.queryForList(
                                "SELECT id FROM public.member_auth_audit ORDER BY id", Long.class))
                .containsExactly(live, retained);
        assertThat(count("member_auth_audit_cleanup")).isEqualTo(2);
    }

    /** SQL 입력 자체가 NULL·범위 밖을 거부하고 100건을 넘기지 않는다. */
    @Test
    void sqlLimitsAndHundredCap() {
        for (Integer limit : new Integer[] {null, -1, 0, 101, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> consume(limit))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("limit must be 1..100");
        }
        for (int index = 0; index < 101; index++)
            audit(index % 2 == 0 ? null : member("ACTIVE"), true);
        assertThat(consume(100)).containsEntry("audits", 100);
        assertThat(count("member_auth_audit")).isEqualTo(1);
        assertThat(consume(1)).containsEntry("audits", 1);
    }

    /** INSERT의 실제 DB 시각과 같은 purge_at도 포함하며 기존 감사 불변성을 유지한다. */
    @Test
    void databaseDeadlineEqualityIsIncludedAndAuditRemainsImmutable() {
        sql.update(
                """
                WITH clock AS (SELECT clock_timestamp() AS instant)
                INSERT INTO public.member_auth_audit(event_key,request_id,action,result_code,created_at,purge_at)
                SELECT gen_random_uuid(),gen_random_uuid(),'LOGIN_LOCAL','DENIED',instant-interval '1 hour',instant FROM clock
                """);
        assertThatThrownBy(
                        () ->
                                sql.execute(
                                        "UPDATE public.member_auth_audit SET"
                                                + " purge_at=purge_at+interval '1 second'"))
                .hasStackTraceContaining("append only");
        assertThatThrownBy(() -> sql.execute("TRUNCATE public.member_auth_audit"))
                .hasStackTraceContaining("append only");
        assertThat(consume(1)).containsEntry("audits", 1);
    }

    /** TEST 대상 접수 전에도 참가자의 실제 회원 접수와 공유 TEST 관계를 보존한다. */
    @Test
    void sharedTestPeerRootIsExcludedBeforeTargetDiscovery() {
        long subject = member("ACTIVE");
        long peer = member("ACTIVE");
        testSource(subject, peer);
        erasure(peer);
        audit(subject, true);
        assertThat(consume(100)).containsEntry("audits", 0);
        assertThat(count("member_auth_audit")).isEqualTo(1);
        assertThat(count("privacy_target")).isZero();
        assertThatThrownBy(() -> sql.execute("DELETE FROM public.member_auth_audit"))
                .hasStackTraceContaining("expiry deletion unauthorized");
    }

    /** 실제 비소유자 연결은 EXECUTE만 가지며 직접 mutation과 권한 없는 실행은 실패 폐쇄한다. */
    @Test
    void explicitExecuteOnlyAndActualGuardDenial() throws Exception {
        long due = audit(null, true);
        assertThat(
                        sql.queryForObject(
                                """
                                SELECT NOT r.rolsuper AND NOT r.rolcreaterole AND NOT EXISTS
                                  (SELECT 1 FROM pg_auth_members m WHERE m.member=r.oid)
                                FROM pg_roles r WHERE r.rolname=?
                                """,
                                Boolean.class,
                                role))
                .isTrue();
        for (String table : List.of("member_auth_audit", "member_auth_audit_cleanup")) {
            for (String privilege : List.of("DELETE", "UPDATE", "TRUNCATE", "INSERT")) {
                assertThat(
                                sql.queryForObject(
                                        "SELECT has_table_privilege(?,?::regclass,?)",
                                        Boolean.class,
                                        role,
                                        "public." + table,
                                        privilege))
                        .isFalse();
            }
        }
        for (String mutation :
                List.of(
                        "DELETE FROM public.member_auth_audit",
                        "UPDATE public.member_auth_audit SET purge_at=clock_timestamp()",
                        "TRUNCATE public.member_auth_audit",
                        "DELETE FROM public.member_auth_audit_cleanup",
                        "TRUNCATE public.member_auth_audit_cleanup")) {
            assertThatThrownBy(() -> executor.execute(mutation))
                    .isInstanceOf(DataAccessException.class);
        }
        sql.execute(
                "REVOKE EXECUTE ON FUNCTION public.consume_member_auth_audit_expiry(integer) FROM "
                        + role);
        assertThatThrownBy(() -> consume(1)).hasStackTraceContaining("permission denied");
        sql.execute(
                "GRANT EXECUTE ON FUNCTION public.consume_member_auth_audit_expiry(integer) TO "
                        + role);
        // 별도 연결에서만 직접 DELETE를 부여해 실제 행 가드도 검사한다. 이 역할은 정상 executor와 다르다.
        String probe = "audit_probe_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE ROLE " + probe + " NOLOGIN");
        sql.execute("GRANT SELECT,DELETE,UPDATE ON public.member_auth_audit TO " + probe);
        sql.execute("GRANT SELECT ON public.privacy_target,public.privacy_erase TO " + probe);
        sql.execute(
                "GRANT EXECUTE ON FUNCTION"
                    + " public.privacy_primary_delete_allowed(bigint,bigint),public.member_auth_audit_expiry_eligible(bigint)"
                    + " TO "
                        + probe);
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SET ROLE " + probe);
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "DELETE FROM public.member_auth_audit WHERE id=" + due))
                    .hasMessageContaining("expiry deletion unauthorized");
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "UPDATE public.member_auth_audit SET"
                                                    + " result_code='DENIED' WHERE id="
                                                    + due))
                    .hasMessageContaining("append only");
        }
        assertThat(consume(1)).containsEntry("audits", 1);
        assertThat(
                        sql.queryForObject(
                                "SELECT"
                                    + " has_function_privilege(?,'public.consume_privacy_primary(bigint)','EXECUTE')",
                                Boolean.class,
                                role))
                .isFalse();
    }

    /** 부모와 감사 각각의 다른 거래 잠금을 건너뛰고 해제 후 재시도한다. */
    @Test
    void memberAndAuditLocksSkipWithoutLaterLocks() throws Exception {
        long member = member("ACTIVE");
        long bound = audit(member, true);
        long available = audit(member("ACTIVE"), true);
        long unbound = audit(null, true);
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement
                    .executeQuery(
                            "SELECT id FROM public.member_account WHERE id="
                                    + member
                                    + " FOR UPDATE")
                    .close();
            assertThat(consume(1)).containsEntry("audits", 1);
            assertThat(
                            sql.queryForObject(
                                    "SELECT count(*) FROM public.member_auth_audit WHERE id=?",
                                    Integer.class,
                                    available))
                    .isZero();
            assertThat(consume(100)).containsEntry("audits", 1);
            assertThat(sql.queryForObject("SELECT id FROM public.member_auth_audit", Long.class))
                    .isEqualTo(bound);
            connection.rollback();
            statement
                    .executeQuery(
                            "SELECT id FROM public.member_auth_audit WHERE id="
                                    + bound
                                    + " FOR UPDATE")
                    .close();
            assertThat(consume(100)).containsEntry("audits", 0);
            connection.rollback();
        }
        assertThat(consume(100)).containsEntry("audits", 1);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.member_auth_audit WHERE id=?",
                                Integer.class,
                                unbound))
                .isZero();
    }

    /** 실제 탈퇴 상태 변경 거래가 부모를 선점하면 건너뛰며 커밋 후에도 적격성을 새로 검사한다. */
    @Test
    void withdrawalWinsParentLockAndRemainsExcludedAfterCommit() throws Exception {
        long member = member("ACTIVE");
        audit(member, true);
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(
                    "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                            + " WHERE id="
                            + member);
            assertThat(consume(100)).containsEntry("audits", 0);
            connection.commit();
        }
        assertThat(consume(100)).containsEntry("audits", 0).containsEntry("operation_key", null);
        assertThat(count("member_auth_audit")).isEqualTo(1);
        assertThat(count("member_auth_audit_cleanup")).isZero();
    }

    /** 실패 주입은 실제 DELETE·영수증 INSERT를 실패시켜 앞선 영수증 정리까지 롤백한다. */
    @Test
    void deleteAndReceiptFailureRollBackWholeUnit() {
        audit(null, true);
        expiredReceipts(1);
        sql.execute(
                "CREATE FUNCTION public.audit_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'synthetic expiry failure'; END $$");
        for (String table : List.of("member_auth_audit", "member_auth_audit_cleanup")) {
            String event = table.equals("member_auth_audit") ? "DELETE" : "INSERT";
            sql.execute(
                    "CREATE TRIGGER zz_audit_test_failure BEFORE "
                            + event
                            + " ON public."
                            + table
                            + " FOR EACH ROW EXECUTE FUNCTION public.audit_test_failure()");
            assertThatThrownBy(() -> consume(100))
                    .hasStackTraceContaining("synthetic expiry failure");
            assertThat(count("member_auth_audit")).isEqualTo(1);
            assertThat(count("member_auth_audit_cleanup")).isEqualTo(1);
            sql.execute("DROP TRIGGER zz_audit_test_failure ON public." + table);
        }
        assertThat(consume(100)).containsEntry("audits", 1).containsEntry("receipts", 1);
        assertThat(count("member_auth_audit_cleanup")).isEqualTo(1);
    }

    /** 영수증 보관 시계는 불변이고 만료 정리에는 독립 1..100 상한과 비재귀 계약을 적용한다. */
    @Test
    void receiptExpiryBoundedImmutableAndNoRecursiveReceipt() {
        expiredReceipts(101);
        assertThat(consume(100))
                .containsEntry("audits", 0)
                .containsEntry("receipts", 100)
                .containsEntry("operation_key", null);
        assertThat(count("member_auth_audit_cleanup")).isEqualTo(1);
        assertThat(consume(1)).containsEntry("receipts", 1).containsEntry("operation_key", null);
        audit(null, true);
        var operation = consume(1).get("operation_key");
        assertThatThrownBy(
                        () ->
                                sql.update(
                                        "UPDATE public.member_auth_audit_cleanup SET"
                                            + " receipt_expires_at=receipt_expires_at+interval '1"
                                            + " day'"))
                .hasStackTraceContaining("mutation unauthorized");
        assertThatThrownBy(() -> sql.execute("TRUNCATE public.member_auth_audit_cleanup"))
                .hasStackTraceContaining("mutation unauthorized");
        assertThatThrownBy(
                        () ->
                                sql.update(
                                        "DELETE FROM public.member_auth_audit_cleanup WHERE"
                                                + " operation_key=?",
                                        operation))
                .hasStackTraceContaining("not expired");
        assertThatThrownBy(
                        () ->
                                sql.update(
                                        """
                                        INSERT INTO public.member_auth_audit_cleanup VALUES
                                        (gen_random_uuid(),'DATA-LIFECYCLE-01',1,clock_timestamp(),'EXPIRED_REMOVED',clock_timestamp()+interval '91 days')
                                        """))
                .hasStackTraceContaining("ck_maac_expiry");
    }

    /** 호출자의 검색 경로와 임시 동명 표는 전체 수식된 실제 테이블과 가드를 대체하지 못한다. */
    @Test
    void hostileSearchPathAndTempObjectsCannotReplaceAuthority() throws Exception {
        audit(null, true);
        try (Connection connection = executor.getDataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TEMP TABLE member_auth_audit(id bigint,purge_at timestamptz)");
            statement.execute("INSERT INTO member_auth_audit VALUES (999,clock_timestamp())");
            statement.execute("CREATE TEMP TABLE member_auth_audit_cleanup(operation_key uuid)");
            statement.execute("SET search_path=pg_temp,public,pg_catalog");
            try (var result =
                    statement.executeQuery(
                            "SELECT * FROM public.consume_member_auth_audit_expiry(100)")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("audits")).isEqualTo(1);
            }
            try (var result =
                    statement.executeQuery("SELECT count(*) FROM pg_temp.member_auth_audit")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(count("member_auth_audit")).isZero();
        assertThat(count("member_auth_audit_cleanup")).isEqualTo(1);
    }

    /** 모든 상태의 회원·공유 TEST·직접 game/member/room 참조가 만료 discovery와 guard에서 제외된다. */
    @Test
    void allStateErasureReferencesAndSharedSourcesStayOwnedByPrimary() throws Exception {
        long direct = member("ACTIVE");
        long targetMember = member("ACTIVE");
        long shared = member("ACTIVE");
        long sharedOwner = member("ACTIVE");
        long game = member("ACTIVE");
        long membership = member("ACTIVE");
        long root = erasure(direct);
        target(erasure(targetMember), "member_id", targetMember);
        long test = testSource(shared, sharedOwner);
        target(erasure(sharedOwner), "test_id", test);
        long room = gameSource(game, membership);
        target(erasure(game), "room_id", room);
        long gameMember =
                sql.queryForObject(
                        "SELECT id FROM public.game_member WHERE room_id=? AND member_id=?",
                        Long.class,
                        room,
                        membership);
        target(erasure(membership), "game_member_id", gameMember);
        for (long member : List.of(direct, targetMember, shared, sharedOwner, game, membership))
            audit(member, true);
        for (String state : List.of("PENDING", "RUNNING", "RETRY", "FAILED")) {
            sql.update(
                    "UPDATE public.privacy_target SET state=?,lease_until=CASE WHEN ?='RUNNING'"
                            + " THEN clock_timestamp()+interval '1 hour' ELSE NULL END",
                    state,
                    state);
            assertThat(
                            sql.queryForObject(
                                    "SELECT public.member_auth_audit_expiry_eligible(?)",
                                    Boolean.class,
                                    shared))
                    .isFalse();
            assertThat(consume(100)).containsEntry("audits", 0);
        }
        assertThat(
                        sql.queryForObject(
                                "SELECT member_id FROM public.privacy_erase WHERE id=?",
                                Long.class,
                                root))
                .isEqualTo(direct);
        assertThat(count("member_auth_audit")).isEqualTo(6);
        for (long member : List.of(direct, targetMember, shared, sharedOwner, game, membership)) {
            assertThatThrownBy(
                            () ->
                                    sql.update(
                                            "DELETE FROM public.member_auth_audit WHERE"
                                                    + " member_id=?",
                                            member))
                    .hasStackTraceContaining("expiry deletion unauthorized");
        }
    }

    /** 직접 접수나 TEST 관계가 없는 실제 PUBLIC 동료는 다른 참가자의 ROOM 대상으로만 보호된다. */
    @Test
    void publicRoomTargetAlonePreservesPeerAuditWithoutPeerErasureRoot() throws Exception {
        long owner = member("ACTIVE");
        long peer = member("ACTIVE");
        sourceParents();
        long room = gameSource(owner, peer);
        long root = erasure(owner);
        long roomTarget = target(root, "room_id", room);
        long peerAudit = audit(peer, true);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.privacy_erase WHERE member_id=?",
                                Integer.class,
                                peer))
                .isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.privacy_target WHERE member_id=? OR"
                                    + " game_member_id IN (SELECT id FROM public.game_member WHERE"
                                    + " member_id=?)",
                                Integer.class,
                                peer,
                                peer))
                .isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.test_member WHERE member_id=?",
                                Integer.class,
                                peer))
                .isZero();
        assertThat(
                        sql.queryForMap(
                                "SELECT source_kind,room_id,member_id,game_member_id FROM"
                                        + " public.privacy_target WHERE id=?",
                                roomTarget))
                .containsEntry("source_kind", "ROOM")
                .containsEntry("room_id", room)
                .containsEntry("member_id", null)
                .containsEntry("game_member_id", null);
        for (String state : List.of("PENDING", "RUNNING", "RETRY", "FAILED")) {
            sql.update(
                    "UPDATE public.privacy_target SET state=?,lease_until=CASE WHEN ?='RUNNING'"
                            + " THEN clock_timestamp()+interval '1 hour' ELSE NULL END WHERE id=?",
                    state,
                    state,
                    roomTarget);
            assertThat(
                            sql.queryForObject(
                                    "SELECT public.member_auth_audit_expiry_eligible(?)",
                                    Boolean.class,
                                    peer))
                    .isFalse();
            assertThat(consume(100))
                    .containsEntry("audits", 0)
                    .containsEntry("operation_key", null);
            assertThat(
                            sql.queryForObject(
                                    "SELECT count(*) FROM public.member_auth_audit WHERE id=?",
                                    Integer.class,
                                    peerAudit))
                    .isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    sql.update(
                                            "DELETE FROM public.member_auth_audit WHERE id=?",
                                            peerAudit))
                    .hasStackTraceContaining("expiry deletion unauthorized");
        }
        assertThat(count("member_auth_audit_cleanup")).isZero();
    }

    /** 실제 봉인·차단·임대된 PRIMARY 소비자는 만료 여부와 관계없이 기존 감사 파기를 계속 수행한다. */
    @Test
    void genuinePrimaryDeletesExpiredAndLiveAuditsWithoutExpiryReceipt() {
        long member = member("ACTIVE");
        audit(member, true);
        audit(member, false);
        long root = erasure(member);
        long target = target(root, "member_id", member);
        sql.update(
                "INSERT INTO public.privacy_target(erase_id,target_key,kind,source_kind) VALUES"
                    + " (?,gen_random_uuid(),'BACKUP','NONE'),(?,gen_random_uuid(),'RESTORE_GUARD','NONE')",
                root,
                root);
        sql.update(
                "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                        + " WHERE id=?",
                member);
        sql.update(
                "UPDATE public.privacy_erase SET"
                    + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=3,state='PROCESSING'"
                    + " WHERE id=?",
                root);
        sql.update(
                "UPDATE public.privacy_target SET"
                    + " state='RUNNING',lease_until=clock_timestamp()+interval '1 hour' WHERE id=?",
                target);
        assertThat(
                        sql.queryForObject(
                                "SELECT public.consume_privacy_primary(?)", Boolean.class, target))
                .isTrue();
        assertThat(count("member_auth_audit")).isZero();
        assertThat(count("member_auth_audit_cleanup")).isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT state FROM public.privacy_erase WHERE id=?",
                                String.class,
                                root))
                .isEqualTo("WAITING");
    }

    /** 기존 DB 시계로 합성 행을 직접 INSERT하며 UPDATE나 트리거 해제 없이 짧은 기한을 만든다. */
    private long audit(Long member, boolean due) {
        return sql.queryForObject(
                """
                WITH clock AS (SELECT clock_timestamp() AS instant)
                INSERT INTO public.member_auth_audit(event_key,member_id,request_id,action,result_code,created_at,purge_at,auth_rev)
                SELECT gen_random_uuid(),?,gen_random_uuid(),'LOGIN_LOCAL','DENIED',instant-interval '2 hours',
                  CASE WHEN ? THEN instant-interval '1 second' ELSE instant+interval '1 hour' END,
                  CASE WHEN ?::bigint IS NULL THEN NULL ELSE 0 END FROM clock RETURNING id
                """,
                Long.class,
                member,
                due,
                member);
    }

    /** ACTIVE·BLOCKED·DELETED의 실제 상태만 만든다. */
    private long member(String state) {
        return sql.queryForObject(
                "INSERT INTO public.member_account(member_key,state,deleted_at) VALUES"
                        + " (gen_random_uuid(),?,CASE WHEN ?='DELETED' THEN clock_timestamp() END)"
                        + " RETURNING id",
                Long.class,
                state,
                state);
    }

    /** 입력 상한을 SQL에 그대로 전달하며 실제 반환 열을 읽는다. */
    private Map<String, Object> consume(Integer limit) {
        return executor.queryForMap(
                "SELECT * FROM public.consume_member_auth_audit_expiry(?::integer)", limit);
    }

    private int count(String table) {
        return sql.queryForObject("SELECT count(*) FROM public." + table, Integer.class);
    }

    /** 과거에 완료된 최소 합성 영수증만 INSERT하며 기한을 UPDATE하지 않는다. */
    private void expiredReceipts(int count) {
        sql.update(
                """
                INSERT INTO public.member_auth_audit_cleanup
                SELECT gen_random_uuid(),'DATA-LIFECYCLE-01',1,clock_timestamp()-interval '91 days',
                  'EXPIRED_REMOVED',clock_timestamp()-interval '2 days' FROM generate_series(1,?)
                """,
                count);
    }

    /** 실제 프로필과 같은 정책에 결속된 미봉인 합성 접수를 만든다. */
    private long erasure(long member) {
        sql.update(
                "INSERT INTO public.member_profile(member_id,nickname_cipher,policy_id,accepted_at)"
                        + " VALUES (?,decode(repeat('03',32),'hex'),?,clock_timestamp())",
                member,
                policy);
        return sql.queryForObject(
                """
                INSERT INTO public.privacy_erase(erase_key,member_id,policy_id,reason_code,locator_cipher,receipt_hash,receipt_until,backup_until)
                VALUES (gen_random_uuid(),?,?,'ACCOUNT_REQUEST',decode(repeat('01',32),'hex'),repeat('a',64),clock_timestamp()+interval '89 days',clock_timestamp()+interval '34 days') RETURNING id
                """,
                Long.class,
                member,
                policy);
    }

    /** 고정된 열 이름만 받아 접수의 실제 소유자 참조를 만든다. */
    private long target(long root, String column, long id) {
        String kind =
                switch (column) {
                    case "member_id" -> "MEMBER";
                    case "test_id" -> "TEST";
                    case "room_id" -> "ROOM";
                    case "game_member_id" -> "MEMBERSHIP";
                    default -> throw new IllegalArgumentException("합성 출처 열 오류");
                };
        return sql.queryForObject(
                "INSERT INTO public.privacy_target(erase_id,target_key,kind,source_kind,"
                        + column
                        + ",locator_cipher) VALUES"
                        + " (?,gen_random_uuid(),'PRIMARY',?,?,decode(repeat('02',32),'hex'))"
                        + " RETURNING id",
                Long.class,
                root,
                kind,
                id);
    }

    /** 고정 버전·사본·런타임의 실제 FK 부모를 가진 합성 출처를 만든다. */
    private long[] sourceParents() {
        long story =
                sql.queryForObject(
                        "INSERT INTO public.story(code,owner_id) VALUES ('AUDIT_SOURCE',?)"
                                + " RETURNING id",
                        Long.class,
                        admin);
        long version =
                sql.queryForObject(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 감사 관계','AUDIT_SOURCE',?,?) RETURNING id",
                        Long.class,
                        story,
                        admin,
                        admin);
        sql.update(
                "INSERT INTO public.story_role(version_id,code,name) VALUES"
                        + " (?,'R1','합성1'),(?,'R2','합성2')",
                version,
                version);
        sql.update(
                "INSERT INTO public.story_pair(version_id,role_a,role_b) VALUES (?,'R1','R2')",
                version);
        long snapshot =
                sql.queryForObject(
                        "INSERT INTO"
                            + " public.review_snapshot(version_id,edit_rev,payload,request_key,created_by)"
                            + " VALUES (?,0,'{}',gen_random_uuid(),?) RETURNING id",
                        Long.class,
                        version,
                        admin);
        long runtime =
                sql.queryForObject(
                        "INSERT INTO public.grade_runtime(code,config_hash,config_data,state)"
                            + " VALUES ('AUDIT_SOURCE',repeat('a',64),'{}','AVAILABLE') RETURNING"
                            + " id",
                        Long.class);
        return new long[] {version, snapshot, runtime};
    }

    /** 실제 두 참가자의 공유 TEST FK 관계를 만든다. */
    private long testSource(long first, long second) {
        long[] parents = sourceParents();
        long test =
                sql.queryForObject(
                        """
                        WITH clock AS (SELECT clock_timestamp() AS instant)
                        INSERT INTO public.play_test(test_key,version_id,snapshot_id,runtime_id,config_hash,runtime_epoch,role_a,role_b,mode,invite_until,created_by,created_at,updated_at)
                        SELECT gen_random_uuid(),?,?,?,repeat('a',64),0,'R1','R2','FUNCTIONAL',instant+interval '7 days',?,instant,instant FROM clock RETURNING id
                        """,
                        Long.class,
                        parents[0],
                        parents[1],
                        parents[2],
                        admin);
        sql.update(
                "INSERT INTO public.test_member(test_id,member_id,slot) VALUES (?,?,1),(?,?,2)",
                test,
                first,
                test,
                second);
        return test;
    }

    /** 실제 PUBLIC 부모와 배정·미배정 참가 참조를 만든다. 수집·공개 승인의 증거는 아니다. */
    private long gameSource(long first, long second) throws Exception {
        String playtestCode = playtestPolicy();
        var document =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        JSON.readTree(
                                db.queryForObject(
                                        "SELECT policy_data::text FROM privacy_policy WHERE code=?",
                                        String.class,
                                        playtestCode));
        document.set(
                "retention",
                JSON.valueToTree(
                        Map.of(
                                "inviteSeconds",
                                1800,
                                "lobbySeconds",
                                1800,
                                "raw",
                                "ACCOUNT_ACTIVE",
                                "backupMaxSeconds",
                                3024000)));
        document.path("evidence")
                .elements()
                .forEachRemaining(
                        e ->
                                ((com.fasterxml.jackson.databind.node.ObjectNode) e)
                                        .put("scope", "FRIENDLY"));
        long friendly =
                sql.queryForObject(
                        "INSERT INTO"
                            + " public.privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                            + " VALUES"
                            + " ('AUDIT_FRIENDLY','SYNTHETIC_HTTP','FRIENDLY','DRAFT',repeat('a',64),?::jsonb,?)"
                            + " RETURNING id",
                        Long.class,
                        JSON.writeValueAsString(document),
                        admin);
        var parents =
                sql.queryForMap(
                        "SELECT v.id AS version,v.story_id AS story,s.id AS snapshot,r.id AS"
                            + " runtime FROM public.story_version v JOIN public.review_snapshot s"
                            + " ON s.version_id=v.id CROSS JOIN public.grade_runtime r LIMIT 1");
        long room =
                sql.queryForObject(
                        """
                        WITH clock AS (SELECT clock_timestamp() AS instant)
                        INSERT INTO public.game_room(room_key,purpose,mode,story_id,version_id,snapshot_id,runtime_id,
                          runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,
                          max_attempts,max_hints,created_at,lobby_until)
                        SELECT gen_random_uuid(),'PUBLIC','COOP',?,?,?,?,0,repeat('a',64),repeat('b',64),?,0,
                          'WAITING',1,600,5,3,instant,instant+interval '30 minutes' FROM clock RETURNING id
                        """,
                        Long.class,
                        parents.get("story"),
                        parents.get("version"),
                        parents.get("snapshot"),
                        parents.get("runtime"),
                        friendly);
        int slot = 0;
        for (long member : List.of(first, second)) {
            long identity =
                    sql.queryForObject(
                            """
                            INSERT INTO public.member_identity(identity_key,member_id,provider,realm,lookup_hash,subject_cipher,password_hash,proof_at,bound_at)
                            VALUES (gen_random_uuid(),?,'LOCAL','LOCAL',md5(gen_random_uuid()::text)||md5(gen_random_uuid()::text),decode(repeat('00',32),'hex'),'synthetic',clock_timestamp(),clock_timestamp()) RETURNING id
                            """,
                            Long.class,
                            member);
            sql.update(
                    "INSERT INTO"
                        + " public.game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at,version_id,role_code)"
                        + " VALUES (?,?,?,?,?,clock_timestamp(),?,?)",
                    room,
                    member,
                    identity,
                    ++slot,
                    friendly,
                    member == first ? parents.get("version") : null,
                    member == first ? "R1" : null);
        }
        return room;
    }

    /** 명시 환경변수가 있을 때만 구조를 내보낸다. 업무 행·역할·자격·OID는 출력하지 않는다. */
    private void exportSchemaEvidence() throws Exception {
        String destination = System.getenv("AUDIT_RETENTION_SCHEMA_EVIDENCE");
        if (destination == null || destination.isBlank()) return;
        var output = JSON.createObjectNode().put("migrationVersion", 43);
        output.put(
                "totalForeignKeys",
                sql.queryForObject(
                        "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON"
                            + " n.oid=c.connamespace WHERE n.nspname='public' AND c.contype='f'",
                        Integer.class));
        var tables = output.putObject("tables");
        for (String table : List.of("member_auth_audit", "member_auth_audit_cleanup")) {
            var target = tables.putObject(table);
            target.set(
                    "columns",
                    JSON.valueToTree(
                            sql.queryForList(
                                    """
                                    SELECT a.attname AS name,format_type(a.atttypid,a.atttypmod) AS type,
                                      NOT a.attnotnull AS nullable,pg_get_expr(d.adbin,d.adrelid) AS default,a.attidentity AS identity
                                    FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                                    WHERE a.attrelid=?::regclass AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum
                                    """,
                                    "public." + table)));
            target.set(
                    "constraints",
                    JSON.valueToTree(
                            sql.queryForList(
                                    "SELECT conname AS name,contype AS"
                                        + " kind,pg_get_constraintdef(oid,true) AS definition FROM"
                                        + " pg_constraint WHERE conrelid=?::regclass ORDER BY"
                                        + " conname",
                                    "public." + table)));
            target.set(
                    "indexes",
                    JSON.valueToTree(
                            sql.queryForList(
                                    """
                                    SELECT c.relname AS name,pg_get_indexdef(i.indexrelid) AS definition,i.indisunique AS unique,
                                      i.indisprimary AS primary,pg_get_expr(i.indpred,i.indrelid) AS predicate
                                    FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE i.indrelid=?::regclass ORDER BY c.relname
                                    """,
                                    "public." + table)));
        }
        output.set(
                "functions",
                JSON.valueToTree(
                        sql.queryForList(
                                """
                                SELECT p.proname AS name,pg_get_functiondef(p.oid) AS definition FROM pg_proc p
                                JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname IN
                                ('consume_member_auth_audit_expiry','member_auth_audit_expiry_eligible',
                                 'guard_member_auth_audit_expiry_delete','guard_member_auth_audit_cleanup') ORDER BY p.proname
                                """)));
        Path path = Path.of(destination);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output),
                StandardCharsets.UTF_8);
    }
}
