package com.reasoning.common.member.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.AuthProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 외부 소유자 전용 파일을 실제 승인 증거로 소비한다. 해시는 파기 실행이나 운영 품질을 증명하지 않는다. 매니페스트와 각 산출물은 절대 경로의 UTF-8 JSON이며
 * 중복/미지 키와 형 변환을 허용하지 않는다. 매니페스트의 정확한 키는 formatNo(정수 1), envCode, scope(MEMBER_AUTH), ownerId(양의
 * 정수), noticeHash, validFrom, validUntil, keyFiles, mail, records다. keyFiles는
 * encryption/search/limit의 실제 공유 키 파일 절대 경로다. mail은 host, port(정수), protocol(smtp), from,
 * timeoutMillis(5000)다. records는 여섯 항목이며 각 항목은 kind, ref, path, sha256다. kind는
 * responsibility/access/keys/ processors/copies/verification 중 하나이고 ref는 비밀 없는 식별자, path는 명시적으로 등록한
 * 산출물 경로다. 산출물의 정확한 키는 formatNo, kind, ref, envCode, scope, ownerId, noticeHash, verifiedAt,
 * validUntil, claims다. 승인 기간은 UTC Z 표기이며 정책 기간 전체를 포함해야 한다. claims의 정확한 형식:
 * responsibility={purpose,items:[문자열],contact};
 * access={privilegedReaders:[문자열],securityOperators:[문자열],erasureOperators:[문자열],boundaries};
 * keys={encryption,search,limit,custodians:[문자열],rotationProcedure,restoreProcedure,lookupVer:1};
 * processors={boundary:mail과 동일,socialProviders:[],gradingProcessors:[],retentionSeconds,
 * storageRestrictions,deletionProcedure,confirmationProcedure};
 * copies={inventory:[{mechanism,lifetimeSeconds,disposition,evidence}],deletionLedger,restoreGuard};
 * inventory는 DATABASE_BACKUP/WAL_PITR/SNAPSHOT/EXPORT/MAIL/PROCESSOR를 각각 한 번 포함하고 disposition은
 * PRESENT 또는 VERIFIED_ABSENT다. 부재도 실제 확인 기록을 요구한다.
 * verification={localAuthErasure,mailDeletion,isolatedRestore,deletionRecordsApplied,
 * excludedProducers:[SOCIAL,GRADING,PLAYTEST],limitations}이며 각 절차 값은 실제 검증 기록 식별/서술이다. 승인자는 파일
 * 소유자이며 이 경계 밖 임의 정책 참조·URL·준비 여부 boolean을 승인 근거로 취급하지 않는다.
 */
public final class MemberPolicyEvidenceRegistry {
    public static final Set<String> KINDS =
            Set.of("responsibility", "access", "keys", "processors", "copies", "verification");
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public record Evidence(
            String kind,
            String ref,
            String sha256,
            String envCode,
            long ownerId,
            String noticeHash,
            Instant verifiedAt,
            Instant validUntil,
            JsonNode claims) {}

    public record Snapshot(
            String envCode,
            long ownerId,
            String noticeHash,
            Instant validFrom,
            Instant validUntil,
            MemberMailTransport.Boundary mail,
            Map<String, Evidence> records) {}

    private final MemberAuthConfiguration.Properties configuration;
    private final AuthProperties keys;
    private final MemberMailTransport mail;

    public MemberPolicyEvidenceRegistry(
            MemberAuthConfiguration.Properties configuration,
            AuthProperties keys,
            MemberMailTransport mail) {
        this.configuration = java.util.Objects.requireNonNull(configuration);
        this.keys = java.util.Objects.requireNonNull(keys);
        this.mail = java.util.Objects.requireNonNull(mail);
    }

