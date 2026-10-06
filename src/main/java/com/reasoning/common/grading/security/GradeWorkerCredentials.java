package com.reasoning.common.grading.security;

import com.reasoning.common.util.CommonUtil;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 신뢰된 배포 설정의 별도 worker 자격증명과 행동·런타임 허용 목록만 검사한다. 호출자는 별도로 안전한 TLS와 root·epoch·source·임대·예산·dispatch
 * 경계를 적용해야 한다. 회원·관리자·세션 인증이나 HTTP 보안 체인을 제공하지 않는다.
 */
public final class GradeWorkerCredentials {
    private final List<Registration> registrations;
    private final List<byte[]> credentialDigests;

    /** 배포 설정으로 허용할 worker 행동이며 업무 상태 전이의 승인 자체는 아니다. */
    public enum Action {
        CLAIM,
        START,
        RENEW,
        COMPLETE
    }

    /**
     * 실제 토큰을 보관하지 않는 불변 배포 등록 값이다.
     *
     * @param workerKey ASCII 영숫자·밑줄·하이픈 1~80자, null 불가
     * @param credentialSha256 토큰의 원본 32바이트에 대한 소문자 16진 SHA-256 64자, null 불가
     * @param runtimeCodes 대문자 ASCII 영숫자·밑줄 1~80자의 비어 있지 않은 집합, null 및 null 원소 불가
     * @param actions 비어 있지 않은 행동 집합, null 및 null 원소 불가
     */
    public record Registration(
            String workerKey,
            String credentialSha256,
            Set<String> runtimeCodes,
            Set<Action> actions) {
        /**
         * 등록 문법을 검증하고 호출자 집합을 불변 사본으로 보관한다.
         *
         * @param workerKey ASCII 영숫자·밑줄·하이픈 1~80자, null 불가
         * @param credentialSha256 소문자 16진 SHA-256 64자, null 불가
         * @param runtimeCodes 대문자 ASCII 영숫자·밑줄 1~80자의 비어 있지 않은 집합, null 원소 불가
         * @param actions 비어 있지 않은 행동 집합, null 및 null 원소 불가
         * @throws IllegalArgumentException 필드가 null이거나 문법·비어 있지 않은 범위를 위반하면 원인 없는
         *     INVALID_WORKER_REGISTRATION
         */
        public Registration {
            if (workerKey == null
                    || !workerKey.matches("[A-Za-z0-9_-]{1,80}")
                    || credentialSha256 == null
                    || !credentialSha256.matches("[0-9a-f]{64}")
                    || runtimeCodes == null
                    || runtimeCodes.isEmpty()
                    || actions == null
                    || actions.isEmpty()) {
                throw new IllegalArgumentException("INVALID_WORKER_REGISTRATION");
            }
            for (String runtimeCode : runtimeCodes) {
                if (runtimeCode == null || !runtimeCode.matches("[A-Z0-9_]{1,80}")) {
                    throw new IllegalArgumentException("INVALID_WORKER_REGISTRATION");
                }
            }
            for (Action action : actions) {
                if (action == null) {
                    throw new IllegalArgumentException("INVALID_WORKER_REGISTRATION");
                }
            }
            runtimeCodes = Set.copyOf(runtimeCodes);
            actions = Set.copyOf(actions);
        }

        /**
         * @return 자격증명 해시를 제외한 등록 식별자와 허용 범위
         */
        @Override
        public String toString() {
            return "Registration[workerKey="
                    + workerKey
                    + ", runtimeCodes="
                    + runtimeCodes
                    + ", actions="
                    + actions
                    + "]";
        }
    }

    /** 이 레지스트리만 생성할 수 있는 프로세스 내 불변 인증 증명이다. 레지스트리 교체·재시작을 넘는 세션이나 업무 권한 증명이 아니다. */
    public static final class VerifiedWorker {
        private final GradeWorkerCredentials owner;
        private final Registration registration;

        /**
         * 인증에 성공한 소유자와 실제 등록 객체만 결합한다.
         *
         * @param owner 인증한 레지스트리, 내부 호출에서 null 불가
         * @param registration 해당 레지스트리의 등록 객체, 내부 호출에서 null 불가
         */
        private VerifiedWorker(GradeWorkerCredentials owner, Registration registration) {
            this.owner = owner;
            this.registration = registration;
        }

