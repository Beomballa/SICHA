package com.reasoning.common.grading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeEventRepository.JobState;
import com.reasoning.common.grading.repository.GradeEventRepository.Reason;
import com.reasoning.common.grading.service.GradeCompletionService.CompletionReceipt;
import com.reasoning.common.util.CommonUtil;

import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 닫힌 응답의 구조만 검사한다. 인증·설치·MODEL 의미·실행 권위는 생성하지 않는다. */
final class GradeRemoteReplyDecoder {
    private GradeRemoteReplyDecoder() {}

    record Identity(UUID jobKey, long leaseGen, int attemptNo) {}

    record Limits(
            Instant deadlineAt,
            Instant leaseUntil,
            long remainingBudgetMillis,
            long remainingLeaseMillis) {}

    record Runtime10(
            String code,
            String configHash,
            long epoch,
            String engineVersion,
            String modelId,
            String modelVersion,
            String pinMode,
            String promptHash,
            String optionsHash,
            String reportContractVersion) {}

    record Fault(String type, int failRuns) {}

    /** 배열을 유출하지 않는 원본 바이트다. canonical 의미 decode는 실제 profile P 고정 뒤 slice2가 맡는다. */
    static final class ModelBytes {
        private final byte[] bytes;
        private final String hash;

        private ModelBytes(byte[] bytes, String hash) {
            this.bytes = bytes.clone();
            this.hash = hash;
        }

        byte[] bytes() {
            return bytes.clone();
        }

        String hash() {
            return hash;
        }

        @Override
        public String toString() {
            return "ModelBytes[redacted]";
        }
    }

    record Input(
            Identity identity,
            String sourceKind,
            String variant,
            Instant deadlineAt,
            long snapshotId,
            String payloadHash,
            String rubricHash,
            String datasetHash,
            String reportHash,
            String originalAttemptHash,
            Runtime10 runtime,
            ModelBytes modelInput,
            Fault fault) {
        @Override
        public String toString() {
            return "Input[untrusted,redacted]";
        }
    }

    record Start(Identity identity, String disposition, Input input, Limits limits) {
        @Override
        public String toString() {
            return "Start[untrusted,redacted]";
        }
    }

    record Observed(Identity identity, String originalAttemptHash, Limits limits) {}

    record Poll(UUID jobKey, long leaseGen, Instant deadline) {}

