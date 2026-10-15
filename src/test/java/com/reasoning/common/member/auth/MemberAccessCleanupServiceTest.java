package com.reasoning.common.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

/** 실제 JDBC 거래 관리자와 합성 JDBC 결과로 접근 이력 권한 함수 호출·확정·롤백을 관찰한다. */
class MemberAccessCleanupServiceTest {
    private static final String SQL =
            "SELECT accesses,receipts,operation_key FROM public.consume_member_access_expiry(?)";
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ResultSet rows = mock(ResultSet.class);
    private MemberAccessCleanupService service;

    @BeforeEach
    void setup() throws Exception {
        when(db.getDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
        service = new MemberAccessCleanupService(db, new DataSourceTransactionManager(source));
        doAnswer(
                        invocation -> {
                            var holder =
                                    (org.springframework.jdbc.datasource.ConnectionHolder)
                                            TransactionSynchronizationManager.getResource(source);
                            assertThat(holder).isNotNull();
                            assertThat(holder.hasTimeout()).isTrue();
                            assertThat(holder.getTimeToLiveInMillis()).isBetween(1L, 15000L);
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .getCurrentTransactionIsolationLevel())
                                    .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                            ResultSetExtractor<?> extractor = invocation.getArgument(1);
                            return extractor.extractData(rows);
                        })
                .when(db)
                .query(eq(SQL), extractor(), anyInt());
    }

