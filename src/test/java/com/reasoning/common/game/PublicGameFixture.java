package com.reasoning.common.game;

import com.reasoning.common.member.auth.MemberAuthConfiguration;
import com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.story.service.PlaytestReportFixture;

import org.springframework.beans.factory.annotation.Autowired;

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

/** 격리 시험 전용 합성 FRIENDLY 고지와 보호 파일을 제공한다. 공개 승인 근거는 생성하지 않는다. */
abstract class PublicGameFixture extends PlaytestReportFixture {
    @Autowired protected MemberAuthConfiguration.Properties friendlyConfiguration;

    protected record Friendly(long id, String code, String hash) {}

    /**
     * 독립 보호 파일 여섯 개를 현재 합성 환경에 연결하고 수집 게이트를 시험 동안 활성화한다.
     *
     * @return 합성 정책의 ID, 코드, 고지 해시
     * @throws Exception 보호 파일 또는 정책 쓰기에 실패할 때
     */
    protected Friendly friendlyPolicy() throws Exception {
        return friendlyPolicy("합성 공개 게임 고지");
    }

    /** 기록 시나리오의 기존 고지 원문을 유지하면서 같은 독립 근거 파일을 사용한다. */
    protected Friendly friendlyPolicy(String noticeBody) throws Exception {
        long owner =
                db.queryForObject(
                        "SELECT owner_id FROM privacy_policy WHERE"
                                + " scope='MEMBER_AUTH' AND state='ACTIVE'",
                        Long.class);
        String code = "FRIENDLY_" + UUID.randomUUID().toString().replace("-", "");
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);
        Instant until = from.plusSeconds(86400);
        var notice =
                JSON.valueToTree(
                        Map.of(
                                "version",
                                "1",
                                "body",
                                noticeBody,
                                "contact",
                                "synthetic@example.invalid"));
        String hash = PlaytestPolicyGate.friendlyNoticeHash(code, notice);
        var evidence = new TreeMap<String, Object>();
        var records = new ArrayList<Map<String, Object>>();
        for (String kind : MemberPolicyEvidenceRegistry.KINDS) {
            String ref = "friendly:" + kind;
            Object claims =
                    switch (kind) {
                        case "responsibility" ->
                                Map.of(
                                        "purpose",
                                        "합성 공개 게임",
                                        "items",
                                        List.of("synthetic gameplay"),
                                        "contact",
                                        "synthetic@example.invalid");
                        case "access" ->
                                Map.of(
                                        "privilegedReaders",
                                        List.of("synthetic"),
                                        "securityOperators",
                                        List.of("synthetic"),
                                        "erasureOperators",
                                        List.of("synthetic"),
                                        "boundaries",
                                        "isolated test");
                        case "keys" ->
                                Map.of(
                                        "custodians",
                                        List.of("synthetic"),
                                        "rotationProcedure",
                                        "synthetic fixture",
                                        "restoreProcedure",
                                        "synthetic fixture");
                        case "processors" ->
                                Map.of(
                                        "gradingProcessors",
                                        List.of("none"),
                                        "storageRestrictions",
                                        "synthetic only",
                                        "deletionProcedure",
                                        "purge fixture",
                                        "confirmationProcedure",
                                        "test assertions");
                        case "copies" ->
                                Map.of(
                                        "inventory",
                                        List.of("none"),
                                        "deletionLedger",
                                        "synthetic",
                                        "restoreGuard",
                                        "synthetic");
                        case "verification" ->
                                Map.of(
                                        "consent",
                                        "synthetic",
                                        "revocation",
                                        "synthetic",
                                        "isolatedRestore",
                                        "synthetic",
                                        "deletionRecordsApplied",
                                        "synthetic",
                                        "limitations",
                                        "not operational evidence");
                        default -> throw new IllegalArgumentException(kind);
                    };
            byte[] bytes =
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "formatNo",
                                    1,
                                    "kind",
                                    kind,
                                    "ref",
                                    ref,
                                    "envCode",
                                    "SYNTHETIC_HTTP",
                                    "scope",
                                    "FRIENDLY",
                                    "ownerId",
                                    owner,
                                    "noticeHash",
                                    hash,
                                    "verifiedAt",
                                    from.toString(),
                                    "validUntil",
                                    until.toString(),
                                    "claims",
                                    claims));
            String digest = MemberPolicyEvidenceRegistry.sha256(bytes);
            records.add(
                    Map.of(
                            "kind",
                            kind,
                            "ref",
                            ref,
                            "path",
                            protectedFile(bytes).toString(),
                            "sha256",
                            digest));
            evidence.put(
                    kind,
                    Map.of(
                            "ref",
                            ref,
                            "sha256",
                            digest,
                            "envCode",
                            "SYNTHETIC_HTTP",
                            "scope",
                            "FRIENDLY",
                            "verifiedAt",
                            from.toString(),
                            "validUntil",
                            until.toString()));
        }
        friendlyConfiguration.setFriendlyEvidenceRegistryFile(
                protectedFile(
                                JSON.writeValueAsBytes(
                                        Map.of(
                                                "formatNo",
                                                1,
                                                "scope",
                                                "FRIENDLY",
                                                "envCode",
                                                "SYNTHETIC_HTTP",
                                                "ownerId",
                                                owner,
                                                "noticeHash",
                                                hash,
                                                "validFrom",
                                                from.toString(),
                                                "validUntil",
                                                until.toString(),
                                                "records",
                                                records)))
                        .toString());
        var document =
                Map.of(
                        "formatNo",
                        1,
                        "notice",
                        notice,
                        "validFrom",
                        from.toString(),
                        "validUntil",
                        until.toString(),
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
                        evidence);
        long id =
                db.queryForObject(
                        "INSERT INTO"
                            + " privacy_policy(code,env_code,scope,state,notice_hash,policy_data,owner_id)"
                            + " VALUES (?,'SYNTHETIC_HTTP','FRIENDLY','DRAFT',?,?::jsonb,?)"
                            + " RETURNING id",
                        Long.class,
                        code,
                        hash,
                        JSON.writeValueAsString(document),
                        owner);
        db.update("UPDATE privacy_policy SET state='ACTIVE' WHERE id=?", id);
        friendlyConfiguration.setFriendlyCollectionEnabled(true);
        return new Friendly(id, code, hash);
    }

    private static Path protectedFile(byte[] bytes) throws Exception {
        Path path = Files.createTempFile("public-game-synthetic-", ".json").toRealPath();
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Files.write(path, bytes);
        path.toFile().deleteOnExit();
        return path;
    }
}
