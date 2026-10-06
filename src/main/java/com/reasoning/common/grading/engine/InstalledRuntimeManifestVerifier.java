package com.reasoning.common.grading.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeModels;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.grading.service.FrozenDatasetValidator;
import com.reasoning.common.grading.service.FrozenDatasetValidator.SelectedSample;
import com.reasoning.common.grading.service.FrozenDatasetValidator.ValidatedDataset;
import com.reasoning.common.grading.service.GradeCalculator;
import com.reasoning.common.grading.service.GradeResultValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.util.CommonUtil;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;

/**
 * 신뢰한 서버 조립 단계에서 실제 실행 인스턴스와 설치 원본을 결속한다. 설치는 프로세스 수명 동안 불변이어야 하며 교체는 새 프로세스로 수행한다. 호스트 침해·계측·이미
 * 로드된 바이트의 변경·hot reload를 증명하지 않는다. AVAILABLE·epoch·작업자·GRADE 인가와 무관하다.
 */
public final class InstalledRuntimeManifestVerifier {
    private static final String INVALID = "INSTALLED_RUNTIME_MISMATCH";
    private static final String UNSUPPORTED = "UNSUPPORTED_INSTALLED_ORIGIN";
    private static final String BOOT_CLASSES = "BOOT-INF/classes/";
    private static final Class<?>[] PARTICIPANTS = {
        LocalSemanticEngine.class, GradeModels.class, GradeCalculator.class,
                GradeResultValidator.class,
        FrozenSnapshotCodec.class, SnapshotJson.class, FrozenModelProjection.class,
                GradeDictionary.class,
        CommonUtil.class, FrozenDatasetValidator.class, InstalledRuntimeManifestVerifier.class
    };
    private final LocalSemanticEngine engine;
    private final GradeDictionary dictionary;
    private final InstalledProfile profile;
    private final byte[] manifest;
    private final String configHash;
    private final String codeHash;
    private final String policyHash;

    /** 배포 관리 코드이며 자체로 코드 동일성·실행 인가를 증명하지 않는다. 기본 정책은 없다. */
    public record InstalledProfile(
            String configId, String engineId, String engineVersion, String policyCode) {
        public InstalledProfile {
            if (configId == null
                    || !configId.matches("[A-Z0-9_]{1,80}")
                    || !label(engineId)
                    || !label(engineVersion)
                    || !"RULE_20260924".equals(policyCode)) throw failure();
        }
    }

