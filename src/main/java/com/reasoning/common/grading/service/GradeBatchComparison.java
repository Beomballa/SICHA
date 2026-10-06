package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.grading.service.FrozenDatasetValidator.SelectedSample;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 실제 완료·복구 증거와 인증된 내부 계산을 안전 비교로만 투영한다. 권한이나 효력을 발급하지 않는다. */
public final class GradeBatchComparison {
    private final JdbcTemplate db;
    private final CryptoService crypto;

    /**
     * @param jdbc 호출자와 동일 거래의 JDBC 도구, null 불가
     * @param crypto 실제 GCM 서비스, null 불가
     */
    public GradeBatchComparison(JdbcTemplate jdbc, CryptoService crypto) {
        this.db = Objects.requireNonNull(jdbc);
        if (jdbc.getDataSource() == null)
            throw new IllegalArgumentException("INVALID_BATCH_COMPARISON");
        this.crypto = Objects.requireNonNull(crypto);
    }

    /**
     * 실제 필수 감사의 저장 순서를 검사한 뒤 내부 비교만 수행한다. 감사 ID는 인가 수단이 아니다.
     *
     * @param job 이미 잠근 실제 작업 전체
     * @param sample 전체 검증된 선택 fixture
     * @param runtime 실제 저장 runtime
     * @param grading 전체 고정 채점표
     * @param contentReadAuditId 호출자가 인가 이후 저장한 실제 CONTENT_READ 감사
     * @return 원문 없는 비교 및 내부 실패 분류
     * @throws DataAccessResourceFailureException 감사·결과·완료 증거 불일치
     */
    public Comparison compare(
            Map<String, Object> job,
            SelectedSample sample,
            RuntimeRow runtime,
            com.reasoning.common.grading.model.GradeModels.Snapshot grading,
            long contentReadAuditId) {
        try {
            requireTransaction();
            Boolean valid =
                    db.queryForObject(
                            """
                            SELECT a.action='CONTENT_READ' AND a.phase='RESULT' AND a.business_result='SUCCESS'
                                AND a.actor_kind IN ('ADMIN','SYSTEM') AND a.scope_kind='batch'
                                AND a.scope_key='batch:'||b.batch_key::text
                            FROM public.test_audit a JOIN public.grade_job j ON j.id=?
                            JOIN public.grade_batch b ON b.id=j.batch_id
                            WHERE a.id=? AND j.batch_id=? AND j.job_key=?
                            """,
                            Boolean.class,
                            number(job, "id"),
                            contentReadAuditId,
                            number(job, "batch_id"),
                            job.get("job_key"));
            if (!Boolean.TRUE.equals(valid)) throw storage();
            String value = comparison(job, sample, runtime, grading);
            return new Comparison(
                    value,
                    "FAIL".equals(value)
                            ? ("COMPLETED".equals(job.get("state"))
                                    ? FailureKind.GRADING
                                    : FailureKind.INFRA)
                            : "PENDING".equals(value)
                                            && Set.of("FAILED", "CANCELLED")
                                                    .contains(job.get("state"))
                                    ? FailureKind.INFRA
                                    : FailureKind.NONE);
        } catch (RuntimeException failure) {
            throw storage();
        }
    }

