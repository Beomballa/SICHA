package com.reasoning.admin.auth.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.AuthException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class NavigationController {
    private static final Set<String> SCREENS =
            Set.of(
                    "ADMIN_HOME",
                    "ADMIN_AUTH_MANAGE",
                    "ADMIN_ACCOUNTS",
                    "ADMIN_ACCOUNT_DETAIL",
                    "ADMIN_STORIES",
                    "ADMIN_STORY_EDITOR",
                    "ADMIN_STORY_ACCESS");
    private static final Set<String> FIELDS =
            Set.of("eventKey", "screenCode", "fromScreenCode", "occurredAt");
    private static final int MAX_ACTORS = 4096;
    private static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final LinkedHashMap<UUID, Long> lastAccepted = new LinkedHashMap<>();
    private final JdbcTemplate db;
    private final AdminSessionAdapter sessions;

    public NavigationController(JdbcTemplate db, AdminSessionAdapter sessions) {
        this.db = db;
        this.sessions = sessions;
    }

    @PostMapping("/admin/api/history/navigation")
    public ResponseEntity<Void> navigate(@RequestBody JsonNode body, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal actor)
                || !sessions.current(request)
                        .map(current -> actor.equals(current.principal()))
                        .orElse(false)) {
            throw AuthException.unauthorized("AUTH_REQUIRED");
        }
        Event event = parse(body);
        UUID actorKey = actor.accountKey();
        synchronized (lastAccepted) {
            // Check idempotency before throttling: replay must not alter the original record or
            // become a 429.
            boolean duplicate =
                    db.query(
                            "SELECT 1 FROM access_history WHERE kind='NAV' AND actor_kind='ADMIN' "
                                    + "AND actor_key=? AND event_key=? LIMIT 1",
                            rs -> {
                                return rs.next();
                            },
                            actorKey,
                            event.key());
            if (duplicate) return ResponseEntity.noContent().build();
            long now = System.nanoTime();
            Long previous = lastAccepted.get(actorKey);
            if (previous != null && now - previous >= 0 && now - previous < INTERVAL_NANOS) {
                throw new AuthException(429, "RATE_LIMITED", "RATE_LIMITED");
            }
            if (lastAccepted.size() >= MAX_ACTORS && previous == null) {
                Iterator<Map.Entry<UUID, Long>> entries = lastAccepted.entrySet().iterator();
                while (entries.hasNext()) {
                    if (now - entries.next().getValue() >= INTERVAL_NANOS) entries.remove();
                }
                if (lastAccepted.size() >= MAX_ACTORS)
                    lastAccepted.remove(lastAccepted.keySet().iterator().next());
            }
            db.update(
                    "INSERT INTO access_history"
                        + " (kind,request_id,event_key,actor_kind,actor_key,screen_code,from_screen_code,client_at)"
                        + " VALUES ('NAV',?,?,'ADMIN',?,?,?,?) ON CONFLICT"
                        + " (actor_kind,actor_key,event_key) WHERE kind='NAV' DO NOTHING",
                    UUID.randomUUID(),
                    event.key(),
                    actorKey,
                    event.screen(),
                    event.from(),
                    event.occurredAt() == null ? null : Timestamp.from(event.occurredAt()));
            lastAccepted.put(actorKey, now);
        }
        return ResponseEntity.noContent().build();
    }

    private static Event parse(JsonNode body) {
        if (body == null || !body.isObject()) throw AuthException.badRequest("INVALID_REQUEST");
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            if (!FIELDS.contains(names.next())) throw AuthException.badRequest("INVALID_REQUEST");
        }
        JsonNode keyNode = body.get("eventKey");
        JsonNode screenNode = body.get("screenCode");
        if (keyNode == null
                || !keyNode.isTextual()
                || screenNode == null
                || !screenNode.isTextual()) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
        String keyText = keyNode.textValue();
        UUID key;
        try {
            if (!keyText.matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
                throw AuthException.unprocessable("INVALID_EVENT_KEY");
            }
            key = UUID.fromString(keyText);
        } catch (IllegalArgumentException failure) {
            throw AuthException.unprocessable("INVALID_EVENT_KEY");
        }
        String screen = screenNode.textValue();
        JsonNode fromNode = body.get("fromScreenCode");
        String from =
                fromNode == null || fromNode.isNull()
                        ? null
                        : fromNode.isTextual() ? fromNode.textValue() : "";
        if (screen.length() > 64
                || !SCREENS.contains(screen)
                || from != null && (from.length() > 64 || !SCREENS.contains(from))) {
            throw AuthException.unprocessable("INVALID_SCREEN_CODE");
        }
        JsonNode timeNode = body.get("occurredAt");
        Instant occurredAt = null;
        if (timeNode != null && !timeNode.isNull()) {
            if (!timeNode.isTextual()) throw AuthException.badRequest("INVALID_REQUEST");
            try {
                occurredAt = Instant.parse(timeNode.textValue());
            } catch (DateTimeParseException failure) {
                throw AuthException.unprocessable("INVALID_OCCURRED_AT");
            }
        }
        return new Event(key, screen, from, occurredAt);
    }

    private record Event(UUID key, String screen, String from, Instant occurredAt) {}
}
