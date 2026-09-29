package com.reasoning.admin.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.audit.AccessHistoryFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityConfig {
    /** Implement in the business layer: compare ID hash/session_key, ACTIVE, auth_rev and current account state/time. */
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
                .GET("/admin/api/auth/csrf", request -> {
                    CsrfToken token = (CsrfToken) request.servletRequest().getAttribute(CsrfToken.class.getName());
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .header("Cache-Control", "no-store")
                            .body(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
                }).build();
    }

    @Bean
    SecurityFilterChain adminSecurity(HttpSecurity http, AdminSessionAdapter sessions,
            ObjectProvider<ActiveSessionVerifier> verifier, ObjectMapper mapper, JdbcTemplate db) throws Exception {
        CookieCsrfTokenRepository csrfCookies = new CookieCsrfTokenRepository();
        csrfCookies.setCookieName("__Host-admin-csrf");
        csrfCookies.setHeaderName("X-CSRF-TOKEN");
        csrfCookies.setCookieCustomizer(cookie -> cookie.path("/").secure(true).httpOnly(true).sameSite("Lax"));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        http.securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfCookies).csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/api/auth/csrf", "/admin/api/auth/login", "/admin/api/auth/login/mfa",
                                "/admin/api/auth/login/status", "/admin/api/auth/enrollment/**",
                                "/admin/api/auth/recovery/**", "/admin/api/auth/logout",
                                "/admin/auth.js", "/admin/auth.css",
                                "/admin/login", "/admin/login/mfa", "/admin/enroll",
                                "/admin/recovery/password", "/admin/recovery/mfa").permitAll()
                        .requestMatchers("/admin/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) -> {
                            if (request.getMethod().equals("GET") && (request.getRequestURI().equals("/admin")
                                    || request.getRequestURI().equals("/admin/auth/manage")
                                    || request.getRequestURI().equals("/admin/accounts")
                                    || request.getRequestURI().startsWith("/admin/accounts/")
                                    || request.getRequestURI().equals("/admin/stories")
                                    || request.getRequestURI().startsWith("/admin/stories/"))) {
                                response.setStatus(303);
                                response.setHeader("Location", "/admin/login");
                                response.setHeader("Cache-Control", "no-store");
                            } else {
                                error(mapper, response, 401, "AUTH_REQUIRED");
                            }
                        })
                        .accessDeniedHandler((request, response, failure) ->
                                error(mapper, response, 403,
                                        failure instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN")))
                .addFilterBefore(new AccessHistoryFilter(db), CsrfFilter.class)
                .addFilterBefore(new SameOriginFilter(mapper), CsrfFilter.class)
                .addFilterBefore(new ActiveSessionFilter(sessions, verifier), AuthorizationFilter.class);
        return http.build();
    }

    private static void error(ObjectMapper mapper, HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), Map.of("code", code, "message", switch (code) {
            case "AUTH_REQUIRED" -> "운영자 인증이 필요합니다.";
            case "CSRF_INVALID" -> "요청 출처 또는 CSRF 확인에 실패했습니다.";
            default -> "접근할 수 없습니다.";
        }, "requestId", UUID.randomUUID().toString()));
    }

    private static final class ActiveSessionFilter extends OncePerRequestFilter {
        private final AdminSessionAdapter sessions;
        private final ObjectProvider<ActiveSessionVerifier> verifier;

        private ActiveSessionFilter(AdminSessionAdapter sessions, ObjectProvider<ActiveSessionVerifier> verifier) {
            this.sessions = sessions;
            this.verifier = verifier;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            ActiveSessionVerifier active = verifier.getIfAvailable();
            if (active != null) {
                sessions.current(request).filter(session -> active.isActive(session.id(), session.principal()))
                        .ifPresent(session -> {
                            var context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                                    .authenticated(session.principal(), null, java.util.List.of()));
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
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String method = request.getMethod();
            if (!request.getRequestURI().startsWith("/admin/") || method.equals("GET") || method.equals("HEAD")
                    || method.equals("OPTIONS") || method.equals("TRACE")) {
                chain.doFilter(request, response);
                return;
            }
            String source = request.getHeader("Origin");
            if (source == null) {
                source = request.getHeader("Referer");
            }
            if (!request.isSecure() || !sameOrigin(source, request)) {
                error(mapper, response, 403, "CSRF_INVALID");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean sameOrigin(String source, HttpServletRequest request) {
            try {
                URI uri = URI.create(source);
                return uri.getUserInfo() == null && uri.getHost() != null && uri.getRawFragment() == null
                        && uri.getScheme().equalsIgnoreCase(request.getScheme())
                        && uri.getHost().equalsIgnoreCase(request.getServerName())
                        && (uri.getPort() == -1 ? 443 : uri.getPort()) == request.getServerPort();
            } catch (IllegalArgumentException | NullPointerException ex) {
                return false;
            }
        }
    }
}
