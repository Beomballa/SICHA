package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.BreachedPasswordChecker;
import com.reasoning.common.member.auth.MemberAuthCleanupService;
import com.reasoning.common.member.auth.MemberAuthService;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.story.model.FrozenSnapshotCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 폐기 PostgreSQL의 실제 가드·refresh·잠금으로 한정 family 정리와 독립 확정을 검사한다. */
public class MemberAuthCleanupIT extends LocalMemberAuthIT {
    private static final String PASSWORD = "Synthetic-member-password-987!";
    @Autowired private MemberPolicyGate gate;
    @Autowired private BreachedPasswordChecker breached;

    /** 기존 합성 컨텍스트와 같은 연결 원천의 소유 거래를 구성한다. */
    private MemberAuthCleanupService cleanup() {
        return new MemberAuthCleanupService(
                db, new DataSourceTransactionManager(db.getDataSource()));
    }

    /**
     * 실제 불변 기한을 신규 행으로 만들며 트리거·시계를 변경하지 않는다.
     *
     * @param expired true면 이미 만료된 10분 창
     * @param member 실제 회원 ID 또는 계정 없는 flow의 null
     * @return 신규 flow ID
     */
    private long flow(boolean expired, Long member) {
        return db.queryForObject(
                "WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS now) INSERT INTO"
                    + " member_flow(flow_key,binder_hash,purpose,state,provider,lookup_hash,code_hash,proof_cipher,created_at,expires_at,member_id,identity_id,auth_rev)"
                    + " SELECT"
                    + " ?,?,'RESET','PENDING','LOCAL',repeat('b',64),repeat('c',64),decode(repeat('00',32),'hex'),clock.now+?::interval,clock.now+?::interval,?,i.id,a.auth_rev"
                    + " FROM clock LEFT JOIN member_account a ON a.id=? LEFT JOIN member_identity i"
                    + " ON i.member_id=a.id RETURNING id",
                Long.class,
                UUID.randomUUID(),
                "a".repeat(64),
                expired ? "-20 minutes" : "0 seconds",
                expired ? "-10 minutes" : "10 minutes",
                member,
                member);
    }

    /**
     * 동일 발급 family 안에서 ACCESS 신규 행만 생성한다.
     *
     * @param session 실제 family ID
     * @param generation family 안에서 사용하지 않은 발급 세대
     * @param expired true면 이미 만료된 5분 창
     * @return 신규 ACCESS ID
     */
    private long access(long session, long generation, boolean expired) {
        return db.queryForObject(
                "WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS now) INSERT INTO"
                    + " member_token(session_id,kind,generation,token_hash,state,issued_at,expires_at)"
                    + " SELECT ?,'ACCESS',?,?,'ISSUED',clock.now+?::interval,clock.now+?::interval"
                    + " FROM clock RETURNING id",
                Long.class,
                session,
                generation,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                expired ? "-6 minutes" : "0 seconds",
                expired ? "-1 minute" : "5 minutes");
    }

    /**
     * 실제 제한 창의 과거 또는 현재 기한을 생성한다.
     *
     * @param expired true면 만료된 15분 창
     * @return 합성 제한 버킷 해시
     */
    private String bucket(boolean expired) {
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        db.update(
                "INSERT INTO"
                    + " member_auth_limit(scope,bucket_hash,window_at,hit_count,blocked_until,purge_at)"
                    + " VALUES"
                    + " ('VERIFY_SOURCE_15M',?,clock_timestamp()+?::interval,7,clock_timestamp()+?::interval,clock_timestamp()+?::interval)",
                hash,
                expired ? "-20 minutes" : "0 seconds",
                expired ? "-5 minutes" : "15 minutes",
                expired ? "-5 minutes" : "15 minutes");
        return hash;
    }

    /** 후보가 없는 정리까지 유한 반복하여 다른 합성 테스트의 만료 잔여물을 제거한다. */
    private void drain() {
        for (int i = 0; i < 100; i++) {
            var counts = cleanup().cleanup(100);
            if (counts.equals(new MemberAuthCleanupService.Counts(0, 0, 0, 0, 0))) return;
        }
        throw new AssertionError("합성 만료 후보가 반복 상한 안에 소진되지 않았습니다.");
    }

