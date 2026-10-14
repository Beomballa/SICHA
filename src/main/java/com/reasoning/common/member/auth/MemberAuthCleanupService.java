package com.reasoning.common.member.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** 만료된 flow·ACCESS·휴면 제한 창만 정리한다. family·재생 증거·개인정보 파기는 책임 밖이다. */
public final class MemberAuthCleanupService {
    /** 거래 확정 후에만 반환하는 비밀 없는 종류별 삭제 수다. */
    public record Counts(int flows, int accessTokens, int limits) {}

    private record Candidate(long id, Long memberId, Long sessionId) {}

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private static final long BUDGET = TimeUnit.SECONDS.toNanos(10);

    /**
     * 동일 연결 원천의 소유 거래를 구성한다.
     *
     * @param db null이 아닌 회원 저장소
     * @param manager db와 동일 DataSource의 JDBC 거래 관리자
     * @throws IllegalArgumentException 거래 소유권이 다를 경우
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
     * DB 절대 시각으로 만료를 재검사하고 종류별 독립 상한 안에서 삭제한다. 회원→세션→flow/ACCESS 순으로 잠그며 erasure에 참조된 회원은 후보 발견부터
     * 제외한다. 잠긴 회원은 부모 후보 선택에서 건너뛰므로 이후 회원의 정리를 막지 않는다. 제한된 부모·자식 후보와 경쟁 스킵 때문에 전체 적체의 정리 기한 준수를
     * 보장하지 않는다. 제한 창은 회원 잠금 없는 별도 거래의 테이블 쓰기 직렬화로 보호한다. 두 거래 중 뒤 거래가 실패해도 앞 거래의 확정은 되돌리지 않으며 성공 집계는
     * 반환하지 않는다.
     *
     * @param limit 종류별 후보 상한 1~100; 총 삭제 수는 최대 3배
     * @return 확정된 종류별 삭제 수; 스킵은 삭제 수에 포함하지 않음
     * @throws IllegalArgumentException 상한이 범위 밖인 경우
     * @throws IllegalStateException 외부 거래 또는 실행 예산 초과인 경우
     * @throws org.springframework.dao.DataAccessException 저장·잠금·삭제 가드 실패 시 해당 거래 롤백
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
        return new Counts(artifacts.flows(), artifacts.accessTokens(), limits);
    }

    /**
     * 만료 자식이 있는 부모만 정렬 잠금한 뒤 자식 후보를 발견하고 바인딩·기한을 다시 검사한다.
     *
     * @param limit 종류별 후보 상한 1~100
     * @param started 전체 호출의 단조 시계 시작값
     * @return 현재 거래의 flow·ACCESS 삭제 수
     * @throws IllegalStateException 전체 실행 예산 초과 시
     */
    private Counts clean(int limit, long started) {
        db.execute("SET LOCAL lock_timeout='1s'");
        budget(started);
        List<Long> parents =
                db.queryForList(
                        "SELECT a.id FROM member_account a WHERE NOT EXISTS (SELECT 1 FROM"
                            + " privacy_erase e WHERE e.member_id=a.id) AND NOT EXISTS (SELECT 1"
                            + " FROM privacy_target p WHERE p.member_id=a.id) AND (EXISTS (SELECT 1"
                            + " FROM member_flow f WHERE f.member_id=a.id AND"
                            + " f.expires_at<=clock_timestamp()) OR EXISTS (SELECT 1 FROM"
                            + " member_session s JOIN member_token t ON t.session_id=s.id WHERE"
                            + " s.member_id=a.id AND t.kind='ACCESS' AND"
                            + " t.expires_at<=clock_timestamp())) ORDER BY a.id LIMIT ? FOR UPDATE"
                            + " OF a SKIP LOCKED",
                        Long.class,
                        limit * 2);
        Set<Long> allowed = new HashSet<>();
        for (long member : parents) {
            budget(started);
            if (!Boolean.TRUE.equals(
                    db.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM privacy_erase WHERE member_id=? UNION ALL"
                                    + " SELECT 1 FROM privacy_target WHERE member_id=?)",
                            Boolean.class,
                            member,
                            member))) allowed.add(member);
        }
        List<Long> selected = parents.stream().filter(allowed::contains).toList();
        String placeholders =
                selected.isEmpty()
                        ? "NULL"
                        : String.join(",", Collections.nCopies(selected.size(), "?"));
        List<Object> parameters = new ArrayList<>(selected);
        parameters.add(limit);
        budget(started);
        List<Candidate> flows =
                db.query(
                        "SELECT f.id,f.member_id,f.session_id FROM member_flow f WHERE"
                                + " f.expires_at<=clock_timestamp() AND (f.member_id IS NULL OR"
                                + " f.member_id IN ("
                                + placeholders
                                + ")) AND NOT EXISTS (SELECT 1 FROM privacy_erase e WHERE"
                                + " e.member_id=f.member_id) AND NOT EXISTS (SELECT 1 FROM"
                                + " privacy_target p WHERE p.member_id=f.member_id) ORDER BY"
                                + " f.expires_at,f.id LIMIT ?",
                        (rs, row) ->
                                new Candidate(
                                        rs.getLong(1),
                                        rs.getObject(2, Long.class),
                                        rs.getObject(3, Long.class)),
                        parameters.toArray());
        budget(started);
        List<Candidate> tokens =
                db.query(
                        "SELECT t.id,s.member_id,t.session_id FROM member_token t JOIN"
                                + " member_session s ON s.id=t.session_id WHERE t.kind='ACCESS' AND"
                                + " t.expires_at<=clock_timestamp() AND s.member_id IN ("
                                + placeholders
                                + ") AND NOT EXISTS (SELECT 1 FROM privacy_erase e WHERE"
                                + " e.member_id=s.member_id) AND NOT EXISTS (SELECT 1 FROM"
                                + " privacy_target p WHERE p.member_id=s.member_id) ORDER BY"
                                + " t.expires_at,t.id LIMIT ?",
                        (rs, row) -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                        parameters.toArray());