    /**
     * 실제 엔진·사전·배포 프로필로 설치 증거를 한 번 계산한다. 파일 접근은 SQL 트랜잭션 밖에서만 수행한다.
     *
     * @param engine 실제 호출할 엔진 인스턴스
     * @param dictionary 실제 호출에 사용할 불변 사전
     * @param profile 신뢰한 서버 배포 설정; 등록 행에서 만들지 않는다
     * @throws IllegalStateException 지원하지 않는 설치 원본 또는 고정 구성 불일치
     */
    public InstalledRuntimeManifestVerifier(
            LocalSemanticEngine engine, GradeDictionary dictionary, InstalledProfile profile) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw failure();
        if (engine == null || dictionary == null || profile == null) throw failure();
        this.engine = engine;
        this.dictionary = dictionary;
        this.profile = profile;
        var descriptor = engine.configurationDescriptor();
        if (!descriptor.dictionaryHash().equals(dictionary.sha256())) throw failure();
        codeHash = installedCodeHash();
        policyHash = policyCatalogueHash(profile.policyCode());
        ObjectNode node =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("formatNo", 1)
                        .put("manifestEncoding", "SNAPSHOT_JSON_CANONICAL_UTF8-v1")
                        .put("configId", profile.configId())
                        .put("engineId", profile.engineId())
                        .put("engineVersion", profile.engineVersion())
                        .put("engineCodeHash", codeHash)
                        .put("provider", "OLLAMA")
                        .put("modelId", descriptor.modelId())
                        .put("modelVersion", descriptor.modelVersion())
                        .put("pinMode", "ALIAS_MONITORED")
                        .put("promptHash", descriptor.promptHash())
                        .put("optionsHash", descriptor.optionsHash())
                        .put("reportContractVersion", "REPORT-1")
                        .put("gradeContractVersion", "CASE-GRADE-01")
                        .put("policyCode", profile.policyCode())
                        .put("policyHash", policyHash)
                        .put("settingsHash", descriptor.settingsHash())
                        .put("dictionaryHash", dictionary.sha256())
                        .put("inputDocumentBinding", LocalSemanticEngine.INPUT_DOCUMENT_BINDING);
        manifest = SnapshotJson.encode(node);
        configHash = SnapshotJson.hash(node);
    }

    /**
     * 잠근 등록 행과 메모리 설치 증거만 비교한다. 파일·네트워크·DB 쓰기 없이 엄격한 닫힌 전체 형식을 검사한다. 숫자의 동등 표기와 JSONB 키 순서는
     * SnapshotJson 표준 바이트로 비교한다.
     *
     * @param registered 소유자가 잠금·현재 epoch/상태/자격을 별도 검사할 등록 행
     * @return 같은 실제 인스턴스만 호출할 수 있는 값; 예약·디스패치 권한이 아니다
     */
    public VerifiedRuntime verify(RuntimeRow registered) {
        try {
            if (registered == null
                    || !profile.configId().equals(registered.code())
                    || registered.configJson() == null) throw failure();
            JsonNode parsed =
                    SnapshotJson.parse(registered.configJson().getBytes(StandardCharsets.UTF_8));
            if (!Arrays.equals(manifest, SnapshotJson.encode(parsed))
                    || !configHash.equals(registered.configHash())
                    || !SnapshotJson.hash(parsed).equals(registered.configHash())) throw failure();
            return new VerifiedRuntime();
        } catch (RuntimeException exception) {
            throw failure();
        }
    }

    public JsonNode registrationManifest() {
        return SnapshotJson.parse(manifest);
    }

    public String configHash() {
        return configHash;
    }

    public String engineCodeHash() {
        return codeHash;
    }

    public String policyHash() {
        return policyHash;
    }

    /** epoch를 포함하지 않는 내부 설정 대조값이며 자체로 실행 권위가 아니다. */
    public record RuntimeConfiguration(
            String code,
            String configHash,
            String engineVersion,
            String modelId,
            String modelVersion,
            String pinMode,
            String promptHash,
            String optionsHash,
            String reportContractVersion) {
        public RuntimeConfiguration {
            if (code == null
                    || configHash == null
                    || engineVersion == null
                    || modelId == null
                    || modelVersion == null
                    || pinMode == null
                    || promptHash == null
                    || optionsHash == null
                    || reportContractVersion == null) throw profileMismatch();
        }

        @Override
        public String toString() {
            return "RuntimeConfiguration[redacted]";
        }
    }

    /**
     * 실제 아홉 설정을 대조하고 private Settings로 decode 이전 profile 예산을 고정한다.
     *
     * @param runtime null 불가인 epoch 없는 설정
     * @param requestStartedNano 원래 START dispatch 직전 monotonic 값
     * @param remainingBudgetMillis 원래 응답의 1~120000ms
     * @param remainingLeaseMillis 원래 응답의 1~30000ms
     * @return 같은 engine에 결속된 원래 live 예산
     * @throws IllegalStateException 설정 불일치의 WORKER_PROFILE_MISMATCH
     */
    public LocalSemanticEngine.JobDeadline openDeadline(
            RuntimeConfiguration runtime,
            long requestStartedNano,
            long remainingBudgetMillis,
            long remainingLeaseMillis) {
        compareConfiguration(runtime);
        return engine.openDeadline(requestStartedNano, remainingBudgetMillis, remainingLeaseMillis);
    }

    /**
     * 같은 실제 설치와 불변 의미 입력을 결속하며 provider I/O·시간 재설정은 하지 않는다.
     *
     * @param runtime null 불가인 아홉 설정
     * @param input null 불가인 실제 canonical 의미 입력
     * @return private 생성 실행값
     * @throws IllegalStateException 설정·입력 불일치의 안전한 고정 오류
     */
    public QualifiedExecution bindExecution(
            RuntimeConfiguration runtime, FrozenModelProjection.SemanticInput input) {
        compareConfiguration(runtime);
        if (input == null) throw profileMismatch();
        return new QualifiedExecution(input);
    }

    /** computed full19 hash와 나머지 여덟 좌표를 정확히 비교한다. */
    private void compareConfiguration(RuntimeConfiguration runtime) {
        var descriptor = engine.configurationDescriptor();
        if (runtime == null
                || !profile.configId().equals(runtime.code())
                || !configHash.equals(runtime.configHash())
                || !profile.engineVersion().equals(runtime.engineVersion())
                || !descriptor.modelId().equals(runtime.modelId())
                || !descriptor.modelVersion().equals(runtime.modelVersion())
                || !"ALIAS_MONITORED".equals(runtime.pinMode())
                || !descriptor.promptHash().equals(runtime.promptHash())
                || !descriptor.optionsHash().equals(runtime.optionsHash())
                || !"REPORT-1".equals(runtime.reportContractVersion())) throw profileMismatch();
    }

    /** private 생성한 실제 engine·전체 사전·바로 그 의미 입력의 소유값이다. */
    public final class QualifiedExecution {
        private final FrozenModelProjection.SemanticInput input;

        private QualifiedExecution(FrozenModelProjection.SemanticInput input) {
            this.input = input;
        }

        public String configHash() {
            return configHash;
        }

        public String runtimeCode() {
            return profile.configId();
        }

        /**
         * 원래 예산과 실제 소유 훅으로 단일 provider core를 소비한다.
         *
         * @param originalBudget null 불가이며 openDeadline의 같은 engine 예산
         * @param owningBeforeChat null 불가인 실제 소유자 전송 경계
         * @return 실제 검증 결과이며 채점은 서버 책임
         * @throws RuntimeException 원래 소유 훅·예산·엔진 실패 타입을 보존한다
         */
        public LocalSemanticEngine.Result evaluate(
                LocalSemanticEngine.JobDeadline originalBudget,
                LocalSemanticEngine.BeforeChat owningBeforeChat) {
            return engine.evaluate(input, dictionary, originalBudget, owningBeforeChat);
        }

        @Override
        public String toString() {
            return "QualifiedExecution[redacted]";
        }
    }

    private static IllegalStateException profileMismatch() {
        return new IllegalStateException("WORKER_PROFILE_MISMATCH");
    }

    /** 비공개 생성자로만 발행한다. 현재 작업자·사본·epoch·GRADE 권한은 영속 소유자의 별도 책임이다. */
    public final class VerifiedRuntime {
        private VerifiedRuntime() {}

        public InstalledProfile profile() {
            return profile;
        }

        public String configHash() {
            return configHash;
        }

        /**
         * 예약 후 커밋한 소유자가 같은 집합·선택·원래 작업 예산과 필수 전송 직전 훅으로 호출한다.
         *
         * @param dataset null 불가인 전체 검증 집합
         * @param selected 같은 집합의 정상 REPORT 선택, null 불가
         * @param deadline 원래 DB 마감의 불변 예산, null 불가; 최대 120초
         * @param beforeChat null 불가인 순서 훅이며 자체 DB 인가 증명은 아님
         * @return 실제 검증 원문을 서버 내부에 유지하는 결과
         * @throws LocalSemanticEngine.EngineException 고정 엔진 오류와 실제 전송 단계
         * @throws RuntimeException 전송 직전 훅의 원래 거절 타입
         */
        public LocalSemanticEngine.Result evaluate(
                ValidatedDataset dataset,
                SelectedSample selected,
                LocalSemanticEngine.JobDeadline deadline,
                LocalSemanticEngine.BeforeChat beforeChat) {
            return engine.evaluate(dataset, selected, dictionary, deadline, beforeChat);
        }
    }

    /** limitSec=1은 카탈로그 해시 매개변수일 뿐 실제 고정 원고의 제한시간 기본값이 아니다. */
    private static String policyCatalogueHash(String policyCode) {
        ObjectNode root =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("formatNo", 1)
                        .put("policyCode", policyCode)
                        .put("limitSecBinding", "FROM_FROZEN_BASIC_LIMIT_SEC");
        var variants = root.putArray("variants");
        for (int difficulty = 1; difficulty <= 5; difficulty++) {
            var variant = variants.addObject().put("difficulty", difficulty);
            variant.set("policy", FrozenSnapshotCodec.createPolicy(policyCode, difficulty, 1));
        }
        return SnapshotJson.hash(root);
    }

    /**
     * 실제 로드된 모든 참여 클래스의 동일 CodeSource·classloader를 확인한다. file: 디렉터리·표준 JAR와 Boot의 BOOT-INF/classes/
     * 원본만 지원한다. 원격·누락·심볼릭 링크·서로 다른 원본은 거절한다.
     */
    private static String installedCodeHash() {
        try {
            URL origin = origin(LocalSemanticEngine.class);
            ClassLoader loader = LocalSemanticEngine.class.getClassLoader();
            if (loader == null
                    || loader.getClass().getName().startsWith("org.springframework.boot.devtools."))
                throw unsupported();
            List<Class<?>> classes = new ArrayList<>();
            for (Class<?> participant : PARTICIPANTS) collect(participant, classes);
            for (Class<?> type : classes) {
                if (type.getClassLoader() != loader
                        || !origin.toExternalForm().equals(origin(type).toExternalForm()))
                    throw unsupported();
            }
            if ("jar".equals(origin.getProtocol()))
                return fingerprintBootJar(bootInstallationPath(origin, loader), classes);
            Path path = installationPath(origin);
            List<String> entries =
                    classes.stream()
                            .map(type -> type.getName().replace('.', '/') + ".class")
                            .toList();
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                for (String entry : entries)
                    if (!Files.isRegularFile(path.resolve(entry), LinkOption.NOFOLLOW_LINKS))
                        throw unsupported();
                return fingerprintDirectory(path);
            }
            return fingerprintJar(path, entries);
        } catch (Exception | LinkageError exception) {
            throw unsupported();
        }
    }

    private static URL origin(Class<?> type) {
        if (type.getProtectionDomain() == null
                || type.getProtectionDomain().getCodeSource() == null
                || type.getProtectionDomain().getCodeSource().getLocation() == null)
            throw unsupported();
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    private static void collect(Class<?> type, List<Class<?>> classes) {
        classes.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) collect(nested, classes);
    }

    /** 실제 CodeSource URL을 로컬 경로로 엄격히 해석하며 지원하지 않는 URL은 추측하지 않는다. */
    static Path installationPath(URL origin) {
        try {
            if (origin == null || !"file".equals(origin.getProtocol())) throw unsupported();
            Path path = Path.of(origin.toURI());
            requireLocal(path);
            return path;
        } catch (Exception exception) {
            throw unsupported();
        }
    }

    /** 실제 Boot 로더와 연결의 공개 API로만 외부 아카이브를 해석한다. 다른 URL 형식으로 재시도하지 않는다. */
    private static Path bootInstallationPath(URL origin, ClassLoader loader) {
        try {
            Class<?> bootLoader =
                    Class.forName(
                            "org.springframework.boot.loader.launch.LaunchedClassLoader",
                            false,
                            loader);
            if (loader.getClass() != bootLoader
                    || origin == null
                    || !"jar".equals(origin.getProtocol())
                    || origin.getQuery() != null
                    || origin.getRef() != null) throw unsupported();
            var connection = origin.openConnection();
            Class<?> bootConnection =
                    Class.forName(
                            "org.springframework.boot.loader.net.protocol.jar.JarUrlConnection",
                            false,
                            loader);
            if (connection.getClass() != bootConnection
                    || !(connection instanceof JarURLConnection jarConnection)) throw unsupported();
            String entry = jarConnection.getEntryName();
            URL nestedUrl = jarConnection.getJarFileURL();
            if ((entry != null && !entry.isEmpty())
                    || !"nested".equals(nestedUrl.getProtocol())
                    || nestedUrl.getQuery() != null
                    || nestedUrl.getRef() != null) throw unsupported();
            Class<?> nestedLocation =
                    Class.forName(
                            "org.springframework.boot.loader.net.protocol.nested.NestedLocation",
                            false,
                            loader);
            Object location =
                    nestedLocation.getMethod("fromUrl", URL.class).invoke(null, nestedUrl);
            if (!BOOT_CLASSES.equals(nestedLocation.getMethod("nestedEntryName").invoke(location)))
                throw unsupported();
            Object resolved = nestedLocation.getMethod("path").invoke(location);
            if (!(resolved instanceof Path path)) throw unsupported();
            requireLocal(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unsupported();
            return path;
        } catch (Exception | LinkageError exception) {
            throw unsupported();
        }
    }

    /** 전체 외부 JAR 지문과 실제 참여 클래스 자원의 바이트를 결속한다. 설치 불변·신뢰 호스트 가정은 그대로다. */
    private static String fingerprintBootJar(Path archive, List<Class<?>> classes) {
        try {
            List<String> entries =
                    classes.stream()
                            .map(type -> BOOT_CLASSES + type.getName().replace('.', '/') + ".class")
                            .toList();
            String hash = fingerprintJar(archive, entries);
            try (JarFile jar = new JarFile(archive.toFile(), true)) {
                for (Class<?> type : classes) {
                    String resource = type.getName().replace('.', '/') + ".class";
                    var entry = jar.getJarEntry(BOOT_CLASSES + resource);
                    try (InputStream stored = jar.getInputStream(entry);
                            InputStream loaded = type.getResourceAsStream("/" + resource)) {
                        if (loaded == null || !streamHash(stored).equals(streamHash(loaded)))
                            throw unsupported();
                    }
                }
            }
            return hash;
        } catch (Exception | LinkageError exception) {
            throw unsupported();
        }
    }

    /** 참여 클래스 자원을 메모리에 통째로 보관하지 않고 SHA-256으로 비교한다. */
    private static String streamHash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** 합성 아티팩트 시험도 실제 파일을 쓴다. 설치 검증 증거로 대체할 경로는 없다. */
    static String fingerprintDirectory(Path root) {
        try {
            requireLocal(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw unsupported();
            List<Path> files = new ArrayList<>();
            try (var walk = Files.walk(root)) {
                for (Path path : walk.toList()) {
                    if (Files.isSymbolicLink(path)) throw unsupported();
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unsupported();
                    files.add(path);
                }
            }
            files.sort(
                    (left, right) ->
                            CommonUtil.compareCodePoints(
                                    relative(root, left), relative(root, right)));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            frame(digest, "SICHA-INSTALLED-DIRECTORY-v1".getBytes(StandardCharsets.UTF_8));
            for (Path file : files) {
                frame(digest, relative(root, file).getBytes(StandardCharsets.UTF_8));
                long size = Files.size(file);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
                long count = feed(digest, file);
                if (count != size) throw unsupported();
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw unsupported();
        }
    }

    /** 전체 JAR 바이트를 해시하며 참여 클래스 항목의 존재·읽기 가능성도 검사한다. */
    static String fingerprintJar(Path archive, List<String> requiredEntries) {
        try {
            requireLocal(archive);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw unsupported();
            try (JarFile jar = new JarFile(archive.toFile(), true)) {
                var seen = new java.util.HashSet<String>();
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!seen.add(entry.getName())) throw unsupported();
                }
                for (String name : requiredEntries) {
                    var entry = jar.getJarEntry(name);
                    if (entry == null || entry.isDirectory()) throw unsupported();
                    try (InputStream input = jar.getInputStream(entry)) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            feed(digest, archive);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw unsupported();
        }
    }

    private static void requireLocal(Path path) throws java.io.IOException {
        if (!path.isAbsolute()
                || Files.isSymbolicLink(path)
                || !path.toRealPath().equals(path.normalize())) throw unsupported();
    }

    private static String relative(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.isAbsolute() || relative.startsWith("..")) throw unsupported();
        return relative.toString().replace(java.io.File.separatorChar, '/');
    }

    private static void frame(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    private static long feed(MessageDigest digest, Path path) throws java.io.IOException {
        long count = 0;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
                count += length;
            }
        }
        return count;
    }

    private static boolean label(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,100}");
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(INVALID);
    }

    private static IllegalStateException unsupported() {
        return new IllegalStateException(UNSUPPORTED);
    }
}
