package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.util.CommonUtil;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** BATCH 서버의 닫힌 값 계약이다. 전송 파서·실행 권위·단회 chat 증명을 제공하지 않는다. */
public final class GradeRemoteExecutionProtocol {
    private GradeRemoteExecutionProtocol() {}

    public enum FailureCode {
        INVALID_REMOTE_REQUEST,
        REMOTE_EXECUTION_FORBIDDEN,
        REMOTE_EXECUTION_NOT_CURRENT,
        REMOTE_EXECUTION_EXPIRED,
        REMOTE_EXECUTION_UNAVAILABLE,
        REMOTE_REQUIRES_SEPARATE_TRANSACTION
    }

    /** 원문과 하위 원인을 보관하지 않는 고정 실패다. */
    public static final class Failure extends RuntimeException {
        private final FailureCode code;

        private Failure(FailureCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }
    }

    /**
     * 원문·원인·스택 없는 고정 실패를 만든다.
     *
     * @param code null 아닌 내부 고정 오류 코드
     * @return 코드 이름만 메시지로 가진 실패
     */
    static Failure failure(FailureCode code) {
        return new Failure(code);
    }

    /** 서버가 재계산할 동등성 digest만 왕복하며 출처나 신원을 받지 않는다. */
    public record AttemptRequest(long leaseGen, int attemptNo, String originalAttemptHash) {
        /**
         * 왕복 tuple의 형식만 검사하며 출처나 신원 권위를 만들지 않는다.
         *
         * @param leaseGen 양수 long 임대 세대
         * @param attemptNo 1~3 시도 번호
         * @param originalAttemptHash null 아닌 소문자64hex 동등성 digest
         * @throws Failure 형식이 다르면 INVALID_REMOTE_REQUEST
         */
        public AttemptRequest {
            if (leaseGen <= 0 || attemptNo < 1 || attemptNo > 3) invalid();
            hash(originalAttemptHash);
        }

        @Override
        public String toString() {
            return "AttemptRequest[redacted]";
        }
    }

    /** 응답 값은 내부 생성만 허용하고 JSON 사본도 원본과 분리한다. */
    public abstract static sealed class Value
            permits StartReply,
                    ApprovedExecutionInput,
                    FenceReply,
                    RenewReply,
                    Limits,
                    Runtime10,
                    CanonicalModelInput,
                    Fault {
        private final ObjectNode json;

        /**
         * 내부 응답 트리를 복사해 호출자와 분리한다.
         *
         * @param json null 아닌 내부 factory의 닫힌 트리
         */
        private Value(ObjectNode json) {
            this.json = json.deepCopy();
        }

        /**
         * @return 호출자가 변경해도 내부 값에 영향이 없는 정확한 닫힌 JSON 사본
         */
        public final ObjectNode toJson() {
            return json.deepCopy();
        }

        @Override
        public final String toString() {
            return getClass().getSimpleName() + "[redacted]";
        }
    }

    public static final class StartReply extends Value {
        private StartReply(ObjectNode json) {
            super(json);
        }

        public String disposition() {
            return toJson().get("disposition").textValue();
        }

        public int attemptNo() {
            return toJson().get("attemptNo").intValue();
        }
    }

    public static final class ApprovedExecutionInput extends Value {
        private ApprovedExecutionInput(ObjectNode json) {
            super(json);
        }

        public String originalAttemptHash() {
            return toJson().get("originalAttemptHash").textValue();
        }
    }

    public static final class FenceReply extends Value {
        private FenceReply(ObjectNode json) {
            super(json);
        }
    }

    public static final class RenewReply extends Value {
        private RenewReply(ObjectNode json) {
            super(json);
        }
    }

    public static final class Limits extends Value {
        private Limits(ObjectNode json) {
            super(json);
        }

        public long remainingBudgetMillis() {
            return toJson().get("remainingBudgetMillis").longValue();
        }

        public long remainingLeaseMillis() {
            return toJson().get("remainingLeaseMillis").longValue();
        }
    }

    public static final class Runtime10 extends Value {
        private Runtime10(ObjectNode json) {
            super(json);
        }
    }

