package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeFrozenInputRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.grading.service.FrozenDatasetValidator.SelectedSample;
import com.reasoning.common.grading.service.GradeBatchComparison;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.story.service.StoryService.ReviewScope;
import com.reasoning.common.util.CommonUtil;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 불변 전체 사본의 BATCH 생성·인가된 영수증 재생·비원문 조회만 담당한다. 실행·집계 권한은 없다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryBatchService {
    private final StoryService stories;
    private final JdbcTemplate db;
    private final GradeRuntimeRepository runtimes;
    private final GradeBatchComparison comparisons;
    private final GradeFrozenInputRepository frozenInputs;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;

    /**
     * 설치는 SQL 잠금 밖에서 조립한 불변 빈만 받으며 빈 레지스트리는 신규 접수만 닫는다.
     *
     * @param stories 실제 현재 세션·부모 인가 경계
     * @param db 같은 거래의 실제 JDBC 도구
     * @param runtimes 실제 runtime 잠금·조회 저장소
     * @param crypto 실제 개인 결과 GCM 서비스, null 불가
     * @param installations SQL 밖에서 조립한 설치 레지스트리, null 불가
     */
    public StoryBatchService(
            StoryService stories,
            JdbcTemplate db,
            GradeRuntimeRepository runtimes,
            CryptoService crypto,
            @Qualifier("gradeInstallations")
                    Map<String, InstalledRuntimeManifestVerifier> installations) {
        this.stories = stories;
        this.db = db;
        this.runtimes = runtimes;
        this.comparisons = new GradeBatchComparison(db, Objects.requireNonNull(crypto));
        this.frozenInputs = new GradeFrozenInputRepository(db);
        this.installations = Map.copyOf(installations);
    }

    /**
     * 정확한 다섯 입력을 경로와 함께 결속하여 모든 fixture의 STAGED 세 반복과 감사·영수증을 원자 생성한다.
     *
     * @param sid 현재 저장 세션 ID
     * @param actor 서버 행위자; 소유자도 전역·사건 REVIEW 필요
     * @param storyCode 부모 사건 코드
     * @param versionNo 양의 버전 번호
     * @param expectedRev 정규 십진 수정번호
     * @param snapshotId 양의 십진 사본 ID
     * @param runtimeConfigId 실제 등록 runtime 코드
     * @param purpose REVIEW 또는 AVAILABILITY
     * @param requestKey 전역 UUID v4 의도 키
     * @param requestId 서버 요청 UUID
     * @return 신규 또는 원래 안전 결과를 보존한 재생 결과
     * @throws AuthException 현재 인가·입력·설치·사본·충돌·필수 저장 실패 시
     */
    public ActionResult createBatch(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            String snapshotId,
            String runtimeConfigId,
            String purpose,
            UUID requestKey,
            UUID requestId) {
        long rev = decimal(expectedRev, false);
        long snapshot = decimal(snapshotId, true);
        uuid(requestKey);
        uuid(requestId);
        if (runtimeConfigId == null
                || !runtimeConfigId.matches("[A-Z0-9_]{1,80}")
                || !Set.of("REVIEW", "AVAILABILITY").contains(purpose == null ? "" : purpose))
            throw AuthException.badRequest("INVALID_REQUEST");
        ObjectNode input =
                object().put("storyCode", storyCode)
                        .put("versionNo", versionNo)
                        .put("expectedRev", expectedRev)
                        .put("snapshotId", snapshotId)
                        .put("runtimeConfigId", runtimeConfigId)
                        .put("purpose", purpose)
                        .put("requestKey", requestKey.toString());
        String hash = SnapshotJson.hash(input);
        try {
            return guarded(
                    () ->
                            stories.withBatchAction(
                                    sid,
                                    actor,
                                    storyCode,
                                    versionNo,
                                    () -> lockRuntimeCode(runtimeConfigId),
                                    (scope, runtime) ->
                                            create(
                                                    scope,
                                                    runtime,
                                                    actor,
                                                    storyCode,
                                                    versionNo,
                                                    rev,
                                                    snapshot,
                                                    purpose,
                                                    requestKey,
                                                    requestId,
                                                    hash,
                                                    false)));
        } catch (ReceiptRace race) {
            // 유니크 실패 거래는 완전히 종료되었다. 승자는 새 현재 인가 뒤에만 읽는다.
            return guarded(
                    () ->
                            stories.withBatchAction(
                                    sid,
                                    actor,
                                    storyCode,
                                    versionNo,
                                    () -> lockRuntimeCode(runtimeConfigId),
                                    (scope, runtime) ->
                                            create(
                                                    scope,
                                                    runtime,
                                                    actor,
                                                    storyCode,
                                                    versionNo,
                                                    rev,
                                                    snapshot,
                                                    purpose,
                                                    requestKey,
                                                    requestId,
                                                    hash,
                                                    true)));
        }
    }

    /**
     * 현재 REVIEW와 실제 부모 귀속으로 잠근 일관 사본만 읽고 필수 CONTENT_READ 감사를 확정한다.
     *
     * @param sid 현재 저장 세션 ID
     * @param actor 현재 인증한 서버 행위자; 전역·활성 사건 REVIEW 필수
     * @param storyCode 경로 사건 코드
     * @param versionNo 양의 버전 번호
     * @param batchKey 같은 부모의 UUID v4 집합 키
     * @param requestId 원문 없는 서버 요청 UUID
     * @return 고정 BatchDetail 허용 목록; 집계 상태·효력을 변경하지 않는다
     * @throws AuthException 현재 인가·다른 부모·손상된 증거·필수 감사 실패 시
     */
    public BatchDetail getBatchDetail(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            UUID batchKey,
            UUID requestId) {
        uuid(batchKey);
        uuid(requestId);
        return guarded(
                () ->
                        stories.withBatchAction(
                                sid,
                                actor,
                                storyCode,
                                versionNo,
                                () -> lockBatchRuntime(batchKey),
                                (scope, runtime) ->
                                        readBatchDetail(
                                                scope, runtime, storyCode, versionNo, batchKey,
                                                actor, requestId)));
    }

    /**
     * 현재 인가·runtime·부모 잠금이 끝난 같은 거래에서 전체 내구 비교를 재사용한다.
     *
     * @param scope 현재 전역·사건 REVIEW로 잠근 부모
     * @param runtime 원래 집합에 결속된 잠근 불변 설정
     * @param storyCode 실제 부모 코드
     * @param versionNo 실제 버전 번호
     * @param batchKey 같은 부모의 실제 집합 UUID
     * @param actor 현재 서버 관리자
     * @param requestId 필수 조회 감사의 서버 UUID
     * @return 복호화·완료 증거까지 검사한 비원문 상세; 상태·효력을 변경하지 않는다
     */
    BatchDetail readBatchDetail(
            ReviewScope scope,
            RuntimeRow runtime,
            String storyCode,
            int versionNo,
            UUID batchKey,
            AdminActor actor,
            UUID requestId) {
        return verifiedBatchRead(scope, runtime, storyCode, versionNo, batchKey, actor, requestId)
                .detail();
    }

    /** 비교 소유자의 같은 잠금 사실과 필수 감사만 근거 서비스에 전달한다. */
    GradeEvidenceRead readGradeEvidence(
            ReviewScope scope,
            RuntimeRow runtime,
            String code,
            int version,
            Map<String, Object> lockedBatch,
            AdminActor actor,
            UUID request) {
        GradeEvidenceRead proof =
                verifiedBatchRead(
                        scope,
                        runtime,
                        code,
                        version,
                        (UUID) lockedBatch.get("batch_key"),
                        actor,
                        request);
        if (!same(proof.batchIdentity(), batchIdentity(lockedBatch))) throw storage();
        verifyResolutionAdoption(number(lockedBatch, "snapshot_id"), number(lockedBatch, "id"));
        verifyGradeEvidenceRead(proof);
        return proof;
    }

    /** 이후 쓰기 뒤 감사와 실제 실행 전체를 새 감사 없이 대조한다. */
    void verifyGradeEvidenceRead(GradeEvidenceRead proof) {
        if (!same(proof.sourceFacts(), evidenceSource(proof.batchId()))
                || !same(
                        proof.readAudit(),
                        parse(
                                db.queryForObject(
                                        "SELECT to_jsonb(a)::text FROM test_audit a WHERE id=?",
                                        String.class,
                                        proof.readAuditId())))) throw storage();
    }

    /** 외부 DTO와 내부 근거는 한 번의 인증된 전체 비교를 공유한다. */
    private GradeEvidenceRead verifiedBatchRead(
            ReviewScope scope,
            RuntimeRow runtime,
            String storyCode,
            int versionNo,
            UUID batchKey,
            AdminActor actor,
            UUID requestId) {
        JsonNode before = history(scope, null, null, null);
        var batch = batch(scope, batchKey);
        if (runtime == null || number(batch, "runtime_id") != runtime.id()) throw storage();
        var saved = frozenInputs.loadSavedFacts(number(batch, "snapshot_id"));
        if (saved.storyId() != scope.storyId()
                || saved.versionId() != scope.versionId()
                || !storyCode.equals(saved.storyCode())
                || versionNo != saved.versionNo()) throw storage();
        var frozen = saved.frozen();
        requireBatchHashes(batch, frozen, runtime);
        var dataset = saved.dataset();
        var jobs = jobs(number(batch, "id"));
        if (jobs.size() != number(batch, "expected_count")
                || jobs.size() != samples(frozen).size() * 3
                || number(batch, "repeat_count") != 3) throw storage();
        // 현재 대상 권한과 필수 감사를 먼저 확정한 거래 안에서만 내부 결과를 검증한다.
        long contentReadAuditId =
                audit(
                        actor,
                        "CONTENT_READ",
                        "batch",
                        "batch:" + batchKey,
                        requestId,
                        object().put("batchKey", batchKey.toString()));
        List<BatchItem> items = new ArrayList<>();
        ObjectNode manifest = object().put("formatNo", 1).put("domain", "GRADE_EVIDENCE_BATCH-v1");
        manifest.put("runtimeConfigId", runtime.code());
        manifest.set("batch", batchIdentity(batch));
        var manifestJobs = manifest.putArray("jobs");
        List<ObjectNode> jobProofs = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int completed = 0;
        int failed = 0;
        int unresolved = 0;
        for (var job : jobs) {
            String code = (String) job.get("sample_code");
            int repeat = (int) number(job, "repeat_no");
            if (repeat < 1 || repeat > 3 || !identities.add(code + ":" + repeat)) throw storage();
            requireJobHashes(job, batch, frozen);
            SelectedSample sample = dataset.select(code);
            String state = (String) job.get("state");
            String comparison =
                    comparisons
                            .compare(
                                    job,
                                    sample,
                                    runtime,
                                    dataset.gradingSnapshot(),
                                    contentReadAuditId)
                            .comparison();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state)) completed++;
            if ("FAIL".equals(comparison)) failed++;
            if ("PENDING".equals(comparison)) unresolved++;
            items.add(
                    new BatchItem(
                            code,
                            repeat,
                            (UUID) job.get("job_key"),
                            state,
                            comparison,
                            (String) job.get("error_code")));
            jobProofs.add(evidenceJob(job, sample.kind(), comparison));
        }
        jobProofs.sort(
                Comparator.comparing(
                                (ObjectNode value) -> value.path("sampleCode").textValue(),
                                CommonUtil::compareCodePoints)
                        .thenComparingInt(value -> value.path("repeatNo").intValue()));
        jobProofs.forEach(manifestJobs::add);
        items.sort(
                Comparator.comparing(BatchItem::sampleCode, CommonUtil::compareCodePoints)
                        .thenComparingInt(BatchItem::repeatNo));
        Boolean passed = (Boolean) batch.get("passed_yn");
        if (passed != null
                && (!"COMPLETED".equals(batch.get("state"))
                        || unresolved != 0
                        || passed != (failed == 0))) throw storage();
        Instant created = instant(batch.get("created_at"));
        BatchDetail detail =
                new BatchDetail(
                        batchKey,
                        (String) batch.get("purpose"),
                        Long.toString(number(batch, "snapshot_id")),
                        runtime.code(),
                        Long.toString(number(batch, "runtime_epoch")),
                        (String) batch.get("dataset_hash"),
                        (String) batch.get("state"),
                        passed,
                        3,
                        jobs.size(),
                        completed,
                        failed,
                        unresolved,
                        created,
                        created.plusSeconds(86400),
                        instant(batch.get("ended_at")),
                        instant(batch.get("valid_until")),
                        List.copyOf(items),
                        requestId);
        if (!same(before, history(scope, null, null, null))
                || !runtime.equals(
                        runtimes.getRuntimeDetail(runtime.id())
                                .orElseThrow(StoryBatchService::storage))) throw storage();
        return new GradeEvidenceRead(
                detail,
                number(batch, "id"),
                batchIdentity(batch),
                SnapshotJson.hash(manifest),
                evidenceSource(number(batch, "id")),
                contentReadAuditId,
                parse(
                        db.queryForObject(
                                "SELECT to_jsonb(a)::text FROM test_audit a WHERE id=?",
                                String.class,
                                contentReadAuditId)));
    }

    /** 집합 명세는 불변 해시·실제 식별자·시각만 보존하며 원문을 포함하지 않는다. */
    private static ObjectNode batchIdentity(Map<String, Object> batch) {
        ObjectNode node = object();
        for (String field : List.of("id", "snapshot_id", "runtime_id", "runtime_epoch"))
            node.put(field, Long.toString(number(batch, field)));
        for (String field :
                List.of(
                        "batch_key",
                        "purpose",
                        "payload_hash",
                        "dataset_hash",
                        "rubric_hash",
                        "config_hash",
                        "state")) node.put(field, batch.get(field).toString());
        for (String field : List.of("repeat_count", "expected_count"))
            node.put(field, number(batch, field));
        if (batch.get("passed_yn") == null) node.putNull("passed_yn");
        else node.put("passed_yn", (Boolean) batch.get("passed_yn"));
        for (String field : List.of("created_at", "ended_at"))
            node.put(field, batch.get(field) == null ? null : instant(batch.get(field)).toString());
        return node;
    }

    /** 비교가 인증한 실제 작업과 모든 복합 시도 키 및 안전 이벤트 투영만 해시로 결속한다. */
    private ObjectNode evidenceJob(Map<String, Object> job, String fixtureKind, String comparison) {
        ObjectNode node =
                object().put("id", Long.toString(number(job, "id")))
                        .put("jobKey", job.get("job_key").toString())
                        .put("sampleCode", (String) job.get("sample_code"))
                        .put("repeatNo", number(job, "repeat_no"))
                        .put("fixtureKind", fixtureKind)
                        .put("comparison", comparison);
        for (String field : List.of("state", "input_hash", "result_hash", "error_code"))
            node.put(field, (String) job.get(field));
        node.put("callCount", number(job, "call_count"));
        node.put("leaseGen", Long.toString(number(job, "lease_gen")));
        for (String field : List.of("accepted_at", "deadline_at"))
            node.put(field, job.get(field) == null ? null : instant(job.get(field)).toString());
        var attempts = node.putArray("attempts");
        for (var row :
                db.queryForList(
                        "SELECT * FROM grade_attempt WHERE job_id=? ORDER BY attempt_no FOR UPDATE",
                        number(job, "id"))) {
            ObjectNode attempt =
                    object().put("jobId", Long.toString(number(row, "job_id")))
                            .put("attemptNo", number(row, "attempt_no"))
                            .put("leaseGen", Long.toString(number(row, "lease_gen")))
                            .put("state", (String) row.get("state"))
                            .put("errorCode", (String) row.get("error_code"))
                            .put("outputHash", (String) row.get("output_hash"))
                            .put(
                                    "completionHash",
                                    row.get("completion_data") == null
                                            ? null
                                            : SnapshotJson.hash(parse(row.get("completion_data"))));
            for (String field : List.of("started_at", "ended_at"))
                attempt.put(
                        field, row.get(field) == null ? null : instant(row.get(field)).toString());
            attempts.add(attempt);
        }
        var events = node.putArray("events");
        for (var row :
                db.queryForList(
                        "SELECT * FROM grade_event WHERE job_id=? ORDER BY id FOR UPDATE",
                        number(job, "id"))) {
            ObjectNode safe =
                    object().put("id", Long.toString(number(row, "id")))
                            .put("jobId", Long.toString(number(row, "job_id")))
                            .put("eventKind", (String) row.get("event_kind"))
                            .put("actorKind", (String) row.get("actor_kind"))
                            .put("actorKey", (String) row.get("actor_key"))
                            .put("commandKey", row.get("command_key").toString())
                            .put("commandHash", (String) row.get("command_hash"))
                            .put("createdAt", instant(row.get("created_at")).toString());
            if (row.get("attempt_no") == null) safe.putNull("attemptNo");
            else safe.put("attemptNo", number(row, "attempt_no"));
            JsonNode detail = parse(row.get("detail"));
            ObjectNode checked = object();
            for (String field :
                    List.of(
                            "leaseGen",
                            "beforeJob",
                            "afterJob",
                            "beforeAttempt",
                            "afterAttempt",
                            "reason",
                            "outputHash",
                            "resultHash")) {
                JsonNode value = detail.get(field);
                if (value == null || value.isNull()) checked.putNull(field);
                else if ("leaseGen".equals(field)
                        && value.isIntegralNumber()
                        && value.canConvertToLong())
                    checked.put(field, Long.toString(value.longValue()));
                else if (Set.of("outputHash", "resultHash").contains(field)
                        && value.isTextual()
                        && value.textValue().matches("[0-9a-f]{64}")) checked.set(field, value);
                else if (!Set.of("leaseGen", "outputHash", "resultHash").contains(field)
                        && value.isTextual()
                        && value.textValue().matches("[A-Z0-9_]{1,40}")) checked.set(field, value);
                else checked.putNull(field);
            }
            safe.put("evidenceHash", SnapshotJson.hash(checked));
            events.add(safe);
        }
        return node;
    }

    /** 비공개 전체 행 해시는 후속 변조 방어용이며 저장 근거나 HTTP 원문으로 내보내지 않는다. */
    private JsonNode evidenceSource(long batch) {
        ObjectNode result = object();
        for (String table : List.of("grade_batch", "grade_job", "grade_attempt", "grade_event")) {
            String relation =
                    switch (table) {
                        case "grade_batch" -> "t.id=?";
                        case "grade_job" -> "t.batch_id=?";
                        default -> "t.job_id IN (SELECT id FROM grade_job WHERE batch_id=?)";
                    };
            result.set(
                    table,
                    parse(
                            db.queryForObject(
                                    "SELECT"
                                        + " coalesce(jsonb_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex')"
                                        + " ORDER BY to_jsonb(t)::text COLLATE"
                                        + " \"C\"),'[]'::jsonb)::text FROM "
                                            + table
                                            + " t WHERE "
                                            + relation,
                                    String.class,
                                    batch)));
        }
        return result;
    }

    record GradeEvidenceRead(
            BatchDetail detail,
            long batchId,
            JsonNode batchIdentity,
            String manifestHash,
            JsonNode sourceFacts,
            long readAuditId,
            JsonNode readAudit) {}

    /**
     * 현재 부모 인가 이후에만 전역 키를 조회하며 재생은 신규 접수 조건을 재검사하지 않는다.
     *
     * @param scope 실제 현재 인가 부모
     * @param runtime 실제 잠근 실행 설정, 없으면 신규 접수 거절
     * @param actor 서버 현재 관리자
     * @param code 실제 경로 사건 코드
     * @param version 실제 버전 번호
     * @param rev 요청한 수정번호
     * @param snapshot 요청한 실제 사본 ID
     * @param purpose REVIEW 또는 AVAILABILITY
     * @param key 전역 의도 UUID
     * @param requestId 서버 요청 UUID
     * @param hash 정규 요청 해시
     * @param receiptOnly 경쟁 이후 재생만 허용하면 true
     * @return 실제 최초 결과 또는 현재 인가된 재생 결과
     * @throws AuthException 권한·설치·정책·저장 사실·필수 감사 불일치
     */
    private ActionResult create(
            ReviewScope scope,
            RuntimeRow runtime,
            AdminActor actor,
            String code,
            int version,
            long rev,
            long snapshot,
            String purpose,
            UUID key,
            UUID requestId,
            String hash,
            boolean receiptOnly) {
        String scopeKey = "version:" + scope.versionId();
        var receipts = db.queryForList("SELECT * FROM test_action WHERE request_key=?", key);
        if (!receipts.isEmpty()) {
            var receipt = receipts.getFirst();
            if (receipt.get("admin_id") == null
                    || receipt.get("member_id") != null
                    || number(receipt, "admin_id") != actor.accountId()
                    || !"BATCH_CREATE".equals(receipt.get("action"))
                    || !scopeKey.equals(receipt.get("scope_key"))
                    || !hash.equals(receipt.get("request_hash")))
                throw AuthException.conflict("REQUEST_KEY_CONFLICT");
            JsonNode stored = parse(receipt.get("result_data"));
            exact(stored, "action", "replayed", "changed", "original", "current", "requestId");
            if (!"BATCH_CREATE".equals(stored.path("action").asText())
                    || !stored.path("changed").isBoolean()
                    || !stored.path("changed").booleanValue()
                    || !stored.path("replayed").isBoolean()
                    || stored.path("replayed").booleanValue()) throw storage();
            SafeResult original = safe(stored.get("original"));
            if (!same(stored.get("original"), stored.get("current"))) throw storage();
            try {
                uuid(UUID.fromString(stored.path("requestId").textValue()));
            } catch (RuntimeException invalid) {
                throw storage();
            }
            var batch = batch(scope, original.batchKey());
            if (!original.snapshotId().equals(Long.toString(number(batch, "snapshot_id")))
                    || !original.createdAt().equals(instant(batch.get("created_at"))))
                throw storage();
            return new ActionResult(
                    "BATCH_CREATE", true, false, original, projection(scope, batch), requestId);
        }
        if (receiptOnly) throw storage();
        if (scope.rev() != rev) throw AuthException.conflict("EDIT_CONFLICT");
        if (db.queryForObject(
                        "SELECT count(*) FROM review_snapshot WHERE id=? AND version_id=?",
                        Integer.class,
                        snapshot,
                        scope.versionId())
                != 1) throw missing();
        if (!Objects.equals(scope.snapshotId(), snapshot)
                || !("REVIEW".equals(scope.status())
                        || "AVAILABILITY".equals(purpose)
                                && Set.of("READY", "PUBLISHED").contains(scope.status())))
            throw AuthException.conflict("STATE_CONFLICT");
        if (runtime == null || !"AVAILABLE".equals(runtime.state()))
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        var verifier = installations.get(runtime.code());
        if (verifier == null) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        var verified = verifier.verify(runtime);
        if (!scope.policyCode().equals(verified.profile().policyCode())) throw storage();
        FrozenSnapshot frozen = frozen(scope, snapshot, code, version);
        if (!scope.policyCode()
                .equals(frozen.payload().path("policy").path("policyCode").textValue()))
            throw storage();
        List<String> samples = samples(frozen);
        int expected = Math.multiplyExact(samples.size(), 3);
        JsonNode history = history(scope, null, null, null);
        OffsetDateTime created =
                db.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (created == null) throw storage();
        UUID batchKey = UUID.randomUUID();
        Long id =
                db.queryForObject(
                        """
                        INSERT INTO grade_batch(batch_key,snapshot_id,runtime_id,purpose,dataset_hash,rubric_hash,
                            payload_hash,config_hash,runtime_epoch,state,repeat_count,expected_count,created_by,created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,'RUNNING',3,?,?,?) RETURNING id
                        """,
                        Long.class,
                        batchKey,
                        snapshot,
                        runtime.id(),
                        purpose,
                        frozen.datasetHash(),
                        frozen.rubricHash(),
                        frozen.payloadHash(),
                        runtime.configHash(),
                        runtime.epoch(),
                        expected,
                        actor.accountId(),
                        created);
        if (id == null) throw storage();
        List<ObjectNode> expectedJobs = new ArrayList<>();
        for (String sample : samples) {
            for (int repeat = 1; repeat <= 3; repeat++) {
                UUID jobKey = UUID.randomUUID();
                Long jobId =
                        db.queryForObject(
                                """
                                INSERT INTO grade_job(job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,
                                    state,input_hash,config_hash,rubric_hash,next_run_at,created_at,updated_at)
                                VALUES (?,?,?,?,?,?,'STAGED',?,?,?,?,?,?) RETURNING id
                                """,
                                Long.class,
                                jobKey,
                                snapshot,
                                runtime.id(),
                                id,
                                sample,
                                repeat,
                                frozen.inputHash(sample),
                                runtime.configHash(),
                                frozen.rubricHash(),
                                created,
                                created,
                                created);
                if (jobId == null) throw storage();
                expectedJobs.add(
                        object().put("id", jobId)
                                .put("jobKey", jobKey.toString())
                                .put("sampleCode", sample)
                                .put("repeatNo", repeat));
            }
        }
        SafeResult original =
                new SafeResult(
                        batchKey,
                        Long.toString(scope.rev()),
                        Long.toString(snapshot),
                        "RUNNING",
                        created.toInstant());
        ActionResult result =
                new ActionResult("BATCH_CREATE", false, true, original, original, requestId);
        String resultJson = encode(actionJson(result));
        Long receiptId;
        try {
            receiptId =
                    db.queryForObject(
                            """
                            INSERT INTO test_action(request_key,admin_id,action,scope_key,request_hash,result_data,created_at)
                            VALUES (?,?,'BATCH_CREATE',?,?,?::jsonb,?) RETURNING id
                            """,
                            Long.class,
                            key,
                            actor.accountId(),
                            scopeKey,
                            hash,
                            resultJson,
                            created);
        } catch (DataAccessException failure) {
            if (uniqueReceipt(failure)) throw new ReceiptRace();
            throw failure;
        }
        if (receiptId == null) throw storage();
        ObjectNode auditDetail =
                object().put("batchKey", batchKey.toString())
                        .put("snapshotId", Long.toString(snapshot))
                        .put("editRev", Long.toString(scope.rev()));
        long auditId = audit(actor, "BATCH_CREATE", "version", scopeKey, requestId, auditDetail);
        var storedBatch = batch(scope, batchKey);
        if (number(storedBatch, "id") != id
                || number(storedBatch, "runtime_id") != runtime.id()
                || number(storedBatch, "runtime_epoch") != runtime.epoch()
                || number(storedBatch, "snapshot_id") != snapshot
                || !purpose.equals(storedBatch.get("purpose"))
                || !"RUNNING".equals(storedBatch.get("state"))
                || number(storedBatch, "expected_count") != expected
                || number(storedBatch, "repeat_count") != 3
                || number(storedBatch, "created_by") != actor.accountId()
                || !created.toInstant().equals(instant(storedBatch.get("created_at")))
                || storedBatch.get("passed_yn") != null
                || storedBatch.get("valid_until") != null
                || storedBatch.get("ended_at") != null) throw storage();
        requireBatchHashes(storedBatch, frozen, runtime);
        var storedJobs = jobs(id);
        if (storedJobs.size() != expectedJobs.size()) throw storage();
        for (int i = 0; i < storedJobs.size(); i++) {
            var job = storedJobs.get(i);
            var identity = expectedJobs.get(i);
            requireJobHashes(job, storedBatch, frozen);
            if (number(job, "id") != identity.get("id").longValue()
                    || !job.get("job_key").toString().equals(identity.get("jobKey").textValue())
                    || !job.get("sample_code").equals(identity.get("sampleCode").textValue())
                    || number(job, "repeat_no") != identity.get("repeatNo").intValue()
                    || !"STAGED".equals(job.get("state"))
                    || number(job, "call_count") != 0
                    || number(job, "lease_gen") != 0
                    || job.get("accepted_at") != null
                    || job.get("deadline_at") != null
                    || job.get("lease_until") != null
                    || job.get("worker_key") != null
                    || !Boolean.FALSE.equals(job.get("has_result_cipher"))
                    || job.get("result_data") != null
                    || job.get("result_hash") != null
                    || job.get("error_code") != null
                    || !created.toInstant().equals(instant(job.get("next_run_at")))
                    || !created.toInstant().equals(instant(job.get("created_at")))
                    || !created.toInstant().equals(instant(job.get("updated_at")))) throw storage();
        }
        if (db.queryForObject(
                                "SELECT count(*) FROM grade_attempt a JOIN grade_job j ON"
                                        + " j.id=a.job_id WHERE j.batch_id=?",
                                Long.class,
                                id)
                        != 0
                || db.queryForObject(
                                "SELECT count(*) FROM grade_event e JOIN grade_job j ON"
                                        + " j.id=e.job_id WHERE j.batch_id=?",
                                Long.class,
                                id)
                        != 0
                || db.queryForObject(
                                "SELECT count(*) FROM execution_issue WHERE batch_id=?",
                                Long.class,
                                id)
                        != 0) throw storage();
        var storedReceipt = db.queryForMap("SELECT * FROM test_action WHERE id=?", receiptId);
        if (!key.equals(storedReceipt.get("request_key"))
                || number(storedReceipt, "admin_id") != actor.accountId()
                || !"BATCH_CREATE".equals(storedReceipt.get("action"))
                || !scopeKey.equals(storedReceipt.get("scope_key"))
                || !hash.equals(storedReceipt.get("request_hash"))
                || !same(parse(storedReceipt.get("result_data")), actionJson(result))
                || !created.toInstant().equals(instant(storedReceipt.get("created_at"))))
            throw storage();
        if (!same(history, history(scope, id, receiptId, auditId))) throw storage();
        if (!same(frozen.payload(), frozen(scope, snapshot, code, version).payload()))
            throw storage();
        RuntimeRow storedRuntime =
                runtimes.getRuntimeDetail(runtime.id()).orElseThrow(StoryBatchService::storage);
        if (!runtime.equals(storedRuntime)) throw storage();
        return result;
    }

    /**
     * 해소 근거의 현재 설정을 실제 설치·원래 정책에 결속하며 운영 효력을 발급하지 않는다.
     *
     * @param runtime 현재 잠근 후속 실행 설정
     * @param policyCode 현재 부모의 정책 코드
     * @throws AuthException 설치·상태 불일치이면 RUNTIME_UNAVAILABLE, 정책 불일치이면 EVIDENCE_INCOMPLETE
     */
    void verifyResolutionRuntime(RuntimeRow runtime, String policyCode) {
        if (runtime == null || !"AVAILABLE".equals(runtime.state()))
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        var verifier = installations.get(runtime.code());
        if (verifier == null) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        InstalledRuntimeManifestVerifier.VerifiedRuntime verified;
        try {
            verified = verifier.verify(runtime);
        } catch (IllegalStateException invalid) {
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        }
        if (!policyCode.equals(verified.profile().policyCode()))
            throw AuthException.unprocessable("EVIDENCE_INCOMPLETE");
    }

    /**
     * 인증된 전체 비교 이후 정상 fixture의 실제 관측 버전·채택 자격도 후속 근거에 요구한다.
     *
     * @param snapshotId 현재 인가·귀속 검증을 마친 고정 사본 ID
     * @param batchId 같은 사본의 잠근 후속 집합 ID
     * @throws AuthException 정상 결과의 관측 버전 미확인/불일치이면 EVIDENCE_INCOMPLETE
     */
    void verifyResolutionAdoption(long snapshotId, long batchId) {
        var dataset = frozenInputs.loadSavedFacts(snapshotId).dataset();
        for (var job : jobs(batchId)) {
            if (!"GRADED".equals(dataset.select((String) job.get("sample_code")).kind())) continue;
            JsonNode summary = parse(job.get("result_data"));
            if (!summary.path("providerVersionMatched").isBoolean()
                    || !summary.path("providerVersionMatched").booleanValue()
                    || !summary.path("fixtureAdoptionEligible").isBoolean()
                    || !summary.path("fixtureAdoptionEligible").booleanValue())
                throw AuthException.unprocessable("EVIDENCE_INCOMPLETE");
        }
    }

    /** 실제 코드→ID 발견 뒤 해당 행만 상위 부모보다 먼저 잠그고 결속을 다시 검사한다. */
    private RuntimeRow lockRuntimeCode(String code) {
        var ids = db.queryForList("SELECT id FROM grade_runtime WHERE code=?", Long.class, code);
        if (ids.isEmpty()) return null;
        RuntimeRow row =
                runtimes.lockRuntime(ids.getFirst()).orElseThrow(StoryBatchService::storage);
        if (!code.equals(row.code())) throw storage();
        return row;
    }

    /** 상세 조회도 runtime을 선발견하되 부모 인가 전 상세 또는 영수증을 노출하지 않는다. */
    private RuntimeRow lockBatchRuntime(UUID key) {
        var ids =
                db.queryForList(
                        "SELECT runtime_id FROM grade_batch WHERE batch_key=?", Long.class, key);
        return ids.isEmpty()
                ? null
                : runtimes.lockRuntime(ids.getFirst()).orElseThrow(StoryBatchService::storage);
    }

    /**
     * 저장 전체 사실과 현재 인가된 물리 부모를 비교하며 역사 조회에는 현재 초안 정책을 요구하지 않는다.
     *
     * @param scope 현재 인가된 실제 부모
     * @param snapshot 양의 저장 사본 ID
     * @param code 실제 사건 코드
     * @param version 실제 버전 번호
     * @return 검증된 전체 사본
     * @throws AuthException 부모 또는 저장 사실 불일치
     */
    private FrozenSnapshot frozen(ReviewScope scope, long snapshot, String code, int version) {
        var saved = frozenInputs.loadSavedFacts(snapshot);
        if (saved.versionId() != scope.versionId()
                || !code.equals(saved.storyCode())
                || version != saved.versionNo()) throw storage();
        return saved.frozen();
    }

    /** 부모 소속을 SQL 조건에 결속한 뒤 집합을 잠근다. */
    private Map<String, Object> batch(ReviewScope scope, UUID key) {
        var rows =
                db.queryForList(
                        "SELECT b.* FROM grade_batch b JOIN review_snapshot s ON s.id=b.snapshot_id"
                                + " WHERE b.batch_key=? AND s.version_id=? FOR UPDATE OF b",
                        key,
                        scope.versionId());
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    /** 전체 자식을 실제 id 정순으로 잠그며 응답 표시 순서는 잠금 이후에 별도로 정한다. */
    private List<Map<String, Object>> jobs(long batch) {
        return db.queryForList(
                """
                SELECT id,job_key,snapshot_id,runtime_id,batch_id,sample_code,repeat_no,state,
                    accepted_at,deadline_at,call_count,lease_gen,lease_until,worker_key,next_run_at,
                    input_hash,config_hash,rubric_hash,result_cipher,result_cipher IS NOT NULL AS has_result_cipher,
                    result_data::text AS result_data,result_hash,error_code,created_at,updated_at
                FROM grade_job WHERE batch_id=? ORDER BY id FOR UPDATE
                """,
                batch);
    }

    /** 전체 사본 코드만 정순으로 나열하며 임의 표본 제한을 두지 않는다. */
    private static List<String> samples(FrozenSnapshot frozen) {
        List<String> result = new ArrayList<>();
        for (JsonNode row : frozen.payload().path("resources").path("gradeSamples"))
            result.add(row.path("code").textValue());
        return result.stream().sorted(CommonUtil::compareCodePoints).toList();
    }

    /** 집합의 모든 원본 해시·실제 runtime 결속을 검사하며 역사 조회에서 가용성·설치를 요구하지 않는다. */
    private static void requireBatchHashes(
            Map<String, Object> batch, FrozenSnapshot frozen, RuntimeRow runtime) {
        if (!frozen.payloadHash().equals(batch.get("payload_hash"))
                || !frozen.datasetHash().equals(batch.get("dataset_hash"))
                || !frozen.rubricHash().equals(batch.get("rubric_hash"))
                || !runtime.configHash().equals(batch.get("config_hash"))
                || !SnapshotJson.hash(parse(runtime.configJson())).equals(runtime.configHash()))
            throw storage();
    }

    /** 각 작업은 같은 집합·사본·runtime과 선택 입력/구성/채점표 해시를 보존해야 한다. */
    private static void requireJobHashes(
            Map<String, Object> job, Map<String, Object> batch, FrozenSnapshot frozen) {
        for (String field : List.of("snapshot_id", "runtime_id", "config_hash", "rubric_hash"))
            if (!Objects.equals(job.get(field), batch.get(field))) throw storage();
        if (number(job, "batch_id") != number(batch, "id")
                || !frozen.inputHash((String) job.get("sample_code")).equals(job.get("input_hash")))
            throw storage();
    }

    /** 안전 원래 결과는 실제 최초 집합 식별자·부모 수정번호·생성 시각만 포함한다. */
    private static SafeResult projection(ReviewScope scope, Map<String, Object> batch) {
        return new SafeResult(
                (UUID) batch.get("batch_key"),
                Long.toString(scope.rev()),
                Long.toString(number(batch, "snapshot_id")),
                (String) batch.get("state"),
                instant(batch.get("created_at")));
    }

    /** 새 감사를 실제 저장 열 전체와 대조하며 원문·사용자 scope는 받지 않는다. */
    private long audit(
            AdminActor actor,
            String action,
            String kind,
            String scope,
            UUID request,
            ObjectNode detail) {
        UUID key = UUID.randomUUID();
        OffsetDateTime time = db.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        Long id =
                db.queryForObject(
                        """
                        INSERT INTO test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,
                            request_id,phase,business_result,detail,created_at)
                        VALUES (?,'ADMIN',?,?,?,?,?,'RESULT','SUCCESS',?::jsonb,?) RETURNING id
                        """,
                        Long.class,
                        key,
                        actor.accountKey().toString(),
                        action,
                        kind,
                        scope,
                        request,
                        encode(detail),
                        time);
        if (id == null || time == null) throw storage();
        var row = db.queryForMap("SELECT * FROM test_audit WHERE id=?", id);
        if (!key.equals(row.get("event_key"))
                || !"ADMIN".equals(row.get("actor_kind"))
                || !actor.accountKey().toString().equals(row.get("actor_ref"))
                || !action.equals(row.get("action"))
                || !kind.equals(row.get("scope_kind"))
                || !scope.equals(row.get("scope_key"))
                || !request.equals(row.get("request_id"))
                || !"RESULT".equals(row.get("phase"))
                || !"SUCCESS".equals(row.get("business_result"))
                || !same(detail, parse(row.get("detail")))
                || !time.toInstant().equals(instant(row.get("created_at")))) throw storage();
        return id;
    }

    /** 같은 부모의 기존 실행·영수증·감사 행은 ID와 전체 행 해시로 보존을 확인한다. */
    private JsonNode history(ReviewScope scope, Long batch, Long receipt, Long audit) {
        ObjectNode result = object();
        for (String table :
                List.of(
                        "grade_batch",
                        "grade_job",
                        "grade_attempt",
                        "grade_event",
                        "execution_issue")) {
            String join =
                    switch (table) {
                        case "grade_batch" -> "JOIN review_snapshot s ON s.id=t.snapshot_id";
                        case "grade_job", "execution_issue" ->
                                "JOIN grade_batch b ON b.id=t.batch_id JOIN review_snapshot s ON"
                                        + " s.id=b.snapshot_id";
                        default ->
                                "JOIN grade_job j ON j.id=t.job_id JOIN grade_batch b ON"
                                        + " b.id=j.batch_id JOIN review_snapshot s ON"
                                        + " s.id=b.snapshot_id";
                    };
            String batchId =
                    "grade_batch".equals(table)
                            ? "t.id"
                            : Set.of("grade_job", "execution_issue").contains(table)
                                    ? "t.batch_id"
                                    : "j.batch_id";
            result.set(
                    table,
                    parse(
                            db.queryForObject(
                                    "SELECT"
                                        + " coalesce(jsonb_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex')"
                                        + " ORDER BY to_jsonb(t)::text COLLATE"
                                        + " \"C\"),'[]'::jsonb)::text FROM "
                                            + table
                                            + " t "
                                            + join
                                            + " WHERE s.version_id=? AND (?::bigint IS NULL OR "
                                            + batchId
                                            + "<>?)",
                                    String.class,
                                    scope.versionId(),
                                    batch,
                                    batch)));
        }
        result.set(
                "receipts",
                parse(
                        db.queryForObject(
                                "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY"
                                    + " id),'[]'::jsonb)::text FROM test_action t WHERE scope_key=?"
                                    + " AND (?::bigint IS NULL OR id<>?)",
                                String.class,
                                "version:" + scope.versionId(),
                                receipt,
                                receipt)));
        result.set(
                "audits",
                parse(
                        db.queryForObject(
                                "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY"
                                    + " id),'[]'::jsonb)::text FROM test_audit t WHERE scope_key=?"
                                    + " AND (?::bigint IS NULL OR id<>?)",
                                String.class,
                                "version:" + scope.versionId(),
                                audit,
                                audit)));
        return result;
    }

    /** 원래 안전 DTO만 엄격하게 해석하며 payload나 새로운 임의 필드를 수용하지 않는다. */
    private static SafeResult safe(JsonNode value) {
        try {
            exact(value, "batchKey", "editRev", "snapshotId", "state", "createdAt");
            UUID key = UUID.fromString(value.path("batchKey").textValue());
            uuid(key);
            decimal(value.path("editRev").textValue(), false);
            decimal(value.path("snapshotId").textValue(), true);
            if (!"RUNNING".equals(value.path("state").textValue())) throw storage();
            return new SafeResult(
                    key,
                    value.path("editRev").textValue(),
                    value.path("snapshotId").textValue(),
                    value.path("state").textValue(),
                    Instant.parse(value.path("createdAt").textValue()));
        } catch (RuntimeException invalid) {
            throw storage();
        }
    }

    /** Jackson Java-time 설정에 의존하지 않는 고정 영수증 JSON이다. */
    private static ObjectNode actionJson(ActionResult result) {
        ObjectNode node =
                object().put("action", result.action())
                        .put("replayed", result.replayed())
                        .put("changed", result.changed());
        node.set("original", safeJson(result.original()));
        node.set("current", safeJson(result.current()));
        return node.put("requestId", result.requestId().toString());
    }

    /** 허용된 안전 식별자·수정번호·상태·UTC 생성 시각만 직렬화한다. */
    private static ObjectNode safeJson(SafeResult value) {
        return object().put("batchKey", value.batchKey().toString())
                .put("editRev", value.editRev())
                .put("snapshotId", value.snapshotId())
                .put("state", value.state())
                .put("createdAt", value.createdAt().toString());
    }

    /** 저장 실패는 원인·SQL·입력 없이 고정 코드로 닫고 실제 키 유니크 경쟁만 새 거래로 넘긴다. */
    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (ReceiptRace race) {
            throw race;
        } catch (AuthException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            String state = sqlState(failure);
            throw AuthException.unavailable(
                    Set.of("55P03", "40P01").contains(state == null ? "" : state)
                            ? "STORY_BUSY"
                            : "STORY_UNAVAILABLE");
        } catch (RuntimeException failure) {
            throw storage();
        }
    }

    /** 전역 영수증 제약의 실제 PostgreSQL 유니크 오류만 재생 경쟁으로 인정한다. */
    private static boolean uniqueReceipt(Throwable failure) {
        for (Throwable error = failure; error != null; error = error.getCause())
            if (error instanceof org.postgresql.util.PSQLException pg
                    && "23505".equals(pg.getSQLState())
                    && pg.getServerErrorMessage() != null
                    && "uk_test_action_request".equals(pg.getServerErrorMessage().getConstraint()))
                return true;
        return false;
    }

    /** 오류 상세를 반환하지 않고 잠금 대기 코드만 분류한다. */
    private static String sqlState(Throwable failure) {
        for (Throwable error = failure; error != null; error = error.getCause())
            if (error instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    /** 정규 bigint 문자열이며 수정번호만 0을 허용한다. */
    private static long decimal(String value, boolean positive) {
        if (value == null || !value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*"))
            throw AuthException.badRequest("INVALID_REQUEST");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    /** UUID v4 이외의 API 식별자를 거절한다. */
    private static void uuid(UUID value) {
        if (value == null || value.version() != 4 || value.variant() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 저장 JSON은 공유 canonical parser로만 읽는다. */
    private static JsonNode parse(Object value) {
        if (value == null) throw storage();
        return SnapshotJson.parse(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 저장 JSON을 canonical 바이트로 직렬화한다. */
    private static String encode(JsonNode value) {
        return new String(SnapshotJson.encode(value), StandardCharsets.UTF_8);
    }

    /** 키 집합이 정확히 같은 객체만 인정한다. */
    private static void exact(JsonNode value, String... fields) {
        if (value == null || !value.isObject() || value.size() != fields.length) throw storage();
        for (String field : fields) if (!value.has(field)) throw storage();
    }

    /** 숫자형 DB 식별자는 문자열로 추측하지 않는다. */
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** JDBC timestamptz를 UTC 순간으로 투영하고 null 완료 시각은 보존한다. */
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime time) return time.toInstant();
        throw storage();
    }

    /** 의미 JSON 표준 바이트로 비교한다. */
    private static boolean same(JsonNode left, JsonNode right) {
        return left != null
                && right != null
                && java.util.Arrays.equals(SnapshotJson.encode(left), SnapshotJson.encode(right));
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static AuthException storage() {
        return AuthException.unavailable("STORY_UNAVAILABLE");
    }

    private static AuthException missing() {
        return new AuthException(404, "NOT_FOUND", "NOT_FOUND");
    }

    private static final class ReceiptRace extends RuntimeException {
        private ReceiptRace() {
            super(null, null, false, false);
        }
    }

    public record SafeResult(
            UUID batchKey, String editRev, String snapshotId, String state, Instant createdAt) {}

    public record ActionResult(
            String action,
            boolean replayed,
            boolean changed,
            SafeResult original,
            SafeResult current,
            UUID requestId) {}

    public record BatchItem(
            String sampleCode,
            int repeatNo,
            UUID jobKey,
            String state,
            String comparison,
            String errorCode) {}

    public record BatchDetail(
            UUID batchKey,
            String purpose,
            String snapshotId,
            String runtimeConfigId,
            String runtimeEpoch,
            String datasetHash,
            String state,
            Boolean passed,
            int repeatCount,
            int totalJobs,
            int completedJobs,
            int failedComparisons,
            int unresolvedJobs,
            Instant createdAt,
            Instant batchDeadline,
            Instant completedAt,
            Instant validUntil,
            List<BatchItem> items,
            UUID requestId) {}
}
