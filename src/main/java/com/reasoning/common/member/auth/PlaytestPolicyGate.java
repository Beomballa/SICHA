package com.reasoning.common.member.auth;

import static com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AuthException;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Independent, fail-closed PLAYTEST evidence boundary. Never accepts MEMBER_AUTH evidence. */
public final class PlaytestPolicyGate {
    public record Notice(
            long policyId, String code, String hash, String version, String body, String contact) {}

    public record Snapshot(
            String env,
            long owner,
            String hash,
            Instant from,
            Instant until,
            Map<String, Artifact> artifacts) {}

    public record Artifact(
            String ref, String hash, Instant verified, Instant until, JsonNode claims) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Long> RETENTION =
            Map.of(
                    "inviteSeconds",
                    604800L,
                    "lobbySeconds",
                    1800L,
                    "resultSeconds",
                    86400L,
                    "rawSeconds",
                    7776000L,
                    "selectedSeconds",
                    31536000L,
                    "backupMaxSeconds",
                    3024000L);
    private final JdbcTemplate db;
    private final MemberAuthConfiguration.Properties properties;

    public PlaytestPolicyGate(JdbcTemplate db, MemberAuthConfiguration.Properties properties) {
        this.db = db;
        this.properties = properties;
    }

    public Snapshot prepare() {
        try {
            if (!properties.isPlaytestCollectionEnabled()
                    || properties.getEnvCode() == null
                    || !properties.getEnvCode().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,39}"))
                throw unavailable();
            JsonNode root = parse(read(properties.getPlaytestEvidenceRegistryFile(), 131072));
            exact(
                    root,
                    "formatNo",
                    "scope",
                    "envCode",
                    "ownerId",
                    "noticeHash",
                    "validFrom",
                    "validUntil",
                    "records");
            integer(root, "formatNo", 1);
            if (!"PLAYTEST".equals(text(root, "scope"))
                    || !properties.getEnvCode().equals(text(root, "envCode"))) throw unavailable();
            long owner = positive(root, "ownerId");
            String hash = hash(root, "noticeHash");
            Instant from = instant(root, "validFrom"), until = instant(root, "validUntil");
            if (!from.isBefore(until)) throw unavailable();
            JsonNode records = root.get("records");
            if (!records.isArray() || records.size() != 6) throw unavailable();
            Map<String, Artifact> result = new TreeMap<>();
            Set<String> paths = new HashSet<>(), refs = new HashSet<>();
            for (JsonNode entry : records) {
                exact(entry, "kind", "ref", "path", "sha256");
                String kind = text(entry, "kind"),
                        ref = reference(entry, "ref"),
                        path = text(entry, "path");
                String digest = hash(entry, "sha256");
                if (!KINDS.contains(kind)
                        || result.containsKey(kind)
                        || !refs.add(ref)
                        || !paths.add(path)) throw unavailable();
                byte[] bytes = read(path, 524288);
                if (!sha256(bytes).equals(digest)) throw unavailable();
                JsonNode artifact = parse(bytes);
                exact(
                        artifact,
                        "formatNo",
                        "kind",
                        "ref",
                        "envCode",
                        "scope",
                        "ownerId",
                        "noticeHash",
                        "verifiedAt",
                        "validUntil",
                        "claims");
                integer(artifact, "formatNo", 1);
                Instant verified = instant(artifact, "verifiedAt"),
                        expiry = instant(artifact, "validUntil");
                if (!kind.equals(text(artifact, "kind"))
                        || !ref.equals(reference(artifact, "ref"))
                        || !properties.getEnvCode().equals(text(artifact, "envCode"))
                        || !"PLAYTEST".equals(text(artifact, "scope"))
                        || owner != positive(artifact, "ownerId")
                        || !hash.equals(hash(artifact, "noticeHash"))
                        || verified.isAfter(from)
                        || expiry.isBefore(until)
                        || !verified.isBefore(expiry)) throw unavailable();
                JsonNode claims = artifact.get("claims");
                verifyClaims(kind, claims);
                result.put(kind, new Artifact(ref, digest, verified, expiry, claims.deepCopy()));
            }
            return new Snapshot(
                    properties.getEnvCode(), owner, hash, from, until, Map.copyOf(result));
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    /** Called under the owner/policy locks and again immediately before committing consent. */
    public Notice lock(Snapshot evidence) {
        return lock(evidence, null);
    }

    /**
     * 사전 잠금된 소유자 밖으로 정책이 바뀌면 새 소유자를 잠그지 않고 거절한다.
     *
     * @param evidence 잠금 전에 준비한 PLAYTEST 근거
     * @param lockedOwners 이미 ID순으로 잠근 관리자 ID 집합; null이면 직접 잠금
     * @return 현재 유효한 고지
     * @throws AuthException 정책 소유자·근거 또는 수집 상태가 달라졌을 때
     */
    public Notice lock(Snapshot evidence, Set<Long> lockedOwners) {
        try {
            if (!properties.isPlaytestCollectionEnabled()) throw unavailable();
            var rows =
                    db.query(
                            "SELECT id,code,notice_hash,owner_id,policy_data::text FROM"
                                    + " privacy_policy WHERE env_code=? AND scope='PLAYTEST' AND"
                                    + " state='ACTIVE'",
                            (rs, n) ->
                                    new Object[] {
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getString(5)
                                    },
                            evidence.env());
            if (rows.size() != 1) throw unavailable();
            Object[] row = rows.getFirst();
            long id = (long) row[0], owner = (long) row[3];
            if (owner != evidence.owner() || lockedOwners != null && !lockedOwners.contains(owner))
                throw unavailable();
            if (lockedOwners == null)
                db.query(
                        "SELECT id FROM admin_account WHERE id=? FOR SHARE",
                        (rs, n) -> rs.getLong(1),
                        owner);
            var locked =
                    db.query(
                            "SELECT id FROM privacy_policy WHERE id=? AND scope='PLAYTEST' AND"
                                    + " state='ACTIVE' FOR SHARE",
                            (rs, n) -> rs.getLong(1),
                            id);
            if (locked.size() != 1) throw unavailable();
            rows =
                    db.query(
                            "SELECT id,code,notice_hash,owner_id,policy_data::text FROM"
                                    + " privacy_policy WHERE env_code=? AND scope='PLAYTEST' AND"
                                    + " state='ACTIVE'",
                            (rs, n) ->
                                    new Object[] {
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getString(5)
                                    },
                            evidence.env());
            if (rows.size() != 1) throw unavailable();
            row = rows.getFirst();
            if ((long) row[0] != id || (long) row[3] != owner || !evidence.hash().equals(row[2]))
                throw unavailable();
            Boolean ready =
                    db.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM admin_account a JOIN admin_credential c ON"
                                + " c.account_id=a.id WHERE a.id=? AND a.active_yn AND a.can_manage"
                                + " AND c.enrolled_at IS NOT NULL AND c.mfa_state='READY')",
                            Boolean.class,
                            owner);
            if (!Boolean.TRUE.equals(ready)) throw unavailable();
            JsonNode data = parse(((String) row[4]).getBytes(StandardCharsets.UTF_8));
            exact(data, "formatNo", "notice", "validFrom", "validUntil", "retention", "evidence");
            integer(data, "formatNo", 1);
            Instant from = instant(data, "validFrom"), until = instant(data, "validUntil");
            if (!from.isBefore(until)
                    || evidence.from().isAfter(from)
                    || evidence.until().isBefore(until)) throw unavailable();
            JsonNode retention = data.get("retention");
            exact(retention, RETENTION.keySet().toArray(String[]::new));
            RETENTION.forEach((key, value) -> integer(retention, key, value));
            JsonNode notice = data.get("notice");
            exact(notice, "version", "body", "contact");
            for (String field : new String[] {"version", "body", "contact"}) {
                String value = text(notice, field);
                if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)
                        || value.codePoints()
                                .anyMatch(
                                        c ->
                                                Character.isISOControl(c)
                                                        && !(field.equals("body") && c == '\n'))
                        || (field.equals("version") && value.codePointCount(0, value.length()) > 60)
                        || (field.equals("body")
                                && value.getBytes(StandardCharsets.UTF_8).length > 32768)
                        || (field.equals("contact")
                                && value.getBytes(StandardCharsets.UTF_8).length > 512))
                    throw unavailable();
            }
            var commitment = JSON.createObjectNode();
            commitment.put("formatNo", 1);
            commitment.put("scope", "PLAYTEST");
            commitment.put("policyCode", (String) row[1]);
            commitment.set("notice", notice);
            if (!sha256(canonical(commitment).getBytes(StandardCharsets.UTF_8)).equals(row[2]))
                throw unavailable();
            JsonNode declared = data.get("evidence");
            exact(declared, KINDS.toArray(String[]::new));
            Instant now =
                    db.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
            if (now.isBefore(from)
                    || !now.isBefore(until)
                    || now.isBefore(evidence.from())
                    || !now.isBefore(evidence.until())) throw unavailable();
            for (String kind : KINDS) {
                JsonNode item = declared.get(kind);
                exact(item, "ref", "sha256", "envCode", "scope", "verifiedAt", "validUntil");
                Artifact actual = evidence.artifacts().get(kind);
                if (actual == null
                        || !actual.ref().equals(reference(item, "ref"))
                        || !actual.hash().equals(hash(item, "sha256"))
                        || !evidence.env().equals(text(item, "envCode"))
                        || !"PLAYTEST".equals(text(item, "scope"))
                        || !actual.verified().equals(instant(item, "verifiedAt"))
                        || !actual.until().equals(instant(item, "validUntil"))
                        || actual.verified().isAfter(now)
                        || !now.isBefore(actual.until())) throw unavailable();
            }
            if (!text(evidence.artifacts().get("responsibility").claims(), "contact")
                    .equals(text(notice, "contact"))) throw unavailable();
            return new Notice(
                    id,
                    (String) row[1],
                    (String) row[2],
                    text(notice, "version"),
                    text(notice, "body"),
                    text(notice, "contact"));
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    public static String noticeHash(String code, JsonNode notice) {
        var object = JSON.createObjectNode();
        object.put("formatNo", 1);
        object.put("scope", "PLAYTEST");
        object.put("policyCode", code);
        object.set("notice", notice);
        return sha256(canonical(object).getBytes(StandardCharsets.UTF_8));
    }

    private static void verifyClaims(String kind, JsonNode claims) {
        switch (kind) {
            case "responsibility" -> {
                exact(claims, "purpose", "items", "contact");
                required(claims, "purpose", "contact");
                strings(claims.get("items"));
            }
            case "access" -> {
                exact(
                        claims,
                        "privilegedReaders",
                        "securityOperators",
                        "erasureOperators",
                        "boundaries");
                strings(claims.get("privilegedReaders"));
                strings(claims.get("securityOperators"));
                strings(claims.get("erasureOperators"));
                required(claims, "boundaries");
            }
            case "keys" -> {
                exact(claims, "custodians", "rotationProcedure", "restoreProcedure");
                strings(claims.get("custodians"));
                required(claims, "rotationProcedure", "restoreProcedure");
            }
            case "processors" -> {
                exact(
                        claims,
                        "gradingProcessors",
                        "storageRestrictions",
                        "deletionProcedure",
                        "confirmationProcedure");
                strings(claims.get("gradingProcessors"));
                required(
                        claims,
                        "storageRestrictions",
                        "deletionProcedure",
                        "confirmationProcedure");
            }
            case "copies" -> {
                exact(claims, "inventory", "deletionLedger", "restoreGuard");
                strings(claims.get("inventory"));
                required(claims, "deletionLedger", "restoreGuard");
            }
            case "verification" -> {
                exact(
                        claims,
                        "consent",
                        "revocation",
                        "isolatedRestore",
                        "deletionRecordsApplied",
                        "limitations");
                required(
                        claims,
                        "consent",
                        "revocation",
                        "isolatedRestore",
                        "deletionRecordsApplied",
                        "limitations");
            }
            default -> throw unavailable();
        }
    }

    private static void required(JsonNode node, String... keys) {
        for (String key : keys)
            if (text(node, key).codePoints().anyMatch(Character::isISOControl)) throw unavailable();
    }

    private static void strings(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 128)
            throw unavailable();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : node)
            if (!item.isTextual()
                    || item.textValue().isBlank()
                    || item.textValue().length() > 4096
                    || item.textValue().codePoints().anyMatch(Character::isISOControl)
                    || !unique.add(item.textValue())) throw unavailable();
    }

    private static long positive(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() <= 0) throw unavailable();
        return value.longValue();
    }

    private static String reference(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) throw unavailable();
        return value;
    }

    private static String hash(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.matches("[0-9a-f]{64}")) throw unavailable();
        return value;
    }

    private static byte[] read(String name, int maximum) throws Exception {
        if (name == null || name.isBlank()) throw unavailable();
        Path path = Path.of(name);
        if (!path.isAbsolute()
                || !path.equals(path.normalize())
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || path.startsWith(Path.of("").toAbsolutePath().normalize())) throw unavailable();
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw unavailable();
        Set<PosixFilePermission> permissions =
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.contains(PosixFilePermission.OWNER_READ)
                || permissions.stream()
                        .anyMatch(
                                p ->
                                        p.name().startsWith("GROUP_")
                                                || p.name().startsWith("OTHERS_")))
            throw unavailable();
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
                .equals(
                        path.getFileSystem()
                                .getUserPrincipalLookupService()
                                .lookupPrincipalByName(System.getProperty("user.name"))))
            throw unavailable();
        Object identity =
                Files.readAttributes(
                                path,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS)
                        .fileKey();
        long length = Files.size(path);
        if (length < 2 || length > maximum) throw unavailable();
        byte[] bytes;
        try (var channel =
                Files.newByteChannel(
                        path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(maximum + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
            if (buffer.position() != length || buffer.position() > maximum) throw unavailable();
            bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
        }
        if (!java.util.Objects.equals(
                identity,
                Files.readAttributes(
                                path,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS)
                        .fileKey())) throw unavailable();
        return bytes;
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            try (var parser = JSON.createParser(text)) {
                parser.enable(
                        com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode value = JSON.readTree(parser);
                if (value == null || parser.nextToken() != null) throw unavailable();
                return value;
            }
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static AuthException unavailable() {
        return AuthException.unavailable("PLAYTEST_COLLECTION_NOT_READY");
    }
}
