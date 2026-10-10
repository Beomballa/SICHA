package com.reasoning.common.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.reasoning.common.grading.config.GradeRemoteOnceJournal;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal.Scope;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker;
import com.reasoning.common.grading.service.GradeResultValidator;
import com.reasoning.common.story.service.PlaytestReportFixture;
import com.reasoning.common.story.service.PlaytestReportFixture.TestAssembly;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 실제 컴파일된 Flutter·폐기 PostgreSQL·합성 SMTP/정책 근거·loopback TLS만 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({
    LocalMemberAuthIT.Installation.class,
    MemberWebBrowserIT.WorkerInstallation.class,
    TestAssembly.class
})
public class MemberWebBrowserIT extends PlaytestReportFixture {
    private static final URI ORIGIN = origin();
    private static final Path ASSETS = assets();
    private static final String STORE_PASSWORD = UUID.randomUUID().toString();
    private static final Path STORE = keyStore();

    @TestConfiguration(proxyBeanMethods = false)
    static class WorkerInstallation {
        @Bean
        WorkerController fixtureWorker() {
            return new WorkerController();
        }
    }

    /** 제품 자산과 component scan에는 없는 시험 전용 제어 워커다. */
    @TestComponent
    @RestController
    static class WorkerController {
        @GetMapping(value = "/player/test-worker.js", produces = "application/javascript")
        String worker() {
            return "self.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));"
                    + "self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));";
        }
    }

