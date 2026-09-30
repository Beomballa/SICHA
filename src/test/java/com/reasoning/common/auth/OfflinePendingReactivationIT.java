package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.service.AdminOfflineBlockService;
import com.reasoning.common.auth.service.AdminOfflineReactivateService;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.EnrollmentService;

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
class OfflinePendingReactivationIT extends DatabaseContextTest {
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
            approvals = Files.createTempFile("h0-pending-approvals-", ".txt");
            publicKey = Files.createTempFile("h0-pending-public-", ".txt");
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
        } catch (Exception failure) {
            throw new IllegalStateException("Test signer unavailable", failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 81));
        properties.add("app.auth.search-key-file", () -> TestKeys.create((byte) 82));
        properties.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 83));
        properties.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
        properties.add("app.auth.offline-approvals-file", approvals::toString);
        properties.add("app.auth.offline-approval-public-key-file", publicKey::toString);
    }

    @Autowired EnrollmentService enrollment;
    @Autowired AdminOfflineBlockService block;
    @Autowired AdminOfflineReactivateService reactivation;
    @Autowired JdbcTemplate db;

    /**
     * Incomplete bootstrap recovery only restores administrative activation; fresh enrollment
     * remains offline.
     */
    @Test
    void pendingBootstrapReactivationDoesNotResurrectOriginalRegistrationCode() throws Exception {
        var initial =
                enrollment.createBootstrap(
                        "pendingboot01", "verified_11111", "operator_111", UUID.randomUUID());
        UUID key = db.queryForObject("SELECT account_key FROM admin_account", UUID.class);
        approve(key, "ADMIN_BLOCK_REV_1", "verified_22222", "approval_222", "operator_111");
        block.block(key, "1", "verified_22222", "approval_222", "operator_111", UUID.randomUUID());
        assertThat(
                        db.queryForObject(
                                "SELECT code_hash IS NULL AND grant_hash IS NULL AND revoked_at IS"
                                    + " NOT NULL FROM admin_enrollment WHERE kind='BOOTSTRAP'",
                                Boolean.class))
                .isTrue();
        assertThatThrownBy(
                        () ->
                                enrollment.exchange(
                                        initial.code(), null, "test-source", UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        approve(
                key,
                "ADMIN_REACTIVATE_PREVIEW_REV_2",
                "verified_33333",
                "approval_333",
                "operator_111");
        var preview =
                reactivation.preview(key, "2", "verified_33333", "approval_333", "operator_111");
        assertThat(preview.ownedCount()).isZero();
        assertThat(preview.relationSample()).isEmpty();
        assertThat(preview.nextCredentialAction()).isEqualTo("REISSUE_ENROLLMENT");
        approve(
                key,
                "ADMIN_REACTIVATE_REV_2_HASH_" + preview.impactHash(),
                "verified_44444",
                "approval_444",
                "operator_111");
        var result =
                reactivation.reactivate(
                        key,
                        "2",
                        preview.impactHash(),
                        "verified_44444",
                        "approval_444",
                        "operator_111",
                        UUID.randomUUID());
        assertThat(result.editRev()).isEqualTo("3");
        assertThat(result.nextCredentialAction()).isEqualTo("REISSUE_ENROLLMENT");
        assertThat(
                        db.queryForObject(
                                "SELECT active_yn AND can_manage FROM admin_account WHERE"
                                    + " account_key=?",
                                Boolean.class,
                                key))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT enrolled_at IS NULL AND mfa_state='PENDING' AND auth_rev=3"
                                    + " AND password_hash IS NULL AND mfa_cipher IS NULL FROM"
                                    + " admin_credential",
                                Boolean.class))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT code_hash IS NULL AND grant_hash IS NULL "
                                        + "FROM admin_enrollment WHERE kind='BOOTSTRAP'",
                                Boolean.class))
                .isTrue();
        assertThatThrownBy(
                        () ->
                                enrollment.exchange(
                                        initial.code(), null, "test-source", UUID.randomUUID()))
                .isInstanceOf(AuthException.class);
        var fresh =
                enrollment.reissueBootstrap("verified_55555", "operator_111", UUID.randomUUID());
        assertThat(fresh.registrationKey()).isEqualTo(initial.registrationKey());
        assertThat(fresh.code()).isNotEqualTo(initial.code());
        assertThat(
                        db.queryForObject(
                                "SELECT generation FROM admin_enrollment WHERE kind='BOOTSTRAP'",
                                Integer.class))
                .isEqualTo(2);
    }

    private static void approve(
            UUID key, String purpose, String verification, String approval, String actor)
            throws Exception {
        byte[] bytes =
                (key
                                + "|"
                                + purpose
                                + "|"
                                + verification
                                + "|"
                                + approval
                                + "|"
                                + actor
                                + "|"
                                + Instant.now().plusSeconds(120).getEpochSecond())
                        .getBytes(StandardCharsets.US_ASCII);
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
