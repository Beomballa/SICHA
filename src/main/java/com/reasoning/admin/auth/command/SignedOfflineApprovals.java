package com.reasoning.admin.auth.command;

import com.reasoning.common.auth.service.RecoveryService.ApprovalVerifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Verifies external, time-bound approvals; private signing key never enters the application. */
@Component
@ConditionalOnProperty(name = {"app.auth.offline-approvals-file", "app.auth.offline-approval-public-key-file"})
public final class SignedOfflineApprovals implements ApprovalVerifier {
    private final Path approvals;
    private final Path publicKey;

    public SignedOfflineApprovals(@Value("${app.auth.offline-approvals-file}") String approvals,
            @Value("${app.auth.offline-approval-public-key-file}") String publicKey) {
        this.approvals = Path.of(approvals);
        this.publicKey = Path.of(publicKey);
    }

    /**
     * Accepts only an unexpired Ed25519-signed assertion naming the exact offline operation.
     * @param accountKey confirmed account identifier
     * @param purpose PASSWORD_RESET, MFA_RECOVERY, exact ADMIN_BLOCK_REV_&lt;rev&gt;,
     *     ADMIN_REACTIVATE_PREVIEW_REV_&lt;rev&gt;, or ADMIN_REACTIVATE_REV_&lt;rev&gt;_HASH_&lt;hash&gt;
     * @param verificationRef out-of-band identity-check reference
     * @param approvalRef independent approval reference
     * @param actorRef responsible offline operator reference
     * @return whether a valid external assertion currently authorizes this exact combination
     */
    @Override
    public boolean offlineApproved(UUID accountKey, String purpose, String verificationRef, String approvalRef, String actorRef) {
        try {
            if (!ownerOnly(approvals) || Files.size(approvals) > 1_048_576
                    || !ownerOnly(publicKey) || Files.size(publicKey) > 4096) return false;
            var keyBytes = Base64.getUrlDecoder().decode(Files.readString(publicKey).trim());
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
            String expected = accountKey + "|" + purpose + "|" + verificationRef + "|" + approvalRef + "|" + actorRef + "|";
            for (String line : Files.readAllLines(approvals, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\t", -1);
                if (parts.length != 2 || parts[0].length() > 512 || parts[1].length() > 128) continue;
                byte[] bytes = Base64.getUrlDecoder().decode(parts[0]);
                String payload = new String(bytes, StandardCharsets.US_ASCII);
                if (!payload.startsWith(expected)) continue;
                long expires = Long.parseLong(payload.substring(expected.length()));
                Instant now = Instant.now();
                if (!now.isBefore(Instant.ofEpochSecond(expires)) || Instant.ofEpochSecond(expires).isAfter(now.plusSeconds(600))) continue;
                Signature signature = Signature.getInstance("Ed25519");
                signature.initVerify(key);
                signature.update(bytes);
                if (signature.verify(Base64.getUrlDecoder().decode(parts[1]))) return true;
            }
        } catch (Exception ignored) {
            // Missing or malformed external evidence must not authorize a recovery.
        }
        return false;
    }

    private static boolean ownerOnly(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        return permissions.stream().noneMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
    }
}