    /**
     * 인증된 TEST poll의 닫힌 세 필드를 검사한다. 임대의 실제 권위는 후속 START가 다시 확인한다.
     *
     * @param body null 불가인 전체 UTF-8 응답
     * @return 원문 그대로 검증된 작업·세대·마감
     * @throws IllegalArgumentException 잘못된 응답은 원인 없는 INVALID_REMOTE_REPLY
     */
    static Poll decodePoll(byte[] body) {
        try {
            JsonNode node = SnapshotJson.parse(body);
            keys(node, "jobKey", "leaseGen", "deadline");
            return new Poll(
                    uuid(node, "jobKey"),
                    integer(node, "leaseGen", 1, Long.MAX_VALUE),
                    time(node, "deadline"));
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    /**
     * 실제 서버 완료 영수증의 여덟 필드와 상태 조합만 검사한다. 호출 소유권이나 실제 요청과의 일치는 별도 owner 책임이다.
     *
     * @param body null 불가인 전체 UTF-8 응답 바이트
     * @return 닫힌 안전 메타데이터 영수증이며 accepted=false는 성공 채점이 아님
     * @throws IllegalArgumentException 무효 타입·키·범위·조합의 원인 없는 INVALID_REMOTE_REPLY
     */
    static CompletionReceipt decodeCompletion(byte[] body) {
        try {
            JsonNode node = SnapshotJson.parse(body);
            keys(
                    node,
                    "jobKey",
                    "attemptNo",
                    "accepted",
                    "state",
                    "retryScheduled",
                    "outcome",
                    "reason",
                    "requestId");
            UUID jobKey = uuid(node, "jobKey");
            UUID requestId = uuid(node, "requestId");
            int attempt = (int) integer(node, "attemptNo", 1, 3);
            boolean accepted = bool(node, "accepted");
            boolean retry = bool(node, "retryScheduled");
            JobState state = JobState.valueOf(text(node, "state"));
            Reason reason = Reason.valueOf(text(node, "reason"));
            String outcome = node.get("outcome").isNull() ? null : text(node, "outcome");
            if (!accepted) {
                if (retry
                        || outcome != null
                        || !Set.of(
                                        Reason.STALE_LEASE,
                                        Reason.TERMINAL,
                                        Reason.DEADLINE_EXCEEDED,
                                        Reason.SOURCE_REVOKED,
                                        Reason.RUNTIME_EPOCH_CHANGED)
                                .contains(reason)) throw failure();
            } else if (state == JobState.COMPLETED) {
                if (retry || !"COMPLETE".equals(outcome) || reason != Reason.NONE) throw failure();
            } else {
                if (!Set.of(
                                Reason.ENGINE_TIMEOUT,
                                Reason.ENGINE_UNAVAILABLE,
                                Reason.INVALID_OUTPUT,
                                Reason.UNRESOLVED_REASONING)
                        .contains(reason)) throw failure();
                if (state == JobState.QUEUED) {
                    if (attempt == 3 || !retry || outcome != null) throw failure();
                } else if (state == JobState.FAILED) {
                    if (attempt != 3 || retry || !"SYSTEM_ERROR".equals(outcome)) throw failure();
                } else throw failure();
            }
            return new CompletionReceipt(
                    jobKey,
                    attempt,
                    accepted,
                    state.name(),
                    retry,
                    outcome,
                    reason.name(),
                    requestId);
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    /**
     * 문자열이나 수를 boolean으로 변환하지 않는다.
     *
     * @param node null 불가인 객체
     * @param key null 불가인 필수 필드명
     * @return 원래 boolean 값
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static boolean bool(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isBoolean()) throw failure();
        return value.booleanValue();
    }

    /**
     * 비영 canonical 소문자 UUID를 변환 없이 확인한다.
     *
     * @param node null 불가인 객체
     * @param key null 불가인 필수 UUID 필드명
     * @return 값의 동등성 비교용 UUID이며 권위가 아님
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static UUID uuid(JsonNode node, String key) {
        String value = text(node, key);
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value) || uuid.equals(new UUID(0, 0))) throw failure();
        return uuid;
    }

    /**
     * 완전한 START 바이트를 strict UTF-8·decoded 중복·후행 토큰·닫힌 키·정확한 수로 검사한다. 자체 MODEL 크기 상한은 만들지 않으며 유한
     * transport 상한은 실제 adapter 조립의 별도 필수값이다.
     *
     * @param body null 불가인 전체 응답 바이트
     * @return 인증되지 않은 구조 값; NEW/REPLAY null 조합과 cross-envelope 좌표는 검사됨
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    static Start decodeStart(byte[] body) {
        try {
            JsonNode node = SnapshotJson.parse(body);
            keys(
                    node,
                    "formatNo",
                    "jobKey",
                    "leaseGen",
                    "attemptNo",
                    "disposition",
                    "input",
                    "limits");
            Identity identity = identity(node);
            String disposition = text(node, "disposition");
            if (disposition.equals("REPLAY")) {
                if (!node.get("input").isNull() || !node.get("limits").isNull()) throw failure();
                return new Start(identity, disposition, null, null);
            }
            if (!disposition.equals("NEW")) throw failure();
            Input input = input(node.get("input"));
            Limits limits = limits(node.get("limits"));
            if (!identity.equals(input.identity) || !input.deadlineAt.equals(limits.deadlineAt))
                throw failure();
            return new Start(identity, disposition, input, limits);
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    /**
     * 완전한 stateless fence 응답을 검사하며 단회 grant로 해석하지 않는다.
     *
     * @param body null 불가인 전체 응답
     * @return 인증되지 않은 tuple/hash/limits 구조 값
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    static Observed decodeFence(byte[] body) {
        return observed(body);
    }

    /**
     * 완전한 renewal 응답을 검사하며 기존 lease의 생존이나 갱신 권위를 증명하지 않는다.
     *
     * @param body null 불가인 전체 응답
     * @return 인증되지 않은 tuple/hash/limits 구조 값
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    static Observed decodeRenew(byte[] body) {
        return observed(body);
    }

    /**
     * 같은 형태의 fence/renew 응답을 읽는다.
     *
     * @param body null 불가인 전체 UTF-8 바이트
     * @return 인증되지 않은 관측 값
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static Observed observed(byte[] body) {
        try {
            JsonNode node = SnapshotJson.parse(body);
            keys(
                    node,
                    "formatNo",
                    "jobKey",
                    "leaseGen",
                    "attemptNo",
                    "originalAttemptHash",
                    "limits");
            return new Observed(
                    identity(node), hash(node, "originalAttemptHash"), limits(node.get("limits")));
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    /**
     * private source preimage 없이 MODEL/ENGINE_ERROR 조합과 공개 좌표만 검사한다.
     *
     * @param node null 불가인 NEW input 객체
     * @return 원본 MODEL bytes를 소유한 구조 값, ENGINE_ERROR이면 modelInput만 null
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static Input input(JsonNode node) {
        String sourceKind = text(node, "sourceKind");
        if (sourceKind.equals("BATCH")) {
            keys(
                    node,
                    "formatNo",
                    "jobKey",
                    "leaseGen",
                    "attemptNo",
                    "sourceKind",
                    "variant",
                    "deadlineAt",
                    "snapshotId",
                    "payloadHash",
                    "rubricHash",
                    "datasetHash",
                    "originalAttemptHash",
                    "runtime",
                    "modelInput",
                    "fault");
        } else if (sourceKind.equals("TEST")) {
            keys(
                    node,
                    "formatNo",
                    "jobKey",
                    "leaseGen",
                    "attemptNo",
                    "sourceKind",
                    "variant",
                    "deadlineAt",
                    "snapshotId",
                    "payloadHash",
                    "rubricHash",
                    "reportHash",
                    "originalAttemptHash",
                    "runtime",
                    "modelInput",
                    "fault");
        } else throw failure();
        Identity identity = identity(node);
        String variant = text(node, "variant");
        if (sourceKind.equals("TEST") && !variant.equals("MODEL")) throw failure();
        ModelBytes model = null;
        Fault fault = null;
        if (variant.equals("MODEL")) {
            if (!node.get("fault").isNull()) throw failure();
            JsonNode payload = node.get("modelInput");
            keys(payload, "encoding", "bytesBase64", "sha256");
            if (!text(payload, "encoding").equals("SNAPSHOT_JSON_CANONICAL_UTF8-v1"))
                throw failure();
            String encoded = text(payload, "bytesBase64");
            byte[] bytes = Base64.getDecoder().decode(encoded);
            String hash = hash(payload, "sha256");
            if (bytes.length == 0
                    || !Base64.getEncoder().encodeToString(bytes).equals(encoded)
                    || !CommonUtil.sha256(bytes).equals(hash)) throw failure();
            model = new ModelBytes(bytes, hash);
        } else if (variant.equals("ENGINE_ERROR")) {
            if (!node.get("modelInput").isNull()) throw failure();
            JsonNode control = node.get("fault");
            keys(control, "type", "failRuns");
            String type = text(control, "type");
            if (!(type.equals("TIMEOUT") || type.equals("UNAVAILABLE"))
                    || integer(control, "failRuns", 3, 3) != 3) throw failure();
            fault = new Fault(type, 3);
        } else {
            throw failure();
        }
        String snapshot = text(node, "snapshotId");
        if (!snapshot.matches("[1-9][0-9]{0,18}")) throw failure();
        long id = Long.parseLong(snapshot);
        if (id <= 0) throw failure();
        return new Input(
                identity,
                sourceKind,
                variant,
                time(node, "deadlineAt"),
                id,
                hash(node, "payloadHash"),
                hash(node, "rubricHash"),
                sourceKind.equals("BATCH") ? hash(node, "datasetHash") : null,
                sourceKind.equals("TEST") ? hash(node, "reportHash") : null,
                hash(node, "originalAttemptHash"),
                runtime(node.get("runtime")),
                model,
                fault);
    }

    /**
     * 정확한 열 필드·설치 좌표 문법과 초기 epoch0을 포함한 정수 범위를 확인한다. 설치 동등성은 증명하지 않는다.
     *
     * @param node null 불가인 runtime 객체
     * @return 설치 증명이 아닌 열 좌표
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static Runtime10 runtime(JsonNode node) {
        keys(
                node,
                "code",
                "configHash",
                "epoch",
                "engineVersion",
                "modelId",
                "modelVersion",
                "pinMode",
                "promptHash",
                "optionsHash",
                "reportContractVersion");
        String code = text(node, "code");
        if (!code.matches("[A-Z0-9_]{1,80}")) throw failure();
        String engineVersion = text(node, "engineVersion");
        String modelId = text(node, "modelId");
        String pinMode = text(node, "pinMode");
        String reportContract = text(node, "reportContractVersion");
        if (!engineVersion.matches("[A-Za-z0-9_.:-]{1,100}")
                || !modelId.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
                || modelId.length() > 200
                || modelId.toLowerCase(java.util.Locale.ROOT).contains("cloud")
                || !pinMode.equals("ALIAS_MONITORED")
                || !reportContract.equals("REPORT-1")) throw failure();
        return new Runtime10(
                code,
                hash(node, "configHash"),
                integer(node, "epoch", 0, Long.MAX_VALUE),
                engineVersion,
                modelId,
                hash(node, "modelVersion"),
                pinMode,
                hash(node, "promptHash"),
                hash(node, "optionsHash"),
                reportContract);
    }

    /**
     * format1·비영 canonical UUID·양수 세대·1~3 시도를 확인한다.
     *
     * @param node null 불가인 식별 봉투
     * @return 내용 일치 비교용 tuple
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static Identity identity(JsonNode node) {
        integer(node, "formatNo", 1, 1);
        return new Identity(
                uuid(node, "jobKey"),
                integer(node, "leaseGen", 1, Long.MAX_VALUE),
                (int) integer(node, "attemptNo", 1, 3));
    }

    /**
     * 서버의 양수 1~120000ms J·1~30000ms L과 UTC 표현을 확인한다.
     *
     * @param node null 불가인 limits 객체
     * @return monotonic 앵커가 없는 상대 제한
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static Limits limits(JsonNode node) {
        keys(node, "deadlineAt", "leaseUntil", "remainingBudgetMillis", "remainingLeaseMillis");
        long budget = integer(node, "remainingBudgetMillis", 1, 120000);
        long lease = integer(node, "remainingLeaseMillis", 1, 30000);
        if (lease > budget) throw failure();
        return new Limits(time(node, "deadlineAt"), time(node, "leaseUntil"), budget, lease);
    }

    /**
     * 실제 Instant.toString의 canonical UTC 표현만 허용한다. 로컬 만료를 계산하지 않는다.
     *
     * @param node null 불가인 객체
     * @param key null 불가인 시각 필드명
     * @return 원래 사실 비교용 UTC 시각
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static Instant time(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.endsWith("Z")) throw failure();
        Instant instant = Instant.parse(value);
        if (!instant.toString().equals(value)) throw failure();
        return instant;
    }

    /**
     * 문자열·소수·overflow를 변환·반올림하지 않는다.
     *
     * @param node null 불가인 객체
     * @param key null 불가인 정수 필드명
     * @param min 포함하는 하한
     * @param max 포함하는 상한
     * @return 범위 내 정확한 long
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static long integer(JsonNode node, String key, long min, long max) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw failure();
        long number = value.longValue();
        if (number < min || number > max) throw failure();
        return number;
    }

    /**
     * 필수 문자열을 정규화하지 않는다.
     *
     * @param node null 불가인 객체
     * @param key null 불가인 필드명
     * @return null·blank 아닌 원문 문자열
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw failure();
        return value.textValue();
    }

    /**
     * hash 문법만 검사하며 private preimage를 만들지 않는다.
     *
     * @param node null 불가인 객체
     * @param key null 불가인 hash 필드명
     * @return 소문자64hex 동등성 문자열
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static String hash(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.matches("[0-9a-f]{64}")) throw failure();
        return value;
    }

    /**
     * 누락·추가 필드를 거절한다. decoded duplicate는 SnapshotJson이 검사한다.
     *
     * @param node null 불가인 닫힌 객체
     * @param fields null 불가인 정확한 키 목록
     * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_REPLY
     */
    private static void keys(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) throw failure();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(fields))) throw failure();
    }

    private static IllegalArgumentException failure() {
        return new IllegalArgumentException("INVALID_REMOTE_REPLY");
    }
}
