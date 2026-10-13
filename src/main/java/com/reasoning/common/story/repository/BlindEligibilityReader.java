package com.reasoning.common.story.repository;

import org.springframework.jdbc.core.JdbcTemplate;

/** 잠금이나 쓰기 없이 실제 출처의 BLIND 평가 및 최초 노출 사실을 판정한다. */
public final class BlindEligibilityReader {
    public enum Use {
        ADMISSION,
        EXECUTION,
        EVIDENCE
    }

    private static final String OCCUPIED =
            """
            EXISTS(SELECT 1 FROM test_member m JOIN play_test t ON t.id=m.test_id
              JOIN story_version v ON v.id=t.version_id
              WHERE v.story_id=a.story_id AND m.member_id=a.member_id AND (%s IS NULL OR t.id<>%s)
                AND (t.state='RUNNING' OR (t.state='WAITING' AND t.invite_until>clock.now
                  AND m.invite_state='ACCEPTED')))
            OR EXISTS(SELECT 1 FROM game_member m JOIN game_room r ON r.id=m.room_id
              WHERE r.story_id=a.story_id AND m.member_id=a.member_id AND m.active_yn
                AND r.purpose='PUBLIC' AND r.state IN ('WAITING','RUNNING'))
            OR EXISTS(SELECT 1 FROM game_room r WHERE r.story_id=a.story_id AND r.selected_member_id=a.member_id
              AND (%s IS NULL OR r.id<>%s) AND r.purpose='REVIEW_SOLO_QUALIFICATION'
              AND (r.state='RUNNING' OR (r.state='WAITING' AND r.lobby_until>clock.now)))
            """;

    private static final String CURRENT =
            """
            a.decision='ELIGIBLE' AND a.revoked_at IS NULL
            AND a.checked_at<=clock.now AND clock.now<a.valid_until
            AND a.id=(SELECT max(latest.id) FROM blind_assessment latest
              WHERE latest.story_id=a.story_id AND latest.member_id=a.member_id)
            """;

    private final JdbcTemplate db;