        /**
         * @return 인증된 배포 worker 키이며 토큰·해시·다른 증명 값은 노출하지 않는다
         */
        public String workerKey() {
            return registration.workerKey();
        }

        /**
         * @return 토큰과 해시를 포함하지 않는 worker 식별자
         */
        @Override
        public String toString() {
            return "VerifiedWorker[workerKey=" + workerKey() + "]";
        }
    }

    /**
     * 신뢰된 배포 등록 목록을 고정한다. 빈 목록은 모든 인증을 거절한다.
     *
     * @param registrations 불변 사본으로 보관할 등록 목록, null 및 null 원소 불가
     * @throws IllegalArgumentException null 등록 또는 중복 worker 키·해시이면 원인 없는
     *     INVALID_WORKER_REGISTRATION
     */
    public GradeWorkerCredentials(List<Registration> registrations) {
        if (registrations == null) {
            throw new IllegalArgumentException("INVALID_WORKER_REGISTRATION");
        }
        var keys = new HashSet<String>();
        var digests = new HashSet<String>();
        var registered = new ArrayList<Registration>();
        var digestBytes = new ArrayList<byte[]>();
        for (Registration registration : registrations) {
            if (registration == null
                    || !keys.add(registration.workerKey())
                    || !digests.add(registration.credentialSha256())) {
                throw new IllegalArgumentException("INVALID_WORKER_REGISTRATION");
            }
            registered.add(registration);
            digestBytes.add(HexFormat.of().parseHex(registration.credentialSha256()));
        }
        this.registrations = List.copyOf(registered);
        this.credentialDigests = List.copyOf(digestBytes);
    }

    /**
     * 정확한 Bearer 헤더에서 정규형 256비트 토큰을 해시하여 모든 등록 해시와 상수 시간 비교한다. 증명에 원본 토큰을 보관하거나 증명·예외에서 해시를 노출하지
     * 않으며 업무 권한을 승인하지 않는다.
     *
     * @param authorizationHeader 정확히 "Bearer "와 패딩 없는 base64url 43자로 구성한 헤더, null이면 인증 실패하며
     *     공백·대소문자·다중 헤더를 정규화하지 않는다
     * @return 이 레지스트리와 실제 등록 객체에 결합한 불변 증명
     * @throws SecurityException 누락·비정규형·잘못된 자격증명이면 원인 없는 WORKER_AUTH_REQUIRED
     * @throws IllegalStateException SHA-256 구현을 사용할 수 없는 경우
     */
    public VerifiedWorker authenticate(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw new SecurityException("WORKER_AUTH_REQUIRED");
        }
        String token = authorizationHeader.substring(7);
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("WORKER_AUTH_REQUIRED");
        }
        if (decoded.length != 32
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
            throw new SecurityException("WORKER_AUTH_REQUIRED");
        }
        byte[] digest = HexFormat.of().parseHex(CommonUtil.sha256(decoded));
        Registration matched = null;
        for (int index = 0; index < registrations.size(); index++) {
            if (MessageDigest.isEqual(digest, credentialDigests.get(index))) {
                matched = registrations.get(index);
            }
        }
        if (matched == null) {
            throw new SecurityException("WORKER_AUTH_REQUIRED");
        }
        return new VerifiedWorker(this, matched);
    }

    /**
     * 동일 레지스트리의 실제 등록 객체와 요청 행동·런타임 허용 범위를 검사한다. 프로세스 수명 동안 설정은 고정되며 epoch·source·임대·예산·dispatch
     * 검증을 대신하지 않는다.
     *
     * @param worker 이 레지스트리가 인증한 증명, null 또는 다른 소유자이면 거절
     * @param action 허용 목록에서 검사할 행동, null이면 거절
     * @param runtimeCode 허용 목록과 정확히 일치할 런타임 코드, null이면 거절
     * @throws SecurityException 증명 소유자·등록 객체·허용 범위가 불일치하면 원인 없는 WORKER_NOT_AUTHORIZED
     */
    public void requirePermission(VerifiedWorker worker, Action action, String runtimeCode) {
        if (worker != null && worker.owner == this && action != null && runtimeCode != null) {
            for (Registration registration : registrations) {
                if (registration == worker.registration
                        && registration.actions().contains(action)
                        && registration.runtimeCodes().contains(runtimeCode)) {
                    return;
                }
            }
        }
        throw new SecurityException("WORKER_NOT_AUTHORIZED");
    }
}
