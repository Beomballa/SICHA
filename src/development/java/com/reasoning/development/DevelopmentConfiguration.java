package com.reasoning.development;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AuthException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** 실행 전용 클래스패스에서 합성 계정 진입만 격리하며 제품 인증 체인은 변경하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Controller
@ConditionalOnProperty(name = "app.development.enabled", havingValue = "true")
public class DevelopmentConfiguration {
    private static final int BODY_LIMIT = 16 * 1024;
    private final DevelopmentState state;
    private final DevelopmentAccounts accounts;
    private final ObjectMapper mapper;

    /**
     * 런처가 검증한 상태와 실제 계정 서비스를 필수로 받는다.
     *
     * @param state 런처 소유 개발 원점이며 null 불가
     * @param accounts 별도 개발 DB의 합성 계정 서비스이며 null 불가
     * @param mapper 기존 JSON 처리기이며 null 불가
     */
    public DevelopmentConfiguration(
            DevelopmentState state, DevelopmentAccounts accounts, ObjectMapper mapper) {
        this.state = state;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    /**
     * 개발 경로 두 개의 열람과 두 개의 명시 POST만 허용한다.
     *
     * @param http 개발 경로 전용 구성기이며 null 불가
     * @return 제품 API와 분리된 개발 체인
     * @throws Exception 보안 체인 구성에 실패한 경우
     */
    @Bean
    @Order(-4)
    SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        request ->
                                request.getRequestURI().equals("/__development")
                                        || request.getRequestURI().startsWith("/__development/"))
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
                // 제품 CSRF는 그대로 두고 이 체인에서는 정확한 Origin POST 검증으로 한정한다.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(DevelopmentConfiguration::allowed)
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, failure) ->
                                                        error(
                                                                response,
                                                                403,
                                                                "DEVELOPMENT_REQUEST_REJECTED"))
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        error(
                                                                response,
                                                                403,
                                                                "DEVELOPMENT_REQUEST_REJECTED")))
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            @Override
                            protected void doFilterInternal(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
                                    throws ServletException, IOException {
                                headers(response);
                                try {
                                    state.requireRequest(
                                            request, "POST".equals(request.getMethod()));
                                } catch (AuthException failure) {
                                    error(
                                            response,
                                            failure.status(),
                                            "DEVELOPMENT_REQUEST_REJECTED");
                                    return;
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        ExceptionTranslationFilter.class);
        return http.build();
    }

    /**
     * 프레임워크 세션 복원 뒤, 기존 관리자 보안 검증 전에 합성 계정의 최근 인증만 갱신한다.
     *
     * @return 관리자 경로에만 적용되는 개발 필터 등록
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> developmentAdministratorRefresh() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setFilter(
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain)
                            throws ServletException, IOException {
                        try {
                            state.requireConnector(request);
                            accounts.refreshAdministrator(request);
                        } catch (AuthException failure) {
                            headers(response);
                            error(response, failure.status(), "DEVELOPMENT_AUTH_REJECTED");
                            return;
                        } catch (RuntimeException failure) {
                            headers(response);
                            error(response, 503, "DEVELOPMENT_UNAVAILABLE");
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                });
        registration.setName("developmentAdministratorRefresh");
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
        return registration;
    }

    /**
     * 준비 상태를 숨기지 않고 개발 유틸리티를 제공한다.
     *
     * @param request 정확한 개발 원점의 읽기 요청
     * @param response 개발 폼의 동일 출처 Origin을 보존할 문서 응답
     * @return 개발 전용 템플릿과 실제 준비 상태
     */
    @GetMapping("/__development")
    public ModelAndView index(HttpServletRequest request, HttpServletResponse response) {
        state.requireRequest(request, false);
        // no-referrer는 네이티브 POST의 Origin도 null로 만들므로 이 비밀 없는 문서만 제한한다.
        response.setHeader("Referrer-Policy", "same-origin");
        return new ModelAndView("development/index", Map.of("ready", accounts.isReady()));
    }

    /**
     * 명시한 폼 제출로 실제 DB·프레임워크 관리자 세션을 발급한다.
     *
     * @param request 동일 출처의 POST 요청
     * @param response 실제 세션 쿠키와 안전한 오류를 전달할 응답
     * @throws IOException 응답을 기록할 수 없는 경우
     */
    @PostMapping("/__development/login/admin")
    public void loginAdministrator(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            state.requireRequest(request, true);
            accounts.loginAdministrator(request, response);
            response.setStatus(303);
            response.setHeader("Location", "/admin");
        } catch (AuthException failure) {
            error(response, failure.status(), "DEVELOPMENT_AUTH_REJECTED");
        } catch (RuntimeException failure) {
            error(response, 503, "DEVELOPMENT_UNAVAILABLE");
        }
    }

    /**
     * 닫힌 소형 JSON 요청에 대해서만 회원 자격을 일회 응답하며 저장·로깅하지 않는다.
     *
     * @param request 동일 출처 사용자 모듈의 POST 요청
     * @return 이메일·비밀번호 두 필드 또는 비밀 없는 고정 오류
     */
    @PostMapping("/__development/member-credentials")
    public ResponseEntity<?> memberCredentials(HttpServletRequest request) {
        try {
            state.requireRequest(request, true);
            var headers = Collections.list(request.getHeaders("X-Sicha-Development"));
            if (headers.size() != 1 || !"1".equals(headers.getFirst())) {
                throw AuthException.forbidden("DEVELOPMENT_REQUEST_REJECTED");
            }
            if (request.getContentType() == null) {
                throw AuthException.badRequest("DEVELOPMENT_BODY_REJECTED");
            }
            MediaType type;
            try {
                type = MediaType.parseMediaType(request.getContentType());
            } catch (IllegalArgumentException failure) {
                throw AuthException.badRequest("DEVELOPMENT_BODY_REJECTED");
            }
            if (!"application".equals(type.getType())
                    || !"json".equals(type.getSubtype())
                    || type.getParameters().keySet().stream()
                            .anyMatch(key -> !"charset".equals(key))
                    || (type.getCharset() != null
                            && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                throw AuthException.badRequest("DEVELOPMENT_BODY_REJECTED");
            }
            byte[] body = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (body.length > BODY_LIMIT) {
                throw AuthException.badRequest("DEVELOPMENT_BODY_REJECTED");
            }
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(body))
                            .toString();
            String slot;
            try (JsonParser parser = mapper.getFactory().createParser(text)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode value = mapper.readTree(parser);
                if (value == null
                        || !value.isObject()
                        || value.size() != 1
                        || !value.has("account")
                        || !value.get("account").isTextual()
                        || parser.nextToken() != null) {
                    throw AuthException.badRequest("DEVELOPMENT_BODY_REJECTED");
                }
                slot = value.get("account").textValue();
            }
            if (!"a".equals(slot) && !"b".equals(slot)) {
                throw AuthException.badRequest("DEVELOPMENT_BODY_REJECTED");
            }
            return ResponseEntity.ok(accounts.memberCredentials(slot));
        } catch (AuthException failure) {
            return ResponseEntity.status(failure.status())
                    .body(Map.of("code", "DEVELOPMENT_REQUEST_REJECTED"));
        } catch (IOException failure) {
            return ResponseEntity.badRequest().body(Map.of("code", "DEVELOPMENT_BODY_REJECTED"));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(503).body(Map.of("code", "DEVELOPMENT_UNAVAILABLE"));
        }
    }

    /**
     * 정확한 네 경로의 메서드만 열고 나머지 개발 자원은 닫는다.
     *
     * @param request 개발 체인에 들어온 요청이며 null 불가
     * @return 쿼리 없이 허용된 경로·메서드가 일치하면 true
     */
    private static boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return request.getQueryString() == null
                && ((("GET".equals(method) || "HEAD".equals(method))
                                && ("/__development".equals(path)
                                        || "/__development/client.mjs".equals(path)))
                        || ("POST".equals(method)
                                && ("/__development/login/admin".equals(path)
                                        || "/__development/member-credentials".equals(path))));
    }

    /**
     * 성공·거절 모두에 비밀 비저장과 동일 출처 실행 경계를 적용한다.
     *
     * @param response 아직 커밋되지 않은 응답이며 null 불가
     */
    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self';"
                    + " form-action 'self'; frame-src 'none'; frame-ancestors 'none'; object-src"
                    + " 'none'; base-uri 'none'");
    }

    /**
     * 고정된 서버 코드만 기록하며 예외·자격·세션 원문을 전달하지 않는다.
     *
     * @param response 오류를 기록할 응답이며 null 불가
     * @param status 비밀 없는 인증 실패의 HTTP 상태
     * @param code 이 클래스에서 정의한 고정 대문자·밑줄 코드만 허용
     * @throws IOException 응답을 기록할 수 없는 경우
     */
    private static void error(HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
