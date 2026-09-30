package com.reasoning.admin.story.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.admin.auth.audit.AccessHistoryFilter;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.CurrentSession;
import com.reasoning.common.auth.service.AuthException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 사건 API의 세션 확인, 엄격 JSON 검증과 캐시 금지 응답을 제공한다. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryHttpSupport {
    private final AdminSessionAdapter sessions;
    private final ObjectMapper mapper;

    /** 현재 관리자 세션 조회기와 기존 JSON 매퍼를 주입한다. */
    public StoryHttpSupport(AdminSessionAdapter sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    /**
     * 현재 관리자 세션을 반환한다.
     *
     * @param request 세션 쿠키를 가진 현재 HTTP 요청
     * @return 현재 일반 관리자 세션
     * @throws AuthException 세션이 없으면 AUTH_REQUIRED(401)
     */
    public CurrentSession currentSession(HttpServletRequest request) {
        return sessions.current(request)
                .orElseThrow(() -> AuthException.unauthorized("AUTH_REQUIRED"));
    }

    /**
     * 접근 이력 필터의 요청 ID를 사용하며 속성이 없으면 새 UUID를 발급한다.
     *
     * @param request 서버 요청 ID 속성을 조회할 요청
     * @return 기존 요청 ID 또는 새 UUID
     */
    public static UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(AccessHistoryFilter.REQUEST_ID_ATTRIBUTE);
        return value instanceof UUID id ? id : UUID.randomUUID();
    }

    /**
     * 바이트 상한을 먼저 확인하고 UTF-8로만 해독한 JSON 객체를 검증한다.
     *
     * @param request 원본 요청 본문과 JSON 콘텐츠 형식을 담은 요청
     * @param maxBytes 경로별 원본 본문 상한인 8192 또는 524288바이트
     * @param fields 정확히 포함해야 하는 최상위 필드 이름
     * @return 중복 필드와 뒤따르는 토큰이 없는 JSON 객체
     * @throws AuthException 바이트 상한 초과 시 PAYLOAD_TOO_LARGE, 잘못된 인코딩·JSON·필드 시 INVALID_REQUEST, 본문 읽기
     *     실패 시 STORY_UNAVAILABLE
     */
    public JsonNode body(HttpServletRequest request, int maxBytes, Set<String> fields) {
        return body(request, maxBytes, fields, Map.of());
    }

    /**
     * 원고 자원의 정규화 전 JSON 필드 바이트 한도를 함께 검사한다.
     *
     * @param request 원본 UTF-8 JSON 본문을 가진 요청
     * @param maxBytes 원본 본문 전체의 바이트 상한
     * @param fields 정확히 포함해야 하는 최상위 필드 이름
     * @param jsonLimits item/changes 내부의 필드별 원본 JSON 값 바이트 상한; 빈 맵은 추가 제한이 없다
     * @return 엄격한 형식과 전체·하위 값 크기 검증을 통과한 JSON 객체
     * @throws AuthException 전체 상한 초과 시 413, 형식 오류 시 400, 하위 값 상한 초과 시 INVALID_INPUT(422), 읽기 실패 시
     *     503
     */
    public JsonNode body(
            HttpServletRequest request,
            int maxBytes,
            Set<String> fields,
            Map<String, Integer> jsonLimits) {
        String contentType = request.getContentType();
        if (contentType == null
                || !contentType.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))
            throw AuthException.badRequest("INVALID_REQUEST");

        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding))
            throw AuthException.badRequest("INVALID_REQUEST");

        try {
            byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes)
                throw new AuthException(413, "PAYLOAD_TOO_LARGE", "PAYLOAD_TOO_LARGE");

            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            try (JsonParser parser = mapper.getFactory().createParser(json)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode value = mapper.readTree(parser);
                if (value == null
                        || !value.isObject()
                        || value.size() != fields.size()
                        || parser.nextToken() != null)
                    throw AuthException.badRequest("INVALID_REQUEST");

                for (String name : fields)
                    if (!value.has(name)) throw AuthException.badRequest("INVALID_REQUEST");

                if (!jsonLimits.isEmpty()) checkJsonFieldBytes(bytes, jsonLimits);
                return value;
            }
        } catch (CharacterCodingException | JsonProcessingException exception) {
            throw AuthException.badRequest("INVALID_REQUEST");
        } catch (IOException exception) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    /** 원본 UTF-8 파서의 바이트 위치로 item/changes의 지정된 하위 값 크기를 잰다. */
    private void checkJsonFieldBytes(byte[] bytes, Map<String, Integer> limits) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken token = parser.nextToken();
                if (!("item".equals(name) || "changes".equals(name))
                        || token != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    continue;
                }

                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    parser.nextToken();
                    long start = parser.currentTokenLocation().getByteOffset();
                    parser.skipChildren();
                    // 문자열의 지연 해독까지 끝내 닫는 따옴표 뒤 위치를 얻는다.
                    if (parser.currentToken() == JsonToken.VALUE_STRING) parser.getText();

                    long end = parser.currentLocation().getByteOffset();
                    Integer limit = limits.get(field);
                    if (limit != null && end - start > limit)
                        throw AuthException.unprocessable("INVALID_INPUT");
                }
            }
        }
    }

    /**
     * 지정한 필수 문자열 필드를 반환한다.
     *
     * @param body 필드를 조회할 JSON 객체
     * @param field 필수 문자열 필드 이름
     * @return 정규화하지 않은 원본 문자열
     * @throws AuthException 누락·null·다른 JSON 자료형이면 INVALID_REQUEST(400)
     */
    public static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) throw AuthException.badRequest("INVALID_REQUEST");

        return value.textValue();
    }

    /**
     * 보호 응답을 캐시에 저장하지 않는 200 응답으로 반환한다.
     *
     * @param value 응답 본문
     * @return no-store가 지정된 200 응답
     */
    public static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    /**
     * 새 자원의 보호 응답을 캐시에 저장하지 않는 201 응답으로 반환한다.
     *
     * @param value 응답 본문
     * @return no-store가 지정된 201 응답
     */
    public static ResponseEntity<?> created(Object value) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(value);
    }

    /**
     * 필수 문자열 필드에서 정규 길이 UUID를 검사한다.
     *
     * @param body 필드를 조회할 JSON 객체
     * @param field 필수 UUID 문자열 필드 이름
     * @param v4 true이면 버전 4 의도 키만 허용한다
     * @return 검증한 UUID
     * @throws AuthException 문자열·UUID 형식이 잘못되면 INVALID_REQUEST(400)
     */
    public static UUID uuid(JsonNode body, String field, boolean v4) {
        return uuid(text(body, field), v4);
    }

    /**
     * 정규 UUID 문자열을 임의 관대한 파싱 없이 검사한다.
     *
     * @param raw 정규 길이이며 RFC 변형 비트를 가진 UUID 문자열
     * @param v4 true이면 버전 4 의도 키만 허용한다
     * @return 검증한 UUID
     * @throws AuthException UUID 형식이 잘못되면 INVALID_REQUEST(400)
     */
    public static UUID uuid(String raw, boolean v4) {
        if (!raw.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-"
                        + (v4 ? "4" : "[0-9a-f]")
                        + "[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw AuthException.badRequest("INVALID_REQUEST");

        return UUID.fromString(raw);
    }
}
