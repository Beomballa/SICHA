package com.reasoning.common.grading.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 호출자가 이미 검사하고 잠근 업무 루트 트랜잭션에 최소 감사를 추가한다. 감사 자체는 root·epoch·source·임대·deadline·dispatch 승인이 아니며
 * TLS, 영수증 재생, 완료·복구 전이는 호출자 책임이다. 자동 등록하거나 트랜잭션을 생성·커밋하지 않는다.
 */
public final class GradeEventRepository {
    private final JdbcTemplate jdbc;
    private final GradeWorkerCredentials credentials;
    private final String coordinatorKey;

    /** 허용된 감사 사건 종류다. */
    public enum EventKind {
        COMPLETE_APPLIED,
        COMPLETE_REJECTED,
        RECOVERY_EXPIRED,
        JOB_ACTIVATED,
        JOB_INPUT_REJECTED,
        JOB_SOURCE_CANCELLED,
        JOB_LEASE_RECLAIMED
    }

    /** 감사에 허용된 작업 상태다. */
    public enum JobState {
        STAGED,
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /** 감사에 허용된 시도 상태다. */
    public enum AttemptState {
        RUNNING,
        SUCCEEDED,
        FAILED,
        EXPIRED
    }

    /** 원문 대신 기록하는 닫힌 사유다. */
    public enum Reason {
        NONE,
        STALE_LEASE,
        TERMINAL,
        DEADLINE_EXCEEDED,
        SOURCE_REVOKED,
        RUNTIME_EPOCH_CHANGED,
        ENGINE_TIMEOUT,
        ENGINE_UNAVAILABLE,
        INVALID_OUTPUT,
        UNRESOLVED_REASONING,
        WORKER_LOST,
        INVALID_REPORT
    }

    /** 자유 문자열·원문 없이 고정 필드만 소유하는 불변 감사 상세다. */
    public record Detail(
            long leaseGen,
            JobState beforeJob,
            JobState afterJob,
            AttemptState beforeAttempt,
            AttemptState afterAttempt,
            Reason reason,
            String outputHash,
            String resultHash) {
        /**
         * @param leaseGen 0 이상 임대 세대
         * @param beforeJob 변경 전 작업 상태, null 불가
         * @param afterJob 변경 후 작업 상태, null 불가
         * @param beforeAttempt 변경 전 시도 상태, 시도 없으면 null 허용
         * @param afterAttempt 변경 후 시도 상태, 시도 없으면 null 허용
         * @param reason 닫힌 사유, null 불가
         * @param outputHash 소문자 SHA-256 64자, null 허용
         * @param resultHash 소문자 SHA-256 64자, null 허용
         * @throws IllegalArgumentException 잘못된 값이면 원인 없는 INVALID_GRADE_AUDIT
         */
        public Detail {
            if (leaseGen < 0
                    || beforeJob == null
                    || afterJob == null
                    || reason == null
                    || (outputHash != null && !hash(outputHash))
                    || (resultHash != null && !hash(resultHash))) throw invalid();
        }

        /**
         * @return 해시를 제외한 세대·상태·사유만 반환한다
         */
        @Override
        public String toString() {
            return "Detail[leaseGen="
                    + leaseGen
                    + ", beforeJob="
                    + beforeJob
                    + ", afterJob="
                    + afterJob
                    + ", beforeAttempt="
                    + beforeAttempt
                    + ", afterAttempt="
                    + afterAttempt
                    + ", reason="
                    + reason
                    + "]";
        }
    }

    /**
     * @param jdbc 호출자 트랜잭션과 같은 datasource, null 불가
     * @param credentials 실제 배포 worker 레지스트리, null 불가
     * @param trustedCoordinatorKey 신뢰된 배포 coordinator의 ASCII 영숫자·밑줄·하이픈 1~80자, null 불가
     * @throws IllegalArgumentException 잘못된 설정이면 원인 없는 INVALID_GRADE_AUDIT
     */
    public GradeEventRepository(
            JdbcTemplate jdbc, GradeWorkerCredentials credentials, String trustedCoordinatorKey) {
        if (jdbc == null
                || jdbc.getDataSource() == null
                || credentials == null
                || trustedCoordinatorKey == null
                || !trustedCoordinatorKey.matches("[A-Za-z0-9_-]{1,80}")) throw invalid();
        this.jdbc = jdbc;
        this.credentials = credentials;
        this.coordinatorKey = trustedCoordinatorKey;
    }

