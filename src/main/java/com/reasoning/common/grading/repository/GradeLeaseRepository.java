package com.reasoning.common.grading.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 짧은 임대 트랜잭션만 소유한다. 검증된 worker 신원과 runtime 허용 권한은 미래 서비스 호출자의 책임이다. AVAILABLE은 GRADE 승인이 아니며 여기서는
 * 업무 효력·runtime 상태·epoch를 검증하지 않는다. claim을 끝낸 뒤 별도 start 트랜잭션이 정상 루트 잠금 순서로 자격과 호출 예약을 검사해야 한다.
 */
@Repository
public class GradeLeaseRepository {
    // 고정된 임대 전용 advisory namespace. SHA-256 앞 32비트 충돌은 추가 직렬화만 유발한다.
    private static final int WORKER_NAMESPACE = 0x47524c53;
    private static final int EXPIRED_LIMIT = 100;
    private static final String SELECT_JOB =
            """
            SELECT j.id,j.snapshot_id,j.batch_id,j.runtime_id,j.job_key,j.worker_key,
                r.code AS runtime_code,j.lease_gen,j.lease_until,j.deadline_at,
                j.state,j.call_count,j.next_run_at
            FROM public.grade_job j JOIN public.grade_runtime r ON r.id=j.runtime_id
            """;
    private static final RowMapper<Job> ROW =
            (row, number) ->
                    new Job(
                            new Lease(
                                    row.getLong("id"),
                                    row.getLong("snapshot_id"),
                                    row.getLong("batch_id"),
                                    row.getLong("runtime_id"),
                                    row.getObject("job_key", UUID.class),
                                    row.getString("worker_key"),
                                    row.getString("runtime_code"),
                                    row.getLong("lease_gen"),
                                    row.getObject("lease_until", OffsetDateTime.class),
                                    row.getObject("deadline_at", OffsetDateTime.class)),
                            row.getString("state"),
                            row.getInt("call_count"),
                            row.getObject("next_run_at", OffsetDateTime.class));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    /**
     * @param jdbc 임대 DB 연결 도구
     * @param manager 같은 연결의 트랜잭션 관리자
     */
    public GradeLeaseRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = template(Objects.requireNonNull(manager), false);
        readTransaction = template(manager, true);
    }

    /**
     * 인증 worker당 하나의 임대를 할당하거나 동일 runtime의 유효한 기존 임대를 그대로 반환한다. 만료·다른 runtime의 RUNNING 행은 고치지 않는다.
     * coordinator가 별도 정상 루트 순서로 정비한다. SKIP LOCKED는 엄격 FIFO를 보장하지 않으며 STAGED나 호출 예약은 건드리지 않는다.
     *
     * @param workerKey 호출자가 인증한 공백 아닌 80자 이하 키, null 불가
     * @param runtimeCode 허용 권한을 확인한 grade_runtime.code, 공백 아닌 80자 이하, null 불가
     * @return 할당·재전송 임대 또는 할당 불가
     * @throws IllegalArgumentException 입력이 잘못된 경우
     * @throws IllegalStateException 호출자가 이미 트랜잭션을 보유한 경우
     */
    public Optional<Lease> claim(String workerKey, String runtimeCode) {
        key(workerKey);
        key(runtimeCode);
        return shortTransaction(
                transaction,
                () -> {
                    jdbc.queryForList(
                            "SELECT pg_advisory_xact_lock(?,?)",
                            WORKER_NAMESPACE,
                            workerHash(workerKey));
                    var existing =
                            jdbc.query(
                                    SELECT_JOB
                                            + " WHERE j.state='RUNNING' AND j.worker_key=? FOR"
                                            + " UPDATE OF j",
                                    ROW,
                                    workerKey);
                    if (!existing.isEmpty()) {
                        Lease lease = existing.getFirst().lease();
                        OffsetDateTime now = dbNow();
                        return lease.runtimeCode().equals(runtimeCode) && valid(lease, now)
                                ? Optional.of(lease)
                                : Optional.empty();
                    }
                    var candidates =
                            jdbc.query(
                                    SELECT_JOB
                                            + """
                                             WHERE r.code=? AND j.state='QUEUED' AND j.next_run_at<=clock_timestamp()
                                                 AND j.deadline_at>clock_timestamp() AND j.call_count<3
                                             ORDER BY j.next_run_at,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                                            """,
                                    ROW,
                                    runtimeCode);
                    if (candidates.isEmpty()) return Optional.empty();
                    Job job = candidates.getFirst();
                    OffsetDateTime now = dbNow();
                    if (!job.state().equals("QUEUED")
                            || job.callCount() >= 3
                            || !job.lease().deadlineAt().isAfter(now)
                            || job.nextRunAt().isAfter(now)) {
                        return Optional.empty();
                    }
                    Lease prior = job.lease();
                    OffsetDateTime until = leaseUntil(now, prior.deadlineAt());
                    jdbc.update(
                            """
                            UPDATE public.grade_job SET state='RUNNING',worker_key=?,lease_gen=lease_gen+1,
                                lease_until=?,updated_at=? WHERE id=?
                            """,
                            workerKey,
                            until,
                            now,
                            prior.jobId());
                    return Optional.of(
                            new Lease(
                                    prior.jobId(),
                                    prior.snapshotId(),
                                    prior.batchId(),
                                    prior.runtimeId(),
                                    prior.jobKey(),
                                    workerKey,
                                    prior.runtimeCode(),
                                    prior.leaseGen() + 1,
                                    until,
                                    prior.deadlineAt()));
                });
    }

    /**
     * job만 잠그고 실제 잠금 후 DB 시각으로 유효한 임대를 연장한다. 세 번째 RUNNING 호출도 갱신 가능하다.
     *
     * @param jobKey 작업 UUID, null 불가
     * @param workerKey 호출자가 인증한 공백 아닌 80자 이하 키
     * @param leaseGen 양수 임대 세대
     * @return 현재 worker·세대·상태·시한이 모두 일치한 갱신 임대 또는 빈 값
     * @throws IllegalArgumentException 입력이 잘못된 경우
     * @throws IllegalStateException 호출자가 이미 트랜잭션을 보유한 경우
     */
    public Optional<Lease> renew(UUID jobKey, String workerKey, long leaseGen) {
        key(workerKey);
        if (jobKey == null || leaseGen <= 0)
            throw new IllegalArgumentException("INVALID_LEASE_INPUT");
        return shortTransaction(
                transaction,
                () -> {
                    var rows =
                            jdbc.query(
                                    SELECT_JOB + " WHERE j.job_key=? FOR UPDATE OF j", ROW, jobKey);
                    if (rows.isEmpty()) return Optional.empty();
                    Job job = rows.getFirst();
                    Lease lease = job.lease();
                    OffsetDateTime now = dbNow();
                    if (!job.state().equals("RUNNING")
                            || !workerKey.equals(lease.workerKey())
                            || leaseGen != lease.leaseGen()
                            || !valid(lease, now)) return Optional.empty();
                    OffsetDateTime until = leaseUntil(now, lease.deadlineAt());
                    jdbc.update(
                            "UPDATE public.grade_job SET lease_until=?,updated_at=? WHERE id=?",
                            until,
                            now,
                            lease.jobId());
                    return Optional.of(
                            new Lease(
                                    lease.jobId(),
                                    lease.snapshotId(),
                                    lease.batchId(),
                                    lease.runtimeId(),
                                    lease.jobKey(),
                                    workerKey,
                                    lease.runtimeCode(),
                                    leaseGen,
                                    until,
                                    lease.deadlineAt()));
                });
    }

    /**
     * 잠금·상태 변경 없이 만료 RUNNING 후보 ID만 읽는다. 이후 coordinator가 정상 루트 순서로 재검사한다.
     *
     * @param limit 내부 조회 상한 1~100, endpoint 설정이 아님
     * @return lease 또는 deadline 만료 후보의 불변 ID 목록
     * @throws IllegalArgumentException 상한 범위가 잘못된 경우
     * @throws IllegalStateException 호출자가 이미 트랜잭션을 보유한 경우
     */
    public List<Long> getExpiredLeaseList(int limit) {
        if (limit < 1 || limit > EXPIRED_LIMIT)
            throw new IllegalArgumentException("INVALID_LEASE_LIMIT");
        return shortTransaction(
                readTransaction,
                () ->
                        List.copyOf(
                                jdbc.queryForList(
                                        """
                                        SELECT id FROM public.grade_job WHERE state='RUNNING'
                                            AND (lease_until<=clock_timestamp() OR deadline_at<=clock_timestamp())
                                        ORDER BY lease_until,id LIMIT ?
                                        """,
                                        Long.class,
                                        limit)));
    }

    /** 외부 루트 잠금을 이어받거나 중단한 채 새 임대를 만들지 않는다. */
    private <T> T shortTransaction(TransactionTemplate template, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LEASE_REQUIRES_SEPARATE_TRANSACTION");
        }
        return template.execute(
                status -> {
                    jdbc.execute("SET LOCAL lock_timeout='5s'");
                    return work.get();
                });
    }

    /** 입구에서 기존 TX를 거절하고 신규 TX를 명시적으로 소유하며 READ COMMITTED를 사용한다. */
    private static TransactionTemplate template(
            PlatformTransactionManager manager, boolean readOnly) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(readOnly);
        return template;
    }

    /** 만료 권위는 JVM 시계가 아니라 잠금 획득 후 읽은 DB 시계다. */
    private OffsetDateTime dbNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    /** 기존 임대와 전체 deadline은 모두 엄격히 미래여야 한다. */
    private static boolean valid(Lease lease, OffsetDateTime now) {
        return lease.leaseUntil() != null
                && lease.deadlineAt() != null
                && lease.leaseUntil().isAfter(now)
                && lease.deadlineAt().isAfter(now);
    }

    /** 갱신으로 고정 deadline을 넘어가지 않는다. */
    private static OffsetDateTime leaseUntil(OffsetDateTime now, OffsetDateTime deadline) {
        OffsetDateTime until = now.plusSeconds(30);
        return until.isBefore(deadline) ? until : deadline;
    }

    /** 신뢰된 키의 최소 경계만 검사하며 정규화나 새 공개 형식을 만들지 않는다. */
    private static void key(String key) {
        if (key == null || key.isBlank() || key.length() > 80) {
            throw new IllegalArgumentException("INVALID_LEASE_INPUT");
        }
    }

    /** JVM 실행마다 달라지지 않는 인증 worker advisory 키를 만든다. */
    private static int workerHash(String worker) {
        try {
            return ByteBuffer.wrap(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(worker.getBytes(StandardCharsets.UTF_8)))
                    .getInt();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", failure);
        }
    }

    /** 잠금 조회의 내부 상태이며 실행 입력이나 결과를 포함하지 않는다. */
    private record Job(Lease lease, String state, int callCount, OffsetDateTime nextRunAt) {}

    /** 후속 서비스 관계 재검사용 불변 임대 사본. 보고서·정답·실행 구성 원문은 포함하지 않는다. */
    public record Lease(
            long jobId,
            long snapshotId,
            long batchId,
            long runtimeId,
            UUID jobKey,
            String workerKey,
            String runtimeCode,
            long leaseGen,
            OffsetDateTime leaseUntil,
            OffsetDateTime deadlineAt) {}
}
