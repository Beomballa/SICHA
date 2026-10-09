package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.engine.LocalSemanticEngine.EngineException;
import com.reasoning.common.grading.model.GradeModels.Status;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;
import com.reasoning.common.grading.service.GradeCompletionService.CompletionReceipt;
import com.reasoning.common.grading.service.GradeStartService.Reservation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BATCH Reservation 전용 내부 단일 호출 조립이다. TEST 보고서 실행은 지원하지 않는다. 자동 활성화·폴링·재시도는 없다. SQL fence 커밋 뒤 회수
 * 경쟁과 외부 실행의 불확실성은 남으며 heartbeat 중단은 전송 취소의 최선 노력이지 외부 exactly-once 증명이 아니다.
 */
public final class GradeLocalRunner {
    private final GradeWorkerCredentials credentials;
    private final GradeStartService start;
    private final GradeCompletionService completion;
    private final GradeLeaseRepository leases;

    /**
     * 같은 실제 레지스트리·DB의 경계만 조립한다. 임대 저장소는 START가 같은 TX 관리자로 제공한다.
     *
     * @param credentials 실제 인증 레지스트리, null 불가
     * @param start 실제 예약 소유자, null 불가
     * @param completion 동일 레지스트리·DataSource 완료 서비스, null 불가
     * @throws IllegalArgumentException 조립 식별자가 다르면 예약 전 거절
     */
    public GradeLocalRunner(
            GradeWorkerCredentials credentials,
            GradeStartService start,
            GradeCompletionService completion) {
        this.credentials = Objects.requireNonNull(credentials);
        this.start = Objects.requireNonNull(start);
        this.completion = Objects.requireNonNull(completion);
        start.requireAssembly(credentials, completion);
        leases = start.leases();
    }

    /** 원문·해시·예외 없이 노출하는 호출 상태다. RECOVERY는 새 전송 허가가 아니다. */
    public enum State {
        REPLAY,
        RECOVERY,
        RECEIPT
    }

    /** 실제 완료 영수증만 그대로 반환하며 비공개 모델 출력은 포함하지 않는다. */
    public record Outcome(State state, CompletionReceipt receipt) {}

    /**
     * 한 신규 예약에서 최대 한 chat만 시작한다. 재전송 예약은 HTTP·갱신·완료 없이 반환한다.
     *
     * @param worker 실제 레지스트리가 인증한 증명, null 불가
     * @param jobKey 실제 비영 BATCH 작업 UUID, null 불가
     * @param leaseGen 양수 실제 임대 세대
     * @return 안전한 재전송·복구 상태 또는 실제 완료 영수증
     * @throws RuntimeException 예약 이전 인증·입력·저장소 실패; 원문은 서비스 경계가 제거한다
     */
    public Outcome runOnce(VerifiedWorker worker, UUID jobKey, long leaseGen) {
        start.requireRunnerPermissions(worker, jobKey);
        Reservation reservation = start.start(worker, jobKey, leaseGen);
        if (reservation.replay()) return new Outcome(State.REPLAY, null);
        var fence = start.beforeChat(worker, reservation);
        var heartbeat = new Heartbeat(worker, reservation, Thread.currentThread());
        LocalSemanticEngine.Result result = null;
        String error = null;
        heartbeat.thread.start();
        try {
            result =
                    reservation
                            .runtime()
                            .evaluate(
                                    reservation.dataset(),
                                    reservation.selection(),
                                    reservation.deadline(),
                                    () -> {
                                        heartbeat.requireLive();
                                        fence.check();
                                        heartbeat.requireLive();
                                    });
        } catch (EngineException failure) {
            if (failure.phase() == LocalSemanticEngine.FailurePhase.MAY_HAVE_SUBMITTED) {
                error =
                        switch (failure.getMessage()) {
                            case "LOCAL_DEADLINE_EXCEEDED" -> "ENGINE_TIMEOUT";
                            case "LOCAL_HTTP_FAILURE", "LOCAL_UNAVAILABLE_OR_INVALID_RESPONSE" ->
                                    "ENGINE_UNAVAILABLE";
                            case "INVALID_LOCAL_OUTPUT",
                                    "INVALID_LOCAL_RESPONSE",
                                    "LOCAL_OUTPUT_LIMIT" ->
                                    "INVALID_OUTPUT";
                            default -> null;
                        };
            }
        } catch (RuntimeException failure) {
            // 실제 callback·SQL·권한 실패는 provider 오류로 변환하지 않는다.
        } finally {
            heartbeat.stopAndJoin();
        }
        if (heartbeat.failed.get()
                || Thread.currentThread().isInterrupted()
                || result == null && error == null) return new Outcome(State.RECOVERY, null);
        var envelope = JsonNodeFactory.instance.objectNode();
        String observed = null;
        if (result != null) {
            observed = result.metadata().postDigest();
            if (result.semanticResult().status() == Status.COMPLETE) {
                envelope.put("kind", "COMPLETE");
                envelope.set(
                        "semantic",
                        SnapshotJson.parse(result.semanticJson().getBytes(StandardCharsets.UTF_8)));
            } else {
                envelope.put("kind", "UNRESOLVED").put("errorCode", "UNRESOLVED_REASONING");
            }
        } else envelope.put("kind", "ERROR").put("errorCode", error);
        try {
            CompletionReceipt receipt =
                    completion.complete(
                            worker,
                            reservation.jobKey(),
                            reservation.leaseGen(),
                            reservation.attemptNo(),
                            observed,
                            null,
                            new String(SnapshotJson.encode(envelope), StandardCharsets.UTF_8),
                            UUID.randomUUID());
            return new Outcome(State.RECEIPT, receipt);
        } catch (RuntimeException failure) {
            return new Outcome(State.RECOVERY, null);
        }
    }