    public static final class CanonicalModelInput extends Value {
        private CanonicalModelInput(ObjectNode json) {
            super(json);
        }

        public String sha256() {
            return toJson().get("sha256").textValue();
        }

        /**
         * @return 내부 factory가 만든 표준 Base64를 해독한 매번 새로운 바이트 배열
         */
        public byte[] payloadBytes() {
            return Base64.getDecoder().decode(toJson().get("bytesBase64").textValue());
        }
    }

    public static final class Fault extends Value {
        private Fault(ObjectNode json) {
            super(json);
        }
    }

    /**
     * 실제 설치 manifest의 열 개 허용 필드만 복사하며 설치 권위를 새로 검증하지 않는다.
     *
     * @param manifest null 아닌 이미 검증한 설치 JSON; 선택 문자열은 공백 불가
     * @param configHash null 아닌 소문자64hex 설정 해시
     * @param epoch 초기 0을 포함한 음수 아닌 long 현재 세대
     * @return 독립 사본을 소유한 runtime10 값
     * @throws Failure 필수 문자열·해시·세대가 잘못되면 INVALID_REMOTE_REQUEST
     */
    static Runtime10 runtime(JsonNode manifest, String configHash, long epoch) {
        hash(configHash);
        if (manifest == null || epoch < 0) invalid();
        ObjectNode node =
                object().put("code", text(manifest, "configId"))
                        .put("configHash", configHash)
                        .put("epoch", epoch);
        for (String key :
                List.of(
                        "engineVersion",
                        "modelId",
                        "modelVersion",
                        "pinMode",
                        "promptHash",
                        "optionsHash",
                        "reportContractVersion")) {
            node.put(key, text(manifest, key));
        }
        hash(node.get("promptHash").textValue());
        hash(node.get("optionsHash").textValue());
        return new Runtime10(node);
    }

    /**
     * 승인 바이트를 재인코딩 없이 복사해 표준 Base64와 정확한 SHA를 만든다.
     *
     * @param bytes null·빈 배열 아닌 승인 canonical UTF-8 바이트; schema 검증은 호출자 책임
     * @return padded Base64와 바이트 SHA를 소유한 값
     * @throws Failure null 또는 빈 배열이면 INVALID_REMOTE_REQUEST
     */
    static CanonicalModelInput model(byte[] bytes) {
        if (bytes == null || bytes.length == 0) invalid();
        byte[] copy = bytes.clone();
        return new CanonicalModelInput(
                object().put("encoding", "SNAPSHOT_JSON_CANONICAL_UTF8-v1")
                        .put("bytesBase64", Base64.getEncoder().encodeToString(copy))
                        .put("sha256", CommonUtil.sha256(copy)));
    }

    /**
     * 승인된 합성 fixture의 두 제어만 닫힌 값으로 만든다.
     *
     * @param type null 아닌 TIMEOUT 또는 UNAVAILABLE
     * @param failRuns 정확히 3인 실패 반복 수
     * @return 모델 입력과 분리한 합성 제어
     * @throws Failure 종류·반복 수가 다르면 INVALID_REMOTE_REQUEST
     */
    static Fault fault(String type, int failRuns) {
        if (!("TIMEOUT".equals(type) || "UNAVAILABLE".equals(type)) || failRuns != 3) invalid();
        return new Fault(object().put("type", type).put("failRuns", 3));
    }

