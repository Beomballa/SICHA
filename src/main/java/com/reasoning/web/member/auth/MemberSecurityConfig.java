package com.reasoning.web.member.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberAuthService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 앱 전용 무쿠키 체인을 관리자 CSRF와 분리하고 승인된 LOCAL 경로만 연다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MemberSecurityConfig {
    static final String AUTH = "/api/member/auth";
    static final String NOTICE = "/api/member/privacy/policies/member-auth";
    static final String PLAYTEST = "/api/playtests";
    private static final String TEST_KEY =
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final Set<String> POST =
            Set.of(
                    AUTH + "/email/signup",
                    AUTH + "/email/verify",
                    AUTH + "/email/signup/complete",
                    AUTH + "/login/local",
                    AUTH + "/refresh",
                    AUTH + "/logout");

    /** 회원 prefix 전체를 먼저 격리하며 세션·CSRF 비활성은 이 체인에만 한정한다. */
    @Bean
    @Order(0)
    SecurityFilterChain memberSecurity(
            HttpSecurity http,
            MemberAuthService service,
            ObjectMapper mapper,
            JdbcTemplate db,
            MemberWebPolicy web)
            throws Exception {
        http.securityMatcher(
                        request ->
                                request.getRequestURI().equals("/api/member")
                                        || request.getRequestURI().startsWith("/api/member/")
                                        || request.getRequestURI().equals(PLAYTEST)
                                        || request.getRequestURI().startsWith(PLAYTEST + "/"))
                .securityContext(
                        context ->
                                context.securityContextRepository(
                                        new NullSecurityContextRepository()))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .rememberMe(remember -> remember.disable())
                .cors(cors -> cors.disable())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(MemberSecurityConfig::route)
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, failure) ->
                                                        error(
                                                                mapper,
                                                                request,
                                                                response,
                                                                403,
                                                                "FORBIDDEN"))
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        error(
                                                                mapper,
                                                                request,
                                                                response,
                                                                403,
                                                                "FORBIDDEN")))
                .addFilterBefore(
                        new MemberFilter(service, mapper, web), ExceptionTranslationFilter.class)
                .addFilterBefore(new AccessFilter(db), MemberFilter.class);
        return http.build();
    }

    /** 메서드와 exact 경로를 함께 검사하여 미래 회원 API를 자동 공개하지 않는다. */
    static boolean route(HttpServletRequest request) {
        if (MemberWebPolicy.route(request)) return true;
        String path = request.getRequestURI();
        if (path.startsWith(PLAYTEST + "/")) {
            String suffix = path.substring(PLAYTEST.length());
            return "GET".equals(request.getMethod())
                            && (suffix.equals("/identity")
                                    || suffix.equals("/invitations")
                                    || suffix.matches("/" + TEST_KEY)
                                    || suffix.matches("/" + TEST_KEY + "/policy-notice")
                                    || suffix.matches("/" + TEST_KEY + "/materials")
                                    || suffix.matches("/" + TEST_KEY + "/report")
                                    || suffix.matches("/" + TEST_KEY + "/result"))
                    || "PATCH".equals(request.getMethod())
                            && suffix.matches("/" + TEST_KEY + "/report")
                    || "POST".equals(request.getMethod())
                            && (suffix.matches("/" + TEST_KEY + "/accept")
                                    || suffix.matches("/" + TEST_KEY + "/ready")
                                    || suffix.matches("/" + TEST_KEY + "/start")
                                    || suffix.matches("/" + TEST_KEY + "/heartbeat")
                                    || suffix.matches("/" + TEST_KEY + "/hints/[1-3]/open")
                                    || suffix.matches("/" + TEST_KEY + "/report/proposals")
                                    || suffix.matches(
                                            "/"
                                                    + TEST_KEY
                                                    + "/report/proposals/"
                                                    + TEST_KEY
                                                    + "/respond")
                                    || suffix.matches("/" + TEST_KEY + "/forfeit")
                                    || suffix.matches("/" + TEST_KEY + "/feedback"));
        }
        return "POST".equals(request.getMethod()) && POST.contains(path)
                || "GET".equals(request.getMethod())
                        && (path.equals(AUTH + "/me") || path.equals(NOTICE));
    }

    /** 중복 헤더·다른 인증 방식과 URL 비밀은 수용하지 않는다. */
    static String bearer(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1 || !headers.getFirst().matches("Bearer [A-Za-z0-9_-]{43}"))
            throw AuthException.unauthorized("MEMBER_AUTH_REQUIRED");
        return headers.getFirst().substring(7);
    }

    /** 검증한 서버 주체만 접근 이력에 투영하고 토큰을 context에 저장하지 않는다. */
    static void principal(MemberAuthService.MemberPrincipal principal) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    /** 입력 원문과 내부 예외 없이 서버 요청 UUID를 고정 오류 봉투로 반환한다. */
    static Map<String, Object> errorBody(HttpServletRequest request, String code) {
        return Map.of(
                "code", code, "message", code, "requestId", RequestAuditKernel.requestId(request));
    }

    static void error(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 429) response.setHeader("Retry-After", "900");
        mapper.writeValue(response.getWriter(), errorBody(request, code));
    }

    private static final class AccessFilter extends OncePerRequestFilter {
        private final RequestAuditKernel kernel;

        private AccessFilter(JdbcTemplate db) {
            kernel = new RequestAuditKernel(db);
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            kernel.record(
                    request,
                    response,
                    chain,
                    (authentication, status, uri) -> {
                        var actor =
                                authentication != null
                                                && authentication.isAuthenticated()
                                                && authentication.getPrincipal()
                                                        instanceof
                                                        MemberAuthService.MemberPrincipal member
                                        ? new RequestAuditKernel.Actor(
                                                RequestAuditKernel.ActorKind.MEMBER,
                                                member.memberKey(),
                                                null)
                                        : new RequestAuditKernel.Actor(
                                                RequestAuditKernel.ActorKind.ANONYMOUS, null, null);
                        return new RequestAuditKernel.Observation(
                                route(request) ? auditRoute(uri) : "UNMATCHED", actor);
                    });
        }
    }

    /** 승인된 동적 경로의 실제 UUID를 접근 이력에 남기지 않는다. */
    private static String auditRoute(String uri) {
        if (uri.startsWith(PLAYTEST + "/")) {
            return uri.replaceFirst("/" + TEST_KEY + "(?=/|$)", "/{testKey}")
                    .replaceFirst(
                            "/report/proposals/" + TEST_KEY + "/respond$",
                            "/report/proposals/{reportKey}/respond")
                    .replaceFirst("/hints/[1-3]/open$", "/hints/{level}/open");
        }
        return uri;
    }

    private static final class MemberFilter extends OncePerRequestFilter {
        private final MemberAuthService service;
        private final ObjectMapper mapper;
        private final MemberWebPolicy web;

        private MemberFilter(MemberAuthService service, ObjectMapper mapper, MemberWebPolicy web) {
            this.service = service;
            this.mapper = mapper;
            this.web = web;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            if (request.getRequestURI().equals(MemberWebPolicy.BASE)
                    || request.getRequestURI().startsWith(MemberWebPolicy.BASE + "/")
                    || request.getHeader(MemberWebPolicy.WEB) != null) {
                try {
                    boolean cookieAuth = MemberWebPolicy.route(request);
                    boolean me =
                            "GET".equals(request.getMethod())
                                    && request.getRequestURI().equals(AUTH + "/me");
                    if (!cookieAuth && !me) throw new AuthException(403, "FORBIDDEN", "FORBIDDEN");
                    web.admit(request, cookieAuth);
                    if (me) principal(service.authenticate(bearer(request), false));
                } catch (AuthException failure) {
                    error(mapper, request, response, failure.status(), failure.code());
                    return;
                }
                chain.doFilter(request, response);
                return;
            }
            if (!request.isSecure()
                    || request.getHeader("Origin") != null
                    || request.getHeader("Sec-Fetch-Site") != null
                    || request.getHeader("Sec-Fetch-Mode") != null
                    || request.getHeader("Sec-Fetch-Dest") != null
                    || request.getHeader("Sec-Fetch-User") != null
                    || (request.getRequestURI().startsWith(PLAYTEST + "/")
                            && request.getHeader("Cookie") != null)) {
                error(mapper, request, response, 403, "FORBIDDEN");
                return;
            }
            if (request.getQueryString() != null
                    && !(request.getRequestURI().equals(PLAYTEST + "/invitations")
                            && "GET".equals(request.getMethod()))) {
                error(mapper, request, response, 400, "INVALID_REQUEST");
                return;
            }
            if (route(request)
                    && (request.getRequestURI().equals(AUTH + "/me")
                            || request.getRequestURI().equals(AUTH + "/logout")
                            || request.getRequestURI().startsWith(PLAYTEST + "/"))) {
                try {
                    principal(
                            service.authenticate(
                                    bearer(request),
                                    request.getRequestURI().equals(AUTH + "/logout")));
                } catch (AuthException failure) {
                    error(mapper, request, response, failure.status(), failure.code());
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
