package com.reasoning.common.member.auth;

import static com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AuthException;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** 소유자와 자격, 정책을 회원 행보다 먼저 잠그고 최종 쓰기 뒤 새 DB 시각으로 효력을 재검사한다. */
public final class MemberPolicyGate {
    public record NoticeText(String version, String body) {}

    public record Notice(
            String policyCode,
            String noticeHash,
            NoticeText notice,
            String contact,
            UUID requestId) {}

    public record Policy(
            long id,
            String code,
            String envCode,
            String state,
            String noticeHash,
            long ownerId,
            JsonNode data) {}

    public record Permit(Policy current, Long pinnedPolicyId, Snapshot evidence) {}

    private final JdbcTemplate db;
    private final MemberAuthConfiguration.Properties configuration;
    private final MemberPolicyEvidenceRegistry registry;
    private static final Map<String, Long> RETENTION =
            Map.ofEntries(
                    Map.entry("flowTtlSeconds", 600L),
                    Map.entry("flowCleanupGraceSeconds", 3600L),
                    Map.entry("accessTtlSeconds", 300L),
                    Map.entry("accessCleanupGraceSeconds", 3600L),
                    Map.entry("refreshIdleSeconds", 2592000L),
                    Map.entry("sessionAbsoluteSeconds", 7776000L),
                    Map.entry("familyCleanupGraceSeconds", 86400L),
                    Map.entry("limitMaxSeconds", 86400L),
                    Map.entry("accessHistoryMaxSeconds", 2592000L),
                    Map.entry("securityAuditMaxSeconds", 7776000L),
                    Map.entry("backupMaxSeconds", 3024000L));

    public MemberPolicyGate(
            JdbcTemplate db,
            MemberAuthConfiguration.Properties configuration,
            MemberPolicyEvidenceRegistry registry) {
        this.db = java.util.Objects.requireNonNull(db);
        this.configuration = java.util.Objects.requireNonNull(configuration);
        this.registry = java.util.Objects.requireNonNull(registry);
    }

    /** 파일·SMTP 구성 검사는 DB 잠금을 얻기 전에 실행한다. */
    public Snapshot prepare() {
        return registry.snapshot();
    }

    public Permit lock(Snapshot snapshot, Long memberId) {
        if (!configuration.isCollectionEnabled()) throw notReady();
        List<Policy> active =
                policies(
                        "env_code=? AND scope='MEMBER_AUTH' AND state='ACTIVE'",
                        configuration.getEnvCode());
        if (active.size() != 1) throw notReady();
        Policy selected = active.getFirst();
        Long pinned = null;
        if (memberId != null) {
            List<Long> values =
                    db.query(
                            "SELECT policy_id FROM member_profile WHERE member_id=?",
                            (rs, row) -> rs.getLong(1),
                            memberId);
            if (values.size() != 1) throw notReady();
            pinned = values.getFirst();
        }
        TreeSet<Long> owners = new TreeSet<>();
        owners.add(selected.ownerId());
        TreeSet<Long> ids = new TreeSet<>();
        ids.add(selected.id());
        if (pinned != null) {
            List<Policy> values = policies("id=?", pinned);
            if (values.size() != 1) throw notReady();
            owners.add(values.getFirst().ownerId());
            ids.add(pinned);
        }
        for (Long owner : owners) {
            List<Long> accounts =
                    db.query(
                            "SELECT id FROM admin_account WHERE id=? FOR SHARE",
                            (rs, row) -> rs.getLong(1),
                            owner);
            if (accounts.size() != 1) throw notReady();
            db.query(
                    "SELECT account_id FROM admin_credential WHERE account_id=? FOR SHARE",
                    (rs, row) -> rs.getLong(1),
                    owner);
        }
        for (Long id : ids) if (policies("id=? FOR SHARE", id).size() != 1) throw notReady();
        Permit permit = new Permit(selected, pinned, snapshot);
        check(permit);
        return permit;
    }

