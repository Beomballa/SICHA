package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.reasoning.admin.auth.command.SignedOfflineApprovals;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

class SignedOfflineApprovalsTest {
    @Test
    void requiresMatchingExternalSignatureAndUnexpiredApproval() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path approvals = Files.createTempFile("h0-approvals-", ".txt");
        Path publicKey = Files.createTempFile("h0-approval-public-", ".txt");
        try {
            Files.setPosixFilePermissions(
                    approvals,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.writeString(
                    publicKey,
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(pair.getPublic().getEncoded()));
            UUID account = UUID.randomUUID();
            String prefix = account + "|PASSWORD_RESET|identity_01|approval_01|operator_01|";
            String assertion = prefix + Instant.now().plusSeconds(120).getEpochSecond();
            byte[] bytes = assertion.getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(bytes);
            Files.writeString(
                    approvals,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                            + "\t"
                            + Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(signer.sign()));
            var verifier = new SignedOfflineApprovals(approvals.toString(), publicKey.toString());
            assertThat(
                            verifier.offlineApproved(
                                    account,
                                    "PASSWORD_RESET",
                                    "identity_01",
                                    "approval_01",
                                    "operator_01"))
                    .isTrue();
            assertThat(
                            verifier.offlineApproved(
                                    account,
                                    "MFA_RECOVERY",
                                    "identity_01",
                                    "approval_01",
                                    "operator_01"))
                    .isFalse();
            assertThat(
                            verifier.offlineApproved(
                                    UUID.randomUUID(),
                                    "PASSWORD_RESET",
                                    "identity_01",
                                    "approval_01",
                                    "operator_01"))
                    .isFalse();
            Files.setPosixFilePermissions(
                    approvals,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
            assertThat(
                            verifier.offlineApproved(
                                    account,
                                    "PASSWORD_RESET",
                                    "identity_01",
                                    "approval_01",
                                    "operator_01"))
                    .isFalse();
            Files.setPosixFilePermissions(approvals, Set.of(PosixFilePermission.OWNER_READ));
            Files.setPosixFilePermissions(
                    publicKey,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_WRITE));
            assertThat(
                            verifier.offlineApproved(
                                    account,
                                    "PASSWORD_RESET",
                                    "identity_01",
                                    "approval_01",
                                    "operator_01"))
                    .isFalse();
        } finally {
            Files.deleteIfExists(approvals);
            Files.deleteIfExists(publicKey);
        }
    }
}
