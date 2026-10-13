package com.reasoning.web.member.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.game.service.GameQualificationService;
import com.reasoning.common.story.service.PlaytestInvestigationService;
import com.reasoning.common.story.service.PlaytestInvitationService;
import com.reasoning.common.story.service.PlaytestReportService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
import java.util.Set;
import java.util.UUID;

/** Bearer 자격을 거래 안에서 재검증하는 초대·동의 전용 HTTP 경계다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api")
public final class PlaytestMemberController {
    private final PlaytestInvitationService invitations;
    private final PlaytestInvestigationService investigation;
    private final PlaytestReportService reports;
    private final GameQualificationService qualifications;
    private final ObjectMapper mapper;

    public PlaytestMemberController(
            PlaytestInvitationService invitations,
            PlaytestInvestigationService investigation,
            PlaytestReportService reports,
            GameQualificationService qualifications,
            ObjectMapper mapper) {
        this.invitations = invitations;
        this.investigation = investigation;
        this.reports = reports;
        this.qualifications = qualifications;
        this.mapper = mapper;
    }

    /** 인증된 자신의 공개 참가 키만 반환한다. */
    @GetMapping("/playtests/identity")
    public ResponseEntity<?> identity(HttpServletRequest request) {
        noQuery(request);
        return ok(invitations.getMemberIdentity(MemberSecurityConfig.bearer(request), id(request)));
    }

    /** 양의 ID 커서와 1~100 페이지 크기만 허용하며 상대 식별자는 노출하지 않는다. */
    @GetMapping("/playtests/invitations")
    public ResponseEntity<?> list(HttpServletRequest request) {
        query(request, Set.of("cursor", "size"));
        String cursor = request.getParameter("cursor");
        String size = request.getParameter("size");
        Long before = cursor == null ? null : positiveLong(cursor);
        int count = size == null ? 20 : pageSize(size);
        return ok(
                invitations.getMemberInvitationList(
                        MemberSecurityConfig.bearer(request), before, count, id(request)));
    }

    /** 지정된 본인의 유효한 초대 상태만 조회한다. */
    @GetMapping("/playtests/{testKey}")
    public ResponseEntity<?> state(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        return ok(
                invitations.getMemberInvitationDetail(
                        MemberSecurityConfig.bearer(request), uuid(testKey), id(request)));
    }

    /** PLAYTEST 정책이 현재 효력을 가지는지 재확인하고 해당 초대의 고지를 읽는다. */
    @GetMapping("/playtests/{testKey}/policy-notice")
    public ResponseEntity<?> notice(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        return ok(
                invitations.getConsentNotice(
                        MemberSecurityConfig.bearer(request), uuid(testKey), id(request)));
    }

    /** 별도 비노출 선언을 실제 회원 수락 거래에 전달한다. */
    @PostMapping("/playtests/{testKey}/accept")
    public ResponseEntity<?> accept(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode body =
                body(
                        request,
                        Set.of(
                                "expectedRev",
                                "inviteGen",
                                "blindDeclared",
                                "policyCode",
                                "noticeHash",
                                "requestKey"));
        JsonNode declared = body.get("blindDeclared");
        if (!declared.isBoolean()) throw invalid();
        int generation = positiveInt(body, "inviteGen");
        long revision = revision(body, "expectedRev");
        UUID key = uuid(testKey);
        UUID requestKey = uuid(text(body, "requestKey"));
        return ok(
                invitations.acceptInvitation(
                        MemberSecurityConfig.bearer(request),
                        key,
                        generation,
                        revision,
                        declared.booleanValue(),
                        text(body, "policyCode"),
                        text(body, "noticeHash"),
                        requestKey,
                        id(request)));
    }

    /** 자신의 대기 의사를 명시적으로 설정하거나 철회하며 전체 의도를 영수증에 결속한다. */
    @PostMapping("/playtests/{testKey}/ready")
    public ResponseEntity<?> ready(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "ready", "requestKey"));
        if (!value.get("ready").isBoolean()) throw invalid();
        return ok(
                investigation.ready(
                        MemberSecurityConfig.bearer(request), uuid(testKey),
                        revision(value, "expectedRev"), value.get("ready").booleanValue(),
                        uuid(text(value, "requestKey")), id(request)));
    }

    /** 쌍의 준비 상태와 최근 접속을 확인한 뒤 단 한 번 역할을 배정한다. */
    @PostMapping("/playtests/{testKey}/start")
    public ResponseEntity<?> start(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                investigation.start(
                        MemberSecurityConfig.bearer(request),
                        uuid(testKey),
                        revision(value, "expectedRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 명시적 하트비트만 자신의 서버 접속 시각을 갱신한다. */
    @PostMapping("/playtests/{testKey}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        body(request, Set.of());
        investigation.heartbeat(MemberSecurityConfig.bearer(request), uuid(testKey), id(request));
        return ResponseEntity.noContent().build();
    }

    /** 자신의 확정된 역할로 허용된 고정 사본 자료만 읽는다. */
    @GetMapping("/playtests/{testKey}/materials")
    public ResponseEntity<?> materials(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        return ok(
                investigation.materials(
                        MemberSecurityConfig.bearer(request), uuid(testKey), id(request)));
    }

    /** 자신의 영속 힌트 개봉만 허용하고 다른 참가자의 사용량과 본문은 노출하지 않는다. */
    @PostMapping("/playtests/{testKey}/hints/{level}/open")
    public ResponseEntity<?> openHint(
            @PathVariable String testKey, @PathVariable String level, HttpServletRequest request) {
        noQuery(request);
        if (!level.matches("[1-3]")) throw invalid();
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                investigation.openHint(
                        MemberSecurityConfig.bearer(request),
                        uuid(testKey),
                        Integer.parseInt(level),
                        revision(value, "expectedRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 공동 초안과 현재 제안을 참가자에게만 반환한다. */
    @GetMapping("/playtests/{testKey}/report")
    public ResponseEntity<?> report(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        return ok(reports.report(MemberSecurityConfig.bearer(request), uuid(testKey), id(request)));
    }

    /** 전체 REPORT-1 본문을 엄격히 읽어 공동 초안을 수정한다. */
    @PatchMapping("/playtests/{testKey}/report")
    public ResponseEntity<?> editReport(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(
                        request,
                        Set.of("expectedRev", "expectedDraftRev", "report", "requestKey"),
                        524288);
        return ok(
                reports.edit(
                        MemberSecurityConfig.bearer(request),
                        uuid(testKey),
                        revision(value, "expectedRev"),
                        revision(value, "expectedDraftRev"),
                        value.get("report"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 현재 공동 초안을 암호화된 불변 제안으로 고정한다. */
    @PostMapping("/playtests/{testKey}/report/proposals")
    public ResponseEntity<?> propose(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(request, Set.of("expectedRev", "expectedDraftRev", "requestKey"), 524288);
        return ResponseEntity.status(201)
                .body(
                        reports.propose(
                                MemberSecurityConfig.bearer(request),
                                uuid(testKey),
                                revision(value, "expectedRev"),
                                revision(value, "expectedDraftRev"),
                                uuid(text(value, "requestKey")),
                                id(request)));
    }

    /** ACCEPT는 실제 판정 소비·복구·정산 경로가 연결되기 전까지 차단한다. */
    @PostMapping("/playtests/{testKey}/report/proposals/{reportKey}/respond")
    public ResponseEntity<?> respond(
            @PathVariable String testKey,
            @PathVariable String reportKey,
            HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "decision", "requestKey"), 524288);
        return ok(
                reports.respond(
                        MemberSecurityConfig.bearer(request),
                        uuid(testKey),
                        uuid(reportKey),
                        revision(value, "expectedRev"),
                        text(value, "decision"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 대기 중인 제출이 없는 플레이만 명시적으로 포기한다. */
    @PostMapping("/playtests/{testKey}/forfeit")
    public ResponseEntity<?> forfeit(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                reports.forfeit(
                        MemberSecurityConfig.bearer(request),
                        uuid(testKey),
                        revision(value, "expectedRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 종료된 자신의 피드백 허용 창을 검사해 최소 결과만 반환한다. */
    @GetMapping("/playtests/{testKey}/result")
    public ResponseEntity<?> result(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        return ok(reports.result(MemberSecurityConfig.bearer(request), uuid(testKey), id(request)));
    }

    /** 자신의 첫 피드백만 암호화 저장한다. */
    @PostMapping("/playtests/{testKey}/feedback")
    public ResponseEntity<?> feedback(@PathVariable String testKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("requestKey", "feedback"), 524288);
        return ok(
                reports.feedback(
                        MemberSecurityConfig.bearer(request),
                        uuid(testKey),
                        value.get("feedback"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 지정한 단독 검수 방의 실제 참가 후보에게 현재 효력 고지만 반환한다. */
    @GetMapping("/qualifications/{roomKey}/notice")
    public ResponseEntity<?> qualificationNotice(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        return ok(
                qualifications.getAcceptanceNotice(
                        MemberSecurityConfig.bearer(request), uuid(roomKey), id(request)));
    }

    /** 현재 회원의 별도 명시 동의만 수락하며 게임 준비나 진행은 시작하지 않는다. */
    @PostMapping("/qualifications/{roomKey}/accept")
    public ResponseEntity<?> qualificationAccept(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(request, Set.of("blindDeclared", "policyCode", "noticeHash", "requestKey"));
        JsonNode declared = value.get("blindDeclared");
        if (!declared.isBoolean()) throw invalid();
        String code = text(value, "policyCode");
        String hash = text(value, "noticeHash");
        if (!code.matches("[A-Za-z0-9_:-]{1,80}") || !hash.matches("[0-9a-f]{64}")) throw invalid();
        return ok(
                qualifications.acceptQualification(
                        MemberSecurityConfig.bearer(request),
                        uuid(roomKey),
                        declared.booleanValue(),
                        code,
                        hash,
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 수락한 단독 회원만 현재 방 수정번호로 명시 시작한다. 동일 요청 키는 원래 시작만 재전송한다. */
    @PostMapping("/qualifications/{roomKey}/start")
    public ResponseEntity<?> qualificationStart(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "requestKey"));
        return ok(
                qualifications.startQualification(
                        MemberSecurityConfig.bearer(request),
                        uuid(roomKey),
                        revision(value, "expectedRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 서버 사본의 두 역할·공통 자료만 반환하며 노출 이력과 업무 감사를 먼저 원자적으로 확정한다. */
    @GetMapping("/qualifications/{roomKey}/materials")
    public ResponseEntity<?> qualificationMaterials(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        return ok(
                qualifications.getMaterials(
                        MemberSecurityConfig.bearer(request), uuid(roomKey), id(request)));
    }

    /** 현재 검수 참가자의 저장 초안과 독립 수정번호만 반환한다. */
    @GetMapping("/qualifications/{roomKey}/report")
    public ResponseEntity<?> qualificationReport(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        return ok(
                qualifications.getReport(
                        MemberSecurityConfig.bearer(request), uuid(roomKey), id(request)));
    }

    /** 명시적으로 저장한 REPORT-1만 CAS 처리하며 무변경 초안은 수정번호를 보존한다. */
    @PatchMapping("/qualifications/{roomKey}/report")
    public ResponseEntity<?> qualificationEditReport(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value =
                body(
                        request,
                        Set.of("expectedRev", "expectedDraftRev", "report", "requestKey"),
                        131072);
        return ok(
                qualifications.editReport(
                        MemberSecurityConfig.bearer(request),
                        uuid(roomKey),
                        revision(value, "expectedRev"),
                        revision(value, "expectedDraftRev"),
                        value.get("report"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 본인이 확인한 저장본만 불변 검수 보고서와 실제 GAME 작업으로 접수한다. */
    @PostMapping("/qualifications/{roomKey}/report/submit")
    public ResponseEntity<?> qualificationSubmitReport(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("expectedRev", "expectedDraftRev", "requestKey"));
        return ok(
                qualifications.submitReport(
                        MemberSecurityConfig.bearer(request),
                        uuid(roomKey),
                        revision(value, "expectedRev"),
                        revision(value, "expectedDraftRev"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 검수 종료 후 허용된 24시간 안에서 실제 저장 결과만 반환한다. */
    @GetMapping("/qualifications/{roomKey}/result")
    public ResponseEntity<?> qualificationResult(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        return ok(
                qualifications.getResult(
                        MemberSecurityConfig.bearer(request), uuid(roomKey), id(request)));
    }

    /** 검수 참가자의 명시적 피드백을 현재 인가·감사·영수증과 함께 접수한다. */
    @PostMapping("/qualifications/{roomKey}/feedback")
    public ResponseEntity<?> qualificationFeedback(
            @PathVariable String roomKey, HttpServletRequest request) {
        noQuery(request);
        JsonNode value = body(request, Set.of("feedback", "requestKey"), 32768);
        return ok(
                qualifications.submitFeedback(
                        MemberSecurityConfig.bearer(request),
                        uuid(roomKey),
                        text(value, "feedback"),
                        uuid(text(value, "requestKey")),
                        id(request)));
    }

    /** 고정 코드만 반환하고 예외·토큰·입력 본문을 반사하지 않는다. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> known(AuthException failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status())
                .body(MemberSecurityConfig.errorBody(request, failure.code()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure, HttpServletRequest request) {
        return ResponseEntity.status(503)
                .body(MemberSecurityConfig.errorBody(request, "PLAYTEST_UNAVAILABLE"));
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

    private static int pageSize(String value) {
        long parsed = positiveLong(value);
        if (parsed > 100) throw invalid();
        return (int) parsed;
    }

    private static int positiveInt(JsonNode body, String field) {
        long value = nonnegativeLong(body, field);
        if (value < 1 || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    private static long nonnegativeLong(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0) throw invalid();
        return value.longValue();
    }

    /** 수정번호는 0 또는 선행 영이 없는 long 범위의 십진 문자열만 받는다. */
    private static long revision(JsonNode body, String field) {
        String value = text(body, field);
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static long positiveLong(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static UUID uuid(String value) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value);
    }

    private static UUID id(HttpServletRequest request) {
        return RequestAuditKernel.requestId(request);
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    private static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok(value);
    }
}
