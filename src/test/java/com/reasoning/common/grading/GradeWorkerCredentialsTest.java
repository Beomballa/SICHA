package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.security.GradeWorkerCredentials.VerifiedWorker;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 공개된 결정적 합성 32바이트만 사용하며 실제 비밀·HTTP·DB·가짜 principal을 사용하지 않는다. */
class GradeWorkerCredentialsTest {
    private static final byte[] SYNTHETIC_BYTES =
            Arrays.copyOf(
                    "SYNTHETIC_TEST_ONLY_NOT_A_SECRET".getBytes(StandardCharsets.US_ASCII), 32);
    private static final String TOKEN =
            Base64.getUrlEncoder().withoutPadding().encodeToString(SYNTHETIC_BYTES);
    private static final String DIGEST = digest(SYNTHETIC_BYTES);

    /** 원본 바이트의 실제 SHA-256으로 worker가 정해지며 반복 인증과 모든 행동 검사를 허용한다. */
    @Test
    void authenticatesActualCredentialToFixedWorkerRepeatedly() {
        var registry =
                new GradeWorkerCredentials(
                        List.of(
                                registration(
                                        "unmatched",
                                        digest(otherBytes()),
                                        Set.of("OTHER"),
                                        Set.of(Action.CLAIM)),
                                registration(
                                        "worker_1-A",
                                        DIGEST,
                                        Set.of("RUNTIME_1"),
                                        Set.of(Action.values())),
                                registration(
                                        "also-unmatched",
                                        digest(thirdBytes()),
                                        Set.of("OTHER"),
                                        Set.of(Action.START))));
        for (int attempt = 0; attempt < 3; attempt++) {
            VerifiedWorker worker = registry.authenticate("Bearer " + TOKEN);
            assertThat(worker.workerKey()).isEqualTo("worker_1-A");
            for (Action action : Action.values()) {
                registry.requirePermission(worker, action, "RUNTIME_1");
            }
        }
    }

    /** 누락·잘못된 토큰·다중 헤더·공백·패딩·대소문자·비정규형 마지막 비트를 거절한다. */
    @Test
    void rejectsMalformedAndNoncanonicalHeadersWithoutLeakingInput() {
        var registry = registry();
        var invalidHeaders =
                new ArrayList<String>(
                        List.of(
                                "",
                                "Bearer",
                                "Bearer ",
                                "bearer " + TOKEN,
                                "BEARER " + TOKEN,
                                " Bearer " + TOKEN,
                                "Bearer  " + TOKEN,
                                "Bearer\t" + TOKEN,
                                "Bearer " + TOKEN + " ",
                                "Bearer " + TOKEN + "\n",
                                "Bearer " + TOKEN + "=",
                                "Bearer " + TOKEN.substring(1),
                                "Bearer " + TOKEN + "A",
                                "Bearer " + TOKEN.substring(0, 42) + "+",
                                "Bearer " + TOKEN.substring(0, 42) + "/",
                                "Bearer " + TOKEN + ",Bearer " + TOKEN,
                                "Bearer " + TOKEN + "\r\nAuthorization: Bearer " + TOKEN,
                                "Bearer "
                                        + Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(otherBytes()),
                                "Bearer " + noncanonicalToken()));
        invalidHeaders.add(null);
        assertThat(Base64.getUrlDecoder().decode(noncanonicalToken())).isEqualTo(SYNTHETIC_BYTES);
        for (String header : invalidHeaders) {
            assertThatThrownBy(() -> registry.authenticate(header))
                    .isInstanceOf(SecurityException.class)
                    .hasMessage("WORKER_AUTH_REQUIRED")
                    .hasNoCause();
        }
    }

