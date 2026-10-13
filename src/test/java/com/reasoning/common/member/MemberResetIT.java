package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberAuthService.FlowStart;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 폐기 PostgreSQL·루프백 메일에서 복구의 단회·회수·목적·감사 경계를 검사한다. */
public class MemberResetIT extends LocalMemberAuthIT {
    private static final String NEW_PASSWORD = "Synthetic-new-password-987!";

    /** 실제 격리 SMTP에서 수신한 코드만 사용하며 비밀을 로그에 출력하지 않는다. */
    private FlowStart verifiedReset(String email) throws Exception {
        var smtp = (GreenMail) ReflectionTestUtils.getField(LocalMemberAuthIT.class, "SMTP");
        assertThat(smtp).isNotNull();
        int expected = smtp.getReceivedMessages().length + 1;
        var flow = service.startReset(email, "198.19.0.1", UUID.randomUUID());
        assertThat(smtp.waitForIncomingEmail(5000, expected)).isTrue();
        var message = smtp.getReceivedMessages()[expected - 1];
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(email);
        var code =
                java.util.regex.Pattern.compile("[0-9]{8}")
                        .matcher(message.getContent().toString());
        assertThat(code.find()).isTrue();
        var verified =
                service.verifyEmail(
                        flow.flowKey(),
                        flow.flowBinder(),
                        code.group(),
                        "198.19.0.1",
                        UUID.randomUUID());
        assertThat(verified.expiresAt()).isEqualTo(flow.expiresAt());
        return flow;
    }