    @AfterEach
    void noLeakedTransaction() {
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void qualifiedEntryIsOnlyMutationAuthorityAndCountsReturnAfterCommit() throws Exception {
        UUID operation = UUID.randomUUID();
        result(100, 100, operation);
        var returned = service.cleanup(100);
        assertThat(returned).isEqualTo(new MemberAccessCleanupService.Counts(100, 100, operation));
        var order = inOrder(connection, db);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(connection).setAutoCommit(false);
        order.verify(db)
                .queryForObject(
                        eq("SELECT set_config('statement_timeout', ?, true)"),
                        eq(String.class),
                        anyString());
        order.verify(db).execute("SET LOCAL lock_timeout='1s'");
        order.verify(db)
                .queryForObject(
                        eq("SELECT set_config('statement_timeout', ?, true)"),
                        eq(String.class),
                        anyString());
        order.verify(db).query(eq(SQL), extractor(), eq(100));
        order.verify(db)
                .queryForObject(
                        eq("SELECT set_config('statement_timeout', ?, true)"),
                        eq(String.class),
                        anyString());
        order.verify(connection).commit();
        verify(connection, never()).rollback();
        verify(db, never()).update(anyString(), any(Object[].class));
        long previousBudget = 10000L;
        for (var call : org.mockito.Mockito.mockingDetails(db).getInvocations()) {
            if (call.getMethod().getName().equals("queryForObject")) {
                String budget = call.getArgument(2);
                assertThat(budget).matches("[1-9][0-9]*ms");
                long millis = Long.parseLong(budget.substring(0, budget.length() - 2));
                assertThat(millis).isBetween(1L, previousBudget);
                previousBudget = millis;
            }
            if (!call.getMethod().getName().equals("getDataSource")) {
                assertThat((String) call.getArgument(0))
                        .isIn(
                                SQL,
                                "SET LOCAL lock_timeout='1s'",
                                "SELECT set_config('statement_timeout', ?, true)");
            }
        }
    }

    @Test
    void lowerLimitAcceptsOneAccessAndReceipt() throws Exception {
        UUID operation = UUID.randomUUID();
        result(1, 1, operation);
        assertThat(service.cleanup(1))
                .isEqualTo(new MemberAccessCleanupService.Counts(1, 1, operation));
        verify(db).query(eq(SQL), extractor(), eq(1));
        verify(connection).commit();
    }

    @Test
    void receiptOnlyAndNoOpDoNotRequireOperationKey() throws Exception {
        result(0, 1, null);
        assertThat(service.cleanup(1)).isEqualTo(new MemberAccessCleanupService.Counts(0, 1, null));
        result(0, 0, null);
        assertThat(service.cleanup(1)).isEqualTo(new MemberAccessCleanupService.Counts(0, 0, null));
        verify(connection, org.mockito.Mockito.times(2)).commit();
    }

    @Test
    void invalidLimitsNeverAcquireConnectionOrExecuteSql() {
        for (int limit : new int[] {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.cleanup(limit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_CLEANUP_LIMIT");
        }
        verifyNoInteractions(source, connection, rows);
        assertThat(org.mockito.Mockito.mockingDetails(db).getInvocations())
                .allSatisfy(
                        call -> assertThat(call.getMethod().getName()).isEqualTo("getDataSource"));
    }

    @Test
    void wrongOwnerAndManagerTypeAreRejectedWithoutSql() {
        assertThatThrownBy(
                        () ->
                                new MemberAccessCleanupService(
                                        db,
                                        new DataSourceTransactionManager(mock(DataSource.class))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_MEMBER_TRANSACTION_MANAGER");
        assertThatThrownBy(
                        () ->
                                new MemberAccessCleanupService(
                                        db, mock(PlatformTransactionManager.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_MEMBER_TRANSACTION_MANAGER");
        assertThatThrownBy(() -> new MemberAccessCleanupService(db, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_MEMBER_TRANSACTION_MANAGER");
        verifyNoInteractions(source, connection, rows);
        assertThat(org.mockito.Mockito.mockingDetails(db).getInvocations())
                .allSatisfy(
                        call -> assertThat(call.getMethod().getName()).isEqualTo("getDataSource"));
    }

    @Test
    void missingJdbcSourceAndNullTemplateAreRejectedWithoutSql() {
        when(db.getDataSource()).thenReturn(null);
        assertThatThrownBy(
                        () ->
                                new MemberAccessCleanupService(
                                        db, new DataSourceTransactionManager(source)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_MEMBER_TRANSACTION_MANAGER");
        assertThatThrownBy(
                        () ->
                                new MemberAccessCleanupService(
                                        null, new DataSourceTransactionManager(source)))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(source, connection, rows);
    }

    @Test
    void constructionUsesSameSourceWithoutExecutingSql() throws Exception {
        var assembled =
                new MemberAccessCleanupService(db, new DataSourceTransactionManager(source));
        verifyNoInteractions(source, connection, rows);
        assertThat(org.mockito.Mockito.mockingDetails(db).getInvocations())
                .allSatisfy(
                        call -> assertThat(call.getMethod().getName()).isEqualTo("getDataSource"));
        result(1, 0, UUID.randomUUID());
        assertThat(assembled.cleanup(1).accesses()).isEqualTo(1);
        verify(source).getConnection();
        verify(connection).commit();
    }

    @Test
    void ambientTransactionOrBoundResourceCannotBorrowAuthority() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.cleanup(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AMBIENT_MEMBER_TRANSACTION");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        TransactionSynchronizationManager.bindResource(source, new Object());
        try {
            assertThatThrownBy(() -> service.cleanup(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AMBIENT_MEMBER_TRANSACTION");
        } finally {
            TransactionSynchronizationManager.unbindResource(source);
        }
        verifyNoInteractions(source, connection, rows);
        assertThat(org.mockito.Mockito.mockingDetails(db).getInvocations())
                .allSatisfy(
                        call -> assertThat(call.getMethod().getName()).isEqualTo("getDataSource"));
    }

    @Test
    void malformedCountAndOperationContractsRollBack() throws Exception {
        Object[][] invalid = {
            {null, 0, null}, {0, null, null}, {-1, 0, null}, {2, 0, UUID.randomUUID()},
            {0, -1, null}, {0, 2, null}, {1, 0, null}, {0, 0, UUID.randomUUID()},
            {0, 1, UUID.randomUUID()}
        };
        for (Object[] values : invalid) {
            result((Integer) values[0], (Integer) values[1], (UUID) values[2]);
            assertThatThrownBy(() -> service.cleanup(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("INVALID_MEMBER_ACCESS_CLEANUP_RESULT");
        }
        verify(connection, org.mockito.Mockito.times(invalid.length)).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void missingOrMultipleRowsRollBack() throws Exception {
        when(rows.next()).thenReturn(false);
        assertThatThrownBy(() -> service.cleanup(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVALID_MEMBER_ACCESS_CLEANUP_RESULT");
        result(1, 0, UUID.randomUUID());
        when(rows.next()).thenReturn(true, true);
        assertThatThrownBy(() -> service.cleanup(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVALID_MEMBER_ACCESS_CLEANUP_RESULT");
        verify(connection, org.mockito.Mockito.times(2)).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void permissionFailureIsVisibleAndRollsBackWithoutFallback() throws Exception {
        var denied =
                new PermissionDeniedDataAccessException(
                        "synthetic permission failure", new SQLException("denied", "42501"));
        doThrow(denied).when(db).query(eq(SQL), extractor(), anyInt());
        assertThatThrownBy(() -> service.cleanup(1)).isSameAs(denied);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void commitFailureCannotReturnUncommittedCounts() throws Exception {
        result(1, 0, UUID.randomUUID());
        doThrow(new SQLException("synthetic commit failure")).when(connection).commit();
        assertThatThrownBy(() -> service.cleanup(1))
                .isInstanceOf(org.springframework.transaction.TransactionException.class);
        verify(connection).commit();
        verify(connection).rollback();
    }

    @Test
    void rollbackFailureCannotReturnCountsAndReleasesOwnedResource() throws Exception {
        result(1, 0, null);
        doThrow(new SQLException("synthetic rollback failure")).when(connection).rollback();
        assertThatThrownBy(() -> service.cleanup(1))
                .isInstanceOf(org.springframework.transaction.TransactionException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void localLockTimeoutFailureRollsBackBeforeEntry() throws Exception {
        doThrow(new org.springframework.dao.QueryTimeoutException("synthetic timeout"))
                .when(db)
                .execute("SET LOCAL lock_timeout='1s'");
        assertThatThrownBy(() -> service.cleanup(1))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(db, never()).query(eq(SQL), extractor(), anyInt());
    }

    @Test
    void finalBudgetSettingFailureRollsBackInsteadOfReturningCounts() throws Exception {
        result(1, 0, UUID.randomUUID());
        var timeout = new org.springframework.dao.QueryTimeoutException("synthetic budget failure");
        when(db.queryForObject(
                        eq("SELECT set_config('statement_timeout', ?, true)"),
                        eq(String.class),
                        anyString()))
                .thenReturn("10000ms", "9999ms")
                .thenThrow(timeout);
        assertThatThrownBy(() -> service.cleanup(1)).isSameAs(timeout);
        verify(db).query(eq(SQL), extractor(), eq(1));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    /** null이 아닌 제네릭 JDBC 추출자 matcher를 만들어 원시 타입 변환을 피한다. */
    private static ResultSetExtractor<MemberAccessCleanupService.Counts> extractor() {
        return org.mockito.ArgumentMatchers.notNull();
    }

    /**
     * 함수가 반환할 합성 단일 행을 준비한다.
     *
     * @param accesses nullable 접근 이력 삭제 수이며 잘못된 결과 시험을 허용한다
     * @param receipts nullable 만료 영수증 삭제 수이며 잘못된 결과 시험을 허용한다
     * @param operation nullable 새 접근 이력 배치 UUID
     * @throws SQLException 합성 JDBC 결과 설정이 실패한 경우
     */
    private void result(Integer accesses, Integer receipts, UUID operation) throws Exception {
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject("accesses", Integer.class)).thenReturn(accesses);
        when(rows.getObject("receipts", Integer.class)).thenReturn(receipts);
        when(rows.getObject("operation_key", UUID.class)).thenReturn(operation);
    }
}