    /** 중복 키·해시와 null 설정을 인증의 모호함 없이 생성 시 거절한다. */
    @Test
    void rejectsDuplicateWorkersDigestsAndNullRegistrations() {
        Registration first =
                registration("worker", DIGEST, Set.of("RUNTIME"), Set.of(Action.CLAIM));
        invalidRegistration(() -> new GradeWorkerCredentials(null));
        invalidRegistration(() -> new GradeWorkerCredentials(Arrays.asList(first, null)));
        invalidRegistration(() -> new GradeWorkerCredentials(List.of(first, first)));
        invalidRegistration(
                () ->
                        new GradeWorkerCredentials(
                                List.of(
                                        first,
                                        registration(
                                                "worker",
                                                digest(otherBytes()),
                                                Set.of("OTHER"),
                                                Set.of(Action.START)))));
        invalidRegistration(
                () ->
                        new GradeWorkerCredentials(
                                List.of(
                                        first,
                                        registration(
                                                "other",
                                                DIGEST,
                                                Set.of("OTHER"),
                                                Set.of(Action.START)))));
    }

    /** worker·runtime·digest·행동의 null과 엄격한 ASCII 문법 위반을 거절한다. */
    @Test
    void rejectsInvalidRegistrationFieldsWithoutLeakingDigest() {
        for (String key : Arrays.asList(null, "", " ", "a b", "a.b", "가", "é", "a".repeat(81))) {
            invalidRegistration(
                    () -> registration(key, DIGEST, Set.of("RUNTIME"), Set.of(Action.CLAIM)));
        }
        for (String hash :
                Arrays.asList(
                        null,
                        "",
                        DIGEST.toUpperCase(),
                        "g".repeat(64),
                        "a".repeat(63),
                        "a".repeat(65),
                        " " + DIGEST)) {
            invalidRegistration(
                    () -> registration("worker", hash, Set.of("RUNTIME"), Set.of(Action.CLAIM)));
        }
        for (String runtime :
                Arrays.asList(null, "", " ", "runtime", "A-B", "A B", "가", "A".repeat(81))) {
            var codes = new HashSet<String>();
            codes.add(runtime);
            invalidRegistration(() -> registration("worker", DIGEST, codes, Set.of(Action.CLAIM)));
        }
        invalidRegistration(() -> registration("worker", DIGEST, null, Set.of(Action.CLAIM)));
        invalidRegistration(() -> registration("worker", DIGEST, Set.of(), Set.of(Action.CLAIM)));
        invalidRegistration(() -> registration("worker", DIGEST, Set.of("RUNTIME"), null));
        invalidRegistration(() -> registration("worker", DIGEST, Set.of("RUNTIME"), Set.of()));
        var actions = new HashSet<Action>();
        actions.add(null);
        invalidRegistration(() -> registration("worker", DIGEST, Set.of("RUNTIME"), actions));
    }

    /** 허용된 최대·최소 길이를 수용하고 토큰에 추가 엔트로피 제한을 적용하지 않는다. */
    @Test
    void acceptsGrammarBoundariesAndAll32ByteValues() {
        byte[] syntheticZeroBytes = new byte[32];
        var registry =
                new GradeWorkerCredentials(
                        List.of(
                                registration(
                                        "a",
                                        digest(syntheticZeroBytes),
                                        Set.of("0"),
                                        Set.of(Action.CLAIM)),
                                registration(
                                        "A".repeat(80),
                                        DIGEST,
                                        Set.of("A".repeat(80)),
                                        Set.of(Action.COMPLETE))));
        registry.requirePermission(
                registry.authenticate(
                        "Bearer "
                                + Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(syntheticZeroBytes)),
                Action.CLAIM,
                "0");
        registry.requirePermission(
                registry.authenticate("Bearer " + TOKEN), Action.COMPLETE, "A".repeat(80));
    }

