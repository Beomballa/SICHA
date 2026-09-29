package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.AdminAccountService;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class AdminAccountRaceIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 71));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 72));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 73));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
    }

    @Autowired JdbcTemplate db;
    @Autowired CryptoService crypto;
    @Autowired AdminSessionAdapter sessions;
    @Autowired AdminAccountService accounts;
    @Autowired DataSource dataSource;

    /** Repeats independent-TX cross revocations without counting previous synthetic winners. */
    @Test
    void crossRevocationIsSerializedAgainstCurrentAuthority() throws Exception {
        for (int iteration = 0; iteration < 8; iteration++) {
            Fixture first = manager((byte) (2 * iteration + 1));
            Fixture second = manager((byte) (2 * iteration + 2));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<String> outcomes;
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a = workers.submit(() -> revoke(first, second, ready, start));
                var b = workers.submit(() -> revoke(second, first, ready, start));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                outcomes = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("RECORDED", "AUTH_REQUIRED");
            assertThat(readyManagerCount()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT sum(edit_rev) FROM admin_account WHERE id IN (?,?)", Long.class,
                    first.principal().accountId(), second.principal().accountId())).isEqualTo(3);
            assertThat(db.queryForObject("SELECT count(*) FROM admin_auth_audit WHERE target_id IN (?,?) "
                    + "AND action='ACCOUNT_PERMISSION_REVOKED' AND outcome='COMMITTED'", Integer.class,
                    first.principal().accountId(), second.principal().accountId())).isEqualTo(1);
            assertThat(sessions.findStoredPrincipal(first.sid())).contains(first.principal());
            assertThat(sessions.findStoredPrincipal(second.sid())).contains(second.principal());
            boolean firstManages = db.queryForObject("SELECT can_manage FROM admin_account WHERE account_key=?",
                    Boolean.class, first.principal().accountKey());
            Fixture winner = firstManages ? first : second;
            Fixture loser = firstManages ? second : first;
            assertThat(accounts.getAccountDetail(winner.sid(), winner.principal(), loser.principal().accountKey())
                    .permissions()).doesNotContain("MANAGE");
            assertThatThrownBy(() -> accounts.getAccountDetail(loser.sid(), loser.principal(),
                    winner.principal().accountKey())).isInstanceOf(AuthException.class).hasMessageContaining("AUTH_REQUIRED");
            db.update("UPDATE admin_account SET active_yn=false WHERE id=?", winner.principal().accountId());
        }
        assertThat(readyManagerCount()).isZero();
    }

    /** An audit failure must release the first transaction before competing removal and fallback recheck. */
    @Test
    void auditFailureFallbackRacesAgainstIndependentManagerRevocation() throws Exception {
        db.execute("CREATE FUNCTION fail_racing_revoke_audit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.action='ACCOUNT_PERMISSION_REVOKED' THEN "
                + "PERFORM pg_advisory_xact_lock(821,2); RAISE EXCEPTION 'synthetic audit outage'; "
                + "END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_racing_revoke BEFORE INSERT ON admin_auth_audit "
                + "FOR EACH ROW EXECUTE FUNCTION fail_racing_revoke_audit()");
        try {
            for (int iteration = 0; iteration < 6; iteration++) {
                Fixture first = manager((byte) (40 + 2 * iteration));
                Fixture second = manager((byte) (41 + 2 * iteration));
                CountDownLatch ready = new CountDownLatch(1);
                CountDownLatch start = new CountDownLatch(1);
                List<String> outcomes;
                try (Connection blocker = dataSource.getConnection();
                        var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                    try (var statement = blocker.createStatement()) {
                        statement.execute("SELECT pg_advisory_lock(821,2)");
                    }
                    var initial = workers.submit(() -> revoke(first, second, new CountDownLatch(0),
                            new CountDownLatch(0)));
                    try {
                        awaitAdvisoryWait("INSERT INTO admin_auth_audit%");
                        var competing = workers.submit(() -> revoke(second, first, ready, start));
                        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                        start.countDown();
                        try (var statement = blocker.createStatement()) {
                            statement.execute("SELECT pg_advisory_unlock(821,2)");
                        }
                        outcomes = List.of(initial.get(15, TimeUnit.SECONDS), competing.get(15, TimeUnit.SECONDS));
                    } finally {
                        try (var statement = blocker.createStatement()) {
                            statement.execute("SELECT pg_advisory_unlock(821,2)");
                        }
                    }
                }
                assertThat(outcomes).containsExactlyInAnyOrder("UNCONFIRMED", "AUTH_REQUIRED");
                assertThat(readyManagerCount()).isEqualTo(1);
                assertThat(db.queryForObject("SELECT sum(edit_rev) FROM admin_account WHERE id IN (?,?)", Long.class,
                        first.principal().accountId(), second.principal().accountId())).isEqualTo(3);
                assertThat(db.queryForObject("SELECT count(*) FROM admin_auth_audit WHERE target_id IN (?,?) "
                        + "AND action='ACCOUNT_PERMISSION_REVOKED' AND outcome='COMMITTED'", Integer.class,
                        first.principal().accountId(), second.principal().accountId())).isZero();
                Fixture winner = db.queryForObject("SELECT can_manage FROM admin_account WHERE id=?", Boolean.class,
                        first.principal().accountId()) ? first : second;
                Fixture loser = winner == first ? second : first;
                assertThatThrownBy(() -> accounts.getAccountDetail(loser.sid(), loser.principal(),
                        winner.principal().accountKey())).isInstanceOf(AuthException.class)
                        .hasMessageContaining("AUTH_REQUIRED");
                db.update("UPDATE admin_account SET active_yn=false WHERE id=?", winner.principal().accountId());
            }
            assertThat(readyManagerCount()).isZero();
        } finally {
            db.execute("DROP TRIGGER fail_racing_revoke ON admin_auth_audit");
            db.execute("DROP FUNCTION fail_racing_revoke_audit()");
        }
    }

    /** Queued competing commits invalidate the fallback's live authority or expected revision. */
    @Test
    void fallbackRejectsStateChangedBetweenAuditRollbackAndRetry() throws Exception {
        db.execute("CREATE FUNCTION fail_retry_audit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.action='ACCOUNT_PERMISSION_REVOKED' THEN "
                + "PERFORM pg_advisory_xact_lock(821,2); RAISE EXCEPTION 'synthetic audit outage'; "
                + "END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_retry BEFORE INSERT ON admin_auth_audit "
                + "FOR EACH ROW EXECUTE FUNCTION fail_retry_audit()");
        try {
            for (int iteration = 0; iteration < 2; iteration++) {
                boolean loseActor = iteration == 0;
                Fixture first = manager((byte) (60 + 2 * iteration));
                Fixture second = manager((byte) (61 + 2 * iteration));
                try (Connection blocker = dataSource.getConnection();
                        var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                    try (var statement = blocker.createStatement()) {
                        statement.execute("SELECT pg_advisory_lock(821,2)");
                    }
                    var initial = workers.submit(() -> revoke(first, second, new CountDownLatch(0),
                            new CountDownLatch(0)));
                    try {
                        awaitAdvisoryWait("INSERT INTO admin_auth_audit%");
                        var competing = workers.submit(() -> {
                            try (Connection connection = dataSource.getConnection()) {
                                connection.setAutoCommit(false);
                                try (var statement = connection.createStatement()) {
                                    statement.execute("SET LOCAL lock_timeout='5s'");
                                    statement.execute("SELECT pg_advisory_xact_lock(821,1)");
                                    long id = loseActor ? first.principal().accountId() : second.principal().accountId();
                                    statement.executeUpdate("UPDATE admin_account SET edit_rev=edit_rev+1"
                                            + (loseActor ? ",can_manage=false" : "") + " WHERE id=" + id);
                                    statement.executeUpdate("UPDATE admin_credential SET auth_rev=auth_rev+1 "
                                            + "WHERE account_id=" + id);
                                    statement.executeUpdate("UPDATE admin_session SET state='REVOKED',"
                                            + "revoked_at=clock_timestamp() WHERE account_id=" + id
                                            + " AND state='ACTIVE'");
                                    connection.commit();
                                } catch (Exception failure) {
                                    connection.rollback();
                                    throw failure;
                                }
                            }
                            return "COMMITTED";
                        });
                        awaitAdvisoryWait("SELECT pg_advisory_xact_lock(821,1)%");
                        try (var statement = blocker.createStatement()) {
                            statement.execute("SELECT pg_advisory_unlock(821,2)");
                        }
                        assertThat(competing.get(15, TimeUnit.SECONDS)).isEqualTo("COMMITTED");
                        assertThat(initial.get(15, TimeUnit.SECONDS))
                                .isEqualTo(loseActor ? "AUTH_REQUIRED" : "STATE_CONFLICT");
                    } finally {
                        try (var statement = blocker.createStatement()) {
                            statement.execute("SELECT pg_advisory_unlock(821,2)");
                        }
                    }
                }
                assertThat(db.queryForObject("SELECT can_manage FROM admin_account WHERE id=?", Boolean.class,
                        second.principal().accountId())).isTrue();
                assertThat(db.queryForObject("SELECT edit_rev FROM admin_account WHERE id=?", Long.class,
                        second.principal().accountId())).isEqualTo(loseActor ? 1 : 2);
                assertThat(db.queryForObject("SELECT count(*) FROM admin_auth_audit WHERE target_id=? "
                        + "AND action='ACCOUNT_PERMISSION_REVOKED'", Integer.class, second.principal().accountId())).isZero();
                assertThat(readyManagerCount()).isEqualTo(loseActor ? 1 : 2);
                db.update("UPDATE admin_account SET active_yn=false WHERE id IN (?,?)",
                        first.principal().accountId(), second.principal().accountId());
            }
            assertThat(readyManagerCount()).isZero();
        } finally {
            db.execute("DROP TRIGGER fail_retry ON admin_auth_audit");
            db.execute("DROP FUNCTION fail_retry_audit()");
        }
    }

    private void awaitAdvisoryWait(String queryPattern) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (db.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE wait_event='advisory' "
                    + "AND query LIKE ?", Boolean.class, queryPattern)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Independent transaction never reached its advisory-lock barrier: " + queryPattern);
    }

    private String revoke(Fixture actor, Fixture target, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Revocation race did not start");
        try {
            var result = accounts.revokePermissions(actor.sid(), actor.principal(), target.principal().accountKey(),
                    "1", List.of("MANAGE"), "ACCESS_REVIEW", "verified_12345", UUID.randomUUID());
            return result.changed() ? result.auditStatus() : "UNEXPECTED_RESULT";
        } catch (AuthException denied) {
            return denied.code();
        }
    }

    private int readyManagerCount() {
        return db.queryForObject("SELECT count(*) FROM admin_account a JOIN admin_credential c "
                + "ON c.account_id=a.id WHERE a.active_yn AND a.can_manage AND c.enrolled_at IS NOT NULL "
                + "AND c.mfa_state='READY'", Integer.class);
    }

    private Fixture manager(byte seed) {
        UUID accountKey = UUID.randomUUID();
        Long id = db.queryForObject("INSERT INTO admin_account(account_key,can_manage) VALUES (?,true) RETURNING id",
                Long.class, accountKey);
        byte[] loginHash = new byte[32];
        Arrays.fill(loginHash, seed);
        Instant now = db.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
        db.update("INSERT INTO admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,"
                + "mfa_verified_at,last_step,enrolled_at,mfa_state) VALUES (?,?,?,?,?,?,?,?, 'READY')",
                id, "synthetic-cipher", loginHash, "synthetic-hash", "synthetic-mfa", now, 0L, now);
        var prepared = sessions.prepare();
        var principal = new AdminPrincipal(id, accountKey, UUID.randomUUID(), 1);
        sessions.save(prepared, principal);
        db.update("INSERT INTO admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,"
                + "expires_at,reauth_at,activated_at) VALUES (?,?,?,1,'ACTIVE',?,?,?::timestamptz + interval '8 hours',?,?)",
                principal.sessionKey(), id, crypto.sessionHash(prepared.id()), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return new Fixture(prepared.id(), principal);
    }

    private record Fixture(String sid, AdminPrincipal principal) {}
}
