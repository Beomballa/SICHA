package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberAuthService.GrantView;
import com.reasoning.common.member.auth.MemberAuthService.RateLimited;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 폐기 PostgreSQL과 합성 계정에서 최근 LOCAL 비밀번호 증명의 목적·세션·감사를 검사한다. */
public class MemberRecentAuthIT extends LocalMemberAuthIT {
    private static final String OLD = "Synthetic-member-password-987!";
    private static final String NEW = "Synthetic-recent-password-456!";
    private static final String SOURCE = "198.19.0.37";

    /** 현재 family에 결속된 수단 식별자를 DB의 공개 키로 확인한다. */
    private UUID identity(String access) {
        return db.queryForObject(
                "SELECT i.identity_key FROM member_identity i JOIN member_session s ON"
                    + " s.identity_id=i.id JOIN member_token t ON t.session_id=s.id WHERE"
                    + " t.token_hash=?",
                UUID.class,
                java.util.HexFormat.of().formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", access)));
    }

    private GrantView grant(String access, String purpose) {
        return service.reauthenticate(
                access, purpose, identity(access), OLD, SOURCE, UUID.randomUUID());
    }

    /** 성공한 변경은 토큰을 반환하지 않고 모든 세션·다른 증명을 회수한다. */
    @Test
    void passwordChangeConsumesGrantAndRevokesFamilies() throws Exception {
        String email = "recent-change-" + UUID.randomUUID() + "@example.invalid";
        var first = account(email);
        String access = first.path("accessToken").asText();
        var second = service.login(email, OLD, SOURCE, UUID.randomUUID());
        var grant = grant(access, "PASSWORD_CHANGE");
        var other = grant(access, "LOGOUT_ALL");
        UUID key = UUID.fromString(grant.reauthGrant().substring(0, 36));
        var stored =
                db.queryForMap(
                        "SELECT"
                            + " flow_key,binder_hash,purpose,state,attempt_count,code_hash,session_id,reauth_purpose,created_at,verified_at,expires_at,proof_cipher"
                            + " FROM member_flow WHERE flow_key=?",
                        key);
        assertThat(stored.get("purpose")).isEqualTo("REAUTH");
        assertThat(stored.get("state")).isEqualTo("VERIFIED");
        assertThat(stored.get("attempt_count")).isEqualTo(0);
        assertThat(stored.get("code_hash")).isNull();
        assertThat(stored.get("reauth_purpose")).isEqualTo("PASSWORD_CHANGE");
        assertThat(stored.get("created_at")).isEqualTo(stored.get("verified_at"));
        assertThat(
                        Duration.between(
                                ((Timestamp) stored.get("created_at")).toInstant(),
                                ((Timestamp) stored.get("expires_at")).toInstant()))
                .isGreaterThan(Duration.ZERO)
                .isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(stored.get("binder_hash").toString())
                .doesNotContain(grant.reauthGrant().substring(37));
        assertThat(
                        new String(
                                (byte[]) stored.get("proof_cipher"),
                                java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(grant.reauthGrant(), OLD);
        assertThat(
                        service.changePassword(
                                        access,
                                        grant.reauthGrant(),
                                        NEW,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .state())
                .isEqualTo("LOGIN_REQUIRED");
        assertThatThrownBy(() -> service.getMe(access, UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.getMe(second.tokens().accessToken(), UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.login(email, OLD, SOURCE, UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThat(service.login(email, NEW, SOURCE, UUID.randomUUID()).principal().authRev())
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM member_flow WHERE flow_key=?",
                                String.class,
                                UUID.fromString(other.reauthGrant().substring(0, 36))))
                .isEqualTo("FAILED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM member_auth_audit WHERE member_id=(SELECT"
                                    + " member_id FROM member_session WHERE session_key=?) AND"
                                    + " action='PASSWORD_CHANGE' AND"
                                    + " result_code='PASSWORD_CHANGED'",
                                Integer.class,
                                UUID.fromString(first.path("sessionKey").asText())))
                .isEqualTo(1);
    }

    /** 틀린 암호·수단·목적과 다른 family의 시도는 권한을 만들거나 정당한 증명을 소비하지 않는다. */
    @Test
    void invalidInputAndFamilyIsolation() throws Exception {
        String email = "recent-isolation-" + UUID.randomUUID() + "@example.invalid";
        var first = account(email);
        String access = first.path("accessToken").asText();
        var second = service.login(email, OLD, SOURCE, UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                service.reauthenticate(
                                        access,
                                        "PASSWORD_CHANGE",
                                        identity(access),
                                        "wrong-password",
                                        SOURCE,
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(
                        () ->
                                service.reauthenticate(
                                        access,
                                        "PASSWORD_CHANGE",
                                        UUID.randomUUID(),
                                        OLD,
                                        SOURCE,
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(
                        () ->
                                service.reauthenticate(
                                        access,
                                        "DELETE",
                                        identity(access),
                                        OLD,
                                        SOURCE,
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        var change = grant(access, "PASSWORD_CHANGE");
        var logout = grant(access, "LOGOUT_ALL");
        assertThatThrownBy(
                        () ->
                                service.logoutAll(
                                        access,
                                        change.reauthGrant(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(
                        () ->
                                service.changePassword(
                                        second.tokens().accessToken(),
                                        change.reauthGrant(),
                                        NEW,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(
                        () ->
                                service.changePassword(
                                        access,
                                        logout.reauthGrant(),
                                        NEW,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM member_flow WHERE flow_key=?",
                                String.class,
                                UUID.fromString(change.reauthGrant().substring(0, 36))))
                .isEqualTo("VERIFIED");
        assertThat(
                        service.changePassword(
                                        access,
                                        change.reauthGrant(),
                                        NEW,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .state())
                .isEqualTo("LOGIN_REQUIRED");
    }

    /** 기존 family에서 refresh한 새 접근 토큰도 증명을 사용할 수 있다. */
    @Test
    void refreshWithinFamilyKeepsGrant() throws Exception {
        String email = "recent-refresh-" + UUID.randomUUID() + "@example.invalid";
        var first = account(email);
        String access = first.path("accessToken").asText();
        var grant = grant(access, "LOGOUT_ALL");
        var refreshed = service.refresh(first.path("refreshToken").asText(), UUID.randomUUID());
        assertThat(
                        service.logoutAll(
                                        refreshed.tokens().accessToken(),
                                        grant.reauthGrant(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .state())
                .isEqualTo("LOGIN_REQUIRED");
        assertThatThrownBy(
                        () -> service.refresh(refreshed.tokens().refreshToken(), UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThat(service.login(email, OLD, SOURCE, UUID.randomUUID()).principal().authRev())
                .isEqualTo(1);
    }

    /** 현재 회수 세대와 비밀번호 해시가 바뀌면 발급된 증명도 사용할 수 없다. */
    @Test
    void staleRevisionAndPasswordHashAreDenied() throws Exception {
        String email = "recent-stale-" + UUID.randomUUID() + "@example.invalid";
        var first = account(email);
        String access = first.path("accessToken").asText();
        var grant = grant(access, "LOGOUT_ALL");
        long member =
                db.queryForObject(
                        "SELECT member_id FROM member_session WHERE session_key=?",
                        Long.class,
                        UUID.fromString(first.path("sessionKey").asText()));
        db.update("UPDATE member_account SET auth_rev=auth_rev+1 WHERE id=?", member);
        assertThatThrownBy(
                        () ->
                                service.logoutAll(
                                        access,
                                        grant.reauthGrant(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        String email2 = "recent-hash-" + UUID.randomUUID() + "@example.invalid";
        var second = account(email2);
        String access2 = second.path("accessToken").asText();
        var hashGrant = grant(access2, "LOGOUT_ALL");
        long member2 =
                db.queryForObject(
                        "SELECT member_id FROM member_session WHERE session_key=?",
                        Long.class,
                        UUID.fromString(second.path("sessionKey").asText()));
        var alternate = service.login(email2, OLD, SOURCE, UUID.randomUUID());
        assertThat(alternate).isNotNull();
        db.update(
                "UPDATE member_identity SET password_hash=(SELECT password_hash FROM"
                    + " member_identity WHERE member_id=?) WHERE member_id=?",
                member,
                member2);
        assertThatThrownBy(
                        () ->
                                service.logoutAll(
                                        access2,
                                        hashGrant.reauthGrant(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
    }

    /** 인증된 회원 키에만 제한 창을 누적하고 여섯 번째 잘못된 비밀번호는 거절한다. */
    @Test
    void memberRateLimitCountsWrongPasswords() throws Exception {
        var issued = account("recent-limit-" + UUID.randomUUID() + "@example.invalid");
        String access = issued.path("accessToken").asText();
        UUID boundIdentity = identity(access);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(
                            () ->
                                    service.reauthenticate(
                                            access,
                                            "LOGOUT_ALL",
                                            boundIdentity,
                                            "Synthetic-wrong-password-789!",
                                            SOURCE,
                                            UUID.randomUUID()))
                    .isInstanceOf(AuthException.class)
                    .isNotInstanceOf(RateLimited.class);
        }
        assertThatThrownBy(
                        () ->
                                service.reauthenticate(
                                        access,
                                        "LOGOUT_ALL",
                                        boundIdentity,
                                        OLD,
                                        SOURCE,
                                        UUID.randomUUID()))
                .isInstanceOf(RateLimited.class);
    }

    /** 감사 실패 시 증명 소비·회수·해시 변경이 전부 롤백된다. */
    @Test
    void mandatoryAuditFailureRollsBack() throws Exception {
        String email = "recent-audit-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        String access = issued.path("accessToken").asText();
        var grant = grant(access, "PASSWORD_CHANGE");
        db.execute(
                "CREATE FUNCTION reject_recent_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF NEW.action='PASSWORD_CHANGE' THEN RAISE EXCEPTION 'synthetic recent"
                    + " audit failure'; END IF; RETURN NEW; END; $$");
        db.execute(
                "CREATE TRIGGER reject_recent_audit BEFORE INSERT ON member_auth_audit FOR EACH ROW"
                        + " EXECUTE FUNCTION reject_recent_audit()");
        try {
            assertThatThrownBy(
                            () ->
                                    service.changePassword(
                                            access,
                                            grant.reauthGrant(),
                                            NEW,
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
            assertThat(service.getMe(access, UUID.randomUUID())).isNotNull();
            assertThat(
                            db.queryForObject(
                                    "SELECT state FROM member_flow WHERE flow_key=?",
                                    String.class,
                                    UUID.fromString(grant.reauthGrant().substring(0, 36))))
                    .isEqualTo("VERIFIED");
        } finally {
            db.execute("DROP TRIGGER reject_recent_audit ON member_auth_audit");
            db.execute("DROP FUNCTION reject_recent_audit()");
        }
        assertThat(
                        service.changePassword(
                                        access,
                                        grant.reauthGrant(),
                                        NEW,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .state())
                .isEqualTo("LOGIN_REQUIRED");
    }

    /** 동시에 완료해도 한 거래만 성공하며 회수 세대는 한 번만 올라간다. */
    @Test
    void simultaneousUseIsOneShot() throws Exception {
        String email = "recent-race-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        String access = issued.path("accessToken").asText();
        var grant = grant(access, "LOGOUT_ALL");
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> use =
                    () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            service.logoutAll(
                                    access,
                                    grant.reauthGrant(),
                                    UUID.randomUUID(),
                                    UUID.randomUUID());
                            return true;
                        } catch (AuthException failure) {
                            return false;
                        }
                    };
            var a = workers.submit(use);
            var b = workers.submit(use);
            assertThat(java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.login(email, OLD, SOURCE, UUID.randomUUID()).principal().authRev())
                .isEqualTo(1);
    }

    /** 실제 REAUTH의 session·목적 모양과 기존 값 불변을 DB가 독립 강제한다. */
    @Test
    void reauthDatabaseRejectsPartialBindingsPurposeDriftAndForeignSession() throws Exception {
        String email = "recent-schema-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        var foreign = account("recent-foreign-" + UUID.randomUUID() + "@example.invalid");
        var granted = grant(issued.path("accessToken").asText(), "PASSWORD_CHANGE");
        UUID key = UUID.fromString(granted.reauthGrant().substring(0, 36));
        Long foreignSession =
                db.queryForObject(
                        "SELECT id FROM member_session WHERE session_key=?",
                        Long.class,
                        UUID.fromString(foreign.path("sessionKey").asText()));
        for (String change :
                java.util.List.of(
                        "reauth_purpose='LOGOUT_ALL'",
                        "session_id=NULL",
                        "auth_rev=auth_rev+1",
                        "expires_at=expires_at+interval '1 second'")) {
            assertThatThrownBy(
                            () ->
                                    db.update(
                                            "UPDATE member_flow SET "
                                                    + change
                                                    + " WHERE flow_key=?",
                                            key))
                    .hasMessageContaining("member_flow immutable binding");
        }
        String clone =
                "INSERT INTO"
                    + " member_flow(flow_key,binder_hash,purpose,state,provider,lookup_hash,proof_cipher,attempt_count,created_at,expires_at,verified_at,member_id,identity_id,auth_rev,session_id,reauth_purpose)"
                    + " SELECT"
                    + " gen_random_uuid(),binder_hash,purpose,state,provider,lookup_hash,proof_cipher,attempt_count,created_at,expires_at,verified_at,member_id,identity_id,auth_rev,%s,%s"
                    + " FROM member_flow WHERE flow_key=?";
        assertThatThrownBy(() -> db.update(clone.formatted("NULL", "reauth_purpose"), key))
                .hasMessageContaining("ck_mf_binding");
        assertThatThrownBy(() -> db.update(clone.formatted("session_id", "NULL"), key))
                .hasMessageContaining("ck_mf_binding");
        assertThatThrownBy(() -> db.update(clone.formatted(foreignSession, "reauth_purpose"), key))
                .hasMessageContaining("fk_mf_session");
    }

    /** 합성 family의 절대 기한이 5분보다 짧으면 증명은 그 기한을 넘지 않는다. */
    @Test
    void grantExpiryIsClampedToFamilyAbsolute() throws Exception {
        String email = "recent-expiry-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        Instant absolute =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(90);
        Instant created = absolute.minusSeconds(7776000);
        // 이미 발급한 family의 불변 시각을 우회하지 않고 별도 합성 과거 family를 생성한다.
        Long family =
                db.queryForObject(
                        "INSERT INTO"
                            + " member_session(session_key,member_id,identity_id,auth_rev,created_at,absolute_until,last_refresh_at,idle_until)"
                            + " SELECT gen_random_uuid(),member_id,identity_id,auth_rev,?,?,?,?"
                            + " FROM member_session WHERE session_key=? RETURNING id",
                        Long.class,
                        Timestamp.from(created),
                        Timestamp.from(absolute),
                        Timestamp.from(absolute.minusSeconds(3600)),
                        Timestamp.from(absolute),
                        UUID.fromString(issued.path("sessionKey").asText()));
        String access = crypto.randomToken(32);
        db.update(
                "INSERT INTO"
                    + " member_token(session_id,kind,generation,token_hash,state,issued_at,expires_at)"
                    + " VALUES (?,'ACCESS',0,?,'ISSUED',clock_timestamp(),?)",
                family,
                java.util.HexFormat.of().formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", access)),
                Timestamp.from(absolute));
        var grant = grant(access, "LOGOUT_ALL");
        assertThat(grant.expiresAt()).isEqualTo(absolute);
    }
}