    /**
     * 원래 결속 24개와 소유 시도를 private canonical preimage로 해시하며 임대 시간은 제외한다.
     *
     * @param binding null 아닌 고정 순서 24개 서버 사실; 숫자·UUID·문자열·OffsetDateTime만 허용
     * @param worker null·공백 아닌 실제 소유자 키
     * @param generation 양수 long 원래 임대 세대
     * @param attempt 1~3 실제 시도 번호
     * @param deadline null 아닌 원래 DB 마감
     * @param runtime null 아닌 설치 allowlist 값
     * @param model MODEL 승인 바이트; fault가 있을 때만 null
     * @param fault ENGINE_ERROR 제어; model이 있을 때만 null
     * @return 권위·서명이 아닌 소문자64hex 동등성 digest
     * @throws Failure 크기·스칼라 종류·tuple·variant가 다르면 INVALID_REMOTE_REQUEST
     * @throws ArithmeticException repeatNo·snapshotFormat이 int 범위를 넘으면 발생
     * @throws IllegalArgumentException canonical JSON 인코딩이 불가능하면 고정 codec 오류
     */
    static String originalHash(
            List<Object> binding,
            String worker,
            long generation,
            int attempt,
            Instant deadline,
            Runtime10 runtime,
            CanonicalModelInput model,
            Fault fault) {
        if (binding == null
                || binding.size() != 24
                || worker == null
                || worker.isBlank()
                || generation <= 0
                || attempt < 1
                || attempt > 3
                || deadline == null
                || runtime == null
                || (model == null) == (fault == null)) invalid();
        var node = object().put("formatNo", 1).put("domain", "GRADE_REMOTE_ORIGINAL_ATTEMPT-v1");
        var facts = node.putArray("sourceBinding");
        for (int i = 0; i < binding.size(); i++) {
            Object value = binding.get(i);
            if (i == 17 || i == 21) {
                if (!(value instanceof Number number)) {
                    invalid();
                }
                facts.add(Math.toIntExact(((Number) value).longValue()));
            } else if (value instanceof Number number) {
                facts.add(Long.toString(number.longValue()));
            } else if (value instanceof UUID uuid) {
                facts.add(uuid.toString());
            } else if (value instanceof OffsetDateTime time) {
                facts.add(time.toInstant().toString());
            } else if (value instanceof String string) {
                facts.add(string);
            } else {
                invalid();
            }
        }
        node.put("workerKey", worker)
                .put("leaseGen", Long.toString(generation))
                .put("attemptNo", attempt)
                .put("deadlineAt", deadline.toString())
                .put("variant", model == null ? "ENGINE_ERROR" : "MODEL");
        node.set("runtime", runtime.toJson());
        node.put("modelInputHash", model == null ? null : model.sha256());
        node.set("fault", fault == null ? JsonNodeFactory.instance.nullNode() : fault.toJson());
        return SnapshotJson.hash(node);
    }

    /**
     * 서버 사본의 공개 식별자와 승인 입력만 BATCH 봉투에 복사한다.
     *
     * @param key null·영 UUID 아닌 작업 식별자
     * @param generation 양수 long 임대 세대
     * @param attempt 1~3 시도 번호
     * @param deadline null 아닌 원래 DB 마감
     * @param snapshotId 양수 long 사본 식별자; 십진 문자열로 출력
     * @param payloadHash null 아닌 소문자64hex 사본 해시
     * @param rubricHash null 아닌 소문자64hex 채점표 해시
     * @param datasetHash null 아닌 소문자64hex 집합 해시
     * @param runtime null 아닌 runtime10 값
     * @param model MODEL 입력; fault가 있을 때만 null
     * @param fault ENGINE_ERROR 제어; model이 있을 때만 null
     * @param originalHash null 아닌 소문자64hex 동등성 digest
     * @return private 출처 사실을 포함하지 않는 독립 봉투
     * @throws Failure 식별·해시·variant 계약이 다르면 INVALID_REMOTE_REQUEST
     */
    static ApprovedExecutionInput input(
            UUID key,
            long generation,
            int attempt,
            Instant deadline,
            long snapshotId,
            String payloadHash,
            String rubricHash,
            String datasetHash,
            Runtime10 runtime,
            CanonicalModelInput model,
            Fault fault,
            String originalHash) {
        identity(key, generation, attempt);
        hash(payloadHash);
        hash(rubricHash);
        hash(datasetHash);
        hash(originalHash);
        if (snapshotId <= 0
                || deadline == null
                || runtime == null
                || (model == null) == (fault == null)) invalid();
        var node =
                identityNode(key, generation, attempt)
                        .put("sourceKind", "BATCH")
                        .put("variant", model == null ? "ENGINE_ERROR" : "MODEL")
                        .put("deadlineAt", deadline.toString())
                        .put("snapshotId", Long.toString(snapshotId))
                        .put("payloadHash", payloadHash)
                        .put("rubricHash", rubricHash)
                        .put("datasetHash", datasetHash)
                        .put("originalAttemptHash", originalHash);
        node.set("runtime", runtime.toJson());
        node.set(
                "modelInput", model == null ? JsonNodeFactory.instance.nullNode() : model.toJson());
        node.set("fault", fault == null ? JsonNodeFactory.instance.nullNode() : fault.toJson());
        return new ApprovedExecutionInput(node);
    }

