package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 소항목과 단서의 활성 연결을 고정 SQL과 부모 잠금으로 관리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryRubricClueService {
    private final StoryService stories;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public StoryRubricClueService(StoryService stories, JdbcTemplate db, ObjectMapper mapper) {
        this.stories = stories;
        this.db = db;
        this.mapper = mapper;
    }

    /**
     * rubricCode~clueCode 의미 순서의 ASCII 튜플 커서로 연결 키만 조회한다.
     *
     * @param sid 현재 세션 ID, null이면 인증 거절
     * @param actor 서버 검증 행위자
     * @param storyCode 접근 가능한 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size 1~100, null이면 20
     * @param afterKey 마지막 복합키 또는 null
     * @param activeYn null/true는 활성, false는 비활성
     * @return 원고 없는 키·상태·시각과 수정번호
     */
    public LinkPage getRubricClueList(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            Integer size,
            String afterKey,
            Boolean activeYn) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        String[] cursor = afterKey == null ? null : key(afterKey);
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                null,
                scope -> {
                    List<LinkKey> rows =
                            db.query(
                                    "SELECT rubric_code,clue_code,active_yn,updated_at FROM"
                                        + " rubric_clue WHERE version_id=? AND active_yn=? AND"
                                        + " (rubric_code COLLATE \"C\">? COLLATE \"C\" OR"
                                        + " (rubric_code COLLATE \"C\"=? COLLATE \"C\" AND"
                                        + " clue_code COLLATE \"C\">? COLLATE \"C\")) ORDER BY"
                                        + " rubric_code COLLATE \"C\",clue_code COLLATE \"C\" LIMIT"
                                        + " ?",
                                    (rs, index) ->
                                            new LinkKey(
                                                    rs.getString(1) + "~" + rs.getString(2),
                                                    rs.getBoolean(3),
                                                    rs.getTimestamp(4).toInstant()),
                                    scope.versionId(),
                                    activeYn == null || activeYn,
                                    cursor == null ? "" : cursor[0],
                                    cursor == null ? "" : cursor[0],
                                    cursor == null ? "" : cursor[1],
                                    limit + 1);
                    boolean next = rows.size() > limit;
                    List<LinkKey> items =
                            List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
                    return new LinkPage(
                            Long.toString(scope.rev()),
                            items,
                            next,
                            next ? items.get(items.size() - 1).itemKey() : null);
                });
    }

    /**
     * 비활성 연결도 감사 후 단건으로 반환한다.
     *
     * @param sid 현재 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 접근 가능한 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey rubricCode~clueCode 형식의 불변 키
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 감사가 확정된 연결 원고와 수정번호
     */
    public LinkDetail getRubricClueDetail(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String itemKey,
            UUID requestId) {
        String[] keys = key(itemKey);
        requestId(requestId);
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                null,
                scope -> {
                    Link item = required(scope, keys);
                    stories.recordChildChange(
                            scope, actor, "rubric-clues", "CONTENT_READ", itemKey, null, requestId);
                    return new LinkDetail(Long.toString(scope.rev()), item);
                });
    }

    /**
     * 같은 버전의 활성 소항목과 활성 단서를 명시적으로 연결한다.
     *
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 활성 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param expectedRev 현재 십진 문자열 수정번호
     * @param item rubricCode/clueCode 필수, linkText는 nullable 4,000 코드포인트
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 생성된 복합키·수정번호·경고, 원고 제외
     */
    public ItemCreated createRubricClue(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            JsonNode item,
            UUID requestId) {
        mutation(expectedRev, requestId);
        fields(item, true);
        String rubric = newCode(item.get("rubricCode"));
        String clue = newCode(item.get("clueCode"));
        String linkText = text(item.get("linkText"));
        String itemKey = rubric + "~" + clue;
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    if (find(scope, new String[] {rubric, clue}) != null)
                        throw AuthException.conflict("ITEM_EXISTS");
                    activeTargets(scope, rubric, clue);
                    db.update(
                            "INSERT INTO rubric_clue(version_id,rubric_code,clue_code,link_text)"
                                + " VALUES (?,?,?,?)",
                            scope.versionId(),
                            rubric,
                            clue,
                            linkText);
                    long rev =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "rubric-clues",
                                    "ITEM_CREATED",
                                    itemKey,
                                    List.of("rubricCode", "clueCode", "linkText"),
                                    requestId);
                    return new ItemCreated(
                            storyCode,
                            versionNo,
                            Long.toString(rev),
                            true,
                            stories.currentWarnings(scope),
                            requestId,
                            itemKey);
                });
    }

    /**
     * 연결 설명을 수정하며 키와 활성 여부는 별도 요청으로만 변경한다.
     *
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 활성 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey rubricCode~clueCode 형식의 불변 키
     * @param expectedRev 무변경에도 확인하는 십진 수정번호
     * @param changes linkText 하나의 객체, null은 원고 비우기
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 실제 변경에만 증가하는 수정번호·변경 여부·경고
     */
    public ContentResult updateRubricClue(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String itemKey,
            String expectedRev,
            JsonNode changes,
            UUID requestId) {
        String[] keys = key(itemKey);
        mutation(expectedRev, requestId);
        fields(changes, false);
        String requested = text(changes.get("linkText"));
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    Link before = required(scope, keys);
                    if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
                    String next = changes.has("linkText") ? requested : before.linkText();
                    if (Objects.equals(next, before.linkText()))
                        return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
                    db.update(
                            "UPDATE rubric_clue SET link_text=?,updated_at=clock_timestamp() "
                                    + "WHERE version_id=? AND rubric_code=? AND clue_code=?",
                            next,
                            scope.versionId(),
                            keys[0],
                            keys[1]);
                    long rev =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "rubric-clues",
                                    "ITEM_UPDATED",
                                    itemKey,
                                    List.of("linkText"),
                                    requestId);
                    return result(storyCode, versionNo, scope, rev, true, requestId);
                });
    }

    /**
     * 규칙에서 쓰는 연결의 해제를 차단하고 복원에는 양 끝 활성 상태를 요구한다.
     *
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 활성 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey 예약된 rubricCode~clueCode 키
     * @param expectedRev 무변경에도 확인하는 십진 수정번호
     * @param active true는 복원, false는 논리 삭제
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 원고 없는 변경 여부·수정번호·경고
     */
    public ContentResult updateRubricClueActive(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String itemKey,
            String expectedRev,
            boolean active,
            UUID requestId) {
        String[] keys = key(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    Link before = required(scope, keys);
                    if (before.activeYn() == active)
                        return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
                    if (active) activeTargets(scope, keys[0], keys[1]);
                    else {
                        String rule =
                                db.query(
                                        "SELECT rule_data::text FROM story_rubric WHERE"
                                            + " version_id=? AND code=? AND active_yn",
                                        rs -> rs.next() ? rs.getString(1) : null,
                                        scope.versionId(),
                                        keys[0]);
                        if (rule != null) {
                            try {
                                if (StoryRubricRule.clueCodes(mapper.readTree(rule))
                                        .contains(keys[1]))
                                    throw AuthException.conflict("REFERENCE_IN_USE");
                            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                throw AuthException.unavailable("STORY_UNAVAILABLE");
                            }
                        }
                    }
                    db.update(
                            "UPDATE rubric_clue SET active_yn=?,updated_at=clock_timestamp() "
                                    + "WHERE version_id=? AND rubric_code=? AND clue_code=?",
                            active,
                            scope.versionId(),
                            keys[0],
                            keys[1]);
                    long rev =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "rubric-clues",
                                    active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                                    itemKey,
                                    List.of("activeYn"),
                                    requestId);
                    return result(storyCode, versionNo, scope, rev, true, requestId);
                });
    }

    private void activeTargets(VersionScope scope, String rubric, String clue) {
        if (db.queryForObject(
                                "SELECT count(*) FROM story_rubric WHERE version_id=? AND code=?"
                                    + " AND active_yn",
                                Integer.class,
                                scope.versionId(),
                                rubric)
                        == 0
                || db.queryForObject(
                                "SELECT count(*) FROM story_clue WHERE version_id=? AND code=? AND"
                                    + " active_yn",
                                Integer.class,
                                scope.versionId(),
                                clue)
                        == 0) throw AuthException.unprocessable("INVALID_INPUT");
    }

    private Link find(VersionScope scope, String[] keys) {
        return db.query(
                "SELECT rubric_code,clue_code,link_text,active_yn,updated_at FROM rubric_clue "
                        + "WHERE version_id=? AND rubric_code=? AND clue_code=? FOR UPDATE",
                rs -> rs.next() ? map(rs) : null,
                scope.versionId(),
                keys[0],
                keys[1]);
    }

    private Link required(VersionScope scope, String[] keys) {
        Link result = find(scope, keys);
        if (result == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return result;
    }

    private static Link map(ResultSet rs) throws SQLException {
        return new Link(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getBoolean(4),
                rs.getTimestamp(5).toInstant());
    }

    private ContentResult result(
            String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(
                code, number, Long.toString(rev), changed, stories.currentWarnings(scope), id);
    }

    private static void fields(JsonNode node, boolean create) {
        if (node == null || !node.isObject() || !create && node.isEmpty())
            throw AuthException.badRequest("INVALID_REQUEST");
        node.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!("linkText".equals(field)
                                    || create
                                            && ("rubricCode".equals(field)
                                                    || "clueCode".equals(field))))
                                throw AuthException.badRequest("INVALID_REQUEST");
                        });
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        return StoryService.text(node.textValue(), 4000, true);
    }

    private static String[] key(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}~[A-Z0-9_]{1,32}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        return value.split("~", -1);
    }

    private static String newCode(JsonNode value) {
        if (value == null || !value.isTextual() || !value.textValue().matches("[A-Z0-9_]{1,32}"))
            throw AuthException.unprocessable("INVALID_INPUT");
        return value.textValue();
    }

    private static void mutation(String rev, UUID id) {
        if (rev == null) throw AuthException.badRequest("INVALID_REQUEST");
        requestId(id);
    }

    private static void requestId(UUID id) {
        if (id == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    public record LinkKey(String itemKey, boolean activeYn, Instant updatedAt) {}

    public record Link(
            String rubricCode,
            String clueCode,
            String linkText,
            boolean activeYn,
            Instant updatedAt) {}

    public record LinkPage(
            String editRev, List<LinkKey> items, boolean hasNext, String nextAfterKey) {}

    public record LinkDetail(String editRev, Link item) {}

    public record ItemCreated(
            String storyCode,
            int versionNo,
            String editRev,
            boolean changed,
            List<Warning> warnings,
            UUID requestId,
            String itemKey) {}
}
