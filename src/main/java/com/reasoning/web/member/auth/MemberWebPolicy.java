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
        return "POST".equals(request.getMethod())
                && switch (request.getRequestURI()) {
                    case BASE + "/login/local", BASE + "/refresh", BASE + "/logout" -> true;
                    default -> false;
                };
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
        if (origins.isEmpty() && !cookieAuth) {
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
        if (request.getQueryString() != null) throw AuthException.badRequest("INVALID_REQUEST");
        if (cookieAuth) {
            if (request.getHeader("Authorization") != null) throw forbidden();
            epoch(request);
        } else if (request.getHeader("Cookie") != null) throw forbidden();
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
