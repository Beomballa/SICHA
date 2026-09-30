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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** STORY-05~10의 persons만 처리하며 동적 테이블·컬럼 입력은 받지 않는다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class StoryPersonService {
    private static final Set<String> FIELDS = Set.of("name", "publicText", "secretText");
    private final StoryService stories;
    private final JdbcTemplate db;

    public StoryPersonService(StoryService stories, JdbcTemplate db) {
        this.stories = stories;
        this.db = db;
    }

    /**
     * 현재 부모 조회 자격 안에서 원문 없는 인물 키를 ASCII 순서로 조회한다.
     *
     * @param sid 현재 일반 세션 ID이며 null이면 인증을 거절한다
     * @param actor 서버가 확인한 행위자
     * @param storyCode 정확한 부모 사건 코드
     * @param versionNo 양의 부모 버전 번호
     * @param size null이면 20, 지정하면 1~100이다
     * @param afterKey 마지막 반환 code 또는 null이며 비활성 여부와 독립적으로 키 형식을 검사한다
     * @param activeYn null이면 true이며 false는 보존 인물만 조회한다
     * @return 같은 부모 수정번호와 키·활성·수정시각 목록
     */
    public PersonPage getPersonList(
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
        boolean active = activeYn == null || activeYn;
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                null,
                scope -> {
                    List<PersonKey> rows =
                            db.query(
                                    "SELECT code,active_yn,updated_at FROM story_person WHERE"
                                        + " version_id=? AND active_yn=? AND code COLLATE \"C\">?"
                                        + " COLLATE \"C\" ORDER BY code COLLATE \"C\" LIMIT ?",
                                    (rs, row) ->
                                            new PersonKey(
                                                    rs.getString(1),
                                                    rs.getBoolean(2),
                                                    rs.getTimestamp(3).toInstant()),
                                    scope.versionId(),
                                    active,
                                    afterKey == null ? "" : afterKey,
                                    limit + 1);
                    boolean hasNext = rows.size() > limit;
                    List<PersonKey> items =
                            List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
                    return new PersonPage(
                            Long.toString(scope.rev()),
                            items,
                            hasNext,
                            hasNext ? items.get(items.size() - 1).code() : null);
                });
    }

    /**
     * 활성 부모의 허용 제작자 또는 비활성 부모 소유자에게 인물 원고를 반환한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버가 확인한 행위자
     * @param storyCode 정확한 부모 사건 코드
     * @param versionNo 양의 부모 버전 번호
     * @param itemKey 같은 버전에서 예약된 인물 code이며 비활성 인물도 조회할 수 있다
     * @param requestId 원문 없는 필수 조회 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 보호된 수정번호와 인물 단건
     * @throws AuthException 대상·자격이 없거나 필수 조회 감사에 실패한 경우
     */
    public PersonDetail getPersonDetail(
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
                    Person item = required(scope, itemKey);
                    stories.recordChildChange(
                            scope, actor, "persons", "CONTENT_READ", itemKey, null, requestId);
                    return new PersonDetail(Long.toString(scope.rev()), item);
                });
    }

    /**
     * 사용하지 않은 인물 키로 생성하고 부모 콘텐츠 수정번호를 한 번 증가시킨다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버가 확인한 소유자 또는 현재 EDIT 협업자
     * @param storyCode 정확한 부모 사건 코드
     * @param versionNo 양의 부모 버전 번호
     * @param expectedRev 현재 콘텐츠 수정번호의 음수 없는 십진수 문자열이며 null을 허용하지 않는다
     * @param item code·name 필수, publicText·secretText 선택 객체이며 서버 필드는 거절한다
     * @param requestId null이 아닌 서버 요청 ID
     * @return 원문을 반사하지 않는 ContentResult와 생성한 itemKey
     * @throws AuthException 잘못된 입력, 예약된 키, 오래된 번호 또는 필수 감사 장애 시
     */
    public PersonCreated createPerson(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String expectedRev,
            JsonNode item,
            UUID requestId) {
        mutation(expectedRev, requestId);
        if (item == null || !item.isObject()) throw AuthException.badRequest("INVALID_REQUEST");
        item.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!FIELDS.contains(field) && !"code".equals(field))
                                throw AuthException.badRequest("INVALID_REQUEST");
                        });
        JsonNode codeValue = item.get("code");
        if (codeValue == null
                || !codeValue.isTextual()
                || !codeValue.textValue().matches("[A-Z0-9_]{1,32}"))
            throw AuthException.unprocessable("INVALID_INPUT");
        String code = codeValue.textValue();
        Map<String, String> values = values(item, true);
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    if (find(scope, code) != null) throw AuthException.conflict("ITEM_EXISTS");
                    db.update(
                            "INSERT INTO story_person(version_id,code,name,public_text,secret_text)"
                                + " VALUES (?,?,?,?,?)",
                            scope.versionId(),
                            code,
                            values.get("name"),
                            values.get("publicText"),
                            values.get("secretText"));
                    long revision =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "persons",
                                    "ITEM_CREATED",
                                    code,
                                    List.of("code", "name", "publicText", "secretText"),
                                    requestId);
                    return new PersonCreated(
                            storyCode,
                            versionNo,
                            Long.toString(revision),
                            true,
                            scope.warnings(),
                            requestId,
                            code);
                });
    }

    /**
     * 활성 인물의 선택한 원고 필드만 바꾸고 키는 변경하지 않는다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버가 확인한 소유자 또는 현재 EDIT 협업자
     * @param storyCode 정확한 부모 사건 코드
     * @param versionNo 양의 부모 버전 번호
     * @param itemKey 같은 버전의 활성 인물 code
     * @param changes 비어 있지 않은 허용 필드 객체이며 선택 텍스트의 명시적 null만 지운다
     * @param expectedRev 무변경 요청도 확인할 현재 콘텐츠 수정번호
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 변화가 없으면 번호·시각·감사를 유지하는 원문 없는 결과
     */
    public ContentResult updatePerson(
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
        if (changes == null || !changes.isObject() || changes.isEmpty())
            throw AuthException.badRequest("INVALID_REQUEST");
        changes.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!FIELDS.contains(field))
                                throw AuthException.badRequest("INVALID_REQUEST");
                        });
        Map<String, String> requested = values(changes, false);
        return stories.withVersion(
                sid,
                actor,
                storyCode,
                versionNo,
                expectedRev,
                scope -> {
                    Person before = required(scope, itemKey);
                    if (!before.activeYn()) throw AuthException.conflict("STATE_CONFLICT");
                    String name = requested.getOrDefault("name", before.name());
                    String publicText = requested.getOrDefault("publicText", before.publicText());
                    String secretText = requested.getOrDefault("secretText", before.secretText());
                    List<String> changedFields = new ArrayList<>();
                    if (!Objects.equals(name, before.name())) changedFields.add("name");
                    if (!Objects.equals(publicText, before.publicText()))
                        changedFields.add("publicText");
                    if (!Objects.equals(secretText, before.secretText()))
                        changedFields.add("secretText");
                    if (changedFields.isEmpty())
                        return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
                    db.update(
                            "UPDATE story_person SET"
                                + " name=?,public_text=?,secret_text=?,updated_at=clock_timestamp()"
                                + " WHERE version_id=? AND code=?",
                            name,
                            publicText,
                            secretText,
                            scope.versionId(),
                            itemKey);
                    long revision =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "persons",
                                    "ITEM_UPDATED",
                                    itemKey,
                                    changedFields,
                                    requestId);
                    return result(storyCode, versionNo, scope, revision, true, requestId);
                });
    }

    /**
     * 인물을 논리 삭제·복원하며 현재 범인 지정과 활성 단서 참조를 자동으로 지우지 않는다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버가 확인한 소유자 또는 현재 EDIT 협업자
     * @param storyCode 정확한 부모 사건 코드
     * @param versionNo 양의 부모 버전 번호
     * @param itemKey 같은 버전에서 예약된 인물 code
     * @param active true는 명시 복원, false는 논리 삭제다
     * @param expectedRev 이미 요청 상태여도 검사할 현재 콘텐츠 수정번호
     * @param requestId null이 아닌 서버 요청 ID
     * @return 실제 상태 변경만 수정번호를 증가시킨다
     * @throws AuthException 활성 범인·단서 참조, 대상 없음, 수정 충돌 또는 감사 실패 시
     */
    public ContentResult updatePersonActive(
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
                    Person before = required(scope, itemKey);
                    if (before.activeYn() == active)
                        return result(storyCode, versionNo, scope, scope.rev(), false, requestId);
                    if (!active && itemKey.equals(scope.culprit()))
                        throw AuthException.conflict("REFERENCE_IN_USE");
                    if (!active
                            && db.queryForObject(
                                            "SELECT count(*) FROM story_clue WHERE version_id=? AND"
                                                + " person_code=? AND active_yn",
                                            Integer.class,
                                            scope.versionId(),
                                            itemKey)
                                    > 0) throw AuthException.conflict("REFERENCE_IN_USE");
                    db.update(
                            "UPDATE story_person SET active_yn=?,updated_at=clock_timestamp() WHERE"
                                + " version_id=? AND code=?",
                            active,
                            scope.versionId(),
                            itemKey);
                    long revision =
                            stories.recordChildChange(
                                    scope,
                                    actor,
                                    "persons",
                                    active ? "ITEM_REACTIVATED" : "ITEM_DEACTIVATED",
                                    itemKey,
                                    List.of("activeYn"),
                                    requestId);
                    return result(storyCode, versionNo, scope, revision, true, requestId);
                });
    }

    /** 선택 필드의 타입·길이·줄바꿈을 검사하며 누락과 null을 구분한다. */
    private static Map<String, String> values(JsonNode input, boolean create) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : List.of("name", "publicText", "secretText")) {
            JsonNode value = input.get(field);
            if (value == null && !create) continue;
            if (value == null || value.isNull()) {
                if ("name".equals(field)) throw AuthException.unprocessable("INVALID_INPUT");
                result.put(field, null);
            } else {
                if (!value.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
                result.put(
                        field,
                        StoryService.text(
                                value.textValue(),
                                "name".equals(field) ? 80 : 8000,
                                !"name".equals(field)));
            }
        }
        return result;
    }

    /** 부모 잠금을 보유한 상태에서 같은 버전의 인물만 조회한다. */
    private Person find(VersionScope scope, String key) {
        return db.query(
                "SELECT code,name,public_text,secret_text,active_yn,updated_at FROM story_person"
                    + " WHERE version_id=? AND code=? FOR UPDATE",
                rs -> rs.next() ? map(rs) : null,
                scope.versionId(),
                key);
    }

    /** 없는 인물의 존재 정보를 다른 버전으로부터 보충하지 않는다. */
    private Person required(VersionScope scope, String key) {
        Person item = find(scope, key);
        if (item == null) throw new AuthException(404, "NOT_FOUND", "NOT_FOUND");
        return item;
    }

    /** 인물의 내부 FK 없이 공개 계약 필드만 매핑한다. */
    private static Person map(ResultSet rs) throws SQLException {
        return new Person(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getBoolean(5),
                rs.getTimestamp(6).toInstant());
    }

    /** 인물 변경 후 원고 없는 영수증을 생성한다. */
    private static ContentResult result(
            String code,
            int number,
            VersionScope scope,
            long revision,
            boolean changed,
            UUID requestId) {
        return new ContentResult(
                code, number, Long.toString(revision), changed, scope.warnings(), requestId);
    }

    /** 조회 경로와 커서는 같은 ASCII 키 문법을 사용한다. */
    private static void key(String key) {
        if (key == null || !key.matches("[A-Z0-9_]{1,32}"))
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    /** 변경 호출을 조회 모드로 해석하지 않도록 null 수정번호를 거절한다. */
    private static void mutation(String expectedRev, UUID id) {
        if (expectedRev == null) throw AuthException.badRequest("INVALID_REQUEST");
        requestId(id);
    }

    /** 인물 조회·변경 감사에는 서버 상관 ID가 필수다. */
    private static void requestId(UUID id) {
        if (id == null) throw AuthException.badRequest("INVALID_REQUEST");
    }

    public record PersonKey(String code, boolean activeYn, Instant updatedAt) {}

    /** 비밀 원고를 포함하므로 조회 감사 이후에만 반환하는 인물 단건이다. */
    public record Person(
            String code,
            String name,
            String publicText,
            String secretText,
            boolean activeYn,
            Instant updatedAt) {}

    public record PersonPage(
            String editRev, List<PersonKey> items, boolean hasNext, String nextAfterKey) {}

    public record PersonDetail(String editRev, Person item) {}

    public record PersonCreated(
            String storyCode,
            int versionNo,
            String editRev,
            boolean changed,
            List<Warning> warnings,
            UUID requestId,
            String itemKey) {}
}
