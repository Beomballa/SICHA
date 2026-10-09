package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;
import com.reasoning.common.story.service.StoryService.ReviewScope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 실제 전체 GRADE 비교만 불변 근거로 봉인한다. READY·공개·운영 효력을 발급하지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryGradeEvidenceService {
    private static final String ACTION = "EVIDENCE_CREATE";
    private static final String MESSAGE = "Verified GRADE execution evidence";
    private final StoryService stories;
    private final JdbcTemplate db;
    private final GradeRuntimeRepository runtimes;
    private final StoryBatchService batches;

    /** 현재 인가 거래·실제 저장소·정순 runtime 잠금·인증된 비교 소유자를 주입한다. */
    public StoryGradeEvidenceService(
            StoryService stories,
            JdbcTemplate db,
            GradeRuntimeRepository runtimes,
            StoryBatchService batches) {
        this.stories = stories;
        this.db = db;
        this.runtimes = runtimes;
        this.batches = batches;
    }

    /**
     * 정규 입력과 전역 의도 키를 결속하여 실제 GRADE PASS 한 건을 원자 생성하거나 재생한다.
     *
     * @param sid 현재 저장 세션 ID, null 불가
     * @param actor 서버 인증 관리자, null 불가
     * @param storyCode 활성 부모 사건 코드
     * @param versionNo 양의 버전 번호
     * @param expectedRev 정규 0 이상 BIGINT 수정번호 문자열
     * @param snapshotId 정규 양의 BIGINT 사본 문자열
     * @param runtimeConfigId 기존 ASCII 설정 코드
     * @param kind GRADE만 연결됨; PLAYTEST는 연결 오류
     * @param executionRefs 서로 다른 실제 UUID v4 집합 1~200개
     * @param reviewIds 반드시 빈 배열
     * @param resolves 닫힌 같은 사본 GRADE 해소 연결 최대100개
     * @param requestKey 전역 UUID v4 의도 키
     * @param requestId 서버 UUID v4 상관 식별자
     * @return 정확한 여섯 필드의 원문 없는 신규·재생 영수증
     * @throws AuthException 입력·현재 권한·실행 부적격·충돌·필수 저장 오류
     */
    public EvidenceResult createGradeEvidence(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            String snapshotId,
            String runtimeConfigId,
            String kind,
            List<UUID> executionRefs,
            List<String> reviewIds,
            JsonNode resolves,
            UUID requestKey,
            UUID requestId) {
        long rev = decimal(expectedRev, false);
        long snapshot = decimal(snapshotId, true);
        uuid(requestKey);
        uuid(requestId);
        if ("PLAYTEST".equals(kind)) throw AuthException.unprocessable("EVIDENCE_NOT_CONNECTED");
        if (!"GRADE".equals(kind)
                || runtimeConfigId == null
                || !runtimeConfigId.matches("[A-Z0-9_]{1,80}")
                || executionRefs == null
                || executionRefs.isEmpty()
                || executionRefs.size() > 200
                || reviewIds == null
                || !reviewIds.isEmpty()) throw invalid();
        executionRefs.forEach(StoryGradeEvidenceService::uuid);
        if (new HashSet<>(executionRefs).size() != executionRefs.size()) throw invalid();
        List<UUID> refs =
                executionRefs.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        JsonNode links = normalizeResolves(resolves);
        Input input = new Input(rev, snapshot, runtimeConfigId, refs, links, requestKey, requestId);
        try {
            return execute(sid, actor, storyCode, versionNo, input, false);
        } catch (ReceiptRace race) {
            return execute(sid, actor, storyCode, versionNo, input, true);
        }
    }

    /** 실패한 키 경쟁만 새 현재 인가 거래에서 재생하며 업무 자체는 자동 재시도하지 않는다. */
    private EvidenceResult execute(
            String sid,
            AdminActor actor,
            String code,
            int version,
            Input input,
            boolean replayOnly) {
        return guarded(
                () ->
                        stories.withGradeEvidenceAction(
                                sid,
                                actor,
                                code,
                                version,
                                () -> lockRuntimes(input),
                                (scope, locked) ->
                                        apply(
                                                scope,
                                                locked,
                                                actor,
                                                code,
                                                version,
                                                input,
                                                replayOnly)));
    }

    /** 선택 집합과 요청 설정의 실제 runtime을 부모보다 먼저 숫자 정순으로 잠근다. */
    private Map<Long, RuntimeRow> lockRuntimes(Input input) {
        var ids = new java.util.TreeSet<Long>();
        ids.addAll(
                db.queryForList(
                        "SELECT id FROM grade_runtime WHERE code=?", Long.class, input.runtime()));
        for (UUID key : input.refs())
            ids.addAll(
                    db.queryForList(
                            "SELECT runtime_id FROM grade_batch WHERE batch_key=?",
                            Long.class,
                            key));
        Map<Long, RuntimeRow> locked = new LinkedHashMap<>();
        for (long id : ids)
            locked.put(
                    id, runtimes.lockRuntime(id).orElseThrow(StoryGradeEvidenceService::storage));
        return Map.copyOf(locked);
    }

    /** 현재 인가·활성 부모 뒤 전역 영수증을 먼저 검사하고 신규에만 현재 실행 정책을 적용한다. */
    private StoryService.GradeEvidenceWork<EvidenceResult> apply(
            ReviewScope scope,
            Map<Long, RuntimeRow> locked,
            AdminActor actor,
            String code,
            int version,
            Input input,
            boolean replayOnly) {
        String scopeKey = "version:" + scope.versionId();
        String hash = intent(scope, input);
        var existing =
                db.queryForList("SELECT * FROM test_action WHERE request_key=?", input.key());
        if (!existing.isEmpty()) {
            var receipt = existing.getFirst();
            if (receipt.get("admin_id") == null
                    || receipt.get("member_id") != null
                    || number(receipt, "admin_id") != actor.accountId()
                    || !ACTION.equals(receipt.get("action"))
                    || !scopeKey.equals(receipt.get("scope_key"))
                    || !hash.equals(receipt.get("request_hash")))
                throw AuthException.conflict("REQUEST_KEY_CONFLICT");
            EvidenceOriginal original = decodeOriginal(parse(receipt.get("result_data")));
            Map<String, Object> set = ownedSet(scope, original.setKey());
            requireStoredEvidence(
                    set, original.recordId(), actor.accountId(), input.key(), input.resolves());
            var memberKeys =
                    db
                            .queryForList(
                                    "SELECT b.batch_key FROM evidence_item i JOIN grade_batch b ON"
                                            + " b.id=i.batch_id WHERE i.set_id=?",
                                    UUID.class,
                                    number(set, "id"))
                            .stream()
                            .sorted(Comparator.comparing(UUID::toString))
                            .toList();
            if (!input.refs().equals(memberKeys)) throw storage();
            if (!original.snapshotId().equals(Long.toString(input.snapshot()))
                    || !original.runtimeConfigId().equals(input.runtime())
                    || !original.editRev().equals(Long.toString(input.rev()))
                    || number(set, "snapshot_id") != input.snapshot()
                    || !runtimes.getRuntimeDetail(number(set, "runtime_id"))
                            .orElseThrow(StoryGradeEvidenceService::storage)
                            .code()
                            .equals(input.runtime())
                    || !Long.toString(input.rev())
                            .equals(
                                    db.queryForObject(
                                            "SELECT evidence_data->'request'->>'expectedRev' FROM"
                                                    + " review_record WHERE id=?",
                                            String.class,
                                            Long.parseLong(original.recordId())))
                    || !original.createdAt().equals(instant(set.get("created_at")))
                    || !original.createdAt().equals(instant(receipt.get("created_at"))))
                throw storage();
            EvidenceCurrent initial =
                    new EvidenceCurrent(original.snapshotId(), true, original.editRev(), true);
            requireRow(
                    "test_action",
                    number(receipt, "id"),
                    receiptExpected(
                            number(receipt, "id"),
                            actor.accountId(),
                            input,
                            scopeKey,
                            hash,
                            new EvidenceResult(
                                    ACTION,
                                    false,
                                    true,
                                    original,
                                    initial,
                                    storedUuid(
                                            parse(receipt.get("result_data"))
                                                    .path("requestId")
                                                    .textValue())),
                            timeJson(set.get("created_at"))));
            return new StoryService.GradeEvidenceWork<>(
                    new EvidenceResult(
                            ACTION, true, false, original, current(scope, set), input.request()),
                    null);
        }
        if (replayOnly) throw storage();
        if (!"REVIEW".equals(scope.status())
                || !Objects.equals(scope.snapshotId(), input.snapshot()))
            throw AuthException.conflict("STATE_CONFLICT");
        if (scope.rev() != input.rev()) throw AuthException.conflict("EDIT_CONFLICT");
        RuntimeRow runtime =
                locked.values().stream()
                        .filter(row -> input.runtime().equals(row.code()))
                        .findFirst()
                        .orElseThrow(() -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
        batches.verifyResolutionRuntime(runtime, scope.policyCode());
        List<Map<String, Object>> selected = new ArrayList<>();
        for (UUID ref : input.refs()) {
            var rows =
                    db.queryForList(
                            "SELECT b.* FROM grade_batch b JOIN review_snapshot s ON"
                                    + " s.id=b.snapshot_id WHERE b.batch_key=? AND s.version_id=?",
                            ref,
                            scope.versionId());
            if (rows.isEmpty()) throw missing();
            selected.add(rows.getFirst());
        }
        selected.sort(Comparator.comparingLong(row -> number(row, "id")));
        for (var row : selected) {
            var actual =
                    db.queryForMap(
                            "SELECT * FROM grade_batch WHERE id=? FOR UPDATE", number(row, "id"));
            if (!same(rowJson("grade_batch", number(row, "id")), mapJson(row))) throw storage();
            requireBatch(actual, input, runtime);
        }
        requireSnapshotClean(input.snapshot());
        requireRenewedExecution(input.snapshot(), selected);
        ObjectNode before = preserved(scope, null, null, List.of());
        List<StoryBatchService.GradeEvidenceRead> proofs = new ArrayList<>();
        for (var row : selected) {
            var proof =
                    batches.readGradeEvidence(
                            scope, runtime, code, version, row, actor, input.request());
            var detail = proof.detail();
            if (!"COMPLETED".equals(detail.state())
                    || !Boolean.TRUE.equals(detail.passed())
                    || detail.totalJobs() == 0
                    || detail.completedJobs() != detail.totalJobs()
                    || detail.failedComparisons() != 0
                    || detail.unresolvedJobs() != 0
                    || detail.items().stream().anyMatch(item -> !"PASS".equals(item.comparison())))
                throw incomplete();
            proofs.add(proof);
        }
        requireResolutions(input.snapshot(), input.resolves());
        ObjectNode summary = summary(selected, proofs, runtime);
        if (SnapshotJson.encode(summary).length > 131072) throw storage();
        String evidenceHash = SnapshotJson.hash(summary);
        UUID setKey = UUID.randomUUID();
        OffsetDateTime time = now();
        JsonNode jsonTime = timeJson(time);
        Long setId =
                db.queryForObject(
                        """
                        INSERT INTO evidence_set(set_key,snapshot_id,runtime_id,runtime_epoch,kind,evidence_hash,
                            summary_data,available_yn,invalidated_at,invalidated_issue_id,created_by,created_at)
                        VALUES (?,?,?,?,'GRADE',?,?::jsonb,true,NULL,NULL,?,?) RETURNING id
                        """,
                        Long.class,
                        setKey,
                        input.snapshot(),
                        runtime.id(),
                        runtime.epoch(),
                        evidenceHash,
                        encode(summary),
                        actor.accountId(),
                        time);
        if (setId == null) throw storage();
        List<ObjectNode> itemRows = new ArrayList<>();
        for (var proof : proofs) {
            if (db.update(
                            "INSERT INTO"
                                + " evidence_item(set_id,snapshot_id,runtime_id,batch_id,evidence_hash)"
                                + " VALUES (?,?,?,?,?)",
                            setId,
                            input.snapshot(),
                            runtime.id(),
                            proof.batchId(),
                            proof.manifestHash())
                    != 1) throw storage();
            itemRows.add(
                    object().put("set_id", setId)
                            .put("snapshot_id", input.snapshot())
                            .put("runtime_id", runtime.id())
                            .put("batch_id", proof.batchId())
                            .put("evidence_hash", proof.manifestHash()));
        }
        ObjectNode wrapper =
                wrapper(
                        input,
                        runtime,
                        setKey,
                        evidenceHash,
                        summary,
                        time.toInstant(),
                        actor.accountKey());
        boolean self = stories.reviewSelf(scope, actor);
        Long recordId =
                db.queryForObject(
                        """
                        INSERT INTO review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,
                            model_id,effort,evidence,self_review_yn,created_at,evidence_set_id)
                        VALUES (?,'GRADE',?,?::jsonb,'PASS',?,NULL,NULL,?,?,?,?) RETURNING id
                        """,
                        Long.class,
                        input.snapshot(),
                        input.key(),
                        encode(wrapper),
                        actor.accountId(),
                        MESSAGE,
                        self,
                        time,
                        setId);
        if (recordId == null) throw storage();
        for (JsonNode link : input.resolves())
            if (decimal(link.path("recordId").textValue(), true) >= recordId) throw storage();
        EvidenceOriginal original =
                new EvidenceOriginal(
                        setKey,
                        recordId.toString(),
                        Long.toString(input.snapshot()),
                        runtime.code(),
                        Long.toString(scope.rev()),
                        "GRADE",
                        "PASS",
                        time.toInstant());
        EvidenceResult result =
                new EvidenceResult(
                        ACTION,
                        false,
                        true,
                        original,
                        new EvidenceCurrent(original.snapshotId(), true, original.editRev(), true),
                        input.request());
        Long receiptId;
        try {
            receiptId =
                    db.queryForObject(
                            "INSERT INTO"
                                + " test_action(request_key,admin_id,action,scope_key,request_hash,result_data,created_at)"
                                + " VALUES (?,?,'EVIDENCE_CREATE',?,?,?::jsonb,?) RETURNING id",
                            Long.class,
                            input.key(),
                            actor.accountId(),
                            scopeKey,
                            hash,
                            encode(resultJson(result)),
                            time);
        } catch (DataAccessException failure) {
            if (uniqueReceipt(failure)) throw new ReceiptRace();
            throw failure;
        }
        if (receiptId == null) throw storage();
        UUID eventKey = UUID.randomUUID();
        ObjectNode auditDetail =
                object().put("setKey", setKey.toString())
                        .put("recordId", recordId.toString())
                        .put("snapshotId", original.snapshotId())
                        .put("editRev", original.editRev());
        Long auditId =
                db.queryForObject(
                        """
                        INSERT INTO test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,
                            request_id,phase,business_result,detail,created_at)
                        VALUES (?,'ADMIN',?,'EVIDENCE_CREATE','version',?,?,'RESULT','SUCCESS',?::jsonb,?) RETURNING id
                        """,
                        Long.class,
                        eventKey,
                        actor.accountKey().toString(),
                        scopeKey,
                        input.request(),
                        encode(auditDetail),
                        time);
        if (auditId == null) throw storage();
        ObjectNode expectedSet =
                object().put("id", setId)
                        .put("set_key", setKey.toString())
                        .put("snapshot_id", input.snapshot())
                        .put("runtime_id", runtime.id())
                        .put("runtime_epoch", runtime.epoch())
                        .put("kind", "GRADE")
                        .put("evidence_hash", evidenceHash)
                        .put("available_yn", true)
                        .putNull("invalidated_at")
                        .putNull("invalidated_issue_id")
                        .put("created_by", actor.accountId());
        expectedSet.set("summary_data", summary);
        expectedSet.set("created_at", jsonTime);
        requireRow("evidence_set", setId, expectedSet);
        var expectedItems = JsonNodeFactory.instance.arrayNode();
        itemRows.forEach(expectedItems::add);
        if (!same(
                expectedItems,
                parse(
                        db.queryForObject(
                                "SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY"
                                        + " batch_id),'[]'::jsonb)::text FROM evidence_item i WHERE"
                                        + " set_id=?",
                                String.class,
                                setId)))) throw storage();
        ObjectNode expectedRecord =
                object().put("id", recordId)
                        .put("snapshot_id", input.snapshot())
                        .put("kind", "GRADE")
                        .put("request_key", input.key().toString())
                        .put("result", "PASS")
                        .put("reviewer_id", actor.accountId())
                        .putNull("model_id")
                        .putNull("effort")
                        .put("evidence", MESSAGE)
                        .put("self_review_yn", self)
                        .put("evidence_set_id", setId);
        expectedRecord.set("evidence_data", wrapper);
        expectedRecord.set("created_at", jsonTime);
        requireRow("review_record", recordId, expectedRecord);
        requireRow(
                "test_action",
                receiptId,
                receiptExpected(
                        receiptId, actor.accountId(), input, scopeKey, hash, result, jsonTime));
        ObjectNode expectedAudit =
                object().put("id", auditId)
                        .put("event_key", eventKey.toString())
                        .put("actor_kind", "ADMIN")
                        .put("actor_ref", actor.accountKey().toString())
                        .put("action", ACTION)
                        .put("scope_kind", "version")
                        .put("scope_key", scopeKey)
                        .put("request_id", input.request().toString())
                        .put("phase", "RESULT")
                        .put("business_result", "SUCCESS");
        expectedAudit.set("detail", auditDetail);
        expectedAudit.set("created_at", jsonTime);
        requireRow("test_audit", auditId, expectedAudit);
        List<Long> auditIds = new ArrayList<>();
        for (var proof : proofs) {
            batches.verifyGradeEvidenceRead(proof);
            auditIds.add(proof.readAuditId());
        }
        auditIds.add(auditId);
        if (!same(before, preserved(scope, setId, receiptId, auditIds))) throw storage();
        for (RuntimeRow row : locked.values())
            if (!row.equals(
                    runtimes.getRuntimeDetail(row.id())
                            .orElseThrow(StoryGradeEvidenceService::storage))) throw storage();
        for (var row : selected)
            requireBatch(
                    db.queryForMap("SELECT * FROM grade_batch WHERE id=?", number(row, "id")),
                    input,
                    runtime);
        requireSnapshotClean(input.snapshot());
        requireRenewedExecution(input.snapshot(), selected);
        batches.verifyResolutionRuntime(runtime, scope.policyCode());
        return new StoryService.GradeEvidenceWork<>(result, recordId, input.key());
    }

    /** 정규 의도는 실제 version ID에 결속하며 행위자는 별도로 비교한다. */
    private String intent(ReviewScope scope, Input input) {
        return intent(scope.versionId(), input);
    }

    /** 역사적 영수증도 같은 실제 버전 범위와 닫힌 입력 정규형으로 검사한다. */
    private String intent(long versionId, Input input) {
        ObjectNode node =
                object().put("domain", "GRADE_EVIDENCE_CREATE-v1")
                        .put("action", ACTION)
                        .put("scope", "version:" + versionId)
                        .put("expectedRev", Long.toString(input.rev()))
                        .put("snapshotId", Long.toString(input.snapshot()))
                        .put("runtimeConfigId", input.runtime())
                        .put("kind", "GRADE");
        var refs = node.putArray("executionRefs");
        input.refs().forEach(ref -> refs.add(ref.toString()));
        node.putArray("reviewIds");
        node.set("resolves", input.resolves());
        return SnapshotJson.hash(node);
    }

    /** 실제 24시간 완료 기한만 검사하며 임의 경과 나이나 운영 TTL은 요구하지 않는다. */
    private void requireBatch(Map<String, Object> row, Input input, RuntimeRow runtime) {
        if (number(row, "snapshot_id") != input.snapshot()
                || number(row, "runtime_id") != runtime.id()
                || !"REVIEW".equals(row.get("purpose"))
                || !"COMPLETED".equals(row.get("state"))
                || !Boolean.TRUE.equals(row.get("passed_yn"))
                || row.get("ended_at") == null) throw incomplete();
        if (number(row, "runtime_epoch") != runtime.epoch())
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        Instant created = instant(row.get("created_at"));
        Instant ended = instant(row.get("ended_at"));
        if (ended.isBefore(created)
                || !ended.isBefore(created.plusSeconds(86400))
                || ended.isAfter(now().toInstant())) throw incomplete();
    }

    /**
     * 실제 최초 OPEN/무효화 감사 이후의 새 전체 실행을 요구하며 경과 나이·해소 시각을 사용하지 않는다.
     *
     * @param snapshot 현재 REVIEW 사본의 양의 내부 ID
     * @param selected 같은 거래에서 잠그고 독립 전체 비교를 수행할 실제 선택 집합
     * @throws AuthException 감사 결속이 손상됐거나 마지막 사건 이후 생성한 집합이 없는 경우
     */
    private void requireRenewedExecution(long snapshot, List<Map<String, Object>> selected) {
        Instant boundary = null;
        for (var issue :
                db.queryForList(
                        "SELECT i.*,b.batch_key FROM execution_issue i JOIN grade_batch b ON"
                                + " b.id=i.batch_id AND b.snapshot_id=i.snapshot_id AND"
                                + " b.runtime_id=i.runtime_id WHERE i.snapshot_id=? AND i.kind IN"
                                + " ('GRADING','INFRA') ORDER BY i.id",
                        snapshot)) {
            long issueId = number(issue, "id");
            String scope = "batch:" + issue.get("batch_key");
            var invalidations =
                    db.queryForList(
                            "SELECT * FROM test_audit WHERE actor_kind='SYSTEM' AND"
                                + " action='EVIDENCE_INVALIDATE' AND scope_kind='batch' AND"
                                + " scope_key=? AND phase='RESULT' AND business_result='SUCCESS'"
                                + " AND detail->>'issueId'=?",
                            scope,
                            Long.toString(issueId));
            var openings =
                    db.queryForList(
                            "SELECT * FROM test_audit WHERE actor_kind='SYSTEM' AND"
                                + " action='BATCH_ISSUE' AND scope_kind='batch' AND scope_key=? AND"
                                + " phase='RESULT' AND business_result='SUCCESS' AND"
                                + " detail->>'issueId'=?",
                            scope,
                            Long.toString(issueId));
            if (invalidations.size() != 1 || openings.size() != 1) throw storage();
            var invalidation = invalidations.getFirst();
            var opening = openings.getFirst();
            JsonNode detail = parse(invalidation.get("detail"));
            JsonNode count = detail.path("count");
            if (!detail.isObject()
                    || detail.size() != 3
                    || !count.isIntegralNumber()
                    || !count.canConvertToInt()
                    || count.intValue() < 0
                    || !Long.toString(issueId).equals(detail.path("issueId").textValue())
                    || !Long.toString(snapshot).equals(detail.path("snapshotId").textValue())
                    || !Objects.equals(opening.get("actor_ref"), invalidation.get("actor_ref"))
                    || !Objects.equals(opening.get("request_id"), invalidation.get("request_id"))
                    || !same(
                            parse(opening.get("detail")),
                            object().put("issueId", Long.toString(issueId))
                                    .put("kind", (String) issue.get("kind"))
                                    .put("reason", (String) issue.get("reason_code")))
                    || instant(opening.get("created_at")).isBefore(instant(issue.get("created_at")))
                    || instant(invalidation.get("created_at"))
                            .isBefore(instant(opening.get("created_at")))
                    || count.longValue()
                            != db.queryForObject(
                                    "SELECT count(*) FROM evidence_set"
                                            + " WHERE snapshot_id=? AND invalidated_issue_id=?",
                                    Long.class,
                                    snapshot,
                                    issueId)) throw storage();
            Instant created = instant(issue.get("created_at"));
            if (boundary == null || created.isAfter(boundary)) boundary = created;
        }
        if (boundary != null) {
            Instant latest = boundary;
            if (selected.stream().noneMatch(row -> instant(row.get("created_at")).isAfter(latest)))
                throw incomplete();
        }
    }

    /** runtime과 무관하게 현재 사본의 모든 OPEN 지적과 미완료 실행을 검사한다. */
    private void requireSnapshotClean(long snapshot) {
        Long count =
                db.queryForObject(
                        """
                        SELECT (SELECT count(*) FROM execution_issue WHERE snapshot_id=? AND state='OPEN')
                            +(SELECT count(*) FROM grade_batch WHERE snapshot_id=?
                                AND (state IN ('STAGED','RUNNING') OR state='COMPLETED' AND passed_yn IS NULL))
                            +(SELECT count(*) FROM grade_job WHERE snapshot_id=? AND state IN ('STAGED','QUEUED','RUNNING'))
                            +(SELECT count(*) FROM grade_attempt a JOIN grade_job j ON j.id=a.job_id
                                WHERE j.snapshot_id=? AND a.state='RUNNING')
                            +(SELECT count(*) FROM grade_batch b WHERE b.snapshot_id=?
                                AND (b.state IN ('FAILED','CANCELLED') OR b.passed_yn=false)
                                AND NOT EXISTS(SELECT 1 FROM execution_issue i WHERE i.batch_id=b.id AND i.state='RESOLVED'))
                        """,
                        Long.class,
                        snapshot,
                        snapshot,
                        snapshot,
                        snapshot,
                        snapshot);
        if (count == null || count != 0) throw incomplete();
    }

    /** 철회된 실제 PASS는 이번 연결 또는 불변 후속 발급의 ACK로 해소한다. */
    private void requireResolutions(long snapshot, JsonNode links) {
        ResolutionReads reads = new ResolutionReads();
        Set<Long> current = new HashSet<>();
        for (JsonNode link : links) {
            long id = decimal(link.path("recordId").textValue(), true);
            var rows =
                    db.queryForList(
                            "SELECT id FROM review_record WHERE id=? AND snapshot_id=?"
                                    + " AND kind='GRADE' AND result='PASS'",
                            id,
                            snapshot);
            if (rows.size() != 1) throw incomplete();
            StoredIssuance target = requireIssuance(id, reads);
            if (target.available()) throw incomplete();
            requireLinks(target, reads);
            requireWithdrawal(target, reads);
            if (target.withdrawnAt().isAfter(now().toInstant())) throw storage();
            current.add(id);
        }
        Set<Long> acknowledged = new HashSet<>();
        List<Long> defects =
                db.queryForList(
                        "SELECT r.id FROM review_record r JOIN evidence_set e ON"
                            + " e.id=r.evidence_set_id WHERE r.snapshot_id=? AND r.kind='GRADE' AND"
                            + " r.result='PASS' AND NOT e.available_yn ORDER BY r.id",
                        Long.class,
                        snapshot);
        for (long id : defects) {
            StoredIssuance target = requireIssuance(id, reads);
            requireLinks(target, reads);
            requireWithdrawal(target, reads);
        }
        for (long id :
                db.queryForList(
                        "SELECT id FROM review_record WHERE snapshot_id=? AND kind='GRADE' AND"
                            + " result='PASS' AND"
                            + " jsonb_array_length(evidence_data->'details'->'resolves')>0 ORDER BY"
                            + " id",
                        Long.class,
                        snapshot)) {
            StoredIssuance successor = requireIssuance(id, reads);
            requireLinks(successor, reads);
            acknowledged.addAll(successor.targets());
        }
        if (defects.stream().anyMatch(id -> !current.contains(id) && !acknowledged.contains(id)))
            throw incomplete();
    }

    /** 최소 불변 집계와 전체 구성원의 인증된 명세 해시만 저장한다. */
    private ObjectNode summary(
            List<Map<String, Object>> selected,
            List<StoryBatchService.GradeEvidenceRead> proofs,
            RuntimeRow runtime) {
        var first = selected.getFirst();
        ObjectNode summary =
                object().put("formatNo", 1)
                        .put("domain", "GRADE_EVIDENCE_SET-v1")
                        .put("batchCount", selected.size())
                        .put(
                                "totalJobCount",
                                proofs.stream().mapToInt(p -> p.detail().totalJobs()).sum());
        for (String field : List.of("payload_hash", "rubric_hash", "dataset_hash", "config_hash")) {
            String value = (String) first.get(field);
            for (var row : selected) if (!Objects.equals(value, row.get(field))) throw incomplete();
            summary.put(field, value);
        }
        if (!runtime.configHash().equals(summary.path("config_hash").textValue())) throw storage();
        var membership = summary.putArray("membership");
        for (var proof : proofs)
            membership.add(
                    object().put("batchId", Long.toString(proof.batchId()))
                            .put("batchKey", proof.detail().batchKey().toString())
                            .put("manifestHash", proof.manifestHash()));
        return summary;
    }

    /** 서버 formatNo3는 입력 원문이나 모델 호출 노력을 복제하지 않는다. */
    private static ObjectNode wrapper(
            Input input,
            RuntimeRow runtime,
            UUID setKey,
            String hash,
            JsonNode summary,
            Instant time,
            UUID reviewer) {
        ObjectNode wrapper = object().put("formatNo", 3);
        wrapper.putObject("request").put("expectedRev", Long.toString(input.rev()));
        ObjectNode detail =
                wrapper.putObject("details")
                        .put("formatNo", 3)
                        .put("snapshotId", Long.toString(input.snapshot()))
                        .put("payloadHash", summary.path("payload_hash").textValue())
                        .put("rubricHash", summary.path("rubric_hash").textValue())
                        .put("runtimeConfigId", runtime.code())
                        .put("configHash", runtime.configHash())
                        .put("executionSetRef", setKey.toString())
                        .put("executionSetHash", hash)
                        .put("checkedAt", time.toString())
                        .put("reviewerRef", reviewer.toString())
                        .put("criticalOpenCount", 0);
        detail.set("resolves", input.resolves());
        return wrapper;
    }

    /** 역사적 근거는 실제 같은 사본의 봉인된 관계와 전체 검수 기록으로 검사한다. */
    private void requireStoredEvidence(
            Map<String, Object> set, String recordId, long reviewer, UUID key, JsonNode resolves) {
        long id = decimal(recordId, true);
        ResolutionReads reads = new ResolutionReads();
        var record =
                db.queryForMap(
                        "SELECT r.*,a.account_key FROM review_record r JOIN admin_account a ON"
                                + " a.id=r.reviewer_id WHERE r.id=?",
                        id);
        StoredIssuance issuance = authenticateIssuance(set, record, id, reviewer, key, resolves);
        reads.issuances.put(id, issuance);
        requireLinks(issuance, reads);
    }

    /** 최초 발급만 인증한다. 현재 권한·설치·품질 또는 후속 집합의 현재 가용성을 재평가하지 않는다. */
    private StoredIssuance authenticateIssuance(
            Map<String, Object> set,
            Map<String, Object> record,
            long id,
            long reviewer,
            UUID key,
            JsonNode resolves) {
        String recordId = Long.toString(id);
        JsonNode summary = parse(set.get("summary_data"));
        exact(
                summary,
                "formatNo",
                "domain",
                "batchCount",
                "totalJobCount",
                "payload_hash",
                "rubric_hash",
                "dataset_hash",
                "config_hash",
                "membership");
        if (!summary.path("formatNo").isIntegralNumber()
                || summary.path("formatNo").intValue() != 1
                || !"GRADE_EVIDENCE_SET-v1".equals(summary.path("domain").textValue())
                || !summary.path("batchCount").isIntegralNumber()
                || !summary.path("totalJobCount").isIntegralNumber()) throw storage();
        for (String field : List.of("payload_hash", "rubric_hash", "dataset_hash", "config_hash"))
            if (summary.path(field).textValue() == null
                    || !summary.path(field).textValue().matches("[0-9a-f]{64}")) throw storage();
        if (!"GRADE".equals(set.get("kind"))
                || !SnapshotJson.hash(summary).equals(set.get("evidence_hash"))
                || number(record, "snapshot_id") != number(set, "snapshot_id")
                || number(record, "evidence_set_id") != number(set, "id")
                || number(record, "reviewer_id") != reviewer
                || number(set, "created_by") != reviewer
                || !key.equals(record.get("request_key"))
                || !"GRADE".equals(record.get("kind"))
                || !"PASS".equals(record.get("result"))
                || record.get("model_id") != null
                || record.get("effort") != null
                || !MESSAGE.equals(record.get("evidence"))
                || !(record.get("self_review_yn") instanceof Boolean)
                || !instant(record.get("created_at")).equals(instant(set.get("created_at"))))
            throw storage();
        RuntimeRow runtime =
                runtimes.getRuntimeDetail(number(set, "runtime_id"))
                        .orElseThrow(StoryGradeEvidenceService::storage);
        JsonNode evidence = parse(record.get("evidence_data"));
        long rev;
        try {
            rev = decimal(evidence.path("request").path("expectedRev").textValue(), false);
        } catch (AuthException malformed) {
            throw storage();
        }
        Input input =
                new Input(
                        rev,
                        number(set, "snapshot_id"),
                        runtime.code(),
                        List.of(),
                        resolves,
                        key,
                        key);
        if (!same(
                evidence,
                wrapper(
                        input,
                        runtime,
                        (UUID) set.get("set_key"),
                        (String) set.get("evidence_hash"),
                        summary,
                        instant(set.get("created_at")),
                        (UUID) record.get("account_key")))) throw storage();
        JsonNode members = summary.path("membership");
        var items =
                db.queryForList(
                        "SELECT"
                            + " i.*,b.batch_key,b.runtime_epoch,b.purpose,b.payload_hash,b.rubric_hash,b.dataset_hash,b.config_hash,b.expected_count"
                            + " FROM evidence_item i JOIN grade_batch b ON b.id=i.batch_id AND"
                            + " b.snapshot_id=i.snapshot_id AND b.runtime_id=i.runtime_id WHERE"
                            + " i.set_id=? ORDER BY i.batch_id",
                        number(set, "id"));
        if (!members.isArray()
                || items.isEmpty()
                || members.size() != items.size()
                || summary.path("batchCount").intValue() != items.size()) throw storage();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            for (String field :
                    List.of("payload_hash", "rubric_hash", "dataset_hash", "config_hash"))
                if (!Objects.equals(item.get(field), summary.path(field).textValue()))
                    throw storage();
            if (number(item, "snapshot_id") != number(set, "snapshot_id")
                    || number(item, "runtime_id") != runtime.id()
                    || number(item, "runtime_epoch") != number(set, "runtime_epoch")
                    || !"REVIEW".equals(item.get("purpose"))
                    || !same(
                            members.get(index),
                            object().put("batchId", Long.toString(number(item, "batch_id")))
                                    .put("batchKey", item.get("batch_key").toString())
                                    .put("manifestHash", (String) item.get("evidence_hash"))))
                throw storage();
        }
        if (summary.path("totalJobCount").longValue()
                != items.stream().mapToLong(item -> number(item, "expected_count")).sum())
            throw storage();
        ObjectNode expectedRecord =
                object().put("id", id)
                        .put("snapshot_id", number(set, "snapshot_id"))
                        .put("kind", "GRADE")
                        .put("request_key", key.toString())
                        .put("result", "PASS")
                        .put("reviewer_id", reviewer)
                        .putNull("model_id")
                        .putNull("effort")
                        .put("evidence", MESSAGE)
                        .put("self_review_yn", (Boolean) record.get("self_review_yn"))
                        .put("evidence_set_id", number(set, "id"));
        expectedRecord.set("evidence_data", evidence);
        expectedRecord.set("created_at", timeJson(set.get("created_at")));
        requireRow("review_record", id, expectedRecord);
        var receipts = db.queryForList("SELECT * FROM test_action WHERE request_key=?", key);
        if (receipts.size() != 1) throw storage();
        var receipt = receipts.getFirst();
        JsonNode result = parse(receipt.get("result_data"));
        EvidenceOriginal original = decodeOriginal(result);
        long version =
                Objects.requireNonNull(
                        db.queryForObject(
                                "SELECT version_id FROM review_snapshot WHERE id=?",
                                Long.class,
                                number(set, "snapshot_id")));
        String scope = "version:" + version;
        if (number(receipt, "admin_id") != reviewer
                || !ACTION.equals(receipt.get("action"))
                || !scope.equals(receipt.get("scope_key"))
                || !original.setKey().equals(set.get("set_key"))
                || !original.recordId().equals(recordId)
                || !original.snapshotId().equals(Long.toString(number(set, "snapshot_id")))
                || !original.runtimeConfigId().equals(runtime.code())
                || !original.editRev().equals(Long.toString(rev))
                || !original.createdAt().equals(instant(set.get("created_at")))
                || !instant(receipt.get("created_at")).equals(instant(set.get("created_at"))))
            throw storage();
        UUID originalRequest = storedUuid(result.path("requestId").textValue());
        List<UUID> refs =
                items.stream()
                        .map(item -> (UUID) item.get("batch_key"))
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList();
        Input originalInput =
                new Input(
                        rev,
                        number(set, "snapshot_id"),
                        runtime.code(),
                        refs,
                        resolves,
                        key,
                        originalRequest);
        EvidenceResult originalResult =
                new EvidenceResult(
                        ACTION,
                        false,
                        true,
                        original,
                        new EvidenceCurrent(original.snapshotId(), true, original.editRev(), true),
                        originalRequest);
        requireRow(
                "test_action",
                number(receipt, "id"),
                receiptExpected(
                        number(receipt, "id"),
                        reviewer,
                        originalInput,
                        scope,
                        intent(version, originalInput),
                        originalResult,
                        timeJson(set.get("created_at"))));
        ObjectNode detail =
                object().put("setKey", original.setKey().toString())
                        .put("recordId", recordId)
                        .put("snapshotId", original.snapshotId())
                        .put("editRev", original.editRev());
        var audits =
                db.queryForList(
                        "SELECT * FROM test_audit WHERE actor_kind='ADMIN' AND actor_ref=? AND"
                            + " action='EVIDENCE_CREATE' AND scope_kind='version' AND scope_key=?"
                            + " AND request_id=? AND phase='RESULT' AND business_result='SUCCESS'",
                        record.get("account_key").toString(),
                        scope,
                        originalRequest);
        if (audits.size() != 1
                || !same(detail, parse(audits.getFirst().get("detail")))
                || !instant(audits.getFirst().get("created_at"))
                        .equals(instant(set.get("created_at")))) throw storage();
        for (var item : items) {
            var reads =
                    db.queryForList(
                            "SELECT * FROM test_audit WHERE actor_kind='ADMIN' AND actor_ref=? AND"
                                + " action='CONTENT_READ' AND scope_kind='batch' AND scope_key=?"
                                + " AND request_id=? AND phase='RESULT' AND"
                                + " business_result='SUCCESS'",
                            record.get("account_key").toString(),
                            "batch:" + item.get("batch_key"),
                            originalRequest);
            if (reads.size() != 1
                    || !same(
                            parse(reads.getFirst().get("detail")),
                            object().put("batchKey", item.get("batch_key").toString()))
                    || instant(reads.getFirst().get("created_at"))
                            .isAfter(instant(set.get("created_at")))) throw storage();
        }
        boolean available = Boolean.TRUE.equals(set.get("available_yn"));
        if (!(set.get("available_yn") instanceof Boolean)
                || available
                        && (set.get("invalidated_at") != null
                                || set.get("invalidated_issue_id") != null)
                || !available
                        && (set.get("invalidated_at") == null
                                || set.get("invalidated_issue_id") == null)) throw storage();
        List<Long> targets = new ArrayList<>();
        for (JsonNode link : resolves)
            targets.add(decimal(link.path("recordId").textValue(), true));
        return new StoredIssuance(
                id,
                number(set, "snapshot_id"),
                available,
                instant(set.get("created_at")),
                available ? null : instant(set.get("invalidated_at")),
                available ? null : number(set, "invalidated_issue_id"),
                List.copyOf(targets));
    }

    /** 거래별 한 번만 전체 발급 원행을 읽고 정규 연결의 작은 메타데이터만 보관한다. */
    private StoredIssuance requireIssuance(long id, ResolutionReads reads) {
        StoredIssuance cached = reads.issuances.get(id);
        if (cached != null) return cached;
        var records =
                db.queryForList(
                        "SELECT r.*,a.account_key FROM review_record r JOIN admin_account a"
                                + " ON a.id=r.reviewer_id WHERE r.id=?",
                        id);
        if (records.size() != 1) throw storage();
        var record = records.getFirst();
        JsonNode links = parse(record.get("evidence_data")).path("details").path("resolves");
        JsonNode normalized;
        try {
            normalized = normalizeResolves(links);
        } catch (AuthException malformed) {
            throw storage();
        }
        if (!same(links, normalized) || record.get("evidence_set_id") == null) throw storage();
        var sets =
                db.queryForList(
                        "SELECT * FROM evidence_set WHERE id=?", number(record, "evidence_set_id"));
        if (sets.size() != 1) throw storage();
        StoredIssuance issuance =
                authenticateIssuance(
                        sets.getFirst(),
                        record,
                        id,
                        number(record, "reviewer_id"),
                        (UUID) record.get("request_key"),
                        normalized);
        reads.issuances.put(id, issuance);
        return issuance;
    }

    /** 낮은 ID의 도달 가능한 연결을 반복 순회한다. 역사적 ACK는 후속 무효화 뒤에도 유지된다. */
    private void requireLinks(StoredIssuance root, ResolutionReads reads) {
        var pending = new java.util.ArrayDeque<StoredIssuance>();
        pending.add(root);
        while (!pending.isEmpty()) {
            StoredIssuance successor = pending.removeFirst();
            if (!reads.linked.add(successor.id())) continue;
            if (!successor.available()) requireWithdrawal(successor, reads);
            for (long id : successor.targets()) {
                if (id >= successor.id()) throw storage();
                StoredIssuance target = requireIssuance(id, reads);
                if (target.snapshot() != successor.snapshot() || target.available())
                    throw storage();
                requireWithdrawal(target, reads);
                if (target.withdrawnAt().isAfter(successor.createdAt())) throw storage();
                pending.addLast(target);
            }
        }
    }

    /** 불변 최초 마커는 실제 같은 사본 OPEN 지적과 쌍을 이룬 SYSTEM 감사로만 인정한다. */
    private void requireWithdrawal(StoredIssuance target, ResolutionReads reads) {
        if (target.available()
                || target.issueId() == null
                || target.withdrawnAt() == null
                || target.withdrawnAt().isBefore(target.createdAt())) throw storage();
        Withdrawal cached = reads.withdrawals.get(target.issueId());
        if (cached == null) {
            var issues =
                    db.queryForList(
                            "SELECT i.*,b.batch_key FROM execution_issue i JOIN grade_batch b ON"
                                    + " b.id=i.batch_id AND b.snapshot_id=i.snapshot_id AND"
                                    + " b.runtime_id=i.runtime_id WHERE i.id=? AND i.kind IN"
                                    + " ('GRADING','INFRA')",
                            target.issueId());
            if (issues.size() != 1) throw storage();
            var issue = issues.getFirst();
            String scope = "batch:" + issue.get("batch_key");
            var openings =
                    db.queryForList(
                            "SELECT * FROM test_audit WHERE actor_kind='SYSTEM' AND"
                                + " action='BATCH_ISSUE' AND scope_kind='batch' AND scope_key=? AND"
                                + " phase='RESULT' AND business_result='SUCCESS' AND"
                                + " detail->>'issueId'=?",
                            scope,
                            target.issueId().toString());
            var invalidations =
                    db.queryForList(
                            "SELECT * FROM test_audit WHERE actor_kind='SYSTEM' AND"
                                + " action='EVIDENCE_INVALIDATE' AND scope_kind='batch' AND"
                                + " scope_key=? AND phase='RESULT' AND business_result='SUCCESS'"
                                + " AND detail->>'issueId'=?",
                            scope,
                            target.issueId().toString());
            if (openings.size() != 1 || invalidations.size() != 1) throw storage();
            var opening = openings.getFirst();
            var invalidation = invalidations.getFirst();
            JsonNode detail = parse(invalidation.get("detail"));
            exact(detail, "issueId", "snapshotId", "count");
            JsonNode count = detail.path("count");
            long snapshot = number(issue, "snapshot_id");
            Instant opened = instant(opening.get("created_at"));
            Instant withdrawn = instant(invalidation.get("created_at"));
            var markers =
                    db.queryForMap(
                            "SELECT min(invalidated_at) AS first_at,max(invalidated_at) AS last_at"
                                    + " FROM evidence_set WHERE snapshot_id=? AND"
                                    + " invalidated_issue_id=?",
                            snapshot,
                            target.issueId());
            Instant marked = instant(markers.get("first_at"));
            if (!target.issueId().toString().equals(detail.path("issueId").textValue())
                    || !Long.toString(snapshot).equals(detail.path("snapshotId").textValue())
                    || !count.isIntegralNumber()
                    || !count.canConvertToInt()
                    || count.intValue() < 1
                    || count.longValue()
                            != db.queryForObject(
                                    "SELECT count(*) FROM evidence_set WHERE snapshot_id=?"
                                            + " AND invalidated_issue_id=?",
                                    Long.class,
                                    snapshot,
                                    target.issueId())
                    || !Objects.equals(opening.get("actor_ref"), invalidation.get("actor_ref"))
                    || !Objects.equals(opening.get("request_id"), invalidation.get("request_id"))
                    || !same(
                            parse(opening.get("detail")),
                            object().put("issueId", target.issueId().toString())
                                    .put("kind", (String) issue.get("kind"))
                                    .put("reason", (String) issue.get("reason_code")))
                    || opened.isBefore(instant(issue.get("created_at")))
                    || marked.isBefore(opened)
                    || withdrawn.isBefore(marked)
                    || !marked.equals(instant(markers.get("last_at")))) throw storage();
            cached = new Withdrawal(snapshot, marked);
            reads.withdrawals.put(target.issueId(), cached);
        }
        if (cached.snapshot() != target.snapshot()
                || !cached.createdAt().equals(target.withdrawnAt())) throw storage();
    }

    /** 같은 부모의 역사적 집합은 신규 상태 조건 없이 읽는다. */
    private Map<String, Object> ownedSet(ReviewScope scope, UUID key) {
        var rows =
                db.queryForList(
                        "SELECT e.* FROM evidence_set e JOIN review_snapshot s ON"
                                + " s.id=e.snapshot_id WHERE e.set_key=? AND s.version_id=?",
                        key,
                        scope.versionId());
        if (rows.size() != 1) throw storage();
        return rows.getFirst();
    }

    /** 새 행의 실제 ID만 제외하며 기존 전체 실행·집합·관련 이력을 보존한다. */
    private ObjectNode preserved(ReviewScope scope, Long set, Long receipt, List<Long> audits) {
        ObjectNode result = object();
        for (String table :
                List.of(
                        "grade_batch",
                        "grade_job",
                        "grade_attempt",
                        "grade_event",
                        "execution_issue",
                        "evidence_set",
                        "evidence_item")) {
            String join =
                    switch (table) {
                        case "grade_attempt", "grade_event" ->
                                "JOIN grade_job j ON j.id=t.job_id JOIN review_snapshot s ON"
                                        + " s.id=j.snapshot_id";
                        default -> "JOIN review_snapshot s ON s.id=t.snapshot_id";
                    };
            String excluded =
                    switch (table) {
                        case "evidence_set" -> " AND t.id<>" + (set == null ? 0 : set);
                        case "evidence_item" -> " AND t.set_id<>" + (set == null ? 0 : set);
                        default -> "";
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
                                            + " t "
                                            + join
                                            + " WHERE s.version_id=?"
                                            + excluded,
                                    String.class,
                                    scope.versionId())));
        }
        for (String table : List.of("test_action", "test_audit")) {
            String exclusions =
                    "test_action".equals(table)
                            ? Long.toString(receipt == null ? 0 : receipt)
                            : audits.isEmpty()
                                    ? "0"
                                    : String.join(
                                            ",", audits.stream().map(Object::toString).toList());
            result.set(
                    table,
                    parse(
                            db.queryForObject(
                                    "SELECT"
                                        + " coalesce(jsonb_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex')"
                                        + " ORDER BY t.id),'[]'::jsonb)::text FROM "
                                            + table
                                            + " t WHERE (scope_key=? OR scope_key IN (SELECT"
                                            + " 'batch:'||b.batch_key::text FROM grade_batch b JOIN"
                                            + " review_snapshot s ON s.id=b.snapshot_id WHERE"
                                            + " s.version_id=?) OR scope_key IN (SELECT"
                                            + " 'issue:'||i.issue_key::text FROM execution_issue i"
                                            + " JOIN review_snapshot s ON s.id=i.snapshot_id WHERE"
                                            + " s.version_id=?)) AND id NOT IN ("
                                            + exclusions
                                            + ")",
                                    String.class,
                                    "version:" + scope.versionId(),
                                    scope.versionId(),
                                    scope.versionId())));
        }
        return result;
    }

    /** 저장 전체 행을 독립적으로 구성한 기대값과 정규 바이트로 비교한다. */
    private void requireRow(String table, long id, JsonNode expected) {
        if (!same(expected, rowJson(table, id))) throw storage();
    }

    private JsonNode rowJson(String table, long id) {
        return parse(
                db.queryForObject(
                        "SELECT to_jsonb(t)::text FROM " + table + " t WHERE id=?",
                        String.class,
                        id));
    }

    /** 비밀 없는 JDBC 행을 SQL과 같은 JSON 표현으로 변환한다. */
    private JsonNode mapJson(Map<String, Object> row) {
        ObjectNode result = object();
        for (var entry : row.entrySet()) {
            Object value = entry.getValue();
            if (value == null) result.putNull(entry.getKey());
            else if (value instanceof Boolean bool) result.put(entry.getKey(), bool);
            else if (value instanceof Number number) result.put(entry.getKey(), number.longValue());
            else if (value instanceof java.sql.Timestamp || value instanceof OffsetDateTime)
                result.set(entry.getKey(), timeJson(value));
            else result.put(entry.getKey(), value.toString());
        }
        return result;
    }

    /** 최초 영수증 전체 열은 독립된 입력 사실로 구성한다. */
    private static ObjectNode receiptExpected(
            long id,
            long adminId,
            Input input,
            String scope,
            String hash,
            EvidenceResult result,
            JsonNode time) {
        ObjectNode row =
                object().put("id", id)
                        .put("request_key", input.key().toString())
                        .put("admin_id", adminId)
                        .putNull("member_id")
                        .put("action", ACTION)
                        .put("scope_key", scope)
                        .put("request_hash", hash);
        row.set("result_data", resultJson(result));
        row.set("created_at", time);
        return row;
    }

    /** 최초 생성 시점의 정확한 영수증 구조만 해석한다. */
    private static EvidenceOriginal decodeOriginal(JsonNode node) {
        try {
            exact(node, "action", "replayed", "changed", "original", "current", "requestId");
            if (!ACTION.equals(node.path("action").textValue())
                    || !node.path("replayed").isBoolean()
                    || node.path("replayed").booleanValue()
                    || !node.path("changed").isBoolean()
                    || !node.path("changed").booleanValue()) throw storage();
            JsonNode original = node.get("original");
            exact(
                    original,
                    "setKey",
                    "recordId",
                    "snapshotId",
                    "runtimeConfigId",
                    "editRev",
                    "kind",
                    "result",
                    "createdAt");
            if (!"GRADE".equals(original.path("kind").textValue())
                    || !"PASS".equals(original.path("result").textValue())) throw storage();
            decimal(original.path("recordId").textValue(), true);
            decimal(original.path("snapshotId").textValue(), true);
            decimal(original.path("editRev").textValue(), false);
            return new EvidenceOriginal(
                    storedUuid(original.path("setKey").textValue()),
                    original.path("recordId").textValue(),
                    original.path("snapshotId").textValue(),
                    original.path("runtimeConfigId").textValue(),
                    original.path("editRev").textValue(),
                    "GRADE",
                    "PASS",
                    Instant.parse(original.path("createdAt").textValue()));
        } catch (RuntimeException malformed) {
            throw storage();
        }
    }

    private static EvidenceCurrent current(ReviewScope scope, Map<String, Object> set) {
        return new EvidenceCurrent(
                Long.toString(number(set, "snapshot_id")),
                Objects.equals(scope.snapshotId(), number(set, "snapshot_id")),
                Long.toString(scope.rev()),
                Boolean.TRUE.equals(set.get("available_yn")));
    }

    /** Java 시간 모듈에 의존하지 않는 닫힌 영수증 JSON이다. */
    private static ObjectNode resultJson(EvidenceResult result) {
        ObjectNode root =
                object().put("action", result.action())
                        .put("replayed", result.replayed())
                        .put("changed", result.changed())
                        .put("requestId", result.requestId().toString());
        var original = result.original();
        root.set(
                "original",
                object().put("setKey", original.setKey().toString())
                        .put("recordId", original.recordId())
                        .put("snapshotId", original.snapshotId())
                        .put("runtimeConfigId", original.runtimeConfigId())
                        .put("editRev", original.editRev())
                        .put("kind", original.kind())
                        .put("result", original.result())
                        .put("createdAt", original.createdAt().toString()));
        var current = result.current();
        root.set(
                "current",
                object().put("snapshotId", current.snapshotId())
                        .put("current", current.current())
                        .put("editRev", current.editRev())
                        .put("available", current.available()));
        return root;
    }

    /** 닫힌 해소 연결은 숫자 ID순으로 정규화하며 중복·숫자 JSON·자유서술은 거절한다. */
    static JsonNode normalizeResolves(JsonNode resolves) {
        if (resolves == null || !resolves.isArray() || resolves.size() > 100) throw invalid();
        List<JsonNode> rows = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (JsonNode row : resolves) {
            if (!row.isObject()
                    || row.size() != 3
                    || !row.has("recordId")
                    || !row.has("reasonCode")
                    || !row.has("verificationRef")) throw invalid();
            long id = decimal(row.path("recordId").textValue(), true);
            String reason = row.path("reasonCode").textValue();
            String ref = row.path("verificationRef").textValue();
            if (!ids.add(id)
                    || !Set.of("RECORD_CORRECTION", "ISSUE_VERIFIED")
                            .contains(reason == null ? "" : reason)
                    || ref == null
                    || !ref.matches("[A-Za-z0-9_-]{8,64}")) throw invalid();
            rows.add(
                    object().put("recordId", Long.toString(id))
                            .put("reasonCode", reason)
                            .put("verificationRef", ref));
        }
        rows.sort(
                Comparator.comparingLong(row -> Long.parseLong(row.path("recordId").textValue())));
        var result = JsonNodeFactory.instance.arrayNode();
        rows.forEach(result::add);
        return result;
    }

    /** 실제 DB 시계의 UTC 순간만 업무 시각으로 사용한다. */
    private OffsetDateTime now() {
        return Objects.requireNonNull(
                db.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
    }

    private JsonNode timeJson(Object value) {
        return parse(
                db.queryForObject(
                        "SELECT to_jsonb(?::timestamptz)::text",
                        String.class,
                        OffsetDateTime.ofInstant(instant(value), java.time.ZoneOffset.UTC)));
    }

    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp time) return time.toInstant();
        if (value instanceof OffsetDateTime time) return time.toInstant();
        throw storage();
    }

    private static long decimal(String value, boolean positive) {
        if (value == null || !value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*"))
            throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw invalid();
        }
    }

    private static void uuid(UUID value) {
        if (value == null || value.version() != 4 || value.variant() != 2) throw invalid();
    }

    private static UUID storedUuid(String value) {
        try {
            UUID key = UUID.fromString(value);
            uuid(key);
            if (!key.toString().equals(value)) throw storage();
            return key;
        } catch (RuntimeException error) {
            throw storage();
        }
    }

    private static JsonNode parse(Object value) {
        if (value == null) throw storage();
        return SnapshotJson.parse(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(JsonNode value) {
        return new String(SnapshotJson.encode(value), StandardCharsets.UTF_8);
    }

    private static boolean same(JsonNode left, JsonNode right) {
        return java.util.Arrays.equals(SnapshotJson.encode(left), SnapshotJson.encode(right));
    }

    private static void exact(JsonNode node, String... keys) {
        if (node == null || !node.isObject() || node.size() != keys.length) throw storage();
        for (String key : keys) if (!node.has(key)) throw storage();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** SQL 원인을 노출하지 않고 지정된 전역 키 제약의 경쟁만 식별한다. */
    private static boolean uniqueReceipt(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof org.postgresql.util.PSQLException pg
                    && "23505".equals(pg.getSQLState())
                    && pg.getServerErrorMessage() != null
                    && "uk_test_action_request".equals(pg.getServerErrorMessage().getConstraint()))
                return true;
        return false;
    }

    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (ReceiptRace race) {
            throw race;
        } catch (AuthException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw storage();
        }
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    private static AuthException incomplete() {
        return AuthException.unprocessable("EVIDENCE_INCOMPLETE");
    }

    private static AuthException missing() {
        return new AuthException(404, "NOT_FOUND", "NOT_FOUND");
    }

    private static AuthException storage() {
        return AuthException.unavailable("STORY_UNAVAILABLE");
    }

    private static final class ReceiptRace extends RuntimeException {
        private ReceiptRace() {
            super(null, null, false, false);
        }
    }

    private record Input(
            long rev,
            long snapshot,
            String runtime,
            List<UUID> refs,
            JsonNode resolves,
            UUID key,
            UUID request) {}

    private record StoredIssuance(
            long id,
            long snapshot,
            boolean available,
            Instant createdAt,
            Instant withdrawnAt,
            Long issueId,
            List<Long> targets) {}

    private record Withdrawal(long snapshot, Instant createdAt) {}

    private static final class ResolutionReads {
        private final Map<Long, StoredIssuance> issuances = new LinkedHashMap<>();
        private final Map<Long, Withdrawal> withdrawals = new LinkedHashMap<>();
        private final Set<Long> linked = new HashSet<>();
    }

    public record EvidenceOriginal(
            UUID setKey,
            String recordId,
            String snapshotId,
            String runtimeConfigId,
            String editRev,
            String kind,
            String result,
            Instant createdAt) {}

    public record EvidenceCurrent(
            String snapshotId, boolean current, String editRev, boolean available) {}

    public record EvidenceResult(
            String action,
            boolean replayed,
            boolean changed,
            EvidenceOriginal original,
            EvidenceCurrent current,
            UUID requestId) {}
}
