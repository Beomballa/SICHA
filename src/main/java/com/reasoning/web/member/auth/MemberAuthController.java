package com.reasoning.web.member.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberAuthService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** 승인된 LOCAL 회원 인증만 연결하며 원문 비밀 응답을 캐시·쿠키로 복제하지 않는다. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MemberAuthController {
    private static final ObjectMapper JSON =
            new ObjectMapper(
                    JsonFactory.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .streamReadConstraints(
                                    StreamReadConstraints.builder()
                                            .maxNestingDepth(4)
                                            .maxStringLength(16384)
                                            .build())
                            .build());
    private final MemberAuthService service;

    public MemberAuthController(MemberAuthService service) {
        this.service = service;
    }

    /** 출처는 신뢰한 connector 주소만 쓰고 사용자 제공 forwarded 헤더는 읽지 않는다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/email/signup")
    public ResponseEntity<?> signup(HttpServletRequest request) {
        JsonNode input = body(request, "email");
        return response(
                202,
                service.startSignup(text(input, "email"), request.getRemoteAddr(), id(request)));
    }

    /** flow binder와 이메일 코드를 같은 폐쇄형 POST에서 검증한다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/email/verify")
    public ResponseEntity<?> verify(HttpServletRequest request) {
        JsonNode input = body(request, "flowKey", "flowBinder", "code");
        return response(
                200,
                service.verifyEmail(
                        uuid(input, "flowKey"),
                        text(input, "flowBinder"),
                        text(input, "code"),
                        request.getRemoteAddr(),
                        id(request)));
    }

    /** 고정 고지·검증 flow를 단회 소비한 서버 결과만 인증 응답으로 투영한다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/email/signup/complete")
    public ResponseEntity<?> complete(HttpServletRequest request) {
        JsonNode input =
                body(
                        request,
                        "flowKey",
                        "flowBinder",
                        "password",
                        "nickname",
                        "policyCode",
                        "noticeHash",
                        "requestKey");
        var issued =
                service.completeSignup(
                        uuid(input, "flowKey"),
                        text(input, "flowBinder"),
                        text(input, "password"),
                        text(input, "nickname"),
                        text(input, "policyCode"),
                        text(input, "noticeHash"),
                        uuid(input, "requestKey"),
                        id(request));
        MemberSecurityConfig.principal(issued.principal());
        return response(201, issued.tokens());
    }

    /** 복구 요청만으로 계정 존재나 세션 회수 상태를 공개하지 않는다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/password/reset")
    public ResponseEntity<?> reset(HttpServletRequest request) {
        var input = body(request, "email");
        return response(
                202,
                service.startReset(text(input, "email"), request.getRemoteAddr(), id(request)));
    }

    /** 메일로 확인한 복구를 단회 소비하며 자동 로그인 토큰은 반환하지 않는다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/password/reset/complete")
    public ResponseEntity<?> completeReset(HttpServletRequest request) {
        var input = body(request, "flowKey", "flowBinder", "newPassword", "requestKey");
        return response(
                200,
                service.completeReset(
                        uuid(input, "flowKey"),
                        text(input, "flowBinder"),
                        text(input, "newPassword"),
                        uuid(input, "requestKey"),
                        id(request)));
    }

    /** 정상 LOCAL 인증 결과만 접근 이력 주체로 채택한다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/login/local")
    public ResponseEntity<?> login(HttpServletRequest request) {
        JsonNode input = body(request, "email", "password");
        var issued =
                service.login(
                        text(input, "email"),
                        text(input, "password"),
                        request.getRemoteAddr(),
                        id(request));
        MemberSecurityConfig.principal(issued.principal());
        return response(200, issued.tokens());
    }

    /** access 없이 refresh 자체를 검증하며 새 토큰의 원문 영수증을 보관하지 않는다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        JsonNode input = body(request, "refreshToken");
        var issued = service.refresh(text(input, "refreshToken"), id(request));
        MemberSecurityConfig.principal(issued.principal());
        return response(200, issued.tokens());
    }

    /** 현재 access 자격을 서비스 거래에서 다시 확인하고 기본 허용 개인정보만 반환한다. */
    @GetMapping(MemberSecurityConfig.AUTH + "/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        return response(200, service.getMe(MemberSecurityConfig.bearer(request), id(request)));
    }

    /** 현재 family의 LOCAL 수단만 목적별 최근 본인 확인 증명으로 발급한다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/reauth/local")
    public ResponseEntity<?> reauthenticate(HttpServletRequest request) {
        var input = body(request, "purpose", "identityKey", "password");
        return response(
                200,
                service.reauthenticate(
                        MemberSecurityConfig.bearer(request),
                        text(input, "purpose"),
                        uuid(input, "identityKey"),
                        text(input, "password"),
                        request.getRemoteAddr(),
                        id(request)));
    }

    /** 탈퇴 증명을 단회 소비해 접근 회수와 내구 접수만 확인하며 전체 파기 완료를 뜻하지 않는다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/account/delete")
    public ResponseEntity<?> deleteAccount(HttpServletRequest request) {
        var input = body(request, "reauthGrant", "requestKey");
        return response(
                202,
                service.deleteAccount(
                        MemberSecurityConfig.bearer(request),
                        text(input, "reauthGrant"),
                        uuid(input, "requestKey"),
                        id(request)));
    }

    /** 별도 영수증 비밀로만 진행 상태를 읽고 회원 인증 권위를 부여하지 않는다. */
    @PostMapping(MemberSecurityConfig.ERASE_STATUS)
    public ResponseEntity<?> erasureStatus(HttpServletRequest request) {
        var input = body(request, "eraseKey", "receiptToken");
        return response(
                200,
                service.status(
                        uuid(input, "eraseKey"),
                        text(input, "receiptToken"),
                        id(request),
                        request.getRemoteAddr()));
    }

    /** 현재 family에 결속된 PASSWORD_CHANGE 증명을 단회 소비하고 새 로그인을 요구한다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/password/change")
    public ResponseEntity<?> changePassword(HttpServletRequest request) {
        var input = body(request, "reauthGrant", "newPassword", "requestKey");
        return response(
                200,
                service.changePassword(
                        MemberSecurityConfig.bearer(request),
                        text(input, "reauthGrant"),
                        text(input, "newPassword"),
                        uuid(input, "requestKey"),
                        id(request)));
    }

    /** LOGOUT_ALL 증명과 현재 자격을 함께 확인하고 모든 family를 원자적으로 회수한다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/logout-all")
    public ResponseEntity<?> logoutAll(HttpServletRequest request) {
        var input = body(request, "reauthGrant", "requestKey");
        return response(
                200,
                service.logoutAll(
                        MemberSecurityConfig.bearer(request),
                        text(input, "reauthGrant"),
                        uuid(input, "requestKey"),
                        id(request)));
    }

    /** 고지 효력과 무관한 보안 회수를 실제 현재 access에 한정한다. */
    @PostMapping(MemberSecurityConfig.AUTH + "/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        JsonNode input = body(request, "requestKey");
        return response(
                200,
                service.logout(
                        MemberSecurityConfig.bearer(request),
                        uuid(input, "requestKey"),
                        id(request)));
    }

    /** 운영 내부 설정 대신 현재 준비된 MEMBER_AUTH 고지의 안전 투영만 공개한다. */
    @GetMapping(MemberSecurityConfig.NOTICE)
    public ResponseEntity<?> notice(HttpServletRequest request) {
        return response(200, service.getNotice(id(request)));
    }

    /** 입력·업무 실패의 원문을 반사하지 않고 실제 고정 상태를 유지한다. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> failure(AuthException failure, HttpServletRequest request) {
        var response = ResponseEntity.status(failure.status());
        if (failure instanceof MemberAuthService.RateLimited limited)
            response.header("Retry-After", Long.toString(limited.retryAfterSeconds()));
        return response.body(MemberSecurityConfig.errorBody(request, failure.code()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unavailable(Exception failure, HttpServletRequest request) {
        return response(503, MemberSecurityConfig.errorBody(request, "AUTH_UNAVAILABLE"));
    }

    /** UTF-8 JSON을 읽는 중16KiB로 제한하고 중복·후행·누락·미지 키를 거절한다. */
    static JsonNode body(HttpServletRequest request, String... fields) {
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            if (!type.getType().equals("application")
                    || !type.getSubtype().equals("json")
                    || type.getCharset() != null
                            && !type.getCharset().equals(StandardCharsets.UTF_8)) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384)
                throw new AuthException(413, "PAYLOAD_TOO_LARGE", "PAYLOAD_TOO_LARGE");
            String source =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            try (var parser = JSON.createParser(source)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null
                        || !node.isObject()
                        || node.size() != fields.length
                        || parser.nextToken() != null) throw invalid();
                Set<String> keys = Set.of(fields);
                node.fieldNames()
                        .forEachRemaining(
                                key -> {
                                    if (!keys.contains(key)) throw invalid();
                                });
                for (String field : fields) text(node, field);
                return node;
            }
        } catch (AuthException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    /** 공개 요청 키는 canonical 소문자 UUID v4만 허용한다. */
    static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value);
    }

    static UUID id(HttpServletRequest request) {
        return RequestAuditKernel.requestId(request);
    }

    private static AuthException invalid() {
        return AuthException.badRequest("INVALID_REQUEST");
    }

    private static ResponseEntity<?> response(int status, Object value) {
        return ResponseEntity.status(status).body(value);
    }
}