    /**
     * NEW의 식별·마감 일치와 REPLAY의 명시 null을 강제한다.
     *
     * @param key null·영 UUID 아닌 작업 식별자
     * @param generation 양수 long 임대 세대
     * @param attempt 1~3 시도 번호
     * @param input NEW 입력; REPLAY일 때 limits와 함께 null
     * @param limits NEW 제한; REPLAY일 때 input과 함께 null
     * @return 소유권을 재생하지 않는 닫힌 응답
     * @throws Failure tuple·null 조합·식별·마감 불일치면 INVALID_REMOTE_REQUEST
     */
    static StartReply start(
            UUID key, long generation, int attempt, ApprovedExecutionInput input, Limits limits) {
        var node = identityNode(key, generation, attempt);
        if ((input == null) != (limits == null)) invalid();
        if (input != null) {
            var json = input.toJson();
            if (!key.toString().equals(json.path("jobKey").textValue())
                    || generation != json.path("leaseGen").longValue()
                    || attempt != json.path("attemptNo").intValue()
                    || !json.path("deadlineAt").equals(limits.toJson().path("deadlineAt")))
                invalid();
        }
        node.put("disposition", input == null ? "REPLAY" : "NEW");
        node.set("input", input == null ? JsonNodeFactory.instance.nullNode() : input.toJson());
        node.set("limits", limits == null ? JsonNodeFactory.instance.nullNode() : limits.toJson());
        return new StartReply(node);
    }

    /**
     * 커밋한 관측을 상태 없는 fence 응답으로 복사한다.
     *
     * @param key null·영 UUID 아닌 작업 식별자
     * @param request null 아닌 원래 tuple
     * @param limits null 아닌 export 제한
     * @return 단회 grant 없는 응답
     * @throws Failure null·식별 불일치면 INVALID_REMOTE_REQUEST
     */
    static FenceReply fence(UUID key, AttemptRequest request, Limits limits) {
        return new FenceReply(observed(key, request, limits));
    }

    /**
     * 갱신 후 관측을 응답으로 복사하며 갱신 SQL은 수행하지 않는다.
     *
     * @param key null·영 UUID 아닌 작업 식별자
     * @param request null 아닌 원래 tuple
     * @param limits null 아닌 export 제한
     * @return chat 소유권 없는 갱신 응답
     * @throws Failure null·식별 불일치면 INVALID_REMOTE_REQUEST
     */
    static RenewReply renew(UUID key, AttemptRequest request, Limits limits) {
        return new RenewReply(observed(key, request, limits));
    }

    /**
     * fence와 renew의 동일한 관측 필드를 새 트리에 조립한다.
     *
     * @param key null·영 UUID 아닌 작업 식별자
     * @param request null 아닌 양수 세대·1~3 시도·소문자64hash 값
     * @param limits null 아닌 양수 상대 제한
     * @return 원본과 분리된 여섯 필드 트리
     * @throws Failure null·식별 불일치면 INVALID_REMOTE_REQUEST
     */
    private static ObjectNode observed(UUID key, AttemptRequest request, Limits limits) {
        if (request == null || limits == null) invalid();
        var node =
                identityNode(key, request.leaseGen(), request.attemptNo())
                        .put("originalAttemptHash", request.originalAttemptHash());
        node.set("limits", limits.toJson());
        return node;
    }

