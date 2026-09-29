package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.reasoning.admin.auth.session.AdminSessionAdapter;
import com.reasoning.admin.auth.session.AdminSessionAdapter.AdminPrincipal;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.story.entity.QStory;
import com.reasoning.common.story.entity.QStoryAccess;
import com.reasoning.common.story.entity.QStoryVersion;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.querydsl.jpa.JPAExpressions;

/** STORY-01~04와 자식 콘텐츠의 공통 버전 잠금·감사 경계를 제공하며 민감 응답을 현재 권한으로 검증한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StoryService {
    private static final String POLICY = "RULE_20260924";
    private static final Set<String> BASIC = Set.of("title", "intro", "setting", "difficulty", "estMin", "estMax", "limitSec", "timelineOrigin");
    private static final Set<String> ANSWER = Set.of("culpritCode", "methodAnswer", "timeAnswer", "motiveAnswer");
    private static final Set<String> REVEAL = Set.of("revealText");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AdminSessionAdapter sessions;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final JPAQueryFactory queries;

    public StoryService(JdbcTemplate db, PlatformTransactionManager manager, AdminSessionAdapter sessions,
            CryptoService crypto, ObjectMapper json, EntityManager em) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.sessions = sessions;
        this.crypto = crypto;
        this.json = json;
        this.queries = new JPAQueryFactory(em);
    }

    /**
     * 새 v4 생성 의도에서 변경 불가능한 ST_ 코드를 만들고 DRAFT와 필수 감사를 함께 확정한다.
     * @param sid 행위자에게 결속된 현재 Spring Session ID이며 null이 아니다
     * @param actor 서버에서 발급한 행위자이며 잠금 아래에서 CREATE 권한을 재검사한다
     * @param createKey 재사용할 수 없는 v4 UUID이며 중복 시 응답을 재생하지 않고 충돌한다
     * @param title 공백만으로 이루어지지 않은 사건명이며 최대 160 유니코드 코드포인트다
     * @param requestId 접근 이력과 연결되는 null이 아닌 서버 요청 ID
     * @return 새 사건·버전 식별자와 초기 수정번호
     * @throws AuthException 입력 오류, 권한 회수, 중복 생성 의도 또는 감사 장애 발생 시
     */
    public StoryCreated createStory(String sid, AdminPrincipal actor, UUID createKey, String title, UUID requestId) {
        if (createKey == null || createKey.version() != 4 || requestId == null) throw AuthException.badRequest("INVALID_REQUEST");
        String value = text(title, 160, false);
        precheck(sid, actor);
        String code = "ST_" + createKey.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return transact(() -> {
            Account account = authorizeLocked(sid, actor);
            if (!account.create) throw AuthException.forbidden("FORBIDDEN");
            Long storyId = db.queryForObject("INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id", Long.class, code, actor.accountId());
            Long versionId = db.queryForObject("INSERT INTO story_version(story_id,version_no,status,title,policy_code,created_by,updated_by) VALUES (?,1,'DRAFT',?,?,?,?) RETURNING id",
                    Long.class, storyId, value, POLICY, actor.accountId(), actor.accountId());
            audit(storyId, versionId, actor, "STORY_CREATED", null, 0L, requestId, "CONTENT", null);
            return new StoryCreated(code, "0", 1, "0", "DRAFT", requestId);
        }, "CREATE_CONFLICT");
    }

    /**
     * 한 SQL 스냅샷에서 권한과 대표 버전 조건을 먼저 적용한 뒤 ID 역순 페이지를 읽는다.
     * @param sid 행위자에게 결속된 현재 Spring Session ID이며 null이 아니다
     * @param actor 잠금 아래에서 현재 권한을 검사할 서버 행위자
     * @param size 1~100의 페이지 크기이며 null이면 20이다
     * @param afterId 선택적인 양의 십진수 배타 커서
     * @param code 선택적인 정확 일치 사건 코드
     * @param activeYn null 또는 true이면 활성 사건, false이면 소유자의 비활성 사건만 조회한다
     * @return 허용된 목록과 다음 커서 및 추가 페이지 유무
     * @throws AuthException 필터 오류, 권한 만료 또는 저장소 장애 발생 시
     */
    public StoryPage getStoryList(String sid, AdminPrincipal actor, Integer size, String afterId, String code, Boolean activeYn) {
        int count = size == null ? 20 : size;
        if (count < 1 || count > 100 || code != null && !code.matches("[A-Z0-9_]{1,40}")) throw AuthException.badRequest("INVALID_REQUEST");
        Long cursor = afterId == null ? null : positive(afterId);
        precheck(sid, actor);
        return transact(() -> {
            Account a = authorizeLocked(sid, actor);
            QStory s = QStory.story;
            QStoryVersion v = QStoryVersion.storyVersion;
            QStoryVersion work = new QStoryVersion("work");
            QStoryAccess access = QStoryAccess.storyAccess;
            boolean active = activeYn == null || activeYn;
            List<String> permissions = new ArrayList<>(List.of("EDIT"));
            if (a.review) permissions.add("REVIEW");
            if (a.publish) permissions.add("PUBLISH");
            var allowed = s.ownerId.eq(actor.accountId()).or(JPAExpressions.selectOne().from(access)
                    .where(access.storyId.eq(s.id), access.adminId.eq(actor.accountId()), access.activeYn.isTrue(),
                            access.permission.in(permissions)).exists());
            var workVersion = v.status.in("DRAFT", "REVIEW", "READY")
                    .and(v.activeYn.isTrue().or(s.ownerId.eq(actor.accountId())));
            var fallback = v.id.eq(s.publishedId).and(v.activeYn.isTrue())
                    .and(JPAExpressions.selectOne().from(work).where(work.storyId.eq(s.id), work.status.in("DRAFT", "REVIEW", "READY"),
                            work.activeYn.isTrue().or(s.ownerId.eq(actor.accountId()))).notExists());
            var rows = queries.select(s.id, s.code, s.editRev, s.activeYn, s.ownerId, v.versionNo, v.editRev,
                            v.status, v.activeYn, v.title, v.difficulty, v.updatedAt)
                    .from(s, v).where(v.storyId.eq(s.id), s.activeYn.eq(active),
                            active ? allowed : s.ownerId.eq(actor.accountId()),
                            workVersion.or(fallback), cursor == null ? null : s.id.lt(cursor),
                            code == null ? null : s.code.eq(code))
                    .orderBy(s.id.desc()).limit(count + 1L).fetch();
            boolean hasNext = rows.size() > count;
            List<StorySummary> items = new ArrayList<>();
            for (Tuple row : rows.subList(0, Math.min(rows.size(), count))) {
                UUID owner = db.queryForObject("SELECT account_key FROM admin_account WHERE id=?", UUID.class, row.get(s.ownerId));
                items.add(new StorySummary(row.get(s.code), Long.toString(row.get(s.editRev)), row.get(s.activeYn), owner,
                        row.get(v.versionNo), Long.toString(row.get(v.editRev)), row.get(v.status), row.get(v.activeYn),
                        row.get(v.title), row.get(v.difficulty), row.get(v.updatedAt)));
            }
            return new StoryPage(items, hasNext, hasNext ? rows.get(count - 1).get(s.id).toString() : null);
        }, null);
    }

    /**
     * 계정→자격→세션→사건→버전을 잠그고 CONTENT_READ 감사를 확정한 뒤 원고를 반환한다.
     * @param sid 행위자에게 결속된 현재 Spring Session ID이며 null이 아니다
     * @param actor 현재 소유자 또는 허용된 협업자 권한을 검사할 서버 행위자
     * @param storyCode 변경 불가능한 정확한 사건 코드
     * @param versionNo 해당 사건의 양의 버전 번호
     * @param requestId 감사에 기록할 null이 아닌 서버 접근 이력 요청 ID
     * @return 저장된 영역, 서버 정책 투영과 차단하지 않는 경고
     * @throws AuthException 접근 불가·대상 없음·권한 만료 또는 필수 조회 감사 실패 시
     */
    public VersionDetail getStoryDetail(String sid, AdminPrincipal actor, String storyCode, int versionNo, UUID requestId) {
        if (requestId == null) throw AuthException.badRequest("INVALID_REQUEST");
        path(storyCode, versionNo);
        precheck(sid, actor);
        return transact(() -> {
            LockedVersion locked = lockVersion(sid, actor, storyCode, versionNo, null);
            StoryRow story = locked.story;
            VersionRow v = locked.version;
            audit(story.id, v.id, actor, "CONTENT_READ", v.rev, v.rev, requestId, "CONTENT", null);
            return detail(story, v);
        }, null);
    }

    /**
     * 편집 HTML을 반환하기 전에 원고를 읽지 않고 대상 버전의 현재 조회 자격만 검사한다.
     * @param sid 현재 일반 세션 ID이며 null이면 인증을 거절한다
     * @param actor 서버가 확인한 행위자
     * @param storyCode 정확한 사건 코드
     * @param versionNo 양의 버전 번호
     * @throws AuthException 인증 만료, 대상 비노출 또는 저장소 장애 시
     */
    public void checkStoryAccess(String sid, AdminPrincipal actor, String storyCode, int versionNo) {
        path(storyCode, versionNo);
        precheck(sid, actor);
        transact(() -> {
            Account account = authorizeLocked(sid, actor);
            StoryRow story = story(storyCode);
            permit(story, account, actor, false);
            Boolean active = db.query("SELECT active_yn FROM story_version WHERE story_id=? AND version_no=? FOR UPDATE",
                    rs -> rs.next() ? rs.getBoolean(1) : null, story.id, versionNo);
            if (active == null || !active && story.owner != actor.accountId()) throw missing();
            return null;
        }, null);
    }

    /**
     * DRAFT의 한 영역만 수정번호 확인과 필수 감사를 포함한 동일 트랜잭션에서 갱신한다.
     * @param sid 행위자에게 결속된 현재 Spring Session ID이며 null이 아니다
     * @param actor 현재 소유자 또는 EDIT 권한을 검사할 서버 행위자
     * @param storyCode 변경 불가능한 정확한 사건 코드
     * @param versionNo 해당 사건의 양의 버전 번호
     * @param section basic, answer, reveal 중 한 영역
     * @param expectedRev 음수가 아닌 십진수 콘텐츠 수정번호이며 무변경 요청에도 오래된 값은 충돌한다
     * @param changes 허용 필드만 포함한 비어 있지 않은 객체이며 생략은 유지, 명시적 null은 허용 필드 삭제다
     * @param requestId 접근 이력과 연결되는 null이 아닌 서버 요청 ID
     * @return 확정된 콘텐츠 수정번호, 실제 변경 여부와 경고
     * @throws AuthException 데이터 오류, 거부·비활성 대상, 수정 충돌 또는 감사 실패 시
     */
    public ContentResult updateStorySection(String sid, AdminPrincipal actor, String storyCode, int versionNo,
            String section, String expectedRev, JsonNode changes, UUID requestId) {
        path(storyCode, versionNo);
        Set<String> allowed = switch (section == null ? "" : section) {
            case "basic" -> BASIC;
            case "answer" -> ANSWER;
            case "reveal" -> REVEAL;
            default -> throw AuthException.badRequest("INVALID_REQUEST");
        };
        if (requestId == null || changes == null || !changes.isObject() || changes.isEmpty()) throw AuthException.badRequest("INVALID_REQUEST");
        changes.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw AuthException.badRequest("INVALID_REQUEST"); });
        long expected = revision(expectedRev);
        precheck(sid, actor);
        return transact(() -> {
            LockedVersion locked = lockVersion(sid, actor, storyCode, versionNo, expected);
            StoryRow story = locked.story;
            VersionRow v = locked.version;
            Map<String, Object> old = fields(v);
            Map<String, Object> next = new LinkedHashMap<>(old);
            changes.properties().forEach(entry -> next.put(entry.getKey(), fieldValue(entry.getKey(), entry.getValue())));
            Short min = (Short) next.get("estMin"), max = (Short) next.get("estMax");
            if (min != null && max != null && max < min) throw AuthException.unprocessable("INVALID_INPUT");
            String culprit = (String) next.get("culpritCode");
            if (culprit != null && db.queryForObject("SELECT count(*) FROM story_person WHERE version_id=? AND code=? AND active_yn", Integer.class, v.id, culprit) == 0)
                throw AuthException.unprocessable("INVALID_INPUT");
            if (old.equals(next)) return new ContentResult(storyCode, versionNo, Long.toString(v.rev), false, warningsWithClues(v), requestId);
            if (v.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
            db.update("UPDATE story_version SET title=?,intro=?,setting=?,difficulty=?,est_min=?,est_max=?,limit_sec=?,timeline_origin=?,"
                            + "culprit_code=?,method_answer=?,time_answer=?,motive_answer=?,reveal_text=?,edit_rev=edit_rev+1,updated_by=?,updated_at=clock_timestamp() WHERE id=?",
                    next.get("title"), next.get("intro"), next.get("setting"), next.get("difficulty"), next.get("estMin"),
                    next.get("estMax"), next.get("limitSec"), next.get("timelineOrigin"), next.get("culpritCode"),
                    next.get("methodAnswer"), next.get("timeAnswer"), next.get("motiveAnswer"), next.get("revealText"), actor.accountId(), v.id);
            audit(story.id, v.id, actor, "SECTION_UPDATED", v.rev, v.rev + 1, requestId, "CONTENT",
                    changes.properties().stream().map(Map.Entry::getKey).toList());
            return new ContentResult(storyCode, versionNo, Long.toString(v.rev + 1), true, warningsWithClues(version(story.id, versionNo)), requestId);
        }, null);
    }

    /**
     * 자식 서비스가 동일한 인증→사건→버전 잠금과 오류 변환 안에서만 동작하도록 경계를 제공한다.
     * @param sid 현재 일반 세션 ID이며 null이면 인증을 거절한다
     * @param actor 서버가 확인한 현재 행위자
     * @param code 정확한 사건 코드
     * @param number 양의 버전 번호
     * @param expectedRev null이면 조회, 아니면 음수 없는 문자열 수정번호를 확인하는 편집이다
     * @param work 보호된 같은 트랜잭션에서 수행할 동기 DB 작업이며 네트워크·사용자 대기를 해서는 안 된다
     * @return 작업의 원문 없는 영수증 또는 인가된 조회 값
     */
    <T> T withVersion(String sid, AdminPrincipal actor, String code, int number, String expectedRev,
            java.util.function.Function<VersionScope, T> work) {
        path(code, number);
        Long expected = expectedRev == null ? null : revision(expectedRev);
        precheck(sid, actor);
        return transact(() -> {
            LockedVersion locked = lockVersion(sid, actor, code, number, expected);
            VersionRow v = locked.version;
            return work.apply(new VersionScope(locked.story.id, v.id, v.rev, v.culprit, warningsWithClues(v)));
        }, null);
    }

    /** 트랜잭션 내부에서 대상 노출을 먼저 검사한 뒤 선택적 편집 권한·상태·수정번호를 확인한다. */
    private LockedVersion lockVersion(String sid, AdminPrincipal actor, String code, int number, Long expected) {
        Account account = authorizeLocked(sid, actor);
        StoryRow story = story(code);
        permit(story, account, actor, false);
        VersionRow v = version(story.id, number);
        if (!v.active && story.owner != actor.accountId()) throw missing();
        if (expected != null) {
            permit(story, account, actor, true);
            if (!story.active || !v.active || !"DRAFT".equals(v.status)) throw AuthException.conflict("STATE_CONFLICT");
            if (v.rev != expected) throw AuthException.conflict("EDIT_CONFLICT");
        }
        return new LockedVersion(story, v);
    }

    /**
     * 자식 실변경의 부모 수정번호를 한 번 올리고 필수 감사를 같은 트랜잭션에 기록한다.
     * @param scope 현재 트랜잭션에서 잠근 버전이며 별도 작업에 재사용하지 않는다
     * @param actor 현재 잠금으로 확인한 서버 행위자
     * @param action 서버가 고정한 ITEM_* 행동 또는 CONTENT_READ
     * @param resource 서버에서 고정한 persons, roles, pairs, clues, clue-roles 또는 hints 자원명
     * @param key 원문이 아닌 검증된 ASCII 자식 키
     * @param fields 원문 대신 변경한 허용 필드명이며 조회이면 null이다
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 감사까지 성공한 현재 콘텐츠 수정번호
     */
    long recordChildChange(VersionScope scope, AdminPrincipal actor, String resource, String action, String key,
            List<String> fields, UUID requestId) {
        if (!Set.of("persons", "roles", "pairs", "clues", "clue-roles", "hints").contains(resource)) throw AuthException.badRequest("INVALID_REQUEST");
        boolean change = !"CONTENT_READ".equals(action);
        if (change && scope.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
        long after = scope.rev + (change ? 1 : 0);
        if (change) {
            db.update("UPDATE story_version SET edit_rev=?,updated_by=?,updated_at=clock_timestamp() WHERE id=?",
                    after, actor.accountId(), scope.versionId);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("revisionScope", "CONTENT");
        detail.put("resource", resource);
        detail.put("itemKey", key);
        if (fields != null) detail.put("changedFields", fields);
        insertAudit(scope.storyId, scope.versionId, actor, action, scope.rev, after, detail);
        return after;
    }

    /** 같은 거래의 현재 단서 배정 상태를 다시 읽어 변경 후 경고를 구성한다. */
    List<Warning> currentWarnings(VersionScope scope) {
        List<Warning> result = new ArrayList<>();
        scope.warnings().stream().filter(w -> !"REFERENCE_UNASSIGNED".equals(w.code())).forEach(result::add);
        result.addAll(unassignedClues(scope.versionId()));
        return List.copyOf(result);
    }

    /** 활성 ROLE 단서 중 현재 활성 배정이 없는 코드만 경고한다. */
    private List<Warning> unassignedClues(long versionId) {
        return db.query("SELECT c.code FROM story_clue c WHERE c.version_id=? AND c.active_yn AND c.scope='ROLE' "
                        + "AND NOT EXISTS (SELECT 1 FROM clue_role cr WHERE cr.version_id=c.version_id AND cr.clue_code=c.code AND cr.active_yn) "
                        + "ORDER BY c.code COLLATE \"C\"",
                (rs, row) -> new Warning("REFERENCE_UNASSIGNED", "clues." + rs.getString(1)), versionId);
    }

    /** 버전 원고와 현재 활성 단서의 경고를 결합한다. */
    private List<Warning> warningsWithClues(VersionRow version) {
        List<Warning> result = new ArrayList<>(warnings(version));
        result.addAll(unassignedClues(version.id()));
        return List.copyOf(result);
    }

    private void precheck(String sid, AdminPrincipal actor) {
        if (sid == null || actor == null || !actor.equals(sessions.findStoredPrincipal(sid).orElse(null)))
            throw AuthException.unauthorized("AUTH_REQUIRED");
    }

    private Account authorizeLocked(String sid, AdminPrincipal actor) {
        db.execute("SET LOCAL lock_timeout = '5s'");
        Account account = db.query("SELECT a.id,a.account_key,a.active_yn,a.can_create,a.can_review,a.can_publish,c.auth_rev,c.enrolled_at,c.mfa_state "
                        + "FROM admin_account a JOIN admin_credential c ON c.account_id=a.id WHERE a.id=? FOR UPDATE OF a,c",
                rs -> rs.next() ? new Account(rs.getLong(1), (UUID) rs.getObject(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getLong(7), rs.getTimestamp(8) != null, rs.getString(9)) : null,
                actor.accountId());
        if (account == null || !account.active || !account.enrolled || !"READY".equals(account.mfaState)
                || account.rev != actor.authRev() || !account.key.equals(actor.accountKey())) throw AuthException.unauthorized("AUTH_REQUIRED");
        Integer live = db.query("SELECT 1 FROM admin_session WHERE session_key=? AND account_id=? AND sid_hash=? AND auth_rev=? "
                        + "AND state='ACTIVE' AND expires_at>clock_timestamp() AND last_action_at+interval '30 minutes'>clock_timestamp() FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, actor.sessionKey(), actor.accountId(), crypto.sessionHash(sid), actor.authRev());
        if (live == null) throw AuthException.unauthorized("AUTH_REQUIRED");
        return account;
    }

    private StoryRow story(String code) {
        StoryRow row = db.query("SELECT id,owner_id,active_yn,edit_rev,published_id FROM story WHERE code=? FOR UPDATE",
                rs -> rs.next() ? new StoryRow(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), rs.getLong(4), (Long) rs.getObject(5)) : null, code);
        if (row == null) throw missing();
        return row;
    }

    private VersionRow version(long storyId, int number) {
        VersionRow row = db.query("SELECT id,edit_rev,status,title,intro,setting,difficulty,est_min,est_max,limit_sec,policy_code,culprit_code,"
                + "method_answer,time_answer,motive_answer,timeline_origin,reveal_text,current_snapshot_id,active_yn,updated_at "
                + "FROM story_version WHERE story_id=? AND version_no=? FOR UPDATE", rs -> rs.next() ? mapVersion(rs) : null, storyId, number);
        if (row == null) throw missing();
        return row;
    }

    private static VersionRow mapVersion(ResultSet rs) throws SQLException {
        return new VersionRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                smallint(rs, 7), smallint(rs, 8), smallint(rs, 9), (Integer) rs.getObject(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16),
                rs.getString(17), (Long) rs.getObject(18), rs.getBoolean(19), rs.getTimestamp(20).toInstant());
    }

    private static Short smallint(ResultSet rs, int column) throws SQLException {
        short value = rs.getShort(column);
        return rs.wasNull() ? null : value;
    }

    private void permit(StoryRow s, Account a, AdminPrincipal actor, boolean edit) {
        if (s.owner == actor.accountId()) return;
        if (!s.active) throw missing();
        int count = db.queryForObject("SELECT count(*) FROM story_access WHERE story_id=? AND admin_id=? AND active_yn "
                + "AND (permission='EDIT' OR (permission='REVIEW' AND ?) OR (permission='PUBLISH' AND ?))",
                Integer.class, s.id, actor.accountId(), a.review, a.publish);
        if (count == 0) throw missing();
        if (edit && db.queryForObject("SELECT count(*) FROM story_access WHERE story_id=? AND admin_id=? AND permission='EDIT' AND active_yn",
                Integer.class, s.id, actor.accountId()) == 0) throw AuthException.forbidden("FORBIDDEN");
    }

    private VersionDetail detail(StoryRow s, VersionRow v) {
        UUID ownerKey = db.queryForObject("SELECT account_key FROM admin_account WHERE id=?", UUID.class, s.owner);
        Map<String, Object> f = fields(v);
        Map<String, Object> sections = Map.of("basic", subset(f, BASIC), "answer", subset(f, ANSWER), "reveal", subset(f, REVEAL));
        return new VersionDetail(db.queryForObject("SELECT code FROM story WHERE id=?", String.class, s.id), Long.toString(s.rev), s.active,
                ownerKey, db.queryForObject("SELECT version_no FROM story_version WHERE id=?", Integer.class, v.id), Long.toString(v.rev),
                v.status, v.active, v.snapshot == null ? null : v.snapshot.toString(), sections, policy(v), warningsWithClues(v), v.updatedAt);
    }

    private static Map<String, Object> subset(Map<String, Object> all, Set<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        keys.stream().sorted().forEach(k -> result.put(k, all.get(k)));
        return result;
    }

    private static Map<String, Object> fields(VersionRow v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", v.title); m.put("intro", v.intro); m.put("setting", v.setting); m.put("difficulty", v.difficulty);
        m.put("estMin", v.estMin); m.put("estMax", v.estMax); m.put("limitSec", v.limitSec); m.put("timelineOrigin", v.timelineOrigin);
        m.put("culpritCode", v.culprit); m.put("methodAnswer", v.method); m.put("timeAnswer", v.time); m.put("motiveAnswer", v.motive);
        m.put("revealText", v.reveal);
        return m;
    }

    private static Policy policy(VersionRow v) {
        Integer d = v.difficulty == null ? null : v.difficulty.intValue();
        return new Policy(v.policy, d == null ? null : d <= 2 ? 5 : d <= 4 ? 3 : 2,
                d == null ? null : d <= 2 ? 3 : d <= 4 ? 2 : 1, 10,
                Map.of("CULPRIT", 25, "METHOD", 20, "TIME", 15, "MOTIVE", 10, "EVIDENCE", 30));
    }

    private static List<Warning> warnings(VersionRow v) {
        List<Warning> result = new ArrayList<>();
        for (String field : List.of("intro", "setting", "culpritCode", "methodAnswer", "timeAnswer", "motiveAnswer", "revealText")) {
            Object value = fields(v).get(field);
            if (value == null || value instanceof String s && s.isBlank())
                result.add(new Warning("MISSING_CONTENT", (Set.of("intro", "setting").contains(field) ? "basic." :
                        field.equals("revealText") ? "reveal." : "answer.") + field));
        }
        if (v.difficulty != null) {
            int low = v.difficulty <= 2 ? 5 : 15;
            int high = v.difficulty <= 2 ? 15 : 40;
            if (v.estMin != null && (v.estMin < low || v.estMin > high))
                result.add(new Warning("POLICY_TIME_RANGE", "basic.estMin"));
            if (v.estMax != null && (v.estMax < low || v.estMax > high))
                result.add(new Warning("POLICY_TIME_RANGE", "basic.estMax"));
        }
        return result;
    }

    private static Object fieldValue(String key, JsonNode node) {
        if (node.isNull()) {
            if (key.equals("title")) throw AuthException.unprocessable("INVALID_INPUT");
            return null;
        }
        if (Set.of("difficulty", "estMin", "estMax", "limitSec").contains(key)) {
            if (!node.isIntegralNumber() || !node.canConvertToInt()) throw AuthException.unprocessable("INVALID_INPUT");
            int n = node.intValue();
            if (key.equals("difficulty") ? n < 1 || n > 5 : key.equals("limitSec") ? n <= 0 : n <= 0 || n > Short.MAX_VALUE)
                throw AuthException.unprocessable("INVALID_INPUT");
            if (key.equals("limitSec")) return Integer.valueOf(n);
            return Short.valueOf((short) n);
        }
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        int max = switch (key) {
            case "title" -> 160;
            case "intro", "methodAnswer" -> 12000;
            case "setting" -> 4000;
            case "timeAnswer", "motiveAnswer" -> 8000;
            case "revealText" -> 20000;
            case "timelineOrigin" -> 120;
            case "culpritCode" -> 32;
            default -> throw AuthException.badRequest("INVALID_REQUEST");
        };
        String value = text(node.textValue(), max, !key.equals("title"));
        if (key.equals("culpritCode") && !value.matches("[A-Z0-9_]{1,32}")) throw AuthException.unprocessable("INVALID_INPUT");
        return value;
    }

    /** 공통 Unicode·길이 검사를 사건 API의 안정적인 입력 오류로 변환한다. */
    static String text(String input, int max, boolean blankAllowed) {
        try {
            return com.reasoning.common.util.CommonUtil.normalizeText(input, max, blankAllowed);
        } catch (IllegalArgumentException invalid) {
            throw AuthException.unprocessable("INVALID_INPUT");
        }
    }

    private void audit(long storyId, Long versionId, AdminPrincipal actor, String action, Long before, Long after,
            UUID requestId, String scope, List<String> fields) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId); detail.put("revisionScope", scope);
        if (fields != null) { detail.put("resource", "section"); detail.put("changedFields", fields); }
        insertAudit(storyId, versionId, actor, action, before, after, detail);
    }

    /** 원문을 받지 않는 고정 감사 항목을 4 KiB 이내 JSON으로 저장하며 실패 시 업무를 되돌린다. */
    private void insertAudit(long storyId, Long versionId, AdminPrincipal actor, String action, Long before, Long after,
            Map<String, Object> detail) {
        try {
            String serialized = json.writeValueAsString(detail);
            if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw AuthException.unprocessable("INVALID_INPUT");
            db.update("INSERT INTO story_audit(story_id,version_id,actor_id,action,before_rev,after_rev,detail) VALUES (?,?,?,?,?,?,?::jsonb)",
                    storyId, versionId, actor.accountId(), action, before, after, serialized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw AuthException.unavailable("STORY_UNAVAILABLE"); }
    }

    private <T> T transact(java.util.function.Supplier<T> action, String createConflict) {
        try { return tx.execute(status -> action.get()); }
        catch (AuthException e) { throw e; }
        catch (DataAccessException e) {
            String state = sqlState(e);
            if ("55P03".equals(state) || "40P01".equals(state)) throw AuthException.unavailable("STORY_BUSY");
            if (createConflict != null && "23505".equals(state)) throw AuthException.conflict(createConflict);
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    private static String sqlState(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    private static void path(String code, int version) {
        if (code == null || !code.matches("[A-Z0-9_]{1,40}") || version <= 0) throw AuthException.badRequest("INVALID_REQUEST");
    }
    private static long revision(String raw) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*")) throw AuthException.badRequest("INVALID_REQUEST");
        try { return Long.parseLong(raw); } catch (NumberFormatException e) { throw AuthException.badRequest("INVALID_REQUEST"); }
    }
    private static long positive(String raw) { long n = revision(raw); if (n == 0) throw AuthException.badRequest("INVALID_REQUEST"); return n; }
    private static AuthException missing() { return new AuthException(404, "NOT_FOUND", "NOT_FOUND"); }

    private record Account(long id, UUID key, boolean active, boolean create, boolean review, boolean publish, long rev,
            boolean enrolled, String mfaState) {}
    private record StoryRow(long id, long owner, boolean active, long rev, Long published) {}
    /** 동일 트랜잭션에서 확인한 부모·버전 잠금 결과다. */
    private record LockedVersion(StoryRow story, VersionRow version) {}

    /** 자식 서비스에 필요한 내부 식별자·수정번호만 전달하며 HTTP로 노출하지 않는다. */
    record VersionScope(long storyId, long versionId, long rev, String culprit, List<Warning> warnings) {}
    private record VersionRow(long id, long rev, String status, String title, String intro, String setting, Short difficulty,
            Short estMin, Short estMax, Integer limitSec, String policy, String culprit, String method, String time,
            String motive, String timelineOrigin, String reveal, Long snapshot, boolean active, Instant updatedAt) {}

    public record StoryCreated(String storyCode, String storyRev, int versionNo, String editRev, String status, UUID requestId) {}
    public record StorySummary(String storyCode, String storyRev, boolean activeYn, UUID ownerAccountKey, int versionNo,
            String editRev, String status, boolean versionActiveYn, String title, Short difficulty, Instant updatedAt) {}
    public record StoryPage(List<StorySummary> items, boolean hasNext, String nextAfterId) {}
    public record Policy(String policyCode, Integer attemptLimit, Integer hintsPerPerson, int wrongPenalty, Map<String, Integer> categoryScores) {}
    public record Warning(String code, String field) {}
    public record VersionDetail(String storyCode, String storyRev, boolean storyActiveYn, UUID ownerAccountKey, int versionNo,
            String editRev, String status, boolean activeYn, String currentSnapshotId, Map<String, Object> sections,
            Policy policy, List<Warning> warnings, Instant updatedAt) {}
    public record ContentResult(String storyCode, int versionNo, String editRev, boolean changed, List<Warning> warnings, UUID requestId) {}
}
