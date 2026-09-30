package com.reasoning.common.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Shared account and relationship impact calculation for online and offline administration. */
@Service
public class AdminImpactService {
    private static final int SAMPLE_SIZE = 20;
    private static final Comparator<Relation> RELATION_ORDER =
            Comparator.comparing(Relation::storyCode).thenComparing(Relation::relation);

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public AdminImpactService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Calculates the complete version-one impact hash and a bounded preview of retained relations.
     * The caller must authorize access and provide a consistent read snapshot or hold the target
     * account lock across this calculation and the subsequent hash comparison. Relationship writers
     * must respect that lock. This method joins the caller's transaction and never establishes its
     * own snapshot or authorization.
     *
     * @param accountKey exact non-null account UUID, not proof of authorization
     * @return current account impact with all relationships included in the hash and at most 20
     *     sampled relations
     * @throws AuthException when the key is null or the account is missing
     * @throws IllegalStateException when canonical serialization or SHA-256 is unavailable
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Impact getImpact(UUID accountKey) {
        if (accountKey == null) throw AuthException.badRequest("INVALID_ACCOUNT_KEY");
        List<AccountState> accounts =
                db.query(
                        "SELECT a.id,a.account_key,a.edit_rev::text,c.auth_rev::text,c.enrolled_at"
                            + " IS NOT"
                            + " NULL,c.mfa_state,a.active_yn,a.can_create,a.can_manage,a.can_publish,a.can_review"
                            + " FROM admin_account a JOIN admin_credential c ON c.account_id=a.id"
                            + " WHERE a.account_key=?",
                        (rs, row) ->
                                new AccountState(
                                        rs.getLong(1),
                                        (UUID) rs.getObject(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getBoolean(5),
                                        rs.getString(6),
                                        rs.getBoolean(7),
                                        rs.getBoolean(8),
                                        rs.getBoolean(9),
                                        rs.getBoolean(10),
                                        rs.getBoolean(11)),
                        accountKey);
        if (accounts.isEmpty()) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        AccountState account = accounts.getFirst();
        List<String> permissions = new ArrayList<>(4);
        if (account.create()) permissions.add("CREATE");
        if (account.manage()) permissions.add("MANAGE");
        if (account.publish()) permissions.add("PUBLISH");
        if (account.review()) permissions.add("REVIEW");
        permissions = List.copyOf(permissions);

        List<Relation> relations =
                db.query(
                        "SELECT s.code,'OWNER' AS relation,true AS active_yn FROM story s WHERE"
                            + " s.owner_id=? UNION ALL SELECT s.code,sa.permission,sa.active_yn"
                            + " FROM story_access sa JOIN story s ON s.id=sa.story_id WHERE"
                            + " sa.admin_id=?",
                        (rs, row) ->
                                new Relation(rs.getString(1), rs.getString(2), rs.getBoolean(3)),
                        account.id(),
                        account.id());
        relations.sort(RELATION_ORDER);
        long ownedCount = 0;
        long accessCount = 0;
        List<List<Object>> relationRecords = new ArrayList<>(relations.size());
        for (Relation relation : relations) {
            if (relation.relation().equals("OWNER")) ownedCount++;
            else if (relation.activeYn()) accessCount++;
            relationRecords.add(
                    List.of(relation.storyCode(), relation.relation(), relation.activeYn()));
        }
        List<Object> canonical =
                List.of(
                        1,
                        account.key().toString(),
                        account.editRev(),
                        account.authRev(),
                        account.enrolled(),
                        account.mfaState(),
                        account.active(),
                        permissions,
                        relationRecords);
        String impactHash;
        try {
            byte[] bytes =
                    json.writer()
                            .without(SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsBytes(canonical);
            impactHash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Admin impact hashing failed", failure);
        }
        boolean truncated = relations.size() > SAMPLE_SIZE;
        return new Impact(
                account.key(),
                account.editRev(),
                account.authRev(),
                account.enrolled(),
                account.mfaState(),
                account.active(),
                permissions,
                ownedCount,
                accessCount,
                List.copyOf(relations.subList(0, Math.min(relations.size(), SAMPLE_SIZE))),
                truncated,
                impactHash);
    }

    /** A retained story ownership or access row, including inactive access rows. */
    public record Relation(String storyCode, String relation, boolean activeYn) {}

    /** Minimal account state, complete relation counts and hash, and bounded relation sample. */
    public record Impact(
            UUID accountKey,
            String editRev,
            String authRev,
            boolean enrolled,
            String mfaState,
            boolean activeYn,
            List<String> permissions,
            long ownedCount,
            long accessCount,
            List<Relation> relationSample,
            boolean truncated,
            String impactHash) {
        public Impact {
            permissions = List.copyOf(permissions);
            relationSample = List.copyOf(relationSample);
        }
    }

    private record AccountState(
            long id,
            UUID key,
            String editRev,
            String authRev,
            boolean enrolled,
            String mfaState,
            boolean active,
            boolean create,
            boolean manage,
            boolean publish,
            boolean review) {}
}