    public Snapshot snapshot() {
        try {
            if (!configuration.isCollectionEnabled()
                    || configuration.getEnvCode() == null
                    || !configuration.getEnvCode().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,39}"))
                throw notReady();
            JsonNode manifest =
                    parse(readProtected(configuration.getEvidenceRegistryFile(), 131072));
            exact(
                    manifest,
                    "formatNo",
                    "envCode",
                    "scope",
                    "ownerId",
                    "noticeHash",
                    "validFrom",
                    "validUntil",
                    "keyFiles",
                    "mail",
                    "records");
            integer(manifest, "formatNo", 1);
            String environment = text(manifest, "envCode");
            if (!environment.equals(configuration.getEnvCode())
                    || !text(manifest, "scope").equals("MEMBER_AUTH")) throw notReady();
            long owner = positiveLong(manifest, "ownerId");
            String notice = hash(manifest, "noticeHash");
            Instant from = instant(manifest, "validFrom"), until = instant(manifest, "validUntil");
            if (!from.isBefore(until)) throw notReady();
            JsonNode references = manifest.get("keyFiles");
            exact(references, "encryption", "search", "limit");
            String encryption = keyReference(keys.getCryptoKeyFile());
            String search = keyReference(keys.getSearchKeyFile());
            String limit = keyReference(keys.getLimitKeyFile());
            if (Set.of(encryption, search, limit).size() != 3
                    || !text(references, "encryption").equals(encryption)
                    || !text(references, "search").equals(search)
                    || !text(references, "limit").equals(limit)) throw notReady();
            byte[] e = keys.decodeRequiredKey(encryption, "MEMBER_KEY"),
                    s = keys.decodeRequiredKey(search, "MEMBER_KEY"),
                    l = keys.decodeRequiredKey(limit, "MEMBER_KEY");
            if (MessageDigest.isEqual(e, s)
                    || MessageDigest.isEqual(e, l)
                    || MessageDigest.isEqual(s, l)) throw notReady();
            java.util.Arrays.fill(e, (byte) 0);
            java.util.Arrays.fill(s, (byte) 0);
            java.util.Arrays.fill(l, (byte) 0);
            MemberMailTransport.Boundary boundary = mail.boundary();
            validateMail(manifest.get("mail"), boundary);
            JsonNode entries = manifest.get("records");
            if (!entries.isArray() || entries.size() != 6) throw notReady();
            Map<String, Evidence> records = new TreeMap<>();
            Set<String> refs = new HashSet<>(), paths = new HashSet<>();
            for (JsonNode entry : entries) {
                exact(entry, "kind", "ref", "path", "sha256");
                String kind = text(entry, "kind"),
                        ref = reference(entry, "ref"),
                        path = text(entry, "path"),
                        digest = hash(entry, "sha256");
                if (!KINDS.contains(kind)
                        || records.containsKey(kind)
                        || !refs.add(ref)
                        || !paths.add(path)) throw notReady();
                byte[] bytes = readProtected(path, 524288);
                if (!sha256(bytes).equals(digest)) throw notReady();
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
                        expires = instant(artifact, "validUntil");
                if (!text(artifact, "kind").equals(kind)
                        || !reference(artifact, "ref").equals(ref)
                        || !text(artifact, "envCode").equals(environment)
                        || !text(artifact, "scope").equals("MEMBER_AUTH")
                        || positiveLong(artifact, "ownerId") != owner
                        || !hash(artifact, "noticeHash").equals(notice)
                        || !verified.isBefore(expires)
                        || verified.isAfter(from)
                        || expires.isBefore(until)) throw notReady();
                JsonNode claims = artifact.get("claims");
                validateClaims(kind, claims, references, boundary);
                records.put(
                        kind,
                        new Evidence(
                                kind,
                                ref,
                                digest,
                                environment,
                                owner,
                                notice,
                                verified,
                                expires,
                                claims.deepCopy()));
            }
            return new Snapshot(
                    environment, owner, notice, from, until, boundary, Map.copyOf(records));
        } catch (Exception failure) {
            throw notReady();
        }
    }

    public void validate(
            Snapshot snapshot,
            long owner,
            String notice,
            Instant from,
            Instant until,
            JsonNode evidence,
            JsonNode publishedNotice,
            Instant now) {
        if (!configuration.isCollectionEnabled()
                || !snapshot.envCode().equals(configuration.getEnvCode())
                || snapshot.ownerId() != owner
                || !snapshot.noticeHash().equals(notice)
                || snapshot.validFrom().isAfter(from)
                || snapshot.validUntil().isBefore(until)
                || now.isBefore(snapshot.validFrom())
                || !now.isBefore(snapshot.validUntil())) throw notReady();
        exact(evidence, KINDS.toArray(String[]::new));
        for (String kind : KINDS) {
            JsonNode declared = evidence.get(kind);
            exact(declared, "ref", "sha256", "envCode", "scope", "verifiedAt", "validUntil");
            Evidence actual = snapshot.records().get(kind);
            if (actual == null
                    || !reference(declared, "ref").equals(actual.ref())
                    || !hash(declared, "sha256").equals(actual.sha256())
                    || !text(declared, "envCode").equals(snapshot.envCode())
                    || !text(declared, "scope").equals("MEMBER_AUTH")
                    || !instant(declared, "verifiedAt").equals(actual.verifiedAt())
                    || !instant(declared, "validUntil").equals(actual.validUntil())
                    || actual.verifiedAt().isAfter(from)
                    || actual.verifiedAt().isAfter(now)
                    || actual.validUntil().isBefore(until)
                    || !now.isBefore(actual.validUntil())) throw notReady();
        }
        if (!text(snapshot.records().get("responsibility").claims(), "contact")
                .equals(text(publishedNotice, "contact"))) throw notReady();
    }

