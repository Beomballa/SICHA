package com.reasoning.development;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.member.auth.MemberAuthConfiguration;
import com.reasoning.common.member.auth.MemberMailTransport;
import com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 격리 개발 DB에만 설치하는 합성 고지다. 법률 승인이나 실제 파기·복원 증거가 아니다. */
public final class DevelopmentPolicy {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String CODE = "SYNTHETIC_DEVELOPMENT_LOCAL_V1";
    private final DevelopmentState state;
    private final JdbcTemplate db;
    private final MemberAuthConfiguration.Properties configuration;
    private final AuthProperties keys;
    private final MemberMailTransport mail;

    DevelopmentPolicy(
            DevelopmentState state,
            JdbcTemplate db,
            MemberAuthConfiguration.Properties configuration,
            AuthProperties keys,
            MemberMailTransport mail) {
        this.state = state;
        this.db = db;
        this.configuration = configuration;
        this.keys = keys;
        this.mail = mail;
    }

    /** 지정 소유자의 합성 정책만 생성하거나 바이트까지 동일한 기존 정책을 재사용한다. */
    void install(long owner) throws IOException {
        state.requireDatabase(db);
        String env = configuration.getEnvCode();
        if (env == null || !env.startsWith("SYNTHETIC_") || !configuration.isCollectionEnabled())
            throw new IllegalStateException("합성 개발 회원 구성 필요");
        String from = "2026-01-01T00:00:00Z", until = "2036-01-01T00:00:00Z";
        if (!Instant.now().isBefore(Instant.parse(until)))
            throw new IllegalStateException("합성 정책 만료");
        JsonNode notice =
                JSON.valueToTree(
                        Map.of(
                                "version",
                                "SYNTHETIC-1",
                                "body",
                                "SYNTHETIC ONLY: 격리 로컬 개발용 가상 회원. 실제 법률·운영·파기·복원 검증 자료가 아닙니다.",
                                "contact",
                                "synthetic@example.invalid"));
        String hash = MemberPolicyGate.noticeHash(CODE, notice);
        Map<String, String> keyFiles =
                Map.of(
                        "encryption",
                        keys.getCryptoKeyFile(),
                        "search",
                        keys.getSearchKeyFile(),
                        "limit",
                        keys.getLimitKeyFile());
        var boundary = mail.boundary();
        Map<String, Object> mailData =
                Map.of(
                        "host",
                        boundary.host(),
                        "port",
                        boundary.port(),
                        "protocol",
                        boundary.protocol(),
                        "from",
                        boundary.from(),
                        "timeoutMillis",
                        boundary.timeoutMillis());
        Map<String, Object> evidence = new TreeMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String kind : new java.util.TreeSet<>(MemberPolicyEvidenceRegistry.KINDS)) {
            String ref = "SYNTHETIC:development:" + kind;
            Map<String, Object> artifact = new TreeMap<>();
            artifact.putAll(
                    Map.of(
                            "formatNo",
                            1,
                            "kind",
                            kind,
                            "ref",
                            ref,
                            "envCode",
                            env,
                            "scope",
                            "MEMBER_AUTH",
                            "ownerId",
                            owner,
                            "noticeHash",
                            hash,
                            "verifiedAt",
                            from,
                            "validUntil",
                            until,
                            "claims",
                            claims(kind, keyFiles, mailData)));
            byte[] bytes = JSON.writeValueAsBytes(artifact);
            Path path = state.home().resolve("synthetic-policy-" + kind + ".json");
            sameFile(path, bytes);
            String sha = MemberPolicyEvidenceRegistry.sha256(bytes);
            records.add(Map.of("kind", kind, "ref", ref, "path", path.toString(), "sha256", sha));
            evidence.put(
                    kind,
                    Map.of(
                            "ref",
                            ref,
                            "sha256",
                            sha,
                            "envCode",
                            env,
                            "scope",
                            "MEMBER_AUTH",
                            "verifiedAt",
                            from,
                            "validUntil",
                            until));
        }
        Map<String, Object> manifest = new TreeMap<>();
        manifest.putAll(
                Map.of(
                        "formatNo",
                        1,
                        "envCode",
                        env,
                        "scope",
                        "MEMBER_AUTH",
                        "ownerId",
                        owner,
                        "noticeHash",
                        hash,
                        "validFrom",
                        from,
                        "validUntil",
                        until,
                        "keyFiles",
                        keyFiles,
                        "mail",
                        mailData,
                        "records",
                        records));
        Path registry = state.home().resolve("synthetic-policy-registry.json");
        sameFile(registry, JSON.writeValueAsBytes(manifest));
        configuration.setEvidenceRegistryFile(registry.toString());
        Map<String, Object> retention = new TreeMap<>();
        retention.putAll(
                Map.of(
                        "flowTtlSeconds",
                        600,
                        "flowCleanupGraceSeconds",
                        3600,
                        "accessTtlSeconds",
                        300,
                        "accessCleanupGraceSeconds",
                        3600,
                        "refreshIdleSeconds",
                        2592000,
                        "sessionAbsoluteSeconds",
                        7776000,
                        "familyCleanupGraceSeconds",
                        86400,
                        "limitMaxSeconds",
                        86400,
                        "accessHistoryMaxSeconds",
                        2592000,
                        "securityAuditMaxSeconds",
                        7776000));
        retention.put("backupMaxSeconds", 3024000);
        JsonNode document =
                JSON.valueToTree(
                        Map.of(
                                "formatNo",
                                1,
                                "notice",
                                notice,
                                "validFrom",
                                from,
                                "validUntil",
                                until,
                                "authProviders",
                                List.of("LOCAL"),
                                "retention",
                                retention,
                                "evidence",
                                evidence));
        var rows =
                db.queryForList(
                        "SELECT code,owner_id,notice_hash,policy_data::text,state FROM"
                                + " privacy_policy WHERE env_code=? AND scope='MEMBER_AUTH'",
                        env);
        if (rows.isEmpty()) {
            db.update(
                    "INSERT INTO"
                        + " privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                        + " VALUES (?,?,'MEMBER_AUTH','DRAFT',?,?::jsonb,?)",
                    CODE,
                    env,
                    hash,
                    JSON.writeValueAsString(document),
                    owner);
            db.update(
                    "UPDATE privacy_policy SET state='ACTIVE' WHERE code=? AND env_code=? AND"
                            + " owner_id=? AND state='DRAFT'",
                    CODE,
                    env,
                    owner);
        } else {
            if (rows.size() != 1) throw new IllegalStateException("예상하지 않은 개발 정책");
            var row = rows.getFirst();
            if (!CODE.equals(row.get("code"))
                    || ((Number) row.get("owner_id")).longValue() != owner
                    || !hash.equals(row.get("notice_hash"))
                    || !"ACTIVE".equals(row.get("state"))
                    || !document.equals(JSON.readTree((String) row.get("policy_data"))))
                throw new IllegalStateException("기존 개발 정책 불일치");
        }
    }

    /**
     * 호출자의 기존 초기화 트랜잭션 안에서 합성 친선 정책만 설치하고 실제 게이트로 검증한다. 회원 인증 정책이나 사건·출시·모델 자료에는 손대지 않는다.
     *
     * @param owner 실제 등록을 마친 합성 개발 관리자 ID
     */
    void installFriendly(long owner) throws IOException {
        state.requireDatabase(db);
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("합성 친선 정책 설치 트랜잭션 필요");
        String env = configuration.getEnvCode();
        if (env == null || !env.startsWith("SYNTHETIC_"))
            throw new IllegalStateException("합성 개발 친선 구성 필요");
        String code = "SYNTHETIC_DEVELOPMENT_FRIENDLY_V1";
        String from = "2026-01-01T00:00:00Z", until = "2036-01-01T00:00:00Z";
        JsonNode notice =
                JSON.valueToTree(
                        Map.of(
                                "version",
                                "SYNTHETIC-FRIENDLY-1",
                                "body",
                                "SYNTHETIC ONLY: 격리 로컬 개발용 가상 친선 플레이. 실제 법률·운영·동의·파기·복원 검증 자료가"
                                        + " 아닙니다. 외부 모델을 호출하지 않습니다.",
                                "contact",
                                "synthetic@example.invalid"));
        String hash = PlaytestPolicyGate.friendlyNoticeHash(code, notice);
        Map<String, Object> evidence = new TreeMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String kind : new java.util.TreeSet<>(MemberPolicyEvidenceRegistry.KINDS)) {
            String ref = "SYNTHETIC:development:friendly:" + kind;
            Map<String, Object> artifact = new TreeMap<>();
            artifact.putAll(
                    Map.of(
                            "formatNo",
                            1,
                            "kind",
                            kind,
                            "ref",
                            ref,
                            "envCode",
                            env,
                            "scope",
                            "FRIENDLY",
                            "ownerId",
                            owner,
                            "noticeHash",
                            hash,
                            "verifiedAt",
                            from,
                            "validUntil",
                            until,
                            "claims",
                            friendlyClaims(kind)));
            byte[] bytes = JSON.writeValueAsBytes(artifact);
            Path path = state.home().resolve("synthetic-friendly-policy-" + kind + ".json");
            sameFile(path, bytes);
            String sha = MemberPolicyEvidenceRegistry.sha256(bytes);
            records.add(Map.of("kind", kind, "ref", ref, "path", path.toString(), "sha256", sha));
            evidence.put(
                    kind,
                    Map.of(
                            "ref",
                            ref,
                            "sha256",
                            sha,
                            "envCode",
                            env,
                            "scope",
                            "FRIENDLY",
                            "verifiedAt",
                            from,
                            "validUntil",
                            until));
        }
        Path registry = state.home().resolve("synthetic-friendly-policy-registry.json");
        sameFile(
                registry,
                JSON.writeValueAsBytes(
                        Map.of(
                                "formatNo",
                                1,
                                "scope",
                                "FRIENDLY",
                                "envCode",
                                env,
                                "ownerId",
                                owner,
                                "noticeHash",
                                hash,
                                "validFrom",
                                from,
                                "validUntil",
                                until,
                                "records",
                                records)));
        JsonNode document =
                JSON.valueToTree(
                        Map.of(
                                "formatNo",
                                1,
                                "notice",
                                notice,
                                "validFrom",
                                from,
                                "validUntil",
                                until,
                                "retention",
                                Map.of(
                                        "inviteSeconds",
                                        1800,
                                        "lobbySeconds",
                                        1800,
                                        "raw",
                                        "ACCOUNT_ACTIVE",
                                        "backupMaxSeconds",
                                        3024000),
                                "evidence",
                                evidence));
        var rows =
                db.queryForList(
                        "SELECT code,owner_id,notice_hash,policy_data::text,state FROM"
                                + " privacy_policy WHERE env_code=? AND scope='FRIENDLY'",
                        env);
        if (rows.isEmpty()) {
            db.update(
                    "INSERT INTO"
                        + " privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                        + " VALUES (?,?,'FRIENDLY','DRAFT',?,?::jsonb,?)",
                    code,
                    env,
                    hash,
                    JSON.writeValueAsString(document),
                    owner);
            if (db.update(
                            "UPDATE privacy_policy SET state='ACTIVE' WHERE code=? AND env_code=?"
                                    + " AND owner_id=? AND scope='FRIENDLY' AND state='DRAFT'",
                            code,
                            env,
                            owner)
                    != 1) throw new IllegalStateException("합성 친선 정책 활성화 실패");
        } else {
            if (rows.size() != 1) throw new IllegalStateException("예상하지 않은 개발 친선 정책");
            var row = rows.getFirst();
            if (!code.equals(row.get("code"))
                    || ((Number) row.get("owner_id")).longValue() != owner
                    || !hash.equals(row.get("notice_hash"))
                    || !"ACTIVE".equals(row.get("state"))
                    || !document.equals(JSON.readTree((String) row.get("policy_data"))))
                throw new IllegalStateException("기존 개발 친선 정책 불일치");
        }
        boolean previousEnabled = configuration.isFriendlyCollectionEnabled();
        String previousRegistry = configuration.getFriendlyEvidenceRegistryFile();
        try {
            configuration.setFriendlyEvidenceRegistryFile(registry.toString());
            configuration.setFriendlyCollectionEnabled(true);
            var gate = PlaytestPolicyGate.friendly(db, configuration);
            gate.lock(gate.prepare());
        } catch (RuntimeException failure) {
            configuration.setFriendlyCollectionEnabled(previousEnabled);
            configuration.setFriendlyEvidenceRegistryFile(previousRegistry);
            throw failure;
        }
    }

    private static Map<String, Object> friendlyClaims(String kind) {
        String synthetic =
                "SYNTHETIC ONLY; not legal, consent, operational or erasure evidence; no model"
                        + " calls";
        return switch (kind) {
            case "responsibility" ->
                    Map.of(
                            "purpose",
                            synthetic,
                            "items",
                            List.of("SYNTHETIC friendly play"),
                            "contact",
                            "synthetic@example.invalid");
            case "access" ->
                    Map.of(
                            "privilegedReaders",
                            List.of("SYNTHETIC"),
                            "securityOperators",
                            List.of("SYNTHETIC"),
                            "erasureOperators",
                            List.of("SYNTHETIC"),
                            "boundaries",
                            synthetic);
            case "keys" ->
                    Map.of(
                            "custodians",
                            List.of("SYNTHETIC"),
                            "rotationProcedure",
                            synthetic,
                            "restoreProcedure",
                            synthetic);
            case "processors" ->
                    Map.of(
                            "gradingProcessors",
                            List.of("SYNTHETIC_NO_MODEL"),
                            "storageRestrictions",
                            synthetic,
                            "deletionProcedure",
                            synthetic,
                            "confirmationProcedure",
                            synthetic);
            case "copies" ->
                    Map.of(
                            "inventory",
                            List.of(
                                    "SYNTHETIC isolated development database and private files; not"
                                            + " an operational inventory"),
                            "deletionLedger",
                            synthetic,
                            "restoreGuard",
                            synthetic);
            case "verification" ->
                    Map.of(
                            "consent",
                            synthetic,
                            "revocation",
                            synthetic,
                            "isolatedRestore",
                            synthetic,
                            "deletionRecordsApplied",
                            synthetic,
                            "limitations",
                            synthetic);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    String code() {
        return CODE;
    }

    String noticeHash() {
        return db.queryForObject(
                "SELECT notice_hash FROM privacy_policy WHERE code=? AND env_code=? AND"
                        + " state='ACTIVE'",
                String.class,
                CODE,
                configuration.getEnvCode());
    }

    /** 비공개 쓰기 원장에 fsync한 뒤 하드 링크로 원자 공개한다. 원장도 삭제하지 않는다. */
    static void sameFile(Path path, byte[] bytes) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path journal = path.resolveSibling(path.getFileName() + ".write-once");
            if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                try (FileChannel channel =
                        FileChannel.open(
                                journal,
                                java.util.Set.of(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS),
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------")))) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
            }
            if (!Arrays.equals(readPrivate(journal), bytes)) throw new IOException("개발 쓰기 원장 불일치");
            Files.createLink(path, journal);
        }
        if (!Arrays.equals(readPrivate(path), bytes)) throw new IOException("개발 전용 파일 불일치");
    }

    static byte[] readPrivate(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                        .equals(PosixFilePermissions.fromString("rw-------"))
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
                        .equals(Files.getOwner(path.getParent(), LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("개발 비밀 파일 소유권 또는 권한 불일치");
        try (var channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > 131072) throw new IOException("개발 파일 크기 초과");
            ByteBuffer bytes = ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining())
                if (channel.read(bytes) < 0) throw new IOException("개발 파일 읽기 불완전");
            return bytes.array();
        }
    }

    private static Map<String, Object> claims(
            String kind, Map<String, String> keys, Map<String, Object> mail) {
        String synthetic = "SYNTHETIC ONLY; not legal or operational evidence";
        return switch (kind) {
            case "responsibility" ->
                    Map.of(
                            "purpose",
                            synthetic,
                            "items",
                            List.of("synthetic email"),
                            "contact",
                            "synthetic@example.invalid");
            case "access" ->
                    Map.of(
                            "privilegedReaders",
                            List.of("SYNTHETIC"),
                            "securityOperators",
                            List.of("SYNTHETIC"),
                            "erasureOperators",
                            List.of("SYNTHETIC"),
                            "boundaries",
                            synthetic);
            case "keys" ->
                    Map.of(
                            "encryption",
                            keys.get("encryption"),
                            "search",
                            keys.get("search"),
                            "limit",
                            keys.get("limit"),
                            "custodians",
                            List.of("SYNTHETIC"),
                            "rotationProcedure",
                            synthetic,
                            "restoreProcedure",
                            synthetic,
                            "lookupVer",
                            1);
            case "processors" ->
                    Map.of(
                            "boundary",
                            mail,
                            "socialProviders",
                            List.of(),
                            "gradingProcessors",
                            List.of(),
                            "retentionSeconds",
                            600,
                            "storageRestrictions",
                            synthetic,
                            "deletionProcedure",
                            synthetic,
                            "confirmationProcedure",
                            synthetic);
            case "copies" ->
                    Map.of(
                            "inventory",
                            List.of(
                                            "DATABASE_BACKUP",
                                            "WAL_PITR",
                                            "SNAPSHOT",
                                            "EXPORT",
                                            "MAIL",
                                            "PROCESSOR")
                                    .stream()
                                    .map(
                                            value ->
                                                    Map.of(
                                                            "mechanism",
                                                            value,
                                                            "lifetimeSeconds",
                                                            600,
                                                            "disposition",
                                                            "VERIFIED_ABSENT",
                                                            "evidence",
                                                            synthetic))
                                    .toList(),
                            "deletionLedger",
                            synthetic,
                            "restoreGuard",
                            synthetic);
            case "verification" ->
                    Map.of(
                            "localAuthErasure",
                            synthetic,
                            "mailDeletion",
                            synthetic,
                            "isolatedRestore",
                            synthetic,
                            "deletionRecordsApplied",
                            synthetic,
                            "excludedProducers",
                            List.of("SOCIAL", "GRADING", "PLAYTEST"),
                            "limitations",
                            synthetic);
            default -> throw new IllegalArgumentException(kind);
        };
    }
}
