package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.member.auth.MemberAccessCleanupService;
import com.reasoning.common.migration.EmbeddedSqlResourceProvider;
import com.reasoning.common.migration.OriginalSqlCatalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 합성 설치와 별도 폐기 PostgreSQL에서 접근 만료 권한·원자성·출처 우선권을 검사한다. */
class MemberAccessCleanupIT extends LocalMemberAuthIT {
    private JdbcTemplate sql;
    private JdbcTemplate executor;
    private DriverManagerDataSource source;
    private String role;
    private long policy;
    private long admin;

    /** 매 시험에 새 DB와 EXECUTE 전용 실제 로그인 연결을 만들며 일반·운영 DB는 사용하지 않는다. */
    @BeforeEach
    void accessDatabase() throws Exception {
        source = database();
        sql = new JdbcTemplate(source);
        var migration = flyway(source, "44");
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(44);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        migration.validate();
        role = "access_invoker_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        sql.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
        executor = new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), role, password));
        sql.execute(
                "GRANT EXECUTE ON FUNCTION public.consume_member_access_expiry(integer) TO "
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
                        VALUES ('ACCESS_SYNTHETIC','SYNTHETIC_HTTP','MEMBER_AUTH','DRAFT',repeat('a',64),?::jsonb,?) RETURNING id
                        """,
                        Long.class,
                        document,
                        admin);
        exportSchemaEvidence();
    }

    /** 실제 제한 연결의 Java 소유 거래가 영수증과 함께 확정되며 EXECUTE 회수 뒤에는 우회하지 않는다. */
    @Test
    void wrapperUsesRestrictedConnectionAndReturnsCommittedReceipt() {
        var wrapper =
                new MemberAccessCleanupService(
                        executor, new DataSourceTransactionManager(executor.getDataSource()));
        long owner = member("ACTIVE");
        long due = access(owner, true);
        long live = access(owner, false);
        var counts = wrapper.cleanup(1);
        assertThat(counts.accesses()).isEqualTo(1);
        assertThat(counts.receipts()).isZero();
        assertThat(exists(due)).isFalse();
        assertThat(exists(live)).isTrue();
        assertThat(
                        sql.queryForObject(
                                "SELECT deleted_count FROM public.member_access_cleanup WHERE"
                                        + " operation_key=?",
                                Integer.class,
                                counts.operationKey()))
                .isEqualTo(1);
        long retained = access(owner, true);
        sql.execute(
                "REVOKE EXECUTE ON FUNCTION public.consume_member_access_expiry(integer) FROM "
                        + role);
        assertThatThrownBy(() -> wrapper.cleanup(1))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThat(exists(retained)).isTrue();
        assertThat(count("member_access_cleanup")).isEqualTo(1);
        assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .isActualTransactionActive())
                .isFalse();
        assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .hasResource(executor.getDataSource()))
                .isFalse();
    }

    /** V43의 실제 PRIMARY EXECUTE·grant option과 역사적 이력·기존 시각은 전진 설치에서도 보존한다. */
    @Test
    void upgradeFrom43PreservesHistoryRowsAndPrimaryAcl() {
        var previous = database();
        var old = new JdbcTemplate(previous);
        var v43 = flyway(previous, "43");
        assertThat(v43.migrate().migrationsExecuted).isEqualTo(43);
        List<Map<String, Object>> history =
                old.queryForList(
                        "SELECT version,script,checksum FROM public.flyway_schema_history WHERE"
                                + " success ORDER BY installed_rank");
        old.execute("CREATE ROLE upgrade_" + role + " NOLOGIN");
        old.execute(
                "GRANT EXECUTE ON FUNCTION public.consume_privacy_primary(bigint) TO upgrade_"
                        + role
                        + " WITH GRANT OPTION");
        Object acl =
                old.queryForObject(
                        "SELECT proacl::text FROM pg_proc WHERE"
                                + " oid='public.consume_privacy_primary(bigint)'::regprocedure",
                        String.class);
        UUID actor = UUID.randomUUID();
        old.update(
                "INSERT INTO public.member_account(member_key,state) VALUES (?,'ACTIVE')", actor);
        old.update(
                """
                INSERT INTO public.access_history(kind,request_id,actor_kind,actor_key,route,method,started_at,created_at)
                VALUES ('SERVER',gen_random_uuid(),'MEMBER',?,'/synthetic','GET',clock_timestamp(),clock_timestamp()-interval '721 hours')
                """,
                actor);
        var rows = old.queryForList("SELECT * FROM public.access_history");
        var current = flyway(previous, "44");
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.migrate().migrationsExecuted).isZero();
        current.validate();
        assertThat(old.queryForList("SELECT * FROM public.access_history")).isEqualTo(rows);
        assertThat(
                        old.queryForList(
                                "SELECT version,script,checksum FROM public.flyway_schema_history"
                                        + " WHERE version::integer<=43 ORDER BY installed_rank"))
                .isEqualTo(history);
        assertThat(
                        old.queryForObject(
                                "SELECT proacl::text FROM pg_proc WHERE"
                                    + " oid='public.consume_privacy_primary(bigint)'::regprocedure",
                                String.class))
                .isEqualTo(acl);
        assertThat(
                        old.queryForObject(
                                "SELECT"
                                    + " has_function_privilege(?,'public.consume_privacy_primary(bigint)','EXECUTE"
                                    + " WITH GRANT OPTION')",
                                Boolean.class,
                                "upgrade_" + role))
                .isTrue();
        assertThat(old.queryForMap("SELECT * FROM public.consume_member_access_expiry(100)"))
                .containsEntry("accesses", 1);
        assertThat(OriginalSqlCatalog.entries()).hasSize(44);
    }

    /** SERVER·NAV의 MEMBER만 생성 시각 720시간으로 만료시키며 다른 시각·행위자는 판정을 바꾸지 않는다. */
    @Test
    void actorKindMatrixAndMinimalReceipt() {
        long active = member("ACTIVE");
        long blocked = member("BLOCKED");
        long deleted = member("DELETED");
        long live = access(active, false);
        long retained = access(deleted, true);
        long unresolved = history(UUID.randomUUID(), "MEMBER", "SERVER", true, UUID.randomUUID());
        access(active, true);
        history(key(blocked), "MEMBER", "NAV", true, UUID.randomUUID());
        history(UUID.randomUUID(), "ADMIN", "SERVER", true, UUID.randomUUID());
        history(UUID.randomUUID(), "ADMIN", "NAV", true, UUID.randomUUID());
        history(null, "ANONYMOUS", "SERVER", true, UUID.randomUUID());
        history(null, "WORKER", "SERVER", true, UUID.randomUUID());
        var first = consume(1);
        assertThat(first).containsEntry("accesses", 1).containsEntry("receipts", 0);
        UUID operation = (UUID) first.get("operation_key");
        var receipt =
                sql.queryForMap(
                        "SELECT * FROM public.member_access_cleanup WHERE operation_key=?",
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
                                        + " public.member_access_cleanup WHERE operation_key=?",
                                Boolean.class,
                                operation))
                .isTrue();
        assertThat(consume(100)).containsEntry("accesses", 1);
        assertThat(consume(100)).containsEntry("accesses", 0).containsEntry("operation_key", null);
        assertThat(exists(live)).isTrue();
        assertThat(exists(retained)).isTrue();
        assertThat(exists(unresolved)).isTrue();
        assertThat(count("access_history")).isEqualTo(7);
    }

    /** 정확한 하한 시각을 INSERT하고 기본 INSERT 시계·NAV 중복 무시도 보존한다. */
    @Test
    void thresholdAndDefaultInsertClockAndNavDedupRemainValid() {
        UUID actor = key(member("ACTIVE"));
        sql.update(
                """
                INSERT INTO public.access_history(kind,request_id,actor_kind,actor_key,route,method,started_at,created_at)
                VALUES ('SERVER',gen_random_uuid(),'MEMBER',?,'/threshold','GET',clock_timestamp(),clock_timestamp()-interval '720 hours')
                """,
                actor);
        assertThat(consume(100)).containsEntry("accesses", 1);
        long fresh =
                sql.queryForObject(
                        """
                        INSERT INTO public.access_history(kind,request_id,actor_kind,actor_key,event_key,screen_code,client_at)
                        VALUES ('NAV',gen_random_uuid(),'MEMBER',?,gen_random_uuid(),'SYNTHETIC',clock_timestamp()-interval '900 hours') RETURNING id
                        """,
                        Long.class,
                        actor);
        assertThat(
                        sql.queryForObject(
                                "SELECT created_at>clock_timestamp()-interval '1 minute' FROM"
                                        + " public.access_history WHERE id=?",
                                Boolean.class,
                                fresh))
                .isTrue();
        assertThat(
                        sql.update(
                                """
                                INSERT INTO public.access_history(kind,request_id,actor_kind,actor_key,event_key,screen_code,client_at)
                                SELECT kind,gen_random_uuid(),actor_kind,actor_key,event_key,screen_code,client_at FROM public.access_history WHERE id=?
                                ON CONFLICT DO NOTHING
                                """,
                                fresh))
                .isZero();
        assertThat(consume(100)).containsEntry("accesses", 0);
    }

    /** SQL NULL·범위 밖 상한을 거부하고 두 종류의 행에 전역 상한을 적용한다. */
    @Test
    void limitsAndOneGlobalHundredCap() {
        for (Integer limit : new Integer[] {null, -1, 0, 101, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> consume(limit))
                    .hasStackTraceContaining("limit must be 1..100");
        }
        long owner = member("ACTIVE");
        for (int index = 0; index < 101; index++) {
            history(
                    key(owner),
                    "MEMBER",
                    index % 2 == 0 ? "SERVER" : "NAV",
                    true,
                    UUID.randomUUID());
        }
        assertThat(consume(100)).containsEntry("accesses", 100);
        assertThat(count("access_history")).isEqualTo(1);
        assertThat(consume(1)).containsEntry("accesses", 1);
    }

    /** MEMBER 전체 열과 양방향 행위자 변환을 고정하고 TRUNCATE를 거부한다. */
    @Test
    void memberBindingCannotBeRewrittenButNonMemberUpdatesRemainAllowed() {
        long member = access(member("ACTIVE"), true);
        for (String change :
                List.of(
                        "created_at=created_at+interval '1 second'",
                        "created_at=created_at-interval '1 second'",
                        "actor_kind='ADMIN'",
                        "actor_key=gen_random_uuid()",
                        "request_id=gen_random_uuid()",
                        "id=DEFAULT",
                        "kind='NAV'",
                        "worker_key='synthetic'",
                        "started_at=clock_timestamp()",
                        "route='/changed'")) {
            assertThatThrownBy(
                            () ->
                                    sql.execute(
                                            "UPDATE public.access_history SET "
                                                    + change
                                                    + " WHERE id="
                                                    + member))
                    .hasStackTraceContaining("binding immutable");
        }
        long other = history(UUID.randomUUID(), "ADMIN", "SERVER", true, UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                sql.execute(
                                        "UPDATE public.access_history SET actor_kind='MEMBER' WHERE"
                                                + " id="
                                                + other))
                .hasStackTraceContaining("binding immutable");
        assertThat(
                        sql.update(
                                "UPDATE public.access_history SET"
                                    + " route='/allowed',created_at=clock_timestamp(),request_id=gen_random_uuid()"
                                    + " WHERE id=?",
                                other))
                .isEqualTo(1);
        assertThatThrownBy(() -> sql.execute("TRUNCATE public.access_history"))
                .hasStackTraceContaining("truncate unauthorized");
    }

    /** EXECUTE 전용 역할과 별도 DML 역할 모두 실제 소유자 검사를 우회하지 못한다. */
    @Test
    void directDmlFakeGucsAndUnrelatedPrimaryAreDenied() throws Exception {
        long due = access(member("ACTIVE"), true);
        for (String table : List.of("access_history", "member_access_cleanup")) {
            for (String privilege : List.of("DELETE", "UPDATE", "INSERT", "TRUNCATE")) {
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
        assertThat(
                        sql.queryForObject(
                                "SELECT rolsuper OR rolcreaterole OR rolcreatedb FROM pg_roles"
                                        + " WHERE rolname=?",
                                Boolean.class,
                                role))
                .isFalse();
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM"
                                        + " pg_roles WHERE rolname=?)",
                                Integer.class,
                                role))
                .isZero();
        assertThatThrownBy(() -> executor.execute("DELETE FROM public.access_history"))
                .hasStackTraceContaining("permission denied");
        String probe = "probe_" + role;
        sql.execute("CREATE ROLE " + probe + " NOLOGIN");
        sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + probe);
        sql.execute(
                "GRANT DELETE,UPDATE,TRUNCATE ON public.access_history,public.member_access_cleanup"
                        + " TO "
                        + probe);
        sql.execute(
                "GRANT EXECUTE ON FUNCTION"
                    + " public.privacy_primary_delete_allowed(bigint,bigint),public.member_access_expiry_eligible(uuid,uuid)"
                    + " TO "
                        + probe);
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SET ROLE " + probe);
            statement.execute("SET app.privacy_primary='true'");
            statement.execute("SET app.member_access_expiry='true'");
            for (String mutation :
                    List.of(
                            "DELETE FROM public.access_history WHERE id=" + due,
                            "UPDATE public.access_history SET created_at=clock_timestamp() WHERE"
                                    + " id="
                                    + due,
                            "TRUNCATE public.access_history",
                            "TRUNCATE public.member_access_cleanup")) {
                assertThatThrownBy(() -> statement.execute(mutation))
                        .satisfies(
                                failure ->
                                        assertThat(((java.sql.SQLException) failure).getSQLState())
                                                .isEqualTo("42501"));
            }
        }
        assertThat(
                        sql.queryForObject(
                                "SELECT"
                                    + " has_function_privilege(?,'public.consume_privacy_primary(bigint)','EXECUTE')",
                                Boolean.class,
                                role))
                .isFalse();
        assertThat(exists(due)).isTrue();
        assertThat(consume(100)).containsEntry("accesses", 1);
    }

    /** 호출자 검색 경로와 임시 동명 표가 실제 표와 고정 권한을 대체하지 못한다. */
    @Test
    void hostileSearchPathCannotShadowRealObjects() throws Exception {
        access(member("ACTIVE"), true);
        try (Connection connection = executor.getDataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE access_history(id bigint)");
            statement.execute("INSERT INTO access_history VALUES (999)");
            statement.execute("CREATE TEMP TABLE member_access_cleanup(operation_key uuid)");
            statement.execute("SET search_path=pg_temp,public,pg_catalog");
            try (var result =
                    statement.executeQuery(
                            "SELECT * FROM public.consume_member_access_expiry(100)")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("accesses")).isEqualTo(1);
            }
            try (var result =
                    statement.executeQuery("SELECT count(*) FROM pg_temp.access_history")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(count("access_history")).isZero();
    }

    /** 다른 거래의 행위자 부모·이력 잠금을 건너뛰고 해제 후에만 소비한다. */
    @Test
    void parentAndHistoryLocksAreSkipped() throws Exception {
        long owner = member("ACTIVE");
        long due = access(owner, true);
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement
                    .executeQuery(
                            "SELECT id FROM public.member_account WHERE id="
                                    + owner
                                    + " FOR UPDATE")
                    .close();
            assertThat(consume(100)).containsEntry("accesses", 0);
            connection.rollback();
            statement
                    .executeQuery(
                            "SELECT id FROM public.access_history WHERE id=" + due + " FOR UPDATE")
                    .close();
            assertThat(consume(100)).containsEntry("accesses", 0);
            connection.rollback();
        }
        assertThat(consume(100)).containsEntry("accesses", 1);
    }

    /** 행위자가 참가하지 않는 요청 TEST도 참가자 부모를 잠가 접수 거래를 추월하지 않는다. */
    @Test
    void requestSourceWithdrawalLocksAreIndependentOfActorMembership() throws Exception {
        long actor = member("ACTIVE");
        long first = member("ACTIVE");
        long second = member("ACTIVE");
        long test = testSource(first, second);
        UUID request = UUID.randomUUID();
        link(test, request, false);
        long due = history(key(actor), "MEMBER", "SERVER", true, request);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.test_member WHERE member_id=?",
                                Integer.class,
                                actor))
                .isZero();
        // 부모 상한 안에 필요한 잠금이 모두 들어오지 않으면 삭제하지 않는다.
        assertThat(consume(1)).containsEntry("accesses", 0);
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement
                    .executeQuery(
                            "SELECT id FROM public.member_account WHERE id="
                                    + first
                                    + " FOR UPDATE")
                    .close();
            assertThat(consume(100)).containsEntry("accesses", 0);
            connection.rollback();
        }
        // 실제 접수와 같은 참가자 부모를 선점하며 미확정 root는 별도 연결에서 보이지 않는다.
        var withdrawal =
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(source));
        withdrawal.executeWithoutResult(
                status -> {
                    sql.queryForList(
                            "SELECT id FROM public.member_account WHERE id=? FOR UPDATE", first);
                    erasure(first);
                    assertThat(consume(100)).containsEntry("accesses", 0);
                });
        assertThat(consume(100)).containsEntry("accesses", 0);
        assertThat(exists(due)).isTrue();
    }

    /** 공유 TEST 동료의 미확정 접수도 해당 동료의 부모 잠금으로 보호한다. */
    @Test
    void sharedPeerRootUsesPeerParentLock() {
        long actor = member("ACTIVE");
        long peer = member("ACTIVE");
        testSource(actor, peer);
        access(actor, true);
        var withdrawal =
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(source));
        withdrawal.executeWithoutResult(
                status -> {
                    sql.queryForList(
                            "SELECT id FROM public.member_account WHERE id=? FOR UPDATE", peer);
                    erasure(peer);
                    assertThat(consume(100)).containsEntry("accesses", 0);
                });
        assertThat(consume(100)).containsEntry("accesses", 0);
    }

    /** 파기 소유권이 없는 요청 TEST는 모든 부모가 상한 안에 잠기면 정상 만료한다. */
    @Test
    void unownedRequestSourceExpiresWithCompleteParentClosure() {
        long actor = member("ACTIVE");
        long test = testSource(member("ACTIVE"), member("ACTIVE"));
        UUID request = UUID.randomUUID();
        link(test, request, false);
        history(key(actor), "MEMBER", "SERVER", true, request);
        assertThat(consume(2)).containsEntry("accesses", 0);
        assertThat(consume(3)).containsEntry("accesses", 1);
        assertThat(count("play_test")).isEqualTo(1);
        assertThat(count("test_audit")).isEqualTo(1);
    }

    /** 별도 연결의 탈퇴 확정 뒤에도 새 상태로 다시 검사한다. */
    @Test
    void withdrawalStateRecheckedAfterCommit() throws Exception {
        long owner = member("ACTIVE");
        access(owner, true);
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(
                    "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                            + " WHERE id="
                            + owner);
            assertThat(consume(100)).containsEntry("accesses", 0);
            connection.commit();
        }
        assertThat(consume(100)).containsEntry("accesses", 0).containsEntry("operation_key", null);
    }

    /** 동시 소비자의 전체 삭제 수와 원자 영수증 수를 대조해 중복 삭제를 검사한다. */
    @Test
    void concurrentSweepersNeverDoubleDelete() throws Exception {
        for (int index = 0; index < 20; index++) access(member("ACTIVE"), true);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return consume(100);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return consume(100);
                            });
            start.countDown();
            int removed =
                    (Integer) first.get(15, TimeUnit.SECONDS).get("accesses")
                            + (Integer) second.get(15, TimeUnit.SECONDS).get("accesses");
            removed += (Integer) consume(100).get("accesses");
            assertThat(removed).isEqualTo(20);
            assertThat(count("access_history")).isZero();
            assertThat(
                            sql.queryForObject(
                                    "SELECT sum(deleted_count)::integer FROM"
                                            + " public.member_access_cleanup",
                                    Integer.class))
                    .isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 모든 상태의 회원 root·대상·공유 TEST·PUBLIC ROOM/MEMBERSHIP을 보존한다. */
    @Test
    void allStateErasureAndPublicSourceExclusions() throws Exception {
        long direct = member("ACTIVE");
        long targetMember = member("ACTIVE");
        long actor = member("ACTIVE");
        long peer = member("ACTIVE");
        long roomOwner = member("ACTIVE");
        long roomPeer = member("ACTIVE");
        erasure(direct);
        target(erasure(targetMember), "member_id", targetMember);
        long test = testSource(actor, peer);
        target(erasure(peer), "test_id", test);
        long room = gameSource(roomOwner, roomPeer);
        target(erasure(roomOwner), "room_id", room);
        long membership =
                sql.queryForObject(
                        "SELECT id FROM public.game_member WHERE room_id=? AND member_id=?",
                        Long.class,
                        room,
                        roomPeer);
        target(erasure(roomPeer), "game_member_id", membership);
        for (long owner : List.of(direct, targetMember, actor, peer, roomOwner, roomPeer))
            access(owner, true);
        for (String state : List.of("PENDING", "RUNNING", "RETRY", "FAILED")) {
            sql.update(
                    "UPDATE public.privacy_target SET state=?,lease_until=CASE WHEN ?='RUNNING'"
                            + " THEN clock_timestamp()+interval '1 hour' ELSE NULL END",
                    state,
                    state);
            assertThat(consume(100)).containsEntry("accesses", 0);
        }
        assertThat(count("access_history")).isEqualTo(6);
        assertThatThrownBy(() -> sql.execute("DELETE FROM public.access_history"))
                .hasStackTraceContaining("expiry deletion unauthorized");
    }

    /** PUBLIC의 미접수 동료도 다른 참가자의 ROOM 대상으로 보호한다. */
    @Test
    void publicRoomTargetProtectsPeerWithoutPeerRoot() throws Exception {
        long owner = member("ACTIVE");
        long peer = member("ACTIVE");
        sourceParents();
        long room = gameSource(owner, peer);
        target(erasure(owner), "room_id", room);
        access(peer, true);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.privacy_erase WHERE member_id=?",
                                Integer.class,
                                peer))
                .isZero();
        assertThat(consume(100)).containsEntry("accesses", 0);
    }

    /** 한 요청에 UUID·이전 숫자 출처가 여러 개면 후속 보호 TEST도 빠짐없이 검사한다. */
    @Test
    void allRequestLinkedSourcesAreConsideredWithoutActorParticipation() {
        long actor = member("ACTIVE");
        long first = member("ACTIVE");
        long second = member("ACTIVE");
        long test = testSource(first, second);
        long protectedTest = testSource(first, second);
        UUID request = UUID.randomUUID();
        link(test, request, false);
        link(protectedTest, request, true);
        history(key(actor), "MEMBER", "NAV", true, request);
        target(erasure(second), "test_id", protectedTest);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.test_member WHERE member_id=?",
                                Integer.class,
                                actor))
                .isZero();
        assertThat(consume(100)).containsEntry("accesses", 0);
        assertThat(count("access_history")).isEqualTo(1);
    }

    /** 실제 봉인·RUNNING·임대된 회원 PRIMARY는 생존·만료 이력을 영수증 없이 삭제한다. */
    @Test
    void genuineAccountPrimaryStillDeletesLiveAndExpiredAccesses() {
        long owner = member("ACTIVE");
        access(owner, true);
        access(owner, false);
        long root = erasure(owner);
        long target = target(root, "member_id", owner);
        sql.update(
                "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                        + " WHERE id=?",
                owner);
        seal(root, target, 3);
        assertThat(
                        sql.queryForObject(
                                "SELECT public.consume_privacy_primary(?)", Boolean.class, target))
                .isTrue();
        assertThat(count("access_history")).isZero();
        assertThat(count("member_access_cleanup")).isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT state FROM public.privacy_erase WHERE id=?",
                                String.class,
                                root))
                .isEqualTo("WAITING");
    }

    /** 실제 TEST PRIMARY는 비참가 행위자의 요청 출처도 허가하며 다른 TEST와 행위자는 보존한다. */
    @Test
    void genuineTestPrimaryAuthorizesAnyMatchingRequestSource() {
        long actor = member("ACTIVE");
        long first = member("ACTIVE");
        long second = member("ACTIVE");
        long counterpart = testSource(first, second);
        long owned = testSource(first, second);
        UUID request = UUID.randomUUID();
        link(counterpart, request, false);
        link(owned, request, true);
        history(key(actor), "MEMBER", "SERVER", true, request);
        history(key(actor), "MEMBER", "NAV", false, request);
        long unrelated = access(actor, false);
        long root = erasure(first);
        long target = target(root, "test_id", owned);
        target(root, "test_id", counterpart);
        target(root, "member_id", first);
        sql.update(
                "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                        + " WHERE id=?",
                first);
        seal(root, target, 5);
        assertThatThrownBy(
                        () -> sql.update("DELETE FROM public.access_history WHERE id=?", unrelated))
                .hasStackTraceContaining("expiry deletion unauthorized");
        assertThat(
                        sql.queryForObject(
                                "SELECT public.consume_privacy_primary(?)", Boolean.class, target))
                .isTrue();
        assertThat(sql.queryForList("SELECT id FROM public.access_history", Long.class))
                .containsExactly(unrelated);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.play_test WHERE id=?",
                                Integer.class,
                                counterpart))
                .isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT count(*) FROM public.member_account WHERE id=?",
                                Integer.class,
                                actor))
                .isEqualTo(1);
        assertThat(count("member_access_cleanup")).isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT state FROM public.privacy_target WHERE id=?",
                                String.class,
                                target))
                .isEqualTo("COMPLETE");
    }

    /** 요청 TEST가 실제 PRIMARY여도 비회원 이력에는 정의되지 않은 삭제 권한을 만들지 않는다. */
    @Test
    void genuineTestPrimaryCannotAuthorizeNonMemberAccesses() {
        long first = member("ACTIVE");
        long second = member("ACTIVE");
        long test = testSource(first, second);
        for (String kind : List.of("ADMIN", "ANONYMOUS", "WORKER")) {
            UUID request = UUID.randomUUID();
            link(test, request, false);
            history(kind.equals("ADMIN") ? UUID.randomUUID() : null, kind, "SERVER", true, request);
        }
        long root = erasure(first);
        long target = target(root, "test_id", test);
        target(root, "member_id", first);
        sql.update(
                "UPDATE public.member_account SET state='DELETED',deleted_at=clock_timestamp()"
                        + " WHERE id=?",
                first);
        seal(root, target, 4);
        assertThatThrownBy(() -> sql.execute("DELETE FROM public.access_history"))
                .hasStackTraceContaining("expiry deletion unauthorized");
        assertThat(
                        sql.queryForObject(
                                "SELECT public.consume_privacy_primary(?)", Boolean.class, target))
                .isTrue();
        assertThat(count("access_history")).isEqualTo(3);
        assertThat(count("test_audit")).isZero();
        assertThat(count("member_access_cleanup")).isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT state FROM public.privacy_target WHERE id=?",
                                String.class,
                                target))
                .isEqualTo("COMPLETE");
    }

    /** 이력 DELETE·영수증 INSERT·만료 영수증 DELETE 실패는 앞선 삭제까지 롤백한다. */
    @Test
    void deletionAndReceiptFailuresRollBackWholeUnit() {
        long due = access(member("ACTIVE"), true);
        expiredReceipts(1);
        sql.execute(
                "CREATE FUNCTION public.access_failure() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'synthetic access failure'; END $$");
        for (String event : List.of("history", "insert", "expiry")) {
            String table = event.equals("history") ? "access_history" : "member_access_cleanup";
            String action = event.equals("insert") ? "INSERT" : "DELETE";
            sql.execute(
                    "CREATE TRIGGER zz_access_failure BEFORE "
                            + action
                            + " ON public."
                            + table
                            + " FOR EACH ROW EXECUTE FUNCTION public.access_failure()");
            try {
                assertThatThrownBy(() -> consume(100))
                        .hasStackTraceContaining("synthetic access failure");
                assertThat(exists(due)).isTrue();
                assertThat(count("member_access_cleanup")).isEqualTo(1);
            } finally {
                sql.execute("DROP TRIGGER zz_access_failure ON public." + table);
            }
        }
        assertThat(consume(100)).containsEntry("accesses", 1).containsEntry("receipts", 1);
        assertThat(count("member_access_cleanup")).isEqualTo(1);
    }

    /** 실제 확정 시 지연 트리거가 실패하면 Java 결과를 반환하지 않고 이력과 영수증을 복원한다. */
    @Test
    void wrapperCommitFailureRollsBackAndDoesNotReturnCounts() {
        long due = access(member("ACTIVE"), true);
        expiredReceipts(1);
        sql.execute(
                "CREATE FUNCTION public.access_commit_failure() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$ BEGIN RAISE EXCEPTION 'synthetic access commit failure'; END $$");
        sql.execute(
                "CREATE CONSTRAINT TRIGGER zz_access_commit_failure AFTER INSERT ON"
                        + " public.member_access_cleanup DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                        + " EXECUTE FUNCTION public.access_commit_failure()");
        var wrapper =
                new MemberAccessCleanupService(
                        executor, new DataSourceTransactionManager(executor.getDataSource()));
        assertThatThrownBy(() -> wrapper.cleanup(100))
                .hasStackTraceContaining("synthetic access commit failure");
        assertThat(exists(due)).isTrue();
        assertThat(count("member_access_cleanup")).isEqualTo(1);
        sql.execute("DROP TRIGGER zz_access_commit_failure ON public.member_access_cleanup");
        assertThat(wrapper.cleanup(100).accesses()).isEqualTo(1);
    }

    /** 최대 90일의 최소 영수증은 불변이며 영수증만 삭제하거나 무작업일 때 새 영수증은 없다. */
    @Test
    void receiptExpiryCapImmutabilityAndNoop() {
        expiredReceipts(101);
        assertThat(consume(100))
                .containsEntry("accesses", 0)
                .containsEntry("receipts", 100)
                .containsEntry("operation_key", null);
        assertThat(count("member_access_cleanup")).isEqualTo(1);
        assertThat(consume(1)).containsEntry("receipts", 1).containsEntry("operation_key", null);
        assertThat(consume(100))
                .containsEntry("accesses", 0)
                .containsEntry("receipts", 0)
                .containsEntry("operation_key", null);
        access(member("ACTIVE"), true);
        consume(1);
        assertThatThrownBy(
                        () ->
                                sql.execute(
                                        "UPDATE public.member_access_cleanup SET deleted_count=1"))
                .hasStackTraceContaining("mutation unauthorized");
        assertThatThrownBy(() -> sql.execute("TRUNCATE public.member_access_cleanup"))
                .hasStackTraceContaining("mutation unauthorized");
        assertThatThrownBy(() -> sql.execute("DELETE FROM public.member_access_cleanup"))
                .hasStackTraceContaining("not expired");
        assertThatThrownBy(
                        () ->
                                sql.execute(
                                        "INSERT INTO public.member_access_cleanup VALUES"
                                            + " (gen_random_uuid(),'DATA-LIFECYCLE-01',1,clock_timestamp(),'EXPIRED_REMOVED',clock_timestamp()+interval"
                                            + " '91 days')"))
                .hasStackTraceContaining("ck_mac_expiry");
        assertThat(consume(100)).containsEntry("receipts", 0);
    }

    /** 컨테이너 안에 새 전용 DB만 만든다. */
    private DriverManagerDataSource database() {
        String name = "access_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE DATABASE " + name);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }

    /** 독립 SQL 자원 없이 지정한 역사적 버전까지 내장 원본을 적용한다. */
    private Flyway flyway(DriverManagerDataSource dataSource, String version) {
        return Flyway.configure()
                .dataSource(dataSource)
                .target(version)
                .locations("classpath:access-no-independent-sql")
                .resourceProvider(new EmbeddedSqlResourceProvider())
                .load();
    }

    /** 실제 DB UUID와 상태를 가진 회원을 만든다. */
    private long member(String state) {
        return sql.queryForObject(
                "INSERT INTO public.member_account(member_key,state,deleted_at) VALUES"
                        + " (gen_random_uuid(),?,CASE WHEN ?='DELETED' THEN clock_timestamp() END)"
                        + " RETURNING id",
                Long.class,
                state,
                state);
    }

    /** 회원의 실제 UUID를 조회한다. */
    private UUID key(long member) {
        return sql.queryForObject(
                "SELECT member_key FROM public.member_account WHERE id=?", UUID.class, member);
    }

    /** UPDATE나 가드 해제 없이 신뢰된 fixture INSERT로 과거 SERVER를 만든다. */
    private long access(long member, boolean due) {
        return history(key(member), "MEMBER", "SERVER", due, UUID.randomUUID());
    }

    /** 모든 행위자와 양쪽 종류의 실제 CHECK를 만족하는 합성 행을 INSERT한다. */
    private long history(UUID actor, String actorKind, String kind, boolean due, UUID request) {
        return sql.queryForObject(
                """
                INSERT INTO public.access_history(kind,request_id,actor_kind,actor_key,worker_key,route,method,started_at,
                  event_key,screen_code,client_at,created_at)
                VALUES (?,?,?,?,CASE WHEN ?='WORKER' THEN 'synthetic_worker' END,
                  CASE WHEN ?='SERVER' THEN '/synthetic' END,CASE WHEN ?='SERVER' THEN 'GET' END,
                  CASE WHEN ?='SERVER' THEN clock_timestamp()+interval '900 hours' END,
                  CASE WHEN ?='NAV' THEN gen_random_uuid() END,CASE WHEN ?='NAV' THEN 'SYNTHETIC' END,
                  CASE WHEN ?='NAV' THEN clock_timestamp()+interval '900 hours' END,
                  CASE WHEN ? THEN clock_timestamp()-interval '721 hours' ELSE clock_timestamp() END) RETURNING id
                """,
                Long.class,
                kind,
                request,
                actorKind,
                actor,
                actorKind,
                kind,
                kind,
                kind,
                kind,
                kind,
                kind,
                due);
    }

    /** 제한 역할의 별도 연결에서 SQL 입력 자체를 검사한다. */
    private Map<String, Object> consume(Integer limit) {
        return executor.queryForMap(
                "SELECT * FROM public.consume_member_access_expiry(?::integer)", limit);
    }

    private boolean exists(long id) {
        return sql.queryForObject(
                        "SELECT count(*) FROM public.access_history WHERE id=?", Integer.class, id)
                != 0;
    }

    private int count(String table) {
        return sql.queryForObject("SELECT count(*) FROM public." + table, Integer.class);
    }

    /** 원래 완료일과 유효기한을 INSERT하며 불변 시계는 갱신하지 않는다. */
    private void expiredReceipts(int count) {
        sql.update(
                """
                INSERT INTO public.member_access_cleanup
                SELECT gen_random_uuid(),'DATA-LIFECYCLE-01',1,clock_timestamp()-interval '91 days',
                  'EXPIRED_REMOVED',clock_timestamp()-interval '2 days' FROM generate_series(1,?)
                """,
                count);
    }

    /** 실제 프로필과 같은 정책에 연결된 미봉인 접수를 만든다. */
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

    /** 고정 열 이름만 허용해 실제 PRIMARY 소유 대상을 만든다. */
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

    /** 기존 가드를 통해 최소 세 대상의 실제 봉인과 임대를 만든다. */
    private void seal(long root, long target, int count) {
        sql.update(
                "INSERT INTO public.privacy_target(erase_id,target_key,kind,source_kind) VALUES"
                    + " (?,gen_random_uuid(),'BACKUP','NONE'),(?,gen_random_uuid(),'RESTORE_GUARD','NONE')",
                root,
                root);
        sql.update(
                "UPDATE public.privacy_erase SET"
                    + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=?,state='PROCESSING'"
                    + " WHERE id=?",
                count,
                root);
        sql.update(
                "UPDATE public.privacy_target SET"
                    + " state='RUNNING',lease_until=clock_timestamp()+interval '1 hour' WHERE id=?",
                target);
    }

    /** 같은 fixture의 실제 FK 부모를 한 번 만들고 여러 출처 시험에서 공유한다. */
    private long[] sourceParents() {
        var existing =
                sql.queryForList(
                        "SELECT v.id AS version,s.id AS snapshot,r.id AS runtime FROM"
                            + " public.story_version v JOIN public.review_snapshot s ON"
                            + " s.version_id=v.id CROSS JOIN public.grade_runtime r ORDER BY v.id"
                            + " LIMIT 1");
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            return new long[] {
                ((Number) row.get("version")).longValue(),
                ((Number) row.get("snapshot")).longValue(),
                ((Number) row.get("runtime")).longValue()
            };
        }
        long story =
                sql.queryForObject(
                        "INSERT INTO public.story(code,owner_id) VALUES ('ACCESS_SOURCE',?)"
                                + " RETURNING id",
                        Long.class,
                        admin);
        long version =
                sql.queryForObject(
                        "INSERT INTO"
                            + " public.story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 접근 관계','ACCESS_SOURCE',?,?) RETURNING id",
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
                            + " VALUES ('ACCESS_SOURCE',repeat('a',64),'{}','AVAILABLE') RETURNING"
                            + " id",
                        Long.class);
        return new long[] {version, snapshot, runtime};
    }

    /** 실제 FK를 가진 두 참가자의 TEST를 만든다. */
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

    /** UUID와 이전 숫자 scope를 실제 test_audit에 기록한다. */
    private void link(long test, UUID request, boolean legacy) {
        String scope =
                legacy
                        ? "test:" + test
                        : "test:"
                                + sql.queryForObject(
                                        "SELECT test_key FROM public.play_test WHERE id=?",
                                        UUID.class,
                                        test);
        sql.update(
                """
                INSERT INTO public.test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail)
                VALUES (gen_random_uuid(),'ADMIN',?,'SYNTHETIC','TEST',?,?,'RESULT','SUCCESS','{}')
                """,
                UUID.randomUUID().toString(),
                scope,
                request);
    }

    /** 기존 감사 fixture와 같은 PUBLIC 부모와 배정·미배정 참가자를 만든다. */
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
                            + " ('ACCESS_FRIENDLY','SYNTHETIC_HTTP','FRIENDLY','DRAFT',repeat('a',64),?::jsonb,?)"
                            + " RETURNING id",
                        Long.class,
                        JSON.writeValueAsString(document),
                        admin);
        long[] parents = sourceParents();
        long story =
                sql.queryForObject(
                        "SELECT story_id FROM public.story_version WHERE id=?",
                        Long.class,
                        parents[0]);
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
                        story,
                        parents[0],
                        parents[1],
                        parents[2],
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
                    member == first ? parents[0] : null,
                    member == first ? "R1" : null);
        }
        return room;
    }

    /** 명시 환경변수가 있을 때만 행과 자격을 포함하지 않는 카탈로그 증거를 내보낸다. */
    private void exportSchemaEvidence() throws Exception {
        String destination = System.getenv("MEMBER_ACCESS_SCHEMA_OUTPUT");
        if (destination == null || destination.isBlank()) return;
        var output = JSON.createObjectNode().put("migrationVersion", 44);
        output.put(
                "totalForeignKeys",
                sql.queryForObject(
                        "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON"
                            + " n.oid=c.connamespace WHERE n.nspname='public' AND c.contype='f'",
                        Integer.class));
        var tables = output.putObject("tables");
        for (String table : List.of("access_history", "member_access_cleanup")) {
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
            target.set(
                    "triggers",
                    JSON.valueToTree(
                            sql.queryForList(
                                    "SELECT tgname AS name,pg_get_triggerdef(oid,true) AS"
                                        + " definition FROM pg_trigger WHERE tgrelid=?::regclass"
                                        + " AND NOT tgisinternal ORDER BY tgname",
                                    "public." + table)));
        }
        output.set(
                "functions",
                JSON.valueToTree(
                        sql.queryForList(
                                """
                                SELECT p.proname AS name,pg_get_functiondef(p.oid) AS definition,p.prosecdef AS securityDefiner,array_to_string(p.proconfig,',') AS configuration
                                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname IN
                                ('consume_member_access_expiry','member_access_expiry_eligible','guard_member_access_binding',
                                 'guard_member_access_expiry_delete','guard_member_access_cleanup') ORDER BY p.proname
                                """)));
        Path path = Path.of(destination);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output),
                StandardCharsets.UTF_8);
    }
}