    /**
     * 실제 작업 runtime에 대한 소유 레지스트리 COMPLETE 권한을 검사한 뒤 추가한다. 성공 영수증 재생에는 호출하지 않는다.
     *
     * @param worker 실제 인증 증명, null·외부 레지스트리 증명은 거절
     * @param jobId 이미 업무 루트 검사·잠금을 완료한 양수 실제 작업 식별자
     * @param attemptNo 실제 시도 1~3
     * @param kind COMPLETE_APPLIED 또는 COMPLETE_REJECTED, null 불가
     * @param commandKey 서버 내부 비영 UUID, null 불가
     * @param requestId 실제 접근 요청 비영 UUID, null 불가
     * @param commandHash 서버 정규 명령 소문자 SHA-256 64자, null 불가
     * @param detail 고정 typed 상세, null 불가
     * @return 실제 DB 생성 감사 식별자
     * @throws IllegalArgumentException 잘못된 입력이면 원인 없는 INVALID_GRADE_AUDIT
     * @throws SecurityException 실제 소유자·행동·runtime 권한이 없으면 WORKER_NOT_AUTHORIZED
     * @throws IllegalStateException TX 경계 위반이면 GRADE_AUDIT_REQUIRES_WRITE_READ_COMMITTED; 저장·FK·중복
     *     실패이면 원인 없는 GRADE_AUDIT_STORAGE_FAILURE이며 호출자는 전체 업무 TX를 롤백해야 한다
     */
    public long recordWorkerEvent(
            VerifiedWorker worker,
            long jobId,
            int attemptNo,
            EventKind kind,
            UUID commandKey,
            UUID requestId,
            String commandHash,
            Detail detail) {
        validate(jobId, commandKey, commandHash, detail);
        if (!workerKind(kind) || attemptNo < 1 || attemptNo > 3 || !uuid(requestId))
            throw invalid();
        return append(worker, jobId, attemptNo, kind, commandKey, requestId, commandHash, detail);
    }

    /**
     * 신뢰된 생성자 coordinator만 SYSTEM 주체로 기록한다. 업무 eligibility 승인을 대신하지 않는다.
     *
     * @param jobId 이미 업무 루트 검사·잠금을 완료한 양수 실제 작업 식별자
     * @param attemptNo RECOVERY_EXPIRED는 실제 시도 1~3 필수, 네 JOB 사건은 null 필수
     * @param kind RECOVERY_EXPIRED 또는 네 JOB 사건, null 불가
     * @param commandKey 서버 내부 비영 UUID, null 불가
     * @param commandHash 서버 정규 명령 소문자 SHA-256 64자, null 불가
     * @param detail 고정 typed 상세, null 불가
     * @return 실제 DB 생성 감사 식별자; request_id는 항상 NULL
     * @throws IllegalArgumentException 잘못된 입력이면 원인 없는 INVALID_GRADE_AUDIT
     * @throws IllegalStateException TX 경계 위반이면 GRADE_AUDIT_REQUIRES_WRITE_READ_COMMITTED; 저장·FK·중복
     *     실패이면 원인 없는 GRADE_AUDIT_STORAGE_FAILURE이며 호출자는 전체 업무 TX를 롤백해야 한다
     */
    public long recordSystemEvent(
            long jobId,
            Integer attemptNo,
            EventKind kind,
            UUID commandKey,
            String commandHash,
            Detail detail) {
        validate(jobId, commandKey, commandHash, detail);
        if (kind == null
                || workerKind(kind)
                || (kind == EventKind.RECOVERY_EXPIRED
                        ? attemptNo == null || attemptNo < 1 || attemptNo > 3
                        : attemptNo != null)) throw invalid();
        return append(null, jobId, attemptNo, kind, commandKey, null, commandHash, detail);
    }

