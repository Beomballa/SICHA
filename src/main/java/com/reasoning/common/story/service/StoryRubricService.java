package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.story.service.StoryService.ContentResult;
import com.reasoning.common.story.service.StoryService.VersionScope;
import com.reasoning.common.story.service.StoryService.Warning;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 채점 소항목의 초안 CRUD와 구조화 규칙을 부모 버전 잠금 안에서 관리한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryRubricService {
    private static final Set<String> FIELDS = Set.of("category", "maxScore", "requiredYn", "passScore",
            "acceptedText", "partialText", "rejectText", "ruleData");
    private static final Set<String> CATEGORIES = Set.of("CULPRIT", "METHOD", "TIME", "MOTIVE", "EVIDENCE");
    private final StoryService stories;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final StoryRubricRule rules;

    public StoryRubricService(StoryService stories, JdbcTemplate db, ObjectMapper mapper) {
        this.stories = stories;
        this.db = db;
        this.mapper = mapper;
        this.rules = new StoryRubricRule(db, mapper);
    }

    /** 부모 경고 구성에서 호출할 수 있는 순수 JDBC 투영이다. 호출자는 이미 부모 버전을 잠근다. */
    static List<Warning> rubricWarnings(JdbcTemplate db, long versionId) {
        Map<String, Integer> targets = Map.of("CULPRIT", 25, "METHOD", 20, "TIME", 15, "MOTIVE", 10, "EVIDENCE", 30);
        List<Warning> result = new ArrayList<>();
        for (String category : List.of("CULPRIT", "METHOD", "TIME", "MOTIVE", "EVIDENCE")) {
            Long score = db.queryForObject("SELECT coalesce(sum(max_score),0) FROM story_rubric WHERE version_id=? "
                    + "AND active_yn AND category=?", Long.class, versionId, category);
            if (!Objects.equals(score, targets.get(category).longValue())) result.add(new Warning("SCORE_TOTAL", "rubrics." + category));
        }
        db.query("SELECT code,max_score,required_yn,pass_score,rule_data IS NULL AS rule_missing,"
                        + "coalesce(rule_data->'levels' @> jsonb_build_array(jsonb_build_object('score',pass_score)),false) AS pass_registered "
                        + "FROM story_rubric WHERE version_id=? AND active_yn ORDER BY code COLLATE \"C\"",
                rs -> {
                    String field = "rubrics." + rs.getString("code") + ".";
                    if (rs.getObject("max_score") == null || rs.getInt("max_score") == 0)
                        result.add(new Warning("MISSING_CONTENT", field + "maxScore"));
                    if (rs.getBoolean("rule_missing")) result.add(new Warning("MISSING_CONTENT", field + "ruleData"));
                    if (rs.getBoolean("required_yn") && (rs.getObject("pass_score") == null
                            || rs.getInt("pass_score") == 0 || !rs.getBoolean("pass_registered")))
                        result.add(new Warning("MISSING_CONTENT", field + "passScore"));
                }, versionId);
        for (String category : List.of("METHOD", "EVIDENCE")) {
            if (db.queryForObject("SELECT count(*) FROM story_rubric WHERE version_id=? AND active_yn "
                            + "AND category=? AND required_yn", Integer.class, versionId, category) == 0)
                result.add(new Warning("MISSING_CONTENT", "rubrics." + category + ".requiredYn"));
        }
        return List.copyOf(result);
    }

    /**
     * 활성 상태와 ASCII 코드 커서로 원고를 제외한 소항목 페이지를 조회한다.
     * @param sid 현재 세션 ID, null은 인증 거절
     * @param actor 서버 검증 행위자
     * @param storyCode 접근 가능한 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size 1~100, null이면 20
     * @param afterKey ASCII 마지막 코드 또는 null
     * @param activeYn null/true이면 활성, false이면 비활성
     * @return 수정번호와 키·상태·시각 페이지
     */
    public RubricPage getRubricList(String sid, AdminActor actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        if (afterKey != null) key(afterKey);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<RubricKey> rows = db.query("SELECT code,active_yn,updated_at FROM story_rubric WHERE version_id=? "
                            + "AND active_yn=? AND code COLLATE \"C\">? COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new RubricKey(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()),
                    scope.versionId(), activeYn == null || activeYn, afterKey == null ? "" : afterKey, limit + 1);
            boolean next = rows.size() > limit;
            List<RubricKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new RubricPage(Long.toString(scope.rev()), items, next, next ? items.get(items.size() - 1).code() : null);
        });
    }

    /**
     * 단건 원고를 조회하고 같은 거래에서 필수 조회 감사를 확정한다.
     * @param sid 현재 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 접근 가능한 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey 불변 ASCII 소항목 코드
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 현재 수정번호와 감사가 확정된 원고
     */
    public RubricDetail getRubricDetail(String sid, AdminActor actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        key(itemKey);
        requestId(requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Rubric item = required(scope, itemKey);
            stories.recordChildChange(scope, actor, "rubrics", "CONTENT_READ", itemKey, null, requestId);
            return new RubricDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * 새 소항목을 생성하며 CULPRIT의 점수·규칙은 서버 값으로 고정한다.
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 활성 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param expectedRev 현재 십진 문자열 수정번호
     * @param item code/category 필수, 나머지는 nullable 또는 계약상 기본값
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 원고 없는 생성 키·수정번호·현재 경고
     */
    public ItemCreated createRubric(String sid, AdminActor actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        fields(item, true);
        String code = newCode(item.get("code"));
        Values requested = values(item, true);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (find(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
            capacity(scope, requested.category(), null);
            Values target = validate(scope, code, requested, null, item);
            db.update("INSERT INTO story_rubric(version_id,code,category,max_score,required_yn,pass_score,"
                            + "accepted_text,partial_text,reject_text,rule_data) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)",
                    scope.versionId(), code, target.category(), target.maxScore(), target.requiredYn(), target.passScore(),
                    target.acceptedText(), target.partialText(), target.rejectText(), json(target.ruleData()));
            long rev = stories.recordChildChange(scope, actor, "rubrics", "ITEM_CREATED", code,
                    List.of("code", "category", "maxScore", "requiredYn", "passScore", "acceptedText", "partialText", "rejectText", "ruleData"), requestId);
            return new ItemCreated(storyCode, versionNo, Long.toString(rev), true, stories.currentWarnings(scope), requestId, code);
        });
    }

    /**
     * 명시된 필드를 병합한 뒤 모든 점수·규칙 불변식을 재검사한다.
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 활성 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey 변경할 수 없는 ASCII 코드
     * @param expectedRev 무변경에도 확인하는 십진 수정번호
     * @param changes 비어 있지 않은 허용 필드 객체, nullable의 null은 삭제
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 실제 변경에만 증가하는 수정번호·변경 여부·경고
     */
    public ContentResult updateRubric(String sid, AdminActor actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, JsonNode changes, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        fields(changes, false);
        Values requested = values(changes, false);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Rubric before = required(scope, itemKey);
            if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
            Values merged = merge(before.values(), requested, changes);
            if (!"CULPRIT".equals(before.category()) && "CULPRIT".equals(merged.category()))
                capacityForCategory(scope, itemKey);
            Values target = validate(scope, itemKey, merged, before, changes);
            List<String> changed = changed(before.values(), target);
            if (changed.isEmpty()) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            db.update("UPDATE story_rubric SET category=?,max_score=?,required_yn=?,pass_score=?,accepted_text=?,"
                            + "partial_text=?,reject_text=?,rule_data=?::jsonb,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    target.category(), target.maxScore(), target.requiredYn(), target.passScore(), target.acceptedText(),
                    target.partialText(), target.rejectText(), json(target.ruleData()), scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "rubrics", "ITEM_UPDATED", itemKey, changed, requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /**
     * 연결이 남아 있는 소항목은 비활성화하지 않고 복원 시 규칙 참조를 재검사한다.
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 활성 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey 예약된 ASCII 코드
     * @param expectedRev 무변경에도 확인하는 십진 수정번호
     * @param active true는 복원, false는 논리 삭제
     * @param requestId 필수 감사 요청 ID, null 불가
     * @return 원고 없는 변경 여부·수정번호·경고
     */
    public ContentResult updateRubricActive(String sid, AdminActor actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Rubric before = required(scope, itemKey);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (active) {
                capacity(scope, before.category(), itemKey);
                validate(scope, itemKey, before.values(), before, mapper.createObjectNode());
            } else if (db.queryForObject("SELECT count(*) FROM rubric_clue WHERE version_id=? AND rubric_code=? AND active_yn",
                    Integer.class, scope.versionId(), itemKey) > 0) throw AuthException.conflict("REFERENCE_IN_USE");
            db.update("UPDATE story_rubric SET active_yn=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    active, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "rubrics", active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                    itemKey, List.of("activeYn"), requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /** 활성 소항목의 상한과 유일한 범인 소항목을 부모 잠금으로 직렬화한다. */
    private void capacity(VersionScope scope, String category, String self) {
        if (db.queryForObject("SELECT count(*) FROM story_rubric WHERE version_id=? AND active_yn",
                Integer.class, scope.versionId()) >= 50) throw AuthException.conflict("STATE_CONFLICT");
        if ("CULPRIT".equals(category)) capacityForCategory(scope, self);
    }

    private void capacityForCategory(VersionScope scope, String self) {
        if (db.queryForObject("SELECT count(*) FROM story_rubric WHERE version_id=? "
                        + "AND category='CULPRIT' AND active_yn AND code<>?", Integer.class,
                scope.versionId(), self == null ? "" : self) > 0) throw AuthException.conflict("STATE_CONFLICT");
    }

    /** 새 분류의 고정 규칙과 명시적 이탈을 확인한 뒤 JSON 크기와 활성 참조를 검사한다. */
    private Values validate(VersionScope scope, String code, Values values, Rubric before, JsonNode supplied) {
        boolean culprit = "CULPRIT".equals(values.category());
        if (culprit) {
            for (String field : List.of("maxScore", "requiredYn", "passScore", "ruleData"))
                if (supplied.has(field) && !sameFixed(field, supplied.get(field))) throw AuthException.unprocessable("INVALID_INPUT");
            values = new Values(values.category(), 25, true, 25, values.acceptedText(), values.partialText(),
                    values.rejectText(), rules.culprit());
        } else if (before != null && "CULPRIT".equals(before.category()) && !supplied.has("ruleData")) {
            throw AuthException.unprocessable("INVALID_INPUT");
        }
        if (values.passScore() != null && (!values.requiredYn() || values.maxScore() == null
                || values.passScore() > values.maxScore())) throw AuthException.unprocessable("INVALID_INPUT");
        if (values.ruleData() != null && !values.ruleData().isNull() && values.maxScore() == null)
            throw AuthException.unprocessable("INVALID_INPUT");
        JsonNode normalized = values.ruleData() == null || values.ruleData().isNull() ? null : values.ruleData().deepCopy();
        rules.validate(normalized, scope.versionId(), code, values.maxScore(), values.requiredYn(), culprit);
        return new Values(values.category(), values.maxScore(), values.requiredYn(), values.passScore(),
                values.acceptedText(), values.partialText(), values.rejectText(), normalized);
    }

    private boolean sameFixed(String field, JsonNode supplied) {
        if ("ruleData".equals(field)) StoryRubricRule.checkMembers(supplied);
        return switch (field) {
            case "maxScore", "passScore" -> supplied.isIntegralNumber() && supplied.canConvertToInt() && supplied.intValue() == 25;
            case "requiredYn" -> supplied.isBoolean() && supplied.booleanValue();
            case "ruleData" -> supplied.equals(rules.culprit());
            default -> false;
        };
    }

    private static Values merge(Values old, Values patch, JsonNode changes) {
        return new Values(changes.has("category") ? patch.category() : old.category(),
                changes.has("maxScore") ? patch.maxScore() : old.maxScore(),
                changes.has("requiredYn") ? patch.requiredYn() : old.requiredYn(),
                changes.has("passScore") ? patch.passScore() : old.passScore(),
                changes.has("acceptedText") ? patch.acceptedText() : old.acceptedText(),
                changes.has("partialText") ? patch.partialText() : old.partialText(),
                changes.has("rejectText") ? patch.rejectText() : old.rejectText(),
                changes.has("ruleData") ? patch.ruleData() : old.ruleData());
    }

    private static List<String> changed(Values before, Values after) {
        List<String> result = new ArrayList<>();
        if (!Objects.equals(before.category(), after.category())) result.add("category");
        if (!Objects.equals(before.maxScore(), after.maxScore())) result.add("maxScore");
        if (before.requiredYn() != after.requiredYn()) result.add("requiredYn");
        if (!Objects.equals(before.passScore(), after.passScore())) result.add("passScore");
        if (!Objects.equals(before.acceptedText(), after.acceptedText())) result.add("acceptedText");
        if (!Objects.equals(before.partialText(), after.partialText())) result.add("partialText");
        if (!Objects.equals(before.rejectText(), after.rejectText())) result.add("rejectText");
        if (!Objects.equals(before.ruleData(), after.ruleData())) result.add("ruleData");
        return result;
    }

    private static Values values(JsonNode input, boolean create) {
        String category = null;
        if (input.has("category")) {
            JsonNode node = input.get("category");
            if (!node.isTextual() || !CATEGORIES.contains(node.textValue())) throw AuthException.unprocessable("INVALID_INPUT");
            category = node.textValue();
        } else if (create) throw AuthException.unprocessable("INVALID_INPUT");
        Integer max = number(input.get("maxScore"));
        Integer pass = number(input.get("passScore"));
        boolean required = false;
        if (input.has("requiredYn")) {
            if (!input.get("requiredYn").isBoolean()) throw AuthException.unprocessable("INVALID_INPUT");
            required = input.get("requiredYn").booleanValue();
        }
        return new Values(category, max, required, pass, text(input.get("acceptedText"), 12000),
                text(input.get("partialText"), 12000), text(input.get("rejectText"), 8000), input.get("ruleData"));
    }

    private static Integer number(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0 || node.intValue() > 100)
            throw AuthException.unprocessable("INVALID_INPUT");
        return node.intValue();
    }

    private String json(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return mapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw AuthException.unprocessable("INVALID_INPUT");
        }
    }

    private static String text(JsonNode node, int max) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        return StoryService.text(node.textValue(), max, true);
    }

    private static void fields(JsonNode node, boolean create) {
        if (node == null || !node.isObject() || !create && node.isEmpty()) throw AuthException.badRequest("INVALID_REQUEST");
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field) && !(create && "code".equals(field))) throw AuthException.badRequest("INVALID_REQUEST");
        });
    }

    private Rubric find(VersionScope scope, String code) {
        return db.query("SELECT code,category,max_score,required_yn,pass_score,accepted_text,partial_text,reject_text,"
                        + "rule_data::text,active_yn,updated_at FROM story_rubric WHERE version_id=? AND code=? FOR UPDATE",
                rs -> rs.next() ? map(rs) : null, scope.versionId(), code);
    }

    private Rubric required(VersionScope scope, String code) {
        Rubric result = find(scope, code);
        if (result == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return result;
    }

    private Rubric map(ResultSet rs) throws SQLException {
        try {
            String raw = rs.getString(9);
            Number max = (Number) rs.getObject(3);
            Number pass = (Number) rs.getObject(5);
            return new Rubric(rs.getString(1), rs.getString(2), max == null ? null : max.intValue(), rs.getBoolean(4),
                    pass == null ? null : pass.intValue(), rs.getString(6), rs.getString(7), rs.getString(8),
                    raw == null ? null : mapper.readTree(raw), rs.getBoolean(10), rs.getTimestamp(11).toInstant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("잘못된 채점 규칙 JSON", e);
        }
    }

    private ContentResult result(String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(code, number, Long.toString(rev), changed, stories.currentWarnings(scope), id);
    }

    private static void key(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) throw AuthException.badRequest("INVALID_REQUEST");
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

    public record RubricKey(String code, boolean activeYn, Instant updatedAt) {}
    public record Rubric(String code, String category, Integer maxScore, boolean requiredYn, Integer passScore,
            String acceptedText, String partialText, String rejectText, JsonNode ruleData, boolean activeYn, Instant updatedAt) {
        Values values() {
            return new Values(category, maxScore, requiredYn, passScore, acceptedText, partialText, rejectText, ruleData);
        }
    }
    private record Values(String category, Integer maxScore, boolean requiredYn, Integer passScore,
            String acceptedText, String partialText, String rejectText, JsonNode ruleData) {}
    public record RubricPage(String editRev, List<RubricKey> items, boolean hasNext, String nextAfterKey) {}
    public record RubricDetail(String editRev, Rubric item) {}
    public record ItemCreated(String storyCode, int versionNo, String editRev, boolean changed,
            List<Warning> warnings, UUID requestId, String itemKey) {}
}
