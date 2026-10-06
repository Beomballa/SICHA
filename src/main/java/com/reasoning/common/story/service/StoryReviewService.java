package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.FrozenDatasetValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;
import com.reasoning.common.story.model.StoryFrozenSnapshotProducer;
import com.reasoning.common.story.model.StoryReviewEvidence;
import com.reasoning.common.story.model.StoryReviewPreview;
import com.reasoning.common.story.service.StoryService.ReviewScope;
import com.reasoning.common.story.service.StoryService.ReviewSource;
import com.reasoning.common.story.service.StoryService.SnapshotRow;
import com.reasoning.common.util.CommonUtil;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 저장 원본의 구조 검사·원자적 검수 요청·현재 권한의 사본 조회·서버 미리보기 투영을 맡으며 품질 승인은 하지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryReviewService {
    private static final Map<String, Integer> WEIGHTS =
            Map.of("CULPRIT", 25, "METHOD", 20, "TIME", 15, "MOTIVE", 10, "EVIDENCE", 30);
    private static final Comparator<Diagnostic> ORDER =
            Comparator.comparing(Diagnostic::code, CommonUtil::compareCodePoints)
                    .thenComparing(Diagnostic::resource, CommonUtil::compareCodePoints)
                    .thenComparing(Diagnostic::itemKey, CommonUtil::compareCodePoints)
                    .thenComparing(Diagnostic::field, CommonUtil::compareCodePoints);
    private final StoryService stories;
    private final FrozenDatasetValidator datasets = new FrozenDatasetValidator();

    /** 기존 현재 인증·부모 잠금·필수 감사 경계만 주입한다. */
    public StoryReviewService(StoryService stories) {
        this.stories = stories;
    }

    /**
     * 현재 REVIEW 자격을 확인한 뒤 같은 키를 먼저 재생하며 신규 수동 결과만 현재 회차에 추가한다.
     *
     * @param sid 현재 저장 세션 ID
     * @param actor 서버 인증 행위자; 전역·사건 REVIEW가 모두 필요하다
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param snapshotId 양의 정규 bigint 사본 ID
     * @param expectedRev 원래 요청의 정규 콘텐츠 수정번호
     * @param requestKey UUID v4 의도 키
     * @param kind MODEL 또는 APPROVAL; 실행 종류는 연결 전 거절한다
     * @param result PASS/FAIL/INCOMPLETE
     * @param modelId 실제 모델 또는 명시 null
     * @param effort 실제 추론 수준 또는 명시 null
     * @param evidence 공백만이 아닌 최대 20000 코드포인트 근거
     * @param evidenceData 닫힌 format 1/2 근거 객체
     * @param requestId 서버 감사 UUID
     * @return 원고·근거 없는 신규 또는 원래 결과 식별자
     * @throws AuthException 현재 인가·입력·키·상태·수정번호·근거·필수 감사 실패 시
     */
    public RecordResult createReviewRecord(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String snapshotId,
            String expectedRev,
            UUID requestKey,
            String kind,
            String result,
            String modelId,
            String effort,
            String evidence,
            JsonNode evidenceData,
            UUID requestId) {
        requireRequest(requestId);
        long selected = decimal(snapshotId, true);
        if (requestKey == null || requestKey.version() != 4 || requestKey.variant() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
        return stories.withReviewAction(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                true,
                scope -> {
                    var input =
                            StoryReviewEvidence.normalize(
                                    kind,
                                    result,
                                    modelId,
                                    effort,
                                    evidence,
                                    evidenceData,
                                    Instant.now());
                    return stories.createReviewRecord(
                            scope, actor, selected, requestKey, input, requestId);
                });
    }

    /**
     * 현재 제작 자료 접근권과 동일 거래의 CONTENT_READ 후 역사적 원문 근거를 반환한다.
     *
     * @param sid 현재 저장 세션 ID
     * @param actor 현재 제작 자료 조회 행위자
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param snapshotId 같은 버전 소유의 양의 정규 사본 ID
     * @param size null이면 20, 아니면 1~100
     * @param afterId null 또는 양의 배타 record ID
     * @param requestId 필수 감사 UUID
     * @return ID 내림차순 원본 details와 현재 사본 여부
     * @throws AuthException 입력·현재 조회권·다른 사본·감사 실패 시
     */
    public RecordPage getReviewRecordList(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String snapshotId,
            Integer size,
            String afterId,
            UUID requestId) {
        requireRequest(requestId);
        long selected = decimal(snapshotId, true);
        int count = size == null ? 20 : size;
        if (count < 1 || count > 100) throw AuthException.badRequest("INVALID_REQUEST");
        Long cursor = afterId == null ? null : decimal(afterId, true);
        return stories.withReviewRead(
                sid,
                actor,
                storyCode,
                versionNo,
                scope -> {
                    SnapshotRow snapshot = stories.reviewRecordSnapshot(scope, selected);
                    List<RecordItem> rows = stories.reviewRecordRows(selected, count, cursor);
                    boolean more = rows.size() > count;
                    List<RecordItem> items = rows.stream().limit(count).toList();
                    stories.recordReviewRecordsRead(
                            scope, actor, snapshot.sourceRev(), selected, requestId);
                    return new RecordPage(
                            snapshotId,
                            snapshot.current(),
                            items,
                            more,
                            more ? items.getLast().recordId() : null);
                });
    }

    /**
     * 현재 전역·사건 REVIEW로 과거 사본의 실행 지적 메타데이터만 읽고 필수 감사를 확정한다.
     *
     * @param sid 현재 저장 세션 ID
     * @param actor 현재 인증 행위자
     * @param storyCode 활성 사건 코드
     * @param versionNo 활성 버전 번호
     * @param snapshotId 같은 버전의 양의 정규 BIGINT 사본 ID
     * @param state null 또는 OPEN/RESOLVED
     * @param cursor null 또는 양의 정규 BIGINT 배타 경계
     * @param size null이면20, 아니면1~100
     * @param requestId 필수 서버 요청 UUID
     * @return 원문 없는 ID 내림차순 페이지
     * @throws AuthException 입력·현재 자격·부모·감사·보존 검사 실패 시
     */
    public ExecutionIssuePage getExecutionIssueList(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String snapshotId,
            String state,
            String cursor,
            Integer size,
            UUID requestId) {
        requireRequest(requestId);
        long selected = decimal(snapshotId, true);
        Long boundary = cursor == null ? null : decimal(cursor, true);
        int count = size == null ? 20 : size;
        if (count < 1
                || count > 100
                || state != null && !Set.of("OPEN", "RESOLVED").contains(state))
            throw AuthException.badRequest("INVALID_REQUEST");

        return stories.withExecutionIssueRead(
                sid,
                actor,
                storyCode,
                versionNo,
                scope -> {
                    long sourceRev = stories.executionIssueSnapshot(scope, selected);
                    var rows = stories.executionIssueRows(selected, state, boundary, count);
                    boolean more = rows.size() > count;
                    var items =
                            rows.stream()
                                    .limit(count)
                                    .map(StoryService.ExecutionIssueRow::item)
                                    .toList();
                    stories.recordExecutionIssueRead(scope, actor, sourceRev, selected, requestId);
                    return new ExecutionIssuePage(
                            items,
                            more ? Long.toString(rows.get(count - 1).id()) : null,
                            requestId);
                });
    }

    /**
     * REVIEW/READY를 명시적으로 DRAFT로 반환하고 과거 사본·결과·확인 표시를 보존한다.
     *
     * @param sid 현재 저장 세션 ID
     * @param actor WITHDRAW의 owner/EDIT 또는 CHANGES_REQUIRED의 전역·사건 REVIEW
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param expectedRev 최신 수정번호
     * @param expectedSnapshotId 현재 사본의 양의 정규 ID
     * @param action WITHDRAW 또는 CHANGES_REQUIRED
     * @param reasonCode 행동별 닫힌 사유 코드
     * @param verificationRef 비개인 ASCII 확인 참조 8~64자
     * @param requestId 필수 감사 UUID
     * @return 과거 사본과 새 DRAFT 수정번호; 새 회차를 만들지 않는다
     * @throws AuthException 인가·입력·상태·사본·수정번호·감사 실패 시
     */
    public ReviewResult returnToDraft(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            String expectedSnapshotId,
            String action,
            String reasonCode,
            String verificationRef,
            UUID requestId) {
        requireRequest(requestId);
        long selected = decimal(expectedSnapshotId, true);
        boolean withdraw = "WITHDRAW".equals(action);
        if (!(withdraw || "CHANGES_REQUIRED".equals(action))
                || !(withdraw
                        ? "AUTHOR_REVISION".equals(reasonCode)
                        : Set.of("CONTENT_DEFECT", "FAIRNESS_ISSUE", "GRADING_ISSUE")
                                .contains(reasonCode == null ? "" : reasonCode))
                || verificationRef == null
                || !verificationRef.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        return stories.withReviewAction(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                !withdraw,
                scope ->
                        stories.returnReviewDraft(
                                scope,
                                actor,
                                selected,
                                action,
                                reasonCode,
                                verificationRef,
                                requestId));
    }

    /**
     * 현재 제작 자료 접근권으로 payload 없는 사본 이력을 읽고 필수 감사를 확정한다.
     *
     * @param sid null이 아닌 현재 저장 세션 ID
     * @param actor 현재 인증·제작 자료 접근권을 다시 확인할 행위자
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size null이면 20, 아니면 1~100
     * @param afterId null 또는 선행 0 없는 양의 bigint 십진 문자열
     * @param requestId null이 아닌 서버 요청 UUID
     * @return ID 내림차순 목록과 배타적인 다음 커서
     * @throws AuthException 입력·현재 접근권·저장·필수 감사 실패 시
     */
    public SnapshotPage getSnapshotList(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            Integer size,
            String afterId,
            UUID requestId) {
        requireRequest(requestId);
        int count = size == null ? 20 : size;
        if (count < 1 || count > 100) throw AuthException.badRequest("INVALID_REQUEST");
        Long cursor = afterId == null ? null : decimal(afterId, true);
        return stories.withReviewRead(
                sid,
                actor,
                storyCode,
                versionNo,
                scope -> {
                    List<SnapshotRow> rows = stories.snapshotRows(scope, count, cursor);
                    boolean more = rows.size() > count;
                    List<SnapshotSummary> items =
                            rows.stream().limit(count).map(StoryReviewService::summary).toList();
                    stories.recordSnapshotRead(
                            scope, actor, scope.rev(), null, null, null, requestId);
                    return new SnapshotPage(
                            items, more, more ? items.get(items.size() - 1).snapshotId() : null);
                });
    }

    /**
     * 인가한 부모에 속한 저장 JSONB와 실제 형식을 재구성 없이 반환한다.
     *
     * @param sid null이 아닌 현재 저장 세션 ID
     * @param actor 현재 제작 자료 접근권을 가진 행위자이며 원래 작성자일 필요는 없다
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param snapshotId 선행 0 없는 양의 bigint 문자열
     * @param requestId null이 아닌 서버 요청 UUID
     * @return 정확한 summary와 저장 payload
     * @throws AuthException 다른 부모 사본은 404, 입력·자격·감사·저장 실패 시
     */
    public SnapshotDetail getSnapshotDetail(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String snapshotId,
            UUID requestId) {
        requireRequest(requestId);
        long selected = decimal(snapshotId, true);
        return stories.withReviewRead(
                sid,
                actor,
                storyCode,
                versionNo,
                scope -> {
                    SnapshotRow row = stories.snapshot(scope, selected);
                    stories.recordSnapshotRead(
                            scope, actor, row.sourceRev(), selected, null, null, requestId);
                    return new SnapshotDetail(summary(row), row.payload());
                });
    }

    /**
     * 저장 초안 또는 불변 사본만 서버에서 허용 목록으로 투영하며 편집·실행 권한을 부여하지 않는다.
     *
     * @param sid null이 아닌 저장 세션 ID
     * @param actor 현재 제작 자료 접근권을 확인할 행위자
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param source DRAFT 또는 SNAPSHOT
     * @param expectedRev DRAFT만 필수인 음수 없는 정규 bigint 문자열; SNAPSHOT이면 null
     * @param snapshotId SNAPSHOT만 필수인 양의 정규 bigint 문자열; DRAFT이면 null
     * @param mode ROLE 또는 REVEAL
     * @param roleCode ROLE만 필수인 활성 역할 코드; REVEAL이면 null
     * @param requestId null이 아닌 서버 요청 UUID
     * @return 비밀 없는 고정 PreviewResult와 원래 sourceRev
     * @throws AuthException 입력·현재 자격·상태·수정번호·없는 역할·지원하지 않는 사본·감사 실패 시
     */
    public PreviewResult getPreview(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String source,
            String expectedRev,
            String snapshotId,
            String mode,
            String roleCode,
            UUID requestId) {
        requireRequest(requestId);
        if (!("DRAFT".equals(source) || "SNAPSHOT".equals(source))
                || !("ROLE".equals(mode) || "REVEAL".equals(mode))
                || ("ROLE".equals(mode)
                        ? roleCode == null || !roleCode.matches("[A-Z0-9_]{1,32}")
                        : roleCode != null)) throw AuthException.badRequest("INVALID_REQUEST");
        boolean draft = "DRAFT".equals(source);
        if (draft
                ? expectedRev == null || snapshotId != null
                : snapshotId == null || expectedRev != null)
            throw AuthException.badRequest("INVALID_REQUEST");
        long reference = decimal(draft ? expectedRev : snapshotId, !draft);
        return stories.withReviewRead(
                sid,
                actor,
                storyCode,
                versionNo,
                scope -> {
                    JsonNode sections;
                    JsonNode resources;
                    long sourceRev;
                    Long selected = draft ? null : reference;
                    if (draft) {
                        if (!scope.active()
                                || !"DRAFT".equals(scope.status())
                                || scope.snapshotId() != null)
                            throw AuthException.conflict("STATE_CONFLICT");
                        if (scope.rev() != reference) throw AuthException.conflict("EDIT_CONFLICT");
                        ReviewSource raw = stories.previewSource(scope);
                        sections = raw.sections();
                        resources = raw.resources();
                        sourceRev = scope.rev();
                    } else {
                        SnapshotRow row = stories.snapshot(scope, reference);
                        if (row.formatNo() != 1)
                            throw AuthException.unavailable("STORY_UNAVAILABLE");
                        JsonNode saved;
                        try {
                            saved =
                                    FrozenSnapshotCodec.decode(SnapshotJson.encode(row.payload()))
                                            .payload();
                        } catch (IllegalArgumentException invalid) {
                            throw AuthException.unavailable("STORY_UNAVAILABLE");
                        }
                        if (!storyCode.equals(saved.get("storyCode").textValue())
                                || versionNo != saved.get("versionNo").intValue()
                                || !Long.toString(row.sourceRev())
                                        .equals(saved.get("sourceRev").textValue()))
                            throw AuthException.unavailable("STORY_UNAVAILABLE");
                        sections = saved.get("sections");
                        resources = saved.get("resources");
                        sourceRev = row.sourceRev();
                    }
                    JsonNode data;
                    try {
                        data =
                                "ROLE".equals(mode)
                                        ? StoryReviewPreview.role(sections, resources, roleCode)
                                        : StoryReviewPreview.reveal(sections);
                    } catch (IllegalArgumentException invalid) {
                        if (!"ROLE_NOT_FOUND".equals(invalid.getMessage())) throw invalid;
                        throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
                    }
                    stories.recordSnapshotRead(
                            scope, actor, sourceRev, selected, mode, roleCode, requestId);
                    return new PreviewResult(
                            source,
                            Long.toString(sourceRev),
                            id(selected),
                            mode,
                            roleCode,
                            true,
                            data,
                            requestId);
                });
    }

    private static SnapshotSummary summary(SnapshotRow row) {
        return new SnapshotSummary(
                Long.toString(row.snapshotId()),
                Long.toString(row.sourceRev()),
                row.formatNo(),
                row.createdAt(),
                row.createdBy(),
                row.current());
    }

    /** 커서·수정번호를 DB 조회 전에 정규 bigint 범위로 제한한다. */
    private static long decimal(String raw, boolean positive) {
        if (raw == null || !raw.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*"))
            throw AuthException.badRequest("INVALID_REQUEST");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    /**
     * 활성 DRAFT의 저장된 전체 원본을 검사하고 원문 없는 진단과 필수 CONTENT_READ 감사를 확정한다.
     *
     * @param sid null이 아닌 현재 세션 ID
     * @param actor 현재 소유자 또는 활성 EDIT 행위자
     * @param storyCode 저장된 불변 사건 코드
     * @param versionNo 양의 버전 번호
     * @param expectedRev null이 아닌 현재 정규 십진 수정번호
     * @param requestId null이 아닌 서버 요청 UUID
     * @return 불충족도 200으로 반환할 전체 수와 각각 최대 200개의 정렬 진단
     * @throws AuthException 입력·현재 인가·상태·수정번호·필수 감사 실패 시
     */
    public PrecheckResult precheck(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            UUID requestId) {
        requireRequest(requestId);
        return stories.withReviewVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    stories.requireReviewDraft(scope);
                    Inspection inspection =
                            inspect(storyCode, versionNo, scope, stories.reviewSource(scope));
                    stories.recordReviewRead(scope, actor, requestId);
                    return inspection.result(scope.rev(), requestId);
                });
    }

    /**
     * 현재 EDIT 인가 후 기존 요청을 먼저 재생하고 신규 키만 완성도·전체 집합을 검사하여 원자적으로 고정한다.
     *
     * @param sid null이 아닌 현재 세션 ID
     * @param actor 서버에서 확인한 현재 편집 행위자
     * @param storyCode 저장 사건 코드
     * @param versionNo 양의 버전 번호
     * @param expectedRev 원래 요청의 전환 전 수정번호이며 null이 아니다
     * @param requestKey 브라우저의 RFC UUID v4이며 null이 아니다
     * @param requestId null이 아닌 이번 서버 요청 UUID
     * @return 신규 사본 또는 과거 사본과 현재 상태를 구분하는 영수증
     * @throws AuthException 키 충돌·불완성·현재 인가·상태·수정번호·크기·감사 실패 시
     */
    public ReviewResult requestReview(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            UUID requestKey,
            UUID requestId) {
        requireRequest(requestId);
        if (requestKey == null || requestKey.version() != 4 || requestKey.variant() != 2)
            throw AuthException.badRequest("INVALID_REQUEST");
        return stories.withReviewVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    var replay = stories.reviewReplay(scope, requestKey);
                    if (replay != null) {
                        if (replay.createdBy() != actor.accountId()
                                || replay.sourceRev() != scope.expected())
                            throw AuthException.conflict("REQUEST_KEY_CONFLICT");
                        return new ReviewResult(
                                Long.toString(replay.snapshotId()),
                                Long.toString(replay.sourceRev()),
                                Long.toString(scope.rev()),
                                scope.status(),
                                id(scope.snapshotId()),
                                true,
                                requestId);
                    }
                    stories.requireReviewDraft(scope);
                    Inspection inspection =
                            inspect(storyCode, versionNo, scope, stories.reviewSource(scope));
                    if (!inspection.errors.isEmpty()) {
                        if (inspection.errors.stream()
                                .anyMatch(d -> "SNAPSHOT_TOO_LARGE".equals(d.code())))
                            throw AuthException.unprocessable("SNAPSHOT_TOO_LARGE");
                        throw AuthException.unprocessable("REVIEW_NOT_READY");
                    }
                    long snapshot =
                            stories.recordReviewRequest(
                                    scope,
                                    actor,
                                    requestKey,
                                    inspection.frozen,
                                    inspection.warnings.size(),
                                    requestId);
                    return new ReviewResult(
                            Long.toString(snapshot),
                            Long.toString(scope.rev()),
                            Long.toString(scope.rev() + 1),
                            "REVIEW",
                            Long.toString(snapshot),
                            false,
                            requestId);
                });
    }

    /**
     * 미작성 값은 먼저 진단하며 완전한 정책·codec을 부르기 위해 값을 만들지 않는다.
     *
     * @param code 저장된 null이 아닌 사건 코드
     * @param number 양의 버전 번호
     * @param scope 현재 잠금에서 읽은 정책·수정번호 문맥
     * @param source 같은 거래에서 읽은 전체 활성 원본이며 null이 아니다
     * @return 전체 진단과 전체 집합 검증을 통과한 경우에만 생성한 사본
     * @throws IllegalArgumentException 알려진 고정 형식 오류가 아닌 프로그램 오류는 그대로 전달한다
     */
    private Inspection inspect(String code, int number, ReviewScope scope, ReviewSource source) {
        Inspection check = new Inspection();
        JsonNode sections = source.sections();
        JsonNode basic = sections.get("basic");
        JsonNode resources = source.resources();
        textFields(check, basic, "basic", "", "title", "intro", "setting", "timelineOrigin");
        textFields(
                check,
                sections.get("answer"),
                "answer",
                "",
                "culpritCode",
                "methodAnswer",
                "timeAnswer",
                "motiveAnswer");
        textFields(check, sections.get("reveal"), "reveal", "", "revealText");
        for (String field : List.of("difficulty", "estMin", "estMax", "limitSec")) {
            JsonNode value = basic.get(field);
            if (value == null
                    || !value.isIntegralNumber()
                    || !value.canConvertToInt()
                    || value.intValue() <= 0) check.error("MISSING_CONTENT", "basic", "", field);
        }
        if (!"RULE_20260924".equals(scope.policyCode()))
            check.error("INVALID_INPUT", "policy", "", "policyCode");
        if (basic.get("difficulty").isIntegralNumber()) {
            int difficulty = basic.get("difficulty").intValue();
            if (difficulty < 1 || difficulty > 5)
                check.error("INVALID_INPUT", "basic", "", "difficulty");
            int low = difficulty <= 2 ? 5 : 15;
            int high = difficulty <= 2 ? 15 : 40;
            for (String field : List.of("estMin", "estMax")) {
                JsonNode value = basic.get(field);
                if (value.isIntegralNumber() && (value.intValue() < low || value.intValue() > high))
                    check.warnings.add(new Diagnostic("POLICY_TIME_RANGE", "basic", "", field));
            }
        }
        for (String resource :
                List.of(
                        "persons",
                        "roles",
                        "pairs",
                        "clues",
                        "hints",
                        "events",
                        "facts",
                        "rubrics",
                        "rubricClues",
                        "gradeSamples"))
            if (resources.get(resource).isEmpty()) check.error("MISSING_CONTENT", resource, "", "");
        rowsText(check, resources, "persons", "name", "publicText");
        rowsText(check, resources, "roles", "name", "brief");
        rowsText(check, resources, "clues", "title", "body");
        rowsText(check, resources, "hints", "body");
        rowsText(check, resources, "events", "actualText", "apparentText");
        rowsText(check, resources, "facts", "statement", "truth", "basis");
        rowsText(check, resources, "rubrics", "acceptedText", "partialText", "rejectText");
        for (JsonNode row : resources.get("events")) {
            for (String field : List.of("startMin", "endMin"))
                if (row.get(field).isNull())
                    check.error("MISSING_CONTENT", "events", row.get("code").textValue(), field);
        }
        for (JsonNode row : resources.get("rubricClues"))
            textFields(
                    check,
                    row,
                    "rubricClues",
                    row.get("rubricCode").textValue() + "~" + row.get("clueCode").textValue(),
                    "linkText");
        for (JsonNode row : resources.get("gradeSamples")) {
            String key = row.get("code").textValue();
            textFields(check, row, "gradeSamples", key, "reason");
            for (String field : List.of("inputData", "expectData", "checkedBy"))
                if (row.get(field).isNull())
                    check.error("MISSING_CONTENT", "gradeSamples", key, field);
        }
        for (String key : source.unresolvedCheckers())
            check.error("REVIEW_NOT_READY", "gradeSamples", key, "checkedBy");
        Set<Integer> hints = new HashSet<>();
        resources.get("hints").forEach(row -> hints.add(row.get("level").intValue()));
        for (int level = 1; level <= 3; level++)
            if (!hints.contains(level))
                check.error("MISSING_CONTENT", "hints", "H" + level, "level");
        rubricStructure(check, resources);
        pairAccess(check, resources);
        // 전체 구조 검사는 등록된 기존 validator를 그대로 사용한다. 의미 추론이나 부분 선택은 없다.
        if (check.errors.isEmpty()) {
            try {
                FrozenSnapshot frozen =
                        StoryFrozenSnapshotProducer.freeze(
                                code, number, scope.rev(), scope.policyCode(), sections, resources);
                check.frozen = datasets.validate(frozen).frozenSnapshot();
            } catch (IllegalArgumentException invalid) {
                String fixed = invalid.getMessage();
                if (fixed == null
                        || !Set.of(
                                        "INVALID_FROZEN_SNAPSHOT",
                                        "INVALID_FROZEN_DATASET",
                                        "SNAPSHOT_TOO_LARGE",
                                        "INVALID_SNAPSHOT_JSON")
                                .contains(fixed)) throw invalid;
                check.error(
                        "SNAPSHOT_TOO_LARGE".equals(fixed) ? fixed : "REVIEW_NOT_READY",
                        "resources",
                        "",
                        "");
            }
        }
        return check;
    }

    /** 배점·필수 분류·등록 규칙의 미작성만 진단하고 단계·참조는 전체 집합 검사기에 맡긴다. */
    private static void rubricStructure(Inspection check, JsonNode resources) {
        Map<String, Integer> totals = new HashMap<>();
        Set<String> required = new HashSet<>();
        int culprit = 0;
        for (JsonNode row : resources.get("rubrics")) {
            String key = row.get("code").textValue();
            String category = row.get("category").textValue();
            if ("CULPRIT".equals(category)) culprit++;
            JsonNode max = row.get("maxScore");
            if (!max.isIntegralNumber() || max.intValue() <= 0)
                check.error("MISSING_CONTENT", "rubrics", key, "maxScore");
            else totals.merge(category, max.intValue(), Integer::sum);
            if (row.get("ruleData").isNull())
                check.error("MISSING_CONTENT", "rubrics", key, "ruleData");
            if (row.get("requiredYn").booleanValue()) required.add(category);
        }
        if (culprit != 1 || !totals.equals(WEIGHTS))
            check.error("SCORE_TOTAL", "rubrics", "", "maxScore");
        for (String category : List.of("CULPRIT", "METHOD", "EVIDENCE"))
            if (!required.contains(category))
                check.error("MISSING_CONTENT", "rubrics", category, "requiredYn");
    }

    /** 각 후보의 두 역할 자료와 조합 전체의 필수 근거 접근을 확인하며 역할별 전부 공개를 강제하지 않는다. */
    private static void pairAccess(Inspection check, JsonNode resources) {
        Set<String> roles = new HashSet<>();
        resources.get("roles").forEach(row -> roles.add(row.get("code").textValue()));
        Map<String, Set<String>> assignments = new HashMap<>();
        resources
                .get("clueRoles")
                .forEach(
                        row ->
                                assignments
                                        .computeIfAbsent(
                                                row.get("roleCode").textValue(),
                                                unused -> new HashSet<>())
                                        .add(row.get("clueCode").textValue()));
        Set<String> common = new HashSet<>();
        resources
                .get("clues")
                .forEach(
                        row -> {
                            if ("COMMON".equals(row.get("scope").textValue()))
                                common.add(row.get("code").textValue());
                        });
        Set<String> requiredRubrics = new HashSet<>();
        resources
                .get("rubrics")
                .forEach(
                        row -> {
                            if (row.get("requiredYn").booleanValue())
                                requiredRubrics.add(row.get("code").textValue());
                        });
        Set<String> necessary = new HashSet<>();
        resources
                .get("rubricClues")
                .forEach(
                        row -> {
                            if (requiredRubrics.contains(row.get("rubricCode").textValue()))
                                necessary.add(row.get("clueCode").textValue());
                        });
        for (JsonNode pair : resources.get("pairs")) {
            String a = pair.get("roleA").textValue();
            String b = pair.get("roleB").textValue();
            String key = a + "~" + b;
            Set<String> accessible = new HashSet<>(common);
            for (String role : List.of(a, b)) {
                if (!roles.contains(role)) check.error("REVIEW_NOT_READY", "pairs", key, "roles");
                Set<String> material = assignments.getOrDefault(role, Set.of());
                if (material.isEmpty() && common.isEmpty())
                    check.error("MISSING_CONTENT", "pairs", key, "clueRoles");
                accessible.addAll(material);
            }
            if (!accessible.containsAll(necessary))
                check.error("REFERENCE_UNASSIGNED", "pairs", key, "rubricClues");
        }
    }

    private static void rowsText(
            Inspection check, JsonNode resources, String resource, String... fields) {
        for (JsonNode row : resources.get(resource))
            textFields(check, row, resource, row.get("code").textValue(), fields);
    }

    private static void textFields(
            Inspection check, JsonNode row, String resource, String key, String... fields) {
        for (String field : fields) {
            JsonNode value = row.get(field);
            if (value == null || !value.isTextual() || value.textValue().isBlank())
                check.error("MISSING_CONTENT", resource, key, field);
        }
    }

    private static void requireRequest(UUID requestId) {
        if (requestId == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static String id(Long value) {
        return value == null ? null : value.toString();
    }

    private static final class Inspection {
        private final List<Diagnostic> errors = new ArrayList<>();
        private final List<Diagnostic> warnings = new ArrayList<>();
        private FrozenSnapshot frozen;

        void error(String code, String resource, String key, String field) {
            errors.add(new Diagnostic(code, resource, key, field));
        }

        PrecheckResult result(long rev, UUID requestId) {
            errors.sort(ORDER);
            warnings.sort(ORDER);
            return new PrecheckResult(
                    Long.toString(rev),
                    errors.isEmpty(),
                    List.copyOf(errors.subList(0, Math.min(200, errors.size()))),
                    List.copyOf(warnings.subList(0, Math.min(200, warnings.size()))),
                    errors.size(),
                    warnings.size(),
                    errors.size() > 200 || warnings.size() > 200,
                    requestId);
        }
    }

    /** 공개 UUID와 실제 현재 포인터만 담는 payload 없는 이력 요약이다. */
    public record SnapshotSummary(
            String snapshotId,
            String sourceRev,
            int formatNo,
            Instant createdAt,
            UUID createdBy,
            boolean current) {}

    /** nextAfterId는 추가 행이 있을 때만 마지막 반환 ID이며 그 외에는 null이다. */
    public record SnapshotPage(List<SnapshotSummary> items, boolean hasNext, String nextAfterId) {}

    /** 민감 조회 감사가 확정된 실제 저장 사본이다. */
    public record SnapshotDetail(SnapshotSummary snapshot, JsonNode payload) {}

    /** DRAFT의 snapshotId와 REVEAL의 roleCode는 null이며 data는 모드별 허용 목록뿐이다. */
    public record PreviewResult(
            String source,
            String sourceRev,
            String snapshotId,
            String mode,
            String roleCode,
            boolean previewOnly,
            JsonNode data,
            UUID requestId) {}

    /** 신규·재생 결과는 식별자만 반환하며 현재 회차 여부를 따로 표시한다. */
    public record RecordResult(
            String recordId,
            String snapshotId,
            boolean current,
            boolean replayed,
            UUID requestId) {}

    /** 과거 검수자 UUID와 원래 근거 details만 제공하며 저장 wrapper는 노출하지 않는다. */
    public record RecordItem(
            String recordId,
            String kind,
            String result,
            UUID reviewerAccountKey,
            String modelId,
            String effort,
            String evidence,
            JsonNode evidenceData,
            boolean selfReviewYn,
            Instant createdAt) {}

    /** 최대 100개 배타 ID 내림차순 페이지다. */
    public record RecordPage(
            String snapshotId,
            boolean current,
            List<RecordItem> items,
            boolean hasNext,
            String nextAfterId) {}

    public record Diagnostic(String code, String resource, String itemKey, String field) {}

    /** BATCH 출처와 실제 과거 관리자 UUID만 제공하며 review 관계는 명시 null이다. */
    public record ExecutionIssue(
            UUID issueKey,
            String snapshotId,
            String runtimeConfigId,
            UUID sourceBatchKey,
            String sourceReviewId,
            String kind,
            String severity,
            String state,
            Instant createdAt,
            Instant resolvedAt,
            UUID resolvedBy,
            UUID targetBatchKey,
            String targetReviewId,
            IssueResolution resolution) {}

    /** 저장 해소 객체의 닫힌 비개인 투영이다. */
    public record IssueResolution(String reasonCode, String verificationRef) {}

    /** 마지막 반환 내부 ID를 배타 커서로 사용하는 불변 페이지다. */
    public record ExecutionIssuePage(
            List<ExecutionIssue> items, String nextCursor, UUID requestId) {
        public ExecutionIssuePage {
            items = List.copyOf(items);
        }
    }

    public record PrecheckResult(
            String editRev,
            boolean eligible,
            List<Diagnostic> errors,
            List<Diagnostic> warnings,
            int errorCount,
            int warningCount,
            boolean truncated,
            UUID requestId) {}

    public record ReviewResult(
            String snapshotId,
            String sourceRev,
            String editRev,
            String status,
            String currentSnapshotId,
            boolean replayed,
            UUID requestId) {}
}
