package com.reasoning.common.story.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 판정 입력·기대값 초안을 부모 권한과 필수 감사 아래 저장하며 실행·사람 확인은 수행하지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryGradeSampleService {
    private static final Set<String> FIELDS = Set.of("inputData", "expectData", "expectedScore", "expectedSuccess", "reason");
    private final StoryService stories;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final StoryGradeSampleFormat format;

    public StoryGradeSampleService(StoryService stories, JdbcTemplate db, ObjectMapper mapper) {
        this.stories = stories;
        this.db = db;
        this.mapper = mapper;
        this.format = new StoryGradeSampleFormat(db, mapper);
    }

    /**
     * 원고를 제외한 코드·상태·시각만 ASCII 순서로 조회한다.
     * @param sid 현재 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param size 1~100 또는 기본 20의 페이지 크기
     * @param afterKey 마지막 ASCII 코드 또는 null
     * @param activeYn null이면 활성, false이면 비활성
     * @return 콘텐츠 수정번호와 키 목록
     */
    public SamplePage getGradeSampleList(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            Integer size, String afterKey, Boolean activeYn) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100) throw AuthException.badRequest("INVALID_REQUEST");
        if (afterKey != null) key(afterKey);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            List<SampleKey> rows = db.query("SELECT code,active_yn,updated_at FROM grade_sample WHERE version_id=? "
                            + "AND active_yn=? AND code COLLATE \"C\">? COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                    (rs, index) -> new SampleKey(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()),
                    scope.versionId(), activeYn == null || activeYn, afterKey == null ? "" : afterKey, limit + 1);
            boolean next = rows.size() > limit;
            List<SampleKey> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new SamplePage(Long.toString(scope.rev()), items, next,
                    next ? items.get(items.size() - 1).code() : null);
        });
    }

    /**
     * 원고와 사람 확인자를 권한·조회 감사 확정 뒤에만 반환한다.
     * @param sid 현재 세션 ID
     * @param actor 서버 검증 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 양의 버전 번호
     * @param itemKey 예약된 불변 코드
     * @param requestId 필수 감사 요청 ID
     * @return 내부 계정 ID가 아닌 accountKey와 구조화 답안
     */
    public SampleDetail getGradeSampleDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, UUID requestId) {
        key(itemKey);
        requestId(requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, null, scope -> {
            Sample item = required(scope, itemKey);
            stories.recordChildChange(scope, actor, "grade-samples", "CONTENT_READ", itemKey, null, requestId);
            return new SampleDetail(Long.toString(scope.rev()), item);
        });
    }

    /**
     * nullable 판정 자료를 가진 새 초안 예시를 만든다.
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param expectedRev 현재 십진 콘텐츠 수정번호
     * @param item code 필수, 나머지는 nullable 구조화 입력 객체
     * @param requestId 필수 감사 요청 ID
     * @return 원고 없는 생성 키와 확정 수정번호
     */
    public ItemCreated createGradeSample(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String expectedRev, JsonNode item, UUID requestId) {
        mutation(expectedRev, requestId);
        fields(item, true);
        String code = newCode(item.get("code"));
        Values values = values(item);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            if (find(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
            JsonData json = validate(scope, values);
            db.update("INSERT INTO grade_sample(version_id,code,input_data,expect_data,expected_score,expected_success,reason) "
                            + "VALUES (?,?,?::jsonb,?::jsonb,?,?,?)",
                    scope.versionId(), code, json.input(), json.expectation(), values.score(), values.success(), values.reason());
            long rev = stories.recordChildChange(scope, actor, "grade-samples", "ITEM_CREATED", code,
                    List.of("code", "inputData", "expectData", "expectedScore", "expectedSuccess", "reason"), requestId);
            return new ItemCreated(storyCode, versionNo, Long.toString(rev), true,
                    stories.currentWarnings(scope), requestId, code);
        });
    }

    /**
     * 제출된 필드만 병합하고 실제 변경에는 모든 예시의 과거 확인을 무효화한다.
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey 수정할 수 없는 예시 코드
     * @param expectedRev 무변경에도 확인하는 현재 수정번호
     * @param changes 허용 필드만 포함한 비어 있지 않은 객체
     * @param requestId 필수 감사 요청 ID
     * @return 변경 여부와 원고 없는 현재 수정번호
     */
    public ContentResult updateGradeSample(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, JsonNode changes, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        fields(changes, false);
        Values patch = values(changes);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Sample before = required(scope, itemKey);
            if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
            Values old = before.values();
            Values merged = new Values(changes.has("inputData") ? patch.input() : old.input(),
                    changes.has("expectData") ? patch.expectation() : old.expectation(),
                    changes.has("expectedScore") ? patch.score() : old.score(),
                    changes.has("expectedSuccess") ? patch.success() : old.success(),
                    changes.has("reason") ? patch.reason() : old.reason());
            JsonData json = validate(scope, merged);
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(old.input(), merged.input())) changed.add("inputData");
            if (!Objects.equals(old.expectation(), merged.expectation())) changed.add("expectData");
            if (!Objects.equals(old.score(), merged.score())) changed.add("expectedScore");
            if (!Objects.equals(old.success(), merged.success())) changed.add("expectedSuccess");
            if (!Objects.equals(old.reason(), merged.reason())) changed.add("reason");
            if (changed.isEmpty()) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            db.update("UPDATE grade_sample SET input_data=?::jsonb,expect_data=?::jsonb,expected_score=?,"
                            + "expected_success=?,reason=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    json.input(), json.expectation(), merged.score(), merged.success(), merged.reason(), scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "grade-samples", "ITEM_UPDATED", itemKey, changed, requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    /**
     * 예시의 논리 삭제·복원만 수행하고 코드 및 자료를 보존한다.
     * @param sid 현재 세션 ID
     * @param actor 현재 소유자 또는 EDIT 행위자
     * @param storyCode 대상 사건 코드
     * @param versionNo 활성 DRAFT 버전 번호
     * @param itemKey 예약된 코드
     * @param expectedRev 무변경에도 확인하는 현재 수정번호
     * @param active true는 복원, false는 논리 삭제
     * @param requestId 필수 감사 요청 ID
     * @return 변경 여부와 확정 수정번호
     */
    public ContentResult updateGradeSampleActive(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String itemKey, String expectedRev, boolean active, UUID requestId) {
        key(itemKey);
        mutation(expectedRev, requestId);
        return stories.withVersion(sid, actor, storyCode, versionNo, expectedRev, scope -> {
            Sample before = required(scope, itemKey);
            if (before.activeYn() == active) return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
            if (active) validate(scope, before.values());
            db.update("UPDATE grade_sample SET active_yn=?,updated_at=clock_timestamp() WHERE version_id=? AND code=?",
                    active, scope.versionId(), itemKey);
            long rev = stories.recordChildChange(scope, actor, "grade-samples",
                    active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED", itemKey, List.of("activeYn"), requestId);
            return result(storyCode, versionNo, scope, rev, true, requestId);
        });
    }

    private JsonData validate(VersionScope scope, Values value) {
        if (value.score() != null && (value.score() < 0 || value.score() > 100))
            throw AuthException.unprocessable("INVALID_INPUT");
        String expectation = format.expectation(value.expectation(), value.score(), value.success(), scope.versionId());
        String input = format.input(value.input(), value.expectation(), scope.versionId());
        return new JsonData(input, expectation);
    }

    private Sample find(VersionScope scope, String code) {
        return db.query("SELECT g.code,g.input_data::text,g.expect_data::text,g.expected_score,g.expected_success,"
                        + "g.reason,a.account_key,g.active_yn,g.updated_at FROM grade_sample g "
                        + "LEFT JOIN admin_account a ON a.id=g.checked_by WHERE g.version_id=? AND g.code=? FOR UPDATE OF g",
                rs -> rs.next() ? map(rs) : null, scope.versionId(), code);
    }

    private Sample required(VersionScope scope, String code) {
        Sample item = find(scope, code);
        if (item == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return item;
    }

    private Sample map(ResultSet rs) throws SQLException {
        try {
            Number number = (Number) rs.getObject(4);
            return new Sample(rs.getString(1), rs.getString(2) == null ? null : mapper.readTree(rs.getString(2)),
                    rs.getString(3) == null ? null : mapper.readTree(rs.getString(3)),
                    number == null ? null : number.intValue(), (Boolean) rs.getObject(5), rs.getString(6),
                    (UUID) rs.getObject(7), rs.getBoolean(8), rs.getTimestamp(9).toInstant());
        } catch (JsonProcessingException e) {
            throw new SQLException("저장된 검증 예시 형식이 올바르지 않습니다", e);
        }
    }

    private ContentResult result(String code, int number, VersionScope scope, long rev, boolean changed, UUID id) {
        return new ContentResult(code, number, Long.toString(rev), changed, stories.currentWarnings(scope), id);
    }

    private static Values values(JsonNode node) {
        return new Values(jsonValue(node.get("inputData")), jsonValue(node.get("expectData")), score(node.get("expectedScore")),
                bool(node.get("expectedSuccess")), text(node.get("reason")));
    }

    private static JsonNode jsonValue(JsonNode value) {
        return value == null || value.isNull() ? null : value;
    }

    private static Integer score(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0 || node.intValue() > 100)
            throw AuthException.unprocessable("INVALID_INPUT");
        return node.intValue();
    }

    private static Boolean bool(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isBoolean()) throw AuthException.unprocessable("INVALID_INPUT");
        return node.booleanValue();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        return StoryService.text(node.textValue(), 8000, true);
    }

    private static void fields(JsonNode value, boolean create) {
        if (value == null || !value.isObject() || !create && value.isEmpty()) throw AuthException.badRequest("INVALID_REQUEST");
        value.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field) && !(create && "code".equals(field))) throw AuthException.badRequest("INVALID_REQUEST");
        });
    }

    private static void key(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,32}")) throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static String newCode(JsonNode value) {
        if (value == null || !value.isTextual() || !value.textValue().matches("[A-Z0-9_]{1,32}"))
            throw AuthException.unprocessable("INVALID_INPUT");
        return value.textValue();
    }

    private static void mutation(String revision, UUID requestId) {
        if (revision == null) throw AuthException.badRequest("INVALID_REQUEST");
        requestId(requestId);
    }

    private static void requestId(UUID id) {
        if (id == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    private record JsonData(String input, String expectation) {}
    private record Values(JsonNode input, JsonNode expectation, Integer score, Boolean success, String reason) {}

    public record SampleKey(String code, boolean activeYn, Instant updatedAt) {}
    public record Sample(String code, JsonNode inputData, JsonNode expectData, Integer expectedScore,
            Boolean expectedSuccess, String reason, UUID checkedBy, boolean activeYn, Instant updatedAt) {
        private Values values() {
            return new Values(inputData, expectData, expectedScore, expectedSuccess, reason);
        }
    }
    public record SamplePage(String editRev, List<SampleKey> items, boolean hasNext, String nextAfterKey) {}
    public record SampleDetail(String editRev, Sample item) {}
    public record ItemCreated(String storyCode, int versionNo, String editRev, boolean changed,
            List<Warning> warnings, UUID requestId, String itemKey) {}
}
