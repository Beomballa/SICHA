package com.reasoning.web.game;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.game.service.GamePlayService;
import com.reasoning.common.game.service.PublicCoopReportService;
import com.reasoning.common.game.service.PublicCoopService;
import com.reasoning.common.game.service.PublicGameService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** 공개 SOLO·COOP HTTP의 엄격한 필드·Bearer·no-store 경계다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api")
public final class PublicGameController {
    private final PublicGameService games;
    private final GamePlayService play;
    private final PublicCoopService coop;
    private final PublicCoopReportService coopReports;
    private final ObjectMapper mapper;

    public PublicGameController(
            PublicGameService games,
            GamePlayService play,
            PublicCoopService coop,
            PublicCoopReportService coopReports,
            ObjectMapper mapper) {
        this.games = games;
        this.play = play;
        this.coop = coop;
        this.coopReports = coopReports;
        this.mapper = mapper;
    }

    /** 회원용 공개 사건 목록은 암호화 커서와 1~100 크기만 허용한다. */
    @GetMapping("/stories")
    public ResponseEntity<?> stories(HttpServletRequest request) {
        query(request, Set.of("cursor", "size"));
        String raw = request.getParameter("size");
        int size = raw == null ? 20 : positiveInt(raw);
        if (size > 100) throw invalid();
        return ok(
                games.stories(bearer(request), request.getParameter("cursor"), size, id(request)));
    }

    /** 지정 사건의 공개 안전 소개만 반환한다. */
    @GetMapping("/stories/{storyCode}")
    public ResponseEntity<?> story(@PathVariable String storyCode, HttpServletRequest request) {
        noQuery(request);
        return ok(games.story(bearer(request), storyCode, id(request)));
    }

    /** 수집 준비가 완료된 FRIENDLY 현재 고지 원본만 제공한다. */
    @GetMapping("/member/privacy/policies/friendly")
    public ResponseEntity<?> notice(HttpServletRequest request) {
        noQuery(request);
        return ok(games.notice(bearer(request), id(request)));
    }

    /** 호출자가 목적·검수자·상대 회원을 입력할 수 없는 SOLO 생성만 허용한다. */
    @PostMapping("/games")
    public ResponseEntity<?> create(HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(
                        request,
                        Set.of(
                                "mode",
                                "storyCode",
                                "expectedPublishedVersionNo",
                                "expectedPlayRev",
                                "policyCode",
                                "noticeHash",
                                "requestKey"));
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(
                        games.createGame(
                                bearer(request),
                                text(value, "mode"),
                                text(value, "storyCode"),
                                positiveInt(value, "expectedPublishedVersionNo"),
                                revision(value, "expectedPlayRev"),
                                text(value, "policyCode"),
                                text(value, "noticeHash"),
                                uuid(text(value, "requestKey")),
                                id(request)));
    }

    /** 참가자 소유권을 확인한 방 상태만 읽는다. */
    @GetMapping("/games/current")
    public ResponseEntity<?> current(HttpServletRequest request) {
        noQuery(request);
        return ok(play.currentRoom(bearer(request), id(request)));
    }

