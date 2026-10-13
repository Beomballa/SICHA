package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.common.grading.repository.GradeRuntimeRepository.RuntimeRow;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 현재 인증과 실제 실행 출처를 같은 잠금 거래에서 검사하여 공개 결정을 봉인한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryReleaseService {
    private final StoryService stories;
    private final StoryReleaseEvidenceService evidence;
    private final GradeRuntimeRepository runtimes;
    private final Map<String, InstalledRuntimeManifestVerifier> installations;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public StoryReleaseService(
            StoryService stories,
            StoryReleaseEvidenceService evidence,
            GradeRuntimeRepository runtimes,
            @Qualifier("gradeInstallations")
                    Map<String, InstalledRuntimeManifestVerifier> installations,
            JdbcTemplate db,
            ObjectMapper json) {
        this.stories = stories;
        this.evidence = evidence;
        this.runtimes = runtimes;
        this.installations = installations;
        this.db = db;
        this.json = json;
    }

    /** 현재 제작 자료 접근을 확인하고 실행 설정·전체 검수 기록의 결정적 상태를 읽는다. */
    public ReleaseCheck check(
            String sid, AdminActor actor, String code, int version, String runtimeCode) {
        requireRuntimeCode(runtimeCode);
        return stories.withReleaseAction(
                sid,
                actor,
                code,
                version,
                "READ",
                () -> lockRuntime(runtimeCode),
                (scope, runtime) -> evaluate(code, version, scope, runtime));
    }

    /** 같은 요청키의 원래 의도를 우선 대조한 뒤 실제 전체 근거가 있는 REVIEW만 READY로 바꾼다. */
    public ActionResult ready(
            String sid,
            AdminActor actor,
            String code,
            int version,
            String expectedRev,
            String snapshot,
            String expectedHash,
            String runtimeCode,
            UUID key) {
        long rev = decimal(expectedRev, false);
        long snapshotId = decimal(snapshot, true);
        requireRuntimeCode(runtimeCode);
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")) throw invalid();
        requireKey(key);
        ObjectNode intent =
                json.createObjectNode()
                        .put("expectedRev", expectedRev)
                        .put("expectedSnapshotId", snapshot)
                        .put("expectedEvidenceHash", expectedHash)
                        .put("runtimeConfigId", runtimeCode)
                        .put("requestKey", key.toString());
        return stories.withReleaseAction(
                sid,
                actor,
                code,
                version,
                "REVIEW",
                () -> lockRuntime(runtimeCode),
                (scope, runtime) -> {
                    ActionResult prior = replay(actor, scope, key, "READY", intent, version);
                    if (prior != null) return prior;
                    if (!"REVIEW".equals(scope.status())
                            || scope.snapshotId() == null
                            || scope.editRev() != rev
                            || scope.snapshotId() != snapshotId)
                        throw AuthException.conflict("STATE_CONFLICT");
                    ReleaseCheck checked = evaluate(code, version, scope, runtime);
                    if (!expectedHash.equals(checked.evidenceHash()))
                        throw AuthException.conflict("EVIDENCE_CHANGED");
                    if (!checked.canReady())
                        throw AuthException.unprocessable("EVIDENCE_NOT_CONNECTED");
                    Map<String, Long> ids = selected(scope.snapshotId());
                    long receipt = ids.get("PLAYTEST");
                    long sourceRev =
                            db.queryForObject(
                                    "SELECT edit_rev FROM review_snapshot WHERE id=? AND"
                                        + " version_id=?",
                                    Long.class,
                                    snapshotId,
                                    scope.versionId());
                    ObjectNode bundle =
                            json.createObjectNode()
                                    .put("formatNo", 1)
                                    .put("gateVersion", "STORY-RELEASE-01")
                                    .put("snapshotId", snapshot)
                                    .put("sourceRev", sourceRev)
                                    .put("evidenceHash", expectedHash);
                    ObjectNode records = bundle.putObject("recordIds");
                    ids.forEach((kind, id) -> records.put(kind, id));
                    ArrayNode resolutions = bundle.putArray("resolutionRecordIds");
                    db.queryForList(
                                    "SELECT id FROM review_record WHERE snapshot_id=? AND"
                                        + " result='PASS' AND"
                                        + " jsonb_array_length(COALESCE(evidence_data->'details'->'resolves','[]'::jsonb))>0"
                                        + " ORDER BY id",
                                    Long.class,
                                    snapshotId)
                            .forEach(resolutions::add);
                    bundle.set("runtime", runtimePins(runtime, scope.policyCode()));
                    bundle.set("checks", json.valueToTree(checked.checks()));
                    if (SnapshotJson.encode(bundle).length > 262144)
                        throw AuthException.unprocessable("INVALID_INPUT");
                    long release =
                            db.queryForObject(
                                    "INSERT INTO"
                                        + " story_release(version_id,snapshot_id,source_rev,runtime_id,runtime_epoch,config_hash,evidence_hash,bundle_data,created_by)"
                                        + " VALUES (?,?,?,?,?,?,?,?::jsonb,?) RETURNING id",
                                    Long.class,
                                    scope.versionId(),
                                    snapshotId,
                                    sourceRev,
                                    runtime.id(),
                                    runtime.epoch(),
                                    runtime.configHash(),
                                    expectedHash,
                                    bundle.toString(),
                                    actor.accountId());
                    db.update(
                            "UPDATE story_version SET"
                                + " status='READY',edit_rev=edit_rev+1,updated_at=clock_timestamp()"
                                + " WHERE id=?",
                            scope.versionId());
                    ObjectNode original =
                            json.createObjectNode()
                                    .put("versionNo", version)
                                    .put("snapshotId", snapshot)
                                    .put("releaseId", Long.toString(release))
                                    .put("editRev", Long.toString(rev + 1));
                    return save(
                            actor,
                            scope,
                            key,
                            "READY",
                            intent,
                            original,
                            true,
                            "READY_GRANTED",
                            version);
                });
    }

    /** 재인증된 PUBLISH가 고정 사본·공개본 CAS와 설치된 동일 runtime을 재확인한다. */
    public ActionResult publish(
            String sid,
            AdminActor actor,
            String code,
            int version,
            String expectedStoryRev,
            String expectedRev,
            String expectedSnapshotId,
            String releaseId,
            Integer publishedNo,
            boolean allowVisibleSwitch,
            UUID key) {
        long storyRev = decimal(expectedStoryRev, false), rev = decimal(expectedRev, false);
        long snapshot = decimal(expectedSnapshotId, true), release = decimal(releaseId, true);
        requireKey(key);
        ObjectNode intent =
                json.createObjectNode()
                        .put("expectedStoryRev", expectedStoryRev)
                        .put("expectedRev", expectedRev)
                        .put("expectedSnapshotId", expectedSnapshotId)
                        .put("releaseId", releaseId)
                        .put("allowVisibleSwitch", allowVisibleSwitch)
                        .put("requestKey", key.toString());
        if (publishedNo == null) intent.putNull("expectedPublishedVersionNo");
        else intent.put("expectedPublishedVersionNo", publishedNo);
        return stories.withReleaseAction(
                sid,
                actor,
                code,
                version,
                "PUBLISH",
                () -> lockReleaseRuntime(release),
                (scope, runtime) -> {
                    ActionResult prior = replay(actor, scope, key, "PUBLISH", intent, version);
                    if (prior != null) return prior;
                    if (scope.storyRev() != storyRev
                            || scope.editRev() != rev
                            || !Objects.equals(scope.snapshotId(), snapshot)
                            || !Objects.equals(scope.releaseId(), release)
                            || !Objects.equals(scope.publishedVersionNo(), publishedNo)
                            || !"READY".equals(scope.status())
                            || (scope.viewYn() && !allowVisibleSwitch))
                        throw AuthException.conflict("STATE_CONFLICT");
                    requireRelease(scope, runtime, release);
                    db.update(
                            "UPDATE story_version SET"
                                + " status='PUBLISHED',edit_rev=edit_rev+1,updated_at=clock_timestamp()"
                                + " WHERE id=?",
                            scope.versionId());
                    db.update(
                            "UPDATE story SET"
                                + " published_id=?,edit_rev=edit_rev+1,play_rev=play_rev+1,updated_at=clock_timestamp()"
                                + " WHERE id=?",
                            scope.versionId(),
                            scope.storyId());
                    ObjectNode original =
                            json.createObjectNode()
                                    .put("versionNo", version)
                                    .put("snapshotId", expectedSnapshotId)
                                    .put("releaseId", releaseId)
                                    .put("storyRev", Long.toString(storyRev + 1))
                                    .put("editRev", Long.toString(rev + 1))
                                    .put("playRev", Long.toString(scope.playRev() + 1))
                                    .put("publishedVersionNo", version)
                                    .put("viewYn", scope.viewYn());
                    return save(
                            actor,
                            scope,
                            key,
                            "PUBLISH",
                            intent,
                            original,
                            true,
                            "PUBLISHED",
                            version);
                });
    }

    /** 숨김은 고장 난 실행 설정에서도 가능하고 최초 결함 차단만 불변으로 추가한다. */
    public ActionResult visibility(
            String sid,
            AdminActor actor,
            String code,
            String expectedStoryRev,
            int publishedNo,
            String releaseId,
            boolean view,
            String reason,
            String reference,
            UUID key) {
        long rev = decimal(expectedStoryRev, false), release = decimal(releaseId, true);
        if (publishedNo <= 0
                || reference == null
                || !reference.matches("[A-Za-z0-9_-]{8,64}")
                || !Set.of("FIRST_EXPOSE", "RESUME", "MAINTENANCE", "DEFECT").contains(reason)
                || (view != Set.of("FIRST_EXPOSE", "RESUME").contains(reason))) throw invalid();
        requireKey(key);
        ObjectNode intent =
                json.createObjectNode()
                        .put("expectedStoryRev", expectedStoryRev)
                        .put("expectedPublishedVersionNo", publishedNo)
                        .put("releaseId", releaseId)
                        .put("viewYn", view)
                        .put("reasonCode", reason)
                        .put("verificationRef", reference)
                        .put("requestKey", key.toString());
        return stories.withReleaseAction(
                sid,
                actor,
                code,
                publishedNo,
                "PUBLISH",
                () -> lockReleaseRuntime(release),
                (scope, runtime) -> {
                    ActionResult prior =
                            replay(actor, scope, key, view ? "SHOW" : "HIDE", intent, publishedNo);
                    if (prior != null) return prior;
                    if (scope.storyRev() != rev
                            || !Objects.equals(scope.publishedVersionNo(), publishedNo)
                            || !Objects.equals(scope.releaseId(), release)
                            || !Objects.equals(scope.publishedId(), scope.versionId())
                            || !"PUBLISHED".equals(scope.status()))
                        throw AuthException.conflict("STATE_CONFLICT");
                    boolean blocked =
                            Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT blocked_yn FROM story_release WHERE id=?",
                                            Boolean.class,
                                            release));
                    if (view) {
                        if (blocked) throw AuthException.conflict("RELEASE_BLOCKED");
                        requireRelease(scope, runtime, release);
                    }
                    boolean newBlock = !view && reason.equals("DEFECT") && !blocked;
                    boolean changed = scope.viewYn() != view || newBlock;
                    if (newBlock)
                        db.update(
                                "UPDATE story_release SET"
                                    + " blocked_yn=true,blocked_reason='DEFECT',blocked_ref=?,blocked_by=?,blocked_at=clock_timestamp()"
                                    + " WHERE id=?",
                                reference,
                                actor.accountId(),
                                release);
                    if (changed)
                        db.update(
                                "UPDATE story SET"
                                    + " view_yn=?,edit_rev=edit_rev+1,play_rev=play_rev+1,updated_at=clock_timestamp()"
                                    + " WHERE id=?",
                                view,
                                scope.storyId());
                    ObjectNode original =
                            json.createObjectNode()
                                    .put("versionNo", publishedNo)
                                    .put("releaseId", releaseId)
                                    .put("storyRev", Long.toString(rev + (changed ? 1 : 0)))
                                    .put(
                                            "playRev",
                                            Long.toString(scope.playRev() + (changed ? 1 : 0)))
                                    .put("publishedVersionNo", publishedNo)
                                    .put("viewYn", view);
                    return save(
                            actor,
                            scope,
                            key,
                            view ? "SHOW" : "HIDE",
                            intent,
                            original,
                            changed,
                            newBlock ? "DEFECT_BLOCKED" : "VISIBILITY_CHANGED",
                            publishedNo);
                });
    }

    /** 서버가 소유한 REVIEW 사본만 수집하고 수집기 내부의 두 출처 검증 결과를 그대로 반환한다. */
    public ActionResult collect(
            String sid,
            AdminActor actor,
            String code,
            int version,
            String snapshot,
            String runtimeCode,
            UUID key) {
        long selected = decimal(snapshot, true);
        requireRuntimeCode(runtimeCode);
        requireKey(key);
        ObjectNode intent =
                json.createObjectNode()
                        .put("versionNo", version)
                        .put("snapshotId", snapshot)
                        .put("runtimeConfigId", runtimeCode)
                        .put("requestKey", key.toString());
        return stories.withReleaseAction(
                sid,
                actor,
                code,
                version,
                "REVIEW",
                () -> lockRuntime(runtimeCode),
                (scope, runtime) -> {
                    ActionResult prior = replay(actor, scope, key, "RECOLLECT", intent, version);
                    if (prior != null) return prior;
                    if (!installed(runtime, scope.policyCode()))
                        throw AuthException.conflict("RUNTIME_UNAVAILABLE");
                    var result =
                            evidence.collectLocked(
                                    scope.versionId(),
                                    scope.snapshotId(),
                                    scope.status(),
                                    actor,
                                    selected,
                                    runtime.id(),
                                    runtime.epoch(),
                                    runtime.configHash());
                    if (result.state() != StoryReleaseEvidenceService.State.VERIFIED
                            || result.receiptId() == null)
                        throw AuthException.unprocessable("EVIDENCE_NOT_CONNECTED");
                    ObjectNode original =
                            json.createObjectNode()
                                    .put("versionNo", version)
                                    .put("snapshotId", snapshot)
                                    .put("receiptId", result.receiptId().toString());
                    return save(
                            actor,
                            scope,
                            key,
                            "RECOLLECT",
                            intent,
                            original,
                            true,
                            "RELEASE_COLLECTED",
                            version);
                });
    }

    /** 사람의 검토 내용은 별도 서비스가 출처 및 부모 REVIEW 잠금 아래 기록한다. */
    public ActionResult review(
            String sid,
            AdminActor actor,
            String code,
            int version,
            String sourceKind,
            long sourceId,
            String verdict,
            int criticalOpenCount,
            String detail,
            String verificationRef,
            Long supersedesId,
            String findingRef,
            UUID key) {
        requireKey(key);
        if (detail == null) throw invalid();
        ObjectNode intent =
                json.createObjectNode()
                        .put("versionNo", version)
                        .put("sourceKind", sourceKind)
                        .put("sourceId", sourceId)
                        .put("verdict", verdict)
                        .put("criticalOpenCount", criticalOpenCount)
                        .put("detailHash", SnapshotJson.hash(json.valueToTree(detail)))
                        .put("verificationRef", verificationRef)
                        .put("requestKey", key.toString());
        if (supersedesId == null) intent.putNull("supersedesId");
        else intent.put("supersedesId", supersedesId);
        if (findingRef == null) intent.putNull("findingRef");
        else intent.put("findingRef", findingRef);
        return stories.withReleaseAction(
                sid,
                actor,
                code,
                version,
                "REVIEW",
                () -> null,
                (scope, ignored) -> {
                    ActionResult prior = replay(actor, scope, key, "HUMAN_REVIEW", intent, version);
                    if (prior != null) return prior;
                    long id =
                            evidence.recordReviewLocked(
                                    scope.versionId(),
                                    scope.snapshotId(),
                                    scope.status(),
                                    actor,
                                    sourceKind,
                                    sourceId,
                                    verdict,
                                    criticalOpenCount,
                                    detail,
                                    verificationRef,
                                    supersedesId,
                                    findingRef);
                    ObjectNode original =
                            json.createObjectNode()
                                    .put("versionNo", version)
                                    .put("recordId", Long.toString(id));
                    if (scope.snapshotId() != null)
                        original.put("snapshotId", scope.snapshotId().toString());
                    return save(
                            actor,
                            scope,
                            key,
                            "HUMAN_REVIEW",
                            intent,
                            original,
                            true,
                            "RELEASE_REVIEWED",
                            version);
                });
    }

    private ReleaseCheck evaluate(
            String code, int version, StoryService.ReleaseScope scope, RuntimeRow runtime) {
        List<Check> checks = new ArrayList<>();
        String hash = hash(scope, runtime.code());
        boolean runtimeOk = installed(runtime, scope.policyCode());
        checks.add(
                new Check(
                        "RUNTIME",
                        runtimeOk ? "PASS" : "UNAVAILABLE",
                        List.of(),
                        runtimeOk ? "INSTALLED" : "RUNTIME_UNAVAILABLE"));
        Map<String, Long> selected =
                scope.snapshotId() == null ? Map.of() : selected(scope.snapshotId());
        for (String kind : List.of("STRUCTURE", "MODEL", "GRADE", "PLAYTEST", "APPROVAL")) {
            Long id = selected.get(kind);
            boolean good = id != null;
            String state = good ? "PASS" : "MISSING";
            if (kind.equals("PLAYTEST") && id == null) state = "UNAVAILABLE";
            if (good && kind.equals("GRADE") && !grade(scope.snapshotId(), id, runtime))
                state = "UNAVAILABLE";
            if (good
                    && kind.equals("PLAYTEST")
                    && evidence.evaluateRelease(
                                            scope.versionId(),
                                            scope.snapshotId(),
                                            runtime.id(),
                                            runtime.epoch(),
                                            runtime.configHash(),
                                            id)
                                    .state()
                            != StoryReleaseEvidenceService.State.VERIFIED) state = "UNAVAILABLE";
            checks.add(
                    new Check(
                            kind,
                            state,
                            id == null ? List.of() : List.of(Long.toString(id)),
                            state));
        }
        boolean open =
                scope.snapshotId() != null
                        && Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM execution_issue WHERE"
                                            + " snapshot_id=? AND state='OPEN' AND"
                                            + " severity='CRITICAL')",
                                        Boolean.class,
                                        scope.snapshotId()));
        boolean unresolved =
                scope.snapshotId() != null
                        && unresolved(scope.snapshotId(), selected.get("APPROVAL"));
        boolean order = approvalAfterSources(selected);
        checks.add(
                new Check(
                        "ISSUES",
                        open || unresolved || !order ? "FAIL" : "PASS",
                        List.of(),
                        open || unresolved || !order ? "UNRESOLVED" : "CLEAR"));
        boolean pass = checks.stream().allMatch(c -> c.state().equals("PASS"));
        boolean ready = scope.canReview() && "REVIEW".equals(scope.status()) && pass;
        boolean currentRelease =
                scope.releaseId() != null
                        && Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT NOT blocked_yn AND source_rev=(SELECT edit_rev FROM"
                                            + " review_snapshot WHERE id=?) AND snapshot_id=? AND"
                                            + " runtime_id=? AND runtime_epoch=? AND config_hash=?"
                                            + " FROM story_release WHERE id=?",
                                        Boolean.class,
                                        scope.snapshotId(),
                                        scope.snapshotId(),
                                        runtime.id(),
                                        runtime.epoch(),
                                        runtime.configHash(),
                                        scope.releaseId()));
        boolean blocked =
                scope.releaseId() != null
                        && Boolean.TRUE.equals(
                                db.queryForObject(
                                        "SELECT blocked_yn FROM story_release WHERE id=?",
                                        Boolean.class,
                                        scope.releaseId()));
        boolean publish =
                scope.canPublish() && "READY".equals(scope.status()) && currentRelease && runtimeOk;
        boolean show =
                scope.canPublish()
                        && "PUBLISHED".equals(scope.status())
                        && Objects.equals(scope.publishedId(), scope.versionId())
                        && currentRelease
                        && runtimeOk;
        return new ReleaseCheck(
                code,
                version,
                Long.toString(scope.editRev()),
                scope.snapshotId() == null ? null : scope.snapshotId().toString(),
                Long.toString(scope.storyRev()),
                Long.toString(scope.playRev()),
                scope.status(),
                scope.publishedVersionNo(),
                scope.viewYn(),
                scope.releaseId() == null ? null : scope.releaseId().toString(),
                blocked,
                runtime.code(),
                hash,
                checks,
                ready,
                publish,
                show);
    }

    private Map<String, Long> selected(long snapshot) {
        Map<String, Long> selected = new LinkedHashMap<>();
        db.query(
                "SELECT kind,id,result FROM review_record WHERE snapshot_id=? ORDER BY id",
                rs -> {
                    if (rs.getString("result").equals("PASS"))
                        selected.put(rs.getString("kind"), rs.getLong("id"));
                    else selected.remove(rs.getString("kind"));
                },
                snapshot);
        Long receipt =
                db.query(
                        "SELECT id FROM release_evidence WHERE snapshot_id=? ORDER BY id DESC LIMIT"
                            + " 1",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        snapshot);
        if (receipt != null) selected.put("PLAYTEST", receipt);
        return selected;
    }

    private boolean unresolved(long snapshot, Long approval) {
        if (approval == null) return true;
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM review_record bad WHERE bad.snapshot_id=? AND bad.result IN ('FAIL','INCOMPLETE')
                          AND NOT EXISTS(SELECT 1 FROM review_record fix WHERE fix.snapshot_id=bad.snapshot_id AND fix.kind=bad.kind
                            AND fix.result='PASS' AND fix.id>bad.id AND fix.id<?
                            AND fix.evidence_data->'details'->'resolves' @> jsonb_build_array(jsonb_build_object('recordId',bad.id::text))))
                        OR EXISTS(SELECT 1 FROM review_record r WHERE r.snapshot_id=? AND r.kind IN ('MODEL','GRADE','STRUCTURE')
                          AND r.result='PASS' AND r.id>=?)
                        """,
                        Boolean.class,
                        snapshot,
                        approval,
                        snapshot,
                        approval));
    }

    private boolean grade(long snapshot, long id, RuntimeRow runtime) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM review_record r JOIN evidence_set e ON e.id=r.evidence_set_id
                          JOIN evidence_item i ON i.set_id=e.id WHERE r.id=? AND r.snapshot_id=? AND r.kind='GRADE'
                          AND r.result='PASS' AND e.kind='GRADE' AND e.snapshot_id=? AND e.runtime_id=?
                          AND e.runtime_epoch=? AND e.available_yn AND e.invalidated_at IS NULL)
                        """,
                        Boolean.class,
                        id,
                        snapshot,
                        snapshot,
                        runtime.id(),
                        runtime.epoch()));
    }

    private boolean approvalAfterSources(Map<String, Long> ids) {
        Long approval = ids.get("APPROVAL"), receipt = ids.get("PLAYTEST");
        if (approval == null || receipt == null) return false;
        for (String kind : List.of("STRUCTURE", "MODEL", "GRADE"))
            if (ids.get(kind) == null || approval <= ids.get(kind)) return false;
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM review_record a JOIN release_evidence e ON e.id=?
                          WHERE a.id=? AND a.created_at>=e.created_at)
                        """,
                        Boolean.class,
                        receipt,
                        approval));
    }

    private String hash(StoryService.ReleaseScope scope, String runtimeCode) {
        ObjectNode input = json.createObjectNode();
        if (scope.snapshotId() == null) input.putNull("snapshotId");
        else input.put("snapshotId", scope.snapshotId());
        input.put("runtimeConfigId", runtimeCode);
        ArrayNode records = input.putArray("records");
        if (scope.snapshotId() != null)
            db.query(
                    "SELECT id,kind,result,evidence_data FROM review_record WHERE snapshot_id=?"
                        + " ORDER BY id",
                    rs -> {
                        ObjectNode record =
                                records.addObject()
                                        .put("id", rs.getLong(1))
                                        .put("kind", rs.getString(2))
                                        .put("result", rs.getString(3));
                        record.put("evidenceHash", SnapshotJson.hash(parse(rs.getString(4))));
                    },
                    scope.snapshotId());
        return SnapshotJson.hash(input);
    }

    private void requireRelease(StoryService.ReleaseScope scope, RuntimeRow runtime, long release) {
        if (!installed(runtime, scope.policyCode())
                || scope.snapshotId() == null
                || !Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(SELECT 1 FROM story_release WHERE id=? AND version_id=? AND snapshot_id=?
                                  AND source_rev=(SELECT edit_rev FROM review_snapshot WHERE id=?)
                                  AND runtime_id=? AND runtime_epoch=? AND config_hash=? AND NOT blocked_yn)
                                """,
                                Boolean.class,
                                release,
                                scope.versionId(),
                                scope.snapshotId(),
                                scope.snapshotId(),
                                runtime.id(),
                                runtime.epoch(),
                                runtime.configHash())))
            throw AuthException.conflict("RELEASE_INVALIDATED");
        Long receipt =
                db.queryForObject(
                        "SELECT (bundle_data->'recordIds'->>'PLAYTEST')::bigint FROM story_release"
                            + " WHERE id=?",
                        Long.class,
                        release);
        if (receipt == null
                || evidence.evaluateRelease(
                                        scope.versionId(),
                                        scope.snapshotId(),
                                        runtime.id(),
                                        runtime.epoch(),
                                        runtime.configHash(),
                                        receipt)
                                .state()
                        != StoryReleaseEvidenceService.State.VERIFIED)
            throw AuthException.unprocessable("EVIDENCE_NOT_CONNECTED");
        Long grade =
                db.queryForObject(
                        "SELECT (bundle_data->'recordIds'->>'GRADE')::bigint FROM story_release"
                            + " WHERE id=?",
                        Long.class,
                        release);
        if (grade == null || !grade(scope.snapshotId(), grade, runtime))
            throw AuthException.unprocessable("EVIDENCE_NOT_CONNECTED");
    }

    private RuntimeRow lockRuntime(String code) {
        Long id =
                db.query(
                        "SELECT id FROM grade_runtime WHERE code=?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        code);
        if (id == null) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        return runtimes.lockRuntime(id)
                .orElseThrow(() -> AuthException.conflict("RUNTIME_UNAVAILABLE"));
    }

    private RuntimeRow lockReleaseRuntime(long release) {
        Long id =
                db.query(
                        "SELECT runtime_id FROM story_release WHERE id=?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        release);
        return id == null ? null : runtimes.lockRuntime(id).orElse(null);
    }

    private boolean installed(RuntimeRow runtime, String policy) {
        if (runtime == null) return false;
        var verifier = installations.get(runtime.code());
        try {
            return "AVAILABLE".equals(runtime.state())
                    && verifier != null
                    && verifier.verify(runtime).profile().policyCode().equals(policy);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private JsonNode runtimePins(RuntimeRow runtime, String policy) {
        var installed = installations.get(runtime.code());
        if (!installed(runtime, policy)) throw AuthException.conflict("RUNTIME_UNAVAILABLE");
        JsonNode manifest = installed.registrationManifest();
        ObjectNode pins =
                json.createObjectNode()
                        .put("configId", runtime.code())
                        .put("policyCode", policy)
                        .put("policyHash", manifest.path("policyHash").asText());
        for (String key :
                List.of(
                        "engineId",
                        "engineVersion",
                        "modelId",
                        "modelVersion",
                        "pinMode",
                        "promptHash",
                        "optionsHash",
                        "reportContractVersion")) {
            JsonNode value = manifest.get(key);
            if (value == null || value.isNull()) pins.putNull(key);
            else pins.put(key, value.asText());
        }
        return pins;
    }

    private ActionResult replay(
            AdminActor actor,
            StoryService.ReleaseScope scope,
            UUID key,
            String action,
            JsonNode intent,
            int version) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "SELECT id,actor_id,story_id,action,request_data::text,result_data::text"
                            + " FROM story_action WHERE request_key=? FOR UPDATE",
                        key);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        Map<String, Object> row = rows.getFirst();
        if (((Number) row.get("actor_id")).longValue() != actor.accountId()
                || ((Number) row.get("story_id")).longValue() != scope.storyId()
                || !action.equals(row.get("action"))
                || !SnapshotJson.hash(intent)
                        .equals(SnapshotJson.hash(parse((String) row.get("request_data")))))
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        JsonNode stored = parse((String) row.get("result_data"));
        if (stored.path("versionNo").asInt() != version)
            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
        return new ActionResult(
                row.get("id").toString(),
                action,
                true,
                stored.path("changed").booleanValue(),
                stored.path("original"),
                current(scope, version));
    }

    private ActionResult save(
            AdminActor actor,
            StoryService.ReleaseScope scope,
            UUID key,
            String action,
            JsonNode intent,
            ObjectNode original,
            boolean changed,
            String audit,
            int version) {
        ObjectNode receipt =
                json.createObjectNode().put("versionNo", version).put("changed", changed);
        receipt.set("original", original);
        Long id =
                db.queryForObject(
                        "INSERT INTO"
                            + " story_action(story_id,request_key,action,actor_id,request_data,result_data)"
                            + " VALUES (?,?,?,?,?::jsonb,?::jsonb) RETURNING id",
                        Long.class,
                        scope.storyId(),
                        key,
                        action,
                        actor.accountId(),
                        intent.toString(),
                        receipt.toString());
        if (changed)
            db.update(
                    "INSERT INTO"
                        + " story_audit(story_id,version_id,actor_id,action,before_rev,after_rev,detail)"
                        + " VALUES (?,?,?,?,?,?,?::jsonb)",
                    scope.storyId(),
                    scope.versionId(),
                    actor.accountId(),
                    audit,
                    action.equals("READY") ? scope.editRev() : scope.storyRev(),
                    action.equals("READY")
                            ? scope.editRev() + 1
                            : Set.of("RECOLLECT", "HUMAN_REVIEW").contains(action)
                                    ? scope.storyRev()
                                    : scope.storyRev() + 1,
                    json.createObjectNode().put("actionId", id.toString()).toString());
        return new ActionResult(
                id.toString(), action, false, changed, original, current(scope, version));
    }

    private JsonNode current(StoryService.ReleaseScope scope, int version) {
        var row =
                db.queryForMap(
                        "SELECT s.edit_rev"
                            + " story_rev,s.play_rev,s.view_yn,s.published_id,v.edit_rev,v.status,v.current_snapshot_id"
                            + " FROM story s JOIN story_version v ON v.id=? AND v.story_id=s.id"
                            + " WHERE s.id=?",
                        scope.versionId(),
                        scope.storyId());
        ObjectNode value =
                json.createObjectNode()
                        .put("versionNo", version)
                        .put("status", (String) row.get("status"))
                        .put("storyRev", row.get("story_rev").toString())
                        .put("playRev", row.get("play_rev").toString())
                        .put("editRev", row.get("edit_rev").toString())
                        .put("viewYn", (boolean) row.get("view_yn"));
        if (row.get("published_id") == null) value.putNull("publishedVersionNo");
        else
            value.put(
                    "publishedVersionNo",
                    db.queryForObject(
                            "SELECT version_no FROM story_version WHERE id=?",
                            Integer.class,
                            row.get("published_id")));
        if (row.get("current_snapshot_id") != null)
            value.put("snapshotId", row.get("current_snapshot_id").toString());
        if (row.get("current_snapshot_id") != null) {
            var seal =
                    db.query(
                            "SELECT id,blocked_yn FROM story_release WHERE snapshot_id=?",
                            rs ->
                                    rs.next()
                                            ? Map.of(
                                                    "id",
                                                    rs.getLong(1),
                                                    "blocked",
                                                    rs.getBoolean(2))
                                            : null,
                            row.get("current_snapshot_id"));
            if (seal != null) {
                value.put("releaseId", seal.get("id").toString());
                value.put("blocked", (boolean) seal.get("blocked"));
            }
        }
        return value;
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception failure) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    private static void requireRuntimeCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,80}")) throw invalid();
    }

    private static long decimal(String raw, boolean positive) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*")) throw invalid();
        try {
            long value = Long.parseLong(raw);
            if (positive && value == 0) throw invalid();
            return value;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static void requireKey(UUID key) {
        if (key == null || key.version() != 4 || key.variant() != 2) throw invalid();
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    public record Check(String code, String state, List<String> recordIds, String summary) {}

    public record ReleaseCheck(
            String storyCode,
            int versionNo,
            String editRev,
            String snapshotId,
            String storyRev,
            String playRev,
            String status,
            Integer publishedVersionNo,
            boolean viewYn,
            String releaseId,
            boolean blocked,
            String runtimeConfigId,
            String evidenceHash,
            List<Check> checks,
            boolean canReady,
            boolean canPublish,
            boolean canShow) {}

    public record ActionResult(
            String actionId,
            String action,
            boolean replayed,
            boolean changed,
            JsonNode original,
            JsonNode current) {}
}
