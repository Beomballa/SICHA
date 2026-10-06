package com.reasoning.admin.story.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.story.service.StoryBatchService;
import com.reasoning.common.story.service.StoryGradeEvidenceService;
import com.reasoning.common.story.service.StoryIssueResolutionService;
import com.reasoning.common.story.service.StoryReviewService;
import com.reasoning.common.story.service.StorySampleCheckService;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/** 저장 원본의 검수 요청·사본 조회·서버 투영과 전체 예시의 명시적인 사람 확인 HTTP 경계다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/stories")
public final class StoryReviewController {
    private final StorySampleCheckService samples;
    private final StoryReviewService reviews;
    private final StoryHttpSupport http;
    private final StoryBatchService batches;
    private final StoryIssueResolutionService resolutions;
    private final StoryGradeEvidenceService evidence;

    /** 구조 검수·사람 확인 업무와 기존 엄격 JSON·현재 세션 경계를 주입한다. */
    public StoryReviewController(
            StorySampleCheckService samples,
            StoryReviewService reviews,
            StoryHttpSupport http,
            StoryBatchService batches,
            StoryIssueResolutionService resolutions,
            StoryGradeEvidenceService evidence) {
        this.samples = samples;
        this.reviews = reviews;
        this.http = http;
        this.batches = batches;
        this.resolutions = resolutions;
        this.evidence = evidence;
    }

    /** 정확한 여덟 필드의 32KiB 본문에서 실제 GRADE 근거만 확정하며 신규·재생 모두 201이다. */
    @PostMapping("/{storyCode}/versions/{versionNo}/evidence")
    public ResponseEntity<?> createGradeEvidence(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of());
        JsonNode body =
                http.body(
                        request,
                        32768,
                        Set.of(
                                "expectedRev",
                                "snapshotId",
                                "runtimeConfigId",
                                "kind",
                                "executionRefs",
                                "reviewIds",
                                "resolves",
                                "requestKey"));
        JsonNode refs = body.get("executionRefs");
        JsonNode reviews = body.get("reviewIds");
        if (refs == null || !refs.isArray() || reviews == null || !reviews.isArray())
            throw AuthException.badRequest("INVALID_REQUEST");
        java.util.List<java.util.UUID> keys = new java.util.ArrayList<>();
        for (JsonNode ref : refs) {
            if (!ref.isTextual()) throw AuthException.badRequest("INVALID_REQUEST");
            java.util.UUID key = StoryHttpSupport.uuid(ref.textValue(), true);
            if (!key.toString().equals(ref.textValue()))
                throw AuthException.badRequest("INVALID_REQUEST");
            keys.add(key);
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode id : reviews) {
            if (!id.isTextual()) throw AuthException.badRequest("INVALID_REQUEST");
            ids.add(id.textValue());
        }
        java.util.UUID requestKey = StoryHttpSupport.uuid(body, "requestKey", true);
        if (!requestKey.toString().equals(StoryHttpSupport.text(body, "requestKey")))
            throw AuthException.badRequest("INVALID_REQUEST");
        return StoryHttpSupport.created(
                evidence.createGradeEvidence(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.text(body, "snapshotId"),
                        StoryHttpSupport.text(body, "runtimeConfigId"),
                        StoryHttpSupport.text(body, "kind"),
                        keys,
                        ids,
                        body.get("resolves"),
                        requestKey,
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 전체 고정 사본의 세 반복을 접수하며 실제 모델 실행이나 집계 승인을 수행하지 않는다.
     *
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param request 정확한 다섯 필드의 최대 8KiB JSON과 현재 세션 요청
     * @return 신규 201 또는 인가된 동일 키 재생 200; 모두 no-store
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/regressions")
    public ResponseEntity<?> createBatch(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of());
        JsonNode body =
                http.body(
                        request,
                        8192,
                        Set.of(
                                "expectedRev",
                                "snapshotId",
                                "runtimeConfigId",
                                "purpose",
                                "requestKey"));
        var result =
                batches.createBatch(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.text(body, "snapshotId"),
                        StoryHttpSupport.text(body, "runtimeConfigId"),
                        StoryHttpSupport.text(body, "purpose"),
                        StoryHttpSupport.uuid(body, "requestKey", true),
                        StoryHttpSupport.requestId(request));
        return result.replayed() ? StoryHttpSupport.ok(result) : StoryHttpSupport.created(result);
    }

    /**
     * 현재 REVIEW로 같은 부모의 원문 없는 집합 상세만 읽고 필수 콘텐츠 감사를 확정한다.
     *
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param batchKey UUID v4 집합 키
     * @param request 쿼리를 허용하지 않는 저장 세션 요청
     * @return 고정 BatchDetail과 no-store
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/regressions/{batchKey}")
    public ResponseEntity<?> batch(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String batchKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of());
        java.util.UUID key;
        try {
            key = java.util.UUID.fromString(batchKey);
            if (!key.toString().equals(batchKey) || key.version() != 4 || key.variant() != 2)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
        return StoryHttpSupport.ok(
                batches.getBatchDetail(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        key,
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 현재 REVIEW로 사본 소유의 실행 지적 이력만 읽는다.
     *
     * @param storyCode 활성 사건 코드
     * @param versionNo 양의 활성 버전 번호
     * @param request snapshotId/state/cursor/size만 허용하는 현재 세션 GET
     * @return 정확한 items/nextCursor/requestId와 no-store
     * @throws AuthException 추가·반복·잘못된 쿼리·현재 자격·감사 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/execution-issues")
    public ResponseEntity<?> executionIssues(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of("snapshotId", "state", "cursor", "size"));
        String raw = request.getParameter("size");
        Integer size = null;
        if (raw != null) {
            if (!raw.matches("[1-9][0-9]{0,2}")) throw AuthException.badRequest("INVALID_REQUEST");
            size = Integer.valueOf(raw);
        }

        return StoryHttpSupport.ok(
                reviews.getExecutionIssueList(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        request.getParameter("snapshotId"),
                        request.getParameter("state"),
                        request.getParameter("cursor"),
                        size,
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 현재 REVIEW 사본의 실제 후속 BATCH 검증으로 실행 지적 한 건만 해소한다.
     *
     * @param storyCode 활성 사건 코드
     * @param versionNo 양의 버전 번호
     * @param issueKey 정규 UUID v4 지적 키
     * @param request 쿼리 없는 현재 세션·출처·CSRF와 정확한 여섯 필드의 8KiB JSON
     * @return 신규·재생 모두 200과 no-store인 원고 없는 해소 영수증
     * @throws AuthException 입력·현재 권한·후속 증거·단회 해소·필수 저장 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/execution-issues/{issueKey}/resolve")
    public ResponseEntity<?> resolveIssue(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String issueKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of());
        JsonNode body =
                http.body(
                        request,
                        8192,
                        Set.of(
                                "expectedRev",
                                "requestKey",
                                "reasonCode",
                                "verificationRef",
                                "targetBatchKey",
                                "targetReviewId"));
        String batch = nullableText(body, "targetBatchKey");
        java.util.UUID issue = StoryHttpSupport.uuid(issueKey, true);
        if (!issue.toString().equals(issueKey)) throw AuthException.badRequest("INVALID_REQUEST");
        return StoryHttpSupport.ok(
                resolutions.resolveIssue(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        issue,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.uuid(body, "requestKey", true),
                        StoryHttpSupport.text(body, "reasonCode"),
                        StoryHttpSupport.text(body, "verificationRef"),
                        batch == null ? null : StoryHttpSupport.uuid(batch, true),
                        nullableText(body, "targetReviewId"),
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 수동 MODEL/APPROVAL 근거를 128KiB 엄격 본문으로 등록한다. 외부 실행 검증 API가 아니다.
     *
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param snapshotId 같은 버전의 사본 ID
     * @param request 현재 세션·동일 출처·CSRF와 정확한 여덟 필드를 가진 요청
     * @return 신규 201 또는 동일 키 재생 200; 모두 no-store
     * @throws AuthException 잘못된 형식·현재 자격·충돌·필수 감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/review-snapshots/{snapshotId}/records")
    public ResponseEntity<?> createRecord(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String snapshotId,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of());
        JsonNode body = recordBody(request);
        var result =
                reviews.createReviewRecord(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        snapshotId,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.uuid(body, "requestKey", true),
                        StoryHttpSupport.text(body, "kind"),
                        StoryHttpSupport.text(body, "result"),
                        nullableText(body, "modelId"),
                        nullableText(body, "effort"),
                        StoryHttpSupport.text(body, "evidence"),
                        body.get("evidenceData"),
                        StoryHttpSupport.requestId(request));
        return result.replayed() ? StoryHttpSupport.ok(result) : StoryHttpSupport.created(result);
    }

    /**
     * 현재 제작 자료 접근권으로 원문 근거의 배타 keyset 페이지를 읽는다.
     *
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param snapshotId 같은 버전의 현재 또는 역사적 사본 ID
     * @param request size 1~100/기본20과 afterId만 허용한 GET; 변경용 CSRF는 필요 없다
     * @return 정확한 근거 항목과 no-store
     * @throws AuthException 추가·중복 쿼리·형식·조회권·감사 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/review-snapshots/{snapshotId}/records")
    public ResponseEntity<?> records(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String snapshotId,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of("size", "afterId"));
        String raw = request.getParameter("size");
        Integer size = null;
        if (raw != null) {
            if (!raw.matches("[1-9][0-9]{0,2}")) throw AuthException.badRequest("INVALID_REQUEST");
            size = Integer.valueOf(raw);
        }
        return StoryHttpSupport.ok(
                reviews.getReviewRecordList(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        snapshotId,
                        size,
                        request.getParameter("afterId"),
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 최신 REVIEW/READY 회차를 명시적인 철회 또는 검수 반려로 DRAFT에 반환한다.
     *
     * @param storyCode 사건 코드
     * @param versionNo 양의 버전 번호
     * @param request 현재 세션·동일 출처·CSRF와 정확한 다섯 필드를 가진 8KiB 요청
     * @return 새 수정번호와 해제된 현재 포인터; no-store
     * @throws AuthException 입력·권한·중복 반환·사본·수정번호·감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/return-to-draft")
    public ResponseEntity<?> returnToDraft(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of());
        JsonNode body =
                http.body(
                        request,
                        8192,
                        Set.of(
                                "expectedRev",
                                "expectedSnapshotId",
                                "action",
                                "reasonCode",
                                "verificationRef"));
        return StoryHttpSupport.ok(
                reviews.returnToDraft(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.text(body, "expectedSnapshotId"),
                        StoryHttpSupport.text(body, "action"),
                        StoryHttpSupport.text(body, "reasonCode"),
                        StoryHttpSupport.text(body, "verificationRef"),
                        StoryHttpSupport.requestId(request)));
    }

    /** 누락은 엄격 본문 검사에서 거절하며 null과 문자열만 명시적으로 허용한다. */
    private static String nullableText(JsonNode body, String field) {
        return body.get(field).isNull() ? null : StoryHttpSupport.text(body, field);
    }

    /** 기존 HTTP 엄격 검사를 재사용하되 같은 원래 바이트의 수는 공유 정확 JSON parser로 읽는다. */
    private JsonNode recordBody(HttpServletRequest request) {
        byte[] bytes;
        try {
            bytes = request.getInputStream().readNBytes(131073);
        } catch (IOException unavailable) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
        if (bytes.length > 131072)
            throw new AuthException(413, "PAYLOAD_TOO_LARGE", "PAYLOAD_TOO_LARGE");
        HttpServletRequest cached =
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                        return new ServletInputStream() {
                            @Override
                            public int read() {
                                return input.read();
                            }

                            @Override
                            public boolean isFinished() {
                                return input.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                throw new IllegalStateException("동기 JSON 요청만 지원합니다.");
                            }
                        };
                    }
                };
        http.body(
                cached,
                131072,
                Set.of(
                        "expectedRev",
                        "requestKey",
                        "kind",
                        "result",
                        "modelId",
                        "effort",
                        "evidence",
                        "evidenceData"));
        try {
            return SnapshotJson.parse(bytes);
        } catch (IllegalArgumentException invalid) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    /**
     * 현재 제작 자료 접근권으로 사본 요약만 읽는다. GET에 변경용 CSRF 경계를 추가하지 않는다.
     *
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param request size 1~100(기본 20)와 양의 정규 afterId만 허용하는 조회 요청
     * @return payload 없는 내림차순 이력과 no-store
     * @throws AuthException 중복·추가 쿼리·형식·현재 접근권·감사 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/review-snapshots")
    public ResponseEntity<?> snapshots(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of("size", "afterId"));
        String raw = request.getParameter("size");
        Integer size = null;
        if (raw != null) {
            if (!raw.matches("[1-9][0-9]{0,2}")) throw AuthException.badRequest("INVALID_REQUEST");
            size = Integer.valueOf(raw);
        }
        return StoryHttpSupport.ok(
                reviews.getSnapshotList(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        size,
                        request.getParameter("afterId"),
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 현재 부모 접근권과 필수 감사로 같은 버전의 실제 저장 사본을 반환한다.
     *
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param snapshotId 양의 정규 bigint 십진 문자열
     * @param request 쿼리 키를 허용하지 않는 저장 세션 조회 요청
     * @return 정확한 summary/payload와 no-store
     * @throws AuthException 입력·현재 자격·다른 부모 ID·감사 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/review-snapshots/{snapshotId}")
    public ResponseEntity<?> snapshot(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String snapshotId,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of());
        return StoryHttpSupport.ok(
                reviews.getSnapshotDetail(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        snapshotId,
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 배타적인 저장 원본·모드 입력만 받아 서버 허용 목록으로 투영한다.
     *
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param request source/mode 및 원본·모드별 필수 키만 가진 저장 세션 GET
     * @return 고정 PreviewResult와 no-store이며 전체 원본은 반환하지 않는다
     * @throws AuthException 추가·중복·배타 입력·현재 인가·감사·저장 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/preview")
    public ResponseEntity<?> preview(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        query(request, Set.of("source", "expectedRev", "snapshotId", "mode", "roleCode"));
        return StoryHttpSupport.ok(
                reviews.getPreview(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        request.getParameter("source"),
                        request.getParameter("expectedRev"),
                        request.getParameter("snapshotId"),
                        request.getParameter("mode"),
                        request.getParameter("roleCode"),
                        StoryHttpSupport.requestId(request)));
    }

    /** 서블릿이 해독한 쿼리의 추가 키·중복 키를 거절하며 빈 값도 임의 기본값으로 바꾸지 않는다. */
    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap()
                .forEach(
                        (key, values) -> {
                            if (!allowed.contains(key) || values == null || values.length != 1)
                                throw AuthException.badRequest("INVALID_REQUEST");
                        });
    }

    /**
     * 저장된 활성 초안을 현재 편집 자격으로 사전 검사한다.
     *
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param request 현재 세션·CSRF·출처와 8KiB 엄격 본문 경계의 요청
     * @return 원문 없는 구조 진단이며 불완전한 초안도 200과 no-store다
     * @throws AuthException 입력·인가·상태·수정번호·감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/review-precheck")
    public ResponseEntity<?> precheck(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode body = http.body(request, 8192, Set.of("expectedRev"));
        return StoryHttpSupport.ok(
                reviews.precheck(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.requestId(request)));
    }

    /**
     * 브라우저 의도 키 하나를 불변 사본과 STRUCTURE 결과로 원자적으로 확정한다.
     *
     * @param storyCode null이 아닌 사건 코드
     * @param versionNo 양의 버전 번호
     * @param request expectedRev와 UUID v4 requestKey만 가진 8KiB 요청
     * @return 신규 201 또는 원래 요청 재생 200이며 모두 no-store다
     * @throws AuthException 입력·현재 인가·완성도·충돌·감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/review-requests")
    public ResponseEntity<?> requestReview(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode body = http.body(request, 8192, Set.of("expectedRev", "requestKey"));
        var result =
                reviews.requestReview(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.uuid(body, "requestKey", true),
                        StoryHttpSupport.requestId(request));
        return result.replayed() ? StoryHttpSupport.ok(result) : StoryHttpSupport.created(result);
    }

    /**
     * 원본을 검토한 현재 편집자의 명시적인 확인만 받아 전체 활성 예시를 확인한다.
     *
     * @param storyCode 대상 사건의 불변 코드이며 null이 아니다
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param request 세션·CSRF·동일 출처 검사를 거친 최대 8192바이트 JSON 요청
     * @return 원고 없는 수정번호·확인 수·변경 여부·서버 요청 ID와 no-store 응답
     * @throws AuthException 입력·현재 자격·상태·수정번호·완성도·감사 검사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/grade-samples/check")
    public ResponseEntity<?> checkGradeSamples(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode body = http.body(request, 8192, Set.of("expectedRev"));

        return StoryHttpSupport.ok(
                samples.checkGradeSamples(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        StoryHttpSupport.text(body, "expectedRev"),
                        StoryHttpSupport.requestId(request)));
    }
}