    public void check(Permit permit) {
        List<Policy> active =
                policies(
                        "env_code=? AND scope='MEMBER_AUTH' AND state='ACTIVE'",
                        configuration.getEnvCode());
        if (!configuration.isCollectionEnabled()
                || active.size() != 1
                || active.getFirst().id() != permit.current().id()) throw notReady();
        Policy current = active.getFirst();
        Boolean ready =
                db.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM admin_account a JOIN admin_credential c ON"
                            + " c.account_id=a.id WHERE a.id=? AND a.active_yn AND a.can_manage AND"
                            + " c.enrolled_at IS NOT NULL AND c.mfa_state='READY')",
                        Boolean.class,
                        current.ownerId());
        if (!Boolean.TRUE.equals(ready) || current.ownerId() != permit.evidence().ownerId())
            throw notReady();
        validateDocument(current);
        Instant now = clock();
        Instant from = instant(current.data(), "validFrom"),
                until = instant(current.data(), "validUntil");
        if (now.isBefore(from) || !now.isBefore(until)) throw notReady();
        registry.validate(
                permit.evidence(),
                current.ownerId(),
                current.noticeHash(),
                from,
                until,
                current.data().get("evidence"),
                current.data().get("notice"),
                now);
        if (permit.pinnedPolicyId() != null) {
            List<Policy> pinned = policies("id=?", permit.pinnedPolicyId());
            if (pinned.size() != 1
                    || !List.of("ACTIVE", "RETIRED").contains(pinned.getFirst().state()))
                throw notReady();
            validateDocument(pinned.getFirst());
        }
    }

    public void checkPinned(Permit permit, long memberId) {
        Long actual =
                db.queryForObject(
                        "SELECT policy_id FROM member_profile WHERE member_id=?",
                        Long.class,
                        memberId);
        if (!java.util.Objects.equals(actual, permit.pinnedPolicyId())) throw notReady();
    }

    public void requireNotice(Permit permit, String code, String hash) {
        if (!permit.current().code().equals(code) || !permit.current().noticeHash().equals(hash))
            throw AuthException.conflict("POLICY_CHANGED");
    }

    public Notice notice(Permit permit, UUID requestId) {
        if (requestId == null) throw AuthException.badRequest("INVALID_REQUEST");
        check(permit);
        JsonNode notice = permit.current().data().get("notice");
        return new Notice(
                permit.current().code(),
                permit.current().noticeHash(),
                new NoticeText(text(notice, "version"), text(notice, "body")),
                text(notice, "contact"),
                requestId);
    }

    public Instant clock() {
        Timestamp timestamp = db.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        if (timestamp == null) throw AuthException.unavailable("AUTH_UNAVAILABLE");
        return timestamp.toInstant();
    }

    private List<Policy> policies(String condition, Object... args) {
        return db.query(
                "SELECT id,code,env_code,state,notice_hash,owner_id,policy_data::text FROM"
                        + " privacy_policy WHERE "
                        + condition,
                (rs, row) ->
                        new Policy(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getLong(6),
                                parse(rs.getString(7).getBytes(StandardCharsets.UTF_8))),
                args);
    }

    private void validateDocument(Policy policy) {
        if (!policy.envCode().equals(configuration.getEnvCode())) throw notReady();
        JsonNode data = policy.data();
        exact(
                data,
                "formatNo",
                "notice",
                "validFrom",
                "validUntil",
                "authProviders",
                "retention",
                "evidence");
        integer(data, "formatNo", 1);
        JsonNode providers = data.get("authProviders");
        if (!providers.isArray()
                || providers.size() != 1
                || !providers.get(0).isTextual()
                || !providers.get(0).textValue().equals("LOCAL")) throw notReady();
        JsonNode notice = data.get("notice");
        exact(notice, "version", "body", "contact");
        for (String field : List.of("version", "body", "contact")) {
            String value = text(notice, field);
            if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)
                    || value.codePoints()
                            .anyMatch(
                                    c ->
                                            Character.isISOControl(c)
                                                    && !(field.equals("body") && c == '\n'))
                    || field.equals("version") && value.codePointCount(0, value.length()) > 60
                    || field.equals("body") && value.getBytes(StandardCharsets.UTF_8).length > 32768
                    || field.equals("contact")
                            && value.getBytes(StandardCharsets.UTF_8).length > 512)
                throw notReady();
        }
        Instant from = instant(data, "validFrom"), until = instant(data, "validUntil");
        if (!from.isBefore(until)) throw notReady();
        JsonNode retention = data.get("retention");
        exact(retention, RETENTION.keySet().toArray(String[]::new));
        RETENTION.forEach((key, seconds) -> integer(retention, key, seconds));
        exact(data.get("evidence"), KINDS.toArray(String[]::new));
        for (String kind : KINDS) {
            JsonNode entry = data.get("evidence").get(kind);
            exact(entry, "ref", "sha256", "envCode", "scope", "verifiedAt", "validUntil");
            if (!text(entry, "ref").matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
                    || !text(entry, "sha256").matches("[0-9a-f]{64}")
                    || !text(entry, "envCode").equals(policy.envCode())
                    || !text(entry, "scope").equals("MEMBER_AUTH")
                    || !instant(entry, "verifiedAt").isBefore(instant(entry, "validUntil"))
                    || instant(entry, "verifiedAt").isAfter(from)
                    || instant(entry, "validUntil").isBefore(until)) throw notReady();
        }
        if (!noticeHash(policy.code(), notice).equals(policy.noticeHash())) throw notReady();
    }

    /** 등록 도구와 합성 픽스처가 같은 정렬 JSON 바이트를 사용할 수 있는 고지 결속 함수다. */
    public static String noticeHash(String policyCode, JsonNode notice) {
        var commitment = new ObjectMapper().createObjectNode();
        commitment.put("formatNo", 1);
        commitment.put("scope", "MEMBER_AUTH");
        commitment.put("policyCode", policyCode);
        commitment.set("notice", notice);
        return sha256(canonical(commitment).getBytes(StandardCharsets.UTF_8));
    }
}