        TreeSet<Long> sessions = new TreeSet<>();
        for (Candidate value : flows) {
            if (value.sessionId() != null) sessions.add(value.sessionId());
        }
        for (Candidate value : tokens) {
            sessions.add(value.sessionId());
        }
        Set<Long> lockedSessions = new HashSet<>();
        for (long session : sessions) {
            budget(started);
            var owners =
                    db.queryForList(
                            "SELECT member_id FROM member_session WHERE id=?", Long.class, session);
            if (owners.size() == 1
                    && allowed.contains(owners.getFirst())
                    && !db.queryForList(
                                    "SELECT id FROM member_session WHERE id=? FOR UPDATE SKIP"
                                            + " LOCKED",
                                    Long.class,
                                    session)
                            .isEmpty()) {
                lockedSessions.add(session);
            }
        }

        int flowCount = 0;
        for (Candidate value : flows) {
            budget(started);
            if (value.memberId() != null && !allowed.contains(value.memberId())) continue;
            if (value.sessionId() != null && !lockedSessions.contains(value.sessionId())) continue;
            flowCount +=
                    db.update(
                            "WITH due AS MATERIALIZED (SELECT id FROM member_flow WHERE id=? AND"
                                + " member_id IS NOT DISTINCT FROM ?::bigint AND session_id IS NOT"
                                + " DISTINCT FROM ?::bigint AND expires_at<=clock_timestamp() FOR"
                                + " UPDATE SKIP LOCKED) DELETE FROM member_flow f USING due WHERE"
                                + " f.id=due.id AND f.expires_at<=clock_timestamp()",
                            value.id(),
                            value.memberId(),
                            value.sessionId());
        }
        int tokenCount = 0;
        for (Candidate value : tokens) {
            budget(started);
            if (!allowed.contains(value.memberId()) || !lockedSessions.contains(value.sessionId()))
                continue;
            tokenCount +=
                    db.update(
                            "WITH due AS MATERIALIZED (SELECT id FROM member_token WHERE id=? AND"
                                + " session_id=? AND kind='ACCESS' AND"
                                + " expires_at<=clock_timestamp() FOR UPDATE SKIP LOCKED) DELETE"
                                + " FROM member_token t USING due WHERE t.id=due.id AND"
                                + " t.kind='ACCESS' AND t.expires_at<=clock_timestamp()",
                            value.id(),
                            value.sessionId());
        }

        budget(started);
        return new Counts(flowCount, tokenCount, 0);
    }

    /**
     * 회원·자식 잠금 없이 제한 창 쓰기 전체와 직렬화하여 INSERT 뒤 행 소실을 막는다.
     *
     * @param limit 제한 창 삭제 상한 1~100
     * @param started 전체 호출의 단조 시계 시작값
     * @return 현재 거래에서 삭제한 휴면 제한 창 수
     * @throws org.springframework.dao.DataAccessException 직렬화 또는 삭제 실패 시
     */
    private int cleanLimits(int limit, long started) {
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
     * 남은 전체 실행 예산을 다음 SQL의 제한으로 전달하며 초과 시 현재 거래를 롤백한다.
     *
     * @param started 전체 호출의 단조 시계 시작값
     * @throws IllegalStateException 10초 실행 예산을 초과한 경우
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
