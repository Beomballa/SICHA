package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.service.AuthException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 실제 LOCAL 가입과 폐기형 PG에서 단회 접수 및 명시된 물리 작업을 확인한다. */
public class MemberErasureServiceIT extends LocalMemberAuthIT {
    private static final String PASSWORD = "Synthetic-member-password-987!";
    private static final String SOURCE = "198.19.0.47";

    /** 현재 세션의 실재 LOCAL identity에 결속된 목적별 증명을 받는다. */
    private String grant(String access, String purpose) {
        UUID identity =
                db.queryForObject(
                        "SELECT i.identity_key FROM member_identity i JOIN member_session s ON"
                            + " s.identity_id=i.id JOIN member_token t ON t.session_id=s.id WHERE"
                            + " t.token_hash=?",
                        UUID.class,
                        java.util.HexFormat.of()
                                .formatHex(crypto.tokenHash("MEMBER_ACCESS_V1", access)));
        return service.reauthenticate(
                        access, purpose, identity, PASSWORD, SOURCE, UUID.randomUUID())
                .reauthGrant();
    }

    /** 고정된 실제 공동 시험에 보고서 접수와 TEST 출처 판정 시도를 생성한다. */
    private long acceptedReportAttempt(
            long testId, long proposer, long acceptor, boolean inFlight) {
        String worker = "ERASURE_" + UUID.randomUUID();
        long report =
                db.queryForObject(
                        "INSERT INTO"
                            + " test_report(report_key,test_id,snapshot_id,runtime_id,config_hash,"
                            + "runtime_epoch,source_draft_rev,proposer_id,payload_cipher,payload_hash,created_at,updated_at)"
                            + " SELECT"
                            + " gen_random_uuid(),id,snapshot_id,runtime_id,config_hash,runtime_epoch,"
                            + "draft_rev,?,decode(repeat('03',32),'hex'),repeat('b',64),clock_timestamp()-interval"
                            + " '1 second',clock_timestamp() FROM play_test WHERE id=? RETURNING"
                            + " id",
                        Long.class,
                        proposer,
                        testId);
        db.update(
                "UPDATE test_report SET"
                    + " state='ACCEPTED',accepted_by=?,accepted_at=clock_timestamp(),submit_no=1,updated_at=clock_timestamp()"
                    + " WHERE id=?",
                acceptor,
                report);
        long job =
                db.queryForObject(
                        "INSERT INTO"
                            + " grade_job(job_key,snapshot_id,runtime_id,report_id,report_hash,state,accepted_at,deadline_at,config_hash,rubric_hash)"
                            + " SELECT"
                            + " gen_random_uuid(),snapshot_id,runtime_id,id,payload_hash,'QUEUED',accepted_at,accepted_at+interval"
                            + " '120 seconds',config_hash,repeat('c',64) FROM test_report WHERE"
                            + " id=? RETURNING id",
                        Long.class,
                        report);
        db.update(
                "UPDATE grade_job SET"
                    + " state='RUNNING',call_count=1,lease_gen=1,worker_key=?,lease_until=clock_timestamp()+interval"
                    + " '60 seconds' WHERE id=?",
                worker,
                job);
        db.update(
                "INSERT INTO"
                    + " grade_attempt(job_id,attempt_no,lease_gen,worker_key,state,provider_ref)"
                    + " VALUES (?,1,1,?,'RUNNING',?)",
                job,
                worker,
                inFlight ? null : "synthetic-provider-reference");
        return job;
    }

    /** AAD가 묶인 보존 위치를 복호화해 원본의 실제 키와 비교한다. */
    private com.fasterxml.jackson.databind.JsonNode locator(String table, long id)
            throws Exception {
        byte[] bytes =
                db.queryForObject(
                        "SELECT locator_cipher FROM public." + table + " WHERE id=?",
                        byte[].class,
                        id);
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(
                        crypto.decrypt(
                                new String(bytes, StandardCharsets.UTF_8),
                                table + "/" + id + "/locator_cipher/v1"));
    }