    /** 호출 소유 thread만 5초마다 인증 갱신한다. DB 정체 시 성공 주기를 보장하지 않으며 실패는 폐쇄한다. */
    private final class Heartbeat {
        private final VerifiedWorker worker;
        private final Reservation reservation;
        private final Thread caller;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final Thread thread;

        private Heartbeat(VerifiedWorker worker, Reservation reservation, Thread caller) {
            this.worker = worker;
            this.reservation = reservation;
            this.caller = caller;
            thread =
                    Thread.ofPlatform()
                            .daemon(true)
                            .name("grade-local-heartbeat")
                            .unstarted(this::loop);
        }

        private void loop() {
            try {
                while (!stopped.get()) {
                    Thread.sleep(5000);
                    if (stopped.get()) return;
                    credentials.requirePermission(
                            worker, Action.RENEW, reservation.runtime().profile().configId());
                    var lease =
                            leases.renew(
                                            reservation.jobKey(),
                                            worker.workerKey(),
                                            reservation.leaseGen())
                                    .orElseThrow();
                    if (lease.jobId() != reservation.jobId()
                            || lease.snapshotId() != reservation.snapshotId()
                            || lease.batchId() != reservation.batchId()
                            || lease.runtimeId() != reservation.runtimeId()
                            || !lease.jobKey().equals(reservation.jobKey())
                            || !lease.workerKey().equals(reservation.workerKey())
                            || lease.leaseGen() != reservation.leaseGen()
                            || !lease.runtimeCode()
                                    .equals(reservation.runtime().profile().configId())
                            || !lease.deadlineAt().equals(reservation.deadlineAt())
                            || lease.leaseUntil().isAfter(reservation.deadlineAt()))
                        throw new IllegalStateException();
                }
            } catch (InterruptedException failure) {
                if (!stopped.get()) cancel();
            } catch (RuntimeException failure) {
                cancel();
            }
        }

        private void cancel() {
            failed.set(true);
            caller.interrupt();
        }

        private void requireLive() {
            if (failed.get() || caller.isInterrupted())
                throw new IllegalStateException("LOCAL_RUN_CANCELLED");
        }

        /** 완료 전에 소유 갱신을 반드시 종료하고 호출자 interrupt를 보존한다. */
        private void stopAndJoin() {
            stopped.set(true);
            thread.interrupt();
            boolean interrupted = Thread.interrupted();
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