    /** 현재 본인의 접속 증명만 갱신하며 준비·동의·revision을 변경하지 않는다. */
    @PostMapping("/games/{gameKey}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        body(request, Set.of());
        play.heartbeat(bearer(request), uuid(gameKey), id(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 고정 사본의 허용 단계 힌트만 명시적으로 연다. */
    @PostMapping("/games/{gameKey}/hints/{level}/open")
    public ResponseEntity<?> hint(
            @PathVariable String gameKey, @PathVariable String level, HttpServletRequest request) {
        noQuery(request);
        if (!level.matches("[1-3]")) throw invalid();
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                play.openHint(
                        bearer(request),
                        uuid(gameKey),
                        revision(value, "expectedRev"),
                        Integer.parseInt(level),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 본인에게 허용된 고정 단서의 첫 읽음 표시만 저장한다. */
    @PostMapping("/games/{gameKey}/clues/{clueCode}/read")
    public ResponseEntity<?> clue(
            @PathVariable String gameKey,
            @PathVariable String clueCode,
            HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                play.clueRead(
                        bearer(request),
                        uuid(gameKey),
                        revision(value, "expectedRev"),
                        clueCode,
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 접수 중인 판정이 없는 기한 내 방만 명시적으로 포기한다. */
    @PostMapping("/games/{gameKey}/forfeit")
    public ResponseEntity<?> forfeit(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                play.forfeit(
                        bearer(request),
                        uuid(gameKey),
                        revision(value, "expectedRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 참가자 소유권을 확인한 방 상태만 읽는다. */
    @GetMapping("/games/{gameKey}")
    public ResponseEntity<?> game(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        return ok(games.game(bearer(request), uuid(gameKey), id(request)));
    }

    /** 명시적 버전·playRev·방 revision과 서버에 고정된 모드로 단회 시작한다. */
    @PostMapping("/games/{gameKey}/start")
    public ResponseEntity<?> start(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(
                        request,
                        Set.of(
                                "expectedRev",
                                "expectedPublishedVersionNo",
                                "expectedPlayRev",
                                "requestKey"));
        String token = bearer(request);
        UUID key = uuid(gameKey);
        UUID requestId = id(request);
        if (games.game(token, key, requestId).mode() == PublicGameService.GameMode.COOP) {
            return ok(
                    coop.startGame(
                            token,
                            key,
                            revision(value, "expectedRev"),
                            positiveInt(value, "expectedPublishedVersionNo"),
                            revision(value, "expectedPlayRev"),
                            uuid(text(value, "requestKey")),
                            requestId));
        }
        return ok(
                games.startGame(
                        token,
                        key,
                        revision(value, "expectedRev"),
                        positiveInt(value, "expectedPublishedVersionNo"),
                        revision(value, "expectedPlayRev"),
                        uuid(text(value, "requestKey")),
                        requestId));
    }

    /** 현재 방장의 명시적 요청으로 초대 비밀을 한 번만 발급한다. */
    @PostMapping("/games/{gameKey}/invitations")
    public ResponseEntity<?> invitation(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(
                        coop.issueInvitation(
                                bearer(request),
                                uuid(gameKey),
                                revision(value, "expectedRev"),
                                uuid(text(value, "requestKey")),
                                id(request)));
    }

    /** 본문의 비밀을 검증하되 참가나 동의를 생성하지 않는다. */
    @PostMapping("/games/invitations/inspect")
    public ResponseEntity<?> inspectInvitation(HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("inviteKey", "inviteSecret"));
        return ok(
                coop.inspectInvitation(
                        bearer(request),
                        uuid(text(value, "inviteKey")),
                        text(value, "inviteSecret"),
                        id(request)));
    }

    /** 별도 회원의 명시적 고지 동의와 유효한 초대 비밀로만 참가한다. */
    @PostMapping("/games/invitations/join")
    public ResponseEntity<?> joinInvitation(HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(
                        request,
                        Set.of(
                                "inviteKey",
                                "inviteSecret",
                                "policyCode",
                                "noticeHash",
                                "requestKey"));
        return ok(
                coop.joinInvitation(
                        bearer(request),
                        uuid(text(value, "inviteKey")),
                        text(value, "inviteSecret"),
                        text(value, "policyCode"),
                        text(value, "noticeHash"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 대기실만 나가며 방장 이탈은 취소하고 개인 기록을 만들지 않는다. */
    @PostMapping("/games/{gameKey}/leave")
    public ResponseEntity<?> leave(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                coop.leave(
                        bearer(request),
                        uuid(gameKey),
                        revision(value, "expectedRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 현재 선택에 대한 본인의 명시적 준비 상태만 변경한다. */
    @PostMapping("/games/{gameKey}/ready")
    public ResponseEntity<?> ready(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(
                        request,
                        Set.of(
                                "expectedRev",
                                "expectedPublishedVersionNo",
                                "expectedPlayRev",
                                "ready",
                                "requestKey"));
        if (!value.get("ready").isBoolean()) throw invalid();
        return ok(
                coop.ready(
                        bearer(request),
                        uuid(gameKey),
                        revision(value, "expectedRev"),
                        positiveInt(value, "expectedPublishedVersionNo"),
                        revision(value, "expectedPlayRev"),
                        value.get("ready").booleanValue(),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 같은 사건의 최신 적격 선택으로 명시 갱신하며 기존 동의와 절대 만료는 유지한다. */
    @PostMapping("/games/{gameKey}/selection/refresh")
    public ResponseEntity<?> refreshSelection(
            @PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                coop.refreshSelection(
                        bearer(request),
                        uuid(gameKey),
                        revision(value, "expectedRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 노출 영수증과 감사가 확정된 뒤 고정 역할 자료만 반환한다. */
    @GetMapping("/games/{gameKey}/materials")
    public ResponseEntity<?> materials(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        return ok(games.materials(bearer(request), uuid(gameKey), id(request)));
    }

    /** 서버 고정 모드에 따라 본인 초안 또는 공동 초안과 고정 제안을 읽는다. */
    @GetMapping("/games/{gameKey}/report")
    public ResponseEntity<?> report(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        String token = bearer(request);
        UUID key = uuid(gameKey);
        UUID requestId = id(request);
        return ok(
                games.game(token, key, requestId).mode() == PublicGameService.GameMode.COOP
                        ? coopReports.report(token, key, requestId)
                        : games.report(token, key, requestId));
    }

    /** 원문 전체 교체의 의도와 요청 키를 고정한다. */
    @PatchMapping("/games/{gameKey}/report")
    public ResponseEntity<?> editReport(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(
                        request,
                        Set.of("expectedRev", "expectedDraftRev", "report", "requestKey"),
                        131072);
        String token = bearer(request);
        UUID key = uuid(gameKey);
        UUID requestId = id(request);
        if (games.game(token, key, requestId).mode() == PublicGameService.GameMode.COOP) {
            return ok(
                    coopReports.editReport(
                            token,
                            key,
                            revision(value, "expectedRev"),
                            revision(value, "expectedDraftRev"),
                            value.get("report"),
                            uuid(text(value, "requestKey")),
                            requestId));
        }
        return ok(
                games.editReport(
                        token,
                        key,
                        revision(value, "expectedRev"),
                        revision(value, "expectedDraftRev"),
                        value.get("report"),
                        uuid(text(value, "requestKey")),
                        requestId));
    }

    /** 저장된 공동 초안 A를 고정하고 제안자의 명시적 동의만 기록한다. */
    @PostMapping("/games/{gameKey}/report/proposals")
    public ResponseEntity<?> propose(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "expectedDraftRev", "requestKey"));
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(
                        coopReports.propose(
                                bearer(request),
                                uuid(gameKey),
                                revision(value, "expectedRev"),
                                revision(value, "expectedDraftRev"),
                                uuid(text(value, "requestKey")),
                                id(request)));
    }

    /** 특정 고정 제안에 대한 역할별 수락·거절·철회를 명시적으로 전달한다. */
    @PostMapping("/games/{gameKey}/report/proposals/{reportKey}/respond")
    public ResponseEntity<?> respond(
            @PathVariable String gameKey,
            @PathVariable String reportKey,
            HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "decision", "requestKey"));
        return ok(
                coopReports.respond(
                        bearer(request),
                        uuid(gameKey),
                        uuid(reportKey),
                        revision(value, "expectedRev"),
                        text(value, "decision"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** SOLO 본인 동의로 채점 작업을 명시 제출한다. */
    @PostMapping("/games/{gameKey}/report/submit")
    public ResponseEntity<?> submitReport(
            @PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "expectedDraftRev", "requestKey"));
        return ok(
                games.submitReport(
                        bearer(request),
                        uuid(gameKey),
                        revision(value, "expectedRev"),
                        revision(value, "expectedDraftRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 실제 종료·채점 저장 상태만 노출한다. */
    @GetMapping("/games/{gameKey}/result")
    public ResponseEntity<?> result(@PathVariable String gameKey, HttpServletRequest request) {
        noQuery(request);
        return ok(games.result(bearer(request), uuid(gameKey), id(request)));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> known(AuthException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status())
                .cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("code", failure.code(), "requestId", id(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure, HttpServletRequest request) {
        return ResponseEntity.status(503)
                .cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("code", "DEPENDENCY_UNAVAILABLE", "requestId", id(request)));
    }

    private JsonNode body(HttpServletRequest request, Set<String> fields) {
        return body(request, fields, 8192);
    }

    private JsonNode body(HttpServletRequest request, Set<String> fields, int limit) {
        String type = request.getContentType();
        if (type == null || !type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))
            throw invalid();
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw invalid();
        try {
            byte[] bytes = request.getInputStream().readNBytes(limit + 1);
            if (bytes.length > limit)
                throw new AuthException(413, "PAYLOAD_TOO_LARGE", "PAYLOAD_TOO_LARGE");
            String source =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            try (JsonParser parser = mapper.getFactory().createParser(source)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode value = mapper.readTree(parser);
                if (value == null
                        || !value.isObject()
                        || value.size() != fields.size()
                        || parser.nextToken() != null) throw invalid();
                value.fieldNames()
                        .forEachRemaining(
                                name -> {
                                    if (!fields.contains(name)) throw invalid();
                                });
                return value;
            }
        } catch (AuthException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static String bearer(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1 || !headers.getFirst().matches("Bearer [A-Za-z0-9_-]{43}"))
            throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
        return headers.getFirst().substring(7);
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        if (request.getQueryString() != null && request.getQueryString().isEmpty()) throw invalid();
        request.getParameterMap()
                .forEach(
                        (key, values) -> {
                            if (!allowed.contains(key) || values == null || values.length != 1)
                                throw invalid();
                        });
    }

    private static String text(JsonNode value, String field) {
        JsonNode part = value.get(field);
        if (part == null || !part.isTextual()) throw invalid();
        return part.textValue();
    }

    private static int positiveInt(JsonNode value, String field) {
        JsonNode part = value.get(field);
        if (part == null
                || !part.isIntegralNumber()
                || !part.canConvertToInt()
                || part.intValue() < 1) throw invalid();
        return part.intValue();
    }

    private static int positiveInt(String raw) {
        if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid();
        return Integer.parseInt(raw);
    }

    private static long revision(JsonNode value, String field) {
        String raw = text(value, field);
        if (!raw.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static UUID uuid(String raw) {
        if (raw == null
                || !raw.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(raw);
    }

    private static UUID id(HttpServletRequest request) {
        return RequestAuditKernel.requestId(request);
    }

    private static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }
}
