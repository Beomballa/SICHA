package com.reasoning.common.member.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 만료 자식과 종료 family를 한정 정리하며 게임 참조 해제·개인정보 파기는 수행하지 않는다. */
public final class MemberAuthCleanupService {
    /** 각각의 소유 거래 확정 뒤에만 반환하는 비밀 없는 종류별 삭제 수다. */
    public record Counts(
            int flows, int accessTokens, int refreshTokens, int sessions, int limits) {}

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private static final long BUDGET = TimeUnit.SECONDS.toNanos(10);
    private static final String TERMINAL =
            "(s.revoked_at IS NOT NULL OR s.idle_until<=clock_timestamp() OR"
                    + " s.absolute_until<=clock_timestamp())";
    // 게임 대상의 파기 참조도 모든 상태에서 보존하며 게임 잠금·쓰기를 가져오지 않는다.
    private static final String EXCLUDED =
            "NOT EXISTS (SELECT 1 FROM privacy_erase e WHERE e.member_id=a.id)"
                    + " AND NOT EXISTS (SELECT 1 FROM privacy_target p WHERE p.member_id=a.id)"
                    + " AND NOT EXISTS (SELECT 1 FROM game_member gm JOIN privacy_target p"
                    + " ON p.room_id=gm.room_id OR p.game_member_id=gm.id WHERE gm.member_id=a.id)";

    /**
     * 동일 DataSource의 JDBC 관리자만 받아 READ_COMMITTED 소유 거래를 구성한다.
     *
     * @param db null이 아닌 회원 JDBC 저장소
     * @param manager 같은 DataSource를 소유하는 null이 아닌 JDBC 거래 관리자
     * @throws IllegalArgumentException 연결 원천 또는 관리자 종류가 일치하지 않는 경우
     */
    public MemberAuthCleanupService(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = Objects.requireNonNull(db);
        if (!(manager instanceof DataSourceTransactionManager owner)
                || db.getDataSource() == null
                || owner.getDataSource() != db.getDataSource()) {
            throw new IllegalArgumentException("INVALID_MEMBER_TRANSACTION_MANAGER");
        }
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    /**
     * 회원→세션→자식 순서의 정렬 잠금과 새 DB 시각으로 종료를 재검사한다. 큰 family는 호출마다 한정된 자식만 제거하며 참조가 없어질 때까지 세션을 보존한다. 제한
     * 창은 별도 확정 거래다. SQL 작업 예산은 10초이며 연결 획득·확정 지연이나 전체 적체 정리 기한을 보장하지 않는다.
     *
     * @param limit 종류별 전역 삭제 상한 1~100
     * @return flow·ACCESS·REFRESH·세션·제한 창의 확정 삭제 수
     * @throws IllegalArgumentException 상한 또는 거래 소유권이 잘못된 경우
     * @throws IllegalStateException 외부 거래 또는 SQL 예산 초과인 경우
     * @throws org.springframework.dao.DataAccessException 해당 소유 거래의 저장 실패 시
     */
    public Counts cleanup(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("INVALID_CLEANUP_LIMIT");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(db.getDataSource())) {
            throw new IllegalStateException("AMBIENT_MEMBER_TRANSACTION");
        }
        long started = System.nanoTime();
        Counts artifacts =
                Objects.requireNonNull(transaction.execute(status -> clean(limit, started)));
        int limits =
                Objects.requireNonNull(transaction.execute(status -> cleanLimits(limit, started)));
        return new Counts(
                artifacts.flows(),
                artifacts.accessTokens(),
                artifacts.refreshTokens(),
                artifacts.sessions(),
                limits);
    }

