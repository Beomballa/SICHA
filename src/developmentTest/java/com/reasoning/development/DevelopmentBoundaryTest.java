package com.reasoning.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.auth.service.AuthException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.Consumer;

/** 실제 서블릿 요청 값으로 개발 connector와 변경 요청의 독립 경계를 검사한다. */
class DevelopmentBoundaryTest {
    private static final URI ORIGIN = URI.create("https://localhost:29443");
    @TempDir Path temporary;

    private DevelopmentState state() throws Exception {
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"));
        return new DevelopmentState(temporary.toRealPath(), ORIGIN);
    }

    private MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/__development");
        request.setSecure(true);
        request.setScheme("https");
        request.setServerName("localhost");
        request.setServerPort(ORIGIN.getPort());
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Test
    void rejectsActualConnectorChangesEvenWithTrustedLookingForwardingHeaders() throws Exception {
        var state = state();
        List<Consumer<MockHttpServletRequest>> changes =
                List.of(
                        request -> {
                            request.setSecure(false);
                            request.setScheme("http");
                        },
                        request -> request.setScheme("http"),
                        request -> request.setServerName("127.0.0.1"),
                        request -> request.setServerName("foreign.invalid"),
                        request -> request.setServerPort(ORIGIN.getPort() + 1),
                        request -> request.setRemoteAddr("192.0.2.8"),
                        request -> request.setRemoteAddr("::1"));
        for (var change : changes) {
            var request = request("POST");
            request.addHeader("Origin", ORIGIN.toString());
            request.addHeader("Forwarded", "for=127.0.0.1;proto=https;host=localhost:29443");
            request.addHeader("X-Forwarded-For", "127.0.0.1");
            change.accept(request);
            assertThatThrownBy(() -> state.requireConnector(request))
                    .isInstanceOf(AuthException.class);
        }
    }