    private static void validateClaims(
            String kind,
            JsonNode claims,
            JsonNode references,
            MemberMailTransport.Boundary boundary) {
        switch (kind) {
            case "responsibility" -> {
                exact(claims, "purpose", "items", "contact");
                texts(claims, "purpose", "contact");
                strings(claims.get("items"), false);
            }
            case "access" -> {
                exact(
                        claims,
                        "privilegedReaders",
                        "securityOperators",
                        "erasureOperators",
                        "boundaries");
                strings(claims.get("privilegedReaders"), false);
                strings(claims.get("securityOperators"), false);
                strings(claims.get("erasureOperators"), false);
                texts(claims, "boundaries");
            }
            case "keys" -> {
                exact(
                        claims,
                        "encryption",
                        "search",
                        "limit",
                        "custodians",
                        "rotationProcedure",
                        "restoreProcedure",
                        "lookupVer");
                integer(claims, "lookupVer", 1);
                strings(claims.get("custodians"), false);
                texts(claims, "rotationProcedure", "restoreProcedure");
                for (String key : List.of("encryption", "search", "limit"))
                    if (!text(claims, key).equals(text(references, key))) throw notReady();
            }
            case "processors" -> {
                exact(
                        claims,
                        "boundary",
                        "socialProviders",
                        "gradingProcessors",
                        "retentionSeconds",
                        "storageRestrictions",
                        "deletionProcedure",
                        "confirmationProcedure");
                validateMail(claims.get("boundary"), boundary);
                empty(claims.get("socialProviders"));
                empty(claims.get("gradingProcessors"));
                boundedSeconds(claims, "retentionSeconds");
                texts(claims, "storageRestrictions", "deletionProcedure", "confirmationProcedure");
            }
            case "copies" -> {
                exact(claims, "inventory", "deletionLedger", "restoreGuard");
                texts(claims, "deletionLedger", "restoreGuard");
                JsonNode inventory = claims.get("inventory");
                if (!inventory.isArray() || inventory.size() != 6) throw notReady();
                Set<String> mechanisms = new HashSet<>();
                for (JsonNode copy : inventory) {
                    exact(copy, "mechanism", "lifetimeSeconds", "disposition", "evidence");
                    if (!mechanisms.add(text(copy, "mechanism"))
                            || !Set.of("PRESENT", "VERIFIED_ABSENT")
                                    .contains(text(copy, "disposition"))) throw notReady();
                    boundedSeconds(copy, "lifetimeSeconds");
                    texts(copy, "evidence");
                }
                if (!mechanisms.equals(
                        Set.of(
                                "DATABASE_BACKUP",
                                "WAL_PITR",
                                "SNAPSHOT",
                                "EXPORT",
                                "MAIL",
                                "PROCESSOR"))) throw notReady();
            }
            case "verification" -> {
                exact(
                        claims,
                        "localAuthErasure",
                        "mailDeletion",
                        "isolatedRestore",
                        "deletionRecordsApplied",
                        "excludedProducers",
                        "limitations");
                texts(
                        claims,
                        "localAuthErasure",
                        "mailDeletion",
                        "isolatedRestore",
                        "deletionRecordsApplied",
                        "limitations");
                if (!strings(claims.get("excludedProducers"), false)
                        .equals(Set.of("SOCIAL", "GRADING", "PLAYTEST"))) throw notReady();
            }
            default -> throw notReady();
        }
    }

    private static void validateMail(JsonNode value, MemberMailTransport.Boundary actual) {
        exact(value, "host", "port", "protocol", "from", "timeoutMillis");
        if (!text(value, "host").equals(actual.host())
                || !text(value, "protocol").equals(actual.protocol())
                || !text(value, "from").equals(actual.from())) throw notReady();
        integer(value, "port", actual.port());
        integer(value, "timeoutMillis", actual.timeoutMillis());
    }