    /** 확인만으로 세션을 회수하지 않고 완료할 때 모든 family와 다른 복구를 원자적으로 닫는다. */
    @Test
    void resetRevokesAllFamiliesAndOtherFlowsWithoutIssuingTokens() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@example.invalid";
        var first = account(email);
        var second =
                service.login(
                        email, "Synthetic-member-password-987!", "198.19.0.2", UUID.randomUUID());
        var flow = verifiedReset(email);
        var stale = verifiedReset(email);
        assertThat(service.getMe(first.path("accessToken").asText(), UUID.randomUUID()))
                .isNotNull();
        var result =
                service.completeReset(
                        flow.flowKey(),
                        flow.flowBinder(),
                        NEW_PASSWORD,
                        UUID.randomUUID(),
                        UUID.randomUUID());
        assertThat(result.state()).isEqualTo("LOGIN_REQUIRED");
        for (String access :
                new String[] {first.path("accessToken").asText(), second.tokens().accessToken()}) {
            assertThatThrownBy(() -> service.getMe(access, UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
        }
        assertThatThrownBy(() -> service.refresh(second.tokens().refreshToken(), UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        for (var rejected : new FlowStart[] {flow, stale}) {
            assertThatThrownBy(
                            () ->
                                    service.completeReset(
                                            rejected.flowKey(),
                                            rejected.flowBinder(),
                                            NEW_PASSWORD,
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
        }
        assertThatThrownBy(
                        () ->
                                service.login(
                                        email,
                                        "Synthetic-member-password-987!",
                                        "198.19.0.2",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        var login = service.login(email, NEW_PASSWORD, "198.19.0.2", UUID.randomUUID());
        assertThat(login.principal().authRev()).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                        + " revoked_at IS NULL",
                                Integer.class,
                                login.principal().memberId()))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM member_auth_audit WHERE member_id=? AND"
                                    + " action='RESET_COMPLETE' AND result_code='PASSWORD_RESET'",
                                Integer.class,
                                login.principal().memberId()))
                .isEqualTo(1);
    }

    /** 목적 교환과 현재 세대 변경은 복구 권한을 만들지 않는다. */
    @Test
    void resetRejectsPurposeSubstitutionAndStaleRevision() throws Exception {
        String email = "reset-stale-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        var flow = verifiedReset(email);
        assertThatThrownBy(
                        () ->
                                service.completeSignup(
                                        flow.flowKey(),
                                        flow.flowBinder(),
                                        NEW_PASSWORD,
                                        "합성회원",
                                        "wrong",
                                        "a".repeat(64),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        Long member =
                db.queryForObject(
                        "SELECT member_id FROM member_session WHERE session_key=?",
                        Long.class,
                        UUID.fromString(issued.path("sessionKey").asText()));
        db.update("UPDATE member_account SET auth_rev=auth_rev+1 WHERE id=?", member);
        assertThatThrownBy(
                        () ->
                                service.completeReset(
                                        flow.flowKey(),
                                        flow.flowBinder(),
                                        NEW_PASSWORD,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM member_flow WHERE flow_key=?",
                                String.class,
                                flow.flowKey()))
                .isEqualTo("FAILED");
        var signup =
                service.startSignup(
                        "other-" + UUID.randomUUID() + "@example.invalid",
                        "198.19.0.3",
                        UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                service.completeReset(
                                        signup.flowKey(),
                                        signup.flowBinder(),
                                        NEW_PASSWORD,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
    }

    /** 회원 존재와 무관한 봉투와 제한을 유지하며 dummy flow는 복구할 수 없다. */
    @Test
    void unknownAddressDoesNotSendMailOrGainAuthority() throws Exception {
        var smtp = (GreenMail) ReflectionTestUtils.getField(LocalMemberAuthIT.class, "SMTP");
        assertThat(smtp).isNotNull();
        int before = smtp.getReceivedMessages().length;
        String email = "missing-" + UUID.randomUUID() + "@example.invalid";
        var flow = service.startReset(email, "198.19.0.4", UUID.randomUUID());
        assertThat(flow.flowBinder()).hasSize(43);
        assertThat(smtp.getReceivedMessages()).hasSize(before);
        assertThat(
                        db.queryForObject(
                                "SELECT member_id IS NULL AND identity_id IS NULL AND auth_rev IS"
                                        + " NULL FROM member_flow WHERE flow_key=?",
                                Boolean.class,
                                flow.flowKey()))
                .isTrue();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(
                            () ->
                                    service.verifyEmail(
                                            flow.flowKey(),
                                            flow.flowBinder(),
                                            "wrong",
                                            "198.19.0.4",
                                            UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM member_flow WHERE flow_key=?",
                                String.class,
                                flow.flowKey()))
                .isEqualTo("FAILED");
        service.startReset(email, "198.19.0.4", UUID.randomUUID());
        service.startReset(email, "198.19.0.4", UUID.randomUUID());
        assertThatThrownBy(() -> service.startReset(email, "198.19.0.4", UUID.randomUUID()))
                .isInstanceOf(com.reasoning.common.member.auth.MemberAuthService.RateLimited.class);
    }

    /** 필수 감사 실패는 비밀번호·세대·세션·단회 소비 전체를 롤백한다. */
    @Test
    void resetAuditFailureRollsBackAllCredentialChanges() throws Exception {
        String email = "reset-audit-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        var flow = verifiedReset(email);
        db.execute(
                "CREATE FUNCTION reject_reset_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF NEW.action='RESET_COMPLETE' THEN RAISE EXCEPTION 'synthetic reset audit"
                    + " failure'; END IF; RETURN NEW; END; $$");
        db.execute(
                "CREATE TRIGGER reject_reset_audit BEFORE INSERT ON member_auth_audit FOR EACH ROW"
                        + " EXECUTE FUNCTION reject_reset_audit()");
        try {
            assertThatThrownBy(
                            () ->
                                    service.completeReset(
                                            flow.flowKey(),
                                            flow.flowBinder(),
                                            NEW_PASSWORD,
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
            assertThat(service.getMe(issued.path("accessToken").asText(), UUID.randomUUID()))
                    .isNotNull();
            assertThat(
                            db.queryForObject(
                                    "SELECT state FROM member_flow WHERE flow_key=?",
                                    String.class,
                                    flow.flowKey()))
                    .isEqualTo("VERIFIED");
        } finally {
            db.execute("DROP TRIGGER reject_reset_audit ON member_auth_audit");
            db.execute("DROP FUNCTION reject_reset_audit()");
        }
        assertThat(
                        service.completeReset(
                                        flow.flowKey(),
                                        flow.flowBinder(),
                                        NEW_PASSWORD,
                                        UUID.randomUUID(),
                                        UUID.randomUUID())
                                .state())
                .isEqualTo("LOGIN_REQUIRED");
    }

    /** 독립 거래의 동시 완료는 하나만 성공하고 해시·세대가 두 번 바뀌지 않는다. */
    @Test
    void concurrentResetCompletionConsumesExactlyOnce() throws Exception {
        String email = "reset-race-" + UUID.randomUUID() + "@example.invalid";
        account(email);
        var flow = verifiedReset(email);
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> complete =
                    () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            service.completeReset(
                                    flow.flowKey(),
                                    flow.flowBinder(),
                                    NEW_PASSWORD,
                                    UUID.randomUUID(),
                                    UUID.randomUUID());
                            return true;
                        } catch (AuthException failure) {
                            return false;
                        }
                    };
            var a = workers.submit(complete);
            var b = workers.submit(complete);
            assertThat(java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        service.login(email, NEW_PASSWORD, "198.19.0.5", UUID.randomUUID())
                                .principal()
                                .authRev())
                .isEqualTo(1);
    }
}