    /** 같은 bound 연결을 빌려 검사한 뒤 INSERT만 수행하고 빌림을 반드시 해제한다. */
    private long append(
            VerifiedWorker worker,
            long jobId,
            Integer attemptNo,
            EventKind kind,
            UUID commandKey,
            UUID requestId,
            String commandHash,
            Detail detail) {
        ConnectionHolder holder = requireTransaction();
        holder.requested();
        try {
            return jdbc.execute(
                    (ConnectionCallback<Long>)
                            connection -> {
                                Connection target = DataSourceUtils.getTargetConnection(connection);
                                if (target
                                        != DataSourceUtils.getTargetConnection(
                                                holder.getConnection())) throw boundary();
                                checkConnection(target);
                                String actorKey = coordinatorKey;
                                if (workerKind(kind)) {
                                    String runtimeCode;
                                    try (var query =
                                            connection.prepareStatement(
                                                    "SELECT r.code FROM public.grade_job j JOIN"
                                                            + " public.grade_runtime r ON"
                                                            + " r.id=j.runtime_id WHERE j.id=?")) {
                                        query.setLong(1, jobId);
                                        try (var rows = query.executeQuery()) {
                                            if (!rows.next()) throw storage();
                                            runtimeCode = rows.getString(1);
                                        }
                                    }
                                    credentials.requirePermission(
                                            worker, Action.COMPLETE, runtimeCode);
                                    actorKey = worker.workerKey();
                                }
                                try (var insert =
                                        connection.prepareStatement(
                                                """
                                                INSERT INTO public.grade_event(job_id,attempt_no,actor_kind,actor_key,
                                                    event_kind,command_key,request_id,command_hash,detail)
                                                VALUES (?,?,?,?,?,?,?,?,?::jsonb) RETURNING id
                                                """)) {
                                    insert.setLong(1, jobId);
                                    insert.setObject(2, attemptNo);
                                    insert.setString(3, workerKind(kind) ? "WORKER" : "SYSTEM");
                                    insert.setString(4, actorKey);
                                    insert.setString(5, kind.name());
                                    insert.setObject(6, commandKey);
                                    insert.setObject(7, requestId);
                                    insert.setString(8, commandHash);
                                    insert.setString(9, safeJson(detail));
                                    // 실제 JSONB 텍스트 크기는 V14 CHECK가 권위 있게 검사한다.
                                    try (var rows = insert.executeQuery()) {
                                        if (!rows.next()) throw storage();
                                        return rows.getLong(1);
                                    }
                                }
                            });
        } catch (DataAccessException failure) {
            throw storage();
        } finally {
            holder.released();
        }
    }

    /** datasource 자원이 없는 경우 연결을 새로 획득하지 않고 SQL 전에 거절한다. */
    private ConnectionHolder requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw boundary();
        Object resource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if (!(resource instanceof ConnectionHolder holder)
                || holder.getConnectionHandle() == null
                || !holder.isSynchronizedWithTransaction()) throw boundary();
        try {
            checkConnection(DataSourceUtils.getTargetConnection(holder.getConnection()));
        } catch (SQLException failure) {
            throw storage();
        }
        return holder;
    }

    /** JDBC 실제 연결 속성으로 SQL 실행 이전 쓰기 READ COMMITTED를 확인한다. */
    private static void checkConnection(Connection connection) throws SQLException {
        if (connection.getAutoCommit()
                || connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
            throw boundary();
    }

    /** 고정 여덟 필드와 명시적 null만 SnapshotJson 정규 바이트로 직렬화한다. */
    private static String safeJson(Detail detail) {
        var node = JsonNodeFactory.instance.objectNode();
        node.put("leaseGen", detail.leaseGen());
        node.put("beforeJob", detail.beforeJob().name());
        node.put("afterJob", detail.afterJob().name());
        node.put(
                "beforeAttempt",
                detail.beforeAttempt() == null ? null : detail.beforeAttempt().name());
        node.put(
                "afterAttempt",
                detail.afterAttempt() == null ? null : detail.afterAttempt().name());
        node.put("reason", detail.reason().name());
        node.put("outputHash", detail.outputHash());
        node.put("resultHash", detail.resultHash());
        return new String(SnapshotJson.encode(node), StandardCharsets.UTF_8);
    }

    private static void validate(long jobId, UUID command, String hash, Detail detail) {
        if (jobId <= 0 || !uuid(command) || !hash(hash) || detail == null) throw invalid();
    }

    private static boolean workerKind(EventKind kind) {
        return kind == EventKind.COMPLETE_APPLIED || kind == EventKind.COMPLETE_REJECTED;
    }

    private static boolean uuid(UUID value) {
        return value != null
                && (value.getMostSignificantBits() != 0 || value.getLeastSignificantBits() != 0);
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_GRADE_AUDIT");
    }

    private static IllegalStateException boundary() {
        return new IllegalStateException("GRADE_AUDIT_REQUIRES_WRITE_READ_COMMITTED");
    }

    private static IllegalStateException storage() {
        return new IllegalStateException("GRADE_AUDIT_STORAGE_FAILURE");
    }
}
