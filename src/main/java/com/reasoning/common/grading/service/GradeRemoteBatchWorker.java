package com.reasoning.common.grading.service;

import com.reasoning.common.grading.config.GradeRemoteOnceJournal;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal.Scope;
import com.reasoning.common.grading.config.WorkerGradeProfileLoader.Profiles;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.RuntimeConfiguration;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.engine.LocalSemanticEngine.JobDeadline;
import com.reasoning.common.grading.engine.LocalSemanticEngine.OwnershipException;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.GradeModels.Status;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.GradeCompletionService.CompletionReceipt;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 실제 original NEW·보호 설치·단일 provider core를 소유한다. durable 의도 뒤 단회 chat/완료만 허용하며 유실·재시작은 journal에서 실행을
 * 복원하지 않는다. Spring·HTTP/TLS·스케줄러·배포를 활성화하지 않는다.
 */
public final class GradeRemoteBatchWorker implements AutoCloseable {
    /**
     * 신뢰된 조립이 실제 서버·자격에 결속한 transport다. 즉시 반환한 future의 완료는 전체 body이며 취소는 실제 요청에 전파해야 한다. 숨은
     * 재시도·redirect·신원 변경은 금지된다. 실제 TLS adapter는 별도 경계다.
     */
    public interface BoundTransport {
        Scope scope();

        /**
         * credential-bound START를 한 번 전송한다.
         *
         * @param jobKey null·영 UUID 불가인 실제 작업
         * @param leaseGen 양수 실제 세대
         * @param maximumWait 최소1ms 전체 응답 상한, null 불가
         * @return 취소 전파 가능한 전체 body future, null 불가
         */
        CompletableFuture<byte[]> start(UUID jobKey, long leaseGen, Duration maximumWait);

        /**
         * 실제 stateless fence를 한 번 전송한다. grant가 아니다.
         *
         * @param jobKey 원래 작업, null 불가
         * @param original 원래 tuple/hash, null 불가
         * @param maximumWait 현재 J/P/L 이내 전체 응답 상한, null 불가
         * @return 전체 body future, null 불가
         */
        CompletableFuture<byte[]> fence(UUID jobKey, AttemptRequest original, Duration maximumWait);

        /**
         * 실제 renewal을 한 번 전송한다. 불확실 응답은 재시도하지 않는다.
         *
         * @param jobKey 원래 작업, null 불가
         * @param original 원래 tuple/hash, null 불가
         * @param maximumWait OLD 확인 lease 이내 전체 응답 상한, null 불가
         * @return 전체 body future, null 불가
         */
        CompletableFuture<byte[]> renew(UUID jobKey, AttemptRequest original, Duration maximumWait);

        /**
         * 원래 시도의 완료를 한 번만 전송한다. server requestId는 실제 서버가 소유한다.
         *
         * @param jobKey 원래 작업, null 불가
         * @param command 원래 tuple와 genuine 결과의 내부 명령, null 불가
         * @param maximumWait 원래 소유 예산 이내 전체 응답 상한, null 불가
         * @return 실제 전체 receipt body future, null 불가
         */
        CompletableFuture<byte[]> complete(
                UUID jobKey, CompletionCommand command, Duration maximumWait);
    }

    /** 호출자 신원·requestId를 포함하지 않는 내부 명령이며 원문은 로그에 출력하지 않는다. */
    public static final class CompletionCommand {
        private final long leaseGen;
        private final int attemptNo;
        private final String observedProviderVersion;
        private final String resultJson;

        private CompletionCommand(
                long leaseGen, int attemptNo, String observedProviderVersion, String resultJson) {
            this.leaseGen = leaseGen;
            this.attemptNo = attemptNo;
            this.observedProviderVersion = observedProviderVersion;
            this.resultJson = resultJson;
        }

        public long leaseGen() {
            return leaseGen;
        }

        public int attemptNo() {
            return attemptNo;
        }

        public String observedProviderVersion() {
            return observedProviderVersion;
        }

        public String providerResponseRef() {
            return null;
        }

        public String resultJson() {
            return resultJson;
        }

