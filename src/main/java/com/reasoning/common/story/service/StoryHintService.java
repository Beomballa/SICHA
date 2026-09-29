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

/** 고정된 힌트 원고 SQL로 STORY-05~10을 처리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryHintService {
    private static final Set<String> FIELDS = Set.of("level", "body");
    private final StoryService stories;
    private final JdbcTemplate db;

    public StoryHintService(StoryService stories, JdbcTemplate db) {
        this.stories = stories;
        this.db = db;
    }

    /**
     * 원고 없이 힌트 키를 ASCII 순서로 조회한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size null이면 20, 아니면 1~100
     * @param afterKey 마지막 ASCII 힌트 코드 또는 null
     * @param activeYn null이면 활성, false이면 비활성
     * @return 수정번호와 키·상태·시각 목록
     */
    public HintPage getHintList(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        if (afterKey != null) key(afterKey);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<HintKey> rows = db.query("SELECT code,active_yn,updated_at FROM story_hint WHERE version_id=? AND active_yn=? "
                            + "AND code COLLATE \"C\">? COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new HintKey(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()),
                    scope.versionId(), activeYn == null || activeYn, afterKey == null ? "" : afterKey, limit + 1);
            boolean next = rows.size() > limit;
            List<HintKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new HintPage(Long.toString(scope.rev()), items, next, next ? items.get(items.size() - 1).code() : null);
        });
    }

    /**
     * 필수 조회 감사를 확정한 뒤 비활성 힌트도 포함한 원고를 반환한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey 예약된 ASCII 힌트 코드
     * @param requestId null이 아닌 서버 요청 ID
     * @return 수정번호와 힌트 원고
     */
    public HintDetail getHintDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        key(itemKey);
        requestId(requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Hint item = required(scope, itemKey);
            stories.recordChildChange(scope, actor, "hints", "CONTENT_READ", itemKey, null, requestId);
            return new HintDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * 코드와 비활성 행까지 점유한 단계를 확인하고 새 힌트를 생성한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param expectedRev 현재 십진 콘텐츠 수정번호
     * @param item code·level 필수, body 선택 객체
     * @param requestId null이 아닌 서버 요청 ID
     * @return 새 itemKey와 수정번호를 포함한 원고 없는 결과
     */
    public ItemCreated createHint(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        fields(item, true);
        String code = newCode(item.get("code"));
        short level = level(item.get("level"));
        String body = body(item.get("body"));
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (find(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
            if (occupied(scope, code, level)) throw AuthException.conflict("SLOT_CONFLICT");
            db.update("INSERT INTO story_hint(version_id,code,level,body) VALUES (?,?,?,?)",
                    scope.versionId(), code, level, body);
            long rev = stories.recordChildChange(scope, actor, "hints", "ITEM_CREATED", code,
                    List.of("code", "level", "body"), requestId);
            return new ItemCreated(storyCode, versionNo, Long.toString(rev), true, stories.currentWarnings(scope), requestId, code);
        });
    }

    /**
     * 활성 힌트의 단계 또는 원고만 변경하며 비활성 단계 점유도 보호한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경할 수 없는 힌트 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param changes 비어 있지 않은 level·body 객체; body null은 원고 삭제
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 변경에만 증가하는 원고 없는 결과
     */
    public ContentResult updateHint(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, JsonNode changes, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        fields(changes, false);
        Short requestedLevel = changes.has("level") ? level(changes.get("level")) : null;
        String requestedBody = changes.has("body") ? body(changes.get("body")) : null;
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Hint before = required(scope, itemKey);
            if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
            short targetLevel = requestedLevel == null ? before.level() : requestedLevel;
            String targetBody = changes.has("body") ? requestedBody : before.body();
            List<String> changed = new ArrayList<>();
            if (targetLevel != before.level()) changed.add("level");
            if (!Objects.equals(targetBody, before.body())) changed.add("body");
            if (changed.isEmpty()) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (targetLevel != before.level() && occupied(scope, itemKey, targetLevel))
                throw AuthException.conflict("SLOT_CONFLICT");
            db.update("UPDATE story_hint SET level=?,body=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    targetLevel, targetBody, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "hints", "ITEM_UPDATED", itemKey, changed, requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /**
     * 기존 힌트를 같은 행에서 비활성화하거나 복원한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 예약된 힌트 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param active true는 복원, false는 비활성화
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 상태 변경에만 증가하는 원고 없는 결과
     */
    public ContentResult updateHintActive(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Hint before = required(scope, itemKey);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            db.update("UPDATE story_hint SET active_yn=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    active, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "hints", active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
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

    /** 단계는 JSON 정수 1~3만 허용한다. */
    private static short level(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > 3)
            throw AuthException.unprocessable("INVALID_INPUT");
        return (short) node.intValue();
    }

    /** 누락 또는 명시적 null은 원고 없음이며 텍스트는 LF와 코드포인트 길이를 정규화한다. */
    private static String body(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        return StoryService.text(node.textValue(), 4000, true);
    }

    /** 동일 버전의 비활성 행도 단계 점유로 처리한다. */
    private boolean occupied(VersionScope scope, String code, short level) {
        return db.queryForObject("SELECT count(*) FROM story_hint WHERE version_id=? AND level=? AND code<>?",
                Integer.class, scope.versionId(), level, code) > 0;
    }

    /** 부모 잠금 이후 동일 버전의 힌트 행을 잠근다. */
    private Hint find(VersionScope scope, String code) {
        return db.query("SELECT code,level,body,active_yn,updated_at FROM story_hint WHERE version_id=? AND code=? FOR UPDATE",
                rs -> rs.next() ? map(rs) : null, scope.versionId(), code);
    }

    /** 다른 버전의 존재 여부를 노출하지 않는다. */
    private Hint required(VersionScope scope, String code) {
        Hint hint = find(scope, code);
        if (hint == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return hint;
    }

    /** 내부 버전 식별자를 제외하고 조회 원고를 매핑한다. */
    private static Hint map(ResultSet rs) throws SQLException {
        return new Hint(rs.getString(1), rs.getShort(2), rs.getString(3), rs.getBoolean(4), rs.getTimestamp(5).toInstant());
    }

    /** 변경 뒤 현재 경고를 반영한다. */
    private ContentResult result(String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(code, number, Long.toString(rev), changed, stories.currentWarnings(scope), id);
    }

    /** 경로와 커서는 정확한 ASCII 힌트 코드를 요구한다. */
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

    public record HintKey(String code, boolean activeYn, Instant updatedAt) {}
    public record Hint(String code, short level, String body, boolean activeYn, Instant updatedAt) {}
    public record HintPage(String editRev, List<HintKey> items, boolean hasNext, String nextAfterKey) {}
    public record HintDetail(String editRev, Hint item) {}
    public record ItemCreated(String storyCode, int versionNo, String editRev, boolean changed,
            List<Warning> warnings, UUID requestId, String itemKey) {}
}
