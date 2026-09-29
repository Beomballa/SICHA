package com.reasoning.admin.story.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.admin.auth.audit.AccessHistoryFilter;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryService;
import com.reasoning.common.story.service.StoryPersonService;
import com.reasoning.common.story.service.StoryRoleService;
import com.reasoning.common.story.service.StoryClueService;
import com.reasoning.common.story.service.StoryHintService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/api/stories")
public final class StoryController {
    private static final Set<String> CREATE_FIELDS = Set.of("createKey", "title");
    private static final Set<String> PATCH_FIELDS = Set.of("expectedRev", "changes");
    private final StoryService stories;
    private final AdminSessionAdapter sessions;
    private final ObjectMapper mapper;
    private final StoryPersonService persons;
    private final StoryRoleService roles;
    private final StoryClueService clues;
    private final StoryHintService hints;

    public StoryController(StoryService stories, AdminSessionAdapter sessions, ObjectMapper mapper,
            StoryPersonService persons, StoryRoleService roles, StoryClueService clues, StoryHintService hints) {
        this.stories = stories;
        this.sessions = sessions;
        this.mapper = mapper;
        this.persons = persons;
        this.roles = roles;
        this.clues = clues;
        this.hints = hints;
    }

    /**
     * 한 번만 사용하는 UUID 의도로 첫 DRAFT를 생성하며 이전 응답을 재생하지 않는다.
     * @param request 현재 세션과 createKey·title을 담은 최대 8 KiB UTF-8 JSON 요청
     * @return 새 사건·버전 식별자, 수정번호와 요청 ID
     * @throws AuthException 입력 오류, CREATE 권한 회수 또는 생성 의도 중복 시
     */
    @PostMapping
    public ResponseEntity<?> createStory(HttpServletRequest request) {
        CurrentSession current = current(request);
        JsonNode body = body(request, 8192, CREATE_FIELDS);
        String key = text(body, "createKey");
        if (!key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(stories.createStory(current.id(), current.principal(), UUID.fromString(key),
                        text(body, "title"), requestId(request)));
    }

    /**
     * 현재 권한이 있는 사건만 ID 역순 커서로 조회한다.
     * @param size 1~100의 페이지 크기이며 null이면 20이다
     * @param afterId 양의 십진수 커서 또는 null
     * @param code 선택적인 정확 일치 사건 코드
     * @param activeYn 기본값 true이며 false는 소유자의 비활성 사건만 선택한다
     * @param request 인증된 현재 브라우저 세션
     * @return 접근 불가 사건명을 제외한 권한 필터 목록
     */
    @GetMapping
    public ResponseEntity<?> getStoryList(@RequestParam(required = false) Integer size,
            @RequestParam(required = false) String afterId, @RequestParam(required = false) String code,
            @RequestParam(required = false) Boolean activeYn, HttpServletRequest request) {
        CurrentSession current = current(request);
        return ok(stories.getStoryList(current.id(), current.principal(), size, afterId, code, activeYn));
    }

    /**
     * 소유자 또는 현재 허용된 협업자의 필수 조회 감사를 확정한 뒤 원고를 반환한다.
     * @param storyCode 변경 불가능한 정확한 사건 코드
     * @param versionNo 해당 사건의 양의 버전 번호
     * @param request 인증된 현재 브라우저 세션
     * @return 저장된 영역, 서버 정책 투영과 차단하지 않는 경고
     * @throws AuthException 현재 권한·대상이 없거나 필수 조회 감사가 실패한 경우
     */
    @GetMapping("/{storyCode}/versions/{versionNo}")
    public ResponseEntity<?> getStoryDetail(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession current = current(request);
        return ok(stories.getStoryDetail(current.id(), current.principal(), storyCode, versionNo, requestId(request)));
    }

    /**
     * 예상 콘텐츠 수정번호에 맞춰 한 영역의 명시적 필드만 바꾼다.
     * @param storyCode 변경 불가능한 정확한 사건 코드
     * @param versionNo 해당 사건의 양의 버전 번호
     * @param section 임의 테이블·컬럼이 아닌 basic, answer, reveal 중 한 영역
     * @param request 현재 세션과 최대 512 KiB UTF-8 JSON 요청이며 허용 필드의 명시적 null은 지운다
     * @return 제출 원고를 되돌려주지 않는 확정 수정번호와 경고
     * @throws AuthException 입력 오류, 오래된 수정번호, 접근 거부 또는 저장 상태 불확실 시
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/sections/{section}")
    public ResponseEntity<?> updateStorySection(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String section, HttpServletRequest request) {
        CurrentSession current = current(request);
        JsonNode body = body(request, 512 * 1024, PATCH_FIELDS);
        if (!body.get("changes").isObject() || body.get("changes").isEmpty())
            throw AuthException.badRequest("INVALID_REQUEST");
        return ok(stories.updateStorySection(current.id(), current.principal(), storyCode, versionNo,
                section, text(body, "expectedRev"), body.get("changes"), requestId(request)));
    }

    /**
     * 인물 원고 없이 같은 버전의 키 목록만 반환한다.
     * @param size 1~100이며 null이면 20이다
     * @param afterKey 마지막 반환 ASCII 인물 코드 또는 null
     * @param activeYn null이면 활성, false이면 비활성 인물만 조회한다
     * @return 동일 콘텐츠 수정번호와 커서 목록
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/persons")
    public ResponseEntity<?> getPersonList(@PathVariable String storyCode, @PathVariable int versionNo,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(persons.getPersonList(actor.id(), actor.principal(), storyCode, versionNo, size, afterKey, activeYn));
    }

    /** 인물의 단건 원고를 현재 부모 권한·조회 감사 확인 후 반환한다. */
    @GetMapping("/{storyCode}/versions/{versionNo}/persons/{itemKey}")
    public ResponseEntity<?> getPersonDetail(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(persons.getPersonDetail(actor.id(), actor.principal(), storyCode, versionNo, itemKey, requestId(request)));
    }

    /**
     * 예약되지 않은 인물 코드를 생성한다.
     * @param request expectedRev와 item만 받는 최대 512 KiB JSON이며 세션·CSRF가 필요하다
     * @return 201 원문 없는 수정번호와 생성 itemKey
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/persons")
    public ResponseEntity<?> createPerson(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, Set.of("expectedRev", "item"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(persons.createPerson(actor.id(), actor.principal(), storyCode, versionNo,
                        text(payload, "expectedRev"), payload.get("item"), requestId(request)));
    }

    /**
     * 선택한 인물 필드만 수정하며 서버 필드·키 변경을 거절한다.
     * @param request expectedRev·changes JSON이며 null과 생략은 다른 의미다
     * @return 원문 없는 확정 수정번호·무변경 여부
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/persons/{itemKey}")
    public ResponseEntity<?> updatePerson(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, PATCH_FIELDS);
        return ok(persons.updatePerson(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), payload.get("changes"), requestId(request)));
    }

    /**
     * 인물의 명시적 논리 삭제·복원만 수행하며 활성 참조를 자동 해제하지 않는다.
     * @param operation 경로에서 고정된 deactivate 또는 reactivate만 허용한다
     * @param request expectedRev 하나만 포함한 최대 8 KiB JSON이며 현재 권한·부모 상태를 재확인한다
     * @return 실제 상태 변경에만 증가한 콘텐츠 수정번호
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/persons/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updatePersonActive(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, @PathVariable String operation, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev"));
        return ok(persons.updatePersonActive(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), "reactivate".equals(operation), requestId(request)));
    }

    /**
     * 역할 코드를 ASCII 오름차순으로 조회하며 원고는 반환하지 않는다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param size 1~100이며 생략하면 20개다
     * @param afterKey 마지막 역할 코드이며 생략하면 첫 페이지다
     * @param activeYn 생략 시 true인 활성 필터
     * @param request 현재 세션을 확인할 HTTP 요청
     * @return 원고 없는 키 페이지와 부모 수정번호
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/roles")
    public ResponseEntity<?> getRoleList(@PathVariable String storyCode, @PathVariable int versionNo,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(roles.getRoleList(actor.id(), actor.principal(), storyCode, versionNo, size, afterKey, activeYn));
    }

    /**
     * 역할 단건 원고를 필수 조회 감사 후에 반환한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param itemKey 영문 대문자·숫자·밑줄 1~32자의 역할 코드
     * @param request 현재 세션 및 서버 요청 ID를 확인할 요청
     * @return 현재 인가·감사에 성공한 보호 원고와 수정번호
     * @throws AuthException 인증·인가·대상 존재·감사 확인 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/roles/{itemKey}")
    public ResponseEntity<?> getRoleDetail(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(roles.getRoleDetail(actor.id(), actor.principal(), storyCode, versionNo, itemKey, requestId(request)));
    }

    /**
     * 역할 생성의 expectedRev·item만 담은 최대 512 KiB JSON을 받는다.
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param request 세션·CSRF가 필요하며 item에는 code·name 및 선택 brief만 허용한다
     * @return 201 상태와 원고 없는 생성 키·수정번호
     * @throws AuthException 입력·키 예약·수정번호·인가·필수 감사 확인 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/roles")
    public ResponseEntity<?> createRole(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, Set.of("expectedRev", "item"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(roles.createRole(actor.id(), actor.principal(), storyCode, versionNo,
                        text(payload, "expectedRev"), payload.get("item"), requestId(request)));
    }

    /**
     * 역할 이름 또는 소개만 수정하고 코드와 서버 필드는 거절한다.
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경 불가인 ASCII 역할 코드
     * @param request expectedRev·changes만 허용하는 최대 512 KiB JSON; 생략과 null은 다르다
     * @return 실제 변경 여부와 확정 부모 수정번호
     * @throws AuthException 입력·상태·수정번호·인가·필수 감사 확인 실패 시
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/roles/{itemKey}")
    public ResponseEntity<?> updateRole(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, PATCH_FIELDS);
        return ok(roles.updateRole(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), payload.get("changes"), requestId(request)));
    }

    /**
     * 역할을 명시적으로 비활성화·복원하며 활성 조합을 자동 삭제하지 않는다.
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey ASCII 역할 코드
     * @param operation 고정 경로의 deactivate 또는 reactivate
     * @param request expectedRev만 포함한 최대 8 KiB JSON; 현재 세션·CSRF가 필요하다
     * @return 실제 상태 변경 여부와 부모 수정번호
     * @throws AuthException 활성 참조·상태·수정번호·인가·필수 감사 확인 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/roles/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updateRoleActive(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, @PathVariable String operation, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev"));
        return ok(roles.updateRoleActive(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), "reactivate".equals(operation), requestId(request)));
    }

    /**
     * 조합의 각 역할 코드를 ASCII 튜플 오름차순으로 조회한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param size 1~100이며 생략하면 20개다
     * @param afterKey ASCII 정순 roleA~roleB이며 생략하면 첫 페이지다
     * @param activeYn 생략 시 true인 활성 필터
     * @param request 현재 세션을 확인할 요청
     * @return 원고 없는 복합 키 페이지와 부모 수정번호
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/pairs")
    public ResponseEntity<?> getPairList(@PathVariable String storyCode, @PathVariable int versionNo,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(roles.getPairList(actor.id(), actor.principal(), storyCode, versionNo, size, afterKey, activeYn));
    }

    /**
     * 고정 키로 찾은 조합을 필수 조회 감사 후 반환한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param itemKey ASCII 정순 roleA~roleB
     * @param request 현재 세션과 서버 요청 ID를 확인할 요청
     * @return 두 역할 키·활성 상태·시각과 부모 수정번호
     * @throws AuthException 인증·인가·대상 존재·필수 감사 확인 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/pairs/{itemKey}")
    public ResponseEntity<?> getPairDetail(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(roles.getPairDetail(actor.id(), actor.principal(), storyCode, versionNo, itemKey, requestId(request)));
    }

    /**
     * 정순의 활성 역할 두 개로 조합을 생성하며 최대 8 KiB JSON만 받는다.
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param request expectedRev와 roleA·roleB만 담은 item; 세션·CSRF가 필요하다
     * @return 201 상태와 원고 없는 생성 키·수정번호
     * @throws AuthException 정순·활성 참조·예약 키·수정번호·인가·감사 확인 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/pairs")
    public ResponseEntity<?> createPair(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev", "item"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(roles.createPair(actor.id(), actor.principal(), storyCode, versionNo,
                        text(payload, "expectedRev"), payload.get("item"), requestId(request)));
    }

    /**
     * 조합은 수정 가능한 속성이 없으므로 빈 PATCH와 임의 변경 모두 400으로 거절한다.
     * @param storyCode 요청 경로의 사건 코드이며 이 거절 경로는 원고를 조회하지 않는다
     * @param versionNo 요청 경로의 버전 번호
     * @param itemKey 요청 경로의 조합 키이며 변경할 수 없다
     * @param request 현재 세션과 엄격 JSON 형식을 검사할 요청
     * @return 정상 반환하지 않는다
     * @throws AuthException 유효한 형식도 INVALID_REQUEST로 거절한다
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/pairs/{itemKey}")
    public ResponseEntity<?> updatePair(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        current(request);
        body(request, 512 * 1024, PATCH_FIELDS);
        throw AuthException.badRequest("INVALID_REQUEST");
    }

    /**
     * 조합의 비활성화·복원만 지원하며 복원 시 활성 역할을 다시 확인한다.
     * @param storyCode 변경할 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경 불가인 ASCII 정순 roleA~roleB
     * @param operation 고정 경로의 deactivate 또는 reactivate
     * @param request expectedRev만 포함한 최대 8 KiB JSON; 현재 세션·CSRF가 필요하다
     * @return 실제 상태 변경 여부와 부모 수정번호
     * @throws AuthException 활성 역할·상태·수정번호·인가·필수 감사 확인 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/pairs/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updatePairActive(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, @PathVariable String operation, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev"));
        return ok(roles.updatePairActive(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), "reactivate".equals(operation), requestId(request)));
    }

    /**
     * 원고를 노출하지 않고 단서 코드의 ASCII 페이지를 조회한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 버전 번호
     * @param size 1~100, 생략하면 20
     * @param afterKey 마지막 ASCII 단서 코드 또는 null
     * @param activeYn 생략하면 활성 단서만 조회한다
     * @param request 인증된 세션 요청
     * @return 수정번호와 키·상태·시각 페이지
     * @throws AuthException 입력·인가 또는 부모 조회 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/clues")
    public ResponseEntity<?> getClueList(@PathVariable String storyCode, @PathVariable int versionNo,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(clues.getClueList(actor.id(), actor.principal(), storyCode, versionNo, size, afterKey, activeYn));
    }

    /**
     * 권한과 필수 조회 감사를 확인한 뒤 단서 원고를 조회한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 버전 번호
     * @param itemKey ASCII 단서 코드
     * @param request 현재 세션과 요청 ID를 가진 요청
     * @return 수정번호와 단서 단건 원고
     * @throws AuthException 입력·인가·대상 또는 감사 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/clues/{itemKey}")
    public ResponseEntity<?> getClueDetail(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(clues.getClueDetail(actor.id(), actor.principal(), storyCode, versionNo, itemKey, requestId(request)));
    }

    /**
     * 단서 코드와 제목을 포함한 새 DRAFT 단서를 생성한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param request expectedRev·item의 최대 512 KiB 엄격 UTF-8 JSON; 세션·CSRF 필수
     * @return 201과 원고 없는 생성 키·수정번호
     * @throws AuthException 입력·참조·수정번호·인가·감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/clues")
    public ResponseEntity<?> createClue(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, Set.of("expectedRev", "item"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(clues.createClue(actor.id(), actor.principal(), storyCode, versionNo,
                        text(payload, "expectedRev"), payload.get("item"), requestId(request)));
    }

    /**
     * 단서 키·서버 필드를 제외한 명시적 원고 변경을 저장한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey 변경 불가인 ASCII 단서 코드
     * @param request expectedRev·changes의 최대 512 KiB 엄격 UTF-8 JSON; 세션·CSRF 필수
     * @return 변경 여부·확정 수정번호·경고, 원고는 반환하지 않는다
     * @throws AuthException 입력·활성 관계·수정번호·인가·감사 실패 시
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/clues/{itemKey}")
    public ResponseEntity<?> updateClue(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, PATCH_FIELDS);
        return ok(clues.updateClue(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), payload.get("changes"), requestId(request)));
    }

    /**
     * 단서를 논리 삭제하거나 복원하며 활성 참조를 자동 해제하지 않는다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey ASCII 단서 코드
     * @param operation 고정된 deactivate 또는 reactivate
     * @param request expectedRev만 담은 최대 8192바이트 JSON; 세션·CSRF 필수
     * @return 변경 여부와 수정번호
     * @throws AuthException 참조·상태·수정번호·인가·감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/clues/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updateClueActive(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, @PathVariable String operation, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev"));
        return ok(clues.updateClueActive(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), "reactivate".equals(operation), requestId(request)));
    }

    /** 원고를 제외한 힌트 코드·상태·시각을 현재 권한의 버전에서 조회한다. */
    @GetMapping("/{storyCode}/versions/{versionNo}/hints")
    public ResponseEntity<?> getHintList(@PathVariable String storyCode, @PathVariable int versionNo,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(hints.getHintList(actor.id(), actor.principal(), storyCode, versionNo, size, afterKey, activeYn));
    }

    /** 현재 접근 권한과 필수 조회 감사를 확정한 뒤 단건 힌트 원고를 반환한다. */
    @GetMapping("/{storyCode}/versions/{versionNo}/hints/{itemKey}")
    public ResponseEntity<?> getHintDetail(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(hints.getHintDetail(actor.id(), actor.principal(), storyCode, versionNo, itemKey, requestId(request)));
    }

    /** 필수 단계와 고정 코드를 갖는 새 힌트를 최대 512 KiB UTF-8 JSON으로 생성한다. */
    @PostMapping("/{storyCode}/versions/{versionNo}/hints")
    public ResponseEntity<?> createHint(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, Set.of("expectedRev", "item"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(hints.createHint(actor.id(), actor.principal(), storyCode, versionNo,
                        text(payload, "expectedRev"), payload.get("item"), requestId(request)));
    }

    /** 키를 유지하고 단계 또는 원고만 명시적 수정번호로 변경한다. */
    @PatchMapping("/{storyCode}/versions/{versionNo}/hints/{itemKey}")
    public ResponseEntity<?> updateHint(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 512 * 1024, PATCH_FIELDS);
        return ok(hints.updateHint(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), payload.get("changes"), requestId(request)));
    }

