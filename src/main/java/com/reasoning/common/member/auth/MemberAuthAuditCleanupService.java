package com.reasoning.common.member.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 저장된 감사 만료와 최소 영수증을 DB의 단일 권한 함수에서 원자적으로 소비한다. */
public final class MemberAuthAuditCleanupService {
    /** 확정된 감사·만료 영수증 삭제 수와 새 감사 배치 영수증의 식별자다. */
    public record Counts(int audits, int receipts, UUID operationKey) {}

    private static final long BUDGET = TimeUnit.SECONDS.toNanos(10);
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    /**
     * SQL 실행 없이 같은 연결 원천의 READ_COMMITTED 소유 거래를 구성한다.
     *
     * @param db null이 아닌 회원 JDBC 저장소
     * @param manager 같은 DataSource의 JDBC 거래 관리자
     * @throws IllegalArgumentException 관리자 종류 또는 연결 원천이 일치하지 않는 경우
     * @throws NullPointerException 저장소가 null인 경우
     */
    public MemberAuthAuditCleanupService(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = Objects.requireNonNull(db);
        if (!(manager instanceof DataSourceTransactionManager owner)
                || db.getDataSource() == null
                || owner.getDataSource() != db.getDataSource()) {
            throw new IllegalArgumentException("INVALID_MEMBER_TRANSACTION_MANAGER");
        }
        owner.setRollbackOnCommitFailure(true);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    /**
     * 만료 영수증 정리·감사 삭제·새 영수증을 함께 확정한 뒤에만 결과를 반환한다. 앞선 접속 근거·인증 자료·제한 창의 독립 확정은 되돌리지 않는다. SQL 작업 예산은
     * 10초이며 연결 획득·확정 지연이나 엄격한 전체 호출 시간 상한은 보장하지 않는다.
     *
     * @param limit 감사와 만료 영수증 각각의 삭제 상한 1~100
     * @return 소유 거래 확정 뒤의 삭제 수와 감사 삭제 시에만 존재하는 새 영수증 UUID
     * @throws IllegalArgumentException 상한이 1~100 범위를 벗어난 경우
     * @throws IllegalStateException 외부 거래·연결 자원, 잘못된 DB 결과 또는 SQL 예산 초과인 경우
     * @throws org.springframework.dao.DataAccessException 권한·저장·DB 호출 실패 시
     * @throws org.springframework.transaction.TransactionException 소유 거래 시작·확정 실패 시
     */
    public Counts cleanup(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("INVALID_CLEANUP_LIMIT");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(db.getDataSource())) {
            throw new IllegalStateException("AMBIENT_MEMBER_TRANSACTION");
        }
        long started = System.nanoTime();
        return Objects.requireNonNull(
                transaction.execute(
                        status -> {
                            budget(started);
                            db.execute("SET LOCAL lock_timeout='1s'");
                            budget(started);
                            Counts counts =
                                    db.query(
                                            "SELECT audits,receipts,operation_key FROM"
                                                    + " public.consume_member_auth_audit_expiry(?)",
                                            (ResultSetExtractor<Counts>)
                                                    rows -> {
                                                        if (!rows.next()) throw invalidResult();
                                                        Integer audits =
                                                                rows.getObject(
                                                                        "audits", Integer.class);
                                                        Integer receipts =
                                                                rows.getObject(
                                                                        "receipts", Integer.class);
                                                        UUID operation =
                                                                rows.getObject(
                                                                        "operation_key",
                                                                        UUID.class);
                                                        if (audits == null
                                                                || receipts == null
                                                                || audits < 0
                                                                || audits > limit
                                                                || receipts < 0
                                                                || receipts > limit
                                                                || (audits > 0)
                                                                        != (operation != null)
                                                                || rows.next()) {
                                                            throw invalidResult();
                                                        }
                                                        return new Counts(
                                                                audits, receipts, operation);
                                                    },
                                            limit);
                            budget(started);
                            return counts;
                        }));
    }

    /**
     * 남은 SQL 작업 예산을 거래 지역 문장 제한에 전달한다.
     *
     * @param started 호출 시작의 단조 시계 값이며 단위는 나노초
     * @throws IllegalStateException SQL 작업 예산 10초가 소진된 경우
     * @throws org.springframework.dao.DataAccessException 제한 설정이 실패한 경우
     */
    private void budget(long started) {
        long remaining = BUDGET - (System.nanoTime() - started);
        if (remaining <= 0) throw new IllegalStateException("MEMBER_AUDIT_CLEANUP_TIMEOUT");
        db.queryForObject(
                "SELECT set_config('statement_timeout', ?, true)",
                String.class,
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)) + "ms");
    }

    /**
     * 비밀 없는 고정 결과 계약 오류를 만든다.
     *
     * @return 원본 SQL·행 값을 포함하지 않는 오류
     */
    private static IllegalStateException invalidResult() {
        return new IllegalStateException("INVALID_MEMBER_AUDIT_CLEANUP_RESULT");
    }
}
