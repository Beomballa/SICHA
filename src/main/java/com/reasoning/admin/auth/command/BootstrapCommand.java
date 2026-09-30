package com.reasoning.admin.auth.command;

import com.reasoning.common.auth.service.AdminOfflineBlockService;
import com.reasoning.common.auth.service.AdminOfflineReactivateService;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.EnrollmentService;
import com.reasoning.common.auth.service.RecoveryService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.UUID;

@Component
public class BootstrapCommand implements ApplicationRunner {
    private final EnrollmentService enrollment;
    private final RecoveryService recovery;
    private final ObjectProvider<AdminOfflineBlockService> block;
    private final ObjectProvider<AdminOfflineReactivateService> reactivation;

    public BootstrapCommand(
            EnrollmentService enrollment,
            RecoveryService recovery,
            ObjectProvider<AdminOfflineBlockService> block,
            ObjectProvider<AdminOfflineReactivateService> reactivation) {
        this.enrollment = enrollment;
        this.recovery = recovery;
        this.block = block;
        this.reactivation = reactivation;
    }

    /**
     * Runs enrollment, recovery and signed emergency administration with a private console.
     *
     * @param args Spring application options with one recognized h0-command mode
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("h0-command")) return;
        String mode = args.getOptionValues("h0-command").getFirst();
        if (!mode.equals("bootstrap-create")
                && !mode.equals("bootstrap-reissue")
                && !mode.equals("recovery-issue")
                && !mode.equals("admin-block")
                && !mode.equals("admin-reactivation-preview")
                && !mode.equals("admin-reactivate")) {
            throw new IllegalArgumentException("Unknown offline command");
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "An interactive, private console is required for offline authentication"
                        + " commands");
        }
        if (mode.equals("admin-block")) {
            UUID requestId = UUID.randomUUID();
            console.printf("Request ID: %s%n", requestId);
            console.printf(
                    "External access block must remain in place; DB revocation does not release"
                        + " it.%n");
            try {
                var result =
                        block.getObject()
                                .block(
                                        UUID.fromString(
                                                console.readLine("Confirmed account key: ")),
                                        console.readLine("Expected edit revision: "),
                                        console.readLine("Verification reference: "),
                                        console.readLine("Independent approval reference: "),
                                        console.readLine("Offline operator reference: "),
                                        requestId);
                console.printf(
                        "Changed: %s; audit status: %s%n", result.changed(), result.auditStatus());
            } catch (AuthException failure) {
                console.printf(
                        "DB revocation not confirmed; requestId=%s; error=%s%n",
                        requestId, failure.code());
                throw failure;
            }
            return;
        }
        if (mode.equals("admin-reactivation-preview") || mode.equals("admin-reactivate")) {
            UUID requestId = UUID.randomUUID();
            console.printf("Request ID: %s%n", requestId);
            console.printf(
                    "DB activation never releases the external access block; separate approval is"
                        + " required.%n");
            UUID accountKey = UUID.fromString(console.readLine("Confirmed account key: "));
            String expectedRev = console.readLine("Expected edit revision: ");
            String impactHash =
                    mode.equals("admin-reactivate")
                            ? console.readLine("Confirmed full impact hash: ")
                            : null;
            String verificationRef = console.readLine("Verification reference: ");
            String approvalRef = console.readLine("Independent approval reference: ");
            String actorRef = console.readLine("Offline operator reference: ");
            try {
                if (mode.equals("admin-reactivation-preview")) {
                    var preview =
                            reactivation
                                    .getObject()
                                    .preview(
                                            accountKey,
                                            expectedRev,
                                            verificationRef,
                                            approvalRef,
                                            actorRef);
                    console.printf(
                            "Owned: %d; active access: %d; truncated: %s; credential action: %s%n",
                            preview.ownedCount(),
                            preview.accessCount(),
                            preview.truncated(),
                            preview.nextCredentialAction());
                    for (var row : preview.relationSample())
                        console.printf(
                                "Story code: %s; relation: %s; active: %s%n",
                                row.storyCode(), row.relation(), row.activeYn());
                    console.printf("Full impact hash: %s%n", preview.impactHash());
                } else {
                    var result =
                            reactivation
                                    .getObject()
                                    .reactivate(
                                            accountKey,
                                            expectedRev,
                                            impactHash,
                                            verificationRef,
                                            approvalRef,
                                            actorRef,
                                            requestId);
                    console.printf(
                            "Changed: %s; audit status: %s; credential action: %s%n",
                            result.changed(), result.auditStatus(), result.nextCredentialAction());
                }
            } catch (AuthException failure) {
                console.printf(
                        "DB activation not confirmed; requestId=%s; error=%s%n",
                        requestId, failure.code());
                throw failure;
            }
            return;
        }
        if (mode.equals("recovery-issue")) {
            UUID account = UUID.fromString(console.readLine("Confirmed account key: "));
            String purpose = console.readLine("Purpose (PASSWORD_RESET or MFA_RECOVERY): ");
            String verification = console.readLine("Identity verification reference: ");
            String approval = console.readLine("Independent approval reference: ");
            String operator = console.readLine("Offline operator reference: ");
            var issued =
                    recovery.issueOffline(
                            account, purpose, verification, approval, operator, UUID.randomUUID());
            console.printf("One-time recovery code (deliver privately): %s%n", issued.code());
            console.printf("Expires at: %s%n", issued.expiresAt());
            return;
        }
        String operator = console.readLine("Operator reference: ");
        String verification = console.readLine("Verification reference: ");
        var result =
                mode.equals("bootstrap-create")
                        ? enrollment.createBootstrap(
                                console.readLine("Login ID: "),
                                verification,
                                operator,
                                UUID.randomUUID())
                        : enrollment.reissueBootstrap(verification, operator, UUID.randomUUID());
        console.printf("Registration key: %s%n", result.registrationKey());
        console.printf("One-time registration code (deliver privately): %s%n", result.code());
        console.printf("Expires at: %s%n", result.codeExpiresAt());
    }
}
