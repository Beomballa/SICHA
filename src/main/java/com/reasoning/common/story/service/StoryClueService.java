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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 고정된 단서·역할 배정 SQL로 STORY-05~10을 처리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryClueService {
    private static final Set<String> FIELDS = Set.of("title", "body", "personCode", "scope", "sourceText");
    private final StoryService stories;
    private final JdbcTemplate db;

    public StoryClueService(StoryService stories, JdbcTemplate db) {
        this.stories = stories;
        this.db = db;
    }

    /**
     * 원고 없이 단서 키를 조회한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size null이면 20, 아니면 1~100
     * @param afterKey 마지막 ASCII 단서 코드 또는 null
     * @param activeYn null이면 활성, false이면 비활성
     * @return 수정번호와 키·상태·시각 목록
     */
    public CluePage getClueList(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = limit(size);
        if (afterKey != null) key(afterKey);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<ClueKey> rows = db.query("SELECT code,active_yn,updated_at FROM story_clue WHERE version_id=? AND active_yn=? "
                            + "AND code COLLATE \"C\">? COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new ClueKey(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()),
                    scope.versionId(), activeYn == null || activeYn, afterKey == null ? "" : afterKey, limit + 1);
            boolean next = rows.size() > limit;
            List<ClueKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new CluePage(Long.toString(scope.rev()), items, next, next ? items.get(items.size() - 1).code() : null);
        });
    }

    /**
     * 필수 조회 감사를 확정한 후 단서 원고를 반환한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey 예약된 ASCII 단서 코드
     * @param requestId null이 아닌 서버 요청 ID
     * @return 비활성 행도 포함하는 단건과 수정번호
     */
    public ClueDetail getClueDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        key(itemKey);
        requestId(requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Clue item = requiredClue(scope, itemKey);
            stories.recordChildChange(scope, actor, "clues", "CONTENT_READ", itemKey, null, requestId);
            return new ClueDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * 새 단서 키를 예약하고 필수 감사를 같은 거래에서 확정한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 편집 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param expectedRev null이 아닌 현재 십진 수정번호
     * @param item code·title 필수, body·personCode·scope·sourceText 선택인 객체; scope 생략은 ROLE
     * @param requestId null이 아닌 서버 요청 ID
     * @return 새 itemKey 및 수정번호를 포함한 원고 없는 결과
     * @throws AuthException 예약된 키·비활성 인물·잘못된 입력·감사 실패 시
     */
    public ItemCreated createClue(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        fields(item, true);
        String code = newCode(item.get("code"));
        Map<String, String> values = values(item, true);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (findClue(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
            activePerson(scope, values.get("personCode"));
            db.update("INSERT INTO story_clue(version_id,code,title,body,person_code,scope,source_text) VALUES (?,?,?,?,?,?,?)",
                    scope.versionId(), code, values.get("title"), values.get("body"), values.get("personCode"),
                    values.get("scope"), values.get("sourceText"));
            long rev = stories.recordChildChange(scope, actor, "clues", "ITEM_CREATED", code,
                    List.of("code", "title", "body", "personCode", "scope", "sourceText"), requestId);
            return created(storyCode, versionNo, scope, rev, requestId, code);
        });
    }

    /**
     * 활성 단서의 지정한 원고만 수정한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 편집 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경 불가능한 단서 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param changes 비어 있지 않은 허용 필드 객체; 선택 필드 null은 삭제, title·scope null은 오류
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 값이 바뀌었을 때만 증가하는 원고 없는 결과
     * @throws AuthException 활성 배정이 있는 COMMON 전환·비활성 인물·감사 실패 시
     */
    public ContentResult updateClue(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, JsonNode changes, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        fields(changes, false);
        Map<String, String> requested = values(changes, false);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Clue before = requiredClue(scope, itemKey);
            if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
            String title = requested.getOrDefault("title", before.title());
            String body = requested.getOrDefault("body", before.body());
            String person = requested.getOrDefault("personCode", before.personCode());
            String targetScope = requested.getOrDefault("scope", before.scope());
            String source = requested.getOrDefault("sourceText", before.sourceText());
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(title, before.title())) changed.add("title");
            if (!Objects.equals(body, before.body())) changed.add("body");
            if (!Objects.equals(person, before.personCode())) changed.add("personCode");
            if (!Objects.equals(targetScope, before.scope())) changed.add("scope");
            if (!Objects.equals(source, before.sourceText())) changed.add("sourceText");
            if (changed.isEmpty()) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (changed.contains("personCode")) activePerson(scope, person);
            if ("COMMON".equals(targetScope) && activeAssignments(scope, itemKey))
                throw AuthException.conflict("REFERENCE_IN_USE");
            db.update("UPDATE story_clue SET title=?,body=?,person_code=?,scope=?,source_text=?,updated_at=clock_timestamp() "
                            + "WHERE version_id=? AND code=?",
                    title, body, person, targetScope, source, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "clues", "ITEM_UPDATED", itemKey, changed, requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /**
     * 활성 배정 참조를 보호하며 단서의 논리 상태만 변경한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 편집 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 예약된 단서 코드
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param active true는 복원, false는 비활성화
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 변경에만 증가하는 원고 없는 결과
     * @throws AuthException 활성 배정·채점 참조, 비활성 인물 또는 감사 실패 시
     */
    public ContentResult updateClueActive(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Clue before = requiredClue(scope, itemKey);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (active) activePerson(scope, before.personCode());
            else if (activeAssignments(scope, itemKey) || activeRubricReferences(scope, itemKey))
                throw AuthException.conflict("REFERENCE_IN_USE");
            db.update("UPDATE story_clue SET active_yn=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    active, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "clues", active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                    itemKey, List.of("activeYn"), requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /**
     * 원고 없이 배정의 ASCII 구성요소 튜플 페이지를 조회한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size null이면 20, 아니면 1~100
     * @param afterKey 마지막 clueCode~roleCode 또는 null; 역할 코드의 상대 정렬을 요구하지 않는다
     * @param activeYn null이면 활성, false이면 비활성
     * @return 수정번호와 키·상태·시각 목록
     */
    public AssignmentPage getClueRoleList(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = limit(size);
        String[] cursor = afterKey == null ? null : assignmentKey(afterKey);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<AssignmentKey> rows = db.query("SELECT clue_code,role_code,active_yn,updated_at FROM clue_role "
                            + "WHERE version_id=? AND active_yn=? AND (clue_code COLLATE \"C\">? COLLATE \"C\" "
                            + "OR (clue_code COLLATE \"C\"=? COLLATE \"C\" AND role_code COLLATE \"C\">? COLLATE \"C\")) "
                            + "ORDER BY clue_code COLLATE \"C\",role_code COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new AssignmentKey(rs.getString(1) + "~" + rs.getString(2), rs.getBoolean(3),
                            rs.getTimestamp(4).toInstant()), scope.versionId(), activeYn == null || activeYn,
                    cursor == null ? "" : cursor[0], cursor == null ? "" : cursor[0], cursor == null ? "" : cursor[1], limit + 1);
            boolean next = rows.size() > limit;
            List<AssignmentKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new AssignmentPage(Long.toString(scope.rev()), items, next, next ? items.get(items.size() - 1).itemKey() : null);
        });
    }

    /**
     * 배정의 단건 조회를 필수 감사 뒤 반환한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey clueCode~roleCode 순서의 ASCII 키
     * @param requestId null이 아닌 서버 요청 ID
     * @return 비활성 행도 포함하는 관계 요소와 수정번호
     */
    public AssignmentDetail getClueRoleDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        String[] keys = assignmentKey(itemKey);
        requestId(requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Assignment item = requiredAssignment(scope, keys);
            stories.recordChildChange(scope, actor, "clue-roles", "CONTENT_READ", itemKey, null, requestId);
            return new AssignmentDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * 활성 ROLE 단서와 같은 버전의 활성 역할을 연결한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 편집 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param expectedRev null이 아닌 현재 십진 수정번호
     * @param item clueCode·roleCode만 포함하는 객체이며 각 코드는 ASCII 1~32자
     * @param requestId null이 아닌 서버 요청 ID
     * @return 의미 순서의 생성 itemKey와 수정번호
     * @throws AuthException 비활성 대상·COMMON 단서·예약된 키·감사 실패 시
     */
    public ItemCreated createClueRole(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        if (item == null || !item.isObject() || item.size() != 2 || !item.has("clueCode") || !item.has("roleCode"))
            throw AuthException.badRequest("INVALID_REQUEST");
        String clue = newCode(item.get("clueCode"));
        String role = newCode(item.get("roleCode"));
        String itemKey = clue + "~" + role;
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (findAssignment(scope, new String[] {clue, role}) != null) throw AuthException.conflict("ITEM_EXISTS");
            activeTargets(scope, clue, role);
            db.update("INSERT INTO clue_role(version_id,clue_code,role_code) VALUES (?,?,?)", scope.versionId(), clue, role);
            long rev = stories.recordChildChange(scope, actor, "clue-roles", "ITEM_CREATED", itemKey,
                    List.of("clueCode", "roleCode"), requestId);
            return created(storyCode, versionNo, scope, rev, requestId, itemKey);
        });
    }

    /**
     * 배정의 논리 상태만 변경하며 복원 시 대상 활성 상태를 다시 검사한다.
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 편집 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey clueCode~roleCode 순서의 예약된 ASCII 키
     * @param expectedRev 무변경에도 검사하는 현재 십진 수정번호
     * @param active true는 복원, false는 비활성화
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 변경에만 증가하는 원고 없는 결과
     */
    public ContentResult updateClueRoleActive(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        String[] keys = assignmentKey(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Assignment before = requiredAssignment(scope, keys);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (active) activeTargets(scope, keys[0], keys[1]);
            db.update("UPDATE clue_role SET active_yn=?,updated_at=clock_timestamp() "
                            + "WHERE version_id=? AND clue_code=? AND role_code=?",
                    active, scope.versionId(), keys[0], keys[1]);
            long rev = stories.recordChildChange(scope, actor, "clue-roles", active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                    itemKey, List.of("activeYn"), requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /** 필드 집합과 빈 PATCH를 검사한다. */
    private static void fields(JsonNode input, boolean create) {
        if (input == null || !input.isObject() || !create && input.isEmpty()) throw AuthException.badRequest("INVALID_REQUEST");
        input.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field) && !(create && "code".equals(field))) throw AuthException.badRequest("INVALID_REQUEST");
        });
    }

    /** 누락과 명시적 null을 구분하고 원고의 길이와 코드 문법을 검사한다. */
    private static Map<String, String> values(JsonNode input, boolean create) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : List.of("title", "body", "personCode", "scope", "sourceText")) {
            JsonNode node = input.get(field);
            if (node == null) {
                if (create) {
                    if ("title".equals(field)) throw AuthException.unprocessable("INVALID_INPUT");
                    result.put(field, "scope".equals(field) ? "ROLE" : null);
                }
                continue;
            }
            if (node.isNull()) {
                if ("title".equals(field) || "scope".equals(field)) throw AuthException.unprocessable("INVALID_INPUT");
                result.put(field, null);
                continue;
            }
            if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
            String value = node.textValue();
            if ("personCode".equals(field)) {
                if (!value.matches("[A-Z0-9_]{1,32}")) throw AuthException.unprocessable("INVALID_INPUT");
            } else if ("scope".equals(field)) {
                if (!Set.of("COMMON", "ROLE").contains(value)) throw AuthException.unprocessable("INVALID_INPUT");
            } else {
                value = StoryService.text(value, switch (field) {
                    case "title" -> 160;
                    case "body" -> 12000;
                    default -> 400;
                }, !"title".equals(field));
            }
            result.put(field, value);
        }
        return result;
    }

    /** 활성 인물의 소속 버전을 검사하며 null은 참조 없음으로 취급한다. */
    private void activePerson(VersionScope scope, String person) {
        if (person != null && db.queryForObject("SELECT count(*) FROM story_person WHERE version_id=? AND code=? AND active_yn",
                Integer.class, scope.versionId(), person) == 0) throw AuthException.unprocessable("INVALID_INPUT");
    }

    /** 활성 배정 여부는 삭제와 COMMON 전환의 동일한 기준이다. */
    private boolean activeAssignments(VersionScope scope, String clue) {
        return db.queryForObject("SELECT count(*) FROM clue_role WHERE version_id=? AND clue_code=? AND active_yn",
                Integer.class, scope.versionId(), clue) > 0;
    }

    /** 단서 삭제에 한해 활성 소항목 연결 및 구조화 예시 경로를 확인한다. */
    private boolean activeRubricReferences(VersionScope scope, String clue) {
        return db.queryForObject("SELECT EXISTS (SELECT 1 FROM rubric_clue WHERE version_id=? AND clue_code=? AND active_yn) "
                        + "OR EXISTS (SELECT 1 FROM story_rubric r "
                        + "CROSS JOIN LATERAL jsonb_array_elements(r.rule_data->'claims') claim "
                        + "CROSS JOIN LATERAL jsonb_array_elements(claim->'exampleClueRoutes') route "
                        + "CROSS JOIN LATERAL jsonb_array_elements_text(route) example(code) "
                        + "WHERE r.version_id=? AND r.active_yn AND example.code=?)",
                Boolean.class, scope.versionId(), clue, scope.versionId(), clue);
    }

    /** 복원과 생성에서 동일 버전 ROLE 단서와 역할 활성 상태를 검사한다. */
    private void activeTargets(VersionScope scope, String clue, String role) {
        Clue target = findClue(scope, clue);
        if (target == null || !target.activeYn() || !"ROLE".equals(target.scope())
                || db.queryForObject("SELECT count(*) FROM story_role WHERE version_id=? AND code=? AND active_yn",
                        Integer.class, scope.versionId(), role) == 0) throw AuthException.unprocessable("INVALID_INPUT");
    }

    /** 같은 버전에서만 단서 단건을 조회한다. */
    private Clue findClue(VersionScope scope, String code) {
        return db.query("SELECT code,title,body,person_code,scope,source_text,active_yn,updated_at FROM story_clue "
                        + "WHERE version_id=? AND code=? FOR UPDATE",
                rs -> rs.next() ? clue(rs) : null, scope.versionId(), code);
    }

    /** 없는 단서의 다른 버전 존재 여부를 노출하지 않는다. */
    private Clue requiredClue(VersionScope scope, String code) {
        Clue clue = findClue(scope, code);
        if (clue == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return clue;
    }

    /** 같은 버전의 고정 구성요소로 배정을 조회한다. */
    private Assignment findAssignment(VersionScope scope, String[] keys) {
        return db.query("SELECT clue_code,role_code,active_yn,updated_at FROM clue_role "
                        + "WHERE version_id=? AND clue_code=? AND role_code=? FOR UPDATE",
                rs -> rs.next() ? assignment(rs) : null, scope.versionId(), keys[0], keys[1]);
    }

    /** 없는 배정의 다른 버전 존재 여부를 노출하지 않는다. */
    private Assignment requiredAssignment(VersionScope scope, String[] keys) {
        Assignment item = findAssignment(scope, keys);
        if (item == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return item;
    }

    /** 내부 식별자를 제외한 단서 원고를 매핑한다. */
    private static Clue clue(ResultSet rs) throws SQLException {
        return new Clue(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getBoolean(7), rs.getTimestamp(8).toInstant());
    }

    /** 배정의 공개 구성요소만 매핑한다. */
    private static Assignment assignment(ResultSet rs) throws SQLException {
        return new Assignment(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getTimestamp(4).toInstant());
    }

    /** 변경 후의 현재 배정 경고를 반영한다. */
    private ContentResult result(String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(code, number, Long.toString(rev), changed, stories.currentWarnings(scope), id);
    }

    /** 생성 응답에만 키를 추가한다. */
    private ItemCreated created(String code, int number, VersionScope scope, long rev, UUID id, String key) {
        return new ItemCreated(code, number, Long.toString(rev), true, stories.currentWarnings(scope), id, key);
    }

    /** 페이지 크기는 1~100이다. */
    private static int limit(Integer size) {
        int value = size == null ? 20 : size;
        if (value < 1 || value > 100) throw AuthException.badRequest("INVALID_REQUEST");
        return value;
    }

    /** 경로와 커서는 정확한 ASCII 단서 코드를 요구한다. */
    private static void key(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 생성 코드의 타입과 문법을 검증한다. */
    private static String newCode(JsonNode node) {
        if (node == null || !node.isTextual() || !node.textValue().matches("[A-Z0-9_]{1,32}"))
            throw AuthException.unprocessable("INVALID_INPUT");
        return node.textValue();
    }

    /** 배정의 의미 순서를 유지해 키와 커서를 분해한다. */
    private static String[] assignmentKey(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}~[A-Z0-9_]{1,32}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        return value.split("~", -1);
    }

    /** 수정번호와 감사 요청 ID 누락을 거절한다. */
    private static void mutation(String rev, UUID id) {
        if (rev == null) throw AuthException.badRequest("INVALID_REQUEST");
        requestId(id);
    }

    /** 조회와 변경의 필수 감사 요청 ID 누락을 거절한다. */
    private static void requestId(UUID id) {
        if (id == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    public record ClueKey(String code, boolean activeYn, Instant updatedAt) {}
    public record Clue(String code, String title, String body, String personCode, String scope,
            String sourceText, boolean activeYn, Instant updatedAt) {}
    public record CluePage(String editRev, List<ClueKey> items, boolean hasNext, String nextAfterKey) {}
    public record ClueDetail(String editRev, Clue item) {}
    public record AssignmentKey(String itemKey, boolean activeYn, Instant updatedAt) {}
    public record Assignment(String clueCode, String roleCode, boolean activeYn, Instant updatedAt) {}
    public record AssignmentPage(String editRev, List<AssignmentKey> items, boolean hasNext, String nextAfterKey) {}
    public record AssignmentDetail(String editRev, Assignment item) {}
    public record ItemCreated(String storyCode, int versionNo, String editRev, boolean changed,
            List<Warning> warnings, UUID requestId, String itemKey) {}
}
