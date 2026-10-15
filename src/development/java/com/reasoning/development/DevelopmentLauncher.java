package com.reasoning.development;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.reasoning.ReasoningApplication;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 일반 bootRun에만 포함되며 기존 DB·배포용 JAR과 분리된 지속형 개발 환경을 시작한다. */
public final class DevelopmentLauncher {
    private static final String DATABASE = "sicha_development";
    private static final String CONTAINER = "sicha-development";
    private static final String VOLUME = "sicha-development-data";
    private static final String IMAGE =
            "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1";
    private static final String LABEL = "com.reasoning.sicha-development";
    private static final URI ORIGIN = URI.create("https://localhost:19443");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DevelopmentLauncher() {}

    /**
     * 소유한 별도 개발 DB와 실제 인증 서비스를 시작하며 데이터·키를 자동 초기화하지 않는다.
     *
     * @param args 추가 설정 인자는 허용하지 않으며 빈 배열이어야 한다
     * @throws Exception 개발 경계·Docker·TLS·응용 시작을 확인할 수 없는 경우
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("개발 bootRun에는 별도 응용 인자를 전달하지 마세요.");
        Path home =
                Path.of(System.getProperty("user.home"), ".local", "share", "sicha-development");
        prepareDirectory(home);
        var state = new DevelopmentState(home, ORIGIN);
        Path lockPath = home.resolve("run.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) privateText(lockPath);
        FileChannel channel =
                FileChannel.open(
                        lockPath,
                        Set.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            throw new IllegalStateException("이 개발 환경의 bootRun이 이미 실행 중입니다.");
        }
        GreenMail smtp =
                new GreenMail(new ServerSetup(19025, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close =
                () -> {
                    if (closed.compareAndSet(false, true)) {
                        smtp.stop();
                        try {
                            lock.release();
                            channel.close();
                        } catch (IOException ignored) {
                            /* 잠금은 프로세스 종료에도 해제된다. */
                        }
                    }
                };
        try {
            Properties secrets = material(home);
            int port = database(home, secrets);
            smtp.start();
            var application = new SpringApplication(ReasoningApplication.class);
            application.addInitializers(
                    context -> {
                        context.getBeanFactory().registerSingleton("developmentState", state);
                        context.getBeanFactory().registerSingleton("developmentSmtp", smtp);
                    });
            application.addListeners(
                    (ApplicationListener<ContextClosedEvent>) event -> close.run());
            application.run(settings(home, secrets, port, smtp.getSmtp().getPort()));
            System.out.println("개발 계정 선택: " + ORIGIN + "/__development");
            System.out.println("별도 개발 DB에 저장됩니다. 재시작 시 데이터와 계정은 유지됩니다. 운영용 실행이 아닙니다.");
        } catch (Exception failure) {
            close.run();
            throw failure;
        }
    }

    /** 현재 사용자 소유의 실제 디렉터리만 준비하고 기존 권한·파일을 덮어쓰지 않는다. */
    private static void prepareDirectory(Path directory) throws IOException {
        Path cursor = directory.getRoot();
        for (Path part : directory) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor))
                throw new IllegalStateException("개발 경로에 심볼릭 링크를 사용할 수 없습니다.");
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(
                        cursor,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            }
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("개발 디렉터리 경로가 파일에 점유되어 있습니다.");
        }
    }

    /** 생성한 비밀은 소유자 전용 신규 파일에만 쓰며 기존 자료는 회전·재생성하지 않는다. */
    private static Properties material(Path home) throws Exception {
        Path file = home.resolve("launcher.properties");
        Properties values = new Properties();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            values.load(new java.io.StringReader(privateText(file)));
            if (!values.stringPropertyNames()
                            .equals(
                                    Set.of(
                                            "format",
                                            "owner",
                                            "databasePassword",
                                            "tlsPassword",
                                            "stage"))
                    || !"1".equals(values.getProperty("format"))
                    || !values.getProperty("owner", "").matches("[a-f0-9]{32}")) {
                throw new IllegalStateException("기존 개발 설정의 형식을 확인할 수 없습니다.");
            }
        } else {
            try (var children = Files.list(home)) {
                if (children.anyMatch(path -> !path.getFileName().toString().equals("run.lock"))) {
                    throw new IllegalStateException("기존 개발 자료가 있어 새 설정으로 대체하지 않습니다.");
                }
            }
            values.setProperty("format", "1");
            values.setProperty("owner", java.util.UUID.randomUUID().toString().replace("-", ""));
            values.setProperty("databasePassword", random());
            values.setProperty("tlsPassword", random());
            values.setProperty("stage", "PREPARED");
            writeNew(file, properties(values));
        }
        for (String key : List.of("databasePassword", "tlsPassword")) {
            if (!values.getProperty(key, "").matches("[A-Za-z0-9_-]{43}"))
                throw new IllegalStateException("개발 비밀 설정 형식이 올바르지 않습니다.");
        }
        boolean prepared = "PREPARED".equals(values.getProperty("stage"));
        if (!prepared && !"CREATED".equals(values.getProperty("stage")))
            throw new IllegalStateException("개발 준비 단계가 올바르지 않습니다.");
        for (String key : List.of("crypto", "search", "limit")) {
            Path target = home.resolve(key + ".secret");
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && prepared)
                writeNew(target, random() + "\n");
            if (!privateText(target).trim().matches("[A-Za-z0-9_-]{43}"))
                throw new IllegalStateException("기존 개발 키를 확인할 수 없습니다.");
        }
        Path corpus = home.resolve("breached.txt");
        if (!Files.exists(corpus, LinkOption.NOFOLLOW_LINKS) && prepared) {
            String digest =
                    java.util.HexFormat.of()
                            .withUpperCase()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-1")
                                            .digest(
                                                    "Development-rejected-password"
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8)));
            writeNew(corpus, digest + "\n");
        }
        if (!privateText(corpus).trim().matches("[A-F0-9]{40}"))
            throw new IllegalStateException("개발 비밀번호 검사 자료가 올바르지 않습니다.");
        Path env = home.resolve("database.env");
        String expected =
                "POSTGRES_USER="
                        + DATABASE
                        + "\nPOSTGRES_DB="
                        + DATABASE
                        + "\nPOSTGRES_PASSWORD="
                        + values.getProperty("databasePassword")
                        + "\n";
        if (!Files.exists(env, LinkOption.NOFOLLOW_LINKS) && prepared) writeNew(env, expected);
        if (!expected.equals(privateText(env)))
            throw new IllegalStateException("개발 DB 비밀의 결속을 확인할 수 없습니다.");
        Path tls = home.resolve("https.p12");
        if (!Files.exists(tls, LinkOption.NOFOLLOW_LINKS) && prepared) {
            var builder =
                    new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                            "-genkeypair",
                            "-alias",
                            "sicha-development",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "365",
                            "-dname",
                            "CN=localhost",
                            "-ext",
                            "SAN=dns:localhost,ip:127.0.0.1",
                            "-storetype",
                            "PKCS12",
                            "-keystore",
                            tls.toString(),
                            "-storepass:env",
                            "SICHA_DEVELOPMENT_TLS_PASSWORD");
            builder.environment()
                    .put("SICHA_DEVELOPMENT_TLS_PASSWORD", values.getProperty("tlsPassword"));
            // 키 도구의 출력은 비밀을 포함할 수 있으므로 외부 로그로 전달하지 않는다.
            execute(builder, Duration.ofSeconds(45));
            Files.setPosixFilePermissions(tls, PosixFilePermissions.fromString("rw-------"));
        }
        privateIdentity(tls);
        return values;
    }

    /** 소유 라벨·고정 이미지·볼륨·루프백 포트를 확인한 지속형 개발 DB만 재사용한다. */
    private static int database(Path home, Properties values) throws Exception {
        requireLocalDocker();
        String owner = values.getProperty("owner");
        String found =
                docker("ps", "-a", "--filter", "name=^" + CONTAINER + "$", "--format", "{{.ID}}")
                        .trim();
        if (found.isEmpty()) {
            if (!"PREPARED".equals(values.getProperty("stage")))
                throw new IllegalStateException("기존 개발 DB 컨테이너가 없습니다. 자동 재생성하거나 초기화하지 않습니다.");
            String volume =
                    docker(
                                    "volume",
                                    "ls",
                                    "--filter",
                                    "name=^" + VOLUME + "$",
                                    "--format",
                                    "{{.Name}}")
                            .trim();
            if (volume.isEmpty())
                docker("volume", "create", "--label", LABEL + "=" + owner, VOLUME);
            else
                verifyLabel(
                        docker("volume", "inspect", "--format", "{{json .Labels}}", VOLUME), owner);
            docker(
                    "run",
                    "--detach",
                    "--name",
                    CONTAINER,
                    "--label",
                    LABEL + "=" + owner,
                    "--publish",
                    "127.0.0.1::5432",
                    "--mount",
                    "type=volume,src=" + VOLUME + ",dst=/var/lib/postgresql/data",
                    "--env-file",
                    home.resolve("database.env").toString(),
                    IMAGE);
        } else if (!found.matches("[a-f0-9]{12,64}")) {
            throw new IllegalStateException("개발 컨테이너 이름이 다른 자료에 점유되어 있습니다.");
        }
        verifyLabel(docker("inspect", "--format", "{{json .Config.Labels}}", CONTAINER), owner);
        if (!IMAGE.equals(docker("inspect", "--format", "{{.Config.Image}}", CONTAINER).trim()))
            throw new IllegalStateException("개발 DB 이미지가 지정한 이미지와 다릅니다.");
        JsonNode mounts =
                JSON.readTree(docker("inspect", "--format", "{{json .Mounts}}", CONTAINER));
        boolean mounted = false;
        for (JsonNode mount : mounts) {
            if ("/var/lib/postgresql/data".equals(mount.path("Destination").asText())) {
                mounted =
                        "volume".equals(mount.path("Type").asText())
                                && VOLUME.equals(mount.path("Name").asText());
            }
        }
        if (!mounted) throw new IllegalStateException("개발 DB의 지속형 볼륨이 일치하지 않습니다.");
        verifyLabel(docker("volume", "inspect", "--format", "{{json .Labels}}", VOLUME), owner);
        validateDockerBinding(
                JSON.readTree(docker("inspect", "--format", "{{json .HostConfig}}", CONTAINER)));
        String status = docker("inspect", "--format", "{{.State.Status}}", CONTAINER).trim();
        if (Set.of("created", "exited").contains(status)) docker("start", CONTAINER);
        else if (!"running".equals(status))
            throw new IllegalStateException("개발 DB를 현재 상태에서 시작할 수 없습니다.");
        String binding = docker("port", CONTAINER, "5432/tcp").trim();
        if (!binding.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}"))
            throw new IllegalStateException("개발 DB 포트가 루프백 전용이 아닙니다.");
        int port = Integer.parseInt(binding.substring(binding.lastIndexOf(':') + 1));
        if (port > 65535) throw new IllegalStateException("개발 DB 포트가 올바르지 않습니다.");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                docker(
                        "exec",
                        CONTAINER,
                        "pg_isready",
                        "-h",
                        "127.0.0.1",
                        "-U",
                        DATABASE,
                        "-d",
                        DATABASE);
                break;
            } catch (IllegalStateException unavailable) {
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("개발 PostgreSQL 준비 시간이 초과되었습니다.");
                Thread.sleep(500);
            }
        }
        if (!"CREATED".equals(values.getProperty("stage"))) {
            values.setProperty("stage", "CREATED");
            publishCompletedState(home, values);
        }
        return port;
    }

    /** Docker 환경·현재 context도 로컬 Unix 소켓인지 변경 작업 전에 확인한다. */
    private static void requireLocalDocker() throws Exception {
        String context = System.getenv("DOCKER_CONTEXT");
        String host = System.getenv("DOCKER_HOST");
        if (context != null && !context.isBlank()) {
            host =
                    JSON.readTree(
                                    docker(
                                            "context",
                                            "inspect",
                                            "--format",
                                            "{{json .Endpoints.docker.Host}}",
                                            context))
                            .asText();
        } else if (host == null || host.isBlank()) {
            host =
                    JSON.readTree(
                                    docker(
                                            "context",
                                            "inspect",
                                            "--format",
                                            "{{json .Endpoints.docker.Host}}"))
                            .asText();
        }
        validateDockerEndpoint(host);
    }

    /**
     * 원격 context에서 승인 밖의 자료를 만들지 않도록 로컬 Unix 소켓만 받는다.
     *
     * @param endpoint Docker CLI가 실제 선택한 비밀 없는 전송 주소
     */
    static void validateDockerEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"unix".equals(uri.getScheme())
                    || uri.getRawAuthority() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPath() == null
                    || !uri.getPath().startsWith("/")) {
                throw new IllegalStateException("개발 DB는 로컬 Docker Unix 소켓만 사용할 수 있습니다.");
            }
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("개발 Docker 전송 주소를 확인할 수 없습니다.");
        }
    }

    /**
     * 중지된 컨테이너도 먼저 검사하여 잘못된 공개 바인딩으로 잠시라도 시작하지 않는다.
     *
     * @param config 실제 Docker inspect의 HostConfig 객체
     */
    static void validateDockerBinding(JsonNode config) {
        JsonNode bindings = config.path("PortBindings");
        JsonNode endpoints = bindings.path("5432/tcp");
        if (!Set.of("default", "bridge").contains(config.path("NetworkMode").asText())
                || !bindings.isObject()
                || bindings.size() != 1
                || !endpoints.isArray()
                || endpoints.size() != 1
                || !"127.0.0.1".equals(endpoints.get(0).path("HostIp").asText())
                || !endpoints.get(0).path("HostPort").isTextual()) {
            throw new IllegalStateException("개발 DB의 시작 전 루프백 바인딩이 올바르지 않습니다.");
        }
        String port = endpoints.get(0).path("HostPort").asText();
        if (!port.isEmpty()
                && (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535)) {
            throw new IllegalStateException("개발 DB의 시작 전 포트가 올바르지 않습니다.");
        }
    }

    /**
     * 원래 비밀의 독립 사본을 보존하고 완성된 파일만 원자 교체한다. 중단돼도 원문을 잘라 쓰지 않는다.
     *
     * @param home 실행 잠금으로 보호된 소유자 전용 개발 디렉터리
     * @param values 기존 비밀은 그대로이고 완료 단계만 변경한 설정
     * @throws IOException 원문 보존·새 파일 동기화·원자 교체를 확인할 수 없는 경우
     */
    static void publishCompletedState(Path home, Properties values) throws IOException {
        Path file = home.resolve("launcher.properties");
        privateIdentity(file);
        byte[] before = Files.readAllBytes(file);
        Path backup = home.resolve("launcher.prepared.properties");
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            writeNew(backup, new String(before, java.nio.charset.StandardCharsets.UTF_8));
        } else {
            privateIdentity(backup);
            if (!java.util.Arrays.equals(Files.readAllBytes(backup), before)) {
                throw new IOException("개발 원문 보존 사본이 현재 설정과 다릅니다.");
            }
        }
        Path temporary =
                Files.createTempFile(
                        home,
                        ".completed-",
                        ".properties",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel output =
                    FileChannel.open(
                            temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer =
                        java.nio.ByteBuffer.wrap(
                                properties(values)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            privateIdentity(file);
            if (!java.util.Arrays.equals(before, Files.readAllBytes(file))) {
                throw new IOException("개발 설정이 동시에 변경돼 완료 기록을 교체하지 않습니다.");
            }
            Files.move(
                    temporary,
                    file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 실제 소유 라벨이 다른 Docker 객체는 이름이 같아도 사용하지 않는다. */
    private static void verifyLabel(String json, String owner) throws IOException {
        if (!owner.equals(JSON.readTree(json).path(LABEL).asText()))
            throw new IllegalStateException("기존 Docker 자료의 개발 소유권이 일치하지 않습니다.");
    }

    /** 환경 변수보다 우선하는 고정 루프백 설정을 응용에 주입한다. */
    private static String[] settings(
            Path home, Properties secrets, int databasePort, int smtpPort) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put(
                "spring.datasource.url",
                "jdbc:postgresql://127.0.0.1:" + databasePort + "/" + DATABASE);
        values.put("spring.datasource.username", DATABASE);
        values.put("spring.datasource.password", secrets.getProperty("databasePassword"));
        for (String key : List.of("crypto", "search", "limit"))
            values.put("app.auth." + key + "-key-file", home.resolve(key + ".secret").toString());
        values.put("app.auth.breached-hashes-file", home.resolve("breached.txt").toString());
        values.put("server.address", "127.0.0.1");
        values.put("server.port", Integer.toString(ORIGIN.getPort()));
        values.put("server.ssl.enabled", "true");
        values.put("server.ssl.key-store", home.resolve("https.p12").toUri().toString());
        values.put("server.ssl.key-store-type", "PKCS12");
        values.put("server.ssl.key-store-password", secrets.getProperty("tlsPassword"));
        values.put("server.forward-headers-strategy", "none");
        values.put("spring.mail.host", "127.0.0.1");
        values.put("spring.mail.port", Integer.toString(smtpPort));
        values.put("app.development.enabled", "true");
        values.put("app.member-web.enabled", "true");
        values.put("app.member-web.origin", ORIGIN.toString());
        values.put("app.member-auth.collection-enabled", "true");
        values.put("app.member-auth.env-code", "SYNTHETIC_DEVELOPMENT");
        values.put("app.member-auth.mail-from", "development@example.invalid");
        values.put("app.member-auth.cleanup-enabled", "false");
        values.put("app.game.expiry-enabled", "false");
        return values.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    /** 현재 사용자 소유·단일 링크·0600의 일반 파일만 비밀 자료로 받는다. */
    private static void privateIdentity(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS)
                        .getName()
                        .equals(System.getProperty("user.name"))
                || !Files.getPosixFilePermissions(file)
                        .equals(PosixFilePermissions.fromString("rw-------"))
                || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                                .intValue()
                        != 1) {
            throw new IllegalStateException("개발 비밀 파일의 소유권·권한이 올바르지 않습니다.");
        }
    }

    /** 크기가 제한된 비밀 텍스트만 읽고 내용을 로그로 전달하지 않는다. */
    private static String privateText(Path file) throws IOException {
        privateIdentity(file);
        if (Files.size(file) > 65536) throw new IllegalStateException("개발 비밀 파일이 허용 크기를 초과했습니다.");
        return Files.readString(file);
    }

    /** 기존 파일을 대체하지 않는 소유자 전용 신규 자료를 기록한다. */
    private static void writeNew(Path file, String text) throws IOException {
        try (FileChannel output =
                FileChannel.open(
                        file,
                        Set.of(
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")))) {
            var buffer =
                    java.nio.ByteBuffer.wrap(
                            text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    /** 로그나 명령행 인자로 노출하지 않을 256비트 개발 비밀을 생성한다. */
    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 소유자 전용 설정에 기록할 값을 직렬화한다. */
    private static String properties(Properties values) throws IOException {
        var output = new java.io.StringWriter();
        values.store(output, "SICHA isolated development only");
        return output.toString();
    }

    /** Docker 실패의 stdout·stderr·비밀 환경은 공개 예외에 싣지 않는다. */
    private static String docker(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        return execute(new ProcessBuilder(command), Duration.ofSeconds(90));
    }

    /** 출력 파이프를 파일로 격리하여 자식 프로세스가 출력 대기로 멈추지 않게 한다. */
    private static String execute(ProcessBuilder builder, Duration timeout) throws Exception {
        Path output =
                Files.createTempFile(
                        "sicha-development-command-",
                        ".out",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Path error =
                Files.createTempFile(
                        "sicha-development-command-",
                        ".err",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Process child = null;
        try {
            child = builder.redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("개발 준비 명령의 제한 시간이 초과되었습니다.");
            }
            if (child.exitValue() != 0)
                throw new IllegalStateException("개발 준비 명령이 실패했습니다. Docker·JDK 설치와 실행 상태를 확인하세요.");
            if (Files.size(output) > 1048576)
                throw new IllegalStateException("개발 준비 명령의 출력 한도를 초과했습니다.");
            return Files.readString(output);
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            Files.deleteIfExists(output);
            Files.deleteIfExists(error);
        }
    }
}
