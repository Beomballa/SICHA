package com.reasoning.common.grading.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.audit.RequestAuditKernel.Actor;
import com.reasoning.common.auth.audit.RequestAuditKernel.ActorKind;
import com.reasoning.common.auth.audit.RequestAuditKernel.Observation;
import com.reasoning.common.grading.controller.GradeWorkerController.Assembly;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 관리자 fallback보다 앞선 내부 worker 전용 경계다. 실제 TLS·자격·서비스 배포를 활성화하지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GradeWorkerSecurityConfig {
    private static final String PREFIX = "/internal/api/grading";
    private static final Pattern ROUTE =
            Pattern.compile(
                    PREFIX
                            + "/jobs/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/(start|before-chat|renew|complete)");

    /**
     * prefix 자체와 모든 자손을 별도 무세션 체인으로 격리한다. 이력→TLS/Bearer→인가 순서를 고정한다.
     *
     * @param http servlet 보안 구성, null 불가
     * @param assemblies 명시 실제 서비스 조립의 선택적 제공자, null 불가
     * @param mapper 고정 오류 직렬화기, null 불가
     * @param db 기존 독립 접근 이력 기록 도구, null 불가
     * @return 네 exact POST만 인증 worker로 허용하는 체인
     * @throws Exception 보안 체인을 조립할 수 없는 경우
     */
    @Bean
    @Order(1)
    SecurityFilterChain gradeWorkerSecurity(
            HttpSecurity http,
            ObjectProvider<Assembly> assemblies,
            ObjectMapper mapper,
            JdbcTemplate db)
            throws Exception {
        var worker = new WorkerFilter(assemblies, mapper);
        http.securityMatcher(
                        request -> {
                            String path = request.getRequestURI();
                            return path.equals(PREFIX) || path.startsWith(PREFIX + "/");
                        })
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
                                auth.requestMatchers(
                                                request ->
                                                        "POST".equals(request.getMethod())
                                                                && ROUTE.matcher(
                                                                                request
                                                                                        .getRequestURI())
                                                                        .matches())
                                        .access(
                                                (authentication, context) -> {
                                                    var current = authentication.get();
                                                    return new org.springframework.security
                                                            .authorization.AuthorizationDecision(
                                                            current != null
                                                                    && current.isAuthenticated()
                                                                    && current.getPrincipal()
                                                                            instanceof
                                                                            GradeWorkerCredentials
                                                                                    .VerifiedWorker);
                                                })
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
                                                                401,
                                                                "WORKER_AUTH_REQUIRED"))
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        error(
                                                                mapper,
                                                                request,
                                                                response,
                                                                403,
                                                                "REMOTE_EXECUTION_FORBIDDEN")))
                .addFilterBefore(worker, ExceptionTranslationFilter.class)
                .addFilterBefore(new WorkerAccessHistoryFilter(db), WorkerFilter.class);
        return http.build();
    }

    /**
     * 알려진 코드에만 고정 문구를 붙이고 외부 원문·예외를 받지 않는다.
     *
     * @param code 서버 고정 코드, null 불가
     * @param requestId 현재 서버 UUID, null 불가
     * @return 정확한 세 필드 오류 봉투
     */
    public static ObjectNode errorBody(String code, UUID requestId) {
        String message =
                switch (code) {
                    case "WORKER_AUTH_REQUIRED" -> "Worker 인증이 필요합니다.";
                    case "WORKER_TLS_REQUIRED" -> "안전한 전송이 필요합니다.";
                    case "INVALID_REMOTE_REQUEST" -> "요청 형식이 올바르지 않습니다.";
                    case "PAYLOAD_TOO_LARGE" -> "요청 크기 한도를 초과했습니다.";
                    case "REMOTE_EXECUTION_FORBIDDEN" -> "실행 권한이 없습니다.";
                    case "REMOTE_EXECUTION_NOT_CURRENT", "REMOTE_EXECUTION_EXPIRED" ->
                            "현재 실행 가능한 시도가 아닙니다.";
                    default -> "실행 서비스를 사용할 수 없습니다.";
                };
        return JsonNodeFactory.instance
                .objectNode()
                .put("code", code)
                .put("message", message)
                .put("requestId", requestId.toString());
    }

    /**
     * 현재 요청 UUID로 고정 거절 응답을 작성한다. redirect나 쿠키는 만들지 않는다.
     *
     * @param mapper 실제 JSON 직렬화기
     * @param request 이력 UUID가 설정된 요청
     * @param response 실제 응답
     * @param status 고정 HTTP 상태
     * @param code 고정 오류 코드
     * @throws IOException 응답 쓰기 실패
     * @throws IllegalStateException 이력 UUID 누락 시 조립 오류
     */
    private static void error(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code)
            throws IOException {
        UUID id = RequestAuditKernel.requestId(request);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), errorBody(code, id));
    }

    /** worker 체인 내부에서만 실제 worker 증명과 승인 경로를 접근 기록에 투영한다. */
    private static final class WorkerAccessHistoryFilter extends OncePerRequestFilter {
        private final RequestAuditKernel kernel;

        /**
         * servlet filter bean이나 관리자 세션 의존성 없이 공통 기록 도구만 만든다.
         *
         * @param db 기존 접근 이력 DB 도구, null 불가
         * @throws NullPointerException DB 도구가 없는 경우
         */
        private WorkerAccessHistoryFilter(JdbcTemplate db) {
            this.kernel = new RequestAuditKernel(db);
        }

        /**
         * worker 인증 앞에서 서버 UUID를 설정하고 완료 후 typed 인증 증명을 관측한다.
         *
         * @param request 실제 worker 요청, null 불가
         * @param response 새 서버 UUID를 제공하는 응답, null 불가
         * @param chain TLS·Bearer·인가·업무 하위 체인, null 불가
         * @throws ServletException 하위 체인 실패
         * @throws IOException 하위 전송 실패
         */
        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            kernel.record(request, response, chain, WorkerAccessHistoryFilter::observe);
        }

        /**
         * 기존 canonical 소문자 v4 경로와 실제 인증된 worker만 기록하며 관리자 활동을 갱신하지 않는다.
         *
         * @param authentication 하위 인증 증명, 부재 시 null
         * @param status 정상 동기 완료 상태, 예외·비동기 진행 시 null; 주체 투영에는 사용하지 않음
         * @param uri query 없는 실제 요청 URI, null 불가
         * @return 승인된 경로 틀 또는 UNMATCHED와 실제 WORKER 또는 ANONYMOUS 주체
         */
        private static Observation observe(
                Authentication authentication, Integer status, String uri) {
            var route = ROUTE.matcher(uri);
            String normalized =
                    route.matches() ? PREFIX + "/jobs/{jobKey}/" + route.group(1) : "UNMATCHED";
            Actor actor =
                    authentication != null
                                    && authentication.isAuthenticated()
                                    && authentication.getPrincipal()
                                            instanceof GradeWorkerCredentials.VerifiedWorker worker
                            ? new Actor(ActorKind.WORKER, null, worker.workerKey())
                            : new Actor(ActorKind.ANONYMOUS, null, null);
            return new Observation(normalized, actor);
        }
    }

    /** 토큰을 Authentication credentials/details에 보관하지 않는 체인 내부 필터다. */
    private static final class WorkerFilter extends OncePerRequestFilter {
        private final ObjectProvider<Assembly> assemblies;
        private final ObjectMapper mapper;

        /**
         * servlet filter bean 등록 없이 체인 내부 의존성만 보관한다.
         *
         * @param assemblies 명시 서비스 공급자
         * @param mapper 고정 오류 직렬화기
         */
        private WorkerFilter(ObjectProvider<Assembly> assemblies, ObjectMapper mapper) {
            this.assemblies = assemblies;
            this.mapper = mapper;
        }

        /**
         * 실제 secure 속성을 먼저 요구하고 정확히 하나의 canonical Bearer를 실제 registry에 검증한다.
         *
         * @param request 실제 connector의 TLS 속성과 원본 헤더를 가진 요청
         * @param response 쿠키·redirect 없는 실제 응답
         * @param chain 인증 뒤 실행할 체인
         * @throws ServletException 하위 체인 실패
         * @throws IOException 응답·하위 전송 실패
         */
        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            if (!request.isSecure()) {
                error(mapper, request, response, 403, "WORKER_TLS_REQUIRED");
                return;
            }
            Assembly assembly;
            try {
                assembly = assemblies.getIfAvailable();
            } catch (RuntimeException failure) {
                error(mapper, request, response, 503, "REMOTE_EXECUTION_UNAVAILABLE");
                return;
            }
            if (assembly == null) {
                error(mapper, request, response, 503, "REMOTE_EXECUTION_UNAVAILABLE");
                return;
            }
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1) {
                error(mapper, request, response, 401, "WORKER_AUTH_REQUIRED");
                return;
            }
            try {
                var verified = assembly.credentials().authenticate(headers.getFirst());
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                verified, null, List.of()));
                SecurityContextHolder.setContext(context);
            } catch (SecurityException failure) {
                error(mapper, request, response, 401, "WORKER_AUTH_REQUIRED");
                return;
            } catch (RuntimeException failure) {
                error(mapper, request, response, 503, "REMOTE_EXECUTION_UNAVAILABLE");
                return;
            }
            // 이력 필터의 finally가 인증 증명을 읽은 뒤 상위 context 필터가 정리한다.
            chain.doFilter(request, response);
        }
    }
}
