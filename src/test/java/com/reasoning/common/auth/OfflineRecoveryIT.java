package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.service.AdminOfflineBlockService;
import com.reasoning.common.auth.service.AdminOfflineReactivateService;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.RecoveryService;
import com.reasoning.common.auth.service.TotpService;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OfflineRecoveryIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));

    static final KeyPair signer;
    static final Path approvals;
    static final Path publicKey;

    static {
        try {
            signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            approvals = Files.createTempFile("h0-offline-approved-", ".txt");
            publicKey = Files.createTempFile("h0-offline-public-", ".txt");
            Files.setPosixFilePermissions(
                    approvals,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.writeString(
                    publicKey,
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(signer.getPublic().getEncoded()));
            approvals.toFile().deleteOnExit();
            publicKey.toFile().deleteOnExit();
        } catch (Exception e) {
            throw new IllegalStateException("Test signer unavailable", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 1));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 2));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 3));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
        properties.add("app.auth.offline-approvals-file", approvals::toString);
        properties.add("app.auth.offline-approval-public-key-file", publicKey::toString);
    }

    @Autowired EnrollmentService enrollment;
    @Autowired AdminOfflineBlockService block;
    @Autowired AdminOfflineReactivateService reactivate;
    @Autowired RecoveryService recovery;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @Test
    void offlineRecoveryAndEmergencyBlockRequireIndependentApproval() throws Exception {
        var initial =
                enrollment.createBootstrap(
                        "offline01", "verified_01", "operator_01", UUID.randomUUID());
        var grant =
                enrollment
                        .exchange(initial.code(), null, "test-source", UUID.randomUUID())
                        .cookie()
                        .value();
        enrollment.setPassword(grant, "Separate-Offline-Password-1234", UUID.randomUUID());
        var mfa = enrollment.prepareMfa(grant, UUID.randomUUID());
        long step = Instant.now().getEpochSecond() / 30 - 1;
        enrollment.verifyMfa(
                grant,
                totp.code(mfa.secret(), Instant.ofEpochSecond(step * 30)),
                "test-source",
                UUID.randomUUID());
        enrollment.complete(grant, UUID.randomUUID());
        UUID accountKey = jdbc.queryForObject("select account_key from admin_account", UUID.class);
        assertThatThrownBy(
                        () ->
                                recovery.issueOffline(
                                        accountKey,
                                        "PASSWORD_RESET",
                                        "verified_02",
                                        "approval_02",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        byte[] assertion =
                (accountKey
                                + "|PASSWORD_RESET|verified_02|approval_02|operator_01|"
                                + Instant.now().plusSeconds(120).getEpochSecond())
                        .getBytes(StandardCharsets.US_ASCII);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(assertion);
        Files.writeString(
                approvals,
                Base64.getUrlEncoder().withoutPadding().encodeToString(assertion)
                        + "\t"
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
        var issued =
                recovery.issueOffline(
                        accountKey,
                        "PASSWORD_RESET",
                        "verified_02",
                        "approval_02",
                        "operator_01",
                        UUID.randomUUID());
        assertThat(issued.code()).hasSize(43);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from admin_auth_audit where actor_kind='OFFLINE'"
                                    + " and action='RECOVERY_ISSUED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select change_data->>'approvalRef' from admin_auth_audit where"
                                    + " actor_kind='OFFLINE' and action='RECOVERY_ISSUED'",
                                String.class))
                .isEqualTo("approval_02");
        assertThatThrownBy(
                        () ->
                                recovery.issueOffline(
                                        accountKey,
                                        "PASSWORD_RESET",
                                        "verified_02",
                                        "approval_02",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("OFFLINE_APPROVAL_USED");

        assertThatThrownBy(
                        () ->
                                block.block(
                                        accountKey,
                                        "1",
                                        "verified_03",
                                        "approval_03",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("OFFLINE_APPROVAL_REQUIRED");
        approve(
                accountKey
                        + "|ADMIN_BLOCK_REV_2|verified_03|approval_03|operator_01|"
                        + Instant.now().plusSeconds(120).getEpochSecond());
        assertThatThrownBy(
                        () ->
                                block.block(
                                        accountKey,
                                        "1",
                                        "verified_03",
                                        "approval_03",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("OFFLINE_APPROVAL_REQUIRED");
        approve(
                accountKey
                        + "|ADMIN_BLOCK_REV_1|verified_03|approval_03|operator_01|"
                        + Instant.now().plusSeconds(120).getEpochSecond());
        var firstBlock =
                block.block(
                        accountKey,
                        "1",
                        "verified_03",
                        "approval_03",
                        "operator_01",
                        UUID.randomUUID());
        assertThat(firstBlock.changed()).isTrue();
        assertThat(firstBlock.auditStatus()).isEqualTo("RECORDED");
        assertThat(firstBlock.editRev()).isEqualTo("2");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active_yn FROM admin_account WHERE account_key=?",
                                Boolean.class,
                                accountKey))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT auth_rev FROM admin_credential", Long.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM admin_auth_grant WHERE token_hash IS NOT NULL"
                                    + " AND revoked_at IS NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT change_data->>'reasonCode' FROM admin_auth_audit "
                                        + "WHERE action='ACCOUNT_EMERGENCY_BLOCKED'",
                                String.class))
                .isEqualTo("INCIDENT");
        assertThatThrownBy(
                        () ->
                                block.block(
                                        accountKey,
                                        "1",
                                        "verified_03",
                                        "approval_03",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("STATE_CONFLICT");

        approve(
                accountKey
                        + "|ADMIN_BLOCK_REV_2|verified_04|approval_04|operator_01|"
                        + Instant.now().plusSeconds(120).getEpochSecond());
        var noChange =
                block.block(
                        accountKey,
                        "2",
                        "verified_04",
                        "approval_04",
                        "operator_01",
                        UUID.randomUUID());
        assertThat(noChange.changed()).isFalse();
        assertThat(noChange.auditStatus()).isEqualTo("NOT_REQUIRED");
        // Synthetic fixture only: re-enable the account to exercise the independent audit-failure
        // block path.
        jdbc.update("UPDATE admin_account SET active_yn=true WHERE account_key=?", accountKey);
        jdbc.execute(
                "CREATE FUNCTION fail_block_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
                    + " NEW.action='ACCOUNT_EMERGENCY_BLOCKED' THEN RAISE EXCEPTION 'audit"
                    + " unavailable'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_block BEFORE INSERT ON admin_auth_audit "
                        + "FOR EACH ROW EXECUTE FUNCTION fail_block_audit()");
        var fallback =
                block.block(
                        accountKey,
                        "2",
                        "verified_04",
                        "approval_04",
                        "operator_01",
                        UUID.randomUUID());
        assertThat(fallback.changed()).isTrue();
        assertThat(fallback.auditStatus()).isEqualTo("UNCONFIRMED");
        assertThat(fallback.editRev()).isEqualTo("3");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active_yn FROM admin_account WHERE account_key=?",
                                Boolean.class,
                                accountKey))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM admin_auth_audit "
                                        + "WHERE action='ACCOUNT_EMERGENCY_BLOCKED'",
                                Integer.class))
                .isEqualTo(1);
        jdbc.execute("DROP TRIGGER fail_block ON admin_auth_audit");
        jdbc.execute("DROP FUNCTION fail_block_audit()");

        Long accountId =
                jdbc.queryForObject(
                        "SELECT id FROM admin_account WHERE account_key=?", Long.class, accountKey);
        Long storyId =
                jdbc.queryForObject(
                        "INSERT INTO story(code,owner_id) VALUES ('OFFLINE_CASE_01',?) RETURNING"
                            + " id",
                        Long.class,
                        accountId);
        jdbc.update(
                "INSERT INTO story_access(story_id,admin_id,permission,active_yn,granted_by) "
                        + "VALUES (?,?,'EDIT',false,?)",
                storyId,
                accountId,
                accountId);
        assertThatThrownBy(
                        () ->
                                reactivate.preview(
                                        accountKey,
                                        "3",
                                        "verified_05",
                                        "approval_05",
                                        "operator_01"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("OFFLINE_APPROVAL_REQUIRED");
        approve(
                accountKey
                        + "|ADMIN_REACTIVATE_PREVIEW_REV_3|verified_05|approval_05|operator_01|"
                        + Instant.now().plusSeconds(120).getEpochSecond());
        assertThatThrownBy(
                        () ->
                                reactivate.preview(
                                        accountKey,
                                        "2",
                                        "verified_05",
                                        "approval_05",
                                        "operator_01"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("STATE_CONFLICT");
        var preview =
                reactivate.preview(accountKey, "3", "verified_05", "approval_05", "operator_01");
        assertThat(preview.ownedCount()).isEqualTo(1);
        assertThat(preview.accessCount()).isZero();
        assertThat(preview.relationSample()).hasSize(2);
        assertThat(preview.impactHash()).matches("[0-9a-f]{64}");
        assertThat(preview.nextCredentialAction()).isEqualTo("LOGIN");
        UUID oldSession = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " VALUES (?,?,?,3,'ACTIVE',now(),now(),now() + interval '8"
                    + " hours',now(),now())",
                oldSession,
                accountId,
                new byte[32]);
        assertThatThrownBy(
                        () ->
                                reactivate.reactivate(
                                        accountKey,
                                        "3",
                                        preview.impactHash(),
                                        "verified_06",
                                        "approval_06",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("OFFLINE_APPROVAL_REQUIRED");
        approve(
                accountKey
                        + "|ADMIN_REACTIVATE_REV_3_HASH_"
                        + "0".repeat(64)
                        + "|verified_06|approval_06|operator_01|"
                        + Instant.now().plusSeconds(120).getEpochSecond());
        assertThatThrownBy(
                        () ->
                                reactivate.reactivate(
                                        accountKey,
                                        "3",
                                        preview.impactHash(),
                                        "verified_06",
                                        "approval_06",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("OFFLINE_APPROVAL_REQUIRED");
        approve(
                accountKey
                        + "|ADMIN_REACTIVATE_REV_3_HASH_"
                        + preview.impactHash()
                        + "|verified_06|approval_06|operator_01|"
                        + Instant.now().plusSeconds(120).getEpochSecond());
        jdbc.update(
                "UPDATE story_access SET active_yn=true WHERE story_id=? AND admin_id=?",
                storyId,
                accountId);
        assertThatThrownBy(
                        () ->
                                reactivate.reactivate(
                                        accountKey,
                                        "3",
                                        preview.impactHash(),
                                        "verified_06",
                                        "approval_06",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("IMPACT_CHANGED");
        jdbc.update(
                "UPDATE story_access SET active_yn=false WHERE story_id=? AND admin_id=?",
                storyId,
                accountId);
        jdbc.execute(
                "CREATE FUNCTION fail_offline_activation_audit() RETURNS trigger LANGUAGE plpgsql"
                    + " AS $$ BEGIN IF NEW.action='ACCOUNT_OFFLINE_REACTIVATED' THEN RAISE"
                    + " EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_offline_activation BEFORE INSERT ON admin_auth_audit "
                        + "FOR EACH ROW EXECUTE FUNCTION fail_offline_activation_audit()");
        assertThatThrownBy(
                        () ->
                                reactivate.reactivate(
                                        accountKey,
                                        "3",
                                        preview.impactHash(),
                                        "verified_06",
                                        "approval_06",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("AUTH_UNAVAILABLE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active_yn FROM admin_account WHERE id=?",
                                Boolean.class,
                                accountId))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT edit_rev FROM admin_account WHERE id=?",
                                Long.class,
                                accountId))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM admin_session WHERE session_key=?",
                                String.class,
                                oldSession))
                .isEqualTo("ACTIVE");
        jdbc.execute("DROP TRIGGER fail_offline_activation ON admin_auth_audit");
        jdbc.execute("DROP FUNCTION fail_offline_activation_audit()");
        var activated =
                reactivate.reactivate(
                        accountKey,
                        "3",
                        preview.impactHash(),
                        "verified_06",
                        "approval_06",
                        "operator_01",
                        UUID.randomUUID());
        assertThat(activated.changed()).isTrue();
        assertThat(activated.editRev()).isEqualTo("4");
        assertThat(activated.auditStatus()).isEqualTo("RECORDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT auth_rev FROM admin_credential WHERE account_id=?",
                                Long.class,
                                accountId))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM admin_session WHERE session_key=?",
                                String.class,
                                oldSession))
                .isEqualTo("REVOKED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT change_data->>'impactHash' FROM admin_auth_audit "
                                        + "WHERE action='ACCOUNT_OFFLINE_REACTIVATED'",
                                String.class))
                .isEqualTo(preview.impactHash());
        assertThatThrownBy(
                        () ->
                                reactivate.reactivate(
                                        accountKey,
                                        "3",
                                        preview.impactHash(),
                                        "verified_06",
                                        "approval_06",
                                        "operator_01",
                                        UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("STATE_CONFLICT");
        UUID otherKey = UUID.randomUUID();
        Long otherId =
                jdbc.queryForObject(
                        "INSERT INTO admin_account(account_key,active_yn,can_manage) "
                                + "VALUES (?,false,true) RETURNING id",
                        Long.class,
                        otherKey);
        jdbc.update(
                "INSERT INTO"
                    + " admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                    + " VALUES (?,?,?,?,?,now(),0,now(),'READY')",
                otherId,
                "synthetic-cipher",
                new byte[32],
                "synthetic-hash",
                "synthetic-mfa");
        approve(
                otherKey
                        + "|ADMIN_REACTIVATE_PREVIEW_REV_1|verified_07|approval_07|operator_01|"
                        + Instant.now().plusSeconds(120).getEpochSecond());
        assertThatThrownBy(
                        () ->
                                reactivate.preview(
                                        otherKey, "1", "verified_07", "approval_07", "operator_01"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("NORMAL_MANAGER_AVAILABLE");
    }

    private static void approve(String assertion) throws Exception {
        byte[] bytes = assertion.getBytes(StandardCharsets.US_ASCII);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(bytes);
        Files.writeString(
                approvals,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                        + "\t"
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }
}
