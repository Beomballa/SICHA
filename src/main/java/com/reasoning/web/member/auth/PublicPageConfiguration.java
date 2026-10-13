package com.reasoning.web.member.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/** 소개 페이지와 전용 스타일만 기존 인증 체인보다 먼저 격리한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.member-web.enabled", havingValue = "true")
public class PublicPageConfiguration {
    /** 소개·고지·전용 스타일의 정확한 경로만 무세션 HTTPS 읽기로 허용한다. */
    @Bean
    @Order(-2)
    SecurityFilterChain publicPageSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        request ->
                                request.getRequestURI().equals("/")
                                        || request.getRequestURI().equals("/privacy")
                                        || request.getRequestURI().equals("/account")
                                        || request.getRequestURI().equals("/play")
                                        || request.getRequestURI().equals("/player-public.css")
                                        || request.getRequestURI()
                                                .equals("/player-common/web-api.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/web-session.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/account.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/solo-model.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/solo-client.mjs")
                                        || request.getRequestURI()
                                                .equals("/player-common/solo-page.mjs"))
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

    /** 쿼리나 비보안 전송을 읽기 경로로 취급하지 않는다. */
    private static boolean allowed(HttpServletRequest request) {
        return request.isSecure()
                && "https".equals(request.getScheme())
                && request.getQueryString() == null
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
                            || request.getRequestURI().equals("/play");
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