    private static String keyReference(String value) throws Exception {
        readProtected(value, 128);
        return Path.of(value).toAbsolutePath().normalize().toString();
    }

    private static byte[] readProtected(String value, int maximum) throws Exception {
        if (value == null || value.isBlank()) throw notReady();
        Path path = Path.of(value);
        if (!path.isAbsolute()
                || !path.equals(path.normalize())
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || path.startsWith(Path.of("").toAbsolutePath().normalize())) throw notReady();
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw notReady();
        Set<PosixFilePermission> permissions =
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.contains(PosixFilePermission.OWNER_READ)
                || permissions.stream()
                        .anyMatch(
                                p ->
                                        p.name().startsWith("GROUP_")
                                                || p.name().startsWith("OTHERS_")))
            throw notReady();
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
                .equals(
                        path.getFileSystem()
                                .getUserPrincipalLookupService()
                                .lookupPrincipalByName(System.getProperty("user.name"))))
            throw notReady();
        Object identity =
                Files.readAttributes(
                                path,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS)
                        .fileKey();
        long length = Files.size(path);
        if (length < 2 || length > maximum) throw notReady();
        byte[] bytes;
        try (var channel =
                Files.newByteChannel(
                        path,
                        Set.of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var buffer = java.nio.ByteBuffer.allocate(maximum + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
            if (buffer.position() != length || buffer.position() > maximum) throw notReady();
            bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
        }
        if (!java.util.Objects.equals(
                identity,
                Files.readAttributes(
                                path,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS)
                        .fileKey())) throw notReady();
        return bytes;
    }

    public static JsonNode parse(byte[] bytes) {
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            try (JsonParser parser = JSON.createParser(text)) {
                JsonNode value = JSON.readTree(parser);
                if (value == null || parser.nextToken() != null) throw notReady();
                return value;
            }
        } catch (Exception failure) {
            throw notReady();
        }
    }

    public static void exact(JsonNode node, String... keys) {
        if (node == null || !node.isObject() || node.size() != keys.length) throw notReady();
        Set<String> expected = Set.of(keys);
        node.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!expected.contains(key)) throw notReady();
                        });
        for (String key : keys) if (!node.hasNonNull(key)) throw notReady();
    }

    public static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null
                || !value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > 32768) throw notReady();
        return value.textValue();
    }

    public static void integer(JsonNode node, String key, long expected) {
        JsonNode value = node.get(key);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() != expected) throw notReady();
    }

    public static Instant instant(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.matches(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z"))
            throw notReady();
        try {
            return Instant.parse(value);
        } catch (Exception failure) {
            throw notReady();
        }
    }

    private static long positiveLong(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() <= 0) throw notReady();
        return value.longValue();
    }

    private static String reference(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) throw notReady();
        return value;
    }

    private static String hash(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.matches("[0-9a-f]{64}")) throw notReady();
        return value;
    }

    private static void texts(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)
                    || value.codePoints().anyMatch(Character::isISOControl)) throw notReady();
        }
    }

    private static void boundedSeconds(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0
                || value.longValue() > 3024000) throw notReady();
    }

    private static void empty(JsonNode node) {
        if (node == null || !node.isArray() || !node.isEmpty()) throw notReady();
    }

    private static Set<String> strings(JsonNode node, boolean allowEmpty) {
        if (node == null || !node.isArray() || node.size() > 128 || !allowEmpty && node.isEmpty())
            throw notReady();
        Set<String> values = new HashSet<>();
        for (JsonNode item : node)
            if (!item.isTextual()
                    || item.textValue().isBlank()
                    || item.textValue().length() > 4096
                    || item.textValue().codePoints().anyMatch(Character::isISOControl)
                    || !values.add(item.textValue())) throw notReady();
        return values;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw notReady();
        }
    }

    public static String canonical(JsonNode node) {
        try {
            return JSON.writeValueAsString(sorted(node));
        } catch (Exception failure) {
            throw notReady();
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var object = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) object.set(name, sorted(node.get(name)));
            return object;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            for (JsonNode item : node) array.add(sorted(item));
            return array;
        }
        return node;
    }

    static AuthException notReady() {
        return AuthException.unavailable("COLLECTION_NOT_READY");
    }
}
