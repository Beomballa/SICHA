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
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 현재 REVIEW의 실제 후속 BATCH 증거로 지적 한 건만 해소하며 운영 효력·사람 검토를 만들지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryIssueResolutionService {
    private static final Set<String> REASONS =
            Set.of("GRADING_FIX_VERIFIED", "INFRA_RECOVERED", "OBSERVATION_COMPLETED");
    private final StoryService stories;
    private final JdbcTemplate db;
    private final GradeRuntimeRepository runtimes;
    private final StoryBatchService batches;

    /** 현재 부모 인가·실제 JDBC·runtime 잠금과 인증된 전체 집합 비교 경계를 주입한다. */
    public StoryIssueResolutionService(
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
     * 현재 REVIEW 쌍으로 인증한 뒤 같은 의도는 원래 영수증을 재생하고 신규 해소만 원자 저장한다.
     *
     * @param sid 현재 저장 세션 ID, null 불가
     * @param actor 서버 인증 관리자, null 불가
     * @param storyCode 활성 부모 사건 코드
     * @param versionNo 양의 버전 번호
     * @param issueKey UUID v4 지적 키
     * @param expectedRev 선행 0 없는 수정번호; 0 허용
     * @param requestKey 전역 UUID v4 의도 키
     * @param reasonCode 세 가지 닫힌 사유 중 해당 지적의 사유
     * @param verificationRef 비개인 ASCII 확인 참조 8~64자
     * @param targetBatchKey 실제 후속 BATCH UUID v4 또는 null
     * @param targetReviewId 정규 양의 BIGINT 또는 null; 현재 BATCH 경계에는 실제 부모가 없어 해소 불가
     * @param requestId 서버 상관 UUID v4
     * @return 새 변경 또는 현재 인가된 최초 해소 영수증; 원고·모델 출력 없음
     * @throws AuthException 입력·현재 권한·상태·후속 증거·필수 저장 오류
     */
    public ResolutionResult resolveIssue(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            UUID issueKey,
            String expectedRev,
            UUID requestKey,
            String reasonCode,
            String verificationRef,
            UUID targetBatchKey,
            String targetReviewId,
            UUID requestId) {
        uuid(issueKey);
        uuid(requestKey);
        uuid(requestId);
        long rev = decimal(expectedRev, false);
        if (targetBatchKey != null) uuid(targetBatchKey);
        if (targetReviewId != null) decimal(targetReviewId, true);
        if ((targetBatchKey == null) == (targetReviewId == null)
                || !REASONS.contains(reasonCode == null ? "" : reasonCode)
                || verificationRef == null
                || !verificationRef.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        ObjectNode input =
                object().put("storyCode", storyCode)
                        .put("versionNo", versionNo)
                        .put("issueKey", issueKey.toString())
                        .put("expectedRev", expectedRev)
                        .put("requestKey", requestKey.toString())
                        .put("reasonCode", reasonCode)
                        .put("verificationRef", verificationRef)
                        .put("targetReviewId", targetReviewId)
                        .put(
                                "targetBatchKey",
                                targetBatchKey == null ? null : targetBatchKey.toString());
        String hash = SnapshotJson.hash(input);
        try {
            return execute(
                    sid,
                    actor,
                    storyCode,
                    versionNo,
                    issueKey,
                    rev,
                    requestKey,
                    reasonCode,
                    verificationRef,
                    targetBatchKey,
                    requestId,
                    hash,
                    false);
        } catch (ReceiptRace race) {
            // 실패한 거래는 끝났다. 승자 영수증만 새 현재 인가 거래에서 확인한다.
            return execute(
                    sid,
                    actor,
                    storyCode,
                    versionNo,
                    issueKey,
                    rev,
                    requestKey,
                    reasonCode,
                    verificationRef,
                    targetBatchKey,
                    requestId,
                    hash,
                    true);
        }
    }

    /** runtime을 정순으로 잠근 뒤 기존 현재 인증·활성 부모·확정 직전 자격 거래를 재사용한다. */
    private ResolutionResult execute(
            String sid,
            AdminActor actor,
            String code,
            int version,
            UUID issue,
            long rev,
            UUID key,
            String reason,
            String ref,
            UUID target,
            UUID request,
            String hash,
            boolean replayOnly) {
        return guarded(
                () ->
                        stories.withBatchAction(
                                sid,
                                actor,
                                code,
                                version,
                                () -> lockRuntimes(issue, target),
                                (scope, locked) ->
                                        apply(
                                                scope,
                                                locked,
                                                actor,
                                                code,
                                                version,
                                                issue,
                                                rev,
                                                key,
                                                reason,
                                                ref,
                                                target,
                                                request,
                                                hash,
                                                replayOnly)));
    }

    /** 관계 발견은 정보를 반환하지 않으며 실제 부모 인가 뒤 원본 귀속·runtime 결속을 다시 검사한다. */
    private Map<Long, RuntimeRow> lockRuntimes(UUID issue, UUID target) {
        List<Long> ids =
                db.queryForList(
                        "SELECT runtime_id FROM execution_issue WHERE issue_key=? UNION SELECT"
                            + " runtime_id FROM grade_batch WHERE batch_key=? ORDER BY runtime_id",
                        Long.class,
                        issue,
                        target);
        Map<Long, RuntimeRow> result = new LinkedHashMap<>();
        for (long id : ids)
            result.put(
                    id, runtimes.lockRuntime(id).orElseThrow(StoryIssueResolutionService::storage));
        return Map.copyOf(result);
    }

    /** 신규 정책보다 먼저 현재 인가된 전역 영수증을 검사하고 실제 원본·대상만 변경한다. */
    private ResolutionResult apply(
            ReviewScope scope,
            Map<Long, RuntimeRow> locked,
            AdminActor actor,
            String code,
            int version,
            UUID issueKey,
            long rev,
            UUID key,
            String reason,
            String ref,
            UUID targetKey,
            UUID request,
            String hash,
            boolean replayOnly) {
        String scopeKey = "issue:" + issueKey;
        var receipts = db.queryForList("SELECT * FROM test_action WHERE request_key=?", key);
        if (!receipts.isEmpty()) {
            var receipt = receipts.getFirst();
            if (receipt.get("admin_id") == null
                    || receipt.get("member_id") != null
                    || number(receipt, "admin_id") != actor.accountId()
                    || !"ISSUE_RESOLVE".equals(receipt.get("action"))
                    || !scopeKey.equals(receipt.get("scope_key"))
                    || !hash.equals(receipt.get("request_hash")))
                throw AuthException.conflict("REQUEST_KEY_CONFLICT");
            var issue = issue(scope, issueKey, true);
            JsonNode stored = parse(receipt.get("result_data"));
            exact(stored, "action", "replayed", "changed", "original", "current", "requestId");
            if (!"ISSUE_RESOLVE".equals(stored.path("action").textValue())
                    || !stored.path("replayed").isBoolean()
                    || stored.path("replayed").booleanValue()
                    || !stored.path("changed").isBoolean()
                    || !stored.path("changed").booleanValue()
                    || !same(stored.get("original"), stored.get("current"))) throw storage();
            storedUuid(stored.path("requestId").textValue());
            IssueResult original = decode(stored.get("original"));
            IssueResult current = projection(scope, issue);
            if (!issueKey.equals(original.issueKey())
                    || !Objects.equals(targetKey, original.targetBatchKey())
                    || !Long.toString(rev).equals(original.editRev())
                    || !original.snapshotId().equals(current.snapshotId())
                    || !original.resolvedAt().equals(current.resolvedAt())
                    || !original.resolvedAt().equals(instant(receipt.get("created_at")))
                    || !original.targetBatchKey().equals(current.targetBatchKey())
                    || number(issue, "resolved_by") != actor.accountId()
                    || !same(
                            parse(issue.get("resolution_data")),
                            object().put("reasonCode", reason).put("verificationRef", ref)))
                throw storage();
            return new ResolutionResult("ISSUE_RESOLVE", true, false, original, current, request);
        }
        if (replayOnly) throw storage();
        var discovered = issue(scope, issueKey, false);
        long sourceId = number(discovered, "batch_id");
        var targetRows =
                targetKey == null
                        ? List.<Map<String, Object>>of()
                        : db.queryForList(
                                "SELECT b.* FROM grade_batch b JOIN review_snapshot s ON"
                                    + " s.id=b.snapshot_id WHERE b.batch_key=? AND s.version_id=?",
                                targetKey,
                                scope.versionId());
        if (targetKey != null && targetRows.isEmpty()) throw missing();
        long targetId = targetRows.isEmpty() ? sourceId : number(targetRows.getFirst(), "id");
        // 원본·대상 집합을 먼저 정순으로 잠근다. 원본 issue를 잡고 부모 집합 잠금을 추가하지 않는다.
        db.queryForList(
                "SELECT id FROM grade_batch WHERE id IN (?,?) ORDER BY id FOR UPDATE",
                sourceId,
                targetId);
        var issue = issue(scope, issueKey, true);
        if (!"OPEN".equals(issue.get("state"))) throw AuthException.conflict("STATE_CONFLICT");
        if (scope.rev() != rev) throw AuthException.conflict("EDIT_CONFLICT");
        if (!"REVIEW".equals(scope.status())
                || !Objects.equals(scope.snapshotId(), number(issue, "snapshot_id")))
            throw AuthException.conflict("STATE_CONFLICT");
        String kind = (String) issue.get("kind");
        if (targetKey == null
                || !("GRADING".equals(kind) && "GRADING_FIX_VERIFIED".equals(reason)
                        || "INFRA".equals(kind) && "INFRA_RECOVERED".equals(reason)))
            throw AuthException.unprocessable("ISSUE_NOT_RESOLVABLE");
        var source = db.queryForMap("SELECT * FROM grade_batch WHERE id=?", sourceId);
        var target = db.queryForMap("SELECT * FROM grade_batch WHERE id=?", targetId);
        RuntimeRow sourceRuntime = locked.get(number(source, "runtime_id"));
        RuntimeRow targetRuntime = locked.get(number(target, "runtime_id"));
        if (sourceRuntime == null
                || targetRuntime == null
                || number(issue, "runtime_id") != sourceRuntime.id()
                || number(source, "snapshot_id") != number(issue, "snapshot_id")) throw storage();
        requireTarget(scope, issue, source, target, sourceRuntime, targetRuntime, kind);
        requireOrigin(issue, source);
        ObjectNode before = preserved(scope, issue, null, List.of());
        long firstAudit = maxAudit();
        var sourceDetail =
                batches.readBatchDetail(
                        scope,
                        sourceRuntime,
                        code,
                        version,
                        (UUID) source.get("batch_key"),
                        actor,
                        request);
        var targetDetail =
                batches.readBatchDetail(
                        scope, targetRuntime, code, version, targetKey, actor, request);
        if ("GRADING".equals(kind)
                && sourceDetail.items().stream()
                        .noneMatch(
                                item ->
                                        "COMPLETED".equals(item.state())
                                                && "FAIL".equals(item.comparison())))
            throw storage();
        if (!"COMPLETED".equals(targetDetail.state())
                || !Boolean.TRUE.equals(targetDetail.passed())
                || targetDetail.completedJobs() != targetDetail.totalJobs()
                || targetDetail.totalJobs() == 0
                || targetDetail.failedComparisons() != 0
                || targetDetail.unresolvedJobs() != 0
                || targetDetail.items().stream()
                        .anyMatch(item -> !"PASS".equals(item.comparison()))) throw incomplete();
        batches.verifyResolutionAdoption(number(target, "snapshot_id"), targetId);
        List<Long> audits =
                db.queryForList(
                        "SELECT id FROM test_audit WHERE id>? AND request_id=? AND"
                            + " actor_kind='ADMIN' AND actor_ref=? AND action='CONTENT_READ' AND"
                            + " scope_kind='batch' AND scope_key IN (?,?) ORDER BY id",
                        Long.class,
                        firstAudit,
                        request,
                        actor.accountKey().toString(),
                        "batch:" + source.get("batch_key"),
                        "batch:" + targetKey);
        if (audits.size() != 2 || !same(before, preserved(scope, issue, null, audits)))
            throw storage();
        List<Long> readIds = List.copyOf(audits);
        JsonNode readRows = auditRows(readIds);
        OffsetDateTime time = now();
        ObjectNode resolution = object().put("reasonCode", reason).put("verificationRef", ref);
        if (db.update(
                        "UPDATE execution_issue SET"
                            + " state='RESOLVED',resolved_batch_id=?,resolved_by=?,resolved_at=?,resolution_data=?::jsonb"
                            + " WHERE id=? AND state='OPEN'",
                        targetId,
                        actor.accountId(),
                        time,
                        encode(resolution),
                        number(issue, "id"))
                != 1) throw AuthException.conflict("STATE_CONFLICT");
        var expected = new LinkedHashMap<>(issue);
        expected.put("state", "RESOLVED");
        expected.put("resolved_batch_id", targetId);
        expected.put("target_batch_key", targetKey);
        expected.put("resolved_by", actor.accountId());
        expected.put("resolved_at", time);
        expected.put("resolution_data", encode(resolution));
        IssueResult original = projection(scope, expected);
        ResolutionResult result =
                new ResolutionResult("ISSUE_RESOLVE", false, true, original, original, request);
        Long receiptId;
        try {
            receiptId =
                    db.queryForObject(
                            "INSERT INTO"
                                + " test_action(request_key,admin_id,action,scope_key,request_hash,result_data,created_at)"
                                + " VALUES (?,?,'ISSUE_RESOLVE',?,?,?::jsonb,?) RETURNING id",
                            Long.class,
                            key,
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
        ObjectNode detail =
                object().put("issueKey", issueKey.toString())
                        .put("snapshotId", Long.toString(number(issue, "snapshot_id")))
                        .put("targetBatchKey", targetKey.toString())
                        .put("reasonCode", reason)
                        .put("verificationRef", ref)
                        .put("editRev", Long.toString(scope.rev()));
        UUID event = UUID.randomUUID();
        Long auditId =
                db.queryForObject(
                        "INSERT INTO"
                            + " test_audit(event_key,actor_kind,actor_ref,action,scope_kind,scope_key,request_id,phase,business_result,detail,created_at)"
                            + " VALUES"
                            + " (?,'ADMIN',?,'ISSUE_RESOLVE','issue',?,?,'RESULT','SUCCESS',?::jsonb,?)"
                            + " RETURNING id",
                        Long.class,
                        event,
                        actor.accountKey().toString(),
                        scopeKey,
                        request,
                        encode(detail),
                        time);
        if (auditId == null) throw storage();
        audits = new ArrayList<>(audits);
        audits.add(auditId);
        var storedIssue = issue(scope, issueKey, true);
        if (!same(parse(storedIssue.get("resolution_data")), resolution)
                || number(storedIssue, "resolved_by") != actor.accountId()
                || !projection(scope, storedIssue).equals(original)) throw storage();
        var receipt = db.queryForMap("SELECT * FROM test_action WHERE id=?", receiptId);
        if (!key.equals(receipt.get("request_key"))
                || number(receipt, "admin_id") != actor.accountId()
                || !"ISSUE_RESOLVE".equals(receipt.get("action"))
                || !scopeKey.equals(receipt.get("scope_key"))
                || !hash.equals(receipt.get("request_hash"))
                || !same(parse(receipt.get("result_data")), resultJson(result))
                || !time.toInstant().equals(instant(receipt.get("created_at")))) throw storage();
        var audit = db.queryForMap("SELECT * FROM test_audit WHERE id=?", auditId);
        if (!event.equals(audit.get("event_key"))
                || !"ADMIN".equals(audit.get("actor_kind"))
                || !actor.accountKey().toString().equals(audit.get("actor_ref"))
                || !"ISSUE_RESOLVE".equals(audit.get("action"))
                || !"issue".equals(audit.get("scope_kind"))
                || !scopeKey.equals(audit.get("scope_key"))
                || !request.equals(audit.get("request_id"))
                || !"RESULT".equals(audit.get("phase"))
                || !"SUCCESS".equals(audit.get("business_result"))
                || !same(parse(audit.get("detail")), detail)
                || !time.toInstant().equals(instant(audit.get("created_at")))) throw storage();
        if (!same(before, preserved(scope, issue, receiptId, audits))) throw storage();
        if (!same(readRows, auditRows(readIds))) throw storage();
        for (RuntimeRow row : locked.values())
            if (!row.equals(
                    runtimes.getRuntimeDetail(row.id())
                            .orElseThrow(StoryIssueResolutionService::storage))) throw storage();
        requireTarget(scope, issue, source, target, sourceRuntime, targetRuntime, kind);
        return result;
    }

    /** 현재 REVIEW의 새 전체 비교와 진짜 변경 설정·현재 epoch만 검사한다. 운영 TTL은 발급·요구하지 않는다. */
    private void requireTarget(
            ReviewScope scope,
            Map<String, Object> issue,
            Map<String, Object> source,
            Map<String, Object> target,
            RuntimeRow sourceRuntime,
            RuntimeRow targetRuntime,
            String kind) {
        if (number(source, "id") == number(target, "id")
                || number(target, "snapshot_id") != number(issue, "snapshot_id")
                || !Set.of("REVIEW", "AVAILABILITY").contains((String) source.get("purpose"))
                || !Set.of("REVIEW", "AVAILABILITY").contains((String) target.get("purpose"))
                || !"COMPLETED".equals(target.get("state"))
                || !Boolean.TRUE.equals(target.get("passed_yn"))
                || target.get("ended_at") == null
                || !instant(target.get("created_at")).isAfter(instant(issue.get("created_at")))
                || instant(target.get("ended_at")).isBefore(instant(target.get("created_at")))
                || !instant(target.get("ended_at"))
                        .isBefore(instant(target.get("created_at")).plusSeconds(86400))
                || instant(target.get("ended_at")).isAfter(now().toInstant())
                || !Objects.equals(source.get("dataset_hash"), target.get("dataset_hash"))
                || !Objects.equals(source.get("rubric_hash"), target.get("rubric_hash"))
                || !Objects.equals(source.get("payload_hash"), target.get("payload_hash")))
            throw incomplete();
        if (number(target, "runtime_epoch") != targetRuntime.epoch())
            throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        batches.verifyResolutionRuntime(targetRuntime, scope.policyCode());
        if ("GRADING".equals(kind)) {
            ObjectNode sourceConfig = (ObjectNode) parse(sourceRuntime.configJson());
            ObjectNode targetConfig = (ObjectNode) parse(targetRuntime.configJson());
            sourceConfig.remove("configId");
            targetConfig.remove("configId");
            if (sourceRuntime.id() == targetRuntime.id()
                    || sourceRuntime.configHash().equals(targetRuntime.configHash())
                    || same(sourceConfig, targetConfig)) throw incomplete();
        }
    }

    /** 원본 지적은 실제 자동 집계 감사와 결속되어야 하며 임의 신규 OPEN 행은 해소 증거가 아니다. */
    private void requireOrigin(Map<String, Object> issue, Map<String, Object> source) {
        var audits =
                db.queryForList(
                        "SELECT detail::text FROM test_audit WHERE actor_kind='SYSTEM' AND"
                                + " action='BATCH_ISSUE' AND scope_kind='batch' AND scope_key=? AND"
                                + " phase='RESULT' AND business_result='SUCCESS' AND"
                                + " detail->>'issueId'=?",
                        String.class,
                        "batch:" + source.get("batch_key"),
                        Long.toString(number(issue, "id")));
        if (audits.size() != 1
                || !same(
                        parse(audits.getFirst()),
                        object().put("issueId", Long.toString(number(issue, "id")))
                                .put("kind", (String) issue.get("kind"))
                                .put("reason", (String) issue.get("reason_code")))) throw storage();
    }

    /** 현재 인가한 실제 버전 소속의 지적만 읽고 필요할 때 자식 행을 잠근다. */
    private Map<String, Object> issue(ReviewScope scope, UUID key, boolean lock) {
        var rows =
                db.queryForList(
                        "SELECT i.*,b.batch_key AS source_batch_key,t.batch_key AS target_batch_key"
                            + " FROM execution_issue i JOIN review_snapshot s ON s.id=i.snapshot_id"
                            + " JOIN grade_batch b ON b.id=i.batch_id AND"
                            + " b.snapshot_id=i.snapshot_id AND b.runtime_id=i.runtime_id LEFT JOIN"
                            + " grade_batch t ON t.id=i.resolved_batch_id AND"
                            + " t.snapshot_id=i.snapshot_id WHERE i.issue_key=? AND s.version_id=?"
                                + (lock ? " FOR UPDATE OF i" : ""),
                        key,
                        scope.versionId());
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    /** 선택 지적의 허용 해소 열 외에는 전체 실행·과거 영수증·감사 행을 보존한다. */
    private ObjectNode preserved(
            ReviewScope scope, Map<String, Object> issue, Long receipt, List<Long> audits) {
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
                        case "grade_batch", "execution_issue", "grade_job" ->
                                "JOIN review_snapshot s ON s.id=t.snapshot_id";
                        default ->
                                "JOIN grade_job j ON j.id=t.job_id JOIN review_snapshot s ON"
                                        + " s.id=j.snapshot_id";
                    };
            String row =
                    "execution_issue".equals(table)
                            ? "CASE WHEN t.id="
                                    + number(issue, "id")
                                    + " THEN"
                                    + " to_jsonb(t)-ARRAY['state','resolved_batch_id','resolved_by','resolved_at','resolution_data']"
                                    + " ELSE to_jsonb(t) END"
                            : "to_jsonb(t)";
            result.set(
                    table,
                    parse(
                            db.queryForObject(
                                    "SELECT coalesce(jsonb_agg(encode(sha256(convert_to(("
                                            + row
                                            + ")::text,'UTF8')),'hex') ORDER BY ("
                                            + row
                                            + ")::text COLLATE \"C\"),'[]'::jsonb)::text FROM "
                                            + table
                                            + " t "
                                            + join
                                            + " WHERE s.version_id=?",
                                    String.class,
                                    scope.versionId())));
        }
        for (String table : List.of("test_action", "test_audit")) {
            String excluded =
                    "test_action".equals(table)
                            ? receipt == null ? "0" : receipt.toString()
                            : audits.isEmpty()
                                    ? "0"
                                    : String.join(
                                            ",", audits.stream().map(Object::toString).toList());
            result.set(
                    table,
                    parse(
                            db.queryForObject(
                                    "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY"
                                            + " id),'[]'::jsonb)::text FROM "
                                            + table
                                            + " t WHERE (scope_key=? OR scope_key IN (SELECT"
                                            + " 'batch:'||b.batch_key::text FROM grade_batch b JOIN"
                                            + " review_snapshot s ON s.id=b.snapshot_id WHERE"
                                            + " s.version_id=?) OR scope_key IN (SELECT"
                                            + " 'issue:'||i.issue_key::text FROM execution_issue i"
                                            + " JOIN review_snapshot s ON s.id=i.snapshot_id WHERE"
                                            + " s.version_id=?)) AND id NOT IN ("
                                            + excluded
                                            + ")",
                                    String.class,
                                    "version:" + scope.versionId(),
                                    scope.versionId(),
                                    scope.versionId())));
        }
        return result;
    }

    /** 실제 해소 대상·서버 시각과 변경하지 않은 부모 수정번호만 투영한다. */
    private static IssueResult projection(ReviewScope scope, Map<String, Object> issue) {
        UUID target = (UUID) issue.get("target_batch_key");
        if (!"RESOLVED".equals(issue.get("state"))
                || target == null
                || issue.get("resolved_at") == null) throw storage();
        return new IssueResult(
                (UUID) issue.get("issue_key"),
                Long.toString(number(issue, "snapshot_id")),
                Long.toString(scope.rev()),
                "RESOLVED",
                target,
                instant(issue.get("resolved_at")));
    }

    /** 최초 영수증의 닫힌 형식·정규 식별자·확정 상태만 해독한다. */
    private static IssueResult decode(JsonNode value) {
        try {
            exact(
                    value,
                    "issueKey",
                    "snapshotId",
                    "editRev",
                    "state",
                    "targetBatchKey",
                    "resolvedAt");
            decimal(value.path("snapshotId").textValue(), true);
            decimal(value.path("editRev").textValue(), false);
            if (!"RESOLVED".equals(value.path("state").textValue())) throw storage();
            return new IssueResult(
                    storedUuid(value.path("issueKey").textValue()),
                    value.path("snapshotId").textValue(),
                    value.path("editRev").textValue(),
                    "RESOLVED",
                    storedUuid(value.path("targetBatchKey").textValue()),
                    Instant.parse(value.path("resolvedAt").textValue()));
        } catch (RuntimeException invalid) {
            throw storage();
        }
    }

    /** 최초 영수증은 공통 표준 JSON과 정확한 여섯 필드로 직렬화한다. */
    private static ObjectNode resultJson(ResolutionResult result) {
        ObjectNode node =
                object().put("action", result.action())
                        .put("replayed", result.replayed())
                        .put("changed", result.changed())
                        .put("requestId", result.requestId().toString());
        node.set("original", projectionJson(result.original()));
        node.set("current", projectionJson(result.current()));
        return node;
    }

    /** 원고·검증 원문 없는 여섯 필드의 안전 해소 투영이다. */
    private static ObjectNode projectionJson(IssueResult result) {
        return object().put("issueKey", result.issueKey().toString())
                .put("snapshotId", result.snapshotId())
                .put("editRev", result.editRev())
                .put("state", result.state())
                .put("targetBatchKey", result.targetBatchKey().toString())
                .put("resolvedAt", result.resolvedAt().toString());
    }

    /** 원인·SQL·비밀은 노출하지 않고 실제 잠금 실패와 의도 키 경쟁만 구분한다. */
    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (ReceiptRace race) {
            throw race;
        } catch (AuthException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            String state = null;
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof SQLException sql) state = sql.getSQLState();
            throw AuthException.unavailable(
                    Set.of("55P03", "40P01").contains(state == null ? "" : state)
                            ? "STORY_BUSY"
                            : "STORY_UNAVAILABLE");
        } catch (RuntimeException failure) {
            throw storage();
        }
    }

    /** 실제 전역 키 유니크 제약의 경쟁만 새 거래에서 재생한다. */
    private static boolean uniqueReceipt(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof org.postgresql.util.PSQLException pg
                    && "23505".equals(pg.getSQLState())
                    && pg.getServerErrorMessage() != null
                    && "uk_test_action_request".equals(pg.getServerErrorMessage().getConstraint()))
                return true;
        return false;
    }

    /** 입력의 정규 십진 bigint만 허용한다. */
    private static long decimal(String value, boolean positive) {
        if (value == null || !value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*"))
            throw AuthException.badRequest("INVALID_REQUEST");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    /** 입력의 UUID v4만 허용한다. */
    private static void uuid(UUID value) {
        if (value == null || value.version() != 4 || value.variant() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 저장 식별자의 정규형은 입력 오류로 반환하지 않는다. */
    private static UUID storedUuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            uuid(result);
            if (!result.toString().equals(value)) throw storage();
            return result;
        } catch (RuntimeException invalid) {
            throw storage();
        }
    }

    /** 실제 새 DB 시각만 사용한다. */
    private OffsetDateTime now() {
        return Objects.requireNonNull(
                db.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
    }

    /** 감사 추가 전 내부 경계이며 외부 식별자나 페이지 번호가 아니다. */
    private long maxAudit() {
        return Objects.requireNonNull(
                db.queryForObject("SELECT coalesce(max(id),0) FROM test_audit", Long.class));
    }

    /** 후속 해소 쓰기가 앞서 확정한 두 읽기 감사의 전체 행을 바꾸거나 지우지 않았는지 대조한다. */
    private JsonNode auditRows(List<Long> ids) {
        return parse(
                db.queryForObject(
                        "SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY id),'[]'::jsonb)::text FROM"
                                + " test_audit a WHERE id IN (?,?)",
                        String.class,
                        ids.get(0),
                        ids.get(1)));
    }

    /** JDBC 시각의 UTC 순간만 비교한다. */
    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp time) return time.toInstant();
        if (value instanceof OffsetDateTime time) return time.toInstant();
        throw storage();
    }

    /** 저장 JSON의 공통 엄격 parser와 표준 바이트만 사용한다. */
    private static JsonNode parse(Object value) {
        if (value == null) throw storage();
        return SnapshotJson.parse(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 영수증·감사 객체를 표준 UTF-8 JSON으로 저장한다. */
    private static String encode(JsonNode value) {
        return new String(SnapshotJson.encode(value), StandardCharsets.UTF_8);
    }

    /** 동일한 JSON 의미를 표준 바이트로 비교한다. */
    private static boolean same(JsonNode a, JsonNode b) {
        return java.util.Arrays.equals(SnapshotJson.encode(a), SnapshotJson.encode(b));
    }

    /** 저장 객체에 정확한 허용 필드만 있는지 검사한다. */
    private static void exact(JsonNode node, String... keys) {
        if (node == null || !node.isObject() || node.size() != keys.length) throw storage();
        for (String key : keys) if (!node.has(key)) throw storage();
    }

    /** JDBC 내부 식별자는 실제 숫자형만 인정한다. */
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
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

    public record IssueResult(
            UUID issueKey,
            String snapshotId,
            String editRev,
            String state,
            UUID targetBatchKey,
            Instant resolvedAt) {}

    public record ResolutionResult(
            String action,
            boolean replayed,
            boolean changed,
            IssueResult original,
            IssueResult current,
            UUID requestId) {}
}
