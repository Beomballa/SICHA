package com.reasoning.common.game.service;

import com.reasoning.common.member.auth.MemberAuthConfiguration.PresenceCleanup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** 게임 소유 루트를 먼저 잠그고 종료 family의 정확한 접속 근거만 해제한다. 웹·수집 활성화와 무관하다. */
@Service
public final class GamePresenceCleanupService implements PresenceCleanup {
    private static final int ROOT_LIMIT = 100;
    private static final long BUDGET = TimeUnit.SECONDS.toNanos(10);
    private static final String TERMINAL =
            "(s.revoked_at IS NOT NULL OR s.idle_until<=clock_timestamp() OR"
                    + " s.absolute_until<=clock_timestamp())";
    private static final String EXCLUDED =
            "NOT EXISTS (SELECT 1 FROM privacy_erase e WHERE e.member_id=gm.member_id) AND NOT"
                    + " EXISTS (SELECT 1 FROM privacy_target p WHERE p.member_id=gm.member_id OR"
                    + " p.room_id=gm.room_id OR p.game_member_id=gm.id)";
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    private record Candidate(
            long id, long room, long member, Timestamp seen, long session, long rev) {}

    private record Work(
            long room, Map<String, Object> binding, List<Long> members, List<Long> owners) {}

    /**
     * SQL을 실행하지 않고 같은 연결 원천의 소유 거래를 구성한다.
     *
     * @param db 게임 저장소의 null 아닌 JDBC 템플릿
     */
    public GamePresenceCleanupService(JdbcTemplate db) {
        this.db = Objects.requireNonNull(db);
        transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(db.getDataSource())));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    /**
     * 한 소유 거래에서 접속 근거를 해제한다. 후속 인증·제한 창 거래의 실패는 이 확정을 되돌리지 않는다. 루트 집합은 각 종류별 100개, 후보는 limit개로
     * 제한한다. 큰/변경된 폐쇄 집합은 다음 호출로 넘긴다. SQL 작업 예산 10초는 연결 획득·확정 지연과 적체 해소 기한을 보장하지 않는다.
     *
     * @param limit 전체 접속 근거 갱신 상한 1~100
     * @return 확정된 접속 근거 해제 수이며 인증 Counts나 개인정보 파기 완료 수가 아니다
     * @throws IllegalArgumentException 상한이 범위 밖인 경우
     * @throws IllegalStateException 외부 거래 또는 SQL 작업 예산 초과인 경우
     * @throws org.springframework.dao.DataAccessException 저장 실패 시 전체 접속 근거 거래 롤백
     */
    @Override
    public int cleanup(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("INVALID_CLEANUP_LIMIT");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(db.getDataSource())) {
            throw new IllegalStateException("AMBIENT_GAME_TRANSACTION");
        }
        long started = System.nanoTime();
        return Objects.requireNonNull(transaction.execute(status -> clean(limit, started)));
    }

    /**
     * 전체 소유자→회원→후보 세션→고정 부모→방→참가자 순서를 유지한다.
     *
     * @param limit 전체 접속 근거 갱신 상한 1~100
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 현재 거래의 실제 갱신 수이며 경합·결속 변경은 집계하지 않는다
     */
    private int clean(int limit, long started) {
        budget(started);
        db.execute("SET LOCAL lock_timeout='1s'");
        budget(started);
        List<Candidate> candidates =
                db.query(
                        "SELECT"
                            + " gm.id,gm.room_id,gm.member_id,gm.last_seen_at,gm.presence_session_id,gm.presence_auth_rev"
                            + " FROM game_member gm JOIN member_session s ON"
                            + " s.id=gm.presence_session_id AND s.member_id=gm.member_id WHERE "
                                + TERMINAL
                                + " AND "
                                + EXCLUDED
                                + " ORDER BY gm.id LIMIT ?",
                        (rs, row) ->
                                new Candidate(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getTimestamp(4),
                                        rs.getLong(5),
                                        rs.getLong(6)),
                        limit);
        List<Work> works = new ArrayList<>();
        TreeSet<Long> members = new TreeSet<>();
        TreeSet<Long> owners = new TreeSet<>();
        for (long room : candidates.stream().map(Candidate::room).distinct().sorted().toList()) {
            Map<String, Object> binding = binding(room, started);
            if (binding == null) continue;
            List<Long> participants = members(room, started);
            if (participants.size() > ROOT_LIMIT) continue;
            List<Long> policies = owners(room, started);
            TreeSet<Long> nextMembers = new TreeSet<>(members);
            TreeSet<Long> nextOwners = new TreeSet<>(owners);
            nextMembers.addAll(participants);
            nextOwners.addAll(policies);
            if (policies.size() > ROOT_LIMIT
                    || nextMembers.size() > ROOT_LIMIT
                    || nextOwners.size() > ROOT_LIMIT) continue;
            members = nextMembers;
            owners = nextOwners;
            works.add(new Work(room, binding, participants, policies));
        }
        for (long owner : owners) {
            if (!lock("admin_account", "id", owner, "SHARE", started)
                    || !lock("admin_credential", "account_id", owner, "SHARE", started)) return 0;
        }
        for (long member : members) {
            if (!lock("member_account", "id", member, "UPDATE", started)) return 0;
        }
        List<Work> stable = new ArrayList<>();
        for (Work work : works) {
            if (stable(work, started)) stable.add(work);
        }
        TreeSet<Long> sessions = new TreeSet<>();
        for (Candidate candidate : candidates) {
            if (stable.stream().anyMatch(work -> work.room() == candidate.room()))
                sessions.add(candidate.session());
        }
        TreeSet<Long> lockedSessions = new TreeSet<>();
        for (long session : sessions) {
            if (lock("member_session", "id", session, "UPDATE", started))
                lockedSessions.add(session);
        }
        for (String[] parent :
                List.of(
                        new String[] {"grade_runtime", "runtime_id", "UPDATE"},
                        new String[] {"story", "story_id", "SHARE"},
                        new String[] {"story_version", "version_id", "SHARE"})) {
            TreeSet<Long> ids = new TreeSet<>();
            for (Work work : stable) ids.add(((Number) work.binding().get(parent[1])).longValue());
            for (long id : ids) {
                if (!lock(parent[0], "id", id, parent[2], started)) return 0;
            }
        }
        int changed = 0;
        for (Work work : stable) {
            if (!lock("game_room", "id", work.room(), "UPDATE", started) || !stable(work, started))
                continue;
            for (Candidate candidate : candidates) {
                if (candidate.room() != work.room()
                        || !lockedSessions.contains(candidate.session())) continue;
                if (!lock("game_member", "id", candidate.id(), "UPDATE", started)) continue;
                budget(started);
                changed +=
                        db.update(
                                "UPDATE game_member gm SET"
                                    + " last_seen_at=NULL,presence_session_id=NULL,presence_auth_rev=NULL,updated_at=clock_timestamp()"
                                    + " WHERE gm.id=? AND gm.room_id=? AND gm.member_id=? AND"
                                    + " gm.last_seen_at IS NOT DISTINCT FROM ? AND"
                                    + " gm.presence_session_id IS NOT DISTINCT FROM ? AND"
                                    + " gm.presence_auth_rev IS NOT DISTINCT FROM ? AND "
                                        + EXCLUDED
                                        + " AND EXISTS (SELECT 1 FROM member_session s WHERE s.id=?"
                                        + " AND s.member_id=gm.member_id AND "
                                        + TERMINAL
                                        + ")",
                                candidate.id(),
                                candidate.room(),
                                candidate.member(),
                                candidate.seen(),
                                candidate.session(),
                                candidate.rev(),
                                candidate.session());
            }
        }
        budget(started);
        return changed;
    }

    /**
     * 방의 고정 결속만 읽으며 상태·등급·출판 게이트를 실행하지 않는다.
     *
     * @param room 후보 방의 내부 ID
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 고정 결속 값이며 방이 없어졌으면 null
     */
    private Map<String, Object> binding(long room, long started) {
        budget(started);
        var rows =
                db.queryForList(
                        "SELECT"
                            + " runtime_id,story_id,version_id,snapshot_id,runtime_epoch,config_hash,rubric_hash,policy_id,reviewer_id,selected_member_id,purpose,mode"
                            + " FROM game_room WHERE id=?",
                        room);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 초과를 식별할 한 행만 더 읽고 과거·비활성 참가자도 폐쇄 집합에 포함한다.
     *
     * @param room 후보 방의 내부 ID
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 정렬한 회원 ID 최대 101개이며 100개 초과는 처리 제외 대상이다
     */
    private List<Long> members(long room, long started) {
        budget(started);
        return db.queryForList(
                "SELECT member_id FROM game_member WHERE room_id=? ORDER BY member_id LIMIT 101",
                Long.class,
                room);
    }

    /**
     * 현재 인증 정책·방 정책·참가자 정책 및 자격 심사 소유자를 한정 발견한다.
     *
     * @param room 후보 방의 내부 ID
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 정렬한 소유자 ID 최대 101개이며 100개 초과는 처리 제외 대상이다
     */
    private List<Long> owners(long room, long started) {
        budget(started);
        return db.queryForList(
                "SELECT owner_id FROM (SELECT p.owner_id FROM privacy_policy p WHERE p.id=(SELECT"
                    + " policy_id FROM game_room WHERE id=?) OR p.id IN (SELECT profile.policy_id"
                    + " FROM member_profile profile JOIN (SELECT member_id FROM game_member WHERE"
                    + " room_id=? ORDER BY member_id LIMIT 101) gm ON"
                    + " gm.member_id=profile.member_id) OR (p.scope='MEMBER_AUTH' AND"
                    + " p.state='ACTIVE') UNION SELECT reviewer_id FROM game_room WHERE id=? AND"
                    + " reviewer_id IS NOT NULL) roots ORDER BY owner_id LIMIT 101",
                Long.class,
                room,
                room,
                room);
    }

    /**
     * 잠금 이후 폐쇄 집합이 달라지면 앞 순서 루트를 추가로 잡지 않고 해당 방을 건너뛴다.
     *
     * @param work null이 아닌 발견 당시의 방 결속·회원·소유자 집합
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 결속과 폐쇄 집합이 모두 그대로이고 파기 참조가 없으면 true
     */
    private boolean stable(Work work, long started) {
        if (!work.binding().equals(binding(work.room(), started))
                || !work.members().equals(members(work.room(), started))
                || !work.owners().equals(owners(work.room(), started))) return false;
        budget(started);
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT NOT EXISTS (SELECT 1 FROM (SELECT id,room_id,member_id FROM"
                            + " game_member WHERE room_id=? ORDER BY member_id LIMIT 101) gm WHERE"
                            + " NOT ("
                                + EXCLUDED
                                + "))",
                        Boolean.class,
                        work.room()));
    }

    /**
     * 정해진 내부 테이블·키만 사용하며 경합 루트는 기다리지 않는다.
     *
     * @param table 내부 호출로 고정한 루트 테이블 이름이며 외부 입력은 허용하지 않는다
     * @param key 내부 호출로 고정한 ID 컬럼 이름
     * @param id 발견한 루트의 내부 ID
     * @param mode 내부 호출로 고정한 SHARE 또는 UPDATE
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 현재 잠금을 획득했으면 true이며 소실·경합이면 false
     */
    private boolean lock(String table, String key, long id, String mode, long started) {
        budget(started);
        return !db.queryForList(
                        "SELECT "
                                + key
                                + " FROM "
                                + table
                                + " WHERE "
                                + key
                                + "=? FOR "
                                + mode
                                + " SKIP LOCKED",
                        Long.class,
                        id)
                .isEmpty();
    }

    /**
     * 남은 SQL 작업 예산을 각 다음 문장에 전달한다.
     *
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @throws IllegalStateException SQL 작업 예산 10초가 소진된 경우
     */
    private void budget(long started) {
        long remaining = BUDGET - (System.nanoTime() - started);
        if (remaining <= 0) throw new IllegalStateException("GAME_PRESENCE_CLEANUP_TIMEOUT");
        db.queryForObject(
                "SELECT set_config('statement_timeout', ?, true)",
                String.class,
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)) + "ms");
    }
}
