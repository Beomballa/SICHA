package com.reasoning.web.member.auth;

import com.reasoning.common.auth.service.AuthException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collections;
import java.util.UUID;

/** 명시적으로 설치한 HTTPS 출처만 브라우저 회원 인증에 허용한다. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class MemberWebPolicy {
    static final String BASE = "/api/member/browser-auth";
    static final String WEB = "X-Sicha-Player-Web";
    static final String EPOCH = "X-Sicha-Player-Web-Epoch";
    static final String COOKIE = "__Secure-sicha_player_refresh";
    private static final String UUID4 =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private final boolean enabled;
    private final URI origin;

    /** enabled가 참이면 경로·인증정보 없는 canonical HTTPS origin이 필수다. */
    public MemberWebPolicy(
            @Value("${app.member-web.enabled:false}") boolean enabled,
            @Value("${app.member-web.origin:}") String configured) {
        this.enabled = enabled;
        URI parsed = null;
        if (enabled) {
            try {
                parsed = URI.create(configured);
                String host = parsed.getHost();
                int port = parsed.getPort();
                String canonical = "https://" + host + (port == -1 ? "" : ":" + port);
                if (!"https".equals(parsed.getScheme())
                        || host == null
                        || !host.equals(host.toLowerCase(java.util.Locale.ROOT))
                        || parsed.getRawUserInfo() != null
                        || parsed.getRawQuery() != null
                        || parsed.getRawFragment() != null
                        || !parsed.getRawPath().isEmpty()
                        || port == 443
                        || port == 0
                        || port < -1
                        || port > 65535
                        || !canonical.equals(configured)) throw new IllegalArgumentException();
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Invalid member web origin");
            }
        }
        origin = parsed;
    }

    static boolean route(HttpServletRequest request) {
        return cookieRoute(request) || publicRoute(request);
    }

    /** 검수 고지와 수락은 인증 쿠키가 아닌 Bearer에만 열린 정확한 경로다. */
    static boolean qualificationRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod())
                        && path.matches(
                                MemberSecurityConfig.QUALIFICATIONS + "/" + UUID4 + "/notice")
                || "GET".equals(request.getMethod())
                        && path.matches(
                                MemberSecurityConfig.QUALIFICATIONS + "/" + UUID4 + "/materials")
                || "GET".equals(request.getMethod())
                        && path.matches(
                                MemberSecurityConfig.QUALIFICATIONS
                                        + "/"
                                        + UUID4
                                        + "/(?:report|result)")
                || "PATCH".equals(request.getMethod())
                        && path.matches(
                                MemberSecurityConfig.QUALIFICATIONS + "/" + UUID4 + "/report")
                || "POST".equals(request.getMethod())
                        && path.matches(
                                MemberSecurityConfig.QUALIFICATIONS
                                        + "/"
                                        + UUID4
                                        + "/(?:report/submit|feedback)")
                || "POST".equals(request.getMethod())
                        && path.matches(
                                MemberSecurityConfig.QUALIFICATIONS + "/" + UUID4 + "/accept")
                || "POST".equals(request.getMethod())
                        && path.matches(
                                MemberSecurityConfig.QUALIFICATIONS + "/" + UUID4 + "/start");
    }

    /** 공개 게임도 익명 공개 경로가 아닌 현재 회원 Bearer 전용 업무다. */
    static boolean gameRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod())
                        && (path.equals("/api/stories")
                                || path.matches("/api/stories/[A-Z0-9_]{1,40}")
                                || path.equals("/api/member/privacy/policies/friendly")
                                || path.equals("/api/games/current")
                                || path.equals("/api/member/records")
                                || path.matches(
                                        "/api/member/records/"
                                                + UUID4
                                                + "(?:/reports/"
                                                + UUID4
                                                + ")?")
                                || path.matches(
                                        "/api/games/" + UUID4 + "(?:/materials|/report|/result)?"))
                || "PATCH".equals(request.getMethod())
                        && path.matches("/api/games/" + UUID4 + "/report")
                || "POST".equals(request.getMethod())
                        && (path.equals("/api/games")
                                || path.equals("/api/games/invitations/inspect")
                                || path.equals("/api/games/invitations/join")
                                || path.matches(
                                        "/api/games/"
                                                + UUID4
                                                + "(?:/start|/report/submit|/report/proposals(?:/"
                                                + UUID4
                                                + "/respond)?|/heartbeat|/forfeit|/invitations|/leave|/ready|/selection/refresh|/hints/[1-3]/open|/clues/[A-Z0-9_]{1,32}/read)"));
    }

    /** 목록의 제한된 커서·크기만 허용하고 중복 키와 임의 query를 거절한다. */
    static boolean gameListQuery(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())
                || !(request.getRequestURI().equals("/api/stories")
                        || request.getRequestURI().equals("/api/member/records"))) return false;
        if (request.getQueryString() == null
                || request.getQueryString().isEmpty()
                || request.getQueryString().length() > 4096) return false;
        for (var entry : request.getParameterMap().entrySet()) {
            String[] values = entry.getValue();
            if (values.length != 1) return false;
            if (entry.getKey().equals("cursor")) {
                if (values[0].isEmpty() || values[0].length() > 2048) return false;
            } else if (entry.getKey().equals("size")) {
                if (!values[0].matches("[1-9][0-9]{0,2}") || Integer.parseInt(values[0]) > 100)
                    return false;
            } else return false;
        }
        return !request.getParameterMap().isEmpty();
    }

    /** 쿠키를 새로 발급하거나 회수하는 exact POST만 인증 경로로 분류한다. */
    static boolean cookieRoute(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && switch (request.getRequestURI()) {
                    case BASE + "/login/local",
                            BASE + "/refresh",
                            BASE + "/logout",
                            BASE + "/email/signup/complete" ->
                            true;
                    default -> false;
                };
    }

    /** 고지와 확인 흐름은 기존 refresh 증명을 업무 자격으로 받지 않는다. */
    static boolean publicRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod()) && path.equals(BASE + "/notice")
                || "POST".equals(request.getMethod())
                        && (path.equals(BASE + "/email/signup")
                                || path.equals(BASE + "/password/reset")
                                || path.equals(BASE + "/password/reset/complete")
                                || path.equals(MemberSecurityConfig.ERASE_STATUS)
                                || path.equals(BASE + "/email/verify"));
    }

    /** raw 헤더 개수를 유지하여 중복·합쳐진 값을 거절한다. */
    static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw forbidden();
        return values.getFirst();
    }

    /** connector의 실제 authority와 출처·fetch metadata를 검사하며 forwarded 값은 읽지 않는다. */
    void admit(HttpServletRequest request, boolean cookieAuth) {
        if (!enabled
                || !request.isSecure()
                || !"https".equals(request.getScheme())
                || !origin.getHost().equals(request.getServerName())
                || (origin.getPort() == -1 ? 443 : origin.getPort()) != request.getServerPort()
                || !"1".equals(single(request, WEB))) throw forbidden();
        var origins = Collections.list(request.getHeaders("Origin"));
        if (origins.isEmpty()
                && !cookieAuth
                && (!publicRoute(request) || "GET".equals(request.getMethod()))) {
            if (!"same-origin".equals(single(request, "Sec-Fetch-Site"))) throw forbidden();
        } else if (origins.size() != 1 || !origin.toString().equals(origins.getFirst())) {
            throw forbidden();
        }
        if (request.getHeader("Sec-Fetch-Site") != null
                && !"same-origin".equals(single(request, "Sec-Fetch-Site"))) throw forbidden();
        if (request.getHeader("Sec-Fetch-Mode") != null) {
            String mode = single(request, "Sec-Fetch-Mode");
            if (!mode.equals("same-origin") && !mode.equals("cors")) throw forbidden();
        }
        if (request.getHeader("Sec-Fetch-Dest") != null
                && !"empty".equals(single(request, "Sec-Fetch-Dest"))) throw forbidden();
        if (request.getHeader("Sec-Fetch-User") != null) throw forbidden();
        if (request.getQueryString() != null
                && !invitationQuery(request)
                && !gameListQuery(request)) throw AuthException.badRequest("INVALID_REQUEST");
        if (cookieAuth) {
            if (request.getHeader("Authorization") != null) throw forbidden();
            epoch(request);
        } else {
            if (request.getHeader("Cookie") != null
                    || publicRoute(request) && request.getHeader("Authorization") != null)
                throw forbidden();
            if (publicRoute(request)) epoch(request);
        }
    }

    /** 웹 목록은 선택적 양의 long 커서와 1~100 크기만 허용하며 중복·다른 query는 거절한다. */
    private static boolean invitationQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        if (!"GET".equals(request.getMethod())
                || !request.getRequestURI().equals(MemberSecurityConfig.PLAYTEST + "/invitations")
                || query == null) return false;
        String[] fields = query.split("&", -1);
        if (fields.length > 2) return false;
        boolean cursor = false;
        boolean size = false;
        try {
            for (String field : fields) {
                if (field.matches("cursor=[1-9][0-9]{0,18}") && !cursor) {
                    cursor = true;
                    if (Long.parseLong(field.substring(7)) <= 0) return false;
                } else if (field.matches("size=[1-9][0-9]{0,2}") && !size) {
                    size = true;
                    if (Integer.parseInt(field.substring(5)) > 100) return false;
                } else return false;
            }
            return true;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    /** POST에서 canonical UUID v4 epoch를 정확히 한 번 요구한다. */
    static UUID epoch(HttpServletRequest request) {
        String value = single(request, EPOCH);
        if (!value.matches(UUID4)) throw AuthException.badRequest("INVALID_REQUEST");
        return UUID.fromString(value);
    }

    record Proof(UUID epoch, String token) {}

    /** Servlet cookie 합치기를 피하고 모든 raw Cookie 줄에서 이름의 중복을 거절한다. */
    static Proof proof(HttpServletRequest request) {
        Proof found = null;
        for (String line : Collections.list(request.getHeaders("Cookie"))) {
            for (String part : line.split(";", -1)) {
                String item = part.trim();
                int equals = item.indexOf('=');
                String name = equals < 0 ? item : item.substring(0, equals).trim();
                if (!name.equals(COOKIE)) continue;
                if (found != null || equals < 0) throw AuthException.badRequest("INVALID_REQUEST");
                String value = item.substring(equals + 1);
                if (!value.matches(UUID4 + "\\.[A-Za-z0-9_-]{43}"))
                    throw AuthException.badRequest("INVALID_REQUEST");
                String token = value.substring(37);
                byte[] decoded = java.util.Base64.getUrlDecoder().decode(token);
                if (decoded.length != 32
                        || !java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(decoded)
                                .equals(token)) throw AuthException.badRequest("INVALID_REQUEST");
                found = new Proof(UUID.fromString(value.substring(0, 36)), token);
            }
        }
        return found;
    }

    private static AuthException forbidden() {
        return new AuthException(403, "FORBIDDEN", "FORBIDDEN");
    }
}
