package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.DatabaseContextTest;
import com.reasoning.common.auth.TestKeys;
import com.reasoning.common.member.auth.MemberAuthConfiguration;
import com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry;
import com.reasoning.common.member.auth.PlaytestPolicyGate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** 실제 폐기형 DB와 보호된 합성 파일로 FRIENDLY 정책의 독립성을 검사한다. */
@SpringBootTest
@Testcontainers
@Import(LocalMemberAuthIT.Installation.class)
class FriendlyPolicyGateIT extends DatabaseContextTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse(
                                    "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                            .asCompatibleSubstituteFor("postgres"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENV = "SYNTHETIC_FRIENDLY";
    private static final Map<String, Object> RETENTION =
            Map.of("inviteSeconds", 1800, "lobbySeconds", 1800,
                    "raw", "ACCOUNT_ACTIVE", "backupMaxSeconds", 3024000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.auth.crypto-key-file", () -> TestKeys.create((byte) 91));
        registry.add("app.auth.search-key-file", () -> TestKeys.create((byte) 92));
        registry.add("app.auth.limit-key-file", () -> TestKeys.create((byte) 93));
        registry.add("app.auth.breached-hashes-file", TestKeys::createCorpus);
        registry.add("app.member-auth.env-code", () -> ENV);
    }

    @Autowired JdbcTemplate db;
    @Autowired MemberAuthConfiguration.Properties properties;
    @Autowired @Qualifier("friendlyPolicyGate") PlaytestPolicyGate friendly;
    @Autowired @Qualifier("playtestPolicyGate") PlaytestPolicyGate playtest;
    @Autowired PlaytestPolicyGate primary;
    private long owner;

    @BeforeEach
    void owner() {
        properties.setFriendlyCollectionEnabled(false);
        properties.setFriendlyEvidenceRegistryFile("");
        properties.setPlaytestCollectionEnabled(false);
        properties.setPlaytestEvidenceRegistryFile("");
        db.update("UPDATE privacy_policy SET state='RETIRED' WHERE state='ACTIVE'");
        owner = db.queryForObject(
                "INSERT INTO admin_account(account_key,can_manage) VALUES (?,true) RETURNING id",
                Long.class, UUID.randomUUID());
        db.update(
                "INSERT INTO admin_credential(account_id,login_cipher,login_hash,password_hash,mfa_cipher,mfa_verified_at,last_step,enrolled_at,mfa_state)"
                        + " VALUES (?,?,?,?,?,now(),0,now(),'READY')",
                owner, "synthetic", java.nio.ByteBuffer.allocate(32).putLong(owner).array(),
                "synthetic", "synthetic");
    }

    @Test
    void independentFriendlyEvidenceAndCurrentOwner() throws Exception {
        assertThat(primary).isSameAs(playtest).isNotSameAs(friendly);
        assertThatThrownBy(friendly::prepare).hasMessage("FRIENDLY_COLLECTION_NOT_READY");
        var fixture = policy("FRIENDLY", RETENTION);
        assertThatThrownBy(friendly::prepare).hasMessage("FRIENDLY_COLLECTION_NOT_READY");
        properties.setFriendlyCollectionEnabled(true);
        var evidence = friendly.prepare();
        assertThat(friendly.lock(evidence).hash()).isEqualTo(fixture.hash());
        assertThat(PlaytestPolicyGate.noticeHash(fixture.code(), JSON.valueToTree(Map.of(
                "version", "1", "body", "합성 공개 게임 고지",
                "contact", "synthetic@example.invalid")))).isNotEqualTo(fixture.hash());
        assertThatThrownBy(() -> playtest.lock(evidence)).hasMessage("PLAYTEST_COLLECTION_NOT_READY");
        db.update("UPDATE admin_account SET can_manage=false WHERE id=?", owner);
        assertThatThrownBy(() -> friendly.lock(evidence)).hasMessage("FRIENDLY_COLLECTION_NOT_READY");
    }

    @Test
    void playtestEvidenceCannotBecomeFriendlyEvidence() throws Exception {
        policy("PLAYTEST", Map.of("inviteSeconds", 604800, "lobbySeconds", 1800,
                "resultSeconds", 86400, "rawSeconds", 7776000,
                "selectedSeconds", 31536000, "backupMaxSeconds", 3024000));
        properties.setFriendlyCollectionEnabled(true);
        properties.setFriendlyEvidenceRegistryFile(properties.getPlaytestEvidenceRegistryFile());
        assertThatThrownBy(friendly::prepare).hasMessage("FRIENDLY_COLLECTION_NOT_READY");
        properties.setPlaytestCollectionEnabled(true);
        assertThat(playtest.lock(playtest.prepare()).policyId()).isPositive();
    }

    @Test
    void wrongFriendlyRetentionCannotPassPolicyDocument() throws Exception {
        var fixture = policy("FRIENDLY", RETENTION);
        properties.setFriendlyCollectionEnabled(true);
        var evidence = friendly.prepare();
        assertThat(friendly.lock(evidence).policyId()).isPositive();
        // 실제 V27 CHECK가 PLAYTEST 보관값의 FRIENDLY 정책 삽입을 거절한다.
        assertThatThrownBy(() -> policy("FRIENDLY", Map.of("inviteSeconds", 604800,
                "lobbySeconds", 1800, "raw", "ACCOUNT_ACTIVE", "backupMaxSeconds", 3024000)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private record Fixture(String code, String hash) {}

    private Fixture policy(String scope, Map<String, Object> retention) throws Exception {
        String code = scope + "_" + UUID.randomUUID().toString().replace("-", "");
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);
        Instant until = from.plusSeconds(86400);
        var notice = JSON.valueToTree(Map.of("version", "1", "body", "합성 공개 게임 고지",
                "contact", "synthetic@example.invalid"));
        String hash = scope.equals("FRIENDLY")
                ? PlaytestPolicyGate.friendlyNoticeHash(code, notice)
                : PlaytestPolicyGate.noticeHash(code, notice);
        var evidence = new TreeMap<String, Object>();
        var records = new ArrayList<Map<String, Object>>();
        for (String kind : MemberPolicyEvidenceRegistry.KINDS) {
            String ref = scope.toLowerCase() + ":" + kind;
            Object claims = switch (kind) {
                case "responsibility" -> Map.of("purpose", "합성 공개 게임", "items", List.of("synthetic gameplay"),
                        "contact", "synthetic@example.invalid");
                case "access" -> Map.of("privilegedReaders", List.of("synthetic"),
                        "securityOperators", List.of("synthetic"), "erasureOperators", List.of("synthetic"),
                        "boundaries", "isolated test");
                case "keys" -> Map.of("custodians", List.of("synthetic"), "rotationProcedure", "synthetic fixture",
                        "restoreProcedure", "synthetic fixture");
                case "processors" -> Map.of("gradingProcessors", List.of("none"),
                        "storageRestrictions", "synthetic only", "deletionProcedure", "purge fixture",
                        "confirmationProcedure", "test assertions");
                case "copies" -> Map.of("inventory", List.of("none"), "deletionLedger", "synthetic",
                        "restoreGuard", "synthetic");
                case "verification" -> Map.of("consent", "synthetic", "revocation", "synthetic",
                        "isolatedRestore", "synthetic", "deletionRecordsApplied", "synthetic",
                        "limitations", "not operational evidence");
                default -> throw new IllegalArgumentException(kind);
            };
            byte[] bytes = JSON.writeValueAsBytes(Map.of("formatNo", 1, "kind", kind, "ref", ref,
                    "envCode", ENV, "scope", scope, "ownerId", owner, "noticeHash", hash,
                    "verifiedAt", from.toString(), "validUntil", until.toString(), "claims", claims));
            String digest = MemberPolicyEvidenceRegistry.sha256(bytes);
            records.add(Map.of("kind", kind, "ref", ref, "path", protectedFile(bytes).toString(),
                    "sha256", digest));
            evidence.put(kind, Map.of("ref", ref, "sha256", digest, "envCode", ENV, "scope", scope,
                    "verifiedAt", from.toString(), "validUntil", until.toString()));
        }
        String registry = protectedFile(JSON.writeValueAsBytes(Map.of("formatNo", 1, "scope", scope,
                "envCode", ENV, "ownerId", owner, "noticeHash", hash,
                "validFrom", from.toString(), "validUntil", until.toString(), "records", records))).toString();
        if (scope.equals("FRIENDLY")) properties.setFriendlyEvidenceRegistryFile(registry);
        else properties.setPlaytestEvidenceRegistryFile(registry);
        var document = Map.of("formatNo", 1, "notice", notice, "validFrom", from.toString(),
                "validUntil", until.toString(), "retention", retention, "evidence", evidence);
        db.update("INSERT INTO privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                        + " VALUES (?,?,?,'DRAFT',?,?::jsonb,?)",
                code, ENV, scope, hash, JSON.writeValueAsString(document), owner);
        db.update("UPDATE privacy_policy SET state='ACTIVE' WHERE code=?", code);
        return new Fixture(code, hash);
    }

    private static Path protectedFile(byte[] bytes) throws Exception {
        Path path = Files.createTempFile("friendly-policy-synthetic-", ".json").toRealPath();
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Files.write(path, bytes);
        path.toFile().deleteOnExit();
        return path;
    }
}
