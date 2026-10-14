package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberAuthCleanupService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 폐기 PostgreSQL의 실제 가드·서비스·잠금으로 만료 보안 자료의 첫 정리 계층만 검사한다. */
public class MemberAuthCleanupIT extends LocalMemberAuthIT {
    private static final String PASSWORD = "Synthetic-member-password-987!";

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
            if (counts.equals(new MemberAuthCleanupService.Counts(0, 0, 0))) return;
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

        assertThat(cleanup().cleanup(100)).isEqualTo(new MemberAuthCleanupService.Counts(2, 1, 1));
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
        assertThat(cleanup().cleanup(100)).isEqualTo(new MemberAuthCleanupService.Counts(0, 0, 0));
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
        assertThat(cleanup().cleanup(2)).isEqualTo(new MemberAuthCleanupService.Counts(2, 2, 2));
        assertThat(cleanup().cleanup(2)).isEqualTo(new MemberAuthCleanupService.Counts(1, 1, 1));
        assertThat(cleanup().cleanup(2)).isEqualTo(new MemberAuthCleanupService.Counts(0, 0, 0));
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
                .isEqualTo(account);
        assertThat(db.queryForList("SELECT * FROM member_auth_audit ORDER BY id")).isEqualTo(audit);
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
                    .isEqualTo(new MemberAuthCleanupService.Counts(1, 0, 1));
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