        @Override
        public String toString() {
            return "CompletionCommand[redacted]";
        }
    }

    /** 실제 adapter 조립의 명시적 유한 상한이며 profile/journal에서 유추하지 않는다. */
    public record TransportBounds(int maximumReplyBytes, Duration maximumWait) {
        /**
         * @param maximumReplyBytes 양수 전체 응답 byte 상한
         * @param maximumWait null 불가이며 checked 변환 가능한 최소1ms 상한
         * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_OWNER
         */
        public TransportBounds {
            try {
                if (maximumReplyBytes <= 0
                        || maximumWait == null
                        || maximumWait.toNanos() < 1_000_000) throw failure();
            } catch (RuntimeException exception) {
                throw failure();
            }
        }
    }

    public enum State {
        REPLAY,
        RECOVERY,
        RECEIPT
    }

    /** 실제 영수증만 반환하며 accepted=false를 성공으로 바꾸지 않는다. */
    public record Outcome(State state, CompletionReceipt receipt) {}

    private final GradeRemoteOnceJournal journal;
    private final BoundTransport transport;
    private final Scope scope;
    private final TransportBounds bounds;
    private final Profiles profiles;
    private final Object observations = new Object();
    private volatile Attempt attempt;
    private volatile CompletableFuture<byte[]> pending;
    private volatile boolean failed;
    private volatile boolean closed;
    private boolean reserving;
    private boolean running;
    private boolean replay;
    private volatile Renewal renewal;
    private int admitted;
    private boolean closeFinished;
    private IOException closeFailure;

    /** 디스크가 아닌 original NEW invocation에서만 생성하는 사적 수명 상태다. */
    private static final class Attempt {
        private final GradeRemoteReplyDecoder.Start start;
        private final long requestStartedNano;
        private volatile long jobExpiry;
        private volatile long leaseExpiry;
        private volatile JobDeadline budget;
        private boolean chatIntent;
        private boolean completionIntent;

        private Attempt(GradeRemoteReplyDecoder.Start start, long requestStartedNano) {
            this.start = start;
            this.requestStartedNano = requestStartedNano;
            jobExpiry = expiry(requestStartedNano, start.limits().remainingBudgetMillis());
            leaseExpiry = expiry(requestStartedNano, start.limits().remainingLeaseMillis());
        }
    }

    /**
     * 같은 실제 transport/journal과 보호 설치를 의무적으로 조립한다. 공개 evaluator/hook 주입은 없다.
     *
     * @param journal null 불가이며 성공 뒤 owner가 close 책임을 가짐
     * @param transport null 불가인 credential-bound 전체 body transport
     * @param bounds null 불가인 명시적 유한 상한
     * @param profiles null 불가인 전체 성공한 실제 보호 설치
     * @throws IllegalArgumentException null·좌표·잠금 불일치의 원인 없는 INVALID_REMOTE_OWNER
     */
    public GradeRemoteBatchWorker(
            GradeRemoteOnceJournal journal,
            BoundTransport transport,
            TransportBounds bounds,
            Profiles profiles) {
        try {
            if (journal == null
                    || transport == null
                    || bounds == null
                    || profiles == null
                    || !journal.scope().equals(transport.scope())) throw failure();
            journal.requireHeld();
        } catch (IOException | RuntimeException exception) {
            throw failure();
        }
        this.journal = journal;
        this.transport = transport;
        this.scope = journal.scope();
        this.bounds = bounds;
        this.profiles = profiles;
    }

    /**
     * IN_FLIGHT force 후 원래 START를 한 번만 요청한다. replay/loss/parse/force 실패는 소유권을 파기한다.
     *
     * @param jobKey null·영 UUID 불가인 실제 서버 작업
     * @param leaseGen 양수 실제 세대이며 로컬에서 만들지 않음
     * @throws IllegalStateException 고정 REMOTE_RESERVATION_REFUSED; 환급·재전송 없음
     */
    public void beginReservation(UUID jobKey, long leaseGen) {
        reserve(jobKey, leaseGen, false);
    }