    /** 힌트를 명시적으로 논리 삭제·복원하며 기존 단계 점유는 유지한다. */
    @PostMapping("/{storyCode}/versions/{versionNo}/hints/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updateHintActive(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, @PathVariable String operation, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev"));
        return ok(hints.updateHintActive(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), "reactivate".equals(operation), requestId(request)));
    }

    /**
     * 단서 코드·역할 코드 튜플의 ASCII 페이지를 원고 없이 조회한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 버전 번호
     * @param size 1~100, 생략하면 20
     * @param afterKey 마지막 clueCode~roleCode 또는 null; 구성요소 간 정렬은 요구하지 않는다
     * @param activeYn 생략하면 활성 배정만 조회한다
     * @param request 인증된 세션 요청
     * @return 수정번호와 배정 키·상태·시각 페이지
     * @throws AuthException 입력·인가 또는 부모 조회 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/clue-roles")
    public ResponseEntity<?> getClueRoleList(@PathVariable String storyCode, @PathVariable int versionNo,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String afterKey,
            @RequestParam(required = false) Boolean activeYn, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(clues.getClueRoleList(actor.id(), actor.principal(), storyCode, versionNo, size, afterKey, activeYn));
    }

    /**
     * 필수 조회 감사 후 단서·역할 배정 상태를 반환한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 사건 버전 번호
     * @param itemKey 의미 순서의 clueCode~roleCode
     * @param request 현재 세션과 요청 ID를 가진 요청
     * @return 수정번호와 배정 구성요소·상태·시각
     * @throws AuthException 입력·인가·대상 또는 감사 실패 시
     */
    @GetMapping("/{storyCode}/versions/{versionNo}/clue-roles/{itemKey}")
    public ResponseEntity<?> getClueRoleDetail(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        CurrentSession actor = current(request);
        return ok(clues.getClueRoleDetail(actor.id(), actor.principal(), storyCode, versionNo, itemKey, requestId(request)));
    }

    /**
     * 활성 ROLE 단서와 활성 역할을 명시적으로 배정한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param request expectedRev·clueCode·roleCode를 담은 최대 8192바이트 JSON; 세션·CSRF 필수
     * @return 201과 의미 순서의 생성 키·수정번호
     * @throws AuthException 참조·중복·입력·수정번호·인가·감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/clue-roles")
    public ResponseEntity<?> createClueRole(@PathVariable String storyCode, @PathVariable int versionNo,
            HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev", "item"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(clues.createClueRole(actor.id(), actor.principal(), storyCode, versionNo,
                        text(payload, "expectedRev"), payload.get("item"), requestId(request)));
    }

    /**
     * 속성이 없는 배정의 PATCH는 내용과 관계없이 거절한다.
     * @param storyCode 요청 경로의 사건 코드
     * @param versionNo 요청 경로의 버전 번호
     * @param itemKey 변경할 수 없는 배정 키
     * @param request 세션과 최대 512 KiB 엄격 JSON 형식을 검사할 요청
     * @return 정상 반환하지 않는다
     * @throws AuthException 유효한 JSON도 INVALID_REQUEST로 거절한다
     */
    @PatchMapping("/{storyCode}/versions/{versionNo}/clue-roles/{itemKey}")
    public ResponseEntity<?> updateClueRole(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, HttpServletRequest request) {
        current(request);
        body(request, 512 * 1024, PATCH_FIELDS);
        throw AuthException.badRequest("INVALID_REQUEST");
    }

    /**
     * 배정을 논리 삭제하거나 대상 활성 상태를 재확인해 복원한다.
     * @param storyCode 부모 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey 의미 순서의 clueCode~roleCode
     * @param operation 고정된 deactivate 또는 reactivate
     * @param request expectedRev만 담은 최대 8192바이트 JSON; 세션·CSRF 필수
     * @return 변경 여부와 수정번호
     * @throws AuthException 참조·상태·수정번호·인가·감사 실패 시
     */
    @PostMapping("/{storyCode}/versions/{versionNo}/clue-roles/{itemKey}/{operation:deactivate|reactivate}")
    public ResponseEntity<?> updateClueRoleActive(@PathVariable String storyCode, @PathVariable int versionNo,
            @PathVariable String itemKey, @PathVariable String operation, HttpServletRequest request) {
        CurrentSession actor = current(request);
        JsonNode payload = body(request, 8192, Set.of("expectedRev"));
        return ok(clues.updateClueRoleActive(actor.id(), actor.principal(), storyCode, versionNo, itemKey,
                text(payload, "expectedRev"), "reactivate".equals(operation), requestId(request)));
    }

    private CurrentSession current(HttpServletRequest request) {
        return sessions.current(request).orElseThrow(() -> AuthException.unauthorized("AUTH_REQUIRED"));
    }

    private static UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(AccessHistoryFilter.REQUEST_ID_ATTRIBUTE);
        return value instanceof UUID id ? id : UUID.randomUUID();
    }

    /**
     * 바이트 상한을 먼저 확인하고 UTF-8로만 해독한 JSON 객체를 검증한다.
     * @param request 원본 요청 본문과 JSON 콘텐츠 형식을 담은 요청
     * @param maxBytes 경로별 원본 본문 상한인 8192 또는 524288바이트
     * @param fields 정확히 포함해야 하는 최상위 필드 이름
     * @return 중복 필드와 뒤따르는 토큰이 없는 JSON 객체
     * @throws AuthException 바이트 상한 초과 시 PAYLOAD_TOO_LARGE, 잘못된 인코딩·JSON·필드 시 INVALID_REQUEST, 본문 읽기 실패 시 STORY_UNAVAILABLE
     */
    private JsonNode body(HttpServletRequest request, int maxBytes, Set<String> fields) {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))
            throw AuthException.badRequest("INVALID_REQUEST");
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding))
            throw AuthException.badRequest("INVALID_REQUEST");
        try {
            byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new AuthException(413, "PAYLOAD_TOO_LARGE", "PAYLOAD_TOO_LARGE");
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonParser parser = mapper.getFactory().createParser(json)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode value = mapper.readTree(parser);
                if (value == null || !value.isObject() || value.size() != fields.size()
                        || parser.nextToken() != null)
                    throw AuthException.badRequest("INVALID_REQUEST");
                for (String name : fields) if (!value.has(name)) throw AuthException.badRequest("INVALID_REQUEST");
                return value;
            }
        } catch (CharacterCodingException | JsonProcessingException exception) {
            throw AuthException.badRequest("INVALID_REQUEST");
        } catch (IOException exception) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) throw AuthException.badRequest("INVALID_REQUEST");
        return value.textValue();
    }

    private static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
