package com.reasoning.admin.story.controller;

import static com.reasoning.admin.story.controller.StoryHttpSupport.created;
import static com.reasoning.admin.story.controller.StoryHttpSupport.ok;
import static com.reasoning.admin.story.controller.StoryHttpSupport.requestId;
import static com.reasoning.admin.story.controller.StoryHttpSupport.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.story.service.StoryPersonService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 같은 사건 버전에 속한 인물의 HTTP 경계를 담당한다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/stories")
public final class StoryPersonController {
    private static final Set<String> PATCH_FIELDS = Set.of("expectedRev", "changes");
    private final StoryPersonService persons;
    private final StoryHttpSupport http;

    /** 인물 업무 서비스와 세션·본문 검증을 담당하는 HTTP 경계를 주입한다. */
    public StoryPersonController(StoryPersonService persons, StoryHttpSupport http) {
        this.persons = persons;
        this.http = http;
    }

    /**
     * 인물 원고 없이 같은 버전의 키 목록만 반환한다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param size 1~100이며 null이면 20이다
     * @param afterKey 마지막 반환 ASCII 인물 코드 또는 null
     * @param activeYn null이면 활성, false이면 비활성 인물만 조회한다
     * @param request 현재 세션을 확인할 HTTP 요청
     * @return 동일 콘텐츠 수정번호와 커서 목록
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/persons")
    public ResponseEntity<?> getPersonList(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);

        return ok(
                persons.getPersonList(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        size,
                        afterKey,
                        activeYn));
    }

    /**
     * 인물의 단건 원고를 현재 부모 권한·조회 감사 확인 후 반환한다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param itemKey 예약된 ASCII 인물 코드
     * @param request 현재 세션과 서버 요청 ID를 확인할 요청
     * @return 부모 수정번호와 보호 인물 원고
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/persons/{itemKey}")
    public ResponseEntity<?> getPersonDetail(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);

        return ok(
                persons.getPersonDetail(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        itemKey,
                        requestId(request)));
    }

    /**
     * 예약되지 않은 인물 코드를 생성한다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param request expectedRev와 item만 받는 최대 512 KiB JSON이며 세션·CSRF가 필요하다
     * @return 201 원문 없는 수정번호와 생성 itemKey
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/persons")
    public ResponseEntity<?> createPerson(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 512 * 1024, Set.of("expectedRev", "item"));

        return created(
                persons.createPerson(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        text(payload, "expectedRev"),
                        payload.get("item"),
                        requestId(request)));
    }

    /**
     * 선택한 인물 필드만 수정하며 서버 필드·키 변경을 거절한다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경 불가인 ASCII 인물 코드
     * @param request expectedRev·changes JSON이며 null과 생략은 다른 의미다
     * @return 원문 없는 확정 수정번호·무변경 여부
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/persons/{itemKey}")
    public ResponseEntity<?> updatePerson(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 512 * 1024, PATCH_FIELDS);

        return ok(
                persons.updatePerson(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        itemKey,
                        text(payload, "expectedRev"),
                        payload.get("changes"),
                        requestId(request)));
    }

    /**
     * 인물의 명시적 논리 삭제·복원만 수행하며 활성 참조를 자동 해제하지 않는다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 예약된 ASCII 인물 코드
     * @param operation 경로에서 고정된 deactivate 또는 reactivate만 허용한다
     * @param request expectedRev 하나만 포함한 최대 8 KiB JSON이며 현재 권한·부모 상태를 재확인한다
     * @return 실제 상태 변경에만 증가한 콘텐츠 수정번호
     */
    @PostMapping(
            "/{storyCode}/versions/{versionNo}/persons/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updatePersonActive(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            @PathVariable String operation,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 8192, Set.of("expectedRev"));

        return ok(
                persons.updatePersonActive(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        itemKey,
                        text(payload, "expectedRev"),
                        "reactivate".equals(operation),
                        requestId(request)));
    }
}