    /**
     * 공개 factual 예약과 실제 run의 admission을 같은 배타 수명 검사에 연결하고 finally 정리까지 lock 수명을 유지한다. 이미 admitted된
     * 관측도 예약의 attempt 게시와 pending 정리가 모두 끝난 뒤에만 관측 monitor에 진입한다.
     *
     * @param jobKey null·영 UUID 불가인 실제 작업
     * @param leaseGen 양수 실제 세대
     * @param fromRun 실제 private run 경로만 true이며 외부 실행 권위가 아님
     * @throws IllegalStateException 모든 불확실 결과의 고정 소유 거절
     */
    private void reserve(UUID jobKey, long leaseGen, boolean fromRun) {
        synchronized (this) {
            requireAvailable();
            if (running && !fromRun
                    || attempt != null
                    || reserving
                    || jobKey == null
                    || jobKey.equals(new UUID(0, 0))
                    || leaseGen <= 0) throw refused();
            reserving = true;
            admitted++;
        }
        synchronized (observations) {
            boolean reserved = false;
            try {
                journal.inFlight(jobKey, leaseGen);
                reserved = true;
                requireScope();
                long started = System.nanoTime();
                dispatch(() -> transport.start(jobKey, leaseGen, bounds.maximumWait()));
                byte[] body = receive(started, bounds.maximumWait());
                var parsed = GradeRemoteReplyDecoder.decodeStart(body);
                requireWait(started, bounds.maximumWait());
                requireAvailable();
                if (!parsed.identity().jobKey().equals(jobKey)
                        || parsed.identity().leaseGen() != leaseGen) throw refused();
                if (!parsed.disposition().equals("NEW")) {
                    replay = true;
                    throw refused();
                }
                Attempt original = new Attempt(parsed, started);
                requireLive(original);
                journal.owned(
                        jobKey,
                        leaseGen,
                        parsed.identity().attemptNo(),
                        parsed.input().originalAttemptHash());
                requireLive(original);
                requireWait(started, bounds.maximumWait());
                synchronized (this) {
                    requireAvailable();
                    attempt = original;
                }
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                invalidate(jobKey, leaseGen, reserved, refused());
                throw refused();
            } finally {
                pending = null;
                synchronized (this) {
                    reserving = false;
                    releaseOperation();
                }
            }
        }
    }

