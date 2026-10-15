package com.reasoning.web.member.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 공개 화면·정적 모듈의 열람을 회원 인증 활성화와 분리하고 관리자 체인보다 먼저 격리한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PublicPageConfiguration {
    /**
     * 정확한 공개 화면·정적 자산만 HTTP와 HTTPS에서 익명으로 읽게 한다. 회원·관리자 API는 포함하지 않는다.
     *
     * @param http 공개 경로 전용 필터 체인 구성기이며 null 불가
     * @return 쿠키·세션·관리자 인증 없이 GET/HEAD만 제공하는 체인
     * @throws Exception 보안 필터를 구성할 수 없는 경우
     */
    @Bean
    @Order(-2)
    SecurityFilterChain publicPageSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        request ->
                                request.getRequestURI().equals("/")
                                        || request.getRequestURI().equals("/privacy")
                                        || request.getRequestURI().equals("/account")
                                        || request.getRequestURI().equals("/play")
                                        || request.getRequestURI().equals("/coop-invite")
                                        || request.getRequestURI().equals("/records")
                                        || request.getRequestURI().equals("/player-public.css")
                                        || request.getRequestURI()
                                                .equals("/player-common/web-api.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/web-session.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/account.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/game-model.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/game-client.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/play-page.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/invite-bootstrap.js")
                                        || request.getRequestURI()
                                                .equals("/player-common/records-page.mjs"))
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
                                auth.requestMatchers(PublicPageConfiguration::allowed)
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, failure) ->
                                                        response.sendError(403))
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        response.sendError(403)))
                .addFilterBefore(new PublicHeaders(), ExceptionTranslationFilter.class);
        return http.build();
    }

    /**
     * 공개 문서와 코드만 읽게 하며 쿼리·변경 요청은 허용하지 않는다.
     *
     * @param request 이미 정확한 공개 경로와 일치한 요청이며 null 불가
     * @return 쿼리 없는 GET/HEAD이면 true
     */
    private static boolean allowed(HttpServletRequest request) {
        return request.getQueryString() == null
                && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
    }

    /** 허용과 거절 모두에 같은 공개 화면 보안 헤더를 적용한다. */
    private static final class PublicHeaders extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            boolean interactive =
                    request.getRequestURI().equals("/account")
                            || request.getRequestURI().equals("/play")
                            || request.getRequestURI().equals("/coop-invite")
                            || request.getRequestURI().equals("/records");
            response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src "
                            + (interactive ? "'self'" : "'none'")
                            + "; style-src 'self';"
                            + (interactive ? " connect-src 'self'; object-src 'none';" : "")
                            + " frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            chain.doFilter(request, response);
        }
    }
}