    @Test
    void expiredArtifactsOnlyAndReplayEvidenceRetained() throws Exception {
        drain();
        var issued = account("cleanup-" + UUID.randomUUID() + "@example.invalid");
        String access = issued.path("accessToken").asText();
        var actor = service.authenticate(access, false);
        long expiredFlow = flow(true, actor.memberId());
        long anonymousFlow = flow(true, null);
        long liveFlow = flow(false, actor.memberId());
        long expiredAccess = access(actor.sessionId(), 100, true);
        long liveAccess = access(actor.sessionId(), 101, false);
        String oldBucket = bucket(true);
        String liveBucket = bucket(false);
        service.refresh(issued.path("refreshToken").asText(), UUID.randomUUID());
        var replay =
                db.queryForList(
                        "SELECT * FROM member_token WHERE session_id=? AND kind='REFRESH' ORDER BY"
                                + " id",
                        actor.sessionId());
        var accounts = db.queryForList("SELECT * FROM member_account ORDER BY id");
        var identities = db.queryForList("SELECT * FROM member_identity ORDER BY id");
        var profiles = db.queryForList("SELECT * FROM member_profile ORDER BY member_id");
        var audits = db.queryForList("SELECT * FROM member_auth_audit ORDER BY id");
        var roots = db.queryForList("SELECT * FROM privacy_erase ORDER BY id");
        var targets = db.queryForList("SELECT * FROM privacy_target ORDER BY id");
        var families = db.queryForList("SELECT * FROM member_session ORDER BY id");

        assertThat(cleanup().cleanup(100))
                .isEqualTo(new MemberAuthCleanupService.Counts(2, 1, 0, 0, 1));
        assertThat(
                        db.queryForList(
                                "SELECT id FROM member_flow WHERE id IN (?,?)",
                                expiredFlow,
                                anonymousFlow))
                .isEmpty();
        assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", liveFlow)).hasSize(1);
        assertThat(db.queryForList("SELECT id FROM member_token WHERE id=?", expiredAccess))
                .isEmpty();
        assertThat(db.queryForList("SELECT id FROM member_token WHERE id=?", liveAccess))
                .hasSize(1);
        assertThat(
                        db.queryForList(
                                "SELECT scope FROM member_auth_limit WHERE bucket_hash=?",
                                oldBucket))
                .isEmpty();
        assertThat(
                        db.queryForMap(
                                "SELECT hit_count FROM member_auth_limit WHERE bucket_hash=?",
                                liveBucket))
                .containsEntry("hit_count", 7);
        assertThat(
                        db.queryForList(
                                "SELECT * FROM member_token WHERE session_id=? AND kind='REFRESH'"
                                        + " ORDER BY id",
                                actor.sessionId()))
                .isEqualTo(replay);
        assertThat(replay).anySatisfy(row -> assertThat(row).containsEntry("state", "USED"));
        assertThat(db.queryForList("SELECT * FROM member_account ORDER BY id")).isEqualTo(accounts);
        assertThat(db.queryForList("SELECT * FROM member_identity ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(identities);
        assertThat(db.queryForList("SELECT * FROM member_profile ORDER BY member_id"))
                .usingRecursiveComparison()
                .isEqualTo(profiles);
        assertThat(db.queryForList("SELECT * FROM member_auth_audit ORDER BY id"))
                .isEqualTo(audits);
        assertThat(db.queryForList("SELECT * FROM privacy_erase ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(roots);
        assertThat(db.queryForList("SELECT * FROM privacy_target ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(targets);
        assertThat(db.queryForList("SELECT * FROM member_session ORDER BY id")).isEqualTo(families);
        assertThat(cleanup().cleanup(100))
                .isEqualTo(new MemberAuthCleanupService.Counts(0, 0, 0, 0, 0));
    }

    @Test
    void eachKindHasIndependentCapAndInvalidLimitsCannotMutate() throws Exception {
        drain();
        var issued = account("cleanup-cap-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        for (int i = 0; i < 3; i++) {
            flow(true, null);
            access(actor.sessionId(), 200 + i, true);
            bucket(true);
        }
        assertThatThrownBy(() -> cleanup().cleanup(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanup().cleanup(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cleanup().cleanup(2))
                .isEqualTo(new MemberAuthCleanupService.Counts(2, 2, 0, 0, 2));
        assertThat(cleanup().cleanup(2))
                .isEqualTo(new MemberAuthCleanupService.Counts(1, 1, 0, 0, 1));
        assertThat(cleanup().cleanup(2))
                .isEqualTo(new MemberAuthCleanupService.Counts(0, 0, 0, 0, 0));
    }

    @Test
    void ambientTransactionWrongManagerAndDeleteFailureFailClosed() {
        drain();
        long first = flow(true, null);
        long second = flow(true, null);
        var outer = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        outer.executeWithoutResult(
                status ->
                        assertThatThrownBy(() -> cleanup().cleanup(100))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("AMBIENT_MEMBER_TRANSACTION"));
        assertThatThrownBy(
                        () ->
                                new MemberAuthCleanupService(
                                        db,
                                        new DataSourceTransactionManager(
                                                new DriverManagerDataSource())))
                .hasMessage("INVALID_MEMBER_TRANSACTION_MANAGER");
        db.execute(
                "CREATE FUNCTION public.fail_cleanup_flow() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN IF OLD.id="
                        + second
                        + " THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN OLD; END $$");
        db.execute(
                "CREATE TRIGGER tr_fail_cleanup_flow BEFORE DELETE ON member_flow FOR EACH ROW"
                        + " EXECUTE FUNCTION public.fail_cleanup_flow()");
        try {
            assertThatThrownBy(() -> cleanup().cleanup(100))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(
                            db.queryForList(
                                    "SELECT id FROM member_flow WHERE id IN (?,?)", first, second))
                    .hasSize(2);
        } finally {
            db.execute("DROP TRIGGER tr_fail_cleanup_flow ON member_flow");
            db.execute("DROP FUNCTION public.fail_cleanup_flow()");
        }
    }

    @Test
    void inFlightErasureReferencesRemainUnchanged() throws Exception {
        drain();
        var issued = account("cleanup-erase-" + UUID.randomUUID() + "@example.invalid");
        String token = issued.path("accessToken").asText();
        var actor = service.authenticate(token, false);
        long flow = flow(true, actor.memberId());
        long access = access(actor.sessionId(), 300, true);
        UUID identity =
                db.queryForObject(
                        "SELECT identity_key FROM member_identity WHERE member_id=?",
                        UUID.class,
                        actor.memberId());
        var grant =
                service.reauthenticate(
                        token,
                        "DELETE_ACCOUNT",
                        identity,
                        PASSWORD,
                        "198.19.71.1",
                        UUID.randomUUID());
        service.deleteAccount(token, grant.reauthGrant(), UUID.randomUUID(), UUID.randomUUID());
        var roots = db.queryForList("SELECT * FROM privacy_erase ORDER BY id");
        var targets = db.queryForList("SELECT * FROM privacy_target ORDER BY id");
        var account = db.queryForMap("SELECT * FROM member_account WHERE id=?", actor.memberId());
        var audit = db.queryForList("SELECT * FROM member_auth_audit ORDER BY id");
        var families =
                db.queryForList(
                        "SELECT * FROM member_session WHERE member_id=? ORDER BY id",
                        actor.memberId());
        var tokens =
                db.queryForList(
                        "SELECT t.* FROM member_token t JOIN member_session s ON s.id=t.session_id"
                                + " WHERE s.member_id=? ORDER BY t.id",
                        actor.memberId());
        long laterEligible = flow(true, null);
        assertThat(cleanup().cleanup(1).flows()).isEqualTo(1);
        assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", laterEligible))
                .isEmpty();
        assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", flow)).hasSize(1);
        assertThat(db.queryForList("SELECT id FROM member_token WHERE id=?", access)).hasSize(1);
        assertThat(db.queryForList("SELECT * FROM privacy_erase ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(roots);
        assertThat(db.queryForList("SELECT * FROM privacy_target ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(targets);
        assertThat(db.queryForMap("SELECT * FROM member_account WHERE id=?", actor.memberId()))
                .usingRecursiveComparison()
                .isEqualTo(account);
        assertThat(db.queryForList("SELECT * FROM member_auth_audit ORDER BY id")).isEqualTo(audit);
        assertThat(
                        db.queryForList(
                                "SELECT * FROM member_session WHERE member_id=? ORDER BY id",
                                actor.memberId()))
                .usingRecursiveComparison()
                .isEqualTo(families);
        assertThat(
                        db.queryForList(
                                "SELECT t.* FROM member_token t JOIN member_session s ON"
                                        + " s.id=t.session_id WHERE s.member_id=? ORDER BY t.id",
                                actor.memberId()))
                .usingRecursiveComparison()
                .isEqualTo(tokens);
    }

    @Test
    void lockedFirstMemberDoesNotStarveLaterEligibleMember() throws Exception {
        drain();
        var first = account("cleanup-locked-" + UUID.randomUUID() + "@example.invalid");
        var second = account("cleanup-later-" + UUID.randomUUID() + "@example.invalid");
        var older = service.authenticate(first.path("accessToken").asText(), false);
        var later = service.authenticate(second.path("accessToken").asText(), false);
        long blocked = flow(true, older.memberId());
        long eligible = flow(true, later.memberId());
        try (Connection held = db.getDataSource().getConnection();
                var statement =
                        held.prepareStatement(
                                "SELECT id FROM member_account WHERE id=? FOR UPDATE")) {
            held.setAutoCommit(false);
            statement.setLong(1, older.memberId());
            statement.executeQuery().close();
            assertThat(cleanup().cleanup(1).flows()).isEqualTo(1);
            assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", eligible))
                    .isEmpty();
            assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", blocked))
                    .hasSize(1);
            held.rollback();
        }
        assertThat(cleanup().cleanup(1).flows()).isEqualTo(1);
    }

    /**
     * 양쪽 실행 순서에서 기존 INSERT→행 잠금 프로토콜의 갱신이 행 소실 없이 끝나는지 확인한다.
     *
     * @param cleanupFirst true면 정리 삭제를 먼저 잠금 장벽에 도착시킴
     * @throws Exception 합성 DB·경쟁 호출·잠금 대기 실패 시
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void limiterRefreshAndCleanupSerializeInBothOrders(boolean cleanupFirst) throws Exception {
        drain();
        String source = "198.19.72." + (cleanupFirst ? "1" : "2");
        String hash = HexFormat.of().formatHex(crypto.limitHash("VERIFY_SOURCE_15M", source));
        db.update(
                "INSERT INTO member_auth_limit(scope,bucket_hash,window_at,hit_count,purge_at)"
                        + " VALUES ('VERIFY_SOURCE_15M',?,clock_timestamp()-interval '20"
                        + " minutes',1,clock_timestamp()-interval '5 minutes')",
                hash);
        var pool = Executors.newFixedThreadPool(2);
        try (Connection held = db.getDataSource().getConnection();
                var statement = held.createStatement()) {
            db.execute(
                    "CREATE FUNCTION public.pause_cleanup_limit() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN PERFORM pg_advisory_xact_lock(8642092); IF TG_OP='DELETE'"
                        + " THEN RETURN OLD; END IF; RETURN NEW; END $$");
            db.execute(
                    "CREATE TRIGGER tr_pause_cleanup_limit "
                            + (cleanupFirst
                                    ? "BEFORE DELETE ON member_auth_limit FOR EACH ROW"
                                    : "AFTER INSERT ON member_auth_limit FOR EACH STATEMENT")
                            + " EXECUTE FUNCTION public.pause_cleanup_limit()");
            statement.execute("SELECT pg_advisory_lock(8642092)");
            if (cleanupFirst) {
                var removing = pool.submit(() -> cleanup().cleanup(100));
                awaitWait("DELETE FROM member_auth_limit%", "advisory");
                var refreshing = pool.submit(() -> requestLimit(source));
                awaitWait("INSERT INTO member_auth_limit%", null);
                statement.execute("SELECT pg_advisory_unlock(8642092)");
                assertThat(removing.get(12, TimeUnit.SECONDS).limits()).isEqualTo(1);
                assertThat(refreshing.get(12, TimeUnit.SECONDS)).isEqualTo("FLOW_UNAVAILABLE");
            } else {
                var refreshing = pool.submit(() -> requestLimit(source));
                awaitWait("INSERT INTO member_auth_limit%", "advisory");
                var removing = pool.submit(() -> cleanup().cleanup(100));
                awaitWait("LOCK TABLE public.member_auth_limit%", null);
                statement.execute("SELECT pg_advisory_unlock(8642092)");
                assertThat(refreshing.get(12, TimeUnit.SECONDS)).isEqualTo("FLOW_UNAVAILABLE");
                assertThat(removing.get(12, TimeUnit.SECONDS).limits()).isZero();
            }
            assertThat(
                            db.queryForMap(
                                    "SELECT hit_count,purge_at>clock_timestamp() AS live FROM"
                                        + " member_auth_limit WHERE scope='VERIFY_SOURCE_15M' AND"
                                        + " bucket_hash=?",
                                    hash))
                    .containsEntry("hit_count", 1)
                    .containsEntry("live", true);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(12, TimeUnit.SECONDS)).isTrue();
            db.execute("DROP TRIGGER tr_pause_cleanup_limit ON member_auth_limit");
            db.execute("DROP FUNCTION public.pause_cleanup_limit()");
        }
    }

    @Test
    void lockedExpiredChildDoesNotRollbackAvailableArtifactsOrLimits() throws Exception {
        drain();
        long locked = flow(true, null);
        long available = flow(true, null);
        String expiredBucket = bucket(true);
        try (Connection held = db.getDataSource().getConnection();
                var statement =
                        held.prepareStatement("SELECT id FROM member_flow WHERE id=? FOR UPDATE")) {
            held.setAutoCommit(false);
            statement.setLong(1, locked);
            statement.executeQuery().close();
            assertThat(cleanup().cleanup(100))
                    .isEqualTo(new MemberAuthCleanupService.Counts(1, 0, 0, 0, 1));
            assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", available))
                    .isEmpty();
            assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", locked)).hasSize(1);
            assertThat(
                            db.queryForList(
                                    "SELECT scope FROM member_auth_limit WHERE bucket_hash=?",
                                    expiredBucket))
                    .isEmpty();
            held.rollback();
        }
        assertThat(cleanup().cleanup(100).flows()).isEqualTo(1);
    }

    @Test
    void limiterLockTimeoutReturnsNoSuccessAndPreservesBucket() throws Exception {
        drain();
        String bucket = bucket(true);
        try (Connection held = db.getDataSource().getConnection();
                var statement = held.createStatement()) {
            held.setAutoCommit(false);
            statement.execute("LOCK TABLE member_auth_limit IN ROW EXCLUSIVE MODE");
            assertThatThrownBy(() -> cleanup().cleanup(100))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            held.rollback();
        }
        assertThat(
                        db.queryForList(
                                "SELECT scope FROM member_auth_limit WHERE bucket_hash=?", bucket))
                .hasSize(1);
    }

    @Test
    void failedLimiterDeleteRollsBackItsBatchWithoutClaimingArtifactRollback() {
        drain();
        long expiredFlow = flow(true, null);
        String bucket = bucket(true);
        db.execute(
                "CREATE FUNCTION public.fail_cleanup_limit() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'synthetic failure'; END $$");
        db.execute(
                "CREATE TRIGGER tr_fail_cleanup_limit BEFORE DELETE ON member_auth_limit FOR EACH"
                        + " ROW EXECUTE FUNCTION public.fail_cleanup_limit()");
        try {
            assertThatThrownBy(() -> cleanup().cleanup(100))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", expiredFlow))
                    .isEmpty();
            assertThat(
                            db.queryForList(
                                    "SELECT scope FROM member_auth_limit WHERE bucket_hash=?",
                                    bucket))
                    .hasSize(1);
        } finally {
            db.execute("DROP TRIGGER tr_fail_cleanup_limit ON member_auth_limit");
            db.execute("DROP FUNCTION public.fail_cleanup_limit()");
        }
    }

    @Test
    void twoCleanupExecutorsCannotDoubleCount() throws Exception {
        drain();
        flow(true, null);
        bucket(true);
        var pool = Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CyclicBarrier(2);
        try {
            java.util.concurrent.Callable<MemberAuthCleanupService.Counts> run =
                    () -> {
                        start.await(5, TimeUnit.SECONDS);
                        return cleanup().cleanup(100);
                    };
            var first = pool.submit(run);
            var second = pool.submit(run);
            var a = first.get(12, TimeUnit.SECONDS);
            var b = second.get(12, TimeUnit.SECONDS);
            assertThat(a.flows() + b.flows()).isEqualTo(1);
            assertThat(a.limits() + b.limits()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 불변 최초 시각과 30일/90일 산식을 신규 family에 그대로 적용한다. */
    private long family(long member, String createdOffset, String refreshedOffset) {
        return db.queryForObject(
                "WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS n), times AS (SELECT"
                    + " n+?::interval AS c,n+?::interval AS r FROM clock) INSERT INTO"
                    + " member_session(session_key,member_id,identity_id,auth_rev,created_at,last_refresh_at,idle_until,absolute_until)"
                    + " SELECT ?,a.id,i.id,a.auth_rev,t.c,t.r,LEAST(t.r+interval '720"
                    + " hours',t.c+interval '2160 hours'), t.c+interval '2160 hours' FROM times"
                    + " t,member_account a JOIN member_identity i ON i.member_id=a.id WHERE a.id=?"
                    + " RETURNING id",
                Long.class,
                createdOffset,
                refreshedOffset,
                UUID.randomUUID(),
                member);
    }

    /** 상태·기한이 불변인 실제 REFRESH 증거를 신규 발급 시각으로 만든다. */
    private String refresh(
            long session, long generation, String state, String issuedOffset, String expiryOffset) {
        String raw = crypto.randomToken(32);
        db.update(
                "WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS n) INSERT INTO"
                    + " member_token(session_id,kind,generation,token_hash,state,issued_at,expires_at,used_at)"
                    + " SELECT ?,'REFRESH',?,?,?,n+?::interval,n+?::interval, CASE WHEN ?='USED'"
                    + " THEN n+?::interval ELSE NULL END FROM clock",
                session,
                generation,
                HexFormat.of().formatHex(crypto.tokenHash("MEMBER_REFRESH_V1", raw)),
                state,
                issuedOffset,
                expiryOffset,
                state,
                issuedOffset);
        return raw;
    }

    /** 만료되지 않은 REAUTH도 실제 복합 session FK에 묶는다. */
    private long boundFlow(long session) {
        return db.queryForObject(
                "WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS n) INSERT INTO"
                    + " member_flow(flow_key,binder_hash,purpose,state,provider,lookup_hash,proof_cipher,created_at,expires_at,verified_at,member_id,identity_id,auth_rev,session_id,reauth_purpose)"
                    + " SELECT"
                    + " ?,repeat('a',64),'REAUTH','VERIFIED','LOCAL',repeat('b',64),decode(repeat('00',32),'hex'),"
                    + " n,n+interval '5"
                    + " minutes',n,s.member_id,s.identity_id,s.auth_rev,s.id,'LOGOUT_ALL' FROM"
                    + " clock,member_session s WHERE s.id=? RETURNING id",
                Long.class,
                UUID.randomUUID(),
                session);
    }

    @Test
    void expiredUsedRefreshOfLiveFamilySurvivesAndActualReplayRevokesCurrentGeneration()
            throws Exception {
        drain();
        var issued = account("cleanup-replay-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        String old = refresh(actor.sessionId(), 500, "USED", "-31 days", "-1 day");
        var before =
                db.queryForList(
                        "SELECT * FROM member_token WHERE session_id=? AND kind='REFRESH' ORDER BY"
                                + " id",
                        actor.sessionId());
        cleanup().cleanup(100);
        assertThat(
                        db.queryForList(
                                "SELECT * FROM member_token WHERE session_id=? AND kind='REFRESH'"
                                        + " ORDER BY id",
                                actor.sessionId()))
                .usingRecursiveComparison()
                .isEqualTo(before);
        assertThatThrownBy(() -> service.refresh(old, UUID.randomUUID()))
                .hasMessageContaining("REFRESH_UNAVAILABLE");
        assertThat(
                        db.queryForObject(
                                "SELECT revoked_at IS NOT NULL FROM member_session WHERE id=?",
                                Boolean.class,
                                actor.sessionId()))
                .isTrue();
        assertThatThrownBy(
                        () ->
                                service.refresh(
                                        issued.path("refreshToken").asText(), UUID.randomUUID()))
                .hasMessageContaining("REFRESH_UNAVAILABLE");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "REVOKED,0 seconds,0 seconds",
        "IDLE,-31 days,-31 days",
        "ABSOLUTE,-91 days,-61 days"
    })
    void terminalFamiliesDrainEveryTokenStateAndLiveBoundFlowOnly(
            String reason, String created, String refreshed) throws Exception {
        drain();
        var issued = account("cleanup-terminal-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        long session = family(actor.memberId(), created, refreshed);
        long bound = boundFlow(session);
        long unrelated = flow(false, actor.memberId());
        access(session, 0, false);
        refresh(session, 0, "ISSUED", "0 seconds", "30 days");
        refresh(session, 1, "USED", "-31 days", "-1 day");
        refresh(session, 2, "REVOKED", "0 seconds", "30 days");
        if (reason.equals("REVOKED"))
            db.update(
                    "UPDATE member_session SET revoked_at=clock_timestamp(),revoke_code='LOGOUT'"
                            + " WHERE id=?",
                    session);
        var counts = cleanup().cleanup(100);
        assertThat(counts).isEqualTo(new MemberAuthCleanupService.Counts(1, 1, 3, 1, 0));
        assertThat(db.queryForList("SELECT id FROM member_session WHERE id=?", session)).isEmpty();
        assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", bound)).isEmpty();
        assertThat(db.queryForList("SELECT id FROM member_flow WHERE id=?", unrelated)).hasSize(1);
        service.authenticate(issued.path("accessToken").asText(), false);
    }

    @Test
    void globalCapsDrainOversizedFamiliesAcrossCallsWithoutDeletingReferencedSession()
            throws Exception {
        drain();
        var issued = account("cleanup-large-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        for (int family = 0; family < 2; family++) {
            long session = family(actor.memberId(), "-31 days", "-31 days");
            for (int child = 0; child < 5; child++) {
                boundFlow(session);
                access(session, child, false);
                refresh(session, child, "USED", "-31 days", "-1 day");
            }
        }
        int flows = 0, access = 0, refresh = 0, sessions = 0;
        for (int tick = 0; tick < 6; tick++) {
            var counts = cleanup().cleanup(2);
            assertThat(counts.flows()).isBetween(0, 2);
            assertThat(counts.accessTokens()).isBetween(0, 2);
            assertThat(counts.refreshTokens()).isBetween(0, 2);
            assertThat(counts.sessions()).isBetween(0, 2);
            flows += counts.flows();
            access += counts.accessTokens();
            refresh += counts.refreshTokens();
            sessions += counts.sessions();
            if (tick == 0) assertThat(counts.sessions()).isZero();
        }
        assertThat(flows).isEqualTo(10);
        assertThat(access).isEqualTo(10);
        assertThat(refresh).isEqualTo(10);
        assertThat(sessions).isEqualTo(2);
    }

    @Test
    void lockedSessionIsSkippedAndLaterTerminalFamilyMakesProgress() throws Exception {
        drain();
        var issued = account("cleanup-session-lock-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        long first = family(actor.memberId(), "-31 days", "-31 days");
        long second = family(actor.memberId(), "-31 days", "-31 days");
        refresh(first, 0, "USED", "-31 days", "-1 day");
        refresh(second, 0, "USED", "-31 days", "-1 day");
        try (Connection held = db.getDataSource().getConnection();
                var statement =
                        held.prepareStatement(
                                "SELECT id FROM member_session WHERE id=? FOR UPDATE")) {
            held.setAutoCommit(false);
            statement.setLong(1, first);
            statement.executeQuery().close();
            assertThat(cleanup().cleanup(1).sessions()).isEqualTo(1);
            assertThat(db.queryForList("SELECT id FROM member_session WHERE id=?", first))
                    .hasSize(1);
            held.rollback();
        }
        assertThat(cleanup().cleanup(1).sessions()).isEqualTo(1);
    }

    @Test
    void lockedRefreshChildKeepsItsTerminalSessionUntilLaterDrain() throws Exception {
        drain();
        var issued = account("cleanup-token-lock-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        long session = family(actor.memberId(), "-31 days", "-31 days");
        refresh(session, 0, "USED", "-31 days", "-1 day");
        access(session, 0, false);
        try (Connection held = db.getDataSource().getConnection();
                var statement =
                        held.prepareStatement(
                                "SELECT id FROM member_token WHERE session_id=? AND kind='REFRESH'"
                                        + " FOR UPDATE")) {
            held.setAutoCommit(false);
            statement.setLong(1, session);
            statement.executeQuery().close();
            var counts = cleanup().cleanup(100);
            assertThat(counts.accessTokens()).isEqualTo(1);
            assertThat(counts.refreshTokens()).isZero();
            assertThat(counts.sessions()).isZero();
            assertThat(db.queryForList("SELECT id FROM member_session WHERE id=?", session))
                    .hasSize(1);
            held.rollback();
        }
        var counts = cleanup().cleanup(100);
        assertThat(counts.refreshTokens()).isEqualTo(1);
        assertThat(counts.sessions()).isEqualTo(1);
    }

    @Test
    void finalSessionDeleteFailureRollsBackFlowsAndBothTokenCategories() throws Exception {
        drain();
        var issued = account("cleanup-rollback-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        long session = family(actor.memberId(), "-31 days", "-31 days");
        boundFlow(session);
        access(session, 0, false);
        refresh(session, 0, "USED", "-31 days", "-1 day");
        var flows = db.queryForList("SELECT * FROM member_flow WHERE session_id=?", session);
        var tokens =
                db.queryForList(
                        "SELECT * FROM member_token WHERE session_id=? ORDER BY id", session);
        db.execute(
                "CREATE FUNCTION public.fail_cleanup_session() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$");
        db.execute(
                "CREATE TRIGGER tr_fail_cleanup_session BEFORE DELETE ON member_session FOR EACH"
                        + " ROW EXECUTE FUNCTION public.fail_cleanup_session()");
        try {
            assertThatThrownBy(() -> cleanup().cleanup(100))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(db.queryForList("SELECT * FROM member_flow WHERE session_id=?", session))
                    .usingRecursiveComparison()
                    .isEqualTo(flows);
            assertThat(
                            db.queryForList(
                                    "SELECT * FROM member_token WHERE session_id=? ORDER BY id",
                                    session))
                    .usingRecursiveComparison()
                    .isEqualTo(tokens);
            assertThat(db.queryForList("SELECT id FROM member_session WHERE id=?", session))
                    .hasSize(1);
        } finally {
            db.execute("DROP TRIGGER tr_fail_cleanup_session ON member_session");
            db.execute("DROP FUNCTION public.fail_cleanup_session()");
        }
    }

    /** 기존 실제 refresh 경로의 조건부 SQL 송신 직전만 합성 장애 장벽으로 감싼다. */
    private MemberAuthService pausedRefresh(CountDownLatch reached, CountDownLatch resume) {
        JdbcTemplate intercepted =
                new JdbcTemplate(db.getDataSource()) {
                    @Override
                    public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                        if (sql.startsWith("WITH clock AS MATERIALIZED")
                                && sql.contains("UPDATE member_session s")) {
                            reached.countDown();
                            try {
                                if (!resume.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("합성 refresh 장벽 해제 실패");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                        }
                        return super.queryForList(sql, type, args);
                    }
                };
        return new MemberAuthService(
                intercepted,
                new DataSourceTransactionManager(db.getDataSource()),
                crypto,
                breached,
                gate,
                mail);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDLE", "ABSOLUTE", "TOKEN"})
    void expiryCrossingAtActualWriteCannotConsumeIssueOrExtend(String boundary) throws Exception {
        drain();
        var issued = account("cleanup-cross-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        long session =
                family(
                        actor.memberId(),
                        boundary.equals("ABSOLUTE") ? "-2160 hours +3 seconds" : "-31 days",
                        boundary.equals("IDLE") || boundary.equals("ABSOLUTE")
                                ? "-720 hours +3 seconds"
                                : "0 seconds");
        String raw =
                refresh(
                        session,
                        0,
                        "ISSUED",
                        "0 seconds",
                        boundary.equals("TOKEN") ? "3 seconds" : "30 days");
        var familyBefore = db.queryForMap("SELECT * FROM member_session WHERE id=?", session);
        var tokensBefore =
                db.queryForList(
                        "SELECT * FROM member_token WHERE session_id=? ORDER BY id", session);
        var reached = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var rotating = pausedRefresh(reached, resume);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result =
                    pool.submit(
                            () -> {
                                try {
                                    rotating.refresh(raw, UUID.randomUUID());
                                    return "ISSUED";
                                } catch (AuthException failure) {
                                    return failure.code();
                                }
                            });
            try {
                assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
                // refresh가 루트를 소유하므로 정리는 건너뛰고, DB 시각만 실제 기한을 넘긴다.
                cleanup().cleanup(100);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (!Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT s.idle_until<=clock_timestamp() OR"
                                    + " s.absolute_until<=clock_timestamp() OR"
                                    + " t.expires_at<=clock_timestamp() FROM member_session s JOIN"
                                    + " member_token t ON t.session_id=s.id WHERE s.id=? AND"
                                    + " t.kind='REFRESH'",
                                Boolean.class,
                                session))) {
                    if (System.nanoTime() >= until) throw new AssertionError("합성 DB 기한 경계 미관측");
                    Thread.sleep(10);
                }
            } finally {
                resume.countDown();
            }
            assertThat(result.get(8, TimeUnit.SECONDS)).isEqualTo("REFRESH_UNAVAILABLE");
        }
        assertThat(db.queryForMap("SELECT * FROM member_session WHERE id=?", session))
                .usingRecursiveComparison()
                .isEqualTo(familyBefore);
        assertThat(
                        db.queryForList(
                                "SELECT * FROM member_token WHERE session_id=? ORDER BY id",
                                session))
                .usingRecursiveComparison()
                .isEqualTo(tokensBefore);
    }

    @Test
    void actualRefreshHoldingRootsWinsAndCleanupCannotRemoveItsLiveFamily() throws Exception {
        drain();
        var issued = account("cleanup-refresh-wins-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        access(actor.sessionId(), 700, true);
        var reached = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var rotating = pausedRefresh(reached, resume);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result =
                    pool.submit(
                            () ->
                                    rotating.refresh(
                                            issued.path("refreshToken").asText(),
                                            UUID.randomUUID()));
            try {
                assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(cleanup().cleanup(100).accessTokens()).isZero();
            } finally {
                resume.countDown();
            }
            var current = result.get(8, TimeUnit.SECONDS);
            assertThat(cleanup().cleanup(100).accessTokens()).isEqualTo(1);
            service.authenticate(current.tokens().accessToken(), false);
            assertThat(
                            db.queryForObject(
                                    "SELECT idle_until>clock_timestamp() AND revoked_at IS NULL"
                                            + " FROM member_session WHERE id=?",
                                    Boolean.class,
                                    actor.sessionId()))
                    .isTrue();
        }
    }

    @Test
    void terminalCleanupHoldingRootsWinsAndActualRefreshCannotMintPair() throws Exception {
        drain();
        var issued = account("cleanup-wins-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        long session = family(actor.memberId(), "-31 days", "-31 days");
        String raw = refresh(session, 0, "ISSUED", "0 seconds", "30 days");
        db.execute(
                "CREATE FUNCTION public.pause_cleanup_refresh() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$ BEGIN IF OLD.session_id="
                        + session
                        + " THEN PERFORM pg_advisory_xact_lock(8642093); END IF; RETURN OLD; END"
                        + " $$");
        db.execute(
                "CREATE TRIGGER tr_pause_cleanup_refresh BEFORE DELETE ON member_token FOR EACH ROW"
                        + " EXECUTE FUNCTION public.pause_cleanup_refresh()");
        var pool = Executors.newFixedThreadPool(2);
        try (Connection held = db.getDataSource().getConnection();
                var statement = held.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(8642093)");
            var removing = pool.submit(() -> cleanup().cleanup(100));
            try {
                awaitWait("WITH due AS MATERIALIZED%", "advisory");
                var rotating =
                        pool.submit(
                                () -> {
                                    try {
                                        service.refresh(raw, UUID.randomUUID());
                                        return "ISSUED";
                                    } catch (AuthException failure) {
                                        return failure.code();
                                    }
                                });
                awaitWait("SELECT id,member_key,state,auth_rev FROM member_account%", null);
                statement.execute("SELECT pg_advisory_unlock(8642093)");
                assertThat(removing.get(8, TimeUnit.SECONDS).sessions()).isEqualTo(1);
                assertThat(rotating.get(8, TimeUnit.SECONDS)).isEqualTo("REFRESH_UNAVAILABLE");
                assertThat(
                                db.queryForList(
                                        "SELECT id FROM member_token WHERE session_id=?", session))
                        .isEmpty();
            } finally {
                statement.execute("SELECT pg_advisory_unlock(8642093)");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
            db.execute("DROP TRIGGER tr_pause_cleanup_refresh ON member_token");
            db.execute("DROP FUNCTION public.pause_cleanup_refresh()");
        }
    }

    @Test
    void qualificationPresenceInInactiveMembershipPreventsSessionDeleteAndIsNeverWritten()
            throws Exception {
        drain();
        String policyCode = playtestPolicy();
        long policy =
                db.queryForObject(
                        "SELECT id FROM privacy_policy WHERE code=?", Long.class, policyCode);
        var issued = account("cleanup-presence-" + UUID.randomUUID() + "@example.invalid");
        var actor = service.authenticate(issued.path("accessToken").asText(), false);
        var peer = account("cleanup-presence-peer-" + UUID.randomUUID() + "@example.invalid");
        UUID source =
                invitation(
                        List.of(
                                actor.memberKey(),
                                service.authenticate(peer.path("accessToken").asText(), false)
                                        .memberKey()));
        var test =
                db.queryForMap(
                        "SELECT t.*,v.story_id FROM play_test t JOIN story_version v ON"
                                + " v.id=t.version_id WHERE t.test_key=?",
                        source);
        var frozen =
                FrozenSnapshotCodec.decode(
                        db.queryForObject(
                                        "SELECT payload::text FROM review_snapshot WHERE id=?",
                                        String.class,
                                        test.get("snapshot_id"))
                                .getBytes(StandardCharsets.UTF_8));
        long room =
                db.queryForObject(
                        "WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS n) INSERT INTO"
                            + " game_room"
                            + " (room_key,purpose,mode,qualification_kind,reviewer_id,selected_member_id,story_id,version_id,snapshot_id,runtime_id,runtime_epoch,config_hash,rubric_hash,policy_id,play_rev,state,difficulty,limit_sec,max_attempts,max_hints,created_at,lobby_until)"
                            + " SELECT"
                            + " ?,'REVIEW_SOLO_QUALIFICATION','SOLO','FUNCTIONAL',?,?,?,?,?,?,?,?,?,?,0,'WAITING',?,?,?,?,n,n+interval"
                            + " '30 minutes' FROM clock RETURNING id",
                        Long.class,
                        UUID.randomUUID(),
                        invitationActor.accountId(),
                        actor.memberId(),
                        test.get("story_id"),
                        test.get("version_id"),
                        test.get("snapshot_id"),
                        test.get("runtime_id"),
                        test.get("runtime_epoch"),
                        test.get("config_hash"),
                        frozen.rubricHash(),
                        policy,
                        frozen.payload().path("sections").path("basic").path("difficulty").asInt(),
                        frozen.payload().path("sections").path("basic").path("limitSec").asInt(),
                        frozen.payload().path("policy").path("attemptLimit").asInt(),
                        frozen.payload().path("policy").path("hintsPerPerson").asInt());
        long session = family(actor.memberId(), "-31 days", "-31 days");
        db.update(
                "INSERT INTO"
                    + " game_member(room_id,member_id,identity_id,slot,policy_id,accepted_at,active_yn,last_seen_at,presence_session_id,presence_auth_rev)"
                    + " SELECT"
                    + " ?,s.member_id,s.identity_id,1,?,clock_timestamp(),false,clock_timestamp(),s.id,s.auth_rev"
                    + " FROM member_session s WHERE s.id=?",
                room,
                policy,
                session);
        var membership = db.queryForList("SELECT * FROM game_member WHERE room_id=?", room);
        var root = db.queryForMap("SELECT * FROM game_room WHERE id=?", room);
        refresh(session, 0, "USED", "-31 days", "-1 day");
        assertThat(cleanup().cleanup(100).refreshTokens()).isEqualTo(1);
        assertThat(db.queryForList("SELECT id FROM member_session WHERE id=?", session)).hasSize(1);
        assertThat(db.queryForList("SELECT * FROM game_member WHERE room_id=?", room))
                .usingRecursiveComparison()
                .isEqualTo(membership);
        assertThat(db.queryForMap("SELECT * FROM game_room WHERE id=?", room))
                .usingRecursiveComparison()
                .isEqualTo(root);
    }

    /**
     * 기존 실제 제한 호출을 사용하고 업무 인증 거절만 결과 코드로 반환한다.
     *
     * @param source 합성 숫자 IP 출처
     * @return 실제 인증 거절 코드
     * @throws AssertionError 존재하지 않는 flow가 인증된 경우
     */
    private String requestLimit(String source) {
        try {
            service.verifyEmail(
                    UUID.randomUUID(), "A".repeat(43), "bad", source, UUID.randomUUID());
            throw new AssertionError("존재하지 않는 flow가 인증되었습니다.");
        } catch (AuthException failure) {
            return failure.code();
        }
    }

    /**
     * 실제 DB 잠금 대기를 제한 시간 안에 관측하며 추측에 의한 sleep 순서를 사용하지 않는다.
     *
     * @param query pg_stat_activity 쿼리의 LIKE 패턴
     * @param event 대기 종류 또는 모든 잠금 대기를 허용하는 null
     * @throws Exception 관측 쿼리·스레드 중단 실패 시
     * @throws AssertionError 3초 안에 실제 대기가 관측되지 않은 경우
     */
    private void awaitWait(String query, String event) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            if (Boolean.TRUE.equals(
                    db.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE"
                                + " datname=current_database() AND pid<>pg_backend_pid() AND"
                                + " wait_event_type='Lock' AND query LIKE ? AND (?::text IS NULL OR"
                                + " wait_event=?))",
                            Boolean.class,
                            query,
                            event,
                            event))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("합성 PostgreSQL 잠금 대기가 관측되지 않았습니다.");
    }
}