    /**
     * 한 original NEW를 실제 MODEL 또는 승인 ENGINE_ERROR로 소비하고 완료를 최대 한 번 요청한다. budget·renewal 게시를 close와
     * 직렬화하며 provider/완료 및 finally 정리 전에는 admission을 반환하지 않는다.
     *
     * @param jobKey null·영 UUID 불가인 실제 작업
     * @param leaseGen 양수 실제 서버 세대
     * @return 실제 receipt 또는 replay/recovery; retryScheduled는 자동 반복하지 않음
     * @throws IllegalStateException 같은 owner의 동시 run 또는 사전 수명 거절
     */
    public Outcome runOnce(UUID jobKey, long leaseGen) {
        synchronized (this) {
            requireAvailable();
            if (running || attempt != null || reserving) throw refused();
            running = true;
            admitted++;
        }
        Attempt original = null;
        try {
            reserve(jobKey, leaseGen, true);
            original = attempt;
            var input = original.start.input();
            var runtime = configuration(input.runtime());
            // 실제 private P를 canonical MODEL decode보다 먼저 한 번 고정한다.
            synchronized (this) {
                requireAvailable();
                original.budget =
                        profiles.openDeadline(
                                runtime,
                                original.requestStartedNano,
                                original.start.limits().remainingBudgetMillis(),
                                original.start.limits().remainingLeaseMillis());
            }
            requireAvailable();
            original.budget.requireLive();
            LocalSemanticEngine.Result result = null;
            String error = null;
            boolean model = "MODEL".equals(input.variant());
            if (model) {
                var semantic = FrozenModelProjection.decodeCanonical(input.modelInput().bytes());
                original.budget.requireLive();
                var qualified = profiles.bind(runtime, semantic);
                original.budget.requireLive();
                requireAvailable();
                Renewal heartbeat = new Renewal(original);
                synchronized (this) {
                    requireAvailable();
                    renewal = heartbeat;
                    heartbeat.thread.start();
                }
                Attempt owned = original;
                try {
                    result = qualified.evaluate(original.budget, () -> owningBeforeChat(owned));
                } catch (LocalSemanticEngine.EngineException exception) {
                    if (exception.phase() == LocalSemanticEngine.FailurePhase.MAY_HAVE_SUBMITTED)
                        error = classify(exception);
                } finally {
                    heartbeat.stopAndJoin();
                    renewal = null;
                }
                if (result == null && error == null) throw refused();
            } else if ("ENGINE_ERROR".equals(input.variant())
                    && input.fault() != null
                    && input.fault().failRuns() == 3) {
                observe(false);
                original.budget.requireLive();
                error =
                        switch (input.fault().type()) {
                            case "TIMEOUT" -> "ENGINE_TIMEOUT";
                            case "UNAVAILABLE" -> "ENGINE_UNAVAILABLE";
                            default -> throw refused();
                        };
            } else throw refused();
            requireAvailable();
            if (Thread.currentThread().isInterrupted()) throw refused();
            if (result != null || !model) original.budget.requireLive();
            original.budget.ownershipWait();
            original.budget.stopAlarm();
            var envelope =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            String observed = null;
            if (result != null) {
                observed = result.metadata().postDigest();
                if (result.semanticResult().status() == Status.COMPLETE) {
                    envelope.put("kind", "COMPLETE");
                    envelope.set(
                            "semantic",
                            SnapshotJson.parse(
                                    result.semanticJson().getBytes(StandardCharsets.UTF_8)));
                } else envelope.put("kind", "UNRESOLVED").put("errorCode", "UNRESOLVED_REASONING");
            } else envelope.put("kind", "ERROR").put("errorCode", error);
            var identity = original.start.identity();
            synchronized (this) {
                requireAvailable();
                if (attempt != original || original.completionIntent) throw refused();
                journal.completionIntent(identity.jobKey(), identity.leaseGen(), model);
                original.completionIntent = true;
            }
            original.budget.ownershipWait();
            if (result != null || !model) original.budget.requireLive();
            requireScope();
            Duration wait = bounded(original.budget.ownershipWait());
            var command =
                    new CompletionCommand(
                            identity.leaseGen(),
                            identity.attemptNo(),
                            observed,
                            new String(SnapshotJson.encode(envelope), StandardCharsets.UTF_8));
            long started = System.nanoTime();
            original.budget.ownershipWait();
            dispatch(() -> transport.complete(identity.jobKey(), command, wait));
            byte[] body = receive(started, wait);
            var receipt = GradeRemoteReplyDecoder.decodeCompletion(body);
            requireWait(started, wait);
            original.budget.ownershipWait();
            requireAvailable();
            if (!identity.jobKey().equals(receipt.jobKey())
                    || identity.attemptNo() != receipt.attemptNo()) throw refused();
            journal.closed(identity.jobKey(), identity.leaseGen());
            requireAvailable();
            attempt = null;
            return new Outcome(State.RECEIPT, receipt);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            if (original != null)
                invalidate(
                        jobKey,
                        leaseGen,
                        true,
                        exception instanceof RuntimeException runtime ? runtime : refused());
            return new Outcome(replay ? State.REPLAY : State.RECOVERY, null);
        } finally {
            pending = null;
            Renewal heartbeat = renewal;
            if (heartbeat != null) {
                heartbeat.stopAndJoin();
                renewal = null;
            }
            if (original != null && original.budget != null) original.budget.stopAlarm();
            synchronized (this) {
                running = false;
                releaseOperation();
            }
        }
    }

