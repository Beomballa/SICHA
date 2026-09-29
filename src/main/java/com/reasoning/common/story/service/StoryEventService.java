package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryService.ContentResult;
import com.reasoning.common.story.service.StoryService.VersionScope;
import com.reasoning.common.story.service.StoryService.Warning;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 고정된 시간선 SQL로 STORY-05~10을 처리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryEventService {
    private static final Set<String> FIELDS = Set.of("startMin", "endMin", "actualText", "apparentText");
    private final StoryService stories;
    private final JdbcTemplate db;

    /**
     * 부모 잠금과 필수 감사를 제공하는 서비스 및 고정 SQL 실행기를 연결한다.
     * @param stories 현재 인가·버전 잠금·감사 서비스
     * @param db 같은 트랜잭션을 사용하는 JDBC 실행기
     */
    public StoryEventService(StoryService stories, JdbcTemplate db) {
        this.stories = stories;
        this.db = db;
    }

    /**
     * 원고 없이 시간선 코드를 ASCII 순서로 조회한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size null이면 20, 아니면 1~100
     * @param afterKey 마지막 ASCII 코드 또는 null
     * @param activeYn null이면 활성, false이면 비활성
     * @return 수정번호와 코드·상태·시각 페이지
     */
    public EventPage getEventList(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        if (afterKey != null) key(afterKey);

        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<EventKey> rows = db.query("SELECT code,active_yn,updated_at FROM story_event WHERE version_id=? AND active_yn=? "
                            + "AND code COLLATE \"C\">? COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new EventKey(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()),
                    scope.versionId(), activeYn == null || activeYn, afterKey == null ? "" : afterKey, limit + 1);
            boolean next = rows.size() > limit;
            List<EventKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new EventPage(Long.toString(scope.rev()), items, next, next ? items.get(items.size() - 1).code() : null);
        });
    }

    /**
     * 필수 조회 감사를 확정한 뒤 비활성 시간선도 포함해 원고를 반환한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey 예약된 ASCII 시간선 코드
     * @param requestId null이 아닌 서버 요청 ID
     * @return 수정번호와 단건 원고
     */
    public EventDetail getEventDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        key(itemKey);
        requestId(requestId);

        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Event item = required(scope, itemKey);
            stories.recordChildChange(scope, actor, "events", "CONTENT_READ", itemKey, null, requestId);
            return new EventDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * 비활성 행도 예약하는 불변 코드로 시간선을 생성한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param expectedRev 현재 십진 콘텐츠 수정번호
     * @param item code 필수, 시간과 원고는 선택인 객체
     * @param requestId null이 아닌 서버 요청 ID
     * @return 생성 코드와 확정 수정번호를 포함하는 원고 없는 결과
     */
    public ItemCreated createEvent(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        fields(item, true);
        String code = newCode(item.get("code"));
        Integer start = minute(item.get("startMin"));
        Integer end = minute(item.get("endMin"));
        time(start, end);
        String actual = text(item.get("actualText"));
        String apparent = text(item.get("apparentText"));

        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (find(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
            db.update("INSERT INTO story_event(version_id,code,start_min,end_min,actual_text,apparent_text) VALUES (?,?,?,?,?,?)",
                    scope.versionId(), code, start, end, actual, apparent);
            long rev = stories.recordChildChange(scope, actor, "events", "ITEM_CREATED", code,
                    List.of("code", "startMin", "endMin", "actualText", "apparentText"), requestId);
            return new ItemCreated(storyCode, versionNo, Long.toString(rev), true, stories.currentWarnings(scope), requestId, code);
        });
    }

    /**
     * 활성 시간선의 명시적 필드만 수정하며 시간 범위는 병합된 값으로 검사한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경할 수 없는 시간선 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param changes 비어 있지 않은 허용 필드 객체; null은 해당 값을 삭제한다
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 변경에만 증가하는 원고 없는 결과
     */
    public ContentResult updateEvent(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, JsonNode changes, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        fields(changes, false);
        Integer requestedStart = changes.has("startMin") ? minute(changes.get("startMin")) : null;
        Integer requestedEnd = changes.has("endMin") ? minute(changes.get("endMin")) : null;
        String requestedActual = changes.has("actualText") ? text(changes.get("actualText")) : null;
        String requestedApparent = changes.has("apparentText") ? text(changes.get("apparentText")) : null;

        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Event before = required(scope, itemKey);
            if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
            Integer start = changes.has("startMin") ? requestedStart : before.startMin();
            Integer end = changes.has("endMin") ? requestedEnd : before.endMin();
            time(start, end);
            String actual = changes.has("actualText") ? requestedActual : before.actualText();
            String apparent = changes.has("apparentText") ? requestedApparent : before.apparentText();
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(start, before.startMin())) changed.add("startMin");
            if (!Objects.equals(end, before.endMin())) changed.add("endMin");
            if (!Objects.equals(actual, before.actualText())) changed.add("actualText");
            if (!Objects.equals(apparent, before.apparentText())) changed.add("apparentText");
            if (changed.isEmpty()) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);

            db.update("UPDATE story_event SET start_min=?,end_min=?,actual_text=?,apparent_text=?,updated_at=clock_timestamp() "
                            + "WHERE version_id=? AND code=?",
                    start, end, actual, apparent, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "events", "ITEM_UPDATED", itemKey, changed, requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /**
     * 예약된 같은 행을 비활성화하거나 복원한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 예약된 시간선 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param active true는 복원, false는 비활성화
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 상태 변경에만 증가하는 원고 없는 결과
     */
    public ContentResult updateEventActive(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);

        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Event before = required(scope, itemKey);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            db.update("UPDATE story_event SET active_yn=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    active, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "events", active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                    itemKey, List.of("activeYn"), requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /** 생성과 PATCH의 허용 필드 및 빈 PATCH를 검사한다. */
    private static void fields(JsonNode input, boolean create) {
        if (input == null || !input.isObject() || !create && input.isEmpty()) throw AuthException.badRequest("INVALID_REQUEST");
        input.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field) && !(create && "code".equals(field))) throw AuthException.badRequest("INVALID_REQUEST");
        });
    }

    /** 누락 또는 null은 미정 시각이며 JSON 정수의 저장 범위를 제한한다. */
    private static Integer minute(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0)
            throw AuthException.unprocessable("INVALID_INPUT");
        return node.intValue();
    }

    /** 시작 없는 종료 및 역전 구간을 거절한다. */
    private static void time(Integer start, Integer end) {
        if (end != null && (start == null || end < start)) throw AuthException.unprocessable("INVALID_INPUT");
    }

    /** 누락 또는 null은 원고 없음이며 LF와 코드포인트 길이를 검사한다. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        return StoryService.text(node.textValue(), 8000, true);
    }

    /** 부모 잠금 이후 동일 버전의 시간선 행을 잠근다. */
    private Event find(VersionScope scope, String code) {
        return db.query("SELECT code,start_min,end_min,actual_text,apparent_text,active_yn,updated_at "
                        + "FROM story_event WHERE version_id=? AND code=? FOR UPDATE",
                rs -> rs.next() ? map(rs) : null, scope.versionId(), code);
    }

    /** 다른 버전의 행 존재 여부를 노출하지 않는다. */
    private Event required(VersionScope scope, String code) {
        Event item = find(scope, code);
        if (item == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return item;
    }

    /** 내부 버전 식별자를 제외한 원고를 매핑한다. */
    private static Event map(ResultSet rs) throws SQLException {
        return new Event(rs.getString(1), (Integer) rs.getObject(2), (Integer) rs.getObject(3),
                rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant());
    }

    /** 변경 뒤 현재 경고를 반영한다. */
    private ContentResult result(String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(code, number, Long.toString(rev), changed, stories.currentWarnings(scope), id);
    }

    /** 경로와 커서는 정확한 ASCII 시간선 코드를 요구한다. */
    private static void key(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 생성 코드의 타입과 문법을 검사한다. */
    private static String newCode(JsonNode node) {
        if (node == null || !node.isTextual() || !node.textValue().matches("[A-Z0-9_]{1,32}"))
            throw AuthException.unprocessable("INVALID_INPUT");
        return node.textValue();
    }

    /** 수정번호와 감사 요청 ID의 누락을 거절한다. */
    private static void mutation(String rev, UUID id) {
        if (rev == null) throw AuthException.badRequest("INVALID_REQUEST");
        requestId(id);
    }

    /** 조회와 변경의 필수 감사 요청 ID를 검사한다. */
    private static void requestId(UUID id) {
        if (id == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    public record EventKey(String code, boolean activeYn, Instant updatedAt) {}
    public record Event(String code, Integer startMin, Integer endMin, String actualText, String apparentText,
            boolean activeYn, Instant updatedAt) {}
    public record EventPage(String editRev, List<EventKey> items, boolean hasNext, String nextAfterKey) {}
    public record EventDetail(String editRev, Event item) {}
    public record ItemCreated(String storyCode, int versionNo, String editRev, boolean changed,
            List<Warning> warnings, UUID requestId, String itemKey) {}
}
