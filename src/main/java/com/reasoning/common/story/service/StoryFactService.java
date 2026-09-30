package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryService.ContentResult;
import com.reasoning.common.story.service.StoryService.VersionScope;
import com.reasoning.common.story.service.StoryService.Warning;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 고정된 사실 원장 SQL로 STORY-05~10을 처리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryFactService {
    private static final Set<String> FIELDS = Set.of("statement", "truth", "basis");
    private static final Set<String> TRUTHS = Set.of("TRUE", "FALSE", "MISREAD");
    private final StoryService stories;
    private final JdbcTemplate db;

    /**
     * 부모 잠금·감사 서비스와 같은 트랜잭션의 JDBC 실행기를 연결한다.
     *
     * @param stories 현재 인가·버전 잠금·감사 서비스
     * @param db 고정 SQL 실행기
     */
    public StoryFactService(StoryService stories, JdbcTemplate db) {
        this.stories = stories;
        this.db = db;
    }

    /**
     * 사실 원고를 제외하고 ASCII 코드 순서의 키 페이지를 조회한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size null이면 20, 아니면 1~100
     * @param afterKey 마지막 ASCII 사실 코드 또는 null
     * @param activeYn null이면 활성, false이면 비활성
     * @return 부모 수정번호와 키·상태·시각 페이지
     */
    public FactPage getFactList(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            Integer size,
            String afterKey,
            Boolean activeYn) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        if (afterKey != null) key(afterKey);

        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                null,
                scope -> {
                    List<FactKey> rows =
                            db.query(
                                    "SELECT code,active_yn,updated_at FROM story_fact WHERE"
                                        + " version_id=? AND active_yn=? AND code COLLATE \"C\">?"
                                        + " COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                                    (rs, index) ->
                                            new FactKey(
                                                    rs.getString(1),
                                                    rs.getBoolean(2),
                                                    rs.getTimestamp(3).toInstant()),
                                    scope.versionId(),
                                    activeYn == null || activeYn,
                                    afterKey == null ? "" : afterKey,
                                    limit + 1);
                    boolean next = rows.size() > limit;
                    List<FactKey> items =
                            List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
                    return new FactPage(
                            Long.toString(scope.rev()),
                            items,
                            next,
                            next ? items.get(items.size() - 1).code() : null);
                });
    }

    /**
     * 필수 조회 감사를 확정한 뒤 비활성 행도 포함한 단건 원고를 반환한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey 예약된 ASCII 사실 코드
     * @param requestId null이 아닌 서버 요청 ID
     * @return 부모 수정번호와 사실 원고
     */
    public FactDetail getFactDetail(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String itemKey,
            UUID requestId) {
        key(itemKey);
        requestId(requestId);

        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                null,
                scope -> {
                    Fact item = required(scope, itemKey);
                    stories.recordChildChange(
                            scope, actor, "facts", "CONTENT_READ", itemKey, null, requestId);
                    return new FactDetail(Long.toString(scope.rev()), item);
                });
    }

    /**
     * 비활성 코드도 예약하는 새 사실을 생성하며 근거를 문서 원고로만 저장한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param expectedRev 현재 십진 콘텐츠 수정번호
     * @param item code 필수, statement·truth·basis 선택 객체
     * @param requestId null이 아닌 서버 요청 ID
     * @return 원고 없는 생성 코드와 확정 수정번호
     */
    public ItemCreated createFact(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            JsonNode item,
            UUID requestId) {
        mutation(expectedRev, requestId);
        fields(item, true);
        String code = newCode(item.get("code"));
        String statement = text(item.get("statement"), 4000);
        String truth = truth(item.get("truth"));
        String basis = text(item.get("basis"), 8000);

        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    if (find(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
                    db.update(
                            "INSERT INTO story_fact(version_id,code,statement,truth,basis) VALUES"
                                + " (?,?,?,?,?)",
                            scope.versionId(),
                            code,
                            statement,
                            truth,
                            basis);
                    long rev =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "facts",
                                    "ITEM_CREATED",
                                    code,
                                    List.of("code", "statement", "truth", "basis"),
                                    requestId);
                    return new ItemCreated(
                            storyCode,
                            versionNo,
                            Long.toString(rev),
                            true,
                            stories.currentWarnings(scope),
                            requestId,
                            code);
                });
    }

    /**
     * 활성 사실의 명시된 원고 필드만 수정하고 null은 해당 값을 비운다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경할 수 없는 사실 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param changes 비어 있지 않은 statement·truth·basis 객체
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 변경에만 증가하는 원고 없는 결과
     */
    public ContentResult updateFact(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String itemKey,
            String expectedRev,
            JsonNode changes,
            UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        fields(changes, false);
        String requestedStatement =
                changes.has("statement") ? text(changes.get("statement"), 4000) : null;
        String requestedTruth = changes.has("truth") ? truth(changes.get("truth")) : null;
        String requestedBasis = changes.has("basis") ? text(changes.get("basis"), 8000) : null;

        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    Fact before = required(scope, itemKey);
                    if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
                    String statement =
                            changes.has("statement") ? requestedStatement : before.statement();
                    String truth = changes.has("truth") ? requestedTruth : before.truth();
                    String basis = changes.has("basis") ? requestedBasis : before.basis();
                    List<String> changed = new ArrayList<>();
                    if (!Objects.equals(statement, before.statement())) changed.add("statement");
                    if (!Objects.equals(truth, before.truth())) changed.add("truth");
                    if (!Objects.equals(basis, before.basis())) changed.add("basis");
                    if (changed.isEmpty())
                        return result(storyCode, versionNo, scope, scope.rev(), false, requestId);

                    db.update(
                            "UPDATE story_fact SET"
                                + " statement=?,truth=?,basis=?,updated_at=clock_timestamp() WHERE"
                                + " version_id=? AND code=?",
                            statement,
                            truth,
                            basis,
                            scope.versionId(),
                            itemKey);
                    long rev =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "facts",
                                    "ITEM_UPDATED",
                                    itemKey,
                                    changed,
                                    requestId);
                    return result(storyCode, versionNo, scope, rev, true, requestId);
                });
    }

    /**
     * 활성 채점 규칙에서 참조 중인 사실은 비활성화하지 않으며 근거 원고는 참조로 해석하지 않는다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 예약된 사실 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param active true는 복원, false는 비활성화
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 상태 변경에만 증가하는 원고 없는 결과
     */
    public ContentResult updateFactActive(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String itemKey,
            String expectedRev,
            boolean active,
            UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);

        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    Fact before = required(scope, itemKey);
                    if (before.activeYn() == active)
                        return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
                    if (!active
                            && db.queryForObject(
                                    "SELECT EXISTS (SELECT 1 FROM story_rubric r CROSS JOIN LATERAL"
                                        + " jsonb_array_elements(r.rule_data->'claims') claim CROSS"
                                        + " JOIN LATERAL"
                                        + " jsonb_array_elements_text(claim->'factCodes')"
                                        + " fact(code) WHERE r.version_id=? AND r.active_yn AND"
                                        + " fact.code=?)",
                                    Boolean.class,
                                    scope.versionId(),
                                    itemKey)) throw AuthException.conflict("REFERENCE_IN_USE");
                    db.update(
                            "UPDATE story_fact SET active_yn=?,updated_at=clock_timestamp() WHERE"
                                + " version_id=? AND code=?",
                            active,
                            scope.versionId(),
                            itemKey);
                    long rev =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "facts",
                                    active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                                    itemKey,
                                    List.of("activeYn"),
                                    requestId);
                    return result(storyCode, versionNo, scope, rev, true, requestId);
                });
    }

    /** 생성과 PATCH의 허용 필드 및 빈 PATCH를 검사한다. */
    private static void fields(JsonNode input, boolean create) {
        if (input == null || !input.isObject() || !create && input.isEmpty())
            throw AuthException.badRequest("INVALID_REQUEST");
        input.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!FIELDS.contains(field) && !(create && "code".equals(field)))
                                throw AuthException.badRequest("INVALID_REQUEST");
                        });
    }

    /** 누락 또는 null은 빈 원고가 아니며 텍스트의 LF와 코드포인트 길이를 검사한다. */
    private static String text(JsonNode node, int maxLength) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        return StoryService.text(node.textValue(), maxLength, true);
    }

    /** 미정 분류를 허용하되 지정된 값은 정확한 세 문자열만 받는다. */
    private static String truth(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || !TRUTHS.contains(node.textValue()))
            throw AuthException.unprocessable("INVALID_INPUT");
        return node.textValue();
    }

    /** 부모 잠금 이후 동일 버전의 사실 행을 잠근다. */
    private Fact find(VersionScope scope, String code) {
        return db.query(
                "SELECT code,statement,truth,basis,active_yn,updated_at "
                        + "FROM story_fact WHERE version_id=? AND code=? FOR UPDATE",
                rs -> rs.next() ? map(rs) : null,
                scope.versionId(),
                code);
    }

    /** 다른 버전의 행 존재 여부를 노출하지 않는다. */
    private Fact required(VersionScope scope, String code) {
        Fact item = find(scope, code);
        if (item == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return item;
    }

    /** 내부 버전 식별자를 제외한 사실 원고를 매핑한다. */
    private static Fact map(ResultSet rs) throws SQLException {
        return new Fact(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getBoolean(5),
                rs.getTimestamp(6).toInstant());
    }

    /** 변경 뒤 현재 경고를 반영한다. */
    private ContentResult result(
            String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(
                code, number, Long.toString(rev), changed, stories.currentWarnings(scope), id);
    }

    /** 경로와 커서는 정확한 ASCII 사실 코드를 요구한다. */
    private static void key(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}"))
            throw AuthException.badRequest("INVALID_REQUEST");
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

    public record FactKey(String code, boolean activeYn, Instant updatedAt) {}

    public record Fact(
            String code,
            String statement,
            String truth,
            String basis,
            boolean activeYn,
            Instant updatedAt) {}

    public record FactPage(
            String editRev, List<FactKey> items, boolean hasNext, String nextAfterKey) {}

    public record FactDetail(String editRev, Fact item) {}

    public record ItemCreated(
            String storyCode,
            int versionNo,
            String editRev,
            boolean changed,
            List<Warning> warnings,
            UUID requestId,
            String itemKey) {}
}