    /** 실제 원래 epoch는 input 봉투에 남기며 아홉 설치 좌표만 qualification에 전달한다. */
    private static RuntimeConfiguration configuration(GradeRemoteReplyDecoder.Runtime10 runtime) {
        return new RuntimeConfiguration(
                runtime.code(),
                runtime.configHash(),
                runtime.engineVersion(),
                runtime.modelId(),
                runtime.modelVersion(),
                runtime.pinMode(),
                runtime.promptHash(),
                runtime.optionsHash(),
                runtime.reportContractVersion());
    }

    /** 기존 LocalRunner의 genuine MAY_HAVE_SUBMITTED 고정 분류만 재사용한다. */
    private static String classify(LocalSemanticEngine.EngineException exception) {
        return switch (exception.getMessage()) {
            case "LOCAL_DEADLINE_EXCEEDED" -> "ENGINE_TIMEOUT";
            case "LOCAL_HTTP_FAILURE", "LOCAL_UNAVAILABLE_OR_INVALID_RESPONSE" ->
                    "ENGINE_UNAVAILABLE";
            case "INVALID_LOCAL_OUTPUT", "INVALID_LOCAL_RESPONSE", "LOCAL_OUTPUT_LIMIT" ->
                    "INVALID_OUTPUT";
            default -> null;
        };
    }

    /**
     * 전체 prep/tags-show 뒤 실제 fence→force→같은 예산 재검사 순서의 private 단회 경계다.
     *
     * @param original null 불가인 현 invocation의 바로 그 원래 NEW
     * @throws RuntimeException 반복 훅·fence·journal·시간 실패는 제공자 오류로 바꾸지 않음
     */
    private void owningBeforeChat(Attempt original) {
        synchronized (observations) {
            synchronized (this) {
                requireAvailable();
                if (attempt != original || original.chatIntent || original.completionIntent)
                    throw refused();
            }
            observe(false);
            original.budget.requireLive();
            var identity = original.start.identity();
            try {
                synchronized (this) {
                    requireAvailable();
                    if (attempt != original || original.chatIntent) throw refused();
                    journal.chatIntent(identity.jobKey(), identity.leaseGen());
                    original.chatIntent = true;
                }
                original.budget.requireLive();
                requireAvailable();
            } catch (IOException exception) {
                invalidate(identity.jobKey(), identity.leaseGen(), true, refused());
                throw refused();
            }
        }
    }

    /** 실제 fence의 admission·실행 모두 active run을 거절하며 예약 정리와 직렬화한다. close는 정리까지 기다린다. */
    public void revalidateReservation() {
        synchronized (this) {
            requireAvailable();
            if (running) throw refused();
            admitted++;
        }
        try {
            synchronized (observations) {
                synchronized (this) {
                    if (running) throw refused();
                }
                observe(false);
            }
        } finally {
            releaseOperation();
        }
    }

    /** 실제 renew의 admission·실행 모두 active run을 거절하며 예약 정리와 직렬화한다. 살아 있는 old lease만 연장한다. */
    public void renewReservation() {
        synchronized (this) {
            requireAvailable();
            if (running) throw refused();
            admitted++;
        }
        try {
            synchronized (observations) {
                synchronized (this) {
                    if (running) throw refused();
                }
                observe(true);
            }
        } finally {
            releaseOperation();
        }
    }

