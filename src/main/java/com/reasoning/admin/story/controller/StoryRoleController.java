package com.reasoning.admin.story.controller;

import static com.reasoning.admin.story.controller.StoryHttpSupport.created;
import static com.reasoning.admin.story.controller.StoryHttpSupport.ok;
import static com.reasoning.admin.story.controller.StoryHttpSupport.requestId;
import static com.reasoning.admin.story.controller.StoryHttpSupport.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryRoleService;

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

/** 같은 사건 버전의 역할과 역할 조합의 HTTP 경계를 담당한다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/stories")
public final class StoryRoleController {
    private static final Set<String> PATCH_FIELDS = Set.of("expectedRev", "changes");
    private final StoryRoleService roles;
    private final StoryHttpSupport http;

    /** 역할·조합 업무 서비스와 세션·본문 검증을 담당하는 HTTP 경계를 주입한다. */
    public StoryRoleController(StoryRoleService roles, StoryHttpSupport http) {
        this.roles = roles;
        this.http = http;
    }

    /**
     * 역할 코드를 ASCII 오름차순으로 조회하며 원고는 반환하지 않는다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param size 1~100이며 생략하면 20개다
     * @param afterKey 마지막 역할 코드이며 생략하면 첫 페이지다
     * @param activeYn 생략 시 true인 활성 필터
     * @param request 현재 세션을 확인할 HTTP 요청
     * @return 원고 없는 키 페이지와 부모 수정번호
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/roles")
    public ResponseEntity<?> getRoleList(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);

        return ok(
                roles.getRoleList(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        size,
                        afterKey,
                        activeYn));
    }

    /**
     * 역할 단건 원고를 필수 조회 감사 후에 반환한다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param itemKey 영문 대문자·숫자·밑줄 1~32자의 역할 코드
     * @param request 현재 세션 및 서버 요청 ID를 확인할 요청
     * @return 현재 인가·감사에 성공한 보호 원고와 수정번호
     * @throws AuthException 인증·인가·대상 존재·감사 확인 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/roles/{itemKey}")
    public ResponseEntity<?> getRoleDetail(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);

        return ok(
                roles.getRoleDetail(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        itemKey,
                        requestId(request)));
    }

    /**
     * 역할 생성의 expectedRev·item만 담은 최대 512 KiB JSON을 받는다.
     *
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param request 세션·CSRF가 필요하며 item에는 code·name 및 선택 brief만 허용한다
     * @return 201 상태와 원고 없는 생성 키·수정번호
     * @throws AuthException 입력·키 예약·수정번호·인가·필수 감사 확인 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/roles")
    public ResponseEntity<?> createRole(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 512 * 1024, Set.of("expectedRev", "item"));

        return created(
                roles.createRole(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        text(payload, "expectedRev"),
                        payload.get("item"),
                        requestId(request)));
    }

    /**
     * 역할 이름 또는 소개만 수정하고 코드와 서버 필드는 거절한다.
     *
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경 불가인 ASCII 역할 코드
     * @param request expectedRev·changes만 허용하는 최대 512 KiB JSON; 생략과 null은 다르다
     * @return 실제 변경 여부와 확정 부모 수정번호
     * @throws AuthException 입력·상태·수정번호·인가·필수 감사 확인 실패 시
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/roles/{itemKey}")
    public ResponseEntity<?> updateRole(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 512 * 1024, PATCH_FIELDS);

        return ok(
                roles.updateRole(
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
     * 역할을 명시적으로 비활성화·복원하며 활성 조합을 자동 삭제하지 않는다.
     *
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey ASCII 역할 코드
     * @param operation 고정 경로의 deactivate 또는 reactivate
     * @param request expectedRev만 포함한 최대 8 KiB JSON; 현재 세션·CSRF가 필요하다
     * @return 실제 상태 변경 여부와 부모 수정번호
     * @throws AuthException 활성 참조·상태·수정번호·인가·필수 감사 확인 실패 시
     */
    @PostMapping(
            "/{storyCode}/versions/{versionNo}/roles/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updateRoleActive(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            @PathVariable String operation,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 8192, Set.of("expectedRev"));

        return ok(
                roles.updateRoleActive(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        itemKey,
                        text(payload, "expectedRev"),
                        "reactivate".equals(operation),
                        requestId(request)));
    }

    /**
     * 조합의 각 역할 코드를 ASCII 튜플 오름차순으로 조회한다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param size 1~100이며 생략하면 20개다
     * @param afterKey ASCII 정순 roleA~roleB이며 생략하면 첫 페이지다
     * @param activeYn 생략 시 true인 활성 필터
     * @param request 현재 세션을 확인할 요청
     * @return 원고 없는 복합 키 페이지와 부모 수정번호
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/pairs")
    public ResponseEntity<?> getPairList(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);

        return ok(
                roles.getPairList(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        size,
                        afterKey,
                        activeYn));
    }

    /**
     * 고정 키로 찾은 조합을 필수 조회 감사 후 반환한다.
     *
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param itemKey ASCII 정순 roleA~roleB
     * @param request 현재 세션과 서버 요청 ID를 확인할 요청
     * @return 두 역할 키·활성 상태·시각과 부모 수정번호
     * @throws AuthException 인증·인가·대상 존재·필수 감사 확인 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/pairs/{itemKey}")
    public ResponseEntity<?> getPairDetail(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);

        return ok(
                roles.getPairDetail(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        itemKey,
                        requestId(request)));
    }

    /**
     * 정순의 활성 역할 두 개로 조합을 생성하며 최대 8 KiB JSON만 받는다.
     *
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param request expectedRev와 roleA·roleB만 담은 item; 세션·CSRF가 필요하다
     * @return 201 상태와 원고 없는 생성 키·수정번호
     * @throws AuthException 정순·활성 참조·예약 키·수정번호·인가·감사 확인 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/pairs")
    public ResponseEntity<?> createPair(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 8192, Set.of("expectedRev", "item"));

        return created(
                roles.createPair(
                        actor.id(),
                        actor.principal(),
                        storyCode,
                        versionNo,
                        text(payload, "expectedRev"),
                        payload.get("item"),
                        requestId(request)));
    }

    /**
     * 조합은 수정 가능한 속성이 없으므로 빈 PATCH와 임의 변경 모두 400으로 거절한다.
     *
     * @param storyCode 요청 경로의 사건 코드이며 이 거절 경로는 원고를 조회하지 않는다
     * @param versionNo 요청 경로의 버전 번호
     * @param itemKey 요청 경로의 조합 키이며 변경할 수 없다
     * @param request 현재 세션과 엄격 JSON 형식을 검사할 요청
     * @return 정상 반환하지 않는다
     * @throws AuthException 유효한 형식도 INVALID_REQUEST로 거절한다
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/pairs/{itemKey}")
    public ResponseEntity<?> updatePair(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            HttpServletRequest request) {
        http.currentSession(request);
        http.body(request, 512 * 1024, PATCH_FIELDS);

        throw AuthException.badRequest("INVALID_REQUEST");
    }

    /**
     * 조합의 비활성화·복원만 지원하며 복원 시 활성 역할을 다시 확인한다.
     *
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경 불가인 ASCII 정순 roleA~roleB
     * @param operation 고정 경로의 deactivate 또는 reactivate
     * @param request expectedRev만 포함한 최대 8 KiB JSON; 현재 세션·CSRF가 필요하다
     * @return 실제 상태 변경 여부와 부모 수정번호
     * @throws AuthException 활성 역할·상태·수정번호·인가·필수 감사 확인 실패 시
     */
    @PostMapping(
            "/{storyCode}/versions/{versionNo}/pairs/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updatePairActive(
            @PathVariable String storyCode,
            @PathVariable int versionNo,
            @PathVariable String itemKey,
            @PathVariable String operation,
            HttpServletRequest request) {
        CurrentSession actor = http.currentSession(request);
        JsonNode payload = http.body(request, 8192, Set.of("expectedRev"));

        return ok(
                roles.updatePairActive(
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
