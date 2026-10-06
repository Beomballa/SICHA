package com.reasoning.common.story.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.service.StoryService.CloneScope;
import com.reasoning.common.story.service.StoryService.Warning;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 현재 공개 사본의 실제 콘텐츠만 새 복합 식별자로 복제하고 원자 감사·영수증·전체 저장 행을 대조한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryCloneService {
    private static final List<Child> CHILDREN =
            List.of(
                    new Child(
                            "persons",
                            "story_person",
                            "code,name,public_text,secret_text",
                            "code,name,publicText,secretText"),
                    new Child("roles", "story_role", "code,name,brief", "code,name,brief"),
                    new Child("pairs", "story_pair", "role_a,role_b", "roleA,roleB"),
                    new Child(
                            "clues",
                            "story_clue",
                            "code,title,body,person_code,scope,source_text",
                            "code,title,body,personCode,scope,sourceText"),
                    new Child("clueRoles", "clue_role", "clue_code,role_code", "clueCode,roleCode"),
                    new Child("hints", "story_hint", "code,level,body", "code,level,body"),
                    new Child(
                            "events",
                            "story_event",
                            "code,start_min,end_min,actual_text,apparent_text",
                            "code,startMin,endMin,actualText,apparentText"),
                    new Child(
                            "facts",
                            "story_fact",
                            "code,statement,truth,basis",
                            "code,statement,truth,basis"),
                    new Child(
                            "rubrics",
                            "story_rubric",
                            "code,category,max_score,required_yn,pass_score,accepted_text,partial_text,reject_text,rule_data",
                            "code,category,maxScore,requiredYn,passScore,acceptedText,partialText,rejectText,ruleData"),
                    new Child(
                            "rubricClues",
                            "rubric_clue",
                            "rubric_code,clue_code,link_text",
                            "rubricCode,clueCode,linkText"),
                    new Child(
                            "gradeSamples",
                            "grade_sample",
                            "code,input_data,expect_data,expected_score,expected_success,reason,checked_by",
                            "code,inputData,expectData,expectedScore,expectedSuccess,reason,checkedBy"));
    private static final Map<String, String> SECTIONS =
            Map.ofEntries(
                    Map.entry("title", "basic.title"),
                    Map.entry("intro", "basic.intro"),
                    Map.entry("setting", "basic.setting"),
                    Map.entry("difficulty", "basic.difficulty"),
                    Map.entry("est_min", "basic.estMin"),
                    Map.entry("est_max", "basic.estMax"),
                    Map.entry("limit_sec", "basic.limitSec"),
                    Map.entry("timeline_origin", "basic.timelineOrigin"),
                    Map.entry("culprit_code", "answer.culpritCode"),
                    Map.entry("method_answer", "answer.methodAnswer"),
                    Map.entry("time_answer", "answer.timeAnswer"),
                    Map.entry("motive_answer", "answer.motiveAnswer"),
                    Map.entry("reveal_text", "reveal.revealText"));
    private static final Set<String> ORIGINAL_FIELDS =
            Set.of(
                    "versionNo",
                    "storyRev",
                    "editRev",
                    "playRev",
                    "publishedVersionNo",
                    "viewYn",
                    "sourceVersionNo",
                    "sourceSnapshotId",
                    "createdAt");
    private final StoryService stories;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    /** 부모 서비스의 JDBC 객체를 그대로 사용해 독립 DataSource·주변 거래 대체를 금지한다. */
    public StoryCloneService(StoryService stories, ObjectMapper json) {
        this.stories = stories;
        this.db = stories.cloneDatabase();
        this.json = json;
    }

    /**
     * 현재 소유자 또는 활성 EDIT가 현재 공개 사본만 복제하며 같은 키 성공은 상태 검사보다 먼저 재생한다.
     *
     * @param sid 현재 일반 관리자 세션 ID
     * @param actor 현재 저장 인증에 결속된 행위자
     * @param code 정규 사건 코드
     * @param expectedStoryRev 0 이상의 정규 십진 문자열
     * @param sourceVersionNo 양의 현재 공개 버전 번호
     * @param sourceSnapshotId 양의 정규 십진 사본 ID 문자열
     * @param requestKey RFC 변형 UUID v4 의도 키
     * @param requestId 서버 요청 상관 UUID
     * @return 답안 없는 최초 결과·현재 최소 상태·정책 경고
     * @throws AuthException 입력·권한·수정번호·공개본·사본·작업본 충돌 또는 원자 저장 실패 시
     */
    public ActionResult cloneDraft(
            String sid,
            AdminActor actor,
            String code,
            String expectedStoryRev,
            int sourceVersionNo,
            String sourceSnapshotId,
            UUID requestKey,
            UUID requestId) {
        long expected = decimal(expectedStoryRev, false);
        long snapshotId = decimal(sourceSnapshotId, true);
        if (sourceVersionNo <= 0
                || requestKey == null
                || requestKey.version() != 4
                || requestKey.variant() != 2
                || requestId == null) throw AuthException.badRequest("INVALID_REQUEST");
        ObjectNode input =
                json.createObjectNode()
                        .put("formatNo", 1)
                        .put("expectedStoryRev", expectedStoryRev)
                        .put("sourceVersionNo", sourceVersionNo)
                        .put("sourceSnapshotId", sourceSnapshotId);
        return stories.withClone(
                sid,
                actor,
                code,
                scope -> {
                    List<Map<String, Object>> receipts =
                            db.queryForList(
                                    "SELECT id,actor_id,action,request_data::text,result_data::text"
                                        + " FROM story_action WHERE story_id=? AND request_key=?"
                                        + " FOR UPDATE",
                                    scope.storyId(),
                                    requestKey);
                    if (!receipts.isEmpty()) {
                        Map<String, Object> receipt = receipts.getFirst();
                        if (((Number) receipt.get("actor_id")).longValue() != actor.accountId()
                                || !"CLONE".equals(receipt.get("action"))
                                || !same(parse((String) receipt.get("request_data")), input))
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        return result(
                                scope,
                                actor,
                                code,
                                ((Number) receipt.get("id")).longValue(),
                                true,
                                storedResult((String) receipt.get("result_data")));
                    }
                    if (!scope.active()) throw AuthException.conflict("STATE_CONFLICT");
                    if (scope.rev() != expected || scope.rev() == Long.MAX_VALUE)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    JsonNode source =
                            one(
                                    "SELECT to_jsonb(v)::text FROM story_version v WHERE story_id=?"
                                        + " AND version_no=?",
                                    scope.storyId(),
                                    sourceVersionNo);
                    if (source == null
                            || scope.publishedId() == null
                            || source.get("id").longValue() != scope.publishedId()
                            || !"PUBLISHED".equals(source.get("status").textValue()))
                        throw AuthException.conflict("STATE_CONFLICT");
                    if (source.get("current_snapshot_id").isNull()
                            || source.get("current_snapshot_id").longValue() != snapshotId)
                        throw AuthException.conflict("SNAPSHOT_CONFLICT");
                    JsonNode working =
                            one(
                                    "SELECT to_jsonb(v)::text FROM story_version v WHERE story_id=?"
                                        + " AND status IN ('DRAFT','REVIEW','READY')",
                                    scope.storyId());
                    if (working != null) {
                        boolean visible =
                                working.get("active_yn").booleanValue()
                                        || scope.ownerId() == actor.accountId();
                        int number = working.get("version_no").intValue();
                        throw new WorkVersionExists(
                                visible ? new WorkNotice(number, path(code, number)) : null);
                    }
                    int maximum =
                            db.queryForObject(
                                    "SELECT max(version_no) FROM story_version WHERE story_id=?",
                                    Integer.class,
                                    scope.storyId());
                    if (maximum == Integer.MAX_VALUE) throw AuthException.conflict("VERSION_LIMIT");
                    int number = maximum + 1;
                    lockChildren(scope);
                    JsonNode snapshot =
                            one(
                                    "SELECT to_jsonb(s)::text FROM review_snapshot s WHERE id=? AND"
                                        + " version_id=?",
                                    snapshotId,
                                    source.get("id").longValue());
                    if (snapshot == null) throw AuthException.conflict("SNAPSHOT_CONFLICT");
                    JsonNode payload = decode(snapshot, code, sourceVersionNo);
                    ObjectNode before = graph(scope, null, null, List.of());
                    JsonNode now = scalar("SELECT to_jsonb(now())::text");
                    JsonNode earliest = scalar("SELECT to_jsonb(clock_timestamp())::text");
                    long versionId = insertVersion(scope, actor, number, snapshotId, payload);
                    insertChildren(versionId, payload.get("resources"));
                    db.update(
                            "UPDATE story_version SET culprit_code=? WHERE id=?",
                            value(payload.get("sections").get("answer").get("culpritCode")),
                            versionId);
                    db.update(
                            "UPDATE story SET edit_rev=edit_rev+1,updated_at=clock_timestamp()"
                                + " WHERE id=?",
                            scope.storyId());
                    JsonNode original =
                            json.createObjectNode()
                                    .put("versionNo", number)
                                    .put("storyRev", Long.toString(scope.rev() + 1))
                                    .put("editRev", "0")
                                    .put("playRev", before.get("story").get("play_rev").asText())
                                    .put("publishedVersionNo", sourceVersionNo)
                                    .put(
                                            "viewYn",
                                            before.get("story").get("view_yn").booleanValue())
                                    .put("sourceVersionNo", sourceVersionNo)
                                    .put("sourceSnapshotId", sourceSnapshotId)
                                    .set("createdAt", now);
                    String previousPolicy = payload.get("policy").get("policyCode").textValue();
                    String currentPolicy = stories.clonePolicyCode();
                    List<PolicyDifference> differences =
                            previousPolicy.equals(currentPolicy)
                                    ? List.of()
                                    : List.of(
                                            new PolicyDifference(
                                                    "policyCode", previousPolicy, currentPolicy));
                    List<Warning> warnings = stories.cloneWarnings(versionId);
                    ObjectNode stored =
                            json.createObjectNode()
                                    .put("formatNo", 1)
                                    .put("sourcePolicyCode", previousPolicy)
                                    .put("policyCode", currentPolicy);
                    stored.set("original", original);
                    stored.set("policyDifferences", json.valueToTree(differences));
                    stored.set("warnings", json.valueToTree(warnings));
                    long actionId =
                            db.queryForObject(
                                    "INSERT INTO"
                                        + " story_action(story_id,request_key,action,actor_id,request_data,result_data)"
                                        + " VALUES (?,?,'CLONE',?,?::jsonb,?::jsonb) RETURNING id",
                                    Long.class,
                                    scope.storyId(),
                                    requestKey,
                                    actor.accountId(),
                                    encode(input),
                                    encode(stored));
                    ObjectNode storyDetail =
                            detail(actionId, requestId, number, snapshotId, "STORY");
                    ObjectNode contentDetail =
                            detail(actionId, requestId, number, snapshotId, "CONTENT");
                    long storyAudit =
                            audit(scope, actor, null, scope.rev(), scope.rev() + 1, storyDetail);
                    long contentAudit = audit(scope, actor, versionId, null, 0L, contentDetail);
                    requireNewRows(scope, actor, versionId, number, snapshotId, payload, now);
                    requireRow(
                            "story_action",
                            actionId,
                            actionRow(scope, actor, actionId, requestKey, input, stored, now));
                    requireRow(
                            "story_audit",
                            storyAudit,
                            auditRow(
                                    scope,
                                    actor,
                                    storyAudit,
                                    null,
                                    scope.rev(),
                                    scope.rev() + 1,
                                    storyDetail,
                                    now));
                    requireRow(
                            "story_audit",
                            contentAudit,
                            auditRow(
                                    scope,
                                    actor,
                                    contentAudit,
                                    versionId,
                                    null,
                                    0L,
                                    contentDetail,
                                    now));
                    ObjectNode after =
                            graph(scope, versionId, actionId, List.of(storyAudit, contentAudit));
                    ObjectNode expectedStory = ((ObjectNode) before.get("story")).deepCopy();
                    expectedStory.put("edit_rev", scope.rev() + 1);
                    JsonNode updated = after.get("story").get("updated_at");
                    JsonNode latest = scalar("SELECT to_jsonb(clock_timestamp())::text");
                    if (time(updated).isBefore(time(earliest))
                            || time(updated).isAfter(time(latest))) throw unavailable();
                    expectedStory.set("updated_at", updated);
                    before.set("story", expectedStory);
                    if (!same(before, after)) throw unavailable();
                    return result(scope, actor, code, actionId, false, stored);
                });
    }

    /** 실제 사본 형식·사건·버전·고정 수정번호와 정규 전체 payload를 검사하며 품질·실행은 검사하지 않는다. */
    private JsonNode decode(JsonNode snapshot, String code, int number) {
        try {
            if (snapshot.get("format_no").intValue() != 1) throw unavailable();
            JsonNode payload =
                    FrozenSnapshotCodec.decode(SnapshotJson.encode(snapshot.get("payload")))
                            .payload();
            if (!code.equals(payload.get("storyCode").textValue())
                    || payload.get("versionNo").intValue() != number
                    || !snapshot.get("edit_rev")
                            .asText()
                            .equals(payload.get("sourceRev").textValue())) throw unavailable();
            return payload;
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    /** 버전 ID 잠금 이후 실제 사본과 자식만 고정 순서로 잠그며 부모로 역행하지 않는다. */
    private void lockChildren(CloneScope scope) {
        db.queryForList(
                "SELECT id FROM review_snapshot WHERE version_id IN (SELECT id FROM story_version"
                    + " WHERE story_id=?) ORDER BY id FOR UPDATE",
                Long.class,
                scope.storyId());
        for (Child child : CHILDREN) {
            String key = child.columns().split(",")[0];
            db.queryForList(
                    "SELECT version_id FROM "
                            + child.table()
                            + " WHERE version_id IN (SELECT id FROM story_version WHERE story_id=?)"
                            + " ORDER BY version_id,"
                            + key
                            + " FOR UPDATE",
                    Long.class,
                    scope.storyId());
        }
    }

    /** 즉시 FK를 지키기 위해 범인 참조는 인물 생성 후 설정하고 나머지 저장 필드를 그대로 옮긴다. */
    private long insertVersion(
            CloneScope scope, AdminActor actor, int number, long snapshot, JsonNode payload) {
        JsonNode sections = payload.get("sections");
        List<String> columns =
                SECTIONS.keySet().stream().filter(c -> !c.equals("culprit_code")).sorted().toList();
        List<Object> values =
                new ArrayList<>(
                        List.of(
                                scope.storyId(),
                                number,
                                stories.clonePolicyCode(),
                                snapshot,
                                actor.accountId(),
                                actor.accountId()));
        for (String column : columns) values.add(section(sections, SECTIONS.get(column)));
        return db.queryForObject(
                "INSERT INTO"
                    + " story_version(story_id,version_no,policy_code,source_snapshot_id,created_by,updated_by,"
                        + String.join(",", columns)
                        + ") VALUES (?,?,?,?,?,?,"
                        + String.join(",", java.util.Collections.nCopies(columns.size(), "?"))
                        + ") RETURNING id",
                Long.class,
                values.toArray());
    }

    /** 고정 컬럼 대응만 사용하며 새 version_id와 보존 코드가 실제 복합 PK/FK의 새 내부 식별자다. */
    private void insertChildren(long versionId, JsonNode resources) {
        for (Child child : CHILDREN) {
            String[] fields = child.fields().split(",");
            List<String> placeholders = new ArrayList<>();
            for (String field : fields) placeholders.add(jsonField(field) ? "?::jsonb" : "?");
            for (JsonNode row : resources.get(child.resource())) {
                List<Object> values = new ArrayList<>();
                values.add(versionId);
                for (String field : fields)
                    values.add(
                            field.equals("checkedBy")
                                    ? null
                                    : jsonField(field)
                                            ? jsonValue(row.get(field))
                                            : value(row.get(field)));
                db.update(
                        "INSERT INTO "
                                + child.table()
                                + "(version_id,"
                                + child.columns()
                                + ") VALUES (?,"
                                + String.join(",", placeholders)
                                + ")",
                        values.toArray());
            }
        }
    }

    /** 새 버전과 열한 자식의 모든 컬럼·실제 부모 참조·시각을 기대 행과 직접 대조한다. */
    private void requireNewRows(
            CloneScope scope,
            AdminActor actor,
            long versionId,
            int number,
            long snapshot,
            JsonNode payload,
            JsonNode now) {
        ObjectNode expected =
                json.createObjectNode()
                        .put("id", versionId)
                        .put("story_id", scope.storyId())
                        .put("version_no", number)
                        .put("edit_rev", 0)
                        .put("status", "DRAFT")
                        .put("policy_code", stories.clonePolicyCode())
                        .put("source_snapshot_id", snapshot)
                        .put("created_by", actor.accountId())
                        .put("updated_by", actor.accountId())
                        .put("active_yn", true);
        expected.putNull("current_snapshot_id");
        expected.set("created_at", now);
        expected.set("updated_at", now);
        SECTIONS.forEach(
                (column, field) -> {
                    String[] names = field.split("\\.");
                    expected.set(column, payload.get("sections").get(names[0]).get(names[1]));
                });
        requireRow("story_version", versionId, expected);
        for (Child child : CHILDREN) {
            List<JsonNode> actual =
                    rows(
                            "SELECT to_jsonb(t)::text FROM "
                                    + child.table()
                                    + " t WHERE version_id=?",
                            versionId);
            List<JsonNode> expectedRows = new ArrayList<>();
            String[] columns = child.columns().split(",");
            String[] fields = child.fields().split(",");
            for (JsonNode row : payload.get("resources").get(child.resource())) {
                ObjectNode target =
                        json.createObjectNode().put("version_id", versionId).put("active_yn", true);
                target.set("created_at", now);
                target.set("updated_at", now);
                for (int index = 0; index < fields.length; index++) {
                    if (fields[index].equals("checkedBy")) target.putNull(columns[index]);
                    else target.set(columns[index], row.get(fields[index]));
                }
                expectedRows.add(target);
            }
            if (!sorted(actual).equals(sorted(expectedRows))) throw unavailable();
        }
    }

    /** 기존 행 전체를 실제 story/version 부모 소속으로 읽는다. 선택적 미구현 release/evidence 조회는 없다. */
    private ObjectNode graph(
            CloneScope scope, Long newVersion, Long newAction, List<Long> newAudits) {
        ObjectNode result = json.createObjectNode();
        result.set(
                "story", one("SELECT to_jsonb(s)::text FROM story s WHERE id=?", scope.storyId()));
        graphRows(
                result,
                "versions",
                "SELECT to_jsonb(t)::text FROM story_version t WHERE story_id=? AND (?::bigint IS"
                    + " NULL OR id<>?)",
                scope.storyId(),
                newVersion,
                newVersion);
        for (Child child : CHILDREN)
            graphRows(
                    result,
                    child.table(),
                    "SELECT to_jsonb(t)::text FROM "
                            + child.table()
                            + " t WHERE version_id IN (SELECT id FROM story_version WHERE"
                            + " story_id=?) AND (?::bigint IS NULL OR version_id<>?)",
                    scope.storyId(),
                    newVersion,
                    newVersion);
        graphRows(
                result,
                "snapshots",
                "SELECT to_jsonb(t)::text FROM review_snapshot t WHERE version_id IN (SELECT id"
                    + " FROM story_version WHERE story_id=?)",
                scope.storyId());
        for (String table : List.of("review_record", "grade_batch", "grade_job", "execution_issue"))
            graphRows(
                    result,
                    table,
                    "SELECT to_jsonb(t)::text FROM "
                            + table
                            + " t WHERE snapshot_id IN (SELECT s.id FROM review_snapshot s JOIN"
                            + " story_version v ON v.id=s.version_id WHERE v.story_id=?)",
                    scope.storyId());
        for (String table : List.of("grade_attempt", "grade_event"))
            graphRows(
                    result,
                    table,
                    "SELECT to_jsonb(t)::text FROM "
                            + table
                            + " t WHERE job_id IN (SELECT j.id FROM grade_job j JOIN"
                            + " review_snapshot s ON s.id=j.snapshot_id JOIN story_version v ON"
                            + " v.id=s.version_id WHERE v.story_id=?)",
                    scope.storyId());
        graphRows(
                result,
                "access",
                "SELECT to_jsonb(t)::text FROM story_access t WHERE story_id=?",
                scope.storyId());
        graphRows(
                result,
                "transfers",
                "SELECT to_jsonb(t)::text FROM story_transfer t WHERE story_id=?",
                scope.storyId());
        graphRows(
                result,
                "actions",
                "SELECT to_jsonb(t)::text FROM story_action t WHERE story_id=? AND (?::bigint IS"
                    + " NULL OR id<>?)",
                scope.storyId(),
                newAction,
                newAction);
        graphRows(
                result,
                "audits",
                "SELECT to_jsonb(t)::text FROM story_audit t WHERE story_id=? AND"
                    + " id<>ALL(?::bigint[])",
                scope.storyId(),
                "{" + String.join(",", newAudits.stream().map(Object::toString).toList()) + "}");
        return result;
    }

    /** 전체 행 정규 JSON을 정렬해 원본과 새 행의 유무·소속·nullable 값까지 비교한다. */
    private void graphRows(ObjectNode target, String key, String sql, Object... args) {
        target.set(key, json.valueToTree(sorted(rows(sql, args))));
    }

    /** 성공 기록에는 허용 식별자·정책·경고만 저장하며 답안과 응답 전체를 넣지 않는다. */
    private ObjectNode actionRow(
            CloneScope scope,
            AdminActor actor,
            long id,
            UUID key,
            JsonNode input,
            JsonNode stored,
            JsonNode now) {
        ObjectNode row =
                json.createObjectNode()
                        .put("id", id)
                        .put("story_id", scope.storyId())
                        .put("request_key", key.toString())
                        .put("action", "CLONE")
                        .put("actor_id", actor.accountId());
        row.set("request_data", input);
        row.set("result_data", stored);
        row.set("created_at", now);
        return row;
    }

    /** 생성 콘텐츠와 사건 수정번호의 다른 범위를 별도 감사 행으로 원자 연결한다. */
    private long audit(
            CloneScope scope,
            AdminActor actor,
            Long versionId,
            Long before,
            Long after,
            JsonNode detail) {
        if (SnapshotJson.encode(detail).length > 4096) throw unavailable();
        return db.queryForObject(
                "INSERT INTO"
                    + " story_audit(story_id,version_id,actor_id,action,before_rev,after_rev,detail)"
                    + " VALUES (?,?,?,'DRAFT_CLONED',?,?,?::jsonb) RETURNING id",
                Long.class,
                scope.storyId(),
                versionId,
                actor.accountId(),
                before,
                after,
                encode(detail));
    }

    /** 원문 없는 감사 허용 필드만 구성한다. */
    private ObjectNode detail(
            long actionId, UUID requestId, int number, long source, String revisionScope) {
        return json.createObjectNode()
                .put("actionId", Long.toString(actionId))
                .put("requestId", requestId.toString())
                .put("revisionScope", revisionScope)
                .put("versionNo", number)
                .put("sourceSnapshotId", Long.toString(source));
    }

    /** 실제 감사의 대상·수정 범위·시각·연결 ID를 포함한 모든 컬럼을 검증한다. */
    private ObjectNode auditRow(
            CloneScope scope,
            AdminActor actor,
            long id,
            Long version,
            Long before,
            Long after,
            JsonNode detail,
            JsonNode now) {
        ObjectNode row =
                json.createObjectNode()
                        .put("id", id)
                        .put("story_id", scope.storyId())
                        .put("actor_id", actor.accountId())
                        .put("action", "DRAFT_CLONED");
        row.set("version_id", json.valueToTree(version));
        row.putNull("target_admin_id");
        row.set("before_rev", json.valueToTree(before));
        row.set("after_rev", json.valueToTree(after));
        row.set("detail", detail);
        row.set("created_at", now);
        return row;
    }

    /** 재생에서도 현재 최소 상태만 투영하고 비활성 작업본은 현재 소유자에게만 경로를 제공한다. */
    private ActionResult result(
            CloneScope scope,
            AdminActor actor,
            String code,
            long actionId,
            boolean replayed,
            JsonNode stored) {
        JsonNode original = stored.get("original");
        int number = original.get("versionNo").intValue();
        JsonNode story = one("SELECT to_jsonb(s)::text FROM story s WHERE id=?", scope.storyId());
        JsonNode version =
                one(
                        "SELECT to_jsonb(v)::text FROM story_version v WHERE story_id=? AND"
                            + " version_no=?",
                        scope.storyId(),
                        number);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("storyRev", story.get("edit_rev").asText());
        current.put("playRev", story.get("play_rev").asText());
        current.put("viewYn", story.get("view_yn").booleanValue());
        Integer published =
                story.get("published_id").isNull()
                        ? null
                        : db.queryForObject(
                                "SELECT version_no FROM story_version WHERE story_id=? AND id=?",
                                Integer.class,
                                scope.storyId(),
                                story.get("published_id").longValue());
        current.put("publishedVersionNo", published);
        String draftPath = null;
        if (version != null
                && (scope.ownerId() == actor.accountId()
                        || scope.active() && version.get("active_yn").booleanValue())) {
            current.put("versionNo", number);
            current.put("status", version.get("status").textValue());
            current.put("editRev", version.get("edit_rev").asText());
            current.put("activeYn", version.get("active_yn").booleanValue());
            draftPath = path(code, number);
        }
        return new ActionResult(
                Long.toString(actionId),
                "CLONE",
                replayed,
                !replayed,
                original.deepCopy(),
                current,
                draftPath,
                stored.get("sourcePolicyCode").textValue(),
                stored.get("policyCode").textValue(),
                differences(stored.get("policyDifferences")),
                warnings(stored.get("warnings")));
    }

    /** 기존 영수증도 닫힌 안전 필드만 해독하여 손상된 JSON을 원문 응답으로 노출하지 않는다. */
    private JsonNode storedResult(String raw) {
        JsonNode stored = parse(raw);
        exact(
                stored,
                Set.of(
                        "formatNo",
                        "original",
                        "sourcePolicyCode",
                        "policyCode",
                        "policyDifferences",
                        "warnings"));
        if (!stored.get("formatNo").isNumber()
                || stored.get("formatNo").decimalValue().compareTo(java.math.BigDecimal.ONE) != 0)
            throw unavailable();
        JsonNode original = stored.get("original");
        exact(original, ORIGINAL_FIELDS);
        for (String field : List.of("versionNo", "publishedVersionNo", "sourceVersionNo")) {
            if (!original.get(field).isNumber()
                    || original.get(field).decimalValue().signum() <= 0
                    || original.get(field)
                                    .decimalValue()
                                    .compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE))
                            > 0
                    || original.get(field).decimalValue().stripTrailingZeros().scale() > 0)
                throw unavailable();
        }
        for (String field : List.of("storyRev", "editRev", "playRev", "sourceSnapshotId")) {
            if (!original.get(field).isTextual()) throw unavailable();
            try {
                decimal(original.get(field).textValue(), field.equals("sourceSnapshotId"));
            } catch (AuthException invalid) {
                throw unavailable();
            }
        }
        if (!original.get("viewYn").isBoolean() || !original.get("createdAt").isTextual())
            throw unavailable();
        time(original.get("createdAt"));
        for (String field : List.of("sourcePolicyCode", "policyCode"))
            if (!stored.get(field).isTextual()
                    || !stored.get(field).textValue().matches("[A-Z0-9_]{1,40}"))
                throw unavailable();
        differences(stored.get("policyDifferences"));
        warnings(stored.get("warnings"));
        return stored;
    }

    /**
     * 허용된 정책 코드 차이의 닫힌 객체만 읽으며 과거 문자열 코드나 원문을 수용하지 않는다.
     *
     * @param node 비-null 차이 배열이며 각 원소는 policyCode 식별자의 field/before/after다
     * @return 호출자가 변경할 수 없는 비개인 정책 차이 목록
     * @throws AuthException 배열·객체·식별자 계약 위반 시 저장소 오류
     */
    private static List<PolicyDifference> differences(JsonNode node) {
        if (!node.isArray()) throw unavailable();
        List<PolicyDifference> values = new ArrayList<>();
        for (JsonNode item : node) {
            exact(item, Set.of("field", "before", "after"));
            if (!item.get("field").isTextual()
                    || !"policyCode".equals(item.get("field").textValue())) throw unavailable();
            for (String field : List.of("before", "after"))
                if (!item.get(field).isTextual()
                        || !item.get(field).textValue().matches("[A-Z0-9_]{1,40}"))
                    throw unavailable();
            values.add(
                    new PolicyDifference(
                            "policyCode",
                            item.get("before").textValue(),
                            item.get("after").textValue()));
        }
        return List.copyOf(values);
    }

    /** 닫힌 code/field 배열을 불변 경고 목록으로 읽고 원문이나 임의 필드를 거절한다. */
    private static List<Warning> warnings(JsonNode node) {
        if (!node.isArray()) throw unavailable();
        List<Warning> values = new ArrayList<>();
        for (JsonNode item : node) {
            exact(item, Set.of("code", "field"));
            if (!item.get("code").isTextual()
                    || !item.get("code").textValue().matches("[A-Z0-9_]{1,80}")
                    || !item.get("field").isTextual()
                    || !item.get("field").textValue().matches("[A-Za-z0-9_.]{1,100}"))
                throw unavailable();
            values.add(new Warning(item.get("code").textValue(), item.get("field").textValue()));
        }
        return List.copyOf(values);
    }

    /** 비-null 객체가 지정한 필드를 빠짐·추가 없이 소유하는지 확인한다. */
    private static void exact(JsonNode node, Set<String> fields) {
        if (!node.isObject() || node.size() != fields.size()) throw unavailable();
        for (String field : fields) if (!node.has(field)) throw unavailable();
    }

    /** 내부 고정 테이블의 양의 ID 행을 기대 정규 JSON 전체와 대조하며 불일치는 거래를 실패시킨다. */
    private void requireRow(String table, long id, JsonNode expected) {
        JsonNode actual = one("SELECT to_jsonb(t)::text FROM " + table + " t WHERE id=?", id);
        if (actual == null || !same(actual, expected)) throw unavailable();
    }

    /** 내부 고정 SQL을 같은 연결로 조회하며 없으면 null, 다중 행이면 저장소 오류다. */
    private JsonNode one(String sql, Object... args) {
        List<JsonNode> rows = rows(sql, args);
        if (rows.size() > 1) throw unavailable();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 내부 시각 조회 SQL의 비-null JSON 단일 값을 정밀 파서로 해독한다. */
    private JsonNode scalar(String sql) {
        return parse(db.queryForObject(sql, String.class));
    }

    /** 내부 SQL의 각 실제 JSONB 행을 손실 없는 노드로 읽으며 파싱 실패를 저장소 오류로 변환한다. */
    private List<JsonNode> rows(String sql, Object... args) {
        return db.query(sql, (rs, index) -> parse(rs.getString(1)), args);
    }

    /** 비-null 행 목록을 정규 JSON 문자열로 정렬해 순서와 무관한 전체 행 보존을 대조한다. */
    private static List<String> sorted(List<JsonNode> rows) {
        return rows.stream().map(StoryCloneService::encode).sorted().toList();
    }

    /** 검증한 sections에서 내부 고정 section.field 경로의 SQL 바인딩 값을 반환한다. */
    private static Object section(JsonNode sections, String field) {
        String[] names = field.split("\\.");
        return value(sections.get(names[0]).get(names[1]));
    }

    /** 검증한 스칼라만 SQL 값으로 바꾸며 JSON null은 null, 정수는 정확한 int 범위만 허용한다. */
    private static Object value(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.decimalValue().intValueExact();
        throw unavailable();
    }

    /** 내부 필드명이 실제 JSONB 콘텐츠 컬럼에 대응하는지 판단한다. */
    private static boolean jsonField(String field) {
        return Set.of("ruleData", "inputData", "expectData").contains(field);
    }

    /** 비-null JSON 노드를 정규 UTF-8 문자열로 바꾸되 JSON null은 SQL null로 보존한다. */
    private static String jsonValue(JsonNode node) {
        return node.isNull() ? null : encode(node);
    }

    /** 비-null 노드의 정확한 수치·배열·문자열을 공통 정규 UTF-8 JSON으로 직렬화한다. */
    private static String encode(JsonNode node) {
        return new String(SnapshotJson.encode(node), StandardCharsets.UTF_8);
    }

    /** 비-null 저장 JSON을 공통 정밀 파서로 읽고 잘못된 JSON은 원문 없는 저장소 오류로 변환한다. */
    private static JsonNode parse(String raw) {
        try {
            return SnapshotJson.parse(raw.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    /** 두 비-null JSON의 노드 구현 종류 대신 전체 정규 바이트를 비교한다. */
    private static boolean same(JsonNode left, JsonNode right) {
        return Arrays.equals(SnapshotJson.encode(left), SnapshotJson.encode(right));
    }

    /** 저장한 비-null 시각 문자열을 오프셋 시각으로 읽으며 해석 실패는 저장소 오류다. */
    private static OffsetDateTime time(JsonNode node) {
        try {
            return OffsetDateTime.parse(node.textValue());
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    /** 정규 십진 문자열을 정확한 long으로 읽고 positive이면 0도 입력 오류로 거절한다. */
    private static long decimal(String raw, boolean positive) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*"))
            throw AuthException.badRequest("INVALID_REQUEST");
        try {
            long value = Long.parseLong(raw);
            if (positive && value == 0) throw AuthException.badRequest("INVALID_REQUEST");
            return value;
        } catch (NumberFormatException invalid) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    /** 검증한 사건 코드와 양의 버전 번호로 같은 서비스의 편집기 내부 경로만 만든다. */
    private static String path(String code, int number) {
        return "/admin/stories/" + code + "/versions/" + number;
    }

    /** 원고·SQL·저장 내용을 담지 않는 고정 저장소 오류를 만든다. */
    private static AuthException unavailable() {
        return AuthException.unavailable("STORY_UNAVAILABLE");
    }

    private record Child(String resource, String table, String columns, String fields) {}

    public record WorkNotice(int versionNo, String draftPath) {}

    /** 원고 없는 작업본 안내만 기존 오류 경계로 전달하는 타입화된 409다. */
    public static final class WorkVersionExists extends RuntimeException {
        private final WorkNotice notice;

        public WorkVersionExists(WorkNotice notice) {
            super("WORK_VERSION_EXISTS");
            this.notice = notice;
        }

        public WorkNotice notice() {
            return notice;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionResult(
            String actionId,
            String action,
            boolean replayed,
            boolean changed,
            JsonNode original,
            Map<String, Object> current,
            String draftPath,
            String sourcePolicyCode,
            String policyCode,
            List<PolicyDifference> policyDifferences,
            List<Warning> warnings) {}

    /** 비개인 정책 식별자의 변경 전후만 나타내며 원고나 정답은 포함하지 않는다. */
    public record PolicyDifference(String field, String before, String after) {}
}