    /**
     * 전역 종류별 후보를 부모 잠금 뒤 선택하고 실제 삭제 시 결속·종료·파기 제외를 재검사한다.
     *
     * @param limit 종류별 전역 삭제 상한 1~100
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 현재 artifact 거래의 삭제 수이며 제한 창은 별도 거래에서 집계한다
     */
    private Counts clean(int limit, long started) {
        budget(started);
        db.execute("SET LOCAL lock_timeout='1s'");
        budget(started);
        List<Long> parents =
                db.queryForList(
                        "SELECT a.id FROM member_account a WHERE "
                                + EXCLUDED
                                + " AND (EXISTS (SELECT 1 FROM member_flow f WHERE f.member_id=a.id"
                                + " AND f.expires_at<=clock_timestamp()) OR EXISTS (SELECT 1 FROM"
                                + " member_session s WHERE s.member_id=a.id AND ("
                                + TERMINAL
                                + " OR EXISTS (SELECT 1 FROM member_token t WHERE t.session_id=s.id"
                                + " AND t.kind='ACCESS' AND t.expires_at<=clock_timestamp()))))"
                                + " ORDER BY a.id LIMIT ? FOR UPDATE OF a SKIP LOCKED",
                        Long.class,
                        limit * 2);
        List<Long> allowed = new ArrayList<>();
        for (long member : parents) {
            budget(started);
            if (Boolean.TRUE.equals(
                    db.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM member_account a WHERE a.id=? AND "
                                    + EXCLUDED
                                    + ")",
                            Boolean.class,
                            member))) allowed.add(member);
        }
        String members = marks(allowed);
        List<Object> parameters = new ArrayList<>(allowed);
        parameters.add(limit * 4);
        budget(started);
        List<Long> sessions =
                db.queryForList(
                        "SELECT s.id FROM member_session s WHERE s.member_id IN ("
                                + members
                                + ") AND ("
                                + TERMINAL
                                + " OR EXISTS (SELECT 1 FROM member_token t WHERE t.session_id=s.id"
                                + " AND t.kind='ACCESS' AND t.expires_at<=clock_timestamp()) OR"
                                + " EXISTS (SELECT 1 FROM member_flow f WHERE"
                                + " f.member_id=s.member_id AND f.session_id=s.id AND"
                                + " f.expires_at<=clock_timestamp())) ORDER BY s.id LIMIT ? FOR"
                                + " UPDATE OF s SKIP LOCKED",
                        Long.class,
                        parameters.toArray());
        String locked = marks(sessions);
        String boundTerminal =
                "EXISTS (SELECT 1 FROM member_session s WHERE s.id=f.session_id"
                        + " AND s.member_id=f.member_id AND "
                        + TERMINAL
                        + ")";
        String flowDue = "(f.expires_at<=clock_timestamp() OR " + boundTerminal + ")";
        parameters = new ArrayList<>(allowed);
        parameters.addAll(sessions);
        parameters.add(limit);
        budget(started);
        int flows =
                db.update(
                        "WITH due AS MATERIALIZED (SELECT f.id FROM member_flow f WHERE"
                                + " (f.member_id IS NULL OR f.member_id IN ("
                                + members
                                + ")) AND (f.session_id IS NULL OR f.session_id IN ("
                                + locked
                                + ")) AND "
                                + flowDue
                                + " ORDER BY f.id LIMIT ? FOR UPDATE OF f SKIP LOCKED) DELETE FROM"
                                + " member_flow f USING due WHERE f.id=due.id AND "
                                + flowDue,
                        parameters.toArray());
        int access = tokens("ACCESS", sessions, limit, started);
        int refresh = tokens("REFRESH", sessions, limit, started);
        parameters = new ArrayList<>(sessions);
        parameters.add(limit);
        budget(started);
        int removed =
                db.update(
                        "WITH due AS MATERIALIZED (SELECT s.id FROM member_session s JOIN"
                                + " member_account a ON a.id=s.member_id WHERE s.id IN ("
                                + locked
                                + ") AND "
                                + TERMINAL
                                + " AND "
                                + EXCLUDED
                                + " AND NOT EXISTS (SELECT 1 FROM member_token t WHERE"
                                + " t.session_id=s.id) AND NOT EXISTS (SELECT 1 FROM member_flow f"
                                + " WHERE f.member_id=s.member_id AND f.session_id=s.id) AND NOT"
                                + " EXISTS (SELECT 1 FROM game_member gm WHERE"
                                + " gm.member_id=s.member_id AND gm.presence_session_id=s.id) ORDER"
                                + " BY s.id LIMIT ?) DELETE FROM member_session s USING due WHERE"
                                + " s.id=due.id AND "
                                + TERMINAL,
                        parameters.toArray());
        budget(started);
        return new Counts(flows, access, refresh, removed, 0);
    }

    /**
     * 잠긴 family의 자식을 종류별 단일 전역 상한 안에서 제거하며 살아 있는 REFRESH는 모두 보존한다.
     *
     * @param kind 내부 호출로 고정한 ACCESS 또는 REFRESH
     * @param sessions 부모 결속을 확인하고 잠근 family ID 목록이며 빈 목록을 허용한다
     * @param limit 종류별 전역 삭제 상한 1~100
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 해당 종류의 실제 삭제 행 수
     */
    private int tokens(String kind, List<Long> sessions, int limit, long started) {
        String eligible =
                kind.equals("ACCESS")
                        ? "(t.expires_at<=clock_timestamp() OR " + TERMINAL + ")"
                        : TERMINAL;
        List<Object> parameters = new ArrayList<>(sessions);
        parameters.add(kind);
        parameters.add(limit);
        budget(started);
        return db.update(
                "WITH due AS MATERIALIZED (SELECT t.id,t.session_id FROM member_token t JOIN"
                        + " member_session s ON s.id=t.session_id WHERE s.id IN ("
                        + marks(sessions)
                        + ") AND t.kind=? AND "
                        + eligible
                        + " ORDER BY t.id LIMIT ? FOR UPDATE OF t SKIP LOCKED) DELETE FROM"
                        + " member_token t USING due,member_session s WHERE t.id=due.id AND"
                        + " t.session_id=due.session_id AND s.id=t.session_id AND "
                        + eligible,
                parameters.toArray());
    }

    /**
     * 빈 집합은 SQL NULL로 표현하여 추가 후보나 미잠금 부모를 선택하지 않는다.
     *
     * @param values null이 아닌 내부 ID 목록이며 빈 목록을 허용한다
     * @return 목록 크기에 맞춘 바인딩 자리 또는 빈 목록의 NULL 표현
     */
    private static String marks(List<Long> values) {
        return values.isEmpty()
                ? "NULL"
                : String.join(",", Collections.nCopies(values.size(), "?"));
    }

    /**
     * 회원 잠금 없는 별도 거래에서 제한 창 쓰기를 직렬화하며 앞 artifact 확정을 되돌리지 않는다.
     *
     * @param limit 제한 창의 전역 삭제 상한 1~100
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @return 현재 제한 창 거래의 실제 삭제 행 수
     */
    private int cleanLimits(int limit, long started) {
        budget(started);
        db.execute("SET LOCAL lock_timeout='5s'");
        budget(started);
        db.execute("LOCK TABLE public.member_auth_limit IN SHARE ROW EXCLUSIVE MODE");
        budget(started);
        int limitCount =
                db.update(
                        "DELETE FROM member_auth_limit l USING (SELECT scope,bucket_hash FROM"
                            + " member_auth_limit WHERE purge_at<=clock_timestamp() AND"
                            + " (blocked_until IS NULL OR blocked_until<=clock_timestamp()) ORDER"
                            + " BY scope,bucket_hash LIMIT ?) due WHERE l.scope=due.scope AND"
                            + " l.bucket_hash=due.bucket_hash AND l.purge_at<=clock_timestamp() AND"
                            + " (l.blocked_until IS NULL OR l.blocked_until<=clock_timestamp())",
                        limit);
        budget(started);
        return limitCount;
    }

    /**
     * 남은 SQL 작업 예산을 다음 문장에 전달하며 초과 시 현재 거래를 롤백한다.
     *
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @throws IllegalStateException SQL 작업 예산 10초가 소진된 경우
     */
    private void budget(long started) {
        long remaining = BUDGET - (System.nanoTime() - started);
        if (remaining <= 0) throw new IllegalStateException("MEMBER_CLEANUP_TIMEOUT");
        db.queryForObject(
                "SELECT set_config('statement_timeout', ?, true)",
                String.class,
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)) + "ms");
    }
}