    /**
     * observation 직렬화 중에도 engine deadline alarm은 독립 monitor로 provider를 취소한다. 공개 호출은 자체 admission,
     * private 호출은 run과 renewal join으로 정리까지 lock을 유지한다. 예약의 게시·finally 정리와도 같은 monitor로 직렬화한다.
     *
     * @param renew true는 실제 RENEW, false는 실제 stateless fence
     * @throws RuntimeException old lease·tuple·원래 마감·body·journal 거절 또는 실제 P timeout
     */
    private void observe(boolean renew) {
        synchronized (observations) {
            requireAvailable();
            Attempt original = attempt;
            if (original == null || original.completionIntent) throw refused();
            try {
                requireLive(original);
                requireScope();
                var identity = original.start.identity();
                var request =
                        new AttemptRequest(
                                identity.leaseGen(),
                                identity.attemptNo(),
                                original.start.input().originalAttemptHash());
                Duration wait = bounded(Duration.ofNanos(remaining(original)));
                long started = System.nanoTime();
                dispatch(
                        () ->
                                renew
                                        ? transport.renew(identity.jobKey(), request, wait)
                                        : transport.fence(identity.jobKey(), request, wait));
                byte[] body = receive(started, wait);
                var response =
                        renew
                                ? GradeRemoteReplyDecoder.decodeRenew(body)
                                : GradeRemoteReplyDecoder.decodeFence(body);
                requireWait(started, wait);
                requireLive(original);
                requireAvailable();
                Renewal heartbeat = renewal;
                if (renew && heartbeat != null && heartbeat.stopped) return;
                if (attempt != original
                        || !identity.equals(response.identity())
                        || !request.originalAttemptHash().equals(response.originalAttemptHash())
                        || !original.start
                                .limits()
                                .deadlineAt()
                                .equals(response.limits().deadlineAt())) throw refused();
                // stop와 적용만 같은 짧은 monitor를 공유한다. body 대기·alarm은 이 monitor를 쓰지 않는다.
                synchronized (renew && heartbeat != null ? heartbeat : observations) {
                    if (renew && heartbeat != null && heartbeat.stopped) return;
                    long job = expiry(started, response.limits().remainingBudgetMillis());
                    long lease = expiry(started, response.limits().remainingLeaseMillis());
                    if (original.budget != null) {
                        if (renew)
                            original.budget.renewFromResponse(
                                    started,
                                    response.limits().remainingBudgetMillis(),
                                    response.limits().remainingLeaseMillis());
                        else
                            original.budget.shortenFromFence(
                                    started,
                                    response.limits().remainingBudgetMillis(),
                                    response.limits().remainingLeaseMillis());
                    }
                    original.jobExpiry = Math.min(original.jobExpiry, job);
                    original.leaseExpiry = renew ? lease : Math.min(original.leaseExpiry, lease);
                    requireLive(original);
                }
            } catch (Exception exception) {
                CompletableFuture<byte[]> request = pending;
                if (request != null) request.cancel(true);
                Renewal heartbeat = renewal;
                if (renew && heartbeat != null && heartbeat.stopped) return;
                if (original.budget != null) {
                    try {
                        original.budget.requireLive();
                    } catch (LocalSemanticEngine.EngineException engine) {
                        if ("LOCAL_DEADLINE_EXCEEDED".equals(engine.getMessage())) throw engine;
                    } catch (RuntimeException ownershipFailure) {
                        // J/L 또는 실제 소유 실패는 아래 고정 소유 거절로 파기한다.
                    }
                }
                if (exception instanceof LocalSemanticEngine.EngineException engine
                        && "LOCAL_DEADLINE_EXCEEDED".equals(engine.getMessage())) throw engine;
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                var identity = original.start.identity();
                invalidate(identity.jobKey(), identity.leaseGen(), true, refused());
                throw refused();
            } finally {
                pending = null;
            }
        }
    }

    /** 실제 renewal만 소유하며 late callback을 완료 이후 적용하지 않는다. */
    private final class Renewal {
        private final Attempt original;
        private volatile boolean stopped;
        private final Thread thread;

        private Renewal(Attempt original) {
            this.original = original;
            thread =
                    Thread.ofPlatform()
                            .daemon(true)
                            .name("grade-remote-renewal")
                            .unstarted(this::loop);
        }

        /** 양수 submillisecond 대기는 스케줄링 간격일 뿐 전송 예산이 아니며 실제 요청은 J/P/L을 다시 검사한다. */
        private void loop() {
            try {
                while (true) {
                    synchronized (this) {
                        if (stopped) return;
                        long delay = Math.min(TimeUnit.SECONDS.toNanos(5), remaining(original) / 3);
                        TimeUnit.NANOSECONDS.timedWait(this, delay);
                        if (stopped) return;
                    }
                    observe(true);
                }
            } catch (RuntimeException | InterruptedException exception) {
                synchronized (this) {
                    if (stopped) return;
                }
                if (exception instanceof LocalSemanticEngine.EngineException engine
                        && "LOCAL_DEADLINE_EXCEEDED".equals(engine.getMessage())) return;
                var identity = original.start.identity();
                invalidate(identity.jobKey(), identity.leaseGen(), true, refused());
            }
        }