    /**
     * @param db 현재 호출자와 같은 연결/거래를 사용하는 JDBC 템플릿
     */
    public BlindEligibilityReader(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 잠긴 부모 아래 새 입장의 현행 평가를 찾는다.
     *
     * @param storyId 사건 ID, 양수
     * @param snapshotId 고정 사본 ID, 양수
     * @param memberId 회원 ID, 양수
     * @return 노출·예약이 없고 현재 유효한 평가 ID, 없으면 null
     */
    public Long currentForAdmission(long storyId, long snapshotId, long memberId) {
        if (storyId <= 0 || snapshotId <= 0 || memberId <= 0) return null;
        return db.query(
                """
                WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS now)
                SELECT a.id FROM blind_assessment a CROSS JOIN clock
                WHERE a.story_id=? AND a.snapshot_id=? AND a.member_id=?
                  AND %s
                  AND NOT EXISTS(SELECT 1 FROM test_exposure e WHERE e.story_id=a.story_id AND e.member_id=a.member_id)
                  AND NOT (%s)
                """
                        .formatted(CURRENT, occupied("NULL", "NULL")),
                rs -> rs.next() ? rs.getLong(1) : null,
                storyId,
                snapshotId,
                memberId);
    }

    /**
     * 평가 생성 전에 사건·회원에 대한 노출과 살아 있는 예약을 검사한다.
     *
     * @param storyId 사건 ID, 양수
     * @param memberId 회원 ID, 양수
     * @return 새 평가가 가능한 경우 true
     */
    public boolean unoccupied(long storyId, long memberId) {
        if (storyId <= 0 || memberId <= 0) return false;
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS now),
                          a AS (SELECT ?::bigint AS story_id, ?::bigint AS member_id)
                        SELECT NOT EXISTS(SELECT 1 FROM test_exposure e WHERE e.story_id=a.story_id AND e.member_id=a.member_id)
                          AND NOT (%s) FROM a CROSS JOIN clock
                        """
                                .formatted(occupied("NULL", "NULL")),
                        Boolean.class,
                        storyId,
                        memberId));
    }

    /**
     * TEST의 실제 회원과 평가 pin에 대해 용도별 자격을 읽는다.
     *
     * @param storyId 사건 ID, 양수
     * @param snapshotId 고정 사본 ID, 양수
     * @param memberId 실제 참가자 ID, 양수
     * @param testId TEST 출처 ID, 양수
     * @param assessmentId 출처에 고정된 평가 ID, null 가능
     * @param use 입장·실행·근거 판정 용도, null이면 부적격
     * @return 실제 평가·선언·최초 노출 자격
     */
    public boolean test(
            long storyId, long snapshotId, long memberId, long testId, Long assessmentId, Use use) {
        return verdict(storyId, snapshotId, memberId, testId, assessmentId, use, true);
    }

    /**
     * SOLO 방의 선택 회원과 평가 pin에 대해 용도별 자격을 읽는다.
     *
     * @param storyId 사건 ID, 양수
     * @param snapshotId 고정 사본 ID, 양수
     * @param memberId 실제 선택 회원 ID, 양수
     * @param roomId SOLO 방 ID, 양수
     * @param assessmentId 방에 고정된 평가 ID, null 가능
     * @param use 입장·실행·근거 판정 용도, null이면 부적격
     * @return 실제 평가·선언·최초 노출 자격
     */
    public boolean game(
            long storyId, long snapshotId, long memberId, long roomId, Long assessmentId, Use use) {
        return verdict(storyId, snapshotId, memberId, roomId, assessmentId, use, false);
    }

    private boolean verdict(
            long story,
            long snapshot,
            long member,
            long sourceId,
            Long assessment,
            Use use,
            boolean test) {
        if (story <= 0
                || snapshot <= 0
                || member <= 0
                || sourceId <= 0
                || assessment == null
                || assessment <= 0
                || use == null) return false;
        String source =
                test
                        ? """
                        SELECT v.story_id,t.version_id,t.snapshot_id,m.member_id,t.id AS test_id,
                          NULL::bigint AS room_id,m.assessment_id,m.blind_declared,
                          (m.invite_state='ACCEPTED') AS accepted
                        FROM play_test t JOIN story_version v ON v.id=t.version_id
                        JOIN test_member m ON m.test_id=t.id AND m.member_id=?
                        WHERE t.id=? AND t.mode='BLIND'
                        """
                        : """
                        SELECT r.story_id,r.version_id,r.snapshot_id,r.selected_member_id AS member_id,
                          NULL::bigint AS test_id,r.id AS room_id,r.assessment_id,m.blind_declared,
                          (m.active_yn AND m.accepted_at IS NOT NULL) AS accepted
                        FROM game_room r LEFT JOIN game_member m ON m.room_id=r.id AND m.member_id=r.selected_member_id
                        WHERE r.id=? AND r.purpose='REVIEW_SOLO_QUALIFICATION'
                          AND r.qualification_kind='BLIND' AND r.mode='SOLO' AND r.selected_member_id=?
                        """;
        String sql =
                """
                WITH clock AS MATERIALIZED (SELECT clock_timestamp() AS now),
                source AS (%s)
                SELECT EXISTS(SELECT 1 FROM source s
                  JOIN blind_assessment a ON a.id=s.assessment_id AND a.id=?
                    AND a.story_id=s.story_id AND a.version_id=s.version_id
                    AND a.snapshot_id=s.snapshot_id AND a.member_id=s.member_id
                  CROSS JOIN clock
                  LEFT JOIN test_exposure e ON e.story_id=a.story_id AND e.member_id=a.member_id
                  WHERE s.story_id=? AND s.snapshot_id=? AND s.member_id=?
                    AND a.decision='ELIGIBLE' AND a.revoked_at IS NULL
                    AND (?='ADMISSION' OR (s.blind_declared IS TRUE AND s.accepted IS TRUE))
                    AND ((e.story_id IS NULL AND ?<>'EVIDENCE' AND %s
                      AND NOT (%s))
                      OR (?<>'ADMISSION' AND e.story_id IS NOT NULL
                        AND e.first_test_id IS NOT DISTINCT FROM s.test_id
                        AND e.first_room_id IS NOT DISTINCT FROM s.room_id
                        AND a.checked_at<=e.first_exposed_at AND e.first_exposed_at<a.valid_until
                        AND e.first_exposed_at<=clock.now
                        AND NOT EXISTS(SELECT 1 FROM blind_assessment later
                          WHERE later.story_id=a.story_id AND later.member_id=a.member_id
                            AND later.id>a.id AND later.decision='INELIGIBLE'))))
                """
                        .formatted(source, CURRENT, occupied("s.test_id", "s.room_id"));
        return Boolean.TRUE.equals(
                test
                        ? db.queryForObject(
                                sql,
                                Boolean.class,
                                member,
                                sourceId,
                                assessment,
                                story,
                                snapshot,
                                member,
                                use.name(),
                                use.name(),
                                use.name())
                        : db.queryForObject(
                                sql,
                                Boolean.class,
                                sourceId,
                                member,
                                assessment,
                                story,
                                snapshot,
                                member,
                                use.name(),
                                use.name(),
                                use.name()));
    }

    private static String occupied(String test, String room) {
        return OCCUPIED.formatted(test, test, room, room);
    }
}