    @Test
    void rejectsAmbiguousOriginsFetchSitesQueriesAndMethods() throws Exception {
        var state = state();
        List<Consumer<MockHttpServletRequest>> changes =
                List.of(
                        request -> request.removeHeader("Origin"),
                        request -> request.addHeader("Origin", ORIGIN.toString()),
                        request -> request.addHeader("Origin", "https://foreign.invalid"),
                        request -> request.addHeader("Sec-Fetch-Site", "cross-site"),
                        request -> {
                            request.addHeader("Sec-Fetch-Site", "same-origin");
                            request.addHeader("Sec-Fetch-Site", "same-origin");
                        },
                        request -> request.setQueryString(""),
                        request -> request.setQueryString("account=a"),
                        request -> request.setMethod("GET"),
                        request -> request.setMethod("PUT"),
                        request -> request.setMethod("OPTIONS"));
        for (var change : changes) {
            var request = request("POST");
            request.addHeader("Origin", ORIGIN.toString());
            change.accept(request);
            assertThatThrownBy(() -> state.requireRequest(request, true))
                    .isInstanceOf(AuthException.class);
        }
        for (String method : List.of("GET", "HEAD")) {
            var read = request(method);
            assertThatCode(() -> state.requireRequest(read, false)).doesNotThrowAnyException();
            read.addHeader("Origin", "null");
            assertThatThrownBy(() -> state.requireRequest(read, false))
                    .isInstanceOf(AuthException.class);
        }
        assertThatThrownBy(() -> state.requireRequest(request("POST"), false))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void connectorDoesNotReplaceNormalAdministratorPostOrQueryGrammar() throws Exception {
        var state = state();
        var request = request("POST");
        request.setRequestURI("/admin/api/stories");
        request.setQueryString("size=20&afterId=1");
        assertThatCode(() -> state.requireConnector(request)).doesNotThrowAnyException();
        assertThatThrownBy(() -> state.requireRequest(request, true))
                .isInstanceOf(AuthException.class);
    }

    /** 주소 직접 입력·북마크는 열되 같은 none 출처의 변경 요청은 허용하지 않는다. */
    @Test
    void directBrowserNavigationDoesNotWeakenMutationAdmission() throws Exception {
        var state = state();
        for (String method : List.of("GET", "HEAD")) {
            var read = request(method);
            read.addHeader("Sec-Fetch-Site", "none");
            assertThatCode(() -> state.requireRequest(read, false)).doesNotThrowAnyException();
        }
        var mutation = request("POST");
        mutation.addHeader("Origin", ORIGIN.toString());
        mutation.addHeader("Sec-Fetch-Site", "none");
        assertThatThrownBy(() -> state.requireRequest(mutation, true))
                .isInstanceOf(AuthException.class);
        var foreign = request("GET");
        foreign.addHeader("Sec-Fetch-Site", "cross-site");
        assertThatThrownBy(() -> state.requireRequest(foreign, false))
                .isInstanceOf(AuthException.class);
    }

    /** 원자 공개 전 임시 파일이 남아도 원래 비밀과 독립 보존 사본으로 완료를 재개한다. */
    @Test
    void interruptedTemporaryPublicationPreservesOriginalSecretsAndCanComplete() throws Exception {
        var home = state().home();
        var values = launcherValues();
        Path original = launcherFile(home, values);
        byte[] before = Files.readAllBytes(original);
        Path interrupted = home.resolve(".completed-interrupted.properties");
        Files.createFile(
                interrupted,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(interrupted, "unfinished private temporary state");
        values.setProperty("stage", "CREATED");
        DevelopmentLauncher.publishCompletedState(home, values);
        assertThat(Files.readAllBytes(home.resolve("launcher.prepared.properties")))
                .isEqualTo(before);
        assertThat(Files.readString(interrupted)).isEqualTo("unfinished private temporary state");
        var completed = new java.util.Properties();
        completed.load(new java.io.StringReader(Files.readString(original)));
        assertThat(completed).isEqualTo(values);
        assertThat(Files.getPosixFilePermissions(original))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /** 다른 파일을 가리키는 보존 경로에서는 원문을 건드리지 않고 실패하며 교정 후 재개한다. */
    @Test
    void unsafeBackupNeverTruncatesSecretsAndRecoveryKeepsTheirValues() throws Exception {
        var home = state().home();
        var values = launcherValues();
        Path original = launcherFile(home, values);
        byte[] before = Files.readAllBytes(original);
        Path backup = home.resolve("launcher.prepared.properties");
        Files.createSymbolicLink(backup, original);
        values.setProperty("stage", "CREATED");
        assertThatThrownBy(() -> DevelopmentLauncher.publishCompletedState(home, values))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(original)).isEqualTo(before);
        Files.delete(backup);
        DevelopmentLauncher.publishCompletedState(home, values);
        assertThat(Files.readAllBytes(backup)).isEqualTo(before);
        var completed = new java.util.Properties();
        completed.load(new java.io.StringReader(Files.readString(original)));
        assertThat(completed).isEqualTo(values);
    }

    /** 시험 소유 임시 디렉터리에만 새 설정을 기록한다. */
    private static Path launcherFile(Path home, java.util.Properties values) throws Exception {
        Path file =
                Files.createFile(
                        home.resolve("launcher.properties"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try (var writer = Files.newBufferedWriter(file)) {
            values.store(writer, "Synthetic fixture only");
        }
        return file;
    }

    /** 실제 개발 비밀과 무관한 임의 시험 값을 만든다. */
    private static java.util.Properties launcherValues() {
        var values = new java.util.Properties();
        values.setProperty("format", "1");
        values.setProperty("owner", java.util.UUID.randomUUID().toString().replace("-", ""));
        values.setProperty("databasePassword", java.util.UUID.randomUUID().toString());
        values.setProperty("tlsPassword", java.util.UUID.randomUUID().toString());
        values.setProperty("stage", "PREPARED");
        return values;
    }

    /** 원격 context의 Unix 아닌 전송과 소켓 URI의 쿼리·authority를 변경 전 거절한다. */
    @Test
    void refusesRemoteDockerEndpointsBeforeProvisioning() {
        assertThatCode(
                        () ->
                                DevelopmentLauncher.validateDockerEndpoint(
                                        "unix:///var/run/docker.sock"))
                .doesNotThrowAnyException();
        for (String endpoint :
                List.of(
                        "tcp://127.0.0.1:2375",
                        "ssh://host.invalid",
                        "https://host.invalid",
                        "unix://host.invalid/run/docker.sock",
                        "unix:///run/docker.sock?x=1",
                        "unix:///run/docker.sock#x",
                        "")) {
            assertThatThrownBy(() -> DevelopmentLauncher.validateDockerEndpoint(endpoint))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 기존 컨테이너를 시작하기 전에 호스트 네트워크·공개 IP·추가 포트·범위 밖 포트를 거절한다. */
    @Test
    void rejectsUnsafeStoredDockerBindingsBeforeStart() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var valid =
                json.readTree(
                        "{\"NetworkMode\":\"default\",\"PortBindings\":{\"5432/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"\"}]}}");
        assertThatCode(() -> DevelopmentLauncher.validateDockerBinding(valid))
                .doesNotThrowAnyException();
        for (String invalid :
                List.of(
                        "{\"NetworkMode\":\"host\",\"PortBindings\":{\"5432/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"5432\"}]}}",
                        "{\"NetworkMode\":\"default\",\"PortBindings\":{\"5432/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"5432\"}]}}",
                        "{\"NetworkMode\":\"default\",\"PortBindings\":{\"5432/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"65536\"}]}}",
                        "{\"NetworkMode\":\"default\",\"PortBindings\":{}}")) {
            var config = json.readTree(invalid);
            assertThatThrownBy(() -> DevelopmentLauncher.validateDockerBinding(config))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsNonprivateHomesLinksAndNoncanonicalOrigins() throws Exception {
        state();
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(() -> new DevelopmentState(temporary, ORIGIN))
                .isInstanceOf(IllegalStateException.class);
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"));
        Path link = temporary.resolve("link");
        Files.createSymbolicLink(link, temporary);
        assertThatThrownBy(() -> new DevelopmentState(link, ORIGIN))
                .isInstanceOf(IllegalStateException.class);
        for (String origin :
                List.of(
                        "http://localhost:29443",
                        "https://127.0.0.1:29443",
                        "https://localhost",
                        "https://localhost:29443/",
                        "https://localhost:29443?x=1",
                        "https://user@localhost:29443",
                        "https://localhost:29443#x")) {
            assertThatThrownBy(() -> new DevelopmentState(temporary, URI.create(origin)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