        /** 대기를 깨우고 실제 응답을 취소한 뒤 join한다. journal 정리 중인 스레드는 interrupt로 채널을 닫지 않는다. */
        private void stopAndJoin() {
            synchronized (this) {
                stopped = true;
                notifyAll();
            }
            if (thread != Thread.currentThread()) {
                if (thread.isAlive()) {
                    CompletableFuture<byte[]> request = pending;
                    if (request != null) request.cancel(true);
                }
                boolean interrupted = false;
                while (thread.isAlive()) {
                    try {
                        thread.join();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                    original.budget.cancel();
                    failed = true;
                }
            }
        }
    }

    /**
     * 전체 body·전송 지연·크기를 포함하며 response/header future는 완료로 취급하지 않는다.
     *
     * @param started dispatch 직전 monotonic 값
     * @param wait null 불가인 명시적 최소1ms 상한
     * @return 최대 명시 replyBytes 이내의 전체 body 복사본
     * @throws Exception timeout·취소·크기·산술 실패; 소유 경계에서 전송을 취소하고 파기함
     */
    private byte[] receive(long started, Duration wait) throws Exception {
        CompletableFuture<byte[]> request = pending;
        if (request == null) throw refused();
        long left = Math.subtractExact(wait.toNanos(), elapsed(started, System.nanoTime()));
        Attempt original = attempt;
        if (original != null && original.budget != null)
            left =
                    Math.min(
                            left,
                            (original.completionIntent
                                            ? original.budget.ownershipWait()
                                            : original.budget.liveWait())
                                    .toNanos());
        requireMillisecond(left);
        byte[] body = request.get(left, TimeUnit.NANOSECONDS);
        requireWait(started, wait);
        if (body == null || body.length == 0 || body.length > bounds.maximumReplyBytes())
            throw refused();
        return body.clone();
    }

    /** 현 invocation 입력만 반환하며 journal에서 복원하거나 public 권위를 발행하지 않는다. */
    synchronized GradeRemoteReplyDecoder.Input reservedInput() {
        requireAvailable();
        if (attempt == null) throw refused();
        requireLive(attempt);
        return attempt.start.input();
    }

    /** 현 invocation의 원래 앵커이며 직렬화하지 않는다. */
    synchronized long requestStartedNano() {
        reservedInput();
        return attempt.requestStartedNano;
    }

    private Duration bounded(Duration budget) {
        long value = Math.min(bounds.maximumWait().toNanos(), budget.toNanos());
        requireMillisecond(value);
        return Duration.ofNanos(value);
    }

    private static long expiry(long started, long millis) {
        if (millis <= 0) throw refused();
        return Math.addExact(started, Math.multiplyExact(millis, 1_000_000));
    }

    private static long elapsed(long started, long now) {
        long value = Math.subtractExact(now, started);
        if (value < 0) throw refused();
        return value;
    }

    private static long remaining(Attempt original) {
        long now = System.nanoTime();
        elapsed(original.requestStartedNano, now);
        long left = Math.subtractExact(Math.min(original.jobExpiry, original.leaseExpiry), now);
        requireMillisecond(left);
        if (original.budget != null) {
            left = Math.min(left, original.budget.liveWait().toNanos());
        }
        return left;
    }

    private static void requireLive(Attempt original) {
        remaining(original);
    }

    private static void requireWait(long started, Duration wait) {
        requireMillisecond(Math.subtractExact(wait.toNanos(), elapsed(started, System.nanoTime())));
    }

    private static void requireMillisecond(long remaining) {
        if (remaining < 1_000_000) throw refused();
    }

    private void requireAvailable() {
        if (closed || failed) throw refused();
        try {
            journal.requireHeld();
        } catch (IOException exception) {
            failed = true;
            Attempt original = attempt;
            if (original != null) {
                var identity = original.start.identity();
                invalidate(identity.jobKey(), identity.leaseGen(), false, refused());
            }
            throw refused();
        }
    }

