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

/** 역할과 역할 조합의 고정 SQL만 사용하여 STORY-05~10을 처리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryRoleService {
    private static final Set<String> ROLE_FIELDS = Set.of("name", "brief");
    private final StoryService stories;
    private final JdbcTemplate db;

    public StoryRoleService(StoryService stories, JdbcTemplate db) {
        this.stories = stories;
        this.db = db;
    }

    /**
     * 부모 조회 자격 아래 코드의 ASCII 순서로 원고 없는 역할 페이지를 반환한다.
     * @param sid 현재 서버 세션 ID이며 null을 허용하지 않는다
     * @param actor 세션과 일치해야 하는 서버 검증 행위자
     * @param storyCode 조회 자격을 확인할 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param size 1~100이며 null이면 20개다
     * @param afterKey 마지막 ASCII 역할 코드이며 null이면 첫 페이지다
     * @param activeYn 활성 필터이며 null이면 true다
     * @return 부모 수정번호와 코드·상태·시각만 포함한 페이지
     * @throws AuthException 입력·현재 인증·조회 자격이 유효하지 않을 때
     */
    public RolePage getRoleList(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = limit(size);
        if (afterKey != null) key(afterKey);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<RoleKey> rows = db.query("SELECT code,active_yn,updated_at FROM story_role WHERE version_id=? AND active_yn=? "
                            + "AND code COLLATE \"C\">? COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new RoleKey(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()),
                    scope.versionId(), activeYn == null || activeYn, afterKey == null ? "" : afterKey, limit + 1);
            boolean next = rows.size() > limit;
            List<RoleKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new RolePage(Long.toString(scope.rev()), items, next, next ? items.get(items.size() - 1).code() : null);
        });
    }

    /**
     * 현재 부모 조회 자격과 필수 조회 감사를 확정한 뒤 역할 원고를 반환한다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 서버 행위자
     * @param storyCode 조회할 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param itemKey 영문 대문자·숫자·밑줄 1~32자의 역할 코드
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 논리 삭제 상태도 포함하는 보호된 역할 원고
     * @throws AuthException 인증·조회 자격·대상 존재·필수 감사 확인 실패 시
     */
    public RoleDetail getRoleDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        key(itemKey);
        requestId(requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Role item = requiredRole(scope, itemKey);
            stories.recordChildChange(scope, actor, "roles", "CONTENT_READ", itemKey, null, requestId);
            return new RoleDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * 예약되지 않은 역할 코드를 생성하고 부모 수정번호와 필수 감사를 같은 거래로 확정한다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 현재 편집 행위자
     * @param storyCode 변경할 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param expectedRev null이 아닌 0~Long.MAX_VALUE의 정규 십진 문자열
     * @param item 필수 code·name과 선택 brief만 허용하는 객체; 이름 80자, 소개 4000 코드 포인트 이하다
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 생성 키와 증가한 부모 수정번호이며 원고는 포함하지 않는다
     * @throws AuthException 입력·편집 자격·부모 상태·수정번호·키 예약·필수 감사 확인 실패 시
     */
    public ItemCreated createRole(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        if (item == null || !item.isObject()) throw AuthException.badRequest("INVALID_REQUEST");
        item.fieldNames().forEachRemaining(field -> {
            if (!ROLE_FIELDS.contains(field) && !"code".equals(field)) throw AuthException.badRequest("INVALID_REQUEST");
        });
        String code = newCode(item.get("code"));
        Map<String, String> values = roleValues(item, true);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (findRole(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
            db.update("INSERT INTO story_role(version_id,code,name,brief) VALUES (?,?,?,?)",
                    scope.versionId(), code, values.get("name"), values.get("brief"));
            long revision = stories.recordChildChange(scope, actor, "roles", "ITEM_CREATED", code,
                    List.of("code", "name", "brief"), requestId);
            return created(storyCode, versionNo, scope, revision, requestId, code);
        });
    }

    /**
     * 활성 역할의 허용 원고만 바꾸며 동등한 값은 시각·수정번호·감사를 유지한다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 현재 편집 행위자
     * @param storyCode 변경할 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 변경 불가인 1~32자 ASCII 역할 코드
     * @param expectedRev null이 아닌 정규 십진 부모 수정번호
     * @param changes 비어 있지 않은 name·brief 객체; 생략은 유지, null은 brief에만 허용한다
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 실제 변경 여부와 현재 부모 수정번호
     * @throws AuthException 입력·인가·상태·수정번호·대상 존재·필수 감사 확인 실패 시
     */
    public ContentResult updateRole(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, JsonNode changes, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        if (changes == null || !changes.isObject() || changes.isEmpty()) throw AuthException.badRequest("INVALID_REQUEST");
        changes.fieldNames().forEachRemaining(field -> {
            if (!ROLE_FIELDS.contains(field)) throw AuthException.badRequest("INVALID_REQUEST");
        });
        Map<String, String> requested = roleValues(changes, false);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Role before = requiredRole(scope, itemKey);
            if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
            String name = requested.getOrDefault("name", before.name());
            String brief = requested.getOrDefault("brief", before.brief());
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(name, before.name())) changed.add("name");
            if (!Objects.equals(brief, before.brief())) changed.add("brief");
            if (changed.isEmpty()) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            db.update("UPDATE story_role SET name=?,brief=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    name, brief, scope.versionId(), itemKey);
            long revision = stories.recordChildChange(scope, actor, "roles", "ITEM_UPDATED", itemKey, changed, requestId);
            return result(storyCode, versionNo, scope, revision, true, requestId);
        });
    }

    /**
     * 활성 조합 참조가 있으면 역할 비활성화를 거절하고 실제 상태 변경만 감사한다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 현재 편집 행위자
     * @param storyCode 변경할 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey 1~32자 ASCII 역할 코드
     * @param expectedRev null이 아닌 정규 십진 부모 수정번호
     * @param active true이면 복원, false이면 논리 삭제하며 물리 삭제하지 않는다
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 실제 상태 변경 여부와 부모 수정번호
     * @throws AuthException 인가·상태·수정번호·활성 조합 또는 단서 배정 참조·필수 감사 확인 실패 시
     */
    public ContentResult updateRoleActive(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Role before = requiredRole(scope, itemKey);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (!active && db.queryForObject("SELECT count(*) FROM story_pair WHERE version_id=? AND active_yn AND (role_a=? OR role_b=?)",
                    Integer.class, scope.versionId(), itemKey, itemKey) > 0) throw AuthException.conflict("REFERENCE_IN_USE");
            if (!active && db.queryForObject("SELECT count(*) FROM clue_role WHERE version_id=? AND role_code=? AND active_yn",
                    Integer.class, scope.versionId(), itemKey) > 0) throw AuthException.conflict("REFERENCE_IN_USE");
            db.update("UPDATE story_role SET active_yn=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    active, scope.versionId(), itemKey);
            long revision = stories.recordChildChange(scope, actor, "roles", active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                    itemKey, List.of("activeYn"), requestId);
            return result(storyCode, versionNo, scope, revision, true, requestId);
        });
    }

    /**
     * 두 구성요소의 ASCII 튜플 순서로 조합 키·상태·시각만 반환한다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 서버 행위자
     * @param storyCode 조회할 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param size 1~100이며 null이면 20개다
     * @param afterKey 정순 roleA~roleB이며 null이면 첫 페이지다
     * @param activeYn 활성 필터이며 null이면 true다
     * @return 원고 없는 조합 키·상태·시각과 같은 부모 수정번호
     * @throws AuthException 잘못된 키·페이지 크기 또는 현재 인증·조회 자격 실패 시
     */
    public PairPage getPairList(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = limit(size);
        String[] cursor = afterKey == null ? null : pairKey(afterKey);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<PairKey> rows = db.query("SELECT role_a,role_b,active_yn,updated_at FROM story_pair WHERE version_id=? AND active_yn=? "
                            + "AND (role_a COLLATE \"C\">? COLLATE \"C\" OR (role_a COLLATE \"C\"=? COLLATE \"C\" "
                            + "AND role_b COLLATE \"C\">? COLLATE \"C\")) "
                            + "ORDER BY role_a COLLATE \"C\",role_b COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new PairKey(rs.getString(1) + "~" + rs.getString(2), rs.getBoolean(3),
                            rs.getTimestamp(4).toInstant()), scope.versionId(), activeYn == null || activeYn,
                    cursor == null ? "" : cursor[0], cursor == null ? "" : cursor[0], cursor == null ? "" : cursor[1], limit + 1);
            boolean next = rows.size() > limit;
            List<PairKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new PairPage(Long.toString(scope.rev()), items, next, next ? items.get(items.size() - 1).itemKey() : null);
        });
    }

    /**
     * 부모 권한·필수 조회 감사가 성공한 조합 단건만 반환한다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 서버 행위자
     * @param storyCode 조회할 사건 코드
     * @param versionNo 사건 내 양의 버전 번호
     * @param itemKey 각 요소가 ASCII 역할 코드이고 roleA&lt;roleB인 roleA~roleB
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 논리 삭제 상태를 포함한 관계 요소와 부모 수정번호
     * @throws AuthException 인증·조회 자격·키 형식·대상 존재·필수 감사 확인 실패 시
     */
    public PairDetail getPairDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        String[] keys = pairKey(itemKey);
        requestId(requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Pair item = requiredPair(scope, keys);
            stories.recordChildChange(scope, actor, "pairs", "CONTENT_READ", itemKey, null, requestId);
            return new PairDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * 정순·서로 다른 활성 역할 둘로 조합을 생성하며 예약된 비활성 키도 재사용하지 않는다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 현재 편집 행위자
     * @param storyCode 변경할 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param expectedRev null이 아닌 정규 십진 부모 수정번호
     * @param item roleA·roleB만 포함하며 같은 버전의 활성 역할이고 ASCII 정순이어야 한다
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 생성한 정규 복합 키와 증가한 부모 수정번호
     * @throws AuthException 입력·역할 참조·예약 키·인가·수정번호·필수 감사 확인 실패 시
     */
    public ItemCreated createPair(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        if (item == null || !item.isObject() || item.size() != 2 || !item.has("roleA") || !item.has("roleB"))
            throw AuthException.badRequest("INVALID_REQUEST");
        String a = newCode(item.get("roleA"));
        String b = newCode(item.get("roleB"));
        if (a.compareTo(b) >= 0) throw AuthException.unprocessable("INVALID_INPUT");
        String itemKey = a + "~" + b;
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (findPair(scope, new String[] {a, b}) != null) throw AuthException.conflict("ITEM_EXISTS");
            activeRoles(scope, a, b);
            db.update("INSERT INTO story_pair(version_id,role_a,role_b) VALUES (?,?,?)", scope.versionId(), a, b);
            long revision = stories.recordChildChange(scope, actor, "pairs", "ITEM_CREATED", itemKey,
                    List.of("roleA", "roleB"), requestId);
            return created(storyCode, versionNo, scope, revision, requestId, itemKey);
        });
    }

    /**
     * 조합은 원고 수정이 없으며 비활성화·복원에서만 상태를 바꾼다.
     * @param sid null이 아닌 현재 서버 세션 ID
     * @param actor 세션과 일치해야 하는 현재 편집 행위자
     * @param storyCode 변경할 사건 코드
     * @param versionNo 활성 DRAFT 버전의 양의 번호
     * @param itemKey ASCII 정순의 변경 불가 roleA~roleB
     * @param expectedRev null이 아닌 정규 십진 부모 수정번호
     * @param active true이면 두 역할의 활성 상태를 다시 확인해 복원하고 false이면 논리 삭제한다
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 실제 상태 변경 여부와 부모 수정번호
     * @throws AuthException 인가·상태·수정번호·복원 대상 역할·필수 감사 확인 실패 시
     */
    public ContentResult updatePairActive(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        String[] keys = pairKey(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Pair before = requiredPair(scope, keys);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (active) activeRoles(scope, keys[0], keys[1]);
            db.update("UPDATE story_pair SET active_yn=?,updated_at=clock_timestamp() WHERE version_id=? AND role_a=? AND role_b=?",
                    active, scope.versionId(), keys[0], keys[1]);
            long revision = stories.recordChildChange(scope, actor, "pairs", active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                    itemKey, List.of("activeYn"), requestId);
            return result(storyCode, versionNo, scope, revision, true, requestId);
        });
    }

    /** 누락한 원고와 명시적 null을 구별해 이름의 필수성과 brief의 삭제를 검사한다. */
    private static Map<String, String> roleValues(JsonNode input, boolean create) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : List.of("name", "brief")) {
            JsonNode value = input.get(field);
            if (value == null && !create) continue;
            if (value == null || value.isNull()) {
                if ("name".equals(field)) throw AuthException.unprocessable("INVALID_INPUT");
                result.put(field, null);
            } else {
                if (!value.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
                result.put(field, StoryService.text(value.textValue(), "name".equals(field) ? 80 : 4000, !"name".equals(field)));
            }
        }
        return result;
    }

    /** 부모 잠금 아래 같은 버전의 역할을 조회한다. */
    private Role findRole(VersionScope scope, String code) {
        return db.query("SELECT code,name,brief,active_yn,updated_at FROM story_role WHERE version_id=? AND code=? FOR UPDATE",
                rs -> rs.next() ? role(rs) : null, scope.versionId(), code);
    }

    /** 존재하지 않는 역할에는 다른 버전 정보를 노출하지 않는다. */
    private Role requiredRole(VersionScope scope, String code) {
        Role role = findRole(scope, code);
        if (role == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return role;
    }

    /** 조합의 두 역할이 모두 같은 버전에서 활성인지 검사한다. */
    private void activeRoles(VersionScope scope, String a, String b) {
        Role first = findRole(scope, a);
        Role second = findRole(scope, b);
        if (first == null || second == null || !first.activeYn() || !second.activeYn())
            throw AuthException.unprocessable("INVALID_INPUT");
    }

    /** 부모 잠금 아래 정순으로 식별한 조합을 조회한다. */
    private Pair findPair(VersionScope scope, String[] keys) {
        return db.query("SELECT role_a,role_b,active_yn,updated_at FROM story_pair WHERE version_id=? AND role_a=? AND role_b=? FOR UPDATE",
                rs -> rs.next() ? pair(rs) : null, scope.versionId(), keys[0], keys[1]);
    }

    /** 조회 자격을 갖춘 요청에만 조합 부재를 반환한다. */
    private Pair requiredPair(VersionScope scope, String[] keys) {
        Pair pair = findPair(scope, keys);
        if (pair == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return pair;
    }

    /** 역할 공개 필드만 매핑한다. */
    private static Role role(ResultSet rs) throws SQLException {
        return new Role(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getTimestamp(5).toInstant());
    }

    /** 조합 공개 필드만 매핑한다. */
    private static Pair pair(ResultSet rs) throws SQLException {
        return new Pair(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getTimestamp(4).toInstant());
    }

    /** 원고 없는 공통 변경 응답을 구성한다. */
    private static ContentResult result(String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(code, number, Long.toString(rev), changed, scope.warnings(), id);
    }

    /** 생성 응답에만 조합 또는 역할 키를 추가한다. */
    private static ItemCreated created(String code, int number, VersionScope scope, long rev, UUID id, String key) {
        return new ItemCreated(code, number, Long.toString(rev), true, scope.warnings(), id, key);
    }

    /** 목록 크기를 1~100으로 제한한다. */
    private static int limit(Integer size) {
        int value = size == null ? 20 : size;
        if (value < 1 || value > 100) throw AuthException.badRequest("INVALID_REQUEST");
        return value;
    }

    /** 경로·커서 역할 코드는 ASCII 대문자 문법이어야 한다. */
    private static void key(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 생성 역할 코드는 타입 오류와 문법 오류를 입력 오류로 거절한다. */
    private static String newCode(JsonNode node) {
        if (node == null || !node.isTextual() || !node.textValue().matches("[A-Z0-9_]{1,32}"))
            throw AuthException.unprocessable("INVALID_INPUT");
        return node.textValue();
    }

    /** 복합 경로·커서를 분리하고 각 요소의 엄격한 ASCII 순서를 검사한다. */
    private static String[] pairKey(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}~[A-Z0-9_]{1,32}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        String[] keys = value.split("~", -1);
        if (keys[0].compareTo(keys[1]) >= 0) throw AuthException.badRequest("INVALID_REQUEST");
        return keys;
    }

    /** 변경 호출에는 현재 예상 수정번호와 요청 ID가 모두 필요하다. */
    private static void mutation(String expectedRev, UUID id) {
        if (expectedRev == null) throw AuthException.badRequest("INVALID_REQUEST");
        requestId(id);
    }

    /** 필수 콘텐츠 감사에 서버 요청 ID를 요구한다. */
    private static void requestId(UUID id) {
        if (id == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 원고를 포함하지 않는 역할 목록 항목이다. */
    public record RoleKey(String code, boolean activeYn, Instant updatedAt) {}

    /** 역할의 보호된 단건 원고다. */
    public record Role(String code, String name, String brief, boolean activeYn, Instant updatedAt) {}

    /** 역할 목록과 같은 부모 수정번호다. */
    public record RolePage(String editRev, List<RoleKey> items, boolean hasNext, String nextAfterKey) {}

    /** 감사 후 반환하는 역할 단건이다. */
    public record RoleDetail(String editRev, Role item) {}

    /** 원고를 포함하지 않는 조합 목록 항목이다. */
    public record PairKey(String itemKey, boolean activeYn, Instant updatedAt) {}

    /** 관계 요소 및 상태만 반환하는 조합 단건이다. */
    public record Pair(String roleA, String roleB, boolean activeYn, Instant updatedAt) {}

    /** 조합 목록과 같은 부모 수정번호다. */
    public record PairPage(String editRev, List<PairKey> items, boolean hasNext, String nextAfterKey) {}

    /** 감사 후 반환하는 조합 단건이다. */
    public record PairDetail(String editRev, Pair item) {}

    /** 생성한 키를 추가한 원문 없는 영수증이다. */
    public record ItemCreated(String storyCode, int versionNo, String editRev, boolean changed,
            List<Warning> warnings, UUID requestId, String itemKey) {}
}
