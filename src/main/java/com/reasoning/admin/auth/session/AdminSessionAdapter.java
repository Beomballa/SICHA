package com.reasoning.admin.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AdminSessionAdapter {
    private static final String CONTEXT_KEY = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private final SessionRepository<? extends Session> repository;
    private final CookieSerializer cookies;
    private final TransactionTemplate independentTransaction;

    public AdminSessionAdapter(SessionRepository<? extends Session> repository, CookieSerializer cookies,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.cookies = cookies;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Generate the JDBC ID before the business PENDING transaction; do not persist it yet. */
    public PreparedSession prepare() {
        Session session = repository.createSession();
        session.setMaxInactiveInterval(Duration.ofMinutes(30));
        return new PreparedSession(session);
    }

    /** Persist after PENDING commits, in a transaction independent of the caller's business transaction. */
    public void save(PreparedSession prepared, AdminPrincipal principal) {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(principal);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of()));
        prepared.session.setAttribute(CONTEXT_KEY, context);
        independentTransaction.executeWithoutResult(status -> saveSession(repository, prepared.session));
        if (!principal.equals(findStoredPrincipal(prepared.id()).orElse(null))) {
            throw new IllegalStateException("Framework session save could not be confirmed");
        }
    }

    private static <S extends Session> void saveSession(SessionRepository<S> repository, Session session) {
        @SuppressWarnings("unchecked") S typedSession = (S) session;
        repository.save(typedSession);
    }

    /** Read the repository, not a still-unflushed request session. */
    public Optional<AdminPrincipal> findStoredPrincipal(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Session stored = repository.findById(id);
        if (stored == null) {
            return Optional.empty();
        }
        return principalFrom(stored.getAttribute(CONTEXT_KEY));
    }

    public Optional<CurrentSession> current(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession presented = request.getSession(false);
        if (presented == null) {
            return Optional.empty();
        }
        String id = presented.getId();
        return findStoredPrincipal(id).map(principal -> new CurrentSession(id, principal));
    }

    public void delete(String id) {
        if (id != null && !id.isBlank()) {
            independentTransaction.executeWithoutResult(status -> repository.deleteById(id));
        }
    }

    /** Issue only after the app ACTIVE transaction commits. Never send a cookie for PENDING. */
    public void issueCookie(String id, HttpServletRequest request, HttpServletResponse response) {
        if (findStoredPrincipal(id).isEmpty()) {
            throw new IllegalStateException("Framework session is not stored");
        }
        cookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, id));
    }

    public void clearCookie(HttpServletRequest request, HttpServletResponse response) {
        CookieSerializer.CookieValue value = new CookieSerializer.CookieValue(request, response, "");
        value.setCookieMaxAge(0);
        cookies.writeCookieValue(value);
    }

    public static Optional<AdminPrincipal> principalFrom(Object contextAttribute) {
        if (!(contextAttribute instanceof SecurityContext context)
                || !(context.getAuthentication() instanceof UsernamePasswordAuthenticationToken authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public record PreparedSession(Session session) {
        public PreparedSession {
            Objects.requireNonNull(session);
        }

        public String id() {
            return session.getId();
        }
    }

    public record CurrentSession(String id, AdminPrincipal principal) {}

    /** Only nonsecret identity/linkage is serialized into Spring Session. Authorization is rechecked per request. */
    public record AdminPrincipal(long accountId, UUID accountKey, UUID sessionKey, long authRev)
            implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        public AdminPrincipal {
            if (accountId <= 0 || authRev <= 0) {
                throw new IllegalArgumentException("Invalid admin principal identity");
            }
            Objects.requireNonNull(accountKey);
            Objects.requireNonNull(sessionKey);
        }

        @Override
        public String toString() {
            return accountKey.toString();
        }
    }
}
