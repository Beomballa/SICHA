package com.reasoning.admin.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.admin.auth.audit.AccessHistoryFilter;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.common.auth.audit.RequestAuditKernel;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityConfig {
    /**
     * Implement in the business layer: compare ID hash/session_key, ACTIVE, auth_rev and current
     * account state/time.
     */
    @FunctionalInterface
    public interface ActiveSessionVerifier {
        boolean isActive(String frameworkSessionId, AdminSessionAdapter.AdminPrincipal principal);
    }

    @Bean
    CookieSerializer adminCookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("__Host-admin-session");
        serializer.setCookiePath("/");
        serializer.setUseSecureCookie(true);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    RouterFunction<ServerResponse> adminCsrfEndpoint() {
        return RouterFunctions.route()
                .GET(
                        "/admin/api/auth/csrf",
                        request -> {
                            CsrfToken token =
                                    (CsrfToken)
                                            request.servletRequest()
                                                    .getAttribute(CsrfToken.class.getName());
                            return ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header("Cache-Control", "no-store")
                                    .body(
                                            Map.of(
                                                    "token",
                                                    token.getToken(),
                                                    "headerName",
                                                    token.getHeaderName()));
                        })
                .build();
    }

    /**
     * 인증 단계의 공개 경로와 비밀 없는 공통 자산만 허용하고 나머지 관리자 요청을 보호한다.
     *
     * @param http 현재 웹 보안 구성. null은 허용하지 않는다.
     * @param sessions 서버 저장 세션 조회 어댑터. null은 허용하지 않는다.
     * @param verifier 활성 세션 검증기의 선택적 조회 제공자. 제공자 자체는 null일 수 없다.
     * @param mapper 고정 인증 오류 응답 직렬화기. null은 허용하지 않는다.
     * @param db 서버 접근 이력 기록용 DB 연결 도구. null은 허용하지 않는다.
     * @return 관리자 인증·CSRF·접근 이력 경계를 적용한 필터 체인.
     * @throws Exception 보안 필터 구성을 초기화할 수 없는 경우.
     */
    @Bean
    @Order(2)
    SecurityFilterChain adminSecurity(
            HttpSecurity http,
            AdminSessionAdapter sessions,
            ObjectProvider<ActiveSessionVerifier> verifier,
            ObjectMapper mapper,
            JdbcTemplate db)
            throws Exception {
        CookieCsrfTokenRepository csrfCookies = new CookieCsrfTokenRepository();
        csrfCookies.setCookieName("__Host-admin-csrf");
        csrfCookies.setHeaderName("X-CSRF-TOKEN");
        csrfCookies.setCookieCustomizer(
                cookie -> cookie.path("/").secure(true).httpOnly(true).sameSite("Lax"));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        http.securityContext(
                        context ->
                                context.securityContextRepository(
                                        new NullSecurityContextRepository()))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfCookies)
                                        .csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/admin/api/auth/csrf",
                                                "/admin/api/auth/login",
                                                "/admin/api/auth/login/mfa",
                                                "/admin/api/auth/login/status",
                                                "/admin/api/auth/enrollment/**",
                                                "/admin/api/auth/recovery/**",
                                                "/admin/api/auth/logout",
                                                "/admin/auth.js",
                                                "/admin/auth.css",
                                                "/admin/ui.css",
                                                "/admin/ui.js",
                                                "/admin/shell.js",
                                                "/admin/login",
                                                "/admin/login/mfa",
                                                "/admin/enroll",
                                                "/admin/recovery/password",
                                                "/admin/recovery/mfa")
                                        .permitAll()
                                        .requestMatchers("/admin/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, failure) -> {
                                                    if (request.getMethod().equals("GET")
                                                            && (request.getRequestURI()
                                                                            .equals("/admin")
                                                                    || request.getRequestURI()
                                                                            .equals(
                                                                                    "/admin/preview/player-home")
                                                                    || request.getRequestURI()
                                                                            .equals(
                                                                                    "/admin/auth/manage")
                                                                    || request.getRequestURI()
                                                                            .equals(
                                                                                    "/admin/accounts")
                                                                    || request.getRequestURI()
                                                                            .startsWith(
                                                                                    "/admin/accounts/")
                                                                    || request.getRequestURI()
                                                                            .equals(
                                                                                    "/admin/stories")
                                                                    || request.getRequestURI()
                                                                            .startsWith(
                                                                                    "/admin/stories/"))) {
                                                        response.setStatus(303);
                                                        response.setHeader(
                                                                "Location", "/admin/login");
                                                        response.setHeader(
                                                                "Cache-Control", "no-store");
                                                    } else {
                                                        error(
                                                                mapper,
                                                                request,
                                                                response,
                                                                401,
                                                                "AUTH_REQUIRED");
                                                    }
                                                })
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        error(
                                                                mapper,
                                                                request,
                                                                response,
                                                                403,
                                                                failure instanceof CsrfException
                                                                        ? "CSRF_INVALID"
                                                                        : "FORBIDDEN")))
                .addFilterBefore(new AccessHistoryFilter(db), CsrfFilter.class)
                .addFilterBefore(new SameOriginFilter(mapper), CsrfFilter.class)
                .addFilterBefore(
                        new ActiveSessionFilter(sessions, verifier), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * 보안 필터의 거절 응답을 접근 이력이 생성한 같은 서버 UUID로 연결한다.
     *
     * @param mapper 고정 오류 응답 직렬화기
     * @param request 접근 이력 필터를 통과하여 UUID 속성이 설정된 요청
     * @param response 실제 거절 상태와 no-store를 기록할 응답
     * @param status 실제 HTTP 오류 코드
     * @param code 원문 없는 서버 고정 오류 식별자
     * @throws IOException 오류 응답을 전송할 수 없는 경우
     * @throws IllegalStateException 서버 요청 UUID가 없어 이력과 연결할 수 없는 경우
     */
    private static void error(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code)
            throws IOException {
        UUID requestId = RequestAuditKernel.requestId(request);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(
                response.getWriter(),
                Map.of(
                        "code",
                        code,
                        "message",
                        switch (code) {
                            case "AUTH_REQUIRED" -> "운영자 인증이 필요합니다.";
                            case "CSRF_INVALID" -> "요청 출처 또는 CSRF 확인에 실패했습니다.";
                            default -> "접근할 수 없습니다.";
                        },
                        "requestId",
                        requestId.toString()));
    }

    private static final class ActiveSessionFilter extends OncePerRequestFilter {
        private final AdminSessionAdapter sessions;
        private final ObjectProvider<ActiveSessionVerifier> verifier;

        private ActiveSessionFilter(
                AdminSessionAdapter sessions, ObjectProvider<ActiveSessionVerifier> verifier) {
            this.sessions = sessions;
            this.verifier = verifier;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            ActiveSessionVerifier active = verifier.getIfAvailable();
            if (active != null) {
                sessions.current(request)
                        .filter(session -> active.isActive(session.id(), session.principal()))
                        .ifPresent(
                                session -> {
                                    var context = SecurityContextHolder.createEmptyContext();
                                    context.setAuthentication(
                                            org.springframework.security.authentication
                                                    .UsernamePasswordAuthenticationToken
                                                    .authenticated(
                                                            session.principal(),
                                                            null,
                                                            java.util.List.of()));
                                    SecurityContextHolder.setContext(context);
                                });
            }
            chain.doFilter(request, response);
        }
    }

    private static final class SameOriginFilter extends OncePerRequestFilter {
        private final ObjectMapper mapper;

        private SameOriginFilter(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String method = request.getMethod();
            if (!request.getRequestURI().startsWith("/admin/")
                    || method.equals("GET")
                    || method.equals("HEAD")
                    || method.equals("OPTIONS")
                    || method.equals("TRACE")) {
                chain.doFilter(request, response);
                return;
            }
            String source = request.getHeader("Origin");
            if (source == null) {
                source = request.getHeader("Referer");
            }
            if (!request.isSecure() || !sameOrigin(source, request)) {
                error(mapper, request, response, 403, "CSRF_INVALID");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean sameOrigin(String source, HttpServletRequest request) {
            try {
                URI uri = URI.create(source);
                return uri.getUserInfo() == null
                        && uri.getHost() != null
                        && uri.getRawFragment() == null
                        && uri.getScheme().equalsIgnoreCase(request.getScheme())
                        && uri.getHost().equalsIgnoreCase(request.getServerName())
                        && (uri.getPort() == -1 ? 443 : uri.getPort()) == request.getServerPort();
            } catch (IllegalArgumentException | NullPointerException ex) {
                return false;
            }
        }
    }
}