    /** 권리행사는 단회 목적과 현재 자격을 요구하며 접수 뒤 외부 완료를 만들지 않는다. */
    @Test
    void intakeAndExplicitPhysicalConsumption() throws Exception {
        String email = "erase-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        String access = issued.path("accessToken").asText();
        long memberId = service.authenticate(access, true).memberId();
        String wrong = grant(access, "LOGOUT_ALL");
        String right = grant(access, "DELETE_ACCOUNT");
        assertThatThrownBy(
                        () ->
                                service.deleteAccount(
                                        access, wrong, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("FLOW_UNAVAILABLE");
        var accepted = service.deleteAccount(access, right, UUID.randomUUID(), UUID.randomUUID());
        assertThat(accepted.state()).isEqualTo("ACCESS_REVOKED");
        assertThatThrownBy(
                        () ->
                                service.deleteAccount(
                                        access, right, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM privacy_erase WHERE erase_key=?",
                                String.class,
                                accepted.eraseKey()))
                .isEqualTo("PROCESSING");
        var pending =
                service.status(
                        accepted.eraseKey(), accepted.receiptToken(), UUID.randomUUID(), SOURCE);
        assertThat(pending.accessRevoked()).isTrue();
        assertThat(pending.primaryDone()).isFalse();
        assertThat(pending.externalDone()).isFalse();
        assertThat(pending.backupDone()).isFalse();
        assertThat(service.processPrimary(accepted.eraseKey(), UUID.randomUUID())).isTrue();
        assertThat(service.processPrimary(accepted.eraseKey(), UUID.randomUUID())).isFalse();
        var waiting =
                service.status(
                        accepted.eraseKey(), accepted.receiptToken(), UUID.randomUUID(), SOURCE);
        assertThat(waiting.state()).isEqualTo("WAITING");
        assertThat(waiting.primaryDone()).isTrue();
        assertThat(waiting.externalDone()).isFalse();
        assertThat(waiting.backupDone()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM member_account m JOIN privacy_erase e ON"
                                        + " e.member_id=m.id WHERE e.erase_key=?",
                                Integer.class,
                                accepted.eraseKey()))
                .isZero();
        for (String table :
                java.util.List.of(
                        "member_account",
                        "member_identity",
                        "member_profile",
                        "member_session",
                        "member_flow",
                        "member_auth_audit")) {
            String column = table.equals("member_account") ? "id" : "member_id";
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM public."
                                            + table
                                            + " WHERE "
                                            + column
                                            + "=?",
                                    Integer.class,
                                    memberId))
                    .as(table)
                    .isZero();
        }
        String reauthBucket =
                java.util.HexFormat.of()
                        .formatHex(crypto.limitHash("REAUTH_MEMBER_15M", Long.toString(memberId)));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM member_auth_limit WHERE"
                                        + " scope='REAUTH_MEMBER_15M' AND bucket_hash=?",
                                Integer.class,
                                reauthBucket))
                .isZero();
        String sourceBucket =
                java.util.HexFormat.of().formatHex(crypto.limitHash("REAUTH_SOURCE_15M", SOURCE));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM member_auth_limit WHERE"
                                        + " scope='REAUTH_SOURCE_15M' AND bucket_hash=?",
                                Integer.class,
                                sourceBucket))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                service.status(
                                        accepted.eraseKey(),
                                        "A".repeat(43),
                                        UUID.randomUUID(),
                                        SOURCE))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("RECEIPT_UNAVAILABLE");
    }

    /** 필수 감사 삽입이 실패하면 계정 회수와 영수증 발급 모두 롤백된다. */
    @Test
    void mandatoryAuditFailureRollsBack() throws Exception {
        String email = "erase-audit-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        String access = issued.path("accessToken").asText();
        String recent = grant(access, "DELETE_ACCOUNT");
        db.execute(
                "CREATE OR REPLACE FUNCTION public.reject_erasure_audit_probe() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='DELETE_ACCOUNT' THEN RAISE"
                        + " EXCEPTION 'ERASURE_AUDIT_PROBE'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER reject_erasure_audit_probe BEFORE INSERT ON"
                        + " public.member_auth_audit FOR EACH ROW EXECUTE FUNCTION"
                        + " public.reject_erasure_audit_probe()");
        try {
            assertThatThrownBy(
                            () ->
                                    service.deleteAccount(
                                            access, recent, UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AuthException.class);
            assertThat(service.authenticate(access, true).memberId()).isPositive();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM privacy_erase e JOIN member_account m ON"
                                            + " m.id=e.member_id WHERE m.member_key=?",
                                    Integer.class,
                                    service.authenticate(access, true).memberKey()))
                    .isZero();
        } finally {
            db.execute("DROP TRIGGER reject_erasure_audit_probe ON public.member_auth_audit");
            db.execute("DROP FUNCTION public.reject_erasure_audit_probe()");
        }
    }

    /** 잘못된 영수증 조회도 동일한 401을 유지하며 출처 제한 창에 실제로 누적한다. */
    @Test
    void invalidReceiptHasUniformErrorAndSourceLimit() throws Exception {
        String source = "198.19.0.48";
        UUID key = UUID.randomUUID();
        for (int attempt = 0; attempt < 30; attempt++) {
            assertThatThrownBy(() -> service.status(key, "A".repeat(43), UUID.randomUUID(), source))
                    .isInstanceOf(AuthException.class)
                    .hasMessageContaining("RECEIPT_UNAVAILABLE");
        }
        assertThatThrownBy(() -> service.status(key, "A".repeat(43), UUID.randomUUID(), source))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("RATE_LIMITED");
    }

    /** DB에 봉인된 90일 영수증도 절대 만료 뒤에는 동일한 401로 거부된다. */
    @Test
    void expiredReceiptIsUnavailable() throws Exception {
        String access =
                account("erase-expired-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        long member = service.authenticate(access, true).memberId();
        long policy =
                db.queryForObject(
                        "SELECT policy_id FROM member_profile WHERE member_id=?",
                        Long.class,
                        member);
        UUID key = UUID.randomUUID();
        String token = crypto.randomToken(32);
        String hash =
                java.util.HexFormat.of()
                        .formatHex(crypto.tokenHash("MEMBER_ERASURE_RECEIPT_V1", token));
        long root =
                db.queryForObject(
                        "WITH fixed AS (SELECT clock_timestamp() AS now) INSERT INTO"
                            + " privacy_erase(erase_key,member_id,policy_id,reason_code,locator_cipher,receipt_hash,created_at,receipt_until)"
                            + " SELECT"
                            + " ?,?,?,'ACCOUNT_REQUEST',decode(repeat('01',32),'hex'),?,now-interval"
                            + " '91 days',now-interval '1 day' FROM fixed RETURNING id",
                        Long.class,
                        key,
                        member,
                        policy,
                        hash);
        db.update(
                "INSERT INTO privacy_target(erase_id,target_key,kind,source_kind,member_id) VALUES"
                        + " (?,gen_random_uuid(),'PRIMARY','MEMBER',?)",
                root,
                member);
        db.update(
                "INSERT INTO privacy_target(erase_id,target_key,kind,source_kind) VALUES"
                    + " (?,gen_random_uuid(),'BACKUP','NONE'),(?,gen_random_uuid(),'RESTORE_GUARD','NONE')",
                root,
                root);
        db.update(
                "UPDATE member_account SET state='DELETED',deleted_at=clock_timestamp() WHERE id=?",
                member);
        db.update(
                "UPDATE privacy_erase SET"
                    + " blocked_at=clock_timestamp(),sealed_at=clock_timestamp(),target_count=3,state='PROCESSING'"
                    + " WHERE id=?",
                root);
        assertThatThrownBy(() -> service.status(key, token, UUID.randomUUID(), "198.19.0.49"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("RECEIPT_UNAVAILABLE");
    }

    /** 신규 수집 중단에도 실제 현재 ACCESS 검사를 유지한 권리행사만 허용한다. */
    @Test
    void deletionRightsContinueWhenCollectionIsDisabled() throws Exception {
        String access =
                account("erase-disabled-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        configuration.setCollectionEnabled(false);
        try {
            String proof = grant(access, "DELETE_ACCOUNT");
            assertThatThrownBy(() -> grant(access, "LOGOUT_ALL")).isInstanceOf(AuthException.class);
            var accepted =
                    service.deleteAccount(access, proof, UUID.randomUUID(), UUID.randomUUID());
            assertThat(accepted.state()).isEqualTo("ACCESS_REVOKED");
            assertThatThrownBy(() -> service.authenticate(access, true))
                    .isInstanceOf(AuthException.class);
        } finally {
            configuration.setCollectionEnabled(true);
        }
    }

    /** 같은 공동 테스트의 두 접수는 실재 canonical 삭제 영수증을 공유하고 각 계정은 따로 지운다. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void twoMembersSharePhysicalTestAndKeepIndependentReceipts(boolean inFlight) throws Exception {
        playtestPolicy();
        var first = account("erase-pair-a-" + UUID.randomUUID() + "@example.invalid");
        var second = account("erase-pair-b-" + UUID.randomUUID() + "@example.invalid");
        String a = first.path("accessToken").asText();
        String b = second.path("accessToken").asText();
        long aId = service.authenticate(a, true).memberId();
        long bId = service.authenticate(b, true).memberId();
        UUID test =
                invitation(
                        java.util.List.of(
                                service.authenticate(a, true).memberKey(),
                                service.authenticate(b, true).memberKey()));
        long testId =
                db.queryForObject("SELECT id FROM play_test WHERE test_key=?", Long.class, test);
        for (String access : java.util.List.of(a, b)) {
            var notice = invitations.getConsentNotice(access, test, UUID.randomUUID());
            long rev =
                    db.queryForObject("SELECT rev FROM play_test WHERE id=?", Long.class, testId);
            invitations.acceptInvitation(
                    access,
                    test,
                    1,
                    rev,
                    false,
                    notice.policyCode(),
                    notice.noticeHash(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
        long jobId = acceptedReportAttempt(testId, aId, bId, inFlight);
        UUID jobKey =
                db.queryForObject("SELECT job_key FROM grade_job WHERE id=?", UUID.class, jobId);
        UUID reportKey =
                db.queryForObject(
                        "SELECT r.report_key FROM test_report r JOIN grade_job j"
                                + " ON j.report_id=r.id WHERE j.id=?",
                        UUID.class,
                        jobId);
        long reportId =
                db.queryForObject("SELECT report_id FROM grade_job WHERE id=?", Long.class, jobId);
        var aGrant = grant(a, "DELETE_ACCOUNT");
        var bGrant = grant(b, "DELETE_ACCOUNT");
        var acceptedA = service.deleteAccount(a, aGrant, UUID.randomUUID(), UUID.randomUUID());
        var acceptedB = service.deleteAccount(b, bGrant, UUID.randomUUID(), UUID.randomUUID());
        for (var accepted : java.util.List.of(acceptedA, acceptedB)) {
            long root =
                    db.queryForObject(
                            "SELECT id FROM privacy_erase WHERE erase_key=?",
                            Long.class,
                            accepted.eraseKey());
            long expectedMember = accepted.eraseKey().equals(acceptedA.eraseKey()) ? aId : bId;
            for (String kind : java.util.List.of("BACKUP", "RESTORE_GUARD")) {
                long target =
                        db.queryForObject(
                                "SELECT id FROM privacy_target WHERE erase_id=? AND kind=?",
                                Long.class,
                                root,
                                kind);
                var restored = locator("privacy_target", target);
                assertThat(restored.path("member").path("id").asLong()).isEqualTo(expectedMember);
                assertThat(restored.path("member").path("key").asText()).isNotBlank();
                assertThat(restored.path("identities").get(0).path("id").asLong()).isPositive();
                assertThat(restored.path("identities").get(0).path("key").asText()).isNotBlank();
                assertThat(restored.path("tests").get(0).path("key").asText())
                        .isEqualTo(test.toString());
                assertThat(restored.path("tests").get(0).path("id").asLong()).isEqualTo(testId);
                assertThat(restored.path("reports").get(0).path("key").asText())
                        .isEqualTo(reportKey.toString());
                assertThat(restored.path("reports").get(0).path("id").asLong()).isEqualTo(reportId);
                assertThat(restored.path("jobs").get(0).path("key").asText())
                        .isEqualTo(jobKey.toString());
                assertThat(restored.path("jobs").get(0).path("id").asLong()).isEqualTo(jobId);
            }
            assertThat(locator("privacy_erase", root).path("member").path("id").asLong())
                    .isEqualTo(expectedMember);
            long processor =
                    db.queryForObject(
                            "SELECT id FROM privacy_target WHERE erase_id=? AND kind='PROCESSOR'"
                                    + " AND source_job_id=? AND source_attempt_no=1",
                            Long.class,
                            root,
                            jobId);
            var captured = locator("privacy_target", processor);
            assertThat(captured.path("runtimeCode").asText()).isEqualTo("H5_SYNTHETIC");
            assertThat(captured.path("runtimeId").asLong()).isPositive();
            assertThat(captured.path("jobId").asLong()).isEqualTo(jobId);
            assertThat(captured.path("jobKey").asText()).isEqualTo(jobKey.toString());
            assertThat(captured.path("attemptNo").asInt()).isEqualTo(1);
            if (inFlight) {
                assertThat(captured.path("providerRef").isNull()).isTrue();
            } else {
                assertThat(captured.path("providerRef").asText())
                        .isEqualTo("synthetic-provider-reference");
            }
            assertThat(captured.path("workerKey").asText()).startsWith("ERASURE_");
            assertThat(captured.path("leaseGen").asLong()).isEqualTo(1);
        }
        long canonical =
                db.queryForObject(
                        "SELECT id FROM privacy_target WHERE erase_id=(SELECT id FROM privacy_erase"
                                + " WHERE erase_key=?) AND test_id=?",
                        Long.class,
                        acceptedA.eraseKey(),
                        testId);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM privacy_target WHERE kind='PRIMARY'"
                                        + " AND test_id=? AND dependency_id IS NULL",
                                Integer.class,
                                testId))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT dependency_id FROM privacy_target WHERE erase_id=(SELECT id"
                                        + " FROM privacy_erase WHERE erase_key=?) AND test_id=?",
                                Long.class,
                                acceptedB.eraseKey(),
                                testId))
                .isEqualTo(canonical);
        assertThat(service.processPrimary(acceptedB.eraseKey(), UUID.randomUUID())).isFalse();
        assertThat(service.processPrimary(acceptedA.eraseKey(), UUID.randomUUID())).isTrue();
        long dependent =
                db.queryForObject(
                        "SELECT id FROM privacy_target WHERE dependency_id=?",
                        Long.class,
                        canonical);
        nonOwnerCannotForgeDependent(dependent, canonical);
        assertThat(
                        db.queryForMap(
                                "SELECT state,result_code,confirmed_at,test_id FROM privacy_target"
                                        + " WHERE id=?",
                                canonical))
                .containsEntry("state", "COMPLETE")
                .containsEntry("result_code", "PHYSICAL_REMOVED")
                .containsKey("confirmed_at")
                .containsEntry("test_id", null);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM play_test WHERE id=?", Integer.class, testId))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_report WHERE id=?",
                                Integer.class,
                                reportId))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_job WHERE id=?", Integer.class, jobId))
                .isZero();
        for (var accepted : java.util.List.of(acceptedA, acceptedB)) {
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM privacy_target WHERE erase_id=(SELECT id"
                                            + " FROM privacy_erase WHERE erase_key=?) AND"
                                            + " kind='PROCESSOR' AND source_job_id IS NULL AND"
                                            + " source_attempt_no IS NULL AND locator_cipher IS NOT"
                                            + " NULL",
                                    Integer.class,
                                    accepted.eraseKey()))
                    .isEqualTo(1);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT test_id FROM privacy_target WHERE erase_id=(SELECT id FROM"
                                        + " privacy_erase WHERE erase_key=?) AND dependency_id=?",
                                Long.class,
                                acceptedB.eraseKey(),
                                canonical))
                .isNull();
        assertThat(service.processPrimary(acceptedB.eraseKey(), UUID.randomUUID())).isTrue();
        assertThat(
                        db.queryForMap(
                                "SELECT state,result_code,confirmed_at,dependency_id FROM"
                                    + " privacy_target WHERE erase_id=(SELECT id FROM privacy_erase"
                                    + " WHERE erase_key=?) AND kind='PRIMARY' AND"
                                    + " result_code='DEPENDENCY_CONFIRMED'",
                                acceptedB.eraseKey()))
                .containsEntry("state", "COMPLETE")
                .containsEntry("dependency_id", null)
                .containsKey("confirmed_at");
        assertThat(service.processPrimary(acceptedA.eraseKey(), UUID.randomUUID())).isTrue();
        assertThat(service.processPrimary(acceptedB.eraseKey(), UUID.randomUUID())).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM member_account WHERE id IN (?,?)",
                                Integer.class,
                                aId,
                                bId))
                .isZero();
        for (var accepted : java.util.List.of(acceptedA, acceptedB)) {
            var status =
                    service.status(
                            accepted.eraseKey(),
                            accepted.receiptToken(),
                            UUID.randomUUID(),
                            SOURCE);
            assertThat(status.state()).isEqualTo("WAITING");
            assertThat(status.primaryDone()).isTrue();
            assertThat(status.externalDone()).isFalse();
            assertThat(status.backupDone()).isFalse();
        }
    }

    /** 물리 삭제 뒤 FK가 끊긴 대상을 비소유자 UPDATE로 완료 위조할 수 없다. */
    private void nonOwnerCannotForgeDependent(long dependent, long canonical) throws Exception {
        String role = "erasure_dependent_probe";
        db.execute("CREATE ROLE " + role + " NOLOGIN");
        try {
            db.execute("GRANT USAGE ON SCHEMA public TO " + role);
            db.execute("GRANT SELECT ON public.privacy_erase TO " + role);
            db.execute("GRANT SELECT ON public.member_account TO " + role);
            db.execute("GRANT SELECT,UPDATE ON public.privacy_target TO " + role);
            db.execute(
                    "GRANT EXECUTE ON FUNCTION public.privacy_primary_dependency_allowed(bigint)"
                            + " TO "
                            + role);
            try (Connection connection = db.getDataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("SET ROLE " + role);
                try {
                    for (boolean clear : new boolean[] {false, true}) {
                        String sql =
                                "UPDATE public.privacy_target SET state='COMPLETE',"
                                        + (clear ? "dependency_id=NULL," : "")
                                        + "locator_cipher=NULL,lease_until=NULL,confirmed_at=clock_timestamp(),result_code='PHYSICAL_REMOVED'"
                                        + " WHERE id="
                                        + dependent;
                        assertThatThrownBy(() -> statement.execute(sql))
                                .isInstanceOf(java.sql.SQLException.class)
                                .hasMessageContaining(
                                        clear
                                                ? "erasure target binding immutable"
                                                : "primary completion unauthorized");
                    }
                } finally {
                    statement.execute("RESET ROLE");
                }
            }
            assertThat(
                            db.queryForObject(
                                    "SELECT dependency_id FROM privacy_target WHERE id=?",
                                    Long.class,
                                    dependent))
                    .isEqualTo(canonical);
            assertThat(
                            db.queryForObject(
                                    "SELECT state FROM privacy_target WHERE id=?",
                                    String.class,
                                    dependent))
                    .isEqualTo("PENDING");
        } finally {
            db.execute("DROP OWNED BY " + role);
            db.execute("DROP ROLE " + role);
        }
    }

    /** 접수의 계정 잠금과 canonical 소비를 두 연결에서 교차시켜 FK 대기 순환을 막는다. */
    @Test
    void concurrentDependentAcceptanceAndCanonicalConsumptionDoNotDeadlock() throws Exception {
        playtestPolicy();
        String a =
                account("erase-race-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String b =
                account("erase-race-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID test =
                invitation(
                        java.util.List.of(
                                service.authenticate(a, true).memberKey(),
                                service.authenticate(b, true).memberKey()));
        long testId =
                db.queryForObject("SELECT id FROM play_test WHERE test_key=?", Long.class, test);
        String aGrant = grant(a, "DELETE_ACCOUNT");
        String bGrant = grant(b, "DELETE_ACCOUNT");
        var acceptedA = service.deleteAccount(a, aGrant, UUID.randomUUID(), UUID.randomUUID());
        long canonical =
                db.queryForObject(
                        "SELECT t.id FROM privacy_target t JOIN privacy_erase e ON e.id=t.erase_id"
                                + " WHERE t.test_id=? AND e.erase_key=?",
                        Long.class,
                        testId,
                        acceptedA.eraseKey());
        db.execute(
                "CREATE FUNCTION public.pause_erasure_dependency_probe() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN IF NEW.dependency_id IS NOT NULL THEN"
                        + " PERFORM pg_advisory_xact_lock(8642091); END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER tr_erasure_pause_dependency BEFORE INSERT ON"
                        + " public.privacy_target FOR EACH ROW EXECUTE FUNCTION"
                        + " public.pause_erasure_dependency_probe()");
        var pool = Executors.newFixedThreadPool(2);
        try (Connection held = db.getDataSource().getConnection();
                var statement = held.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(8642091)");
            try {
                var intake =
                        pool.submit(
                                () ->
                                        service.deleteAccount(
                                                b, bGrant, UUID.randomUUID(), UUID.randomUUID()));
                awaitDatabaseWait("advisory", "privacy_target");
                var consume =
                        pool.submit(
                                () ->
                                        service.processPrimary(
                                                acceptedA.eraseKey(), UUID.randomUUID()));
                awaitDatabaseWait("account", "member_account");
                statement.execute("SELECT pg_advisory_unlock(8642091)");
                var acceptedB = intake.get(10, TimeUnit.SECONDS);
                assertThat(consume.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM play_test WHERE id=?",
                                        Integer.class,
                                        testId))
                        .isZero();
                assertThat(
                                db.queryForObject(
                                        "SELECT state FROM privacy_target WHERE id=?",
                                        String.class,
                                        canonical))
                        .isEqualTo("COMPLETE");
                assertThat(service.processPrimary(acceptedB.eraseKey(), UUID.randomUUID()))
                        .isTrue();
                assertThat(service.processPrimary(acceptedA.eraseKey(), UUID.randomUUID()))
                        .isTrue();
                assertThat(service.processPrimary(acceptedB.eraseKey(), UUID.randomUUID()))
                        .isTrue();
                for (var accepted : java.util.List.of(acceptedA, acceptedB)) {
                    var status =
                            service.status(
                                    accepted.eraseKey(),
                                    accepted.receiptToken(),
                                    UUID.randomUUID(),
                                    SOURCE);
                    assertThat(status.state()).isEqualTo("WAITING");
                    assertThat(status.primaryDone()).isTrue();
                    assertThat(status.externalDone()).isFalse();
                    assertThat(status.backupDone()).isFalse();
                }
            } finally {
                statement.execute("SELECT pg_advisory_unlock(8642091)");
            }
        } finally {
            pool.shutdownNow();
            db.execute("DROP TRIGGER tr_erasure_pause_dependency ON public.privacy_target");
            db.execute("DROP FUNCTION public.pause_erasure_dependency_probe()");
        }
    }

    /** 실제 PostgreSQL 대기 이벤트로 교차 지점을 확인하며 고정 시간 추측으로 통과하지 않는다. */
    private void awaitDatabaseWait(String event, String queryPart) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Integer count =
                    db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND"
                                + " (?<>'advisory' OR wait_event='advisory') AND query LIKE ? AND"
                                + " pid<>pg_backend_pid()",
                            Integer.class,
                            event,
                            "%" + queryPart + "%");
            if (count != null && count > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("실제 공동 시험 잠금 교차를 관찰하지 못했습니다: " + event);
    }

    /** 한쪽 회원만 탈퇴하면 살아남은 회원의 최초 노출 시각과 회원 자격은 그대로다. */
    @Test
    void sharedTestPreservesSurvivorExposure() throws Exception {
        playtestPolicy();
        var first = account("erase-single-a-" + UUID.randomUUID() + "@example.invalid");
        var second = account("erase-single-b-" + UUID.randomUUID() + "@example.invalid");
        String a = first.path("accessToken").asText();
        String b = second.path("accessToken").asText();
        long bId = service.authenticate(b, true).memberId();
        UUID test =
                invitation(
                        java.util.List.of(
                                service.authenticate(a, true).memberKey(),
                                service.authenticate(b, true).memberKey()));
        long testId =
                db.queryForObject("SELECT id FROM play_test WHERE test_key=?", Long.class, test);
        long story =
                db.queryForObject(
                        "SELECT v.story_id FROM play_test t JOIN story_version v ON"
                                + " v.id=t.version_id WHERE t.id=?",
                        Long.class,
                        testId);
        db.update(
                "INSERT INTO test_exposure(story_id,member_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                story,
                bId);
        var exposure =
                db.queryForObject(
                        "SELECT first_exposed_at FROM test_exposure WHERE story_id=? AND"
                                + " member_id=?",
                        java.sql.Timestamp.class,
                        story,
                        bId);
        var accepted =
                service.deleteAccount(
                        a, grant(a, "DELETE_ACCOUNT"), UUID.randomUUID(), UUID.randomUUID());
        assertThat(service.processPrimary(accepted.eraseKey(), UUID.randomUUID())).isTrue();
        assertThat(service.processPrimary(accepted.eraseKey(), UUID.randomUUID())).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT first_exposed_at FROM test_exposure WHERE story_id=? AND"
                                        + " member_id=?",
                                java.sql.Timestamp.class,
                                story,
                                bId))
                .isEqualTo(exposure);
        assertThat(service.authenticate(b, true).memberId()).isEqualTo(bId);
    }
}
