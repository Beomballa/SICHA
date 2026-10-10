package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.FrozenSnapshotContractTest;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal;
import com.reasoning.common.grading.config.GradeRemoteOnceJournal.Scope;
import com.reasoning.common.grading.config.WorkerGradeProfileLoader;
import com.reasoning.common.grading.controller.GradeWorkerController.Assembly;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.InstalledProfile;
import com.reasoning.common.grading.engine.LocalSemanticEngine;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeLeaseRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Action;
import com.reasoning.common.grading.security.GradeWorkerCredentials.Registration;
import com.reasoning.common.grading.service.GradeRemoteBatchWorker;
import com.reasoning.common.grading.service.GradeRemoteExecutionProtocol.AttemptRequest;
import com.reasoning.common.member.LocalMemberAuthIT;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;
import com.reasoning.common.util.CommonUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 보고서 시험을 상속하지 않고 실제 SR-02 원본과 TEST 소비 조립만 공유한다. */
public abstract class PlaytestReportFixture extends LocalMemberAuthIT {
    protected static final String WORKER = "H5_REPORT_SYNTHETIC_WORKER";
    protected static final byte[] SECRET =
            ByteBuffer.allocate(32).putLong(501).putLong(502).putLong(503).putLong(504).array();
    protected static final UUID SERVER_SCOPE = UUID.randomUUID();
    protected static final SyntheticProvider PROVIDER = new SyntheticProvider();

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> PROVIDER.server.stop(0), "h5-provider-stop"));
    }

    @Autowired protected PlaytestInvestigationService investigation;
    @Autowired protected PlaytestReportService reports;
    @Autowired protected PlaytestAccess access;
    @Autowired protected Assembly assembly;
    @Autowired protected GradeWorkerCredentials credentials;
    @Autowired protected StoryReviewService reviews;
    @Autowired protected com.reasoning.admin.auth.session.AdminSessionAdapter adminSessions;
    @Autowired protected com.reasoning.common.grading.repository.GradeRuntimeRepository runtimes;
    @Autowired protected InstalledRuntimeManifestVerifier syntheticRuntime;

    @Autowired
    @Qualifier("gradeInstallations")
    protected Map<String, InstalledRuntimeManifestVerifier> installations;

    /** SR-02가 실제 원고를 동결하고 mutable editRev만 증가시킨 뒤 초대를 생성한다. */
    @Override
    protected UUID invitation(List<UUID> members) {
        long owner =
                db.queryForObject(
                        "SELECT owner_id FROM privacy_policy WHERE scope='MEMBER_AUTH' AND"
                                + " state='ACTIVE'",
                        Long.class);
        db.update("UPDATE admin_account SET can_review=true WHERE id=?", owner);
        UUID accountKey =
                db.queryForObject(
                        "SELECT account_key FROM admin_account WHERE id=?", UUID.class, owner);
        var prepared = adminSessions.prepare();
        var principal = new AdminPrincipal(owner, accountKey, UUID.randomUUID(), 1);
        adminSessions.save(prepared, principal);
        db.update(
                "WITH t AS MATERIALIZED (SELECT clock_timestamp() n) INSERT INTO"
                    + " admin_session(session_key,account_id,sid_hash,auth_rev,state,started_at,last_action_at,expires_at,reauth_at,activated_at)"
                    + " SELECT ?,?,?,1,'ACTIVE',n,n,n+interval '8 hours',n,n FROM t",
                principal.sessionKey(),
                owner,
                crypto.sessionHash(prepared.id()));
        runtimes.registerRuntime(
                "H5_SYNTHETIC",
                syntheticRuntime.configHash(),
                syntheticRuntime.registrationManifest().toString());
        String code = "H5_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        invitationSid = prepared.id();
        invitationActor = principal;
        invitationStoryCode = code;
        long story =
                db.queryForObject(
                        "INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id",
                        Long.class,
                        code,
                        owner);
        for (String permission : List.of("REVIEW", "EDIT"))
            db.update(
                    "INSERT INTO story_access(story_id,admin_id,permission,granted_by) VALUES"
                            + " (?,?,?,?)",
                    story,
                    owner,
                    permission,
                    owner);
        long version =
                db.queryForObject(
                        "INSERT INTO"
                            + " story_version(story_id,version_no,title,policy_code,created_by,updated_by)"
                            + " VALUES (?,1,'합성 검수','RULE_20260924',?,?) RETURNING id",
                        Long.class,
                        story,
                        owner,
                        owner);
        ObjectNode source = FrozenSnapshotContractTest.complete();
        ObjectNode basic = (ObjectNode) source.path("sections").path("basic");
        basic.put("estMin", 15).put("estMax", 30).put("timelineOrigin", "사건 당일 자정");
        JsonNode resources = source.path("resources");
        for (JsonNode row : resources.path("persons"))
            ((ObjectNode) row).put("publicText", "합성 공개 인물 자료");
        for (JsonNode row : resources.path("hints"))
            ((ObjectNode) row).put("body", "관찰과 연결을 확인하는 합성 힌트");
        ((com.fasterxml.jackson.databind.node.ArrayNode) resources.path("hints"))
                .addObject()
                .put("code", "H3")
                .put("level", 3)
                .put("body", "합성 세 번째 힌트");
        ((com.fasterxml.jackson.databind.node.ArrayNode) resources.path("clueRoles"))
                .addObject()
                .put("clueCode", "C2")
                .put("roleCode", "R3");
        for (JsonNode row : resources.path("events"))
            ((ObjectNode) row)
                    .put("startMin", 0)
                    .put("endMin", 1)
                    .put("actualText", "실제 합성 경과")
                    .put("apparentText", "표면 합성 경과");
        for (JsonNode row : resources.path("facts"))
            ((ObjectNode) row).put("statement", "합성 사실 명제").put("basis", "C1/C2 합성 연결");
        for (JsonNode row : resources.path("rubrics"))
            ((ObjectNode) row)
                    .put("acceptedText", "등록 경로 표현")
                    .put("partialText", "등록 부분 단계")
                    .put("rejectText", "등록 모순 기준");
        saveReviewRows(
                version,
                resources,
                "persons",
                "story_person",
                "code,name,public_text,secret_text",
                "code,name,publicText,secretText");
        saveReviewRows(
                version, resources, "roles", "story_role", "code,name,brief", "code,name,brief");
        saveReviewRows(version, resources, "pairs", "story_pair", "role_a,role_b", "roleA,roleB");
        saveReviewRows(
                version,
                resources,
                "clues",
                "story_clue",
                "code,title,body,person_code,scope,source_text",
                "code,title,body,personCode,scope,sourceText");
        saveReviewRows(
                version,
                resources,
                "clueRoles",
                "clue_role",
                "clue_code,role_code",
                "clueCode,roleCode");
        saveReviewRows(
                version, resources, "hints", "story_hint", "code,level,body", "code,level,body");
        saveReviewRows(
                version,
                resources,
                "events",
                "story_event",
                "code,start_min,end_min,actual_text,apparent_text",
                "code,startMin,endMin,actualText,apparentText");
        saveReviewRows(
                version,
                resources,
                "facts",
                "story_fact",
                "code,statement,truth,basis",
                "code,statement,truth,basis");
        saveReviewRows(
                version,
                resources,
                "rubrics",
                "story_rubric",
                "code,category,max_score,required_yn,pass_score,accepted_text,partial_text,reject_text,rule_data",
                "code,category,maxScore,requiredYn,passScore,acceptedText,partialText,rejectText,ruleData");
        saveReviewRows(
                version,
                resources,
                "rubricClues",
                "rubric_clue",
                "rubric_code,clue_code,link_text",
                "rubricCode,clueCode,linkText");
        for (JsonNode row : resources.path("gradeSamples"))
            db.update(
                    "INSERT INTO"
                        + " grade_sample(version_id,code,input_data,expect_data,expected_score,expected_success,reason,checked_by)"
                        + " VALUES (?,?,?::jsonb,?::jsonb,?,?,?,?)",
                    version,
                    sqlValue(row.get("code")),
                    row.get("inputData").toString(),
                    row.get("expectData").toString(),
                    sqlValue(row.get("expectedScore")),
                    sqlValue(row.get("expectedSuccess")),
                    sqlValue(row.get("reason")),
                    owner);
        JsonNode answer = source.path("sections").path("answer");
        db.update(
                "UPDATE story_version SET"
                    + " edit_rev=1,title=?,intro=?,setting=?,difficulty=?,est_min=?,est_max=?,limit_sec=?,timeline_origin=?,culprit_code=?,method_answer=?,time_answer=?,motive_answer=?,reveal_text=?"
                    + " WHERE id=?",
                sqlValue(basic.get("title")),
                sqlValue(basic.get("intro")),
                sqlValue(basic.get("setting")),
                sqlValue(basic.get("difficulty")),
                sqlValue(basic.get("estMin")),
                sqlValue(basic.get("estMax")),
                sqlValue(basic.get("limitSec")),
                sqlValue(basic.get("timelineOrigin")),
                sqlValue(answer.get("culpritCode")),
                sqlValue(answer.get("methodAnswer")),
                sqlValue(answer.get("timeAnswer")),
                sqlValue(answer.get("motiveAnswer")),
                sqlValue(source.path("sections").path("reveal").get("revealText")),
                version);
        var reviewed =
                reviews.requestReview(
                        prepared.id(),
                        principal,
                        code,
                        1,
                        "1",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        long snapshot = Long.parseLong(reviewed.snapshotId());
        assertThat(reviewed.sourceRev()).isEqualTo("1");
        assertThat(reviewed.editRev()).isEqualTo("2");
        assertThat(
                        db.queryForObject(
                                "SELECT s.edit_rev+1=v.edit_rev AND s.id=v.current_snapshot_id FROM"
                                    + " review_snapshot s JOIN story_version v ON v.id=s.version_id"
                                    + " WHERE s.id=?",
                                Boolean.class,
                                snapshot))
                .isTrue();
        var result =
                invitations.createInvitation(
                        prepared.id(),
                        principal,
                        code,
                        1,
                        2,
                        snapshot,
                        "H5_SYNTHETIC",
                        "FUNCTIONAL",
                        "R1",
                        "R2",
                        members,
                        UUID.randomUUID(),
                        UUID.randomUUID());
        return (UUID) result.current().get("testKey");
    }

    /** 기존 합성 원고의 자원별 활성 행을 실제 검수 저장 구조에 넣는다. */
    private void saveReviewRows(
            long version,
            JsonNode resources,
            String resource,
            String table,
            String columns,
            String fields) {
        String[] names = fields.split(",");
        var placeholders = new java.util.ArrayList<String>();
        for (String name : names) placeholders.add(name.equals("ruleData") ? "?::jsonb" : "?");
        for (JsonNode row : resources.path(resource)) {
            var values = new java.util.ArrayList<Object>();
            values.add(version);
            for (String name : names)
                values.add(
                        name.equals("ruleData")
                                ? row.get(name).toString()
                                : sqlValue(row.get(name)));
            db.update(
                    "INSERT INTO "
                            + table
                            + "(version_id,"
                            + columns
                            + ") VALUES (?,"
                            + String.join(",", placeholders)
                            + ")",
                    values.toArray());
        }
    }

    /** 합성 사본의 nullable 원시 값을 JDBC 값으로 보존한다. */
    private static Object sqlValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        return value.textValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestAssembly {
        /** 실제 설치와 보호 profile이 동일한 합성 loopback Ollama 설정을 공유한다. */
        @Bean
        @Primary
        InstalledRuntimeManifestVerifier playtestSemanticInstallation() {
            return PROVIDER.installation;
        }

        /** 실제 TEST 조립과 같은 레지스트리에서만 인증 가능한 합성 256비트 worker를 등록한다. */
        @Bean
        @Primary
        GradeWorkerCredentials playtestWorkerCredentials() {
            return new GradeWorkerCredentials(
                    List.of(
                            new Registration(
                                    WORKER,
                                    CommonUtil.sha256(SECRET),
                                    Set.of("H5_SYNTHETIC"),
                                    Set.of(
                                            Action.CLAIM,
                                            Action.START,
                                            Action.RENEW,
                                            Action.COMPLETE))));
        }

        /** 시험 런타임과 실제 보안 레지스트리·저장소·정책·암호 경계를 같은 TEST 조립에 결속한다. */
        @Bean
        Assembly playtestAssembly(
                GradeWorkerCredentials credentials,
                JdbcTemplate db,
                @Qualifier("gradeInstallations")
                        Map<String, InstalledRuntimeManifestVerifier> installations,
                PlaytestPolicyGate policy,
                MemberPolicyGate memberPolicy,
                CryptoService crypto) {
            return Assembly.forTest(
                    credentials,
                    db,
                    new DataSourceTransactionManager(db.getDataSource()),
                    installations,
                    policy,
                    memberPolicy,
                    crypto,
                    "H5_SYNTHETIC_COORDINATOR");
        }
    }

    /**
     * @param key 실제 고정 사본에 연결된 초대
     * @param marker 초안 간 차이
     * @return 등록 인물을 고른 REPORT-1
     */
    protected JsonNode report(UUID key, String marker) throws Exception {
        String culprit =
                db.queryForObject(
                        "SELECT s.payload->'resources'->'persons'->0->>'code' FROM review_snapshot"
                                + " s JOIN play_test t ON t.snapshot_id=s.id WHERE t.test_key=?",
                        String.class,
                        key);
        ObjectNode value = JSON.createObjectNode();
        value.put("culpritCode", culprit);
        value.put("method", "합성 방법 " + marker);
        value.put("time", "합성 시각");
        value.put("motive", "합성 동기");
        value.put("evidence", "합성 근거");
        return value;
    }

    /** REVIEW 원본에 등록된 명제 문구를 제출 근거에 넣어 합성 판정의 참조 위치를 만든다. */
    protected JsonNode supportedReport(UUID key) throws Exception {
        ObjectNode value = (ObjectNode) report(key, "접수");
        JsonNode source =
                JSON.readTree(
                        db.queryForObject(
                                "SELECT s.payload::text FROM review_snapshot s JOIN play_test t ON"
                                        + " t.snapshot_id=s.id WHERE t.test_key=?",
                                String.class,
                                key));
        StringBuilder evidence = new StringBuilder("합성 근거");
        for (JsonNode rubric : source.path("resources").path("rubrics"))
            for (JsonNode claim : rubric.path("ruleData").path("claims"))
                evidence.append(' ').append(claim.path("meaning").asText());
        value.put("evidence", evidence.toString());
        return value;
    }

    /** 합성 Ollama는 실제 설치 엔진이 보낸 답안 없는 투영만 읽어 의미 명제를 반환한다. */
    protected static final class SyntheticProvider {
        private final com.sun.net.httpserver.HttpServer server;
        private final GradeDictionary dictionary =
                new GradeDictionary(
                        "H5_SYNTHETIC", List.of(new GradeDictionary.Term("ONE", "개념", "합성")));
        private final LocalSemanticEngine.Settings settings;
        final InstalledRuntimeManifestVerifier installation;
        public volatile JsonNode semantic;
        public volatile int chats;

        private SyntheticProvider() {
            try {
                server =
                        com.sun.net.httpserver.HttpServer.create(
                                new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext(
                        "/api/tags",
                        exchange ->
                                reply(
                                        exchange,
                                        "{\"models\":[{\"name\":\"qwen3:8b\",\"digest\":\""
                                                + "a".repeat(64)
                                                + "\"}]}"));
                server.createContext(
                        "/api/show",
                        exchange ->
                                reply(
                                        exchange,
                                        "{\"template\":\"{{ .System }}{{ .Prompt }}{{ .Response"
                                                + " }}\",\"thinking\":{\"values\":[false,true]}}"));
                server.createContext(
                        "/api/chat",
                        exchange -> {
                            try {
                                JsonNode request =
                                        SnapshotJson.parse(
                                                exchange.getRequestBody().readAllBytes());
                                JsonNode document =
                                        JSON.readTree(
                                                request.path("messages")
                                                        .get(1)
                                                        .path("content")
                                                        .asText());
                                var input =
                                        FrozenModelProjection.decodeCanonical(
                                                SnapshotJson.encode(document.path("input")));
                                assertThat(input.payload().toString())
                                        .doesNotContain(
                                                "gradeSamples",
                                                "expectedScore",
                                                "checkedBy",
                                                "answer");
                                var result =
                                        JSON.createObjectNode()
                                                .put("formatNo", 1)
                                                .put("status", "COMPLETE");
                                var items = result.putArray("items");
                                for (var coordinate : input.orderedRubricCoordinates()) {
                                    JsonNode rubric = null;
                                    for (JsonNode candidate :
                                            input.payload().path("gradingContext").path("rubrics"))
                                        if (coordinate
                                                .rubricCode()
                                                .equals(candidate.path("code").asText()))
                                            rubric = candidate;
                                    if (rubric == null)
                                        throw new IllegalArgumentException("MISSING_RUBRIC");
                                    var item =
                                            items.addObject()
                                                    .put("rubricCode", coordinate.rubricCode())
                                                    .put("reason", "합성 제공자 판정");
                                    var claims = item.putArray("claims");
                                    for (String code : coordinate.claimCodes()) {
                                        String meaning = null;
                                        for (JsonNode claim :
                                                rubric.path("ruleData").path("claims"))
                                            if (code.equals(claim.path("code").asText()))
                                                meaning = claim.path("meaning").asText();
                                        if (meaning == null || meaning.isBlank())
                                            throw new IllegalArgumentException("MISSING_CLAIM");
                                        int start = input.report().evidence().indexOf(meaning);
                                        var spans =
                                                claims.addObject()
                                                        .put("code", code)
                                                        .put("met", start >= 0)
                                                        .putArray("spans");
                                        if (start >= 0)
                                            spans.addObject()
                                                    .put("field", "evidence")
                                                    .put("start", start)
                                                    .put("end", start + meaning.length());
                                    }
                                    var contradictions = item.putArray("contradictions");
                                    for (String code : coordinate.contradictionCodes())
                                        contradictions
                                                .addObject()
                                                .put("code", code)
                                                .put("met", false)
                                                .putArray("spans");
                                }
                                semantic = result;
                                chats++;
                                var envelope =
                                        JSON.createObjectNode()
                                                .put("model", "qwen3:8b")
                                                .put("done", true)
                                                .put("done_reason", "stop");
                                envelope.putObject("message")
                                        .put("role", "assistant")
                                        .put("content", result.toString());
                                reply(exchange, envelope.toString());
                            } catch (Exception failure) {
                                exchange.close();
                                throw new java.io.IOException(
                                        "SYNTHETIC_PROVIDER_FAILURE", failure);
                            }
                        });
                server.start();
                settings =
                        new LocalSemanticEngine.Settings(
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                "qwen3:8b",
                                "a".repeat(64),
                                dictionary.sha256(),
                                "{{ .System }}{{ .Prompt }}{{ .Response }}",
                                65536,
                                4096,
                                0,
                                1,
                                false,
                                Duration.ofSeconds(30));
                installation =
                        new InstalledRuntimeManifestVerifier(
                                new LocalSemanticEngine(settings),
                                dictionary,
                                new InstalledProfile(
                                        "H5_SYNTHETIC", "LOCAL", "1", "RULE_20260924"));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("SYNTHETIC_PROVIDER_FAILURE", failure);
            }
        }

        /** 합성 loopback 응답을 실제 HTTP 스트림으로 전달한다. */
        private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body)
                throws java.io.IOException {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    /** 실제 TEST Assembly 자격과 저장소 거래를 묶어 worker의 byte 응답 경계로 전달한다. TLS 검증은 아니다。 */
    protected final class AssemblyTransport implements GradeRemoteBatchWorker.PollingTransport {
        private final Scope scope;
        // 시험 전용 전송 시각 편차이며 DB 시계·저장 마감·해시·권한은 변경하지 않는다.
        private final boolean wireClockSkew;
        Instant wireDeadline;
        private Duration wireOffset;
        public int polls;
        public int starts;
        public int fences;
        public int renewals;
        public int completions;
        private final GradeWorkerCredentials.VerifiedWorker worker;
        private final GradeLeaseRepository leases =
                new GradeLeaseRepository(db, new DataSourceTransactionManager(db.getDataSource()));

        public AssemblyTransport(Scope scope) {
            this(scope, false);
        }

        AssemblyTransport(Scope scope, boolean wireClockSkew) {
            this.scope = scope;
            this.wireClockSkew = wireClockSkew;
            worker =
                    assembly.credentials()
                            .authenticate(
                                    "Bearer "
                                            + Base64.getUrlEncoder()
                                                    .withoutPadding()
                                                    .encodeToString(SECRET));
            assertThat(worker.workerKey()).isEqualTo(scope.workerKey());
            assertThat(scope.credentialSha256()).isEqualTo(CommonUtil.sha256(SECRET));
        }

        @Override
        public Scope scope() {
            return scope;
        }

        @Override
        public CompletableFuture<GradeRemoteBatchWorker.PollReply> poll(
                String code, Duration wait) {
            polls++;
            for (Action action : Action.values())
                assembly.credentials().requirePermission(worker, action, code);
            assembly.testRecovery().recoverExpiredTestQueue(20);
            if (!db.queryForList(
                            "SELECT id FROM grade_job WHERE state='RUNNING' AND worker_key=? LIMIT"
                                    + " 1",
                            Long.class,
                            worker.workerKey())
                    .isEmpty())
                return CompletableFuture.completedFuture(
                        new GradeRemoteBatchWorker.PollReply(204, new byte[0]));
            var lease = leases.claimTest(worker.workerKey(), code);
            if (lease.isEmpty())
                return CompletableFuture.completedFuture(
                        new GradeRemoteBatchWorker.PollReply(204, new byte[0]));
            var claimed = lease.orElseThrow();
            if (wireClockSkew) {
                wireDeadline = Instant.now().minusSeconds(30);
                wireOffset = Duration.between(claimed.deadlineAt().toInstant(), wireDeadline);
                assertThat(claimed.deadlineAt().toInstant()).isAfter(wireDeadline);
            }
            byte[] body =
                    SnapshotJson.encode(
                            JSON.createObjectNode()
                                    .put("jobKey", claimed.jobKey().toString())
                                    .put("leaseGen", claimed.leaseGen())
                                    .put(
                                            "deadline",
                                            wireClockSkew
                                                    ? wireDeadline.toString()
                                                    : claimed.deadlineAt().toInstant().toString()));
            return CompletableFuture.completedFuture(
                    new GradeRemoteBatchWorker.PollReply(200, body));
        }

        @Override
        public CompletableFuture<byte[]> start(UUID key, long gen, Duration wait) {
            starts++;
            var started = assembly.start().startApproved(worker, key, gen);
            assertThat(started.disposition()).isEqualTo("NEW");
            return CompletableFuture.completedFuture(exportedClocks(started.toJson()));
        }

        @Override
        public CompletableFuture<byte[]> fence(UUID key, AttemptRequest original, Duration wait) {
            fences++;
            return CompletableFuture.completedFuture(
                    exportedClocks(assembly.start().revalidate(worker, key, original).toJson()));
        }

        @Override
        public CompletableFuture<byte[]> renew(UUID key, AttemptRequest original, Duration wait) {
            renewals++;
            return CompletableFuture.completedFuture(
                    exportedClocks(assembly.start().renewApproved(worker, key, original).toJson()));
        }

        /** 전송한 절대 시각만 이동하며 실제 서비스는 원래 튜플·해시를 보존한다. */
        private byte[] exportedClocks(ObjectNode response) {
            ObjectNode limits = (ObjectNode) response.path("limits");
            assertThat(limits.path("remainingBudgetMillis").longValue()).isPositive();
            assertThat(limits.path("remainingLeaseMillis").longValue()).isPositive();
            if (wireClockSkew) {
                limits.put("deadlineAt", wireDeadline.toString());
                limits.put(
                        "leaseUntil",
                        Instant.parse(limits.path("leaseUntil").asText())
                                .plus(wireOffset)
                                .toString());
                if (response.has("input")) {
                    ObjectNode input = (ObjectNode) response.path("input");
                    assertThat(input.path("sourceKind").asText()).isEqualTo("TEST");
                    input.put("deadlineAt", wireDeadline.toString());
                }
            }
            return SnapshotJson.encode(response);
        }

        @Override
        public CompletableFuture<byte[]> complete(
                UUID key, GradeRemoteBatchWorker.CompletionCommand command, Duration wait) {
            completions++;
            var receipt =
                    assembly.completion()
                            .complete(
                                    worker,
                                    key,
                                    command.leaseGen(),
                                    command.attemptNo(),
                                    command.observedProviderVersion(),
                                    command.providerResponseRef(),
                                    command.resultJson(),
                                    UUID.randomUUID());
            return CompletableFuture.completedFuture(
                    SnapshotJson.encode(
                            JSON.createObjectNode()
                                    .put("jobKey", receipt.jobKey().toString())
                                    .put("attemptNo", receipt.attemptNo())
                                    .put("accepted", receipt.accepted())
                                    .put("state", receipt.state())
                                    .put("retryScheduled", receipt.retryScheduled())
                                    .put("outcome", receipt.outcome())
                                    .put("reason", receipt.reason())
                                    .put("requestId", receipt.requestId().toString())));
        }
    }

    /** 설치의 전체 실제 설정·사전을 0600 문서에 고정하고 loader로 다시 검증한다. */
    protected static WorkerGradeProfileLoader.Profiles profiles(Path root) throws Exception {
        var settings = PROVIDER.settings;
        var dictionary = PROVIDER.dictionary;
        var row = JSON.createObjectNode();
        row.putObject("profile")
                .put("configId", "H5_SYNTHETIC")
                .put("engineId", "LOCAL")
                .put("engineVersion", "1")
                .put("policyCode", "RULE_20260924");
        row.putObject("settings")
                .put("endpoint", settings.endpoint().toString())
                .put("model", settings.model())
                .put("modelDigest", settings.modelDigest())
                .put("dictionaryHash", settings.dictionaryHash())
                .put("modelTemplate", settings.modelTemplate())
                .put("numCtx", settings.numCtx())
                .put("numPredict", settings.numPredict())
                .put("temperature", settings.temperature())
                .put("seed", settings.seed())
                .put("thinking", settings.thinking())
                .put("executionTimeoutMillis", settings.executionTimeout().toMillis());
        row.put("dictionaryCode", dictionary.dictionaryCode());
        var terms = row.putArray("dictionaryTerms");
        for (var term : dictionary.terms())
            terms.addObject()
                    .put("conceptCode", term.conceptCode())
                    .put("canonical", term.canonical())
                    .put("alias", term.alias());
        var document = JSON.createObjectNode().put("formatNo", 1);
        document.putArray("profiles").add(row);
        Path path = protectedFile(root.resolve("profiles.json"));
        Files.write(path, SnapshotJson.encode(document));
        return new WorkerGradeProfileLoader().load(path.toString());
    }

    /** 보호 descriptor, 기존 잠금 파일과 force된 헤더만 제공한다. */
    protected static Path journal(Path root, Scope scope) throws Exception {
        Path descriptor = protectedFile(root.resolve("descriptor.json"));
        Path bytes = protectedFile(root.resolve("journal.bin"));
        protectedFile(root.resolve("owner.lock"));
        try (var channel = FileChannel.open(bytes, StandardOpenOption.WRITE)) {
            var header = ByteBuffer.wrap(GradeRemoteOnceJournal.provisionedHeader(scope));
            while (header.hasRemaining()) channel.write(header);
            channel.force(true);
        }
        Files.write(
                descriptor,
                SnapshotJson.encode(
                        JSON.createObjectNode()
                                .put("formatNo", 1)
                                .put("serverScopeId", scope.serverScopeId().toString())
                                .put("workerKey", scope.workerKey())
                                .put("credentialSha256", scope.credentialSha256())
                                .put("journalFile", "journal.bin")
                                .put("lockFile", "owner.lock")
                                .put("capacityBytes", 65536)));
        return descriptor;
    }

    /** 실제 POSIX 소유자 전용 권한으로 새 fixture 파일을 만든다. */
    protected static Path protectedFile(Path path) throws Exception {
        return Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
}