    /**
     * 실제 관측부터 export까지의 지연을 checked 산술로 차감하고 밀리초를 내림한다.
     *
     * @param now 실제 locked DB 시각, null 불가
     * @param deadline null 아닌 원래 DB 마감, now 이후 최대 120초
     * @param lease null 아닌 현재 임대, now 이후 최대 30초
     * @param batchDeadline null 아닌 실제 batch 생성 후 24시간이며 now 이후
     * @param elapsedNanos 관측 쿼리 직전부터 export까지의 음수 아닌 경과 나노초
     * @return 각각 최소 1ms인 양수 제한
     * @throws Failure null·만료·손상된 시간·overflow·1ms 미만이면 REMOTE_EXECUTION_EXPIRED
     */
    static Limits limits(
            Instant now,
            Instant deadline,
            Instant lease,
            Instant batchDeadline,
            long elapsedNanos) {
        try {
            if (now == null
                    || deadline == null
                    || lease == null
                    || batchDeadline == null
                    || elapsedNanos < 0) throw new ArithmeticException();
            long job = Duration.between(now, deadline).toNanos();
            long leased = Duration.between(now, lease).toNanos();
            long batch = Duration.between(now, batchDeadline).toNanos();
            if (job <= 0
                    || job > 120_000_000_000L
                    || leased <= 0
                    || leased > 30_000_000_000L
                    || batch <= 0) throw new ArithmeticException();
            long budget = Math.subtractExact(Math.min(job, batch), elapsedNanos);
            long remainingLease =
                    Math.subtractExact(Math.min(leased, Math.min(job, batch)), elapsedNanos);
            if (budget < 1_000_000 || remainingLease < 1_000_000) throw new ArithmeticException();
            return new Limits(
                    object().put("deadlineAt", deadline.toString())
                            .put("leaseUntil", lease.toString())
                            .put("remainingBudgetMillis", budget / 1_000_000)
                            .put("remainingLeaseMillis", remainingLease / 1_000_000));
        } catch (ArithmeticException | java.time.DateTimeException failure) {
            throw failure(FailureCode.REMOTE_EXECUTION_EXPIRED);
        }
    }

    /**
     * tuple 범위만 검사하며 DB 권위를 주장하지 않는다.
     *
     * @param key null·영 UUID 아닌 작업 식별자
     * @param generation 양수 long 임대 세대
     * @param attempt 1~3 시도 번호
     * @throws Failure 범위가 다르면 INVALID_REMOTE_REQUEST
     */
    static void identity(UUID key, long generation, int attempt) {
        if (key == null
                || key.equals(new UUID(0, 0))
                || generation <= 0
                || attempt < 1
                || attempt > 3) invalid();
    }

    /**
     * 검사한 tuple을 formatNo=1 트리로 만든다.
     *
     * @param key null·영 UUID 아닌 작업 식별자
     * @param generation 양수 long 임대 세대
     * @param attempt 1~3 시도 번호
     * @return 새로 소유한 네 식별 필드
     * @throws Failure 범위가 다르면 INVALID_REMOTE_REQUEST
     */
    private static ObjectNode identityNode(UUID key, long generation, int attempt) {
        identity(key, generation, attempt);
        return object().put("formatNo", 1)
                .put("jobKey", key.toString())
                .put("leaseGen", generation)
                .put("attemptNo", attempt);
    }

    /**
     * 설치 필드를 정규화 없이 문자열로 읽는다.
     *
     * @param node null 아닌 내부 manifest
     * @param key null 아닌 내부 고정 필드명
     * @return null·공백 아닌 원래 문자열
     * @throws Failure 누락·문자열 아닌 값·공백이면 INVALID_REMOTE_REQUEST
     */
    private static String text(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) invalid();
        return value.textValue();
    }

    /**
     * 내용 권위 없이 정확한 소문자64hex 형식만 검사한다.
     *
     * @param value null 불가인 해시 문자열
     * @throws Failure null·형식 불일치면 INVALID_REMOTE_REQUEST
     */
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) invalid();
    }

    /**
     * @return 다른 응답과 공유하지 않는 빈 JSON 객체
     */
    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * @throws Failure 원문·원인 없는 INVALID_REMOTE_REQUEST를 항상 발생시킴
     */
    private static void invalid() {
        throw failure(FailureCode.INVALID_REMOTE_REQUEST);
    }
}