    private void requireScope() {
        if (!scope.equals(transport.scope())) throw refused();
    }

    /**
     * close admission과 즉시 반환하는 전송·future 게시를 직렬화한다. 전체 body는 이 monitor 밖에서 기다린다.
     *
     * @param request null 불가인 단회 credential-bound 전송
     * @throws RuntimeException 종료·실패·null future는 소유 거절이며 재전송하지 않음
     */
    private synchronized void dispatch(Supplier<CompletableFuture<byte[]>> request) {
        requireAvailable();
        Attempt original = attempt;
        if (original != null) {
            if (original.completionIntent && original.budget != null)
                original.budget.ownershipWait();
            else requireLive(original);
        }
        CompletableFuture<byte[]> future = request.get();
        if (future == null) throw refused();
        pending = future;
    }

    /** 모든 admitted 호출의 정리 완료를 알리며 close의 대기는 monitor를 해제한다. */
    private synchronized void releaseOperation() {
        admitted--;
        notifyAll();
    }

    /**
     * 불확실 실행은 취소와 최소 tombstone만 남기며 오류/환급/재전송을 만들지 않는다.
     *
     * @param jobKey null 불가인 실제 작업
     * @param leaseGen 양수 실제 세대
     * @param reserved IN_FLIGHT force 성공 여부
     * @param originalFailure null 불가인 안전한 원래 소유 실패
     */
    private void invalidate(
            UUID jobKey, long leaseGen, boolean reserved, RuntimeException originalFailure) {
        failed = true;
        Attempt original = attempt;
        if (original != null && original.budget != null) original.budget.cancel(originalFailure);
        attempt = null;
        CompletableFuture<byte[]> request = pending;
        if (request != null) request.cancel(true);
        if (reserved) {
            try {
                journal.abandon(jobKey, leaseGen);
            } catch (IOException exception) {
                failed = true;
            }
        }
    }

    /**
     * admission을 닫고 게시된 전송/provider를 취소한 뒤 모든 admitted 호출과 renewal/alarm을 정리하고 lock을 닫는다. body·join
     * 대기는 owner monitor 밖이며 동시 close도 실제 lock 해제까지 기다린다. 불확실 중지는 성공이 아니다.
     *
     * @throws IOException force·channel close 실패의 원인 없는 INVALID_REMOTE_JOURNAL
     */
    @Override
    public void close() throws IOException {
        boolean interrupted = false;
        synchronized (this) {
            if (closed) {
                while (!closeFinished) {
                    try {
                        wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                if (closeFailure != null) throw new IOException("INVALID_REMOTE_JOURNAL");
                return;
            }
            closed = true;
        }
        Attempt original = attempt;
        if (original != null && original.budget != null) original.budget.cancel();
        CompletableFuture<byte[]> request = pending;
        if (request != null) request.cancel(true);
        Renewal heartbeat = renewal;
        if (heartbeat != null) heartbeat.stopAndJoin();
        synchronized (this) {
            while (admitted != 0) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        // admitted 호출이 종료되기 전 provider attach도 같은 취소 예산을 만나며 run의 finally가 alarm을 join한다.
        if (original != null && original.budget != null) original.budget.stopAlarm();
        IOException failure = null;
        original = attempt;
        if (original != null) {
            attempt = null;
            var identity = original.start.identity();
            try {
                journal.abandon(identity.jobKey(), identity.leaseGen());
            } catch (IOException exception) {
                failure = new IOException("INVALID_REMOTE_JOURNAL");
            }
        }
        try {
            journal.close();
        } catch (IOException exception) {
            failure = new IOException("INVALID_REMOTE_JOURNAL");
        }
        synchronized (this) {
            closeFailure = failure;
            closeFinished = true;
            notifyAll();
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }

    private static OwnershipException refused() {
        return new OwnershipException();
    }

    private static IllegalArgumentException failure() {
        return new IllegalArgumentException("INVALID_REMOTE_OWNER");
    }
}