    private static URI origin() {
        String value = System.getenv("PLAYER_WEB_TEST_ORIGIN");
        if (value == null) throw new IllegalStateException("PLAYER_WEB_TEST_ORIGIN is required");
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme())
                || !"localhost".equals(uri.getHost())
                || uri.getPort() < 1024
                || uri.getPort() > 65535
                || uri.getPort() == 18443
                || !value.equals("https://localhost:" + uri.getPort())) {
            throw new IllegalStateException(
                    "A canonical nonproduction localhost HTTPS origin is required");
        }
        return uri;
    }

    private static Path assets() {
        String value = System.getenv("PLAYER_WEB_TEST_ASSETS");
        if (value == null) throw new IllegalStateException("PLAYER_WEB_TEST_ASSETS is required");
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute())
                throw new IllegalStateException("Absolute Flutter assets path required");
            path = path.toRealPath();
            if (!Files.isRegularFile(path.resolve("index.html"))
                    || !Files.isRegularFile(path.resolve("main.dart.js"))
                    || !Files.isRegularFile(path.resolve("flutter_bootstrap.js"))) {
                throw new IllegalStateException("Actual compiled Flutter assets are required");
            }
            return path;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Flutter assets unavailable", failure);
        }
    }

    private static Path keyStore() {
        try {
            Path directory =
                    Files.createTempDirectory(
                            "player-browser-tls-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            Path store = directory.resolve("test.p12");
            ProcessBuilder command =
                    new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                            "-genkeypair",
                            "-alias",
                            "test",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "1",
                            "-dname",
                            "CN=localhost",
                            "-ext",
                            "SAN=dns:localhost,ip:127.0.0.1",
                            "-storetype",
                            "PKCS12",
                            "-keystore",
                            store.toString(),
                            "-storepass:env",
                            "PLAYER_TEST_STORE_PASSWORD");
            command.environment().put("PLAYER_TEST_STORE_PASSWORD", STORE_PASSWORD);
            Process process = command.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Test certificate deadline exceeded");
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException("Test certificate creation failed");
            Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-------"));
            store.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            return store;
        } catch (Exception failure) {
            throw new IllegalStateException("Disposable TLS setup failed", failure);
        }
    }

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry properties) {
        properties.add("server.port", ORIGIN::getPort);
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> STORE.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> STORE_PASSWORD);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("app.member-web.enabled", () -> true);
        properties.add("app.member-web.origin", ORIGIN::toString);
        properties.add("app.member-web.assets-directory", ASSETS::toString);
    }

    /** 전용 Gradle 메서드 필터는 상속된 LocalMemberAuthIT 시험을 제외해야 한다. */
    @Test
    void webAuthenticationLifecycle() throws Exception {
        String email = "web-browser-" + UUID.randomUUID() + "@example.invalid";
        var issued = account(email);
        long member =
                db.queryForObject(
                        "SELECT member_id FROM member_session WHERE session_key=?",
                        Long.class,
                        UUID.fromString(issued.path("sessionKey").asText()));
        Path receipt = Files.createTempFile("player-browser-result-", ".json");
        Path errors = Files.createTempFile("player-browser-error-", ".txt");
        try {
            Process browser =
                    new ProcessBuilder("node", "src/test/browser/player-web-auth.mjs")
                            .redirectOutput(receipt.toFile())
                            .redirectError(errors.toFile())
                            .start();
            try (var input = browser.getOutputStream()) {
                JSON.writeValue(
                        input,
                        Map.of(
                                "origin",
                                ORIGIN.toString(),
                                "email",
                                email,
                                "password",
                                "Synthetic-member-password-987!",
                                "nickname",
                                "합성회원"));
            }
            if (!browser.waitFor(150, TimeUnit.SECONDS)) {
                browser.destroyForcibly();
                throw new AssertionError("Compiled Flutter browser deadline exceeded");
            }
            // Node의 고정 단계·횟수·비밀 없는 진단만 전달하며 자격 원문은 출력하지 않는다.
            assertThat(browser.exitValue())
                    .withFailMessage("Browser regression failed: %s", Files.readString(errors))
                    .isZero();
            var result = JSON.readTree(receipt.toFile());
            assertThat(result.path("ok").asBoolean()).isTrue();
            assertThat(result.path("counters").path("sentinel").asInt()).isZero();
            assertThat(result.path("counters").path("unknownReloadRefresh").asInt()).isZero();
            assertThat(result.path("counters").path("reloadRefresh").asInt()).isEqualTo(1);
            assertThat(result.path("counters").path("twoTabRefresh").asInt()).isEqualTo(2);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                            + " revoke_code='REFRESH_REUSED'",
                                    Integer.class,
                                    member))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                            + " revoked_at IS NULL",
                                    Integer.class,
                                    member))
                    .isEqualTo(1); // 가입 fixture의 native family만 남는다.
            assertThat(
                            db.queryForObject(
                                    "SELECT coalesce(max(t.generation),0) FROM member_token t JOIN"
                                            + " member_session s ON s.id=t.session_id WHERE"
                                            + " s.member_id=?",
                                    Integer.class,
                                    member))
                    .isGreaterThanOrEqualTo(3);
            // 영수증 원문 대신 검증한 정수 횟수만 XML에 남겨 재현 근거를 보존한다.
            var safeResult = JSON.createObjectNode().put("ok", true);
            var safeCounters = safeResult.putObject("counters");
            for (String name :
                    new String[] {
                        "login",
                        "refresh",
                        "logout",
                        "me",
                        "sentinel",
                        "reloadRefresh",
                        "twoTabRefresh",
                        "unknownReloadRefresh",
                        "boundaryProbes",
                        "redirectRefused",
                        "droppedResponse",
                        "serviceWorkerBlocked"
                    }) {
                var value = result.path("counters").path(name);
                assertThat(value.isIntegralNumber()).isTrue();
                safeCounters.set(name, value);
            }
            var observations = safeResult.putObject("responseObservation");
            for (String name : new String[] {"deliveredFetch200", "checkedFetch200"}) {
                var value = result.path("responseObservation").path(name);
                assertThat(value.isIntegralNumber()).isTrue();
                observations.set(name, value);
            }
            System.out.println("PLAYER_WEB_AUTH_EVIDENCE " + safeResult);
        } finally {
            Files.deleteIfExists(receipt);
            Files.deleteIfExists(errors);
        }
    }

    /** 별도 두 LOCAL 계정의 실제 초대를 컴파일된 Flutter 조사 화면에서 진행한다. */
    @Test
    void webInvestigationLifecycle() throws Exception {
        runInvestigation(false);
    }

    /** 같은 TLS·회원·조사 경로에서 실제 TEST 소비와 개인별 결과 접근까지 검증한다. */
    @Test
    void webReportLifecycle() throws Exception {
        runInvestigation(true);
    }

    /**
     * @param reportMode 참이면 원본 사본의 명제를 담은 보고서를 UI로 접수하고 기존 worker로 소비한다
     */
    private void runInvestigation(boolean reportMode) throws Exception {
        playtestPolicy();
        String emailA = "web-investigation-a-" + UUID.randomUUID() + "@example.invalid";
        String emailB = "web-investigation-b-" + UUID.randomUUID() + "@example.invalid";
        var nativeA = account(emailA);
        var nativeB = account(emailB);
        UUID memberA =
                invitations
                        .getMemberIdentity(nativeA.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID memberB =
                invitations
                        .getMemberIdentity(nativeB.path("accessToken").asText(), UUID.randomUUID())
                        .memberKey();
        UUID key = invitation(List.of(memberA, memberB));
        List<Long> memberIds =
                List.of(nativeA, nativeB).stream()
                        .map(
                                issued ->
                                        db.queryForObject(
                                                "SELECT member_id FROM member_session WHERE"
                                                        + " session_key=?",
                                                Long.class,
                                                UUID.fromString(
                                                        issued.path("sessionKey").asText())))
                        .toList();
        Path receipt = Files.createTempFile("player-investigation-result-", ".json");
        Path errors = Files.createTempFile("player-investigation-error-", ".txt");
        Process browser = null;
        try {
            browser =
                    new ProcessBuilder("node", "src/test/browser/player-web-investigation.mjs")
                            .redirectOutput(receipt.toFile())
                            .redirectError(errors.toFile())
                            .start();
            try (var input = browser.getOutputStream()) {
                var fixture =
                        new HashMap<String, Object>(
                                Map.of(
                                        "origin",
                                        ORIGIN.toString(),
                                        "testKey",
                                        key.toString(),
                                        "accounts",
                                        List.of(
                                                Map.of(
                                                        "email",
                                                        emailA,
                                                        "memberKey",
                                                        memberA.toString()),
                                                Map.of(
                                                        "email",
                                                        emailB,
                                                        "memberKey",
                                                        memberB.toString())),
                                        "password",
                                        "Synthetic-member-password-987!"));
                if (reportMode) {
                    fixture.put("mode", "report");
                    fixture.put("report", supportedReport(key));
                }
                JSON.writeValue(input, fixture);
            }
            if (reportMode)
                consumeBrowserReport(
                        browser,
                        key,
                        receipt,
                        List.of(
                                nativeA.path("accessToken").asText(),
                                nativeB.path("accessToken").asText()));
            if (!browser.waitFor(reportMode ? 10 : 150, TimeUnit.SECONDS)) {
                browser.destroyForcibly();
                throw new AssertionError("Compiled Flutter investigation deadline exceeded");
            }
            assertThat(browser.exitValue())
                    .withFailMessage("Browser investigation failed: %s", Files.readString(errors))
                    .isZero();
            var lines = Files.readAllLines(receipt);
            assertThat(lines).hasSize(reportMode ? 3 : 1);
            if (reportMode) {
                assertThat(lines.get(0)).isEqualTo("{\"checkpoint\":\"grade\"}");
                assertThat(lines.get(1)).isEqualTo("{\"checkpoint\":\"revoke\"}");
            }
            var result = JSON.readTree(lines.getLast());
            assertThat(result.path("ok").asBoolean()).isTrue();
            var safe = JSON.createObjectNode().put("ok", true);
            var counts = safe.putObject("counters");
            for (String name :
                    List.of(
                            "login",
                            "logout",
                            "accept",
                            "ready",
                            "start",
                            "hint",
                            "heartbeat",
                            "materials",
                            "validatedMaterials",
                            "droppedAccept",
                            "originalReplay",
                            "boundaryProbes",
                            "backgroundHidden",
                            "sameRoleRecovered",
                            "accountPurged",
                            "runtimeErrors",
                            "excludedCalls")) {
                var value = result.path("counters").path(name);
                assertThat(value.isIntegralNumber() && value.canConvertToLong()).isTrue();
                assertThat(value.longValue()).isBetween(0L, 9007199254740991L);
                counts.set(name, value);
            }
            assertThat(counts.path("accept").asInt()).isEqualTo(3);
            assertThat(counts.path("ready").asInt()).isEqualTo(2);
            assertThat(counts.path("start").asInt()).isEqualTo(1);
            assertThat(counts.path("hint").asInt()).isEqualTo(1);
            assertThat(counts.path("materials").asInt())
                    .isPositive()
                    .isEqualTo(counts.path("validatedMaterials").asInt());
            for (String name :
                    List.of(
                            "droppedAccept",
                            "originalReplay",
                            "backgroundHidden",
                            "sameRoleRecovered",
                            "accountPurged")) {
                assertThat(counts.path(name).asInt()).isEqualTo(1);
            }
            assertThat(counts.path("runtimeErrors").asInt()).isZero();
            assertThat(counts.path("excludedCalls").asInt()).isZero();
            if (reportMode) {
                for (String name :
                        List.of(
                                "reportEdit",
                                "reportPropose",
                                "reportAccept",
                                "feedback",
                                "reportHidden",
                                "feedbackHidden",
                                "reportReplay",
                                "feedbackReplay",
                                "ownFeedbackIsolation",
                                "freshResult",
                                "oldOwnerDenied",
                                "revokedResult",
                                "revokedMutation")) {
                    var value = result.path("counters").path(name);
                    assertThat(value.isIntegralNumber()).isTrue();
                    assertThat(value.intValue())
                            .isEqualTo(
                                    switch (name) {
                                        case "reportEdit" -> 2;
                                        case "feedback" -> 3;
                                        case "freshResult" -> 2;
                                        default -> 1;
                                    });
                    counts.set(name, value);
                }
                assertThat(
                                db.queryForMap(
                                        "SELECT state,outcome,attempt_count,wrong_count FROM"
                                                + " play_test WHERE test_key=?",
                                        key))
                        .containsEntry("state", "ENDED")
                        .containsEntry("outcome", "SUCCESS")
                        .containsEntry("attempt_count", 1)
                        .containsEntry("wrong_count", 0);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM test_member m JOIN play_test t ON"
                                                + " t.id=m.test_id WHERE t.test_key=? AND"
                                                + " m.feedback_at IS NOT NULL",
                                        Integer.class,
                                        key))
                        .isEqualTo(2);
            }
            for (long member : memberIds) {
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                                + " revoked_at IS NULL",
                                        Integer.class,
                                        member))
                        .isEqualTo(1);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM member_session WHERE member_id=? AND"
                                                + " revoke_code='REFRESH_REUSED'",
                                        Integer.class,
                                        member))
                        .isZero();
            }
            assertThat(
                            db.queryForList(
                                    "SELECT session_key FROM member_session WHERE member_id IN"
                                            + " (?,?) AND revoked_at IS NULL",
                                    UUID.class,
                                    memberIds.get(0),
                                    memberIds.get(1)))
                    .containsExactlyInAnyOrder(
                            UUID.fromString(nativeA.path("sessionKey").asText()),
                            UUID.fromString(nativeB.path("sessionKey").asText()));
            var participants =
                    db.queryForList(
                            "SELECT slot,member_id,invite_state,role_code FROM test_member m JOIN"
                                + " play_test t ON t.id=m.test_id WHERE t.test_key=? ORDER BY slot",
                            key);
            assertThat(participants).hasSize(2);
            assertThat(participants.stream().map(row -> row.get("slot")).toList())
                    .containsExactly(1, 2);
            assertThat(participants.stream().map(row -> row.get("member_id")).toList())
                    .containsExactlyElementsOf(memberIds);
            assertThat(participants.stream().map(row -> row.get("invite_state")).toList())
                    .containsOnly(reportMode ? "REVOKED" : "ACCEPTED");
            assertThat(participants.stream().map(row -> row.get("role_code")).toList())
                    .containsExactlyInAnyOrder("R1", "R2");
            for (String table : List.of("test_action", "test_audit")) {
                var expectedActions =
                        new HashMap<>(
                                Map.of(
                                        "INVITATION_ACCEPT", 2,
                                        "TEST_READY", 2,
                                        "TEST_START", 1,
                                        "HINT_OPEN", 1));
                if (reportMode)
                    expectedActions.putAll(
                            Map.of(
                                    "REPORT_EDIT", 1,
                                    "REPORT_PROPOSE", 1,
                                    "REPORT_ACCEPT", 1,
                                    "TEST_FEEDBACK", 2,
                                    "INVITATION_REVOKE", 1));
                for (var expected : expectedActions.entrySet()) {
                    assertThat(
                                    db.queryForObject(
                                            "SELECT count(*) FROM "
                                                    + table
                                                    + " WHERE scope_key=? AND action=?",
                                            Integer.class,
                                            "test:" + key,
                                            expected.getKey()))
                            .isEqualTo(expected.getValue());
                }
            }
            System.out.println(
                    (reportMode
                                    ? "PLAYER_WEB_REPORT_EVIDENCE "
                                    : "PLAYER_WEB_INVESTIGATION_EVIDENCE ")
                            + safe);
        } finally {
            if (browser != null && browser.isAlive()) {
                browser.destroyForcibly();
                browser.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(receipt);
            Files.deleteIfExists(errors);
        }
    }

    /**
     * 브라우저가 살아 있는 동안 해당 초대의 단일 QUEUED TEST만 소비한다.
     *
     * @param browser 자격을 stdin으로만 받은 실제 Node 프로세스
     * @param key 이 시험에서 생성한 초대
     * @param receipt 원문 없는 회수 체크포인트와 최종 정수 영수증 파일
     * @param nativeTokens 동일 두 회원의 가입 fixture 자격이며 결과 서비스의 현재 인가 대조에만 쓴다
     */
    private void consumeBrowserReport(
            Process browser, UUID key, Path receipt, List<String> nativeTokens) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(250);
        boolean consumed = false;
        boolean revoked = false;
        while (browser.isAlive() && System.nanoTime() < deadline) {
            String checkpoints = Files.readString(receipt);
            if (!consumed && checkpoints.startsWith("{\"checkpoint\":\"grade\"}\n")) {
                var queued =
                        db.queryForList(
                                "SELECT j.job_key FROM grade_job j JOIN test_report r ON"
                                        + " r.id=j.report_id JOIN play_test t ON t.id=r.test_id"
                                        + " WHERE t.test_key=? AND j.state='QUEUED'",
                                UUID.class,
                                key);
                if (!queued.isEmpty()) {
                    assertThat(queued).hasSize(1);
                    assertThat(
                                    db.queryForList(
                                            "SELECT j.job_key FROM grade_job j JOIN grade_runtime"
                                                    + " rt ON rt.id=j.runtime_id WHERE"
                                                    + " rt.code='H5_SYNTHETIC' AND j.state IN"
                                                    + " ('QUEUED','RUNNING')",
                                            UUID.class))
                            .containsExactly(queued.getFirst());
                    consumeReportJob(key, queued.getFirst());
                    consumed = true;
                }
            }
            if (!revoked
                    && checkpoints.startsWith(
                            "{\"checkpoint\":\"grade\"}\n{\"checkpoint\":\"revoke\"}\n")) {
                assertThat(consumed).isTrue();
                for (String token : nativeTokens) {
                    var current = reports.result(token, key, UUID.randomUUID());
                    assertThat(current.resultPhase()).isEqualTo("AVAILABLE");
                    assertThat(current.feedbackSubmitted()).isTrue();
                    assertThat(current.finalScore())
                            .isEqualTo(
                                    db.queryForObject(
                                            "SELECT final_score FROM play_test WHERE test_key=?",
                                            Integer.class,
                                            key));
                    var summary = JSON.valueToTree(current.scoreSummary());
                    assertThat(summary.path("finalScore").intValue())
                            .isEqualTo(current.finalScore());
                }
                long revision =
                        db.queryForObject(
                                "SELECT rev FROM play_test WHERE test_key=?", Long.class, key);
                invitations.revokeInvitation(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        key,
                        revision,
                        "CONTENT_REVIEW",
                        "SYNTHETIC_BROWSER_REVIEW",
                        UUID.randomUUID(),
                        UUID.randomUUID());
                revoked = true;
            }
            browser.waitFor(100, TimeUnit.MILLISECONDS);
        }
        assertThat(browser.isAlive()).withFailMessage("Browser report deadline exceeded").isFalse();
        if (browser.exitValue() == 0) {
            assertThat(consumed).isTrue();
            assertThat(revoked).isTrue();
        }
    }

    /**
     * @param key 브라우저가 실제로 공동 접수한 초대
     * @param jobKey 현재 QUEUED 집합에서 유일하게 확인한 작업
     */
    private void consumeReportJob(UUID key, UUID jobKey) throws Exception {
        var pinned =
                db.queryForMap(
                        "SELECT j.id,j.accepted_at,j.deadline_at,j.report_hash,j.rubric_hash,"
                            + " j.snapshot_id,j.config_hash,r.payload_hash,r.payload_cipher, r.id"
                            + " AS report_id,r.test_id,r.source_draft_rev FROM grade_job j JOIN"
                            + " test_report r ON r.id=j.report_id JOIN play_test t ON"
                            + " t.id=r.test_id WHERE j.job_key=? AND t.test_key=? AND"
                            + " j.state='QUEUED' AND r.state='ACCEPTED' AND r.submit_no=1 AND"
                            + " r.source_draft_rev=t.draft_rev AND r.payload_hash=t.draft_hash AND"
                            + " r.proposer_id<>r.accepted_by AND r.proposer_id=(SELECT member_id"
                            + " FROM test_member WHERE test_id=t.id AND slot=1) AND"
                            + " r.accepted_by=(SELECT member_id FROM test_member WHERE test_id=t.id"
                            + " AND slot=2) AND j.batch_id IS NULL AND j.sample_code IS NULL AND"
                            + " j.repeat_no IS NULL AND j.input_hash IS NULL AND"
                            + " j.accepted_at=r.accepted_at AND"
                            + " j.deadline_at=j.accepted_at+interval '120 seconds'",
                        jobKey,
                        key);
        long jobId = ((Number) pinned.get("id")).longValue();
        var report =
                SnapshotJson.parse(
                        crypto.decrypt(
                                        new String(
                                                (byte[]) pinned.get("payload_cipher"),
                                                StandardCharsets.UTF_8),
                                        "test_report/"
                                                + pinned.get("report_id")
                                                + "/test/"
                                                + pinned.get("test_id")
                                                + "/snapshot/"
                                                + pinned.get("snapshot_id")
                                                + "/draft/"
                                                + pinned.get("source_draft_rev")
                                                + "/payload/v1")
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(report.equals(supportedReport(key)))
                .withFailMessage("Browser fixed report differs from frozen source-supported input")
                .isTrue();
        assertThat(SnapshotJson.hash(report)).isEqualTo(pinned.get("payload_hash"));
        var factsTx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                db.getDataSource()));
        factsTx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var saved =
                factsTx.execute(
                        status ->
                                new GradeFrozenInputRepository(db)
                                        .loadSavedFacts(
                                                ((Number) pinned.get("snapshot_id")).longValue()));
        assertThat(saved).isNotNull();
        var validated = new GradeResultValidator().parseReport(report.toString());
        assertThat(saved.frozen().reportHash(validated)).isEqualTo(pinned.get("report_hash"));
        assertThat(saved.frozen().rubricHash()).isEqualTo(pinned.get("rubric_hash"));
        Path root =
                Files.createTempDirectory(
                                "player-report-worker-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")))
                        .toRealPath();
        Scope scope = new Scope(SERVER_SCOPE, WORKER, CommonUtil.sha256(SECRET));
        var transport = new AssemblyTransport(scope);
        int chats = PROVIDER.chats;
        try {
            var profiles = profiles(root);
            var descriptor = journal(root, scope);
            try (var worker =
                    new GradeRemoteBatchWorker(
                            GradeRemoteOnceJournal.open(descriptor.toString()),
                            transport,
                            new GradeRemoteBatchWorker.TransportBounds(
                                    8 * 1024 * 1024, Duration.ofSeconds(10)),
                            profiles)) {
                var outcome = worker.pollAndRunOnce("H5_SYNTHETIC");
                assertThat(outcome).isNotNull();
                assertThat(outcome.state()).isEqualTo(GradeRemoteBatchWorker.State.RECEIPT);
                assertThat(outcome.receipt().jobKey()).isEqualTo(jobKey);
                assertThat(outcome.receipt().attemptNo()).isEqualTo(1);
                assertThat(outcome.receipt().accepted()).isTrue();
                assertThat(outcome.receipt().state()).isEqualTo("COMPLETED");
                assertThat(outcome.receipt().outcome()).isEqualTo("COMPLETE");
            }
        } finally {
            for (String file :
                    List.of("profiles.json", "descriptor.json", "journal.bin", "owner.lock"))
                Files.deleteIfExists(root.resolve(file));
            Files.deleteIfExists(root);
        }
        assertThat(transport.polls).isEqualTo(1);
        assertThat(transport.starts).isEqualTo(1);
        assertThat(transport.fences).isEqualTo(1);
        assertThat(transport.completions).isEqualTo(1);
        assertThat(PROVIDER.chats).isEqualTo(chats + 1);
        var completed =
                db.queryForMap(
                        "SELECT accepted_at,deadline_at,report_hash,rubric_hash,snapshot_id,"
                                + " config_hash,state,call_count,input_hash,batch_id,sample_code,"
                                + " repeat_no FROM grade_job WHERE id=?",
                        jobId);
        for (String field :
                List.of(
                        "accepted_at",
                        "deadline_at",
                        "report_hash",
                        "rubric_hash",
                        "snapshot_id",
                        "config_hash"))
            assertThat(completed.get(field)).isEqualTo(pinned.get(field));
        assertThat(completed)
                .containsEntry("state", "COMPLETED")
                .containsEntry("call_count", 1)
                .containsEntry("input_hash", null)
                .containsEntry("batch_id", null)
                .containsEntry("sample_code", null)
                .containsEntry("repeat_no", null);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_attempt WHERE job_id=? AND"
                                        + " attempt_no=1 AND state='SUCCEEDED' AND output_cipher"
                                        + " IS NOT NULL AND completion_data IS NOT NULL",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
        byte[] output =
                db.queryForObject(
                        "SELECT output_cipher FROM grade_attempt WHERE job_id=? AND attempt_no=1",
                        byte[].class,
                        jobId);
        var semantic =
                SnapshotJson.parse(
                        crypto.decrypt(
                                        new String(output, StandardCharsets.UTF_8),
                                        "grade_attempt/" + jobId + "/1/output/v1")
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(SnapshotJson.encode(semantic))
                .withFailMessage("Stored semantic output differs from actual loopback provider")
                .containsExactly(SnapshotJson.encode(PROVIDER.semantic));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_event WHERE job_id=? AND"
                                        + " event_kind='COMPLETE_APPLIED' AND actor_kind='WORKER'",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_audit WHERE scope_key=? AND"
                                        + " action='GRADE_COMPLETE' AND actor_kind='WORKER'"
                                        + " AND business_result='GRADED'",
                                Integer.class,
                                jobKey.toString()))
                .isEqualTo(1);
    }
}
