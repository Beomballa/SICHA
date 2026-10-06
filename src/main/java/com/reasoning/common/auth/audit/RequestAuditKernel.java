package com.reasoning.common.auth.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 체인별 인증·경로 정책과 분리된 서버 요청 상관관계·접근 관측 도구다. bean으로 등록하지 않는다. */
public final class RequestAuditKernel {
    public static final String REQUEST_ID_ATTRIBUTE =
            RequestAuditKernel.class.getName() + ".requestId";
    private static final Logger log = LoggerFactory.getLogger(RequestAuditKernel.class);
    private final JdbcTemplate db;

    /** 인증 권한이 아닌 이력 저장용 주체 구분이다. */
    public enum ActorKind {
        ADMIN,
        MEMBER,
        WORKER,
        ANONYMOUS
    }

    /**
     * 실제 인증에서 투영한 저장 값이며 인증을 부여하거나 주체를 추정하지 않는다.
     *
     * @param kind 닫힌 주체 구분, null 불가
     * @param actorKey ADMIN/MEMBER의 실제 UUID만 허용하며 다른 주체는 null
     * @param workerKey WORKER의 실제 키만 허용하며 다른 주체는 null
     */
    public record Actor(ActorKind kind, UUID actorKey, String workerKey) {
        /**
         * 주체별 필수 키와 상호 배타성을 검증한다. 키 형식의 추가 제한은 하지 않는다.
         *
         * @param kind 주체 구분, null 불가
         * @param actorKey ADMIN/MEMBER만 비null
         * @param workerKey WORKER만 비null
         * @throws NullPointerException 주체 구분이 없는 경우
         * @throws IllegalArgumentException 주체와 키의 조합이 맞지 않는 경우
         */
        public Actor {
            Objects.requireNonNull(kind);
            boolean valid =
                    switch (kind) {
                        case ADMIN, MEMBER -> actorKey != null && workerKey == null;
                        case WORKER -> actorKey == null && workerKey != null;
                        case ANONYMOUS -> actorKey == null && workerKey == null;
                    };
            if (!valid) throw new IllegalArgumentException("INVALID_AUDIT_ACTOR");
        }
    }

    /**
     * 체인 소유자가 결정한 불변 접근 관측 값이다.
     *
     * @param route 정규화된 경로 틀 또는 UNMATCHED, null 불가
     * @param actor 실제 인증에서 투영한 기록 주체, null 불가
     */
    public record Observation(String route, Actor actor) {
        /**
         * 누락된 정책 결과를 기본 경로나 주체로 대체하지 않는다.
         *
         * @param route 정규화 경로, null 불가
         * @param actor 검증된 기록 주체, null 불가
         * @throws NullPointerException 필수 관측 값이 누락된 경우
         */
        public Observation {
            Objects.requireNonNull(route);
            Objects.requireNonNull(actor);
        }
    }

    /** 인증·경로 해석 및 필요한 체인별 완료 부작용은 해당 어댑터가 소유한다. */
    @FunctionalInterface
    public interface CompletionPolicy {
        /**
         * 하위 체인 완료 직후의 증명으로 저장 값을 결정한다. 실패하면 후속 INSERT도 생략된다.
         *
         * @param authentication 상위 context 정리 전 인증, 부재 시 null
         * @param status 정상 동기 완료의 상태 코드, 예외·비동기 진행 시 null
         * @param uri query 없는 실제 요청 URI, null 불가
         * @return 정규화 경로와 주체, null 불가
         * @throws RuntimeException 정책 처리 실패; 접근 이력의 비치명적 실패 경계에서 처리
         */
        Observation observe(Authentication authentication, Integer status, String uri);
    }

    /**
     * 기존 독립 접근 이력 저장 도구만 보관하며 트랜잭션이나 활성화를 만들지 않는다.
     *
     * @param db 접근 이력 DB 도구, null 불가
     * @throws NullPointerException DB 도구가 없는 경우
     */
    public RequestAuditKernel(JdbcTemplate db) {
        this.db = Objects.requireNonNull(db);
    }

    /**
     * 생산자가 설정한 실제 UUID만 읽고 헤더·기본값·새 UUID로 대체하지 않는다.
     *
     * @param request 서버 이력 생산자를 통과한 요청
     * @return 현재 서버 요청 UUID
     * @throws IllegalStateException 속성이 없거나 UUID 타입이 아닌 경우
     */
    public static UUID requestId(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ID_ATTRIBUTE) instanceof UUID id) return id;
        throw new IllegalStateException("REQUEST_ID_UNAVAILABLE");
    }

    /**
     * 새 서버 UUID로 입력 추적 값을 덮어쓰고 하위 체인 완료·예외·비동기 진행을 독립 접근 이력에 관측한다.
     *
     * @param request 클라이언트 추적 값을 신뢰하지 않는 실제 요청, null 불가
     * @param response 새 X-Request-Id를 제공할 응답, null 불가
     * @param chain 인증·인가·업무 하위 체인, null 불가
     * @param policy 체인 소유자의 필수 완료 정책, null 불가
     * @throws NullPointerException 필수 완료 정책이 없는 경우
     * @throws ServletException 하위 체인 실패
     * @throws IOException 하위 전송 실패; 이력 실패는 고정 경고만 남김
     */
    public void record(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            CompletionPolicy policy)
            throws ServletException, IOException {
        Objects.requireNonNull(policy);
        UUID requestId = UUID.randomUUID();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId.toString());
        Instant started = Instant.now();
        long startTick = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            Instant ended = Instant.now();
            long duration =
                    Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTick));
            // 하위 인증은 완료됐지만 상위 context 필터는 아직 인증 증명을 정리하지 않았다.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            // 예외 또는 비동기 진행 중이면 최종 응답 상태를 관측했다고 주장하지 않는다.
            Integer status = completed && !request.isAsyncStarted() ? response.getStatus() : null;
            try {
                Observation observation =
                        Objects.requireNonNull(
                                policy.observe(authentication, status, request.getRequestURI()));
                Actor actor = observation.actor();
                db.update(
                        "INSERT INTO access_history"
                            + " (kind,request_id,actor_kind,actor_key,worker_key,route,method,started_at,ended_at,duration_ms,http_status)"
                            + " VALUES ('SERVER',?,?,?,?,?,?,?,?,?,?)",
                        requestId,
                        actor.kind().name(),
                        actor.actorKey(),
                        actor.workerKey(),
                        observation.route(),
                        method(request.getMethod()),
                        Timestamp.from(started),
                        Timestamp.from(ended),
                        duration,
                        status);
            } catch (RuntimeException failure) {
                // JDBC 예외는 SQL 인자 원문을 포함할 수 있으므로 요청·주체·예외를 출력하지 않는다.
                log.warn("Access history write failed; operational investigation required");
            }
        }
    }

    /**
     * 승인된 HTTP 메서드만 기록하여 임의 입력 원문을 남기지 않는다.
     *
     * @param method 실제 요청 메서드, null 불가
     * @return 승인된 메서드 또는 UNKNOWN
     */
    private static String method(String method) {
        return switch (method) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE" -> method;
            default -> "UNKNOWN";
        };
    }
}