    /** 호출자의 가변 목록·집합 변경에서 등록과 이미 발급한 증명의 권한을 격리한다. */
    @Test
    void snapshotsMutableDeploymentInputsWithoutLaterGrants() {
        var runtimes = new HashSet<>(Set.of("RUNTIME"));
        var actions = new HashSet<>(Set.of(Action.CLAIM));
        var registration = registration("worker", DIGEST, runtimes, actions);
        var registrations = new ArrayList<>(List.of(registration));
        var registry = new GradeWorkerCredentials(registrations);
        var worker = registry.authenticate("Bearer " + TOKEN);
        runtimes.clear();
        runtimes.add("OTHER");
        actions.clear();
        actions.add(Action.COMPLETE);
        registrations.clear();
        registrations.add(
                registration(
                        "added", digest(otherBytes()), Set.of("OTHER"), Set.of(Action.COMPLETE)));
        registry.requirePermission(worker, Action.CLAIM, "RUNTIME");
        registry.requirePermission(
                registry.authenticate("Bearer " + TOKEN), Action.CLAIM, "RUNTIME");
        denied(() -> registry.requirePermission(worker, Action.CLAIM, "OTHER"));
        denied(() -> registry.requirePermission(worker, Action.COMPLETE, "RUNTIME"));
        assertThatThrownBy(
                        () ->
                                registry.authenticate(
                                        "Bearer "
                                                + Base64.getUrlEncoder()
                                                        .withoutPadding()
                                                        .encodeToString(otherBytes())))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKER_AUTH_REQUIRED")
                .hasNoCause();
        assertThatThrownBy(() -> registration.runtimeCodes().add("OTHER"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registration.actions().add(Action.COMPLETE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 동일한 등록 객체라도 다른 레지스트리의 증명, null, 범위 밖 행동·runtime을 거절한다. */
    @Test
    void bindsProofToExactRegistryAndEnforcesPermissions() {
        var registration = registration("worker", DIGEST, Set.of("RUNTIME"), Set.of(Action.CLAIM));
        var registry = new GradeWorkerCredentials(List.of(registration));
        var other = new GradeWorkerCredentials(List.of(registration));
        var worker = registry.authenticate("Bearer " + TOKEN);
        denied(() -> other.requirePermission(worker, Action.CLAIM, "RUNTIME"));
        denied(
                () ->
                        registry.requirePermission(
                                other.authenticate("Bearer " + TOKEN), Action.CLAIM, "RUNTIME"));
        denied(() -> registry.requirePermission(null, Action.CLAIM, "RUNTIME"));
        denied(() -> registry.requirePermission(worker, null, "RUNTIME"));
        denied(() -> registry.requirePermission(worker, Action.CLAIM, null));
        denied(() -> registry.requirePermission(worker, Action.START, "RUNTIME"));
        denied(() -> registry.requirePermission(worker, Action.RENEW, "RUNTIME"));
        denied(() -> registry.requirePermission(worker, Action.COMPLETE, "RUNTIME"));
        for (String runtime : List.of("OTHER", "runtime", " RUNTIME", "RUNTIME ", "")) {
            denied(() -> registry.requirePermission(worker, Action.CLAIM, runtime));
        }
    }

    /** 빈 설정은 유효한 형식의 토큰도 거절하며 기존 증명도 받지 않는다. */
    @Test
    void emptyConfigurationDeniesAll() {
        var empty = new GradeWorkerCredentials(List.of());
        assertThatThrownBy(() -> empty.authenticate("Bearer " + TOKEN))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKER_AUTH_REQUIRED")
                .hasNoCause();
        denied(() -> empty.requirePermission(null, Action.CLAIM, "RUNTIME"));
        denied(
                () ->
                        empty.requirePermission(
                                registry().authenticate("Bearer " + TOKEN),
                                Action.CLAIM,
                                "RUNTIME"));
    }

    /** 공개 증명 생성자·기본 설정이 없으며 로그에서 비밀 값을 제외한다. */
    @Test
    void exposesOnlyPrivateProofConstructionAndRedactedLogs() {
        assertThat(Modifier.isFinal(GradeWorkerCredentials.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(VerifiedWorker.class.getModifiers())).isTrue();
        assertThat(VerifiedWorker.class.getConstructors()).isEmpty();
        for (var constructor : VerifiedWorker.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }
        assertThat(GradeWorkerCredentials.class.getConstructors()).hasSize(1);
        assertThat(GradeWorkerCredentials.class.getConstructors()[0].getParameterTypes())
                .containsExactly(List.class);
        assertThat(
                        Arrays.stream(VerifiedWorker.class.getDeclaredMethods())
                                .filter(method -> Modifier.isPublic(method.getModifiers()))
                                .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("workerKey", "toString");
        var registration = registration("worker", DIGEST, Set.of("RUNTIME"), Set.of(Action.CLAIM));
        var registry = new GradeWorkerCredentials(List.of(registration));
        assertThat(registration.toString())
                .contains("worker")
                .doesNotContain(TOKEN, DIGEST, "credentialSha256");
        assertThat(registry.authenticate("Bearer " + TOKEN).toString())
                .contains("worker")
                .doesNotContain(TOKEN, DIGEST);
        assertThat(registry.toString()).doesNotContain(TOKEN, DIGEST);
    }

    /**
     * @return 합성 worker와 CLAIM/RUNTIME만 등록한 레지스트리
     */
    private static GradeWorkerCredentials registry() {
        return new GradeWorkerCredentials(
                List.of(registration("worker", DIGEST, Set.of("RUNTIME"), Set.of(Action.CLAIM))));
    }

    /**
     * 입력을 그대로 등록에 전달하며 잘못된 입력 검사에서도 정규화하지 않는다.
     *
     * @param key 검사할 worker 키, null 포함
     * @param hash 검사할 SHA-256 문자열, null 포함
     * @param runtimes 검사할 runtime 집합, null 포함
     * @param actions 검사할 행동 집합, null 포함
     * @return 검증된 등록
     * @throws IllegalArgumentException 구현의 등록 계약을 위반하는 경우
     */
    private static Registration registration(
            String key, String hash, Set<String> runtimes, Set<Action> actions) {
        return new Registration(key, hash, runtimes, actions);
    }

    /**
     * CommonUtil과 독립적인 JDK SHA-256으로 합성 fixture의 해시를 만든다.
     *
     * @param bytes null이 아닌 합성 원본 바이트
     * @return 소문자 SHA-256 16진 문자열
     * @throws AssertionError 필수 JDK 알고리즘이 없는 경우
     */
    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * @return 합성 원본의 마지막 바이트만 변경한 별도의 공개 32바이트 fixture
     */
    private static byte[] otherBytes() {
        byte[] bytes = SYNTHETIC_BYTES.clone();
        bytes[31] = 1;
        return bytes;
    }

    /**
     * @return 전체 등록 비교의 마지막에 배치할 세 번째 공개 32바이트 fixture
     */
    private static byte[] thirdBytes() {
        byte[] bytes = SYNTHETIC_BYTES.clone();
        bytes[31] = 2;
        return bytes;
    }

    /**
     * @return 원본 디코딩 결과는 같지만 마지막 미사용 비트가 0이 아닌 43자 토큰
     */
    private static String noncanonicalToken() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(TOKEN.charAt(42));
        return TOKEN.substring(0, 42) + alphabet.charAt(last + 1);
    }

    /**
     * 등록 예외의 고정 메시지와 원인 비공개를 검사한다.
     *
     * @param operation 잘못된 설정을 생성하는 처리, null 불가
     */
    private static void invalidRegistration(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_WORKER_REGISTRATION")
                .hasNoCause();
    }

    /**
     * 권한 예외의 고정 메시지와 원인 비공개를 검사한다.
     *
     * @param operation 거절될 권한 검사, null 불가
     */
    private static void denied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKER_NOT_AUTHORIZED")
                .hasNoCause();
    }
}