    /**
     * JDBC·JPA 관리자가 결속한 실제 같은 DataSource 쓰기 READ COMMITTED 연결에서만 내부 비교를 허용한다.
     *
     * @throws DataAccessResourceFailureException 거래·바인딩·연결 불일치
     */
    private void requireTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager
                        .isSynchronizationActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly()) throw storage();
        Object resource =
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .getResource(db.getDataSource());
        if (!(resource instanceof org.springframework.jdbc.datasource.ConnectionHolder holder)
                || holder.getConnectionHandle() == null) throw storage();
        Boolean valid =
                db.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                                connection -> {
                                    var target =
                                            org.springframework.jdbc.datasource.DataSourceUtils
                                                    .getTargetConnection(connection);
                                    return target
                                                    == org.springframework.jdbc.datasource
                                                            .DataSourceUtils.getTargetConnection(
                                                            holder.getConnection())
                                            && org.springframework.jdbc.datasource.DataSourceUtils
                                                    .isConnectionTransactional(
                                                            target, db.getDataSource())
                                            && !target.getAutoCommit()
                                            && !target.isReadOnly()
                                            && target.getTransactionIsolation()
                                                    == java.sql.Connection
                                                            .TRANSACTION_READ_COMMITTED;
                                });
        if (!Boolean.TRUE.equals(valid)) throw storage();
    }

    /**
     * 안전 비교 분류이며 점수·원문·암호를 포함하지 않는다.
     *
     * @param comparison PASS·FAIL·PENDING 안전 표시
     * @param failureKind 실제 불일치·기술 불완전 분류
     */
    public record Comparison(String comparison, FailureKind failureKind) {}

    /** 인증된 정상 불일치와 기술적 불완전을 구분한다. */
    public enum FailureKind {
        NONE,
        GRADING,
        INFRA
    }

    /**
     * 활성 작업은 항상 PENDING이며 terminal만 전체 기대값과 내구 신뢰 증거로 비교한다.
     *
     * @param job 실제 잠근 작업 전체
     * @param sample 전체 검증된 fixture
     * @param runtime 실제 불변 실행 구성
     * @param grading 전체 고정 채점표
     * @return PASS·FAIL·PENDING만 반환
     * @throws DataAccessResourceFailureException 실제 완료·복구·결과 증거 불일치
     */
    private String comparison(
            Map<String, Object> job,
            SelectedSample sample,
            RuntimeRow runtime,
            com.reasoning.common.grading.model.GradeModels.Snapshot grading) {
        String state = (String) job.get("state");
        if ("STAGED".equals(state)
                && (number(job, "call_count") != 0
                        || number(job, "lease_gen") != 0
                        || job.get("accepted_at") != null
                        || job.get("deadline_at") != null)) throw storage();
        if (Set.of("STAGED", "QUEUED", "RUNNING").contains(state)) return "PENDING";
        long id = number(job, "id");
        // 자식 이후 시도·감사를 잠그므로 coordinator/완료와 잠금 순서가 동일하다.
        var attempts =
                db.queryForList(
                        """
                        SELECT job_id,attempt_no,lease_gen,worker_key,started_at,ended_at,state,error_code,
                            output_hash,output_cipher IS NOT NULL AS has_output_cipher,observed_version,
                            completion_data::text AS completion_data
                        FROM grade_attempt WHERE job_id=? ORDER BY attempt_no FOR UPDATE
                        """,
                        id);
        var events =
                db.queryForList(
                        "SELECT * FROM grade_event WHERE job_id=? ORDER BY id FOR UPDATE", id);
        if (systemEffect(job, attempts, events)) return "PENDING";
        if ("CANCELLED".equals(state)) {
            if (job.get("result_data") != null || job.get("error_code") == null) throw storage();
            return "PENDING";
        }
        if ("COMPLETED".equals(state)) {
            JsonNode summary = parse(job.get("result_data"));
            if ("INPUT_ERROR".equals(sample.kind())) {
                if (!SnapshotJson.hash(summary).equals(job.get("result_hash"))) throw storage();
                exact(
                        summary,
                        "kind",
                        "error",
                        "attemptDelta",
                        "baseScore",
                        "baseSuccess",
                        "expectationAgreement",
                        "providerMatch",
                        "candidateAdoption",
                        "providerApplicable",
                        "validationSource",
                        "validatorVersion",
                        "validatedAt");
                var event =
                        events.stream()
                                .filter(
                                        e ->
                                                "SYSTEM".equals(e.get("actor_kind"))
                                                        && "JOB_INPUT_REJECTED"
                                                                .equals(e.get("event_kind"))
                                                        && e.get("attempt_no") == null)
                                .findFirst()
                                .orElseThrow(GradeBatchComparison::storage);
                JsonNode evidence = parse(event.get("detail"));
                exact(
                        evidence,
                        "leaseGen",
                        "beforeJob",
                        "afterJob",
                        "beforeAttempt",
                        "afterAttempt",
                        "reason",
                        "outputHash",
                        "resultHash");
                if (evidence.path("leaseGen").longValue() != 0
                        || !"STAGED".equals(evidence.path("beforeJob").asText())
                        || !"COMPLETED".equals(evidence.path("afterJob").asText())
                        || !evidence.path("beforeAttempt").isNull()
                        || !evidence.path("afterAttempt").isNull()
                        || !"INVALID_REPORT".equals(evidence.path("reason").asText())
                        || !evidence.path("outputHash").isNull()
                        || !Objects.equals(
                                job.get("result_hash"), evidence.path("resultHash").textValue()))
                    throw storage();
                if (!attempts.isEmpty()
                        || number(job, "call_count") != 0
                        || !Boolean.FALSE.equals(job.get("has_result_cipher"))
                        || !"INVALID_REPORT".equals(job.get("error_code"))
                        || !"INPUT_ERROR".equals(summary.path("kind").asText())
                        || !"SERVER_REPORT_VALIDATOR"
                                .equals(summary.path("validationSource").asText())
                        || !"REPORT-1".equals(summary.path("validatorVersion").asText())
                        || !instant(job.get("accepted_at"))
                                .equals(Instant.parse(summary.path("validatedAt").textValue()))
                        || !summary.path("baseScore").isNull()
                        || !summary.path("baseSuccess").isNull()
                        || !summary.path("attemptDelta").isIntegralNumber()
                        || summary.path("attemptDelta").intValue() != 0
                        || !summary.path("expectationAgreement").isBoolean()
                        || !summary.path("expectationAgreement").booleanValue()
                        || !falseBoolean(summary.get("providerMatch"))
                        || !falseBoolean(summary.get("candidateAdoption"))
                        || !falseBoolean(summary.get("providerApplicable"))) throw storage();
                if (!same(summary.get("error"), sample.expectation().get("error"))) throw storage();
                return "PASS";
            }
            if (!Boolean.TRUE.equals(job.get("has_result_cipher"))
                    || job.get("error_code") != null
                    || attempts.isEmpty()) throw storage();
            exact(
                    summary,
                    "formatNo",
                    "baseScore",
                    "success",
                    "items",
                    "expectationMatched",
                    "providerVersionMatched",
                    "fixtureAdoptionEligible");
            if (!summary.path("expectationMatched").isBoolean()
                    || !summary.path("providerVersionMatched").isBoolean()
                    || !summary.path("fixtureAdoptionEligible").isBoolean()
                    || !summary.path("formatNo").isIntegralNumber()
                    || !summary.path("formatNo").canConvertToInt()
                    || summary.path("formatNo").intValue() != 1
                    || !summary.path("baseScore").isIntegralNumber()
                    || !summary.path("baseScore").canConvertToInt()
                    || !summary.path("success").isBoolean()
                    || !summary.path("items").isArray()) throw storage();
            requireSummary(summary, grading);
            boolean matched =
                    "GRADED".equals(sample.kind())
                            && Objects.equals(
                                    sample.expectedScore(), summary.path("baseScore").intValue())
                            && Objects.equals(
                                    sample.expectedSuccess(),
                                    summary.path("success").booleanValue())
                            && sameItems(summary.get("items"), sample.expectation().get("items"));
            if (matched != summary.path("expectationMatched").booleanValue()
                    || summary.path("fixtureAdoptionEligible").booleanValue()
                            != (matched && summary.path("providerVersionMatched").booleanValue()))
                throw storage();
            var last = attempts.getLast();
            if (number(job, "call_count") != attempts.size()) throw storage();
            for (int index = 0; index < attempts.size(); index++) {
                var attempt = attempts.get(index);
                if (number(attempt, "attempt_no") != index + 1
                        || attempt.get("ended_at") == null
                        || instant(attempt.get("ended_at"))
                                .isBefore(instant(attempt.get("started_at")))) throw storage();
                if (index + 1 != attempts.size()) {
                    if (!"FAILED".equals(attempt.get("state"))
                            || attempt.get("output_hash") != null
                            || !Boolean.FALSE.equals(attempt.get("has_output_cipher")))
                        throw storage();
                    if (attempt.get("completion_data") == null)
                        requireRecoveryAttempt(attempt, events);
                    else requireAttemptReceipt(job, attempt, events);
                }
            }
            requireCompletion(job, last, events, "COMPLETE");
            requireAuthenticatedSummary(job, summary, grading);
            if (!"SUCCEEDED".equals(last.get("state"))
                    || last.get("error_code") != null
                    || last.get("output_hash") == null
                    || !Boolean.TRUE.equals(last.get("has_output_cipher"))
                    || job.get("result_hash") == null) throw storage();
            boolean observedMatch =
                    last.get("observed_version") != null
                            && last.get("observed_version")
                                    .equals(
                                            parse(runtime.configJson())
                                                    .path("modelVersion")
                                                    .textValue());
            if (observedMatch != summary.path("providerVersionMatched").booleanValue())
                throw storage();
            return matched ? "PASS" : "FAIL";
        }
        if (!"FAILED".equals(state)
                || job.get("result_data") != null
                || job.get("result_hash") != null
                || !Boolean.FALSE.equals(job.get("has_result_cipher"))
                || job.get("error_code") == null) throw storage();
        if (attempts.isEmpty()) {
            // 활성화 취소/집합 만료는 실행 비교 증거가 아니다.
            if (events.stream()
                    .noneMatch(
                            e ->
                                    "SYSTEM".equals(e.get("actor_kind"))
                                            && "JOB_SOURCE_CANCELLED".equals(e.get("event_kind"))))
                throw storage();
            return "PENDING";
        }
        var last = attempts.getLast();
        boolean recovery = last.get("completion_data") == null;
        if (recovery) requireRecovery(job, last, events);
        else requireCompletion(job, last, events, "SYSTEM_ERROR");
        if (number(job, "call_count") != attempts.size() || (!recovery && attempts.size() != 3))
            throw storage();
        String expectedError =
                sample.fault() == null
                        ? null
                        : "TIMEOUT".equals(sample.fault().type())
                                ? "ENGINE_TIMEOUT"
                                : "ENGINE_UNAVAILABLE";
        boolean matches =
                !recovery && "ENGINE_ERROR".equals(sample.kind()) && expectedError != null;
        for (int i = 0; i < attempts.size(); i++) {
            var attempt = attempts.get(i);
            if (number(attempt, "attempt_no") != i + 1
                    || !"FAILED".equals(attempt.get("state"))
                    || attempt.get("ended_at") == null
                    || attempt.get("output_hash") != null
                    || instant(attempt.get("ended_at")).isBefore(instant(attempt.get("started_at")))
                    || !Boolean.FALSE.equals(attempt.get("has_output_cipher"))) throw storage();
            if (attempt.get("completion_data") == null) requireRecoveryAttempt(attempt, events);
            else requireAttemptReceipt(job, attempt, events);
            matches &= expectedError != null && expectedError.equals(attempt.get("error_code"));
        }
        JsonNode error = sample.expectation().get("error");
        matches &=
                expectedError != null
                        && expectedError.equals(job.get("error_code"))
                        && error != null
                        && "GRADING_UNAVAILABLE".equals(error.path("code").asText())
                        && "SYSTEM_ERROR".equals(error.path("state").asText())
                        && error.path("score").isNull()
                        && error.path("attemptDelta").isIntegralNumber()
                        && error.path("attemptDelta").intValue() == 0;
        return matches ? "PASS" : "FAIL";
    }

    /**
     * 집계 자식 효과는 실제 SYSTEM test_audit로만 읽고 완료·복구 영수증으로 재해석하지 않는다.
     *
     * @param job 현재 잠근 실제 작업
     * @param attempts 현재 잠근 실제 예약 전체
     * @param events 실제 완료·복구 감사 전체
     * @return 실제 집계 종료 효과이면 true
     * @throws DataAccessResourceFailureException 실제 효과·작업·시도 결속 불일치
     */
    private boolean systemEffect(
            Map<String, Object> job,
            List<Map<String, Object>> attempts,
            List<Map<String, Object>> events) {
        if (!Set.of("FAILED", "CANCELLED").contains(job.get("state"))) return false;
        var rows =
                db.queryForList(
                        """
                        SELECT a.detail::text FROM public.test_audit a JOIN public.grade_batch b ON b.id=?
                        WHERE a.actor_kind='SYSTEM' AND a.action='BATCH_CHILD_EFFECT' AND a.scope_kind='batch'
                            AND a.scope_key='batch:'||b.batch_key::text AND a.phase='RESULT' AND a.business_result='SUCCESS'
                            AND a.detail->>'jobId'=? ORDER BY a.id FOR UPDATE OF a
                        """,
                        number(job, "batch_id"),
                        Long.toString(number(job, "id")));
        if (rows.isEmpty()) return false;
        if (rows.size() != 1 || number(job, "call_count") != attempts.size()) throw storage();
        JsonNode d = parse(rows.getFirst().get("detail"));
        exact(
                d,
                "jobId",
                "attemptNo",
                "leaseGen",
                "beforeJob",
                "afterJob",
                "beforeAttempt",
                "afterAttempt",
                "reason",
                "endedAt");
        String reason = d.path("reason").textValue();
        if (!Set.of("DEADLINE_EXCEEDED", "SOURCE_REVOKED", "RUNTIME_EPOCH_CHANGED", "TERMINAL")
                        .contains(reason)
                || !Set.of("STAGED", "QUEUED", "RUNNING").contains(d.path("beforeJob").textValue())
                || !job.get("state").equals(d.path("afterJob").textValue())
                || !(Set.of("SOURCE_REVOKED", "TERMINAL").contains(reason) ? "CANCELLED" : "FAILED")
                        .equals(job.get("state"))
                || !Objects.equals(job.get("error_code"), reason)
                || number(job, "lease_gen") != d.path("leaseGen").longValue()
                || !instant(job.get("updated_at"))
                        .equals(Instant.parse(d.path("endedAt").textValue()))
                || job.get("result_data") != null
                || job.get("result_hash") != null
                || !Boolean.FALSE.equals(job.get("has_result_cipher"))
                || job.get("worker_key") != null
                || job.get("lease_until") != null) throw storage();
        for (int i = 0; i < attempts.size(); i++) {
            var attempt = attempts.get(i);
            if (number(attempt, "attempt_no") != i + 1 || attempt.get("ended_at") == null)
                throw storage();
            boolean effect =
                    !d.path("attemptNo").isNull()
                            && number(attempt, "attempt_no") == d.path("attemptNo").longValue();
            if (effect) {
                String after = "DEADLINE_EXCEEDED".equals(reason) ? "EXPIRED" : "FAILED";
                if (i + 1 != attempts.size()
                        || !"RUNNING".equals(d.path("beforeJob").textValue())
                        || !"RUNNING".equals(d.path("beforeAttempt").textValue())
                        || !after.equals(d.path("afterAttempt").textValue())
                        || !after.equals(attempt.get("state"))
                        || !reason.equals(attempt.get("error_code"))
                        || number(attempt, "lease_gen") != number(job, "lease_gen")
                        || !instant(attempt.get("ended_at")).equals(instant(job.get("updated_at")))
                        || attempt.get("completion_data") != null
                        || attempt.get("output_hash") != null
                        || !Boolean.FALSE.equals(attempt.get("has_output_cipher"))) throw storage();
            } else {
                if (!"FAILED".equals(attempt.get("state"))) throw storage();
                if (attempt.get("completion_data") == null) requireRecoveryAttempt(attempt, events);
                else requireAttemptReceipt(job, attempt, events);
            }
        }
        if (d.path("attemptNo").isNull()) {
            if ("RUNNING".equals(d.path("beforeJob").textValue())
                    || !d.path("beforeAttempt").isNull()
                    || !d.path("afterAttempt").isNull()) throw storage();
        } else if (d.path("attemptNo").longValue() != attempts.size()) throw storage();
        return true;
    }

    /**
     * 완료 봉투·실제 시도·최소 감사 결속 없이 terminal 상태만으로 비교하지 않는다.
     *
     * @param job 실제 terminal 작업
     * @param attempt 실제 마지막 시도
     * @param events 실제 작업 감사 전체
     * @param outcome COMPLETE 또는 SYSTEM_ERROR
     * @throws DataAccessResourceFailureException 최초 영수증·감사·상태 불일치
     */
    private static void requireCompletion(
            Map<String, Object> job,
            Map<String, Object> attempt,
            List<Map<String, Object>> events,
            String outcome) {
        JsonNode envelope = parse(attempt.get("completion_data"));
        exact(envelope, "receipt", "auditEventId", "commandHash");
        JsonNode receipt = envelope.get("receipt");
        exact(
                receipt,
                "jobKey",
                "attemptNo",
                "accepted",
                "state",
                "retryScheduled",
                "outcome",
                "reason",
                "requestId");
        long eventId = envelope.path("auditEventId").longValue();
        var event =
                events.stream()
                        .filter(e -> number(e, "id") == eventId)
                        .findFirst()
                        .orElseThrow(GradeBatchComparison::storage);
        if (!"WORKER".equals(event.get("actor_kind"))
                || !"COMPLETE_APPLIED".equals(event.get("event_kind"))
                || !Objects.equals(event.get("attempt_no"), attempt.get("attempt_no"))
                || !Objects.equals(event.get("actor_key"), attempt.get("worker_key"))
                || !Objects.equals(
                        event.get("command_hash"), envelope.path("commandHash").textValue())
                || !Objects.equals(
                        event.get("request_id").toString(), receipt.path("requestId").textValue())
                || !job.get("job_key").toString().equals(receipt.path("jobKey").textValue())
                || number(attempt, "attempt_no") != receipt.path("attemptNo").longValue()
                || number(job, "call_count") != number(attempt, "attempt_no")
                || !receipt.path("accepted").isBoolean()
                || !receipt.path("accepted").booleanValue()
                || !falseBoolean(receipt.get("retryScheduled"))
                || !outcome.equals(receipt.path("outcome").asText())
                || !job.get("state").equals(receipt.path("state").textValue())
                || !Objects.equals(
                        attempt.get("error_code") == null ? "NONE" : attempt.get("error_code"),
                        receipt.path("reason").textValue())
                || attempt.get("ended_at") == null) throw storage();
        requireEventDetail(job, attempt, event, outcome);
    }

    /**
     * 과거 실패 시도도 실제 적용 감사·최초 재시도 영수증 결속을 보존해야 한다.
     *
     * @param job 실제 작업
     * @param attempt 실제 실패 시도
     * @param events 실제 감사 전체
     * @throws DataAccessResourceFailureException 영수증·감사·시도 불일치
     */
    private static void requireAttemptReceipt(
            Map<String, Object> job,
            Map<String, Object> attempt,
            List<Map<String, Object>> events) {
        JsonNode envelope = parse(attempt.get("completion_data"));
        exact(envelope, "receipt", "auditEventId", "commandHash");
        JsonNode receipt = envelope.get("receipt");
        exact(
                receipt,
                "jobKey",
                "attemptNo",
                "accepted",
                "state",
                "retryScheduled",
                "outcome",
                "reason",
                "requestId");
        var event =
                events.stream()
                        .filter(e -> number(e, "id") == envelope.path("auditEventId").longValue())
                        .findFirst()
                        .orElseThrow(GradeBatchComparison::storage);
        boolean terminal = number(attempt, "attempt_no") == 3;
        if (!"WORKER".equals(event.get("actor_kind"))
                || !"COMPLETE_APPLIED".equals(event.get("event_kind"))
                || !Objects.equals(event.get("attempt_no"), attempt.get("attempt_no"))
                || !Objects.equals(event.get("actor_key"), attempt.get("worker_key"))
                || !Objects.equals(
                        event.get("command_hash"), envelope.path("commandHash").textValue())
                || event.get("request_id") == null
                || !event.get("request_id").toString().equals(receipt.path("requestId").textValue())
                || !job.get("job_key").toString().equals(receipt.path("jobKey").textValue())
                || receipt.path("attemptNo").longValue() != number(attempt, "attempt_no")
                || !receipt.path("accepted").isBoolean()
                || !receipt.path("accepted").booleanValue()
                || !receipt.path("retryScheduled").isBoolean()
                || receipt.path("retryScheduled").booleanValue() == terminal
                || !(terminal
                        ? "SYSTEM_ERROR".equals(receipt.path("outcome").asText())
                        : receipt.path("outcome").isNull())
                || !(terminal ? "FAILED" : "QUEUED").equals(receipt.path("state").asText())
                || !Objects.equals(attempt.get("error_code"), receipt.path("reason").textValue()))
            throw storage();
        JsonNode detail = parse(event.get("detail"));
        if (!detail.path("reason").asText().equals(attempt.get("error_code"))
                || detail.path("leaseGen").longValue() != number(attempt, "lease_gen")
                || !"FAILED".equals(detail.path("afterAttempt").asText())
                || !(terminal ? "FAILED" : "QUEUED").equals(detail.path("afterJob").asText()))
            throw storage();
    }

    /**
     * 실제 최소 감사의 해시·종료 상태·세대가 작업·시도와 같아야 한다.
     *
     * @param job 실제 작업
     * @param attempt 실제 완료 시도
     * @param event 실제 WORKER 완료 감사
     * @param outcome COMPLETE 또는 SYSTEM_ERROR
     * @throws DataAccessResourceFailureException 상태·세대·해시 불일치
     */
    private static void requireEventDetail(
            Map<String, Object> job,
            Map<String, Object> attempt,
            Map<String, Object> event,
            String outcome) {
        JsonNode detail = parse(event.get("detail"));
        exact(
                detail,
                "leaseGen",
                "beforeJob",
                "afterJob",
                "beforeAttempt",
                "afterAttempt",
                "reason",
                "outputHash",
                "resultHash");
        if (detail.path("leaseGen").longValue() != number(attempt, "lease_gen")
                || !"RUNNING".equals(detail.path("beforeJob").asText())
                || !"RUNNING".equals(detail.path("beforeAttempt").asText())
                || !job.get("state").equals(detail.path("afterJob").asText())
                || !attempt.get("state").equals(detail.path("afterAttempt").asText())
                || !Objects.equals(
                        attempt.get("output_hash"),
                        detail.path("outputHash").isNull()
                                ? null
                                : detail.path("outputHash").textValue())
                || !Objects.equals(
                        job.get("result_hash"),
                        detail.path("resultHash").isNull()
                                ? null
                                : detail.path("resultHash").textValue())
                || !("COMPLETE".equals(outcome) ? "NONE" : job.get("error_code"))
                        .equals(detail.path("reason").textValue())) throw storage();
    }

    /**
     * 복구 감사는 별도 신뢰 증거이며 완료 영수증을 만들거나 기대 ENGINE_ERROR로 둔갑시키지 않는다.
     *
     * @param job 실제 실패 작업
     * @param attempt 실제 마지막 복구 시도
     * @param events 실제 감사 전체
     * @throws DataAccessResourceFailureException 복구 상태·마감·원인 불일치
     */
    private static void requireRecovery(
            Map<String, Object> job,
            Map<String, Object> attempt,
            List<Map<String, Object>> events) {
        var event = requireRecoveryAttempt(attempt, events);
        JsonNode detail = parse(event.get("detail"));
        String reason = detail.path("reason").asText();
        if (!"FAILED".equals(detail.path("afterJob").asText())
                || !Objects.equals(job.get("error_code"), reason)
                || number(job, "call_count") != number(attempt, "attempt_no")
                || !(Set.of("WORKER_LOST", "DEADLINE_EXCEEDED", "RUNTIME_EPOCH_CHANGED")
                        .contains(reason))) throw storage();
        if ("WORKER_LOST".equals(reason) && number(job, "call_count") != 3) throw storage();
        if ("DEADLINE_EXCEEDED".equals(reason)
                && instant(job.get("updated_at")).isBefore(instant(job.get("deadline_at"))))
            throw storage();
    }

    /**
     * 불확실한 외부 호출의 복구는 실제 WORKER_LOST 시도·내구 SYSTEM 감사로만 인정한다.
     *
     * @param attempt 실제 실패 복구 시도
     * @param events 실제 감사 전체
     * @return 실제 RECOVERY_EXPIRED 감사
     * @throws DataAccessResourceFailureException 복구 영수증·세대·상태 불일치
     */
    private static Map<String, Object> requireRecoveryAttempt(
            Map<String, Object> attempt, List<Map<String, Object>> events) {
        var event =
                events.stream()
                        .filter(
                                e ->
                                        "SYSTEM".equals(e.get("actor_kind"))
                                                && "RECOVERY_EXPIRED".equals(e.get("event_kind"))
                                                && Objects.equals(
                                                        e.get("attempt_no"),
                                                        attempt.get("attempt_no")))
                        .findFirst()
                        .orElseThrow(GradeBatchComparison::storage);
        JsonNode detail = parse(event.get("detail"));
        exact(
                detail,
                "leaseGen",
                "beforeJob",
                "afterJob",
                "beforeAttempt",
                "afterAttempt",
                "reason",
                "outputHash",
                "resultHash");
        if (attempt.get("completion_data") != null
                || !"WORKER_LOST".equals(attempt.get("error_code"))
                || !"FAILED".equals(attempt.get("state"))
                || attempt.get("ended_at") == null
                || detail.path("leaseGen").longValue() != number(attempt, "lease_gen")
                || !"RUNNING".equals(detail.path("beforeJob").asText())
                || !"RUNNING".equals(detail.path("beforeAttempt").asText())
                || !"FAILED".equals(detail.path("afterAttempt").asText())
                || !detail.path("outputHash").isNull()
                || !detail.path("resultHash").isNull()) throw storage();
        return event;
    }

    /**
     * 현재 대상 인가·필수 감사 이후 GCM/AAD와 원래 완료 해시로 내부 계산 결과를 검증하고 판정 필드만 대조한다.
     *
     * @param job 현재 거래에서 잠근 실제 작업이며 result_cipher/result_hash가 존재해야 한다
     * @param summary 원문 없는 저장 요약이며 암호화된 실제 계산값과 일치해야 한다
     * @param grading 전체 검증된 고정 채점표이며 null 불가
     * @throws DataAccessResourceFailureException 복호화·형식·해시·계산 요약 결속 실패를 원문 없는 저장 오류로 처리한다
     */
    private void requireAuthenticatedSummary(
            Map<String, Object> job,
            JsonNode summary,
            com.reasoning.common.grading.model.GradeModels.Snapshot grading) {
        Object cipher = job.get("result_cipher");
        if (!(cipher instanceof byte[] bytes)) throw storage();
        String plain;
        try {
            plain =
                    crypto.decrypt(
                            new String(bytes, StandardCharsets.UTF_8),
                            "grade_job/" + number(job, "id") + "/result/v1");
        } catch (AuthException failure) {
            throw storage();
        }
        JsonNode result = SnapshotJson.parse(plain.getBytes(StandardCharsets.UTF_8));
        exact(result, "formatNo", "baseResult");
        if (!result.path("formatNo").isIntegralNumber()
                || !result.path("formatNo").canConvertToInt()
                || result.path("formatNo").intValue() != 1
                || !SnapshotJson.hash(result).equals(job.get("result_hash"))) throw storage();
        JsonNode base = result.get("baseResult");
        exact(base, "status", "items", "baseScore", "success");
        if (!"COMPLETE".equals(base.path("status").textValue())
                || !base.path("items").isArray()
                || !same(base.get("baseScore"), summary.get("baseScore"))
                || !same(base.get("success"), summary.get("success"))) throw storage();
        ObjectNode projection = object();
        projection.set("baseScore", base.get("baseScore"));
        projection.set("success", base.get("success"));
        var items = projection.putArray("items");
        for (JsonNode item : base.get("items")) {
            exact(item, "rubricCode", "score", "requiredMet", "claims", "contradictions", "reason");
            ObjectNode safe = items.addObject();
            safe.set("rubricCode", item.get("rubricCode"));
            safe.set("score", item.get("score"));
            safe.set("requiredMet", item.get("requiredMet"));
        }
        requireSummary(projection, grading);
        if (!same(items, summary.get("items"))) throw storage();
    }

    /**
     * 전체 rubric의 판정 필드만 비교하며 기대 설명 reason은 안전 결과 요약에 요구하지 않는다.
     *
     * @param actual 인증된 안전 항목 배열
     * @param expected 전체 검증된 기대 항목 배열
     * @return 판정 필드 전체 일치 여부
     * @throws DataAccessResourceFailureException 실제 수치·필드 형식 불일치
     */
    private static boolean sameItems(JsonNode actual, JsonNode expected) {
        if (expected == null || !expected.isArray() || actual.size() != expected.size())
            return false;
        Set<String> codes = new HashSet<>();
        boolean matched = true;
        for (JsonNode item : actual) {
            exact(item, "rubricCode", "score", "requiredMet");
            String code = item.path("rubricCode").textValue();
            if (code == null
                    || !codes.add(code)
                    || !item.path("score").isIntegralNumber()
                    || !(item.path("requiredMet").isBoolean() || item.path("requiredMet").isNull()))
                throw storage();
            boolean found = false;
            for (JsonNode value : expected)
                if (code.equals(value.path("rubricCode").textValue()))
                    found =
                            same(item.get("score"), value.get("score"))
                                    && same(item.get("requiredMet"), value.get("requiredMet"));
            matched &= found;
        }
        return matched;
    }

    /**
     * 정상 요약은 고정 채점표 전체의 등록 단계·필수 충족·합계·성공과 자체 일관성을 유지해야 한다.
     *
     * @param summary 안전 판정 요약
     * @param grading 전체 검증된 고정 채점표
     * @throws DataAccessResourceFailureException 항목·등록 단계·합계·필수 충족 불일치
     */
    private static void requireSummary(
            JsonNode summary, com.reasoning.common.grading.model.GradeModels.Snapshot grading) {
        JsonNode items = summary.get("items");
        if (items.size() != grading.rubrics().size()) throw storage();
        Set<String> codes = new HashSet<>();
        int sum = 0;
        boolean success = true;
        for (JsonNode item : items) {
            exact(item, "rubricCode", "score", "requiredMet");
            String code = item.path("rubricCode").textValue();
            if (code == null
                    || !codes.add(code)
                    || !item.path("score").isIntegralNumber()
                    || !item.path("score").canConvertToInt()) throw storage();
            var rubric =
                    grading.rubrics().stream()
                            .filter(r -> r.code().equals(code))
                            .findFirst()
                            .orElseThrow(GradeBatchComparison::storage);
            int score = item.path("score").intValue();
            if (score < 0
                    || score > rubric.maxScore()
                    || rubric.levels().stream().noneMatch(level -> level.score() == score))
                throw storage();
            if (rubric.required()) {
                boolean met = score >= rubric.passScore();
                if (!item.path("requiredMet").isBoolean()
                        || item.path("requiredMet").booleanValue() != met) throw storage();
                success &= met;
            } else if (!item.path("requiredMet").isNull()) throw storage();
            sum += score;
        }
        if (sum != summary.path("baseScore").intValue()
                || success != summary.path("success").booleanValue()) throw storage();
    }

    /** 저장 JSON은 공유 canonical parser로만 읽는다. */
    private static JsonNode parse(Object value) {
        if (value == null) throw storage();
        return SnapshotJson.parse(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 키 집합이 정확히 같은 객체만 인정한다. */
    private static void exact(JsonNode value, String... fields) {
        if (value == null || !value.isObject() || value.size() != fields.length) throw storage();
        for (String field : fields) if (!value.has(field)) throw storage();
    }

    /** 숫자형 DB 식별자는 문자열로 추측하지 않는다. */
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** JDBC timestamptz를 UTC 순간으로 투영하고 null 완료 시각은 보존한다. */
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime time) return time.toInstant();
        throw storage();
    }

    /** 의미 JSON 표준 바이트로 비교한다. */
    private static boolean same(JsonNode left, JsonNode right) {
        return left != null
                && right != null
                && java.util.Arrays.equals(SnapshotJson.encode(left), SnapshotJson.encode(right));
    }

    /**
     * 실제 JSON boolean false만 인정한다.
     *
     * @param value 검사할 JSON 값, null이면 false
     * @return 정확한 false boolean 여부
     */
    private static boolean falseBoolean(JsonNode value) {
        return value != null && value.isBoolean() && !value.booleanValue();
    }

    /**
     * 안전 투영용 새 객체를 소유한다.
     *
     * @return 비어 있는 새 JSON 객체
     */
    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** 원인·SQL·원문 없는 고정 저장 오류다. */
    private static DataAccessResourceFailureException storage() {
        return new DataAccessResourceFailureException("BATCH_COMPARISON_STORAGE_FAILURE");
    }
}
