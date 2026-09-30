package com.reasoning.common.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthModels {
    private AuthModels() {}

    public record BootstrapResult(UUID registrationKey, String code, Instant codeExpiresAt) {}

    public record FlowCookie(String name, String value, Instant expiresAt) {}

    public record EnrollExchange(String stage, Instant expiresAt, FlowCookie cookie) {}

    public record EnrollStage(String stage, Instant expiresAt) {}

    public record MfaSetup(
            String stage, String secret, String provisioningUri, Instant expiresAt) {}

    public record EnrollComplete(String result, String nextAction, List<String> recoveryCodes) {}

    public record LoginStart(String stage, Instant expiresAt, FlowCookie cookie) {}

    public record Authenticated(String result, Instant absoluteExpiresAt, FlowCookie cookie) {}

    public record SessionPrincipal(
            long accountId,
            UUID accountKey,
            UUID sessionKey,
            Instant absoluteExpiresAt,
            Instant idleExpiresAt,
            Instant reauthExpiresAt,
            boolean canManage,
            boolean canCreate,
            boolean canReview,
            boolean canPublish) {}

    public record Me(
            UUID accountKey,
            List<String> permissions,
            Instant absoluteExpiresAt,
            Instant idleExpiresAt,
            Instant reauthExpiresAt) {}

    public record ReauthResult(
            Instant reauthExpiresAt, Instant absoluteExpiresAt, FlowCookie cookie) {}

    public record RecoveryIssue(String code, Instant expiresAt) {}

    public record RecoveryExchange(
            String purpose, String stage, Instant expiresAt, FlowCookie cookie) {}

    public record RecoveryStage(String purpose, String stage, Instant expiresAt) {}

    public record SimpleResult(String result, String nextAction) {}
}
